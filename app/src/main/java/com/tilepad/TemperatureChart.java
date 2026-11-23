package com.tilepad;

import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.view.View;

import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** The monitor's graph: battery minimum over time, the expected heating threshold, and when the car heated. */
final class TemperatureChart extends View {
    /** At least this much time across, so the first readings don't stretch over the whole width. */
    private static final long MIN_SPAN_MS = 10 * 60 * 1000L;
    private final Ui ui;
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG), dash = new Paint(Paint.ANTI_ALIAS_FLAG),
        fill = new Paint(), label = new Paint(Paint.ANTI_ALIAS_FLAG), axis = new Paint();
    private List<TemperatureLog.Point> points = Collections.emptyList();
    private int thresholdC;
    private long now;

    TemperatureChart(Ui ui) {
        super(ui.context);
        this.ui = ui;
        line.setColor(Ui.ACCENT); line.setStyle(Paint.Style.STROKE); line.setStrokeWidth(ui.dp(3));
        line.setStrokeCap(Paint.Cap.ROUND); line.setStrokeJoin(Paint.Join.ROUND);
        dash.setColor(Ui.AMBER); dash.setStyle(Paint.Style.STROKE); dash.setStrokeWidth(ui.dp(1.5f));
        dash.setPathEffect(new DashPathEffect(new float[]{ui.dp(8), ui.dp(6)}, 0));
        fill.setColor(Ui.alpha(Ui.GREEN, 0.16f));
        label.setColor(Ui.MUTED); label.setTextSize(ui.dp(14));
        axis.setColor(Ui.LINE); axis.setStrokeWidth(ui.dp(1));
        // A dashed line needs software drawing on Android 4.4.
        setLayerType(LAYER_TYPE_SOFTWARE, null);
    }

    void set(List<TemperatureLog.Point> points, int thresholdC, long now) {
        this.points = points; this.thresholdC = thresholdC; this.now = now;
        invalidate();
    }

    @Override protected void onDraw(Canvas canvas) {
        float left = ui.dp(52), right = getWidth() - ui.dp(12), top = ui.dp(10), bottom = getHeight() - ui.dp(26);
        if (right <= left || bottom <= top) return;
        int low = thresholdC, high = thresholdC;
        for (TemperatureLog.Point p : points) { low = Math.min(low, p.minimumC); high = Math.max(high, p.minimumC); }
        low -= 2; high += 2;
        if (high - low < 8) { int extra = 8 - (high - low); low -= extra / 2; high += extra - extra / 2; }
        long start = points.isEmpty() ? now - MIN_SPAN_MS : Math.min(points.get(0).at, now - MIN_SPAN_MS);
        float perMs = (right - left) / Math.max(1, now - start), perC = (bottom - top) / (high - low);

        canvas.drawLine(left, bottom, right, bottom, axis);
        canvas.drawLine(left, top, left, bottom, axis);
        label.setColor(Ui.MUTED);
        label.setTextAlign(Paint.Align.RIGHT);
        canvas.drawText(high + "°C", left - ui.dp(8), top + ui.dp(12), label);
        canvas.drawText(low + "°C", left - ui.dp(8), bottom, label);
        label.setTextAlign(Paint.Align.LEFT);
        long minutes = Math.max(1, (now - start + 30000) / 60000);
        canvas.drawText(String.format(Locale.US, "%d min ago", minutes), left, getHeight() - ui.dp(6), label);
        label.setTextAlign(Paint.Align.RIGHT);
        canvas.drawText("now", right, getHeight() - ui.dp(6), label);

        // Shade from each reading taken while the car heated to the next one.
        for (int i = 0; i + 1 < points.size(); i++) {
            TemperatureLog.Point a = points.get(i), b = points.get(i + 1);
            if (a.heating && b.at - a.at <= TemperatureLog.BREAK_MS)
                canvas.drawRect(left + (a.at - start) * perMs, top, left + (b.at - start) * perMs, bottom, fill);
        }
        float thresholdY = bottom - (thresholdC - low) * perC;
        canvas.drawLine(left, thresholdY, right, thresholdY, dash);
        label.setColor(Ui.AMBER);
        canvas.drawText(thresholdC + "°C", right, thresholdY - ui.dp(6), label);

        for (int i = 0; i < points.size(); i++) {
            TemperatureLog.Point p = points.get(i);
            float x = left + (p.at - start) * perMs, y = bottom - (p.minimumC - low) * perC;
            if (i > 0 && p.at - points.get(i - 1).at <= TemperatureLog.BREAK_MS) {
                TemperatureLog.Point q = points.get(i - 1);
                canvas.drawLine(left + (q.at - start) * perMs, bottom - (q.minimumC - low) * perC, x, y, line);
            } else canvas.drawPoint(x, y, line);
        }
    }
}
