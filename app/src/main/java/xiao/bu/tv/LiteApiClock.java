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
