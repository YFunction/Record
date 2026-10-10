package cn.personal.recorder;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public final class RecordingService extends Service {
    static final String STOP = "cn.personal.recorder.STOP";
    static final String EXTRA_CATEGORY = "recording-category";
    static volatile boolean active;
    static volatile String state = "尚未录音";
    static volatile long startedElapsed;
    static volatile long stoppedElapsed;
    static volatile boolean finalizing;
    static volatile String session = "";
    private AacRecorder recorder;
    private LiveTranscriber liveText;
    private PowerManager.WakeLock wakeLock;
    private ScheduledExecutorService uploader;
    private final Handler main = new Handler(Looper.getMainLooper());
    private boolean stopping;
    private boolean captureStopped, liveDone, finished;
    @Override public IBinder onBind(Intent intent) { return null; }
    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && STOP.equals(intent.getAction())) {
            stopping = true;
            if (recorder != null) recorder.stop(); else finishRecording();
            return START_NOT_STICKY;
        }
        if (active || stopping || finalizing) return START_NOT_STICKY;
        if (!new Vault(this).recordingConfigured()) { stopSelf(); return START_NOT_STICKY; }
        try {
            NotificationManager nm = getSystemService(NotificationManager.class);
            nm.createNotificationChannel(new NotificationChannel("recording", "录音状态", NotificationManager.IMPORTANCE_LOW));
            startForeground(1, notification("正在准备录音"), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
            recorder = new AacRecorder(this, intent == null ? null : intent.getStringExtra(EXTRA_CATEGORY));
            session = recorder.sessionId();
            if (new Vault(this).preferences().getBoolean("live-transcription-enabled", true) && !AnalysisService.busy)
                liveText = LiveTranscriber.start(this, session, () -> main.post(this::onLiveFinished));
            liveDone = liveText == null;
            wakeLock = getSystemService(PowerManager.class).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "EncryptedRecorder:capture");
            wakeLock.acquire(60 * 60 * 1000L);
            active = true; finalizing = false; startedElapsed = 0; stoppedElapsed = 0; state = "正在准备麦克风与编码器";
            if (new Vault(this).configured()) {
            uploader = Executors.newSingleThreadScheduledExecutor();
            uploader.scheduleWithFixedDelay(() -> {
                try { Uploader.drain(this, () -> !active, 20_000); } catch (Exception ignored) {}
                if (active) main.post(() -> {
                    if (active) getSystemService(NotificationManager.class).notify(1, notification(Uploader.status));
                });
            }, 0, 3, TimeUnit.SECONDS);
            }
            Thread capture = new Thread(() -> {
                boolean complete = false;
                try {
                    recorder.run(() -> { if (wakeLock != null && !wakeLock.isHeld()) wakeLock.acquire(60 * 60 * 1000L); },
                        () -> { startedElapsed = android.os.SystemClock.elapsedRealtime();
                            LaunchDiagnostics.serviceNote(this, "microphone-started");
                            state = "正在录音，音频约每 30 秒加密保存";
                            main.post(() -> getSystemService(NotificationManager.class).notify(1,
                                notification(new Vault(this).configured() ? "本地加密 · 云端同步" : "本地加密保存")));
                        }, liveText == null ? null : liveText::accept);
                    complete = true;
                    state = "录音已停止，密文已保存在本地";
                } catch (Exception e) { state = "录音已停止：" + e.getMessage(); LaunchDiagnostics.serviceNote(this, "capture-failed:" + e.getClass().getSimpleName()); }
                finally {
                    try { if (liveText != null) liveText.finish(complete); }
                    finally { main.post(this::onCaptureStopped); }
                }
            }, "audio-capture");
            capture.start();
        } catch (Exception e) {
            state = "无法启动录音：请检查权限并重新打开应用";
            LaunchDiagnostics.serviceNote(this, "service-failed:" + e.getClass().getSimpleName());
            if (liveText != null) liveText.finish(false);
            finishRecording();
        }
        return START_NOT_STICKY;
    }
    private Notification notification(String detail) {
        PendingIntent open = PendingIntent.getActivity(this, 1, new Intent(this, MainActivity.class)
            .putExtra("settings", true), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent stop = PendingIntent.getService(this, 2, new Intent(this, RecordingService.class).setAction(STOP), PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, "recording").setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle(finalizing ? "正在保存实时文字" : "加密录音正在运行").setContentText(detail).setOngoing(true).setContentIntent(open)
            .addAction(new Notification.Action.Builder(null, "停止录音", stop).build()).build();
    }
    private void onCaptureStopped() {
        captureStopped = true; stoppedElapsed = android.os.SystemClock.elapsedRealtime();
        if (!liveDone) {
            finalizing = true; state = "录音已停止，正在加密保存实时文字";
            getSystemService(NotificationManager.class).notify(1, notification(state));
            main.postDelayed(() -> {
                if (captureStopped && !liveDone && !finished) {
                    state = "实时文字保存超时；录音密文已保留，可稍后完整转写";
                    finishRecording();
                }
            }, 45_000);
        }
        maybeFinish();
    }
    private void onLiveFinished() { liveDone = true; maybeFinish(); }
    private void maybeFinish() { if (captureStopped && liveDone) finishRecording(); }
    private void finishRecording() {
        if (finished) return;
        finished = true; active = false; finalizing = false;
        if (uploader != null) uploader.shutdownNow();
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        UploadWorker.schedule(this);
        stopForeground(STOP_FOREGROUND_REMOVE); stopSelf();
    }
    @Override public void onDestroy() {
        if (recorder != null) recorder.stop();
        active = false; finalizing = false;
        if (uploader != null) uploader.shutdownNow();
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        super.onDestroy();
    }
}
