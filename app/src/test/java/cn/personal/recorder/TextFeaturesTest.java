package cn.personal.recorder;

import java.nio.charset.StandardCharsets;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import javax.crypto.AEADBadTagException;
import javax.crypto.spec.SecretKeySpec;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import static org.junit.Assert.*;

public class TextFeaturesTest {
    private final SecretKeySpec key = new SecretKeySpec(new byte[32], "AES");
    @Test public void textAndSummaryAreAuthenticatedCiphertext() throws Exception {
        byte[] text = "发言人 1：测试文字\n总结：测试结论".getBytes(StandardCharsets.UTF_8);
        byte[] blob = TextCrypto.encrypt(text, key, "record.enc");
        assertFalse(new String(blob, StandardCharsets.UTF_8).contains("测试文字"));
        assertArrayEquals(text, TextCrypto.decrypt(blob, key, "record.enc"));
        assertFalse(java.util.Arrays.equals(blob, TextCrypto.encrypt(text, key, "record.enc")));
    }
    @Test public void tamperingAndRenamingCannotDecrypt() throws Exception {
        byte[] blob = TextCrypto.encrypt("文字".getBytes(StandardCharsets.UTF_8), key, "record.enc");
        try { TextCrypto.decrypt(blob, key, "renamed.enc"); fail(); } catch (AEADBadTagException expected) {}
        blob[blob.length - 1] ^= 1;
        try { TextCrypto.decrypt(blob, key, "record.enc"); fail(); } catch (AEADBadTagException expected) {}
    }
    @Test public void wrongKeyAndAudioEnvelopeAreRejected() throws Exception {
        byte[] blob = TextCrypto.encrypt(new byte[10], key, "record.enc"); byte[] wrong = new byte[32]; wrong[0] = 1;
        try { TextCrypto.decrypt(blob, new SecretKeySpec(wrong, "AES"), "record.enc"); fail(); } catch (AEADBadTagException expected) {}
        blob[1] = 'R'; try { TextCrypto.decrypt(blob, key, "record.enc"); fail(); } catch (java.io.IOException expected) {}
    }
    @Test public void timestampsSpeakersAndOverlapReachText() throws Exception {
        JSONObject doc = Transcript.create("id", true); doc.getJSONObject("names").put("0", "小王");
        doc.getJSONArray("segments").put(new JSONObject().put("start", 61).put("end", 63).put("speaker", 0).put("text", "下周提交").put("overlap", true));
        String text = Transcript.text(doc); assertTrue(text.contains("00:01:01")); assertTrue(text.contains("小王")); assertTrue(text.contains("重叠语音")); assertTrue(text.contains("下周提交"));
    }
    @Test public void markdownExportKeepsTimestampsSpeakersAndGeneratedResults() throws Exception {
        JSONObject doc = Transcript.create("record-id", true); doc.getJSONObject("names").put("0", "小王");
        doc.getJSONArray("segments").put(new JSONObject().put("start", 3).put("end", 4).put("speaker", 0).put("text", "决定周五交付"));
        doc.put("summary", "主题\n待办：负责人小王，截止周五").put("outline", "交付计划").put("mindMap", "{\"name\":\"会议\",\"children\":[]}");
        String markdown = TextExporter.markdown(doc);
        assertTrue(markdown.contains("00:00:03")); assertTrue(markdown.contains("小王")); assertTrue(markdown.contains("待办"));
        assertTrue(markdown.contains("## 大纲")); assertTrue(markdown.contains("- 会议"));
    }
    @Test public void wordExportIsAValidPackageAndEscapesXml() throws Exception {
        JSONObject doc = Transcript.create("id", true); doc.getJSONArray("segments")
            .put(new JSONObject().put("start", 0).put("end", 1).put("speaker", 0).put("text", "A < B & 中文"));
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(); TextExporter.writeDocx(doc, bytes);
        boolean contentTypes = false, rootRels = false, document = false; String xml = "";
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes.toByteArray()), StandardCharsets.UTF_8)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if ("[Content_Types].xml".equals(entry.getName())) contentTypes = true;
                if ("_rels/.rels".equals(entry.getName())) rootRels = true;
                if ("word/document.xml".equals(entry.getName())) { document = true; xml = new String(zip.readAllBytes(), StandardCharsets.UTF_8); }
            }
        }
        assertTrue(contentTypes); assertTrue(rootRels); assertTrue(document); assertTrue(xml.contains("A &lt; B &amp; 中文"));
    }
    @Test public void splittingPreservesAllTextAndUnicode() {
        String text = ("中文🙂文字\n").repeat(7000); List<String> parts = Transcript.parts(text, 20000);
        assertEquals(text, String.join("", parts)); for (String p : parts) assertFalse(Character.isHighSurrogate(p.charAt(p.length() - 1)));
    }
    @Test public void longSummaryUsesEveryPartThenMerges() throws Exception {
        String text = "开始\n" + "内容\n".repeat(15000) + "最后的决定"; List<String> inputs = new ArrayList<>();
        String summary = DeepSeekClient.summarize(text, "synthetic-key-for-test", () -> false, s -> {}, (body, credential, cancelled) -> {
            assertEquals("deepseek-flash", body.getString("model")); assertEquals("disabled", body.getJSONObject("thinking").getString("type"));
            JSONArray messages = body.getJSONArray("messages"); assertTrue(messages.getJSONObject(1).get("content") instanceof String);
            assertFalse(body.toString().contains("input_audio")); assertFalse(body.toString().contains("file_data")); assertFalse(body.toString().contains(credential));
            String input = messages.getJSONObject(1).getString("content"); inputs.add(input); return "纪要 " + inputs.size();
        });
        List<String> expected = Transcript.parts(text, 20000); assertEquals(expected, inputs.subList(0, expected.size()));
        assertEquals(expected.size() + 1, inputs.size()); assertTrue(summary.contains("纪要"));
    }
    @Test public void cancelledSummaryNeverStartsNetwork() throws Exception {
        try { DeepSeekClient.summarize("文字", "synthetic-key", () -> true, s -> {}, (r, k, c) -> { fail(); return ""; }); fail(); }
        catch (InterruptedException expected) {}
    }
    @Test public void outlineAndMindMapUseTextOnlyPromptsAndValidateTree() throws Exception {
        String outline = DeepSeekClient.generate("会议转写", "synthetic-key", () -> false, s -> {}, (body, credential, cancelled) -> {
            assertEquals("synthetic-key", credential); assertEquals("会议转写", body.getJSONArray("messages").getJSONObject(1).getString("content"));
            assertTrue(body.getJSONArray("messages").getJSONObject(0).getString("content").contains("层级大纲"));
            return "## 讨论\n- 决定";
        }, "meeting", "outline");
        assertTrue(outline.contains("决定"));
        String map = DeepSeekClient.generate("课堂转写", "synthetic-key", () -> false, s -> {}, (body, credential, cancelled) -> {
            String instruction = body.getJSONArray("messages").getJSONObject(0).getString("content");
            assertTrue(instruction.contains("严格有效的 JSON")); assertFalse(body.toString().contains("input_audio"));
            return "{\"name\":\"课程\",\"children\":[{\"name\":\"概念\",\"children\":[]}]}";
        }, "classroom", "mindmap");
        assertEquals("课程", new JSONObject(map).getString("name"));
        try {
            DeepSeekClient.generate("课堂转写", "synthetic-key", () -> false, s -> {}, (body, credential, cancelled) -> "not json", "classroom", "mindmap");
            fail("invalid mind map output should not be stored");
        } catch (java.io.IOException expected) { assertTrue(expected.getMessage().contains("格式无效")); }
    }
    @Test public void summaryPromptNamesRequiredDecisionDisputeAndTodoFields() throws Exception {
        DeepSeekClient.generate("会议文字", "synthetic-key", () -> false, s -> {}, (body, credential, cancelled) -> {
            String prompt = body.getJSONArray("messages").getJSONObject(0).getString("content");
            assertTrue(prompt.contains("主题")); assertTrue(prompt.contains("结论与决定")); assertTrue(prompt.contains("争议点"));
            assertTrue(prompt.contains("负责人")); assertTrue(prompt.contains("截止时间")); assertTrue(prompt.contains("来源时间"));
            return "纪要";
        }, "meeting", "summary");
    }
    @Test public void speakerIdsRemainStableAcrossBatches() {
        try (SpeakerRegistry speakers = new SpeakerRegistry()) {
            assertEquals(0, speakers.assign(new float[]{1, 0, 0})); assertEquals(1, speakers.assign(new float[]{0, 1, 0}));
            assertEquals(0, speakers.assign(new float[]{5, 0.1f, 0})); assertEquals(1, speakers.assign(new float[]{0.1f, 8, 0}));
        }
    }
    @Test public void invalidVoiceVectorsAreRejected() {
        try (SpeakerRegistry speakers = new SpeakerRegistry()) {
            try { speakers.assign(new float[]{0, 0}); fail(); } catch (IllegalArgumentException expected) {}
            try { speakers.assign(new float[]{Float.NaN, 1}); fail(); } catch (IllegalArgumentException expected) {}
        }
    }
    @Test public void knownSpeakerCountBoundsNoisyBatchIdentities() {
        try (SpeakerRegistry speakers = new SpeakerRegistry(2)) {
            assertEquals(0, speakers.assign(new float[]{1, 0, 0})); assertEquals(1, speakers.assign(new float[]{0, 1, 0}));
            assertEquals(0, speakers.assign(new float[]{0.2f, 0.1f, 1}));
        }
    }
}
