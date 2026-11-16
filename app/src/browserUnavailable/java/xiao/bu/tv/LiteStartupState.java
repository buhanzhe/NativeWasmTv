package xiao.bu.tv;

/** Cheap immutable browser activity observation; no URL rules or JNI probing. */
public final class LiteStartupState {
    public final long documentReadyAt,lastProgressAt;
    public final int documentStatus,pendingRequests;
    public final boolean busy,mediaIntent,interactionRequired;
    public LiteStartupState(long ready,long progress,int status,int pending,boolean busy,boolean media,boolean interactive){
        documentReadyAt=ready;lastProgressAt=progress;documentStatus=status;pendingRequests=pending;
        this.busy=busy;mediaIntent=media;interactionRequired=interactive;
    }
}
