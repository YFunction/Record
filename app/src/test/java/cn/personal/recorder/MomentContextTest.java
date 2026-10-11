package cn.personal.recorder;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class MomentContextTest {
    @Test public void contextPlaybackStaysWithinRecording() {
        assertEquals(0, MomentContext.start(7_000));
        assertEquals(37_000, MomentContext.end(7_000, 100_000));
        assertEquals(15_000, MomentContext.start(30_000));
        assertEquals(40_000, MomentContext.end(30_000, 40_000));
    }
    @Test public void excerptSelectsNearbySourceSegment() throws Exception {
        JSONObject doc = new JSONObject().put("segments", new JSONArray()
            .put(new JSONObject().put("start", 2).put("end", 4).put("text", "前一句"))
            .put(new JSONObject().put("start", 25).put("end", 29).put("text", "关键原话")));
        int selected = MomentContext.nearbySegment(doc, 27_000);
        assertEquals(1, selected);
        assertEquals("关键原话", MomentContext.excerpt(doc, selected));
        assertEquals(-1, MomentContext.nearbySegment(doc, 60_000));
    }
}
