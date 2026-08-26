package xiao.bu.tv;

import com.bu.cc.tv.NativeCmgDecryptor;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.ActivityManager;
import android.app.UiModeManager;
import android.content.Intent;
import android.content.ComponentName;
import android.content.Context;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.media.AudioManager;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.os.Build;
import android.os.Bundle;
import android.os.CpuUsageInfo;
import android.os.HardwarePropertiesManager;
import android.os.IBinder;
import android.os.SystemClock;
import android.util.Log;
import android.util.TypedValue;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowManager;
import android.widget.AdapterView;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import tv.danmaku.ijk.media.player.IMediaPlayer;
import tv.danmaku.ijk.media.player.IjkMediaMeta;
import tv.danmaku.ijk.media.player.IjkMediaPlayer;

public final class MainActivity extends Activity {
    private static final String TAG = "MainActivity";
    static final String PREFERENCES = "tv_player";
    private static final String LAST_GROUP_INDEX = "last_group_index";
    private static final String LAST_CHANNEL_INDEX = "last_channel_index";
    private static final String REVERSE_UP_DOWN = "reverse_up_down";
    static final String AUTO_START = "auto_start";
    private static final String DECODE_MODE = "decode_mode";
    private static final String DECODE_MODE_AUTO = "auto";
    private static final String DECODE_MODE_HARDWARE = "hardware";
    private static final String DECODE_MODE_SOFTWARE = "software";
    private static final String H264_SPS_COMPATIBILITY = "h264_sps_compatibility";
    private static final String HARDDECODE_AB_MIGRATION = "harddecode_ab_migration_v1";
    private static final String HARDWARE_DECODER = "hardware_decoder";
    private static final String HARDWARE_DECODER_AUTO = "auto";
    private static final String MSTAR_AVC_DECODER = "OMX.MS.AVC.Decoder";
    private static final String SURFACE_MODE = "surface_mode";
    private static final String SURFACE_MODE_NORMAL = "normal";
    private static final String SURFACE_MODE_LEGACY = "legacy";
    private static final String VIDEO_SCALE_MODE = "video_scale_mode";
    private static final String VIDEO_SCALE_FIT = "fit";
    private static final String VIDEO_SCALE_STRETCH = "stretch";
    private static final String RESOLUTION_MODE = "resolution_mode";
    private static final String RESOLUTION_MODE_HIGH = "high";
    private static final String RESOLUTION_MODE_MEDIUM = "medium";
    private static final String RESOLUTION_MODE_LOW = "low";
    private static final String CLOCK_LOCATION = "clock_location";
    private static final String CLOCK_LOCATION_CHANNEL_LIST = "channel_list";
    private static final String CLOCK_LOCATION_VIDEO = "video";
    private static final String SHOW_DEBUG_INFO = "show_debug_info";
    private static final String SHOW_NETWORK_SPEED = "show_network_speed";
    private static final String SHOW_DATE = "show_date";
    private static final String FLY_MOUSE_ENABLED = "fly_mouse_enabled";
    private static final String LIVE_DELAY_MODE = "live_delay_mode";
    private static final String LIVE_DELAY_LOW = "low";
    private static final String LIVE_DELAY_BALANCED = "balanced";
    private static final String LIVE_DELAY_STABLE = "stable";
    private static final String GITHUB_URL = "https://github.com/buhanzhe/NativeWasmTv";
    private static final int FIRST_LAUNCH_GROUP_INDEX = 1;
    private static final int FIRST_LAUNCH_CHANNEL_INDEX = 0;
    private static final long CHANNEL_BAR_TIMEOUT_MS = 3000L;
    private static final long CHANNEL_SWITCH_DEBOUNCE_MS = 250L;
    private static final long PANEL_TIMEOUT_MS = 5000L;
    private static final long BACK_PROMPT_TIMEOUT_MS = 5000L;
    private static final long EXIT_CONFIRM_TIMEOUT_MS = BACK_PROMPT_TIMEOUT_MS;
    private static final long CHANNEL_PREFETCH_DELAY_MS = 1500L;
    private static final long CCTV_BUFFERING_RECOVERY_MS = 15000L;
    private static final long CCTV_VIDEO_STALL_RECOVERY_MS = 8000L;
    private static final long CUSTOM_SOURCE_TIMEOUT_MS = 5000L;
    private static final long NUMERIC_CHANNEL_TIMEOUT_MS = 1200L;
    private static final long VIDEO_RENDER_START_TIMEOUT_MS = 10000L;
    // Kept local because older ijkplayer Java artifacts do not expose every info constant.
    private static final int MEDIA_INFO_VIDEO_RENDERING_START = 3;

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
    private final Runnable hideBackPrompt = new Runnable() {
        @Override
        public void run() {
            backPrompt.setVisibility(View.GONE);
            lastBackPressedAt = 0L;
            root.requestFocus();
        }
    };
    private final Runnable commitNumericChannel = new Runnable() {
        @Override
        public void run() {
            commitNumericChannel();
        }
    };
    private final SimpleDateFormat channelListClockFormat =
            new SimpleDateFormat("HH:mm:ss", Locale.getDefault());
    private final SimpleDateFormat videoDateFormat =
            new SimpleDateFormat("yyyy年MM月dd日", Locale.getDefault());
    private final Runnable updateClock = new Runnable() {
        @Override
        public void run() {
            boolean clockOnVideo = CLOCK_LOCATION_VIDEO.equals(clockLocation);
            boolean clockInChannelList = CLOCK_LOCATION_CHANNEL_LIST.equals(clockLocation)
                    && channelListPanel.getVisibility() == View.VISIBLE;
            if (!clockOnVideo && !clockInChannelList && !showDate) {
                return;
            }
            Date nowDate = new Date();
            String time = channelListClockFormat.format(nowDate);
            if (clockOnVideo) {
                videoClock.setText(time);
            } else if (clockInChannelList) {
                channelListClock.setText(time);
            }
            if (showDate) {
                videoDate.setText(videoDateFormat.format(nowDate));
            }
            long now = System.currentTimeMillis();
            root.postDelayed(this, 1000L - now % 1000L);
        }
    };

    private View root;
    private View channelBar;
    private View loadingPanel;
    private View channelListPanel;
    private TextView channelListTitle;
    private TextView channelListClock;
    private TextView videoClock;
    private TextView videoDate;
    private TextView debugInfoOverlay;
    private TextView networkSpeedOverlay;
    private TextView channelName;
    private TextView statusText;
    private TextView videoInfo;
    private TextView loadingChannel;
    private TextView loadingStatus;
    private TextView numericChannelOverlay;
    private TextView managementUrl;
    private ListView groupList;
    private ListView channelList;
    private ChannelListAdapter groupAdapter;
    private ChannelListAdapter channelAdapter;
    private LiveUrlResolver liveUrlResolver;
    private YangshipinWebResolver yangshipinResolver;
    private DirectVideoView videoView;
    private WebSourceView webSourceView;
    private FlyMouseCursorView flyMouseCursor;
    private SurfaceHolder videoSurfaceHolder;
    private HlsProxyServer proxy;
    private boolean proxyStatefulCmgSource;
    private boolean lowResourceDevice;
    private IjkMediaPlayer player;
    private boolean prepared;
    private boolean videoRenderingStarted;
    private boolean activeSoftwareDecode;
    private boolean autoSoftwareDecode;
    private Channel activePlayerChannel;
    private String activePlayerStreamUrl;
    private String webStreamHeaders;
    private Channel pendingPlayerChannel;
    private String pendingPlayerStreamUrl;
    private boolean pendingForceSoftwareDecode;
    private int pendingPlayerRequestId = -1;
    private int legacyHardwareRetryRequestId = -1;
    private volatile int playRequestId;
    private int playerStartRetryCount;
    private int bufferingEventId;
    private int currentGroupIndex;
    private int currentChannelIndex;
    private int currentSourceIndex;
    private int triedCustomSources;
    private int browsingGroupIndex;
    private int pendingRelativeGroupIndex = -1;
    private int pendingRelativeChannelIndex = -1;
    private int videoWidth;
    private int videoHeight;
    private int videoSarNum = 1;
    private int videoSarDen = 1;
    private long lastBackPressedAt;
    private long bufferingStartedAt;
    private long lastPlaybackProgressAt;
    private long lastPlaybackPosition = -1L;
    private long estimatedVideoBitrate = -1L;
    private long estimatedAudioBitrate = -1L;
    private final long[] networkSpeedSampleBytes = new long[6];
    private final long[] networkSpeedSampleTimes = new long[6];
    private int networkSpeedSampleNext;
    private int networkSpeedSampleCount;
    private long smoothedNetworkBytesPerSecond = -1L;
    private HlsProxyServer sampledNetworkProxy;
    private long lastSystemCpuTotalJiffies;
    private long lastSystemCpuIdleJiffies;
    private long lastHardwareCpuActiveMillis;
    private long lastHardwareCpuTotalMillis;
    private long lastSysfsCpuIdleMicros;
    private long lastSysfsCpuSampleElapsedMillis;
    private int lastSysfsCpuCount;
    private boolean procStatCpuUnavailable;
    private boolean hardwareCpuUnavailable;
    private boolean sysfsCpuUnavailable;
    private String systemCpuMetricLabel = "CPU（系统）";
    private String systemCpuMetricSource = "";
    private boolean buffering;
    private boolean bufferingStatusVisible;
    private boolean playbackProgressObserved;
    private int stallRecoveryRequestId = -1;
    private AutoUpdater autoUpdater;
    private QrCodeView managementQr;
    private View managementPanel;
    private View backPrompt;
    private Button backPromptOk;
    private PlaylistManager playlistManager;
    private LocalControlServer controlServer;
    private volatile boolean reverseUpDown;
    private volatile boolean autoStart;
    private volatile String decodeMode;
    private volatile String hardwareDecoder;
    private volatile String surfaceMode;
    private volatile boolean h264SpsCompatibility;
    private volatile String videoScaleMode;
    private volatile String resolutionMode;
    private volatile String clockLocation;
    private volatile boolean showDebugInfo;
    private volatile boolean showNetworkSpeed;
    private volatile boolean showDate;
    private volatile boolean flyMouseEnabled;
    private volatile String liveDelayMode;
    private int clockViewportWidth;
    private int clockViewportHeight;
    private boolean remoteInputMode;
    private String numericChannelInput = "";
    private boolean playbackGestureTracking;
    private boolean playbackGestureVertical;
    private boolean playbackGestureLeftSide;
    private float playbackGestureDownX;
    private float playbackGestureDownY;
    private int playbackGestureStartVolume;
    private int playbackGestureLastVolume = -1;
    private int playbackGestureTouchSlop;
    private ServiceConnection crashRecoveryConnection;
    private boolean crashRecoveryBound;

    private final Runnable commitRelativeChannelSwitch = new Runnable() {
        @Override
        public void run() {
            int groupIndex = pendingRelativeGroupIndex;
            int channelIndex = pendingRelativeChannelIndex;
            pendingRelativeGroupIndex = -1;
            pendingRelativeChannelIndex = -1;
            if (groupIndex != currentGroupIndex || channelIndex < 0) {
                return;
            }
            Channel[] channels = currentGroup().channels;
            if (channels == null || channels.length == 0) {
                Log.w(TAG, "Ignoring channel switch because group is empty index="
                        + groupIndex);
                return;
            }
            channelIndex = ChannelCatalog.wrapIndex(channels, channelIndex);
            if (channelIndex == currentChannelIndex) {
                // An immediate UP/DOWN or DOWN/UP pair has returned to the active
                // channel. Avoid tearing down and recreating the decoder/proxy.
                showChannelBar(channels[channelIndex].name,
                        prepared ? "直播播放中" : "正在准备直播");
                return;
            }
            switchChannel(channelIndex);
        }
    };

