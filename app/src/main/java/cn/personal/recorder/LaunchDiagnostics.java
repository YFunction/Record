package cn.personal.recorder;

import android.Manifest;
import android.app.KeyguardManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.PowerManager;

/** Small local startup trace; excludes credentials, recording metadata, and exception messages. */
final class LaunchDiagnostics {
    static void note(Context context, String event, boolean focused) {
        KeyguardManager km = context.getSystemService(KeyguardManager.class);
        context.getSharedPreferences("launch-diagnostics", Context.MODE_PRIVATE).edit()
            .putString("event", event).putLong("time", System.currentTimeMillis()).putBoolean("focused", focused)
            .putBoolean("locked", km.isKeyguardLocked()).putBoolean("device-locked", km.isDeviceLocked()).apply();
    }
    static String describe(Context context) {
        android.content.SharedPreferences p = context.getSharedPreferences("launch-diagnostics", Context.MODE_PRIVATE);
        return "app=" + BuildConfig.VERSION_NAME + "\nandroid=" + Build.VERSION.SDK_INT + "\nmodel=" + Build.MANUFACTURER + " " + Build.MODEL +
            "\nlast-event=" + p.getString("event", "none") + "\ntime=" + p.getLong("time", 0) + "\nwindow-focused=" + p.getBoolean("focused", false) +
            "\nservice-event=" + p.getString("service-event", "none") +
            "\nkeyguard-locked=" + p.getBoolean("locked", false) + "\ndevice-locked=" + p.getBoolean("device-locked", false) +
            "\nmicrophone-granted=" + (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) +
            "\nbattery-unrestricted=" + context.getSystemService(PowerManager.class).isIgnoringBatteryOptimizations(context.getPackageName()) + "\n";
    }
    static void serviceNote(Context context, String event) {
        context.getSharedPreferences("launch-diagnostics", Context.MODE_PRIVATE).edit().putString("service-event", event).apply();
    }
}
