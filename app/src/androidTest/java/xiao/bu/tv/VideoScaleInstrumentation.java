package xiao.bu.tv;

import android.app.Instrumentation;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.Bundle;
import android.view.View;
import java.lang.reflect.Method;

public final class VideoScaleInstrumentation extends Instrumentation {
    @Override public void onCreate(Bundle args) {super.onCreate(args);start();}
    @Override public void onStart() {
        Bundle result=new Bundle(); android.util.Log.i("BlurCheck","start");
        try {
            runOnMainSync(()->{
                android.util.Log.i("BlurCheck","layout checks");
                DirectVideoView v=new DirectVideoView(getTargetContext());
                v.setVideoSize(1920,1080,1,1);
                measure(v);check(v.getMeasuredWidth()==1920,"Original ratio");
                v.setAspectRatio(2f);measure(v);
                check(v.getMeasuredWidth()==2160 && v.getMeasuredHeight()==1080,"18:9 size");
                v.setAspectRatio(0);v.setBlurBackground(true);measure(v);
                check(v.getMeasuredWidth()==2400 && v.getSurfaceView().getMeasuredWidth()==1920,"Blur viewport and original foreground");
                v.layout(0,0,2400,1080);
                check(v.getSurfaceView().getLeft()==240,"Foreground centered");
                try {
                    java.lang.reflect.Field field=DirectVideoView.class.getDeclaredField("blur");field.setAccessible(true);
                    VideoBlurBackdrop backdrop=(VideoBlurBackdrop)field.get(v);
                    Method bars=VideoBlurBackdrop.class.getDeclaredMethod("hasBars");bars.setAccessible(true);
                    Method delay=VideoBlurBackdrop.class.getDeclaredMethod("frameDelay");delay.setAccessible(true);
                    check((Boolean)bars.invoke(backdrop),"Pillarbox requires backdrop");
                    v.setBlurFrameRateSource(()->25f);
                    check((Long)delay.invoke(backdrop)==40L,"25 fps capture interval");
                    v.setBlurFrameRateSource(()->-1f);
                    check((Long)delay.invoke(backdrop)==250L,"Paused state polling interval");
                    v.setVideoSize(2400,1080,1,1);measure(v);v.layout(0,0,2400,1080);
                    check(!(Boolean)bars.invoke(backdrop),"No sampling for full viewport video");
                    v.setVideoSize(1920,1080,1,1);
                } catch(ReflectiveOperationException e) { throw new RuntimeException(e); }
                v.setFrameCovered(true);v.setFrameCovered(false);
                v.setBlurBackground(false);v.setFillVideo(true);measure(v);
                check(v.getMeasuredWidth()==2400 && v.getSurfaceView().getMeasuredHeight()==1350,"Crop dimensions");
                v.setFillVideo(false);measure(v);check(v.getMeasuredWidth()==1920,"Exit mode restores fit");
                v.releaseOutput();
            });
            android.util.Log.i("BlurCheck","layout passed");
            Bitmap bitmap=Bitmap.createBitmap(25,25,Bitmap.Config.ARGB_8888);
            bitmap.eraseColor(Color.BLACK);bitmap.setPixel(12,12,Color.WHITE);
            Method blur=VideoBlurBackdrop.class.getDeclaredMethod("gaussian",Bitmap.class);blur.setAccessible(true);blur.invoke(null,bitmap);
            check(Color.red(bitmap.getPixel(12,12))>0 && Color.red(bitmap.getPixel(12,12))<255,"Gaussian spreads impulse");
            check(bitmap.getPixel(11,12)==bitmap.getPixel(13,12),"Gaussian symmetry");bitmap.recycle();
            android.util.Log.i("BlurCheck","starting activity");
            MainActivity activity=(MainActivity)startActivitySync(new android.content.Intent(getTargetContext(),MainActivity.class)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK));
            android.util.Log.i("BlurCheck","activity started");
            final DirectVideoView[] live={null};
            runOnMainSync(()->{
                live[0]=new DirectVideoView(activity);live[0].setVideoSize(640,480,1,1);live[0].setBlurBackground(true);
                ((android.widget.FrameLayout)activity.findViewById(R.id.root)).addView(live[0],new android.widget.FrameLayout.LayoutParams(-1,-1));
            });
            android.os.SystemClock.sleep(1500);
            runOnMainSync(()->{
                android.graphics.Canvas c=live[0].getVideoSurfaceHolder().lockCanvas();
                check(c!=null,"Surface canvas");c.drawColor(Color.RED);
                live[0].getVideoSurfaceHolder().unlockCanvasAndPost(c);live[0].invalidate();
            });
            android.os.SystemClock.sleep(2000);
            java.lang.reflect.Field controller=DirectVideoView.class.getDeclaredField("blur");controller.setAccessible(true);
            java.lang.reflect.Field image=VideoBlurBackdrop.class.getDeclaredField("image");image.setAccessible(true);
            runOnMainSync(()->{
                try {
                    Bitmap captured=(Bitmap)image.get(controller.get(live[0]));
                    check(captured!=null,"Real Surface PixelCopy capture");
                    check(Color.red(captured.getPixel(captured.getWidth()/2,captured.getHeight()/2))>200,"Captured current frame");
                    Bitmap output=Bitmap.createBitmap(live[0].getWidth(),live[0].getHeight(),Bitmap.Config.ARGB_8888);
                    output.eraseColor(Color.GREEN);
                    ((VideoBlurBackdrop)controller.get(live[0])).draw(new android.graphics.Canvas(output));
                    check(output.getPixel(output.getWidth()/2,output.getHeight()/2)==Color.GREEN,"Foreground excluded from backdrop drawing");
                    android.view.SurfaceView surface=live[0].getSurfaceView();
                    if(surface.getLeft()>0) check(Color.red(output.getPixel(0,output.getHeight()/2))>150,"Side bars drawn");
                    else if(surface.getTop()>0) check(Color.red(output.getPixel(output.getWidth()/2,0))>150,"Letterbox bars drawn");
                    output.recycle();
                }catch(ReflectiveOperationException e){throw new RuntimeException(e);}
                android.graphics.Canvas c=live[0].getVideoSurfaceHolder().lockCanvas();
                c.drawColor(Color.BLUE);live[0].getVideoSurfaceHolder().unlockCanvasAndPost(c);
            });
            android.os.SystemClock.sleep(500);
            runOnMainSync(()->{
                try {
                    Bitmap captured=(Bitmap)image.get(controller.get(live[0]));
                    check(Color.blue(captured.getPixel(captured.getWidth()/2,captured.getHeight()/2))>200,"Backdrop follows changed frame within 500ms");
                    live[0].setBlurFrameRateSource(()->-1f);
                }catch(ReflectiveOperationException e){throw new RuntimeException(e);}
            });
            android.os.SystemClock.sleep(150);
            runOnMainSync(()->{
                android.graphics.Canvas c=live[0].getVideoSurfaceHolder().lockCanvas();
                c.drawColor(Color.RED);live[0].getVideoSurfaceHolder().unlockCanvasAndPost(c);
            });
            android.os.SystemClock.sleep(350);
            runOnMainSync(()->{
                try {
                    Bitmap captured=(Bitmap)image.get(controller.get(live[0]));
                    check(Color.blue(captured.getPixel(captured.getWidth()/2,captured.getHeight()/2))>200,"Paused backdrop stops sampling");
                    live[0].setFrameCovered(true);check(image.get(controller.get(live[0]))==null,"Switch clears old backdrop");
                    ((android.widget.FrameLayout)activity.findViewById(R.id.root)).removeView(live[0]);activity.finish();
                }catch(ReflectiveOperationException e){throw new RuntimeException(e);}
            });
            result.putString("result","PASS: ratios, centering, Gaussian, black-bar clipping, no-bars skip, fps interval, real PixelCopy, frame update <500ms, paused sampling, switch cleanup");
            finish(-1,result);
        }catch(Throwable e){result.putString("error",e.toString());finish(0,result);}
    }
    private static void measure(View v){v.measure(View.MeasureSpec.makeMeasureSpec(2400,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(1080,View.MeasureSpec.EXACTLY));}
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
}
