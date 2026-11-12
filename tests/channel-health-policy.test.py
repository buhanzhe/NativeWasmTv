from pathlib import Path
import subprocess,tempfile
root=Path(__file__).resolve().parents[1]
java="""package xiao.bu.tv;class Check{static void ok(boolean b){if(!b)throw new AssertionError();}public static void main(String[]a){
 for(int status:new int[]{-1,0,1}){ok(ChannelHealthPolicy.fresh(status,1000,1000));ok(!ChannelHealthPolicy.fresh(status,1000,1000+ChannelHealthPolicy.ttl(status)));ok(!ChannelHealthPolicy.fresh(status,2000,1000));ok(!ChannelHealthPolicy.fresh(status,0,1000));}
 ok(ChannelHealthPolicy.entryStatus(404)==-1&&ChannelHealthPolicy.entryStatus(410)==-1);
 for(int code:new int[]{200,206,401,403,429,500,502,503})ok(ChannelHealthPolicy.entryStatus(code)==0);
 ok(ChannelHealthPolicy.result(true,true,false,0,0)==1);ok(ChannelHealthPolicy.result(true,true,true,1920,1080)==1);
 ok(ChannelHealthPolicy.result(true,true,true,0,0)==0);ok(ChannelHealthPolicy.result(false,true,true,1920,1080)==0);
 ok(ChannelHealthPolicy.unresolved("https://host/site.cjs?id=1")&&ChannelHealthPolicy.unresolved("https://host/page.js?x=1"));
 ok(!ChannelHealthPolicy.unresolved("https://host/media.m3u8?token=a.js"));
 System.out.println("PASS expiration, clock rollback, confirmed absence, auth/server uncertainty and usable track evidence");}}
"""
with tempfile.TemporaryDirectory() as d:
 p=Path(d)/'Check.java';p.write_text(java,encoding='utf-8');subprocess.run(['javac','--release','8','-encoding','UTF-8','-d',d,str(root/'app/src/main/java/xiao/bu/tv/ChannelHealthPolicy.java'),str(p)],check=True);subprocess.run(['java','-cp',d,'xiao.bu.tv.Check'],check=True)
