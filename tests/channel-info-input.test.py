"""Actual numeric input methods and WebView metadata/card clocks, without Android timing races."""
from pathlib import Path
import os,subprocess,tempfile,xml.etree.ElementTree as ET
root=Path(__file__).resolve().parents[1]
main=(root/'app/src/main/java/xiao/bu/tv/MainActivity.java').read_text('utf8')
def method(signature):
    a=main.index(signature);b=main.index('{',a)+1;depth=1
    while depth:depth+=(main[b]=='{')-(main[b]=='}');b+=1
    return main[a:b]
methods='\n'.join(method(s) for s in ['    private void enterNumericChannel(', '    private void commitNumericChannel()', '    private void clearNumericChannelInput()'])
hide=method('    private final Runnable hideChannelBar =')+';'
java=r'''package xiao.bu.tv;
class Check {
 static final int NUMERIC_CHANNEL_MAX_DIGITS=4;static final long NUMERIC_CHANNEL_TIMEOUT_MS=3000;
 static class View {static int VISIBLE=0,GONE=8;}
 static class TextView {String text="";int visibility=8;void setText(String s){text=s;}void setVisibility(int v){visibility=v;}void bringToFront(){}}
 static class SystemClock {static long now;static long elapsedRealtime(){return now;}}
 static class Queue {Runnable pending;long delay;int visibility=0;int getVisibility(){return visibility;}void setVisibility(int v){visibility=v;}void removeCallbacks(Runnable r){if(pending==r)pending=null;}void postDelayed(Runnable r,long d){pending=r;delay=d;}void tick(){Runnable r=pending;pending=null;if(r!=null)r.run();}}
 static class ChannelCatalog {static String wanted;static int[] findGlobalChannel(String n){wanted=n;int i=Integer.parseInt(n);return i>0&&i<=9999?new int[]{2,i-1}:null;}}
 static class Channel {String name="current";}
 String numericChannelInput="",notice="";TextView numericChannelOverlay=new TextView();Queue channelBar=new Queue();int currentGroupIndex=-1,selected=-1;
 Runnable commitNumericChannel=()->commitNumericChannel();
 boolean loadingActive;int playRequestId=7;WebChannelInfo webChannelInfo=new WebChannelInfo();
 HIDE
 void ensureFlyMouseOnTop(){}void switchChannel(int i){selected=i;}Channel currentChannel(){return new Channel();}void showChannelBar(String n,String s){notice=s;}
 METHODS
 static void check(boolean b,String why){if(!b)throw new AssertionError(why);}
 public static void main(String[]args){
  Check input=new Check();for(char c:"123".toCharArray())input.enterNumericChannel(c-'0');
  check(input.numericChannelOverlay.text.equals("123")&&input.numericChannelOverlay.visibility==View.VISIBLE&&input.selected==-1,"third digit must paint before switching");
  input.enterNumericChannel(4);check(input.numericChannelOverlay.text.equals("1234")&&input.numericChannelInput.equals("1234")&&input.selected==-1,"four digits stay visible");
  check(input.channelBar.delay==3000,"inter-digit wait retained");input.channelBar.tick();
  check(input.currentGroupIndex==2&&input.selected==1233&&ChannelCatalog.wanted.equals("1234"),"complete four-digit number reaches channel lookup");
  check(input.numericChannelInput.isEmpty()&&input.numericChannelOverlay.visibility==View.GONE,"commit cleans overlay and buffer");
  input=new Check();input.enterNumericChannel(1);input.enterNumericChannel(2);input.commitNumericChannel();check(input.selected==11&&input.channelBar.pending==null,"OK commits a short number exactly once");
  input=new Check();for(char c:"9999".toCharArray())input.enterNumericChannel(c-'0');input.channelBar.tick();check(input.selected==9998,"four-digit upper bound");
  input=new Check();input.enterNumericChannel(0);input.channelBar.tick();check(input.notice.endsWith("0")&&input.selected==-1,"unknown channel reports full input");
  input=new Check();for(char c:"12345".toCharArray())input.enterNumericChannel(c-'0');check(input.numericChannelInput.equals("5"),"fifth digit begins a new bounded input");
  Check hud=new Check();hud.webChannelInfo.begin(7,0);SystemClock.now=3000;hud.hideChannelBar.run();
  check(hud.channelBar.visibility==View.VISIBLE&&hud.channelBar.delay==12000,"actual hide callback retains the card while metadata is pending");
  hud.webChannelInfo.ready(7,4000,true);SystemClock.now=5000;hud.hideChannelBar.run();check(hud.channelBar.delay==4000,"actual hide callback allows five readable seconds");
  SystemClock.now=9000;hud.hideChannelBar.run();check(hud.channelBar.visibility==View.GONE,"actual hide callback eventually dismisses the card");
  hud=new Check();hud.playRequestId=8;hud.webChannelInfo.begin(7,0);hud.hideChannelBar.run();check(hud.channelBar.visibility==View.GONE,"previous channel's metadata cannot retain another card");
  WebChannelInfo info=new WebChannelInfo();check(info.remaining(3,0)==0,"native channels retain normal timeout");
  info.begin(3,1000);check(info.remaining(3,4000)==12000,"page readiness waits for real playback information");
  check(info.ready(3,7000,true)&&info.remaining(3,7000)==5000,"complete metadata gets five readable seconds");
  info.begin(3,7500);check(info.remaining(3,7500)==4500,"duplicate page-ready events do not restart the interval");
  check(!info.ready(3,8000,true)&&info.remaining(3,12000)==0,"metadata polling never extends the card forever");
  info.begin(4,20000);check(info.remaining(4,35000)==0,"missing metadata wait is bounded");
  check(info.ready(4,50000,true)&&info.remaining(4,50000)==5000,"late metadata still receives its readable interval");
  info.cancel();check(!info.ready(4,51000,true)&&info.remaining(4,51000)==0,"explicit dismiss cannot reappear");
  check(info.frameRate(4,"doc/video",0,100,true)==0,"first frame sample has no invented rate");
  check(info.frameRate(4,"doc/video",1000,125,true)==25,"measured decoded frame rate");
  check(info.frameRate(4,"doc/new",2000,200,true)==0,"media replacement resets rate");
  check(info.frameRate(4,"doc/new",3000,0,true)==0,"stream frame-counter reset is safe");
  check(info.frameRate(4,"doc/new",4000,24,true)==24,"rate recovers after stream renewal");
  check(info.frameRate(4,"doc/new",5000,25,false)==0,"pause does not display stale rate");
  System.out.println("PASS visible 3/4-digit input, delayed/OK commit, 9999, unknown/cancel bounds; late WebView metadata, readable timeout, explicit dismissal and measured FPS resets");
 }
}'''.replace('METHODS',methods).replace('HIDE',hide)
layout=ET.parse(root/'app/src/main/res/layout/activity_main.xml')
android='{http://schemas.android.com/apk/res/android}'
numeric=next(e for e in layout.iter() if e.get(android+'id')=='@+id/numeric_channel_overlay')
assert numeric.get(android+'layout_width')=='wrap_content', 'Overlay must grow to fit four digits'
jdk=Path(os.environ.get('JAVA_HOME','C:/Users/Administrator/.jdks/jbr-17.0.14'))/'bin'
with tempfile.TemporaryDirectory() as folder:
    f=Path(folder)/'Check.java';f.write_text(java,'utf8')
    subprocess.run([str(jdk/'javac.exe'),'--release','8','-encoding','UTF-8','-d',folder,str(f),str(root/'app/src/main/java/xiao/bu/tv/WebChannelInfo.java')],check=True)
    subprocess.run([str(jdk/'java.exe'),'-cp',folder,'xiao.bu.tv.Check'],check=True)
