package cn.personal.recorder;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.os.SystemClock;
import java.io.File;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.function.BooleanSupplier;
import java.util.Arrays;
import org.json.JSONObject;

final class Uploader {
    static volatile String status = "等待上传";
    static String validateUrl(String input) throws Exception {
        URI uri = new URI(input.trim());
        boolean localDebug = BuildConfig.DEBUG && "http".equals(uri.getScheme()) &&
            ("127.0.0.1".equals(uri.getHost()) || "localhost".equals(uri.getHost()));
        if (!("https".equals(uri.getScheme()) || localDebug) || uri.getHost() == null ||
            uri.getRawUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null ||
            !(uri.getPath().isEmpty() || "/".equals(uri.getPath())))
            throw new IllegalArgumentException("使用 HTTPS 域名根地址；调试版支持 http://127.0.0.1:8080");
        return input.trim().replaceAll("/+$", "");
    }
    static synchronized void drain(Context c, BooleanSupplier cancelled, long budgetMillis) throws Exception {
        Vault vault = new Vault(c);
        if (!vault.configured()) return;
        ConnectivityManager cm = c.getSystemService(ConnectivityManager.class);
        NetworkCapabilities net = cm.getNetworkCapabilities(cm.getActiveNetwork());
        if (net == null || !net.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) ||
            (vault.preferences().getBoolean("wifi-only", false) && !net.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED))) {
            status = "等待可用网络，录音保存在本地"; return;
        }
        String base = validateUrl(vault.preferences().getString("server", ""));
        String token = vault.token();
        long until = SystemClock.elapsedRealtime() + budgetMillis;
        java.util.List<File> pending = new java.util.ArrayList<>(java.util.Arrays.asList(ChunkStore.files(c)));
        pending.addAll(java.util.Arrays.asList(TextStore.files(c)));
        for (File f : pending) {
            if (!vault.configured() || cancelled.getAsBoolean() || SystemClock.elapsedRealtime() > until) return;
            if (ChunkStore.uploaded(f)) continue;
            String digest = ChunkStore.digest(f);
            boolean text = TextStore.validName(f.getName());
            HttpURLConnection conn = (HttpURLConnection) new URL(base + (text ? "/v1/documents/" : "/v1/chunks/") + f.getName()).openConnection();
            try {
                conn.setInstanceFollowRedirects(false);
                conn.setConnectTimeout(10_000); conn.setReadTimeout(15_000);
                conn.setRequestMethod("PUT"); conn.setDoOutput(true);
                conn.setFixedLengthStreamingMode(f.length());
                conn.setRequestProperty("Authorization", "Bearer " + token);
                conn.setRequestProperty("Content-Type", "application/octet-stream");
                conn.setRequestProperty("X-Content-SHA256", digest);
                try (OutputStream out = conn.getOutputStream()) { Files.copy(f.toPath(), out); }
                int code = conn.getResponseCode();
                if (code != 200 && code != 201) throw new java.io.IOException("HTTP " + code);
                byte[] body;
                try (InputStream in = conn.getInputStream()) {
                    ByteArrayOutputStream result = new ByteArrayOutputStream();
                    byte[] block = new byte[1024];
                    int count;
                    while (result.size() <= 8192 && (count = in.read(block, 0, Math.min(block.length, 8193 - result.size()))) != -1)
                        result.write(block, 0, count);
                    body = result.toByteArray();
                }
                if (body.length > 8192) throw new java.io.IOException("服务器确认过大");
                JSONObject ack = new JSONObject(new String(body, StandardCharsets.UTF_8));
                if (!ack.getBoolean("stored") || !f.getName().equals(ack.getString("name")) ||
                    !digest.equals(ack.getString("sha256"))) throw new java.io.IOException("服务器确认不匹配");
                ChunkStore.acknowledge(f);
                status = "密文已上传，待传 " + (ChunkStore.pending(c) + TextStore.pending(c)) + " 份";
            } catch (Exception e) { status = "上传暂未完成（" + safeError(e) + "），本地密文保留"; throw e; }
            finally { conn.disconnect(); }
        }
        if (!cancelled.getAsBoolean() && SystemClock.elapsedRealtime() <= until) uploadCategoryCatalog(c, base, token, until, cancelled);
        status = "全部密文已上传";
    }
    private static void uploadCategoryCatalog(Context c, String base, String token, long until, BooleanSupplier cancelled) throws Exception {
        byte[] blob = CategoryStore.encryptedFile(c); if (blob == null) return;
        try {
            if (CategoryStore.uploaded(c, blob)) return;
            if (!vaultStillConfigured(c) || cancelled.getAsBoolean() || SystemClock.elapsedRealtime() > until) return;
            byte[] hash = java.security.MessageDigest.getInstance("SHA-256").digest(blob); StringBuilder hex = new StringBuilder();
            for (byte b : hash) hex.append(String.format(java.util.Locale.ROOT, "%02x", b & 255)); Arrays.fill(hash, (byte) 0);
            HttpURLConnection conn = (HttpURLConnection) new URL(base + "/v1/category-catalog").openConnection();
            try {
                conn.setInstanceFollowRedirects(false); conn.setConnectTimeout(10_000); conn.setReadTimeout(15_000);
                conn.setRequestMethod("PUT"); conn.setDoOutput(true); conn.setFixedLengthStreamingMode(blob.length);
                conn.setRequestProperty("Authorization", "Bearer " + token); conn.setRequestProperty("Content-Type", "application/octet-stream"); conn.setRequestProperty("X-Content-SHA256", hex.toString());
                try (OutputStream out = conn.getOutputStream()) { out.write(blob); }
                int code = conn.getResponseCode(); if (code != 200 && code != 201) throw new java.io.IOException("HTTP " + code);
                byte[] body; try (InputStream in = conn.getInputStream()) { ByteArrayOutputStream result = new ByteArrayOutputStream(); byte[] block = new byte[1024]; int count; while ((count = in.read(block)) != -1) { if (result.size() + count > 8192) throw new java.io.IOException("服务器确认过大"); result.write(block, 0, count); } body = result.toByteArray(); }
                JSONObject ack = new JSONObject(new String(body, StandardCharsets.UTF_8));
                if (!ack.getBoolean("stored") || !"category-catalog".equals(ack.getString("name")) || !hex.toString().equals(ack.getString("sha256")) || ack.getLong("size") != blob.length) throw new java.io.IOException("服务器确认不匹配");
                CategoryStore.acknowledge(c, blob); status = "录音与加密分类目录已同步";
            } catch (Exception e) { status = "分类目录同步暂未完成，手机密文保留"; throw e; }
            finally { conn.disconnect(); }
        } finally { java.util.Arrays.fill(blob, (byte) 0); }
    }
    private static boolean vaultStillConfigured(Context c) { return new Vault(c).configured(); }
    static void restoreCategoryCatalog(Context c) throws Exception {
        Vault vault = new Vault(c); if (!vault.configured()) throw new java.io.IOException("请先配置并开启云端同步");
        String base = validateUrl(vault.preferences().getString("server", ""));
        HttpURLConnection conn = (HttpURLConnection) new URL(base + "/v1/category-catalog").openConnection();
        try {
            conn.setInstanceFollowRedirects(false); conn.setConnectTimeout(10_000); conn.setReadTimeout(20_000);
            conn.setRequestMethod("GET"); conn.setRequestProperty("Authorization", "Bearer " + vault.token());
            int code = conn.getResponseCode(); if (code == 404) throw new java.io.IOException("服务器上还没有分类备份"); if (code != 200) throw new java.io.IOException("HTTP " + code);
            String digest = conn.getHeaderField("X-Content-SHA256"); if (digest == null || !digest.matches("[0-9a-f]{64}")) throw new java.io.IOException("服务器校验值无效");
            byte[] blob; try (InputStream in = conn.getInputStream()) { ByteArrayOutputStream out = new ByteArrayOutputStream(); byte[] block = new byte[8192]; int n; while ((n = in.read(block)) != -1) { if (out.size() + n > CategoryStore.LIMIT) throw new java.io.IOException("分类备份超过大小限制"); out.write(block, 0, n); } blob = out.toByteArray(); }
            try { CategoryStore.restore(c, blob, digest); } finally { java.util.Arrays.fill(blob, (byte) 0); }
        } finally { conn.disconnect(); }
    }
    private static String safeError(Exception e) {
        String message = e.getMessage();
        if (message != null && message.matches("HTTP \\d{3}")) return message;
        return "网络或服务器确认异常";
    }
}
