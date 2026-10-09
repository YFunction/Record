package cn.personal.recorder;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;
import org.junit.Test;
import static org.junit.Assert.*;

public class LocalModeTest {
    private final Map<String, Object> values = new HashMap<>();
    private final SharedPreferences prefs = (SharedPreferences) Proxy.newProxyInstance(
        SharedPreferences.class.getClassLoader(), new Class[]{SharedPreferences.class}, (p, method, args) -> {
            switch (method.getName()) {
                case "contains": return values.containsKey(args[0]);
                case "getString": case "getBoolean": return values.getOrDefault(args[0], args[1]);
                default: throw new AssertionError("Unexpected preferences call " + method.getName());
            }
        });
    private final Context context = new ContextWrapper(null) {
        @Override public SharedPreferences getSharedPreferences(String name, int mode) { return prefs; }
        @Override public Object getSystemService(String name) { throw new AssertionError("Local mode must not access network services"); }
    };
    @Test public void localRecordingAndAutoStartDoNotRequireServerOrBackup() throws Exception {
        Vault vault = new Vault(context); assertFalse(vault.recordingConfigured());
        values.put("recording-key", "wrapped-key");
        assertTrue(vault.recordingConfigured()); assertTrue(vault.ready()); assertFalse(vault.configured());
        Uploader.drain(context, () -> false, 1000); UploadWorker.schedule(context);
    }
    @Test public void disablingCloudStopsUploadEvenWithExistingCredentials() throws Exception {
        values.put("recording-key", "wrapped-key"); values.put("upload-token", "wrapped-token");
        values.put("server", "https://example.ts.net"); values.put("backed-up", true); values.put("cloud-enabled", false);
        Vault vault = new Vault(context); assertTrue(vault.ready()); assertFalse(vault.configured());
        Uploader.drain(context, () -> false, 1000); UploadWorker.schedule(context);
    }
    @Test public void oldCloudSettingsArePreservedAndBackupRemainsRequiredForUpload() {
        values.put("recording-key", "wrapped-key"); values.put("upload-token", "wrapped-token"); values.put("server", "https://example.ts.net");
        Vault vault = new Vault(context); assertTrue(vault.cloudEnabled()); assertFalse(vault.configured()); assertTrue(vault.ready());
        values.put("backed-up", true); assertTrue(vault.configured());
        values.put("ready", false); assertFalse(vault.ready()); assertTrue(vault.recordingConfigured());
    }
}
