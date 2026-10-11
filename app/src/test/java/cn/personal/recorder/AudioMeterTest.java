package cn.personal.recorder;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 36)
public class AudioMeterTest {
    @Test public void pcmLevelIsVisibleAndClearRemovesPreviousSpeech() {
        AudioMeter meter = new AudioMeter();
        byte[] pcm = new byte[2048];
        for (int i = 0; i < pcm.length; i += 2) { pcm[i] = 0; pcm[i + 1] = 64; }
        meter.accept(pcm, pcm.length);
        float[] bars = new float[AudioMeter.BARS]; meter.copyTo(bars);
        boolean audible = false; for (float bar : bars) audible |= bar > 0.1f;
        assertTrue(audible);
        meter.clear(); meter.copyTo(bars);
        for (float bar : bars) assertEquals(0f, bar, 0f);
    }
}
