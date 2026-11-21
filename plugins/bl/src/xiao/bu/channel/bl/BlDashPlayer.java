package xiao.bu.channel.bl;

import android.content.Context;
import android.net.Uri;
import android.view.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import okhttp3.*;
import org.json.JSONArray;
import com.google.android.exoplayer2.*;
import com.google.android.exoplayer2.source.*;
import com.google.android.exoplayer2.trackselection.DefaultTrackSelector;
import com.google.android.exoplayer2.trackselection.TrackSelectionArray;
import com.google.android.exoplayer2.upstream.*;
import com.google.android.exoplayer2.video.VideoListener;
import tv.danmaku.ijk.media.player.AbstractMediaPlayer;
import tv.danmaku.ijk.media.player.MediaInfo;
import tv.danmaku.ijk.media.player.misc.ITrackInfo;

/** API-16+ DASH merge; neither the UI module nor another HTTP client is packaged. */
final class BlDashPlayer extends AbstractMediaPlayer {
    private static final int BUFFER_TARGET_BYTES=8*1024*1024;
    private static final int LOADING_CHECK_BYTES=256*1024;
    private static final int MAX_SKIP_WITHOUT_RANGE=1024*1024;
    private final DefaultAllocator allocator=new DefaultAllocator(true,64*1024);
    private final SimpleExoPlayer player;private final BlApi api;private final String audio;private String source="";private boolean notified,released;private int width,height;private Throwable lastFailure;
    private final BlAudioRenderers audioRenderers;
    private BlStreams.Track failureTrack=BlStreams.Track.UNKNOWN;
    private final Set<Load> loads=Collections.newSetFromMap(new ConcurrentHashMap<Load,Boolean>());
    private final AtomicBoolean cancelled=new AtomicBoolean();
    private final Map<String,String> preferred=new ConcurrentHashMap<>();
    private final List<String> videoAlternatives,audioAlternatives=new ArrayList<>();
    BlDashPlayer(Context context,BlApi api,String audio,List<String> videoAlternatives,JSONArray audioBackups,boolean allowPassthrough){this.api=api;this.audio=audio;this.videoAlternatives=new ArrayList<>(videoAlternatives);for(int i=0;i<Math.min(8,audioBackups.length());i++){String url=audioBackups.optString(i);if(!url.isEmpty()&&!audioAlternatives.contains(url))audioAlternatives.add(url);}
        DefaultLoadControl load=new DefaultLoadControl(allocator,2000,10000,500,1000,BUFFER_TARGET_BYTES,false);
        audioRenderers=new BlAudioRenderers(context,allowPassthrough);
        player=ExoPlayerFactory.newSimpleInstance(context,audioRenderers,new DefaultTrackSelector(),load);
        player.addListener(new Player.DefaultEventListener(){
            public void onPlayerStateChanged(boolean ready,int state){
                if(released)return;
                if(state==Player.STATE_READY&&!notified){
                    notified=true;
                    // Exo can enter READY with one renderer disabled for an unsupported track.
                    boolean video=hasTrack(C.TRACK_TYPE_VIDEO),sound=audio.isEmpty()||hasTrack(C.TRACK_TYPE_AUDIO);
                    if(!video||!sound){
                        failureTrack=video?BlStreams.Track.AUDIO:BlStreams.Track.VIDEO;
                        lastFailure=new IOException("设备无法解码所选 DASH 音视频轨");
                        notifyOnError(-1004,-1010);
                        return;
                    }
                    notifyOnPrepared();
                }
                if(state==Player.STATE_ENDED)notifyOnCompletion();
            }
            public void onSeekProcessed(){
                // A seek outside the buffer interrupts the old loader. A blocking socket read
                // must also have its OkHttp call cancelled before Exo can load the new range.
                for(Load load:loads)if(load.thread.isInterrupted())load.cancel();
            }
            public void onPlayerError(ExoPlaybackException error){
                if(released)return;
                lastFailure=error;
                if(error.type==ExoPlaybackException.TYPE_RENDERER&&error.rendererIndex>=0&&error.rendererIndex<player.getRendererCount()){
                    int type=player.getRendererType(error.rendererIndex);
                    failureTrack=type==C.TRACK_TYPE_AUDIO?BlStreams.Track.AUDIO:type==C.TRACK_TYPE_VIDEO?BlStreams.Track.VIDEO:BlStreams.Track.UNKNOWN;
                }else if(error.type==ExoPlaybackException.TYPE_SOURCE&&error.getSourceException() instanceof TrackReadFailure){
                    failureTrack=((TrackReadFailure)error.getSourceException()).track;
                }
                notifyOnError(-1004,error.type);
            }
        });
        player.addVideoListener(new VideoListener(){public void onVideoSizeChanged(int w,int h,int rotation,float ratio){width=w;height=h;notifyOnVideoSizeChanged(w,h,Math.round(ratio*1000),1000);}public void onRenderedFirstFrame(){notifyOnInfo(3,0);}});
    }
    private boolean hasTrack(int type){
        TrackSelectionArray tracks=player.getCurrentTrackSelections();
        for(int i=0;i<tracks.length;i++)if(player.getRendererType(i)==type&&tracks.get(i)!=null)return true;
        return false;
    }
    public void setDisplay(SurfaceHolder holder){player.setVideoSurfaceHolder(holder);}public void setSurface(Surface surface){player.setVideoSurface(surface);}
    public void setDataSource(String url){source=url;}public String getDataSource(){return source;}
    Throwable failure(){return lastFailure;}
    BlStreams.Track failureTrack(){return failureTrack;}
    boolean passthroughFailed(){return failureTrack==BlStreams.Track.AUDIO&&audioRenderers.passthroughActive;}
    boolean transportFailed(){
        if(!(lastFailure instanceof ExoPlaybackException))return false;
        ExoPlaybackException error=(ExoPlaybackException)lastFailure;
        return error.type==ExoPlaybackException.TYPE_SOURCE&&error.getSourceException() instanceof TrackReadFailure;
    }
    public void prepareAsync(){
        DataSource.Factory transport=()->new SharedSource(api,loads,cancelled,preferred,source,audio,videoAlternatives,audioAlternatives);
        ExtractorMediaSource.Factory media=new ExtractorMediaSource.Factory(transport)
                .setContinueLoadingCheckIntervalBytes(LOADING_CHECK_BYTES);
        ExtractorMediaSource video=media.createMediaSource(Uri.parse(source));
        player.prepare(audio.isEmpty()?video:new MergingMediaSource(video,media.createMediaSource(Uri.parse(audio))));
    }
    public void start(){player.setPlayWhenReady(true);}public void stop(){player.stop();}public void pause(){player.setPlayWhenReady(false);}public boolean isPlaying(){return player.getPlayWhenReady()&&player.getPlaybackState()==Player.STATE_READY;}
    public void seekTo(long at){player.seekTo(at);}public long getCurrentPosition(){return player.getCurrentPosition();}public long getDuration(){long value=player.getDuration();return value==C.TIME_UNSET?0:value;}
    public void release(){released=true;cancelled.set(true);resetListeners();for(Load load:loads)load.cancel();player.release();loads.clear();preferred.clear();}public void reset(){notified=false;player.stop(true);resetListeners();}
    void speed(float value){player.setPlaybackParameters(new PlaybackParameters(value));}
    public void setVolume(float left,float right){player.setVolume((left+right)/2);}public void setAudioStreamType(int type){}public void setKeepInBackground(boolean value){}public void setScreenOnWhilePlaying(boolean value){}public void setWakeMode(Context c,int mode){}
    public void setLooping(boolean value){player.setRepeatMode(value?Player.REPEAT_MODE_ONE:Player.REPEAT_MODE_OFF);}public boolean isLooping(){return player.getRepeatMode()==Player.REPEAT_MODE_ONE;}
    public int getVideoWidth(){return width;}public int getVideoHeight(){return height;}public int getVideoSarNum(){return 1;}public int getVideoSarDen(){return 1;}public int getAudioSessionId(){return 0;}public MediaInfo getMediaInfo(){return new MediaInfo();}public ITrackInfo[] getTrackInfo(){return new ITrackInfo[0];}public void setLogEnabled(boolean value){}public boolean isPlayable(){return true;}
    public void setDataSource(Context c,Uri uri){setDataSource(uri.toString());}public void setDataSource(Context c,Uri uri,Map<String,String> headers){setDataSource(uri.toString());}public void setDataSource(FileDescriptor descriptor)throws IOException{throw new IOException("DASH 仅接受网络媒体地址");}
    private static final class Load {
        final Thread thread=Thread.currentThread();
        final Set<Call> calls=Collections.newSetFromMap(new ConcurrentHashMap<Call,Boolean>());
        final AtomicBoolean cancelled=new AtomicBoolean();

