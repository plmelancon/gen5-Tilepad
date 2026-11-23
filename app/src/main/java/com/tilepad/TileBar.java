package com.tilepad;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;

/** The Precondition tile's bar: how far the battery is from where it started to the target. */
final class TileBar extends Drawable {
    private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG), fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF box = new RectF();
    private final float height;
    private float fraction;

    TileBar(Ui ui) {
        height = ui.dp(8);
        track.setColor(Ui.LINE);
    }

    void set(float fraction, int color) {
        this.fraction = Math.max(0f, Math.min(1f, fraction));
        fill.setColor(color);
        invalidateSelf();
    }

    @Override public void draw(Canvas canvas) {
        Rect b = getBounds();
        float top = b.centerY() - height / 2;
        box.set(b.left, top, b.right, top + height);
        canvas.drawRoundRect(box, height / 2, height / 2, track);
        if (fraction <= 0) return;
        // Never thinner than its own round ends.
        box.right = b.left + Math.max(height, b.width() * fraction);
        canvas.drawRoundRect(box, height / 2, height / 2, fill);
    }
    @Override public void setAlpha(int alpha) { }
    @Override public void setColorFilter(ColorFilter filter) { }
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
}
