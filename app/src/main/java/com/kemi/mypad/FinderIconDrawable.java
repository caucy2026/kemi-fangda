package com.kemi.mypad;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.view.View;

/** Small, original Finder-style icons drawn crisply at any density. */
final class FinderIconDrawable extends Drawable {
    enum Kind { FOLDER, DOCUMENT, IMAGE, VIDEO, AUDIO, PDF, APK }

    private final Kind kind;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();

    FinderIconDrawable(Kind kind) { this.kind = kind; }

    static Kind kindFor(String mime) {
        if (mime == null) return Kind.DOCUMENT;
        if (mime.startsWith("image/")) return Kind.IMAGE;
        if (mime.startsWith("video/")) return Kind.VIDEO;
        if (mime.startsWith("audio/")) return Kind.AUDIO;
        if (mime.equals("application/pdf")) return Kind.PDF;
        if (mime.equals("application/vnd.android.package-archive")) return Kind.APK;
        return Kind.DOCUMENT;
    }

    @Override public void draw(Canvas canvas) {
        float w = getBounds().width(), h = getBounds().height();
        canvas.save();
        canvas.translate(getBounds().left, getBounds().top);
        if (kind == Kind.FOLDER) drawFolder(canvas, w, h); else drawDocument(canvas, w, h);
        canvas.restore();
    }

    private void drawFolder(Canvas c, float w, float h) {
        float l = w * .06f, r = w * .94f, top = h * .20f, bottom = h * .82f;
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(Color.rgb(89, 181, 246));
        path.reset();
        path.moveTo(l, top + h * .12f); path.lineTo(l, top + h * .05f);
        path.quadTo(l, top, l + w * .08f, top);
        path.lineTo(l + w * .36f, top); path.lineTo(l + w * .46f, top + h * .11f);
        path.lineTo(r - w * .07f, top + h * .11f); path.quadTo(r, top + h * .11f, r, top + h * .19f);
        path.lineTo(r, bottom); path.quadTo(r, bottom + h * .07f, r - w * .08f, bottom + h * .07f);
        path.lineTo(l + w * .08f, bottom + h * .07f); path.quadTo(l, bottom + h * .07f, l, bottom);
        path.close(); c.drawPath(path, paint);
        paint.setColor(Color.rgb(107, 197, 251));
        c.drawRoundRect(new RectF(l, top + h * .20f, r, bottom + h * .08f), h * .08f, h * .08f, paint);
        paint.setColor(Color.argb(52, 255, 255, 255));
        c.drawRoundRect(new RectF(l + w * .08f, top + h * .25f, r - w * .08f, top + h * .31f), h * .03f, h * .03f, paint);
    }

    private void drawDocument(Canvas c, float w, float h) {
        int accent = accentColor();
        float l = w * .17f, top = h * .07f, r = w * .83f, bottom = h * .93f, fold = w * .22f;
        paint.setStyle(Paint.Style.FILL); paint.setColor(Color.rgb(249, 251, 253));
        path.reset(); path.moveTo(l + h * .06f, top); path.lineTo(r - fold, top); path.lineTo(r, top + fold);
        path.lineTo(r, bottom - h * .06f); path.quadTo(r, bottom, r - h * .06f, bottom);
        path.lineTo(l + h * .06f, bottom); path.quadTo(l, bottom, l, bottom - h * .06f);
        path.lineTo(l, top + h * .06f); path.quadTo(l, top, l + h * .06f, top); path.close(); c.drawPath(path, paint);
        paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(Math.max(1f, w * .025f)); paint.setColor(Color.rgb(201, 209, 218)); c.drawPath(path, paint);
        paint.setStyle(Paint.Style.FILL); paint.setColor(Color.rgb(225, 231, 237));
        path.reset(); path.moveTo(r - fold, top); path.lineTo(r - fold, top + fold); path.lineTo(r, top + fold); path.close(); c.drawPath(path, paint);
        paint.setColor(accent); c.drawRoundRect(new RectF(l + w * .09f, h * .55f, r - w * .09f, h * .77f), h * .035f, h * .035f, paint);
        paint.setColor(Color.WHITE); paint.setTextAlign(Paint.Align.CENTER); paint.setTypeface(android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD));
        paint.setTextSize(h * .145f); c.drawText(label(), w / 2f, h * .715f, paint);
    }

    private int accentColor() {
        switch (kind) {
            case IMAGE: return Color.rgb(58, 174, 104);
            case VIDEO: return Color.rgb(151, 91, 210);
            case AUDIO: return Color.rgb(239, 97, 147);
            case PDF: return Color.rgb(232, 73, 64);
            case APK: return Color.rgb(54, 167, 143);
            default: return Color.rgb(91, 114, 137);
        }
    }

    private String label() {
        switch (kind) {
            case IMAGE: return "IMG";
            case VIDEO: return "VID";
            case AUDIO: return "AUD";
            case PDF: return "PDF";
            case APK: return "APK";
            default: return "DOC";
        }
    }

    @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); invalidateSelf(); }
    @Override public void setColorFilter(ColorFilter filter) { paint.setColorFilter(filter); invalidateSelf(); }
    @Override public int getOpacity() { return android.graphics.PixelFormat.TRANSLUCENT; }
}
