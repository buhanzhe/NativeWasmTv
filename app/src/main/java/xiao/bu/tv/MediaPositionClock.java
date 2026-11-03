package xiao.bu.tv;

/** Presentation clock: live MPEG-TS timestamps are not session elapsed time. */
final class MediaPositionClock {
    private Object owner;
    private int request;
    private long raw=-1, sampledAt, elapsed;
    synchronized long position(Object player,int requestId,long position,long duration,long now,boolean playing) {
        if(duration>0)return Math.max(0L,Math.min(duration,position));
        if(player!=owner||requestId!=request){
            owner=player;request=requestId;raw=position;sampledAt=now;elapsed=0;
            return 0;
        }
        long interval=Math.max(0L,now-sampledAt),delta=position-raw;
        if(playing&&position>=0&&raw>=0&&delta!=0){
            // Rebase/discontinuity or an invalid seek must not publish a 26-hour jump.
            long maximum=Math.max(30000L,interval>Long.MAX_VALUE/2?Long.MAX_VALUE:interval*2+5000L);
            long progress=delta>0&&delta<=maximum?delta:interval;
            elapsed=progress>Long.MAX_VALUE-elapsed?Long.MAX_VALUE:elapsed+progress;
        }
        raw=position;sampledAt=now;
        return elapsed;
    }
}