    private final Runnable updateVideoInfo = new Runnable() {
        @Override
        public void run() {
            refreshVideoInfo();
            if (hasActivePlayer()) {
                videoInfo.postDelayed(this, 1000L);
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        CrashReporter.install(this);
        showCrashRecoveryNotice();
        TlsCompat.install();
        configureResourceProfile();
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        applySystemUiVisibility();
        setContentView(R.layout.activity_main);

        root = findViewById(R.id.root);
        playbackGestureTouchSlop = ViewConfiguration.get(this).getScaledTouchSlop();
        channelBar = findViewById(R.id.channel_bar);
        loadingPanel = findViewById(R.id.loading_panel);
        channelListPanel = findViewById(R.id.channel_list_panel);
        channelListTitle = (TextView) findViewById(R.id.channel_list_title);
        channelListClock = (TextView) findViewById(R.id.channel_list_clock);
        videoClock = (TextView) findViewById(R.id.video_clock);
        videoDate = (TextView) findViewById(R.id.video_date);
        debugInfoOverlay = (TextView) findViewById(R.id.debug_info_overlay);
        networkSpeedOverlay = (TextView) findViewById(R.id.network_speed_overlay);
        channelName = (TextView) findViewById(R.id.channel_name);
        statusText = (TextView) findViewById(R.id.status_text);
        videoInfo = (TextView) findViewById(R.id.video_info);
        loadingChannel = (TextView) findViewById(R.id.loading_channel);
        loadingStatus = (TextView) findViewById(R.id.loading_status);
        numericChannelOverlay = (TextView) findViewById(R.id.numeric_channel_overlay);
        webSourceView = (WebSourceView) findViewById(R.id.web_source);
        flyMouseCursor = (FlyMouseCursorView) findViewById(R.id.fly_mouse_cursor);
        managementUrl = (TextView) findViewById(R.id.management_url);
        managementQr = (QrCodeView) findViewById(R.id.management_qr);
        managementPanel = findViewById(R.id.management_panel);
        backPrompt = findViewById(R.id.back_navigation_prompt);
        backPromptOk = (Button) findViewById(R.id.back_prompt_ok);
        root.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
            @Override
            public void onLayoutChange(View view, int left, int top, int right, int bottom,
                    int oldLeft, int oldTop, int oldRight, int oldBottom) {
                int width = right - left;
                int height = bottom - top;
                if (width != clockViewportWidth || height != clockViewportHeight) {
                    configureVideoClockForViewport(width, height);
                }
            }
        });
        groupList = (ListView) findViewById(R.id.channel_group_list);
        channelList = (ListView) findViewById(R.id.channel_list);
        groupAdapter = new ChannelListAdapter(this);
        channelAdapter = new ChannelListAdapter(this);
        final SharedPreferences preferences = getSharedPreferences(PREFERENCES, MODE_PRIVATE);
        // Player selection was removed; discard values left by earlier test builds.
        preferences.edit().remove("player_backend").apply();
        String detectedDefaultDecoder = defaultHardwareDecoder();
        if (MSTAR_AVC_DECODER.equals(detectedDefaultDecoder)
                && !preferences.getBoolean(HARDDECODE_AB_MIGRATION, false)) {
            // Select the old-TV IJK hardware compatibility settings once.
            preferences.edit()
                    .putString(DECODE_MODE, DECODE_MODE_HARDWARE)
                    .putString(HARDWARE_DECODER, MSTAR_AVC_DECODER)
                    .putString(SURFACE_MODE, SURFACE_MODE_LEGACY)
                    .putBoolean(H264_SPS_COMPATIBILITY, true)
                    .putBoolean(HARDDECODE_AB_MIGRATION, true)
                    .apply();
        }
        reverseUpDown = preferences.getBoolean(REVERSE_UP_DOWN, false);
        autoStart = preferences.getBoolean(AUTO_START, false);
        decodeMode = sanitizeDecodeMode(preferences.getString(DECODE_MODE, DECODE_MODE_AUTO));
        hardwareDecoder = sanitizeHardwareDecoder(preferences.getString(
                HARDWARE_DECODER, detectedDefaultDecoder));
        surfaceMode = sanitizeSurfaceMode(preferences.getString(
                SURFACE_MODE, defaultSurfaceMode()));
        h264SpsCompatibility = preferences.getBoolean(H264_SPS_COMPATIBILITY, true);
        videoScaleMode = sanitizeVideoScaleMode(
                preferences.getString(VIDEO_SCALE_MODE, VIDEO_SCALE_FIT));
        resolutionMode = sanitizeResolutionMode(
                preferences.getString(RESOLUTION_MODE, RESOLUTION_MODE_HIGH));
        clockLocation = sanitizeClockLocation(
                preferences.getString(CLOCK_LOCATION, CLOCK_LOCATION_CHANNEL_LIST));
        showDebugInfo = preferences.getBoolean(SHOW_DEBUG_INFO, false);
        showNetworkSpeed = preferences.getBoolean(SHOW_NETWORK_SPEED, false);
        showDate = preferences.getBoolean(SHOW_DATE, false);
        flyMouseEnabled = preferences.getBoolean(FLY_MOUSE_ENABLED, false);
        liveDelayMode = sanitizeLiveDelayMode(
                preferences.getString(LIVE_DELAY_MODE, LIVE_DELAY_STABLE));
        remoteInputMode = hasTelevisionUi();
        playlistManager = new PlaylistManager(this);
        ChannelCatalog.setCustomGroups(playlistManager.loadCached());
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
        groupList.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (channelListPanel.getVisibility() == View.VISIBLE
                        && position != browsingGroupIndex) {
                    showChannelMenu(position);
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
        channelList.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                switchBrowsingChannel(position);
            }
        });

        videoView = (DirectVideoView) findViewById(R.id.video_surface);
        configureWebSourceView();
        applyFlyMouseVisibility();
        applyDisplaySettings();
        View.OnClickListener openChannelsOnClick = new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                openChannelList();
            }
        };
        root.setOnClickListener(openChannelsOnClick);
        videoView.setOnClickListener(openChannelsOnClick);
        backPromptOk.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                confirmBackPrompt();
            }
        });
        videoView.setSurfaceCallback(new DirectVideoView.SurfaceCallback() {
            @Override
            public void onVideoSurfaceCreated(SurfaceHolder holder) {
                videoSurfaceHolder = holder;
                Log.i(TAG, "Video surface created size=" + videoView.getWidth()
                        + "x" + videoView.getHeight() + " sdk=" + Build.VERSION.SDK_INT);
                if (pendingPlayerRequestId == playRequestId && pendingPlayerChannel != null) {
                    startPendingPlayer();
                } else if (player != null) {
                    player.setDisplay(holder);
                }
            }

            @Override
            public void onVideoSurfaceDestroyed(SurfaceHolder holder) {
                if (videoSurfaceHolder != holder) {
                    return;
                }
                Log.i(TAG, "Video surface destroyed sdk=" + Build.VERSION.SDK_INT);
                if (hasActivePlayer() && Build.VERSION.SDK_INT < Build.VERSION_CODES.M
                        && activePlayerChannel != null && activePlayerStreamUrl != null) {
                    queuePendingPlayer(activePlayerChannel, activePlayerStreamUrl,
                            activeSoftwareDecode);
                    releasePlayer();
                } else if (player != null) {
                    player.setDisplay(null);
                }
                videoSurfaceHolder = null;
            }
        });
        root.requestFocus();
        autoUpdater = new AutoUpdater(this);
        autoUpdater.checkForUpdates();
        boolean hasLastChannel = preferences.contains(LAST_GROUP_INDEX)
                && preferences.contains(LAST_CHANNEL_INDEX);
        if (hasLastChannel) {
            currentGroupIndex = ChannelCatalog.wrapGroupIndex(
                    preferences.getInt(LAST_GROUP_INDEX, FIRST_LAUNCH_GROUP_INDEX));
            currentChannelIndex = ChannelCatalog.wrapIndex(currentGroup().channels,
                    preferences.getInt(LAST_CHANNEL_INDEX, FIRST_LAUNCH_CHANNEL_INDEX));
        } else {
            currentGroupIndex = FIRST_LAUNCH_GROUP_INDEX;
            currentChannelIndex = FIRST_LAUNCH_CHANNEL_INDEX;
        }
        browsingGroupIndex = currentGroupIndex;
        showChannelMenu(currentGroupIndex);

        try {
            switchChannel(currentChannelIndex);
        } catch (Exception error) {
            Log.e(TAG, "Unable to start player", error);
            showChannelBar(currentChannel().name,
                    "启动失败: " + error.getMessage());
        }
        startManagementServer();
    }

    private boolean hasTelevisionUi() {
        UiModeManager manager = (UiModeManager) getSystemService(UI_MODE_SERVICE);
        return (manager != null && manager.getCurrentModeType()
                == Configuration.UI_MODE_TYPE_TELEVISION)
                || getPackageManager().hasSystemFeature("android.software.leanback");
    }

    private void configureWebSourceView() {
        webSourceView.setListener(new WebSourceView.Listener() {
            @Override
            public void onPageStarted(int requestId, String url) {
                if (requestId == playRequestId) {
                    updateLoadingStatus("正在加载网页直播");
                }
            }

            @Override
            public void onPageReady(int requestId, String url, String title) {
                if (requestId != playRequestId || !webSourceView.isPageVisible()) {
                    return;
                }
                Channel channel = currentChannel();
                hideLoading();
                persistPlayingChannel(channel, requestId);
                showChannelBar(channel.name, flyMouseEnabled
                        ? "网页已打开 · 手机飞鼠可操作"
                        : "网页已打开 · 可在管理页开启飞鼠");
                ensureFlyMouseOnTop();
            }

            @Override
            public void onPageError(int requestId, String message) {
                if (requestId != playRequestId || !webSourceView.isPageVisible()) {
                    return;
                }
                hideLoading();
                showChannelBar(currentChannel().name, "网页加载失败: " + message);
            }

            @Override
            public void onStreamDiscovered(int requestId, String streamUrl, String pageUrl,
                    String userAgent, String cookies) {
                if (requestId != playRequestId || !webSourceView.isPageVisible()) {
                    return;
                }
                Channel channel = currentChannel();
                webStreamHeaders = buildWebStreamHeaders(pageUrl, userAgent, cookies);
                if (proxy != null) {
                    proxy.setWebRequestHeaders(pageUrl, userAgent, cookies);
                }
                Log.i(TAG, "Web source stream discovered channel=" + channel.name
                        + " url=" + streamUrl);
                closeWebSource();
                showLoading(channel.name, "检测到直播流，正在接管播放");
                showChannelBar(channel.name, "已嗅探直播链接 · IJK 接管播放");
                startResolvedPlayer(channel, streamUrl);
            }
        });
    }

    private void openWebSource(Channel channel, String configuredUrl, int requestId) {
        String pageUrl = configuredUrl.substring("webview://".length());
        if (!pageUrl.startsWith("http://") && !pageUrl.startsWith("https://")) {
            hideLoading();
            showChannelBar(channel.name, "WebView 地址无效");
            return;
        }
        releasePlayer();
        videoView.setVisibility(View.INVISIBLE);
        showLoading(channel.name, "正在打开网页直播");
        webSourceView.open(requestId, pageUrl);
        ensureFlyMouseOnTop();
    }

    private void closeWebSource() {
        if (webSourceView != null && webSourceView.isPageVisible()) {
            webSourceView.closePage();
        }
        if (videoView != null) {
            videoView.setVisibility(View.VISIBLE);
        }
    }

    private static boolean isWebViewSource(String url) {
        return url != null && (url.startsWith("webview://http://")
                || url.startsWith("webview://https://"));
    }

    private static String buildWebStreamHeaders(String pageUrl, String userAgent,
            String cookies) {
        StringBuilder headers = new StringBuilder();
        appendWebHeader(headers, "Referer", pageUrl);
        appendWebHeader(headers, "User-Agent", userAgent);
        appendWebHeader(headers, "Cookie", cookies);
        return headers.toString();
    }

    private static void appendWebHeader(StringBuilder headers, String name, String value) {
        if (value == null || value.length() == 0) {
            return;
        }
        String safeValue = value.replace('\r', ' ').replace('\n', ' ').trim();
        if (safeValue.length() > 0) {
            headers.append(name).append(": ").append(safeValue).append("\r\n");
        }
    }

    private void confirmBackPrompt() {
        backPrompt.removeCallbacks(hideBackPrompt);
        backPrompt.setVisibility(View.GONE);
        lastBackPressedAt = 0L;
        openManagement();
    }

    private void openManagement() {
        clearNumericChannelInput();
        if (remoteInputMode) {
            openManagementPanel();
        } else {
            openManagementPage();
        }
    }

    private void openManagementPanel() {
        closeChannelList();
        backPrompt.removeCallbacks(hideBackPrompt);
        backPrompt.setVisibility(View.GONE);
        lastBackPressedAt = 0L;
        refreshManagementAddress();
        managementPanel.setVisibility(View.VISIBLE);
        managementPanel.bringToFront();
        ensureFlyMouseOnTop();
        root.requestFocus();
    }

    private void closeManagementPanel() {
        managementPanel.setVisibility(View.GONE);
        root.requestFocus();
    }

    private void openManagementPage() {
        if (controlServer == null || controlServer.getPort() == 0) {
            Toast.makeText(this, "管理服务尚未启动", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            startActivity(new Intent(this, ManagementActivity.class)
                    .putExtra(ManagementActivity.EXTRA_URL, controlServer.getLoopbackUrl()));
        } catch (RuntimeException error) {
            Toast.makeText(this, "无法打开管理网页", Toast.LENGTH_SHORT).show();
        }
    }

    private void startManagementServer() {
        try {
            InputStream input = getResources().openRawResource(R.raw.control);
            byte[] html;
            try {
                html = readStream(input);
            } finally {
                input.close();
            }
            controlServer = new LocalControlServer(html, new LocalControlServer.Listener() {
                @Override
                public String stateJson() {
                    return buildControlState();
                }

                @Override
                public String control(JSONObject request) throws Exception {
                    return handleWebControl(request);
                }

                @Override
                public String pointer(JSONObject request) throws Exception {
                    return handleWebPointer(request);
                }

                @Override
                public String settings(JSONObject request) throws Exception {
                    return handleWebSettings(request);
                }
            });
            controlServer.start();
            refreshManagementAddress();
        } catch (IOException error) {
            Log.e(TAG, "Unable to start management server", error);
            managementUrl.setText("局域网管理服务启动失败：端口 "
                    + LocalControlServer.PREFERRED_PORT + "-"
                    + LocalControlServer.MAX_PORT + " 均不可用");
            managementQr.setText(null);
        }
    }

    private static byte[] readStream(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int count;
        while ((count = input.read(buffer)) != -1) {
            output.write(buffer, 0, count);
        }
        return output.toByteArray();
    }

    private void refreshManagementAddress() {
        if (controlServer == null) {
            return;
        }
        String url = controlServer.getLanUrl();
        if (url == null) {
            managementUrl.setText("未检测到局域网 IPv4 地址");
            managementQr.setText(null);
        } else {
            managementUrl.setText(url);
            managementQr.setText(url);
        }
    }

    private String buildControlState() {
        try {
            ChannelCatalog.Group[] groups = ChannelCatalog.GROUPS;
            int groupIndex = Math.max(0, Math.min(currentGroupIndex, groups.length - 1));
            ChannelCatalog.Group group = groups[groupIndex];
            int channelIndex = ChannelCatalog.wrapIndex(group.channels, currentChannelIndex);
            Channel channel = group.channels[channelIndex];
            JSONObject root = new JSONObject();
            root.put("ok", true);
            root.put("githubUrl", GITHUB_URL);
            root.put("display", new JSONObject()
                    .put("width", Math.max(0, MainActivity.this.root.getWidth()))
                    .put("height", Math.max(0, MainActivity.this.root.getHeight())));
            JSONObject current = new JSONObject();
            current.put("groupIndex", groupIndex);
            current.put("channelIndex", channelIndex);
            current.put("group", group.title);
            current.put("name", channel.name);
            current.put("sourceIndex", group.source == ChannelCatalog.SOURCE_CUSTOM
                    ? currentSourceIndex : 0);
            current.put("sourceCount", Math.max(1, channel.sourceCount()));
            root.put("current", current);
            JSONArray jsonGroups = new JSONArray();
            for (int groupPosition = 0; groupPosition < groups.length; groupPosition++) {
                JSONObject jsonGroup = new JSONObject();
                jsonGroup.put("name", groups[groupPosition].title);
                JSONArray channels = new JSONArray();
                for (Channel item : groups[groupPosition].channels) {
                    channels.put(new JSONObject().put("name", item.name)
                            .put("sourceCount", Math.max(1, item.sourceCount())));
                }
                jsonGroup.put("channels", channels);
                jsonGroups.put(jsonGroup);
            }
            root.put("groups", jsonGroups);
            root.put("settings", new JSONObject()
                    .put("reverseKeys", reverseUpDown)
                    .put("autoStart", autoStart)
                    .put("decodeMode", decodeMode)
                    .put("hardwareDecoder", hardwareDecoder)
                    .put("hardwareDecoders", availableHardwareDecodersJson())
                    .put("surfaceMode", surfaceMode)
                    .put("h264SpsCompatibility", h264SpsCompatibility)
                    .put("videoScaleMode", videoScaleMode)
                    .put("resolutionMode", resolutionMode)
                    .put("clockLocation", clockLocation)
                    .put("showDebugInfo", showDebugInfo)
                    .put("showNetworkSpeed", showNetworkSpeed)
                    .put("showDate", showDate)
                    .put("flyMouseEnabled", flyMouseEnabled)
                    .put("liveDelayMode", liveDelayMode)
                    .put("playlistUrl", playlistManager.getPlaylistUrl())
                    .put("recommendedPlaylistUrl", PlaylistManager.RECOMMENDED_URL));
            return root.toString();
        } catch (JSONException error) {
            return "{\"ok\":false,\"message\":\"状态生成失败\"}";
        }
    }

    private String handleWebControl(JSONObject request) throws JSONException {
        final String action = request.optString("action", "");
        final int requestedGroup = request.optInt("group", -1);
        final int requestedChannel = request.optInt("channel", -1);
        if (!"next".equals(action) && !"previous".equals(action)
                && !"toggle".equals(action) && !"play".equals(action)) {
            throw new JSONException("未知的控制指令");
        }
        if ("play".equals(action)) {
            ChannelCatalog.Group[] groups = ChannelCatalog.GROUPS;
            if (requestedGroup < 0 || requestedGroup >= groups.length
                    || requestedChannel < 0
                    || requestedChannel >= groups[requestedGroup].channels.length) {
                throw new JSONException("频道不存在");
            }
        }
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if ("next".equals(action)) {
                    switchRelative(1);
                } else if ("previous".equals(action)) {
                    switchRelative(-1);
                } else if ("toggle".equals(action)) {
                    togglePlayback();
                } else {
                    ChannelCatalog.Group[] groups = ChannelCatalog.GROUPS;
                    if (requestedGroup < 0 || requestedGroup >= groups.length
                            || requestedChannel < 0
                            || requestedChannel >= groups[requestedGroup].channels.length) {
                        return;
                    }
                    currentGroupIndex = requestedGroup;
                    browsingGroupIndex = requestedGroup;
                    switchChannel(requestedChannel);
                    closeChannelList();
                }
            }
        });
        return new JSONObject().put("ok", true).toString();
    }

    private String handleWebPointer(JSONObject request) throws JSONException {
        if (!flyMouseEnabled) {
            throw new JSONException("请先在操作与启动中开启手机飞鼠");
        }
        final String action = request.optString("action", "move");
        if (!"move".equals(action) && !"click".equals(action)
                && !"scroll".equals(action) && !"back".equals(action)
                && !"reset".equals(action)) {
            throw new JSONException("未知的飞鼠指令");
        }
        final float dx = clampPointerDelta((float) request.optDouble("dx", 0d));
        final float dy = clampPointerDelta((float) request.optDouble("dy", 0d));
        final int scrollY = (int) clampPointerDelta((float) request.optDouble("scrollY", 0d));
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (!flyMouseEnabled) {
                    return;
                }
                if ("move".equals(action)) {
                    flyMouseCursor.moveBy(dx, dy);
                    ensureFlyMouseOnTop();
                } else if ("click".equals(action)) {
                    dispatchFlyMouseClick();
                } else if ("scroll".equals(action)) {
                    webSourceView.scrollByRemote(scrollY);
                } else if ("back".equals(action)) {
                    onBackPressed();
                } else {
                    flyMouseCursor.resetPosition();
                    ensureFlyMouseOnTop();
                }
            }
        });
        return new JSONObject().put("ok", true).toString();
    }

    private static float clampPointerDelta(float value) {
        return Math.max(-240f, Math.min(240f, value));
    }

    private void applyFlyMouseVisibility() {
        if (flyMouseCursor == null) {
            return;
        }
        flyMouseCursor.setVisibility(flyMouseEnabled ? View.VISIBLE : View.GONE);
        if (flyMouseEnabled) {
            flyMouseCursor.resetPosition();
            ensureFlyMouseOnTop();
        }
    }

    private void ensureFlyMouseOnTop() {
        if (flyMouseEnabled && flyMouseCursor != null) {
            flyMouseCursor.bringToFront();
        }
    }

    private void dispatchFlyMouseClick() {
        if (flyMouseCursor == null || flyMouseCursor.getVisibility() != View.VISIBLE) {
            return;
        }
        float x = flyMouseCursor.cursorX();
        float y = flyMouseCursor.cursorY();
        long now = SystemClock.uptimeMillis();
        MotionEvent down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, x, y, 0);
        MotionEvent up = MotionEvent.obtain(now, now + 40L, MotionEvent.ACTION_UP, x, y, 0);
        down.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        up.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        int cursorVisibility = flyMouseCursor.getVisibility();
        try {
            // The cursor is a full-screen overlay. Hide it only while hit-testing the
            // synthetic tap so the real WebView/TV control underneath receives it.
            flyMouseCursor.setVisibility(View.INVISIBLE);
            root.dispatchTouchEvent(down);
            root.dispatchTouchEvent(up);
            flyMouseCursor.setVisibility(cursorVisibility);
            flyMouseCursor.pulseClick();
            ensureFlyMouseOnTop();
        } finally {
            flyMouseCursor.setVisibility(cursorVisibility);
            down.recycle();
            up.recycle();
        }
    }

    private String handleWebSettings(JSONObject request) throws Exception {
        boolean restartPlayback = false;
        boolean recreateSurface = false;
        if (request.has("reverseKeys")) {
            reverseUpDown = request.optBoolean("reverseKeys", false);
            getSharedPreferences(PREFERENCES, MODE_PRIVATE).edit()
                    .putBoolean(REVERSE_UP_DOWN, reverseUpDown).apply();
        }
        if (request.has("autoStart")) {
            autoStart = request.optBoolean("autoStart", false);
            getSharedPreferences(PREFERENCES, MODE_PRIVATE).edit()
                    .putBoolean(AUTO_START, autoStart).apply();
            Log.i(TAG, "Boot auto start=" + autoStart);
        }
        if (request.has("decodeMode")) {
            final String requestedMode = sanitizeDecodeMode(
                    request.optString("decodeMode", DECODE_MODE_AUTO));
            if (!requestedMode.equals(request.optString("decodeMode", DECODE_MODE_AUTO))) {
                throw new JSONException("不支持的解码模式");
            }
            boolean changed = !requestedMode.equals(decodeMode);
            decodeMode = requestedMode;
            autoSoftwareDecode = false;
            getSharedPreferences(PREFERENCES, MODE_PRIVATE).edit()
                    .putString(DECODE_MODE, decodeMode).apply();
            restartPlayback |= changed;
        }
        if (request.has("hardwareDecoder")) {
            String rawDecoder = request.optString(
                    "hardwareDecoder", HARDWARE_DECODER_AUTO);
            String requestedDecoder = sanitizeHardwareDecoder(rawDecoder);
            if (!requestedDecoder.equals(rawDecoder)) {
                throw new JSONException("所选硬解解码器不可用");
            }
            restartPlayback |= !requestedDecoder.equals(hardwareDecoder);
            hardwareDecoder = requestedDecoder;
            getSharedPreferences(PREFERENCES, MODE_PRIVATE).edit()
                    .putString(HARDWARE_DECODER, hardwareDecoder).apply();
        }
        if (request.has("surfaceMode")) {
            String rawSurfaceMode = request.optString(
                    "surfaceMode", SURFACE_MODE_NORMAL);
            String requestedSurfaceMode = sanitizeSurfaceMode(rawSurfaceMode);
            if (!requestedSurfaceMode.equals(rawSurfaceMode)) {
                throw new JSONException("不支持的 Surface 模式");
            }
            recreateSurface |= !requestedSurfaceMode.equals(surfaceMode);
            surfaceMode = requestedSurfaceMode;
            getSharedPreferences(PREFERENCES, MODE_PRIVATE).edit()
                    .putString(SURFACE_MODE, surfaceMode).apply();
        }
        if (request.has("h264SpsCompatibility")) {
            boolean requestedCompatibility = request.optBoolean(
                    "h264SpsCompatibility", true);
            restartPlayback |= requestedCompatibility != h264SpsCompatibility;
            h264SpsCompatibility = requestedCompatibility;
            getSharedPreferences(PREFERENCES, MODE_PRIVATE).edit()
                    .putBoolean(H264_SPS_COMPATIBILITY, h264SpsCompatibility).apply();
        }
        if (request.has("videoScaleMode")) {
            String rawMode = request.optString("videoScaleMode", VIDEO_SCALE_FIT);
            final String requestedMode = sanitizeVideoScaleMode(rawMode);
            if (!requestedMode.equals(rawMode)) {
                throw new JSONException("不支持的视频画面模式");
            }
            videoScaleMode = requestedMode;
            getSharedPreferences(PREFERENCES, MODE_PRIVATE).edit()
                    .putString(VIDEO_SCALE_MODE, videoScaleMode).apply();
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    applyDisplaySettings();
                }
            });
        }
        if (request.has("resolutionMode")) {
            String rawMode = request.optString("resolutionMode", RESOLUTION_MODE_HIGH);
            String requestedMode = sanitizeResolutionMode(rawMode);
            if (!requestedMode.equals(rawMode)) {
                throw new JSONException("不支持的分辨率档位");
            }
            restartPlayback |= !requestedMode.equals(resolutionMode);
            resolutionMode = requestedMode;
            getSharedPreferences(PREFERENCES, MODE_PRIVATE).edit()
                    .putString(RESOLUTION_MODE, resolutionMode).apply();
        }
        if (request.has("clockLocation")) {
            String rawLocation = request.optString(
                    "clockLocation", CLOCK_LOCATION_CHANNEL_LIST);
            final String requestedLocation = sanitizeClockLocation(rawLocation);
            if (!requestedLocation.equals(rawLocation)) {
                throw new JSONException("不支持的时间显示位置");
            }
            clockLocation = requestedLocation;
            getSharedPreferences(PREFERENCES, MODE_PRIVATE).edit()
                    .putString(CLOCK_LOCATION, clockLocation).apply();
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    applyClockLocation();
                }
            });
        }
        if (request.has("showDebugInfo")) {
            showDebugInfo = request.optBoolean("showDebugInfo", false);
            getSharedPreferences(PREFERENCES, MODE_PRIVATE).edit()
                    .putBoolean(SHOW_DEBUG_INFO, showDebugInfo).apply();
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    applyDebugInfoVisibility();
                }
            });
        }
        if (request.has("showNetworkSpeed")) {
            showNetworkSpeed = request.optBoolean("showNetworkSpeed", false);
            getSharedPreferences(PREFERENCES, MODE_PRIVATE).edit()
                    .putBoolean(SHOW_NETWORK_SPEED, showNetworkSpeed).apply();
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    applyNetworkSpeedVisibility();
                }
            });
        }
        if (request.has("showDate")) {
            showDate = request.optBoolean("showDate", false);
            getSharedPreferences(PREFERENCES, MODE_PRIVATE).edit()
                    .putBoolean(SHOW_DATE, showDate).apply();
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    applyClockLocation();
                }
            });
        }
        if (request.has("flyMouseEnabled")) {
            flyMouseEnabled = request.optBoolean("flyMouseEnabled", false);
            getSharedPreferences(PREFERENCES, MODE_PRIVATE).edit()
                    .putBoolean(FLY_MOUSE_ENABLED, flyMouseEnabled).apply();
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    applyFlyMouseVisibility();
                }
            });
        }
        if (request.has("liveDelayMode")) {
            String rawMode = request.optString("liveDelayMode", LIVE_DELAY_STABLE);
            String requestedMode = sanitizeLiveDelayMode(rawMode);
            if (!requestedMode.equals(rawMode)) {
                throw new JSONException("不支持的直播延迟模式");
            }
            restartPlayback |= !requestedMode.equals(liveDelayMode);
            liveDelayMode = requestedMode;
            getSharedPreferences(PREFERENCES, MODE_PRIVATE).edit()
                    .putString(LIVE_DELAY_MODE, liveDelayMode).apply();
        }
        if (recreateSurface) {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    root.postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            recreate();
                        }
                    }, 500L);
                }
            });
        } else if (restartPlayback) {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    switchChannel(currentChannelIndex);
                }
            });
        }
        String message = "设置已保存";
        if (request.has("playlistUrl")) {
            final ChannelCatalog.Group[] customGroups = playlistManager.downloadAndSave(
                    request.optString("playlistUrl", ""));
            applyPlaylistGroups(customGroups);
            int channelCount = 0;
            for (ChannelCatalog.Group group : customGroups) {
                channelCount += group.channels.length;
            }
            message = customGroups.length == 0 ? "已移除在线频道"
                    : "已加载 " + customGroups.length + " 个分组、" + channelCount + " 个频道";
        }
        return new JSONObject().put("ok", true).put("message", message).toString();
    }

    private void applyPlaylistGroups(final ChannelCatalog.Group[] customGroups)
            throws InterruptedException {
        final CountDownLatch applied = new CountDownLatch(1);
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                boolean wasCustom = currentGroupIndex < ChannelCatalog.GROUPS.length
                        && currentGroup().source == ChannelCatalog.SOURCE_CUSTOM;
                ChannelCatalog.setCustomGroups(customGroups);
                if (currentGroupIndex >= ChannelCatalog.GROUPS.length) {
                    currentGroupIndex = 0;
                    currentChannelIndex = ChannelCatalog.defaultChannelIndex(currentGroup());
                    browsingGroupIndex = currentGroupIndex;
                    switchChannel(currentChannelIndex);
                } else if (wasCustom) {
                    currentChannelIndex = ChannelCatalog.wrapIndex(
                            currentGroup().channels, currentChannelIndex);
                    switchChannel(currentChannelIndex);
                }
                if (channelListPanel.getVisibility() == View.VISIBLE) {
                    showChannelMenu(currentGroupIndex);
                }
                applied.countDown();
            }
        });
        applied.await(5L, TimeUnit.SECONDS);
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
        cancelPendingRelativeSwitch();
        clearNumericChannelInput();
        currentSourceIndex = 0;
        triedCustomSources = 1;
        startChannel(index);
    }

    private void startChannel(int index) {
        armCrashRecovery();
        closeWebSource();
        webStreamHeaders = null;
        final ChannelCatalog.Group group = currentGroup();
        currentChannelIndex = ChannelCatalog.wrapIndex(group.channels, index);
        final Channel channel = group.channels[currentChannelIndex];
        if (channelListPanel.getVisibility() == View.VISIBLE) {
            groupAdapter.setSelectedIndex(currentGroupIndex);
            channelAdapter.setSelectedIndex(currentChannelIndex);
            groupList.setSelection(currentGroupIndex);
            channelList.setSelection(currentChannelIndex);
        }
        final int requestId = ++playRequestId;
        playerStartRetryCount = 0;
        legacyHardwareRetryRequestId = -1;
        clearPendingPlayer();
        releasePlayer();
        resetVideoLayout();
        showLoading(channel.name, group.source == ChannelCatalog.SOURCE_CUSTOM
                ? customSourceStatus("正在连接") : "正在准备直播");
        try {
            resetProxyForChannelSwitch();
        } catch (IOException error) {
            Log.e(TAG, "Unable to reset proxy for channel switch", error);
            hideLoading();
            showChannelBar(channel.name, "切换失败: " + error.getMessage());
            return;
        }
        if (group.source == ChannelCatalog.SOURCE_CCTV_WEB
                || group.source == ChannelCatalog.SOURCE_CUSTOM) {
            resolveFallbackUrl(channel, requestId);
            return;
        }
        resolveYangshipinUrl(channel, requestId);
    }

    private String customSourceStatus(String prefix) {
        Channel channel = currentChannel();
        int count = Math.max(1, channel.sourceCount());
        return prefix + "线路 " + (currentSourceIndex + 1) + "/" + count;
    }

    private boolean switchCustomSource(int offset, boolean automatic, String reason) {
        cancelPendingRelativeSwitch();
        if (currentGroup().source != ChannelCatalog.SOURCE_CUSTOM) {
            return false;
        }
        Channel channel = currentChannel();
        int count = channel.sourceCount();
        if (count <= 1) {
            if (automatic) {
                hideLoading();
                showChannelBar(channel.name, reason + "，当前频道没有备用线路");
            } else {
                showChannelBar(channel.name, "当前频道只有一条线路");
            }
            return true;
        }
        if (automatic && triedCustomSources >= count) {
            hideLoading();
            showChannelBar(channel.name, "全部 " + count + " 条线路均不可用");
            return true;
        }
        if (!automatic) {
            clearNumericChannelInput();
        }
        currentSourceIndex = (currentSourceIndex + offset) % count;
        if (currentSourceIndex < 0) {
            currentSourceIndex += count;
        }
        if (automatic) {
            triedCustomSources++;
        } else {
            triedCustomSources = 1;
        }
        startChannel(currentChannelIndex);
        showChannelBar(channel.name, (automatic ? reason + "，自动切换至" : "已切换至")
                + "线路 " + (currentSourceIndex + 1) + "/" + count);
        return true;
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
        boolean statefulCmgSource = currentGroup().source != ChannelCatalog.SOURCE_CCTV_WEB
                && currentGroup().source != ChannelCatalog.SOURCE_CUSTOM;
        HlsProxyServer previous = proxy;
        proxy = null;
        if (previous != null) {
            // A CCTV proxy may still have prefetched segments queued for decryption.
            // Closing it first cancels the old stateful H5E session before the new one starts.
            previous.close();
        }
        // Reset shared CMG state only after every old proxy decrypt task has been
        // cancelled. Otherwise a late old task can repopulate the just-reset runtime
        // while the next source is being initialized.
        HlsProxyServer.resetCmgSessionForChannelSwitch();
        HlsProxyServer next = new HlsProxyServer(
                statefulCmgSource, lowResourceDevice,
                h264SpsCompatibility, cctvLiveEdgeHoldBackSegments(),
                currentGroup().source != ChannelCatalog.SOURCE_CUSTOM, resolutionMode,
                cctvStartupDownloadSegments(), cctvStartupDecryptSegments());
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
        yangshipinResolver.resolve(requestId, channel, yangshipinDefinition(channel),
                new YangshipinWebResolver.Callback() {
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
                    HlsProxyServer.configureCmgContext(
                            cmgTag, cmgInitTimeMs, cmgUpdateBaseTimeMs);
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
        final boolean directCustomSource = currentGroup().source == ChannelCatalog.SOURCE_CUSTOM;
        final String configuredUrl = directCustomSource
                ? channel.sourceUrl(currentSourceIndex) : channel.url;
        if (configuredUrl == null) {
            hideLoading();
            showChannelBar(channel.name, "没有可用的备用源");
            return;
        }
        if (isWebViewSource(configuredUrl)) {
            String yangshipinPid = extractYangshipinPid(configuredUrl);
            if (yangshipinPid != null
                    && (channel.streamId == null
                    || !channel.streamId.startsWith("web_ysp_fallback_"))) {
                Channel resolverChannel = new Channel(channel.number, channel.name,
                        "web_ysp_fallback_" + yangshipinPid, configuredUrl,
                        yangshipinPid, null, channel.yangshipinMaxDefinition);
                updateLoadingStatus("正在解析网页直播地址");
                resolveYangshipinUrl(resolverChannel, requestId);
                return;
            }
            openWebSource(channel, configuredUrl, requestId);
            return;
        }
        updateLoadingStatus(directCustomSource
                ? customSourceStatus("正在连接") : "正在获取高清线路");
        showChannelBar(channel.name, directCustomSource
                ? customSourceStatus("正在连接") : "正在解析备用源");
        new Thread(new Runnable() {
            @Override
            public void run() {
                String streamUrl = configuredUrl;
                if (!directCustomSource) {
                    try {
                        streamUrl = liveUrlResolver.resolve(channel);
                    } catch (IOException error) {
                        Log.w(TAG, "Falling back to static HLS for " + channel.name, error);
                    }
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
            if (currentGroup().source == ChannelCatalog.SOURCE_CUSTOM) {
                switchCustomSource(1, true, "线路连接失败");
                return;
            }
            hideLoading();
            showChannelBar(channel.name, "连接失败: " + error.getMessage());
        }
    }

    private void startPlayer(final Channel channel, final String streamUrl) throws IOException {
        startPlayer(channel, streamUrl, false);
    }

    private static String extractYangshipinPid(String configuredUrl) {
        if (configuredUrl == null) {
            return null;
        }
        String lower = configuredUrl.toLowerCase(Locale.US);
        if (lower.indexOf("yangshipin.cn/") < 0) {
            return null;
        }
        int marker = lower.indexOf("pid=");
        if (marker < 0) {
            return null;
        }
        int start = marker + 4;
        int end = configuredUrl.length();
        for (int index = start; index < configuredUrl.length(); index++) {
            char value = configuredUrl.charAt(index);
            if (value == '&' || value == '#' || value == '/') {
                end = index;
                break;
            }
        }
        String pid = configuredUrl.substring(start, end).trim();
        return pid.length() == 0 ? null : pid;
    }

    private void startPlayer(final Channel channel, final String streamUrl,
            boolean forceSoftwareDecode) throws IOException {
        startIjkPlayer(channel, streamUrl, forceSoftwareDecode);
    }

    private void startIjkPlayer(final Channel channel, final String streamUrl,
            boolean forceSoftwareDecode) throws IOException {
        if (!videoView.isSurfaceReady()) {
            queuePendingPlayer(channel, streamUrl, forceSoftwareDecode);
            updateLoadingStatus("等待视频输出界面");
            Log.i(TAG, "Deferring player until Surface is ready channel=" + channel.name);
            return;
        }
        clearPendingPlayer();
        releasePlayer();
        resetVideoLayout();
        IjkMediaPlayer.loadLibrariesOnce(null);

        final IjkMediaPlayer nextPlayer = new IjkMediaPlayer();
        player = nextPlayer;
        final boolean customSource = currentGroup().source == ChannelCatalog.SOURCE_CUSTOM;
        final int sourceRequestId = playRequestId;
        final boolean softwareDecode = forceSoftwareDecode || shouldUseSoftwareDecode();
        activeSoftwareDecode = softwareDecode;
        activePlayerChannel = channel;
        activePlayerStreamUrl = streamUrl;
        final boolean legacyMediaCodec = Build.VERSION.SDK_INT <= Build.VERSION_CODES.KITKAT
                || lowResourceDevice;
        nextPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "mediacodec",
                softwareDecode ? 0 : 1);
        if (!softwareDecode) {
            nextPlayer.setOnMediaCodecSelectListener(
                    new IjkMediaPlayer.OnMediaCodecSelectListener() {
                        @Override
                        public String onMediaCodecSelect(IMediaPlayer mediaPlayer,
                                String mimeType, int profile, int level) {
                            if (!HARDWARE_DECODER_AUTO.equals(hardwareDecoder)
                                    && "video/avc".equalsIgnoreCase(mimeType)) {
                                Log.i(TAG, "Forcing MediaCodec=" + hardwareDecoder
                                        + " mime=" + mimeType + " profile=" + profile
                                        + " level=" + level);
                                return hardwareDecoder;
                            }
                            String selected = IjkMediaPlayer.DefaultMediaCodecSelector.sInstance
                                    .onMediaCodecSelect(mediaPlayer, mimeType, profile, level);
                            Log.i(TAG, "Default MediaCodec=" + selected + " mime=" + mimeType
                                    + " profile=" + profile + " level=" + level);
                            return selected;
                        }
                    });
        }
        // Several KitKat-era TV codecs fail silently when IJK asks them to reconfigure a
        // Surface for rotation or resolution changes. TV streams are landscape and fixed-size,
        // so keep those optional MediaCodec paths off on legacy/low-RAM devices.
        nextPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "mediacodec-auto-rotate",
                legacyMediaCodec ? 0 : 1);
        nextPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER,
                "mediacodec-handle-resolution-change", legacyMediaCodec ? 0 : 1);
        nextPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "an", 0);
        nextPlayer.setVolume(1.0f, 1.0f);
        nextPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "framedrop",
                softwareDecode ? 5 : 1);
        nextPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "packet-buffering", 1);
        final boolean cctvSource = currentGroup().source == ChannelCatalog.SOURCE_CCTV_WEB;
        nextPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "min-frames",
                cctvSource ? cctvIjkMinFrames() : 60);
        nextPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "infbuf", 0);
        nextPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "sync-av-start", 1);
        nextPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "max_cached_duration",
                cctvSource ? 45000 : 30000);
        nextPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "first-high-water-mark-ms",
                cctvSource ? cctvIjkFirstBufferMs() : 3500);
        nextPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "next-high-water-mark-ms",
                cctvSource ? 8000 : 5000);
        nextPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "last-high-water-mark-ms",
                cctvSource ? 10000 : 5000);
        nextPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "reconnect", 1);
        /* Every channel switch creates a localhost proxy on a new port. IJK 0.8.8
         * can retain an empty localhost DNS-cache entry from the closed proxy,
         * making the first connection to the new port fail spuriously. */
        nextPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "dns_cache_clear", 1);
        nextPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "http-detect-range-support", 0);
        nextPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "probesize", 256 * 1024);
        /* Start at the first segment exposed by the selected startup policy. Using a
         * negative index would discard already prepared data in the two-segment modes. */
        nextPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "live_start_index",
                cctvSource ? 0 : -3);
        videoSurfaceHolder = videoView.getVideoSurfaceHolder();
        if (videoSurfaceHolder == null) {
            queuePendingPlayer(channel, streamUrl, forceSoftwareDecode);
            nextPlayer.release();
            player = null;
            return;
        }
        // Bind the holder before prepareAsync. API 18 vendor codecs cannot reliably retarget
        // an already configured decoder to a Surface that appears later.
        nextPlayer.setDisplay(videoSurfaceHolder);
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
                scheduleVideoRenderWatchdog(channel, streamUrl, nextPlayer,
                        sourceRequestId, softwareDecode);
                prefetchNearbyChannels(channel);
                hideLoading();
                String playingStatus = softwareDecode ? "直播播放中 · 兼容软解" : "直播播放中";
                showChannelBar(channel.name, customSource
                        ? customSourceStatus(playingStatus + " · ") : playingStatus);
            }
        });
        nextPlayer.setOnInfoListener(new IMediaPlayer.OnInfoListener() {
            @Override
            public boolean onInfo(IMediaPlayer mediaPlayer, int what, int extra) {
                if (player != mediaPlayer) {
                    return false;
                }
                if (what == MEDIA_INFO_VIDEO_RENDERING_START) {
                    videoRenderingStarted = true;
                    persistPlayingChannel(channel, sourceRequestId);
                    Log.i(TAG, "First video frame rendered decoder="
                            + (softwareDecode ? "software" : "hardware")
                            + " channel=" + channel.name);
                } else if (what == IMediaPlayer.MEDIA_INFO_BUFFERING_START) {
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
                                if (cctvSource) {
                                    showLoading(channel.name, "正在缓冲，请稍候");
                                } else {
                                    showChannelBar(channel.name, "正在缓冲");
                                }
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
                        if (cctvSource) {
                            hideLoading();
                        }
                        showChannelBar(channel.name, customSource
                                ? customSourceStatus("直播播放中 · ") : "直播播放中");
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
                    if (customSource) {
                        final IMediaPlayer failedPlayer = mediaPlayer;
                        channelBar.post(new Runnable() {
                            @Override
                            public void run() {
                                if (sourceRequestId == playRequestId
                                        && player == failedPlayer) {
                                    switchCustomSource(1, true, "线路播放失败");
                                }
                            }
                        });
                        return true;
                    }
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
        if (customSource) {
            channelBar.postDelayed(new Runnable() {
                @Override
                public void run() {
                    if (sourceRequestId == playRequestId && player == nextPlayer && !prepared) {
                        switchCustomSource(1, true, "连接超过 5 秒");
                    }
                }
            }, CUSTOM_SOURCE_TIMEOUT_MS);
        }
    }

    private boolean shouldUseSoftwareDecode() {
        if (DECODE_MODE_SOFTWARE.equals(decodeMode)) {
            return true;
        }
        return DECODE_MODE_AUTO.equals(decodeMode) && autoSoftwareDecode;
    }

    private static String sanitizeDecodeMode(String mode) {
        if (DECODE_MODE_HARDWARE.equals(mode) || DECODE_MODE_SOFTWARE.equals(mode)) {
            return mode;
        }
        return DECODE_MODE_AUTO;
    }

    private String defaultHardwareDecoder() {
        Set<String> decoders = availableHardwareDecoderNames();
        return decoders.contains(MSTAR_AVC_DECODER)
                ? MSTAR_AVC_DECODER : HARDWARE_DECODER_AUTO;
    }

    private String sanitizeHardwareDecoder(String decoder) {
        if (decoder == null || decoder.length() == 0
                || HARDWARE_DECODER_AUTO.equals(decoder)) {
            return HARDWARE_DECODER_AUTO;
        }
        return availableHardwareDecoderNames().contains(decoder)
                ? decoder : HARDWARE_DECODER_AUTO;
    }

    private Set<String> availableHardwareDecoderNames() {
        Set<String> decoders = new LinkedHashSet<String>();
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.JELLY_BEAN) {
            return decoders;
        }
        try {
            int codecCount = MediaCodecList.getCodecCount();
            for (int index = 0; index < codecCount; index++) {
                MediaCodecInfo codecInfo = MediaCodecList.getCodecInfoAt(index);
                if (codecInfo == null || codecInfo.isEncoder()) {
                    continue;
                }
                String name = codecInfo.getName();
                if (name == null || isSoftwareCodecName(name)) {
                    continue;
                }
                for (String type : codecInfo.getSupportedTypes()) {
                    if ("video/avc".equalsIgnoreCase(type)) {
                        decoders.add(name);
                        break;
                    }
                }
            }
        } catch (Throwable error) {
            Log.w(TAG, "Unable to enumerate AVC hardware decoders", error);
        }
        return decoders;
    }

    private static boolean isSoftwareCodecName(String codecName) {
        String lower = codecName.toLowerCase(Locale.US);
        return lower.startsWith("omx.google.")
                || lower.startsWith("omx.pv.")
                || lower.startsWith("omx.ffmpeg.")
                || lower.startsWith("omx.avcodec.")
                || lower.startsWith("c2.android.")
                || lower.contains(".software.")
                || lower.contains(".sw.");
    }

    private JSONArray availableHardwareDecodersJson() {
        JSONArray result = new JSONArray();
        for (String decoder : availableHardwareDecoderNames()) {
            result.put(decoder);
        }
        return result;
    }

    private static String defaultSurfaceMode() {
        return Build.VERSION.SDK_INT <= Build.VERSION_CODES.KITKAT
                ? SURFACE_MODE_LEGACY : SURFACE_MODE_NORMAL;
    }

    private static String sanitizeSurfaceMode(String mode) {
        return SURFACE_MODE_LEGACY.equals(mode)
                ? SURFACE_MODE_LEGACY : SURFACE_MODE_NORMAL;
    }

    private static String sanitizeVideoScaleMode(String mode) {
        return VIDEO_SCALE_STRETCH.equals(mode) ? VIDEO_SCALE_STRETCH : VIDEO_SCALE_FIT;
    }

    private static String sanitizeResolutionMode(String mode) {
        if (RESOLUTION_MODE_MEDIUM.equals(mode) || RESOLUTION_MODE_LOW.equals(mode)) {
            return mode;
        }
        return RESOLUTION_MODE_HIGH;
    }

    private static String sanitizeClockLocation(String location) {
        return CLOCK_LOCATION_VIDEO.equals(location)
                ? CLOCK_LOCATION_VIDEO : CLOCK_LOCATION_CHANNEL_LIST;
    }

    private static String sanitizeLiveDelayMode(String mode) {
        if (LIVE_DELAY_LOW.equals(mode) || LIVE_DELAY_BALANCED.equals(mode)) {
            return mode;
        }
        return LIVE_DELAY_STABLE;
    }

    private int cctvLiveEdgeHoldBackSegments() {
        return LIVE_DELAY_LOW.equals(liveDelayMode) ? 1 : 2;
    }

    private int cctvStartupDownloadSegments() {
        return LIVE_DELAY_LOW.equals(liveDelayMode) ? 1 : 2;
    }

    private int cctvStartupDecryptSegments() {
        return LIVE_DELAY_STABLE.equals(liveDelayMode) ? 2 : 1;
    }

    private int cctvIjkMinFrames() {
        if (LIVE_DELAY_LOW.equals(liveDelayMode)) {
            return 40;
        }
        if (LIVE_DELAY_BALANCED.equals(liveDelayMode)) {
            return 80;
        }
        return 140;
    }

    private int cctvIjkFirstBufferMs() {
        if (LIVE_DELAY_LOW.equals(liveDelayMode)) {
            return 1000;
        }
        if (LIVE_DELAY_BALANCED.equals(liveDelayMode)) {
            return 3000;
        }
        return 6000;
    }

    private void applyDisplaySettings() {
        videoView.setLegacySurfaceMode(SURFACE_MODE_LEGACY.equals(surfaceMode));
        videoView.setStretchVideo(VIDEO_SCALE_STRETCH.equals(videoScaleMode));
        applyClockLocation();
        applyNetworkSpeedVisibility();
    }

    private void applyClockLocation() {
        root.removeCallbacks(updateClock);
        boolean clockOnVideo = CLOCK_LOCATION_VIDEO.equals(clockLocation);
        configureVideoClockForViewport(root.getWidth(), root.getHeight());
        videoClock.setVisibility(clockOnVideo ? View.VISIBLE : View.GONE);
        videoDate.setVisibility(showDate ? View.VISIBLE : View.GONE);
        channelListClock.setVisibility(clockOnVideo ? View.GONE : View.VISIBLE);
        applyDebugInfoVisibility();
        if (clockOnVideo || channelListPanel.getVisibility() == View.VISIBLE || showDate) {
            root.post(updateClock);
        }
    }

    private void configureVideoClockForViewport(int viewportWidth, int viewportHeight) {
        if (videoClock == null) {
            return;
        }
        if (viewportWidth <= 0 || viewportHeight <= 0) {
            viewportWidth = getResources().getDisplayMetrics().widthPixels;
            viewportHeight = getResources().getDisplayMetrics().heightPixels;
        }
        int shortSide = Math.min(viewportWidth, viewportHeight);
        float textSizePx = Math.max(24f, Math.min(96f, shortSide * 0.042f));
        float shadowRadiusPx = Math.max(2f, textSizePx * 0.09f);
        float shadowOffsetPx = Math.max(1f, textSizePx * 0.045f);
        videoClock.setTextSize(TypedValue.COMPLEX_UNIT_PX, textSizePx);
        videoClock.setShadowLayer(shadowRadiusPx, shadowOffsetPx, shadowOffsetPx,
                0xe6000000);

        android.graphics.Paint.FontMetrics metrics = videoClock.getPaint().getFontMetrics();
        int textWidth = (int) Math.ceil(videoClock.getPaint().measureText("88:88:88"));
        int textHeight = (int) Math.ceil(metrics.descent - metrics.ascent);
        FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) videoClock.getLayoutParams();
        params.width = textWidth + (int) Math.ceil(shadowRadiusPx * 2f);
        params.height = textHeight + (int) Math.ceil(shadowRadiusPx * 2f);
        params.topMargin = Math.max(4, Math.round(viewportHeight * 0.0125f));
        params.rightMargin = Math.max(6, Math.round(viewportWidth * 0.01f));
        videoClock.setLayoutParams(params);
        configureVideoDateForViewport(viewportWidth, viewportHeight);
        configureDebugInfoForViewport(viewportWidth, viewportHeight,
                CLOCK_LOCATION_VIDEO.equals(clockLocation), params);
        clockViewportWidth = viewportWidth;
        clockViewportHeight = viewportHeight;
        Log.i(TAG, "Video clock layout viewport=" + viewportWidth + "x" + viewportHeight
                + " textPx=" + Math.round(textSizePx)
                + " size=" + params.width + "x" + params.height
                + " margins=" + params.rightMargin + "," + params.topMargin);
    }

    private void configureVideoDateForViewport(int viewportWidth, int viewportHeight) {
        if (videoDate == null) {
            return;
        }
        int shortSide = Math.min(viewportWidth, viewportHeight);
        float textSizePx = Math.max(18f, Math.min(54f, shortSide * 0.03f));
        float shadowRadiusPx = Math.max(1.5f, textSizePx * 0.09f);
        float shadowOffsetPx = Math.max(1f, textSizePx * 0.045f);
        videoDate.setTextSize(TypedValue.COMPLEX_UNIT_PX, textSizePx);
        videoDate.setShadowLayer(shadowRadiusPx, shadowOffsetPx, shadowOffsetPx,
                0xe6000000);

        FrameLayout.LayoutParams params =
                (FrameLayout.LayoutParams) videoDate.getLayoutParams();
        params.width = FrameLayout.LayoutParams.WRAP_CONTENT;
        params.height = FrameLayout.LayoutParams.WRAP_CONTENT;
        params.leftMargin = Math.max(8, Math.round(viewportWidth * 0.01f));
        params.topMargin = Math.max(6, Math.round(viewportHeight * 0.0125f));
        videoDate.setLayoutParams(params);
    }

    private void configureDebugInfoForViewport(int viewportWidth, int viewportHeight,
            boolean clockOnVideo, FrameLayout.LayoutParams clockParams) {
        if (debugInfoOverlay == null) {
            return;
        }
        int shortSide = Math.min(viewportWidth, viewportHeight);
        float textSizePx = Math.max(14f, Math.min(48f, shortSide * 0.022f));
        float shadowRadiusPx = Math.max(1.5f, textSizePx * 0.09f);
        float shadowOffsetPx = Math.max(1f, textSizePx * 0.045f);
        debugInfoOverlay.setTextSize(TypedValue.COMPLEX_UNIT_PX, textSizePx);
        debugInfoOverlay.setShadowLayer(shadowRadiusPx, shadowOffsetPx, shadowOffsetPx,
                0xe6000000);
        debugInfoOverlay.setLineSpacing(0f, 1.08f);

        FrameLayout.LayoutParams params =
                (FrameLayout.LayoutParams) debugInfoOverlay.getLayoutParams();
        params.width = FrameLayout.LayoutParams.WRAP_CONTENT;
        params.height = FrameLayout.LayoutParams.WRAP_CONTENT;
        params.rightMargin = clockParams.rightMargin;
        params.topMargin = clockOnVideo
                ? clockParams.topMargin + clockParams.height
                        + Math.max(3, Math.round(viewportHeight * 0.004f))
                : clockParams.topMargin;
        debugInfoOverlay.setLayoutParams(params);
    }

    private void applyDebugInfoVisibility() {
        if (debugInfoOverlay == null) {
            return;
        }
        debugInfoOverlay.setVisibility(showDebugInfo ? View.VISIBLE : View.GONE);
        if (showDebugInfo) {
            configureVideoClockForViewport(root.getWidth(), root.getHeight());
            refreshVideoInfo();
        }
    }

    private void applyNetworkSpeedVisibility() {
        if (networkSpeedOverlay == null) {
            return;
        }
        networkSpeedOverlay.setVisibility(showNetworkSpeed ? View.VISIBLE : View.GONE);
        if (showNetworkSpeed) {
            refreshVideoInfo();
        }
    }

    private void scheduleVideoRenderWatchdog(final Channel channel, final String streamUrl,
            final IjkMediaPlayer watchedPlayer, final int requestId,
            final boolean softwareDecode) {
        channelBar.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (requestId != playRequestId || player != watchedPlayer || !prepared
                        || videoRenderingStarted) {
                    return;
                }
                if (buffering) {
                    channelBar.postDelayed(this, 3000L);
                    return;
                }
                if (!watchedPlayer.isPlaying() || watchedPlayer.getVideoWidth() <= 0
                        || watchedPlayer.getVideoHeight() <= 0) {
                    channelBar.postDelayed(this, 3000L);
                    return;
                }
                if (softwareDecode) {
                    Log.w(TAG, "Software decoder produced no visible frame channel="
                            + channel.name);
                    showChannelBar(channel.name, "兼容软解仍未检测到画面");
                    return;
                }
                boolean legacyCodec = Build.VERSION.SDK_INT <= Build.VERSION_CODES.KITKAT
                        || lowResourceDevice;
                if (legacyCodec && legacyHardwareRetryRequestId != requestId) {
                    legacyHardwareRetryRequestId = requestId;
                    Log.w(TAG, "No rendered frame; recreating legacy hardware decoder with "
                            + "ready Surface device=" + Build.MANUFACTURER + "/" + Build.MODEL
                            + " sdk=" + Build.VERSION.SDK_INT + " outputFps="
                            + watchedPlayer.getVideoOutputFramesPerSecond());
                    showLoading(channel.name, "正在重新连接兼容硬解");
                    try {
                        startPlayer(channel, streamUrl, false);
                    } catch (IOException error) {
                        Log.e(TAG, "Unable to restart legacy hardware decoder", error);
                        hideLoading();
                        showChannelBar(channel.name, "兼容硬解重试失败: " + error.getMessage());
                    }
                    return;
                }
                if (!DECODE_MODE_AUTO.equals(decodeMode)) {
                    Log.w(TAG, "Hardware decoder produced no visible frame; automatic fallback "
                            + "disabled mode=" + decodeMode + " channel=" + channel.name);
                    showChannelBar(channel.name, "硬解未检测到画面，可在管理页选择兼容软解");
                    return;
                }
                autoSoftwareDecode = true;
                Log.w(TAG, "Hardware decoder produced no visible frame; falling back to "
                        + "software decoder device=" + Build.MANUFACTURER + "/" + Build.MODEL
                        + " sdk=" + Build.VERSION.SDK_INT + " channel=" + channel.name);
                showLoading(channel.name, "硬解未检测到画面，正在切换兼容软解");
                try {
                    startPlayer(channel, streamUrl, true);
                } catch (IOException error) {
                    Log.e(TAG, "Unable to start software decoder fallback", error);
                    hideLoading();
                    showChannelBar(channel.name, "兼容软解启动失败: " + error.getMessage());
                }
            }
        }, VIDEO_RENDER_START_TIMEOUT_MS);
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

    private void persistPlayingChannel(Channel channel, int requestId) {
        if (requestId != playRequestId || currentGroupIndex < 0
                || currentGroupIndex >= ChannelCatalog.GROUPS.length) {
            return;
        }
        Channel[] channels = currentGroup().channels;
        if (currentChannelIndex < 0 || currentChannelIndex >= channels.length
                || channels[currentChannelIndex] != channel) {
            return;
        }
        getSharedPreferences(PREFERENCES, MODE_PRIVATE).edit()
                .putInt(LAST_GROUP_INDEX, currentGroupIndex)
                .putInt(LAST_CHANNEL_INDEX, currentChannelIndex)
                .apply();
        // The risky decoder/proxy transition is over once a real frame is rendered.
        // Stop the separate watchdog process so stable playback has no extra memory cost.
        releaseCrashRecovery(true);
    }

    private void armCrashRecovery() {
        final Intent watchdog = new Intent(this, CrashRecoveryService.class)
                .setAction(CrashRecoveryService.ACTION_ARM);
        try {
            startService(watchdog);
            if (!crashRecoveryBound) {
                if (crashRecoveryConnection == null) {
                    crashRecoveryConnection = new ServiceConnection() {
                        @Override
                        public void onServiceConnected(ComponentName name, IBinder service) {
                            crashRecoveryBound = true;
                        }

                        @Override
                        public void onServiceDisconnected(ComponentName name) {
                            crashRecoveryBound = false;
                        }
                    };
                }
                crashRecoveryBound = bindService(watchdog,
                        crashRecoveryConnection, Context.BIND_AUTO_CREATE);
            }
        } catch (RuntimeException error) {
            crashRecoveryBound = false;
            Log.w(TAG, "Unable to arm crash recovery watchdog", error);
        }
    }

    private void showCrashRecoveryNotice() {
        if (getIntent().getBooleanExtra(CrashRecoveryService.EXTRA_RECOVERED, false)) {
            getIntent().removeExtra(CrashRecoveryService.EXTRA_RECOVERED);
            Toast.makeText(this, "检测到异常退出，已自动恢复", Toast.LENGTH_LONG).show();
        }
    }

    private void releaseCrashRecovery(boolean normalExit) {
        if (normalExit) {
            try {
                startService(new Intent(this, CrashRecoveryService.class)
                        .setAction(CrashRecoveryService.ACTION_DISARM));
            } catch (RuntimeException error) {
                Log.w(TAG, "Unable to disarm crash recovery watchdog", error);
            }
        }
        if (crashRecoveryBound && crashRecoveryConnection != null) {
            try {
                unbindService(crashRecoveryConnection);
            } catch (RuntimeException error) {
                Log.w(TAG, "Unable to unbind crash recovery watchdog", error);
            }
        }
        crashRecoveryBound = false;
    }

    private void prefetchChannel(Channel channel) {
        try {
            liveUrlResolver.resolve(channel);
        } catch (IOException error) {
            Log.d(TAG, "Unable to prefetch " + channel.streamId, error);
        }
    }

    private void switchRelative(int offset) {
        ChannelCatalog.Group group = currentGroup();
        Channel[] channels = group.channels;
        if (channels == null || channels.length == 0) {
            Log.w(TAG, "Ignoring relative switch for empty group=" + currentGroupIndex);
            return;
        }
        int baseIndex = pendingRelativeGroupIndex == currentGroupIndex
                && pendingRelativeChannelIndex >= 0
                ? pendingRelativeChannelIndex : currentChannelIndex;
        int targetIndex = ChannelCatalog.wrapIndex(channels, baseIndex + offset);
        pendingRelativeGroupIndex = currentGroupIndex;
        pendingRelativeChannelIndex = targetIndex;
        channelBar.removeCallbacks(commitRelativeChannelSwitch);
        showChannelBar(channels[targetIndex].name, "正在切换频道");
        channelBar.postDelayed(commitRelativeChannelSwitch, CHANNEL_SWITCH_DEBOUNCE_MS);
    }

    private void cancelPendingRelativeSwitch() {
        if (channelBar != null) {
            channelBar.removeCallbacks(commitRelativeChannelSwitch);
        }
        pendingRelativeGroupIndex = -1;
        pendingRelativeChannelIndex = -1;
    }

    private void enterNumericChannel(int digit) {
        if (numericChannelInput.length() >= 3) {
            clearNumericChannelInput();
        }
        numericChannelInput += String.valueOf(digit);
        channelBar.removeCallbacks(commitNumericChannel);
        numericChannelOverlay.setText(numericChannelInput);
        numericChannelOverlay.setVisibility(View.VISIBLE);
        numericChannelOverlay.bringToFront();
        ensureFlyMouseOnTop();
        if (numericChannelInput.length() >= 3) {
            commitNumericChannel();
        } else {
            channelBar.postDelayed(commitNumericChannel, NUMERIC_CHANNEL_TIMEOUT_MS);
        }
    }

    private void commitNumericChannel() {
        if (numericChannelInput.length() == 0) {
            return;
        }
        String channelNumber = numericChannelInput;
        clearNumericChannelInput();
        Channel[] channels = currentGroup().channels;
        for (int index = 0; index < channels.length; index++) {
            if (channelNumber.equals(channels[index].number)) {
                switchChannel(index);
                return;
            }
        }
        showChannelBar(currentChannel().name, "没有频道号 " + channelNumber);
    }

    private void clearNumericChannelInput() {
        if (channelBar != null) {
            channelBar.removeCallbacks(commitNumericChannel);
        }
        numericChannelInput = "";
        if (numericChannelOverlay != null) {
            numericChannelOverlay.setVisibility(View.GONE);
        }
    }

    private void togglePlayback() {
        cancelPendingRelativeSwitch();
        Channel channel = currentChannel();
        if (!hasActivePlayer() || !prepared) {
            switchChannel(currentChannelIndex);
        } else {
            if (player.isPlaying()) {
                player.pause();
                showChannelBar(channel.name, "已暂停");
            } else {
                player.start();
                showChannelBar(channel.name, "直播播放中");
            }
        }
    }

    private void switchBrowsingChannel(int position) {
        currentGroupIndex = browsingGroupIndex;
        switchChannel(position);
        closeChannelList();
    }

    private void openChannelList() {
        cancelPendingRelativeSwitch();
        clearNumericChannelInput();
        lastBackPressedAt = 0L;
        backPrompt.removeCallbacks(hideBackPrompt);
        backPrompt.setVisibility(View.GONE);
        closeManagementPanel();
        channelListPanel.setVisibility(View.VISIBLE);
        ensureFlyMouseOnTop();
        showChannelMenu(currentGroupIndex);
        applyClockLocation();
        channelList.post(new Runnable() {
            @Override
            public void run() {
                channelList.setSelection(currentChannelIndex);
                channelList.requestFocusFromTouch();
                channelList.requestFocus();
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
        applyClockLocation();
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

    private String yangshipinDefinition(Channel channel) {
        if (RESOLUTION_MODE_LOW.equals(resolutionMode)) {
            return "hd";
        }
        if (RESOLUTION_MODE_MEDIUM.equals(resolutionMode)
                || "shd".equals(channel.yangshipinMaxDefinition)) {
            return "shd";
        }
        return "fhd";
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
        videoRenderingStarted = false;
        activeSoftwareDecode = false;
        buffering = false;
        bufferingStatusVisible = false;
        bufferingEventId++;
        playbackProgressObserved = false;
        lastPlaybackPosition = -1L;
        lastPlaybackProgressAt = 0L;
        estimatedVideoBitrate = -1L;
        estimatedAudioBitrate = -1L;
        resetNetworkSpeedSamples();
        if (networkSpeedOverlay != null && showNetworkSpeed) {
            networkSpeedOverlay.setText("--");
        }
        if (videoInfo != null) {
            videoInfo.removeCallbacks(updateVideoInfo);
        }
        if (player != null) {
            IjkMediaPlayer oldPlayer = player;
            player = null;
            try {
                oldPlayer.setDisplay(null);
            } catch (RuntimeException error) {
                Log.w(TAG, "Unable to detach old IJK player", error);
            }
            try {
                oldPlayer.release();
            } catch (RuntimeException error) {
                Log.w(TAG, "Unable to release old IJK player", error);
            }
        }
        activePlayerChannel = null;
        activePlayerStreamUrl = null;
    }

    private boolean hasActivePlayer() {
        return player != null;
    }

    private void queuePendingPlayer(Channel channel, String streamUrl,
            boolean forceSoftwareDecode) {
        pendingPlayerChannel = channel;
        pendingPlayerStreamUrl = streamUrl;
        pendingForceSoftwareDecode = forceSoftwareDecode;
        pendingPlayerRequestId = playRequestId;
    }

    private void clearPendingPlayer() {
        pendingPlayerChannel = null;
        pendingPlayerStreamUrl = null;
        pendingForceSoftwareDecode = false;
        pendingPlayerRequestId = -1;
    }

    private void startPendingPlayer() {
        if (pendingPlayerRequestId != playRequestId || pendingPlayerChannel == null
                || pendingPlayerStreamUrl == null) {
            clearPendingPlayer();
            return;
        }
        Channel channel = pendingPlayerChannel;
        String streamUrl = pendingPlayerStreamUrl;
        boolean forceSoftwareDecode = pendingForceSoftwareDecode;
        clearPendingPlayer();
        try {
            startPlayer(channel, streamUrl, forceSoftwareDecode);
        } catch (IOException error) {
            Log.e(TAG, "Unable to resume player after Surface creation", error);
            hideLoading();
            showChannelBar(channel.name, "视频界面恢复失败: " + error.getMessage());
        }
    }

    private void resetVideoLayout() {
        videoWidth = 0;
        videoHeight = 0;
        videoSarNum = 1;
        videoSarDen = 1;
        videoView.resetSurfaceBufferSizePreservingAspect();
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
        if (videoInfo == null && debugInfoOverlay == null) {
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
            long playbackPosition = player != null ? player.getCurrentPosition() : -1L;
            // A live HLS window can rebase the reported position when older
            // segments leave the manifest.  A backwards jump is still playback
            // progress; treating it as a stall causes a false reconnect roughly once
            // per playlist-history window.
            if (playbackPosition >= 0L && playbackPosition != lastPlaybackPosition) {
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
        String decoderStatus;
        if (activeSoftwareDecode) {
            decoderStatus = "IJK软解";
        } else if (!HARDWARE_DECODER_AUTO.equals(hardwareDecoder)) {
            decoderStatus = hardwareDecoder;
        } else {
            decoderStatus = "IJK硬解";
        }
        if (videoInfo != null) {
            videoInfo.setText("源: " + resolution + "  fps: " + fps + "  " + decoderStatus);
        }
        if (debugInfoOverlay != null && showDebugInfo) {
            PlaybackDebugStats stats = collectPlaybackDebugStats(outputFps);
            String debugResolution = stats.width > 0 && stats.height > 0
                    ? stats.width + "×" + stats.height : "--";
            String debugFps = stats.frameRate > 0.01f
                    ? String.format(Locale.US, "%.1ffps", stats.frameRate) : "--fps";
            debugInfoOverlay.setText("视频 " + debugResolution + " · " + debugFps
                    + " · " + stats.videoCodec + " · " + formatBitrate(stats.videoBitrate)
                    + "\n音频 " + stats.audioCodec + " · "
                    + formatBitrate(stats.audioBitrate)
                    + "\n" + stats.cpuLabel + " " + formatCpuUsage(stats.cpuUsage));
        }
        if (networkSpeedOverlay != null && showNetworkSpeed) {
            networkSpeedOverlay.setText(
                    formatNetworkSpeed(sampleNetworkBytesPerSecond()));
        }
    }

    private long sampleNetworkBytesPerSecond() {
        HlsProxyServer activeProxy = proxy;
        if (activeProxy == null) {
            resetNetworkSpeedSamples();
            return -1L;
        }
        if (sampledNetworkProxy != activeProxy) {
            resetNetworkSpeedSamples();
            sampledNetworkProxy = activeProxy;
        }
        long now = SystemClock.elapsedRealtime();
        long totalBytes = activeProxy.getUpstreamDownloadedBytes();
        int slot = networkSpeedSampleNext;
        networkSpeedSampleBytes[slot] = totalBytes;
        networkSpeedSampleTimes[slot] = now;
        networkSpeedSampleNext = (slot + 1) % networkSpeedSampleBytes.length;
        if (networkSpeedSampleCount < networkSpeedSampleBytes.length) {
            networkSpeedSampleCount++;
        }
        if (networkSpeedSampleCount < 2) {
            return smoothedNetworkBytesPerSecond;
        }
        int oldest = (networkSpeedSampleNext - networkSpeedSampleCount
                + networkSpeedSampleBytes.length) % networkSpeedSampleBytes.length;
        long elapsedMs = now - networkSpeedSampleTimes[oldest];
        long downloaded = totalBytes - networkSpeedSampleBytes[oldest];
        if (elapsedMs <= 0L || downloaded < 0L) {
            resetNetworkSpeedSamples();
            sampledNetworkProxy = activeProxy;
            return -1L;
        }
        long sample = downloaded * 1000L / elapsedMs;
        smoothedNetworkBytesPerSecond = smoothedNetworkBytesPerSecond < 0L
                ? sample : (smoothedNetworkBytesPerSecond * 2L + sample * 3L) / 5L;
        return smoothedNetworkBytesPerSecond;
    }

    private void resetNetworkSpeedSamples() {
        networkSpeedSampleNext = 0;
        networkSpeedSampleCount = 0;
        smoothedNetworkBytesPerSecond = -1L;
        sampledNetworkProxy = null;
    }

    private static String formatNetworkSpeed(long bytesPerSecond) {
        if (bytesPerSecond < 0L) {
            return "--";
        }
        if (bytesPerSecond >= 1024L * 1024L) {
            return String.format(Locale.US, "%.1f MB/s",
                    bytesPerSecond / (1024f * 1024f));
        }
        return Math.round(bytesPerSecond / 1024f) + " KB/s";
    }

    private PlaybackDebugStats collectPlaybackDebugStats(float measuredOutputFps) {
        PlaybackDebugStats stats = new PlaybackDebugStats();
        stats.width = videoWidth;
        stats.height = videoHeight;
        stats.frameRate = measuredOutputFps;
        stats.cpuUsage = sampleSystemCpuUsage();
        stats.cpuLabel = systemCpuMetricLabel;
        if (player != null) {
            applyIjkMetadata(stats);
            applyIjkRuntimeBitrates(stats);
        }
        return stats;
    }

    private float sampleSystemCpuUsage() {
        float usage = sampleProcStatCpuUsage();
        if (usage >= 0f) {
            useSystemCpuMetric("proc-stat", "CPU（系统）");
            return usage;
        }
        usage = sampleHardwareCpuUsage();
        if (usage >= 0f) {
            useSystemCpuMetric("hardware-properties", "CPU（系统）");
            return usage;
        }
        usage = sampleCpuIdleSysfsUsage();
        if (usage >= 0f) {
            useSystemCpuMetric("cpuidle-sysfs", "CPU（系统）");
            return usage;
        }
        usage = sampleSystemLoadAverage();
        if (usage >= 0f) {
            // Android 8+ commonly hides aggregate CPU time from ordinary apps.
            // A normalized one-minute load is still system-wide, but is not the
            // same thing as instantaneous utilization, so label it explicitly.
            useSystemCpuMetric("loadavg", "CPU（系统负载）");
            return usage;
        }
        useSystemCpuMetric("unavailable", "CPU（系统）");
        return -1f;
    }

    private float sampleProcStatCpuUsage() {
        if (procStatCpuUnavailable) {
            return -1f;
        }
        FileInputStream input = null;
        try {
            input = new FileInputStream("/proc/stat");
            byte[] buffer = new byte[512];
            int length = input.read(buffer);
            if (length <= 0) {
                return -1f;
            }
            int lineEnd = 0;
            while (lineEnd < length && buffer[lineEnd] != '\n') {
                lineEnd++;
            }
            String[] fields = new String(buffer, 0, lineEnd, "US-ASCII")
                    .trim().split("\\s+");
            if (fields.length < 5 || !"cpu".equals(fields[0])) {
                return -1f;
            }
            /* /proc/stat: user nice system idle iowait irq softirq steal ...
             * guest values are already included in user/nice, so do not count them twice. */
            int lastField = Math.min(fields.length - 1, 8);
            long total = 0L;
            for (int index = 1; index <= lastField; index++) {
                total += Long.parseLong(fields[index]);
            }
            long idle = Long.parseLong(fields[4]);
            if (fields.length > 5) {
                idle += Long.parseLong(fields[5]);
            }
            float usage = -1f;
            long totalDelta = total - lastSystemCpuTotalJiffies;
            long idleDelta = idle - lastSystemCpuIdleJiffies;
            if (lastSystemCpuTotalJiffies > 0L && totalDelta > 0L
                    && idleDelta >= 0L) {
                usage = Math.max(0f, Math.min(100f,
                        (totalDelta - idleDelta) * 100f / totalDelta));
            }
            lastSystemCpuTotalJiffies = total;
            lastSystemCpuIdleJiffies = idle;
            return usage;
        } catch (IOException error) {
            procStatCpuUnavailable = true;
            return -1f;
        } catch (NumberFormatException error) {
            return -1f;
        } catch (SecurityException error) {
            procStatCpuUnavailable = true;
            return -1f;
        } finally {
            if (input != null) {
                try {
                    input.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    private float sampleHardwareCpuUsage() {
        if (hardwareCpuUnavailable || Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            return -1f;
        }
        try {
            HardwarePropertiesManager manager = (HardwarePropertiesManager)
                    getSystemService(HARDWARE_PROPERTIES_SERVICE);
            if (manager == null) {
                hardwareCpuUnavailable = true;
                return -1f;
            }
            CpuUsageInfo[] cores = manager.getCpuUsages();
            if (cores == null || cores.length == 0) {
                hardwareCpuUnavailable = true;
                return -1f;
            }
            long active = 0L;
            long total = 0L;
            for (CpuUsageInfo core : cores) {
                if (core != null) {
                    active += core.getActive();
                    total += core.getTotal();
                }
            }
            long activeDelta = active - lastHardwareCpuActiveMillis;
            long totalDelta = total - lastHardwareCpuTotalMillis;
            float usage = -1f;
            if (lastHardwareCpuTotalMillis > 0L && totalDelta > 0L
                    && activeDelta >= 0L) {
                usage = clampCpuUsage(activeDelta * 100f / totalDelta);
            }
            lastHardwareCpuActiveMillis = active;
            lastHardwareCpuTotalMillis = total;
            return usage;
        } catch (RuntimeException error) {
            // The API is public but most devices expose it only to device-owner or
            // privileged applications. Do not retry a denied binder call every second.
            hardwareCpuUnavailable = true;
            Log.i(TAG, "System HardwareProperties CPU unavailable: "
                    + error.getClass().getSimpleName());
            return -1f;
        }
    }

    private float sampleCpuIdleSysfsUsage() {
        if (sysfsCpuUnavailable) {
            return -1f;
        }
        File[] cpuDirectories;
        try {
            cpuDirectories = new File("/sys/devices/system/cpu").listFiles();
        } catch (SecurityException error) {
            sysfsCpuUnavailable = true;
            return -1f;
        }
        if (cpuDirectories == null) {
            sysfsCpuUnavailable = true;
            return -1f;
        }
        long idleMicros = 0L;
        int cpuCount = 0;
        for (File cpuDirectory : cpuDirectories) {
            String name = cpuDirectory.getName();
            if (!isCpuDirectoryName(name) || !isCpuOnline(cpuDirectory)) {
                continue;
            }
            File[] states;
            try {
                states = new File(cpuDirectory, "cpuidle").listFiles();
            } catch (SecurityException error) {
                continue;
            }
            if (states == null) {
                continue;
            }
            long coreIdleMicros = 0L;
            boolean readable = false;
            for (File state : states) {
                if (!state.getName().startsWith("state")) {
                    continue;
                }
                try {
                    coreIdleMicros += Long.parseLong(
                            readSmallAsciiFile(new File(state, "time")));
                    readable = true;
                } catch (IOException ignored) {
                } catch (NumberFormatException ignored) {
                } catch (SecurityException ignored) {
                }
            }
            if (readable) {
                idleMicros += coreIdleMicros;
                cpuCount++;
            }
        }
        if (cpuCount == 0) {
            sysfsCpuUnavailable = true;
            return -1f;
        }
        long now = SystemClock.elapsedRealtime();
        long elapsedMicros = (now - lastSysfsCpuSampleElapsedMillis) * 1000L;
        long idleDelta = idleMicros - lastSysfsCpuIdleMicros;
        float usage = -1f;
        if (lastSysfsCpuSampleElapsedMillis > 0L && lastSysfsCpuCount == cpuCount
                && elapsedMicros > 0L && idleDelta >= 0L) {
            long availableMicros = elapsedMicros * cpuCount;
            if (availableMicros > 0L) {
                usage = clampCpuUsage(
                        (availableMicros - Math.min(availableMicros, idleDelta))
                                * 100f / availableMicros);
            }
        }
        lastSysfsCpuIdleMicros = idleMicros;
        lastSysfsCpuSampleElapsedMillis = now;
        lastSysfsCpuCount = cpuCount;
        return usage;
    }

    private float sampleSystemLoadAverage() {
        try {
            String text = readSmallAsciiFile(new File("/proc/loadavg"));
            int separator = text.indexOf(' ');
            String first = separator >= 0 ? text.substring(0, separator) : text;
            float oneMinuteLoad = Float.parseFloat(first);
            int processors = Math.max(1, Runtime.getRuntime().availableProcessors());
            return Math.max(0f, oneMinuteLoad * 100f / processors);
        } catch (IOException error) {
            return -1f;
        } catch (NumberFormatException error) {
            return -1f;
        } catch (SecurityException error) {
            return -1f;
        }
    }

    private static boolean isCpuDirectoryName(String name) {
        if (name == null || name.length() <= 3 || !name.startsWith("cpu")) {
            return false;
        }
        for (int index = 3; index < name.length(); index++) {
            if (!Character.isDigit(name.charAt(index))) {
                return false;
            }
        }
        return true;
    }

    private static boolean isCpuOnline(File cpuDirectory) {
        File online = new File(cpuDirectory, "online");
        if (!online.exists()) {
            return true;
        }
        try {
            return !"0".equals(readSmallAsciiFile(online));
        } catch (IOException ignored) {
            return true;
        } catch (SecurityException ignored) {
            return true;
        }
    }

    private static String readSmallAsciiFile(File file) throws IOException {
        FileInputStream input = new FileInputStream(file);
        try {
            byte[] buffer = new byte[128];
            int length = input.read(buffer);
            if (length <= 0) {
                throw new IOException("Empty file: " + file);
            }
            return new String(buffer, 0, length, "US-ASCII").trim();
        } finally {
            input.close();
        }
    }

    private void useSystemCpuMetric(String source, String label) {
        systemCpuMetricLabel = label;
        if (!source.equals(systemCpuMetricSource)) {
            systemCpuMetricSource = source;
            Log.i(TAG, "System CPU metric source=" + source);
        }
    }

    private static float clampCpuUsage(float usage) {
        return Math.max(0f, Math.min(100f, usage));
    }

    private void applyIjkMetadata(PlaybackDebugStats stats) {
        try {
            IjkMediaMeta meta = IjkMediaMeta.parse(player.getMediaMeta());
            if (meta == null) {
                return;
            }
            IjkMediaMeta.IjkStreamMeta video = meta.mVideoStream;
            if (video != null) {
                if (video.mWidth > 0 && video.mHeight > 0) {
                    stats.width = video.mWidth;
                    stats.height = video.mHeight;
                }
                if (stats.frameRate <= 0.01f && video.mFpsNum > 0 && video.mFpsDen > 0) {
                    stats.frameRate = (float) video.mFpsNum / video.mFpsDen;
                }
                stats.videoCodec = readableCodec(video.mCodecName, null);
                stats.videoBitrate = video.mBitrate;
            }
            IjkMediaMeta.IjkStreamMeta audio = meta.mAudioStream;
            if (audio != null) {
                stats.audioCodec = readableCodec(audio.mCodecName, null);
                stats.audioBitrate = audio.mBitrate;
            }
        } catch (RuntimeException error) {
            Log.w(TAG, "Unable to read IJK stream metadata", error);
        }
    }

    /**
     * HLS/TS live streams commonly omit per-stream bit rates from their metadata.
     * IJK still exposes the queued bytes and duration for each elementary stream,
     * which lets us estimate the encoded bit rate without confusing it with the
     * much more bursty network download speed.
     */
    private void applyIjkRuntimeBitrates(PlaybackDebugStats stats) {
        IjkMediaPlayer activePlayer = player;
        if (activePlayer == null) {
            return;
        }
        try {
            long videoSample = estimateCachedBitrate(activePlayer.getVideoCachedBytes(),
                    activePlayer.getVideoCachedDuration(), 32000L, 200000000L);
            long audioSample = estimateCachedBitrate(activePlayer.getAudioCachedBytes(),
                    activePlayer.getAudioCachedDuration(), 4000L, 10000000L);

            if (videoSample > 0L) {
                estimatedVideoBitrate = smoothBitrate(estimatedVideoBitrate, videoSample);
            }
            if (audioSample > 0L) {
                estimatedAudioBitrate = smoothBitrate(estimatedAudioBitrate, audioSample);
            }

            long totalBitrate = activePlayer.getBitRate();
            if (estimatedVideoBitrate <= 0L && totalBitrate > 0L
                    && estimatedAudioBitrate > 0L
                    && totalBitrate > estimatedAudioBitrate) {
                estimatedVideoBitrate = totalBitrate - estimatedAudioBitrate;
            }
            if (estimatedAudioBitrate <= 0L && totalBitrate > 0L
                    && estimatedVideoBitrate > 0L
                    && totalBitrate > estimatedVideoBitrate) {
                estimatedAudioBitrate = totalBitrate - estimatedVideoBitrate;
            }

            // Metadata is authoritative when present; runtime estimates only fill gaps.
            if (stats.videoBitrate <= 0L && estimatedVideoBitrate > 0L) {
                stats.videoBitrate = estimatedVideoBitrate;
            }
            if (stats.audioBitrate <= 0L && estimatedAudioBitrate > 0L) {
                stats.audioBitrate = estimatedAudioBitrate;
            }
        } catch (RuntimeException error) {
            Log.w(TAG, "Unable to estimate IJK stream bitrates", error);
        }
    }

    private static long estimateCachedBitrate(long cachedBytes, long cachedDurationMs,
            long minimumBitrate, long maximumBitrate) {
        // Very short queue windows exaggerate individual packet boundaries.
        if (cachedBytes <= 0L || cachedDurationMs < 250L
                || cachedBytes > Long.MAX_VALUE / 8000L) {
            return -1L;
        }
        long bitrate = cachedBytes * 8000L / cachedDurationMs;
        return bitrate >= minimumBitrate && bitrate <= maximumBitrate ? bitrate : -1L;
    }

    private static long smoothBitrate(long previous, long sample) {
        if (previous <= 0L) {
            return sample;
        }
        // A 25% moving update keeps the overlay readable while following changes.
        return previous + (sample - previous) / 4L;
    }

    private static String readableCodec(String mimeOrCodec, String codecs) {
        String value = mimeOrCodec;
        if (value == null || value.trim().length() == 0) {
            value = codecs;
        }
        if (value == null || value.trim().length() == 0) {
            return "--";
        }
        String lower = value.toLowerCase(Locale.US);
        if (lower.contains("avc") || lower.contains("h264") || lower.contains("h.264")) {
            return "H.264";
        }
        if (lower.contains("hevc") || lower.contains("h265") || lower.contains("h.265")
                || lower.contains("hvc1") || lower.contains("hev1")) {
            return "H.265";
        }
        if (lower.contains("mpeg2video") || lower.contains("video/mpeg2")) {
            return "MPEG-2";
        }
        if (lower.contains("mp4v") || lower.contains("mpeg4")) {
            return "MPEG-4";
        }
        if (lower.contains("mp4a") || lower.contains("aac")) {
            return "AAC";
        }
        if (lower.contains("eac3") || lower.contains("e-ac-3")) {
            return "E-AC-3";
        }
        if (lower.contains("ac3") || lower.contains("ac-3")) {
            return "AC-3";
        }
        if (lower.contains("opus")) {
            return "Opus";
        }
        if (lower.contains("vorbis")) {
            return "Vorbis";
        }
        if (lower.contains("audio/mpeg") || lower.equals("mp3")) {
            return "MP3";
        }
        int slash = value.lastIndexOf('/');
        String shortName = slash >= 0 ? value.substring(slash + 1) : value;
        int comma = shortName.indexOf(',');
        if (comma > 0) {
            shortName = shortName.substring(0, comma);
        }
        return shortName.length() > 16 ? shortName.substring(0, 16) : shortName;
    }

    private static String formatBitrate(long bitsPerSecond) {
        if (bitsPerSecond <= 0L) {
            return "--";
        }
        if (bitsPerSecond >= 1000000L) {
            return String.format(Locale.US, "%.1fMbps", bitsPerSecond / 1000000f);
        }
        if (bitsPerSecond >= 1000L) {
            return Math.round(bitsPerSecond / 1000f) + "kbps";
        }
        return bitsPerSecond + "bps";
    }

    private static String formatCpuUsage(float usage) {
        return usage >= 0f ? String.format(Locale.US, "%.0f%%", usage) : "--";
    }

    private static final class PlaybackDebugStats {
        int width;
        int height;
        float frameRate;
        String videoCodec = "--";
        long videoBitrate = -1L;
        String audioCodec = "--";
        long audioBitrate = -1L;
        float cpuUsage = -1f;
        String cpuLabel = "CPU（系统）";
    }

    private void moveChannelMenuSelection(int offset) {
        if (groupList.hasFocus()) {
            int position = groupList.getSelectedItemPosition();
            if (position == AdapterView.INVALID_POSITION) {
                position = browsingGroupIndex;
            }
            int nextPosition = Math.max(0, Math.min(
                    ChannelCatalog.GROUPS.length - 1, position + offset));
            if (nextPosition != browsingGroupIndex) {
                showChannelMenu(nextPosition);
            } else {
                groupList.setSelection(nextPosition);
            }
            return;
        }

        if (!channelList.hasFocus()) {
            channelList.requestFocus();
        }
        int position = channelList.getSelectedItemPosition();
        Channel[] channels = ChannelCatalog.GROUPS[browsingGroupIndex].channels;
        if (position == AdapterView.INVALID_POSITION) {
            position = browsingGroupIndex == currentGroupIndex
                    ? currentChannelIndex : ChannelCatalog.defaultChannelIndex(
                            ChannelCatalog.GROUPS[browsingGroupIndex]);
        }
        int nextPosition = Math.max(0, Math.min(channels.length - 1, position + offset));
        channelList.setSelection(nextPosition);
    }

    private static boolean isHandledRemoteKey(int keyCode) {
        if (digitForKeyCode(keyCode) >= 0) {
            return true;
        }
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_MENU:
            case KeyEvent.KEYCODE_BACK:
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
                return true;
            default:
                return false;
        }
    }

    private static int digitForKeyCode(int keyCode) {
        if (keyCode >= KeyEvent.KEYCODE_0 && keyCode <= KeyEvent.KEYCODE_9) {
            return keyCode - KeyEvent.KEYCODE_0;
        }
        if (keyCode >= KeyEvent.KEYCODE_NUMPAD_0 && keyCode <= KeyEvent.KEYCODE_NUMPAD_9) {
            return keyCode - KeyEvent.KEYCODE_NUMPAD_0;
        }
        return -1;
    }

    private void setRemoteInputMode(boolean remote) {
        if (remoteInputMode != remote) {
            remoteInputMode = remote;
            Log.i(TAG, "Input mode changed to " + (remote ? "remote" : "touch"));
        }
    }

    private static boolean isTouchInput(MotionEvent event) {
        int source = event.getSource();
        return (source & InputDevice.SOURCE_TOUCHSCREEN) == InputDevice.SOURCE_TOUCHSCREEN
                || (source & InputDevice.SOURCE_STYLUS) == InputDevice.SOURCE_STYLUS;
    }

    private boolean canStartPlaybackGesture() {
        return root != null
                && channelListPanel != null
                && channelListPanel.getVisibility() != View.VISIBLE
                && managementPanel != null
                && managementPanel.getVisibility() != View.VISIBLE
                && backPrompt != null
                && backPrompt.getVisibility() != View.VISIBLE
                && (webSourceView == null || !webSourceView.isPageVisible());
    }

    private void beginPlaybackGesture(MotionEvent event) {
        playbackGestureTracking = true;
        playbackGestureVertical = false;
        playbackGestureDownX = event.getX();
        playbackGestureDownY = event.getY();
        playbackGestureLeftSide = playbackGestureDownX < root.getWidth() / 2f;
        playbackGestureLastVolume = -1;
        if (playbackGestureLeftSide) {
            AudioManager audio = (AudioManager) getSystemService(AUDIO_SERVICE);
            playbackGestureStartVolume = audio == null ? 0
                    : audio.getStreamVolume(AudioManager.STREAM_MUSIC);
            playbackGestureLastVolume = playbackGestureStartVolume;
        }
    }

    private boolean handlePlaybackGesture(MotionEvent event) {
        float deltaX = event.getX() - playbackGestureDownX;
        float deltaY = event.getY() - playbackGestureDownY;
        float absoluteX = Math.abs(deltaX);
        float absoluteY = Math.abs(deltaY);
        if (!playbackGestureVertical
                && absoluteY > playbackGestureTouchSlop
                && absoluteY > absoluteX * 1.15f) {
            playbackGestureVertical = true;
        }

        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_MOVE:
                if (playbackGestureVertical && playbackGestureLeftSide) {
                    updateGestureVolume(deltaY);
                }
                return true;
            case MotionEvent.ACTION_UP:
                if (playbackGestureVertical && playbackGestureLeftSide) {
                    updateGestureVolume(deltaY);
                } else if (playbackGestureVertical) {
                    float channelThreshold = Math.max(playbackGestureTouchSlop * 4f,
                            root.getHeight() * 0.08f);
                    if (absoluteY >= channelThreshold) {
                        // Swipe up advances; swipe down returns to the previous channel.
                        switchRelative(deltaY < 0f ? 1 : -1);
                    }
                } else if (Math.max(absoluteX, absoluteY)
                        <= playbackGestureTouchSlop * 1.5f) {
                    if (playbackGestureLeftSide) {
                        openChannelList();
                    } else {
                        openManagementPage();
                    }
                }
                resetPlaybackGesture();
                return true;
            case MotionEvent.ACTION_CANCEL:
                resetPlaybackGesture();
                return true;
            default:
                return true;
        }
    }

    private void updateGestureVolume(float deltaY) {
        AudioManager audio = (AudioManager) getSystemService(AUDIO_SERVICE);
        if (audio == null) {
            return;
        }
        int maximum = Math.max(1, audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC));
        float fullRangeDistance = Math.max(1f, root.getHeight() * 0.7f);
        int target = playbackGestureStartVolume
                + Math.round(-deltaY / fullRangeDistance * maximum);
        target = Math.max(0, Math.min(maximum, target));
        if (target == playbackGestureLastVolume) {
            return;
        }
        playbackGestureLastVolume = target;
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, target, AudioManager.FLAG_SHOW_UI);
    }

    private void resetPlaybackGesture() {
        playbackGestureTracking = false;
        playbackGestureVertical = false;
        playbackGestureLastVolume = -1;
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN && isTouchInput(event)) {
            setRemoteInputMode(false);
            if (canStartPlaybackGesture()) {
                beginPlaybackGesture(event);
                return true;
            }
        }
        if (playbackGestureTracking) {
            return handlePlaybackGesture(event);
        }
        return super.dispatchTouchEvent(event);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        int keyCode = event.getKeyCode();
        if (event.getAction() == KeyEvent.ACTION_DOWN && isHandledRemoteKey(keyCode)) {
            setRemoteInputMode(true);
        }
        if (event.getAction() == KeyEvent.ACTION_UP && isHandledRemoteKey(keyCode)) {
            return true;
        }
        if (event.getAction() != KeyEvent.ACTION_DOWN) {
            return super.dispatchKeyEvent(event);
        }
        if (event.getRepeatCount() > 0
                && keyCode != KeyEvent.KEYCODE_DPAD_UP
                && keyCode != KeyEvent.KEYCODE_DPAD_DOWN
                && isHandledRemoteKey(keyCode)) {
            return true;
        }

        if (backPrompt.getVisibility() == View.VISIBLE
                && (keyCode == KeyEvent.KEYCODE_DPAD_CENTER
                || keyCode == KeyEvent.KEYCODE_ENTER
                || keyCode == KeyEvent.KEYCODE_MENU)) {
            confirmBackPrompt();
            return true;
        }
        if (backPrompt.getVisibility() == View.VISIBLE && keyCode != KeyEvent.KEYCODE_BACK) {
            return isHandledRemoteKey(keyCode) || super.dispatchKeyEvent(event);
        }

        if (managementPanel.getVisibility() == View.VISIBLE) {
            if (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_MENU) {
                closeManagementPanel();
            }
            return isHandledRemoteKey(keyCode) || super.dispatchKeyEvent(event);
        }

        if (channelListPanel.getVisibility() == View.VISIBLE) {
            scheduleChannelListDismiss();
            switch (keyCode) {
                case KeyEvent.KEYCODE_BACK:
                case KeyEvent.KEYCODE_MENU:
                    closeChannelList();
                    return true;
                case KeyEvent.KEYCODE_DPAD_LEFT:
                    groupList.requestFocus();
                    groupList.setSelection(browsingGroupIndex);
                    return true;
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                    channelList.requestFocus();
                    return true;
                case KeyEvent.KEYCODE_DPAD_UP:
                    moveChannelMenuSelection(-1);
                    return true;
                case KeyEvent.KEYCODE_DPAD_DOWN:
                    moveChannelMenuSelection(1);
                    return true;
                case KeyEvent.KEYCODE_DPAD_CENTER:
                case KeyEvent.KEYCODE_ENTER:
                    if (channelList.hasFocus()) {
                        int position = channelList.getSelectedItemPosition();
                        if (position != AdapterView.INVALID_POSITION) {
                            switchBrowsingChannel(position);
                        }
                    } else {
                        channelList.requestFocus();
                    }
                    return true;
                default:
                    return super.dispatchKeyEvent(event);
            }
        }

        if (event.getRepeatCount() > 0 && (keyCode == KeyEvent.KEYCODE_DPAD_UP
                || keyCode == KeyEvent.KEYCODE_DPAD_DOWN)) {
            return true;
        }
        int digit = digitForKeyCode(keyCode);
        if (digit >= 0) {
            enterNumericChannel(digit);
            return true;
        }
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
                if (switchCustomSource(-1, false, "")) {
                    return true;
                }
                return super.dispatchKeyEvent(event);
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (switchCustomSource(1, false, "")) {
                    return true;
                }
                return super.dispatchKeyEvent(event);
            case KeyEvent.KEYCODE_DPAD_UP:
                switchRelative(reverseUpDown ? 1 : -1);
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                switchRelative(reverseUpDown ? -1 : 1);
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
                openChannelList();
                return true;
            case KeyEvent.KEYCODE_MENU:
                openManagement();
                return true;
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
                togglePlayback();
                return true;
            case KeyEvent.KEYCODE_BACK:
                onBackPressed();
                return true;
            default:
                return super.dispatchKeyEvent(event);
        }
    }

    @Override
    public void onBackPressed() {
        cancelPendingRelativeSwitch();
        clearNumericChannelInput();
        if (webSourceView != null && webSourceView.canGoBack()) {
            webSourceView.goBack();
            return;
        }
        if (managementPanel.getVisibility() == View.VISIBLE) {
            closeManagementPanel();
            return;
        }
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
        backPrompt.removeCallbacks(hideBackPrompt);
        backPrompt.setVisibility(View.VISIBLE);
        backPrompt.bringToFront();
        ensureFlyMouseOnTop();
        backPromptOk.requestFocus();
        backPrompt.postDelayed(hideBackPrompt, BACK_PROMPT_TIMEOUT_MS);
    }

    @Override
    protected void onPause() {
        if (videoView != null) {
            videoView.onPause();
        }
        if (webSourceView != null) {
            webSourceView.pausePage();
        }
        super.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (videoView != null) {
            videoView.onResume();
        }
        if (webSourceView != null) {
            webSourceView.resumePage();
        }
        refreshManagementAddress();
        applySystemUiVisibility();
    }

    @Override
    protected void onDestroy() {
        playRequestId++;
        cancelPendingRelativeSwitch();
        releaseCrashRecovery(isFinishing());
        if (root != null) {
            root.removeCallbacks(updateClock);
        }
        if (backPrompt != null) {
            backPrompt.removeCallbacks(hideBackPrompt);
        }
        clearNumericChannelInput();
        if (autoUpdater != null) {
            autoUpdater.destroy();
        }
        if (controlServer != null) {
            controlServer.close();
            controlServer = null;
        }
        if (webSourceView != null) {
            webSourceView.destroyPage();
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
