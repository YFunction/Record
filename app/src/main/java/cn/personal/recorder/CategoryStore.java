package cn.personal.recorder;

import android.content.Context;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.UUID;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import org.json.JSONArray;
import org.json.JSONObject;

/** Category names, per-recording assignments and AI permissions are stored as one encrypted catalog. */
final class CategoryStore {
    static final int LIMIT = 1024 * 1024;
    private static final byte[] MAGIC = new byte[]{'C','C','0','1'};
    private static final byte[] AAD = "category-catalog-v1".getBytes(StandardCharsets.UTF_8);
    private static final String PRIVATE = "00000000-0000-4000-8000-000000000001";
    private static final String MEETING = "00000000-0000-4000-8000-000000000002";
    private static final String CLASSROOM = "00000000-0000-4000-8000-000000000003";
    private static final String INTERVIEW = "00000000-0000-4000-8000-000000000004";
    private static final String UNCATEGORIZED = "00000000-0000-4000-8000-000000000005";
    private CategoryStore() {}

    static final class State {
        final JSONArray categories;
        final JSONObject assignments;
        String defaultId;
        long updatedAt;
        State(JSONArray categories, JSONObject assignments, String defaultId, long updatedAt) {
            this.categories = categories; this.assignments = assignments; this.defaultId = defaultId; this.updatedAt = updatedAt;
        }
        int size() { return categories.length(); }
        JSONObject category(int index) { return categories.optJSONObject(index); }
        String id(JSONObject category) { return category == null ? UNCATEGORIZED : category.optString("id", UNCATEGORIZED); }
        String name(String id) { JSONObject c = find(this, id); return c == null ? "未分类" : c.optString("name", "未分类"); }
        String assigned(String session) {
            String id = assignments.optString(session, UNCATEGORIZED);
            return find(this, id) == null ? UNCATEGORIZED : id;
        }
    }

    static synchronized State load(Context context) throws Exception {
        java.io.File file = file(context);
        if (!file.isFile()) return defaults();
        byte[] envelope = java.nio.file.Files.readAllBytes(file.toPath());
        try {
            JSONObject json = new JSONObject(new String(decrypt(envelope, new Vault(context).recordingKey()), StandardCharsets.UTF_8));
            if (json.optInt("version") != 1) throw new IOException("分类目录版本不受支持");
            JSONArray categories = json.optJSONArray("categories"); JSONObject assignments = json.optJSONObject("assignments");
            String defaultId = json.optString("defaultCategory", "");
            if (categories == null || categories.length() < 1 || categories.length() > 200 || assignments == null || find(categories, defaultId) == null)
                throw new IOException("分类目录数据无效");
            State state = new State(categories, assignments, defaultId, json.optLong("updatedAt"));
            return state;
        } finally { Arrays.fill(envelope, (byte) 0); }
    }

    static synchronized String defaultId(Context context) {
        try { return load(context).defaultId; } catch (Exception e) { return PRIVATE; }
    }
    static synchronized String categoryFor(Context context, String session) {
        try { return load(context).assigned(session); } catch (Exception e) { return UNCATEGORIZED; }
    }
    static String name(Context context, String id) {
        try { return load(context).name(id); } catch (Exception e) { return "未分类"; }
    }
    static boolean aiAllowed(Context context, String session) {
        try {
            State state = load(context); JSONObject category = find(state, state.assigned(session));
            return category != null && category.optBoolean("allowExternalAi", false);
        } catch (Exception e) { return false; }
    }
    static String template(Context context, String session) {
        try { JSONObject category = find(load(context), categoryFor(context, session)); return category == null ? "standard" : category.optString("template", "standard"); }
        catch (Exception e) { return "standard"; }
    }
    static String aiPolicy(Context context, String session) {
        try { State state = load(context); return state.name(state.assigned(session)); } catch (Exception e) { return "未分类"; }
    }

