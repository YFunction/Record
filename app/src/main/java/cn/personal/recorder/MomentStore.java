package cn.personal.recorder;

import android.content.Context;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/** Encrypted, session-local timestamps. No plaintext index or audio is written. */
final class MomentStore {
    static final int MAX_MARKS = 500;
    static final class Mark {
        final long offsetMs;
        final String note;
        Mark(long offsetMs, String note) { this.offsetMs = offsetMs; this.note = note; }
    }
    private static File file(Context context, String session) throws IOException {
        if (!TextStore.validSession(session)) throw new IOException("录音编号无效");
        File dir = new File(context.getNoBackupFilesDir(), "moments");
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("无法建立瞬间目录");
        return new File(dir, session + ".enc");
    }
    private static String aad(String session) { return "moment-v1:" + session + ".enc"; }
    static synchronized List<Mark> list(Context context, String session) throws Exception {
        JSONObject doc = read(context, session);
        JSONArray marks = doc.getJSONArray("marks");
        List<Mark> result = new ArrayList<>(marks.length());
        for (int i = 0; i < marks.length(); i++) {
            JSONObject mark = marks.getJSONObject(i);
            result.add(new Mark(mark.getLong("offsetMs"), mark.optString("note", "")));
        }
        return result;
    }
    static synchronized int add(Context context, String session, long offsetMs) throws Exception {
        if (offsetMs < 0) throw new IOException("录音尚未开始");
        JSONObject doc = read(context, session);
        int before = doc.getJSONArray("marks").length();
        int count = append(doc, offsetMs);
        if (count != before) write(context, session, doc);
        return count;
    }
    static int append(JSONObject doc, long offsetMs) throws Exception {
        if (offsetMs < 0) throw new IOException("录音尚未开始");
        JSONArray marks = doc.getJSONArray("marks");
        if (marks.length() >= MAX_MARKS) throw new IOException("本场标记已达上限");
        if (marks.length() > 0 && offsetMs - marks.getJSONObject(marks.length() - 1).getLong("offsetMs") < 1000)
            return marks.length();
        marks.put(new JSONObject().put("offsetMs", offsetMs).put("note", ""));
        return marks.length();
    }
    static synchronized void rename(Context context, String session, int index, String note) throws Exception {
        JSONObject doc = read(context, session);
        JSONArray marks = doc.getJSONArray("marks");
        if (index < 0 || index >= marks.length()) throw new IOException("找不到这一标记");
        marks.getJSONObject(index).put("note", note == null ? "" : note.trim().substring(0, Math.min(note.trim().length(), 120)));
        write(context, session, doc);
    }
    static synchronized boolean delete(Context context, String session) {
        try { File file = file(context, session); return !file.exists() || file.delete(); }
        catch (IOException e) { return false; }
    }
    private static JSONObject read(Context context, String session) throws Exception {
        File file = file(context, session);
        if (!file.isFile()) return new JSONObject().put("version", 1).put("session", session).put("marks", new JSONArray());
        if (file.length() > 512 * 1024) throw new IOException("瞬间记录过大");
        byte[] plain = TextCrypto.decrypt(Files.readAllBytes(file.toPath()), new Vault(context).recordingKey(), aad(session));
        try {
            JSONObject doc = new JSONObject(new String(plain, StandardCharsets.UTF_8));
            if (doc.getInt("version") != 1 || !session.equals(doc.getString("session")) || doc.getJSONArray("marks").length() > MAX_MARKS)
                throw new IOException("瞬间记录无效");
            return doc;
        } finally { Arrays.fill(plain, (byte) 0); }
    }
    private static void write(Context context, String session, JSONObject doc) throws Exception {
        byte[] plain = doc.toString().getBytes(StandardCharsets.UTF_8);
        byte[] blob;
        try { blob = TextCrypto.encrypt(plain, new Vault(context).recordingKey(), aad(session)); }
        finally { Arrays.fill(plain, (byte) 0); }
        ChunkStore.atomicWrite(file(context, session), blob);
    }
}
