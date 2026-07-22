package xiao.bu.tv;

import com.bu.cc.tv.NativeCmgDecryptor;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.ActivityManager;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.Base64;
import android.util.Log;
import android.view.KeyEvent;
import android.view.Surface;
import android.view.View;
import android.view.WindowManager;
import android.widget.AdapterView;
import android.widget.FrameLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;

import tv.danmaku.ijk.media.player.IMediaPlayer;
import tv.danmaku.ijk.media.player.IjkMediaPlayer;

public final class MainActivity extends Activity {
    private static final String TAG = "MainActivity";
    private static final String PREFERENCES = "tv_player";
    private static final String LAST_GROUP_INDEX = "last_group_index";
    private static final String LAST_CHANNEL_INDEX = "last_channel_index";
    private static final long CHANNEL_BAR_TIMEOUT_MS = 3000L;
    private static final long PANEL_TIMEOUT_MS = 5000L;
    private static final long EXIT_CONFIRM_TIMEOUT_MS = 2000L;
    private static final long CHANNEL_PREFETCH_DELAY_MS = 1500L;
    private static final long CCTV_BUFFERING_RECOVERY_MS = 8000L;
    private static final long CCTV_VIDEO_STALL_RECOVERY_MS = 8000L;

    private final Runnable hideChannelBar = new Runnable() {
        @Override
        public void run() {
            channelBar.setVisibility(View.GONE);
        }
    };
    private final Runnable hideChannelList = new Runnable() {
        @Override
        public void run() {
            closeChannelList();
        }
    };

    private View root;
    private View channelBar;
    private View loadingPanel;
    private View channelListPanel;
    private TextView channelListTitle;
    private TextView channelName;
    private TextView statusText;
    private TextView videoInfo;
    private TextView loadingChannel;
    private TextView loadingStatus;
    private ListView groupList;
    private ListView channelList;
    private ChannelListAdapter groupAdapter;
    private ChannelListAdapter channelAdapter;
    private LiveUrlResolver liveUrlResolver;
    private YangshipinWebResolver yangshipinResolver;
    private DirectVideoView videoView;
    private Surface videoSurface;
    private HlsProxyServer proxy;
    private boolean proxyStatefulCmgSource;
    private boolean lowResourceDevice;
    private File cmgDebugDir;
    private IjkMediaPlayer player;
    private boolean prepared;
    private volatile int playRequestId;
    private int playerStartRetryCount;
    private int bufferingEventId;
    private int currentGroupIndex;
    private int currentChannelIndex;
    private int browsingGroupIndex;
    private int videoWidth;
    private int videoHeight;
    private int videoSarNum = 1;
    private int videoSarDen = 1;
    private long lastBackPressedAt;
    private long bufferingStartedAt;
    private long lastPlaybackProgressAt;
    private long lastPlaybackPosition = -1L;
    private boolean buffering;
    private boolean bufferingStatusVisible;
    private boolean playbackProgressObserved;
    private int stallRecoveryRequestId = -1;
    private AutoUpdater autoUpdater;

