package xiao.bu.tv;

/** Bounded automatic resolution, driven by browser work rather than websites. */
final class LiteWebStartupPolicy {
    static final long BASE=30000,HARD=45000,PREPARE=12000;
    enum Decision { WAIT, RENDER, FAIL }
    private static boolean working(LiteStartupState s,long now){
        return s.busy||s.pendingRequests>0||s.mediaIntent||s.lastProgressAt>0&&now>=s.lastProgressAt&&now-s.lastProgressAt<8000;
    }
    static boolean retryDependency(String message,int attempts,LiteStartupState state,boolean preparing,boolean published,boolean interacting){
        return attempts==0&&!preparing&&!published&&!interacting&&state!=null&&!state.interactionRequired
            &&state.documentStatus>=200&&state.documentStatus<300&&message!=null
            &&message.contains("Promise rejection: Error: Loader timeout");
    }
    static Decision render(long started,long now,LiteStartupState s,boolean interactive){
        if(s==null||s.documentReadyAt<=0)return Decision.WAIT;
        if(interactive||s.interactionRequired)return Decision.RENDER;
        if(now-started>=HARD)return Decision.RENDER;
        if(now<s.documentReadyAt||now-s.documentReadyAt<10000)return Decision.WAIT;
        return working(s,now)?Decision.WAIT:Decision.RENDER;
    }
    static Decision deadline(long started,long now,LiteStartupState s,boolean preparing){
        if(preparing||s==null)return Decision.FAIL;
        if(now-started<HARD&&s.documentStatus<400&&working(s,now)&&!s.interactionRequired)return Decision.WAIT;
        return s.documentReadyAt>0&&s.documentStatus<400?Decision.RENDER:Decision.FAIL;
    }
}
