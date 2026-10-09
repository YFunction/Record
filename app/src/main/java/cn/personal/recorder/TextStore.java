package cn.personal.recorder;

import android.content.Context;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.UUID;
import org.json.JSONObject;

/** Immutable encrypted revisions. A private pointer selects the latest revision. */
final class TextStore {
    static final String UUID_PATTERN = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";
    static boolean validSession(String id) { return id != null && id.matches(UUID_PATTERN); }
    static boolean validName(String name) { return name.matches(UUID_PATTERN + "_" + UUID_PATTERN + "\\.enc"); }
    static File directory(Context c) {
        File dir = new File(c.getNoBackupFilesDir(), "texts");
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IllegalStateException("无法建立文字目录"); return dir;
    }
    static File[] files(Context c) {
        File[] files = directory(c).listFiles((dir, name) -> validName(name)); if (files == null) return new File[0]; Arrays.sort(files); return files;
    }
    static File latest(Context c, String session) {
        if (!validSession(session)) return null;
        try {
            File pointer = new File(directory(c), session + ".latest"); if (!pointer.isFile() || pointer.length() > 100) return null;
            String name = new String(Files.readAllBytes(pointer.toPath()), StandardCharsets.UTF_8);
            File file = new File(directory(c), name); return validName(name) && name.startsWith(session + "_") && file.isFile() ? file : null;
        } catch (Exception e) { return null; }
    }
    static JSONObject read(Context c, String session) throws Exception {
        File file = latest(c, session); if (file == null) return null;
        if (file.length() > TextCrypto.LIMIT + 32) throw new java.io.IOException("文字密文过大");
        byte[] text = TextCrypto.decrypt(Files.readAllBytes(file.toPath()), new Vault(c).recordingKey(), file.getName());
        try {
            JSONObject doc = new JSONObject(new String(text, StandardCharsets.UTF_8));
            if (doc.getInt("version") != 1 || !session.equals(doc.getString("session"))) throw new java.io.IOException("文字记录不匹配");
            return doc;
        } finally { Arrays.fill(text, (byte) 0); }
    }
    static synchronized void save(Context c, JSONObject doc) throws Exception {
        String session = doc.getString("session"); if (!validSession(session)) throw new java.io.IOException("录音编号无效");
        doc.put("updatedAt", System.currentTimeMillis()); String name = session + "_" + UUID.randomUUID() + ".enc";
        byte[] text = doc.toString().getBytes(StandardCharsets.UTF_8); byte[] blob;
        try { blob = TextCrypto.encrypt(text, new Vault(c).recordingKey(), name); } finally { Arrays.fill(text, (byte) 0); }
        // Remove only confirmed old revisions. The current revision and pending text are retained.
        long bytes = 0;
        for (File f : files(c)) {
            File current = latest(c, f.getName().substring(0, 36));
            if (!f.equals(current) && ChunkStore.uploaded(f) && f.delete()) new File(f.getPath() + ".ack").delete();
            else bytes += f.length();
        }
        if (bytes + blob.length > 64L * 1024 * 1024) throw new java.io.IOException("文字存储已满，请先备份或清理");
        File file = new File(directory(c), name); ChunkStore.atomicWrite(file, blob);
        try { ChunkStore.atomicWrite(new File(directory(c), session + ".latest"), name.getBytes(StandardCharsets.UTF_8)); }
        catch (Exception e) { file.delete(); throw e; }
        UploadWorker.schedule(c);
    }
    static int pending(Context c) { int count = 0; for (File f : files(c)) if (!ChunkStore.uploaded(f)) count++; return count; }
    static synchronized boolean delete(Context c, String session) {
        if (!validSession(session)) return false; boolean ok = true;
        for (File f : files(c)) if (f.getName().startsWith(session + "_")) {
            if (!f.delete()) ok = false; else new File(f.getPath() + ".ack").delete();
        }
        File pointer = new File(directory(c), session + ".latest"); if (pointer.exists() && !pointer.delete()) ok = false; return ok;
    }
}
