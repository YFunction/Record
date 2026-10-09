package cn.personal.recorder;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONObject;

/** User-initiated foreground processing. Requests are never scheduled for AI automatically. */
public final class AnalysisService extends Service {
    static final String TRANSCRIBE = "transcribe", SUMMARY = "summary", MODELS = "models", IMPORT = "import", CANCEL = "cancel";
    static volatile boolean busy, transcribing;
    static volatile String state = "", session = "";
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private final Handler main = new Handler(Looper.getMainLooper());
    private PowerManager.WakeLock wake;
    @Override public IBinder onBind(Intent intent) { return null; }
    @Override public int onStartCommand(Intent intent, int flags, int id) {
        if (intent == null) return START_NOT_STICKY;
        if (CANCEL.equals(intent.getAction())) { if (!busy) stopSelf(); else { cancelled.set(true); state = "正在停止处理…"; } return START_NOT_STICKY; }
        if (busy) return START_NOT_STICKY;
        String action = intent.getAction(), recording = intent.getStringExtra("session");
        if (!TRANSCRIBE.equals(action) && !SUMMARY.equals(action) && !MODELS.equals(action) && !IMPORT.equals(action)) { stopSelf(); return START_NOT_STICKY; }
        if ((TRANSCRIBE.equals(action) || SUMMARY.equals(action)) && !TextStore.validSession(recording)) { stopSelf(); return START_NOT_STICKY; }
        if (TRANSCRIBE.equals(action) && RecordingService.active) { state = "请先停止录音再提取文字"; stopSelf(); return START_NOT_STICKY; }
        busy = true; transcribing = TRANSCRIBE.equals(action); session = recording == null ? "" : recording; cancelled.set(false);
        try {
            getSystemService(NotificationManager.class).createNotificationChannel(new NotificationChannel("text-processing", "录音文字处理", NotificationManager.IMPORTANCE_LOW));
            state = TRANSCRIBE.equals(action) ? "准备本地提取文字" : SUMMARY.equals(action) ? "准备 AI 总结" : "准备离线模型";
            int type = Build.VERSION.SDK_INT >= 35 && transcribing ? ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING : ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC;
            startForeground(2, notification(), type);
            wake = getSystemService(PowerManager.class).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "recorder:text-processing"); wake.acquire(60 * 60 * 1000L);
        } catch (Exception e) { state = "系统未允许后台处理，请重新打开文字页面"; busy = false; transcribing = false; stopSelf(); return START_NOT_STICKY; }
        new Thread(() -> {
            try {
                if (MODELS.equals(action)) ModelManager.download(this, cancelled::get, this::progress);
                else if (IMPORT.equals(action)) ModelManager.importZip(this, intent.getData(), cancelled::get, this::progress);
                else if (TRANSCRIBE.equals(action)) {
                    RecordingLibrary.Entry entry = null; for (RecordingLibrary.Entry e : RecordingLibrary.list(this)) if (e.id.equals(recording)) entry = e;
                    if (entry == null || entry.chunks.length == 0) throw new java.io.IOException("音频副本已移除，无法重新转写");
                    JSONObject doc = LocalTranscriber.transcribe(this, entry, intent.getIntExtra("speaker-count", -1), cancelled::get, this::progress); LocalTranscriber.check(cancelled::get); TextStore.save(this, doc);
                } else {
                    if (!CategoryStore.aiAllowed(this, recording)) throw new java.io.IOException("本分类未允许外部 AI 读取文字，请在分类管理中明确开启");
                    JSONObject doc = TextStore.read(this, recording); if (doc == null) throw new java.io.IOException("请先提取录音文字");
                    String text = (doc.getBoolean("complete") ? "完整录音\n" : "部分录音：未正常结束，仅包含已保存片段\n") + Transcript.text(doc);
                    String summary = DeepSeekClient.summarize(text, new Vault(this).aiKey(), cancelled::get, this::progress, CategoryStore.template(this, recording)); LocalTranscriber.check(cancelled::get);
                    doc.put("summary", summary).put("summaryModel", DeepSeekClient.MODEL).put("summaryAt", System.currentTimeMillis()); TextStore.save(this, doc);
                }
                state = TRANSCRIBE.equals(action) ? "文字提取完成，已加密保存" : SUMMARY.equals(action) ? "AI 总结完成，已加密保存" : "离线模型已准备好";
            } catch (InterruptedException e) { state = "处理已取消，已有记录保留"; }
            catch (OutOfMemoryError e) { state = "手机内存不足，请关闭其他应用后重试"; }
            catch (Exception | LinkageError e) { state = safe(e); }
            finally { main.post(() -> { busy = false; transcribing = false; if (wake != null && wake.isHeld()) wake.release(); wake = null; stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(); }); }
        }, "private-text-processing").start();
        return START_NOT_STICKY;
    }
    private void progress(String message) {
        state = message; if (wake != null && !wake.isHeld()) wake.acquire(60 * 60 * 1000L);
        main.post(() -> { if (busy) getSystemService(NotificationManager.class).notify(2, notification()); });
    }
    private Notification notification() {
        Intent screen = new Intent(this, TextActivity.class).putExtra("session", session);
        if (session.isEmpty()) screen = new Intent(this, SettingsActivity.class);
        PendingIntent open = PendingIntent.getActivity(this, 200, screen, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop = PendingIntent.getService(this, 201, new Intent(this, AnalysisService.class).setAction(CANCEL), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, "text-processing").setSmallIcon(R.drawable.ic_recorder).setContentTitle("录音文字处理")
            .setContentText(state).setContentIntent(open).setOngoing(true).addAction(new Notification.Action.Builder(null, "取消", stop).build()).build();
    }
    private static String safe(Throwable e) {
        // Never expose remote response bodies, request payloads, credentials or native config dumps.
        if (e instanceof java.io.IOException && e.getMessage() != null && !e.getMessage().contains("http") && e.getMessage().length() < 150) return e.getMessage();
        return "处理失败，已有密文保留；请检查网络、Key 或离线模型后重试";
    }
    @Override public void onTimeout(int startId, int type) { cancelled.set(true); state = "系统处理时限已到，请重新发起"; stopSelf(); }
    @Override public void onDestroy() { cancelled.set(true); if (wake != null && wake.isHeld()) wake.release(); super.onDestroy(); }
}
