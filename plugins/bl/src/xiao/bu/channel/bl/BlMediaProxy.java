package xiao.bu.channel.bl;

import okhttp3.*;
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.*;

/** Token-bound loopback transport: TLS/CDN traffic always uses the host OkHttp instance. */
final class BlMediaProxy implements Closeable {
    private final BlApi api;
    private final ServerSocket server;
    private final String token=UUID.randomUUID().toString();
    private static final class Route {
        final String remote;
        final BlDiskBuffer disk;
        Route(String remote, BlDiskBuffer disk) { this.remote=remote;this.disk=disk; }
    }
    private final Map<String,Route> urls=Collections.synchronizedMap(new LinkedHashMap<String,Route>(128,.75f,true){protected boolean removeEldestEntry(Map.Entry<String,Route> entry){return size()>1024;}});
    private final Set<Socket> sockets=Collections.newSetFromMap(new ConcurrentHashMap<Socket,Boolean>());
    private final Set<Response> responses=Collections.newSetFromMap(new ConcurrentHashMap<Response,Boolean>());
    private final Map<Socket,Long> socketSessions=new ConcurrentHashMap<>();
    private final Map<Response,Long> responseSessions=new ConcurrentHashMap<>();
    private long nextSession;
    private final ThreadPoolExecutor workers=new ThreadPoolExecutor(0,4,15,TimeUnit.SECONDS,new SynchronousQueue<Runnable>(),new ThreadPoolExecutor.AbortPolicy());
    private volatile boolean closed;
    private volatile int epoch;
    BlMediaProxy(BlApi api)throws IOException{
        this.api=api;server=new ServerSocket(0,8,InetAddress.getByName("127.0.0.1"));
        Thread accept=new Thread(new Runnable(){public void run(){while(!closed)try{final Socket s=server.accept();sockets.add(s);try{workers.execute(new Runnable(){public void run(){serve(s);}});}catch(RejectedExecutionException full){sockets.remove(s);s.close();}}catch(IOException e){if(!closed)android.util.Log.w("BL","Media accept failed");}}},"bl-media-accept");accept.setDaemon(true);accept.start();
    }
    synchronized long begin(){return ++nextSession;}
    String url(String remote)throws IOException{return url(remote,0);}
    String url(String remote,long session)throws IOException{return url(remote,session,null);}
    synchronized String url(String remote,long session,BlDiskBuffer disk)throws IOException{
        HttpUrl parsed=HttpUrl.parse(remote);if(parsed==null||!(parsed.scheme().equals("https")||parsed.scheme().equals("http")))throw new IOException("无效播放地址");
        String key;try{key=session+"-"+BlApi.md5(remote);}catch(Exception e){throw new IOException(e);}
        urls.put(key,new Route(remote,disk));return "http://127.0.0.1:"+server.getLocalPort()+"/"+token+"/"+key;
    }
    void retire(long session){synchronized(urls){for(Iterator<String> i=urls.keySet().iterator();i.hasNext();)if(i.next().startsWith(session+"-"))i.remove();}for(Map.Entry<Response,Long> e:responseSessions.entrySet())if(e.getValue()==session)api.cancel(e.getKey());for(Map.Entry<Socket,Long> e:socketSessions.entrySet())if(e.getValue()==session)try{e.getKey().close();}catch(IOException ignored){}}
    void clear(){epoch++;urls.clear();for(Response r:responses)api.cancel(r);for(Socket s:sockets)try{s.close();}catch(IOException ignored){}}
    private void serve(Socket socket){
        Response response=null;final int requestEpoch=epoch;
        try{
            socket.setSoTimeout(20000);BufferedReader in=new BufferedReader(new InputStreamReader(socket.getInputStream(),"US-ASCII"));String start=in.readLine();if(start==null)return;
            String[] request=start.split(" ");if(request.length!=3||!(request[0].equals("GET")||request[0].equals("HEAD"))){error(socket,405);return;}
            String path=request[1];if(!path.startsWith("/"+token+"/")){error(socket,403);return;}String key=path.substring(token.length()+2);Route route=urls.get(key);if(route==null){error(socket,404);return;}String remote=route.remote;long session=Long.parseLong(key.substring(0,key.indexOf('-')));socketSessions.put(socket,session);
            Request.Builder builder=api.mediaRequest(remote);String line;int headerBytes=start.length();
            while((line=in.readLine())!=null&&!line.isEmpty()){headerBytes+=line.length();if(headerBytes>8192){error(socket,431);return;}int colon=line.indexOf(':');if(colon>0){String name=line.substring(0,colon);if(name.equalsIgnoreCase("Range")||name.equalsIgnoreCase("If-Range"))builder.header(name,line.substring(colon+1).trim());}}
            boolean head=request[0].equals("HEAD");
            if(head)builder.head();
            Request upstream=builder.build();
            BlMediaRange asked=route.disk!=null&&!head&&upstream.header("If-Range")==null
                    ?BlMediaRange.requested(upstream.header("Range")):null;
            // Some servers compress regular requests. Cached ranges use the unencoded entity.
            if(asked!=null)upstream=upstream.newBuilder().header("Accept-Encoding","identity").build();
            if(asked!=null&&replay(socket,route,upstream,asked,key,session,requestEpoch))return;
            response=open(upstream,session);
            if(!active(key,requestEpoch))return;
            ResponseBody body=response.body();if(body==null){error(socket,502);return;}
            String type=response.header("Content-Type","application/octet-stream");boolean playlist=type.toLowerCase(Locale.US).contains("mpegurl")||HttpUrl.parse(remote).encodedPath().endsWith(".m3u8");
            byte[] rewritten=null;if(playlist&&response.isSuccessful()&&!head)rewritten=playlist(new String(BlApi.read(body.byteStream(),2*1024*1024),"UTF-8"),remote,session).getBytes("UTF-8");
            OutputStream out=socket.getOutputStream();StringBuilder headers=new StringBuilder("HTTP/1.1 "+response.code()+" "+(response.isSuccessful()?"OK":"Upstream")+"\r\nConnection: close\r\nCache-Control: no-store\r\nContent-Type: "+(rewritten!=null?"application/vnd.apple.mpegurl":type)+"\r\n");
            long length=rewritten==null?body.contentLength():rewritten.length;if(head)try{length=Long.parseLong(response.header("Content-Length","-1"));}catch(NumberFormatException ignored){length=-1;}if(length>=0)headers.append("Content-Length: ").append(length).append("\r\n");
            for(String headerName:new String[]{"Content-Range","Accept-Ranges"}){String value=response.header(headerName);if(value!=null&&rewritten==null)headers.append(headerName).append(": ").append(value).append("\r\n");}
            out.write(headers.append("\r\n").toString().getBytes("US-ASCII"));
            if(!head){
                if(rewritten!=null)out.write(rewritten);
                else{
                    BlMediaRange range=asked==null||playlist?null:mp4Range(response,asked);
                    BlDiskBuffer.Resource resource=range==null?null:route.disk.observe(remote,response,range);
                    copy(body,out,new byte[32768],route.disk,resource,range==null?0:range.start,range==null?-1:range.end-range.start+1);
                }
            }
            out.flush();
        }catch(Exception ignored){/* Seeking, leaving a screen and retrying close these sockets intentionally. */}
        finally{if(response!=null)release(response);socketSessions.remove(socket);sockets.remove(socket);try{socket.close();}catch(IOException ignored){}}
    }

