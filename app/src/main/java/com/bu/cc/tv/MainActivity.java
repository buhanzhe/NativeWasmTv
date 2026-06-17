package com.bu.cc.tv;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.Base64;
import android.util.Log;
import android.view.KeyEvent;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
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
    private View channelListPanel;
    private TextView channelListTitle;
    private TextView channelName;
    private TextView statusText;
    private TextView videoInfo;
    private ListView groupList;
    private ListView channelList;
    private ChannelListAdapter groupAdapter;
    private ChannelListAdapter channelAdapter;
    private LiveUrlResolver liveUrlResolver;
    private YangshipinWebResolver yangshipinResolver;
    private SharpVideoView videoView;
    private Surface videoSurface;
    private SurfaceView plainVideoView;
    private Surface plainVideoSurface;
    private HlsProxyServer proxy;
    private IjkMediaPlayer player;
    private boolean prepared;
    private boolean usePlainSurfaceForCurrentPlayer;
    private volatile int playRequestId;
    private int currentGroupIndex;
    private int currentChannelIndex;
    private int browsingGroupIndex;
    private int videoWidth;
    private int videoHeight;
    private int videoSarNum = 1;
    private int videoSarDen = 1;
    private long lastBackPressedAt;

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
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        applySystemUiVisibility();
        setContentView(R.layout.activity_main);

        root = findViewById(R.id.root);
        channelBar = findViewById(R.id.channel_bar);
        channelListPanel = findViewById(R.id.channel_list_panel);
        channelListTitle = (TextView) findViewById(R.id.channel_list_title);
        channelName = (TextView) findViewById(R.id.channel_name);
        statusText = (TextView) findViewById(R.id.status_text);
        videoInfo = (TextView) findViewById(R.id.video_info);
        groupList = (ListView) findViewById(R.id.channel_group_list);
        channelList = (ListView) findViewById(R.id.channel_list);
        groupAdapter = new ChannelListAdapter(this);
        channelAdapter = new ChannelListAdapter(this);
        liveUrlResolver = new LiveUrlResolver(getSharedPreferences("live_url_resolver", MODE_PRIVATE));
        yangshipinResolver = new YangshipinWebResolver(this, (FrameLayout) root);
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

        videoView = (SharpVideoView) findViewById(R.id.video_surface);
        plainVideoView = new SurfaceView(this);
        plainVideoView.setVisibility(View.GONE);
        ((FrameLayout) root).addView(plainVideoView, 1,
                new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT));
        View.OnClickListener openChannelsOnClick = new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                openChannelList();
            }
        };
        root.setOnClickListener(openChannelsOnClick);
        videoView.setOnClickListener(openChannelsOnClick);
        videoView.setSurfaceCallback(new SharpVideoView.SurfaceCallback() {
            @Override
            public void onVideoSurfaceCreated(Surface surface) {
                videoSurface = surface;
                if (player != null) {
                    if (usePlainSurfaceForCurrentPlayer) {
                        return;
                    }
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
        plainVideoView.setOnClickListener(openChannelsOnClick);
        plainVideoView.getHolder().addCallback(new SurfaceHolder.Callback() {
            @Override
            public void surfaceCreated(SurfaceHolder holder) {
                plainVideoSurface = holder.getSurface();
                if (player != null && usePlainSurfaceForCurrentPlayer) {
                    player.setSurface(plainVideoSurface);
                }
            }

            @Override
            public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
                plainVideoSurface = holder.getSurface();
                if (player != null && usePlainSurfaceForCurrentPlayer) {
                    player.setSurface(plainVideoSurface);
                }
            }

            @Override
            public void surfaceDestroyed(SurfaceHolder holder) {
                if (player != null && usePlainSurfaceForCurrentPlayer) {
                    player.setSurface(null);
                }
                plainVideoSurface = null;
            }
        });
        root.requestFocus();
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
            proxy = new HlsProxyServer(getExternalFilesDir("cmg-debug"));
            proxy.start();
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
        releasePlayer();
        resetVideoLayout();
        if (group.source == ChannelCatalog.SOURCE_CCTV_WEB) {
            resolveFallbackUrl(channel, requestId);
            return;
        }
        resolveYangshipinUrl(channel, requestId);
    }

    private void resolveYangshipinUrl(final Channel channel, final int requestId) {
        if (channel.yangshipinPid == null) {
            resolveFallbackUrl(channel, requestId);
            return;
        }
        showChannelBar(channel.name, "正在解析央视频源");
        yangshipinResolver.resolve(requestId, channel, new YangshipinWebResolver.Callback() {
            @Override
            public void onResolved(int resolvedRequestId, String url,
                    String cmgTag, String cmgInitialUpdateTag, String cmgUpdateTag) {
                if (resolvedRequestId != playRequestId) {
                    return;
                }
                if (cmgTag != null && cmgTag.length() > 0) {
                    int initialUpdateTag = parseHexUpdateTag(cmgInitialUpdateTag);
                    int updateTag = parseHexUpdateTag(cmgUpdateTag);
                    HlsProxyServer.configureCmgDebugContext(cmgTag,
                            cmgInitialUpdateTag, cmgUpdateTag);
                    HlsProxyServer.configureCmgUpdateTags(initialUpdateTag, updateTag);
                    boolean configured = NativeCmgDecryptor.configureRuntimeForProbe(cmgTag, 0);
                    Log.i(TAG, "Configured CMG runtime from Yangshipin tag="
                            + cmgTag + " initialTag=" + cmgInitialUpdateTag
                            + " updateTag=" + cmgUpdateTag + " ok=" + configured);
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

    private void resolveFallbackUrl(final Channel channel, final int requestId) {
        if (channel.url == null) {
            showChannelBar(channel.name, "没有可用的备用源");
            return;
        }
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
        try {
            startPlayer(channel, streamUrl);
        } catch (IOException error) {
            Log.e(TAG, "Unable to play " + channel.name, error);
            showChannelBar(channel.name, "连接失败: " + error.getMessage());
        }
    }

    private void startPlayer(final Channel channel, String streamUrl) throws IOException {
        releasePlayer();
        resetVideoLayout();
        IjkMediaPlayer.loadLibrariesOnce(null);

        final IjkMediaPlayer nextPlayer = new IjkMediaPlayer();
        player = nextPlayer;
        boolean yangshipinSource = streamUrl != null
                && streamUrl.toLowerCase(Locale.US).contains("ysp.cctv.cn");
        usePlainSurfaceForCurrentPlayer = yangshipinSource;
        videoView.setVisibility(usePlainSurfaceForCurrentPlayer ? View.GONE : View.VISIBLE);
        plainVideoView.setVisibility(usePlainSurfaceForCurrentPlayer ? View.VISIBLE : View.GONE);
        nextPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "mediacodec", 1);
        nextPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "mediacodec-auto-rotate", 1);
        nextPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "mediacodec-handle-resolution-change", 1);
        nextPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "framedrop", 0);
        nextPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "packet-buffering", 1);
        nextPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "max-buffer-size", 24 * 1024 * 1024);
        nextPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "min-frames", 30);
        nextPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "infbuf", 0);
        nextPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "sync-av-start", 1);
        nextPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "http-detect-range-support", 0);
        nextPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "reconnect", 1);
        nextPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "probesize", 1024 * 1024);
        nextPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "analyzemaxduration", 100L * 1000L);
        nextPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "live_start_index", -6);
        if (usePlainSurfaceForCurrentPlayer && plainVideoSurface != null) {
            nextPlayer.setSurface(plainVideoSurface);
        } else if (!usePlainSurfaceForCurrentPlayer && videoSurface != null) {
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
                updateVideoLayout(mediaPlayer);
                mediaPlayer.start();
                scheduleVideoInfoRefresh();
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
                    showChannelBar(channel.name, "正在缓冲");
                } else if (what == IMediaPlayer.MEDIA_INFO_BUFFERING_END) {
                    showChannelBar(channel.name, "直播播放中");
                }
                return false;
            }
        });
        nextPlayer.setOnErrorListener(new IMediaPlayer.OnErrorListener() {
            @Override
            public boolean onError(IMediaPlayer mediaPlayer, int what, int extra) {
                if (player == mediaPlayer) {
                    showChannelBar(channel.name, "播放错误: " + what + "/" + extra);
                }
                return true;
            }
        });
        nextPlayer.setDataSource(proxy.proxyUrl(streamUrl));
        nextPlayer.prepareAsync();
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
        channelListTitle.setText("频道列表");
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

    private void releasePlayer() {
        prepared = false;
        if (videoInfo != null) {
            videoInfo.removeCallbacks(updateVideoInfo);
        }
        if (player != null) {
            player.setSurface(null);
            player.release();
            player = null;
        }
        usePlainSurfaceForCurrentPlayer = false;
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
        if (videoView != null) {
            videoView.onResume();
        }
        applySystemUiVisibility();
    }

    @Override
    protected void onDestroy() {
        playRequestId++;
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
