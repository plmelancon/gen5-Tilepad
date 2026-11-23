package com.tilepad;

import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * Battery monitor: the temperature now, its graph, what the session is doing, the car's raw values
 * and the last things Tilepad sent or saw. It is the only sheet while it shows, so it says the
 * session's state itself.
 */
final class MonitorCard extends LinearLayout {
    private static final int EVENT_LINES = 3;
    private final Ui ui;
    private final TextView temperature, age, state, left, reading, raw, trail, legend;
    private final TemperatureChart chart;
    private final LinearLayout buttons;
    private String buttonKey = "";

    MonitorCard(Ui ui) {
        super(ui.context);
        this.ui = ui;
        setOrientation(VERTICAL);
        setPadding(ui.dp(28), ui.dp(20), ui.dp(24), ui.dp(18));

        // Two columns: the screen is only 480 dp tall.
        LinearLayout top = ui.row();
        LinearLayout now = ui.column();
        now.addView(ui.line("Battery temperature", 15, Ui.MUTED, false));
        temperature = ui.line("", 44, Ui.TEXT, true);
        now.addView(temperature);
        age = ui.line("", 14, Ui.MUTED, false);
        now.addView(age);
        state = ui.text("", 16, Ui.TEXT, false);
        state.setMaxLines(3);
        state.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LayoutParams stp = new LayoutParams(-1, -2); stp.topMargin = ui.dp(6);
        now.addView(state, stp);
        // Keep warm's time limit has its own line, so a long state text can never hide it.
        left = ui.line("", 15, Ui.GREEN, true);
        now.addView(left, new LayoutParams(-1, -2));
        reading = ui.text("", 14, Ui.MUTED, false);
        reading.setMaxLines(2);
        reading.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LayoutParams rp = new LayoutParams(-1, -2); rp.topMargin = ui.dp(4);
        now.addView(reading, rp);
        top.addView(now, new LayoutParams(ui.dp(400), -2));

        LinearLayout graph = ui.column();
        chart = new TemperatureChart(ui);
        graph.addView(chart, new LayoutParams(-1, ui.dp(176)));
        legend = ui.text("", 13, Ui.MUTED, false);
        legend.setMaxLines(2);
        graph.addView(legend, new LayoutParams(-1, -2));
        LayoutParams gp = new LayoutParams(0, -2, 1f); gp.leftMargin = ui.dp(20);
        top.addView(graph, gp);
        addView(top, new LayoutParams(-1, -2));

        raw = ui.text("", 13, Ui.MUTED, false);
        raw.setMaxLines(2);
        LayoutParams wp = new LayoutParams(-1, -2); wp.topMargin = ui.dp(8);
        addView(raw, wp);
        trail = ui.text("", 12, Ui.FAINT, false);
        trail.setMaxLines(EVENT_LINES);
        LayoutParams tp = new LayoutParams(-1, -2); tp.topMargin = ui.dp(6);
        addView(trail, tp);

        buttons = ui.row();
        buttons.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        LayoutParams bp = new LayoutParams(-1, ui.dp(52)); bp.topMargin = ui.dp(10);
        addView(buttons, bp);
    }

    /** Rebuilt only when the labels change, like the popup's. Close comes first for the knob. */
    List<View> setActions(StatusCard.Action... actions) {
        StringBuilder key = new StringBuilder();
        for (StatusCard.Action a : actions) key.append(a.label).append('|').append(a.fill).append(';');
        if (!key.toString().equals(buttonKey)) {
            buttonKey = key.toString();
            buttons.removeAllViews();
            for (StatusCard.Action a : actions) {
                int pressed = a.fill == Ui.SURFACE ? Ui.SURFACE_PRESSED : Ui.alpha(a.fill, 0.75f);
                LinearLayout b = ui.button(a.label, null, a.fill, pressed, Ui.TEXT);
                LayoutParams p = new LayoutParams(-2, -1); p.leftMargin = ui.dp(14);
                buttons.addView(b, p);
            }
        }
        List<View> views = new ArrayList<>();
        for (int i = 0; i < buttons.getChildCount(); i++) {
            final StatusCard.Action a = actions[i];
            View b = buttons.getChildAt(i);
            b.setOnClickListener(new OnClickListener() { @Override public void onClick(View v) { a.run.run(); } });
            views.add(b);
        }
        return views;
    }

    void set(PreconditioningSnapshot s, BatteryStatus battery, KeepWarm.Status keepWarm, String endNote,
             List<TemperatureLog.Point> points, List<String> events, long now) {
        temperature.setText(battery.info == null ? "—" : battery.info.minimumC + "°C");
        temperature.setTextColor(battery.fresh(now) ? Ui.TEXT : Ui.MUTED);
        age.setText(PreconPresenter.batteryAge(battery, now));
        state.setText(PreconPresenter.monitorState(s, keepWarm, endNote));
        String keep = PreconPresenter.keepWarmLine(keepWarm);
        left.setText(keep);
        left.setTextColor(keepWarm.armed ? Ui.GREEN : Ui.MUTED);
        left.setVisibility(keep.isEmpty() ? GONE : VISIBLE);
        String says = PreconPresenter.reading(s, battery, now);
        reading.setText(says);
        reading.setVisibility(says.isEmpty() ? GONE : VISIBLE);
        chart.set(points, battery.thresholdC, now);
        legend.setText(points.isEmpty() ? "The graph starts with the first reading. It covers this viewing or keep-warm period only."
            : "Lowest battery temperature. Dashed line: where this car is expected to stop heating. Green: the car was heating.");
        raw.setText(PreconPresenter.rawValues(s, battery));
        StringBuilder lines = new StringBuilder();
        for (int i = Math.max(0, events.size() - EVENT_LINES); i < events.size(); i++) {
            if (lines.length() > 0) lines.append('\n');
            lines.append(events.get(i));
        }
        trail.setText(lines);
        trail.setVisibility(lines.length() == 0 ? GONE : VISIBLE);
    }
}
