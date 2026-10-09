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
    static volatile boolean active;
    static volatile String state = "尚未录音";
    private AacRecorder recorder;
    private PowerManager.WakeLock wakeLock;
    private ScheduledExecutorService uploader;
    private final Handler main = new Handler(Looper.getMainLooper());
    private boolean stopping;
    @Override public IBinder onBind(Intent intent) { return null; }
    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && STOP.equals(intent.getAction())) {
            stopping = true;
            if (recorder != null) recorder.stop(); else stopSelf();
            return START_NOT_STICKY;
        }
        if (active || stopping) return START_NOT_STICKY;
        if (!new Vault(this).configured()) { stopSelf(); return START_NOT_STICKY; }
        try {
            NotificationManager nm = getSystemService(NotificationManager.class);
            nm.createNotificationChannel(new NotificationChannel("recording", "录音状态", NotificationManager.IMPORTANCE_LOW));
            startForeground(1, notification("正在准备录音"), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
            recorder = new AacRecorder(this);
            wakeLock = getSystemService(PowerManager.class).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "EncryptedRecorder:capture");
            wakeLock.acquire(60 * 60 * 1000L);
            active = true; state = "正在准备麦克风与编码器";
            uploader = Executors.newSingleThreadScheduledExecutor();
            uploader.scheduleWithFixedDelay(() -> {
                try { Uploader.drain(this, () -> !active, 20_000); } catch (Exception ignored) {}
                if (active) main.post(() -> {
                    if (active) getSystemService(NotificationManager.class).notify(1, notification(Uploader.status));
                });
            }, 0, 3, TimeUnit.SECONDS);
            Thread capture = new Thread(() -> {
                try {
                    recorder.run(() -> { if (wakeLock != null && !wakeLock.isHeld()) wakeLock.acquire(60 * 60 * 1000L); },
                        () -> state = "正在录音，音频约每 30 秒加密保存");
                    state = "录音已停止，密文已保存在本地";
                } catch (Exception e) { state = "录音已停止：" + e.getMessage(); }
                finally { main.post(this::finishRecording); }
            }, "audio-capture");
            capture.start();
        } catch (Exception e) { state = "无法启动录音：请检查权限并重新打开应用"; finishRecording(); }
        return START_NOT_STICKY;
    }
    private Notification notification(String detail) {
        PendingIntent open = PendingIntent.getActivity(this, 1, new Intent(this, MainActivity.class)
            .putExtra("settings", true), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent stop = PendingIntent.getService(this, 2, new Intent(this, RecordingService.class).setAction(STOP), PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, "recording").setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("加密录音正在运行").setContentText(detail).setOngoing(true).setContentIntent(open)
            .addAction(new Notification.Action.Builder(null, "停止录音", stop).build()).build();
    }
    private void finishRecording() {
        active = false;
        if (uploader != null) uploader.shutdownNow();
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        UploadWorker.schedule(this);
        stopForeground(STOP_FOREGROUND_REMOVE); stopSelf();
    }
    @Override public void onDestroy() {
        if (recorder != null) recorder.stop();
        active = false;
        if (uploader != null) uploader.shutdownNow();
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        super.onDestroy();
    }
}
