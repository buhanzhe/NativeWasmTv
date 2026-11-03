from pathlib import Path
import os, subprocess, tempfile
root=Path(__file__).resolve().parents[1]
files={
'android/graphics/Paint.java':'''package android.graphics; public class Paint { public static int calls;float size;public void setTextSize(float s){size=s;}public float getTextSize(){return size;}public float measureText(String s){calls++;float w=0;for(char c:s.toCharArray())w+=c<128?.5f:1;return w*size;}}''',
'xiao/bu/tv/Channel.java':'package xiao.bu.tv; class Channel {final String name;Channel(String n){name=n;}}',
'xiao/bu/tv/ChannelCatalog.java':'''package xiao.bu.tv; class ChannelCatalog {static Group[] GROUPS;static class Group {final Channel[] channels;Group(String... names){channels=new Channel[names.length];for(int i=0;i<names.length;i++)channels[i]=new Channel(names[i]);}}}''',
'xiao/bu/tv/ChannelNameWidth.java':(root/'app/src/main/java/xiao/bu/tv/ChannelNameWidth.java').read_text(encoding='utf8'),
'xiao/bu/tv/Check.java':'''package xiao.bu.tv;import android.graphics.Paint;class Check {static void eq(float a,float b){if(Math.abs(a-b)>.001)throw new AssertionError(a+" != "+b);}public static void main(String[] args){
ChannelCatalog.GROUPS=new ChannelCatalog.Group[]{new ChannelCatalog.Group("短"),new ChannelCatalog.Group("最长频道名称")};Paint p=new Paint();p.setTextSize(20);eq(ChannelNameWidth.pixels(p),120);
int calls=Paint.calls;for(int i=0;i<10000;i++)eq(ChannelNameWidth.pixels(p),120);if(Paint.calls!=calls)throw new AssertionError("Warm reads rescan catalog");
p.setTextSize(30);eq(ChannelNameWidth.pixels(p),180);if(Paint.calls!=calls)throw new AssertionError("Scale change rescans catalog");
eq(ChannelNameWidth.measure(new ChannelCatalog.Group[]{new ChannelCatalog.Group("一二三四五六七八九十更多文字")}),9);
eq(ChannelNameWidth.measure(new ChannelCatalog.Group[]{new ChannelCatalog.Group("ABCDEFGHIJ")}),5);
ChannelNameWidth.restore(4);eq(ChannelNameWidth.pixels(p),120);ChannelNameWidth.restore(0);eq(ChannelNameWidth.pixels(p),0);ChannelNameWidth.restore(10);eq(ChannelNameWidth.pixels(p),270);ChannelNameWidth.restore(99);eq(ChannelNameWidth.pixels(p),270);
if(!ChannelNameWidth.META.equals("channel_name_width_v5"))throw new AssertionError("Old capped catalog widths must be remeasured");
System.out.println("PASS catalog-wide maximum, 9-character cap, mixed-width names, persisted restore, scale changes and 10000 cached reads");}}'''}
with tempfile.TemporaryDirectory() as temp:
 out=Path(temp)
 for name,text in files.items():
  f=out/name;f.parent.mkdir(parents=True,exist_ok=True);f.write_text(text,encoding='utf8')
 jdk=Path(os.environ.get('JAVA_HOME',root/'.codex-tmp/jdk8/runtime/jdk8u504-b01'))/'bin'
 subprocess.run([str(jdk/'javac.exe'),'-encoding','UTF-8','-d',temp]+[str(out/n) for n in files],check=True)
 subprocess.run([str(jdk/'java.exe'),'-cp',temp,'xiao.bu.tv.Check'],check=True)
