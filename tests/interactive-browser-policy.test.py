"""Interactive browsing must disable TV autoplay/fullscreen work and restore it for TV channels."""
from pathlib import Path
import os,subprocess,tempfile
root=Path(__file__).resolve().parents[1]
s=(root/'app/src/main/java/xiao/bu/tv/WebPageScriptManager.java').read_text(encoding='utf8')
main=(root/'app/src/main/java/xiao/bu/tv/MainActivity.java').read_text(encoding='utf8')
assert 'setMovementOnly(!isQuickWebInteractive() && webViewMinimalMode' in main, 'Interactive websites must retain fly-mouse clicks even when TV minimal mode is enabled'
def method(signature):
 a=s.index(signature);b=s.index('{',a)+1;depth=1
 while depth:depth+=(s[b]=='{')-(s[b]=='}');b+=1
 return s[a:b]
java='''import java.util.*;
class InteractivePolicyCheck {
 boolean tvPlaybackEnabled=true,configured,lastAdBlock,lastWebRtc,lastEnabled,lastAutoPlay;
 String lastSources="",script="";List<String> userScripts=Collections.emptyList();int installed;
 static class View {Object getContext(){return null;}}View view=new View();
 static class WebMediaSession {static String script(Object c){return "MEDIA_SESSION";}}
 static class WebTvPlaybackPolicy {static String script(Object c,boolean a){return "TV_HELPER";}}
 static class WebAdBlocker {static String cosmeticScript(){return "";}}
 String systemMouseSelectionScript(){return "";}String webRtcBlockScript(){return "";}
 List<String> compiledUserScripts(String s){return Collections.emptyList();}void installHandlers(){installed++;}
 SETTER
 UPDATE
 static void ok(boolean b,String why){if(!b)throw new AssertionError(why);}
 public static void main(String[] args){InteractivePolicyCheck p=new InteractivePolicyCheck();
 p.update(false,true,false,"[]",true);ok(p.script.contains("TV_HELPER"),"TV defaults");
 p.setTvPlaybackEnabled(false);p.update(false,true,false,"[]",true);ok(!p.script.contains("TV_HELPER")&&p.script.contains(".stop()"),"Browsing disables TV helper");
 int n=p.installed;p.update(false,true,false,"[]",true);ok(n==p.installed,"Unchanged browsing state is cheap");
 p.setTvPlaybackEnabled(true);p.update(false,true,false,"[]",true);ok(p.script.contains("TV_HELPER")&&p.installed==n+1,"TV helper restored despite identical settings");
 System.out.println("PASS browser helper disabled, no redundant reinstall, TV helper restored on mode change");}
}'''.replace('SETTER',method('void setTvPlaybackEnabled(')).replace('UPDATE',method('void update(boolean adBlock, boolean webRtc, boolean userScriptEnabled, String sourcesJson, boolean autoPlay)'))
with tempfile.TemporaryDirectory() as tmp:
 p=Path(tmp)/'InteractivePolicyCheck.java';p.write_text(java,encoding='utf8');jdk=Path(os.environ.get('JAVA_HOME','C:/Users/Administrator/.jdks/jbr-17.0.14'))/'bin'
 subprocess.run([str(jdk/'javac.exe'),'-encoding','UTF-8',str(p)],check=True);subprocess.run([str(jdk/'java.exe'),'-cp',tmp,'InteractivePolicyCheck'],check=True)
