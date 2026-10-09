package cn.personal.recorder;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.InputType;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

public final class MainActivity extends Activity {
    private Vault vault;
    private TextView status;
    private boolean launchHandled;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable refresh = new Runnable() {
        @Override public void run() {
            status.setText(getString(R.string.status_detail, RecordingService.state, Uploader.status,
                ChunkStore.pending(MainActivity.this), ChunkStore.bytes(MainActivity.this) / 1024 / 1024));
            handler.postDelayed(this, 2000);
        }
    };
    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        setShowWhenLocked(true); setTurnScreenOn(true);
        vault = new Vault(this);
        launchHandled = (saved != null && saved.getBoolean("handled")) || getIntent().getBooleanExtra("settings", false);
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = column(); scroll.addView(root); setContentView(scroll);
        scroll.setOnApplyWindowInsetsListener((v, insets) -> {
            android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return insets;
        });
        TextView title = label("加密录音", 28); root.addView(title);
        root.addView(label("完成首次配置后，每次打开自动开始。录音期间可熄屏，通知栏可停止。", 16));
        status = label("", 18); root.addView(status);
        root.addView(button("开始录音", this::startRecording));
        root.addView(button("停止录音", () -> {
            launchHandled = true;
            if (RecordingService.active) startService(new Intent(this, RecordingService.class).setAction(RecordingService.STOP));
        }));
        root.addView(button("服务器与上传设置", this::configure));
        root.addView(button("导出恢复密钥", this::exportKey));
        root.addView(button("补传本地密文", () -> { UploadWorker.schedule(this); toast("已安排补传；系统可能延后执行"); }));
        root.addView(button("清理已确认上传的本地副本", () -> new AlertDialog.Builder(this)
            .setMessage("仅删除服务器已确认保存的本地密文。未上传片段会保留；请先确保云端备份可靠。")
            .setNegativeButton("取消", null).setPositiveButton("清理", (d, w) -> { ChunkStore.clearUploaded(this); toast("清理完成"); }).show()));
        root.addView(button("打开应用系统设置", () -> startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.parse("package:" + getPackageName())))));
        root.addView(label("密钥只保存在手机和你的备份文件中。服务器无法解密。断网不影响本地保存；达到容量上限会停止录音。", 14));
    }
    @Override protected void onResume() {
        super.onResume(); handler.post(refresh);
        if (!launchHandled && vault.ready()) { launchHandled = true; startRecording(); }
    }
    @Override protected void onPause() { handler.removeCallbacks(refresh); super.onPause(); }
    @Override protected void onSaveInstanceState(Bundle out) { out.putBoolean("handled", launchHandled); super.onSaveInstanceState(out); }
    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent); setIntent(intent);
        launchHandled = intent.getBooleanExtra("settings", false);
    }
    private void startRecording() {
        launchHandled = true;
        if (!vault.configured()) { toast("请先配置服务器并导出恢复密钥"); configure(); return; }
        if (RecordingService.active) { toast("已经在录音"); return; }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            if (Build.VERSION.SDK_INT >= 33) requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS}, 10);
            else requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 10);
            return;
        }
        try { startForegroundService(new Intent(this, RecordingService.class)); }
        catch (Exception e) { toast("启动受系统限制，请解锁后重新打开应用"); }
    }
    @Override public void onRequestPermissionsResult(int request, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(request, permissions, results);
        if (request == 10 && checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) startRecording();
        else if (request == 10) toast("录音需要麦克风权限，可在应用系统设置中开启");
    }
    private void configure() {
        if (RecordingService.active) { toast("请先停止录音，再修改设置"); return; }
        LinearLayout form = column();
        EditText url = input("Tailscale Serve 的 HTTPS 根地址，例如 https://recorder.your-tailnet.ts.net", false);
        url.setText(vault.preferences().getString("server", "")); form.addView(url);
        EditText token = input("上传令牌（留空保留已有令牌）", true); form.addView(token);
        EditText restore = input("已有恢复密钥可在首次初始化时填入；留空生成新密钥", true);
        if (!vault.preferences().contains("recording-key")) form.addView(restore);
        CheckBox wifi = new CheckBox(this); wifi.setText("仅通过非计费网络上传");
        wifi.setChecked(vault.preferences().getBoolean("wifi-only", false)); form.addView(wifi);
        CheckBox enable = new CheckBox(this); enable.setText("启用打开应用自动录音");
        enable.setChecked(vault.preferences().getBoolean("ready", false)); form.addView(enable);
        form.addView(label("首次保存后请导出恢复密钥。密钥备份成功前，自动录音不会启用。", 14));
        ScrollView wrapper = new ScrollView(this); wrapper.addView(form);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("首次配置 / 设置")
            .setView(wrapper).setNegativeButton("取消", null).setPositiveButton("保存", null).create();
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            try {
                String endpoint = Uploader.validateUrl(url.getText().toString());
                String credential = token.getText().toString().trim();
                if (credential.isEmpty() && !vault.preferences().contains("upload-token")) throw new IllegalArgumentException("请输入服务器上传令牌");
                if (!credential.isEmpty() && !credential.matches("[A-Za-z0-9_-]{32,256}")) throw new IllegalArgumentException("令牌应为 32～256 位字母、数字、下划线或连字符");
                vault.initialize(restore.getText().toString());
                if (!credential.isEmpty()) vault.saveToken(credential);
                if (!vault.preferences().edit().putString("server", endpoint).putBoolean("wifi-only", wifi.isChecked())
                    .putBoolean("ready", enable.isChecked()).commit()) throw new java.io.IOException("无法保存配置");
                UploadWorker.periodic(this); UploadWorker.schedule(this); dialog.dismiss();
                if (!vault.preferences().getBoolean("backed-up", false)) exportKey(); else toast("设置已保存");
            } catch (Exception e) { toast(e.getMessage() == null ? "配置保存失败" : e.getMessage()); }
        })); dialog.show();
    }
    private void exportKey() {
        if (RecordingService.active) { toast("请先停止录音，再导出密钥"); return; }
        if (!vault.preferences().contains("recording-key")) { toast("请先保存首次配置"); return; }
        new AlertDialog.Builder(this).setTitle("备份解密密钥")
            .setMessage("恢复文件可解密你的全部录音。请保存到你控制的安全位置，并额外保留离线副本。不要放到录音存储服务器或分享给其他人。")
            .setNegativeButton("取消", null).setPositiveButton("选择保存位置", (d, w) -> {
                Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                    .setType("text/plain").putExtra(Intent.EXTRA_TITLE, "recorder-recovery-key.txt");
                startActivityForResult(intent, 20);
            }).show();
    }
    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != 20 || result != RESULT_OK || data == null || data.getData() == null) return;
        try {
            try (OutputStream out = getContentResolver().openOutputStream(data.getData(), "wt")) {
                if (out == null) throw new java.io.IOException("无法打开备份文件");
                out.write((vault.recoveryKey() + "\n").getBytes(StandardCharsets.UTF_8)); out.flush();
            }
            if (!vault.preferences().edit().putBoolean("backed-up", true).commit()) throw new java.io.IOException("无法保存备份状态");
            UploadWorker.schedule(this);
            toast("恢复密钥已导出。现在可点击开始录音。");
        } catch (Exception e) { toast("密钥备份失败，请重试"); }
    }
    private LinearLayout column() {
        LinearLayout box = new LinearLayout(this); box.setOrientation(LinearLayout.VERTICAL); box.setPadding(24, 24, 24, 24); return box;
    }
    private TextView label(String text, int size) {
        TextView label = new TextView(this); label.setText(text); label.setTextSize(size);
        label.setTextColor(Color.rgb(30, 40, 50)); label.setPadding(0, 12, 0, 16); return label;
    }
    private EditText input(String hint, boolean secret) {
        EditText field = new EditText(this); field.setHint(hint); field.setSingleLine(true);
        field.setInputType(InputType.TYPE_CLASS_TEXT | (secret ? InputType.TYPE_TEXT_VARIATION_PASSWORD : InputType.TYPE_TEXT_VARIATION_URI)); return field;
    }
    private Button button(String text, Runnable action) {
        Button button = new Button(this); button.setText(text); button.setOnClickListener(v -> action.run()); return button;
    }
    private void toast(String message) { Toast.makeText(this, message, Toast.LENGTH_LONG).show(); }
}
