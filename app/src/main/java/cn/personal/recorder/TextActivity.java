package cn.personal.recorder;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.media.MediaPlayer;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextPaint;
import android.text.method.LinkMovementMethod;
import android.text.style.BackgroundColorSpan;
import android.text.style.ClickableSpan;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.io.File;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONArray;
import org.json.JSONObject;

/** An unlocked page; only the explicit summary action authorizes a text request to DeepSeek. */
public final class TextActivity extends Activity {
    private Ui ui;
    private String session, revision = "";
    private JSONObject document;
    private TextView status, transcript, summary, outline, playbackStatus;
    private LinearLayout mindMap;
    private Button extract, summarize, makeOutline, makeMindMap;
    private boolean loading, changing;
    private boolean categoryAiAllowed;
    private String categoryName = "未分类";
    private MediaPlayer player;
    private RecordingSource playbackSource;
    private int playbackGeneration;
    private ScrollView pageScroll;
    private int focusSegment = -1, focusOffset = -1, activePlaybackSegment = -1, highlightOffset = -1;
    private String focusField = "";
    private boolean focusConsumed;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable refresh = new Runnable() {
        @Override public void run() { update(); handler.postDelayed(this, 1000); }
    };
    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved); session = getIntent().getStringExtra("session"); if (!TextStore.validSession(session)) { finish(); return; }
        focusSegment = getIntent().getIntExtra("focus-segment", -1);
        focusField = getIntent().getStringExtra("focus-field"); if (focusField == null) focusField = "";
        refreshCategoryPolicy();
        ui = new Ui(this); LinearLayout root = ui.screen(); pageScroll = (ScrollView) root.getParent();
        LinearLayout heading = ui.row(); heading.addView(ui.button("返回", this::finish, false));
        TextView title = ui.title("录音详情", 24); title.setPadding(ui.dp(14), 0, 0, 0); heading.addView(title); root.addView(heading);
        root.addView(ui.label("分类：" + categoryName + " · 发言人识别在手机本地完成", 13, ui.muted));
        LinearLayout actions = ui.card(root); actions.addView(ui.title("录音文字", 18));
        status = ui.label("读取加密记录…", 13, ui.muted); actions.addView(status);
        extract = ui.button("本地提取文字", this::extract, true); actions.addView(extract, ui.spaced());
        actions.addView(ui.label("录音时可在首页预览实时文字；停止后可在此进行更完整的离线转写与发言人区分。当前不支持导入外部音频。", 12, ui.muted));
        ui.action(actions, "取消当前处理", "保留已有结果；已发送的 AI 请求仍可能计费", () -> { if (AnalysisService.busy) startService(new Intent(this, AnalysisService.class).setAction(AnalysisService.CANCEL)); });
        ui.action(actions, "文字与 AI 设置", "离线模型、DeepSeek Key", () -> startActivity(new Intent(this, SettingsActivity.class)));
        LinearLayout result = ui.card(root); result.addView(ui.title("录音文字", 18));
        result.addView(ui.label("发言人编号为估计结果，重叠说话与噪声可能造成误分，请核对。", 12, ui.muted));
        ui.action(result, "修改发言人称呼", "可改为你确认的姓名；修改后需重新总结", this::rename);
        ui.action(result, "修正转写文字", "按时间选择片段修改；修改后需重新总结", this::editSegment);
        playbackStatus = ui.label("点击蓝色时间点回听；播放时高亮当前片段", 12, ui.muted); playbackStatus.setOnClickListener(v -> stopPlayback()); result.addView(playbackStatus);
        transcript = ui.label("尚未提取文字", 15, ui.ink); transcript.setTextIsSelectable(false);
        transcript.setMovementMethod(LinkMovementMethod.getInstance()); transcript.setHighlightColor(Color.TRANSPARENT); result.addView(transcript);
        root.addView(ui.label("内容成果", 21, ui.ink));
        root.addView(ui.label("根据当前转写生成；结果在手机加密保存。外部 AI 只会在你确认后收到文字。", 12, ui.muted));
        LinearLayout notes = ui.card(root); notes.addView(ui.title("AI 总结", 18));
        summarize = ui.button("生成 AI 总结", () -> generate("summary"), false); notes.addView(summarize, ui.spaced());
        summary = ui.label("尚未生成总结", 15, ui.ink); summary.setTextIsSelectable(true); notes.addView(summary);
        LinearLayout outlineCard = ui.card(root); outlineCard.addView(ui.title("大纲", 18));
        makeOutline = ui.button("生成大纲", () -> generate("outline"), false); outlineCard.addView(makeOutline, ui.spaced());
        outline = ui.label("尚未生成大纲", 15, ui.ink); outline.setTextIsSelectable(true); outlineCard.addView(outline);
        LinearLayout mapCard = ui.card(root); mapCard.addView(ui.title("思维导图", 18));
        makeMindMap = ui.button("生成思维导图", () -> generate("mindmap"), false); mapCard.addView(makeMindMap, ui.spaced());
        mindMap = ui.column(0); mapCard.addView(mindMap);
        LinearLayout exports = ui.card(root); exports.addView(ui.title("导出与分享", 18));
        exports.addView(ui.label("保留时间戳、发言人和当前成果。导出文件为明文，请保存到受控位置。", 12, ui.muted));
        ui.action(exports, "导出 Markdown", "适合归档、编辑和电脑端查看", () -> export(31));
        ui.action(exports, "导出 Word", "生成 .docx 文档", () -> export(32));
        ui.action(exports, "导出 PDF", "生成便于阅读和打印的文档", () -> export(33));
        ui.action(exports, "导出 SRT 字幕", "保留分段时间与发言人标注", () -> export(34));
        ui.action(exports, "导出 VTT 字幕", "用于网页播放器和视频编辑工具", () -> export(35));
        ui.action(exports, "分享至 Notion / 飞书", "通过系统分享面板发送文字；接收应用可能上传内容", this::share);
        update();
    }
    @Override protected void onResume() { super.onResume(); refreshCategoryPolicy(); handler.removeCallbacks(refresh); handler.post(refresh); }
    @Override protected void onPause() { handler.removeCallbacks(refresh); stopPlayback(); super.onPause(); }
    @Override protected void onDestroy() { stopPlayback(); io.shutdown(); super.onDestroy(); }
    private void update() {
        boolean liveBusy = LiveTranscriber.isBusyFor(session);
        boolean busy = AnalysisService.busy || liveBusy || changing || loading; boolean aiAllowed = categoryAiAllowed;
        boolean canGenerate = !busy && document != null && aiAllowed;
        extract.setEnabled(!busy && !RecordingService.active); summarize.setEnabled(canGenerate); makeOutline.setEnabled(canGenerate); makeMindMap.setEnabled(canGenerate);
        extract.setAlpha(extract.isEnabled() ? 1f : 0.45f); summarize.setAlpha(summarize.isEnabled() ? 1f : 0.45f);
        makeOutline.setAlpha(makeOutline.isEnabled() ? 1f : 0.45f); makeMindMap.setAlpha(makeMindMap.isEnabled() ? 1f : 0.45f);
        if (liveBusy) status.setText("正在加密保存实时文字，请稍后再进行完整转写或总结");
        else if (AnalysisService.busy) status.setText(AnalysisService.session.isEmpty() || session.equals(AnalysisService.session) ? AnalysisService.state : "正在处理另一场录音");
        else status.setText(!aiAllowed ? "本分类禁止向外部 AI 发送文字 · 可在设置 → 分类管理中更改" : (!AnalysisService.state.isEmpty() && (session.equals(AnalysisService.session) || AnalysisService.session.isEmpty()) ? AnalysisService.state : "文字与总结在手机加密保存，按云端设置同步"));
        File file = TextStore.latest(this, session); String name = file == null ? "none" : file.getName();
        if (!loading && !changing && !name.equals(revision) && !io.isShutdown()) {
            loading = true; io.execute(() -> {
                try { JSONObject doc = TextStore.read(this, session); handler.post(() -> { loading = false; if (isDestroyed()) return; document = doc; revision = name; display(); }); }
                catch (Exception e) { handler.post(() -> { loading = false; revision = name; toast("无法解密文字记录，请检查密钥和文件"); }); }
            });
        }
        updatePlayback();
    }
    private void refreshCategoryPolicy() {
        categoryName = CategoryStore.name(this, CategoryStore.categoryFor(this, session)); categoryAiAllowed = CategoryStore.aiAllowed(this, session);
    }
    private void display() {
        try {
            transcript.setText(document == null ? "尚未提取文字" : transcriptSpans(document));
            String value = document == null ? "" : document.optString("summary"); summary.setText(value.isEmpty() ? "尚未生成总结" : value);
            String outlineText = document == null ? "" : document.optString("outline"); outline.setText(outlineText.isEmpty() ? "尚未生成大纲" : outlineText);
            renderMindMap(document == null ? "" : document.optString("mindMap"));
            if (!focusConsumed && document != null) {
                focusConsumed = true;
                View target = focusSegment >= 0 ? transcript : "AI 总结".equals(focusField) ? summary
                    : "大纲".equals(focusField) ? outline : "思维导图".equals(focusField) ? mindMap : transcript;
                target.post(() -> {
                    if (isDestroyed() || target.getHeight() == 0) return;
                    int[] targetAt = new int[2], scrollAt = new int[2];
                    target.getLocationOnScreen(targetAt); pageScroll.getLocationOnScreen(scrollAt);
                    int lineTop = focusSegment >= 0 && focusOffset >= 0 && transcript.getLayout() != null
                        ? transcript.getLayout().getLineTop(transcript.getLayout().getLineForOffset(focusOffset)) : 0;
                    pageScroll.smoothScrollTo(0, Math.max(0, pageScroll.getScrollY() + targetAt[1] - scrollAt[1] + lineTop - ui.dp(90)));
                });
            }
        } catch (Exception e) { toast("文字格式不完整，请重新提取"); }
    }
    private CharSequence transcriptSpans(JSONObject doc) throws Exception {
        SpannableStringBuilder text = new SpannableStringBuilder();
        if (!doc.getBoolean("complete")) text.append("部分录音：只包含已保存片段\n\n");
        if (doc.optBoolean("previewSkipped")) text.append("实时预览漏掉了部分片段；可重新进行完整转写。\n\n");
        JSONArray lines = doc.getJSONArray("segments");
        focusOffset = -1; highlightOffset = -1;
        for (int i = 0; i < lines.length(); i++) {
            JSONObject line = lines.getJSONObject(i); String time = Transcript.time(line.getDouble("start")); int begin = text.length();
            text.append(time);
            double seconds = line.getDouble("start");
            text.setSpan(new ClickableSpan() {
                @Override public void onClick(View widget) { playFrom(seconds); }
                @Override public void updateDrawState(TextPaint paint) { paint.setColor(ui.accent); paint.setUnderlineText(false); }
            }, begin, text.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            text.append("–").append(Transcript.time(line.getDouble("end"))).append("  ")
                .append(Transcript.speaker(doc, line.getInt("speaker")));
            if (line.optBoolean("overlap")) text.append("（重叠语音，归属待核对）");
            text.append("：").append(line.getString("text")).append('\n');
            if (i == focusSegment) focusOffset = begin;
            if (i == (player != null ? activePlaybackSegment : focusSegment)) {
                highlightOffset = begin;
                int color = player != null ? (ui.accent & 0x00ffffff) | 0x44000000 : ui.soft;
                text.setSpan(new BackgroundColorSpan(color), begin, text.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
        }
        return text.length() == 0 ? "尚未提取文字" : text;
    }
    private void renderMindMap(String serialized) {
        mindMap.removeAllViews();
        if (serialized == null || serialized.trim().isEmpty()) { mindMap.addView(ui.label("尚未生成思维导图", 14, ui.muted)); return; }
        try { addMindMapNode(mindMap, new JSONObject(serialized), 0, new int[]{0}); }
        catch (Exception e) { mindMap.addView(ui.label("思维导图暂时无法显示，请重新生成。", 14, ui.muted)); }
    }
    private void addMindMapNode(LinearLayout parent, JSONObject node, int depth, int[] count) throws Exception {
        if (++count[0] > 160) return;
        String name = node.optString("name", "主题");
        LinearLayout item = ui.row(); item.setGravity(Gravity.CENTER_VERTICAL); item.setPadding(ui.dp(10 + Math.min(depth, 5) * 16), ui.dp(5), ui.dp(8), ui.dp(5));
        View dot = new View(this); dot.setBackground(ui.shape(depth == 0 ? ui.accent : ui.divider, 8));
        LinearLayout.LayoutParams dotParams = new LinearLayout.LayoutParams(ui.dp(depth == 0 ? 10 : 7), ui.dp(depth == 0 ? 10 : 7)); dotParams.rightMargin = ui.dp(10); item.addView(dot, dotParams);
        TextView label = depth == 0 ? ui.title(name, 16) : ui.label(name, 14, ui.ink); label.setPadding(0, ui.dp(4), 0, ui.dp(4));
        item.addView(label, new LinearLayout.LayoutParams(0, -2, 1));
        if (depth == 0) item.setBackground(ui.shape(ui.soft, 14));
        LinearLayout.LayoutParams itemParams = new LinearLayout.LayoutParams(-1, -2); itemParams.topMargin = ui.dp(2); itemParams.bottomMargin = ui.dp(2); parent.addView(item, itemParams);
        org.json.JSONArray children = node.optJSONArray("children");
        if (children != null) for (int i = 0; i < children.length(); i++) addMindMapNode(parent, children.getJSONObject(i), depth + 1, count);
    }
    private boolean editable() { if (AnalysisService.busy || LiveTranscriber.isBusyFor(session) || changing || loading) { toast("请等待当前处理完成"); return false; } return true; }
    private void start(String action) {
        start(action, -1);
    }
    private void start(String action, int speakerCount) {
        if (!editable()) return;
        try { startForegroundService(new Intent(this, AnalysisService.class).setAction(action).putExtra("session", session).putExtra("speaker-count", speakerCount)); }
        catch (Exception e) { toast("系统未允许启动处理，请重新打开此页面"); }
    }
    private void extract() {
        if (!editable()) return; if (RecordingService.active) { toast("请先停止录音"); return; }
        if (!ModelManager.ready(this)) { toast("请先在设置中下载或导入离线模型"); startActivity(new Intent(this, SettingsActivity.class)); return; }
        EditText people = input(""); people.setHint("人数（可选，1～20；留空自动判断）"); people.setSingleLine(true); people.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle(document == null ? "本地提取录音文字" : "重新提取录音文字？")
            .setMessage("在手机上识别文字与发言人，音频不会发送给 AI。处理时会显示通知，可熄屏等待。已有修正和总结会在成功完成后被新转写替换，失败时保留旧结果。")
            .setView(people).setNegativeButton("取消", null).setPositiveButton("开始提取", null).create();
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            int count = -1; String value = people.getText().toString().trim();
            if (!value.isEmpty()) { try { count = Integer.parseInt(value); } catch (NumberFormatException e) { count = 0; } if (count < 1 || count > 20) { toast("人数请填 1～20，或留空自动判断"); return; } }
            dialog.dismiss(); start(AnalysisService.TRANSCRIBE, count);
        })); dialog.show();
    }
    private void generate(String kind) {
        if (!editable() || document == null) return;
        if (!CategoryStore.aiAllowed(this, session)) { toast("本分类未允许 DeepSeek 读取文字；可在设置 → 分类管理中明确开启"); startActivity(new Intent(this, CategoryActivity.class)); return; }
        if (!new Vault(this).aiConfigured()) { toast("请先在设置中填写 DeepSeek Key"); startActivity(new Intent(this, SettingsActivity.class)); return; }
        int parts;
        try { parts = Transcript.parts(Transcript.text(document), 20000).size(); } catch (Exception e) { toast("请先提取有效的录音文字"); return; }
        String action = "outline".equals(kind) ? AnalysisService.OUTLINE : "mindmap".equals(kind) ? AnalysisService.MINDMAP : AnalysisService.SUMMARY;
        String resultName = "outline".equals(kind) ? "大纲" : "mindmap".equals(kind) ? "思维导图" : "AI 总结";
        new AlertDialog.Builder(this).setTitle("发送文字并生成" + resultName)
            .setMessage("当前分类：" + CategoryStore.name(this, CategoryStore.categoryFor(this, session)) + "。仅向 DeepSeek V4.1 Flash 发送本场转写文字、发言人标注和时间，不上传音频或录音密钥。HTTPS 加密传输，但服务商会读取文字用于整理；请求按你的 DeepSeek 账户计费。\n\n本场约 " + parts + " 段输入，长文本还会发起合并请求。结果在手机加密保存，按云端设置同步。")
            .setNegativeButton("取消", null).setPositiveButton("确认并生成", (d, w) -> start(action)).show();
    }
    private void rename() {
        if (!editable() || document == null) return;
        try {
            java.util.SortedSet<Integer> ids = new java.util.TreeSet<>(); JSONArray lines = document.getJSONArray("segments");
            for (int i = 0; i < lines.length(); i++) ids.add(lines.getJSONObject(i).getInt("speaker"));
            Integer[] speakers = ids.toArray(new Integer[0]); String[] names = new String[speakers.length];
            for (int i = 0; i < names.length; i++) names[i] = Transcript.speaker(document, speakers[i]);
            new AlertDialog.Builder(this).setTitle("选择发言人").setItems(names, (d, index) -> {
                EditText field = input(names[index]); field.setSingleLine(true); field.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(40)});
                new AlertDialog.Builder(this).setTitle("发言人称呼").setView(field).setNegativeButton("取消", null).setPositiveButton("保存", (dialog, which) -> {
                    String name = field.getText().toString().trim(); if (name.isEmpty() || !editable()) return;
                    try { JSONObject doc = new JSONObject(document.toString()); doc.getJSONObject("names").put(speakers[index].toString(), name); saveEdit(doc); } catch (Exception e) { toast("保存失败"); }
                }).show();
            }).setNegativeButton("关闭", null).show();
        } catch (Exception e) { toast("没有可修改的发言人"); }
    }
    private EditText input(String value) { EditText field = new EditText(this); field.setText(value); field.setTextColor(ui.ink); field.setPadding(ui.dp(20), ui.dp(12), ui.dp(20), ui.dp(12)); field.setSaveEnabled(false); return field; }
    private void editSegment() {
        if (!editable() || document == null) return;
        try {
            JSONArray lines = document.getJSONArray("segments"); String[] labels = new String[lines.length()];
            for (int i = 0; i < lines.length(); i++) { JSONObject line = lines.getJSONObject(i); String text = line.getString("text"); labels[i] = Transcript.time(line.getDouble("start")) + " " + Transcript.speaker(document, line.getInt("speaker")) + "：" + text.substring(0, Math.min(text.length(), 40)); }
            new AlertDialog.Builder(this).setTitle("选择需要修正的片段").setItems(labels, (d, index) -> {
                try {
                    EditText field = input(lines.getJSONObject(index).getString("text")); field.setMinLines(3); field.setMaxLines(8); field.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(8000)});
                    new AlertDialog.Builder(this).setTitle(labels[index]).setView(field).setNegativeButton("取消", null).setPositiveButton("保存", (dialog, which) -> {
                        if (!editable()) return; try { JSONObject doc = new JSONObject(document.toString()); doc.getJSONArray("segments").getJSONObject(index).put("text", field.getText().toString()); saveEdit(doc); } catch (Exception e) { toast("保存失败"); }
                    }).show();
                } catch (Exception e) { toast("无法读取片段"); }
            }).setNegativeButton("关闭", null).show();
        } catch (Exception e) { toast("没有可修改的文字"); }
    }
    private void saveEdit(JSONObject doc) throws Exception {
        doc.put("summary", "").put("outline", "").put("mindMap", "").remove("summaryModel");
        doc.remove("summaryAt"); doc.remove("outlineModel"); doc.remove("outlineAt"); doc.remove("mindMapModel"); doc.remove("mindMapAt"); changing = true;
        io.execute(() -> { try { TextStore.save(this, doc); handler.post(() -> { changing = false; revision = ""; update(); toast("已加密保存，旧 AI 成果已清除，可重新生成"); }); }
            catch (Exception e) { handler.post(() -> { changing = false; toast("保存失败，原记录保留"); }); } });
    }
    private void export(int format) {
        if (!editable() || document == null) return;
        boolean subtitle = format == 34 || format == 35;
        if (subtitle) try { TextExporter.subtitles(document, format == 35); }
        catch (Exception e) { toast("尚无有效字幕片段，请先提取或修正转写文字"); return; }
        String title = format == 31 ? "Markdown" : format == 32 ? "Word" : format == 33 ? "PDF" : format == 34 ? "SRT 字幕" : "VTT 字幕";
        String subtitleNotice = subtitle && (!document.optBoolean("complete", false) || document.optBoolean("previewSkipped"))
            ? "\n\n这份文字来自部分录音或实时预览有漏段，字幕也会缺少相应内容；建议先运行完整转写。" : "";
        new AlertDialog.Builder(this).setTitle("导出" + title)
            .setMessage(subtitle ? "字幕按已有转写片段的开始和结束时间生成，可能包含识别误差、发言人误分和较长片段；不推测逐字时间。导出文件是未加密明文，请核对内容并保存到受控位置。"
                + subtitleNotice : "导出文件包含转写、发言人和已生成成果，且为未加密明文。请只保存到你控制的位置。")
            .setNegativeButton("取消", null).setPositiveButton("选择保存位置", (d, w) -> {
                String extension = format == 31 ? ".md" : format == 32 ? ".docx" : format == 33 ? ".pdf" : format == 34 ? ".srt" : ".vtt";
                String mime = format == 31 ? "text/markdown" : format == 32 ? "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
                    : format == 33 ? "application/pdf" : "text/plain";
                startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                    .setType(mime).putExtra(Intent.EXTRA_TITLE, "录音文字-" + session.substring(0, 8) + extension), format);
            }).show();
    }
    private void share() {
        if (!editable() || document == null) return;
        new AlertDialog.Builder(this).setTitle("分享明文文字？")
            .setMessage("将转写、发言人、时间戳和已有成果发送到你选择的应用。Notion、飞书或其他接收应用可能将内容上传到各自云端。")
            .setNegativeButton("取消", null).setPositiveButton("选择应用", (d, w) -> io.execute(() -> {
                try {
                    JSONObject doc = TextStore.read(this, session); String contents = TextExporter.markdown(doc);
                    if (contents.length() > 100000) { handler.post(() -> toast("内容较长，请先导出文件再导入目标应用")); return; }
                    handler.post(() -> {
                        if (isDestroyed()) return;
                        Intent send = new Intent(Intent.ACTION_SEND).setType("text/plain")
                            .putExtra(Intent.EXTRA_SUBJECT, "录音文字-" + session.substring(0, 8))
                            .putExtra(Intent.EXTRA_TEXT, contents);
                        startActivity(Intent.createChooser(send, "分享录音文字"));
                    });
                } catch (Exception e) { handler.post(() -> toast("无法读取加密文字记录")); }
            })).show();
    }
    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data); if ((request < 31 || request > 35) || result != RESULT_OK || data == null || data.getData() == null) return;
        io.execute(() -> {
            byte[] text = null;
            try {
                JSONObject doc = TextStore.read(this, session); if (doc == null) throw new java.io.IOException();
                try (OutputStream out = getContentResolver().openOutputStream(data.getData(), "wt")) {
                    if (out == null) throw new java.io.IOException();
                    if (request == 31) { text = TextExporter.markdown(doc).getBytes(StandardCharsets.UTF_8); out.write(text); }
                    else if (request == 32) TextExporter.writeDocx(doc, out);
                    else if (request == 33) TextExporter.writePdf(this, doc, out);
                    else { text = TextExporter.subtitles(doc, request == 35).getBytes(StandardCharsets.UTF_8); out.write(text); }
                    out.flush();
                }
                handler.post(() -> toast("文字与成果已导出"));
            } catch (Exception e) { handler.post(() -> toast("导出失败，目标可能留有部分文件，请删除后重试")); }
            finally { if (text != null) Arrays.fill(text, (byte) 0); }
        });
    }
    private void playFrom(double seconds) {
        if (RecordingService.active || AnalysisService.transcribing) { toast("录音或转写处理中，暂不能回放"); return; }
        stopPlayback(); MainActivity.playbackActive = true; int generation = playbackGeneration; playbackStatus.setText("正在解密并定位录音… 点击停止");
        io.execute(() -> {
            RecordingSource source = null;
            try {
                RecordingLibrary.Entry entry = null;
                for (RecordingLibrary.Entry item : RecordingLibrary.list(this)) if (item.id.equals(session)) { entry = item; break; }
                if (entry == null || entry.chunks.length == 0) throw new java.io.IOException("本地音频已清理");
                source = new RecordingSource(entry.chunks, new Vault(this).recordingKey());
                RecordingSource readySource = source;
                handler.post(() -> {
                    if (isDestroyed() || generation != playbackGeneration) { readySource.close(); return; }
                    try {
                        playbackSource = readySource; player = new MediaPlayer(); player.setDataSource(readySource);
                        player.setOnPreparedListener(media -> {
                            if (generation != playbackGeneration) return;
                            media.seekTo((int) Math.min(Integer.MAX_VALUE, seconds * 1000)); media.start();
                            playbackStatus.setText("正在回听 " + Transcript.time(seconds) + " · 点击停止");
                            updatePlayback();
                        });
                        player.setOnCompletionListener(media -> stopPlayback());
                        player.setOnErrorListener((media, what, extra) -> { stopPlayback(); toast("回放失败，请检查本地音频"); return true; }); player.prepareAsync();
                    } catch (Exception e) { stopPlayback(); toast("无法播放这段录音"); }
                });
            } catch (Exception e) {
                if (source != null) source.close(); String message = e.getMessage();
                handler.post(() -> { if (generation == playbackGeneration) { stopPlayback(); toast(message == null ? "无法读取本地音频" : message); } });
            }
        });
    }
    private void updatePlayback() {
        if (player == null || document == null) return;
        try {
            if (!player.isPlaying()) return;
            double position = player.getCurrentPosition() / 1000.0;
            playbackStatus.setText("正在回听 " + Transcript.time(position) + " · 点击停止");
            int segment = Transcript.segmentAt(document, position);
            if (segment == activePlaybackSegment) return;
            activePlaybackSegment = segment;
            transcript.setText(transcriptSpans(document));
            if (segment >= 0) transcript.post(this::followPlaybackSegment);
        } catch (Exception e) { stopPlayback(); }
    }
    private void followPlaybackSegment() {
        if (activePlaybackSegment < 0 || highlightOffset < 0 || transcript.getLayout() == null || isDestroyed()) return;
        int line = transcript.getLayout().getLineForOffset(highlightOffset);
        int[] transcriptAt = new int[2], pageAt = new int[2];
        transcript.getLocationOnScreen(transcriptAt); pageScroll.getLocationOnScreen(pageAt);
        int top = transcriptAt[1] + transcript.getLayout().getLineTop(line);
        int bottom = transcriptAt[1] + transcript.getLayout().getLineBottom(line);
        int visibleTop = pageAt[1] + ui.dp(90), visibleBottom = pageAt[1] + pageScroll.getHeight() - ui.dp(60);
        if (top < visibleTop) pageScroll.smoothScrollBy(0, top - visibleTop);
        else if (bottom > visibleBottom) pageScroll.smoothScrollBy(0, bottom - visibleBottom);
    }
    private void stopPlayback() {
        playbackGeneration++;
        boolean hadPlayback = player != null || activePlaybackSegment >= 0;
        if (player != null) { try { player.release(); } catch (Exception ignored) {} player = null; }
        if (playbackSource != null) { playbackSource.close(); playbackSource = null; }
        MainActivity.playbackActive = false;
        activePlaybackSegment = -1;
        if (hadPlayback) {
            try { if (document != null && transcript != null) transcript.setText(transcriptSpans(document)); } catch (Exception ignored) {}
        }
        if (playbackStatus != null) playbackStatus.setText("点击蓝色时间点回听；播放时高亮当前片段");
    }
    private void toast(String message) { android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_LONG).show(); }
}
