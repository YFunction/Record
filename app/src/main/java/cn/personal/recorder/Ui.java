package cn.personal.recorder;

import android.app.Activity;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.view.Gravity;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

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
        LinearLayout root = column(22); scroll.addView(root); activity.setContentView(scroll);
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
    Button button(String text, Runnable action, boolean primary) {
        Button button = new Button(activity); button.setText(text); button.setAllCaps(false); button.setTextSize(15); button.setMinHeight(dp(52));
        button.setBackground(new RippleDrawable(ColorStateList.valueOf(0x22999999), shape(primary ? accent : soft, 18), null));
        button.setTextColor(primary ? onAccent : accent); button.setPadding(dp(16), dp(12), dp(16), dp(12)); button.setOnClickListener(v -> action.run()); return button;
    }
    void action(LinearLayout parent, String title, String subtitle, Runnable action) {
        LinearLayout row = row(); row.setPadding(0, dp(9), 0, dp(9)); row.setMinimumHeight(dp(66));
        LinearLayout text = column(0); text.addView(label(title, 16, ink));
        if (!subtitle.isEmpty()) text.addView(label(subtitle, 12, muted));
        row.addView(text, new LinearLayout.LayoutParams(0, -2, 1)); row.addView(label("›", 24, muted));
        row.setBackground(new RippleDrawable(ColorStateList.valueOf(0x22999999), shape(surface, 12), null));
        row.setOnClickListener(v -> action.run()); row.setContentDescription(title + "，" + subtitle); parent.addView(row);
    }
}
