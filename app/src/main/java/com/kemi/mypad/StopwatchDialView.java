package com.kemi.mypad;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.view.View;

/** Smooth macOS-style stopwatch dial synchronized with the digital hundredths display. */
final class StopwatchDialView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private long elapsedMillis;

    StopwatchDialView(Context context) { super(context); }

    void setElapsedMillis(long elapsedMillis) {
        this.elapsedMillis = Math.max(0, elapsedMillis);
        invalidate();
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float size = Math.min(getWidth(), getHeight());
        float cx = getWidth() / 2f, cy = getHeight() / 2f, radius = size * .43f;
        paint.setStyle(Paint.Style.FILL); paint.setColor(Color.WHITE);
        canvas.drawCircle(cx, cy, radius, paint);
        paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(size * .016f); paint.setColor(Color.rgb(202, 212, 219));
        canvas.drawCircle(cx, cy, radius, paint);

        for (int i = 0; i < 60; i++) {
            double angle = Math.toRadians(i * 6 - 90);
            float outerX = cx + (float) Math.cos(angle) * radius * .88f;
            float outerY = cy + (float) Math.sin(angle) * radius * .88f;
            float innerScale = i % 5 == 0 ? .73f : .80f;
            float innerX = cx + (float) Math.cos(angle) * radius * innerScale;
            float innerY = cy + (float) Math.sin(angle) * radius * innerScale;
            paint.setColor(i % 5 == 0 ? Color.rgb(53, 66, 77) : Color.rgb(157, 170, 180));
            paint.setStrokeWidth(i % 5 == 0 ? size * .018f : size * .008f);
            paint.setStrokeCap(Paint.Cap.ROUND);
            canvas.drawLine(innerX, innerY, outerX, outerY, paint);
        }

        double seconds = (elapsedMillis % 60_000L) / 1000d;
        drawHand(canvas, cx, cy, radius * .66f, seconds * 6 - 90, Color.rgb(24, 157, 143), size * .018f);
        double minutes = (elapsedMillis % 3_600_000L) / 60_000d;
        drawHand(canvas, cx, cy, radius * .43f, minutes * 6 - 90, Color.rgb(50, 62, 72), size * .026f);
        paint.setStyle(Paint.Style.FILL); paint.setColor(Color.rgb(24, 157, 143));
        canvas.drawCircle(cx, cy, size * .032f, paint);
        paint.setColor(Color.WHITE); canvas.drawCircle(cx, cy, size * .012f, paint);
    }

    private void drawHand(Canvas canvas, float cx, float cy, float length, double degrees, int color, float width) {
        double angle = Math.toRadians(degrees);
        paint.setStyle(Paint.Style.STROKE); paint.setStrokeCap(Paint.Cap.ROUND); paint.setStrokeWidth(width); paint.setColor(color);
        canvas.drawLine(cx, cy, cx + (float) Math.cos(angle) * length, cy + (float) Math.sin(angle) * length, paint);
    }
}
