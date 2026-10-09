package cn.personal.recorder;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Category management lives behind Settings; no sensitive catalog values are exposed to the server. */
public final class CategoryActivity extends Activity {
    private Ui ui;
    private LinearLayout root, list;
    private CategoryStore.State state;
    private final ExecutorService io = Executors.newSingleThreadExecutor();

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved); ui = new Ui(this); root = ui.screen();
        LinearLayout header = ui.row(); header.addView(ui.button("返回", this::finish, false));
        TextView title = ui.title("录音分类", 25); title.setPadding(ui.dp(14), 0, 0, 0); header.addView(title); root.addView(header);
        root.addView(ui.label("新录音使用默认分类；历史录音可以随时调整。分类名称和分配记录只在本地及云端以密文保存。", 13, ui.muted));
        list = ui.column(0); root.addView(list);
        root.addView(ui.button("＋ 新建分类", this::add, true), ui.spaced());
        LinearLayout cloud = ui.card(root); cloud.addView(ui.title("加密备份", 17));
        cloud.addView(ui.label("服务器仅保存加密后的分类目录。恢复会覆盖手机当前分类名称、默认分类和历史录音分配。", 12, ui.muted));
        ui.action(cloud, "从服务器恢复分类目录", new Vault(this).configured() ? "下载并解密服务器上的加密备份" : "请先在设置中配置并开启云端同步", this::restoreCloud);
        render();
    }
    @Override protected void onDestroy() { io.shutdownNow(); super.onDestroy(); }

    private void render() {
        list.removeAllViews();
        try { state = CategoryStore.load(this); }
        catch (Exception e) { list.addView(ui.label("无法读取加密分类目录，请检查录音密钥。", 14, ui.danger)); return; }
        java.util.Map<String, Integer> counts = new java.util.HashMap<>();
        try { for (RecordingLibrary.Entry entry : RecordingLibrary.list(this)) counts.put(entry.categoryId, counts.getOrDefault(entry.categoryId, 0) + 1); }
        catch (Exception ignored) { }
        for (int i = 0; i < state.size(); i++) {
            org.json.JSONObject category = state.category(i); if (category == null) continue;
            String id = category.optString("id"), name = category.optString("name", "未分类");
            int assigned = counts.getOrDefault(id, 0);
            LinearLayout card = ui.card(list); LinearLayout row = ui.row(); row.setGravity(Gravity.CENTER_VERTICAL);
            LinearLayout labels = ui.column(0); labels.addView(ui.title(name, 17));
            String details = (id.equals(state.defaultId) ? "快速录音默认 · " : "") + templateName(category.optString("template")) + " · " + assigned + " 场" + (category.optBoolean("allowExternalAi") ? " · 允许 DeepSeek 读取文字" : " · 禁止外部 AI");
            labels.addView(ui.label(details, 12, ui.muted)); row.addView(labels, new LinearLayout.LayoutParams(0, -2, 1));
            row.addView(ui.button("管理", () -> actions(id), false)); card.addView(row);
        }
    }
    private void actions(String id) {
        org.json.JSONObject category = find(id); if (category == null) { render(); return; }
        String[] choices = id.equals(state.defaultId) ? new String[]{"编辑名称与模板", "管理外部 AI 权限"} : new String[]{"编辑名称与模板", "设为快速录音默认", "管理外部 AI 权限", "删除并重新分配录音"};
        new AlertDialog.Builder(this).setTitle(category.optString("name")).setItems(choices, (d, selected) -> {
            String option = choices[selected];
            if (option.startsWith("编辑") || option.startsWith("管理外部")) edit(id, option.startsWith("管理外部"));
            else if (option.startsWith("设为")) { try { CategoryStore.setDefault(this, id); render(); toast("快速录音默认分类已更新"); } catch (Exception e) { toast("保存失败"); } }
            else remove(id);
        }).setNegativeButton("关闭", null).show();
    }
    private void add() { edit(null, false); }
    private void edit(String id, boolean privacyOnly) {
        org.json.JSONObject existing = id == null ? null : find(id);
        LinearLayout form = ui.column(8);
        EditText name = new EditText(this); name.setSingleLine(true); name.setTextSize(16); name.setTextColor(ui.ink); name.setHint("分类名称"); name.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        if (!privacyOnly) { if (existing != null) name.setText(existing.optString("name")); form.addView(name); }
        String[] templates = {"通用记录", "隐私备忘", "会议纪要", "课堂笔记", "访谈调研"}; String[] values = {"standard", "private", "meeting", "classroom", "interview"};
        int initial = 0; if (existing != null) for (int i = 0; i < values.length; i++) if (values[i].equals(existing.optString("template"))) initial = i;
        final int[] selected = {initial};
        if (!privacyOnly) {
            TextView template = ui.label("总结模板：" + templates[initial] + "（点击更改）", 14, ui.ink); template.setPadding(0, ui.dp(12), 0, ui.dp(12));
            template.setOnClickListener(v -> new AlertDialog.Builder(this).setTitle("选择总结模板").setSingleChoiceItems(templates, selected[0], (dialog, which) -> { selected[0] = which; template.setText("总结模板：" + templates[which] + "（点击更改）"); dialog.dismiss(); }).setNegativeButton("取消", null).show()); form.addView(template);
        }
        Switch allow = new Switch(this); allow.setText("允许 DeepSeek 读取本分类的文字"); allow.setTextColor(ui.ink); allow.setChecked(existing != null && existing.optBoolean("allowExternalAi")); form.addView(allow);
        form.addView(ui.label("只影响你手动点击“生成 AI 总结”时的文字发送权限；不会自动上传转写。关闭后，原有总结仍可查看。", 12, ui.muted));
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle(existing == null ? "新建分类" : "编辑分类").setView(form).setNegativeButton("取消", null).setPositiveButton("保存", null).create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            try {
                if (existing == null) CategoryStore.add(this, name.getText().toString(), values[selected[0]], allow.isChecked());
                else if (privacyOnly) CategoryStore.edit(this, id, existing.optString("name"), existing.optString("template", "standard"), allow.isChecked());
                else CategoryStore.edit(this, id, name.getText().toString(), values[selected[0]], allow.isChecked());
                dialog.dismiss(); render(); toast("分类已加密保存");
            } catch (Exception e) { toast(e.getMessage() == null ? "保存失败" : e.getMessage()); }
        })); dialog.show();
    }
    private void remove(String id) {
        String[] names = new String[state.size() - 1], ids = new String[state.size() - 1]; int p = 0;
        for (int i = 0; i < state.size(); i++) { org.json.JSONObject c = state.category(i); if (c != null && !id.equals(c.optString("id"))) { ids[p] = c.optString("id"); names[p++] = c.optString("name"); } }
        if (p == 0) { toast("至少保留一个分类"); return; }
        new AlertDialog.Builder(this).setTitle("删除分类").setMessage("选择将原有录音重新分配到的分类。录音文件与文字不会被删除。")
            .setItems(names, (d, which) -> new AlertDialog.Builder(this).setTitle("确认删除并重新分配？")
                .setMessage("“" + find(id).optString("name") + "”中的录音将改为“" + names[which] + "”。")
                .setNegativeButton("取消", null).setPositiveButton("删除分类", (dialog, w) -> {
                    try { CategoryStore.remove(this, id, ids[which]); render(); toast("分类已删除，录音已重新分配"); }
                    catch (Exception e) { toast(e.getMessage() == null ? "删除失败" : e.getMessage()); }
                }).show()).setNegativeButton("取消", null).show();
    }
    private void restoreCloud() {
        if (!new Vault(this).configured()) { toast("先在设置中配置服务器并开启同步"); return; }
        new AlertDialog.Builder(this).setTitle("恢复加密分类备份？").setMessage("这会覆盖手机现有的分类名称、默认分类和录音归类。服务器只返回密文，只有本机恢复密钥能解密。")
            .setNegativeButton("取消", null).setPositiveButton("下载并恢复", (d, w) -> {
                ToastStatus.show(this, "正在安全下载…");
                io.execute(() -> {
                    try { Uploader.restoreCategoryCatalog(this); runOnUiThread(() -> { render(); toast("服务器分类目录已恢复"); }); }
                    catch (Exception e) { runOnUiThread(() -> toast("恢复失败：" + (e.getMessage() == null ? "服务器暂不可用" : e.getMessage()))); }
                });
            }).show();
    }
    private org.json.JSONObject find(String id) { if (state == null) return null; for (int i = 0; i < state.size(); i++) { org.json.JSONObject c = state.category(i); if (c != null && id.equals(c.optString("id"))) return c; } return null; }
    private static String templateName(String value) { switch (value) { case "private": return "隐私模板"; case "meeting": return "会议模板"; case "classroom": return "课堂模板"; case "interview": return "访谈模板"; default: return "通用模板"; } }
    private void toast(String value) { android.widget.Toast.makeText(this, value, android.widget.Toast.LENGTH_LONG).show(); }
    private static final class ToastStatus { static void show(Activity activity, String message) { android.widget.Toast.makeText(activity, message, android.widget.Toast.LENGTH_SHORT).show(); } }
}
