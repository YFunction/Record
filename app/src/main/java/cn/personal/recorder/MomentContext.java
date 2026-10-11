package cn.personal.recorder;

import org.json.JSONArray;
import org.json.JSONObject;

/** A moment is a reference into the original encrypted recording, not a second audio copy. */
final class MomentContext {
    static final long BEFORE_MS = 15_000, AFTER_MS = 30_000;

    static long start(long offsetMs) { return Math.max(0, offsetMs - BEFORE_MS); }
    static long end(long offsetMs, long durationMs) {
        return Math.max(0, Math.min(durationMs, offsetMs + AFTER_MS));
    }
    static int nearbySegment(JSONObject doc, long offsetMs) {
        if (doc == null) return -1;
        JSONArray segments = doc.optJSONArray("segments");
        if (segments == null) return -1;
        double target = offsetMs / 1000.0, bestDistance = Double.POSITIVE_INFINITY;
        int best = -1;
        for (int i = 0; i < segments.length(); i++) {
            JSONObject segment = segments.optJSONObject(i);
            if (segment == null) continue;
            double start = segment.optDouble("start", Double.NaN), end = segment.optDouble("end", Double.NaN);
            if (!Double.isFinite(start) || !Double.isFinite(end) || end < start) continue;
            double distance = target < start ? start - target : target > end ? target - end : 0;
            if (distance < bestDistance) { bestDistance = distance; best = i; }
        }
        return bestDistance <= 20 ? best : -1;
    }
    static String excerpt(JSONObject doc, int index) {
        if (doc == null || index < 0) return "暂无附近文字；可回听原音";
        JSONObject segment = doc.optJSONArray("segments").optJSONObject(index);
        if (segment == null) return "暂无附近文字；可回听原音";
        String text = segment.optString("text", "").trim().replace('\n', ' ');
        return text.isEmpty() ? "暂无附近文字；可回听原音" : text.substring(0, Math.min(text.length(), 100));
    }
}
