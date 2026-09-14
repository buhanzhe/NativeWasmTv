package xiao.bu.tv;

import android.os.*;
import java.io.*;
import java.net.*;
import java.util.UUID;
import java.util.concurrent.*;
import org.json.JSONObject;

/** One bounded file session. Control stays on HTTP; all media travels over RTSP. */
final class MultimediaCastManager implements Closeable {
    final MainActivity host;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private volatile Session sending;
    private Runnable pendingLocal;
    private volatile boolean transitioning;
    private volatile String message = "选择图片或视频，由本机转码后投送";
    private volatile String receiverToken = "";
    private volatile long receiverBeat;
    private volatile boolean destroyed;
    MultimediaCastManager(MainActivity host) {
        this.host=host;
        File[] stale=new File(host.getCacheDir(),"multimedia-cast").listFiles();
        if(stale!=null)for(File file:stale)if(file.isFile()&&file.getName().startsWith("cast-"))file.delete();
    }
    boolean active() { return sending != null || transitioning || !receiverToken.isEmpty(); }
    private <T> T onUi(Callable<T> action) throws Exception {
        if (Looper.myLooper()==Looper.getMainLooper()) return action.call();
        FutureTask<T> task=new FutureTask<>(action); ui.post(task);
        return task.get(8,TimeUnit.SECONDS);
    }
    synchronized String upload(String address, String name, InputStream input, int length, boolean preserveAudio) throws Exception {
        if (Build.VERSION.SDK_INT<18) throw new IOException("多媒体转码需要 Android 4.3 或以上的手机");
        if (destroyed || active()) throw new IOException("请先结束当前多媒体投送");
        if(length<=0 || length>1024*1024*1024) throw new IOException("请选择不超过 1 GB 的图片或视频");
        final String target=host.multimediaTarget(address);
        if(target.isEmpty()) throw new IOException("请填写电视地址");
        onUi(() -> { host.checkMultimediaStart(); return null; });
        File folder=new File(host.getCacheDir(),"multimedia-cast"); folder.mkdirs();
        if(folder.getUsableSpace()<length+32L*1024*1024) throw new IOException("手机可用存储空间不足");
        File file=File.createTempFile("cast-",".media",folder);
        Session session=new Session(target,new MultimediaInput(host,file),name,preserveAudio); sending=session; message="正在读取文件";
        try {
            try(FileOutputStream out=new FileOutputStream(file)) {
                byte[] buffer=new byte[64*1024]; int remaining=length;
                while(remaining>0) {
                    if(session.cancelled) throw new IOException("已停止投送");
                    int count=input.read(buffer,0,Math.min(buffer.length,remaining));
                    if(count<0) throw new EOFException("文件上传不完整"); out.write(buffer,0,count); remaining-=count;
                }
            }
            session.thread=new Thread(() -> run(session),"multimedia-session"); session.thread.start();
            return state().toString();
        } catch(Exception error) { sending=null; file.delete(); message=error.getMessage(); throw error; }
    }
    synchronized void openLocal(String address, android.net.Uri uri, String name, boolean preserveAudio) throws Exception {
        if(Build.VERSION.SDK_INT<18)throw new IOException("多媒体转码需要 Android 4.3 或以上的手机");
        if(destroyed || !receiverToken.isEmpty())throw new IOException("当前设备正在接收多媒体");
        if(sending!=null || transitioning) {
            pendingLocal=() -> {try {openLocal(address,uri,name,preserveAudio);} catch(Exception e){message=e.getMessage();}};
            if(sending!=null)sending.cancelled=true;message="正在切换投送内容";return;
        }
        String target=host.multimediaTarget(address);
        if(target.isEmpty())throw new IOException("请填写电视地址");
        host.checkMultimediaStart();
        Session session=new Session(target,new MultimediaInput(host,uri),name,preserveAudio);
        sending=session;message="正在读取本地文件";
        session.thread=new Thread(() -> run(session),"multimedia-session");session.thread.start();
    }
    private void run(Session s) {
        CastNetworkLease wifi=new CastNetworkLease(); PowerManager.WakeLock cpu=null;
        boolean suspended=false;
        try {
            message="正在连接电视";
            JSONObject hello=request(s.target,new JSONObject().put("action","hello"));
            if(hello.optInt("protocol")!=1 || !host.multimediaTakeoverSession().isEmpty() && !hello.optBoolean("takeoverMedia")) throw new IOException("请先更新电视端应用，以支持保留网页切换多媒体");
            CastConfig config=onUi(() -> { host.checkMultimediaStart(); host.suspendForMultimedia(false); return host.multimediaConfig(); });
            suspended=true; wifi.acquire(host);
            cpu=((PowerManager)host.getSystemService(android.content.Context.POWER_SERVICE)).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"nTv:multimedia");
            cpu.acquire();
            if(s.cancelled) return;
            message="正在启动 H.264 / AAC 转码";
            s.stream=new MultimediaStream(s.file,config.width,config.height,config.fps,config.bitrate,s.preserveAudio,error -> {s.finished=true;s.error=error;});
            s.stream.start(); long deadline=SystemClock.elapsedRealtime()+15000;
            while(!s.cancelled&&!s.stream.ready()) {
                if(!s.stream.error().isEmpty()) throw new IOException(s.stream.error());
                if(SystemClock.elapsedRealtime()>deadline) throw new IOException("手机编码器启动超时"); Thread.sleep(25);
            }
            if(s.cancelled)return;
            URL receiverUrl=new URL(s.target); String local;
            try(Socket route=new Socket()) {
                route.connect(new InetSocketAddress(receiverUrl.getHost(),receiverUrl.getPort()<0?80:receiverUrl.getPort()),1500);
                local=route.getLocalAddress().getHostAddress();
            }
            if(local.contains(":")) local="["+local+"]";
            request(s.target,new JSONObject().put("action","start").put("token",s.token)
                    .put("url","rtsp://"+local+":"+s.stream.server.port()+"/cast")
                    .put("takeoverSession",host.multimediaTakeoverSession()).put("transport",config.transport).put("image",s.stream.isImage()).put("title",s.name));
            s.startedRemote = true;
            message="正在投送 · H.264 / RTSP"; long last=SystemClock.elapsedRealtime();
            while(!s.cancelled&&!s.finished) {
                try {
                    JSONObject beat=request(s.target,new JSONObject().put("action","heartbeat").put("token",s.token));
                    if(!beat.optBoolean("active")) {s.cancelled=true;break;}
                    last=SystemClock.elapsedRealtime();
                } catch(Exception failure) {if(SystemClock.elapsedRealtime()-last>=3000)throw new IOException("电视连接已断开");}
                Thread.sleep(700);
            }
            if(!s.error.isEmpty()) throw new IOException(s.error);
            if(s.finished&&!s.cancelled)Thread.sleep(250);
            message=s.cancelled?"已结束投送":"播放结束";
        } catch(Exception failure) { message=s.cancelled?"已结束投送":failure.getMessage(); }
        finally {
            transitioning=true;
            final long position = s.stream == null ? 0 : s.stream.positionMs();
            final boolean image = s.stream != null && s.stream.isImage();
            if(s.stream!=null)s.stream.close();
            wifi.release(); if(cpu!=null&&cpu.isHeld())cpu.release();
            if(suspended) {
                try {onUi(() -> {
                if(sending==s)sending=null;
                if(destroyed || sending != null) {s.file.delete();return null;}
                if(host.restoreMultimediaWeb()) {s.file.delete();}
                else if(s.startedRemote && s.resumeLocal) host.resumeSentMultimedia(s.file,s.name,image,position);
                else {s.file.delete();if(s.resumeLocal)host.restoreAfterMultimedia();}
                return null;
                });}catch(Exception ignored){}
            } else {if(sending==s)sending=null;s.file.delete();}
            // Resume locally before waiting on an unreachable TV's final stop request.
            try {request(s.target,new JSONObject().put("action","stop").put("token",s.token));} catch(Exception ignored) {}
            ui.post(() -> {transitioning=false;Runnable next=pendingLocal;pendingLocal=null;if(!destroyed && next!=null)next.run();});
        }
    }
    JSONObject state() throws Exception {
        Session s=sending;
        return new JSONObject().put("ok",true).put("active",s!=null || transitioning).put("message",message)
                .put("positionMs",s!=null&&s.stream!=null?s.stream.positionMs():0);
    }
    String control(JSONObject request) throws Exception {
        String action=request.optString("action");
        if("state".equals(action))return state().toString();
        if("stopSending".equals(action)) {return onUi(() -> {returnToPrevious();return state().toString();});}
        return onUi(() -> {
            JSONObject result=new JSONObject().put("ok",true).put("protocol",1);
            if("hello".equals(action))return result.put("takeoverMedia",true).toString();
            String token=request.optString("token");
            if(token.isEmpty())throw new IOException("缺少会话标识");
            if("start".equals(action)) {
                host.checkMultimediaReceiver(request.optString("takeoverSession"));
                if(active())throw new IOException("电视正在投送，请先结束当前会话");
                URI stream=new URI(request.optString("url"));
                if(!"rtsp".equals(stream.getScheme())||stream.getHost()==null||!"/cast".equals(stream.getPath()))throw new IOException("无效的 RTSP 地址");
                receiverToken=token;receiverBeat=SystemClock.elapsedRealtime();
                host.suspendForMultimedia(true);
                try {host.startMultimediaReceiver(stream.toString(),request.optString("transport","tcp"),request.optString("title","多媒体投屏"));ui.post(watchdog);}
                catch(Exception e){endReceiver();throw e;}
            } else if(token.equals(receiverToken)) {
                if("heartbeat".equals(action))receiverBeat=SystemClock.elapsedRealtime();
                else if("stop".equals(action))endReceiver();
            }
            return result.put("active",token.equals(receiverToken)).toString();
        });
    }
    private final Runnable watchdog=new Runnable(){public void run(){if(receiverToken.isEmpty())return;if(SystemClock.elapsedRealtime()-receiverBeat>=3000)endReceiver();else ui.postDelayed(this,250);}};
    private void endReceiver() {
        if(receiverToken.isEmpty())return;receiverToken="";ui.removeCallbacks(watchdog);
        if(!destroyed)host.restoreAfterMultimedia();
    }
    void returnToPrevious() {
        pendingLocal=null;
        Session s=sending;
        if(s!=null){s.cancelled=true;return;}
        endReceiver();
    }
    void stop(){pendingLocal=null;Session s=sending;if(s!=null){s.resumeLocal=false;s.cancelled=true;if(s.stream!=null)s.stream.close();}endReceiver();}
    @Override public void close(){destroyed=true;stop();}
    private static JSONObject request(String base,JSONObject data)throws Exception {
        HttpURLConnection c=NetworkClient.open(new URL(base+"/api/multimedia/control"));
        try {
            c.setConnectTimeout(900);c.setReadTimeout(1100);c.setRequestMethod("POST");c.setDoOutput(true);
            byte[] body=data.toString().getBytes("UTF-8");c.setFixedLengthStreamingMode(body.length);c.setRequestProperty("Content-Type","application/json");
            try(OutputStream out=c.getOutputStream()){out.write(body);}
            int code=c.getResponseCode();if(code==404)throw new IOException("电视端不支持多媒体投送，请更新电视端应用");
            InputStream stream=code<400?c.getInputStream():c.getErrorStream();
            ByteArrayOutputStream result=new ByteArrayOutputStream();byte[] buffer=new byte[2048];int n;
            if(stream==null)throw new IOException("电视连接失败："+code);
            try(InputStream in=stream){while((n=in.read(buffer))!=-1){if(result.size()+n>65536)throw new IOException("电视响应过大");result.write(buffer,0,n);}}
            JSONObject json=new JSONObject(result.toString("UTF-8"));if(code>=400||!json.optBoolean("ok"))throw new IOException(json.optString("error","电视连接失败"));return json;
        } finally {c.disconnect();}
    }
    private static final class Session {
        final String target,token=UUID.randomUUID().toString(),name;final MultimediaInput file;
        final boolean preserveAudio;volatile boolean cancelled,finished;volatile boolean startedRemote,resumeLocal=true;volatile String error="";volatile MultimediaStream stream;Thread thread;
        Session(String target,MultimediaInput file,String name,boolean preserveAudio){this.preserveAudio=preserveAudio;this.target=target;this.file=file;this.name=name;}
    }
}
