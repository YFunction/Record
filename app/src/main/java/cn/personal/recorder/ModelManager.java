package cn.personal.recorder;

import android.content.Context;
import java.io.File;
import java.io.InputStream;
import java.io.FileOutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.json.JSONObject;

/** Optional public model weights. No audio/text/key is involved in model downloads. */
final class ModelManager {
    private static JSONObject manifest(Context c) throws Exception {
        try (InputStream in = c.getAssets().open("speech-models.json")) {
            java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream(); byte[] block = new byte[1024]; int n;
            while ((n = in.read(block)) != -1) { if (bytes.size() + n > 8192) throw new java.io.IOException("模型配置过大"); bytes.write(block, 0, n); }
            return new JSONObject(bytes.toString("UTF-8"));
        }
    }
    static File directory(Context c) { return new File(c.getNoBackupFilesDir(), "speech-models-v1"); }
    static String path(Context c, String name) { return new File(directory(c), name).getAbsolutePath(); }
    static boolean ready(Context c) {
        try {
            JSONObject files = manifest(c).getJSONObject("files"); Iterator<String> names = files.keys();
            while (names.hasNext()) { String name = names.next(); File f = new File(directory(c), name); if (!f.isFile() || f.length() != files.getJSONObject(name).getLong("size")) return false; }
            return true;
        } catch (Exception e) { return false; }
    }
    static void verify(Context c) throws Exception {
        if (!ready(c)) throw new java.io.IOException("请先在设置中下载或导入离线模型");
        JSONObject files = manifest(c).getJSONObject("files"); Iterator<String> names = files.keys();
        while (names.hasNext()) { String name = names.next(); if (!hash(new File(directory(c), name)).equals(files.getJSONObject(name).getString("sha256"))) throw new java.io.IOException("离线模型校验失败，请重新安装模型"); }
    }
    static long downloadBytes(Context c) { try { return manifest(c).getLong("size"); } catch (Exception e) { return 0; } }
    static void download(Context c, BooleanSupplier cancelled, Consumer<String> progress) throws Exception {
        JSONObject spec = manifest(c); File zip = new File(c.getNoBackupFilesDir(), "speech-models-download.part");
        HttpURLConnection connection = null;
        try {
            URL url = new URL(spec.getString("url"));
            for (int redirects = 0; redirects < 6; redirects++) {
                LocalTranscriber.check(cancelled); if (!"https".equals(url.getProtocol())) throw new java.io.IOException("模型下载必须使用 HTTPS");
                connection = (HttpURLConnection) url.openConnection(); connection.setInstanceFollowRedirects(false); connection.setConnectTimeout(15000); connection.setReadTimeout(15000);
                int code = connection.getResponseCode();
                if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                    String location = connection.getHeaderField("Location"); URL next = new URL(url, location); connection.disconnect(); connection = null; url = next; continue;
                }
                if (code != 200) throw new java.io.IOException("模型下载失败（HTTP " + code + "）"); break;
            }
            if (connection == null || connection.getResponseCode() != 200) throw new java.io.IOException("模型下载重定向异常");
            copy(c, connection.getInputStream(), zip, spec.getLong("size"), cancelled, progress); install(c, zip, cancelled, progress);
        } finally { if (connection != null) connection.disconnect(); zip.delete(); }
    }
    static void importZip(Context c, android.net.Uri uri, BooleanSupplier cancelled, Consumer<String> progress) throws Exception {
        File zip = new File(c.getNoBackupFilesDir(), "speech-models-import.part");
        try {
            InputStream in = c.getContentResolver().openInputStream(uri); if (in == null) throw new java.io.IOException("无法读取模型包");
            copy(c, in, zip, manifest(c).getLong("size"), cancelled, progress); install(c, zip, cancelled, progress);
        } finally { zip.delete(); }
    }
    private static void copy(Context c, InputStream input, File destination, long expected, BooleanSupplier cancelled, Consumer<String> progress) throws Exception {
        if (new android.os.StatFs(c.getNoBackupFilesDir().getPath()).getAvailableBytes() < expected * 3 + 64L * 1024 * 1024) { input.close(); throw new java.io.IOException("模型安装需要约 700 MB 可用空间"); }
        try (InputStream in = input; FileOutputStream out = new FileOutputStream(destination)) {
            byte[] block = new byte[65536]; long bytes = 0; int last = -1, count;
            while ((count = in.read(block)) != -1) {
                LocalTranscriber.check(cancelled); bytes += count; if (bytes > expected) throw new java.io.IOException("模型包大小无效"); out.write(block, 0, count);
                int percent = (int) (100 * bytes / expected); if (percent != last) { last = percent; progress.accept("准备离线模型 · " + percent + "%"); }
            }
            if (bytes != expected) throw new java.io.IOException("模型包下载不完整"); out.getFD().sync();
        }
    }
    private static synchronized void install(Context c, File zip, BooleanSupplier cancelled, Consumer<String> progress) throws Exception {
        JSONObject spec = manifest(c); if (!hash(zip).equals(spec.getString("sha256"))) throw new java.io.IOException("模型包校验失败，请使用对应版本的模型包");
        File staging = new File(c.getNoBackupFilesDir(), "speech-models-new-" + UUID.randomUUID()); if (!staging.mkdir()) throw new java.io.IOException("无法建立模型目录");
        try {
            JSONObject files = spec.getJSONObject("files"); Set<String> seen = new HashSet<>();
            try (ZipFile archive = new ZipFile(zip)) {
                java.util.Enumeration<? extends ZipEntry> entries = archive.entries();
                while (entries.hasMoreElements()) {
                    LocalTranscriber.check(cancelled); ZipEntry entry = entries.nextElement(); String name = entry.getName();
                    if (!files.has(name) || !seen.add(name) || entry.isDirectory() || entry.getSize() != files.getJSONObject(name).getLong("size")) throw new java.io.IOException("模型包内容无效");
                    progress.accept("校验并安装离线模型"); File target = new File(staging, name); long bytes = 0;
                    try (InputStream in = archive.getInputStream(entry); FileOutputStream out = new FileOutputStream(target)) {
                        byte[] block = new byte[65536]; int count;
                        while ((count = in.read(block)) != -1) { LocalTranscriber.check(cancelled); bytes += count; if (bytes > entry.getSize()) throw new java.io.IOException("模型大小异常"); out.write(block, 0, count); }
                        out.getFD().sync();
                    }
                    if (bytes != entry.getSize() || !hash(target).equals(files.getJSONObject(name).getString("sha256"))) throw new java.io.IOException("模型文件校验失败");
                }
            }
            if (seen.size() != files.length()) throw new java.io.IOException("模型包文件缺失");
            File destination = directory(c); File old = new File(c.getNoBackupFilesDir(), "speech-models-old-" + UUID.randomUUID());
            boolean moved = destination.exists(); if (moved && !destination.renameTo(old)) throw new java.io.IOException("无法替换模型");
            if (!staging.renameTo(destination)) { if (moved) old.renameTo(destination); throw new java.io.IOException("模型安装失败"); }
            removeOwnDirectory(old); progress.accept("离线模型已准备好");
        } finally { removeOwnDirectory(staging); }
    }
    private static void removeOwnDirectory(File dir) { File[] children = dir.listFiles(); if (children != null) for (File file : children) if (file.isFile()) file.delete(); dir.delete(); }
    private static String hash(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256"); try (InputStream in = Files.newInputStream(file.toPath())) { byte[] block = new byte[65536]; int n; while ((n = in.read(block)) != -1) digest.update(block, 0, n); }
        StringBuilder hex = new StringBuilder(); for (byte b : digest.digest()) hex.append(String.format(java.util.Locale.ROOT, "%02x", b & 255)); return hex.toString();
    }
}
