package cn.personal.recorder;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.KeyguardManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity {
    private Ui ui;
    private int INK, MUTED, TEAL, RED;
    private Vault vault;
    private TextView timer, state, mode, sync, playing;
    private Button record;
    private boolean resumed;
    private AlertDialog visibleDialog;
    private LinearLayout library;
    private boolean launchHandled, libraryBusy;
    private static volatile boolean exportBusy;
    private int ticks, playbackGeneration;
    private String librarySignature = "", exportSession, libraryFilter = "";
    private MediaPlayer player;
    private RecordingSource playingSource;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable refresh = new Runnable() {
        @Override public void run() { update(); handler.postDelayed(this, 1000); }
    };
    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        ui = new Ui(this); INK = ui.ink; MUTED = ui.muted; TEAL = ui.accent; RED = ui.danger;
        setShowWhenLocked(true); setTurnScreenOn(true);
        vault = new Vault(this);
        LaunchDiagnostics.note(this, "activity-created", false);
        launchHandled = (saved != null && saved.getBoolean("handled")) || getIntent().getBooleanExtra("settings", false);
        if (saved != null) exportSession = saved.getString("export-session");
        LinearLayout root = ui.screen();
        LinearLayout header = row(); header.addView(ui.title("录音", 30), new LinearLayout.LayoutParams(0, -2, 1));
        header.addView(button("设置", () -> unlocked(this::settings), false)); root.addView(header);
        root.addView(label("声音留在当下，记录安心保存", 13, MUTED));
        LinearLayout hero = card(root);
        mode = label("本地加密保存", 13, TEAL); mode.setGravity(Gravity.CENTER); hero.addView(mode);
        timer = label("00:00:00", 52, INK); timer.setTypeface(Typeface.MONOSPACE, Typeface.NORMAL); timer.setGravity(Gravity.CENTER);
        timer.setAutoSizeTextTypeUniformWithConfiguration(24, 52, 1, android.util.TypedValue.COMPLEX_UNIT_SP); hero.addView(timer);
        state = label("准备就绪", 14, MUTED); state.setGravity(Gravity.CENTER); hero.addView(state);
        record = button("开始录音", () -> { if (RecordingService.active) stopRecording(); else startRecording(); }, true);
        record.setTextSize(18); record.setMinHeight(dp(72)); hero.addView(record, spaced());
        sync = label("", 12, MUTED); sync.setGravity(Gravity.CENTER); hero.addView(sync);
        LinearLayout heading = row(); heading.addView(ui.title("最近录音", 19), new LinearLayout.LayoutParams(0, -2, 1));
        heading.addView(button("分类", () -> unlocked(this::chooseLibraryFilter), false));
        heading.addView(button("刷新", () -> { librarySignature = ""; loadLibrary(); }, false)); root.addView(heading);
        playing = label("", 13, TEAL); playing.setVisibility(View.GONE); playing.setOnClickListener(v -> releasePlayer()); root.addView(playing);
        library = column(0); root.addView(library); renderLibrary(java.util.Collections.emptyList());
        root.addView(label("录音后可熄屏继续，在通知栏随时停止。", 12, MUTED));
        UploadWorker.periodic(this);
    }
    @Override protected void onResume() {
        super.onResume(); resumed = true; setShowWhenLocked(true);
        handler.removeCallbacks(refresh); handler.post(refresh); handler.post(this::maybeAutoStart);
    }
    private void maybeAutoStart() {
        // While-in-use microphone permission is checked when a foreground service is created.
        // onResume may run before the lock-screen window is visible; wait for focus as well.
        if (!launchHandled && vault.ready()) {
            if (resumed && hasWindowFocus()) startRecording();
            else LaunchDiagnostics.note(this, "waiting-for-visible-window", hasWindowFocus());
        }
    }
    @Override public void onWindowFocusChanged(boolean focused) {
        super.onWindowFocusChanged(focused); if (focused && vault != null) maybeAutoStart();
    }
    @Override protected void onPause() { resumed = false; handler.removeCallbacks(refresh); releasePlayer(); super.onPause(); }
    @Override protected void onDestroy() { io.shutdown(); super.onDestroy(); }
    @Override protected void onSaveInstanceState(Bundle out) { out.putBoolean("handled", launchHandled); out.putString("export-session", exportSession); super.onSaveInstanceState(out); }
    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent); setIntent(intent); launchHandled = intent.getBooleanExtra("settings", false);
        LaunchDiagnostics.note(this, "new-launch-intent", hasWindowFocus());
        if (visibleDialog != null) visibleDialog.dismiss();
        setShowWhenLocked(true); setTurnScreenOn(true); update(); handler.post(this::maybeAutoStart);
    }
    private void update() {
        boolean active = RecordingService.active;
        long seconds = active && RecordingService.startedElapsed > 0 ? (SystemClock.elapsedRealtime() - RecordingService.startedElapsed) / 1000 : 0;
        timer.setText(duration(seconds));
        state.setText(active ? RecordingService.state : ("尚未录音".equals(RecordingService.state) ? "准备就绪 · 随时开始" : RecordingService.state));
        record.setText(active ? "停止并保存" : "开始录音"); record.setBackgroundTintList(ColorStateList.valueOf(active ? RED : TEAL)); record.setTextColor(active ? ui.background : ui.onAccent);
        mode.setText(vault.cloudEnabled() ? "本地加密 · 云端同步" : "本地加密录音");

        sync.setText(!vault.cloudEnabled() ? "每 30 秒加密保存 · 无需网络" : (!vault.configured() ? "完成服务器设置与密钥备份后开始同步" : Uploader.status));
        if (getSystemService(KeyguardManager.class).isKeyguardLocked()) { library.setVisibility(View.GONE); releasePlayer(); librarySignature = ""; if (visibleDialog != null) visibleDialog.dismiss(); }
        else library.setVisibility(View.VISIBLE);
        if (ticks++ % 3 == 0) loadLibrary();
    }
    private static String duration(long seconds) { return String.format(Locale.ROOT, "%02d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60); }
    private void startRecording() {
        launchHandled = true;
        if (AnalysisService.transcribing) { toast("请先完成或取消本地文字提取，再开始录音"); return; }
        if (exportBusy) { toast("请等待音频导出完成"); return; }
        if (RecordingService.active) return;
        releasePlayer();
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            if (Build.VERSION.SDK_INT >= 33) requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS}, 10);
            else requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 10);
            return;
        }
        try {
            vault.initialize(""); LaunchDiagnostics.note(this, "foreground-service-requested", hasWindowFocus());
            startForegroundService(new Intent(this, RecordingService.class).putExtra(RecordingService.EXTRA_CATEGORY, CategoryStore.defaultId(this)));
        } catch (Exception e) {
            LaunchDiagnostics.note(this, "start-rejected:" + e.getClass().getSimpleName(), hasWindowFocus());
            toast("系统暂未允许启动录音，可在设置中查看锁屏排查并导出诊断");
        }
    }
    private void stopRecording() { launchHandled = true; if (RecordingService.active) startService(new Intent(this, RecordingService.class).setAction(RecordingService.STOP)); }
    @Override public void onRequestPermissionsResult(int request, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(request, permissions, results);
        if (request == 10 && checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) startRecording();
        else if (request == 10) toast("录音需要麦克风权限，可在系统设置中开启");
    }
    private void unlocked(Runnable action) {
        launchHandled = true; KeyguardManager km = getSystemService(KeyguardManager.class);
        if (!km.isKeyguardLocked()) { action.run(); return; }
        km.requestDismissKeyguard(this, new KeyguardManager.KeyguardDismissCallback() {
            @Override public void onDismissSucceeded() { action.run(); }
            @Override public void onDismissCancelled() { toast("解锁后可访问录音和设置"); }
            @Override public void onDismissError() { toast("请先解锁手机"); }
        });
    }
    private boolean editable() { if (RecordingService.active || exportBusy) { toast("请先停止录音，或等待导出完成"); return false; } return true; }
    private void settings() {
        launchHandled = true; releasePlayer(); startActivity(new Intent(this, SettingsActivity.class));
    }
    private void showPrivate(AlertDialog.Builder builder) {
        AlertDialog dialog = builder.create(); visibleDialog = dialog; setShowWhenLocked(false);
        dialog.setOnDismissListener(d -> { if (visibleDialog == dialog) { visibleDialog = null; setShowWhenLocked(true); } });
        dialog.show();
    }
    private void loadLibrary() {
        if (libraryBusy || io.isShutdown()) return; libraryBusy = true;
        io.execute(() -> {
            try {
                List<RecordingLibrary.Entry> entries = RecordingLibrary.list(this);
                CategoryStore.State catalog = CategoryStore.load(this);
                if (!libraryFilter.isEmpty()) {
                    boolean exists = false; for (int i = 0; i < catalog.size(); i++) if (libraryFilter.equals(catalog.id(catalog.category(i)))) exists = true;
                    if (!exists) libraryFilter = "";
                }
                StringBuilder signature = new StringBuilder().append(catalog.updatedAt).append(':').append(libraryFilter).append(';');
                for (RecordingLibrary.Entry e : entries) signature.append(e.id).append(':').append(e.bytes).append(':').append(e.uploaded).append(':').append(e.categoryId).append(';');
                handler.post(() -> {
                    libraryBusy = false; if (isDestroyed()) return;
                    if (getSystemService(KeyguardManager.class).isKeyguardLocked()) return;
                    if (!signature.toString().equals(librarySignature) || librarySignature.isEmpty()) { librarySignature = signature.toString(); renderLibrary(entries); }
                });
            } catch (Exception e) { handler.post(() -> { libraryBusy = false; toast("无法读取本地录音"); }); }
        });
    }
    private void renderLibrary(List<RecordingLibrary.Entry> entries) {
        library.removeAllViews();
        List<RecordingLibrary.Entry> visible = new java.util.ArrayList<>();
        for (RecordingLibrary.Entry entry : entries) if (libraryFilter.isEmpty() || libraryFilter.equals(entry.categoryId)) visible.add(entry);
        if (visible.isEmpty()) { LinearLayout empty = card(library); empty.addView(ui.title(entries.isEmpty() ? "还没有录音" : "该分类还没有录音", 17)); empty.addView(label(entries.isEmpty() ? "点击开始，声音会加密保存在手机中。" : "可以在录音操作中更改分类。", 13, MUTED)); return; }
        int shown = 0;
        for (RecordingLibrary.Entry entry : visible) {
            if (shown++ >= 20) break;
            LinearLayout item = card(library); item.setPadding(dp(16), dp(10), dp(16), dp(10));
            item.addView(label(new SimpleDateFormat("MM月dd日  HH:mm", Locale.CHINA).format(new java.util.Date(entry.time)), 17, INK));
            item.addView(label(entry.categoryName, 12, TEAL));
            String saved = entry.chunks.length == 0 ? "文字与总结" : entry.uploaded == entry.chunks.length ? "云端已保存" : "本地已加密";
            item.addView(label(entry.chunks.length == 0 ? "转写与总结已加密 · 点击查看  ›" : String.format(Locale.CHINA, "%s · %.1f MB · 播放 / 文字  ›", saved, entry.bytes / 1048576.0), 12, MUTED));
            item.setContentDescription("录音 " + new java.util.Date(entry.time) + "，点击查看文字、播放或导出"); item.setOnClickListener(v -> unlocked(() -> recordingActions(entry)));
        }
        if (visible.size() > 20) library.addView(button("查看更早录音（共 " + visible.size() + " 场）", () -> unlocked(() -> {
            String[] names = new String[visible.size()]; for (int i = 0; i < names.length; i++) names[i] = visible.get(i).categoryName + " · " + new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA).format(new java.util.Date(visible.get(i).time));
            showPrivate(new AlertDialog.Builder(this).setTitle("全部本地录音").setItems(names, (d, w) -> recordingActions(visible.get(w))).setNegativeButton("关闭", null));
        }), false));
    }
    private void chooseLibraryFilter() {
        try {
            CategoryStore.State catalog = CategoryStore.load(this); String[] names = new String[catalog.size() + 1]; String[] ids = new String[names.length];
            names[0] = "全部分类"; ids[0] = "";
            for (int i = 0; i < catalog.size(); i++) { org.json.JSONObject c = catalog.category(i); names[i + 1] = c.optString("name"); ids[i + 1] = c.optString("id"); }
            int selected = 0; for (int i = 0; i < ids.length; i++) if (ids[i].equals(libraryFilter)) selected = i;
            new AlertDialog.Builder(this).setTitle("按分类筛选").setSingleChoiceItems(names, selected, (d, which) -> { libraryFilter = ids[which]; librarySignature = ""; d.dismiss(); loadLibrary(); }).setNegativeButton("关闭", null).show();
        } catch (Exception e) { toast("无法读取分类"); }
    }
    private void recordingActions(RecordingLibrary.Entry entry) {
        if (!editable()) return;
        showPrivate(new AlertDialog.Builder(this).setTitle("本地录音 · " + entry.categoryName).setItems(new String[]{"更改分类", "文字与 AI 总结", "播放（内存解密）", "导出 AAC 音频", "删除本地录音与文字"}, (d, w) -> {
            if (w == 0) changeCategory(entry);
            else if (w == 1) { releasePlayer(); startActivity(new Intent(this, TextActivity.class).putExtra("session", entry.id)); }
            else if (w == 2) { if (entry.chunks.length == 0) toast("本地音频已清理，文字仍可查看"); else play(entry); }
            else if (w == 3 && entry.chunks.length == 0) toast("本地音频已清理，文字仍可查看");
            else if (w == 3) showPrivate(new AlertDialog.Builder(this).setTitle("导出音频").setMessage("导出的 AAC 文件是明文，可由其他播放器打开。请选择你控制的保存位置。意外中断的录音只导出已保存的连续片段。")
                .setNegativeButton("取消", null).setPositiveButton("选择位置", (dialog, which) -> { exportSession = entry.id;
                    startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("audio/aac")
                        .putExtra(Intent.EXTRA_TITLE, "录音-" + new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(new java.util.Date(entry.time)) + ".aac"), 21); }));
            else showPrivate(new AlertDialog.Builder(this).setTitle("删除本地录音与文字？").setMessage("这会删除本场音频、转写和总结的手机副本。未上传内容将无法恢复，云端副本不会删除。")
                .setNegativeButton("取消", null).setPositiveButton("删除", (dialog, which) -> { if (!editable()) return; releasePlayer();
                    if (AnalysisService.busy) { toast("请等待文字处理结束后再删除"); return; }
                    io.execute(() -> { boolean ok = RecordingLibrary.delete(entry); ok = TextStore.delete(this, entry.id) && ok; boolean result = ok; handler.post(() -> { librarySignature = ""; loadLibrary(); toast(result ? "本地录音与文字已删除" : "部分文件删除失败，请重试"); }); }); }));
        }).setNegativeButton("关闭", null));
    }
    private void changeCategory(RecordingLibrary.Entry entry) {
        try {
            CategoryStore.State catalog = CategoryStore.load(this); String[] names = new String[catalog.size()]; String[] ids = new String[names.length];
            for (int i = 0; i < names.length; i++) { org.json.JSONObject c = catalog.category(i); names[i] = c.optString("name"); ids[i] = c.optString("id"); }
            new AlertDialog.Builder(this).setTitle("将录音归入分类").setItems(names, (d, which) -> io.execute(() -> {
                try { CategoryStore.assign(this, entry.id, ids[which]); handler.post(() -> { librarySignature = ""; loadLibrary(); toast("录音已归入“" + names[which] + "”"); }); }
                catch (Exception e) { handler.post(() -> toast("分类保存失败")); }
            })).setNegativeButton("取消", null).show();
        } catch (Exception e) { toast("无法读取分类"); }
    }
    private void play(RecordingLibrary.Entry entry) {
        releasePlayer(); int generation = playbackGeneration; playing.setText("正在验证并解密录音… 点击取消"); playing.setVisibility(View.VISIBLE);
        io.execute(() -> {
            try {
                RecordingSource source = new RecordingSource(entry.chunks, vault.recordingKey());
                handler.post(() -> {
                    if (isDestroyed() || generation != playbackGeneration || RecordingService.active || getSystemService(KeyguardManager.class).isKeyguardLocked()) { source.close(); return; }
                    try {
                        playingSource = source; player = new MediaPlayer(); player.setDataSource(source);
                        player.setOnPreparedListener(p -> { p.start(); playing.setText(getString(R.string.playback_detail,
                            source.complete ? "播放中" : "播放已保存的部分", duration(source.samples / 16000))); });
                        player.setOnCompletionListener(p -> releasePlayer()); player.setOnErrorListener((p, a, b) -> { releasePlayer(); toast("播放失败，可尝试导出 AAC 后播放"); return true; }); player.prepareAsync();
                    } catch (Exception e) { releasePlayer(); toast("无法播放这场录音"); }
                });
            } catch (Exception e) { handler.post(() -> { if (generation == playbackGeneration) { releasePlayer(); toast(e.getMessage()); } }); }
        });
    }
    private void releasePlayer() {
        playbackGeneration++; if (player != null) { player.release(); player = null; }
        if (playingSource != null) { playingSource.close(); playingSource = null; } if (playing != null) playing.setVisibility(View.GONE);
    }
    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (result != RESULT_OK || data == null || data.getData() == null) return;
        Uri destination = data.getData();
        if (request == 21 && exportSession != null) {
            String session = exportSession; exportSession = null; exportBusy = true; releasePlayer(); toast("正在导出，请保持应用打开");
            io.execute(() -> {
                byte[] block = new byte[65536];
                try {
                    RecordingLibrary.Entry entry = null; for (RecordingLibrary.Entry e : RecordingLibrary.list(this)) if (e.id.equals(session)) entry = e;
                    if (entry == null) throw new java.io.IOException("找不到这场录音"); boolean complete;
                    try (RecordingSource source = new RecordingSource(entry.chunks, vault.recordingKey()); OutputStream out = getContentResolver().openOutputStream(destination, "wt")) {
                        if (out == null) throw new java.io.IOException("无法写入导出位置"); long position = 0; int count;
                        while ((count = source.readAt(position, block, 0, block.length)) != -1) { out.write(block, 0, count); position += count; }
                        out.flush(); complete = source.complete;
                    }
                    handler.post(() -> toast(complete ? "AAC 音频已导出" : "已导出连续的部分录音（缺少结束标记）"));
                } catch (Exception e) { handler.post(() -> toast("导出失败，目标可能留有不完整文件，请删除后重试：" + e.getMessage())); }
                finally { Arrays.fill(block, (byte) 0); handler.post(() -> exportBusy = false); }
            });
        }
    }
    private int dp(int n) { return ui.dp(n); }
    private LinearLayout column(int padding) { return ui.column(padding); }
    private LinearLayout row() { return ui.row(); }
    private LinearLayout.LayoutParams spaced() { return ui.spaced(); }
    private LinearLayout card(LinearLayout parent) { return ui.card(parent); }
    private TextView label(String text, int size, int color) { return ui.label(text, size, color); }
    private Button button(String text, Runnable action, boolean primary) { return ui.button(text, action, primary); }
    private void toast(String message) { android.widget.Toast.makeText(this, message == null ? "操作失败" : message, android.widget.Toast.LENGTH_LONG).show(); }
}
