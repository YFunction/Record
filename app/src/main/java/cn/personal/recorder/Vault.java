package cn.personal.recorder;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.util.Arrays;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/** Only the wrapping key is non-exportable. The recording key has an offline backup. */
final class Vault {
    private static final String ALIAS = "recorder.wrap.v1";
    private final SharedPreferences prefs;
    Vault(Context context) { prefs = context.getSharedPreferences("vault", Context.MODE_PRIVATE); }
    private SecretKey wrappingKey() throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        if (!store.containsAlias(ALIAS)) {
            KeyGenerator gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
            gen.init(new KeyGenParameterSpec.Builder(ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setKeySize(256).build());
            gen.generateKey();
        }
        return (SecretKey) store.getKey(ALIAS, null);
    }
    private void saveSecret(String name, byte[] value) throws Exception {
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.ENCRYPT_MODE, wrappingKey());
        c.updateAAD(name.getBytes(StandardCharsets.UTF_8));
        byte[] encrypted = c.doFinal(value);
        byte[] blob = new byte[12 + encrypted.length];
        System.arraycopy(c.getIV(), 0, blob, 0, 12);
        System.arraycopy(encrypted, 0, blob, 12, encrypted.length);
        if (!prefs.edit().putString(name, Base64.encodeToString(blob, Base64.NO_WRAP)).commit())
            throw new java.io.IOException("无法保存加密配置");
    }
    private byte[] loadSecret(String name) throws Exception {
        byte[] blob = Base64.decode(prefs.getString(name, ""), Base64.NO_WRAP);
        if (blob.length < 28) throw new IllegalStateException("加密配置缺失，请完成初始化");
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.DECRYPT_MODE, wrappingKey(), new GCMParameterSpec(128, blob, 0, 12));
        c.updateAAD(name.getBytes(StandardCharsets.UTF_8));
        return c.doFinal(blob, 12, blob.length - 12);
    }
    synchronized void initialize(String restore) throws Exception {
        if (prefs.contains("recording-key")) return;
        byte[] key;
        if (restore.trim().isEmpty()) { key = new byte[32]; new SecureRandom().nextBytes(key); }
        else { key = Base64.decode(restore.trim(), Base64.DEFAULT); }
        if (key.length != 32) throw new IllegalArgumentException("恢复密钥必须为 Base64 编码的 32 字节密钥");
        try { saveSecret("recording-key", key); }
        finally { Arrays.fill(key, (byte) 0); }
    }
    String recoveryKey() throws Exception { return Base64.encodeToString(loadSecret("recording-key"), Base64.NO_WRAP); }
    SecretKey recordingKey() throws Exception {
        byte[] key = loadSecret("recording-key");
        try { return new SecretKeySpec(key, "AES"); } finally { Arrays.fill(key, (byte) 0); }
    }
    void saveToken(String token) throws Exception { saveSecret("upload-token", token.getBytes(StandardCharsets.UTF_8)); }
    String token() throws Exception { return new String(loadSecret("upload-token"), StandardCharsets.UTF_8); }
    SharedPreferences preferences() { return prefs; }
    boolean configured() {
        return prefs.contains("recording-key") && prefs.contains("upload-token") && prefs.contains("server")
            && prefs.getBoolean("backed-up", false);
    }
    boolean ready() { return configured() && prefs.getBoolean("ready", false); }
}
