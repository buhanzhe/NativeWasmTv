package xiao.bu.tv;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.View;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import org.json.JSONArray;
import org.json.JSONObject;

/** Real decoder / SurfaceView retirement, followed immediately by list selection. */
public final class SurfaceRetirementInstrumentation extends Instrumentation {
    private volatile MainActivity activity;
    private Bundle args;
    private File out;
    private final JSONArray rows = new JSONArray();
    interface Work { void run() throws Exception; }
    interface Condition { boolean get() throws Exception; }
    public void onCreate(Bundle value) { super.onCreate(value); args=value; start(); }
    public void callActivityOnResume(Activity value) {
        super.callActivityOnResume(value);
        if (value instanceof MainActivity) activity=(MainActivity)value;
    }
    private Object get(String name) throws Exception { Field f=MainActivity.class.getDeclaredField(name); f.setAccessible(true); return f.get(activity); }
    private void set(String name,Object value) throws Exception { Field f=MainActivity.class.getDeclaredField(name); f.setAccessible(true); f.set(activity,value); }
    private Object call(String name,Class<?>[] types,Object... values) throws Exception { Method m=MainActivity.class.getDeclaredMethod(name,types); m.setAccessible(true); return m.invoke(activity,values); }
    private void main(Work work) throws Exception {
        Throwable[] error={null}; runOnMainSync(()->{try{work.run();}catch(Throwable e){error[0]=e;}});
        if(error[0]!=null)throw new AssertionError(error[0]);
    }
    private static void check(boolean value,String text) { if(!value)throw new AssertionError(text); }
    private void until(Condition condition,long timeout,String text) throws Exception {
        long deadline=SystemClock.elapsedRealtime()+timeout;
        while(!condition.get()&&SystemClock.elapsedRealtime()<deadline)SystemClock.sleep(40);
        check(condition.get(),text);
    }
    private void ready(int index) throws Exception {
        until(()->(Integer)get("currentChannelIndex")==index&&(Boolean)get("videoRenderingStarted")
                &&!(Boolean)get("channelSwitchAnimating"),20000,"No target video frame: "+index);
        SystemClock.sleep(160);
    }
    private void touch(int action,long down,float x,float y) throws Exception {
        View root=(View)get("root"); int[] at=new int[2]; main(()->root.getLocationOnScreen(at));
        MotionEvent e=MotionEvent.obtain(down,SystemClock.uptimeMillis(),action,at[0]+root.getWidth()*x,at[1]+root.getHeight()*y,0);
        e.setSource(InputDevice.SOURCE_TOUCHSCREEN); sendPointerSync(e); e.recycle();
    }
    private void swipe(boolean cancel) throws Exception {
        long down=SystemClock.uptimeMillis(); touch(0,down,.8f,.7f);
        for(int i=1;i<=6;i++){touch(2,down,.8f,.7f-.35f*i/6);SystemClock.sleep(20);}
        touch(cancel?3:1,down,.8f,.35f);
    }
    private boolean color(int index,String name) throws Exception {
        Bitmap image=getUiAutomation().takeScreenshot(); check(image!=null,"No screenshot");
        try {
            int pixel=image.getPixel(image.getWidth()*3/5,image.getHeight()/3);
            if(name!=null)try(FileOutputStream f=new FileOutputStream(new File(out,name+".png"))){image.compress(Bitmap.CompressFormat.PNG,100,f);}
            int r=(pixel>>16)&255,b=pixel&255;
            return index<0?r>b+70||b>r+70:index==0?r>b+70:b>r+70;
        } finally {image.recycle();}
    }
    private boolean debugOwnership() { return Boolean.parseBoolean(args.getString("debugOwnership", "false")); }
    private void debug(int index) throws Exception {
        if (!debugOwnership()) return;
        main(()->call("refreshVideoInfo",new Class[]{}));
        String text=((android.widget.TextView)get("debugInfoOverlay")).getText().toString();
        String expected=index==0?"640x360":"960x540",previous=index==0?"960x540":"640x360";
        check(text.contains(expected)&&!text.contains(previous),"HUD contains wrong channel metadata: "+text);
        check(text.contains(index==0?"/red/live.m3u8":"/blue/live.m3u8"),"HUD contains wrong source: "+text);
    }
    private void webSwipe(String base) throws Exception {
        WebSourceView web=(WebSourceView)get("webSourceView");
        boolean minimal=(Boolean)get("webViewMinimalMode"),covered=(Boolean)get("webViewPageCover");
        boolean sniff=(Boolean)get("webViewAutoPlaySniffed");
        try {
            String list="#EXTM3U\n#EXTINF:-1 group-title=\"Web swipe\",A\nwebview://"+args.getString("webA",base+"/a")+"\n#EXTINF:-1 group-title=\"Web swipe\",B\nwebview://"+args.getString("webB",base+"/b")+"\n";
            Method parse=PlaylistManager.class.getDeclaredMethod("parse",byte[].class,String.class);parse.setAccessible(true);
            ChannelCatalog.Group[] fixture=(ChannelCatalog.Group[])parse.invoke(null,list.getBytes("UTF-8"),base+"/list.m3u");
            main(()->{set("startupMaintenanceDestroyed",true);set("webViewMinimalMode",true);set("webViewAutoPlaySniffed",false);
                web.applyMinimalMode(true);web.applyPageCover(false);ChannelCatalog.GROUPS=fixture;set("currentGroupIndex",0);set("currentSourceIndex",0);
                call("startChannel",new Class[]{int.class},0);});
            until(()->{boolean[] ready={false};main(()->ready[0]=web.isChannelPresentationReady((Integer)get("playRequestId")));return ready[0];},20000,"Initial webpage not playing");
            final long start=SystemClock.elapsedRealtime();final boolean[] tracing={true};
            Runnable sample=new Runnable(){public void run(){if(!tracing[0])return;try{
                Field holder=WebSourceView.class.getDeclaredField("channelSwipe");holder.setAccessible(true);Object swipe=holder.get(web);
                Field stage=WebChannelSwipe.class.getDeclaredField("stage");stage.setAccessible(true);
                rows.put(new JSONObject().put("t",SystemClock.elapsedRealtime()-start).put("y",web.getTranslationY())
                        .put("stage",stage.getInt(swipe)).put("request",get("playRequestId"))
                        .put("frameCover",get("webLastFrame")!=null&&((WebLastFrame)get("webLastFrame")).active()));
            }catch(Exception e){throw new AssertionError(e);}web.postOnAnimation(this);}};
            main(()->web.postOnAnimation(sample));
            if(Boolean.parseBoolean(args.getString("adbSwipe","false"))) {
                try(android.os.ParcelFileDescriptor command=getUiAutomation().executeShellCommand("input swipe 1500 830 1500 220 650");
                        java.io.FileInputStream input=new java.io.FileInputStream(command.getFileDescriptor())) {while(input.read()!=-1){} }
            } else swipe(false);
            SystemClock.sleep(4500);main(()->tracing[0]=false);
            Bitmap image=getUiAutomation().takeScreenshot();if(image!=null)try(FileOutputStream f=new FileOutputStream(new File(out,"web-after.png"))){image.compress(Bitmap.CompressFormat.PNG,100,f);}finally{image.recycle();}
            check((Integer)get("currentChannelIndex")==1,"Web swipe did not select next channel");
        } finally {
            main(()->{set("webViewMinimalMode",minimal);set("webViewAutoPlaySniffed",sniff);web.applyMinimalMode(minimal);web.applyPageCover(covered);});
        }
    }
    public void onStart() {
        Bundle result=new Bundle(); int code=1,g=0,c=0,s=0;boolean saved=false;
        ChannelCatalog.Group[] groups=ChannelCatalog.GROUPS;
        String left="brightness",right="channel";boolean automatic=false,maintenance=false,showDebug=false;
        try {
            getTargetContext().startActivity(new Intent(getTargetContext(),MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            until(()->activity!=null,60000,"No Activity");SystemClock.sleep(900);
            out=new File(getTargetContext().getExternalFilesDir(null),args.getString("out","surface-retirement"));out.mkdirs();
            g=(Integer)get("currentGroupIndex");c=(Integer)get("currentChannelIndex");s=(Integer)get("currentSourceIndex");
            groups=ChannelCatalog.GROUPS;left=(String)get("leftVerticalGesture");right=(String)get("rightVerticalGesture");automatic=(Boolean)get("autoSwitchSource");saved=true;
            maintenance=(Boolean)get("startupMaintenanceDestroyed");
            showDebug=(Boolean)get("showDebugInfo");
            if(Boolean.parseBoolean(args.getString("webSwipe","false"))){webSwipe(args.getString("base"));result.putString("stream","Web swipe trace saved\n");code=-1;} else {
            String base=args.getString("base"),m3u="#EXTM3U\n#EXTINF:-1 group-title=\"Surface test\",Red\n"+base+"/red/live.m3u8\n#EXTINF:-1 group-title=\"Surface test\",Blue\n"+base+"/blue/live.m3u8\n";
            Method parse=PlaylistManager.class.getDeclaredMethod("parse",byte[].class,String.class);parse.setAccessible(true);
            ChannelCatalog.Group[] fixture=(ChannelCatalog.Group[])parse.invoke(null,m3u.getBytes("UTF-8"),base+"/list.m3u");
            main(()->{set("startupMaintenanceDestroyed",true);if(debugOwnership())set("showDebugInfo",true);ChannelCatalog.GROUPS=fixture;set("currentGroupIndex",0);set("currentSourceIndex",0);set("leftVerticalGesture","brightness");set("rightVerticalGesture","channel");set("autoSwitchSource",false);call("startChannel",new Class[]{int.class},0);});
            ready(0);debug(0);check(color(0,"initial-red"),"Initial picture is not red");
            for(int i=0;i<12;i++) {
                DirectVideoView outgoing=(DirectVideoView)get("videoView");
                swipe(false);ready(1);debug(1);
                check(outgoing.getParent()==null&&!outgoing.isSurfaceReady(),"Retired Surface still attached/valid");
                check(!outgoing.getSurfaceView().getHolder().getSurface().isValid(),"Retired native Surface still retains buffer");
                check(get("standbyVideoView")==null&&get("retiringVideoView")==null,"Retired references remain");
                check(color(1,i<3?"swipe-blue-"+i:null),"Previous red picture appeared after swipe");
                DirectVideoView listSurface=(DirectVideoView)get("videoView");
                main(()->{call("openChannelList",new Class[]{});set("browsingGroupIndex",0);call("switchBrowsingChannel",new Class[]{int.class},0);
                    if(debugOwnership())check(((android.widget.TextView)get("debugInfoOverlay")).getText().length()==0&&get("latestPlaybackDebugStats")==null,"Old HUD survives menu close");
                    check(get("videoView")==listSurface&&!listSurface.isFrameCovered()&&listSurface.isSurfaceReady(),"List switch cleared the retained frame");
                });
                // Sample while the target is opening as well as after first frame.
                for(int n=0;n<3;n++){SystemClock.sleep(60);
                    check(color(-1,null),"List handoff showed a black frame");
                    if(debugOwnership())check(!((android.widget.TextView)get("debugInfoOverlay")).getText().toString().contains("960x540"),"Old HUD restored during prepare");
                }
                ready(0);debug(0);check(color(0,i<3?"list-red-"+i:null),"Wrong picture after list switch");
                check(get("videoView")==listSurface,"List switch replaced the existing SurfaceView");
                rows.put(new JSONObject().put("cycle",i+1).put("retiredDetached",true).put("oldSurfaceInvalid",true).put("correctPictures",true).put("currentChannelHud",debugOwnership()).put("listFramePreserved",true));
            }
            DirectVideoView active=(DirectVideoView)get("videoView");swipe(true);SystemClock.sleep(300);
            check(get("videoView")==active&&active.isSurfaceReady()&&active.getParent()!=null&&color(0,"cancel-keeps-red"),"Cancelled swipe discarded active output");
            result.putString("stream","PASS 12 swipe -> list cycles, retired output detached, old native Surface invalid, target colours verified, cancelled gesture preserves active output\n");code=-1;
            }
        } catch(Throwable error) {result.putString("stream",android.util.Log.getStackTraceString(error));}
        finally {
            if(saved)try{final ChannelCatalog.Group[] original=groups;final int og=g,oc=c,os=s;final String ol=left,or=right;final boolean oa=automatic,om=maintenance,od=showDebug;
                main(()->{ChannelCatalog.GROUPS=original;set("startupMaintenanceDestroyed",om);set("showDebugInfo",od);set("currentGroupIndex",og);set("currentSourceIndex",os);set("leftVerticalGesture",ol);set("rightVerticalGesture",or);set("autoSwitchSource",oa);call("startChannel",new Class[]{int.class},oc);});
            }catch(Exception ignored){}
            if(out!=null)try(FileOutputStream f=new FileOutputStream(new File(out,"results.json"))){f.write(rows.toString(2).getBytes("UTF-8"));}catch(Exception ignored){}
        }
        finish(code,result);
    }
}
