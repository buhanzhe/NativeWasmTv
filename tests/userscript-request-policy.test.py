"""Execute request metadata scope/validation and the bundled room redirect."""
from pathlib import Path
import os, subprocess, tempfile
root=Path(__file__).resolve().parents[1]
jar=next(p for p in (Path.home()/'.gradle/caches/modules-2/files-2.1/org.json/json').rglob('*.jar') if not p.name.endswith(('-sources.jar','-javadoc.jar')))
java=r'''package xiao.bu.tv;
import org.json.*;import java.nio.file.*;
class Check {
 static void ok(boolean b){if(!b)throw new AssertionError();}
 static String scripts(String source,boolean enabled)throws Exception{return new JSONArray().put(new JSONObject().put("source",source).put("enabled",enabled)).toString();}
 static String get(String source,String page)throws Exception{return UserScriptRequestPolicy.userAgent(scripts(source,true),page);}
 public static void main(String[] args)throws Exception{
  String source=new String(Files.readAllBytes(Paths.get(args[0])),"UTF-8");
  String ua=get(source,"https://www.douyu.com/4837387");ok(ua.contains("iPad"));
  ok(get(source,"https://m.douyu.com/4837387?foo=1#bar").equals(ua));
  ok(get(source,"https://www.douyu.com.evil.invalid/4837387").isEmpty());
  ok(get(source,"https://example.org/4837387").isEmpty());
  ok(UserScriptRequestPolicy.userAgent(scripts(source,false),"https://www.douyu.com/4837387").isEmpty());
  String custom="// ==UserScript==\n// @match https://*.example.org/live/*\n// @exclude-match https://private.example.org/*\n// @exclude *?deny=1\n// @user-agent owner UA\n// ==/UserScript==";
  ok(get(custom,"https://example.org/live/1").equals("owner UA"));
  ok(get(custom,"https://a.example.org/live/1").equals("owner UA"));
  ok(get(custom,"https://private.example.org/live/1").isEmpty());
  ok(get(custom,"https://example.org/live/1?deny=1").isEmpty());
  ok(get(custom,"https://example.org/other/1").isEmpty());
  ok(get(custom.replace("owner UA","evil\rHeader: injection"),"https://example.org/live/1").isEmpty());
  ok(get(custom.replace("// @match https://*.example.org/live/*\n",""),"https://example.org/live/1").isEmpty());
  ok(get(custom,"file:///live/1").isEmpty());
  System.out.println("PASS script UA matches/exclusions, disabled/invalid metadata and original/mobile room scope");
 }
}'''
with tempfile.TemporaryDirectory() as folder:
    p=Path(folder);f=p/'Check.java';f.write_text(java,encoding='utf8')
    jdk=Path(os.environ['JAVA_HOME'])/'bin'
    subprocess.run([str(jdk/'javac'),'--release','8','-encoding','UTF-8','-cp',str(jar),'-d',folder,str(root/'app/src/main/java/xiao/bu/tv/UserScriptRequestPolicy.java'),str(f)],check=True)
    subprocess.run([str(jdk/'java'),'-cp',folder+os.pathsep+str(jar),'xiao.bu.tv.Check',str(root/'app/src/main/assets/userscripts/ntv-douyu-mobile.user.js')],check=True)
subprocess.run(['node','-e',r'''
const fs=require('fs'),vm=require('vm'),assert=require('assert');
const code=fs.readFileSync('app/src/main/assets/userscripts/ntv-douyu-mobile.user.js','utf8');
function run(href){let next='';vm.runInNewContext(code,{URL,location:{href,replace(x){next=x}}});return next;}
assert.equal(run('https://www.douyu.com/4837387?foo=1#bar'),'https://m.douyu.com/4837387?foo=1#bar');
for(const u of ['https://m.douyu.com/4837387','https://www.douyu.com/video/1','https://www.douyu.com:8443/4837387','https://www.douyu.com.evil.invalid/4837387'])assert.equal(run(u),'');
function background(headless){let removed=0,watched=0;const doc={querySelectorAll(q){assert.equal(q,'link[rel="stylesheet"],style');return [{parentNode:{removeChild(){removed++}}}];}};const mediaSource=function(){};const context={MediaSource:mediaSource,WebKitMediaSource:mediaSource,ManagedMediaSource:mediaSource,URL,location:{href:'https://m.douyu.com/4837387'},__host(){return {headless}},document:doc,MutationObserver:class{constructor(fn){this.fn=fn}observe(target){assert.equal(target,doc);watched++;}}};context.window=context;vm.runInNewContext(code,context);assert.equal(context.MediaSource,headless?undefined:mediaSource);assert.equal(context.WebKitMediaSource,headless?undefined:mediaSource);assert.equal(context.ManagedMediaSource,headless?undefined:mediaSource);return {removed,watched};}
assert.deepEqual(background(false),{removed:0,watched:0});
assert.deepEqual(background(true),{removed:1,watched:1});
console.log('PASS same-room redirect and headless-only CSS trimming; interactive page unchanged');
'''],cwd=root,check=True)
