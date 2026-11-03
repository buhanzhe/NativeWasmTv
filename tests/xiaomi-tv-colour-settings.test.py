"""Check real Xiaomi TV entry discovery and HDR menu callbacks with Android stubs."""
from pathlib import Path
import os, subprocess, tempfile
root=Path(__file__).resolve().parents[1]
source=root/'app/src/main/java/xiao/bu/tv'
main=(source/'MainActivity.java').read_text(encoding='utf8')
start=main.index('    private void showTvUiHdrModes(')
method=main[start:main.index('    private void showTvUiExit(',start)]
files={
'android/content/Context.java':'''package android.content;import android.content.pm.*;public abstract class Context {public abstract PackageManager getPackageManager();public abstract int checkPermission(String p,int pid,int uid);}''',
'android/content/ComponentName.java':'''package android.content;public class ComponentName {private final String p,n;public ComponentName(String p,String n){this.p=p;this.n=n;}public String getPackageName(){return p;}public String getClassName(){return n;}}''',
'android/content/Intent.java':'''package android.content;public class Intent {public static final String ACTION_MAIN="main";ComponentName c;public Intent(String action){}public Intent setComponent(ComponentName c){this.c=c;return this;}public ComponentName getComponent(){return c;}}''',
'android/content/ActivityNotFoundException.java':'package android.content;public class ActivityNotFoundException extends RuntimeException {}',
'android/content/pm/ApplicationInfo.java':'package android.content.pm;public class ApplicationInfo {public static final int FLAG_SYSTEM=1,FLAG_UPDATED_SYSTEM_APP=128;public boolean enabled=true;public int flags=1;}',
'android/content/pm/ActivityInfo.java':'package android.content.pm;public class ActivityInfo {public String name,packageName,permission,label;public boolean enabled=true,exported=true;public ApplicationInfo applicationInfo=new ApplicationInfo();public CharSequence loadLabel(PackageManager pm){return label;}}',
'android/content/pm/PackageInfo.java':'package android.content.pm;public class PackageInfo {public ActivityInfo[] activities;}',
'android/content/pm/ResolveInfo.java':'package android.content.pm;public class ResolveInfo {public ActivityInfo activityInfo;}',
'android/content/pm/PackageManager.java':'''package android.content.pm;import android.content.Intent;public abstract class PackageManager {public static final int GET_ACTIVITIES=1,PERMISSION_GRANTED=0;public static class NameNotFoundException extends Exception {}public abstract PackageInfo getPackageInfo(String p,int flags)throws NameNotFoundException;public abstract ResolveInfo resolveActivity(Intent i,int flags);}''',
'android/os/Build.java':'package android.os;public class Build {public static String MANUFACTURER="xiaomi",BRAND="Xiaomi";}',
'android/os/Process.java':'package android.os;public class Process {public static int myPid(){return 1;}public static int myUid(){return 1;}}',
'android/os/Bundle.java':'''package android.os;import java.util.*;public class Bundle {final Map<String,Object> m=new HashMap<>();public void putStringArray(String k,String[] v){m.put(k,v);}public String[] getStringArray(String k){return (String[])m.get(k);}public void putBoolean(String k,boolean v){m.put(k,v);}public void putInt(String k,int v){m.put(k,v);}public int getInt(String k){return (Integer)m.get(k);}}''',
'android/widget/Toast.java':'''package android.widget;import android.content.Context;public class Toast {public static final int LENGTH_SHORT=0;public static int shown;public static Toast makeText(Context c,String t,int d){return new Toast();}public void show(){shown++;}}''',
'xiao/bu/tv/skin/TvUi.java':'package xiao.bu.tv.skin;import android.os.Bundle;public class TvUi {public interface Events {Bundle options(int category);void action(int category,int option);void dismiss();}}',
}
check=r'''package xiao.bu.tv;
import java.util.*;import android.content.*;import android.content.pm.*;import android.os.*;import android.widget.Toast;
class TestPm extends PackageManager {
 final Map<String,ActivityInfo> activities=new LinkedHashMap<>();boolean failQuery,failResolve;
 void add(ActivityInfo a){activities.put(a.packageName+"/"+a.name,a);}
 public PackageInfo getPackageInfo(String p,int f)throws NameNotFoundException {
  if(failQuery)throw new SecurityException();PackageInfo i=new PackageInfo();
  i.activities=activities.values().stream().filter(a->p.equals(a.packageName)).toArray(ActivityInfo[]::new);return i;
 }
 public ResolveInfo resolveActivity(Intent i,int flags){
  if(failResolve)throw new SecurityException();ComponentName c=i.getComponent();ActivityInfo a=activities.get(c.getPackageName()+"/"+c.getClassName());
  if(a==null)return null;ResolveInfo r=new ResolveInfo();r.activityInfo=a;return r;
 }
}
class TestContext extends Context {
 final TestPm pm=new TestPm();boolean grant;
 public PackageManager getPackageManager(){return pm;}public int checkPermission(String p,int pid,int uid){return grant?0:-1;}
}
class TestMenu {xiao.bu.tv.skin.TvUi.Events events;int dismissed;void showSubmenu(Object root,Bundle b,xiao.bu.tv.skin.TvUi.Events e){events=e;}void dismiss(){dismissed++;}}
class MainActivity extends TestContext {
 String hdrMode=HdrMode.HARDWARE;boolean remoteInputMode,television=true;Object root=new Object();TestMenu tvUiMenu=new TestMenu();
 int started,changed,launchFailure;String changedMode;boolean hasTelevisionUi(){return television;}
 void applyTvUiSetting(String key,Object value){changed++;changedMode=(String)value;}
 public void startActivity(Intent i){if(launchFailure==1)throw new SecurityException();if(launchFailure==2)throw new ActivityNotFoundException();started++;}
 METHOD
 void openTvUiMenu(int category){}
 void open(){showTvUiHdrModes();}
}
public class ColourSettingsCheck {
 static final String PKG="com.xiaomi.mitv.settings",PICTURE="com.xiaomi.mitv.settings.picture.PictureActivity";
 static void ok(boolean v,String message){if(!v)throw new AssertionError(message);}
 static ActivityInfo activity(String p,String n,String label){ActivityInfo a=new ActivityInfo();a.packageName=p;a.name=n;a.label=label;return a;}
 static void hidden(TestContext c,String why){ok(XiaomiTvColorSettings.find(c,true)==null,why);}
 public static void main(String[] args){
  TestContext c=new TestContext();hidden(c,"Missing settings activity must be hidden");
  c.pm.add(activity(PKG,"MainActivity","设置"));hidden(c,"Generic settings must not impersonate colour settings");
  ActivityInfo picture=activity("com.android.tv.settings",PICTURE,"Picture");c.pm.add(picture);
  ok(XiaomiTvColorSettings.find(c,true)!=null,"Known Xiaomi picture component in TV settings not found");
  ok(XiaomiTvColorSettings.find(c,false)==null,"Xiaomi phone must be excluded");
  Build.MANUFACTURER="Sony";Build.BRAND="Sony";hidden(c,"Other brands must be excluded even with matching package");
  Build.MANUFACTURER="Xiaomi";Build.BRAND="Redmi";
  picture.exported=false;hidden(c,"Private activity must be excluded");picture.exported=true;
  picture.enabled=false;hidden(c,"Disabled component must be excluded");picture.enabled=true;
  picture.applicationInfo.enabled=false;hidden(c,"Disabled app must be excluded");picture.applicationInfo.enabled=true;
  picture.applicationInfo.flags=0;hidden(c,"User-installed imitation settings must be excluded");picture.applicationInfo.flags=128;
  picture.permission="signature.only";hidden(c,"Unheld activity permission must be excluded");c.grant=true;
  ok(XiaomiTvColorSettings.find(c,true)!=null,"Granted permission was incorrectly rejected");
  c.pm.failResolve=true;hidden(c,"Resolver failure must hide the shortcut");c.pm.failResolve=false;
  ActivityInfo colour=activity(PKG,"vendor.ColourPanel","色彩");c.pm.add(colour);
  ok(XiaomiTvColorSettings.find(c,true).getComponent().getClassName().equals(colour.name),"Dedicated colour page must precede picture fallback");
  MainActivity m=new MainActivity();m.open();ok(m.tvUiMenu.events.options(0).getStringArray("items").length==3,"Unsupported device gained an entry");
  m.pm.add(colour);m.hdrMode=HdrMode.BT709;m.open();Bundle choices=m.tvUiMenu.events.options(0);
  ok(choices.getStringArray("items").length==4&&choices.getInt("selected")==2,"System shortcut changed HDR selection");
  m.tvUiMenu.events.action(0,3);ok(m.started==1&&m.changed==0&&m.tvUiMenu.dismissed==1,"Shortcut must launch without changing HDR mode");
  m.tvUiMenu.events.action(0,1);ok(m.changed==1&&m.changedMode.equals(HdrMode.MAPPING),"HDR choices no longer work");
  int notifications=Toast.shown;m.pm.activities.clear();m.tvUiMenu.events.action(0,3);
  ok(m.started==1&&m.changed==1&&Toast.shown==notifications+1,"Removed activity must be rechecked safely at click time");
  m.pm.add(colour);for(int failure:new int[]{1,2}){m.launchFailure=failure;m.open();m.tvUiMenu.events.action(0,3);}
  ok(m.started==1&&m.changed==1&&Toast.shown==notifications+3,"Launch restrictions must not crash or alter HDR mode");
  m.television=false;m.open();ok(m.tvUiMenu.events.options(0).getStringArray("items").length==3,"Phone menu exposed TV shortcut");
  System.out.println("PASS Xiaomi TV/system activity gating, dedicated colour priority, HDR menu selection and removed/blocked activity handling");
 }
}
'''.replace('METHOD',method)
with tempfile.TemporaryDirectory() as directory:
 folder=Path(directory)
 for name,text in files.items():
  file=folder/name;file.parent.mkdir(parents=True,exist_ok=True);file.write_text(text,encoding='utf8')
 for name in ['XiaomiTvColorSettings.java','HdrMode.java']:
  (folder/'xiao/bu/tv'/name).write_text((source/name).read_text(encoding='utf8'),encoding='utf8')
 (folder/'xiao/bu/tv/ColourSettingsCheck.java').write_text(check,encoding='utf8')
 jdk=Path(os.environ['JAVA_HOME'])/'bin'
 subprocess.run([str(jdk/'javac.exe'),'-encoding','UTF-8','-d',directory]+[str(p) for p in folder.rglob('*.java')],check=True)
 subprocess.run([str(jdk/'java.exe'),'-cp',directory,'xiao.bu.tv.ColourSettingsCheck'],check=True)
