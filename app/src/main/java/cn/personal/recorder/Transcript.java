package cn.personal.recorder;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.json.JSONArray;
import org.json.JSONObject;

final class Transcript {
    static JSONObject create(String session, boolean complete) throws Exception {
        return new JSONObject().put("version", 1).put("session", session).put("complete", complete)
            .put("updatedAt", System.currentTimeMillis()).put("segments", new JSONArray()).put("names", new JSONObject()).put("summary", "");
    }
    static String time(double seconds) {
        long s = Math.max(0, (long) seconds); return String.format(Locale.ROOT, "%02d:%02d:%02d", s / 3600, s / 60 % 60, s % 60);
    }
    static String speaker(JSONObject doc, int id) {
        return doc.optJSONObject("names").optString(Integer.toString(id), id < 0 ? "未确定发言人" : "发言人 " + (id + 1));
    }
    static String text(JSONObject doc) throws Exception {
        StringBuilder out = new StringBuilder(); JSONArray segments = doc.getJSONArray("segments");
        for (int i = 0; i < segments.length(); i++) {
            JSONObject s = segments.getJSONObject(i);
            out.append('[').append(time(s.getDouble("start"))).append("–").append(time(s.getDouble("end"))).append("] ")
                .append(speaker(doc, s.getInt("speaker"))).append(s.optBoolean("overlap") ? "（重叠语音，归属待核对）" : "").append('：').append(s.getString("text")).append('\n');
        }
        return out.toString();
    }
    static List<String> parts(String text, int limit) {
        if (limit < 2) throw new IllegalArgumentException("文本分段长度过小");
        List<String> parts = new ArrayList<>();
        for (int start = 0; start < text.length();) {
            int end = Math.min(text.length(), start + limit);
            if (end < text.length()) {
                int line = text.lastIndexOf('\n', end - 1); if (line > start + limit / 2) end = line + 1;
                if (end > start && Character.isHighSurrogate(text.charAt(end - 1))) end--;
            }
            parts.add(text.substring(start, end)); start = end;
        }
        return parts;
    }
}