        void cancel(){
            cancelled.set(true);
            for(Call call:calls)call.cancel();
        }
    }

    private static final class TrackReadFailure extends IOException{
        final BlStreams.Track track;
        TrackReadFailure(BlStreams.Track track,IOException error){super((track==BlStreams.Track.AUDIO?"DASH 音频":"DASH 视频")+"线路读取失败",error);this.track=track;}
    }
    private static final class SharedSource extends BaseDataSource {
        private static final Pattern RANGE=Pattern.compile("bytes (\\d+)-(\\d+)/(\\d+|\\*)",Pattern.CASE_INSENSITIVE);
        final BlApi api;final Set<Load> loads;final AtomicBoolean cancelled;final Map<String,String> preferred;final String video,audio;final List<String> videos,audios;
        private final List<String> candidates=new ArrayList<>();
        private Load load;
        private Response response;
        private InputStream stream;
        private Uri uri;
        private boolean opened;
        private String original,selected;
        private int nextCandidate;
        private long position,remaining,responseEnd;

        SharedSource(BlApi api,Set<Load> loads,AtomicBoolean cancelled,Map<String,String> preferred,String video,String audio,List<String> videos,List<String> audios){super(true);this.api=api;this.loads=loads;this.cancelled=cancelled;this.preferred=preferred;this.video=video;this.audio=audio;this.videos=videos;this.audios=audios;}
        public long open(DataSpec spec)throws IOException{
            transferInitializing(spec);
            load=new Load();
            loads.add(load);
            original=spec.uri.toString();position=spec.position;remaining=spec.length;nextCandidate=0;
            Set<String> addresses=new LinkedHashSet<>();
            String working=preferred.get(original);
            if(working!=null)addresses.add(working);
            addresses.add(original);
            if(original.equals(audio))addresses.addAll(audios);
            else if(original.equals(video))addresses.addAll(videos);
            candidates.clear();candidates.addAll(addresses);
            connect(null);
            opened=true;transferStarted(spec);
            return remaining;
        }

