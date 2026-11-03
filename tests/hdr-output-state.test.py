"""Exercise production HDR presentation policy and source-sized Surface lifecycle."""
from pathlib import Path
import os
import subprocess
import tempfile

root = Path(__file__).resolve().parents[1]
java_root = root / 'app/src/main/java/xiao/bu/tv'
hdr = (java_root / 'NativeHdrOutput.java').read_text(encoding='utf8')
view = (java_root / 'DirectVideoView.java').read_text(encoding='utf8')
main = (java_root / 'MainActivity.java').read_text(encoding='utf8')

def method(source, start, end):
    return source[source.index(start):source.index(end, source.index(start))]

presentation = method(hdr, '    private void configurePresentation(', '    private int buildProgram(')
context = method(hdr, '    private EGLConfig createOutputContext(', '    private void configurePresentation(')
sizes = method(view, '    void setVideoSize(', '    @Override protected void onMeasure(')
created = method(view, '    @Override public void surfaceCreated(', '    @Override public void surfaceChanged(')
destroyed = method(view, '    @Override public void surfaceDestroyed(', '    void releaseOutput(')
layout = method(main, '    private void updateVideoLayout(', '    private static boolean isAudioOnly(')
geometry = method(hdr, '    private void updateOutputGeometry(', '    private static void requestBufferSize(')
buffer_request = method(hdr, '    private static void requestBufferSize(', '    static float renderedFrameRate(')

