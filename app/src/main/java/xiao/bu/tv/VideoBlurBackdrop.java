package xiao.bu.tv;

import android.graphics.*;
import android.os.*;

/** Bounded single-surface sampling; no second decoder or full-resolution bitmap. */
final class VideoBlurBackdrop {
    private final DirectVideoView owner;
    private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Rect target = new Rect();
    private Bitmap image;
    private boolean enabled, pending;
    private int generation;
    private final Runnable tick = new Runnable() {
        @Override public void run() { capture(); }
    };
    VideoBlurBackdrop(DirectVideoView owner) { this.owner = owner; }
    void enable(boolean value) {
        if (enabled == value) return;
        enabled = value; clear(); owner.invalidate();
    }
    void clear() {
        generation++; pending = false; owner.removeCallbacks(tick);
        if (image != null) { image.recycle(); image = null; }
        owner.invalidate();
    }
    void draw(Canvas canvas) {
        if (!enabled || !owner.isShown() || owner.isFrameCovered()) return;
        if (image != null) {
            float scale = Math.max((float) owner.getWidth()/image.getWidth(),
                    (float) owner.getHeight()/image.getHeight());
            int w = Math.round(image.getWidth()*scale), h = Math.round(image.getHeight()*scale);
            int x = (owner.getWidth()-w)/2, y = (owner.getHeight()-h)/2;
            target.set(x,y,x+w,y+h); canvas.drawBitmap(image,null,target,paint);
            canvas.drawColor(0x30000000);
        }
        if (!pending) { pending=true; owner.postDelayed(tick, 1000); }
    }
    private void capture() {
        if (!enabled || !owner.isShown() || owner.isFrameCovered()
                || !owner.isSurfaceReady() || Build.VERSION.SDK_INT < 24) {
            pending=false; return;
        }
        int w=owner.getSurfaceView().getWidth(), h=owner.getSurfaceView().getHeight();
        if(w<=0 || h<=0) {pending=false;return;}
        final int token=generation;
        float scale=96f/Math.max(w,h);
        final Bitmap sample=Bitmap.createBitmap(Math.max(1,Math.round(w*scale)),
                Math.max(1,Math.round(h*scale)),Bitmap.Config.ARGB_8888);
        Api24.copy(owner,sample,result->{
            if(result!=0){sample.recycle();if(token==generation){pending=false;owner.invalidate();}return;}
            new Thread(()->{
                gaussian(sample);
                owner.post(()->{
                    if(token!=generation){sample.recycle();return;}
                    Bitmap old=image;image=sample;pending=false;
                    if(old!=null)old.recycle();owner.invalidate();
                });
            },"video-blur").start();
        });
    }
    private static void gaussian(Bitmap b) {
        int w=b.getWidth(),h=b.getHeight();int[] a=new int[w*h],out=new int[w*h];
        int[] kernel={1,4,6,4,1};b.getPixels(a,0,w,0,0,w,h);
        for(int pass=0;pass<6;pass++){
            boolean horizontal=pass%2==0;
            for(int y=0;y<h;y++)for(int x=0;x<w;x++){
                int r=0,g=0,blue=0;
                for(int i=-2;i<=2;i++){
                    int xx=horizontal?Math.max(0,Math.min(w-1,x+i)):x;
                    int yy=horizontal?y:Math.max(0,Math.min(h-1,y+i));
                    int c=a[yy*w+xx],k=kernel[i+2];
                    r+=((c>>16)&255)*k;g+=((c>>8)&255)*k;blue+=(c&255)*k;
                }
                out[y*w+x]=0xff000000|((r/16)<<16)|((g/16)<<8)|(blue/16);
            }
            int[] swap=a;a=out;out=swap;
        }
        b.setPixels(a,0,w,0,0,w,h);
    }
    private interface Result {void done(int result);}
    @android.annotation.TargetApi(24) private static final class Api24 {
        static void copy(DirectVideoView v,Bitmap b,Result result){
            try {android.view.PixelCopy.request(v.getSurfaceView(),b,result::done,
                    new Handler(Looper.getMainLooper()));}
            catch(IllegalArgumentException e){result.done(-1);}
        }
    }
}