        private boolean isCancelled(){
            return cancelled.get()||load.cancelled.get()||load.thread.isInterrupted();
        }

        private void checkCancelled()throws IOException{
            if(isCancelled())throw new InterruptedIOException("DASH 读取已取消");
        }

        private void connect(IOException failure)throws IOException{
            while(nextCandidate<candidates.size()){
                checkCancelled();
                if(failure!=null)api.diagnostics.record("DASH CDN 回退",failure,
                        (original.equals(audio)?"音频":"视频")+" 字节位置="+position);
                selected=candidates.get(nextCandidate++);
                try{
                    requestSelected();
                    preferred.put(original,selected);
                    return;
                }catch(IOException error){
                    if(isCancelled())throw error;
                    failure=error;
                    disconnect(true);
                }
            }
            BlStreams.Track track=original.equals(audio)?BlStreams.Track.AUDIO:BlStreams.Track.VIDEO;
            throw new TrackReadFailure(track,failure==null?new IOException("没有可用的媒体地址"):failure);
        }

        private void requestSelected()throws IOException{
            checkCancelled();
            responseEnd=-1;
            response=api.execute(api.dashRequest(selected,position,remaining).build(),load.calls,load.cancelled);
            if(response.code()==416&&response.header("Content-Range","").equals("bytes */"+position)){
                remaining=0;
                return;
            }
            if(!response.isSuccessful()||response.body()==null)throw BlDiagnostics.response(response);
            MediaType type=response.body().contentType();
            if(type!=null&&(type.subtype().equals("html")||type.subtype().equals("json")))throw new IOException("DASH CDN 返回了网页或 JSON 错误页");
            uri=Uri.parse(response.request().url().toString());
            stream=response.body().byteStream();
            long available=response.body().contentLength();
            if(response.code()==206){
                Matcher range=RANGE.matcher(response.header("Content-Range",""));
                if(!range.matches())throw new IOException("DASH CDN 返回无效的 Content-Range");
                try{
                    long start=Long.parseLong(range.group(1)),end=Long.parseLong(range.group(2));
                    if(start!=position||end<start)throw new IOException("DASH CDN 返回了错误的字节位置");
                    responseEnd=end;
                    if(!range.group(3).equals("*")){
                        long total=Long.parseLong(range.group(3));
                        if(total<=end)throw new IOException("DASH CDN 返回了错误的媒体长度");
                        available=total-start;
                    }
                }catch(NumberFormatException invalid){throw new IOException("DASH CDN 返回无效的 Content-Range",invalid);}
            }else if(position>0){
                if(position>MAX_SKIP_WITHOUT_RANGE)throw new IOException("DASH CDN 不支持从指定位置读取");
                if(available>=0)available-=position;
                byte[] discard=new byte[8192];
                for(long left=position;left>0;){
                    checkCancelled();
                    int read=stream.read(discard,0,(int)Math.min(left,discard.length));
                    if(read<0)throw new EOFException("DASH CDN 未返回请求位置的数据");
                    left-=read;
                }
            }
            if(available>=0)remaining=remaining==C.LENGTH_UNSET?available:Math.min(remaining,available);
        }

