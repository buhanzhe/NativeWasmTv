"""Exercise the actual app sink and filters without logcat or Android permissions."""
from pathlib import Path
import subprocess, tempfile
root = Path(__file__).resolve().parents[1]
with tempfile.TemporaryDirectory() as tmp:
 d = Path(tmp)
 files = {
  'tv/danmaku/ijk/media/player/IjkMediaPlayer.java': 'package tv.danmaku.ijk.media.player;public class IjkMediaPlayer {public static void loadLibrariesOnce(Object o){}}',
  'android/content/Context.java': 'package android.content; public class Context { public java.io.File getFilesDir(){return new java.io.File(System.getProperty("log.dir"));}}',
  'android/os/Process.java': 'package android.os; public class Process {public static int myPid(){return 123;} public static int myTid(){return 124;}}',
  'android/util/Log.java': '''package android.util; public class Log {
   public static int println(int l,String t,String m){return 0;}
   public static String getStackTraceString(Throwable t){java.io.StringWriter s=new java.io.StringWriter();t.printStackTrace(new java.io.PrintWriter(s));return s.toString();}}''',
  'org/json/JSONException.java': 'package org.json; public class JSONException extends Exception {}',
  'org/json/JSONObject.java': '''package org.json; public class JSONObject {
   java.util.Map<String,Object> m=new java.util.LinkedHashMap<>();
   public JSONObject put(String k,Object v)throws JSONException{m.put(k,v);return this;}
   public String toString(){return m.toString();}}''',
  'xiao/bu/tv/AppLog.java': (root/'app/src/main/java/xiao/bu/tv/AppLog.java').read_text('utf-8'),
  'xiao/bu/tv/AppLogReader.java': (root/'app/src/main/java/xiao/bu/tv/AppLogReader.java').read_text('utf-8'),
  'xiao/bu/tv/LogCheck.java': r'''package xiao.bu.tv;
import java.io.*;
public class LogCheck {
 static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);}
 public static void main(String[] args)throws Exception {
  AppLog.initialize(new android.content.Context());
  Thread.sleep(100);
  if(args.length>0){check(AppLog.snapshot().contains("retained-across-restart"),"lost restart logs");return;}
  AppLog.d("TV","debug-not-persisted");
  AppLog.i("TV","normal-only");
  AppLog.e("TV","error-first\nerror-continuation 中文");
  AppLog.appendNative(6,"native-error 中文".getBytes("UTF-8"));
  AppLog.e("TV","FATAL EXCEPTION: sample\ncrash stack");
  String all=AppLogReader.read("all"), error=AppLogReader.read("error"), crash=AppLogReader.read("crash");
  check(!all.contains("debug-not-persisted"),"debug traces persisted");
  check(all.contains("normal-only")&&all.contains("IJKMEDIA: native-error 中文"),all);
  check(error.contains("error-continuation 中文")&&!error.contains("normal-only"),error);
  check(crash.contains("crash stack")&&!crash.contains("error-first"),crash);
  StringBuilder large=new StringBuilder();for(int i=0;i<12000;i++)large.append('测');
  for(int i=0;i<110;i++){AppLog.i("TV",large.toString());AppLog.snapshot();}
  AppLog.i("TV","retained-across-restart");String rotated=AppLog.snapshot();
  File folder=new File(System.getProperty("log.dir"),"logs");
  File[] files=folder.listFiles();check(files!=null&&files.length==2,"rotation missing");
  for(File f:files)check(f.length()<1100000,"unbounded log file "+f.length());
  check(!rotated.contains("normal-only")&&rotated.contains("retained-across-restart"),"rotation order");
  check(!AppLog.note().contains("写入失败"),AppLog.note());
  java.lang.reflect.Field disk=AppLog.class.getDeclaredField("fileLock");disk.setAccessible(true);
  synchronized(disk.get(null)) {
   Thread producer=new Thread(()->{for(int i=0;i<2000;i++)AppLog.i("TV","slow-disk-producer-"+i);});
   producer.start();producer.join(1000);
   check(!producer.isAlive(),"disk snapshot/writer lock blocked log producer");
   java.lang.reflect.Field queue=AppLog.class.getDeclaredField("pending");queue.setAccessible(true);
   check(((java.util.ArrayDeque<?>)queue.get(null)).size()<=256,"unbounded queue");
  }
  check(AppLog.snapshot().contains("slow-disk-producer-1999"),"lost newest queued entry");
  check(AppLog.note().contains("高负载时丢弃"),"missing dropped-log notice");
  AppLog.i("TV","retained-across-restart");AppLog.snapshot();
 }
}'''
 }
 for name, body in files.items():
  p=d/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(body,encoding='utf-8')
 subprocess.run(['javac','-encoding','UTF-8','-d',tmp,*[str(d/name) for name in files]],check=True)
 command=['java','-Dlog.dir='+str(d/'private'),'-cp',tmp,'xiao.bu.tv.LogCheck']
 subprocess.run(command,check=True,timeout=20)
 subprocess.run(command+['restart'],check=True,timeout=10)
print('PASS app/native UTF-8 logs, multiline error/crash filters, bounded rotation, restart persistence, debug filtering and nonblocking bounded queue without logcat')
