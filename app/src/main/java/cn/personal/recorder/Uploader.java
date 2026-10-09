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
        for (File f : ChunkStore.files(c)) {
            if (!vault.configured() || cancelled.getAsBoolean() || SystemClock.elapsedRealtime() > until) return;
            if (ChunkStore.uploaded(f)) continue;
            String digest = ChunkStore.digest(f);
            HttpURLConnection conn = (HttpURLConnection) new URL(base + "/v1/chunks/" + f.getName()).openConnection();
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
                status = "密文已上传，待传 " + ChunkStore.pending(c) + " 段";
            } catch (Exception e) { status = "上传暂未完成（" + safeError(e) + "），本地密文保留"; throw e; }
            finally { conn.disconnect(); }
        }
        status = "全部密文已上传";
    }
    private static String safeError(Exception e) {
        String message = e.getMessage();
        if (message != null && message.matches("HTTP \\d{3}")) return message;
        return "网络或服务器确认异常";
    }
}
