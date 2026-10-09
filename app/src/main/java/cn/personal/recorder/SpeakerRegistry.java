package cn.personal.recorder;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Session-only voice prototypes keep estimated speaker IDs stable across memory batches. */
final class SpeakerRegistry implements AutoCloseable {
    private final List<float[]> voices = new ArrayList<>();
    private final int limit;
    SpeakerRegistry() { this(40); }
    SpeakerRegistry(int limit) { if (limit < 1 || limit > 40) throw new IllegalArgumentException(); this.limit = limit; }
    int assign(float[] embedding) {
        if (embedding.length == 0) throw new IllegalArgumentException("无效声纹");
        float[] voice = embedding.clone(); normalize(voice); double best = -1; int index = -1;
        for (int i = 0; i < voices.size(); i++) {
            double similarity = 0; float[] previous = voices.get(i);
            if (previous.length != voice.length) throw new IllegalArgumentException("声纹维度不匹配");
            for (int j = 0; j < voice.length; j++) similarity += previous[j] * voice[j];
            if (similarity > best) { best = similarity; index = i; }
        }
        if (best >= 0.7 || (voices.size() >= limit && limit < 40)) {
            float[] previous = voices.get(index); for (int j = 0; j < voice.length; j++) previous[j] = previous[j] * 0.8f + voice[j] * 0.2f;
            normalize(previous); Arrays.fill(voice, 0); return index;
        }
        if (voices.size() >= limit) { Arrays.fill(voice, 0); throw new IllegalArgumentException("发言人估计超过 40 位，请检查音频噪声"); }
        voices.add(voice); return voices.size() - 1;
    }
    private static void normalize(float[] vector) {
        double squares = 0; for (float v : vector) squares += v * v;
        if (!Double.isFinite(squares) || squares < 1e-12) throw new IllegalArgumentException("无效声纹");
        float scale = (float) Math.sqrt(squares); for (int i = 0; i < vector.length; i++) vector[i] /= scale;
    }
    @Override public void close() { for (float[] voice : voices) Arrays.fill(voice, 0); voices.clear(); }
}
