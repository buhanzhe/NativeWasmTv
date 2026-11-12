package xiao.bu.tv;
import java.net.*;import java.util.*;
/** Assigned unicast addresses, with LAN adapters before cellular/tunnel adapters. */
final class LanAddresses {
 static final class Candidate {final InetAddress address;final String device;Candidate(InetAddress a,String d){address=a;device=d==null?"":d.toLowerCase(Locale.US);}}
 static boolean usable(InetAddress a){return a!=null&&!a.isAnyLocalAddress()&&!a.isLoopbackAddress()&&!a.isMulticastAddress()&&(!(a instanceof Inet6Address)||!a.isLinkLocalAddress())&&!a.getHostAddress().equals("192.0.0.4")&&!a.getHostAddress().equals("255.255.255.255")&&!a.getHostAddress().startsWith("0.");}
 static List<Candidate> collect(){List<Candidate> out=new ArrayList<>();try{
  Enumeration<NetworkInterface> all=NetworkInterface.getNetworkInterfaces();if(all==null)return out;
  while(all.hasMoreElements()){NetworkInterface net=all.nextElement();try{if(!net.isUp()||net.isLoopback())continue;}catch(SocketException|SecurityException unsupported){}
   try{Enumeration<InetAddress> addresses=net.getInetAddresses();while(addresses.hasMoreElements()){InetAddress a=addresses.nextElement();if(usable(a))out.add(new Candidate(a,net.getName()));}}catch(RuntimeException unavailable){}
  }
 }catch(SocketException|SecurityException unavailable){}return out;}
 static int rank(Candidate c){String n=c.device;int adapter=n.startsWith("wlan")||n.startsWith("wifi")||n.startsWith("eth")||n.startsWith("en")||n.startsWith("br")||n.startsWith("ap")||n.startsWith("tiwlan")||n.startsWith("ra")?0:n.contains("p2p")?2:n.contains("rmnet")||n.startsWith("ccmni")||n.startsWith("pdp")||n.startsWith("ppp")||n.contains("tun")||n.contains("clat")?8:4;
  byte[] bytes=c.address.getAddress();int priority=c.address.isLinkLocalAddress()?50:c.address.isSiteLocalAddress()?0:10;
  if(bytes.length==4){int a=bytes[0]&255,b=bytes[1]&255;if(a==192&&b==168)priority=0;else if(a==10)priority=1;else if(a==172&&b>=16&&b<=31)priority=2;else if(a==100&&b>=64&&b<=127)priority=3;}
  else if((bytes[0]&0xfe)==0xfc)priority=0;
  return adapter*100+priority;
 }
 static List<Candidate> ordered(List<Candidate> input){List<Candidate> result=new ArrayList<>();for(Candidate c:input)if(usable(c.address))result.add(c);Collections.sort(result,(a,b)->rank(a)-rank(b));return result;}
 static String preferred(List<Candidate> input){List<Candidate> values=ordered(input);for(Candidate c:values)if(rank(c)<800&&c.address.getAddress().length==4&&!c.address.isLinkLocalAddress())return host(c.address);for(Candidate c:values)if(rank(c)<800&&c.address.getAddress().length==16)return host(c.address);for(Candidate c:values)if(rank(c)<800&&c.address.getAddress().length==4)return host(c.address);return null;}
 static List<String> ipv4(List<Candidate> input){LinkedHashSet<String> result=new LinkedHashSet<>();for(Candidate c:ordered(input))if(c.address.getAddress().length==4)result.add(host(c.address));return new ArrayList<>(result);}
 static String host(InetAddress a){String s=a.getHostAddress();int zone=s.indexOf('%');return zone<0?s:s.substring(0,zone);}
 static String authority(String address){return address!=null&&address.indexOf(':')>=0&&!address.startsWith("[")?"["+address+"]":address;}
}
