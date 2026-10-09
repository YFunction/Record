package cn.personal.recorder;

import android.media.MediaDataSource;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import org.json.JSONObject;

/** Seekable ADTS stream. Only one authenticated plaintext slice is kept in memory. */
final class RecordingSource extends MediaDataSource {
    private final SecretKey key;
    private final List<File> files = new ArrayList<>();
    private final List<Long> offsets = new ArrayList<>();
    private final List<Integer> lengths = new ArrayList<>();
    private byte[] cache;
    private int cachedIndex = -1;
    private long size;
    private boolean closed;
    long startedAt, samples;
    boolean complete;

    RecordingSource(File[] chunks, SecretKey key) throws IOException {
        this.key = key;
        if (chunks.length == 0) throw new IOException("录音文件已被移除");
        String session = chunks[0].getName().substring(0, 36);
        try {
            int expected = 0;
            for (File file : chunks) {
                if (complete) throw new IOException("结束标记后仍有片段");
                Slice slice = decrypt(file);
                try {
                    JSONObject m = slice.meta;
                    if (!session.equals(m.getString("session")) || m.getInt("index") != expected ||
                        !file.getName().equals(session + "_" + String.format(java.util.Locale.ROOT, "%08d", expected) + ".enc") ||
                        m.getInt("version") != 1 || !"aac-adts".equals(m.getString("codec")) ||
                        m.getInt("sampleRate") != 16000 || m.getInt("channels") != 1 || m.getLong("samples") < 0)
                        throw new IOException("录音片段缺失或格式不匹配，无法播放或导出");
                    if (expected == 0) startedAt = m.getLong("startedAt");
                    else if (startedAt != m.getLong("startedAt")) throw new IOException("片段时间不一致");
                    int length = slice.plain.length - slice.audioOffset;
                    complete = m.getBoolean("final");
                    if (complete && (length != 0 || m.getLong("samples") != 0)) throw new IOException("结束标记无效");
                    samples += m.getLong("samples");
                    if (length > 0) {
                        files.add(file); offsets.add(size); lengths.add(length); size += length;
                    }
                    expected++;
                } finally { Arrays.fill(slice.plain, (byte) 0); }
            }
            if (size == 0) throw new IOException("录音中没有可播放的音频");
        } catch (IOException e) { throw e; }
        catch (Exception e) { throw new IOException("密文认证失败，录音可能损坏或密钥不匹配", e); }
    }

    private static final class Slice {
        byte[] plain; JSONObject meta; int audioOffset;
    }
    private Slice decrypt(File file) throws Exception {
        if (file.length() < 32 || file.length() > 2 * 1024 * 1024) throw new IOException("片段大小无效");
        byte[] blob = Files.readAllBytes(file.toPath());
        if (ByteBuffer.wrap(blob).getInt() != 0x45523031) throw new IOException("密文格式无效");
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, blob, 4, 12));
        cipher.updateAAD(file.getName().getBytes(StandardCharsets.UTF_8));
        byte[] plain = cipher.doFinal(blob, 16, blob.length - 16);
        try {
            if (plain.length < 8 || ByteBuffer.wrap(plain).getInt() != 0x45414131) throw new IOException("音频格式无效");
            int jsonLength = ByteBuffer.wrap(plain, 4, 4).getInt();
            if (jsonLength < 2 || jsonLength > 65536 || jsonLength > plain.length - 8) throw new IOException("元信息格式无效");
            Slice slice = new Slice(); slice.plain = plain; slice.audioOffset = 8 + jsonLength;
            slice.meta = new JSONObject(new String(plain, 8, jsonLength, StandardCharsets.UTF_8));
            return slice;
        } catch (Exception e) { Arrays.fill(plain, (byte) 0); throw e; }
    }
    @Override public synchronized int readAt(long position, byte[] buffer, int offset, int count) throws IOException {
        if (closed) throw new IOException("播放已结束");
        if (position < 0 || offset < 0 || count < 0 || offset > buffer.length - count) throw new IOException("读取范围无效");
        if (count == 0) return 0;
        if (position >= size) return -1;
        int total = 0;
        try {
            int low = 0, high = offsets.size() - 1;
            while (low < high) {
                int mid = (low + high + 1) >>> 1;
                if (offsets.get(mid) <= position) low = mid; else high = mid - 1;
            }
            for (int i = low; i < files.size() && total < count; i++) {
                if (cachedIndex != i) {
                    clearCache(); Slice slice = decrypt(files.get(i));
                    try { cache = Arrays.copyOfRange(slice.plain, slice.audioOffset, slice.plain.length); }
                    finally { Arrays.fill(slice.plain, (byte) 0); }
                    if (cache.length != lengths.get(i)) throw new IOException("录音文件已改变");
                    cachedIndex = i;
                }
                int inside = (int) (position - offsets.get(i));
                int length = Math.min(count - total, cache.length - inside);
                System.arraycopy(cache, inside, buffer, offset + total, length);
                total += length; position += length;
            }
            return total;
        } catch (IOException e) { throw e; }
        catch (Exception e) { throw new IOException("读取或解密录音失败", e); }
    }
    @Override public synchronized long getSize() { return size; }
    private void clearCache() { if (cache != null) Arrays.fill(cache, (byte) 0); cache = null; cachedIndex = -1; }
    @Override public synchronized void close() { closed = true; clearCache(); }
}
