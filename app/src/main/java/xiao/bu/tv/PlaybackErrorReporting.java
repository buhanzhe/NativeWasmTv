package xiao.bu.tv;

import android.os.Process;
import android.os.SystemClock;
import xiao.bu.tv.AppLog;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** One error-only log reader per activity lifetime, idle-blocking rather than polling logcat. */
final class PlaybackErrorReporting {
    private static final String TAG="PlayerErrorReporting";
    private static final ThreadPoolExecutor worker=new ThreadPoolExecutor(1,1,30,TimeUnit.SECONDS,
            new ArrayBlockingQueue<Runnable>(4),task->{Thread thread=new Thread(task,"player-error-upload");
                thread.setPriority(Thread.MIN_PRIORITY);thread.setDaemon(true);return thread;},new ThreadPoolExecutor.AbortPolicy());
    static {worker.allowCoreThreadTimeOut(true);}
    private static final PlayerErrorReports reports=new PlayerErrorReports(SystemClock::elapsedRealtime,
            task->worker.execute(()->{try{Thread.sleep(150);}catch(InterruptedException ignored){Thread.currentThread().interrupt();}task.run();}),
            CrashReporting::postPlayerError);
    private static Watcher watcher;
    private PlaybackErrorReporting(){}

    static synchronized void begin(int request,String backend,String channel,String source,String decode,int line){
        if(!CrashReporting.enabled())return;
        PlayerErrorReports.Scene scene=reports.begin(request,backend,channel,source,decode,line);
        if(watcher==null){watcher=new Watcher();watcher.start();}
        // Ordered with native logs by logd; old queued lines keep their old scene.
        AppLog.i(PlayerErrorReports.MARKER_TAG,watcher.token+":"+scene.id);
    }
    static void error(int request,String component,String kind,String message){
        if(CrashReporting.enabled())reports.error(request,component,kind,message,null);
    }
    static void caught(int request,String component,String operation,Throwable error){
        if(CrashReporting.enabled())reports.error(request,component,operation,
                error==null?operation:error.getClass().getName()+": "+error.getMessage(),error);
    }
    static void releaseException(String component,Throwable error){
        if(CrashReporting.enabled())reports.unscopedException(component,"ReleaseException",error);
    }
    static synchronized void close(){
        reports.end();Watcher previous=watcher;watcher=null;if(previous!=null)previous.close();
    }
    private static final class Watcher extends Thread {
        final String token=Long.toHexString(System.nanoTime());
        volatile boolean closed;volatile java.lang.Process process;
        Watcher(){super("player-error-log");setDaemon(true);setPriority(Thread.MIN_PRIORITY);}
        void close(){closed=true;java.lang.Process p=process;if(p!=null)releaseInput(p);}
        private void releaseInput(java.lang.Process p){try{p.getOutputStream().close();}catch(java.io.IOException ignored){}}
        private void finish(java.lang.Process p){
            releaseInput(p);long deadline=SystemClock.elapsedRealtime()+1000;
            while(SystemClock.elapsedRealtime()<deadline)try{p.exitValue();return;}
                catch(IllegalThreadStateException alive){try{Thread.sleep(10);}catch(InterruptedException ignored){break;}}
            p.destroy();
        }
        @Override public void run(){
            PlayerErrorReports.Tail tail=reports.new Tail(Process.myPid(),token);
            try{
                // -T/--pid are unavailable on Android 4.x. Always filter PID in Java;
                // the marker discards old buffered logs from before this watcher.
                // The shell blocks on our stdin pipe. Parent death closes that
                // pipe; its EXIT trap reaps logcat even after force-stop/update.
                // All shell text is constant, without any channel/user input.
                String command="/system/bin/logcat -v threadtime IJKMEDIA:E MediaCodec:E ACodec:E"
                        +" MediaPlayer:E NuPlayer:E AudioTrack:E MainActivity:W "+PlayerErrorReports.MARKER_TAG+":I '*:S' & "
                        +"log_pid=$!; trap 'kill \"$log_pid\" 2>/dev/null; wait \"$log_pid\" 2>/dev/null' EXIT; read done";
                java.lang.Process p=new ProcessBuilder("/system/bin/sh","-c",command)
                        .redirectErrorStream(true).start();
                process=p;if(closed)return;
                try(BufferedReader input=new BufferedReader(new InputStreamReader(p.getInputStream(),"UTF-8"))){
                    String line;while(!closed&&(line=input.readLine())!=null)tail.line(line);
                }
            }catch(Exception unavailable){if(!closed)AppLog.w(TAG,"Native error logs unavailable; callback reporting remains enabled");}
            finally{java.lang.Process p=process;if(p!=null)finish(p);process=null;}
        }
    }
}
