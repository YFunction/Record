package cn.personal.recorder;

import android.content.Context;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaRecorder;
import android.os.SystemClock;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.UUID;
import javax.crypto.SecretKey;

/** One continuous capture/encoder; segmentation never restarts the microphone. */
final class AacRecorder {
    private static final int RATE = 16000, SAMPLES_PER_CHUNK = RATE * 30;
    private final Context context;
    private final SecretKey key;
    private final String session = UUID.randomUUID().toString();
    private final long started = System.currentTimeMillis();
    private final ByteArrayOutputStream chunk = new ByteArrayOutputStream();
    private volatile boolean stop;
    private int sequence, chunkSamples;
    AacRecorder(Context c, String categoryId) throws Exception {
        context = c; key = new Vault(c).recordingKey();
        CategoryStore.assign(c, session, categoryId == null ? CategoryStore.defaultId(c) : categoryId);
    }
    String sessionId() { return session; }
    interface PcmSink { void accept(byte[] pcm, int count); }
    void stop() { stop = true; }
    void run(Runnable onChunk, Runnable onStarted, PcmSink liveText) throws Exception {
        if (context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED)
            throw new SecurityException("麦克风权限未授予");
        int min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        if (min <= 0) throw new IllegalStateException("麦克风不支持当前音频参数");
        AudioRecord record = null;
        MediaCodec codec = null;
        try {
            record = new AudioRecord.Builder().setAudioSource(MediaRecorder.AudioSource.MIC)
                .setAudioFormat(new AudioFormat.Builder().setSampleRate(RATE).setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build()).setBufferSizeInBytes(Math.max(min * 2, 8192)).build();
            if (record.getState() != AudioRecord.STATE_INITIALIZED) throw new IllegalStateException("麦克风初始化失败");
            MediaFormat format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, RATE, 1);
            format.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
            format.setInteger(MediaFormat.KEY_BIT_RATE, 64000);
            format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 2048);
            codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC);
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE); codec.start();
            record.startRecording();
            if (record.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) throw new IllegalStateException("麦克风未开始录音");
            onStarted.run();
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            byte[] pcm = new byte[2048];
            long samples = 0;
            while (!stop) {
                if (record.getActiveRecordingConfiguration() != null && record.getActiveRecordingConfiguration().isClientSilenced())
                    throw new IllegalStateException("麦克风已被系统静音或被其他应用占用");
                int input = codec.dequeueInputBuffer(10_000);
                if (input >= 0) {
                    ByteBuffer buffer = codec.getInputBuffer(input);
                    int count = record.read(pcm, 0, Math.min(pcm.length, buffer.capacity()) & ~1, AudioRecord.READ_BLOCKING);
                    if (count <= 0) throw new IllegalStateException("麦克风读取失败：" + count);
                    if (liveText != null) {
                        try { liveText.accept(pcm, count); } catch (RuntimeException ignored) { /* Preview cannot interrupt recording. */ }
                    }
                    buffer.clear(); buffer.put(pcm, 0, count);
                    codec.queueInputBuffer(input, 0, count, samples * 1_000_000L / RATE, 0);
                    samples += count / 2;
                }
                drain(codec, info, false, onChunk);
            }
            record.stop();
            long deadline = SystemClock.elapsedRealtime() + 5000;
            boolean eosQueued = false, eosReceived = false;
            while (!eosReceived && SystemClock.elapsedRealtime() < deadline) {
                if (!eosQueued) {
                    int input = codec.dequeueInputBuffer(10_000);
                    if (input >= 0) {
                        codec.queueInputBuffer(input, 0, 0, samples * 1_000_000L / RATE, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                        eosQueued = true;
                    }
                }
                eosReceived = drain(codec, info, true, onChunk);
            }
            if (!eosReceived) throw new IllegalStateException("编码器结束超时，已保存完成片段");
            flush(onChunk);
            ChunkStore.save(context, key, session, sequence, started, 0, new byte[0], true);
        } finally {
            try { flush(onChunk); }
            finally {
                if (record != null) { try { record.stop(); } catch (Exception ignored) {} record.release(); }
                if (codec != null) { try { codec.stop(); } catch (Exception ignored) {} codec.release(); }
            }
        }
    }
    private boolean drain(MediaCodec codec, MediaCodec.BufferInfo info, boolean wait, Runnable onChunk) throws Exception {
        while (true) {
            int output = codec.dequeueOutputBuffer(info, wait ? 10_000 : 0);
            if (output == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) continue;
            if (output < 0) return false;
            try {
                if (info.size > 0 && (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                    ByteBuffer buffer = codec.getOutputBuffer(output);
                    buffer.position(info.offset); buffer.limit(info.offset + info.size);
                    byte[] frame = new byte[info.size]; buffer.get(frame);
                    if (frame.length + 7 >= 8192) throw new IllegalStateException("AAC 帧过大");
                    chunk.write(adts(frame.length + 7)); chunk.write(frame); chunkSamples += 1024;
                    if (chunkSamples >= SAMPLES_PER_CHUNK) flush(onChunk);
                }
                if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) return true;
            } finally { codec.releaseOutputBuffer(output, false); }
        }
    }
    private void flush(Runnable onChunk) throws Exception {
        if (chunk.size() == 0) return;
        ChunkStore.save(context, key, session, sequence, started, chunkSamples, chunk.toByteArray(), false);
        sequence++; chunk.reset(); chunkSamples = 0; onChunk.run();
    }
    static byte[] adts(int size) {
        // AAC-LC, MPEG-4, 16 kHz (sampling frequency index 8), mono.
        return new byte[] {(byte)0xff, (byte)0xf1, (byte)((1 << 6) | (8 << 2)),
            (byte)((1 << 6) | (size >> 11)), (byte)(size >> 3), (byte)(((size & 7) << 5) | 0x1f), (byte)0xfc};
    }
}