    private final Runnable updateVideoInfo = new Runnable() {
        @Override
        public void run() {
            refreshVideoInfo();
            if (player != null) {
                videoInfo.postDelayed(this, 1000L);
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        TlsCompat.install();
        configureResourceProfile();
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        applySystemUiVisibility();
        setContentView(R.layout.activity_main);

        root = findViewById(R.id.root);
        channelBar = findViewById(R.id.channel_bar);
        loadingPanel = findViewById(R.id.loading_panel);
        channelListPanel = findViewById(R.id.channel_list_panel);
        channelListTitle = (TextView) findViewById(R.id.channel_list_title);
        channelName = (TextView) findViewById(R.id.channel_name);
        statusText = (TextView) findViewById(R.id.status_text);
        videoInfo = (TextView) findViewById(R.id.video_info);
        loadingChannel = (TextView) findViewById(R.id.loading_channel);
        loadingStatus = (TextView) findViewById(R.id.loading_status);
        groupList = (ListView) findViewById(R.id.channel_group_list);
        channelList = (ListView) findViewById(R.id.channel_list);
        groupAdapter = new ChannelListAdapter(this);
        channelAdapter = new ChannelListAdapter(this);
        liveUrlResolver = new LiveUrlResolver(getSharedPreferences("live_url_resolver", MODE_PRIVATE));
        yangshipinResolver = new YangshipinWebResolver(this, (FrameLayout) root,
                getIntent().getBooleanExtra("cmg_keep_web_trace", false));
        groupList.setAdapter(groupAdapter);
        channelList.setAdapter(channelAdapter);
        groupList.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                showChannelMenu(position);
            }
        });
        channelList.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                switchBrowsingChannel(position);
            }
        });

        videoView = (DirectVideoView) findViewById(R.id.video_surface);
        View.OnClickListener openChannelsOnClick = new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                openChannelList();
            }
        };
        root.setOnClickListener(openChannelsOnClick);
        videoView.setOnClickListener(openChannelsOnClick);
        videoView.setSurfaceCallback(new DirectVideoView.SurfaceCallback() {
            @Override
            public void onVideoSurfaceCreated(Surface surface) {
                videoSurface = surface;
                if (player != null) {
                    player.setSurface(surface);
                }
            }

            @Override
            public void onVideoSurfaceDestroyed(Surface surface) {
                if (player != null && videoSurface == surface) {
                    player.setSurface(null);
                }
                if (videoSurface == surface) {
                    videoSurface = null;
                }
            }
        });
        root.requestFocus();
        autoUpdater = new AutoUpdater(this);
        autoUpdater.checkForUpdates();
        maybeProbeCmgRuntime();
        if (getIntent().hasExtra("cmg_compare")) {
            return;
        }

        SharedPreferences preferences = getSharedPreferences(PREFERENCES, MODE_PRIVATE);
        currentGroupIndex = ChannelCatalog.wrapGroupIndex(
                preferences.getInt(LAST_GROUP_INDEX, 0));
        int defaultIndex = ChannelCatalog.defaultChannelIndex(currentGroup());
        currentChannelIndex = ChannelCatalog.wrapIndex(currentGroup().channels,
                preferences.getInt(LAST_CHANNEL_INDEX, defaultIndex));
        browsingGroupIndex = currentGroupIndex;
        showChannelMenu(currentGroupIndex);

        try {
            cmgDebugDir = getIntent().getBooleanExtra("cmg_debug_dump", false)
                    ? getExternalFilesDir("cmg-debug") : null;
            switchChannel(currentChannelIndex);
        } catch (Exception error) {
            Log.e(TAG, "Unable to start player", error);
            showChannelBar(currentChannel().name,
                    "启动失败: " + error.getMessage());
        }
    }

    private void maybeProbeCmgRuntime() {
        if (getIntent().getExtras() != null) {
            Log.i(TAG, "Intent extras: " + getIntent().getExtras());
        }
        if (getIntent().hasExtra("cmg_compare")) {
            new Thread(new Runnable() {
                @Override
                public void run() {
                    runCmgCompareProbe();
                }
            }, "cmg-compare-probe").start();
            return;
        }
        if (getIntent().hasExtra("cmg_replay")) {
            new Thread(new Runnable() {
                @Override
                public void run() {
                    runCmgReplayProbe();
                }
            }, "cmg-replay-probe").start();
            return;
        }
        if (!getIntent().hasExtra("cmg_probe")) {
            return;
        }
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    Log.i(TAG, "CMG native probe start");
                    Log.i(TAG, "CMG native probe: " + NativeCmgDecryptor.probeRuntime());
                } catch (Throwable error) {
                    Log.e(TAG, "CMG native probe failed", error);
                }
            }
        }, "cmg-native-probe").start();
    }

    private void runCmgCompareProbe() {
        try {
            String playerTag = getIntent().getStringExtra("cmg_tag");
            if (playerTag == null) {
                playerTag = "1780652630064";
            }
            String updateTagText = getIntent().getStringExtra("cmg_update_tag");
            if (updateTagText == null) {
                updateTagText = "0";
            }
            int updateTag = (int) Long.parseLong(updateTagText, 16);
            Log.i(TAG, "CMG compare configure ok="
                    + NativeCmgDecryptor.configureRuntimeForProbe(playerTag, updateTag)
                    + " tag=" + playerTag + " updateTag=" + updateTagText);

            File dir = getExternalFilesDir(null);
            if (dir == null) {
                throw new IOException("External files dir unavailable");
            }
            String beforeName = getIntent().getStringExtra("cmg_before");
            if (beforeName == null) {
                beforeName = "official-cmg-nal-1-before.b64";
            }
            String afterName = getIntent().getStringExtra("cmg_after");
            if (afterName == null) {
                afterName = "official-cmg-nal-1-after.b64";
            }
            byte[] before = readBase64File(new File(dir, beforeName));
            byte[] officialAfter = readBase64File(new File(dir, afterName));
            String warmupName = getIntent().getStringExtra("cmg_warm_before");
            if (warmupName == null) {
                warmupName = "official-cmg-nal-0-before.b64";
            }
            File warmupFile = new File(dir, warmupName);
            if (warmupFile.exists()) {
                String warmupTagText = getIntent().getStringExtra("cmg_warm_update_tag");
                if (warmupTagText == null) {
                    warmupTagText = "6c34b9ae";
                }
                int warmupTag = (int) Long.parseLong(warmupTagText, 16);
                byte[] warmup = readBase64File(warmupFile);
                NativeCmgDecryptor.setUpdateTagForProbe(warmupTag);
                byte[] warmupAfter = NativeCmgDecryptor.decodeNalForProbe(warmup, true, true);
                Log.i(TAG, "CMG compare warmup tag=" + warmupTagText
                        + " len=" + warmup.length
                        + " out=" + (warmupAfter == null ? -1 : warmupAfter.length)
                        + " diff=" + (warmupAfter == null ? -1 : diffCount(warmup, warmupAfter)));
                NativeCmgDecryptor.setUpdateTagForProbe(updateTag);
            }
            int preStep = getIntent().getIntExtra("cmg_pre_step", -1);
            if (preStep >= 0 && preStep <= 8) {
                byte[] preStepAfter = NativeCmgDecryptor.decodeNalSingleStepForProbe(
                        before, true, preStep);
                Log.i(TAG, "CMG compare pre-step-" + preStep
                        + " out=" + (preStepAfter == null ? -1 : preStepAfter.length)
                        + " diff=" + (preStepAfter == null ? -1 : diffCount(before, preStepAfter)));
            }
            byte[] nativeAfter = NativeCmgDecryptor.decodeNalForProbe(before, true, true);
            if (nativeAfter == null) {
                Log.e(TAG, "CMG compare native output is null");
                return;
            }
            logByteCompare("official", before, officialAfter);
            logByteCompare("native", before, nativeAfter);
            logByteCompare("native-vs-official", officialAfter, nativeAfter);
            for (int step = 0; step <= 8; step++) {
                byte[] stepAfter = NativeCmgDecryptor.decodeNalSingleStepForProbe(
                        before, true, step);
                if (stepAfter == null) {
                    Log.e(TAG, "CMG compare native-step-" + step + " output is null");
                    continue;
                }
                logByteCompare("native-step-" + step, before, stepAfter);
            }
        } catch (Throwable error) {
            Log.e(TAG, "CMG compare failed", error);
        }
    }

    private void runCmgReplayProbe() {
        try {
            String playerTag = getIntent().getStringExtra("cmg_tag");
            if (playerTag == null) {
                playerTag = "player_container_player";
            }
            boolean forceOfficialTags = !getIntent().hasExtra("cmg_replay_no_force");
            boolean callNativeActive = !getIntent().hasExtra("cmg_replay_no_active");
            int targetIndex = getIntent().getIntExtra("cmg_replay_target", 71);
            Log.i(TAG, "CMG replay configure ok="
                    + NativeCmgDecryptor.configureRuntimeForProbe(playerTag, 0)
                    + " tag=" + playerTag
                    + " forceOfficialTags=" + forceOfficialTags
                    + " callNativeActive=" + callNativeActive
                    + " target=" + targetIndex);

            File dir = getExternalFilesDir(null);
            if (dir == null) {
                throw new IOException("External files dir unavailable");
            }
            String manifestName = getIntent().getStringExtra("cmg_replay_manifest");
            if (manifestName == null) {
                manifestName = "cmg-replay-manifest.txt";
            }
            String[] lines = new String(readFile(new File(dir, manifestName)), "UTF-8")
                    .split("\\r?\\n");
            int firstActiveMismatch = -1;
            int firstDecodeMismatch = -1;
            int decodedCount = 0;
            int activeCount = 0;
            for (String line : lines) {
                if (line == null || line.length() == 0 || line.startsWith("#")) {
                    continue;
                }
                String[] parts = line.split("\\|", -1);
                if (parts.length < 7) {
                    Log.w(TAG, "CMG replay skip malformed line: " + line);
                    continue;
                }
                int index = Integer.parseInt(parts[0]);
                int nalType = Integer.parseInt(parts[1]);
                String officialTagText = parts[2];
                boolean decoded = "1".equals(parts[3]);
                String beforeName = parts[4];
                String expectedName = parts[5];
                int officialDiff = Integer.parseInt(parts[6]);

                int nativeTag = 0;
                if (callNativeActive) {
                    nativeTag = NativeCmgDecryptor.updateSessionForProbe();
                    activeCount++;
                    int officialTag = parseHexUpdateTag(officialTagText);
                    if (firstActiveMismatch < 0 && officialTag != 0 && nativeTag != officialTag) {
                        firstActiveMismatch = index;
                        Log.i(TAG, "CMG replay first active mismatch index=" + index
                                + " type=" + nalType
                                + " nativeTag=" + String.format(Locale.US, "%08x", nativeTag)
                                + " officialTag=" + officialTagText);
                    }
                }
                if (!decoded) {
                    if (index <= 8 || index == targetIndex || firstActiveMismatch == index) {
                        Log.i(TAG, "CMG replay active-only index=" + index
                                + " type=" + nalType
                                + " nativeTag=" + String.format(Locale.US, "%08x", nativeTag)
                                + " officialTag=" + officialTagText);
                    }
                    continue;
                }

                byte[] before = readBase64File(new File(dir, beforeName));
                byte[] expected = readBase64File(new File(dir, expectedName));
                if (forceOfficialTags) {
                    NativeCmgDecryptor.setUpdateTagForProbe(parseHexUpdateTag(officialTagText));
                }
                byte[] actual = NativeCmgDecryptor.decodeNalForProbe(before, true, true);
                decodedCount++;
                if (actual == null) {
                    Log.e(TAG, "CMG replay native null index=" + index + " type=" + nalType);
                    if (firstDecodeMismatch < 0) {
                        firstDecodeMismatch = index;
                    }
                    continue;
                }
                int expectedDiff = diffCount(before, expected);
                int actualDiff = diffCount(before, actual);
                int nativeVsOfficial = diffCount(expected, actual);
                int firstNativeVsOfficial = firstDiff(expected, actual);
                if (nativeVsOfficial != 0 && firstDecodeMismatch < 0) {
                    firstDecodeMismatch = index;
                    Log.i(TAG, "CMG replay first decode mismatch index=" + index
                            + " type=" + nalType
                            + " officialTag=" + officialTagText
                            + " nativeTag=" + String.format(Locale.US, "%08x", nativeTag)
                            + " officialDiff=" + expectedDiff
                            + " actualDiff=" + actualDiff
                            + " nativeVsOfficial=" + nativeVsOfficial
                            + " firstDiff=" + firstNativeVsOfficial
                            + " expectedHead64=" + headHex(expected, 64)
                            + " actualHead64=" + headHex(actual, 64));
                }
                if (index <= 8 || index == targetIndex || nativeVsOfficial != 0) {
                    Log.i(TAG, "CMG replay step index=" + index
                            + " type=" + nalType
                            + " officialTag=" + officialTagText
                            + " nativeTag=" + String.format(Locale.US, "%08x", nativeTag)
                            + " officialDiff=" + officialDiff
                            + " expectedDiff=" + expectedDiff
                            + " actualDiff=" + actualDiff
                            + " nativeVsOfficial=" + nativeVsOfficial);
                }
            }
            Log.i(TAG, "CMG replay summary activeCount=" + activeCount
                    + " decodedCount=" + decodedCount
                    + " firstActiveMismatch=" + firstActiveMismatch
                    + " firstDecodeMismatch=" + firstDecodeMismatch);
        } catch (Throwable error) {
            Log.e(TAG, "CMG replay failed", error);
        }
    }

    private static byte[] readBase64File(File file) throws IOException {
        String text = new String(readFile(file), "US-ASCII");
        return Base64.decode(text.trim(), Base64.DEFAULT);
    }

    private static byte[] readFile(File file) throws IOException {
        FileInputStream input = new FileInputStream(file);
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream((int) file.length());
            byte[] buffer = new byte[16 * 1024];
            int count;
            while ((count = input.read(buffer)) != -1) {
                output.write(buffer, 0, count);
            }
            return output.toByteArray();
        } finally {
            input.close();
        }
    }

    private static void logByteCompare(String label, byte[] expectedBase, byte[] actual) {
        Log.i(TAG, "CMG compare " + label
                + " baseLen=" + expectedBase.length
                + " actualLen=" + actual.length
                + " firstDiff=" + firstDiff(expectedBase, actual)
                + " diffCount=" + diffCount(expectedBase, actual)
                + " baseSha256=" + sha256Hex(expectedBase)
                + " actualSha256=" + sha256Hex(actual)
                + " actualHead64=" + headHex(actual, 64));
    }

    private static int firstDiff(byte[] left, byte[] right) {
        int length = Math.min(left.length, right.length);
        for (int index = 0; index < length; index++) {
            if (left[index] != right[index]) {
                return index;
            }
        }
        return left.length == right.length ? -1 : length;
    }

    private static int diffCount(byte[] left, byte[] right) {
        int length = Math.min(left.length, right.length);
        int count = Math.abs(left.length - right.length);
        for (int index = 0; index < length; index++) {
            if (left[index] != right[index]) {
                count++;
            }
        }
        return count;
    }

    private static String sha256Hex(byte[] data) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return hex(digest.digest(data), digest.getDigestLength());
        } catch (NoSuchAlgorithmException error) {
            return "sha256-unavailable";
        }
    }

    private static String headHex(byte[] data, int maxLength) {
        int length = Math.min(data.length, maxLength);
        byte[] head = new byte[length];
        System.arraycopy(data, 0, head, 0, length);
        return hex(head, length);
    }

    private static String hex(byte[] data, int length) {
        char[] chars = new char[length * 2];
        final char[] digits = "0123456789abcdef".toCharArray();
        for (int index = 0; index < length; index++) {
            int value = data[index] & 0xff;
            chars[index * 2] = digits[value >>> 4];
            chars[index * 2 + 1] = digits[value & 0x0f];
        }
        return new String(chars);
    }

    private void applySystemUiVisibility() {
        int flags = View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_FULLSCREEN;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            flags |= View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY;
        }
        getWindow().getDecorView().setSystemUiVisibility(flags);
    }

    private ChannelCatalog.Group currentGroup() {
        return ChannelCatalog.GROUPS[currentGroupIndex];
    }

    private Channel currentChannel() {
        return currentGroup().channels[currentChannelIndex];
    }

    private void switchChannel(int index) {
        final ChannelCatalog.Group group = currentGroup();
        currentChannelIndex = ChannelCatalog.wrapIndex(group.channels, index);
        final Channel channel = group.channels[currentChannelIndex];
        getSharedPreferences(PREFERENCES, MODE_PRIVATE).edit()
                .putInt(LAST_GROUP_INDEX, currentGroupIndex)
                .putInt(LAST_CHANNEL_INDEX, currentChannelIndex)
                .apply();
        if (channelListPanel.getVisibility() == View.VISIBLE) {
            groupAdapter.setSelectedIndex(currentGroupIndex);
            channelAdapter.setSelectedIndex(currentChannelIndex);
            groupList.setSelection(currentGroupIndex);
            channelList.setSelection(currentChannelIndex);
        }
        final int requestId = ++playRequestId;
        playerStartRetryCount = 0;
        releasePlayer();
        resetVideoLayout();
        showLoading(channel.name, "正在准备直播");
        try {
            resetProxyForChannelSwitch();
        } catch (IOException error) {
            Log.e(TAG, "Unable to reset proxy for channel switch", error);
            hideLoading();
            showChannelBar(channel.name, "切换失败: " + error.getMessage());
            return;
        }
        if (group.source == ChannelCatalog.SOURCE_CCTV_WEB) {
            resolveFallbackUrl(channel, requestId);
            return;
        }
        resolveYangshipinUrl(channel, requestId);
    }

    private void configureResourceProfile() {
        ActivityManager manager = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
        int memoryClassMb = manager == null ? 0 : manager.getMemoryClass();
        int largeMemoryClassMb = manager == null ? 0 : manager.getLargeMemoryClass();
        boolean systemLowRam = Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT
                && manager != null && manager.isLowRamDevice();
        lowResourceDevice = Build.VERSION.SDK_INT <= Build.VERSION_CODES.KITKAT
                || systemLowRam
                || (memoryClassMb > 0 && memoryClassMb <= 64);
        Log.i(TAG, "Resource profile low=" + lowResourceDevice
                + " memoryClassMb=" + memoryClassMb
                + " largeMemoryClassMb=" + largeMemoryClassMb
                + " heapLimitMb=" + (Runtime.getRuntime().maxMemory() / (1024L * 1024L)));
    }

    private void resetProxyForChannelSwitch() throws IOException {
        boolean statefulCmgSource = currentGroup().source != ChannelCatalog.SOURCE_CCTV_WEB;
        HlsProxyServer.resetCmgSessionForChannelSwitch();
        HlsProxyServer previous = proxy;
        proxy = null;
        if (previous != null) {
            // A CCTV proxy may still have prefetched segments queued for decryption.
            // Closing it first cancels the old stateful H5E session before the new one starts.
            previous.close();
        }
        HlsProxyServer next = new HlsProxyServer(
                cmgDebugDir, statefulCmgSource, lowResourceDevice);
        next.start();
        proxy = next;
        proxyStatefulCmgSource = statefulCmgSource;
    }

    private void resolveYangshipinUrl(final Channel channel, final int requestId) {
        if (channel.yangshipinPid == null) {
            resolveFallbackUrl(channel, requestId);
            return;
        }
        updateLoadingStatus("正在获取央视频线路");
        showChannelBar(channel.name, "正在解析央视频源");
        yangshipinResolver.resolve(requestId, channel, new YangshipinWebResolver.Callback() {
            @Override
            public void onResolved(int resolvedRequestId, String url,
                    String cmgTag, String cmgInitialUpdateTag, String cmgUpdateTag,
                    int cmgUpdateWarmupCount, long cmgInitTimeMs,
                    long cmgUpdateBaseTimeMs, String cmgUpdateTrace,
                    String cmgNativeTrace) {
                if (resolvedRequestId != playRequestId) {
                    return;
                }
                if (cmgTag != null && cmgTag.length() > 0) {
                    int initialUpdateTag = parseHexUpdateTag(cmgInitialUpdateTag);
                    int updateTag = parseHexUpdateTag(cmgUpdateTag);
                    HlsProxyServer.configureCmgDebugContext(cmgTag,
                            cmgInitialUpdateTag, cmgUpdateTag,
                            cmgInitTimeMs, cmgUpdateBaseTimeMs, cmgUpdateTrace);
                    HlsProxyServer.configureCmgUpdateTags(initialUpdateTag, updateTag);
                    NativeCmgDecryptor.configureLocationForProbe(
                            "https://www.yangshipin.cn/tv/home?pid=" + channel.yangshipinPid);
                    boolean configured = NativeCmgDecryptor.configureRuntimeForProbe(cmgTag, 0);
                    CmgWarmupResult warmup = configured
                            ? warmupCmgUpdateSession(cmgUpdateWarmupCount,
                                    cmgInitTimeMs, cmgUpdateBaseTimeMs, cmgUpdateTrace,
                                    cmgNativeTrace, initialUpdateTag, updateTag)
                            : CmgWarmupResult.empty();
                    HlsProxyServer.configureCmgRuntimeClock(
                            cmgUpdateBaseTimeMs > 0L ? cmgUpdateBaseTimeMs : cmgInitTimeMs,
                            warmup.clockOffsetMs);
                    Log.i(TAG, "Configured CMG runtime from Yangshipin tag="
                            + cmgTag + " initialTag=" + cmgInitialUpdateTag
                            + " updateTag=" + cmgUpdateTag + " ok=" + configured
                            + " warmup=" + warmup.count + "/" + cmgUpdateWarmupCount
                            + " initTime=" + cmgInitTimeMs
                            + " clockOffsetMs=" + warmup.clockOffsetMs
                            + " traceLen=" + (cmgUpdateTrace == null ? 0 : cmgUpdateTrace.length()));
                }
                startResolvedPlayer(channel, url);
            }

            @Override
            public void onFailed(int resolvedRequestId, String reason) {
                if (resolvedRequestId != playRequestId) {
                    return;
                }
                if (channel.url != null) {
                    Log.w(TAG, "Falling back to VDN for " + channel.name + ": " + reason);
                    resolveFallbackUrl(channel, requestId);
                } else {
                    Log.w(TAG, "YSP resolve failed for " + channel.name + ": " + reason);
                    hideLoading();
                    showChannelBar(channel.name, "央视频源解析失败: " + reason);
                }
            }
        });
    }

    private static int parseHexUpdateTag(String text) {
        if (text == null || text.length() == 0) {
            return 0;
        }
        try {
            return (int) Long.parseLong(text, 16);
        } catch (NumberFormatException error) {
            Log.w(TAG, "Invalid CMG update tag: " + text);
            return 0;
        }
    }

    private static CmgWarmupResult warmupCmgUpdateSession(int requestedCount, long initTimeMs,
            long baseTimeMs, String trace, String nativeTrace, int targetInitTag,
            int targetUpdateTag) {
        int count = Math.max(0, Math.min(requestedCount, 96));
        String[] entries = trace == null || trace.length() == 0
                ? new String[0] : trace.split(";");
        if (count == 0 && targetInitTag == 0 && targetUpdateTag == 0
                && entries.length == 0
                && (nativeTrace == null || nativeTrace.length() == 0)) {
            return CmgWarmupResult.empty();
        }
        int clockOffsetMs = 0;
        if (initTimeMs > 0L) {
            int matchedOffset = initializeCmgAtOfficialInitTag(initTimeMs, targetInitTag);
            clockOffsetMs = matchedOffset;
            Log.i(TAG, "CMG native traced InitPlayer time=" + initTimeMs
                    + " offset=" + matchedOffset
                    + " initResult=" + String.format(Locale.US, "%08x",
                    NativeCmgDecryptor.getPlayerInitResultForProbe()));
        }
        if (nativeTrace != null && nativeTrace.length() > 0) {
            int replayTag = NativeCmgDecryptor.replayOfficialTraceForProbe(
                    nativeTrace, trace, baseTimeMs, clockOffsetMs);
            Log.i(TAG, "CMG native official trace replay tag="
                    + String.format(Locale.US, "%08x", replayTag)
                    + " target=" + String.format(Locale.US, "%08x", targetUpdateTag)
                    + " traceLen=" + nativeTrace.length());
            if (replayTag != 0) {
                NativeCmgDecryptor.clearClockForProbe();
                return new CmgWarmupResult(count, clockOffsetMs);
            }
        }
        if (baseTimeMs > 0L && entries.length > 0) {
            int tracedCount = Math.min(count, entries.length);
            int firstMismatch = -1;
            int lastTag = 0;
            for (int index = 0; index < tracedCount; index++) {
                String[] parts = entries[index].split(",", -1);
                long deltaMs = parsePositiveLong(parts.length > 0 ? parts[0] : "");
                String officialTagText = parts.length > 1 ? parts[1] : "";
                NativeCmgDecryptor.setClockForProbe(baseTimeMs + deltaMs + clockOffsetMs);
                lastTag = NativeCmgDecryptor.updateSessionForProbe();
                int officialTag = parseHexUpdateTag(officialTagText);
                if (firstMismatch < 0 && officialTag != 0 && lastTag != officialTag) {
                    firstMismatch = index;
                    Log.i(TAG, "CMG traced warmup first tag mismatch index=" + index
                            + " nativeTag=" + String.format(Locale.US, "%08x", lastTag)
                            + " officialTag=" + officialTagText
                            + " deltaMs=" + deltaMs);
                }
            }
            NativeCmgDecryptor.clearClockForProbe();
            Log.i(TAG, "CMG native traced UpdatePlayer warmup count=" + tracedCount
                    + "/" + count + " lastTag=" + String.format(Locale.US, "%08x", lastTag)
                    + " firstMismatch=" + firstMismatch
                    + " baseTimeMs=" + baseTimeMs);
            return new CmgWarmupResult(tracedCount, clockOffsetMs);
        }
        int lastTag = 0;
        for (int index = 0; index < count; index++) {
            lastTag = NativeCmgDecryptor.updateSessionForProbe();
        }
        NativeCmgDecryptor.clearClockForProbe();
        if (count > 0) {
            Log.i(TAG, "CMG native UpdatePlayer warmup count=" + count
                    + " lastTag=" + String.format(Locale.US, "%08x", lastTag));
        }
        return new CmgWarmupResult(count, clockOffsetMs);
    }

    private static final class CmgWarmupResult {
        final int count;
        final int clockOffsetMs;

        CmgWarmupResult(int count, int clockOffsetMs) {
            this.count = count;
            this.clockOffsetMs = clockOffsetMs;
        }

        static CmgWarmupResult empty() {
            return new CmgWarmupResult(0, 0);
        }
    }

    private static int initializeCmgAtOfficialInitTag(long initTimeMs, int targetInitTag) {
        int bestOffset = 0;
        int bestResult = 0;
        int[] offsets = new int[121];
        offsets[0] = 0;
        int count = 1;
        for (int offset = 1; offset <= 60; offset++) {
            offsets[count++] = offset;
            offsets[count++] = -offset;
        }
        for (int index = 0; index < count; index++) {
            int offset = offsets[index];
            NativeCmgDecryptor.resetRuntimeForProbe();
            NativeCmgDecryptor.setClockForProbe(initTimeMs + offset);
            if (!NativeCmgDecryptor.initializeRuntimeForProbe()) {
                continue;
            }
            int result = NativeCmgDecryptor.getPlayerInitResultForProbe();
            if (index == 0) {
                bestResult = result;
            }
            if (targetInitTag != 0 && result == targetInitTag) {
                Log.i(TAG, "CMG native InitPlayer matched official tag="
                        + String.format(Locale.US, "%08x", targetInitTag)
                        + " offsetMs=" + offset);
                return offset;
            }
            bestOffset = offset;
        }
        NativeCmgDecryptor.resetRuntimeForProbe();
        NativeCmgDecryptor.setClockForProbe(initTimeMs);
        NativeCmgDecryptor.initializeRuntimeForProbe();
        Log.w(TAG, "CMG native InitPlayer did not match official tag target="
                + String.format(Locale.US, "%08x", targetInitTag)
                + " first=" + String.format(Locale.US, "%08x", bestResult)
                + " searchedOffsetMs=" + bestOffset);
        return 0;
    }

    private static void waitForCmgUpdateTag(int currentTag, int targetTag) {
        if (targetTag == 0 || currentTag == targetTag) {
            return;
        }
        long deadline = android.os.SystemClock.elapsedRealtime() + 1500L;
        int lastTag = currentTag;
        int attempts = 0;
        while (android.os.SystemClock.elapsedRealtime() < deadline) {
            attempts++;
            lastTag = NativeCmgDecryptor.updateSessionForProbe();
            if (lastTag == targetTag) {
                Log.i(TAG, "CMG native reached official updateTag="
                        + String.format(Locale.US, "%08x", targetTag)
                        + " attempts=" + attempts);
                return;
            }
            try {
                Thread.sleep(10L);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        Log.w(TAG, "CMG native did not reach official updateTag target="
                + String.format(Locale.US, "%08x", targetTag)
                + " last=" + String.format(Locale.US, "%08x", lastTag)
                + " attempts=" + attempts);
    }

    private static long parsePositiveLong(String text) {
        if (text == null || text.length() == 0) {
            return 0L;
        }
        try {
            long value = Long.parseLong(text);
            return Math.max(0L, value);
        } catch (NumberFormatException error) {
            return 0L;
        }
    }

    private void resolveFallbackUrl(final Channel channel, final int requestId) {
        if (channel.url == null) {
            hideLoading();
            showChannelBar(channel.name, "没有可用的备用源");
            return;
        }
        updateLoadingStatus("正在获取高清线路");
        showChannelBar(channel.name, "正在解析备用源");
        new Thread(new Runnable() {
            @Override
            public void run() {
                String streamUrl = channel.url;
                try {
                    streamUrl = liveUrlResolver.resolve(channel);
                } catch (IOException error) {
                    Log.w(TAG, "Falling back to static HLS for " + channel.name, error);
                }
                final String resolvedUrl = streamUrl;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (requestId != playRequestId) {
                            return;
                        }
                        startResolvedPlayer(channel, resolvedUrl);
                    }
                });
            }
        }, "live-url-resolve").start();
    }

    private void startResolvedPlayer(Channel channel, String streamUrl) {
        updateLoadingStatus("正在连接视频");
        try {
            startPlayer(channel, streamUrl);
        } catch (IOException error) {
            Log.e(TAG, "Unable to play " + channel.name, error);
            hideLoading();
            showChannelBar(channel.name, "连接失败: " + error.getMessage());
        }
    }

    private void startPlayer(final Channel channel, final String streamUrl) throws IOException {
        releasePlayer();
        resetVideoLayout();
        IjkMediaPlayer.loadLibrariesOnce(null);

        final IjkMediaPlayer nextPlayer = new IjkMediaPlayer();
        player = nextPlayer;
        boolean softwareDecode = getIntent().getBooleanExtra("debug_software_decode", false);
        nextPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "mediacodec",
                softwareDecode ? 0 : 1);
        nextPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "mediacodec-auto-rotate", 1);
        nextPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER,
                "mediacodec-handle-resolution-change", 1);
        nextPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "an", 0);
        nextPlayer.setVolume(1.0f, 1.0f);
        nextPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "framedrop", 1);
        nextPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "packet-buffering", 1);
        final boolean cctvSource = currentGroup().source == ChannelCatalog.SOURCE_CCTV_WEB;
        nextPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "min-frames",
                cctvSource ? 100 : 60);
        nextPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "infbuf", 0);
        nextPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "sync-av-start", 1);
        nextPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "max_cached_duration", 30000);
        nextPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "first-high-water-mark-ms",
                cctvSource ? 5000 : 3500);
        nextPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "next-high-water-mark-ms", 5000);
        nextPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "last-high-water-mark-ms", 5000);
        nextPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "reconnect", 1);
        /* Every channel switch creates a localhost proxy on a new port. IJK 0.8.8
         * can retain an empty localhost DNS-cache entry from the closed proxy,
         * making the first connection to the new port fail spuriously. */
        nextPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "dns_cache_clear", 1);
        nextPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "http-detect-range-support", 0);
        nextPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "probesize", 256 * 1024);
        nextPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "live_start_index",
                cctvSource ? -1 : -3);
        if (videoSurface != null) {
            nextPlayer.setSurface(videoSurface);
        }
        nextPlayer.setOnVideoSizeChangedListener(new IMediaPlayer.OnVideoSizeChangedListener() {
            @Override
            public void onVideoSizeChanged(IMediaPlayer mediaPlayer, int width, int height,
                    int sarNum, int sarDen) {
                if (player != mediaPlayer) {
                    return;
                }
                updateVideoLayout(mediaPlayer);
            }
        });
        nextPlayer.setOnPreparedListener(new IMediaPlayer.OnPreparedListener() {
            @Override
            public void onPrepared(IMediaPlayer mediaPlayer) {
                if (player != mediaPlayer) {
                    return;
                }
                prepared = true;
                lastPlaybackProgressAt = SystemClock.elapsedRealtime();
                lastPlaybackPosition = -1L;
                playbackProgressObserved = false;
                updateVideoLayout(mediaPlayer);
                mediaPlayer.start();
                scheduleVideoInfoRefresh();
                prefetchNearbyChannels(channel);
                hideLoading();
                showChannelBar(channel.name, "直播播放中");
            }
        });
        nextPlayer.setOnInfoListener(new IMediaPlayer.OnInfoListener() {
            @Override
            public boolean onInfo(IMediaPlayer mediaPlayer, int what, int extra) {
                if (player != mediaPlayer) {
                    return false;
                }
                if (what == IMediaPlayer.MEDIA_INFO_BUFFERING_START) {
                    buffering = true;
                    bufferingStartedAt = SystemClock.elapsedRealtime();
                    final int eventId = ++bufferingEventId;
                    final int requestId = playRequestId;
                    final IjkMediaPlayer watchedPlayer = nextPlayer;
                    channelBar.postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            if (buffering && eventId == bufferingEventId
                                    && requestId == playRequestId) {
                                bufferingStatusVisible = true;
                                showChannelBar(channel.name, "正在缓冲");
                            }
                        }
                    }, 400L);
                    if (cctvSource) {
                        channelBar.postDelayed(new Runnable() {
                            @Override
                            public void run() {
                                if (buffering && eventId == bufferingEventId
                                        && requestId == playRequestId
                                        && player == watchedPlayer) {
                                    recoverCctvPlayback(requestId, watchedPlayer,
                                            "buffering for " + CCTV_BUFFERING_RECOVERY_MS + "ms");
                                }
                            }
                        }, CCTV_BUFFERING_RECOVERY_MS);
                    }
                } else if (what == IMediaPlayer.MEDIA_INFO_BUFFERING_END) {
                    long elapsed = buffering
                            ? SystemClock.elapsedRealtime() - bufferingStartedAt : 0L;
                    buffering = false;
                    bufferingEventId++;
                    if (bufferingStatusVisible) {
                        bufferingStatusVisible = false;
                        showChannelBar(channel.name, "直播播放中");
                    }
                    if (elapsed >= 250L) {
                        Log.i(TAG, "Buffering recovered channel=" + channel.name
                                + " elapsedMs=" + elapsed);
                    }
                }
                return false;
            }
        });
        nextPlayer.setOnErrorListener(new IMediaPlayer.OnErrorListener() {
            @Override
            public boolean onError(IMediaPlayer mediaPlayer, int what, int extra) {
                if (player == mediaPlayer) {
                    if (playerStartRetryCount < 2) {
                        final int requestId = playRequestId;
                        final IMediaPlayer failedPlayer = mediaPlayer;
                        final int retry = ++playerStartRetryCount;
                        Log.w(TAG, "Player start failed; retrying local proxy request "
                                + retry + "/2 error=" + what + "/" + extra);
                        channelBar.postDelayed(new Runnable() {
                            @Override
                            public void run() {
                                if (requestId != playRequestId || player != failedPlayer) {
                                    return;
                                }
                                try {
                                    startPlayer(channel, streamUrl);
                                } catch (IOException error) {
                                    Log.e(TAG, "Unable to retry " + channel.name, error);
                                }
                            }
                        }, 500L);
                        return true;
                    }
                    hideLoading();
                    showChannelBar(channel.name, "播放错误: " + what + "/" + extra);
                }
                return true;
            }
        });
        nextPlayer.setDataSource(proxy.proxyUrl(streamUrl));
        nextPlayer.prepareAsync();
    }

    private void recoverCctvPlayback(int requestId, IMediaPlayer watchedPlayer, String reason) {
        if (requestId != playRequestId || player != watchedPlayer
                || currentGroup().source != ChannelCatalog.SOURCE_CCTV_WEB
                || stallRecoveryRequestId == requestId) {
            return;
        }
        stallRecoveryRequestId = requestId;
        Log.w(TAG, "Recovering stalled CCTV playback at live edge: " + reason);
        switchChannel(currentChannelIndex);
    }

    private void prefetchNearbyChannels(final Channel playingChannel) {
        final ChannelCatalog.Group group = currentGroup();
        if (group.source != ChannelCatalog.SOURCE_CCTV_WEB
                || group.channels[currentChannelIndex] != playingChannel) {
            return;
        }
        final Channel previous = group.channels[ChannelCatalog.wrapIndex(
                group.channels, currentChannelIndex - 1)];
        final Channel next = group.channels[ChannelCatalog.wrapIndex(
                group.channels, currentChannelIndex + 1)];
        final int requestId = playRequestId;
        channelBar.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (requestId != playRequestId) {
                    return;
                }
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        prefetchChannel(next);
                    }
                }, "channel-url-prefetch-next").start();
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        prefetchChannel(previous);
                    }
                }, "channel-url-prefetch-previous").start();
            }
        }, CHANNEL_PREFETCH_DELAY_MS);
    }

    private void prefetchChannel(Channel channel) {
        try {
            liveUrlResolver.resolve(channel);
        } catch (IOException error) {
            Log.d(TAG, "Unable to prefetch " + channel.streamId, error);
        }
    }

    private void switchRelative(int offset) {
        switchChannel(currentChannelIndex + offset);
    }

    private void togglePlayback() {
        Channel channel = currentChannel();
        if (player == null || !prepared) {
            switchChannel(currentChannelIndex);
        } else if (player.isPlaying()) {
            player.pause();
            showChannelBar(channel.name, "已暂停");
        } else {
            player.start();
            showChannelBar(channel.name, "直播播放中");
        }
    }

    private void switchBrowsingChannel(int position) {
        currentGroupIndex = browsingGroupIndex;
        switchChannel(position);
        closeChannelList();
    }

    private void openChannelList() {
        lastBackPressedAt = 0L;
        channelListPanel.setVisibility(View.VISIBLE);
        showChannelMenu(currentGroupIndex);
        groupList.post(new Runnable() {
            @Override
            public void run() {
                groupList.requestFocus();
            }
        });
        scheduleChannelListDismiss();
    }

    private void showChannelMenu(int groupIndex) {
        browsingGroupIndex = ChannelCatalog.wrapGroupIndex(groupIndex);
        ChannelCatalog.Group group = ChannelCatalog.GROUPS[browsingGroupIndex];
        int selectedIndex = browsingGroupIndex == currentGroupIndex
                ? currentChannelIndex : ChannelCatalog.defaultChannelIndex(group);
        channelListTitle.setText(getString(R.string.channel_panel_title,
                group.title, group.channels.length));
        groupAdapter.showGroups(ChannelCatalog.GROUPS, browsingGroupIndex);
        channelAdapter.showChannels(group.channels, selectedIndex);
        groupList.setSelection(browsingGroupIndex);
        channelList.setSelection(selectedIndex);
        scheduleChannelListDismiss();
    }

    private void closeChannelList() {
        channelListPanel.removeCallbacks(hideChannelList);
        channelListPanel.setVisibility(View.GONE);
        root.requestFocus();
    }

    private void scheduleChannelListDismiss() {
        channelListPanel.removeCallbacks(hideChannelList);
        channelListPanel.postDelayed(hideChannelList, PANEL_TIMEOUT_MS);
    }

    private void showChannelBar(final String channel, final String status) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                channelBar.removeCallbacks(hideChannelBar);
                channelName.setText(channel);
                statusText.setText(status);
                channelBar.setVisibility(View.VISIBLE);
                channelBar.postDelayed(hideChannelBar, CHANNEL_BAR_TIMEOUT_MS);
            }
        });
    }

    private void showLoading(final String channel, final String status) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                loadingPanel.animate().cancel();
                loadingChannel.setText(channel);
                loadingStatus.setText(status);
                if (loadingPanel.getVisibility() != View.VISIBLE) {
                    loadingPanel.setAlpha(0f);
                    loadingPanel.setScaleX(0.96f);
                    loadingPanel.setScaleY(0.96f);
                    loadingPanel.setVisibility(View.VISIBLE);
                    loadingPanel.animate().alpha(1f).scaleX(1f).scaleY(1f)
                            .setDuration(160L).start();
                }
            }
        });
    }

    private void updateLoadingStatus(final String status) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                loadingStatus.setText(status);
            }
        });
    }

    private void hideLoading() {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                loadingPanel.animate().cancel();
                loadingPanel.setVisibility(View.GONE);
            }
        });
    }

    private void releasePlayer() {
        prepared = false;
        buffering = false;
        bufferingStatusVisible = false;
        bufferingEventId++;
        playbackProgressObserved = false;
        lastPlaybackPosition = -1L;
        lastPlaybackProgressAt = 0L;
        if (videoInfo != null) {
            videoInfo.removeCallbacks(updateVideoInfo);
        }
        if (player != null) {
            player.setSurface(null);
            player.release();
            player = null;
        }
    }

    private void resetVideoLayout() {
        videoWidth = 0;
        videoHeight = 0;
        videoSarNum = 1;
        videoSarDen = 1;
        videoView.setVideoSize(0, 0, 1, 1);
        refreshVideoInfo();
    }

    private void updateVideoLayout(IMediaPlayer mediaPlayer) {
        videoWidth = mediaPlayer.getVideoWidth();
        videoHeight = mediaPlayer.getVideoHeight();
        videoSarNum = mediaPlayer.getVideoSarNum();
        videoSarDen = mediaPlayer.getVideoSarDen();
        if (videoSarNum <= 0) {
            videoSarNum = 1;
        }
        if (videoSarDen <= 0) {
            videoSarDen = 1;
        }
        videoView.setVideoSize(videoWidth, videoHeight, videoSarNum, videoSarDen);
        refreshVideoInfo();
        Log.i(TAG, "Video source=" + videoWidth + "x" + videoHeight
                + " sar=" + videoSarNum + "/" + videoSarDen);
    }

    private void scheduleVideoInfoRefresh() {
        videoInfo.removeCallbacks(updateVideoInfo);
        videoInfo.post(updateVideoInfo);
    }

    @SuppressLint("SetTextI18n")
    private void refreshVideoInfo() {
        if (videoInfo == null) {
            return;
        }
        String resolution = videoWidth > 0 && videoHeight > 0
                ? videoWidth + "x" + videoHeight : "--";
        float outputFps = 0f;
        float decodeFps = 0f;
        if (player != null) {
            outputFps = player.getVideoOutputFramesPerSecond();
            decodeFps = player.getVideoDecodeFramesPerSecond();
        }
        if (prepared && currentGroup().source == ChannelCatalog.SOURCE_CCTV_WEB) {
            long now = SystemClock.elapsedRealtime();
            long playbackPosition = player.getCurrentPosition();
            if (playbackPosition > lastPlaybackPosition) {
                playbackProgressObserved = true;
                lastPlaybackPosition = playbackPosition;
                lastPlaybackProgressAt = now;
            } else if (playbackProgressObserved && !buffering && lastPlaybackProgressAt > 0L
                    && now - lastPlaybackProgressAt >= CCTV_VIDEO_STALL_RECOVERY_MS) {
                recoverCctvPlayback(playRequestId, player,
                        "playback clock stopped for "
                                + (now - lastPlaybackProgressAt) + "ms");
            }
        }
        String fps = outputFps > 0.01f
                ? String.format(Locale.US, "%.1f/%.1f", outputFps, decodeFps) : "--";
        videoInfo.setText("源: " + resolution + "  fps: " + fps);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        int keyCode = event.getKeyCode();
        if (channelListPanel.getVisibility() == View.VISIBLE) {
            scheduleChannelListDismiss();
            if (event.getAction() == KeyEvent.ACTION_UP
                    && (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_MENU)) {
                closeChannelList();
                return true;
            }
            if (event.getAction() == KeyEvent.ACTION_UP
                    && keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
                groupList.requestFocus();
                return true;
            }
            if (event.getAction() == KeyEvent.ACTION_UP
                    && keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                channelList.requestFocus();
                return true;
            }
            if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                return true;
            }
            return super.dispatchKeyEvent(event);
        }

        if (event.getAction() != KeyEvent.ACTION_UP) {
            return super.dispatchKeyEvent(event);
        }
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_UP:
                switchRelative(-1);
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                switchRelative(1);
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_MENU:
                openChannelList();
                return true;
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
                togglePlayback();
                return true;
            default:
                return super.dispatchKeyEvent(event);
        }
    }

    @Override
    public void onBackPressed() {
        if (channelListPanel.getVisibility() == View.VISIBLE) {
            closeChannelList();
            return;
        }
        long now = SystemClock.elapsedRealtime();
        if (now - lastBackPressedAt <= EXIT_CONFIRM_TIMEOUT_MS) {
            finish();
            return;
        }
        lastBackPressedAt = now;
        Toast.makeText(this, R.string.press_back_again_to_exit, Toast.LENGTH_SHORT).show();
    }

    @Override
    protected void onPause() {
        if (videoView != null) {
            videoView.onPause();
        }
        super.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (autoUpdater != null) {
            autoUpdater.onResume();
        }
        if (videoView != null) {
            videoView.onResume();
        }
        applySystemUiVisibility();
    }

    @Override
    protected void onDestroy() {
        playRequestId++;
        if (autoUpdater != null) {
            autoUpdater.destroy();
        }
        releasePlayer();
        if (yangshipinResolver != null) {
            yangshipinResolver.destroy();
        }
        if (proxy != null) {
            proxy.close();
        }
        super.onDestroy();
    }
}
