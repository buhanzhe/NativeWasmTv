package xiao.bu.tv;

public final class CastRoutePolicyTest {
    static void check(boolean condition) { if (!condition) throw new AssertionError(); }
    public static void main(String[] args) {
        check(CastRouteHandover.canEnd("current", "current"));
        check(!CastRouteHandover.canEnd("new", "old"));
        check(!CastRouteHandover.canEnd("new", ""));
        check(!CastRouteHandover.canEnd("", ""));
        for (int sdk : new int[] {14, 19, 22}) check(CastPermissionPolicy.directPermission(sdk,25).isEmpty());
        for (int sdk : new int[] {23,25,28,29,30,31,32,33,36,37})
            check(CastPermissionPolicy.directPermission(sdk,25).endsWith("ACCESS_FINE_LOCATION"));
        for (int sdk : new int[] {33,36,37})
            check(CastPermissionPolicy.directPermission(sdk,33).endsWith("NEARBY_WIFI_DEVICES"));
        check(!CastPermissionPolicy.requiresLocalNetworkPermission(37,25));
        check(!CastPermissionPolicy.requiresLocalNetworkPermission(36,37));
        check(CastPermissionPolicy.requiresLocalNetworkPermission(37,37));
        check(CastRouteHandover.accepts(true,"http://a","old","http://a","old","new",999,3000));
        check(!CastRouteHandover.accepts(true,"http://a","old","http://b","old","new",999,3000));
        check(!CastRouteHandover.accepts(true,"http://a","old","http://a","wrong","new",999,3000));
        check(!CastRouteHandover.accepts(true,"http://a","old","http://a","old","old",999,3000));
        check(!CastRouteHandover.accepts(true,"http://a","old","http://a","old","",999,3000));
        check(!CastRouteHandover.accepts(true,"http://a","old","http://a","old","new",3000,3000));
        check(!CastRouteHandover.accepts(false,"http://a","old","http://a","old","new",999,3000));
        check(!CastRouteHandover.accepts(true,"http://a","","http://a","","new",999,3000));
        check(CastRouteHandover.preservePlayback(true, 8, 8, true));
        // Address migration must not swallow a concurrent channel/catalog change,
        // an initial claim, or an unfinished first catalog load.
        check(!CastRouteHandover.preservePlayback(true, 9, 8, true));
        check(!CastRouteHandover.preservePlayback(true, 8, 8, false));
        check(!CastRouteHandover.preservePlayback(false, 8, 8, true));
        check(!CastRouteHandover.preservePlayback(true, 8, -1, true));
        check(!CastRouteHandover.preservePlayback(true, -1, -1, true));
        CastFrameTiming timing = new CastFrameTiming();
        timing.submitted(1,100); timing.swapCompleted(1,110); timing.encoded(1,120);
        check(timing.snapshot(130).samples == 1);
        timing.reset();
        check(timing.snapshot(130).samples == 0 && timing.snapshot(130).pending == 0);
        timing.submitted(2,200); timing.swapCompleted(2,210); timing.encoded(2,220);
        check(timing.snapshot(230).samples == 1);
        System.out.println("Cast permission and handover policies passed");
    }
}
