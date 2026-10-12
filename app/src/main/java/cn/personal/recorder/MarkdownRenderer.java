package cn.personal.recorder;

import android.graphics.Typeface;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.BackgroundColorSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.text.style.StrikethroughSpan;
import android.text.style.StyleSpan;
import android.text.style.TypefaceSpan;
import android.text.style.UnderlineSpan;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Local, non-interactive Markdown preview. AI text is never interpreted as HTML or loaded by a WebView. */
final class MarkdownRenderer {
    private static final Pattern HEADING = Pattern.compile("^\\s{0,3}(#{1,6})\\s+(.+?)\\s*#*\\s*$");
    private static final Pattern LIST = Pattern.compile("^(\\s*)([-*+]|[0-9]+[.)])\\s+(.+)$");
    private static final Pattern RULE = Pattern.compile("^\\s{0,3}(?:-{3,}|\\*{3,}|_{3,})\\s*$");
    private static final Pattern TABLE_RULE = Pattern.compile("^\\s*\\|?\\s*:?-{3,}:?\\s*(?:\\|\\s*:?-{3,}:?\\s*)+\\|?\\s*$");

    private MarkdownRenderer() {}

    static CharSequence render(String markdown, Ui ui) {
        SpannableStringBuilder out = new SpannableStringBuilder();
        if (markdown == null || markdown.isEmpty()) return out;
        String[] lines = markdown.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
        boolean code = false;
        List<String> headers = null;
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (line.trim().startsWith("```")) { code = !code; headers = null; continue; }
            if (code) {
                int from = out.length(); out.append(line); decorate(out, from, out.length(), new TypefaceSpan("monospace"));
                decorate(out, from, out.length(), new BackgroundColorSpan(ui.soft)); out.append('\n'); continue;
            }
            if (TABLE_RULE.matcher(line).matches()) continue;
            if (i + 1 < lines.length && line.indexOf('|') >= 0 && TABLE_RULE.matcher(lines[i + 1]).matches()) {
                headers = cells(line); i++; continue;
            }
            if (headers != null && line.indexOf('|') >= 0 && !line.trim().isEmpty()) {
                List<String> values = cells(line);
                for (int c = 0; c < values.size(); c++) {
                    if (c > 0) out.append("  ·  ");
                    if (c < headers.size() && !headers.get(c).isEmpty()) { appendInline(out, headers.get(c), ui); out.append("："); }
                    appendInline(out, values.get(c), ui);
                }
                out.append('\n'); continue;
            }
            headers = null;
            Matcher heading = HEADING.matcher(line);
            if (heading.matches()) {
                int from = out.length(); appendInline(out, heading.group(2), ui);
                decorate(out, from, out.length(), new StyleSpan(Typeface.BOLD));
                decorate(out, from, out.length(), new RelativeSizeSpan(heading.group(1).length() <= 2 ? 1.3f : 1.12f));
                out.append('\n'); continue;
            }
            if (RULE.matcher(line).matches()) { out.append("────────\n"); continue; }
            Matcher list = LIST.matcher(line);
            if (list.matches()) {
                int indent = Math.min(4, list.group(1).length() / 2);
                for (int j = 0; j < indent; j++) out.append("  ");
                String marker = list.group(2);
                out.append(marker.length() == 1 ? "• " : marker + " ");
                appendInline(out, list.group(3), ui); out.append('\n'); continue;
            }
            if (line.trim().startsWith(">")) {
                int from = out.length(); out.append("│ "); appendInline(out, line.trim().substring(1).trim(), ui);
                decorate(out, from, out.length(), new ForegroundColorSpan(ui.muted)); out.append('\n'); continue;
            }
            appendInline(out, line, ui); out.append('\n');
        }
        if (out.length() > 0 && out.charAt(out.length() - 1) == '\n') out.delete(out.length() - 1, out.length());
        return out;
    }

    private static List<String> cells(String row) {
        String value = row.trim();
        if (value.startsWith("|")) value = value.substring(1);
        if (value.endsWith("|")) value = value.substring(0, value.length() - 1);
        List<String> cells = new ArrayList<>();
        for (String cell : value.split("\\|", -1)) cells.add(cell.trim());
        return cells;
    }

    private static void appendInline(SpannableStringBuilder out, String source, Ui ui) {
        for (int i = 0; i < source.length();) {
            if (source.charAt(i) == '\\' && i + 1 < source.length()) { out.append(source.charAt(i + 1)); i += 2; continue; }
            if (source.charAt(i) == '[') {
                int close = source.indexOf("](", i + 1), end = close < 0 ? -1 : source.indexOf(')', close + 2);
                if (close > i + 1 && end > close + 2) {
                    int from = out.length(); appendInline(out, source.substring(i + 1, close), ui);
                    decorate(out, from, out.length(), new ForegroundColorSpan(ui.accent));
                    decorate(out, from, out.length(), new UnderlineSpan());
                    out.append(" (").append(source.substring(close + 2, end)).append(')'); i = end + 1; continue;
                }
            }
            String marker = source.startsWith("**", i) || source.startsWith("__", i) || source.startsWith("~~", i)
                ? source.substring(i, i + 2) : source.charAt(i) == '*' || source.charAt(i) == '_' || source.charAt(i) == '`'
                ? source.substring(i, i + 1) : "";
            if (!marker.isEmpty()) {
                int end = source.indexOf(marker, i + marker.length());
                if (end > i + marker.length()) {
                    int from = out.length();
                    if ("`".equals(marker)) out.append(source.substring(i + 1, end));
                    else appendInline(out, source.substring(i + marker.length(), end), ui);
                    Object style = "**".equals(marker) || "__".equals(marker) ? new StyleSpan(Typeface.BOLD)
                        : "*".equals(marker) || "_".equals(marker) ? new StyleSpan(Typeface.ITALIC)
                        : "~~".equals(marker) ? new StrikethroughSpan() : new TypefaceSpan("monospace");
                    decorate(out, from, out.length(), style);
                    if ("`".equals(marker)) decorate(out, from, out.length(), new BackgroundColorSpan(ui.soft));
                    i = end + marker.length(); continue;
                }
            }
            out.append(source.charAt(i++));
        }
    }

    private static void decorate(SpannableStringBuilder out, int from, int to, Object span) {
        if (to > from) out.setSpan(span, from, to, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }
}
