package com.kemi.mypad;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;

/** One coherent, touch-readable macOS-style icon family for the main resource sidebar. */
final class SidebarIconDrawable extends Drawable {
    enum Kind { RECENT, DOWNLOAD, FAVORITE, STORAGE, USB, NETWORK, SCREENS, APPS, TOOLS, SETTINGS, CLEAN, SEND, EXIT }

    private final Kind kind;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private int color;

    SidebarIconDrawable(Kind kind, int color) { this.kind = kind; this.color = color; }

    @Override public void draw(Canvas canvas) {
        float w = getBounds().width(), h = getBounds().height();
        canvas.save(); canvas.translate(getBounds().left, getBounds().top);
        paint.setColor(color); paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(Math.max(1.7f, w * .075f));
        paint.setStrokeCap(Paint.Cap.ROUND); paint.setStrokeJoin(Paint.Join.ROUND);
        switch (kind) {
            case RECENT: circle(canvas,w,h); canvas.drawLine(w*.5f,h*.5f,w*.5f,h*.27f,paint); canvas.drawLine(w*.5f,h*.5f,w*.69f,h*.61f,paint); break;
            case DOWNLOAD: canvas.drawLine(w*.5f,h*.14f,w*.5f,h*.64f,paint); chevron(canvas,w*.28f,h*.47f,w*.5f,h*.69f,w*.72f,h*.47f); canvas.drawLine(w*.2f,h*.84f,w*.8f,h*.84f,paint); break;
            case FAVORITE: star(canvas,w,h); break;
            case STORAGE: canvas.drawRoundRect(new RectF(w*.14f,h*.2f,w*.86f,h*.8f),w*.08f,w*.08f,paint); canvas.drawLine(w*.2f,h*.61f,w*.8f,h*.61f,paint); canvas.drawCircle(w*.69f,h*.71f,w*.035f,paint); break;
            case USB: canvas.drawRoundRect(new RectF(w*.25f,h*.27f,w*.75f,h*.84f),w*.1f,w*.1f,paint); canvas.drawRect(w*.36f,h*.1f,w*.64f,h*.3f,paint); canvas.drawLine(w*.43f,h*.1f,w*.43f,h*.22f,paint); canvas.drawLine(w*.57f,h*.1f,w*.57f,h*.22f,paint); break;
            case NETWORK: canvas.drawRect(w*.16f,h*.19f,w*.84f,h*.67f,paint); canvas.drawLine(w*.5f,h*.67f,w*.5f,h*.82f,paint); canvas.drawLine(w*.31f,h*.82f,w*.69f,h*.82f,paint); break;
            case SCREENS: canvas.drawRect(w*.08f,h*.18f,w*.64f,h*.66f,paint); canvas.drawRect(w*.43f,h*.38f,w*.92f,h*.82f,paint); break;
            case APPS: for(int y=0;y<2;y++) for(int x=0;x<2;x++) canvas.drawRoundRect(new RectF(w*(.14f+x*.4f),h*(.14f+y*.4f),w*(.42f+x*.4f),h*(.42f+y*.4f)),w*.06f,w*.06f,paint); break;
            case TOOLS: canvas.drawLine(w*.2f,h*.8f,w*.78f,h*.22f,paint); canvas.drawCircle(w*.23f,h*.23f,w*.12f,paint); canvas.drawCircle(w*.77f,h*.77f,w*.12f,paint); break;
            case SETTINGS: gear(canvas,w,h); break;
            case CLEAN: canvas.drawRoundRect(new RectF(w*.22f,h*.27f,w*.78f,h*.84f),w*.07f,w*.07f,paint); canvas.drawLine(w*.16f,h*.24f,w*.84f,h*.24f,paint); canvas.drawLine(w*.37f,h*.13f,w*.63f,h*.13f,paint); break;
            case SEND: path.reset(); path.moveTo(w*.1f,h*.45f); path.lineTo(w*.88f,h*.12f); path.lineTo(w*.65f,h*.88f); path.lineTo(w*.44f,h*.59f); path.close(); canvas.drawPath(path,paint); canvas.drawLine(w*.44f,h*.59f,w*.88f,h*.12f,paint); break;
            case EXIT: canvas.drawLine(w*.5f,h*.12f,w*.5f,h*.5f,paint); canvas.drawArc(new RectF(w*.15f,h*.2f,w*.85f,h*.9f),-50,280,false,paint); break;
        }
        canvas.restore();
    }

    private void circle(Canvas c,float w,float h){ c.drawCircle(w*.5f,h*.5f,w*.36f,paint); }
    private void chevron(Canvas c,float ax,float ay,float bx,float by,float cx,float cy){ path.reset();path.moveTo(ax,ay);path.lineTo(bx,by);path.lineTo(cx,cy);c.drawPath(path,paint); }
    private void star(Canvas c,float w,float h){ path.reset(); for(int i=0;i<10;i++){ double a=Math.toRadians(-90+i*36);float r=i%2==0?w*.38f:w*.17f;float x=w*.5f+(float)Math.cos(a)*r,y=h*.5f+(float)Math.sin(a)*r;if(i==0)path.moveTo(x,y);else path.lineTo(x,y);}path.close();c.drawPath(path,paint); }
    private void gear(Canvas c,float w,float h){ c.drawCircle(w*.5f,h*.5f,w*.3f,paint);c.drawCircle(w*.5f,h*.5f,w*.1f,paint);for(int i=0;i<8;i++){double a=Math.toRadians(i*45);c.drawLine(w*.5f+(float)Math.cos(a)*w*.31f,h*.5f+(float)Math.sin(a)*h*.31f,w*.5f+(float)Math.cos(a)*w*.42f,h*.5f+(float)Math.sin(a)*h*.42f,paint);} }

    @Override public void setTint(int tintColor) { color = tintColor; invalidateSelf(); }
    @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); invalidateSelf(); }
    @Override public void setColorFilter(ColorFilter colorFilter) { paint.setColorFilter(colorFilter); invalidateSelf(); }
    @Override public int getOpacity() { return android.graphics.PixelFormat.TRANSLUCENT; }
}
