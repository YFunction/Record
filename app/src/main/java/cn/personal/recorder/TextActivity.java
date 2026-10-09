package cn.personal.recorder;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
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
    private TextView status, transcript, summary;
    private Button extract, summarize;
    private boolean loading, changing;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable refresh = new Runnable() {
        @Override public void run() { update(); handler.postDelayed(this, 1000); }
    };
    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved); session = getIntent().getStringExtra("session"); if (!TextStore.validSession(session)) { finish(); return; }
        ui = new Ui(this); LinearLayout root = ui.screen(); LinearLayout heading = ui.row(); heading.addView(ui.button("返回", this::finish, false));
        TextView title = ui.title("文字与总结", 24); title.setPadding(ui.dp(14), 0, 0, 0); heading.addView(title); root.addView(heading);
        root.addView(ui.label("本地识别发言人 · AI 仅接收转写文字", 13, ui.muted));
        LinearLayout actions = ui.card(root); status = ui.label("读取加密记录…", 13, ui.muted); actions.addView(status);
        extract = ui.button("本地提取文字", this::extract, true); actions.addView(extract, ui.spaced());
        summarize = ui.button("生成 AI 总结", this::summarize, false); actions.addView(summarize, ui.spaced());
        ui.action(actions, "取消当前处理", "保留旧结果；已发送的 AI 请求仍可能计费", () -> { if (AnalysisService.busy) startService(new Intent(this, AnalysisService.class).setAction(AnalysisService.CANCEL)); });
        ui.action(actions, "文字与 AI 设置", "离线模型、DeepSeek Key", () -> startActivity(new Intent(this, SettingsActivity.class)));
        LinearLayout result = ui.card(root); result.addView(ui.title("录音文字", 18));
        result.addView(ui.label("发言人编号为估计结果，重叠说话与噪声可能造成误分，请核对。", 12, ui.muted));
        ui.action(result, "修改发言人称呼", "可改为你确认的姓名；修改后需重新总结", this::rename);
        ui.action(result, "修正转写文字", "按时间选择片段修改；修改后需重新总结", this::editSegment);
        transcript = ui.label("尚未提取文字", 15, ui.ink); transcript.setTextIsSelectable(true); result.addView(transcript);
        LinearLayout notes = ui.card(root); notes.addView(ui.title("AI 总结", 18)); summary = ui.label("尚未生成总结", 15, ui.ink); summary.setTextIsSelectable(true); notes.addView(summary);
        root.addView(ui.button("导出文字与总结", this::export, false), ui.spaced()); update();
    }
    @Override protected void onResume() { super.onResume(); handler.removeCallbacks(refresh); handler.post(refresh); }
    @Override protected void onPause() { handler.removeCallbacks(refresh); super.onPause(); }
    @Override protected void onDestroy() { io.shutdown(); super.onDestroy(); }
    private void update() {
        boolean busy = AnalysisService.busy || changing || loading; extract.setEnabled(!busy && !RecordingService.active); summarize.setEnabled(!busy && document != null);
        extract.setAlpha(extract.isEnabled() ? 1f : 0.45f); summarize.setAlpha(summarize.isEnabled() ? 1f : 0.45f);
        if (AnalysisService.busy) status.setText(AnalysisService.session.isEmpty() || session.equals(AnalysisService.session) ? AnalysisService.state : "正在处理另一场录音");
        else status.setText(!AnalysisService.state.isEmpty() && (session.equals(AnalysisService.session) || AnalysisService.session.isEmpty()) ? AnalysisService.state : "文字与总结在手机加密保存，按云端设置同步");
        File file = TextStore.latest(this, session); String name = file == null ? "none" : file.getName();
        if (!loading && !changing && !name.equals(revision) && !io.isShutdown()) {
            loading = true; io.execute(() -> {
                try { JSONObject doc = TextStore.read(this, session); handler.post(() -> { loading = false; if (isDestroyed()) return; document = doc; revision = name; display(); }); }
                catch (Exception e) { handler.post(() -> { loading = false; revision = name; toast("无法解密文字记录，请检查密钥和文件"); }); }
            });
        }
    }
    private void display() {
        try {
            transcript.setText(document == null ? "尚未提取文字" : (document.getBoolean("complete") ? "" : "部分录音：只包含已保存片段\n\n") + Transcript.text(document));
            String value = document == null ? "" : document.optString("summary"); summary.setText(value.isEmpty() ? "尚未生成总结" : value);
        } catch (Exception e) { toast("文字格式不完整，请重新提取"); }
    }
    private boolean editable() { if (AnalysisService.busy || changing || loading) { toast("请等待当前处理完成"); return false; } return true; }
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
    private void summarize() {
        if (!editable() || document == null) return;
        if (!new Vault(this).aiConfigured()) { toast("请先在设置中填写 DeepSeek Key"); startActivity(new Intent(this, SettingsActivity.class)); return; }
        int parts;
        try { parts = Transcript.parts(Transcript.text(document), 20000).size(); } catch (Exception e) { toast("请先提取有效的录音文字"); return; }
        new AlertDialog.Builder(this).setTitle("发送文字并生成总结")
            .setMessage("仅向 DeepSeek V4.1 Flash 发送本场转写文字、发言人标注和时间，不上传音频或录音密钥。HTTPS 加密传输，但服务商会读取文字用于总结；请求按你的 DeepSeek 账户计费。\n\n本场约 " + parts + " 段输入，长文本还会发起合并请求。结果在手机加密保存，按云端设置同步。")
            .setNegativeButton("取消", null).setPositiveButton("生成总结", (d, w) -> start(AnalysisService.SUMMARY)).show();
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
        doc.put("summary", "").remove("summaryModel"); doc.remove("summaryAt"); changing = true;
        io.execute(() -> { try { TextStore.save(this, doc); handler.post(() -> { changing = false; revision = ""; update(); toast("已加密保存，旧总结已清除，可重新生成"); }); }
            catch (Exception e) { handler.post(() -> { changing = false; toast("保存失败，原记录保留"); }); } });
    }
    private void export() {
        if (!editable() || document == null) return;
        new AlertDialog.Builder(this).setTitle("导出明文文字")
            .setMessage("导出文件包含转写和总结，为明文。请选择你控制的保存位置。")
            .setNegativeButton("取消", null).setPositiveButton("选择位置", (d, w) -> startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                .setType("text/plain").putExtra(Intent.EXTRA_TITLE, "录音文字-" + session.substring(0, 8) + ".txt"), 31)).show();
    }
    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data); if (request != 31 || result != RESULT_OK || data == null || data.getData() == null) return;
        io.execute(() -> {
            byte[] text = null;
            try {
                JSONObject doc = TextStore.read(this, session); if (doc == null) throw new java.io.IOException();
                text = ("录音文字\n\n" + Transcript.text(doc) + "\nAI 总结\n\n" + doc.optString("summary", "尚未生成")).getBytes(StandardCharsets.UTF_8);
                try (OutputStream out = getContentResolver().openOutputStream(data.getData(), "wt")) { if (out == null) throw new java.io.IOException(); out.write(text); out.flush(); }
                handler.post(() -> toast("文字与总结已导出"));
            } catch (Exception e) { handler.post(() -> toast("导出失败，目标可能留有部分文件，请删除后重试")); }
            finally { if (text != null) Arrays.fill(text, (byte) 0); }
        });
    }
    private void toast(String message) { android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_LONG).show(); }
}
