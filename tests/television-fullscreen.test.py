"""Run the real fullscreen policy with old TV, generic box and touch-device configurations."""
from pathlib import Path
import os, subprocess, tempfile

root = Path(__file__).resolve().parents[1]
files = {
 'android/content/Context.java': '''package android.content;public class Context {
 public static final int MODE_PRIVATE=0;public SharedPreferences preferences=new SharedPreferences();
 public SharedPreferences getSharedPreferences(String name,int mode){return preferences;}
 public android.content.res.Resources resources=new android.content.res.Resources();
 public android.content.pm.PackageManager pm=new android.content.pm.PackageManager();
 public android.content.res.Resources getResources(){return resources;}
 public android.content.pm.PackageManager getPackageManager(){return pm;}}''',
 'android/content/SharedPreferences.java': '''package android.content;public class SharedPreferences {public Boolean value;public boolean getBoolean(String key,boolean fallback){return value==null?fallback:value;}}''',
 'android/content/res/Resources.java': '''package android.content.res;public class Resources {
 public Configuration config=new Configuration();public Configuration getConfiguration(){return config;}}''',
 'android/content/res/Configuration.java': '''package android.content.res;public class Configuration {
 public static final int UI_MODE_TYPE_MASK=15,UI_MODE_TYPE_TELEVISION=4;public int uiMode=1;}''',
 'android/content/pm/PackageManager.java': '''package android.content.pm;public class PackageManager {
 public java.util.Set<String> features=new java.util.HashSet<>();public boolean hasSystemFeature(String v){return features.contains(v);}}''',
 'android/os/Build.java': '''package android.os;public class Build {
 public static String MODEL="Generic",DEVICE="device",PRODUCT="product";
 public static class VERSION {public static int SDK_INT;}}''',
 'android/view/View.java': '''package android.view;public class View {
 public static final int SYSTEM_UI_FLAG_FULLSCREEN=4,SYSTEM_UI_FLAG_HIDE_NAVIGATION=2,
 SYSTEM_UI_FLAG_LAYOUT_STABLE=256,SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN=1024,
 SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION=512,SYSTEM_UI_FLAG_IMMERSIVE_STICKY=4096;}''',
 'xiao/bu/tv/FullscreenPolicy.java': (root/'app/src/main/java/xiao/bu/tv/FullscreenPolicy.java').read_text(encoding='utf8'),
 'xiao/bu/tv/Check.java': '''package xiao.bu.tv;import android.content.*;import android.os.Build;
 class MainActivity {static final String PREFERENCES="tv_player";}
 public class Check {
 static void check(boolean value,String reason){if(!value)throw new AssertionError(reason);}
 public static void main(String[] args){
  for(int api:new int[]{14,16,19,25,34})for(boolean layout:new boolean[]{false,true}){
   Build.VERSION.SDK_INT=api;
   for(int kind=0;kind<6;kind++){
    Context c=new Context();Build.MODEL="Generic";
    if(kind!=0)c.pm.features.add("android.hardware.touchscreen");
    if(kind==1)c.resources.config.uiMode=4|32;
    if(kind==2)c.pm.features.add("android.software.leanback");
    if(kind==3)c.pm.features.add("android.hardware.type.television");
    if(kind==4)Build.MODEL="Vendor TV Box";
    boolean television=kind!=5;
    check(FullscreenPolicy.isTelevision(c)==television,"TV detection for config/features/model/old no-touch box");
    int flags=FullscreenPolicy.flags(c,layout);
    check((flags&4)!=0,"status bar hidden, playback viewport remains screen-sized");
    if(television)check((flags&(2|512|4096))==0,"TV must not enter immersive navigation or consume first remote key");
    else {check((flags&2)!=0,"phone/tablet navigation still hidden");
      check(((flags&4096)!=0)==(api>=19),"phone immersive only on supported Android");}
    check(((flags&1024)!=0)==(layout&&api>=16),"layout flags obey caller and legacy API");
    for(boolean enabled:new boolean[]{true,false}){
     c.preferences.value=enabled;
     int chosen=FullscreenPolicy.flags(c,layout);
     check((chosen&4)!=0,"disabled fullscreen still hides status bar");
     check(((chosen&2)!=0)==enabled,"explicit switch controls navigation hiding on TVs and phones");
     check(((chosen&4096)!=0)==(enabled&&api>=19),"explicit switch controls immersive mode with API guard");
     Context restarted=new Context();restarted.preferences=c.preferences;
     check(FullscreenPolicy.isEnabled(restarted)==enabled,"saved choice overrides device default after recreation");
    }
   }
  }
  System.out.println("PASS TV fullscreen: API14/16/19/25/34, TV/leanback/hardware/model/generic boxes, touch phones/tablets and layout modes");
 }}'''
}
with tempfile.TemporaryDirectory() as folder:
 out = Path(folder)
 for name, body in files.items():
  file=out/name;file.parent.mkdir(parents=True,exist_ok=True);file.write_text(body,encoding='utf8')
 jdk=Path(os.environ.get('JAVA_HOME','C:/Program Files/Android/Android Studio/jbr'))/'bin'
 subprocess.run([str(jdk/'javac.exe'),'-encoding','UTF-8','-d',str(out),*[str(out/n) for n in files]],check=True)
 subprocess.run([str(jdk/'java.exe'),'-cp',str(out),'xiao.bu.tv.Check'],check=True)
