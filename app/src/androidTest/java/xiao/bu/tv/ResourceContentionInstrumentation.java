package xiao.bu.tv;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.os.Bundle;
import android.os.Debug;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.View;
import android.widget.ListView;
import java.io.File;
import java.io.PrintWriter;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.json.JSONObject;

/** Same-process, bounded resource contention; never included in ordinary APKs. */
public final class ResourceContentionInstrumentation extends Instrumentation {
    private volatile MainActivity activity;
    private volatile boolean mainResumed;
    private Bundle arguments;
    private final Handler main = new Handler(Looper.getMainLooper());
    private PrintWriter evidence;
    private long started, nextMemory;
    private String phase = "startup";
    private int originalGroup, originalChannel, originalSource;
    private boolean captured;
    private String originalTheme;
    private final Load load = new Load();
    private int samples, actions, switches, pssKb;
    private long maxUiMs;
    interface Work { void run() throws Exception; }

    @Override public void onCreate(Bundle args) { super.onCreate(args); arguments=args; start(); }
    @Override public void callActivityOnResume(Activity value) {
        super.callActivityOnResume(value);
        if (value instanceof MainActivity) { activity = (MainActivity)value; mainResumed = true; }
    }
    @Override public void callActivityOnPause(Activity value) {
        if (value instanceof MainActivity) mainResumed = false;
        super.callActivityOnPause(value);
    }
    private Object field(String name) throws Exception {
        Field f=MainActivity.class.getDeclaredField(name);f.setAccessible(true);return f.get(activity);
    }
    private void set(String name,int value) throws Exception {
        Field f=MainActivity.class.getDeclaredField(name);f.setAccessible(true);f.setInt(activity,value);
    }
    private Object call(String name,Class<?>[] types,Object... args) throws Exception {
        Method m=MainActivity.class.getDeclaredMethod(name,types);m.setAccessible(true);return m.invoke(activity,args);
    }
    private long ui(Work work) throws Exception {
        final CountDownLatch done=new CountDownLatch(1);
        final Throwable[] error={null};long begin=SystemClock.elapsedRealtime();
        main.post(()->{try{work.run();}catch(Throwable failure){error[0]=failure;}finally{done.countDown();}});
        if(!done.await(5,TimeUnit.SECONDS))throw new AssertionError("UI acknowledgement exceeded 5 seconds");
        long elapsed=SystemClock.elapsedRealtime()-begin;maxUiMs=Math.max(maxUiMs,elapsed);
        if(error[0]!=null)throw new AssertionError(error[0]);return elapsed;
    }
    private synchronized void record(JSONObject value) {
        try {
            value.put("elapsedMs",SystemClock.elapsedRealtime()-started).put("phase",phase);
            evidence.println(value);evidence.flush();
            Bundle progress=new Bundle();progress.putString("stream","STRESS_EVENT "+value+"\n");sendStatus(1,progress);
        }catch(Exception failure){android.util.Log.e("ResourceStress","Evidence write failed",failure);}
    }
    private JSONObject sample() throws Exception {
        final JSONObject[] current={null};final int[] request={0};final boolean[] rendered={false};
        long latency=ui(()->{
            current[0]=(JSONObject)call("buildLocalMediaState",new Class[]{boolean.class},false);
            request[0]=(Integer)field("playRequestId");
            rendered[0]=(Boolean)field("videoRenderingStarted")
                    && ((Integer)field("playbackReadyRequestId")).intValue()==request[0];
        });
        if(SystemClock.elapsedRealtime()>=nextMemory){
            Debug.MemoryInfo memory=new Debug.MemoryInfo();Debug.getMemoryInfo(memory);pssKb=memory.getTotalPss();
            nextMemory=SystemClock.elapsedRealtime()+15000;
        }
        JSONObject m=current[0],value=new JSONObject().put("type","sample").put("uiMs",latency)
                .put("request",request[0]).put("rendered",rendered[0]).put("pid",android.os.Process.myPid()).put("mainResumed",mainResumed)
                .put("workerDuty",load.duty).put("workerCpuMs",load.cpuMs).put("workerPasses",load.passes)
                .put("workerAlive",load.isAlive()).put("workerArrayBytes",load.arrayBytes)
                .put("appCpuMs",android.os.Process.getElapsedCpuTime()).put("pssKb",pssKb)
                .put("javaUsedBytes",Runtime.getRuntime().totalMemory()-Runtime.getRuntime().freeMemory())
                .put("javaMaxBytes",Runtime.getRuntime().maxMemory());
        for(String key:new String[]{"name","playing","prepared","outputFps","decodeFps","positionMs",
                "videoCachedDurationMs","audioCachedDurationMs","loadError","sourceIndex","sourceCount"})
            value.put(key,m.opt(key));
        samples++;record(value);
        if(load.failure!=null)throw new AssertionError("Stress worker failed",load.failure);
        return value;
    }
    private void menus() throws Exception {
        long begin=SystemClock.elapsedRealtime();
        ui(()->{call("openTvUiMenu",new Class[]{});
            if(!((TvUiMenu)field("tvUiMenu")).visible())throw new AssertionError("Native menu did not open");});SystemClock.sleep(700);
        ui(()->{TvUiMenu menu=(TvUiMenu)field("tvUiMenu");
            menu.dismiss();call("openChannelList",new Class[]{});
            if(!((View)field("channelListPanel")).isShown())throw new AssertionError("Real channel list is not visible");});
        SystemClock.sleep(700);
        ui(()->call("closeChannelList",new Class[]{}));
        actions++;
        record(new JSONObject().put("type","menu_cycle").put("menuAndListVerified",true).put("durationMs",SystemClock.elapsedRealtime()-begin));
    }
    private void changeChannel(int position) throws Exception {
        long begin=SystemClock.elapsedRealtime();
        ui(()->{((TvUiMenu)field("tvUiMenu")).dismiss();call("closeChannelList",new Class[]{});
            call("openChannelList",new Class[]{});});
        SystemClock.sleep(150);
        final int[] request={0};
        ui(()->{ListView list=(ListView)field("channelList");
            int target=Math.min(position,list.getCount()-1);
            if(target<0)throw new AssertionError("Empty real channel list");
            list.performItemClick(null,target,list.getAdapter().getItemId(target));request[0]=(Integer)field("playRequestId");});
        switches++;
        record(new JSONObject().put("type","channel_switch").put("targetIndex",position)
                .put("request",request[0]).put("dispatchMs",SystemClock.elapsedRealtime()-begin));
    }
    private void stage(String name,int duty,int seconds,boolean switchChannels) throws Exception {
        phase=name;load.duty=duty;
        record(new JSONObject().put("type","phase_begin").put("seconds",seconds).put("duty",duty));
        long until=SystemClock.elapsedRealtime()+seconds*1000L,nextAction=SystemClock.elapsedRealtime()+10000;
        int cursor=0;
        while(SystemClock.elapsedRealtime()<until){
            long tick=SystemClock.elapsedRealtime();sample();
            if(duty>0&&tick>=nextAction){
                if(switchChannels)changeChannel(new int[]{4,7,0,4,7,0}[cursor++%6]);
                else menus();
                nextAction=SystemClock.elapsedRealtime()+28000;
            }
            long remaining=1000-(SystemClock.elapsedRealtime()-tick);if(remaining>0)SystemClock.sleep(remaining);
        }
        record(new JSONObject().put("type","phase_end"));
    }
    @Override public void onStart() {
        int code=-1;String failure="";started=SystemClock.elapsedRealtime();
        try {
            File file=new File(getTargetContext().getExternalFilesDir(null),"resource-stress.jsonl");
            evidence=new PrintWriter(file,"UTF-8");
            record(new JSONObject().put("type","start").put("evidence",file.toString()).put("api",android.os.Build.VERSION.SDK_INT)
                    .put("processors",Runtime.getRuntime().availableProcessors()));
            getTargetContext().startActivity(new Intent(getTargetContext(),MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            long deadline=SystemClock.elapsedRealtime()+60000;
            while(activity==null&&SystemClock.elapsedRealtime()<deadline)SystemClock.sleep(100);
            if(activity==null)throw new AssertionError("Activity did not resume");
            ui(()->{originalGroup=(Integer)field("currentGroupIndex");originalChannel=(Integer)field("currentChannelIndex");
                originalSource=(Integer)field("currentSourceIndex");captured=true;});
            originalTheme=getTargetContext().getSharedPreferences("tv_ui",0).getString("selected","builtin");
            TvUiPlugin.select(getTargetContext(),"ui6");SystemClock.sleep(500);
            record(new JSONObject().put("type","theme").put("selected","ui6").put("original",originalTheme));
            deadline=SystemClock.elapsedRealtime()+60000;
            while(true){JSONObject value=sample();if(value.optBoolean("rendered")&&value.optBoolean("playing"))break;
                if(SystemClock.elapsedRealtime()>deadline)throw new AssertionError("Baseline did not reach a real rendered frame");SystemClock.sleep(1000);}
            load.start();
            if(arguments!=null&&"true".equals(arguments.getString("smoke"))){
                phase="smoke";load.duty=100;menus();stage("smoke",100,20,true);
                return;
            }
            stage("baseline",0,60,false);
            stage("half_load",50,120,false);
            stage("full_load",100,180,false);
            stage("full_load_switching",100,180,true);
            load.stopLoad();load.join(2000);
            if(load.isAlive())throw new AssertionError("Stress worker did not stop");
            stage("recovery",0,60,false);
        }catch(Throwable error){code=1;failure=android.util.Log.getStackTraceString(error);
            if(evidence!=null)try{record(new JSONObject().put("type","failure").put("error",failure));}catch(Exception ignored){}
        }finally{
            load.stopLoad();try{if(load.isAlive())load.join(2000);}catch(InterruptedException ignored){}
            if(originalTheme!=null)try{TvUiPlugin.select(getTargetContext(),originalTheme);}
                catch(Throwable restoreFailure){code=1;failure+="\nTheme restore: "+restoreFailure;}
            if(captured)try{
                ui(()->{((TvUiMenu)field("tvUiMenu")).dismiss();call("closeChannelList",new Class[]{});
                    set("currentGroupIndex",originalGroup);set("currentSourceIndex",originalSource);
                    if((Integer)field("currentChannelIndex")!=originalChannel)call("startChannel",new Class[]{int.class},originalChannel);});
                record(new JSONObject().put("type","restored").put("group",originalGroup).put("channel",originalChannel).put("source",originalSource));
            }catch(Throwable restoreFailure){code=1;failure+="\nRestore: "+restoreFailure;}
            if(evidence!=null){try{record(new JSONObject().put("type","end").put("code",code).put("workerAlive",load.isAlive())
                    .put("samples",samples).put("menuCycles",actions).put("switches",switches).put("maxUiMs",maxUiMs));}catch(Exception ignored){}evidence.close();}
            Bundle result=new Bundle();result.putString("stream",code==-1?"Resource contention test finished; inspect sample and device logs for playback failures":"FAIL "+failure);finish(code,result);
        }
    }

    /** Exactly one normal-priority thread; bounded live storage, no unbounded allocations. */
    private static final class Load extends Thread {
        volatile int duty;
        volatile boolean running=true;
        volatile long cpuMs,passes,checksum;
        volatile int arrayBytes;
        volatile Throwable failure;
        Load(){super("ntv-resource-stress");setDaemon(true);setPriority(Thread.NORM_PRIORITY);}
        void stopLoad(){running=false;duty=0;interrupt();}
        @Override public void run(){
            try{
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_DEFAULT);
                int count=(int)Math.max(128*1024,Math.min(1024*1024,Runtime.getRuntime().maxMemory()/64));
                int[] data=new int[count];arrayBytes=count*4;byte[][] churn=new byte[8][];int slot=0;
                long hardStop=SystemClock.elapsedRealtime()+900000,startCpu=Debug.threadCpuTimeNanos();int seed=0x734aac9;
                while(running&&SystemClock.elapsedRealtime()<hardStop){
                    long slice=SystemClock.elapsedRealtime();int target=duty;
                    if(target>0){
                        long busyUntil=slice+target;
                        do {
                            for(int i=0;i<data.length;i++){seed^=seed<<13;seed^=seed>>>17;seed^=seed<<5;data[i]=data[i]*1664525+seed;}
                            checksum=data[(int)(passes%data.length)];passes++;
                        }while(running&&SystemClock.elapsedRealtime()<busyUntil);
                        churn[slot++&7]=new byte[64*1024];
                    }
                    cpuMs=(Debug.threadCpuTimeNanos()-startCpu)/1000000;
                    long rest=100-(SystemClock.elapsedRealtime()-slice);if(rest>0)try{Thread.sleep(rest);}catch(InterruptedException ignored){}
                }
            }catch(Throwable error){failure=error;}
        }
    }
}