        public int read(byte[] buffer,int at,int count)throws IOException{
            if(count==0)return 0;
            while(remaining!=0){
                checkCancelled();
                try{
                    // A valid 206 may cover only part of the requested range.
                    if(responseEnd>=0&&position>responseEnd){
                        disconnect(false);
                        requestSelected();
                        if(remaining==0)return C.RESULT_END_OF_INPUT;
                    }
                    long length=remaining<0?count:Math.min(count,remaining);
                    if(responseEnd>=0)length=Math.min(length,responseEnd-position+1);
                    int read=stream.read(buffer,at,(int)length);
                    if(read<0){
                        if(remaining<0)return C.RESULT_END_OF_INPUT;
                        throw new EOFException("DASH CDN 提前断开");
                    }
                    position+=read;
                    if(remaining>0)remaining-=read;
                    bytesTransferred(read);
                    return read;
                }catch(IOException error){
                    if(isCancelled())throw error;
                    disconnect(true);
                    connect(error);
                }
            }
            return C.RESULT_END_OF_INPUT;
        }

        public Uri getUri(){return uri;}public Map<String,List<String>> getResponseHeaders(){return response==null?Collections.<String,List<String>>emptyMap():response.headers().toMultimap();}
        private void disconnect(boolean failed){
            if(failed&&selected.equals(preferred.get(original)))preferred.remove(original);
            if(response!=null){if(failed)api.cancel(response);response.close();}
            response=null;stream=null;uri=null;
        }
        public void close(){
            try{
                if(load!=null&&isCancelled())load.cancel();
                disconnect(false);
            }finally{
                if(load!=null){loads.remove(load);load=null;}
                if(opened){opened=false;transferEnded();}
            }
        }
    }
}
