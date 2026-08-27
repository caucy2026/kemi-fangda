package com.kemi.mypad;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.drawable.Drawable;

/** Font-independent navigation chevron with macOS-style weight and alignment. */
final class MacChevronDrawable extends Drawable {
    private final boolean forward;
    private final int color;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();

    MacChevronDrawable(boolean forward, int color) {
        this.forward = forward;
        this.color = color;
    }

    @Override public void draw(Canvas canvas) {
        float w = getBounds().width(), h = getBounds().height();
        canvas.save();
        canvas.translate(getBounds().left, getBounds().top);
        paint.setColor(color);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(Math.max(1.8f, w * .10f));
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
        float left = w * .34f, right = w * .66f;
        if (forward) { float swap = left; left = right; right = swap; }
        path.reset();
        path.moveTo(right, h * .22f);
        path.lineTo(left, h * .50f);
        path.lineTo(right, h * .78f);
        canvas.drawPath(path, paint);
        canvas.restore();
    }

    @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); invalidateSelf(); }
    @Override public void setColorFilter(ColorFilter filter) { paint.setColorFilter(filter); invalidateSelf(); }
    @Override public int getOpacity() { return android.graphics.PixelFormat.TRANSLUCENT; }
}
