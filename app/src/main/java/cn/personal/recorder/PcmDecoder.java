package cn.personal.recorder;

import android.media.AudioFormat;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.function.BooleanSupplier;

/** AAC is decrypted through MediaDataSource and decoded in bounded memory, never a temp file. */
final class PcmDecoder implements AutoCloseable {
    private final MediaExtractor extractor = new MediaExtractor();
    private MediaCodec codec;
    private boolean inputEnded, outputEnded;
    private float[] available = new float[0];
    private int offset;
    private final MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
    PcmDecoder(RecordingSource source) throws Exception {
        try {
            extractor.setDataSource(source); if (extractor.getTrackCount() == 0) throw new java.io.IOException("录音中没有音轨");
            MediaFormat format = extractor.getTrackFormat(0);
            if (format.getInteger(MediaFormat.KEY_SAMPLE_RATE) != 16000 || format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) != 1)
                throw new java.io.IOException("录音格式不受支持");
            extractor.selectTrack(0); codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME));
            codec.configure(format, null, null, 0); codec.start();
        } catch (Exception e) { close(); throw e; }
    }
    float[] read(int maxSamples, BooleanSupplier cancelled) throws Exception {
        float[] batch = new float[maxSamples]; int written = 0;
        long lastOutput = android.os.SystemClock.elapsedRealtime();
        try {
            while (written < maxSamples) {
                if (cancelled.getAsBoolean()) throw new InterruptedException();
                if (offset < available.length) {
                    int count = Math.min(maxSamples - written, available.length - offset);
                    System.arraycopy(available, offset, batch, written, count); written += count; offset += count;
                    if (offset == available.length) { Arrays.fill(available, 0); available = new float[0]; offset = 0; }
                    continue;
                }
                if (outputEnded) break;
                if (!inputEnded) {
                    int input = codec.dequeueInputBuffer(10_000);
                    if (input >= 0) {
                        ByteBuffer data = codec.getInputBuffer(input); int size = extractor.readSampleData(data, 0);
                        if (size < 0) { codec.queueInputBuffer(input, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputEnded = true; }
                        else { codec.queueInputBuffer(input, 0, size, extractor.getSampleTime(), 0); extractor.advance(); }
                    }
                }
                int output = codec.dequeueOutputBuffer(info, 10_000);
                if (output == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    MediaFormat format = codec.getOutputFormat();
                    if (format.getInteger(MediaFormat.KEY_SAMPLE_RATE) != 16000 || format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) != 1 ||
                        (format.containsKey(MediaFormat.KEY_PCM_ENCODING) && format.getInteger(MediaFormat.KEY_PCM_ENCODING) != AudioFormat.ENCODING_PCM_16BIT))
                        throw new java.io.IOException("解码后的音频格式不受支持");
                } else if (output >= 0) {
                    lastOutput = android.os.SystemClock.elapsedRealtime();
                    try {
                        available = new float[info.size / 2];
                        if (info.size > 0) {
                            ByteBuffer data = codec.getOutputBuffer(output); if (data == null || info.size % 2 != 0) throw new java.io.IOException("解码器返回无效音频数据");
                            data.order(ByteOrder.LITTLE_ENDIAN); data.position(info.offset); data.limit(info.offset + info.size);
                            for (int i = 0; i < available.length; i++) available[i] = data.getShort() / 32768f;
                        }
                        outputEnded = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                    } finally { codec.releaseOutputBuffer(output, false); }
                }
                if (android.os.SystemClock.elapsedRealtime() - lastOutput > 30000) throw new java.io.IOException("音频解码超时，请检查录音是否完整");
            }
            if (written == batch.length) return batch;
            float[] result = Arrays.copyOf(batch, written); Arrays.fill(batch, 0); return result;
        } catch (Exception e) { Arrays.fill(batch, 0); throw e; }
    }
    @Override public void close() {
        Arrays.fill(available, 0); if (codec != null) { try { codec.stop(); } catch (Exception ignored) {} codec.release(); codec = null; } extractor.release();
    }
}
