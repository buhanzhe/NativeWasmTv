package xiao.bu.tv;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.view.View;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.*;
import org.json.JSONObject;

/** Bounded startup wallpaper; never part of the playing video's background. */
final class PersonalBackground implements Closeable {
    private final SharedPreferences prefs;
    private final File directory;
    private final Handler ui=new Handler();
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private View root;
    private volatile String mode,status="",copyright="";
    private volatile String bingChoice;
    private volatile boolean bingAutoUpdate;
    private String catalogCache;
    private final Object catalogLock=new Object();
    private volatile long revision;
    private volatile int generation;
    private volatile boolean closed;
    private volatile boolean startupFinished;
    private volatile long minimumDisplayMs=1000L;
    private volatile String imageUrl="";
    static int validDisplaySeconds(int seconds) {
        if(seconds<1||seconds>120)throw new IllegalArgumentException("开屏时间须为 1–120 秒");
        return seconds;
    }
    synchronized void setDisplaySeconds(int seconds) {
        minimumDisplayMs=validDisplaySeconds(seconds)*1000L;
        prefs.edit().putInt("background_display_seconds",seconds).apply();
    }
    synchronized void selectUrl(String value) throws IOException {
        value=value.trim();
        if(value.isEmpty()) {
            imageUrl="";if("url".equals(mode))mode="off";
            prefs.edit().putString("background_image_url","").putString("personal_background",mode).apply();refresh(false);return;
        }
        URL url=new URL(value);
        if((!"http".equals(url.getProtocol())&&!"https".equals(url.getProtocol()))||url.getHost().isEmpty())
            throw new IOException("请输入 HTTP 或 HTTPS 图片链接");
        imageUrl=value;mode="url";
        prefs.edit().putString("background_image_url",value).putString("personal_background",mode).apply();
        refresh(false);
    }
    // A failed stream, missing first-frame callback or image download must never
    // keep a decorative layer above the controls indefinitely.
    private static final long MAXIMUM_STARTUP_MS=30000L;
    private boolean startupExpired;
    private final Runnable expireStartup=()->{startupExpired=true;completeStartupIfReady();};
    private volatile boolean playbackReady;
    private volatile boolean imageLoading;
    private volatile boolean deferredBingUpdate;
    private volatile long firstDrawAt=-1L;
    private final Runnable removeStartup=this::completeStartupIfReady;
    PersonalBackground(Context context,View root,SharedPreferences prefs) {
        this.root=root;this.prefs=prefs;directory=new File(context.getFilesDir(),"personal-background");
        imageUrl=prefs.getString("background_image_url","");
        int seconds=prefs.getInt("background_display_seconds",1);
        minimumDisplayMs=(seconds>=1&&seconds<=120?seconds:1)*1000L;
        mode=prefs.getString("personal_background","off");
        if(!validMode(mode))mode="off";
        bingChoice=prefs.getString("background_bing_choice","");
        bingAutoUpdate=prefs.getBoolean("background_bing_auto_update",true);
        if(!bingChoice.isEmpty()&&!validBingId(bingChoice))bingChoice="";
        copyright=prefs.getString(bingChoice.isEmpty()?"background_copyright":"background_bing_choice_copyright","");
        ui.postDelayed(expireStartup,Math.max(MAXIMUM_STARTUP_MS,minimumDisplayMs+10000L));
        refresh(true);
    }
    static boolean validMode(String value){return "off".equals(value)||"bing".equals(value)||"custom".equals(value)||"url".equals(value);}
    JSONObject state() throws org.json.JSONException {
        return new JSONObject().put("mode",mode).put("status",status).put("copyright","bing".equals(mode)?copyright:"")
                .put("revision",revision).put("customAvailable",file("custom").isFile()).put("imageAvailable",imageAvailable())
                .put("bingAutoUpdate",bingAutoUpdate&&bingChoice.isEmpty()).put("bingChoice",bingChoice).put("bingChoiceTitle",prefs.getString("background_bing_choice_title",""))
                .put("startupFinished",startupFinished).put("startupPlaybackReady",playbackReady)
                .put("startupTimedOut",startupExpired).put("imageUrl",imageUrl).put("displaySeconds",minimumDisplayMs/1000L)
                .put("startupDisplayMs",firstDrawAt<0?0:android.os.SystemClock.uptimeMillis()-firstDrawAt);
    }
    synchronized void select(String value) throws IOException {
        if(!validMode(value))throw new IOException("不支持的背景选项");
        if("custom".equals(value)&&!file("custom").isFile())throw new IOException("请先上传自定义图片");
        if("url".equals(value)&&imageUrl.isEmpty())throw new IOException("请先保存背景图片链接");
        mode=value;prefs.edit().putString("personal_background",value).apply();refresh(false);
    }
    private File file(String value){return new File(directory,value+".jpg");}
    private File selectedImage(){return file("bing".equals(mode)&&!bingChoice.isEmpty()?"bing-picked":mode);}
    private boolean imageAvailable(){return !"off".equals(mode)&&selectedImage().isFile()
            &&(!"url".equals(mode)||imageUrl.equals(prefs.getString("background_cached_url","")))
            &&(!"bing".equals(mode)||bingChoice.isEmpty()||bingChoice.equals(prefs.getString("background_bing_cached_choice","")));}
    private static boolean validBingId(String value){return value!=null&&value.matches("/th\\?id=OHR\\.[A-Za-z0-9_-]+");}
    private static URL bingImage(String id,String size) throws IOException {
        if(!validBingId(id))throw new IOException("Bing 图片地址无效");
        return new URL("https://www.bing.com"+id+"_"+size+".jpg");
    }
    JSONObject bingCatalog() throws Exception {
        synchronized(catalogLock){
            File cached=new File(directory,"bing-catalog.json");
            if(catalogCache==null&&cached.isFile())try(InputStream input=new FileInputStream(cached)){
                String text=new String(read(input,256*1024),"UTF-8");new JSONObject(text);catalogCache=text;
            }catch(Exception ignored){}
            String day=today();
            if(catalogCache!=null&&day.equals(new JSONObject(catalogCache).optString("day")))
                return new JSONObject(catalogCache).put("ok",true).put("stale",false);
            try{
                org.json.JSONArray images=new JSONObject(new String(download(new URL("https://www.bing.com/HPImageArchive.aspx?format=js&idx=0&n=8&mkt=zh-CN"),256*1024),"UTF-8")).getJSONArray("images");
                org.json.JSONArray choices=new org.json.JSONArray();
                for(int i=0;i<images.length()&&i<8;i++){
                    JSONObject image=images.getJSONObject(i);String id=image.optString("urlbase");
                    if(!validBingId(id))continue;
                    choices.put(new JSONObject().put("id",id).put("title",image.optString("title","Bing 每日壁纸"))
                            .put("date",image.optString("startdate")).put("copyright",image.optString("copyright"))
                            .put("thumbnail",bingImage(id,"320x180").toString()));
                }
                if(choices.length()==0)throw new IOException("Bing 暂未返回壁纸");
                String text=new JSONObject().put("day",day).put("images",choices).toString();
                if(!directory.isDirectory()&&!directory.mkdirs()&&!directory.isDirectory())throw new IOException("无法创建背景目录");
                File temporary=new File(directory,"bing-catalog.json.tmp");
                try{
                    try(OutputStream out=new FileOutputStream(temporary)){out.write(text.getBytes("UTF-8"));}
                    if(!temporary.renameTo(cached))throw new IOException("无法保存壁纸列表");
                }finally{temporary.delete();}
                catalogCache=text;
                return new JSONObject(text).put("ok",true).put("stale",false);
            }catch(Exception error){
                if(catalogCache==null)throw error;
                return new JSONObject(catalogCache).put("ok",true).put("stale",true);
            }
        }
    }
    void selectBing(String id) throws Exception {
        String title="",credit="";
        if(!id.isEmpty()){
            if(!validBingId(id))throw new IOException("请选择列表中的 Bing 壁纸");
            org.json.JSONArray images=bingCatalog().getJSONArray("images");boolean found=false;
            for(int i=0;i<images.length();i++){
                JSONObject image=images.getJSONObject(i);
                if(id.equals(image.getString("id"))){title=image.getString("title");credit=image.getString("copyright");found=true;break;}
            }
            if(!found)throw new IOException("壁纸列表已更新，请重新选择");
        }
        synchronized(this){
            bingChoice=id;bingAutoUpdate=id.isEmpty();mode="bing";copyright=id.isEmpty()?prefs.getString("background_copyright",""):credit;
            prefs.edit().putString("personal_background","bing").putString("background_bing_choice",id)
                    .putBoolean("background_bing_auto_update",bingAutoUpdate).putString("background_bing_choice_title",title).putString("background_bing_choice_copyright",credit).apply();
            refresh(false);
        }
    }
    synchronized void setBingAutoUpdate(boolean enabled) throws Exception {
        if(enabled){selectBing("");return;}
        bingAutoUpdate=false;
        prefs.edit().putBoolean("background_bing_auto_update",false).apply();
        refresh(false); // Invalidates an in-flight daily download and reuses the current image.
    }
    static boolean needsBingRefresh(boolean cached,boolean automatic,String cachedDay,String day) {
        return !cached||(automatic&&!day.equals(cachedDay));
    }
    private static String today(){SimpleDateFormat f=new SimpleDateFormat("yyyyMMdd",Locale.US);f.setTimeZone(TimeZone.getTimeZone("Asia/Shanghai"));return f.format(new Date());}
    private synchronized void refresh(boolean cachedStartup){
        final int token=++generation;final String selected=mode,choice=bingChoice,remote=imageUrl;final boolean daily=bingAutoUpdate;revision++;
        deferredBingUpdate=false;
        imageLoading=!"off".equals(selected);
        if("off".equals(selected)){status="已关闭";apply(token,null);return;}
        status="正在载入…";
        worker.execute(()->{
            if(closed||token!=generation)return;
            try{
                if("url".equals(selected)) {
                    File target=file("url");
                    if(!target.isFile()||!remote.equals(prefs.getString("background_cached_url",""))) {
                        Bitmap downloaded=decode(download(new URL(remote),8*1024*1024));
                        try { synchronized(PersonalBackground.this) {
                            if(closed||token!=generation)return;
                            save(target,downloaded);prefs.edit().putString("background_cached_url",remote).apply();
                        }} finally {downloaded.recycle();}
                    }
                }
                if("bing".equals(selected)){
                    if(!choice.isEmpty()){
                        File target=file("bing-picked");
                        if(!target.isFile()||!choice.equals(prefs.getString("background_bing_cached_choice",""))){
                            Bitmap picked=decode(download(bingImage(choice,"1280x720"),8*1024*1024));
                            try{synchronized(PersonalBackground.this){
                                if(closed||token!=generation)return;
                                save(target,picked);prefs.edit().putString("background_bing_cached_choice",choice).apply();
                            }}finally{picked.recycle();}
                        }
                        if(token==generation&&!closed){status=appliedStatus();revision++;}
                        apply(token,decode(target));return;
                    }
                    synchronized(PersonalBackground.this){if(closed||token!=generation)return;copyright=prefs.getString("background_copyright","");}
                    boolean cached=file("bing").isFile();
                    if(cached){
                        if(!startupFinished)apply(token,decode(file("bing")));
                        if(!needsBingRefresh(true,daily,prefs.getString("background_bing_day",""),today())){status=appliedStatus();return;}
                        if(cachedStartup){
                            if(!closed&&token==generation){deferredBingUpdate=true;status="已载入本地缓存，播放就绪后更新每日壁纸";}
                            return;
                        }
                    }
                    if(needsBingRefresh(cached,daily,prefs.getString("background_bing_day",""),today())){
                        try{
                            JSONObject image=new JSONObject(new String(download(new URL("https://www.bing.com/HPImageArchive.aspx?format=js&idx=0&n=1&mkt=zh-CN"),256*1024),"UTF-8"))
                                    .getJSONArray("images").getJSONObject(0);
                            URL url=new URL("https://www.bing.com"+image.getString("urlbase")+"_1280x720.jpg");
                            if(!"https".equals(url.getProtocol())||!"www.bing.com".equals(url.getHost()))throw new IOException("Bing 图片地址无效");
                            Bitmap bitmap=decode(download(url,8*1024*1024));
                            try{synchronized(PersonalBackground.this){
                                if(closed||token!=generation)return;
                                save(file("bing"),bitmap);copyright=image.optString("copyright","");
                                prefs.edit().putString("background_bing_day",today()).putString("background_copyright",copyright).apply();
                            }}finally{bitmap.recycle();}
                        }catch(Exception error){if(!cached)throw error;if(token==generation&&!closed)status="更新失败，保留已缓存的 Bing 壁纸";return;}
                    }
                }
                Bitmap bitmap=decode(file(selected));
                if(token==generation&&!closed){status=appliedStatus();revision++;}
                apply(token,bitmap);
            }catch(Exception|OutOfMemoryError error){if(token==generation&&!closed)status="背景载入失败："+error.getMessage();}
            finally{ui.post(()->{if(!closed&&token==generation){imageLoading=false;completeStartupIfReady();}});}
        });
    }
    synchronized void upload(byte[] body) throws IOException {
        if(body==null||body.length==0)throw new IOException("请选择非空图片");
        Bitmap bitmap=decode(body);
        try{save(file("custom"),bitmap);}finally{bitmap.recycle();}
        select("custom");
    }
    synchronized void upload(File image) throws IOException {
        if(image==null||!image.isFile()||image.length()==0)throw new IOException("请选择非空图片");
        Bitmap bitmap=decode(image);
        try{save(file("custom"),bitmap);}finally{bitmap.recycle();}
        select("custom");
    }
    LocalControlServer.Resource preview() throws IOException {
        File image=selectedImage();if(!imageAvailable())throw new IOException("尚无背景图片");
        try(InputStream input=new FileInputStream(image)){return new LocalControlServer.Resource("image/jpeg",read(input,8*1024*1024));}
    }
    private void save(File target,Bitmap bitmap) throws IOException {
        if(!directory.isDirectory()&&!directory.mkdirs()&&!directory.isDirectory())throw new IOException("无法创建背景目录");
        File temporary=new File(target.getPath()+".tmp");
        try{
            try(OutputStream output=new FileOutputStream(temporary)){if(!bitmap.compress(Bitmap.CompressFormat.JPEG,88,output))throw new IOException("无法保存图片");}
            if(!temporary.renameTo(target))throw new IOException("无法更新背景图片");
        }finally{temporary.delete();}
    }
    private static Bitmap decode(File file) throws IOException {
        try {
            BitmapFactory.Options options=new BitmapFactory.Options();
            options.inJustDecodeBounds=true;BitmapFactory.decodeFile(file.getPath(),options);
            if(options.outWidth<=0||options.outHeight<=0)throw new IOException("请选择有效的图片");
            options.inSampleSize=1;
            while(Math.max(options.outWidth,options.outHeight)/options.inSampleSize>1280)options.inSampleSize*=2;
            options.inJustDecodeBounds=false;options.inPreferredConfig=Bitmap.Config.RGB_565;
            Bitmap bitmap=BitmapFactory.decodeFile(file.getPath(),options);
            if(bitmap==null)throw new IOException("图片解码失败");
            return bitmap;
        } catch(OutOfMemoryError error){throw new IOException("图片解码内存不足，请重试",error);}
    }
    private static Bitmap decode(byte[] body) throws IOException {
        BitmapFactory.Options o=new BitmapFactory.Options();o.inJustDecodeBounds=true;BitmapFactory.decodeByteArray(body,0,body.length,o);
        if(o.outWidth<=0||o.outHeight<=0)throw new IOException("请选择有效的图片");
        o.inSampleSize=1;while(Math.max(o.outWidth,o.outHeight)/o.inSampleSize>1280)o.inSampleSize*=2;
        o.inJustDecodeBounds=false;o.inPreferredConfig=Bitmap.Config.RGB_565;
        Bitmap value=BitmapFactory.decodeByteArray(body,0,body.length,o);if(value==null)throw new IOException("图片解码失败");return value;
    }
    private static byte[] download(URL url,int limit) throws IOException {
        HttpURLConnection c=NetworkClient.openBounded(url,8000);
        try{if(c.getResponseCode()!=200)throw new IOException("HTTP "+c.getResponseCode());try(InputStream input=c.getInputStream()){return read(input,limit);}}
        finally{c.disconnect();}
    }
    private static byte[] read(InputStream input,int limit) throws IOException {
        ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] buffer=new byte[8192];int n;
        while((n=input.read(buffer))!=-1){if(out.size()+n>limit)throw new IOException("背景文件过大");out.write(buffer,0,n);}return out.toByteArray();
    }
    private void apply(int token,Bitmap bitmap){
        ui.post(()->{
            if(closed||token!=generation||root==null){if(bitmap!=null)bitmap.recycle();return;}
            if(bitmap==null||startupFinished){
                if(bitmap!=null)bitmap.recycle();
                root.setVisibility(View.GONE);root.setBackgroundDrawable(null);
            }else{
                root.setBackgroundDrawable(new Wallpaper(bitmap,()->{
                    if(closed||startupFinished)return;
                    if(firstDrawAt<0)firstDrawAt=android.os.SystemClock.uptimeMillis();
                    ui.post(removeStartup);
                }));root.setVisibility(View.VISIBLE);
            }
            completeStartupIfReady();
            // Do not recycle the previous drawable while a hardware display list may still reference it.
        });
    }
    private String appliedStatus(){return startupFinished?"已保存，下次启动显示":"已应用";}
    void finishStartup(){
        playbackReady=true;
        completeStartupIfReady();
    }
    private void completeStartupIfReady(){
        if(closed||startupFinished||(!playbackReady&&!startupExpired)||root==null)return;
        ui.removeCallbacks(removeStartup);
        if(!startupExpired&&!"off".equals(mode)){
            if(root.getBackground()!=null){
                if(firstDrawAt<0)return;
                long remaining=minimumDisplayMs-(android.os.SystemClock.uptimeMillis()-firstDrawAt);
                if(remaining>0){ui.postDelayed(removeStartup,remaining);return;}
            }else if(imageLoading)return;
        }
        startupFinished=true;
        ui.removeCallbacks(expireStartup);
        if(root!=null){root.setVisibility(View.GONE);root.setBackgroundDrawable(null);}
        if(!"off".equals(mode)&&firstDrawAt>=0)status="启动背景已移除，下次启动显示";
        if(deferredBingUpdate){
            deferredBingUpdate=false;final int token=generation;
            ui.postDelayed(()->{if(!closed&&token==generation&&"bing".equals(mode)&&bingChoice.isEmpty()&&bingAutoUpdate)refresh(false);},1500L);
        }
    }
    @Override public synchronized void close(){closed=true;generation++;ui.removeCallbacks(removeStartup);ui.removeCallbacks(expireStartup);worker.shutdownNow();root=null;}
    private static final class Wallpaper extends Drawable {
        final Bitmap bitmap;final Paint paint=new Paint(Paint.FILTER_BITMAP_FLAG);final Rect source=new Rect();
        private Runnable firstDraw;
        Wallpaper(Bitmap bitmap,Runnable firstDraw){this.bitmap=bitmap;this.firstDraw=firstDraw;}
        @Override protected void onBoundsChange(Rect bounds){
            float scale=Math.max(bounds.width()/(float)bitmap.getWidth(),bounds.height()/(float)bitmap.getHeight());
            if(scale<=0)return;int w=Math.min(bitmap.getWidth(),Math.round(bounds.width()/scale)),h=Math.min(bitmap.getHeight(),Math.round(bounds.height()/scale));
            int x=(bitmap.getWidth()-w)/2,y=(bitmap.getHeight()-h)/2;source.set(x,y,x+w,y+h);
        }
        @Override public void draw(Canvas canvas){
            // The initial phone window may still be portrait while the activity's
            // landscape request is applied. Never publish a portrait-cropped frame.
            Rect bounds=getBounds();
            if(bounds.width()<=0||bounds.height()<=0||bounds.width()<bounds.height())return;
            canvas.drawBitmap(bitmap,source,bounds,paint);
            if(firstDraw!=null){Runnable drawn=firstDraw;firstDraw=null;drawn.run();}
        }
        @Override public void setAlpha(int alpha){paint.setAlpha(alpha);invalidateSelf();}
        @Override public void setColorFilter(ColorFilter filter){paint.setColorFilter(filter);invalidateSelf();}
        @Override public int getOpacity(){return PixelFormat.TRANSLUCENT;}
    }
}
