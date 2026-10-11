package cn.personal.recorder;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.KeyguardManager;
import android.content.Intent;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONObject;

/** One local, unlocked view of encrypted marks across recording sessions. */
public final class MomentInboxActivity extends Activity {
    private static final int MAX_VISIBLE = 150;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private Ui ui;
    private LinearLayout list;
    private TextView status, playback;
    private Button pendingButton, allButton;
    private boolean showAll, loading;
    private MediaPlayer player;
    private RecordingSource source;
    private int playbackGeneration;
    private String exportSession;
    private int exportIndex = -1;
    private long exportOffset = -1;
    private boolean exporting;

    private static final class Item {
        final RecordingLibrary.Entry entry;
        final MomentStore.Mark mark;
        final int index, segment;
        final String excerpt;
        Item(RecordingLibrary.Entry entry, MomentStore.Mark mark, int index, int segment, String excerpt) {
            this.entry = entry; this.mark = mark; this.index = index; this.segment = segment; this.excerpt = excerpt;
        }
    }
    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        if (saved != null) {
            exportSession = saved.getString("export-session");
            exportIndex = saved.getInt("export-index", -1);
            exportOffset = saved.getLong("export-offset", -1);
        }
        ui = new Ui(this);
        LinearLayout root = ui.screen();
        LinearLayout heading = ui.row(); heading.addView(ui.quietButton("返回", this::finish));
        TextView title = ui.title("瞬间收件箱", 24); title.setPadding(ui.dp(14), 0, 0, 0); heading.addView(title); root.addView(heading);
        root.addView(ui.label("标记引用原录音；回听前后文不会额外保存一份音频。", 13, ui.muted));
        LinearLayout tabs = ui.row();
        pendingButton = ui.quietButton("待整理", () -> { showAll = false; load(); });
        allButton = ui.quietButton("全部瞬间", () -> { showAll = true; load(); });
        tabs.addView(pendingButton, new LinearLayout.LayoutParams(0, ui.dp(48), 1));
        tabs.addView(allButton, new LinearLayout.LayoutParams(0, ui.dp(48), 1)); root.addView(tabs);
        status = ui.label("正在读取加密标记…", 13, ui.muted); root.addView(status);
        playback = ui.label("", 13, ui.accent); playback.setVisibility(View.GONE);
        playback.setOnClickListener(v -> stopPlayback()); root.addView(playback);
        list = ui.column(0); root.addView(list);
        load();
    }
    @Override protected void onResume() {
        super.onResume();
        if (getSystemService(KeyguardManager.class).isKeyguardLocked()) { finish(); return; }
        load();
    }
    @Override protected void onPause() { stopPlayback(); super.onPause(); }
    @Override protected void onDestroy() { io.shutdown(); super.onDestroy(); }
    @Override protected void onSaveInstanceState(Bundle out) {
        out.putString("export-session", exportSession); out.putInt("export-index", exportIndex);
        out.putLong("export-offset", exportOffset); super.onSaveInstanceState(out);
    }

    private void load() {
        if (loading || io.isShutdown()) return;
        final boolean requestedAll = showAll;
        loading = true; status.setText("正在读取加密标记…");
        io.execute(() -> {
            List<Item> items = new ArrayList<>(); int total = 0, pending = 0, unreadable = 0;
            try {
                for (RecordingLibrary.Entry entry : RecordingLibrary.list(this)) {
                    List<MomentStore.Mark> marks;
                    try { marks = MomentStore.list(this, entry.id); }
                    catch (Exception e) { unreadable++; continue; }
                    if (marks.isEmpty()) continue;
                    JSONObject doc = null;
                    try { doc = TextStore.read(this, entry.id); } catch (Exception ignored) { /* Audio remains usable. */ }
                    for (int i = 0; i < marks.size(); i++) {
                        MomentStore.Mark mark = marks.get(i); total++;
                        if ("pending".equals(mark.state)) pending++;
                        if (!requestedAll && !"pending".equals(mark.state)) continue;
                        int segment = MomentContext.nearbySegment(doc, mark.offsetMs);
                        items.add(new Item(entry, mark, i, segment, MomentContext.excerpt(doc, segment)));
                    }
                }
                items.sort(Comparator.comparingLong((Item item) -> item.entry.time).reversed()
                    .thenComparing(Comparator.comparingLong((Item item) -> item.mark.offsetMs).reversed()));
                int found = total, waiting = pending, errors = unreadable;
                handler.post(() -> {
                    loading = false; if (isDestroyed()) return;
                    if (requestedAll != showAll) { load(); return; }
                    status.setText(waiting + " 个待整理 · 共 " + found + " 个瞬间"
                        + (errors > 0 ? " · " + errors + " 场标记无法读取" : ""));
                    render(items); pendingButton.setAlpha(showAll ? 0.65f : 1f); allButton.setAlpha(showAll ? 1f : 0.65f);
                });
            } catch (Exception e) {
                handler.post(() -> { loading = false; status.setText("无法读取瞬间；请检查本机密钥与加密记录"); });
            }
        });
    }
    private void render(List<Item> items) {
        list.removeAllViews();
        if (items.isEmpty()) {
            LinearLayout empty = ui.card(list);
            empty.addView(ui.title(showAll ? "还没有瞬间" : "待整理瞬间已清空", 17));
            empty.addView(ui.label(showAll ? "录音时点击“打标签”，瞬间会出现在这里。" : "可以切到“全部瞬间”回看已保留和已忽略的标记。", 13, ui.muted));
            return;
        }
        SimpleDateFormat dates = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA);
        for (int i = 0; i < Math.min(items.size(), MAX_VISIBLE); i++) {
            Item item = items.get(i);
            LinearLayout card = ui.card(list);
            card.addView(ui.title(dates.format(new Date(item.entry.time)) + "  ·  " + Transcript.time(item.mark.offsetMs / 1000.0), 16));
            card.addView(ui.label(item.entry.categoryName + " · " + stateName(item.mark.state), 12, ui.accent));
            if (!item.mark.note.isEmpty()) card.addView(ui.label(item.mark.note, 15, ui.ink));
            card.addView(ui.label(item.excerpt, 14, ui.muted));
            card.setOnClickListener(v -> actions(item));
            card.setContentDescription("瞬间，" + item.entry.categoryName + "，录音内 " + Transcript.time(item.mark.offsetMs / 1000.0) + "，" + item.excerpt);
        }
        if (items.size() > MAX_VISIBLE) list.addView(ui.label("仅显示最近 " + MAX_VISIBLE + " 个瞬间，请先整理较新的标记。", 13, ui.muted));
    }
    private static String stateName(String state) {
        return "kept".equals(state) ? "已保留" : "ignored".equals(state) ? "已忽略" : "待整理";
    }
    private void actions(Item item) {
        final AlertDialog[] sheet = new AlertDialog[1];
        sheet[0] = ui.bottomSheet("声音瞬间", item.entry.categoryName + " · " + Transcript.time(item.mark.offsetMs / 1000.0), (widgets, options) -> {
            widgets.action(options, "回听前后文", "标记前 15 秒至后 30 秒；只读取原录音", () -> { sheet[0].dismiss(); play(item, true); });
            widgets.action(options, "从标记处播放", "继续播放本场录音", () -> { sheet[0].dismiss(); play(item, false); });
            widgets.action(options, "导出加密片段", "仅导出前后文密文；电脑需恢复密钥解密", () -> { sheet[0].dismiss(); chooseClipDestination(item); });
            widgets.action(options, "查看附近文字", item.segment < 0 ? "尚无附近转写，可先在录音详情提取" : item.excerpt,
                () -> { sheet[0].dismiss(); startActivity(new Intent(this, TextActivity.class).putExtra("session", item.entry.id).putExtra("focus-segment", item.segment)); });
            widgets.action(options, "修改备注", item.mark.note.isEmpty() ? "添加一个线索" : item.mark.note, () -> { sheet[0].dismiss(); edit(item); });
            if (!"kept".equals(item.mark.state)) widgets.action(options, "保留这个瞬间", "在全部瞬间中继续显示", () -> { sheet[0].dismiss(); changeState(item, "kept"); });
            if (!"ignored".equals(item.mark.state)) widgets.action(options, "忽略这个瞬间", "原录音不会删除，可在全部瞬间找回", () -> { sheet[0].dismiss(); changeState(item, "ignored"); });
            if (!"pending".equals(item.mark.state)) widgets.action(options, "放回待整理", "重新出现在待整理列表", () -> { sheet[0].dismiss(); changeState(item, "pending"); });
        });
        sheet[0].show();
    }
    private void edit(Item item) {
        android.widget.EditText input = new android.widget.EditText(this);
        input.setText(item.mark.note); input.setHint("备注，可留空");
        input.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(120)});
        new AlertDialog.Builder(this).setTitle("瞬间备注").setView(input).setNegativeButton("取消", null)
            .setPositiveButton("保存", (dialog, which) -> io.execute(() -> {
                try { MomentStore.rename(this, item.entry.id, item.index, input.getText().toString()); handler.post(this::load); }
                catch (Exception e) { handler.post(() -> status.setText("备注保存失败，请重试")); }
            })).show();
    }
    private void chooseClipDestination(Item item) {
        if (exporting) { status.setText("请等待当前片段导出完成"); return; }
        if (RecordingService.active || AnalysisService.transcribing) { status.setText("请先结束录音或转写，再导出片段"); return; }
        if (item.entry.chunks.length == 0) { status.setText("本机音频已清理，无法导出片段"); return; }
        exportSession = item.entry.id; exportIndex = item.index; exportOffset = item.mark.offsetMs;
        String name = "Record-moment-" + new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(new Date(item.entry.time))
            + "-" + item.mark.offsetMs / 1000 + ".rcclip";
        startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
            .setType("application/octet-stream").putExtra(Intent.EXTRA_TITLE, name), 40);
    }
    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != 40) return;
        String session = exportSession; int index = exportIndex; long offset = exportOffset;
        exportSession = null; exportIndex = -1; exportOffset = -1;
        if (result != RESULT_OK || data == null || data.getData() == null || !TextStore.validSession(session) || exporting) return;
        Uri destination = data.getData(); exporting = true; status.setText("正在加密导出前后文，请保持应用打开…");
        io.execute(() -> {
            byte[] envelope = null;
            try {
                RecordingLibrary.Entry entry = null;
                for (RecordingLibrary.Entry candidate : RecordingLibrary.list(this)) if (session.equals(candidate.id)) { entry = candidate; break; }
                List<MomentStore.Mark> marks = MomentStore.list(this, session);
                if (entry == null || index < 0 || index >= marks.size() || marks.get(index).offsetMs != offset)
                    throw new java.io.IOException("瞬间标记已改变，请重新选择");
                javax.crypto.SecretKey key = new Vault(this).recordingKey();
                try (RecordingSource recording = new RecordingSource(entry.chunks, key)) {
                    envelope = MomentClip.create(recording, recording.samples, key, session, offset);
                }
                try (OutputStream out = getContentResolver().openOutputStream(destination, "wt")) {
                    if (out == null) throw new java.io.IOException("无法写入所选位置");
                    out.write(envelope); out.flush();
                }
                handler.post(() -> { exporting = false; status.setText("加密片段已导出；电脑需使用恢复密钥解密"); });
            } catch (Exception e) {
                handler.post(() -> { exporting = false; status.setText("导出失败；目标可能留有不完整文件，请删除后重试"); });
            } finally { if (envelope != null) Arrays.fill(envelope, (byte) 0); }
        });
    }
    private void changeState(Item item, String value) {
        io.execute(() -> {
            try { MomentStore.setState(this, item.entry.id, item.index, value); handler.post(this::load); }
            catch (Exception e) { handler.post(() -> status.setText("状态保存失败，请重试")); }
        });
    }
    private void play(Item item, boolean contextOnly) {
        stopPlayback();
        if (RecordingService.active || AnalysisService.transcribing) { status.setText("请先结束录音或转写，再回听"); return; }
        if (item.entry.chunks.length == 0) { status.setText("本机音频已清理，仍可查看文字和备注"); return; }
        final int generation = playbackGeneration;
        playback.setText("正在验证加密录音… 点击停止"); playback.setVisibility(View.VISIBLE);
        io.execute(() -> {
            try {
                RecordingSource opened = new RecordingSource(item.entry.chunks, new Vault(this).recordingKey());
                handler.post(() -> {
                    if (isDestroyed() || generation != playbackGeneration || getSystemService(KeyguardManager.class).isKeyguardLocked()) { opened.close(); return; }
                    try {
                        source = opened; player = new MediaPlayer(); player.setDataSource(opened);
                        long duration = opened.samples * 1000 / 16000;
                        long start = Math.min(duration, contextOnly ? MomentContext.start(item.mark.offsetMs) : item.mark.offsetMs);
                        long end = contextOnly ? MomentContext.end(item.mark.offsetMs, duration) : duration;
                        player.setOnPreparedListener(p -> {
                            if (start > 0) p.seekTo((int) Math.min(Integer.MAX_VALUE, start)); else beginPlayback(generation, contextOnly, end);
                        });
                        player.setOnSeekCompleteListener(p -> beginPlayback(generation, contextOnly, end));
                        player.setOnCompletionListener(p -> stopPlayback());
                        player.setOnErrorListener((p, what, extra) -> { stopPlayback(); status.setText("回听失败，请尝试播放整场录音"); return true; });
                        player.prepareAsync();
                    } catch (Exception e) { opened.close(); stopPlayback(); status.setText("无法播放这段录音"); }
                });
            } catch (Exception e) { handler.post(() -> { if (generation == playbackGeneration) { stopPlayback(); status.setText("密文验证或读取失败，无法回听"); } }); }
        });
    }
    private void beginPlayback(int generation, boolean contextOnly, long endMs) {
        if (generation != playbackGeneration || player == null) return;
        if (player.isPlaying()) return;
        player.start(); playback.setText(contextOnly ? "正在回听前后文 · 点击停止" : "从标记处播放 · 点击停止");
        if (contextOnly) handler.postDelayed(() -> checkEnd(generation, endMs), 150);
    }
    private void checkEnd(int generation, long endMs) {
        if (generation != playbackGeneration || player == null) return;
        if (player.getCurrentPosition() >= endMs) stopPlayback();
        else handler.postDelayed(() -> checkEnd(generation, endMs), 150);
    }
    private void stopPlayback() {
        playbackGeneration++;
        if (player != null) { player.release(); player = null; }
        if (source != null) { source.close(); source = null; }
        if (playback != null) playback.setVisibility(View.GONE);
    }
}
