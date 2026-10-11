package cn.personal.recorder;

import java.util.ArrayList;
import java.util.List;
import java.util.Arrays;
import org.junit.Test;
import static org.junit.Assert.*;

public class LiveTranscriberTest {
    @Test public void pcmWindowsKeepSignedSamplesAndAbsoluteTimesAcrossReads() {
        LiveTranscriber.WindowBuffer buffer = new LiveTranscriber.WindowBuffer(4);
        List<LiveTranscriber.Window> windows = new ArrayList<>();
        byte[] first = {0, 0, -1, 127, 0, -128, -1, -1, 0, 64};
        byte[] second = {0, -64, 0, 0, 0, 32};
        buffer.offer(first, first.length, windows::add);
        buffer.offer(second, second.length, windows::add);
        assertEquals(2, windows.size());
        assertEquals(8, buffer.samplesSeen());
        assertEquals(0, windows.get(0).start);
        assertEquals(4, windows.get(0).end);
        assertEquals(4, windows.get(1).start);
        assertEquals(8, windows.get(1).end);
        assertEquals(0f, windows.get(0).samples[0], 0f);
        assertEquals(32767 / 32768f, windows.get(0).samples[1], 0f);
        assertEquals(-1f, windows.get(0).samples[2], 0f);
        assertEquals(-1 / 32768f, windows.get(0).samples[3], 0f);
        assertEquals(0.5f, windows.get(1).samples[0], 0f);
        assertEquals(-0.5f, windows.get(1).samples[1], 0f);
    }
    @Test public void skippedWindowsMergeIntoReviewableTimeRanges() {
        org.json.JSONArray ranges = LiveTranscriber.mergedMissing(Arrays.asList(
            new long[]{160000, 240000}, new long[]{80000, 160000}, new long[]{400000, 480000}));
        assertEquals(2, ranges.length());
        assertEquals(5.0, ranges.optJSONArray(0).optDouble(0), 0.001);
        assertEquals(15.0, ranges.optJSONArray(0).optDouble(1), 0.001);
        assertEquals(25.0, ranges.optJSONArray(1).optDouble(0), 0.001);
    }
}