    private boolean active(String key,int requestEpoch){
        return !closed&&requestEpoch==epoch&&urls.containsKey(key);
    }

    private Response open(Request request,long session)throws IOException{
        Response response=api.execute(request);
        responses.add(response);
        responseSessions.put(response,session);
        return response;
    }

    private void release(Response response){
        responseSessions.remove(response);
        responses.remove(response);
        response.close();
    }

    private static BlMediaRange mp4Range(Response response,BlMediaRange asked){
        if(response.code()!=206||response.body()==null)return null;
        MediaType type=response.body().contentType();
        if(type==null||!type.subtype().equals("mp4")&&!type.subtype().equals("octet-stream"))return null;
        if(type.subtype().equals("octet-stream")&&!response.request().url().encodedPath().toLowerCase(Locale.US).endsWith(".mp4"))return null;
        try{
            BlMediaRange range=BlMediaRange.received(response);
            return range.start==asked.start&&range.end<=asked.end&&range.cacheable(response)?range:null;
        }catch(IOException invalid){return null;}
    }

    /** Return the cached prefix, then forward the rest of the requested range from the origin. */
    private boolean replay(Socket socket,Route route,Request request,BlMediaRange asked,String key,long session,int requestEpoch)throws IOException{
        BlDiskBuffer.Resource resource=route.disk.find(route.remote,asked.start);
        if(resource==null)return false;
        long end=Math.min(asked.end,resource.length-1),position=asked.start;
        byte[] buffer=new byte[32768];
        int count=route.disk.read(resource,position,buffer,0,(int)Math.min(buffer.length,end-position+1));
        if(count==0)return false;
        OutputStream out=socket.getOutputStream();
        String head="HTTP/1.1 206 OK\r\nConnection: close\r\nCache-Control: no-store\r\nAccept-Ranges: bytes\r\n"
                +"Content-Type: "+resource.type+"\r\nContent-Length: "+(end-position+1)
                +"\r\nContent-Range: bytes "+position+"-"+end+"/"+resource.length+"\r\n\r\n";
        out.write(head.getBytes("US-ASCII"));
        while(active(key,requestEpoch)){
            if(count>0){
                out.write(buffer,0,count);
                position+=count;
            }else{
                String header="bytes="+position+"-"+end;
                Response response=open(request.newBuilder().header("Range",header).build(),session);
                try{
                    if(!active(key,requestEpoch))return true;
                    BlMediaRange range=mp4Range(response,BlMediaRange.requested(header));
                    if(range==null||!resource.matches(response,range)){
                        route.disk.invalidate(resource);
                        throw new IOException("MP4 缓冲片段对应的媒体已变化");
                    }
                    copy(response.body(),out,buffer,route.disk,resource,position,range.end-position+1);
                    position=range.end+1;
                }finally{release(response);}
            }
            if(position>end)break;
            count=route.disk.read(resource,position,buffer,0,(int)Math.min(buffer.length,end-position+1));
        }
        out.flush();
        return true;
    }

