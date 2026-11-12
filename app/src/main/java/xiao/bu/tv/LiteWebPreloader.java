package xiao.bu.tv;

import android.content.Context;
import android.os.*;

/** One prepared page/transport lease. Main-thread ownership only. */
final class LiteWebPreloader implements java.io.Closeable {
    static final long MAX_AGE_MS=5*60*1000L;
    static final class Request {
        final String page,script,userAgent,signature;final int threads;final boolean images;
        Request(String page,String script,String userAgent,int threads,String settings){
            this(page,script,userAgent,threads,settings,true);
        }
        Request(String page,String script,String userAgent,int threads,String settings,boolean images){
            this.images=images;this.page=page;this.script=script;this.userAgent=userAgent;this.threads=threads;
            signature=page+'\n'+script+'\n'+userAgent+'\n'+threads+'\n'+images+'\n'+settings;
        }
    }
    private final Context context;private final Handler main=new Handler(Looper.getMainLooper());
    private Request request;private LiteWebPlayback prepared;private long readyAt=-1;private String failure="";private int generation;
    LiteWebPreloader(Context c){context=c;}
    static boolean eligible(int sdk,int cores,long available,boolean lowMemory){return sdk>=19&&cores>1&&!lowMemory&&available>=160L*1024*1024;}
    boolean occupied(){return prepared!=null&&!prepared.closed();}
    boolean ready(){return occupied()&&prepared.prepared()&&readyAt>=0&&SystemClock.elapsedRealtime()-readyAt<MAX_AGE_MS;}
    void prepare(Request value){
        if(ready()&&request.signature.equals(value.signature))return;
        close();request=value;failure="";
        final int epoch=generation;
        prepared=new LiteWebPlayback(context,new LiteWebPlayback.Host(){
            public void stream(String url,boolean temporary){}
            public void prepared(){if(epoch!=generation)return;readyAt=SystemClock.elapsedRealtime();main.removeCallbacksAndMessages(null);main.postDelayed(LiteWebPreloader.this::close,MAX_AGE_MS);}
            public void status(String text){}
            public void failure(String text){if(epoch!=generation)return;failure=text;AppLog.d("LitePreparse","Candidate not retained: "+text);LiteWebPreloader.this.close();}
            public String userAgent(String page,String fallback){return value.userAgent;}
            public boolean loadImages(){return value.images;}
            public int networkThreadMultiplier(){return value.threads;}
            public boolean playing(){return false;}public long position(){return 0;}public long duration(){return 0;}
            public int width(){return 0;}public int height(){return 0;}public void setPaused(boolean paused){}
        });
        prepared.prepare(value.page,value.script);
        if(occupied()&&readyAt<0)main.postDelayed(this::close,15000L);
    }
    void cancelPending(){if(!ready())close();}
    void retain(Request value){if(value==null||request==null||!value.signature.equals(request.signature)||!ready())close();}
    LiteWebPlayback take(Request value){
        retain(value);if(!ready())return null;
        LiteWebPlayback result=prepared;++generation;prepared=null;request=null;main.removeCallbacksAndMessages(null);
        AppLog.i("LitePreparse","Adopting prepared page ageMs="+(SystemClock.elapsedRealtime()-readyAt));readyAt=-1;return result;
    }
    public void close(){++generation;main.removeCallbacksAndMessages(null);if(prepared!=null)prepared.close();prepared=null;request=null;readyAt=-1;}
}
