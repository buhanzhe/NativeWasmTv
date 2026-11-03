"""Exercise the production Bing toggle and cache policy without Android dependencies."""
from pathlib import Path
import os, subprocess, tempfile
root=Path(__file__).resolve().parents[1]
source=(root/'app/src/main/java/xiao/bu/tv/PersonalBackground.java').read_text(encoding='utf-8')
def method(marker):
 start=source.index(marker);end=source.index('{',start)+1;depth=1
 while depth:
  depth+=(source[end]=='{')-(source[end]=='}');end+=1
 return source[start:end]
java='''import java.util.*;
public class BingToggleCheck {
 boolean bingAutoUpdate=true;String bingChoice="",mode="bing";int generation,refreshes;Prefs prefs=new Prefs();
 static class Prefs {Map<String,Boolean> values=new HashMap<>();Prefs edit(){return this;}Prefs putBoolean(String k,boolean v){values.put(k,v);return this;}void apply(){}}
 void refresh(boolean cachedStartup){generation++;refreshes++;}
 void selectBing(String id){bingChoice=id;bingAutoUpdate=id.isEmpty();mode="bing";}
 METHODS
 static void check(boolean ok,String name){if(!ok)throw new AssertionError(name);}
 public static void main(String[] args)throws Exception{
  check(!needsBingRefresh(true,false,"yesterday","today"),"Disabled daily updates retain stale cached wallpaper");
  check(!needsBingRefresh(true,true,"today","today"),"Reuse today's wallpaper");
  check(needsBingRefresh(true,true,"yesterday","today"),"Daily refresh resumes when enabled");
  check(needsBingRefresh(false,false,"yesterday","today"),"A missing image can be fetched once");
  BingToggleCheck b=new BingToggleCheck();b.setBingAutoUpdate(false);
  check(!b.bingAutoUpdate&&!b.prefs.values.get("background_bing_auto_update"),"Persist disabled toggle");
  check(b.generation==1&&b.refreshes==1&&b.mode.equals("bing"),"Invalidate old fetch without disabling the splash");
  b.bingChoice="picked";b.setBingAutoUpdate(true);check(b.bingAutoUpdate&&b.bingChoice.isEmpty(),"Enabling resumes the daily source");
  System.out.println("PASS Bing daily update toggle: persistence, cached-image retention, retry and refresh policy");
 }
}'''.replace('METHODS',method('    synchronized void setBingAutoUpdate(')+'\n'+method('    static boolean needsBingRefresh('))
assert '.put("bingAutoUpdate",bingAutoUpdate&&bingChoice.isEmpty())' in source
assert '&&bingChoice.isEmpty()&&bingAutoUpdate)refresh(false)' in source
assert 'if(request.has("bingAutoUpdate"))personalBackground.setBingAutoUpdate' in (root/'app/src/main/java/xiao/bu/tv/MainActivity.java').read_text(encoding='utf-8')
jdk=Path(os.environ.get('JAVA_HOME','C:/Program Files/Android/Android Studio/jbr'))/'bin'
with tempfile.TemporaryDirectory() as folder:
 p=Path(folder)/'BingToggleCheck.java';p.write_text(java,encoding='utf-8')
 subprocess.run([str(jdk/'javac.exe'),'-encoding','UTF-8',str(p)],check=True)
 subprocess.run([str(jdk/'java.exe'),'-cp',folder,'BingToggleCheck'],check=True)
