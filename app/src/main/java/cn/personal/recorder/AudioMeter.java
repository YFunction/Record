package cn.personal.recorder;

/** Short-lived levels only; raw microphone samples never enter the UI. */
final class AudioMeter {
    static final int BARS = 48;
    private final float[] levels = new float[BARS];
    private int next;
    private long lastSampleAt = -1000;

    synchronized void accept(byte[] pcm, int count) {
        long now = android.os.SystemClock.elapsedRealtime();
        if (now - lastSampleAt < 65) return;
        lastSampleAt = now;
        long sum = 0;
        int samples = 0;
        for (int i = 0; i + 1 < count; i += 4) {
            int value = (short) (((pcm[i + 1] & 255) << 8) | (pcm[i] & 255));
            sum += (long) value * value;
            samples++;
        }
        float rms = samples == 0 ? 0f : (float) Math.sqrt(sum / (double) samples) / 32768f;
        levels[next++ % BARS] = Math.min(1f, (float) Math.sqrt(rms) * 1.5f);
    }

    synchronized void copyTo(float[] output) {
        for (int i = 0; i < BARS; i++) output[i] = levels[(next + i) % BARS];
    }

    synchronized void clear() {
        java.util.Arrays.fill(levels, 0f);
        next = 0; lastSampleAt = -1000;
    }
}
