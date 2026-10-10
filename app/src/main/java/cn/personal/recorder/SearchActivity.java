package cn.personal.recorder;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.KeyguardManager;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** On-demand local search; results are removed from the UI when this page is left. */
public final class SearchActivity extends Activity {
    private Ui ui;
    private EditText query;
    private TextView scope, status;
    private LinearLayout results;
    private String categoryId = "", categoryName = "全部分类";
    private volatile int generation;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        if (Build.VERSION.SDK_INT >= 33) setRecentsScreenshotEnabled(false);
        ui = new Ui(this);
        String requested = getIntent().getStringExtra("category");
        try {
            CategoryStore.State catalog = CategoryStore.load(this);
            for (int i = 0; i < catalog.size(); i++) {
                org.json.JSONObject item = catalog.category(i);
                if (item != null && catalog.id(item).equals(requested)) {
                    categoryId = requested; categoryName = catalog.name(requested); break;
                }
            }
        } catch (Exception ignored) { /* The search page still supports all categories. */ }
        LinearLayout root = ui.screen(); LinearLayout heading = ui.row();
        heading.addView(ui.button("返回", this::finish, false));
        TextView title = ui.title("本地搜索", 24); title.setPadding(ui.dp(14), 0, 0, 0); heading.addView(title); root.addView(heading);
        root.addView(ui.label("只搜索手机上的加密文字资料；输入的关键词不会发往云端。", 13, ui.muted));
        LinearLayout controls = ui.card(root);
        query = new EditText(this); query.setSingleLine(true); query.setTextColor(ui.ink); query.setHintTextColor(ui.muted);
        query.setHint("搜索发言、总结或大纲"); query.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        query.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(80)});
        query.setOnEditorActionListener((v, action, event) -> { if (action == EditorInfo.IME_ACTION_SEARCH) { search(); return true; } return false; });
        controls.addView(query);
        scope = ui.label("范围：" + categoryName + "　›", 15, ui.accent);
        scope.setOnClickListener(v -> chooseCategory()); controls.addView(scope);
        controls.addView(ui.button("搜索本机文字", this::search, true), ui.spaced());
        status = ui.label("输入关键词后开始搜索；多个词用空格分开，需出现在同一段文字中。", 13, ui.muted); root.addView(status);
        results = ui.column(0); root.addView(results);
    }
    @Override protected void onResume() {
        super.onResume();
        if (getSystemService(KeyguardManager.class).isKeyguardLocked()) { finish(); return; }
        if (query != null && !query.getText().toString().trim().isEmpty()) search();
    }
    @Override protected void onPause() {
        generation++; results.removeAllViews(); status.setText("搜索结果已隐藏");
        super.onPause();
    }
    @Override protected void onDestroy() { generation++; io.shutdownNow(); super.onDestroy(); }

    private void chooseCategory() {
        try {
            CategoryStore.State catalog = CategoryStore.load(this);
            String[] names = new String[catalog.size() + 1], ids = new String[names.length];
            names[0] = "全部分类"; ids[0] = "";
            for (int i = 0; i < catalog.size(); i++) {
                org.json.JSONObject item = catalog.category(i); ids[i + 1] = catalog.id(item); names[i + 1] = catalog.name(ids[i + 1]);
            }
            new AlertDialog.Builder(this).setTitle("搜索范围").setItems(names, (dialog, which) -> {
                categoryId = ids[which]; categoryName = names[which]; scope.setText("范围：" + categoryName + "　›");
                if (!query.getText().toString().trim().isEmpty()) search();
            }).show();
        } catch (Exception e) { status.setText("无法读取分类目录，请检查密钥"); }
    }
    private void search() {
        if (getSystemService(KeyguardManager.class).isKeyguardLocked()) { finish(); return; }
        String phrase = query.getText().toString().trim();
        if (phrase.isEmpty()) { results.removeAllViews(); status.setText("输入关键词后开始搜索"); return; }
        try { LocalSearch.terms(phrase); } catch (IllegalArgumentException e) { status.setText(e.getMessage()); return; }
        String filter = categoryId; int request = ++generation;
        results.removeAllViews(); status.setText("正在本机解密搜索…");
        io.execute(() -> {
            try {
                LocalSearch.Result found = LocalSearch.search(this, phrase, filter,
                    () -> request != generation || Thread.currentThread().isInterrupted());
                main.post(() -> { if (request == generation && !isDestroyed() && !isFinishing()) show(found); });
            } catch (Exception e) {
                main.post(() -> { if (request == generation && !isDestroyed()) status.setText("搜索失败，请确认手机密钥和分类目录可用"); });
            }
        });
    }
    private void show(LocalSearch.Result found) {
        if (getSystemService(KeyguardManager.class).isKeyguardLocked()) { results.removeAllViews(); finish(); return; }
        String count = found.hits.isEmpty() ? "没有找到匹配内容" : "找到 " + found.hits.size() + " 条匹配";
        status.setText(count + (found.hits.size() == LocalSearch.MAX_RESULTS ? " · 仅显示前 80 条" : "")
            + (found.unreadable > 0 ? " · " + found.unreadable + " 份文字无法解密" : ""));
        results.removeAllViews();
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA);
        for (LocalSearch.Hit hit : found.hits) {
            LinearLayout card = ui.card(results);
            card.addView(ui.title(hit.category + " · " + hit.field, 16));
            card.addView(ui.label(format.format(new Date(hit.recordedAt)), 12, ui.muted));
            card.addView(ui.label(hit.snippet, 15, ui.ink));
            TextView open = ui.label("查看录音详情　›", 13, ui.accent); card.addView(open);
            card.setOnClickListener(v -> startActivity(new Intent(this, TextActivity.class)
                .putExtra("session", hit.session).putExtra("focus-segment", hit.segment).putExtra("focus-field", hit.field)));
            card.setContentDescription(hit.category + "，" + hit.field + "，" + hit.snippet + "，打开录音详情");
        }
    }
}
