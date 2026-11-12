package xiao.bu.tv;

import android.content.Context;
import android.net.Uri;
import java.io.*;
import java.net.*;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.*;
import org.json.*;

/** Offline XMLTV/HLS fixture: real decoded archives, no device Wi-Fi dependency. */
final class SevenDayLocalFixture implements Closeable {
    private final Context context;private final ServerSocket server;
    private final ExecutorService workers=Executors.newFixedThreadPool(4);
    private final long today=EpgManager.dayStart(System.currentTimeMillis());
    SevenDayLocalFixture(Context context)throws IOException{
        this.context=context;server=new ServerSocket(0,16,InetAddress.getByName("127.0.0.1"));
        Thread accept=new Thread(()->{while(!server.isClosed())try{Socket socket=server.accept();workers.execute(()->serve(socket));}catch(IOException stopped){}},"seven-day-local-fixture");accept.setDaemon(true);accept.start();
    }
    String base(){return "http://127.0.0.1:"+server.getLocalPort();}
    private long day(int offset){Calendar date=Calendar.getInstance();date.setTimeInMillis(today);date.add(Calendar.DATE,-offset);return date.getTimeInMillis();}
    private byte[] asset(String path)throws IOException{try(InputStream in=context.getAssets().open("seven/"+path);ByteArrayOutputStream out=new ByteArrayOutputStream()){byte[] block=new byte[8192];int n;while((n=in.read(block))!=-1)out.write(block,0,n);return out.toByteArray();}}
    private String guide(String path){
        SimpleDateFormat date=new SimpleDateFormat("yyyyMMddHHmmss Z",Locale.US);
        StringBuilder xml=new StringBuilder("<?xml version='1.0' encoding='UTF-8'?><tv><channel id='seven'><display-name>七日回看测试</display-name></channel><channel id='broken'><display-name>失败保护测试</display-name></channel>");
        for(int offset=0;offset<7;offset++){
            if(path.contains("single")&&offset!=0||path.contains("sparse")&&(offset==2||offset==4))continue;
            for(int hour=0;hour<24;hour++){long begin=day(offset)+hour*3600000L;xml.append("<programme channel='seven' start='").append(date.format(new Date(begin))).append("' stop='").append(date.format(new Date(begin+3600000))).append("' catchup-id='d").append(offset).append("-h").append(hour).append("'><title>第").append(offset).append("日前 ").append(hour).append("点节目</title></programme>");}
        }
        xml.append("<programme channel='seven' start='").append(date.format(new Date(day(9)))).append("' stop='").append(date.format(new Date(day(9)+3600000))).append("'><title>过期节目</title></programme>");
        return xml.append("<programme channel='broken' start='").append(date.format(new Date(day(1)+3600000))).append("' stop='").append(date.format(new Date(day(1)+7200000))).append("'><title>失败保护节目</title></programme></tv>").toString();
    }
    private String channels(){return "#EXTM3U\n#EXTINF:-1 tvg-id=\"seven\" group-title=\"七日测试\" catchup=\"default\" catchup-days=\"7\" catchup-source=\""+base()+"/archive.m3u8?line=1&start={utc}&end={utcend}&program={catchup-id}\",七日回看测试\n"+base()+"/live.m3u8?line=1\n#EXTINF:-1 tvg-id=\"seven\" group-title=\"七日测试\" catchup=\"append\" catchup-days=\"7\" catchup-source=\"?start={utc}&end={utcend}&program={catchup-id}\",七日回看测试\n"+base()+"/live.m3u8?line=2\n#EXTINF:-1 tvg-id=\"broken\" group-title=\"七日测试\" catchup=\"default\" catchup-days=\"7\" catchup-source=\""+base()+"/missing.m3u8?start={utc}&end={utcend}\",失败保护测试\n"+base()+"/live.m3u8?line=3\n";}
    private void serve(Socket socket){try(Socket client=socket){
        client.setSoTimeout(5000);BufferedReader in=new BufferedReader(new InputStreamReader(client.getInputStream(),"UTF-8"));String first=in.readLine();if(first==null)return;String[] request=first.split(" ");String line;while((line=in.readLine())!=null&&!line.isEmpty()){}
        Uri uri=Uri.parse(request[1]);String path=uri.getPath(),type="application/vnd.apple.mpegurl";int status=200;byte[] body;
        if(path.equals("/config.json")){JSONArray dates=new JSONArray();for(int i=6;i>=0;i--)dates.put(day(i));body=new JSONObject().put("today",today).put("dates",dates).toString().getBytes("UTF-8");type="application/json";}
        else if(path.equals("/channels.m3u"))body=channels().getBytes("UTF-8");
        else if(path.startsWith("/guide")){body=guide(path).getBytes("UTF-8");type="application/xml";}
        else if(path.equals("/missing.m3u8")){status=404;body=new byte[0];}
        else if(path.equals("/live.m3u8")||path.equals("/archive.m3u8")){
            String start=uri.getQueryParameter("start");StringBuilder playlist=new StringBuilder();
            if(start==null){playlist.append("#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-TARGETDURATION:2\n");for(int i=0;i<300;i++){if(i>0&&i%6==0)playlist.append("#EXT-X-DISCONTINUITY\n");playlist.append("#EXTINF:2,\n").append(base()).append("/archive-0/index").append(i%6).append(".ts\n");}playlist.append("#EXT-X-ENDLIST\n");}
            else{
                long requested=Long.parseLong(start)*1000;int offset=-1;for(int i=0;i<7;i++)if(EpgManager.dayStart(requested)==day(i))offset=i;
                Calendar date=Calendar.getInstance();date.setTimeInMillis(requested);String pid="d"+offset+"-h"+date.get(Calendar.HOUR_OF_DAY);
                if(offset<0||!pid.equals(uri.getQueryParameter("program"))||Long.parseLong(uri.getQueryParameter("end"))*1000!=requested+3600000){status=400;}
                else{
                    SimpleDateFormat utc=new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'",Locale.US);utc.setTimeZone(TimeZone.getTimeZone("UTC"));String data=new String(asset("archive-"+offset+"/index.m3u8"),"UTF-8");boolean firstSegment=true;
                    for(String item:data.split("\n")){if(firstSegment&&item.startsWith("#EXTINF:")){playlist.append("#EXT-X-PROGRAM-DATE-TIME:").append(utc.format(new Date(requested))).append('\n');firstSegment=false;}if(!item.isEmpty()&&!item.startsWith("#"))playlist.append(base()).append("/archive-").append(offset).append('/');playlist.append(item).append('\n');}
                }
            }
            body=playlist.toString().getBytes("UTF-8");
        }else if(path.matches("/archive-[0-6]/index[0-9]+\\.ts")){body=asset(path.substring(1));type="video/mp2t";}
        else{status=404;body=new byte[0];}
        OutputStream out=client.getOutputStream();out.write(("HTTP/1.1 "+status+" Result\r\nContent-Type: "+type+"\r\nContent-Length: "+body.length+"\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n").getBytes("UTF-8"));if(!request[0].equals("HEAD"))out.write(body);out.flush();
    }catch(Exception ignored){}}
    public void close()throws IOException{server.close();workers.shutdownNow();}
}
