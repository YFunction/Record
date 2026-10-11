package cn.personal.recorder;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.View;

/** Audio level display: 48 rounded bars, refreshed from the capture meter. */
final class WaveformView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float[] levels = new float[AudioMeter.BARS];
    private final int accent, quiet;
    private boolean running;

    public WaveformView(Context context) {
        this(context, context.getColor(R.color.accent), context.getColor(R.color.divider));
    }
    WaveformView(Context context, int accent, int quiet) {
        super(context);
        this.accent = accent; this.quiet = quiet;
        setContentDescription("实时录音波形");
    }

    void show(AudioMeter meter, boolean running) {
        if (meter != null) meter.copyTo(levels);
        else java.util.Arrays.fill(levels, 0f);
        this.running = running;
        invalidate();
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float width = getWidth(), height = getHeight();
        if (width <= 0 || height <= 0) return;
        float stride = width / AudioMeter.BARS;
        float barWidth = Math.max(2f, stride * 0.48f);
        float center = height / 2f;
        paint.setColor(running ? accent : quiet);
        for (int i = 0; i < AudioMeter.BARS; i++) {
            float half = Math.max(stride * 0.35f, levels[i] * height * 0.45f);
            float left = i * stride + (stride - barWidth) / 2f;
            canvas.drawRoundRect(left, center - half, left + barWidth, center + half, barWidth / 2f, barWidth / 2f, paint);
        }
    }
}
