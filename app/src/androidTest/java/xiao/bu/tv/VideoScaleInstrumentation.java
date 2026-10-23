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
        Bundle result=new Bundle();
        try {
            runOnMainSync(()->{
                DirectVideoView v=new DirectVideoView(getTargetContext());
                v.setVideoSize(1920,1080,1,1);
                measure(v);check(v.getMeasuredWidth()==1920,"Original ratio");
                v.setAspectRatio(2f);measure(v);
                check(v.getMeasuredWidth()==2160 && v.getMeasuredHeight()==1080,"18:9 size");
                v.setAspectRatio(0);v.setBlurBackground(true);measure(v);
                check(v.getMeasuredWidth()==2400 && v.getSurfaceView().getMeasuredWidth()==1920,"Blur viewport and original foreground");
                v.layout(0,0,2400,1080);
                check(v.getSurfaceView().getLeft()==240,"Foreground centered");
                v.setFrameCovered(true);v.setFrameCovered(false);
                v.setBlurBackground(false);v.setFillVideo(true);measure(v);
                check(v.getMeasuredWidth()==2400 && v.getSurfaceView().getMeasuredHeight()==1350,"Crop dimensions");
                v.setFillVideo(false);measure(v);check(v.getMeasuredWidth()==1920,"Exit mode restores fit");
                v.releaseOutput();
            });
            Bitmap bitmap=Bitmap.createBitmap(25,25,Bitmap.Config.ARGB_8888);
            bitmap.eraseColor(Color.BLACK);bitmap.setPixel(12,12,Color.WHITE);
            Method blur=VideoBlurBackdrop.class.getDeclaredMethod("gaussian",Bitmap.class);blur.setAccessible(true);blur.invoke(null,bitmap);
            check(Color.red(bitmap.getPixel(12,12))>0 && Color.red(bitmap.getPixel(12,12))<255,"Gaussian spreads impulse");
            check(bitmap.getPixel(11,12)==bitmap.getPixel(13,12),"Gaussian symmetry");bitmap.recycle();
            MainActivity activity=(MainActivity)startActivitySync(new android.content.Intent(getTargetContext(),MainActivity.class)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK));
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
                    live[0].setFrameCovered(true);check(image.get(controller.get(live[0]))==null,"Switch clears old backdrop");
                    ((android.widget.FrameLayout)activity.findViewById(R.id.root)).removeView(live[0]);activity.finish();
                }catch(ReflectiveOperationException e){throw new RuntimeException(e);}
            });
            result.putString("result","PASS: original, 18:9, blur layout/centering, crop, restore, Gaussian impulse/symmetry");
            finish(-1,result);
        }catch(Throwable e){result.putString("error",e.toString());finish(0,result);}
    }
    private static void measure(View v){v.measure(View.MeasureSpec.makeMeasureSpec(2400,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(1080,View.MeasureSpec.EXACTLY));}
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
}
