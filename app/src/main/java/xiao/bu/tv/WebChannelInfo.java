package xiao.bu.tv;

/** Channel-card lifetime and measured WebView frame rate; no timers or I/O of its own. */
final class WebChannelInfo {
    private int displayRequest=-1, sampleRequest=-1;
    private long waitUntil, readableUntil, sampleAt, sampleFrames;
    private String sampleMedia="";
    private float fps;
    void begin(int request,long now){if(displayRequest==request)return;displayRequest=request;waitUntil=now+15000;readableUntil=0;}
    void cancel(){displayRequest=-1;}
    long remaining(int request,long now){return displayRequest==request?Math.max(0,(readableUntil>0?readableUntil:waitUntil)-now):0;}
    boolean ready(int request,long now,boolean available){
        if(displayRequest!=request||readableUntil>0||!available)return false;
        readableUntil=now+5000;return true;
    }
    float frameRate(int request,String media,long now,long frames,boolean playing){
        if(sampleRequest!=request||!sampleMedia.equals(media)||frames<sampleFrames||frames<0||!playing){
            sampleRequest=request;sampleMedia=media;sampleAt=now;sampleFrames=frames;fps=0;return fps;
        }
        long elapsed=now-sampleAt;
        if(elapsed>=500){
            float measured=(frames-sampleFrames)*1000f/elapsed;
            fps=measured>=0&&measured<=240?measured:0;sampleAt=now;sampleFrames=frames;
        }
        return fps;
    }
}
