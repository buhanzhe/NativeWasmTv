package xiao.bu.tv;
import android.content.Context;import android.net.*;import android.os.Build;import java.net.*;import java.util.*;
/** Framework fallbacks cover restricted/broken interface enumeration on older TVs. */
final class AndroidLanAddresses {
 static List<LanAddresses.Candidate> collect(Context c){List<LanAddresses.Candidate> values=LanAddresses.collect();
  if(Build.VERSION.SDK_INT>=21)try{Api21.collect(c,values);}catch(RuntimeException ignored){}
  try{ConnectivityManager cm=(ConnectivityManager)c.getSystemService(Context.CONNECTIVITY_SERVICE);NetworkInfo n=cm==null?null:cm.getNetworkInfo(ConnectivityManager.TYPE_WIFI);
   if(n==null||n.isConnected()){android.net.wifi.WifiManager wifi=(android.net.wifi.WifiManager)c.getApplicationContext().getSystemService(Context.WIFI_SERVICE);android.net.wifi.WifiInfo info=wifi==null?null:wifi.getConnectionInfo();int ip=info==null?0:info.getIpAddress();if(ip!=0)values.add(new LanAddresses.Candidate(InetAddress.getByAddress(new byte[]{(byte)ip,(byte)(ip>>8),(byte)(ip>>16),(byte)(ip>>24)}),"wlan-fallback"));}
  }catch(Exception ignored){}return values;
 }
 static String preferred(Context c){return LanAddresses.preferred(collect(c));}
 private static final class Api21 {
  static void collect(Context c,List<LanAddresses.Candidate> values){ConnectivityManager cm=(ConnectivityManager)c.getSystemService(Context.CONNECTIVITY_SERVICE);if(cm==null)return;Network[] networks=cm.getAllNetworks();if(networks==null)return;
   for(Network network:networks)try{NetworkInfo info=cm.getNetworkInfo(network);if(info!=null&&!info.isConnected())continue;LinkProperties p=cm.getLinkProperties(network);if(p!=null)for(LinkAddress a:p.getLinkAddresses())if(LanAddresses.usable(a.getAddress()))values.add(new LanAddresses.Candidate(a.getAddress(),p.getInterfaceName()));}catch(RuntimeException ignored){}
  }
 }
}
