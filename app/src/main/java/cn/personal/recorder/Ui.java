package cn.personal.recorder;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.view.Gravity;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.view.View;

/** Shared native widgets; colors and the system dialog theme follow system night mode. */
final class Ui {
    final Activity activity;
    final int background, surface, ink, muted, accent, onAccent, soft, divider, danger;
    Ui(Activity activity) {
        this.activity = activity;
        background = color(R.color.background); surface = color(R.color.surface); ink = color(R.color.ink);
        muted = color(R.color.muted); accent = color(R.color.accent); onAccent = color(R.color.on_accent);
        soft = color(R.color.soft); divider = color(R.color.divider); danger = color(R.color.danger);
    }
    private int color(int id) { return activity.getColor(id); }
    int dp(int value) { return Math.round(value * activity.getResources().getDisplayMetrics().density); }
    LinearLayout screen() {
        ScrollView scroll = new ScrollView(activity); scroll.setFillViewport(true); scroll.setBackgroundColor(background);
        LinearLayout root = column(18); scroll.addView(root); activity.setContentView(scroll);
        scroll.setOnApplyWindowInsetsListener((view, insets) -> {
            android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom); return insets;
        });
        return root;
    }
    LinearLayout column(int padding) {
        LinearLayout box = new LinearLayout(activity); box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(padding), dp(padding), dp(padding), dp(padding)); return box;
    }
    LinearLayout row() {
        LinearLayout box = new LinearLayout(activity); box.setOrientation(LinearLayout.HORIZONTAL); box.setGravity(Gravity.CENTER_VERTICAL); return box;
    }
    LinearLayout.LayoutParams spaced() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2); p.topMargin = dp(10); p.bottomMargin = dp(10); return p;
    }
    GradientDrawable shape(int color, int radius) {
        GradientDrawable bg = new GradientDrawable(); bg.setColor(color); bg.setCornerRadius(dp(radius)); return bg;
    }
    LinearLayout card(LinearLayout parent) {
        LinearLayout box = column(20); box.setBackground(shape(surface, 24)); parent.addView(box, spaced()); return box;
    }
    TextView label(String text, int size, int color) {
        TextView label = new TextView(activity); label.setText(text); label.setTextSize(size); label.setTextColor(color);
        label.setPadding(0, dp(5), 0, dp(7)); return label;
    }
    TextView title(String text, int size) { TextView v = label(text, size, ink); v.setTypeface(null, Typeface.BOLD); return v; }
    LinearLayout categoryTile(String name, String count, boolean selected, Runnable action) {
        int fill = selected ? accent : surface;
        int foreground = selected ? onAccent : ink;
        LinearLayout tile = new LinearLayout(activity); tile.setOrientation(LinearLayout.VERTICAL);
        tile.setGravity(Gravity.CENTER_VERTICAL); tile.setPadding(dp(15), dp(10), dp(15), dp(10));
        tile.setBackground(new RippleDrawable(ColorStateList.valueOf(selected ? 0x33ffffff : 0x22999999), shape(fill, 20), null));
        tile.setMinimumHeight(dp(82)); tile.setContentDescription(name + "，" + count + " 条录音");
        TextView total = label(count, 12, selected ? onAccent : muted); total.setPadding(0, 0, 0, dp(3));
        TextView category = title(name, 15); category.setTextColor(foreground); category.setPadding(0, 0, 0, 0);
        tile.addView(total); tile.addView(category); tile.setOnClickListener(v -> action.run());
        return tile;
    }
    Button button(String text, Runnable action, boolean primary) {
        Button button = new Button(activity); button.setText(text); button.setAllCaps(false); button.setTextSize(15); button.setMinHeight(dp(52));
        button.setBackground(new RippleDrawable(ColorStateList.valueOf(0x22999999), shape(primary ? accent : soft, 18), null));
        button.setTextColor(primary ? onAccent : accent); button.setPadding(dp(16), dp(12), dp(16), dp(12)); button.setOnClickListener(v -> action.run()); return button;
    }
    void action(LinearLayout parent, String title, String subtitle, Runnable action) {
        LinearLayout row = row(); row.setPadding(dp(14), dp(7), dp(12), dp(7)); row.setMinimumHeight(dp(66));
        LinearLayout text = column(0); text.addView(label(title, 16, ink));
        if (!subtitle.isEmpty()) text.addView(label(subtitle, 12, muted));
        row.addView(text, new LinearLayout.LayoutParams(0, -2, 1)); row.addView(label("›", 24, muted));
        row.setBackground(new RippleDrawable(ColorStateList.valueOf(0x22999999), shape(soft, 16), null));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2); params.topMargin = dp(5); params.bottomMargin = dp(5);
        row.setOnClickListener(v -> action.run()); row.setContentDescription(title + "，" + subtitle); parent.addView(row, params);
    }
    AlertDialog bottomSheet(String title, String subtitle, SheetContent content) {
        LinearLayout panel = column(20); panel.setBackground(shape(surface, 28));
        LinearLayout handleRow = row(); handleRow.setGravity(Gravity.CENTER);
        View handle = new View(activity); handle.setBackground(shape(divider, 8));
        LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(dp(38), dp(4)); hp.bottomMargin = dp(18); handleRow.addView(handle, hp); panel.addView(handleRow);
        panel.addView(title(title, 21));
        if (subtitle != null && !subtitle.isEmpty()) panel.addView(label(subtitle, 13, muted));
        LinearLayout actions = column(0);
        ScrollView actionScroll = new ScrollView(activity) {
            @Override protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
                int max = Ui.this.dp(420);
                int limit = View.MeasureSpec.getMode(heightMeasureSpec) == View.MeasureSpec.UNSPECIFIED ? max : Math.min(View.MeasureSpec.getSize(heightMeasureSpec), max);
                super.onMeasure(widthMeasureSpec, View.MeasureSpec.makeMeasureSpec(limit, View.MeasureSpec.AT_MOST));
            }
        };
        actionScroll.setFillViewport(false); actionScroll.setVerticalScrollBarEnabled(false); actionScroll.addView(actions);
        panel.addView(actionScroll);
        content.build(this, actions);
        Button close = button("关闭", () -> { }, false);
        panel.addView(close, new LinearLayout.LayoutParams(-1, -2));
        AlertDialog dialog = new AlertDialog.Builder(activity).setView(panel).create();
        close.setOnClickListener(v -> dialog.dismiss());
        dialog.setOnShowListener(ignored -> {
            android.view.Window window = dialog.getWindow();
            if (window != null) {
                window.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
                window.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT);
                window.setGravity(Gravity.BOTTOM);
                window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
                WindowManager.LayoutParams attrs = window.getAttributes(); attrs.dimAmount = 0.42f; window.setAttributes(attrs);
            }
        });
        return dialog;
    }
    interface SheetContent { void build(Ui ui, LinearLayout actions); }
}