    static synchronized void assign(Context context, String session, String categoryId) throws Exception {
        State state = load(context);
        if (!TextStore.validSession(session) || find(state, categoryId) == null) throw new IllegalArgumentException("录音或分类无效");
        state.assignments.put(session, categoryId); save(context, state);
    }
    static synchronized void setDefault(Context context, String categoryId) throws Exception {
        State state = load(context); if (find(state, categoryId) == null) throw new IllegalArgumentException("分类不存在");
        state.defaultId = categoryId; save(context, state);
    }
    static synchronized String add(Context context, String name, String template) throws Exception {
        return add(context, name, template, false);
    }
    static synchronized String add(Context context, String name, String template, boolean allowAi) throws Exception {
        State state = load(context); validateName(state, name, null); validateTemplate(template);
        if (state.size() >= 200) throw new IllegalArgumentException("最多创建 200 个分类");
        String id = UUID.randomUUID().toString();
        state.categories.put(new JSONObject().put("id", id).put("name", name.trim()).put("template", template).put("allowExternalAi", allowAi));
        save(context, state); return id;
    }
    static synchronized void edit(Context context, String id, String name, String template, boolean allowAi) throws Exception {
        State state = load(context); JSONObject category = find(state, id);
        if (category == null) throw new IllegalArgumentException("分类不存在");
        validateName(state, name, id); validateTemplate(template);
        category.put("name", name.trim()).put("template", template).put("allowExternalAi", allowAi); save(context, state);
    }
    static synchronized void remove(Context context, String id, String reassignTo) throws Exception {
        State state = load(context);
        if (id.equals(state.defaultId)) throw new IllegalArgumentException("先更换快速录音默认分类，再删除此分类");
        if (find(state, id) == null || find(state, reassignTo) == null) throw new IllegalArgumentException("分类不存在");
        for (int i = 0; i < state.categories.length(); i++) if (id.equals(state.id(state.category(i)))) { state.categories.remove(i); break; }
        for (java.util.Iterator<String> it = state.assignments.keys(); it.hasNext();) {
            String session = it.next(); if (id.equals(state.assignments.optString(session))) state.assignments.put(session, reassignTo);
        }
        save(context, state);
    }
    static synchronized void save(Context context, State state) throws Exception {
        new Vault(context).initialize("");
        state.updatedAt = System.currentTimeMillis();
        JSONObject json = new JSONObject().put("version", 1).put("updatedAt", state.updatedAt)
            .put("defaultCategory", state.defaultId).put("categories", state.categories).put("assignments", state.assignments);
        byte[] plain = json.toString().getBytes(StandardCharsets.UTF_8), envelope;
        try { envelope = encrypt(plain, new Vault(context).recordingKey()); } finally { Arrays.fill(plain, (byte) 0); }
        try {
            if (envelope.length > LIMIT) throw new IOException("分类目录超过 1 MB");
            ChunkStore.atomicWrite(file(context), envelope);
            java.io.File ack = ackFile(context); if (ack.exists()) ack.delete();
        } finally { Arrays.fill(envelope, (byte) 0); }
        UploadWorker.schedule(context);
    }
    static synchronized byte[] encryptedFile(Context context) throws Exception {
        java.io.File file = file(context);
        if (!file.isFile()) return null;
        byte[] bytes = java.nio.file.Files.readAllBytes(file.toPath());
        if (bytes.length > LIMIT || bytes.length < 32) { Arrays.fill(bytes, (byte) 0); throw new IOException("分类密文大小无效"); }
        return bytes;
    }
    static synchronized boolean uploaded(Context context, byte[] bytes) throws Exception {
        java.io.File ack = ackFile(context); if (!ack.isFile()) return false;
        byte[] prior = java.nio.file.Files.readAllBytes(ack.toPath()), digest = MessageDigest.getInstance("SHA-256").digest(bytes);
        try { return MessageDigest.isEqual(prior, digest); } finally { Arrays.fill(prior, (byte) 0); Arrays.fill(digest, (byte) 0); }
    }
    static synchronized void acknowledge(Context context, byte[] bytes) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
        try { ChunkStore.atomicWrite(ackFile(context), digest); } finally { Arrays.fill(digest, (byte) 0); }
    }
    static synchronized boolean hasPending(Context context) throws Exception {
        byte[] bytes = encryptedFile(context); if (bytes == null) return false;
        try { return !uploaded(context, bytes); } finally { Arrays.fill(bytes, (byte) 0); }
    }
    static synchronized void restore(Context context, byte[] bytes, String claimedDigest) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes); StringBuilder hex = new StringBuilder();
        for (byte b : digest) hex.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
        try {
            if (!MessageDigest.isEqual(hex.toString().getBytes(StandardCharsets.US_ASCII), claimedDigest.getBytes(StandardCharsets.US_ASCII))) throw new IOException("服务器校验值不匹配");
            byte[] plain = decrypt(bytes, new Vault(context).recordingKey());
            try {
                JSONObject json = new JSONObject(new String(plain, StandardCharsets.UTF_8)); JSONArray categories = json.optJSONArray("categories");
                if (json.optInt("version") != 1 || categories == null || categories.length() < 1 || categories.length() > 200 || find(categories, json.optString("defaultCategory")) == null)
                    throw new IOException("服务器分类目录格式无效");
            } finally { Arrays.fill(plain, (byte) 0); }
            ChunkStore.atomicWrite(file(context), bytes); java.io.File ack = ackFile(context); if (ack.exists()) ack.delete();
        } finally { Arrays.fill(digest, (byte) 0); }
    }
    static java.io.File file(Context context) {
        java.io.File dir = new java.io.File(context.getNoBackupFilesDir(), "category-catalog");
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IllegalStateException("无法创建分类目录");
        return new java.io.File(dir, "catalog.enc");
    }
    private static java.io.File ackFile(Context context) { return new java.io.File(file(context).getPath() + ".ack"); }

    private static byte[] encrypt(byte[] plain, SecretKey key) throws Exception {
        byte[] nonce = new byte[12]; new SecureRandom().nextBytes(nonce);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, nonce)); cipher.updateAAD(AAD);
        byte[] encrypted = cipher.doFinal(plain), out = new byte[16 + encrypted.length];
        System.arraycopy(MAGIC, 0, out, 0, 4); System.arraycopy(nonce, 0, out, 4, 12); System.arraycopy(encrypted, 0, out, 16, encrypted.length); Arrays.fill(encrypted, (byte) 0); return out;
    }
    private static byte[] decrypt(byte[] blob, SecretKey key) throws Exception {
        if (blob.length < 32 || blob.length > LIMIT || !Arrays.equals(Arrays.copyOf(blob, 4), MAGIC)) throw new IOException("分类密文格式无效");
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, blob, 4, 12)); cipher.updateAAD(AAD);
        return cipher.doFinal(blob, 16, blob.length - 16);
    }
    private static State defaults() throws Exception {
        JSONArray categories = new JSONArray()
            .put(new JSONObject().put("id", PRIVATE).put("name", "隐私录音").put("template", "private").put("allowExternalAi", false))
            .put(new JSONObject().put("id", MEETING).put("name", "会议记录").put("template", "meeting").put("allowExternalAi", false))
            .put(new JSONObject().put("id", CLASSROOM).put("name", "课堂记录").put("template", "classroom").put("allowExternalAi", false))
            .put(new JSONObject().put("id", INTERVIEW).put("name", "访谈调研").put("template", "interview").put("allowExternalAi", false))
            .put(new JSONObject().put("id", UNCATEGORIZED).put("name", "未分类").put("template", "standard").put("allowExternalAi", false));
        return new State(categories, new JSONObject(), PRIVATE, 0);
    }
    private static JSONObject find(State state, String id) { return state == null ? null : find(state.categories, id); }
    private static JSONObject find(JSONArray categories, String id) {
        if (id == null) return null;
        for (int i = 0; i < categories.length(); i++) { JSONObject c = categories.optJSONObject(i); if (c != null && id.equals(c.optString("id"))) return c; }
        return null;
    }
    private static void validateName(State state, String name, String exceptId) {
        if (name == null || name.trim().isEmpty() || name.trim().length() > 40) throw new IllegalArgumentException("分类名称需为 1～40 个字符");
        for (int i = 0; i < state.size(); i++) { JSONObject c = state.category(i); if (c != null && !c.optString("id").equals(exceptId) && name.trim().equalsIgnoreCase(c.optString("name"))) throw new IllegalArgumentException("分类名称已存在"); }
    }
    private static void validateTemplate(String template) {
        if (!("standard".equals(template) || "private".equals(template) || "meeting".equals(template) || "classroom".equals(template) || "interview".equals(template))) throw new IllegalArgumentException("总结模板无效");
    }
}
