"""Actual SDK boundary with a deferred, disabled or throwing Bugly implementation."""
from pathlib import Path
import os,subprocess,tempfile
root=Path(__file__).resolve().parents[1]
files={
'android/content/Context.java':'package android.content;public class Context{public Context getApplicationContext(){return this;}}',
'android/os/Build.java':'package android.os;public class Build{public static class VERSION{public static int SDK_INT=14;}}',
'android/os/SystemClock.java':'package android.os;public class SystemClock{public static long now;public static long elapsedRealtime(){return now;}}',
'android/util/Log.java':'package android.util;public class Log{public static int i(String t,String m){return 0;}public static int w(String t,String m){return 0;}public static int w(String t,String m,Throwable e){return 0;}}',
'xiao/bu/tv/BuildConfig.java':'package xiao.bu.tv;public class BuildConfig{public static String VERSION_NAME="1.8.1",FLAVOR="arm32",BUILD_TYPE="release";public static boolean DEBUG=false;}',
'com/tencent/bugly/CrashModule.java':'package com.tencent.bugly;public class CrashModule{public static volatile boolean ready;private static final CrashModule value=new CrashModule();public static CrashModule getInstance(){return value;}public boolean hasInitialized(){return ready;}}',
'com/tencent/bugly/crashreport/BuglyLog.java':'package com.tencent.bugly.crashreport;public class BuglyLog{public static String last;public static int cache;public static void setCache(int n){cache=n;}public static void e(String tag,String message){last=message;}}',
'com/tencent/bugly/crashreport/CrashReport.java':'''package com.tencent.bugly.crashreport;
import android.content.Context;import java.util.*;
public class CrashReport{
 public static int starts;public static volatile boolean fail;public static final List<Throwable> errors=Collections.synchronizedList(new ArrayList<>());
 public static void postCatchedException(Throwable error,Thread origin){if(fail)throw new IllegalStateException();if(!com.tencent.bugly.CrashModule.ready)throw new AssertionError("SDK used before ready");errors.add(error);}
 public static void setCollectPrivacyInfo(Context c,boolean b){}public static void putUserData(Context c,String k,String v){}
 public static void initCrashReport(Context c,String id,boolean debug,UserStrategy s){starts++;}public static void setIsDevelopmentDevice(Context c,boolean b){}
 public static class UserStrategy{
  public UserStrategy(Context c){}public void setAppVersion(String s){}public void setAppChannel(String s){}public void setEnableNativeCrashMonitor(boolean b){}public void setEnableANRCrashMonitor(boolean b){}public void setEnableUserInfo(boolean b){}public void setBuglyLogUpload(boolean b){if(!b)throw new AssertionError("missing error log attachment");}public void setEnableCatchAnrTrace(boolean b){}public void setEnableRecordAnrMainStack(boolean b){}public void setAppReportDelay(long t){}
 }
}''',
'xiao/bu/tv/CrashReporting.java':(root/'app/src/main/java/xiao/bu/tv/CrashReporting.java').read_text(encoding='utf8'),
'xiao/bu/tv/PlayerErrorReports.java':(root/'app/src/main/java/xiao/bu/tv/PlayerErrorReports.java').read_text(encoding='utf8'),
'xiao/bu/tv/Check.java':'''package xiao.bu.tv;
import android.content.Context;import android.os.Build;import android.os.SystemClock;import com.tencent.bugly.*;import com.tencent.bugly.crashreport.*;
public class Check{
 static void check(boolean b,String m){if(!b)throw new AssertionError(m);}
 static void waitFor(int n)throws Exception{long end=System.currentTimeMillis()+3500;while(CrashReport.errors.size()<n&&System.currentTimeMillis()<end)Thread.sleep(20);check(CrashReport.errors.size()==n,"expected SDK deliveries "+n);}
 public static void main(String[]args)throws Exception{
  Context c=new Context();CrashReporting.startIfAllowed(c);check(CrashReport.starts==0&&!CrashReporting.enabled(),"API14 does not initialize SDK");
  Build.VERSION.SDK_INT=19;CrashReporting.startIfAllowed(c);check(CrashReport.starts==1&&BuglyLog.cache==16384,"SDK configured once");CrashReporting.startIfAllowed(c);check(CrashReport.starts==1,"duplicate init avoided");
  PlayerErrorReports.PlaybackFailure error=new PlayerErrorReports.PlaybackFailure(1,"summary","native details");
  CrashReporting.postPlayerError(error,Thread.currentThread());check(CrashReport.errors.isEmpty(),"early error buffered");
  CrashModule.ready=true;waitFor(1);check(BuglyLog.last.contains("native details")&&BuglyLog.last.contains("[nTvPlayer:1]"),"correlated official SDK log attachment");
  CrashModule.ready=false;for(int i=0;i<12;i++)CrashReporting.postPlayerError(new RuntimeException("queued"+i),Thread.currentThread());CrashModule.ready=true;waitFor(9);
  check(CrashReport.errors.get(1).getMessage().equals("queued4"),"queue bounded to newest eight");
  CrashModule.ready=false;CrashReporting.postPlayerError(new RuntimeException("expired"),Thread.currentThread());SystemClock.now=60001;Thread.sleep(1200);CrashModule.ready=true;Thread.sleep(1100);check(CrashReport.errors.size()==9,"unavailable SDK timeout bounded");
  CrashReport.fail=true;CrashReporting.postPlayerError(error,Thread.currentThread());check(CrashReport.errors.size()==9,"SDK failure never escapes");
  System.out.println("PASS API14 gate, one init, delayed SDK readiness, correlated log attachment, eight-report queue, expiration and SDK exceptions");
 }
}'''
}
with tempfile.TemporaryDirectory() as folder:
 out=Path(folder)
 for name,body in files.items():p=out/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(body,encoding='utf8')
 jdk=Path(os.environ.get('JAVA_HOME','C:/Users/Administrator/.jdks/jbr-17.0.14'))/'bin'
 subprocess.run([str(jdk/'javac.exe'),'-encoding','UTF-8','-d',folder,*[str(out/name) for name in files]],check=True)
 subprocess.run([str(jdk/'java.exe'),'-cp',folder,'xiao.bu.tv.Check'],check=True)
