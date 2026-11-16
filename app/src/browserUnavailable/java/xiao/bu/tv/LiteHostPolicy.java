package xiao.bu.tv;

/** Optional embedding-app policies. The reusable engine contains no site rules. */
public interface LiteHostPolicy {
    default boolean preserveMediaPipeline(String page){return false;}
    default boolean preferNativeHls(String page,String media){return false;}
    default boolean allowAuthRenewal(String page){return false;}
    default String selectedSource(String page,String request,String body){return null;}
    default String selectedLogSource(String page,String message){return null;}
    default boolean matchesSelectedHost(String page,String provider,String target,String media){return false;}
    default boolean nativeSelectedHls(String page,String provider,String target){return false;}
    default boolean isPreroll(String page,String media){return false;}
    default int scriptBudgetMillis(String page,int sdk,int normal){return normal;}
    default boolean freshNavigation(String page){return false;}
    default int readTimeoutMillis(String page,String request,String method){return 0;}
    default int requestTimeoutMillis(String page,String request,String method){return 0;}
    default boolean ignoreDocumentClock(String page){return false;}
    default long apiClock(String page,String request,String body,long serverDate){return 0;}
    default boolean traceApiStatus(String request){return false;}
    default boolean allowWorkers(String page,int sdk,int packetLimit){return true;}
}
