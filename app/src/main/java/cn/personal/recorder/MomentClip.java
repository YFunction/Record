package cn.personal.recorder;

import android.media.MediaDataSource;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import org.json.JSONObject;

/** Explicit, bounded encrypted export of a marked AAC excerpt. */
final class MomentClip {
    private static final int RATE = 16000, FRAME_SAMPLES = 1024, MAX_AUDIO_BYTES = 1024 * 1024;
    private static final byte[] MAGIC = {'R', 'C', '0', '1'};
    private static final byte[] AAD = "record-moment-clip-v1".getBytes(StandardCharsets.UTF_8);

    static byte[] create(MediaDataSource source, long samples, SecretKey key, String session, long offsetMs) throws Exception {
        if (!TextStore.validSession(session) || offsetMs < 0) throw new IOException("瞬间标记无效");
        long durationMs = samples * 1000 / RATE;
        if (durationMs <= 0 || offsetMs > durationMs + 1000) throw new IOException("标记超出录音范围");
        long startMs = Math.min(durationMs, MomentContext.start(offsetMs));
        long endMs = MomentContext.end(offsetMs, durationMs);
        byte[] audio = extract(source, startMs, endMs);
        if (audio.length == 0) throw new IOException("所选范围没有可导出的音频");
        try {
            JSONObject metadata = new JSONObject().put("version", 1).put("session", session)
                .put("markOffsetMs", offsetMs).put("startMs", startMs).put("endMs", endMs)
                .put("codec", "aac-adts");
            return seal(key, metadata, audio);
        } finally { Arrays.fill(audio, (byte) 0); }
    }
    static byte[] seal(SecretKey key, JSONObject metadata, byte[] audio) throws Exception {
        byte[] plain = null;
        try {
            byte[] header = metadata.toString().getBytes(StandardCharsets.UTF_8);
            if (header.length > 2048 || audio.length == 0 || audio.length > MAX_AUDIO_BYTES) throw new IOException("片段内容过大或为空");
            plain = ByteBuffer.allocate(4 + header.length + audio.length).putInt(header.length).put(header).put(audio).array();
            byte[] nonce = new byte[12]; new SecureRandom().nextBytes(nonce);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, nonce)); cipher.updateAAD(AAD);
            byte[] sealed = cipher.doFinal(plain);
            return ByteBuffer.allocate(16 + sealed.length).put(MAGIC).put(nonce).put(sealed).array();
        } finally { if (plain != null) Arrays.fill(plain, (byte) 0); }
    }
    static byte[] extract(MediaDataSource source, long startMs, long endMs) throws IOException {
        if (startMs < 0 || endMs <= startMs) throw new IOException("片段时间范围无效");
        long first = startMs * RATE / (1000L * FRAME_SAMPLES);
        long lastExclusive = (endMs * RATE + 1000L * FRAME_SAMPLES - 1) / (1000L * FRAME_SAMPLES);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] header = new byte[7]; long position = 0, frame = 0, size = source.getSize();
        while (position < size && frame < lastExclusive) {
            readExactly(source, position, header, 7);
            int length = adtsLength(header);
            if (length > size - position) throw new IOException("AAC 帧已截断");
            if (frame >= first) {
                if (out.size() + length > MAX_AUDIO_BYTES) throw new IOException("片段过大，请缩短标记范围");
                byte[] audioFrame = new byte[length];
                try { readExactly(source, position, audioFrame, length); out.write(audioFrame); }
                finally { Arrays.fill(audioFrame, (byte) 0); }
            }
            position += length; frame++;
        }
        return out.toByteArray();
    }
    static int adtsLength(byte[] h) throws IOException {
        if (h.length < 7 || (h[0] & 255) != 255 || (h[1] & 0xf6) != 0xf0
            || ((h[2] >>> 2) & 15) != 8 || ((h[2] & 1) << 2 | ((h[3] >>> 6) & 3)) != 1)
            throw new IOException("AAC 帧头无效");
        int length = ((h[3] & 3) << 11) | ((h[4] & 255) << 3) | ((h[5] & 224) >>> 5);
        if (length < 7 || length > 8192) throw new IOException("AAC 帧长无效");
        return length;
    }
    private static void readExactly(MediaDataSource source, long position, byte[] target, int length) throws IOException {
        int read = 0;
        while (read < length) {
            int n = source.readAt(position + read, target, read, length - read);
            if (n <= 0) throw new IOException("音频片段不完整");
            read += n;
        }
    }
}
