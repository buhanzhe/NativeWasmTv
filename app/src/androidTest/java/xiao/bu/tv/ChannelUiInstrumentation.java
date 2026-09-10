package xiao.bu.tv;

import android.app.Instrumentation;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.View;
import android.widget.ListView;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.List;

/** Tests keys without committing channel changes or changing saved preferences. */
public final class ChannelUiInstrumentation extends Instrumentation {
    private MainActivity activity;
    private Throwable failure;
    private boolean epgOnly;
    private boolean logoOnly;
    @Override public void onCreate(Bundle args) {
        super.onCreate(args);
        epgOnly = args != null && "true".equals(args.getString("epgOnly"));
        logoOnly = args != null && "true".equals(args.getString("logoOnly"));
        start();
    }
    private static void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
    private static Object get(Object object, String name) throws Exception {
        Field field = object.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(object);
    }
    private static void set(Object object, String name, Object value) throws Exception {
        Field field = object.getClass().getDeclaredField(name); field.setAccessible(true); field.set(object,value);
    }
    private static Object call(Object object, String name) throws Exception {
        Method method=object.getClass().getDeclaredMethod(name); method.setAccessible(true); return method.invoke(object);
    }
    private interface Check { void run() throws Exception; }
    private void main(final Check check) throws Exception {
        runOnMainSync(new Runnable() { @Override public void run() {
            try { check.run(); } catch (Throwable error) { failure=error; }
        }});
        if (failure != null) throw new Exception(failure);
    }
    private void key(int code) { activity.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, code)); }
    private void pass(String text) { Bundle result=new Bundle(); result.putString("stream","PASS "+text+"\n"); sendStatus(0,result); }

    private void logoCache() throws Exception {
        final File directory = new File(getTargetContext().getCacheDir(), "logo-regression-" + System.nanoTime());
        check(directory.mkdirs(), "Cannot create logo fixture directory");
        final Context context = new ContextWrapper(getTargetContext()) {
            @Override public Context getApplicationContext() { return this; }
            @Override public File getCacheDir() { return directory; }
        };
        final android.graphics.Bitmap bitmap = android.graphics.Bitmap.createBitmap(4, 4, android.graphics.Bitmap.Config.ARGB_8888);
        bitmap.eraseColor(android.graphics.Color.BLUE);
        java.io.ByteArrayOutputStream png = new java.io.ByteArrayOutputStream();
        bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, png);
        final byte[] bytes = png.toByteArray();
        final java.net.ServerSocket server = new java.net.ServerSocket(0, 4, java.net.InetAddress.getByName("127.0.0.1"));
        final java.util.concurrent.atomic.AtomicInteger requests = new java.util.concurrent.atomic.AtomicInteger();
        Thread peer = new Thread(new Runnable() { public void run() {
            while (!server.isClosed()) {
                try {
                    java.net.Socket socket = server.accept();
                    try {
                        socket.setSoTimeout(3000);
                        java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.InputStreamReader(socket.getInputStream(), "UTF-8"));
                        String line;
                        while ((line = reader.readLine()) != null && line.length() > 0) { }
                        requests.incrementAndGet();
                        java.io.OutputStream output = socket.getOutputStream();
                        output.write(("HTTP/1.1 200 OK\r\nContent-Type: image/png\r\nContent-Length: " + bytes.length + "\r\nConnection: close\r\n\r\n").getBytes("UTF-8"));
                        output.write(bytes); output.flush();
                    } finally { socket.close(); }
                } catch (Exception ignored) { if (!server.isClosed()) return; }
            }
        }}, "logo-cache-test-peer");
        peer.start();
        final ChannelLogoLoader[] loader = new ChannelLogoLoader[1];
        final android.widget.ImageView[] image = new android.widget.ImageView[1];
        final ChannelLogoCache disk = new ChannelLogoCache(directory);
        try {
            for (int stage = 0; stage < 4; stage++) {
                final String url = "http://127.0.0.1:" + server.getLocalPort() + "/logo.png?stage=" + stage;
                if (stage == 2) check(disk.fileFor("测试频道").setLastModified(System.currentTimeMillis() - ChannelLogoCache.VALID_MS - 1000L), "Cannot expire PNG fixture");
                if (stage == 3) {
                    FileOutputStream broken = new FileOutputStream(disk.fileFor("测试频道"));
                    try { broken.write(new byte[]{1, 2, 3}); } finally { broken.close(); }
                }
                main(new Check() { public void run() {
                    if (loader[0] != null) loader[0].close();
                    loader[0] = new ChannelLogoLoader();
                    image[0] = new android.widget.ImageView(context);
                    loader[0].load(image[0], "测试频道", url);
                }});
                final boolean[] visible = {false};
                long deadline = SystemClock.uptimeMillis() + 5000L;
                while (!visible[0] && SystemClock.uptimeMillis() < deadline) {
                    main(new Check() { public void run() { visible[0] = image[0].getVisibility() == View.VISIBLE && image[0].getDrawable() != null; }});
                    if (!visible[0]) SystemClock.sleep(20);
                }
                check(visible[0], "Logo did not display at stage " + stage);
                check(requests.get() == (stage < 2 ? 1 : stage), "Repeated network request or stale PNG at stage " + stage);
                check(disk.read("测试频道", System.currentTimeMillis()) != null, "Successful image not cached as PNG");
            }
            File file = disk.fileFor("测试频道");
            check(disk.read("测试频道", file.lastModified() + ChannelLogoCache.VALID_MS) == null, "PNG survived 24h expiry");
            check(disk.read("测试频道", file.lastModified() - 1L) == null, "Clock rollback extends cache");
            check(disk.fileFor("../频道").getCanonicalFile().getParentFile().equals(file.getCanonicalFile().getParentFile()), "Unsafe cache name");
            pass("PNG cache: cold loader reuses channel-name cache despite URL change; 24h expiry and corrupt file redownload; cached image displays");
        } finally {
            main(new Check() { public void run() { if (loader[0] != null) loader[0].close(); }});
            server.close(); peer.join(1000); bitmap.recycle();
            File folder = new File(directory, "channel-logos-v1");
            File[] files = folder.listFiles();
            if (files != null) for (File file : files) file.delete();
            folder.delete(); directory.delete();
        }
    }

    private void epg() throws Exception {
        long day=EpgManager.dayStart(System.currentTimeMillis());
        SimpleDateFormat format=new SimpleDateFormat("yyyyMMddHHmmss Z", Locale.US);
        String start=format.format(new Date(day)), stop=format.format(new Date(day+3600000L));
        String xml="<tv><channel id='test'><display-name>Test</display-name><icon src='../logos/test.png'/></channel>"
                +"<channel id='orphan'><display-name>Logo only</display-name><icon src='https://images.example.test/icon.png'/></channel>"
                +"<channel id='invalid'><icon src='file:///test.png'/></channel>"
                +"<programme channel='test' start='"+start+"' stop='"+stop+"'><title>Midnight</title>"
                +"<icon src='https://images.example.test/program-poster.png'/></programme></tv>";
        Method parse=EpgManager.class.getDeclaredMethod("parse", byte[].class); parse.setAccessible(true);
        EpgManager manager=new EpgManager(getTargetContext());
        Object guide=parse.invoke(null,(Object)xml.getBytes("UTF-8"));
        Method publish=EpgManager.class.getDeclaredMethod("publish",guide.getClass(),String.class);
        publish.setAccessible(true);
        publish.invoke(manager,guide,"https://epg.example.test/feed/daily.xml");
        Channel named=new Channel("1","Test","test","http://test",null,null);
        check("https://epg.example.test/logos/test.png".equals(manager.logoFor(named)),"Relative EPG logo/name match failed");
        Channel identified=new Channel("1","Different name","test","http://test",null,null,null,"test");
        check(manager.logoFor(named).equals(manager.logoFor(identified)),"EPG ID match failed");
        check("https://images.example.test/icon.png".equals(manager.logoFor(
                new Channel("2","Logo only","orphan","http://test",null,null))),"Logo requires programme data");
        check("https://playlist.example.test/logo.png".equals(manager.logoFor(named.withLogo(
                "https://playlist.example.test/logo.png"))),"Explicit playlist logo lost priority");
        check(manager.logoFor(new Channel("3","invalid","invalid","http://test",null,null)).isEmpty(),"Non-HTTP logo accepted");
        check(manager.logoFor(new Channel("4","Missing","missing","http://test",null,null)).isEmpty(),"Missing logo not hidden");
        pass("EPG channel icons: ID/name matching, relative URLs, no programme required, missing/invalid hidden; programme posters ignored");
        List<EpgManager.Program> programs=manager.programsFor(new Channel("1","Test","test","http://test",null,null));
        check(programs.size()==1,"Midnight programme was dropped");
        java.util.ArrayList<EpgManager.Program> list=new java.util.ArrayList<EpgManager.Program>(programs);
        list.add(programs.get(0));
        list.add(new EpgManager.Program(day-7200000L,day-3600000L,"Yesterday"));
        list.add(new EpgManager.Program(day+172800000L,day+176400000L,"Future"));
        check(new EpgDisplayCache().programsFor(list).size()==1,"Today filter/dedup failed");
        final File file=new File(getTargetContext().getCacheDir(),"epg-day-regression.tmp");
        Context wrapper=new ContextWrapper(getTargetContext()) {
            @Override public Context getApplicationContext() { return this; }
            @Override public File getFileStreamPath(String name) { return file; }
        };
        try {
            new FileOutputStream(file).close();
            EpgManager cached=new EpgManager(wrapper);
            check((Boolean)call(cached,"isCacheFresh"),"Today's cache not reused");
            check(file.setLastModified(day-1),"Cannot set fixture timestamp");
            check(!(Boolean)call(cached,"isCacheFresh"),"Yesterday's cache treated as today");
        } finally { file.delete(); }
        pass("EPG retains midnight, filters today, deduplicates, checks calendar-day cache");
        final EpgManager rowGuide = new EpgManager(getTargetContext());
        String rowXml = "<tv><channel id='row'><display-name>菜单测试</display-name></channel>"
                + "<programme channel='row' start='" + format.format(new Date(System.currentTimeMillis()-60000L))
                + "' stop='" + format.format(new Date(System.currentTimeMillis()+60000L))
                + "'><title>正在播出的节目</title></programme></tv>";
        publish.invoke(rowGuide, parse.invoke(null, (Object)rowXml.getBytes("UTF-8")), "https://example.test/epg.xml");
        main(new Check() { public void run() {
            ChannelListAdapter adapter = new ChannelListAdapter(getTargetContext(), new UiScaleHelper());
            try {
                adapter.setEpgManager(rowGuide);
                adapter.showChannels(0, new Channel[]{
                    new Channel("1", "菜单测试", "row", "http://test", null, null),
                    new Channel("2", "没有节目数据", "missing", "http://test", null, null)}, 0, 0, 0);
                android.widget.FrameLayout parent = new android.widget.FrameLayout(getTargetContext());
                View row = adapter.getView(0, null, parent);
                android.widget.TextView title = (android.widget.TextView)row.findViewById(R.id.channel_item_program);
                check(title.getVisibility() == View.VISIBLE && "正在播出的节目".equals(title.getText().toString()), "Current programme missing from row");
                check(row.findViewById(R.id.channel_group_count).getVisibility() == View.GONE, "Single source count is redundant");
                row = adapter.getView(1, row, parent);
                check(title.getVisibility() == View.GONE && title.getText().length() == 0, "Recycled row keeps previous programme");
                check(getTargetContext().getResources().getIdentifier("channel_item_logo", "id", getTargetContext().getPackageName()) == 0, "Logo view remains in layout");
            } finally { /* Adapter owns no logo requests or background resources. */ }
        }});
        pass("Channel rows show current EPG; recycled rows clear absent programme/logo; single source badge hidden");
    }

    @Override public void onStart() {
        Bundle result=new Bundle();
        try {
            if (logoOnly) {
                logoCache(); result.putString("stream", "LOGO CACHE CHECKS PASSED\n"); finish(-1,result); return;
            }
            epg();
            if (epgOnly) {
                result.putString("stream","EPG CHECKS PASSED\n"); finish(-1,result); return;
            }
            activity=(MainActivity)startActivitySync(new Intent(getTargetContext(),MainActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            SystemClock.sleep(3500);
            final int originalGroup=(Integer)get(activity,"currentGroupIndex");
            final int originalIndex=(Integer)get(activity,"currentChannelIndex");
            final boolean reverse=(Boolean)get(activity,"reverseUpDown");
            try {
                main(new Check() { public void run() throws Exception {
                    call(activity,"closeManagementPanel"); call(activity,"closeChannelList");
                    View root = (View)get(activity,"root");
                    long now = SystemClock.uptimeMillis();
                    int captureGeneration = (Integer)get(activity,"channelSwipeCaptureGeneration");
                    Method begin = MainActivity.class.getDeclaredMethod("beginPlaybackGesture", android.view.MotionEvent.class);
                    begin.setAccessible(true);
                    android.view.MotionEvent down = android.view.MotionEvent.obtain(now, now,
                            android.view.MotionEvent.ACTION_DOWN, root.getWidth() * .75f, root.getHeight() * .5f, 0);
                    begin.invoke(activity, down); down.recycle();
                    check((Integer)get(activity,"channelSwipeCaptureGeneration") == captureGeneration,
                            "Right-side tap started an unnecessary frame capture");
                    call(activity,"resetPlaybackGesture");
                    check((Integer)get(activity,"channelSwipeCaptureGeneration") == captureGeneration,
                            "Tap reset touched swipe visuals");
                    check(((View)get(activity,"channelSwitchBlackout")).getVisibility() != View.VISIBLE,
                            "Tap left black overlay visible");

                    int group=originalGroup;
                    for(int i=0;i<ChannelCatalog.GROUPS.length;i++) if(ChannelCatalog.GROUPS[i].channels.length>12) {group=i;break;}
                    check(ChannelCatalog.GROUPS[group].channels.length>4,"Need at least five channels");
                    set(activity,"currentGroupIndex",group);
                    set(activity,"currentChannelIndex",ChannelCatalog.GROUPS[group].channels.length/2);
                    int index=(Integer)get(activity,"currentChannelIndex");
                    for(boolean flipped:new boolean[]{false,true}) {
                        set(activity,"reverseUpDown",flipped);
                        for(int key:new int[]{KeyEvent.KEYCODE_DPAD_UP,KeyEvent.KEYCODE_DPAD_DOWN,
                                KeyEvent.KEYCODE_CHANNEL_UP,KeyEvent.KEYCODE_CHANNEL_DOWN}) {
                            call(activity,"cancelPendingRelativeSwitch");
                            key(key);
                            int step=(key==KeyEvent.KEYCODE_DPAD_UP||key==KeyEvent.KEYCODE_CHANNEL_UP)?-1:1;
                            if(flipped)step=-step;
                            check((Integer)get(activity,"pendingRelativeChannelIndex")==index+step,"Key reversal failed: "+key+" reverse="+flipped);
                            call(activity,"cancelPendingRelativeSwitch");
                        }
                    }
                    call(activity,"clearNumericChannelInput"); key(KeyEvent.KEYCODE_1);
                }});
                pass("DPAD and channel +/- keys, both reverse settings");
                SystemClock.sleep(1800);
                main(new Check() { public void run() throws Exception {
                    check("1".equals(get(activity,"numericChannelInput")),"Digit committed before older user entered next digit");
                    key(KeyEvent.KEYCODE_8);
                    check("18".equals(get(activity,"numericChannelInput")),"Second digit lost");
                    call(activity,"clearNumericChannelInput");
                    call(activity,"openChannelList");
                }});
                pass("1 then 8 after 1.8 seconds stays channel 18 input");
                SystemClock.sleep(350);
                main(new Check() { public void run() throws Exception {
                    View epg=(View)get(activity,"epgColumn");
                    check(epg.getVisibility()==View.GONE,"EPG expanded by default");
                    ListView list=(ListView)get(activity,"channelList");
                    check(!list.isVerticalScrollBarEnabled()
                            && !((ListView)get(activity,"groupList")).isVerticalScrollBarEnabled()
                            && !((ListView)get(activity,"epgList")).isVerticalScrollBarEnabled(), "Menu scrollbars remain visible");
                    View toggle=(View)get(activity,"epgToggle");
                    check(toggle.getLeft() >= list.getRight(), "EPG handle is not beside channel column");
                    check(list.getHeight() > ((View)get(activity,"channelListPanel")).getHeight() * 0.94f,
                            "Top/bottom chrome wastes menu height");
                    View selected=list.getSelectedView();
                    check(selected!=null,"No selected row");
                    check(selected.findViewById(R.id.channel_item_program)!=null, "Channel details missing");
                    int center=selected.getTop()+selected.getHeight()/2;
                    check(Math.abs(center-list.getHeight()/2)<=selected.getHeight(),"Selected channel not centered: "+center+" / "+list.getHeight()
                            +" padding="+list.getPaddingTop()+","+list.getPaddingBottom()+" first="+list.getFirstVisiblePosition()+" selected="+list.getSelectedItemPosition());
                    key(KeyEvent.KEYCODE_DPAD_RIGHT);
                    check(epg.getVisibility()==View.VISIBLE,"Right did not expand EPG");
                    check(((View)get(activity,"epgFavorite")).hasFocus(),"Favorite header not reachable");
                    key(KeyEvent.KEYCODE_DPAD_LEFT);
                    check(epg.getVisibility()==View.GONE,"Left did not collapse EPG");
                }});
                pass("Menu defaults collapsed, selected channel centered, favorite header reachable");
                for(final boolean last:new boolean[]{false,true}) {
                    main(new Check() { public void run() throws Exception {
                        int group=(Integer)get(activity,"currentGroupIndex");
                        set(activity,"currentChannelIndex",last ? ChannelCatalog.GROUPS[group].channels.length-1 : 0);
                        call(activity,"openChannelList");
                    }});
                    SystemClock.sleep(250);
                    main(new Check() { public void run() throws Exception {
                        ListView list=(ListView)get(activity,"channelList");
                        View selected=list.getSelectedView();
                        check(selected!=null,"No boundary selection");
                        check(selected.getTop() >= 0 && selected.getBottom() <= list.getHeight(),
                                "Boundary channel is not visible, last="+last);
                        check(list.getPaddingTop() < selected.getHeight() && list.getPaddingBottom() < selected.getHeight(),
                                "Artificial boundary padding remains");
                        if (!last) check(list.getFirstVisiblePosition() == 0
                                        && selected.getTop() <= list.getListPaddingTop() + 2,
                                "First channel pushed down");
                    }});
                }
                pass("Boundary channels remain visible without artificial whitespace");
            } finally {
                failure=null;
                main(new Check() { public void run() throws Exception {
                    call(activity,"cancelPendingRelativeSwitch"); call(activity,"clearNumericChannelInput");
                    set(activity,"currentGroupIndex",originalGroup);set(activity,"currentChannelIndex",originalIndex);
                    set(activity,"reverseUpDown",reverse); activity.finish();
                }});
            }
            result.putString("stream","ALL CHECKS PASSED\n"); finish(-1,result);
        } catch(Throwable error) {
            result.putString("stream",android.util.Log.getStackTraceString(error));finish(0,result);
        }
    }
}
