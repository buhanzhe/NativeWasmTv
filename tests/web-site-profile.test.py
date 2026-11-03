"""Exercise provider boundaries, canonical room URLs and explicit-UA precedence."""
from pathlib import Path
import os, subprocess, tempfile
root=Path(__file__).resolve().parents[1]
source=root/'app/src/main/java/xiao/bu/tv/WebSiteProfile.java'
java=r'''package xiao.bu.tv;
class ProfileCheck {
 static void check(boolean b,String why){if(!b)throw new AssertionError(why);}
 public static void main(String[] args){
  for(String host:new String[]{"www.douyu.com","m.douyu.com","www.huya.com","m.huya.com","www.yy.com"})
   check(WebSiteProfile.userAgentMode("auto","https://"+host+"/123").equals("ipad"),host);
  for(String url:new String[]{"https://www.yangshipin.cn/tv/home?pid=600002521","https://tv.cctv.com/","https://m.gdtv.cn/43","https://live.jstv.com/","https://www.hebtv.com/","https://huya.com.evil.test/","https://evil.test/?u=www.douyu.com","invalid",null})
   check(WebSiteProfile.userAgentMode("auto",url).equals("windows"),"Windows station/default boundary: "+url);
  String input="https://WWW.DOUYU.COM/4837387?x=a%2Fb&empty=&twice=1&twice=2#keep%20me";
  String expected="https://m.douyu.com/4837387?x=a%2Fb&empty=&twice=1&twice=2#keep%20me";
  check(WebSiteProfile.pageUrl("auto",input).equals(expected),"query/fragment bytes preserved");
  for(String mode:new String[]{"windows","macos","ipad","native"}){
   check(WebSiteProfile.userAgentMode(mode,input).equals(mode),"manual UA not overridden");
   check(WebSiteProfile.pageUrl(mode,input).equals(input),"manual URL not rewritten");
  }
  for(String url:new String[]{"https://m.douyu.com/4837387","https://www.douyu.com/video/4837387","https://www.douyu.com/login","https://www.douyu.com/","https://www.douyu.com:443/4837387","https://u@www.douyu.com/4837387","https://www.douyu.com.evil.test/4837387","file://www.douyu.com/4837387","broken"})
   check(WebSiteProfile.pageUrl("auto",url).equals(url),"noncanonical URL untouched: "+url);
  check(WebSiteProfile.pageUrl("auto",null)==null,"null is safe");
  System.out.println("PASS automatic site UA, station defaults, explicit choices, exact host/room mapping and signed query preservation");
 }
}'''
jdk=Path(os.environ.get('JAVA_HOME','C:/Users/Administrator/.jdks/jbr-17.0.14'))/'bin'
with tempfile.TemporaryDirectory() as folder:
 p=Path(folder)/'ProfileCheck.java';p.write_text(java,encoding='utf8')
 subprocess.run([str(jdk/'javac.exe'),'-encoding','UTF-8','-d',folder,str(source),str(p)],check=True)
 subprocess.run([str(jdk/'java.exe'),'-cp',folder,'xiao.bu.tv.ProfileCheck'],check=True)
