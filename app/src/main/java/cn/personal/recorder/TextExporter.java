package cn.personal.recorder;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.pdf.PdfDocument;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.json.JSONArray;
import org.json.JSONObject;

/** User-initiated plaintext exports. Callers must disclose the destination and obtain consent. */
final class TextExporter {
    private TextExporter() {}

    static String markdown(JSONObject doc) throws Exception {
        StringBuilder out = new StringBuilder("# 录音记录\n\n");
        out.append("- 录音编号：").append(doc.optString("session")).append('\n');
        if (!doc.optBoolean("complete", false)) out.append("- 状态：部分录音\n");
        out.append("\n## 转写\n\n").append(Transcript.text(doc));
        section(out, "主题与摘要", doc.optString("summary"), "尚未生成");
        section(out, "大纲", doc.optString("outline"), "尚未生成");
        out.append("\n## 思维导图\n\n");
        String map = doc.optString("mindMap");
        if (map.isEmpty()) out.append("尚未生成\n");
        else try { appendMindMap(out, new JSONObject(map), 0); } catch (Exception e) { out.append(map).append('\n'); }
        return out.toString();
    }

    static String plainText(JSONObject doc) throws Exception {
        return markdown(doc).replace("# ", "").replace("## ", "").replace("### ", "");
    }

    /** Uses existing segment boundaries; no word-level timing is inferred. */
    static String subtitles(JSONObject doc, boolean webVtt) throws Exception {
        StringBuilder out = new StringBuilder(webVtt ? "WEBVTT\n\n" : "");
        JSONArray segments = doc.getJSONArray("segments"); int cue = 0;
        for (int i = 0; i < segments.length(); i++) {
            JSONObject line = segments.getJSONObject(i);
            String words = line.optString("text").replaceAll("[\\p{Cntrl}]+", " ").replaceAll("\\s+", " ").trim();
            if (words.isEmpty()) continue;
            double from = line.optDouble("start", Double.NaN), to = line.optDouble("end", Double.NaN);
            if (!Double.isFinite(from) || !Double.isFinite(to) || from < 0 || to <= from || to >= Long.MAX_VALUE / 1000.0) continue;
            long start = Math.round(from * 1000), end = Math.max(start + 1, Math.round(to * 1000));
            cue++; if (!webVtt) out.append(cue).append('\n');
            out.append(cueTime(start, webVtt)).append(" --> ").append(cueTime(end, webVtt)).append('\n');
            int speaker = line.optInt("speaker", -1);
            String caption = (speaker >= 0 ? Transcript.speaker(doc, speaker) + "：" : "") + words;
            caption = caption.replaceAll("[\\p{Cntrl}]+", " ").replaceAll("\\s+", " ").trim();
            if (webVtt) caption = caption.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
            out.append(caption).append("\n\n");
        }
        if (cue == 0) throw new java.io.IOException("没有可导出的有效字幕片段");
        return out.toString();
    }
    private static String cueTime(long millis, boolean webVtt) {
        long seconds = millis / 1000;
        return String.format(Locale.ROOT, "%02d:%02d:%02d%c%03d", seconds / 3600,
            seconds / 60 % 60, seconds % 60, webVtt ? '.' : ',', millis % 1000);
    }

    private static void section(StringBuilder out, String title, String value, String fallback) {
        out.append("\n## ").append(title).append("\n\n").append(value == null || value.trim().isEmpty() ? fallback : value).append('\n');
    }

    private static void appendMindMap(StringBuilder out, JSONObject node, int depth) {
        if (node == null || depth > 12) return;
        for (int i = 0; i < depth; i++) out.append("  ");
        out.append("- ").append(node.optString("name", "主题")).append('\n');
        JSONArray children = node.optJSONArray("children");
        if (children != null) for (int i = 0; i < children.length(); i++) appendMindMap(out, children.optJSONObject(i), depth + 1);
    }

