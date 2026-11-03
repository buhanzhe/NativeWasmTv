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
    private volatile int generation;
    private HandlerThread worker;
    private Handler workerHandler;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private long captureStarted;
    interface FrameRateSource { float frameRate(); }
    private FrameRateSource frameRateSource;
    void setFrameRateSource(FrameRateSource source) { frameRateSource = source; }
    private boolean hasBars() {
        android.view.SurfaceView surface = owner.getSurfaceView();
        return surface.getWidth() > 0 && surface.getHeight() > 0
                && (surface.getLeft() > 0 || surface.getTop() > 0
                || surface.getRight() < owner.getWidth() || surface.getBottom() < owner.getHeight());
    }
    private long frameDelay() {
        float fps = frameRateSource == null ? 30f : frameRateSource.frameRate();
        if (fps < 0f) return 250L; // Paused/buffering: poll state without copying frames.
        if (!(fps >= 1f)) fps = 30f;
        android.view.Display display = owner.getDisplay();
        float refresh = display == null ? 60f : display.getRefreshRate();
        return Math.max(1L, (long) Math.ceil(1000d / Math.min(fps, refresh > 0 ? refresh : 60f)));
    }
    private void schedule() {
        if (pending || !enabled || !hasBars() || !owner.isShown() || owner.isFrameCovered()) return;
        pending = true;
        owner.postDelayed(tick, Math.max(0L, frameDelay() - (SystemClock.uptimeMillis() - captureStarted)));
    }
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
        image = null; // RenderThread may still hold the previous display list.
        if (worker != null) { worker.quitSafely(); worker = null; workerHandler = null; }
        owner.invalidate();
    }
    void draw(Canvas canvas) {
        if (!enabled || !owner.isShown() || owner.isFrameCovered()) return;
        if (!hasBars()) { if (image != null || pending) clear(); return; }
        if (image != null) {
            float scale = Math.max((float) owner.getWidth()/image.getWidth(),
                    (float) owner.getHeight()/image.getHeight());
            int w = Math.round(image.getWidth()*scale), h = Math.round(image.getHeight()*scale);
            int x = (owner.getWidth()-w)/2, y = (owner.getHeight()-h)/2;
            target.set(x,y,x+w,y+h);
            android.view.SurfaceView surface = owner.getSurfaceView();
            int saved = canvas.save();
            canvas.clipRect(surface.getLeft(), surface.getTop(), surface.getRight(), surface.getBottom(), Region.Op.DIFFERENCE);
            canvas.drawBitmap(image,null,target,paint);
            canvas.drawColor(0x30000000);
            canvas.restoreToCount(saved);
        }
        schedule();
    }
    private void capture() {
        if (!enabled || !owner.isShown() || owner.isFrameCovered()
                || !hasBars() || !owner.isSurfaceReady() || Build.VERSION.SDK_INT < 24) {
            pending=false; return;
        }
        int w=owner.getSurfaceView().getWidth(), h=owner.getSurfaceView().getHeight();
        if(w<=0 || h<=0) {pending=false;return;}
        if (frameRateSource != null && frameRateSource.frameRate() < 0f) {
            pending = false; captureStarted = SystemClock.uptimeMillis(); schedule(); return;
        }
        captureStarted = SystemClock.uptimeMillis();
        final int token=generation;
        if (worker == null) {
            worker = new HandlerThread("video-blur", android.os.Process.THREAD_PRIORITY_BACKGROUND);
            worker.start(); workerHandler = new Handler(worker.getLooper());
        }
        float scale=96f/Math.max(w,h);
        final Bitmap sample=Bitmap.createBitmap(Math.max(1,Math.round(w*scale)),
                Math.max(1,Math.round(h*scale)),Bitmap.Config.ARGB_8888);
        // PixelCopy must return to a looper that outlives clear(). A worker
        // stopped during the copy can otherwise lose its callback and bitmap.
        Api24.copy(owner,sample,ui,result->{
            if(token!=generation){sample.recycle();return;}
            if(result!=0){completeCapture(token,sample,result);return;}
            if(!workerHandler.post(()->{
                if(token==generation)gaussian(sample);
                ui.post(()->completeCapture(token,sample,result));
            }))completeCapture(token,sample,-1);
        });
    }
    private void completeCapture(int token,Bitmap sample,int result) {
        if(token!=generation){sample.recycle();return;}
        if(result==0) {
            // Published bitmaps may still be used by the hardware display list.
            // Only recycle samples that were never drawn; let GC release old images.
            image=sample;
            owner.invalidate();
        } else sample.recycle();
        pending=false;
        // Back off unavailable surfaces rather than retrying every display frame.
        if(result!=0)captureStarted=SystemClock.uptimeMillis()+200L;
        schedule();
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
        static void copy(DirectVideoView v,Bitmap b,Handler handler,Result result){
            try {android.view.PixelCopy.request(v.getSurfaceView(),b,result::done,
                    handler);}
            catch(IllegalArgumentException e){result.done(-1);}
        }
    }
}