    private void copy(ResponseBody body,OutputStream out,byte[] buffer,BlDiskBuffer disk,
                      BlDiskBuffer.Resource resource,long position,long remaining)throws IOException{
        InputStream stream=body.byteStream();
        while(!closed&&remaining!=0){
            int count=stream.read(buffer,0,remaining<0?buffer.length:(int)Math.min(buffer.length,remaining));
            if(count<0){
                if(remaining>0)throw new EOFException("MP4 分片读取未完成");
                return;
            }
            out.write(buffer,0,count);
            if(resource!=null)disk.offer(resource,position,buffer,0,count);
            position+=count;
            if(remaining>0)remaining-=count;
        }
    }
    private String playlist(String text,String base)throws IOException{return playlist(text,base,0);}
    private String playlist(String text,String base,long session)throws IOException{
        StringBuilder out=new StringBuilder();for(String line:text.split("\r?\n")){
            if(!line.isEmpty()&&!line.startsWith("#"))line=url(resolve(base,line.trim()),session);
            else if(line.startsWith("#")){Matcher m=Pattern.compile("URI=\"([^\"]+)\"").matcher(line);StringBuffer b=new StringBuffer();while(m.find())m.appendReplacement(b,Matcher.quoteReplacement("URI=\""+url(resolve(base,m.group(1)),session)+"\""));m.appendTail(b);line=b.toString();}
            out.append(line).append('\n');
        }return out.toString();
    }
    private static String resolve(String base,String next)throws IOException{try{return new URI(base).resolve(next).toString();}catch(URISyntaxException e){throw new IOException(e);}}
    private static void error(Socket s,int code)throws IOException{s.getOutputStream().write(("HTTP/1.1 "+code+" Error\r\nContent-Length: 0\r\nConnection: close\r\n\r\n").getBytes("US-ASCII"));}
    public void close(){closed=true;try{server.close();}catch(IOException ignored){}clear();workers.shutdownNow();}
}
