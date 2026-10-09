package cn.personal.recorder;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.InputType;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONObject;

/** Separate from the lock-screen recording window; never starts recording on resume. */
public final class SettingsActivity extends Activity {
    private Ui ui;
    private Vault vault;
    private TextView storage, pending, keyStatus;
    private ProgressBar capacity;
    private Switch cloud, wifi;
    private boolean refreshing;
    private boolean modelsReady;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable refresh = new Runnable() {
        @Override public void run() { refreshInfo(); handler.postDelayed(this, 3000); }
    };
    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved); ui = new Ui(this); vault = new Vault(this); buildScreen();
    }
    private void buildScreen() {
        LinearLayout root = ui.screen();
        LinearLayout header = ui.row(); header.addView(ui.button("返回", this::finish, false));
        TextView title = ui.title("设置", 28); title.setPadding(ui.dp(18), 0, 0, 0); header.addView(title); root.addView(header);
        root.addView(ui.label("录音、同步与安全，按你的习惯设置", 13, ui.muted));

        LinearLayout recording = ui.card(root); recording.addView(ui.title("录音", 17));
        toggle(recording, "打开应用自动录音", "双按电源键进入后自动开始", vault.preferences().getBoolean("ready", true),
            enabled -> vault.preferences().edit().putBoolean("ready", enabled).apply());
        recording.addView(ui.label("约每 30 秒加密保存，停止时保存尾段。", 12, ui.muted));

        LinearLayout organization = ui.card(root); organization.addView(ui.title("分类与整理", 17));
        ui.action(organization, "录音分类", "设置快速录音默认分类、模板与 AI 文字权限", () -> startActivity(new Intent(this, CategoryActivity.class)));
        organization.addView(ui.label("历史录音默认归入“未分类”；分类目录与每场录音的归属均加密同步。", 12, ui.muted));

        LinearLayout network = ui.card(root); network.addView(ui.title("保存与同步", 17));
        cloud = toggle(network, "云端同步", "关闭后仅保存在手机，开启后补传本地片段", vault.cloudEnabled(), enabled -> {
            if (!editable()) { rebuildLater(); return; }
            if (enabled && (!vault.preferences().contains("server") || !vault.preferences().contains("upload-token"))) {
                rebuildLater(); configure(); return;
            }
            vault.preferences().edit().putBoolean("cloud-enabled", enabled).apply();
            UploadWorker.periodic(this); UploadWorker.schedule(this);
            if (enabled && !vault.preferences().getBoolean("backed-up", false)) exportKey();
        });
        ui.action(network, "服务器连接", vault.preferences().contains("server") ? "已配置 · 点击修改" : "尚未配置", this::configure);
        ui.action(network, "导入连接配置", "选择服务器生成的 JSON 文件", () -> {
            if (editable()) startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("application/json"), 22);
        });
        wifi = toggle(network, "仅非计费网络上传", "移动网络下继续本地保存", vault.preferences().getBoolean("wifi-only", false), enabled -> {
            if (!editable()) { rebuildLater(); return; }
            vault.preferences().edit().putBoolean("wifi-only", enabled).apply(); UploadWorker.periodic(this); UploadWorker.schedule(this);
        });
        pending = ui.label("", 12, ui.muted); network.addView(pending);
        ui.action(network, "立即补传", "上传未确认保存的密文", () -> {
            if (!vault.configured()) { toast("请开启同步、配置服务器并备份密钥"); return; }
            UploadWorker.schedule(this); toast("已安排补传");
        });

        LinearLayout safety = ui.card(root); safety.addView(ui.title("密钥与存储", 17));
        keyStatus = ui.label("", 12, ui.muted); safety.addView(keyStatus);
        ui.action(safety, "备份恢复密钥", "换机或卸载后，用于解密录音", this::exportKey);
        if (!vault.recordingConfigured()) ui.action(safety, "导入已有密钥", "仅首次使用时可导入", this::importKey);
        storage = ui.label("正在读取存储…", 13, ui.muted); safety.addView(storage);
        capacity = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal); capacity.setMax(256);
        capacity.setProgressTintList(ColorStateList.valueOf(ui.accent)); safety.addView(capacity);
        ui.action(safety, "清理已上传副本", "未上传片段会保留", this::clearUploaded);

        LinearLayout ai = ui.card(root); ai.addView(ui.title("文字与 AI", 17));
        modelsReady = ModelManager.ready(this);
        ui.action(ai, "离线文字与发言人模型", modelsReady ? "已安装 · 可离线提取" : "首次需下载约 " + String.format(Locale.CHINA, "%.0f MB", ModelManager.downloadBytes(this) / 1048576.0), this::speechModels);
        ui.action(ai, "开源模型与许可", "SenseVoiceSmall、pyannote、3D-Speaker", this::modelNotices);
        ui.action(ai, "DeepSeek Key", vault.aiConfigured() ? "已加密保存 · 点击修改" : "由你提供，仅用于文字总结", this::configureAi);
        ai.addView(ui.label("V4.1 Flash · 本地转写。生成总结前需本分类已允许 AI，并由你逐场确认；仅发送文字，服务商会读取内容。", 12, ui.muted));
        ui.action(ai, "清除 DeepSeek Key", "不影响录音、文字和已有总结", () -> {
            if (AnalysisService.busy) { toast("请先结束当前文字处理"); return; }
            new AlertDialog.Builder(this).setTitle("清除手机上的 Key？").setNegativeButton("取消", null).setPositiveButton("清除", (d, w) -> { vault.removeAiKey(); buildScreen(); }).show();
        });

        LinearLayout system = ui.card(root); system.addView(ui.title("应用", 17));
        system.addView(ui.label("外观 · 跟随系统浅色 / 深色模式", 14, ui.ink));
        ui.action(system, "权限与后台运行", "麦克风、通知与电池设置", () -> startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName()))));
        ui.action(system, "锁屏启动排查", "检查双按启动与录音权限", () -> new AlertDialog.Builder(this).setTitle("锁屏启动排查")
            .setMessage("先在解锁状态授予麦克风权限，再打开自动录音。将 MagicOS 电源键双按映射到“加密录音”，并检查应用启动/电池设置。\n\n如果锁屏双按只显示系统解锁页，没有出现录音首页，系统可能尚未把启动请求交给应用。若已出现首页但录音失败，可导出诊断信息协助排查。\n\n手机重启后第一次解锁前，加密配置尚不可用。")
            .setNegativeButton("关闭", null).setPositiveButton("导出诊断", (d, w) -> exportDiagnostics()).show());
        system.addView(ui.label("加密录音 " + BuildConfig.VERSION_NAME + " · 音频仅以密文保存在服务器", 12, ui.muted));
        refreshInfo();
    }
    private interface ToggleAction { void change(boolean enabled); }
    private Switch toggle(LinearLayout parent, String title, String subtitle, boolean value, ToggleAction action) {
        LinearLayout row = ui.row(); row.setPadding(0, ui.dp(12), 0, ui.dp(12));
        LinearLayout labels = ui.column(0); labels.addView(ui.label(title, 16, ui.ink)); labels.addView(ui.label(subtitle, 12, ui.muted));
        row.addView(labels, new LinearLayout.LayoutParams(0, -2, 1));
        Switch control = new Switch(this); control.setContentDescription(title); control.setMinWidth(ui.dp(48)); control.setMinHeight(ui.dp(48)); control.setChecked(value);
        control.setOnCheckedChangeListener((v, enabled) -> action.change(enabled)); row.addView(control); parent.addView(row); return control;
    }
    @Override protected void onResume() { super.onResume(); handler.removeCallbacks(refresh); handler.post(refresh); }
    @Override protected void onPause() { handler.removeCallbacks(refresh); super.onPause(); }
    @Override protected void onDestroy() { io.shutdown(); super.onDestroy(); }
    private void rebuildLater() { handler.post(() -> { if (!isDestroyed()) buildScreen(); }); }
    private boolean editable() { if (RecordingService.active) { toast("请先停止录音再修改此设置"); return false; } return true; }
    private void refreshInfo() {
        if (modelsReady != ModelManager.ready(this)) { buildScreen(); return; }
        cloud.setEnabled(!RecordingService.active); wifi.setEnabled(!RecordingService.active);
        keyStatus.setText(vault.preferences().getBoolean("backed-up", false) ? "恢复密钥已备份" : "尚未备份 · 建议尽早保存独立副本");
        if (refreshing || io.isShutdown()) return; refreshing = true;
        io.execute(() -> {
            try {
                long bytes = ChunkStore.bytes(this); int count = ChunkStore.pending(this) + TextStore.pending(this) + (CategoryStore.hasPending(this) ? 1 : 0);
                handler.post(() -> { refreshing = false; if (isDestroyed()) return;
                    storage.setText(String.format(Locale.CHINA, "本地 %.1f / 256 MB", bytes / 1048576.0)); capacity.setProgress((int) (bytes / 1048576));
                    pending.setText(!vault.cloudEnabled() ? "仅本地保存" : (!vault.configured() ? "服务器配置与密钥备份完成后开始同步" : "待同步 " + count + " 项 · " + Uploader.status));
                });
            } catch (Exception e) { handler.post(() -> refreshing = false); }
        });
    }
    private EditText input(String label, boolean secret) {
        EditText field = new EditText(this); field.setHint(label); field.setTextSize(14); field.setSingleLine(true); field.setTextColor(ui.ink); field.setHintTextColor(ui.muted);
        field.setInputType(InputType.TYPE_CLASS_TEXT | (secret ? InputType.TYPE_TEXT_VARIATION_PASSWORD : InputType.TYPE_TEXT_VARIATION_URI)); return field;
    }
    private void configureAi() {
        if (AnalysisService.busy) { toast("请先结束当前文字处理"); return; }
        LinearLayout form = ui.column(20); EditText key = input("输入你的 DeepSeek API Key", true); key.setSaveEnabled(false); form.addView(key);
        form.addView(ui.label("Key 只加密保存在手机，不传到你的录音存储服务器。使用官方 deepseek-flash 模型，仅在你点击总结后发送文字并计费。", 12, ui.muted));
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("DeepSeek Key").setView(form).setNegativeButton("取消", null).setPositiveButton("保存", null).create();
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            if (AnalysisService.busy) return;
            try { vault.initialize(""); vault.saveAiKey(key.getText().toString().trim()); key.setText(""); dialog.dismiss(); buildScreen(); toast("Key 已加密保存"); }
            catch (Exception e) { toast("Key 保存失败，请检查格式"); }
        })); dialog.show();
    }
    private void speechModels() {
        if (AnalysisService.busy) { toast(AnalysisService.state); return; }
        new AlertDialog.Builder(this).setTitle("离线文字与发言人模型")
            .setMessage("下载约 " + String.format(Locale.CHINA, "%.0f MB", ModelManager.downloadBytes(this) / 1048576.0) + "，安装需约 700 MB 可用空间，建议使用 Wi-Fi。模型安装后可断网转写，不上传音频。也可先从版本发布页下载模型 ZIP，再导入。")
            .setNegativeButton("取消", null).setNeutralButton("导入模型包", (d, w) -> startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("application/zip"), 24))
            .setPositiveButton("下载模型", (d, w) -> {
                try { startForegroundService(new Intent(this, AnalysisService.class).setAction(AnalysisService.MODELS)); toast("已开始下载，进度见通知栏"); }
                catch (Exception e) { toast("系统未允许下载，请重新打开设置"); }
            }).show();
    }
    private void modelNotices() {
        try {
            ByteArrayOutputStream contents = new ByteArrayOutputStream();
            for (String name : new String[]{"NOTICE.txt", "FunASR-MODEL-LICENSE.txt", "pyannote-LICENSE.txt", "Apache-2.0.txt", "onnxruntime-LICENSE.txt"}) {
                contents.write(("\n\n" + name + "\n\n").getBytes(StandardCharsets.UTF_8));
                try (InputStream in = getAssets().open("speech-licenses/" + name)) { byte[] block = new byte[2048]; int n; while ((n = in.read(block)) != -1) contents.write(block, 0, n); }
            }
            TextView text = ui.label(contents.toString("UTF-8"), 13, ui.ink); text.setTextIsSelectable(true); text.setPadding(ui.dp(20), ui.dp(12), ui.dp(20), ui.dp(12));
            ScrollView scroll = new ScrollView(this); scroll.addView(text);
            new AlertDialog.Builder(this).setTitle("开源模型与许可").setView(scroll).setPositiveButton("关闭", null).show();
        } catch (Exception e) { toast("无法读取许可说明"); }
    }
    private void configure() {
        if (!editable()) return;
        LinearLayout form = ui.column(20);
        form.addView(ui.label("服务器 HTTPS 地址", 13, ui.muted));
        EditText url = input("https://设备名.tailnet.ts.net", false); String oldUrl = vault.preferences().getString("server", ""); url.setText(oldUrl); form.addView(url);
        form.addView(ui.label("访问令牌", 13, ui.muted)); EditText token = input("已有令牌可留空保留", true); form.addView(token);
        form.addView(ui.label("保存后开启云端同步。恢复密钥单独备份，服务器不持有录音密钥。", 12, ui.muted));
        ScrollView wrapper = new ScrollView(this); wrapper.addView(form);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("服务器连接").setView(wrapper).setNegativeButton("取消", null).setPositiveButton("保存", null).create();
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            if (!editable()) return;
            try {
                String endpoint = Uploader.validateUrl(url.getText().toString()); String credential = token.getText().toString().trim();
                if (credential.isEmpty() && (!vault.preferences().contains("upload-token") || !endpoint.equals(oldUrl))) throw new IllegalArgumentException("首次连接或更换服务器时，请填写访问令牌");
                if (!credential.isEmpty() && !credential.matches("[A-Za-z0-9_-]{32,256}")) throw new IllegalArgumentException("令牌应为 32～256 位字母、数字、下划线或连字符");
                vault.initialize(""); if (!credential.isEmpty()) vault.saveToken(credential);
                if (!vault.preferences().edit().putString("server", endpoint).putBoolean("cloud-enabled", true).commit()) throw new java.io.IOException();
                UploadWorker.periodic(this); UploadWorker.schedule(this); dialog.dismiss(); buildScreen();
                if (!vault.preferences().getBoolean("backed-up", false)) exportKey(); else toast("连接设置已保存");
            } catch (Exception e) { toast(e.getMessage() == null ? "保存失败，请重试" : e.getMessage()); }
        })); dialog.show();
    }
    private void exportKey() {
        if (!editable()) return;
        try { vault.initialize(""); } catch (Exception e) { toast("无法初始化录音密钥"); return; }
        new AlertDialog.Builder(this).setTitle("备份恢复密钥")
            .setMessage("备份文件能解密全部录音，请保存到你控制的安全位置并保留离线副本，不要放到录音存储服务器。")
            .setNegativeButton("取消", null).setPositiveButton("选择位置", (d, w) -> startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                .setType("text/plain").putExtra(Intent.EXTRA_TITLE, "recorder-recovery-key.txt"), 20)).show();
    }
    private void importKey() {
        if (!editable() || vault.recordingConfigured()) return;
        LinearLayout form = ui.column(20); EditText key = input("Base64 恢复密钥", true); form.addView(key);
        new AlertDialog.Builder(this).setTitle("导入已有密钥").setView(form).setNegativeButton("取消", null).setPositiveButton("导入", (d, w) -> {
            try { if (key.getText().toString().trim().isEmpty()) throw new IllegalArgumentException(); vault.initialize(key.getText().toString()); buildScreen(); toast("密钥已导入，请保留原备份"); }
            catch (Exception e) { toast("导入失败，请检查密钥格式"); }
        }).show();
    }
    private void clearUploaded() {
        if (AnalysisService.transcribing) { toast("请先结束文字提取再清理音频"); return; }
        if (!editable()) return;
        new AlertDialog.Builder(this).setTitle("清理已上传副本？").setMessage("仅删除服务器确认保存的片段。清理后手机端录音可能不完整；请确保云端备份可靠。")
            .setNegativeButton("取消", null).setPositiveButton("清理", (d, w) -> { if (!editable()) return;
                if (AnalysisService.transcribing) { toast("请先结束文字提取"); return; }
                io.execute(() -> { ChunkStore.clearUploaded(this); handler.post(() -> { refreshInfo(); toast("清理完成"); }); }); }).show();
    }
    private void exportDiagnostics() {
        startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("text/plain")
            .putExtra(Intent.EXTRA_TITLE, "recorder-diagnostics.txt"), 23);
    }
    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (result != RESULT_OK || data == null || data.getData() == null) return;
        Uri destination = data.getData();
        if (request == 24) {
            if (AnalysisService.busy) { toast("请先结束当前处理"); return; }
            try { getContentResolver().takePersistableUriPermission(destination, Intent.FLAG_GRANT_READ_URI_PERMISSION); } catch (SecurityException ignored) { }
            try { startForegroundService(new Intent(this, AnalysisService.class).setAction(AnalysisService.IMPORT).setData(destination)); }
            catch (Exception e) { toast("无法开始模型导入，请重试"); } return;
        }
        if (request == 22 && !editable()) return;
        io.execute(() -> {
            try {
                if (request == 22) {
                    byte[] bytes;
                    try (InputStream in = getContentResolver().openInputStream(destination)) {
                        if (in == null) throw new java.io.IOException(); ByteArrayOutputStream contents = new ByteArrayOutputStream(); byte[] block = new byte[1024]; int count;
                        while (contents.size() <= 8192 && (count = in.read(block, 0, Math.min(block.length, 8193 - contents.size()))) != -1) contents.write(block, 0, count);
                        bytes = contents.toByteArray(); Arrays.fill(block, (byte) 0); if (bytes.length > 8192) throw new java.io.IOException();
                    }
                    JSONObject config;
                    try { config = new JSONObject(new String(bytes, StandardCharsets.UTF_8)); } finally { Arrays.fill(bytes, (byte) 0); }
                    String server = Uploader.validateUrl(config.getString("server")); String token = config.getString("token");
                    if (!token.matches("[A-Za-z0-9_-]{32,256}") || RecordingService.active) throw new java.io.IOException();
                    vault.initialize(""); vault.saveToken(token);
                    if (!vault.preferences().edit().putString("server", server).putBoolean("cloud-enabled", true).commit()) throw new java.io.IOException();
                    UploadWorker.periodic(this); UploadWorker.schedule(this);
                    handler.post(() -> { if (isDestroyed()) return; buildScreen(); toast("连接配置已导入"); if (!vault.preferences().getBoolean("backed-up", false)) exportKey(); });
                } else if (request == 20 || request == 23) {
                    String content = request == 20 ? vault.recoveryKey() + "\n" : LaunchDiagnostics.describe(this);
                    try (OutputStream out = getContentResolver().openOutputStream(destination, "wt")) {
                        if (out == null) throw new java.io.IOException(); out.write(content.getBytes(StandardCharsets.UTF_8)); out.flush();
                    }
                    if (request == 20) {
                        if (!vault.preferences().edit().putBoolean("backed-up", true).commit()) throw new java.io.IOException();
                        UploadWorker.periodic(this); UploadWorker.schedule(this);
                    }
                    handler.post(() -> { if (!isDestroyed()) { buildScreen(); toast(request == 20 ? "恢复密钥已备份" : "诊断已导出，不含令牌、密钥或录音内容"); } });
                }
            } catch (Exception e) { handler.post(() -> toast("操作失败，请检查文件并重试")); }
        });
    }
    private void toast(String message) { android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_LONG).show(); }
}
