"""Exercise real group metadata and visit grants without Android/network dependencies."""
from pathlib import Path
import os
import subprocess
import tempfile

root = Path(__file__).resolve().parents[1]
java = r'''
package xiao.bu.tv;
public final class GroupPasswordCheck {
 static void check(boolean ok,String reason){if(!ok)throw new AssertionError(reason);}
 public static void main(String[] args){
  ChannelCatalog.Group a=new ChannelCatalog.Group("测试频道_10086",3,new Channel[0]);
  ChannelCatalog.Group b=new ChannelCatalog.Group("高清秒播_0709",3,new Channel[0]);
  check(a.title.equals("测试频道_10086")&&a.displayTitle.equals("测试频道")&&a.password.equals("10086"),"raw title survives save/export while UI hides suffix");
  check(b.displayTitle.equals("高清秒播")&&b.password.equals("0709"),"leading zero preserved");
  ChannelCatalog.Group eight=new ChannelCatalog.Group("八位密码_01234567",3,new Channel[0]);
  check(eight.displayTitle.equals("八位密码")&&eight.password.equals("01234567"),"eight digits and leading zero preserved");
  for(String ordinary:new String[]{"普通频道","频道_4K","频道_","_123","频道_１２３","频道_12x"}){
   GroupPassword parsed=new GroupPassword(ordinary,true);
   check(parsed.password.isEmpty()&&parsed.title.equals(ordinary),"ordinary name: "+ordinary);
  }
  check(new GroupPassword("我的_测试_001",true).title.equals("我的_测试"),"final separator only");
  check(new GroupPassword("频道_123",false).password.isEmpty(),"built-in groups unaffected");
  GroupPassword.Session session=new GroupPassword.Session();Object normal=new Object(),favorite=new Object();
  check(!session.unlock(eight,eight,eight.password,"1234567"),"eight-digit password cannot lose leading zero");
  check(!session.unlock(eight,eight,eight.password,"0123456"),"seven digits cannot unlock eight-digit group");
  check(session.unlock(eight,eight,eight.password,"01234567")&&session.allows(eight,eight,eight.password),"eight-digit unlock");
  session.visit(normal);check(!session.allows(eight,eight,eight.password),"eight-digit group relocks on leaving");
  check(!session.allows(a,a,a.password),"locked initially");
  check(!session.unlock(a,a,a.password,"00000")&&!session.allows(a,a,a.password),"wrong password grants nothing");
  check(session.unlock(a,a,a.password,"10086"),"unlock A");
  for(int i=0;i<100;i++){session.visit(a);check(session.allows(a,a,a.password),"same visit channel cycles/retries");}
  check(!session.unlock(b,b,b.password,"709")&&!session.allows(b,b,b.password),"no numeric coercion");
  check(session.allows(a,a,a.password),"failed/cancelled entry does not revoke current visit");
  check(session.allows(normal,normal,""),"ordinary group remains open");session.visit(normal);
  check(!session.allows(a,a,a.password),"leaving group relocks");
  check(session.unlock(b,b,b.password,"0709"),"unlock B");
  check(!session.allows(a,a,a.password),"only current group grant retained");
  session.visit(favorite);check(!session.allows(favorite,b,b.password),"favorite cannot inherit direct group grant");
  check(session.unlock(favorite,b,b.password,"0709"),"favorite own visit unlock");
  check(session.allows(favorite,b,b.password)&&!session.allows(favorite,a,a.password),"favorites enforce original protected owner");
  session.visit(favorite);check(session.allows(favorite,b,b.password),"opening same favorite group preserves its visit");
  session.visit(normal);check(!session.allows(favorite,b,b.password),"leaving favorites relocks");
  session.unlock(a,a,a.password,"10086");Object replacement=new Object();
  check(!session.allows(replacement,replacement,a.password),"same-named replacement/source cannot reuse grant");
  session.clear();check(!session.allows(a,a,a.password),"destroy/restart clears grant");
  System.out.println("PASS: raw/display titles, numeric suffix, leading zero, wrong/cancel, repeated switching, leave/reentry, favorite isolation, catalog replacement, in-memory reset");
 }
}
'''
with tempfile.TemporaryDirectory(prefix='ntv-group-password-') as tmp:
    test = Path(tmp) / 'GroupPasswordCheck.java'
    test.write_text(java, encoding='utf8')
    java_bin = Path(os.environ['JAVA_HOME']) / 'bin'
    sources = [root / 'app/src/main/java/xiao/bu/tv' / (name + '.java')
               for name in ['GroupPassword', 'ChannelCatalog', 'Channel', 'IptvCatchup']]
    subprocess.run([str(java_bin / 'javac.exe'), '--release', '8', '-encoding', 'UTF-8', '-d', tmp,
                    *map(str, sources), str(test)], check=True)
    subprocess.run([str(java_bin / 'java.exe'), '-cp', tmp, 'xiao.bu.tv.GroupPasswordCheck'], check=True)
