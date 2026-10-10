package cn.personal.recorder;

import android.content.Context;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.BooleanSupplier;
import org.json.JSONArray;
import org.json.JSONObject;

/** Searches decrypted documents only in memory. No plaintext index or network request. */
final class LocalSearch {
    static final int MAX_RESULTS = 80, MAX_PER_RECORDING = 4;
    static final class Hit {
        final String session, category, field, snippet;
        final long recordedAt;
        final int segment;
        final double seconds;
        Hit(String session, String category, long recordedAt, String field, String snippet, int segment, double seconds) {
            this.session = session; this.category = category; this.recordedAt = recordedAt;
            this.field = field; this.snippet = snippet; this.segment = segment; this.seconds = seconds;
        }
    }
    static final class Result {
        final List<Hit> hits;
        final int unreadable;
        Result(List<Hit> hits, int unreadable) { this.hits = hits; this.unreadable = unreadable; }
    }
    private LocalSearch() {}

    static Result search(Context context, String query, String categoryId, BooleanSupplier cancelled) {
        String[] words = terms(query); List<Hit> hits = new ArrayList<>(); int unreadable = 0;
        if (words.length == 0) return new Result(hits, 0);
        for (RecordingLibrary.Entry entry : RecordingLibrary.list(context)) {
            if (cancelled.getAsBoolean() || hits.size() >= MAX_RESULTS) break;
            if (!categoryId.isEmpty() && !categoryId.equals(entry.categoryId)) continue;
            if (TextStore.latest(context, entry.id) == null) continue;
            try {
                JSONObject doc = TextStore.read(context, entry.id);
                if (doc != null) hits.addAll(matches(doc, entry, words, Math.min(MAX_PER_RECORDING, MAX_RESULTS - hits.size()), cancelled));
            } catch (Exception e) { unreadable++; }
        }
        return new Result(hits, unreadable);
    }
    static String[] terms(String query) {
        String clean = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        if (clean.length() > 80) throw new IllegalArgumentException("搜索词最多 80 个字符");
        return clean.isEmpty() ? new String[0] : clean.split("\\s+");
    }
    static List<Hit> matches(JSONObject doc, RecordingLibrary.Entry entry, String[] words, int limit, BooleanSupplier cancelled) throws Exception {
        List<Hit> found = new ArrayList<>(); JSONArray segments = doc.getJSONArray("segments");
        for (int i = 0; i < segments.length() && found.size() < limit; i++) {
            if (cancelled.getAsBoolean()) break;
            JSONObject line = segments.getJSONObject(i);
            String phrase = Transcript.speaker(doc, line.optInt("speaker", -1)) + "：" + line.optString("text");
            if (containsAll(phrase, words)) found.add(new Hit(entry.id, entry.categoryName, entry.time,
                Transcript.time(line.optDouble("start")) + " · 转写", snippet(phrase, words), i, line.optDouble("start")));
        }
        for (String[] field : new String[][]{{"summary", "AI 总结"}, {"outline", "大纲"}}) {
            if (cancelled.getAsBoolean() || found.size() >= limit) break;
            String value = doc.optString(field[0]);
            if (!value.isEmpty() && containsAll(value, words)) found.add(new Hit(entry.id, entry.categoryName,
                entry.time, field[1], snippet(value, words), -1, 0));
        }
        return found;
    }
    private static boolean containsAll(String value, String[] words) {
        String normalized = value.toLowerCase(Locale.ROOT);
        for (String word : words) if (!normalized.contains(word)) return false;
        return true;
    }
    static String snippet(String value, String[] words) {
        String flat = value.replaceAll("\\s+", " ").trim();
        if (flat.length() <= 120) return flat;
        String lower = flat.toLowerCase(Locale.ROOT); int match = Math.max(0, lower.indexOf(words[0]));
        int start = Math.min(Math.max(0, match - 35), Math.max(0, flat.length() - 120));
        if (start > 0 && Character.isLowSurrogate(flat.charAt(start))) start++;
        int end = Math.min(flat.length(), start + 120);
        if (end < flat.length() && Character.isHighSurrogate(flat.charAt(end - 1))) end--;
        return (start > 0 ? "…" : "") + flat.substring(start, end) + (end < flat.length() ? "…" : "");
    }
}
