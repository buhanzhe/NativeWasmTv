package xiao.bu.tv;

import java.net.*;
import java.util.*;
import okhttp3.Dns;

/** API 14–19 may wait on unusable AAAA routes before returning a usable A.
 * Query the device's existing resolver for A first on an IPv4 network. Never
 * substitute a public provider, and keep platform/IPv6-only fallback. */
final class LegacySystemDns implements Dns {
  private static final LegacySystemDns LEGACY=new LegacySystemDns();
  static Dns create(){return android.os.Build.VERSION.SDK_INT<=19?LEGACY:Dns.SYSTEM;}
  static boolean isSystem(Dns dns){return dns==Dns.SYSTEM||dns instanceof LegacySystemDns||dns instanceof ProcessDns&&((ProcessDns)dns).usesSystemResolver()||dns instanceof LiteDnsCache&&isSystem(((LiteDnsCache)dns).delegate());}
  @Override public List<InetAddress> lookup(String hostname)throws UnknownHostException{
    if(hostname==null||hostname.isEmpty())throw new UnknownHostException("Empty hostname");
    if(hostname.equalsIgnoreCase("localhost")||hostname.toLowerCase(Locale.US).endsWith(".localhost")||hostname.indexOf(':')>=0||hostname.matches("[0-9.]+")||!ipv4Network())return Dns.SYSTEM.lookup(hostname);
    String[] servers=systemServers();
    return servers.length==0?Dns.SYSTEM.lookup(hostname):new PublicDns(servers,53).lookup(hostname);
  }
  private static boolean ipv4Network(){
    try{Enumeration<NetworkInterface> interfaces=NetworkInterface.getNetworkInterfaces();while(interfaces!=null&&interfaces.hasMoreElements()){
      Enumeration<InetAddress> addresses=interfaces.nextElement().getInetAddresses();while(addresses.hasMoreElements()){
        InetAddress value=addresses.nextElement();if(value instanceof Inet4Address&&!value.isLoopbackAddress()&&!value.isLinkLocalAddress()&&!value.isAnyLocalAddress())return true;
      }
    }}catch(SocketException ignored){}return false;
  }
  static String[] systemServers(){
    List<String> servers=new ArrayList<>();
    try{java.lang.reflect.Method get=Class.forName("android.os.SystemProperties").getMethod("get",String.class);
      for(int i=1;i<=4;i++){String value=String.valueOf(get.invoke(null,"net.dns"+i)).trim();
        if(value.matches("[0-9]+(?:\\.[0-9]+){3}")&&!servers.contains(value))servers.add(value);
      }
    }catch(Exception ignored){}return servers.toArray(new String[0]);
  }
}