    static void writeDocx(JSONObject doc, OutputStream destination) throws Exception {
        byte[] xml = wordDocument(plainText(doc)).getBytes(StandardCharsets.UTF_8);
        ZipOutputStream zip = new ZipOutputStream(destination, StandardCharsets.UTF_8);
        try {
            put(zip, "[Content_Types].xml", "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                + "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">"
                + "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>"
                + "<Default Extension=\"xml\" ContentType=\"application/xml\"/>"
                + "<Override PartName=\"/word/document.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml\"/>"
                + "</Types>");
            put(zip, "_rels/.rels", "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"word/document.xml\"/>"
                + "</Relationships>");
            ZipEntry entry = new ZipEntry("word/document.xml"); zip.putNextEntry(entry); zip.write(xml); zip.closeEntry(); zip.finish(); zip.flush();
        } finally { java.util.Arrays.fill(xml, (byte) 0); }
    }

    private static String wordDocument(String text) {
        StringBuilder xml = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>")
            .append("<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body>");
        for (String line : text.split("\\n", -1)) {
            xml.append("<w:p><w:r><w:rPr><w:rFonts w:eastAsia=\"等线\"/></w:rPr><w:t xml:space=\"preserve\">")
                .append(xmlEscape(line)).append("</w:t></w:r></w:p>");
        }
        return xml.append("<w:sectPr><w:pgSz w:w=\"11906\" w:h=\"16838\"/><w:pgMar w:top=\"1440\" w:right=\"1440\" w:bottom=\"1440\" w:left=\"1440\"/></w:sectPr></w:body></w:document>").toString();
    }

    private static String xmlEscape(String value) {
        StringBuilder out = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '&') out.append("&amp;"); else if (c == '<') out.append("&lt;"); else if (c == '>') out.append("&gt;");
            else if (c == '"') out.append("&quot;"); else if (c == '\'') out.append("&apos;");
            else if (c == '\t' || c == '\n' || c == '\r' || c >= 0x20) out.append(c);
        }
        return out.toString();
    }

    private static void put(ZipOutputStream zip, String name, String value) throws Exception {
        zip.putNextEntry(new ZipEntry(name)); zip.write(value.getBytes(StandardCharsets.UTF_8)); zip.closeEntry();
    }

    static void writePdf(Context context, JSONObject doc, OutputStream destination) throws Exception {
        PdfDocument pdf = new PdfDocument(); Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setColor(0xff20242b); paint.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL)); paint.setTextSize(11.5f);
        Paint heading = new Paint(paint); heading.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD)); heading.setTextSize(14f);
        List<String> lines = wrap(plainText(doc), paint, 510f);
        final int pageWidth = 595, pageHeight = 842; final float left = 42f, top = 46f, bottom = 48f, lineHeight = 17f;
        int pageNumber = 0, index = 0;
        try {
            while (index < lines.size() || pageNumber == 0) {
                PdfDocument.Page page = pdf.startPage(new PdfDocument.PageInfo.Builder(pageWidth, pageHeight, ++pageNumber).create());
                Canvas canvas = page.getCanvas(); float y = top;
                canvas.drawText("加密录音 · 文字与成果", left, y, heading); y += lineHeight * 1.8f;
                int limit = (int) ((pageHeight - bottom - y) / lineHeight);
                for (int i = 0; i < limit && index < lines.size(); i++, index++) {
                    canvas.drawText(lines.get(index), left, y, lines.get(index).startsWith("录音记录") ? heading : paint); y += lineHeight;
                }
                paint.setTextSize(9f); canvas.drawText("第 " + pageNumber + " 页", pageWidth - 82f, pageHeight - 22f, paint); paint.setTextSize(11.5f);
                pdf.finishPage(page);
            }
            pdf.writeTo(destination);
        } finally { pdf.close(); }
    }

    private static List<String> wrap(String text, Paint paint, float width) {
        List<String> lines = new ArrayList<>();
        for (String source : text.split("\\n", -1)) {
            if (source.isEmpty()) { lines.add(""); continue; }
            int start = 0;
            while (start < source.length()) {
                int end = start, lastFit = start;
                while (end < source.length()) {
                    int next = end + Character.charCount(source.codePointAt(end));
                    if (paint.measureText(source, start, next) > width && lastFit > start) break;
                    end = next; lastFit = end;
                    if (paint.measureText(source, start, end) > width) break;
                }
                if (lastFit == start) { end = start + Character.charCount(source.codePointAt(start)); lastFit = end; }
                lines.add(source.substring(start, lastFit)); start = lastFit;
            }
        }
        return lines;
    }
}
