package cn.personal.recorder;

import android.content.Context;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;
import java.util.concurrent.TimeUnit;

/** Opt-in local audio retention. It never deletes server copies or encrypted text results. */
public final class RetentionWorker extends Worker {
    public RetentionWorker(Context context, WorkerParameters params) { super(context, params); }

    static void schedule(Context context, int days) {
        WorkManager manager = WorkManager.getInstance(context);
        if (days <= 0) { manager.cancelUniqueWork("local-audio-retention"); return; }
        PeriodicWorkRequest request = new PeriodicWorkRequest.Builder(RetentionWorker.class, 1, TimeUnit.DAYS)
            .setInitialDelay(1, TimeUnit.DAYS).build();
        manager.enqueueUniquePeriodicWork("local-audio-retention", ExistingPeriodicWorkPolicy.UPDATE, request);
    }

    @Override public Result doWork() {
        Context context = getApplicationContext();
        int days = new Vault(context).preferences().getInt("local-audio-retention-days", 0);
        if (days <= 0 || RecordingService.active || AnalysisService.busy || MainActivity.playbackActive) return Result.success();
        long cutoff = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(days);
        try {
            Vault vault = new Vault(context);
            if (!vault.recordingConfigured()) return Result.success();
            javax.crypto.SecretKey key = vault.recordingKey();
            for (RecordingLibrary.Entry entry : RecordingLibrary.list(context)) {
                if (entry.chunks.length == 0 || entry.time > cutoff || entry.uploaded != entry.chunks.length) continue;
                if (RecordingService.active || AnalysisService.busy || MainActivity.playbackActive) break;
                try (RecordingSource source = new RecordingSource(entry.chunks, key)) {
                    if (source.complete) RecordingLibrary.delete(entry);
                } catch (Exception ignored) {
                    // Corrupt, incomplete or unreadable sessions are preserved for manual review.
                }
            }
            return Result.success();
        } catch (Exception e) { return Result.retry(); }
    }
}
