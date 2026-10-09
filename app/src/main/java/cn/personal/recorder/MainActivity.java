package cn.personal.recorder;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.KeyguardManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity {
    private static final int INK = 0xff182b32, MUTED = 0xff65767d, TEAL = 0xff126b61, RED = 0xffba413f;
    private Vault vault;
    private TextView timer, state, mode, storage, sync, backup, playing;
    private Button record, localMode, cloudMode;
    private ProgressBar capacity;
    private LinearLayout library;
    private boolean launchHandled, libraryBusy;
    private static volatile boolean exportBusy;
    private int ticks, playbackGeneration;
    private String librarySignature = "", exportSession;
    private MediaPlayer player;
    private RecordingSource playingSource;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable refresh = new Runnable() {
        @Override public void run() { update(); handler.postDelayed(this, 1000); }
    };
    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        setShowWhenLocked(!getIntent().getBooleanExtra("settings", false)); setTurnScreenOn(true);
        vault = new Vault(this);
        launchHandled = (saved != null && saved.getBoolean("handled")) || getIntent().getBooleanExtra("settings", false);
        if (saved != null) exportSession = saved.getString("export-session");
        ScrollView scroll = new ScrollView(this); scroll.setFillViewport(true); scroll.setBackgroundColor(0xfff2f5f3);
        LinearLayout root = column(22); scroll.addView(root); setContentView(scroll);
        scroll.setOnApplyWindowInsetsListener((v, insets) -> {
            android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom); return insets;
        });
        LinearLayout header = row();
        TextView title = label("录音", 30, INK); title.setTypeface(null, Typeface.BOLD);
        header.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        header.addView(button("设置", () -> unlocked(this::settings), false)); root.addView(header);
        root.addView(label("留住声音，安心保存", 14, MUTED));
        LinearLayout hero = card(root);
        mode = label("本地加密录音", 14, TEAL); mode.setGravity(Gravity.CENTER); hero.addView(mode);
        timer = label("00:00:00", 46, INK); timer.setTypeface(Typeface.MONOSPACE, Typeface.NORMAL); timer.setGravity(Gravity.CENTER); hero.addView(timer);
        timer.setAutoSizeTextTypeUniformWithConfiguration(24, 46, 1, android.util.TypedValue.COMPLEX_UNIT_SP);
        state = label("准备就绪", 14, MUTED); state.setGravity(Gravity.CENTER); hero.addView(state);
        record = button("开始录音", () -> { if (RecordingService.active) stopRecording(); else startRecording(); }, true);
        record.setTextSize(18); record.setMinHeight(dp(64)); hero.addView(record, spaced());
        TextView hint = label("每 30 秒加密保存 · 停止时保存最后一段", 12, MUTED); hint.setGravity(Gravity.CENTER); hero.addView(hint);
        LinearLayout save = card(root); save.addView(label("保存方式", 17, INK));
        LinearLayout choices = row();
        localMode = button("仅本地", () -> unlocked(() -> changeMode(false)), false);
        cloudMode = button("本地 + 云端", () -> unlocked(() -> changeMode(true)), false);
        choices.addView(localMode, new LinearLayout.LayoutParams(0, -2, 1));
        LinearLayout.LayoutParams cloudLayout = new LinearLayout.LayoutParams(0, -2, 1); cloudLayout.leftMargin = dp(8);
        choices.addView(cloudMode, cloudLayout); save.addView(choices);
        sync = label("无需服务器，即可开始录音", 13, MUTED); save.addView(sync);
        storage = label("", 13, MUTED); save.addView(storage);
        capacity = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        capacity.setMax(256); capacity.setProgressTintList(ColorStateList.valueOf(TEAL)); save.addView(capacity);
        backup = label("备份恢复密钥，避免换机后无法解密  ›", 13, TEAL); backup.setPadding(dp(8), dp(12), dp(8), dp(12));
        backup.setOnClickListener(v -> unlocked(this::exportKey)); root.addView(backup);
        LinearLayout heading = row(); heading.addView(label("最近录音", 19, INK), new LinearLayout.LayoutParams(0, -2, 1));
        heading.addView(button("刷新", () -> { librarySignature = ""; loadLibrary(); }, false)); root.addView(heading);
        playing = label("", 13, TEAL); playing.setVisibility(View.GONE); playing.setOnClickListener(v -> releasePlayer()); root.addView(playing);
        library = column(0); root.addView(library); library.addView(label("还没有录音\n点击上方按钮开始，录音会加密保存在手机中。", 14, MUTED));
        root.addView(label("可熄屏继续录音，通知栏可随时停止。\n首次录音后，重新打开应用会按设置自动开始。", 12, MUTED));
        UploadWorker.periodic(this);
    }
    @Override protected void onResume() {
        super.onResume(); handler.removeCallbacks(refresh); handler.post(refresh);
        if (!launchHandled && vault.ready()) { launchHandled = true; startRecording(); }
    }
    @Override protected void onPause() { handler.removeCallbacks(refresh); releasePlayer(); super.onPause(); }
    @Override protected void onDestroy() { io.shutdown(); super.onDestroy(); }
    @Override protected void onSaveInstanceState(Bundle out) { out.putBoolean("handled", launchHandled); out.putString("export-session", exportSession); super.onSaveInstanceState(out); }
    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent); setIntent(intent); launchHandled = intent.getBooleanExtra("settings", false);
        setShowWhenLocked(!launchHandled); setTurnScreenOn(!launchHandled);
    }
    private void update() {
        boolean active = RecordingService.active;
        long seconds = active && RecordingService.startedElapsed > 0 ? (SystemClock.elapsedRealtime() - RecordingService.startedElapsed) / 1000 : 0;
        timer.setText(duration(seconds));
        state.setText(active ? RecordingService.state : ("尚未录音".equals(RecordingService.state) ? "准备就绪 · 随时开始" : RecordingService.state));
        record.setText(active ? "停止并保存" : "开始录音"); record.setBackgroundTintList(ColorStateList.valueOf(active ? RED : TEAL));
        mode.setText(vault.cloudEnabled() ? "本地加密 · 云端同步" : "本地加密录音");
        styleChoice(localMode, !vault.cloudEnabled()); styleChoice(cloudMode, vault.cloudEnabled()); localMode.setEnabled(!active); cloudMode.setEnabled(!active);
        sync.setText(!vault.cloudEnabled() ? "音频仅保存在手机中，无需网络" : (!vault.configured() ? "完成服务器设置与密钥备份后开始同步" : Uploader.status));
        backup.setText(vault.preferences().getBoolean("backed-up", false) ? "恢复密钥已备份 · 再次导出  ›" : "备份恢复密钥，避免换机后无法解密  ›");
        if (getSystemService(KeyguardManager.class).isKeyguardLocked()) { library.setVisibility(View.GONE); releasePlayer(); librarySignature = ""; }
        else library.setVisibility(View.VISIBLE);
        if (ticks++ % 3 == 0) loadLibrary();
    }
    private static String duration(long seconds) { return String.format(Locale.ROOT, "%02d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60); }
    private void startRecording() {
        launchHandled = true;
        if (exportBusy) { toast("请等待音频导出完成"); return; }
        if (RecordingService.active) return;
        releasePlayer();
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            if (Build.VERSION.SDK_INT >= 33) requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS}, 10);
            else requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 10);
            return;
        }
        try { vault.initialize(""); startForegroundService(new Intent(this, RecordingService.class)); }
        catch (Exception e) { toast("无法启动录音，请检查权限并解锁后重试"); }
    }
    private void stopRecording() { launchHandled = true; if (RecordingService.active) startService(new Intent(this, RecordingService.class).setAction(RecordingService.STOP)); }
    @Override public void onRequestPermissionsResult(int request, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(request, permissions, results);
        if (request == 10 && checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) startRecording();
        else if (request == 10) toast("录音需要麦克风权限，可在系统设置中开启");
    }
    private void unlocked(Runnable action) {
        launchHandled = true; KeyguardManager km = getSystemService(KeyguardManager.class);
        if (!km.isKeyguardLocked()) { setShowWhenLocked(false); action.run(); return; }
        km.requestDismissKeyguard(this, new KeyguardManager.KeyguardDismissCallback() {
            @Override public void onDismissSucceeded() { setShowWhenLocked(false); action.run(); }
            @Override public void onDismissCancelled() { toast("解锁后可访问录音和设置"); }
            @Override public void onDismissError() { toast("请先解锁手机"); }
        });
    }
    private boolean editable() { if (RecordingService.active || exportBusy) { toast("请先停止录音，或等待导出完成"); return false; } return true; }
    private void changeMode(boolean cloud) {
        if (!editable()) return;
        if (cloud) { configure(); return; }
        vault.preferences().edit().putBoolean("cloud-enabled", false).apply(); UploadWorker.periodic(this); update(); toast("已切换为仅本地保存");
    }
    private void settings() {
        if (!editable()) return;
        String[] actions = {"服务器与同步设置", "打开应用自动录音", "导出恢复密钥", "导入已有密钥（首次使用）", "补传本地密文", "清理已上传副本", "应用系统设置"};
        new AlertDialog.Builder(this).setTitle("录音设置").setItems(actions, (d, which) -> {
            switch (which) {
                case 0: configure(); break;
                case 1:
                    CheckBox auto = new CheckBox(this); auto.setPadding(dp(24), dp(16), dp(24), dp(16)); auto.setText("打开应用后自动开始录音"); auto.setChecked(vault.preferences().getBoolean("ready", true));
                    new AlertDialog.Builder(this).setTitle("自动录音").setView(auto).setNegativeButton("取消", null).setPositiveButton("保存", (dialog, w) -> vault.preferences().edit().putBoolean("ready", auto.isChecked()).apply()).show(); break;
                case 2: exportKey(); break;
                case 3: importKey(); break;
                case 4: if (!vault.configured()) { toast("请启用云端同步并完成密钥备份"); return; } UploadWorker.schedule(this); toast("已安排补传"); break;
                case 5:
                    new AlertDialog.Builder(this).setTitle("清理本地副本").setMessage("仅删除服务器已确认保存的片段。清理后本地录音可能不完整；请先确保云端备份可靠。")
                        .setNegativeButton("取消", null).setPositiveButton("清理", (dialog, w) -> { if (!editable()) return; releasePlayer();
                            io.execute(() -> { ChunkStore.clearUploaded(this); handler.post(() -> { librarySignature = ""; loadLibrary(); }); }); }).show(); break;
                case 6: startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName()))); break;
                default: break;
            }
        }).setNegativeButton("关闭", null).show();
    }
    private void configure() {
        if (!editable()) return;
        AlertDialog[] editor = new AlertDialog[1];
        LinearLayout form = column(22);
        CheckBox cloud = new CheckBox(this); cloud.setText("启用云端同步"); cloud.setChecked(true); form.addView(cloud);
        EditText url = input("Tailscale Serve HTTPS 根地址", false); url.setText(vault.preferences().getString("server", "")); form.addView(url);
        EditText token = input("上传令牌（留空保留已有令牌）", true); form.addView(token);
        form.addView(button("导入服务器连接配置", () -> {
            if (editor[0] != null) editor[0].dismiss();
            startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("application/json"), 22);
        }, false));
        CheckBox wifi = new CheckBox(this); wifi.setText("仅通过非计费网络上传"); wifi.setChecked(vault.preferences().getBoolean("wifi-only", false)); form.addView(wifi);
        form.addView(label("音频始终先在手机加密。密钥备份成功后，才会向服务器同步密文。", 13, MUTED));
        ScrollView wrapper = new ScrollView(this); wrapper.addView(form);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("云端同步").setView(wrapper).setNegativeButton("取消", null).setPositiveButton("保存", null).create();
        editor[0] = dialog;
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            try {
                if (cloud.isChecked()) {
                    String endpoint = Uploader.validateUrl(url.getText().toString()); String credential = token.getText().toString().trim();
                    if (credential.isEmpty() && !vault.preferences().contains("upload-token")) throw new IllegalArgumentException("请输入服务器上传令牌");
                    if (!credential.isEmpty() && !credential.matches("[A-Za-z0-9_-]{32,256}")) throw new IllegalArgumentException("令牌应为 32～256 位字母、数字、下划线或连字符");
                    vault.initialize(""); if (!credential.isEmpty()) vault.saveToken(credential);
                    if (!vault.preferences().edit().putString("server", endpoint).putBoolean("wifi-only", wifi.isChecked()).putBoolean("cloud-enabled", true).commit()) throw new java.io.IOException("无法保存配置");
                } else vault.preferences().edit().putBoolean("cloud-enabled", false).apply();
                UploadWorker.periodic(this); UploadWorker.schedule(this); dialog.dismiss(); update();
                if (cloud.isChecked() && !vault.preferences().getBoolean("backed-up", false)) exportKey(); else toast("设置已保存");
            } catch (Exception e) { toast(e.getMessage() == null ? "配置保存失败" : e.getMessage()); }
        })); dialog.show();
    }
    private void importKey() {
        if (vault.recordingConfigured()) { toast("已有录音密钥，为保护本地录音，不能替换"); return; }
        EditText key = input("Base64 恢复密钥", true);
        new AlertDialog.Builder(this).setTitle("导入恢复密钥").setView(key).setNegativeButton("取消", null).setPositiveButton("导入", (d, w) -> {
            try { if (key.getText().toString().trim().isEmpty()) throw new IllegalArgumentException(); vault.initialize(key.getText().toString()); toast("密钥已导入，请保留原备份"); }
            catch (Exception e) { toast("密钥导入失败，请检查格式"); }
        }).show();
    }
    private void exportKey() {
        if (!editable()) return;
        try { vault.initialize(""); } catch (Exception e) { toast("无法初始化密钥"); return; }
        new AlertDialog.Builder(this).setTitle("备份恢复密钥").setMessage("恢复文件可解密你的全部录音。请保存到你控制的安全位置，并额外保留离线副本。不要放到录音存储服务器或分享给其他人。")
            .setNegativeButton("取消", null).setPositiveButton("选择保存位置", (d, w) -> startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                .setType("text/plain").putExtra(Intent.EXTRA_TITLE, "recorder-recovery-key.txt"), 20)).show();
    }
    private void loadLibrary() {
        if (libraryBusy || io.isShutdown()) return; libraryBusy = true;
        io.execute(() -> {
            try {
                List<RecordingLibrary.Entry> entries = RecordingLibrary.list(this); long bytes = ChunkStore.bytes(this); int pending = ChunkStore.pending(this);
                StringBuilder signature = new StringBuilder(); for (RecordingLibrary.Entry e : entries) signature.append(e.id).append(':').append(e.bytes).append(':').append(e.uploaded).append(';');
                handler.post(() -> {
                    libraryBusy = false; if (isDestroyed()) return;
                    storage.setText(String.format(Locale.CHINA, "本地 %.1f / 256 MB%s", bytes / 1048576.0, vault.cloudEnabled() ? " · 待同步 " + pending + " 段" : " · " + entries.size() + " 场录音"));
                    capacity.setProgress((int) (bytes / 1048576));
                    if (getSystemService(KeyguardManager.class).isKeyguardLocked()) return;
                    if (!signature.toString().equals(librarySignature) || librarySignature.isEmpty()) { librarySignature = signature.toString(); renderLibrary(entries); }
                });
            } catch (Exception e) { handler.post(() -> { libraryBusy = false; storage.setText("无法读取本地存储"); }); }
        });
    }
    private void renderLibrary(List<RecordingLibrary.Entry> entries) {
        library.removeAllViews();
        if (entries.isEmpty()) { library.addView(label("还没有录音\n点击上方按钮开始，录音会加密保存在手机中。", 14, MUTED)); return; }
        int shown = 0;
        for (RecordingLibrary.Entry entry : entries) {
            if (shown++ >= 20) break;
            LinearLayout item = card(library); item.setPadding(dp(16), dp(10), dp(16), dp(10));
            item.addView(label(new SimpleDateFormat("MM月dd日  HH:mm", Locale.CHINA).format(new java.util.Date(entry.time)), 17, INK));
            String saved = entry.uploaded == entry.chunks.length ? "云端已保存" : "本地已加密";
            item.addView(label(String.format(Locale.CHINA, "%s · %.1f MB · 播放 / 导出  ›", saved, entry.bytes / 1048576.0), 12, MUTED));
            item.setContentDescription("录音 " + new java.util.Date(entry.time) + "，点击播放或导出"); item.setOnClickListener(v -> unlocked(() -> recordingActions(entry)));
        }
        if (entries.size() > 20) library.addView(button("查看更早录音（共 " + entries.size() + " 场）", () -> unlocked(() -> {
            String[] names = new String[entries.size()]; for (int i = 0; i < names.length; i++) names[i] = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA).format(new java.util.Date(entries.get(i).time));
            new AlertDialog.Builder(this).setTitle("全部本地录音").setItems(names, (d, w) -> recordingActions(entries.get(w))).setNegativeButton("关闭", null).show();
        }), false));
    }
    private void recordingActions(RecordingLibrary.Entry entry) {
        if (!editable()) return;
        new AlertDialog.Builder(this).setTitle("本地录音").setItems(new String[]{"播放（内存解密）", "导出 AAC 音频", "删除本地录音"}, (d, w) -> {
            if (w == 0) play(entry);
            else if (w == 1) new AlertDialog.Builder(this).setTitle("导出音频").setMessage("导出的 AAC 文件是明文，可由其他播放器打开。请选择你控制的保存位置。意外中断的录音只导出已保存的连续片段。")
                .setNegativeButton("取消", null).setPositiveButton("选择位置", (dialog, which) -> { exportSession = entry.id;
                    startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("audio/aac")
                        .putExtra(Intent.EXTRA_TITLE, "录音-" + new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(new java.util.Date(entry.time)) + ".aac"), 21); }).show();
            else new AlertDialog.Builder(this).setTitle("删除本地录音？").setMessage("这会删除整场录音的手机副本。未上传的片段将无法恢复，云端副本不会删除。")
                .setNegativeButton("取消", null).setPositiveButton("删除", (dialog, which) -> { if (!editable()) return; releasePlayer();
                    io.execute(() -> { boolean ok = RecordingLibrary.delete(entry); handler.post(() -> { librarySignature = ""; loadLibrary(); toast(ok ? "本地录音已删除" : "部分文件删除失败，请重试"); }); }); }).show();
        }).setNegativeButton("关闭", null).show();
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
        if (request == 22) {
            if (!editable()) return;
            try (java.io.InputStream in = getContentResolver().openInputStream(destination)) {
                if (in == null) throw new java.io.IOException();
                java.io.ByteArrayOutputStream contents = new java.io.ByteArrayOutputStream();
                byte[] block = new byte[1024]; int count;
                while (contents.size() <= 8192 && (count = in.read(block, 0, Math.min(block.length, 8193 - contents.size()))) != -1) contents.write(block, 0, count);
                byte[] bytes = contents.toByteArray(); if (bytes.length > 8192) throw new java.io.IOException();
                org.json.JSONObject config = new org.json.JSONObject(new String(bytes, StandardCharsets.UTF_8));
                String server = Uploader.validateUrl(config.getString("server")); String token = config.getString("token");
                if (!token.matches("[A-Za-z0-9_-]{32,256}")) throw new java.io.IOException();
                vault.initialize(""); vault.saveToken(token);
                if (!vault.preferences().edit().putString("server", server).putBoolean("cloud-enabled", true).commit()) throw new java.io.IOException();
                UploadWorker.periodic(this); UploadWorker.schedule(this);
                toast("连接配置已导入。关闭设置后，首页会显示同步状态。");
                if (!vault.preferences().getBoolean("backed-up", false)) exportKey();
            } catch (Exception e) { toast("连接配置导入失败，请选择服务器生成的 JSON 文件"); }
        } else if (request == 20) io.execute(() -> {
            try {
                try (OutputStream out = getContentResolver().openOutputStream(destination, "wt")) { if (out == null) throw new java.io.IOException(); out.write((vault.recoveryKey() + "\n").getBytes(StandardCharsets.UTF_8)); out.flush(); }
                if (!vault.preferences().edit().putBoolean("backed-up", true).commit()) throw new java.io.IOException();
                UploadWorker.periodic(this); UploadWorker.schedule(this); handler.post(() -> toast("恢复密钥已备份"));
            } catch (Exception e) { handler.post(() -> toast("密钥备份失败，请重试")); }
        });
        else if (request == 21 && exportSession != null) {
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
    private int dp(int n) { return Math.round(n * getResources().getDisplayMetrics().density); }
    private LinearLayout column(int padding) { LinearLayout box = new LinearLayout(this); box.setOrientation(LinearLayout.VERTICAL); box.setPadding(dp(padding), dp(padding), dp(padding), dp(padding)); return box; }
    private LinearLayout row() { LinearLayout box = new LinearLayout(this); box.setOrientation(LinearLayout.HORIZONTAL); box.setGravity(Gravity.CENTER_VERTICAL); return box; }
    private LinearLayout.LayoutParams spaced() { LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2); p.topMargin = dp(10); p.bottomMargin = dp(10); return p; }
    private LinearLayout card(LinearLayout parent) { LinearLayout box = column(18); GradientDrawable bg = new GradientDrawable(); bg.setColor(Color.WHITE); bg.setCornerRadius(dp(22)); box.setBackground(bg); parent.addView(box, spaced()); return box; }
    private TextView label(String text, int size, int color) { TextView label = new TextView(this); label.setText(text); label.setTextSize(size); label.setTextColor(color); label.setPadding(0, dp(6), 0, dp(8)); return label; }
    private EditText input(String hint, boolean secret) { EditText field = new EditText(this); field.setHint(hint); field.setTextSize(14); field.setSingleLine(true); field.setInputType(InputType.TYPE_CLASS_TEXT | (secret ? InputType.TYPE_TEXT_VARIATION_PASSWORD : InputType.TYPE_TEXT_VARIATION_URI)); return field; }
    private Button button(String text, Runnable action, boolean primary) {
        Button button = new Button(this); button.setText(text); button.setAllCaps(false); button.setTextSize(14); button.setMinHeight(dp(48));
        GradientDrawable bg = new GradientDrawable(); bg.setColor(primary ? TEAL : 0xffe8f0ed); bg.setCornerRadius(dp(14)); button.setBackground(bg); button.setTextColor(primary ? Color.WHITE : TEAL);
        button.setPadding(dp(14), dp(8), dp(14), dp(8)); button.setOnClickListener(v -> action.run()); return button;
    }
    private void styleChoice(Button button, boolean selected) { button.setBackgroundTintList(ColorStateList.valueOf(selected ? TEAL : 0xffe8f0ed)); button.setTextColor(selected ? Color.WHITE : TEAL); }
    private void toast(String message) { android.widget.Toast.makeText(this, message == null ? "操作失败" : message, android.widget.Toast.LENGTH_LONG).show(); }
}
