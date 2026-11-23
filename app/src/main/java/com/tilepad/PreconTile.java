package com.tilepad;

/** One-button battery preconditioning; all state comes from the service's snapshot. */
final class PreconTile extends StatusTile {
    private final TileBar bar;

    PreconTile(Ui ui, TileSpec spec, EditListener edits) {
        super(ui, spec, edits, Glyph.Kind.BATTERY_HEAT, "Precondition");
        bar = new TileBar(ui);
        bind(PreconditioningSnapshot.checking());
    }

    void bind(PreconditioningSnapshot s) { bind(s, KeepWarm.Status.OFF, BatteryStatus.NONE, 0); }

    /** Only on change: a new text here re-lays out the whole grid, and the tile is bound every second. */
    private void setRight(String text) {
        if (text.contentEquals(metaRight.getText())) return;
        metaRight.setText(text);
        metaRight.setTextColor(Ui.AMBER);
    }

    void bind(PreconditioningSnapshot s, KeepWarm.Status keepWarm, BatteryStatus battery, long now) {
        int color = Ui.tone(PreconPresenter.tone(s, keepWarm));
        glyph.setColor(color);
        spinner.setColor(color);
        spinner.setSpinning(PreconPresenter.busy(s));
        headline.setText(PreconPresenter.headline(s, keepWarm));
        boolean off = (s.state == PreconditioningSnapshot.State.IDLE
            || s.state == PreconditioningSnapshot.State.ENDED) && s.indicator != 1 && !keepWarm.armed;
        headline.setTextColor(off ? Ui.TEXT : color);
        // Without the bar the tile keeps its plain words: no temperature labels under an empty row.
        show(detail, keepWarm.armed ? PreconPresenter.detail(s, keepWarm, battery, now) : PreconPresenter.detail(s));
        show(meta, PreconPresenter.hint(s, keepWarm));
        // With a fresh reading: the temperature large, a bar towards the target, a label at each end.
        PreconPresenter.Bar shown = PreconPresenter.bar(s, keepWarm, battery, now);
        if (shown == null) {
            caption.setText("Precondition");
            Ui.background(detail, null);
            setRight("");
        } else {
            caption.setText(shown.caption);
            headline.setText(shown.temperature);
            headline.setTextColor(shown.heating ? Ui.GREEN : Ui.TEXT);
            bar.set(shown.fraction, Ui.GREEN);
            Ui.background(detail, bar);
            show(detail, "\u00a0"); // keeps the row's height and lets the bar draw
            show(meta, shown.left);
            setRight(shown.right);
        }
        String indicator = PreconPresenter.indicator(s);
        setPill(indicator, s.indicator == 1 ? Ui.GREEN : Ui.MUTED);
        setContentDescription("Battery preconditioning, " + (keepWarm.armed ? "keeping warm, " : "") + s.title() + ". " + s.detail);
    }
}
