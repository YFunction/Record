package cn.personal.recorder;

import android.content.Context;
import androidx.work.BackoffPolicy;
import androidx.work.Constraints;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.ExistingWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;
import java.util.concurrent.TimeUnit;

public final class UploadWorker extends Worker {
    public UploadWorker(Context c, WorkerParameters params) { super(c, params); }
    private static Constraints constraints(Context c) {
        boolean wifiOnly = new Vault(c).preferences().getBoolean("wifi-only", false);
        return new Constraints.Builder().setRequiredNetworkType(wifiOnly ? NetworkType.UNMETERED : NetworkType.CONNECTED).build();
    }
    static void schedule(Context c) {
        if (!new Vault(c).configured()) return;
        WorkManager wm = WorkManager.getInstance(c);
        wm.enqueueUniqueWork("upload-now", ExistingWorkPolicy.KEEP,
            new OneTimeWorkRequest.Builder(UploadWorker.class).setConstraints(constraints(c))
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build());
    }
    static void periodic(Context c) {
        if (!new Vault(c).configured()) {
            WorkManager.getInstance(c).cancelUniqueWork("upload-now");
            WorkManager.getInstance(c).cancelUniqueWork("upload-recovery");
            return;
        }
        WorkManager.getInstance(c).enqueueUniquePeriodicWork("upload-recovery", ExistingPeriodicWorkPolicy.UPDATE,
            new PeriodicWorkRequest.Builder(UploadWorker.class, 15, TimeUnit.MINUTES)
                .setConstraints(constraints(c)).build());
    }
    @Override public Result doWork() {
        if (!new Vault(getApplicationContext()).configured()) return Result.success();
        try {
            Uploader.drain(getApplicationContext(), () -> isStopped(), 60_000);
            return ChunkStore.pending(getApplicationContext()) + TextStore.pending(getApplicationContext()) == 0 ? Result.success() : Result.retry();
        } catch (Exception e) { return Result.retry(); }
    }
}