check = r'''
class HdrOutputStateCheck {
    static void ok(boolean value,String why) { if(!value) throw new AssertionError(why); }
    static class EGLConfig {}
    static class Build {static class VERSION {static int SDK_INT=28;}}
    static class EGL14 {
        static final int EGL_MIN_SWAP_INTERVAL=1;
        static final int EGL_RED_SIZE=2,EGL_GREEN_SIZE=3,EGL_BLUE_SIZE=4,EGL_RENDERABLE_TYPE=5,EGL_OPENGL_ES2_BIT=4,EGL_SURFACE_TYPE=6,EGL_WINDOW_BIT=7,EGL_NONE=8,EGL_NO_CONTEXT=0,EGL_CONTEXT_CLIENT_VERSION=9;
        static boolean noEs3Config,rejectEs3,noConfig,emptyEs3Config;
        static int configCalls,contextCalls,lastBits,lastContext;
        static boolean eglChooseConfig(int display,int[] attrs,int offset,EGLConfig[] configs,int co,int limit,int[] count,int at){
            configCalls++;lastBits=attrs[7];count[at]=emptyEs3Config&&(lastBits&0x40)!=0?0:1;configs[co]=new EGLConfig();
            return !noConfig&&(!noEs3Config||(lastBits&0x40)==0);
        }
        static int eglCreateContext(int display,EGLConfig config,int shared,int[] attrs,int at){
            contextCalls++;lastContext=attrs[1];return lastContext==3&&rejectEs3?0:lastContext;
        }
        static boolean query=true,rejectZero; static int minimum,calls,last;
        static boolean eglGetConfigAttrib(int d,EGLConfig c,int key,int[] value,int offset) {
            if(query)value[offset]=minimum; return query;
        }
        static boolean eglSwapInterval(int display,int interval) {
            calls++;last=interval;return interval!=0||!rejectZero;
        }
    }
    static class Presentation {
        int display,eglContext,swapInterval=1;
        CONTEXT
        PRESENTATION
    }
    static class Holder {
        int width=1920,height=1080,fixed,layout;
        Object getSurface() { return this; }
        void setFixedSize(int w,int h) {width=w;height=h;fixed++;}
        void setSizeFromLayout() {width=1920;height=1080;layout++;}
    }
    static class SurfaceHolder extends Holder {}
    static class MediaFormat extends java.util.HashMap<String,Integer> {}
    static class SurfaceView {
        SurfaceHolder holder=new SurfaceHolder();
        SurfaceHolder getHolder() { return holder; }
    }
    static class Blur {void clear() {}}
    interface Callback {void onVideoSurfaceCreated(SurfaceHolder h,Object s);default void onVideoSurfaceDestroyed(SurfaceHolder h,Object s){}}
    static class Buffers {
        SurfaceHolder activeHolder; SurfaceView surfaceView=new SurfaceView();
        int videoWidth,videoHeight,sarNum=1,sarDen=1;
        int gpuBufferWidth,gpuBufferHeight;
        boolean viewportBufferMode,appliedViewportBufferMode,fillVideo,stretchVideo;
        Blur blur=new Blur();Callback callback;
        Object getParent() { return new android.view.View(); }
        void invalidate() {} void requestLayout() {}
        SIZES
        CREATED
        DESTROYED
    }
    static class IMediaPlayer {
        int width,height;
        int getVideoWidth(){return width;} int getVideoHeight(){return height;}
        int getVideoSarNum(){return 1;} int getVideoSarDen(){return 1;}
    }
    static class IjkMediaPlayer extends IMediaPlayer {
        static final int FFP_PROPV_DECODER_AVCODEC=1;
        int decoder=2;int getVideoDecoder(){return decoder;}
    }
    static class Bridge {
        int[] decoderGeometry;
        Object target;int requestedWidth,requestedHeight;
        void requestBufferSize(Object s,int w,int h,Bridge expected){requestedWidth=w;requestedHeight=h;}
        static int integer(MediaFormat format,String key,int fallback){return format.containsKey(key)?format.get(key):fallback;}
        GEOMETRY
    }
    static class Surface {}
    static class DirectVideoView extends Buffers {
        Surface surface=new Surface();java.util.List<Runnable> queue=new java.util.ArrayList<>();
        Surface getVideoSurface(){return surface;}
        void post(Runnable r){queue.add(r);}
        void drain(){for(Runnable r:queue)r.run();queue.clear();}
    }
    static class NativeHdrOutput {
        static NativeHdrOutput active;
        static java.lang.ref.WeakReference<DirectVideoView> outputView;
        Surface target;boolean closed;
        REQUEST_BUFFER
    }
    static class Log {static void i(String tag,String value) {}}
    static class Layout {
        static final String TAG="test";
        int videoWidth,videoHeight,videoSarNum,videoSarDen;
        boolean lowResourceDevice; Buffers videoView=new Buffers();
        void refreshVideoInfo() {}
        LAYOUT
    }
    static void presentation() {
        Presentation p=new Presentation();p.configurePresentation(new EGLConfig());
        ok(EGL14.last==0&&EGL14.calls==1&&p.swapInterval==0,"Avoid duplicate display wait when EGL allows zero");
        EGL14.minimum=1;EGL14.calls=0;p=new Presentation();p.configurePresentation(new EGLConfig());
        ok(EGL14.last==1&&EGL14.calls==1,"Honor minimum swap interval");
        EGL14.query=false;EGL14.calls=0;p=new Presentation();p.configurePresentation(new EGLConfig());
        ok(EGL14.last==1&&EGL14.calls==1,"Unknown config retains compatible synchronization");
        EGL14.query=true;EGL14.minimum=0;EGL14.rejectZero=true;EGL14.calls=0;
        p=new Presentation();p.configurePresentation(new EGLConfig());
        ok(EGL14.last==1&&p.swapInterval==1&&EGL14.calls==2,"Rejected zero interval has bounded fallback");
    }
    static void contexts() {
        Presentation p=new Presentation();p.createOutputContext();
        ok(p.eglContext==3&&EGL14.configCalls==1&&EGL14.contextCalls==1&&EGL14.lastBits==(4|0x40),"Prefer an ES3+ES2 compatible config on Android 9");
        EGL14.rejectEs3=true;EGL14.configCalls=EGL14.contextCalls=0;p=new Presentation();p.createOutputContext();
        ok(p.eglContext==2&&EGL14.contextCalls==2,"Rejected ES3 context must retry ES2 once");
        EGL14.rejectEs3=false;EGL14.noEs3Config=true;EGL14.configCalls=EGL14.contextCalls=0;p=new Presentation();p.createOutputContext();
        ok(p.eglContext==2&&EGL14.configCalls==2&&EGL14.contextCalls==1&&EGL14.lastBits==4,"Missing ES3 config must choose compatible ES2 config");
        EGL14.noEs3Config=false;EGL14.emptyEs3Config=true;EGL14.configCalls=EGL14.contextCalls=0;p=new Presentation();p.createOutputContext();
        ok(p.eglContext==2&&EGL14.configCalls==2,"Empty ES3 config list needs ES2 fallback");EGL14.emptyEs3Config=false;
        Build.VERSION.SDK_INT=17;EGL14.configCalls=EGL14.contextCalls=0;p=new Presentation();p.createOutputContext();
        ok(p.eglContext==2&&EGL14.configCalls==1&&EGL14.contextCalls==1,"Old Android must preserve ES2");
        EGL14.noConfig=true;p=new Presentation();
        try{p.createOutputContext();throw new AssertionError("Missing EGL config accepted");}catch(IllegalStateException expected){}
        EGL14.noConfig=false;
    }
    static void surfaces() {
        Buffers v=new Buffers();v.setVideoSize(3840,2160,1,1);
        SurfaceHolder recreated=new SurfaceHolder();v.surfaceView.holder=recreated;
        v.callback=(h,s)->ok(h.width==3840&&h.height==2160,"Restore 4K before attaching decoder");
        v.surfaceCreated(recreated);
        ok(recreated.fixed==1,"Recreated buffer restored once");
        for(int n=0;n<100;n++)v.setVideoSize(3840,2160,1,1);
        ok(recreated.fixed==1,"Repeated source-size callbacks must not resize buffers");
        v.setViewportBufferMode(true);v.setVideoSize(3840,2160,1,1);
        ok(recreated.width==1920&&recreated.height==1080,"Retain explicit low-RAM software viewport policy");
        v.setViewportBufferMode(false);v.setVideoSize(3840,2160,1,1);
        ok(recreated.width==3840&&recreated.height==2160,"Hardware mode restores original resolution");
        v.setVideoSize(1920,1080,1,1);
        ok(recreated.width==1920&&recreated.height==1080,"Real source-size changes still apply");
        v.setVideoSize(3840,2160,1,1);v.setGpuBufferSize(1920,1080);
        ok(recreated.width==1920&&recreated.height==1080,"Reduced GPU input needs matching buffer");
        v.setVideoSize(3840,2160,1,1);
        ok(recreated.width==1920,"Repeated coded metadata must preserve GPU buffer policy");
        v.setGpuBufferSize(0,0);
        ok(recreated.width==3840&&recreated.height==2160,"Leaving GPU mode must restore source resolution");
        v.setGpuBufferSize(1920,1080);v.surfaceDestroyed(recreated);v.surfaceCreated(recreated);
        ok(recreated.width==3840&&v.gpuBufferWidth==0,"Surface recreation must discard the previous GPU owner policy");
        v=new Buffers();v.surfaceCreated(v.surfaceView.holder);
        ok(v.surfaceView.holder.fixed==0,"Do not force invalid dimensions on initial Surface");
    }
    static void layouts() {
        Layout v=new Layout();IjkMediaPlayer p=new IjkMediaPlayer();
        p.width=3840;p.height=2160;v.updateVideoLayout(p);
        int calls=v.videoView.surfaceView.holder.fixed;
        for(int[] size:new int[][]{{0,0},{3840,0},{0,2160}}) {
            p.width=size[0];p.height=size[1];v.updateVideoLayout(p);
            ok(v.videoWidth==3840&&v.videoHeight==2160,"Transient mode-reopen size lost source geometry");
            ok(v.videoView.surfaceView.holder.fixed==calls,"Invalid size resized output");
        }
        p.width=3840;p.height=2160;v.updateVideoLayout(p);
        ok(v.videoView.surfaceView.holder.fixed==calls,"Same-size HDR mode reopen unnecessarily resizes");
        p.width=1920;p.height=1080;v.updateVideoLayout(p);
        ok(v.videoWidth==1920&&v.videoView.surfaceView.holder.width==1920,"Valid incoming source never applied");
    }
    static void cropValidation() {
        Bridge b=new Bridge();MediaFormat f=new MediaFormat();f.put("width",3840);f.put("height",2160);
        b.updateOutputGeometry(f);ok(java.util.Arrays.equals(b.decoderGeometry,new int[]{3840,2160,0,0}),"Missing crop inferred visible dimensions");
        f.put("crop-left",0);f.put("crop-top",0);f.put("crop-right",1919);f.put("crop-bottom",1079);
        b.updateOutputGeometry(f);ok(java.util.Arrays.equals(b.decoderGeometry,new int[]{3840,2160,1920,1080}),"Keep coded and visible dimensions distinct");
        ok(b.requestedWidth==1920&&b.requestedHeight==1080,"Half-sized GPU output must render at 1080p");
        for(int[] crop:new int[][]{{-1,0,1919,1079},{0,0,3840,1079},{0,0,1919,2160},{40,0,0,1079},{0,0,Integer.MAX_VALUE,Integer.MAX_VALUE}}){
            f.put("crop-left",crop[0]);f.put("crop-top",crop[1]);f.put("crop-right",crop[2]);f.put("crop-bottom",crop[3]);
            b.updateOutputGeometry(f);ok(b.decoderGeometry[2]==0&&b.decoderGeometry[3]==0,"Invalid crop accepted");
            ok(b.requestedWidth==0&&b.requestedHeight==0,"Invalid crop must not lower resolution");
        }
        f.put("crop-left",0);f.put("crop-top",0);f.put("crop-right",3839);f.put("crop-bottom",2159);
        b.updateOutputGeometry(f);ok(b.decoderGeometry[2]==3840&&b.decoderGeometry[3]==2160,"Full 4K output lost");
        ok(b.requestedWidth==0&&b.requestedHeight==0,"Full 4K input must retain 4K rendering");
        for(int[] crop:new int[][]{{3839,2151},{1919,2159},{3039,1709}}){
            f.put("crop-right",crop[0]);f.put("crop-bottom",crop[1]);b.updateOutputGeometry(f);
            ok(b.requestedWidth==0,"Ordinary crop or aspect change must not reduce rendering");
        }
    }
    static void queuedResizes() {
        DirectVideoView view=new DirectVideoView();view.setVideoSize(3840,2160,1,1);
        NativeHdrOutput.outputView=new java.lang.ref.WeakReference<>(view);
        NativeHdrOutput first=new NativeHdrOutput();first.target=view.surface;NativeHdrOutput.active=first;
        NativeHdrOutput.requestBufferSize(view.surface,1920,1080,first);view.drain();
        ok(view.gpuBufferWidth==1920,"Active GPU resize not applied");
        first.closed=true;NativeHdrOutput.requestBufferSize(view.surface,0,0,null);
        NativeHdrOutput next=new NativeHdrOutput();next.target=view.surface;NativeHdrOutput.active=next;
        NativeHdrOutput.requestBufferSize(view.surface,1280,720,next);view.drain();
        ok(view.gpuBufferWidth==1280,"Old release must not reset a newer GPU owner");
        NativeHdrOutput.requestBufferSize(view.surface,1920,1080,first);view.drain();
        ok(view.gpuBufferWidth==1280,"Closed output must not apply stale metadata");
        next.closed=true;NativeHdrOutput.requestBufferSize(view.surface,0,0,null);view.drain();
        ok(view.gpuBufferWidth==0&&view.surfaceView.holder.width==3840,"Release must restore 4K direct output");
        next.closed=false;NativeHdrOutput.requestBufferSize(view.surface,1280,720,next);
        view.surface=new Surface();view.drain();
        ok(view.gpuBufferWidth==0,"Recreated Surface must reject queued old-Surface resize");
    }
    public static void main(String[] args) {
        presentation();contexts();surfaces();layouts();cropValidation();queuedResizes();
        System.out.println("PASS HDR swap policy/fallback, 4K Surface recreation, invalid mode-reopen sizes and repeated metadata");
    }
}
'''.replace('PRESENTATION', presentation).replace('        CONTEXT\n', context).replace('SIZES', sizes).replace(
    'CREATED', created.replace('@Override ', '')).replace('DESTROYED',destroyed.replace('@Override ', '')).replace('LAYOUT', layout).replace('        GEOMETRY\n',geometry).replace('REQUEST_BUFFER',buffer_request)
with tempfile.TemporaryDirectory() as directory:
    out = Path(directory)
    parent = out / 'android/view/View.java'
    parent.parent.mkdir(parents=True)
    parent.write_text('package android.view; public class View {public int getWidth(){return 1920;} public int getHeight(){return 1080;}}')
    path = out / 'HdrOutputStateCheck.java'
    path.write_text(check, encoding='utf8')
    jdk = Path(os.environ['JAVA_HOME']) / 'bin'
    subprocess.run([str(jdk / 'javac.exe'), '-encoding', 'UTF-8', '-d', directory, str(path), str(parent)], check=True)
    subprocess.run([str(jdk / 'java.exe'), '-cp', directory, 'HdrOutputStateCheck'], check=True)
