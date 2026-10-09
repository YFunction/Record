package cn.personal.recorder;

import android.content.Context;
import android.util.AtomicFile;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import org.json.JSONObject;

final class ChunkStore {
    static final long CAPACITY = 256L * 1024 * 1024;
    static File directory(Context c) {
        File dir = new File(c.getNoBackupFilesDir(), "chunks");
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IllegalStateException("无法创建录音目录");
        return dir;
    }
    static File[] files(Context c) {
        File[] files = directory(c).listFiles((d, n) -> n.endsWith(".enc"));
        if (files == null) return new File[0];
        Arrays.sort(files); return files;
    }
    static long bytes(Context c) { long total = 0; for (File f : files(c)) total += f.length(); return total; }
    static boolean uploaded(File f) { return new File(f.getPath() + ".ack").isFile(); }
    static int pending(Context c) { int n = 0; for (File f : files(c)) if (!uploaded(f)) n++; return n; }
    static void acknowledge(File f) throws Exception { atomicWrite(new File(f.getPath() + ".ack"), new byte[] {1}); }
    static synchronized void clearUploaded(Context c) {
        for (File f : files(c)) if (uploaded(f) && f.delete()) new File(f.getPath() + ".ack").delete();
    }
    static void atomicWrite(File file, byte[] bytes) throws Exception {
        AtomicFile atomic = new AtomicFile(file);
        FileOutputStream out = null;
        try { out = atomic.startWrite(); out.write(bytes); atomic.finishWrite(out); }
        catch (Exception e) { if (out != null) atomic.failWrite(out); throw e; }
    }
    static void save(Context c, SecretKey key, String session, int index, long started,
                     int samples, byte[] audio, boolean complete) throws Exception {
        String filename = session + "_" + String.format(java.util.Locale.ROOT, "%08d", index) + ".enc";
        JSONObject meta = new JSONObject().put("version", 1).put("session", session).put("index", index)
            .put("startedAt", started).put("codec", "aac-adts").put("sampleRate", 16000)
            .put("channels", 1).put("samples", samples).put("final", complete);
        byte[] json = meta.toString().getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream plain = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(plain)) {
            out.writeBytes("EAA1"); out.writeInt(json.length); out.write(json); out.write(audio);
        }
        byte[] nonce = new byte[12]; new SecureRandom().nextBytes(nonce);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, nonce));
        cipher.updateAAD(filename.getBytes(StandardCharsets.UTF_8));
        byte[] plaintext = plain.toByteArray();
        byte[] encrypted;
        try { encrypted = cipher.doFinal(plaintext); } finally { Arrays.fill(plaintext, (byte) 0); }
        ByteArrayOutputStream envelope = new ByteArrayOutputStream();
        envelope.write(new byte[] {'E','R','0','1'}); envelope.write(nonce); envelope.write(encrypted);
        if (bytes(c) + envelope.size() > CAPACITY || new android.os.StatFs(directory(c).getPath()).getAvailableBytes() < envelope.size() + 16L*1024*1024)
            throw new java.io.IOException("本地存储不足：请清理已上传录音后重新开始");
        atomicWrite(new File(directory(c), filename), envelope.toByteArray());
    }
    static String digest(File file) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file.toPath()));
        StringBuilder hex = new StringBuilder();
        for (byte b : digest) hex.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
        return hex.toString();
    }
}
