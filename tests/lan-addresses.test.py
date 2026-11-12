"""Execute real address selection and IPv6 management-origin parsing without DNS/network I/O."""
from pathlib import Path
import subprocess,os,tempfile
root=Path(__file__).resolve().parents[1]
source=r"""package xiao.bu.tv;import java.net.*;import java.util.*;class Check{
 static void ok(boolean b,String why){if(!b)throw new AssertionError(why);}
 static LanAddresses.Candidate c(String a,String n)throws Exception{return new LanAddresses.Candidate(InetAddress.getByName(a),n);}
 public static void main(String[]unused)throws Exception{
  ok(LanAddresses.preferred(Arrays.asList(c("100.64.2.3","eth0"))).equals("100.64.2.3"),"CGNAT interface IPv4 was discarded");
  ok(LanAddresses.preferred(Arrays.asList(c("198.51.100.3","eth0"))).equals("198.51.100.3"),"Assigned non-RFC1918 IPv4 was discarded");
  ok(LanAddresses.preferred(Arrays.asList(c("10.2.3.4","rmnet0"),c("192.168.1.8","wlan0"))).equals("192.168.1.8"),"Cellular beat LAN");
  String v6=LanAddresses.preferred(Arrays.asList(c("fd01::1234","eth0"),c("fe80::1","wlan0")));ok(v6!=null&&v6.contains(":"),"ULA fallback missing");
  ok(LanAddresses.preferred(Arrays.asList(c("192.168.1.8","eth0"),c("fd01::1","eth0"))).equals("192.168.1.8"),"IPv4 priority changed");
  for(String a:new String[]{"0.0.0.0","127.0.0.1","255.255.255.255","224.0.0.1","::","::1","fe80::1","ff02::1"})ok(!LanAddresses.usable(InetAddress.getByName(a)),"Unusable address offered: "+a);
  ok(LanAddresses.preferred(Arrays.asList(c("10.2.3.4","rmnet0")))==null,"Cell-only address advertised as LAN");
  ok(LanAddresses.authority(v6).startsWith("[")&&LanAddresses.authority(v6).endsWith("]"),"IPv6 URL not bracketed");
  ok(ControlSite.ownsOrigin("http://[fd01::1234]:9966/index.html",9966,v6),"Compressed IPv6 origin rejected");
  ok(ControlSite.ownsOrigin("http://[::1]:9966/index.html",9966,null),"IPv6 loopback rejected");
  ok(!ControlSite.ownsOrigin("http://[fd01::9999]:9966/index.html",9966,v6),"Foreign IPv6 origin accepted");
  ok(!ControlSite.ownsOrigin("http://user@[fd01::1234]:9966/",9966,v6),"Userinfo origin accepted");
  System.out.println("PASS non-RFC1918 IPv4, LAN preference, IPv6-only ULA fallback, link-local/loopback/multicast exclusions and exact IPv6 origins");
 }}"""
with tempfile.TemporaryDirectory() as folder:
 p=Path(folder)/'Check.java';p.write_text(source,encoding='utf8');jdk=Path(os.environ['JAVA_HOME'])/'bin'
 subprocess.run([str(jdk/'javac'),'-encoding','UTF-8','-d',folder,str(root/'app/src/main/java/xiao/bu/tv/LanAddresses.java'),str(root/'app/src/main/java/xiao/bu/tv/ControlSite.java'),str(p)],check=True)
 subprocess.run([str(jdk/'java'),'-cp',folder,'xiao.bu.tv.Check'],check=True)
