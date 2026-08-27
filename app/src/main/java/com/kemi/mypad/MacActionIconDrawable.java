package com.kemi.mypad;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;

/** Consistent macOS-style outline icons for the file context menu. */
final class MacActionIconDrawable extends Drawable {
    enum Kind { COPY, CUT, DELETE, REFRESH }

    private final Kind kind;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();

    MacActionIconDrawable(Kind kind) { this.kind = kind; }

    @Override public void draw(Canvas canvas) {
        float w = getBounds().width(), h = getBounds().height();
        canvas.save();
        canvas.translate(getBounds().left, getBounds().top);
        paint.setColor(Color.rgb(55, 61, 68));
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(Math.max(1.6f, w * .075f));
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
        switch (kind) {
            case COPY: drawCopy(canvas, w, h); break;
            case CUT: drawCut(canvas, w, h); break;
            case DELETE: drawDelete(canvas, w, h); break;
            case REFRESH: drawRefresh(canvas, w, h); break;
        }
        canvas.restore();
    }

    private void drawCopy(Canvas c, float w, float h) {
        float r = w * .08f;
        c.drawRoundRect(new RectF(w * .14f, h * .12f, w * .69f, h * .68f), r, r, paint);
        c.drawRoundRect(new RectF(w * .31f, h * .31f, w * .86f, h * .88f), r, r, paint);
    }

    private void drawCut(Canvas c, float w, float h) {
        c.drawCircle(w * .24f, h * .25f, w * .12f, paint);
        c.drawCircle(w * .24f, h * .75f, w * .12f, paint);
        c.drawLine(w * .34f, h * .31f, w * .84f, h * .78f, paint);
        c.drawLine(w * .34f, h * .69f, w * .84f, h * .22f, paint);
        paint.setStyle(Paint.Style.FILL);
        c.drawCircle(w * .47f, h * .50f, w * .055f, paint);
        paint.setStyle(Paint.Style.STROKE);
    }

    private void drawDelete(Canvas c, float w, float h) {
        c.drawRoundRect(new RectF(w * .25f, h * .29f, w * .75f, h * .88f), w * .06f, w * .06f, paint);
        c.drawLine(w * .18f, h * .25f, w * .82f, h * .25f, paint);
        c.drawLine(w * .38f, h * .14f, w * .62f, h * .14f, paint);
        c.drawLine(w * .41f, h * .42f, w * .41f, h * .73f, paint);
        c.drawLine(w * .59f, h * .42f, w * .59f, h * .73f, paint);
    }

    private void drawRefresh(Canvas c, float w, float h) {
        RectF oval = new RectF(w * .16f, h * .16f, w * .84f, h * .84f);
        c.drawArc(oval, -55, 285, false, paint);
        paint.setStyle(Paint.Style.FILL);
        path.reset();
        path.moveTo(w * .77f, h * .09f);
        path.lineTo(w * .88f, h * .32f);
        path.lineTo(w * .63f, h * .29f);
        path.close();
        c.drawPath(path, paint);
        paint.setStyle(Paint.Style.STROKE);
    }

    @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); invalidateSelf(); }
    @Override public void setColorFilter(ColorFilter filter) { paint.setColorFilter(filter); invalidateSelf(); }
    @Override public int getOpacity() { return android.graphics.PixelFormat.TRANSLUCENT; }
}
