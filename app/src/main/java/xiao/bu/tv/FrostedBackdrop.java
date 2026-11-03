package xiao.bu.tv;

import android.graphics.*;
import android.os.*;
import android.view.*;

/** Optional SD/HD snapshot. UHD menus use the skin's gradient without touching video. */
public final class FrostedBackdrop {
    private interface Result { void done(int result); }
    private final View owner;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private BitmapShader shader;
    private final Matrix transform = new Matrix();
    private final RectF target = new RectF();
    private final int[] location = new int[2];
    private Bitmap image;
    private int generation;
    // All capture entry points and publication run on the main looper. Keep a
    // single optional readback in flight, including after its panel is closed.
    private static boolean copying;
    private boolean attempted;
    public FrostedBackdrop(View owner) { this.owner = owner; }
    public void clear() { generation++; attempted=false; image=null; shader=null; }
    public void capture() {
        if (Build.VERSION.SDK_INT < 24 || attempted || copying || !owner.isShown()) return;
        SurfaceView surface = surface(owner.getRootView());
        if (surface == null || surface.getWidth() <= 0 || surface.getHeight() <= 0) return;
        // Check both source metadata and the real buffer, not just screen size.
        android.graphics.Rect frame=surface.getHolder().getSurfaceFrame();
        if (frame.width() <= 0 || frame.height() <= 0
                || (long)frame.width()*frame.height() > 1920L*1080) return;
        attempted=true;
        int token=++generation;
        surface.getLocationOnScreen(location);
        int sx=location[0],sy=location[1],sw=surface.getWidth(),sh=surface.getHeight();
        float scale=128f/Math.max(sw,sh);
        final Bitmap sample;
        try { sample=Bitmap.createBitmap(Math.max(1,Math.round(sw*scale)),
                Math.max(1,Math.round(sh*scale)),Bitmap.Config.ARGB_8888); }
        catch (OutOfMemoryError unavailable) { return; }
        Handler ui=new Handler(Looper.getMainLooper());
        copying=true;
        Api24.copy(surface,sample,result->{
            if(result!=0 || token!=generation || !owner.isShown()){
                sample.recycle();copying=false;
                return;
            }
            new Thread(()->{
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);
                boolean ready=false;
                try { blur(sample); ready=true; }
                catch (OutOfMemoryError unavailable) { /* The gradient needs no bitmap. */ }
                final boolean blurred=ready;
                ui.post(()->{
                    copying=false;
                    if(!blurred || token!=generation || !owner.isShown()){sample.recycle();return;}
                    image=sample;
                    owner.getLocationOnScreen(location);
                    target.set(sx-location[0],sy-location[1],sx-location[0]+sw,sy-location[1]+sh);
                    shader=new BitmapShader(sample,Shader.TileMode.CLAMP,Shader.TileMode.CLAMP);
                    transform.setScale(target.width()/sample.getWidth(),target.height()/sample.getHeight());
                    transform.postTranslate(target.left,target.top);shader.setLocalMatrix(transform);
                    owner.invalidate();
                });
            },"theme-glass").start();
        },ui);
    }
    public boolean draw(Canvas canvas,RectF bounds,float radius) {
        if(shader==null)return false;
        paint.setShader(shader);canvas.drawRoundRect(bounds,radius,radius,paint);paint.setShader(null);
        return true;
    }
    private static SurfaceView surface(View view) {
        if(view instanceof DirectVideoView) {
            DirectVideoView video=(DirectVideoView)view;
            return video.isShown() && video.canCaptureMenuBackdrop() ? video.getSurfaceView() : null;
        }
        if(view instanceof ViewGroup){ViewGroup group=(ViewGroup)view;for(int i=0;i<group.getChildCount();i++){SurfaceView found=surface(group.getChildAt(i));if(found!=null)return found;}}
        return null;
    }
    private static void blur(Bitmap bitmap) {
        int w=bitmap.getWidth(),h=bitmap.getHeight();int[] src=new int[w*h],out=new int[w*h];
        bitmap.getPixels(src,0,w,0,0,w,h);
        for(int y=0;y<h;y++)for(int x=0;x<w;x++){
            int red=0,green=0,blue=0,count=0;
            for(int yy=Math.max(0,y-4);yy<=Math.min(h-1,y+4);yy++)for(int xx=Math.max(0,x-4);xx<=Math.min(w-1,x+4);xx++){
                int color=src[yy*w+xx];red+=(color>>16)&255;green+=(color>>8)&255;blue+=color&255;count++;
            }
            // Precompose the dark glass tint here, so its outer antialiased edge
            // is drawn once rather than accumulating several translucent rims.
            int sheen=5*(w+h-x-y)/Math.max(1,w+h);
            red=33+sheen+red/count*3/10;
            green=32+sheen+green/count*3/10;
            blue=32+sheen+blue/count*3/10;
            out[y*w+x]=0xff000000|(red<<16)|(green<<8)|blue;
        }
        bitmap.setPixels(out,0,w,0,0,w,h);
    }
    @android.annotation.TargetApi(24) private static final class Api24 {
        static void copy(SurfaceView view,Bitmap bitmap,Result listener,Handler handler) {
            try {android.view.PixelCopy.request(view,bitmap,listener::done,handler);}
            catch(RuntimeException | OutOfMemoryError error){listener.done(-1);}
        }
    }
}
