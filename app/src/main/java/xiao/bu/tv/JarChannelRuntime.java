package xiao.bu.tv;

import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.FrameLayout;
import dalvik.system.DexClassLoader;
import java.io.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.*;
import org.json.JSONObject;
import xiao.bu.tv.jar.ChannelPlugin;

/** Executable plugins are trusted code, pinned by the playlist's SHA-256. */
final class JarChannelRuntime {
    interface Callback extends ChannelPlugin.Host { boolean current(); }
    private final Context context;
    private final FrameLayout root;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private ChannelPlugin plugin;
    private View view;
    private int generation;
    private boolean destroyed;
    private Callback callback;
    private android.webkit.WebView browser;
    private String resolution="720p";
    private float pageScale=1;
    private final ChannelFilePicker filePicker=new ChannelFilePicker();
    private DesktopWebProfile browserProfile;
    private String nativeUserAgent;
    JarChannelRuntime(Context context, FrameLayout root) { this.context=context;this.root=root; }
    void open(String source, final Callback callback) {
        close(); this.callback=callback; final int token=generation;
        worker.execute(()->{
            try {
                JarChannelSource spec=new JarChannelSource(source);
                File dir=new File(context.getFilesDir(),"channel-jars");
                if(!dir.isDirectory()&&!dir.mkdirs())throw new IOException("无法创建插件目录");
                File jar=new File(dir,spec.sha+".jar");
                if(jar.exists()) {
                    try(InputStream in=new FileInputStream(jar)) {
                        if(!spec.sha.equals(sha(RadioUiCatalog.read(in,32*1024*1024)))) {
                            if(!jar.delete())throw new IOException("无法清理损坏插件");
                        }
                    }
                }
                if(!jar.exists()) {
                    byte[] bytes=RadioUiCatalog.download(spec.url,32*1024*1024,25000);
                    if(!spec.sha.equals(sha(bytes)))throw new IOException("JAR 插件校验失败");
                    File temp=File.createTempFile("install-",".tmp",dir);
                    try {
                        try(FileOutputStream out=new FileOutputStream(temp)){out.write(bytes);out.getFD().sync();}
                        if(!temp.setReadOnly()||!temp.renameTo(jar))throw new IOException("无法保存插件");
                    } finally {temp.delete();}
                }
                JSONObject info;
                try(ZipFile zip=new ZipFile(jar)) {
                    ZipEntry metadata=zip.getEntry("channel-plugin.json");
                    if(metadata==null||zip.getEntry("classes.dex")==null)throw new IOException("不是频道插件");
                    try(InputStream in=zip.getInputStream(metadata)) {
                        info=new JSONObject(new String(RadioUiCatalog.read(in,4096),"UTF-8"));
                    }
                }
                String entry=info.getString("entry");
                if(info.getInt("api")!=1 || info.getInt("minSdk")<14
                        || info.getInt("minSdk")>Build.VERSION.SDK_INT || !entry.startsWith("xiao.bu.channel."))
                    throw new IOException("频道插件版本不兼容");
                if(!jar.setReadOnly())throw new IOException("插件必须只读");
                File dex=new File(dir,"dex");if(!dex.isDirectory()&&!dex.mkdirs())throw new IOException("无法创建 DEX 目录");
                ClassLoader loader=new DexClassLoader(jar.getAbsolutePath(),dex.getAbsolutePath(),null,ChannelPlugin.class.getClassLoader());
                Class<? extends ChannelPlugin> type=loader.loadClass(entry).asSubclass(ChannelPlugin.class);
                main.post(()->{
                    if(destroyed||token!=generation||!callback.current())return;
                    ChannelPlugin incoming=null;
                    try {
                        incoming=type.getConstructor().newInstance();
                        View content=incoming.create(context,loader,spec.config,new ChannelPlugin.NativeHost(){
                            public okhttp3.OkHttpClient httpClient(){return NetworkClient.sharedClient();}
                            public void chooseDirectory(android.webkit.ValueCallback<android.net.Uri[]> selected){
                                if(token!=generation||!callbackCurrent()){selected.onReceiveValue(null);return;}
                                if(context instanceof android.app.Activity)filePicker.openDirectory((android.app.Activity)context,selected);
                                else selected.onReceiveValue(null);
                            }
                            public void chooseFiles(android.webkit.ValueCallback<android.net.Uri[]> callback,String accept,boolean multiple){
                                if(token!=generation||!callbackCurrent()){callback.onReceiveValue(null);return;}
                                if(context instanceof android.app.Activity)filePicker.open((android.app.Activity)context,callback,accept,multiple);
                                else callback.onReceiveValue(null);
                            }
                            private boolean callbackCurrent(){return callback.current();}
                            public void play(String url){if(token==generation&&callback.current())callback.play(url);}
                            public void status(String message){if(token==generation&&callback.current())callback.status(message);}
                            public void configureBrowser(android.webkit.WebView web){
                                if(token!=generation||!callback.current())return;
                                if(browser!=web){browser=web;nativeUserAgent=web.getSettings().getUserAgentString();
                                    if("ntv-channel-media".equals(web.getTag()))browserProfile=new DesktopWebProfile(web);
                                    web.addOnLayoutChangeListener((v,l,t,r,b,ol,ot,or,ob)->{if(browser==web)applyBrowser();});}
                                applyBrowser();
                            }
                        });
                        if(content==null||content.getParent()!=null)throw new IOException("插件界面无效");
                        plugin=incoming;view=content;
                        // Above media/artwork/browser, below subtitle, HUD and channel menus.
                        root.addView(content,Math.min(3,root.getChildCount()),new FrameLayout.LayoutParams(-1,-1));
                    } catch(Exception|LinkageError e) {
                        if(incoming!=null)try{incoming.dispose();}catch(Throwable ignored){}
                        callback.status("频道插件启动失败: "+e.getMessage());
                    }
                });
            } catch(Exception|LinkageError e) {
                main.post(()->{if(!destroyed&&token==generation&&callback.current())callback.status("频道插件加载失败: "+e.getMessage());});
            }
        });
    }
    void update(long position,boolean playing) {
        if(callback!=null&&!callback.current()){close();return;}
        if(plugin!=null)try{plugin.update(position,playing);}catch(Throwable error){xiao.bu.tv.AppLog.w("JarChannel","Plugin update",error);close();}
    }
    void close() {
        generation++;
        callback=null;
        if(browserProfile!=null){browserProfile.dispose();browserProfile=null;}
        browser=null;
        filePicker.cancel();
        if(view!=null){root.removeView(view);view=null;}
        if(plugin!=null){try{plugin.dispose();}catch(Throwable error){xiao.bu.tv.AppLog.w("JarChannel","Plugin dispose",error);}plugin=null;}
    }
    void destroy(){destroyed=true;close();worker.shutdownNow();}
    boolean fileResult(int request,int result,android.content.Intent data){return filePicker.result(request,result,data);}
    void configure(String resolution,float scale){this.resolution=resolution;pageScale=scale;applyBrowser();}
    private void applyBrowser(){
        if(browser==null)return;
        if(browserProfile!=null){
            android.content.SharedPreferences prefs=context.getSharedPreferences(MainActivity.PREFERENCES,0);
            String mode=prefs.getString("web_view_user_agent","windows"),version=prefs.getString("web_view_browser_version","native");
            String ua="native".equals(mode)?nativeUserAgent:"ipad".equals(mode)?DesktopWebProfile.ipadUserAgent(nativeUserAgent,version):DesktopWebProfile.userAgent(mode,nativeUserAgent,version);
            if(!ua.equals(browser.getSettings().getUserAgentString()))browser.getSettings().setUserAgentString(ua);
            int width=JarBrowserViewport.width(resolution);
            int height=browser.getWidth()>0?Math.max(1,Math.round(width*browser.getHeight()/(float)browser.getWidth())):width*9/16;
            browserProfile.update(mode,nativeUserAgent,version,width,height,pageScale);
            browserProfile.applyToCurrentDocument();
        }
        JarBrowserViewport.apply(browser,resolution,pageScale);
    }
    boolean visible(){return view!=null&&view.isShown();}
    boolean goBackIfPossible(){
        if(visible()&&plugin instanceof ChannelPlugin.NativePage) {
            try { if(((ChannelPlugin.NativePage)plugin).onBack())return true; }
            catch(RuntimeException|LinkageError error){failNativePage("back",error);return true;}
        }
        if(!visible()||browser==null||!browser.canGoBack())return false;
        browser.goBack();return true;
    }
    boolean dispatchMedia(android.view.KeyEvent event){
        if(visible()&&plugin instanceof ChannelPlugin.NativePage){
            try{return view.dispatchKeyEvent(event);}
            catch(RuntimeException|LinkageError error){failNativePage("key",error);return true;}
        }
        if(!visible()||browser==null||!"ntv-channel-media".equals(browser.getTag()))return false;
        String action;
        switch(event.getKeyCode()){
            case android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:action="toggle";break;
            case android.view.KeyEvent.KEYCODE_MEDIA_PLAY:action="play";break;
            case android.view.KeyEvent.KEYCODE_MEDIA_PAUSE:action="pause";break;
            case android.view.KeyEvent.KEYCODE_MEDIA_STOP:action="stop";break;
            case android.view.KeyEvent.KEYCODE_MEDIA_NEXT:action="nexttrack";break;
            case android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS:action="previoustrack";break;
            default:return false;
        }
        if(event.getAction()==android.view.KeyEvent.ACTION_DOWN&&event.getRepeatCount()==0)
            browser.evaluateJavascript("window.__NTV_FOLIA_FULL__&&window.__NTV_FOLIA_FULL__.media('"+action+"')",null);
        return true;
    }
    // Dispatch real pointer sequences to the plugin, preserving native WebView hit testing.
    boolean dispatchPointer(android.view.MotionEvent source, boolean rootCoordinates) {
        if (!visible()) return false;
        android.view.MotionEvent event = android.view.MotionEvent.obtain(source);
        try {
            if (!rootCoordinates) {
                int[] location = new int[2]; root.getLocationOnScreen(location);
                event.offsetLocation(source.getRawX()-source.getX()-location[0],
                        source.getRawY()-source.getY()-location[1]);
            }
            event.offsetLocation(root.getScrollX()-view.getLeft(), root.getScrollY()-view.getTop());
            android.graphics.Matrix inverse = new android.graphics.Matrix();
            if (view.getMatrix().invert(inverse)) event.transform(inverse);
            int action = event.getActionMasked();
            if (action == android.view.MotionEvent.ACTION_DOWN) view.requestFocus();
            if (action == android.view.MotionEvent.ACTION_DOWN || action == android.view.MotionEvent.ACTION_UP
                    || action == android.view.MotionEvent.ACTION_MOVE || action == android.view.MotionEvent.ACTION_CANCEL)
                view.dispatchTouchEvent(event);
            else view.dispatchGenericMotionEvent(event);
            return true;
        } catch(RuntimeException|LinkageError error) {
            failNativePage("pointer",error);return true;
        } finally { event.recycle(); }
    }
    boolean dispatchKey(android.view.KeyEvent event){if(!visible())return false;if(browser!=null){browser.requestFocus();return browser.dispatchKeyEvent(event);}return view.dispatchKeyEvent(event);}
    void setActive(boolean active){
        if(plugin instanceof ChannelPlugin.NativePage){
            try{((ChannelPlugin.NativePage)plugin).setActive(active);}
            catch(RuntimeException|LinkageError error){failNativePage("active",error);return;}
        }
        if(browser!=null){if(active)browser.onResume();else{update(0,false);browser.onPause();}}
    }
    private void failNativePage(String action,Throwable error){
        xiao.bu.tv.AppLog.w("JarChannel","Plugin "+action,error);
        Callback owner=callback;
        close();
        if(owner!=null&&owner.current())owner.status("频道页面运行失败，请重新进入");
    }
    private static String sha(byte[] bytes)throws Exception {
        StringBuilder result=new StringBuilder();
        for(byte b:java.security.MessageDigest.getInstance("SHA-256").digest(bytes))
            result.append(String.format(java.util.Locale.US,"%02x",b&255));
        return result.toString();
    }
}
