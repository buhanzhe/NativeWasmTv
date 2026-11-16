package xiao.bu.tv;

import java.text.ParsePosition;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import okhttp3.HttpUrl;
import org.json.JSONObject;

/** Timestamp from the original signed API, never from cached static assets. */
final class LiteApiClock {
  static boolean wuhanPage(int sdk,String value){
    HttpUrl page=value==null?null:HttpUrl.parse(value);
    return sdk==19&&page!=null&&page.isHttps()&&page.host().equals("ronghehao-h5.whjyapp.com")
        &&page.encodedPath().equals("/")&&"/pages/live/tv".equals(page.fragment());
  }
  static long wuhan(int sdk,String page,String request,String body,long serverDate){
    if(!wuhanPage(sdk,page)||request==null||body==null||body.length()>65536||serverDate<=0)return 0;
    HttpUrl api=HttpUrl.parse(request);
    if(api==null||!api.isHttps()||!api.host().equals("ronghehao.whjyapp.com")||!api.encodedPath().equals("/v3/media_channel_program/dateList"))return 0;
    try{
      JSONObject root=new JSONObject(body),data=root.optJSONObject("data");org.json.JSONArray dates=data==null?null:data.optJSONArray("date");
      if(root.optInt("code",-1)!=1||dates==null||dates.length()==0||dates.length()>31)return 0;
      String active=null;
      for(int i=0;i<dates.length();i++){JSONObject item=dates.optJSONObject(i);if(item!=null&&item.optInt("active",0)==1){if(active!=null)return 0;active=item.optString("date","");}}
      SimpleDateFormat day=new SimpleDateFormat("yyyy-MM-dd",Locale.US);day.setTimeZone(TimeZone.getTimeZone("Asia/Shanghai"));
      return day.format(new Date(serverDate)).equals(active)?serverDate:0;
    }catch(Exception invalid){return 0;}
  }
  static long bozhou(String page,String request,String body) {
    try {
      HttpUrl owner=HttpUrl.parse(page),api=HttpUrl.parse(request);
      if(owner==null||api==null||!owner.host().equals("www.ahbztv.com")||!api.isHttps()
          ||!api.host().equals("jkwg.ahbztv.com")||!api.encodedPath().equals("/bzapi/external/externalService")
          ||body==null||body.length()>65536)return 0;
      JSONObject data=new JSONObject(body);int state=data.optInt("state",-1);
      String value=data.optString("currTime","");if((state!=1000&&state!=1006)||!value.matches("[0-9]{14}"))return 0;
      SimpleDateFormat format=new SimpleDateFormat("yyyyMMddHHmmss",Locale.US);
      format.setTimeZone(TimeZone.getTimeZone("Asia/Shanghai"));format.setLenient(false);
      ParsePosition position=new ParsePosition(0);Date parsed=format.parse(value,position);
      return parsed!=null&&position.getIndex()==14?parsed.getTime():0;
    } catch(Exception invalid) {return 0;}
  }
}
