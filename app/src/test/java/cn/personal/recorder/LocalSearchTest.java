package cn.personal.recorder;

import java.util.Collections;
import java.util.List;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class LocalSearchTest {
    @Test public void mixedLanguageTermsMustAppearInTheSameSegmentAndKeepSourceTime() throws Exception {
        String id = "10000000-0000-4000-8000-000000000123";
        JSONObject doc = Transcript.create(id, true);
        doc.getJSONObject("names").put("0", "李老师");
        doc.getJSONArray("segments")
            .put(new JSONObject().put("start", 8.25).put("end", 12).put("speaker", 0).put("text", "这周确认 Budget 预算"))
            .put(new JSONObject().put("start", 12).put("end", 15).put("speaker", 0).put("text", "预算另行讨论"));
        RecordingLibrary.Entry entry = new RecordingLibrary.Entry(id, "category", "会议记录", Collections.emptyList(), 1000);
        List<LocalSearch.Hit> hits = LocalSearch.matches(doc, entry, LocalSearch.terms("预算 budget"), 4, () -> false);
        assertEquals(1, hits.size()); assertEquals(0, hits.get(0).segment);
        assertEquals(8.25, hits.get(0).seconds, 0.001); assertEquals("00:00:08 · 转写", hits.get(0).field);
        assertTrue(hits.get(0).snippet.contains("李老师"));
        assertEquals("会议记录", hits.get(0).category);
    }
    @Test public void summaryCanBeFoundWithoutTranscriptMatchAndSnippetIsBounded() throws Exception {
        String id = "10000000-0000-4000-8000-000000000124";
        JSONObject doc = Transcript.create(id, true).put("summary", "项目决定：下周交付第一版；负责人张三。");
        RecordingLibrary.Entry entry = new RecordingLibrary.Entry(id, "category", "会议记录", Collections.emptyList(), 1000);
        List<LocalSearch.Hit> hits = LocalSearch.matches(doc, entry, LocalSearch.terms("交付 张三"), 4, () -> false);
        assertEquals(1, hits.size()); assertEquals("AI 总结", hits.get(0).field); assertEquals(-1, hits.get(0).segment);
        String snippet = LocalSearch.snippet("🙂".repeat(100) + "关键词" + "🙂".repeat(100), LocalSearch.terms("关键词"));
        assertTrue(snippet.contains("关键词")); assertTrue(snippet.length() <= 122);
        assertFalse(Character.isHighSurrogate(snippet.charAt(snippet.length() - 2)));
    }
}
