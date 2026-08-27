package xiao.bu.tv;

import com.bu.cc.tv.NativeCmgDecryptor;
import android.animation.TimeInterpolator;
import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.ActivityManager;
import android.app.UiModeManager;
import android.content.Intent;
import android.content.ComponentName;
import android.content.Context;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.media.AudioManager;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.graphics.Bitmap;
import android.graphics.Paint;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.CpuUsageInfo;
import android.os.Handler;
import android.os.HardwarePropertiesManager;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.util.TypedValue;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.Gravity;
import android.view.PixelCopy;
import android.view.SurfaceHolder;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.AdapterView;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
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
    private static final String LAST_CHANNEL_SNAPSHOT = "last_channel_snapshot_v1";
    private static final String FAVORITE_CHANNEL_KEYS = "favorite_channel_keys_v1";
    private static final String FAVORITE_GROUP_INDEX_MIGRATED =
            "favorite_group_index_migrated_v1";
    private static final String CENTRAL_GROUPS_MERGED = "central_groups_merged_v1";
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
    private static final String WEB_VIEW_RESOLUTION = "web_view_resolution";
    private static final String WEB_VIEW_RESOLUTION_1080P = "1080p";
    private static final String WEB_VIEW_RESOLUTION_720P = "720p";
    private static final String WEB_VIEW_RESOLUTION_480P = "480p";
    private static final String WEB_VIEW_LOAD_IMAGES = "web_view_load_images";
    private static final String CLOCK_LOCATION = "clock_location";
    private static final String CLOCK_LOCATION_CHANNEL_LIST = "channel_list";
    private static final String CLOCK_LOCATION_VIDEO = "video";
    private static final String CLOCK_LOCATION_LEFT = "left";
    private static final String CLOCK_LOCATION_RIGHT = "right";
    private static final String SHOW_DATE_TIME = "show_date_time";
    private static final String DATE_TIME_FORMAT = "date_time_format";
    private static final String DATE_TIME_DATE_FIRST = "date_time_week";
    private static final String DATE_TIME_TIME_FIRST = "time_date_week";
    private static final String DATE_TIME_WEEK_FIRST = "week_date_time";
    private static final String EPG_URL = "epg_url";
    private static final String SHOW_DEBUG_INFO = "show_debug_info";
    private static final String SHOW_NETWORK_SPEED = "show_network_speed";
    private static final String SHOW_DATE = "show_date";
    private static final String FLY_MOUSE_ENABLED = "fly_mouse_enabled";
    private static final String LIVE_DELAY_MODE = "live_delay_mode";
    private static final String LIVE_DELAY_LOW = "low";
    private static final String LIVE_DELAY_BALANCED = "balanced";
    private static final String LIVE_DELAY_STABLE = "stable";
    private static final String GITHUB_URL = "https://github.com/buhanzhe/NativeWasmTv";
    private static final int FIRST_LAUNCH_GROUP_INDEX = 2;
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
    private static final int LOCAL_PLAYLIST_PERMISSION_REQUEST = 4201;
    private static final long VIDEO_RENDER_START_TIMEOUT_MS = 10000L;
    private static final long GESTURE_SWITCH_ANIMATION_MS = 220L;
    private static final long GESTURE_REBOUND_ANIMATION_MS = 230L;
    private static final long GESTURE_REBOUND_FINISH_MS = 240L;
    private static final DecelerateInterpolator GESTURE_SWITCH_EASING =
            new DecelerateInterpolator(1.7f);
    private static final DecelerateInterpolator PLAYBACK_RESTORE_EASING =
            new DecelerateInterpolator(1.6f);
    private static final OvershootInterpolator GESTURE_REBOUND_EASING =
            new OvershootInterpolator(0.55f);
    // Kept local because older ijkplayer Java artifacts do not expose every info constant.
    private static final int MEDIA_INFO_VIDEO_RENDERING_START = 3;

    private final Runnable hideChannelBar = new Runnable() {
        @Override
        public void run() {
            if (!loadingActive) {
                channelBar.setVisibility(View.GONE);
            }
        }
    };
    private final Runnable hideChannelList = new Runnable() {
        @Override
        public void run() {
            if (channelPanelTouching || channelPanelHovering) {
                channelListPanel.postDelayed(this, PANEL_TIMEOUT_MS);
            } else {
                closeChannelList();
            }
        }
    };
    private final Runnable hideBackPrompt = new Runnable() {
        @Override
        public void run() {
            backPrompt.setVisibility(View.GONE);
            lastBackPressedAt = 0L;
            lastWebBackPressedAt = 0L;
            webClosePrompt = false;
            resetBackPromptContent();
            root.requestFocus();
        }
    };
    private final Runnable commitNumericChannel = new Runnable() {
        @Override
        public void run() {
            commitNumericChannel();
        }
    };
    private final Runnable updateClock = new Runnable() {
        @Override
        public void run() {
            if (!showDateTime) {
                return;
            }
            Date nowDate = new Date();
            videoClock.setText(formatDateTime(nowDate));
            long now = System.currentTimeMillis();
            root.postDelayed(this, 1000L - now % 1000L);
        }
    };
    private final SimpleDateFormat channelEpgTimeFormat =
            new SimpleDateFormat("HH:mm", Locale.getDefault());

    private View root;
    private View channelBar;
    private View channelListPanel;
    private ProgressBar channelProgress;
    private TextView videoClock;
    private TextView videoDate;
    private TextView debugInfoOverlay;
    private TextView networkSpeedOverlay;
    private TextView channelName;
    private TextView statusText;
    private TextView channelSourcePath;
    private TextView channelEpg;
    private TextView videoInfo;
    private TextView numericChannelOverlay;
    private TextView managementUrl;
    private ListView groupList;
    private ListView channelList;
    private ListView epgList;
    private View epgColumn;
    private TextView epgStatus;
    private ChannelListAdapter groupAdapter;
    private ChannelListAdapter channelAdapter;
    private EpgListAdapter epgAdapter;
    private EpgManager epgManager;
    private LiveUrlResolver liveUrlResolver;
    private YangshipinWebResolver yangshipinResolver;
    private DirectVideoView videoView;
    private View channelSwitchBlackout;
    private ImageView channelSwipeSnapshot;
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
    private boolean activeEmbeddedCctvResolver;
    private boolean activeEmbeddedYangshipinResolver;
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
    private long lastWebBackPressedAt;
    private boolean webClosePrompt;
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
    private TextView backPromptText;
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
    private volatile String webViewResolution;
    private volatile boolean webViewLoadImages;
    private volatile String clockLocation;
    private volatile boolean showDebugInfo;
    private volatile boolean showNetworkSpeed;
    private volatile boolean showDateTime;
    private volatile String dateTimeFormat;
    private volatile String epgUrl;
    private volatile boolean flyMouseEnabled;
    private volatile String liveDelayMode;
    private int clockViewportWidth;
    private int clockViewportHeight;
    private boolean remoteInputMode;
    private String numericChannelInput = "";
    private boolean playbackGestureTracking;
    private boolean loadingActive;
    private final LinkedHashSet<String> favoriteChannelKeys =
            new LinkedHashSet<String>();
    private final Paint columnMeasurePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private boolean favoriteActionFocused;
    private boolean playbackGestureVertical;
    private boolean playbackGestureHorizontal;
    private boolean playbackGestureLeftSide;
    private float playbackGestureDownX;
    private float playbackGestureDownY;
    private float playbackGestureDeltaX;
    private float playbackGestureDeltaY;
    private int playbackGestureStartVolume;
    private int playbackGestureLastVolume = -1;
    private int playbackGestureTouchSlop;
    private int playbackGestureTopExclusion;
    private int playbackGestureBottomExclusion;
    private boolean playbackGestureEdgeBlocked;
    private boolean channelPanelTouching;
    private boolean channelPanelHovering;
    private boolean channelSwitchAnimating;
    private boolean gestureReboundAnimating;
    private float channelSwitchDirectionX;
    private float channelSwitchDirectionY;
    private int channelSwitchRequestId = -1;
    private Bitmap channelSwipeBitmap;
    private int channelSwipeCaptureGeneration;
    private AudioManager playbackAudioManager;
    private boolean mutedByAudioFocus;
    private boolean mutedByCallMode;
    private ServiceConnection crashRecoveryConnection;
    private boolean crashRecoveryBound;

    private static final class LastChannelSnapshot {
        final ChannelCatalog.Group group;
        final int sourceIndex;

        LastChannelSnapshot(ChannelCatalog.Group group, int sourceIndex) {
            this.group = group;
            this.sourceIndex = sourceIndex;
        }
    }

    private final Runnable finishGestureRebound = new Runnable() {
        @Override
        public void run() {
            gestureReboundAnimating = false;
            clearChannelSwitchVisuals();
            restorePlaybackLayer();
        }
    };

    private final AudioManager.OnAudioFocusChangeListener playbackAudioFocusListener =
            new AudioManager.OnAudioFocusChangeListener() {
        @Override
        public void onAudioFocusChange(int focusChange) {
            mutedByAudioFocus = focusChange != AudioManager.AUDIOFOCUS_GAIN;
            refreshCallAudioMute();
            applyPlaybackMuteState();
        }
    };

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
                abortChannelSwitchAnimation();
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
        configurePlaybackGestureExclusion();
        channelBar = findViewById(R.id.channel_bar);
        channelListPanel = findViewById(R.id.channel_list_panel);
        channelProgress = (ProgressBar) findViewById(R.id.channel_progress);
        videoClock = (TextView) findViewById(R.id.video_clock);
        videoDate = (TextView) findViewById(R.id.video_date);
        debugInfoOverlay = (TextView) findViewById(R.id.debug_info_overlay);
        networkSpeedOverlay = (TextView) findViewById(R.id.network_speed_overlay);
        channelName = (TextView) findViewById(R.id.channel_name);
        statusText = (TextView) findViewById(R.id.status_text);
        channelSourcePath = (TextView) findViewById(R.id.channel_source_path);
        channelEpg = (TextView) findViewById(R.id.channel_epg);
        videoInfo = (TextView) findViewById(R.id.video_info);
        numericChannelOverlay = (TextView) findViewById(R.id.numeric_channel_overlay);
        webSourceView = (WebSourceView) findViewById(R.id.web_source);
        flyMouseCursor = (FlyMouseCursorView) findViewById(R.id.fly_mouse_cursor);
        managementUrl = (TextView) findViewById(R.id.management_url);
        managementQr = (QrCodeView) findViewById(R.id.management_qr);
        managementPanel = findViewById(R.id.management_panel);
        backPrompt = findViewById(R.id.back_navigation_prompt);
        backPromptText = (TextView) findViewById(R.id.back_prompt_text);
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
        epgList = (ListView) findViewById(R.id.epg_list);
        epgColumn = findViewById(R.id.epg_column);
        epgStatus = (TextView) findViewById(R.id.epg_status);
        groupAdapter = new ChannelListAdapter(this);
        channelAdapter = new ChannelListAdapter(this);
        channelAdapter.setFavoriteListener(new ChannelListAdapter.FavoriteListener() {
            @Override
            public boolean isFavorite(int position) {
                return isBrowsingChannelFavorite(position);
            }

            @Override
            public void onFavoriteClick(int position) {
                channelList.setSelection(position);
                favoriteActionFocused = true;
                updateFavoriteButton();
                toggleBrowsingChannelFavorite(position);
            }
        });
        epgAdapter = new EpgListAdapter(this);
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
        webViewResolution = sanitizeWebViewResolution(preferences.getString(
                WEB_VIEW_RESOLUTION, WEB_VIEW_RESOLUTION_1080P));
        webViewLoadImages = preferences.getBoolean(WEB_VIEW_LOAD_IMAGES, true);
        webSourceView.applyConfiguration(webViewResolution, webViewLoadImages);
        String legacyClockLocation = preferences.getString(
                CLOCK_LOCATION, CLOCK_LOCATION_CHANNEL_LIST);
        clockLocation = sanitizeClockLocation(legacyClockLocation);
        showDebugInfo = preferences.getBoolean(SHOW_DEBUG_INFO, false);
        showNetworkSpeed = preferences.getBoolean(SHOW_NETWORK_SPEED, false);
        showDateTime = preferences.contains(SHOW_DATE_TIME)
                ? preferences.getBoolean(SHOW_DATE_TIME, false)
                : preferences.getBoolean(SHOW_DATE, false)
                        || CLOCK_LOCATION_VIDEO.equals(legacyClockLocation);
        dateTimeFormat = sanitizeDateTimeFormat(preferences.getString(
                DATE_TIME_FORMAT, DATE_TIME_DATE_FIRST));
        epgUrl = preferences.getString(EPG_URL, "").trim();
        flyMouseEnabled = preferences.getBoolean(FLY_MOUSE_ENABLED, false);
        liveDelayMode = sanitizeLiveDelayMode(
                preferences.getString(LIVE_DELAY_MODE, LIVE_DELAY_STABLE));
        remoteInputMode = hasTelevisionUi();
        playlistManager = new PlaylistManager(this);
        loadFavoriteChannels(preferences);
        final LastChannelSnapshot startupSnapshot = loadLastChannelSnapshot(preferences);
        if (startupSnapshot == null) {
            ChannelCatalog.setCustomGroups(playlistManager.loadCached());
        } else {
            // Restore the selected channel from a tiny snapshot first. Parsing and
            // merging every configured source is deferred until playback is already
            // being prepared, which keeps cold-start feedback immediate.
            ChannelCatalog.setCustomGroups(
                    new ChannelCatalog.Group[] { startupSnapshot.group });
        }
        refreshFavoriteCatalog();
        requestLocalPlaylistPermissionIfNeeded();
        epgManager = new EpgManager(this);
        liveUrlResolver = new LiveUrlResolver(getSharedPreferences("live_url_resolver", MODE_PRIVATE));
        yangshipinResolver = new YangshipinWebResolver(this, (FrameLayout) root,
                getIntent().getBooleanExtra("cmg_keep_web_trace", false));
        groupList.setAdapter(groupAdapter);
        channelList.setAdapter(channelAdapter);
        epgList.setAdapter(epgAdapter);
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
        channelList.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                showEpgForBrowsingChannel(position);
                updateFavoriteButton();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
        configureChannelPanelInteraction();

        videoView = (DirectVideoView) findViewById(R.id.video_surface);
        channelSwitchBlackout = findViewById(R.id.channel_switch_blackout);
        channelSwipeSnapshot = (ImageView) findViewById(R.id.channel_swipe_snapshot);
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
        migrateFavoriteGroupIndex(preferences);
        migrateMergedCentralGroups(preferences);
        boolean hasLastChannel = preferences.contains(LAST_GROUP_INDEX)
                && preferences.contains(LAST_CHANNEL_INDEX);
        if (startupSnapshot != null) {
            currentGroupIndex = findGroupByTitle(
                    ChannelCatalog.GROUPS, startupSnapshot.group.title);
            if (currentGroupIndex < 0 || currentGroup().channels.length == 0) {
                currentGroupIndex = ChannelCatalog.firstPlayableGroupIndex();
            }
            currentChannelIndex = 0;
            int sourceCount = Math.max(1, currentChannel().sourceCount());
            currentSourceIndex = (startupSnapshot.sourceIndex % sourceCount
                    + sourceCount) % sourceCount;
        } else if (hasLastChannel) {
            currentGroupIndex = ChannelCatalog.wrapGroupIndex(
                    preferences.getInt(LAST_GROUP_INDEX, FIRST_LAUNCH_GROUP_INDEX));
            if (currentGroup().channels.length == 0) {
                currentGroupIndex = ChannelCatalog.firstPlayableGroupIndex();
            }
            currentChannelIndex = ChannelCatalog.wrapIndex(currentGroup().channels,
                    preferences.getInt(LAST_CHANNEL_INDEX, FIRST_LAUNCH_CHANNEL_INDEX));
        } else {
            currentGroupIndex = FIRST_LAUNCH_GROUP_INDEX;
            currentChannelIndex = FIRST_LAUNCH_CHANNEL_INDEX;
        }
        browsingGroupIndex = currentGroupIndex;
        showChannelMenu(currentGroupIndex);

        try {
            if (startupSnapshot == null) {
                switchChannel(currentChannelIndex);
            } else {
                triedCustomSources = 1;
                startChannel(currentChannelIndex);
            }
        } catch (Exception error) {
            Log.e(TAG, "Unable to start player", error);
            showChannelBar(currentChannel().name,
                    "启动失败: " + error.getMessage());
        }
        startManagementServer();
        refreshEpg();
        if (startupSnapshot != null) {
            loadCompleteCatalogInBackground();
        }
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
                revealIncomingChannel(requestId);
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
                abortChannelSwitchAnimation();
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
            abortChannelSwitchAnimation();
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
        if (webSourceView != null) {
            webSourceView.closePage();
        }
        clearWebCloseConfirmation();
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
        if (webClosePrompt) {
            clearWebCloseConfirmation();
            root.requestFocus();
            return;
        }
        backPrompt.removeCallbacks(hideBackPrompt);
        backPrompt.setVisibility(View.GONE);
        lastBackPressedAt = 0L;
        openManagement();
    }

    private void showBackPrompt(boolean forWebClose) {
        webClosePrompt = forWebClose;
        backPromptText.setText(forWebClose
                ? R.string.press_back_again_to_close_web
                : R.string.back_navigation_prompt);
        backPromptOk.setText(forWebClose ? R.string.cancel : R.string.confirm);
        backPrompt.removeCallbacks(hideBackPrompt);
        backPrompt.setVisibility(View.VISIBLE);
        backPrompt.bringToFront();
        ensureFlyMouseOnTop();
        backPromptOk.requestFocus();
        backPrompt.postDelayed(hideBackPrompt, BACK_PROMPT_TIMEOUT_MS);
    }

    private void clearWebCloseConfirmation() {
        lastWebBackPressedAt = 0L;
        if (!webClosePrompt || backPrompt == null) {
            return;
        }
        backPrompt.removeCallbacks(hideBackPrompt);
        backPrompt.setVisibility(View.GONE);
        webClosePrompt = false;
        resetBackPromptContent();
    }

    private void resetBackPromptContent() {
        if (backPromptText != null) {
            backPromptText.setText(R.string.back_navigation_prompt);
        }
        if (backPromptOk != null) {
            backPromptOk.setText(R.string.confirm);
        }
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

                @Override
                public String uploadPlaylist(String sourceId, String fileName, byte[] body)
                        throws Exception {
                    PlaylistManager.ImportedFile imported = playlistManager.importLocalPlaylist(
                            sourceId, fileName, body);
                    return new JSONObject().put("ok", true)
                            .put("name", imported.displayName)
                            .put("location", imported.location).toString();
                }

                @Override
                public LocalControlServer.Resource recording(String token) throws Exception {
                    return handleRecordingResource(token);
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
            current.put("sourceIndex", catalogSource(group, channel)
                    == ChannelCatalog.SOURCE_CUSTOM
                    ? currentSourceIndex : 0);
            current.put("sourceCount", Math.max(1, channel.sourceCount()));
            root.put("current", current);
            boolean recordingAvailable = proxy != null && activePlayerStreamUrl != null
                    && activePlayerStreamUrl.length() > 0;
            root.put("recording", new JSONObject()
                    .put("available", recordingAvailable)
                    .put("name", channel.name)
                    .put("group", group.title)
                    .put("width", Math.max(0, videoWidth))
                    .put("height", Math.max(0, videoHeight))
                    .put("playlistPath", "/api/recording/playlist"));
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
                    .put("webViewResolution", webViewResolution)
                    .put("webViewLoadImages", webViewLoadImages)
                    .put("webViewCacheBytes", webSourceView == null
                            ? 0L : webSourceView.browserCacheSizeBytes())
                    .put("clockLocation", clockLocation)
                    .put("showDebugInfo", showDebugInfo)
                    .put("showNetworkSpeed", showNetworkSpeed)
                    .put("showDate", showDateTime)
                    .put("showDateTime", showDateTime)
                    .put("dateTimeFormat", dateTimeFormat)
                    .put("flyMouseEnabled", flyMouseEnabled)
                    .put("liveDelayMode", liveDelayMode)
                    .put("epgUrl", epgUrl)
                    .put("effectiveEpgUrl", effectiveEpgUrl())
                    .put("recommendedEpgUrl", EpgManager.DEFAULT_URL)
                    .put("playlistUrl", playlistManager.getPlaylistUrl())
                    .put("playlistSources", playlistManager.getSourcesJson())
                    .put("playlistGroups", playlistManager.getGroupSettingsJson())
                    .put("recommendedPlaylistUrl", PlaylistManager.getRecommendedUrl()));
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
                && !"reset".equals(action) && !"key".equals(action)
                && !"text".equals(action) && !"menu".equals(action)) {
            throw new JSONException("未知的飞鼠指令");
        }
        final float dx = clampPointerDelta((float) request.optDouble("dx", 0d));
        final float dy = clampPointerDelta((float) request.optDouble("dy", 0d));
        final int scrollY = (int) clampPointerDelta((float) request.optDouble("scrollY", 0d));
        final String keyName = request.optString("key", "");
        final int keyCode = remoteKeyCode(keyName);
        final int metaState = (request.optBoolean("shift", false) ? KeyEvent.META_SHIFT_ON : 0)
                | (request.optBoolean("ctrl", false) ? KeyEvent.META_CTRL_ON : 0)
                | (request.optBoolean("alt", false) ? KeyEvent.META_ALT_ON : 0);
        final String text = request.optString("text", "");
        if ("key".equals(action) && keyCode == KeyEvent.KEYCODE_UNKNOWN) {
            throw new JSONException("不支持的按键");
        }
        if ("text".equals(action) && (text.length() == 0 || text.length() > 1000)) {
            throw new JSONException("输入文字不能为空且不能超过 1000 个字符");
        }
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
                    handleRemoteScroll(scrollY);
                } else if ("back".equals(action)) {
                    onBackPressed();
                } else if ("menu".equals(action)) {
                    openManagement();
                } else if ("key".equals(action)) {
                    if (webSourceView != null && webSourceView.isPageVisible()
                            && keyCode != KeyEvent.KEYCODE_VOLUME_UP
                            && keyCode != KeyEvent.KEYCODE_VOLUME_DOWN
                            && keyCode != KeyEvent.KEYCODE_VOLUME_MUTE) {
                        webSourceView.dispatchRemoteKey(keyCode, metaState);
                    } else {
                        long now = SystemClock.uptimeMillis();
                        dispatchKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_DOWN,
                                keyCode, 0, metaState));
                        dispatchKeyEvent(new KeyEvent(now, now + 24L, KeyEvent.ACTION_UP,
                                keyCode, 0, metaState));
                    }
                } else if ("text".equals(action)) {
                    if (webSourceView != null) {
                        webSourceView.inputTextRemote(text);
                    }
                } else {
                    flyMouseCursor.resetPosition();
                    ensureFlyMouseOnTop();
                }
            }
        });
        return new JSONObject().put("ok", true).toString();
    }

    private static int remoteKeyCode(String name) {
        if (name == null) {
            return KeyEvent.KEYCODE_UNKNOWN;
        }
        String key = name.trim().toLowerCase(Locale.US);
        if (key.length() == 1) {
            char value = key.charAt(0);
            if (value >= 'a' && value <= 'z') {
                return KeyEvent.KEYCODE_A + value - 'a';
            }
            if (value >= '0' && value <= '9') {
                return KeyEvent.KEYCODE_0 + value - '0';
            }
        }
        if ("up".equals(key)) return KeyEvent.KEYCODE_DPAD_UP;
        if ("down".equals(key)) return KeyEvent.KEYCODE_DPAD_DOWN;
        if ("left".equals(key)) return KeyEvent.KEYCODE_DPAD_LEFT;
        if ("right".equals(key)) return KeyEvent.KEYCODE_DPAD_RIGHT;
        if ("enter".equals(key) || "ok".equals(key)) return KeyEvent.KEYCODE_ENTER;
        if ("tab".equals(key)) return KeyEvent.KEYCODE_TAB;
        if ("space".equals(key)) return KeyEvent.KEYCODE_SPACE;
        if ("backspace".equals(key)) return KeyEvent.KEYCODE_DEL;
        if ("delete".equals(key)) return KeyEvent.KEYCODE_FORWARD_DEL;
        if ("escape".equals(key) || "esc".equals(key)) return KeyEvent.KEYCODE_ESCAPE;
        if ("home".equals(key)) return KeyEvent.KEYCODE_MOVE_HOME;
        if ("end".equals(key)) return KeyEvent.KEYCODE_MOVE_END;
        if ("pageup".equals(key)) return KeyEvent.KEYCODE_PAGE_UP;
        if ("pagedown".equals(key)) return KeyEvent.KEYCODE_PAGE_DOWN;
        if ("menu".equals(key)) return KeyEvent.KEYCODE_MENU;
        if ("playpause".equals(key)) return KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE;
        if ("volumeup".equals(key)) return KeyEvent.KEYCODE_VOLUME_UP;
        if ("volumedown".equals(key)) return KeyEvent.KEYCODE_VOLUME_DOWN;
        if ("mute".equals(key)) return KeyEvent.KEYCODE_VOLUME_MUTE;
        if ("comma".equals(key)) return KeyEvent.KEYCODE_COMMA;
        if ("period".equals(key)) return KeyEvent.KEYCODE_PERIOD;
        if ("slash".equals(key)) return KeyEvent.KEYCODE_SLASH;
        if ("minus".equals(key)) return KeyEvent.KEYCODE_MINUS;
        if ("equals".equals(key)) return KeyEvent.KEYCODE_EQUALS;
        if ("semicolon".equals(key)) return KeyEvent.KEYCODE_SEMICOLON;
        if ("apostrophe".equals(key)) return KeyEvent.KEYCODE_APOSTROPHE;
        if ("leftbracket".equals(key)) return KeyEvent.KEYCODE_LEFT_BRACKET;
        if ("rightbracket".equals(key)) return KeyEvent.KEYCODE_RIGHT_BRACKET;
        if ("backslash".equals(key)) return KeyEvent.KEYCODE_BACKSLASH;
        if ("grave".equals(key)) return KeyEvent.KEYCODE_GRAVE;
        return KeyEvent.KEYCODE_UNKNOWN;
    }

    private LocalControlServer.Resource handleRecordingResource(String token)
            throws IOException {
        HlsProxyServer activeProxy = proxy;
        String activeUrl = activePlayerStreamUrl;
        if (activeProxy == null || activeUrl == null || activeUrl.length() == 0) {
            throw new IOException("当前频道还没有可录制的视频流");
        }
        HlsProxyServer.ProxyResponse response = activeProxy.fetchForRecording(
                activeUrl, token, "/api/recording/resource/");
        return new LocalControlServer.Resource(response.contentType, response.body);
    }

    private void handleRemoteScroll(int scrollY) {
        if (webSourceView != null && webSourceView.isPageVisible()) {
            webSourceView.scrollByRemote(scrollY);
            return;
        }
        if (channelListPanel == null || channelListPanel.getVisibility() != View.VISIBLE) {
            return;
        }
        ListView target = epgList.hasFocus() ? epgList
                : groupList.hasFocus() ? groupList : channelList;
        channelListPanel.removeCallbacks(hideChannelList);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            target.scrollListBy(scrollY);
        } else {
            int step = scrollY == 0 ? 0 : scrollY > 0 ? 1 : -1;
            int next = Math.max(0, Math.min(target.getCount() - 1,
                    target.getFirstVisiblePosition() + step));
            target.setSelection(next);
        }
        scheduleChannelListDismiss();
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
        boolean applyWebViewSettings = false;
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
                    "clockLocation", CLOCK_LOCATION_RIGHT);
            final String requestedLocation = sanitizeClockLocation(rawLocation);
            if (!requestedLocation.equals(rawLocation)
                    && !CLOCK_LOCATION_VIDEO.equals(rawLocation)
                    && !CLOCK_LOCATION_CHANNEL_LIST.equals(rawLocation)) {
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
        if (request.has("showDate") || request.has("showDateTime")) {
            showDateTime = request.has("showDateTime")
                    ? request.optBoolean("showDateTime", false)
                    : request.optBoolean("showDate", false);
            getSharedPreferences(PREFERENCES, MODE_PRIVATE).edit()
                    .putBoolean(SHOW_DATE_TIME, showDateTime).apply();
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    applyClockLocation();
                }
            });
        }
        if (request.has("webViewResolution")) {
            String rawMode = request.optString(
                    "webViewResolution", WEB_VIEW_RESOLUTION_1080P);
            String requestedMode = sanitizeWebViewResolution(rawMode);
            if (!requestedMode.equals(rawMode)) {
                throw new JSONException("不支持的网页分辨率");
            }
            webViewResolution = requestedMode;
            getSharedPreferences(PREFERENCES, MODE_PRIVATE).edit()
                    .putString(WEB_VIEW_RESOLUTION, webViewResolution).apply();
            applyWebViewSettings = true;
        }
        if (request.has("webViewLoadImages")) {
            webViewLoadImages = request.optBoolean("webViewLoadImages", true);
            getSharedPreferences(PREFERENCES, MODE_PRIVATE).edit()
                    .putBoolean(WEB_VIEW_LOAD_IMAGES, webViewLoadImages).apply();
            applyWebViewSettings = true;
        }
        if (applyWebViewSettings) {
            final String requestedWebViewResolution = webViewResolution;
            final boolean requestedWebViewLoadImages = webViewLoadImages;
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    if (webSourceView != null) {
                        webSourceView.applyConfiguration(requestedWebViewResolution,
                                requestedWebViewLoadImages);
                    }
                }
            });
        }
        if (request.has("dateTimeFormat")) {
            String rawFormat = request.optString(DATE_TIME_FORMAT, DATE_TIME_DATE_FIRST);
            String requestedFormat = sanitizeDateTimeFormat(rawFormat);
            if (!requestedFormat.equals(rawFormat)) {
                throw new JSONException("不支持的日期时间排序");
            }
            dateTimeFormat = requestedFormat;
            getSharedPreferences(PREFERENCES, MODE_PRIVATE).edit()
                    .putString(DATE_TIME_FORMAT, dateTimeFormat).apply();
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    applyClockLocation();
                }
            });
        }
        if (request.has("epgUrl")) {
            String requestedEpgUrl = request.optString("epgUrl", "").trim();
            if (requestedEpgUrl.length() > 0
                    && !requestedEpgUrl.startsWith("http://")
                    && !requestedEpgUrl.startsWith("https://")) {
                throw new JSONException("节目单地址仅支持 HTTP 或 HTTPS");
            }
            epgUrl = requestedEpgUrl;
            SharedPreferences.Editor editor = getSharedPreferences(
                    PREFERENCES, MODE_PRIVATE).edit();
            if (epgUrl.length() == 0) {
                editor.remove(EPG_URL);
            } else {
                editor.putString(EPG_URL, epgUrl);
            }
            editor.apply();
            refreshEpg();
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
        final boolean clearWebCache = request.optBoolean("clearWebCache", false);
        if (clearWebCache) {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    if (webSourceView != null) {
                        webSourceView.clearBrowserCache();
                    }
                    Log.i(TAG, "WebView cache and temporary site data cleared");
                }
            });
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
        String message = clearWebCache ? "网页缓存已清除" : "设置已保存";
        if (request.has("playlistGroupStates")) {
            final ChannelCatalog.Group[] customGroups = playlistManager.updateGroupStates(
                    request.optJSONArray("playlistGroupStates"));
            applyPlaylistGroupVisibility(customGroups);
            message = "频道分组设置已保存";
        }
        if (request.has("playlistSources")) {
            JSONArray sources = request.optJSONArray("playlistSources");
            ensureLocalPlaylistPermission(sources);
            PlaylistManager.UpdateResult result = playlistManager.updateSources(sources);
            final ChannelCatalog.Group[] customGroups = result.groups;
            applyPlaylistGroups(customGroups);
            int channelCount = 0;
            for (ChannelCatalog.Group group : customGroups) {
                channelCount += group.channels.length;
            }
            message = result.enabledCount == 0 ? "已停用全部在线频道"
                    : "已合并 " + result.enabledCount + " 个源、"
                            + customGroups.length + " 个分组、" + channelCount + " 个频道";
            if (!result.warnings.isEmpty()) {
                message += "；" + result.warnings.get(0);
            }
        } else if (request.has("playlistUrl")) {
            final ChannelCatalog.Group[] customGroups = playlistManager.downloadAndSave(
                    request.optString("playlistUrl", ""));
            applyPlaylistGroups(customGroups);
            message = customGroups.length == 0 ? "已移除在线频道" : "频道源已更新";
        }
        return new JSONObject().put("ok", true).put("message", message).toString();
    }

    @SuppressLint("NewApi")
    private void ensureLocalPlaylistPermission(JSONArray sources) throws IOException {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M
                || checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE)
                        == PackageManager.PERMISSION_GRANTED) {
            return;
        }
        boolean needsPermission = false;
        if (sources != null) {
            for (int index = 0; index < sources.length(); index++) {
                JSONObject source = sources.optJSONObject(index);
                if (source != null && source.optBoolean("enabled", true)
                        && playlistManager.requiresExternalPermission(
                                source.optString("location", ""))) {
                    needsPermission = true;
                    break;
                }
            }
        }
        if (!needsPermission) {
            return;
        }
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                requestPermissions(new String[] { Manifest.permission.READ_EXTERNAL_STORAGE },
                        LOCAL_PLAYLIST_PERMISSION_REQUEST);
            }
        });
        throw new IOException("已请求本地文件读取权限，请允许后再次保存频道源");
    }

    private void requestLocalPlaylistPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                && playlistManager.hasEnabledExternalLocalSource()
                && checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE)
                        != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[] { Manifest.permission.READ_EXTERNAL_STORAGE },
                    LOCAL_PLAYLIST_PERMISSION_REQUEST);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions,
            int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != LOCAL_PLAYLIST_PERMISSION_REQUEST || grantResults.length == 0
                || grantResults[0] != PackageManager.PERMISSION_GRANTED) {
            return;
        }
        ChannelCatalog.setCustomGroups(playlistManager.loadCached());
        refreshFavoriteCatalog();
        if (channelListPanel.getVisibility() == View.VISIBLE) {
            showChannelMenu(currentGroupIndex);
        }
        epgAdapter.notifyDataSetChanged();
        refreshEpg();
    }

    private void applyPlaylistGroups(final ChannelCatalog.Group[] customGroups)
            throws InterruptedException {
        applyPlaylistGroups(customGroups, true);
    }

    private void applyPlaylistGroupVisibility(final ChannelCatalog.Group[] customGroups)
            throws InterruptedException {
        applyPlaylistGroups(customGroups, false);
    }

    private void applyPlaylistGroups(final ChannelCatalog.Group[] customGroups,
            final boolean restartActiveCustom) throws InterruptedException {
        final CountDownLatch applied = new CountDownLatch(1);
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                ChannelCatalog.Group[] before = ChannelCatalog.GROUPS;
                String activeGroupTitle = null;
                String activeChannelKey = null;
                boolean wasCustom = false;
                if (currentGroupIndex >= 0 && currentGroupIndex < before.length) {
                    ChannelCatalog.Group activeGroup = before[currentGroupIndex];
                    if (activeGroup.channels.length > 0) {
                        int activeIndex = ChannelCatalog.wrapIndex(
                                activeGroup.channels, currentChannelIndex);
                        activeGroupTitle = activeGroup.title;
                        activeChannelKey = favoriteKey(
                                activeGroup, activeGroup.channels[activeIndex]);
                        wasCustom = catalogSource(activeGroup,
                                activeGroup.channels[activeIndex])
                                == ChannelCatalog.SOURCE_CUSTOM;
                    }
                }
                ChannelCatalog.setCustomGroups(customGroups);
                int restoredGroup = findGroupByTitle(
                        ChannelCatalog.GROUPS, activeGroupTitle);
                if (restoredGroup >= 0) {
                    currentGroupIndex = restoredGroup;
                    ChannelCatalog.Group group = ChannelCatalog.GROUPS[restoredGroup];
                    int restoredChannel = findChannelByKey(group, activeChannelKey);
                    currentChannelIndex = restoredChannel >= 0 ? restoredChannel
                            : ChannelCatalog.wrapIndex(group.channels, currentChannelIndex);
                } else {
                    currentGroupIndex = ChannelCatalog.firstPlayableGroupIndex();
                    currentChannelIndex = ChannelCatalog.defaultChannelIndex(currentGroup());
                }
                refreshFavoriteCatalog();
                boolean hasPlayableChannel = currentGroupIndex < ChannelCatalog.GROUPS.length
                        && currentGroup().channels.length > 0;
                if (!hasPlayableChannel) {
                    currentGroupIndex = ChannelCatalog.firstPlayableGroupIndex();
                    hasPlayableChannel = currentGroup().channels.length > 0;
                    if (hasPlayableChannel) {
                        currentChannelIndex = ChannelCatalog.defaultChannelIndex(currentGroup());
                    }
                }
                if (hasPlayableChannel) {
                    int sourceCount = Math.max(1, currentChannel().sourceCount());
                    currentSourceIndex = (currentSourceIndex % sourceCount
                            + sourceCount) % sourceCount;
                    saveLastChannelSnapshot(currentGroup(), currentChannel());
                }
                boolean selectionChanged = !hasPlayableChannel
                        || activeChannelKey == null
                        || !activeChannelKey.equals(favoriteKey(currentGroup(),
                                currentGroup().channels[ChannelCatalog.wrapIndex(
                                        currentGroup().channels, currentChannelIndex)]));
                browsingGroupIndex = currentGroupIndex;
                if (hasPlayableChannel && (selectionChanged
                        || (restartActiveCustom && wasCustom))) {
                    switchChannel(currentChannelIndex);
                }
                if (channelListPanel.getVisibility() == View.VISIBLE) {
                    showChannelMenu(currentGroupIndex);
                }
                refreshEpg();
                applied.countDown();
            }
        });
        applied.await(5L, TimeUnit.SECONDS);
    }

    private void loadCompleteCatalogInBackground() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    ChannelCatalog.Group[] groups = playlistManager.loadCached();
                    if (!isFinishing()) {
                        applyPlaylistGroups(groups, false);
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                } catch (RuntimeException error) {
                    Log.w(TAG, "Unable to load complete channel catalog", error);
                }
            }
        }, "channel-catalog-startup").start();
    }

    private static int findGroupByTitle(ChannelCatalog.Group[] groups, String title) {
        if (title == null) {
            return -1;
        }
        for (int index = 0; index < groups.length; index++) {
            if (title.equals(groups[index].title)) {
                return index;
            }
        }
        return -1;
    }

    private static int findChannelByKey(ChannelCatalog.Group group, String key) {
        if (key == null) {
            return -1;
        }
        for (int index = 0; index < group.channels.length; index++) {
            if (key.equals(favoriteKey(group, group.channels[index]))) {
                return index;
            }
        }
        return -1;
    }

    private LastChannelSnapshot loadLastChannelSnapshot(SharedPreferences preferences) {
        String saved = preferences.getString(LAST_CHANNEL_SNAPSHOT, "");
        if (saved.length() == 0) {
            return null;
        }
        try {
            JSONObject value = new JSONObject(saved);
            String groupTitle = value.optString("groupTitle", "").trim();
            String name = value.optString("name", "").trim();
            if (groupTitle.length() == 0 || name.length() == 0) {
                return null;
            }
            JSONArray savedUrls = value.optJSONArray("urls");
            java.util.ArrayList<String> urls = new java.util.ArrayList<String>();
            if (savedUrls != null) {
                for (int index = 0; index < savedUrls.length(); index++) {
                    String url = savedUrls.optString(index, "").trim();
                    if (url.length() > 0) {
                        urls.add(url);
                    }
                }
            }
            Channel channel = new Channel(
                    value.optString("number", ""), name,
                    emptyToNull(value.optString("streamId", "")),
                    urls.toArray(new String[urls.size()]),
                    emptyToNull(value.optString("yangshipinPid", "")),
                    emptyToNull(value.optString("yangshipinStreamId", "")),
                    emptyToNull(value.optString("yangshipinMaxDefinition", "")),
                    emptyToNull(value.optString("epgId", "")));
            int source = value.optInt("catalogSource", ChannelCatalog.SOURCE_CUSTOM);
            channel = channel.withCatalogSource(source);
            if (channel.sourceCount() == 0
                    && channel.yangshipinPid == null && channel.streamId == null) {
                return null;
            }
            return new LastChannelSnapshot(new ChannelCatalog.Group(
                    groupTitle, source, new Channel[] { channel }),
                    Math.max(0, value.optInt("sourceIndex", 0)));
        } catch (Exception error) {
            Log.w(TAG, "Unable to read last channel snapshot", error);
            preferences.edit().remove(LAST_CHANNEL_SNAPSHOT).apply();
            return null;
        }
    }

    private void saveLastChannelSnapshot(ChannelCatalog.Group group, Channel channel) {
        if (group == null || channel == null) {
            return;
        }
        String groupTitle = group.title;
        int source = catalogSource(group, channel);
        if (group.source == ChannelCatalog.SOURCE_FAVORITES
                && channel.favoriteKey != null) {
            int separator = channel.favoriteKey.indexOf('\u001f');
            if (separator > 0) {
                groupTitle = channel.favoriteKey.substring(0, separator);
            }
        }
        try {
            JSONArray urls = new JSONArray();
            for (String url : channel.urls) {
                if (url != null && url.length() > 0) {
                    urls.put(url);
                }
            }
            JSONObject value = new JSONObject()
                    .put("groupTitle", groupTitle)
                    .put("catalogSource", source)
                    .put("number", channel.number)
                    .put("name", channel.name)
                    .put("streamId", channel.streamId == null ? "" : channel.streamId)
                    .put("urls", urls)
                    .put("yangshipinPid", channel.yangshipinPid == null
                            ? "" : channel.yangshipinPid)
                    .put("yangshipinStreamId", channel.yangshipinStreamId == null
                            ? "" : channel.yangshipinStreamId)
                    .put("yangshipinMaxDefinition", channel.yangshipinMaxDefinition == null
                            ? "" : channel.yangshipinMaxDefinition)
                    .put("epgId", channel.epgId == null ? "" : channel.epgId)
                    .put("sourceIndex", currentSourceIndex);
            getSharedPreferences(PREFERENCES, MODE_PRIVATE).edit()
                    .putString(LAST_CHANNEL_SNAPSHOT, value.toString())
                    .putInt(LAST_GROUP_INDEX, currentGroupIndex)
                    .putInt(LAST_CHANNEL_INDEX, currentChannelIndex)
                    .apply();
        } catch (JSONException error) {
            Log.w(TAG, "Unable to save last channel snapshot", error);
        }
    }

    private static String emptyToNull(String value) {
        String normalized = value == null ? "" : value.trim();
        return normalized.length() == 0 ? null : normalized;
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

    private static int catalogSource(ChannelCatalog.Group group, Channel channel) {
        if (channel.catalogSource >= 0) {
            return channel.catalogSource;
        }
        return group.source;
    }

    private int currentCatalogSource() {
        ChannelCatalog.Group group = currentGroup();
        if (group.channels.length == 0) {
            return group.source;
        }
        return catalogSource(group, group.channels[ChannelCatalog.wrapIndex(
                group.channels, currentChannelIndex)]);
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
        final int source = catalogSource(group, channel);
        saveLastChannelSnapshot(group, channel);
        configureEmbeddedResolverMode(group, channel);
        if (channelListPanel.getVisibility() == View.VISIBLE) {
            groupAdapter.setSelectedIndex(currentGroupIndex);
            channelAdapter.setChannelState(currentChannelIndex,
                    browsingGroupIndex == currentGroupIndex ? currentChannelIndex : -1,
                    currentSourceIndex);
            groupList.setSelection(currentGroupIndex);
            channelList.setSelection(currentChannelIndex);
        }
        final int requestId = ++playRequestId;
        if (channelSwitchAnimating
                && (channelSwitchDirectionY != 0f || channelSwitchDirectionX != 0f)) {
            channelSwitchRequestId = requestId;
            positionIncomingChannelOffscreen();
        }
        playerStartRetryCount = 0;
        legacyHardwareRetryRequestId = -1;
        clearPendingPlayer();
        releasePlayer();
        resetVideoLayout();
        showLoading(channel.name, source == ChannelCatalog.SOURCE_CUSTOM
                ? customSourceStatus("正在连接") : "正在准备直播");
        try {
            resetProxyForChannelSwitch();
        } catch (IOException error) {
            Log.e(TAG, "Unable to reset proxy for channel switch", error);
            abortChannelSwitchAnimation();
            hideLoading();
            showChannelBar(channel.name, "切换失败: " + error.getMessage());
            return;
        }
        if (source == ChannelCatalog.SOURCE_CCTV_WEB
                || source == ChannelCatalog.SOURCE_CUSTOM) {
            resolveFallbackUrl(channel, requestId);
            return;
        }
        resolveYangshipinUrl(channel, requestId);
    }

    private void configureEmbeddedResolverMode(ChannelCatalog.Group group, Channel channel) {
        activeEmbeddedCctvResolver = false;
        activeEmbeddedYangshipinResolver = false;
        if (catalogSource(group, channel) != ChannelCatalog.SOURCE_CUSTOM) {
            return;
        }
        String configuredUrl = channel.sourceUrl(currentSourceIndex);
        if (!isWebViewSource(configuredUrl)) {
            activeEmbeddedCctvResolver = isCctvDirectStream(configuredUrl);
            return;
        }
        activeEmbeddedYangshipinResolver = extractYangshipinPid(configuredUrl) != null;
        activeEmbeddedCctvResolver = !activeEmbeddedYangshipinResolver
                && extractCctvWebChannel(configuredUrl) != null;
    }

    private static boolean isCctvDirectStream(String url) {
        if (url == null) {
            return false;
        }
        String normalized = url.toLowerCase(Locale.US);
        return normalized.contains("cctvwbcd") && normalized.contains("/cdrmld")
                && normalized.contains(".m3u8");
    }

    private boolean isActiveCctvWebSource() {
        return currentCatalogSource() == ChannelCatalog.SOURCE_CCTV_WEB
                || activeEmbeddedCctvResolver;
    }

    private String customSourceStatus(String prefix) {
        Channel channel = currentChannel();
        int count = Math.max(1, channel.sourceCount());
        return prefix + "线路 " + (currentSourceIndex + 1) + "/" + count;
    }

    private boolean switchCustomSource(int offset, boolean automatic, String reason) {
        cancelPendingRelativeSwitch();
        if (currentCatalogSource() != ChannelCatalog.SOURCE_CUSTOM) {
            return false;
        }
        Channel channel = currentChannel();
        int count = channel.sourceCount();
        if (count <= 1) {
            if (automatic) {
                abortChannelSwitchAnimation();
                hideLoading();
                showChannelBar(channel.name, reason + "，当前频道没有备用线路");
            } else {
                showChannelBar(channel.name, "当前频道只有一条线路");
            }
            return true;
        }
        if (automatic && triedCustomSources >= count) {
            abortChannelSwitchAnimation();
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
        boolean statefulCmgSource = !isActiveCctvWebSource()
                && (currentCatalogSource() != ChannelCatalog.SOURCE_CUSTOM
                        || activeEmbeddedYangshipinResolver);
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
                currentCatalogSource() != ChannelCatalog.SOURCE_CUSTOM
                        || activeEmbeddedCctvResolver || activeEmbeddedYangshipinResolver,
                resolutionMode,
                cctvStartupDownloadSegments(), cctvStartupDecryptSegments());
        next.start();
        proxy = next;
        proxyStatefulCmgSource = statefulCmgSource;
    }

    private void resolveYangshipinUrl(final Channel channel, final int requestId) {
        resolveYangshipinUrl(channel, channel, requestId);
    }

    private void resolveYangshipinUrl(final Channel resolverChannel,
            final Channel playbackChannel, final int requestId) {
        if (resolverChannel.yangshipinPid == null) {
            resolveFallbackUrl(playbackChannel, requestId);
            return;
        }
        updateLoadingStatus("正在获取央视频线路");
        showChannelBar(playbackChannel.name, "正在解析央视频源");
        yangshipinResolver.resolve(requestId, resolverChannel,
                yangshipinDefinition(resolverChannel),
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
                            "https://www.yangshipin.cn/tv/home?pid="
                                    + resolverChannel.yangshipinPid);
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
                startResolvedPlayer(playbackChannel, url);
            }

            @Override
            public void onFailed(int resolvedRequestId, String reason) {
                if (resolvedRequestId != playRequestId) {
                    return;
                }
                if (resolverChannel.url != null) {
                    Log.w(TAG, "Falling back to VDN for " + playbackChannel.name
                            + ": " + reason);
                    resolveFallbackUrl(playbackChannel, requestId);
                } else if (currentCatalogSource() == ChannelCatalog.SOURCE_CUSTOM
                        && resolverChannel != playbackChannel) {
                    Log.w(TAG, "Embedded YSP resolve failed for " + playbackChannel.name
                            + ": " + reason);
                    switchCustomSource(1, true, "央视频解析失败");
                } else {
                    Log.w(TAG, "YSP resolve failed for " + playbackChannel.name
                            + ": " + reason);
                    abortChannelSwitchAnimation();
                    hideLoading();
                    showChannelBar(playbackChannel.name, "央视频源解析失败: " + reason);
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
        final boolean directCustomSource = currentCatalogSource()
                == ChannelCatalog.SOURCE_CUSTOM;
        final String configuredUrl = directCustomSource
                ? channel.sourceUrl(currentSourceIndex) : channel.url;
        if (configuredUrl == null) {
            abortChannelSwitchAnimation();
            hideLoading();
            showChannelBar(channel.name, "没有可用的备用源");
            return;
        }
        if (isWebViewSource(configuredUrl)) {
            String yangshipinPid = extractYangshipinPid(configuredUrl);
            if (yangshipinPid != null) {
                Channel resolverChannel = ChannelCatalog.findYangshipinChannelByPid(
                        yangshipinPid);
                if (resolverChannel == null) {
                    resolverChannel = new Channel(channel.number, channel.name,
                            "embedded_ysp_" + yangshipinPid, null,
                            yangshipinPid, null, channel.yangshipinMaxDefinition);
                }
                updateLoadingStatus("正在解析网页直播地址");
                resolveYangshipinUrl(resolverChannel, channel, requestId);
                return;
            }
            Channel cctvChannel = extractCctvWebChannel(configuredUrl);
            if (cctvChannel != null) {
                resolveEmbeddedCctvUrl(channel, cctvChannel, requestId);
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

    private void resolveEmbeddedCctvUrl(final Channel playbackChannel,
            final Channel resolverChannel, final int requestId) {
        updateLoadingStatus("正在获取央视网直播线路");
        showChannelBar(playbackChannel.name, "正在解析央视网源");
        new Thread(new Runnable() {
            @Override
            public void run() {
                String streamUrl = resolverChannel.url;
                try {
                    streamUrl = liveUrlResolver.resolve(resolverChannel);
                } catch (IOException error) {
                    Log.w(TAG, "Using built-in CCTV fallback for "
                            + resolverChannel.streamId, error);
                }
                final String resolvedUrl = streamUrl;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (requestId != playRequestId) {
                            return;
                        }
                        if (resolvedUrl == null || resolvedUrl.length() == 0) {
                            switchCustomSource(1, true, "央视网解析失败");
                            return;
                        }
                        startResolvedPlayer(playbackChannel, resolvedUrl);
                    }
                });
            }
        }, "embedded-cctv-resolve").start();
    }

    private void startResolvedPlayer(Channel channel, String streamUrl) {
        updateLoadingStatus("正在连接视频");
        try {
            startPlayer(channel, streamUrl);
        } catch (IOException error) {
            Log.e(TAG, "Unable to play " + channel.name, error);
            if (currentCatalogSource() == ChannelCatalog.SOURCE_CUSTOM) {
                switchCustomSource(1, true, "线路连接失败");
                return;
            }
            abortChannelSwitchAnimation();
            hideLoading();
            showChannelBar(channel.name, "连接失败: " + error.getMessage());
        }
    }

    private void startPlayer(final Channel channel, final String streamUrl) throws IOException {
        startPlayer(channel, streamUrl, false);
    }

    private static String extractYangshipinPid(String configuredUrl) {
        Uri pageUri = parseWebViewPageUri(configuredUrl);
        if (pageUri == null || !hostMatches(pageUri.getHost(), "yangshipin.cn")) {
            return null;
        }
        try {
            String pid = pageUri.getQueryParameter("pid");
            return pid == null || pid.trim().length() == 0 ? null : pid.trim();
        } catch (UnsupportedOperationException error) {
            return null;
        }
    }

    private static Channel extractCctvWebChannel(String configuredUrl) {
        Uri pageUri = parseWebViewPageUri(configuredUrl);
        if (pageUri == null || !hostMatches(pageUri.getHost(), "cctv.com")) {
            return null;
        }
        java.util.List<String> segments = pageUri.getPathSegments();
        for (int index = 0; index + 1 < segments.size(); index++) {
            if ("live".equalsIgnoreCase(segments.get(index))) {
                return ChannelCatalog.findCctvChannelByWebSlug(segments.get(index + 1));
            }
        }
        return null;
    }

    private static Uri parseWebViewPageUri(String configuredUrl) {
        if (!isWebViewSource(configuredUrl)) {
            return null;
        }
        try {
            return Uri.parse(configuredUrl.substring("webview://".length()));
        } catch (RuntimeException error) {
            return null;
        }
    }

    private static boolean hostMatches(String host, String domain) {
        if (host == null) {
            return false;
        }
        String lower = host.toLowerCase(Locale.US);
        return lower.equals(domain) || lower.endsWith("." + domain);
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
        final boolean customSource = currentCatalogSource() == ChannelCatalog.SOURCE_CUSTOM;
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
        requestPlaybackAudioFocus();
        float playbackVolume = isPlaybackMuted() ? 0f : 1f;
        nextPlayer.setVolume(playbackVolume, playbackVolume);
        nextPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "framedrop",
                softwareDecode ? 5 : 1);
        nextPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "packet-buffering", 1);
        final boolean cctvSource = isActiveCctvWebSource();
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
                if (!isWaitingForIncomingFrame(sourceRequestId)) {
                    hideLoading();
                }
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
                    hideLoading();
                    revealIncomingChannel(sourceRequestId);
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
                    abortChannelSwitchAnimation();
                    hideLoading();
                    showChannelBar(channel.name, "播放错误: " + what + "/" + extra);
                }
                return true;
            }
        });
        // The local proxy prepares and rewrites HTTP/HLS playlists. RTMP is already
        // implemented by the bundled IJK/FFmpeg build and must be opened directly;
        // wrapping it in an HTTP proxy would make the proxy treat RTMP as a URLConnection.
        nextPlayer.setDataSource(isRtmpSource(streamUrl)
                ? streamUrl : proxy.proxyUrl(streamUrl));
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
        return CLOCK_LOCATION_LEFT.equals(location)
                ? CLOCK_LOCATION_LEFT : CLOCK_LOCATION_RIGHT;
    }

    private static boolean isRtmpSource(String sourceUrl) {
        if (sourceUrl == null) {
            return false;
        }
        String value = sourceUrl.trim().toLowerCase(Locale.US);
        return value.startsWith("rtmp://") || value.startsWith("rtmpt://")
                || value.startsWith("rtmps://");
    }

    private static String sanitizeWebViewResolution(String mode) {
        if (WEB_VIEW_RESOLUTION_720P.equals(mode)
                || WEB_VIEW_RESOLUTION_480P.equals(mode)) {
            return mode;
        }
        return WEB_VIEW_RESOLUTION_1080P;
    }

    private static String sanitizeDateTimeFormat(String format) {
        if (DATE_TIME_TIME_FIRST.equals(format) || DATE_TIME_WEEK_FIRST.equals(format)) {
            return format;
        }
        return DATE_TIME_DATE_FIRST;
    }

    private String formatDateTime(Date date) {
        String pattern;
        if (DATE_TIME_TIME_FIRST.equals(dateTimeFormat)) {
            pattern = "HH:mm:ss yyyy年MM月dd日 EEEE";
        } else if (DATE_TIME_WEEK_FIRST.equals(dateTimeFormat)) {
            pattern = "EEEE yyyy年MM月dd日 HH:mm:ss";
        } else {
            pattern = "yyyy年MM月dd日 HH:mm:ss EEEE";
        }
        return new SimpleDateFormat(pattern, Locale.CHINA).format(date);
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
        configureVideoClockForViewport(root.getWidth(), root.getHeight());
        videoClock.setVisibility(showDateTime ? View.VISIBLE : View.GONE);
        videoDate.setVisibility(View.GONE);
        applyDebugInfoVisibility();
        if (showDateTime) {
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
        float textSizePx = Math.max(18f, Math.min(52f, shortSide * 0.03f));
        float shadowRadiusPx = Math.max(2f, textSizePx * 0.09f);
        float shadowOffsetPx = Math.max(1f, textSizePx * 0.045f);
        videoClock.setTextSize(TypedValue.COMPLEX_UNIT_PX, textSizePx);
        videoClock.setShadowLayer(shadowRadiusPx, shadowOffsetPx, shadowOffsetPx,
                0xe6000000);

        android.graphics.Paint.FontMetrics metrics = videoClock.getPaint().getFontMetrics();
        int textWidth = (int) Math.ceil(videoClock.getPaint().measureText(
                "8888年88月88日 88:88:88 星期三"));
        int textHeight = (int) Math.ceil(metrics.descent - metrics.ascent);
        FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) videoClock.getLayoutParams();
        params.width = textWidth + (int) Math.ceil(shadowRadiusPx * 2f);
        params.height = textHeight + (int) Math.ceil(shadowRadiusPx * 2f);
        params.gravity = Gravity.TOP | (CLOCK_LOCATION_LEFT.equals(clockLocation)
                ? Gravity.LEFT : Gravity.RIGHT);
        params.topMargin = Math.max(4, Math.round(viewportHeight * 0.01f));
        params.leftMargin = CLOCK_LOCATION_LEFT.equals(clockLocation)
                ? Math.max(6, Math.round(viewportWidth * 0.01f)) : 0;
        params.rightMargin = CLOCK_LOCATION_RIGHT.equals(clockLocation)
                ? Math.max(6, Math.round(viewportWidth * 0.01f)) : 0;
        videoClock.setGravity(CLOCK_LOCATION_LEFT.equals(clockLocation)
                ? Gravity.LEFT | Gravity.CENTER_VERTICAL
                : Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        videoClock.setLayoutParams(params);
        configureVideoDateForViewport(viewportWidth, viewportHeight);
        configureDebugInfoForViewport(viewportWidth, viewportHeight,
                showDateTime && CLOCK_LOCATION_RIGHT.equals(clockLocation), params);
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
                || !isActiveCctvWebSource()
                || stallRecoveryRequestId == requestId) {
            return;
        }
        stallRecoveryRequestId = requestId;
        Log.w(TAG, "Recovering stalled CCTV playback at live edge: " + reason);
        if (currentCatalogSource() == ChannelCatalog.SOURCE_CUSTOM) {
            startChannel(currentChannelIndex);
        } else {
            switchChannel(currentChannelIndex);
        }
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
                || !sameChannelIdentity(channels[currentChannelIndex], channel)) {
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

    private static boolean sameChannelIdentity(Channel first, Channel second) {
        if (first == second) {
            return true;
        }
        if (first == null || second == null || !first.name.equals(second.name)) {
            return false;
        }
        if (first.yangshipinPid != null || second.yangshipinPid != null) {
            return first.yangshipinPid != null
                    && first.yangshipinPid.equals(second.yangshipinPid);
        }
        if (first.streamId != null || second.streamId != null) {
            return first.streamId != null && first.streamId.equals(second.streamId);
        }
        return first.url == null ? second.url == null
                : second.url != null && Channel.sameSourceUrl(first.url, second.url);
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

    private void loadFavoriteChannels(SharedPreferences preferences) {
        favoriteChannelKeys.clear();
        String saved = preferences.getString(FAVORITE_CHANNEL_KEYS, "[]");
        try {
            JSONArray values = new JSONArray(saved);
            for (int index = 0; index < values.length(); index++) {
                String key = values.optString(index, "");
                if (key.length() > 0) {
                    favoriteChannelKeys.add(key);
                }
            }
        } catch (JSONException error) {
            Log.w(TAG, "Unable to read favorite channels", error);
        }
    }

    private void saveFavoriteChannels() {
        JSONArray values = new JSONArray();
        for (String key : favoriteChannelKeys) {
            values.put(key);
        }
        getSharedPreferences(PREFERENCES, MODE_PRIVATE).edit()
                .putString(FAVORITE_CHANNEL_KEYS, values.toString()).apply();
    }

    private static String favoriteKey(ChannelCatalog.Group group, Channel channel) {
        if (group.source == ChannelCatalog.SOURCE_FAVORITES
                && channel.favoriteKey != null) {
            return channel.favoriteKey;
        }
        String identity = channel.yangshipinPid;
        if (identity == null || identity.length() == 0) {
            identity = channel.streamId;
        }
        if ((identity == null || identity.length() == 0) && channel.url != null) {
            identity = channel.url;
        }
        return group.title + "\u001f" + channel.name + "\u001f"
                + (identity == null ? "" : identity);
    }

    private void refreshFavoriteCatalog() {
        ChannelCatalog.Group[] before = ChannelCatalog.GROUPS;
        String activeFavoriteKey = null;
        if (currentGroupIndex >= 0 && currentGroupIndex < before.length) {
            ChannelCatalog.Group activeGroup = before[currentGroupIndex];
            if (activeGroup.source == ChannelCatalog.SOURCE_FAVORITES
                    && activeGroup.channels.length > 0) {
                int activeIndex = ChannelCatalog.wrapIndex(
                        activeGroup.channels, currentChannelIndex);
                activeFavoriteKey = activeGroup.channels[activeIndex].favoriteKey;
            }
        }

        java.util.ArrayList<Channel> favorites = new java.util.ArrayList<Channel>();
        for (String wantedKey : favoriteChannelKeys) {
            boolean found = false;
            for (ChannelCatalog.Group group : before) {
                if (group.source == ChannelCatalog.SOURCE_FAVORITES) {
                    continue;
                }
                for (Channel channel : group.channels) {
                    if (wantedKey.equals(favoriteKey(group, channel))) {
                        favorites.add(channel.asFavorite(wantedKey,
                                catalogSource(group, channel)));
                        found = true;
                        break;
                    }
                }
                if (found) {
                    break;
                }
            }
        }
        ChannelCatalog.setFavoriteChannels(
                favorites.toArray(new Channel[favorites.size()]));

        if (activeFavoriteKey == null) {
            return;
        }
        ChannelCatalog.Group favoriteGroup = ChannelCatalog.GROUPS[0];
        for (int index = 0; index < favoriteGroup.channels.length; index++) {
            if (activeFavoriteKey.equals(favoriteGroup.channels[index].favoriteKey)) {
                currentGroupIndex = 0;
                currentChannelIndex = index;
                return;
            }
        }
        ChannelCatalog.Group[] after = ChannelCatalog.GROUPS;
        for (int groupIndex = 1; groupIndex < after.length; groupIndex++) {
            ChannelCatalog.Group group = after[groupIndex];
            for (int channelIndex = 0; channelIndex < group.channels.length; channelIndex++) {
                if (activeFavoriteKey.equals(favoriteKey(group, group.channels[channelIndex]))) {
                    currentGroupIndex = groupIndex;
                    currentChannelIndex = channelIndex;
                    return;
                }
            }
        }
        currentGroupIndex = 1;
        currentChannelIndex = ChannelCatalog.defaultChannelIndex(currentGroup());
    }

    private void migrateFavoriteGroupIndex(SharedPreferences preferences) {
        if (preferences.getBoolean(FAVORITE_GROUP_INDEX_MIGRATED, false)) {
            return;
        }
        SharedPreferences.Editor editor = preferences.edit()
                .putBoolean(FAVORITE_GROUP_INDEX_MIGRATED, true);
        if (preferences.contains(LAST_GROUP_INDEX)) {
            editor.putInt(LAST_GROUP_INDEX,
                    Math.max(0, preferences.getInt(LAST_GROUP_INDEX, 0)) + 1);
        }
        editor.apply();
    }

    private void migrateMergedCentralGroups(SharedPreferences preferences) {
        if (preferences.getBoolean(CENTRAL_GROUPS_MERGED, false)) {
            return;
        }
        SharedPreferences.Editor editor = preferences.edit()
                .putBoolean(CENTRAL_GROUPS_MERGED, true);
        if (preferences.contains(LAST_GROUP_INDEX)) {
            int oldIndex = Math.max(0, preferences.getInt(LAST_GROUP_INDEX, 0));
            if (oldIndex == 2) {
                oldIndex = 1;
            } else if (oldIndex >= 3) {
                oldIndex--;
            }
            editor.putInt(LAST_GROUP_INDEX, oldIndex);
        }
        editor.apply();
    }

    private void updateFavoriteButton() {
        if (channelAdapter == null || browsingGroupIndex < 0
                || browsingGroupIndex >= ChannelCatalog.GROUPS.length) {
            return;
        }
        ChannelCatalog.Group group = ChannelCatalog.GROUPS[browsingGroupIndex];
        int position = channelList.getSelectedItemPosition();
        if (group.channels.length == 0 || position == AdapterView.INVALID_POSITION
                || position >= group.channels.length) {
            favoriteActionFocused = false;
            channelAdapter.setFavoriteFocusIndex(-1);
            return;
        }
        channelAdapter.setFavoriteFocusIndex(favoriteActionFocused ? position : -1);
    }

    private boolean isBrowsingChannelFavorite(int position) {
        if (browsingGroupIndex < 0 || browsingGroupIndex >= ChannelCatalog.GROUPS.length) {
            return false;
        }
        ChannelCatalog.Group group = ChannelCatalog.GROUPS[browsingGroupIndex];
        return position >= 0 && position < group.channels.length
                && favoriteChannelKeys.contains(favoriteKey(group, group.channels[position]));
    }

    private void setFavoriteActionFocused(boolean focused) {
        favoriteActionFocused = focused;
        updateFavoriteButton();
        if (focused) {
            channelList.requestFocus();
        }
    }

    private void toggleSelectedChannelFavorite() {
        toggleBrowsingChannelFavorite(channelList.getSelectedItemPosition());
    }

    private void toggleBrowsingChannelFavorite(int position) {
        if (browsingGroupIndex < 0 || browsingGroupIndex >= ChannelCatalog.GROUPS.length) {
            return;
        }
        ChannelCatalog.Group group = ChannelCatalog.GROUPS[browsingGroupIndex];
        if (group.channels.length == 0 || position == AdapterView.INVALID_POSITION
                || position >= group.channels.length) {
            return;
        }
        Channel channel = group.channels[position];
        String key = favoriteKey(group, channel);
        boolean added;
        if (favoriteChannelKeys.contains(key)) {
            favoriteChannelKeys.remove(key);
            added = false;
        } else {
            favoriteChannelKeys.add(key);
            added = true;
        }
        saveFavoriteChannels();
        refreshFavoriteCatalog();
        groupAdapter.showGroups(ChannelCatalog.GROUPS, browsingGroupIndex);
        if (browsingGroupIndex == 0) {
            showChannelMenu(0);
            if (ChannelCatalog.GROUPS[0].channels.length == 0) {
                favoriteActionFocused = false;
                groupList.requestFocus();
            } else {
                setFavoriteActionFocused(true);
            }
        } else {
            setFavoriteActionFocused(true);
        }
        showChannelBar(channel.name, added ? "已添加到我的收藏" : "已取消收藏");
    }

    private void openChannelList() {
        cancelPendingRelativeSwitch();
        clearNumericChannelInput();
        lastBackPressedAt = 0L;
        backPrompt.removeCallbacks(hideBackPrompt);
        backPrompt.setVisibility(View.GONE);
        closeManagementPanel();
        channelListPanel.setVisibility(View.VISIBLE);
        updateChannelPanelWidth();
        ensureFlyMouseOnTop();
        showChannelMenu(currentGroupIndex);
        applyClockLocation();
        channelList.post(new Runnable() {
            @Override
            public void run() {
                favoriteActionFocused = false;
                channelList.setSelection(currentChannelIndex);
                channelList.setItemChecked(currentChannelIndex, true);
                groupList.setSelection(currentGroupIndex);
                groupList.setItemChecked(currentGroupIndex, true);
                updateFavoriteButton();
                channelList.requestFocusFromTouch();
                channelList.requestFocus();
            }
        });
        scheduleChannelListDismiss();
    }

    private void showChannelMenu(int groupIndex) {
        favoriteActionFocused = false;
        browsingGroupIndex = ChannelCatalog.wrapGroupIndex(groupIndex);
        ChannelCatalog.Group group = ChannelCatalog.GROUPS[browsingGroupIndex];
        int selectedIndex = browsingGroupIndex == currentGroupIndex
                ? currentChannelIndex : ChannelCatalog.defaultChannelIndex(group);
        groupAdapter.showGroups(ChannelCatalog.GROUPS, browsingGroupIndex);
        int playingIndex = browsingGroupIndex == currentGroupIndex
                ? currentChannelIndex : -1;
        channelAdapter.showChannels(group.channels, selectedIndex,
                playingIndex, currentSourceIndex);
        groupList.setSelection(browsingGroupIndex);
        groupList.setItemChecked(browsingGroupIndex, true);
        channelList.setSelection(selectedIndex);
        channelList.setItemChecked(selectedIndex, true);
        showEpgForBrowsingChannel(selectedIndex);
        updateFavoriteButton();
        scheduleChannelListDismiss();
    }

    private void closeChannelList() {
        channelListPanel.removeCallbacks(hideChannelList);
        channelPanelTouching = false;
        channelPanelHovering = false;
        favoriteActionFocused = false;
        channelListPanel.setVisibility(View.GONE);
        applyClockLocation();
        root.requestFocus();
    }

    private void scheduleChannelListDismiss() {
        channelListPanel.removeCallbacks(hideChannelList);
        channelListPanel.postDelayed(hideChannelList, PANEL_TIMEOUT_MS);
    }

    private void configureChannelPanelInteraction() {
        View.OnTouchListener touchListener = new View.OnTouchListener() {
            @Override
            public boolean onTouch(View view, MotionEvent event) {
                int action = event.getActionMasked();
                if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_MOVE) {
                    channelPanelTouching = true;
                    channelListPanel.removeCallbacks(hideChannelList);
                } else if (action == MotionEvent.ACTION_UP
                        || action == MotionEvent.ACTION_CANCEL) {
                    channelPanelTouching = false;
                    scheduleChannelListDismiss();
                }
                return false;
            }
        };
        View.OnHoverListener panelHoverListener = new View.OnHoverListener() {
            @Override
            public boolean onHover(View view, MotionEvent event) {
                int action = event.getActionMasked();
                channelPanelHovering = action != MotionEvent.ACTION_HOVER_EXIT;
                if (channelPanelHovering) {
                    channelListPanel.removeCallbacks(hideChannelList);
                } else {
                    scheduleChannelListDismiss();
                }
                return false;
            }
        };
        channelListPanel.setOnTouchListener(touchListener);
        groupList.setOnTouchListener(touchListener);
        channelList.setOnTouchListener(touchListener);
        epgList.setOnTouchListener(touchListener);
        channelListPanel.setOnHoverListener(panelHoverListener);
        epgList.setOnHoverListener(panelHoverListener);
        groupList.setOnHoverListener(new View.OnHoverListener() {
            @Override
            public boolean onHover(View view, MotionEvent event) {
                updatePanelHoverState(event);
                if (event.getActionMasked() == MotionEvent.ACTION_HOVER_MOVE) {
                    int position = groupList.pointToPosition(
                            (int) event.getX(), (int) event.getY());
                    if (position != AdapterView.INVALID_POSITION
                            && position != browsingGroupIndex) {
                        showChannelMenu(position);
                    }
                }
                return false;
            }
        });
        channelList.setOnHoverListener(new View.OnHoverListener() {
            @Override
            public boolean onHover(View view, MotionEvent event) {
                updatePanelHoverState(event);
                if (event.getActionMasked() == MotionEvent.ACTION_HOVER_MOVE) {
                    int position = channelList.pointToPosition(
                            (int) event.getX(), (int) event.getY());
                    if (position != AdapterView.INVALID_POSITION) {
                        channelList.setSelection(position);
                        showEpgForBrowsingChannel(position);
                    }
                }
                return false;
            }
        });
    }

    private void updatePanelHoverState(MotionEvent event) {
        channelPanelHovering = event.getActionMasked() != MotionEvent.ACTION_HOVER_EXIT;
        if (channelPanelHovering) {
            channelListPanel.removeCallbacks(hideChannelList);
        } else {
            scheduleChannelListDismiss();
        }
    }

    private void updateChannelPanelWidth() {
        int screenWidth = Math.max(root.getWidth(), getResources().getDisplayMetrics().widthPixels);
        float density = getResources().getDisplayMetrics().density;
        int maximumPanelWidth = Math.max(1, screenWidth - Math.round(24f * density));

        int groupDesired = desiredGroupColumnWidth(density);
        int channelDesired = desiredChannelColumnWidth(density);
        int epgDesired = desiredEpgColumnWidth(density);
        // Panel horizontal padding is 28dp. The two separators each occupy 17dp.
        int fixedWidth = Math.round(62f * density);
        int desiredPanelWidth = groupDesired + channelDesired + epgDesired + fixedWidth;
        int panelWidth = Math.min(maximumPanelWidth, desiredPanelWidth);
        int availableColumns = Math.max(3, panelWidth - fixedWidth);

        int groupMinimum = Math.round(150f * density);
        int channelMinimum = Math.round(215f * density);
        int epgMinimum = Math.round(220f * density);
        int[] widths = fitChannelColumns(availableColumns,
                groupDesired, channelDesired, epgDesired,
                groupMinimum, channelMinimum, epgMinimum);
        setExactWidth(groupList, widths[0]);
        setExactWidth(channelList, widths[1]);
        setExactWidth(epgColumn, widths[2]);

        ViewGroup.LayoutParams params = channelListPanel.getLayoutParams();
        if (params.width != panelWidth) {
            params.width = panelWidth;
            channelListPanel.setLayoutParams(params);
        }
    }

    private int desiredGroupColumnWidth(float density) {
        Paint paint = columnPaint(14f);
        float widest = 0f;
        int widestCount = 1;
        for (ChannelCatalog.Group group : ChannelCatalog.GROUPS) {
            widest = Math.max(widest, paint.measureText(group.title));
            widestCount = Math.max(widestCount, group.channels.length);
        }
        Paint countPaint = columnPaint(11f);
        int countWidth = Math.max(Math.round(28f * density),
                (int) Math.ceil(countPaint.measureText(String.valueOf(widestCount)))
                        + Math.round(14f * density));
        int chrome = Math.round(56f * density);
        return clamp((int) Math.ceil(widest) + countWidth + chrome,
                Math.round(150f * density), Math.round(250f * density));
    }

    private int desiredChannelColumnWidth(float density) {
        // Keep the channel column steady while browsing. The title area is sized for
        // roughly eight CJK characters; longer names are intentionally ellipsized.
        return Math.round(240f * density);
    }

    private int desiredEpgColumnWidth(float density) {
        Paint statusPaint = columnPaint(13f);
        float widest = epgStatus == null || epgStatus.getText() == null ? 0f
                : statusPaint.measureText(epgStatus.getText().toString());
        Paint titlePaint = columnPaint(14f);
        if (epgAdapter != null) {
            for (int index = 0; index < epgAdapter.getCount(); index++) {
                EpgManager.Program program = epgAdapter.getItem(index);
                if (program != null && program.title != null) {
                    widest = Math.max(widest, titlePaint.measureText(program.title));
                }
            }
        }
        return clamp((int) Math.ceil(widest) + Math.round(28f * density),
                Math.round(220f * density), Math.round(440f * density));
    }

    private Paint columnPaint(float textSizeSp) {
        columnMeasurePaint.setTextSize(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP,
                textSizeSp, getResources().getDisplayMetrics()));
        return columnMeasurePaint;
    }

    private static int[] fitChannelColumns(int available,
            int desiredFirst, int desiredSecond, int desiredThird,
            int minimumFirst, int minimumSecond, int minimumThird) {
        int desiredTotal = desiredFirst + desiredSecond + desiredThird;
        if (desiredTotal <= available) {
            return new int[] { desiredFirst, desiredSecond, desiredThird };
        }
        int minimumTotal = minimumFirst + minimumSecond + minimumThird;
        if (minimumTotal >= available) {
            int first = Math.max(1, Math.round(available * 0.26f));
            int second = Math.max(1, Math.round(available * 0.36f));
            return new int[] { first, second, Math.max(1, available - first - second) };
        }
        int extra = available - minimumTotal;
        int needFirst = Math.max(0, desiredFirst - minimumFirst);
        int needSecond = Math.max(0, desiredSecond - minimumSecond);
        int needThird = Math.max(0, desiredThird - minimumThird);
        int needTotal = Math.max(1, needFirst + needSecond + needThird);
        int first = minimumFirst + extra * needFirst / needTotal;
        int second = minimumSecond + extra * needSecond / needTotal;
        return new int[] { first, second, available - first - second };
    }

    private static void setExactWidth(View view, int width) {
        if (view == null) {
            return;
        }
        ViewGroup.LayoutParams params = view.getLayoutParams();
        if (params.width != width) {
            params.width = width;
            if (params instanceof LinearLayout.LayoutParams) {
                ((LinearLayout.LayoutParams) params).weight = 0f;
            }
            view.setLayoutParams(params);
        }
    }

    private static int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private void updateChannelBarWidth() {
        int screenWidth = Math.max(root.getWidth(), getResources().getDisplayMetrics().widthPixels);
        float density = getResources().getDisplayMetrics().density;
        int leftContent = Math.max(measuredTextWidth(channelName),
                Math.max(measuredTextWidth(statusText), Math.max(
                        measuredTextWidth(channelSourcePath), measuredTextWidth(channelEpg))));
        leftContent = Math.max(Math.round(170f * density),
                Math.min(Math.round(420f * density), leftContent + Math.round(8f * density)));
        int rightContent = Math.max(Math.round(170f * density),
                Math.min(Math.round(260f * density),
                        measuredTextWidth(videoInfo) + Math.round(6f * density)));

        ViewGroup.LayoutParams videoInfoParams = videoInfo.getLayoutParams();
        if (videoInfoParams.width != rightContent) {
            videoInfoParams.width = rightContent;
            videoInfo.setLayoutParams(videoInfoParams);
        }

        int fixedSpace = Math.round(61f * density);
        if (channelProgress.getVisibility() == View.VISIBLE) {
            fixedSpace += Math.round(34f * density);
        }
        int preferred = leftContent + rightContent + fixedSpace;
        int widthStep = Math.max(1, Math.round(8f * density));
        preferred = ((preferred + widthStep - 1) / widthStep) * widthStep;
        int margin = Math.round(32f * density);
        ViewGroup.LayoutParams params = channelBar.getLayoutParams();
        params.width = Math.min(preferred, Math.max(1, screenWidth - margin));
        channelBar.setLayoutParams(params);
    }

    private static int measuredTextWidth(TextView view) {
        if (view == null || view.getVisibility() == View.GONE || view.getText() == null) {
            return 0;
        }
        String[] lines = view.getText().toString().split("\\n", -1);
        float maximum = 0f;
        for (String line : lines) {
            maximum = Math.max(maximum, view.getPaint().measureText(line));
        }
        return (int) Math.ceil(maximum);
    }

    private String effectiveEpgUrl() {
        if (epgUrl != null && epgUrl.length() > 0) {
            return epgUrl;
        }
        String embedded = playlistManager == null ? "" : playlistManager.getEmbeddedEpgUrl();
        return embedded.length() > 0 ? embedded : EpgManager.DEFAULT_URL;
    }

    private void refreshEpg() {
        if (epgManager == null) {
            return;
        }
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (epgStatus != null) {
                    epgStatus.setText("正在加载节目单…");
                }
            }
        });
        epgManager.refresh(effectiveEpgUrl(), new EpgManager.Listener() {
            @Override
            public void onUpdated() {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        int position = channelList == null
                                ? currentChannelIndex : channelList.getSelectedItemPosition();
                        if (position == AdapterView.INVALID_POSITION) {
                            position = browsingGroupIndex == currentGroupIndex
                                    ? currentChannelIndex : 0;
                        }
                        showEpgForBrowsingChannel(position);
                        if (channelBar.getVisibility() == View.VISIBLE) {
                            updateChannelCardEpg(channelName.getText().toString());
                        }
                    }
                });
            }
        });
    }

    private void showEpgForBrowsingChannel(int position) {
        if (epgManager == null || epgAdapter == null
                || browsingGroupIndex < 0 || browsingGroupIndex >= ChannelCatalog.GROUPS.length) {
            return;
        }
        Channel[] channels = ChannelCatalog.GROUPS[browsingGroupIndex].channels;
        if (channels == null || channels.length == 0) {
            epgAdapter.showPrograms(null);
            epgStatus.setText("暂无频道");
            return;
        }
        int safePosition = ChannelCatalog.wrapIndex(channels, position);
        Channel channel = channels[safePosition];
        java.util.List<EpgManager.Program> programs = epgManager.programsFor(channel);
        epgAdapter.showPrograms(programs);
        if (programs.isEmpty()) {
            String error = epgManager.getLastError();
            epgStatus.setText(epgManager.isLoading() ? channel.name + " · 正在加载节目单"
                    : error.length() > 0 ? channel.name + " · 加载失败"
                    : channel.name + " · 暂无节目单");
        } else {
            epgStatus.setText(channel.name + " · 今日节目");
            int current = epgAdapter.currentProgramIndex();
            if (current >= 0) {
                epgList.setSelection(current);
            }
        }
        if (channelListPanel != null && channelListPanel.getVisibility() == View.VISIBLE) {
            updateChannelPanelWidth();
        }
    }

    private void showChannelBar(final String channel, final String status) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                channelBar.removeCallbacks(hideChannelBar);
                channelName.setText(channel);
                statusText.setText(status);
                channelProgress.setVisibility(loadingActive ? View.VISIBLE : View.GONE);
                updateChannelSourcePath(channel);
                updateChannelCardEpg(channel);
                showChannelCard();
                if (!loadingActive) {
                    channelBar.postDelayed(hideChannelBar, CHANNEL_BAR_TIMEOUT_MS);
                }
            }
        });
    }

    private void showChannelCard() {
        updateChannelBarWidth();
        if (channelBar.getVisibility() == View.VISIBLE) {
            return;
        }
        channelBar.setAlpha(0f);
        channelBar.setTranslationY(18f * getResources().getDisplayMetrics().density);
        channelBar.setVisibility(View.VISIBLE);
        channelBar.animate().alpha(1f).translationY(0f).setDuration(180L).start();
    }

    private void updateChannelSourcePath(String displayedChannel) {
        if (channelSourcePath == null || displayedChannel == null
                || currentGroupIndex < 0
                || currentGroupIndex >= ChannelCatalog.GROUPS.length) {
            return;
        }
        ChannelCatalog.Group group = currentGroup();
        if (group.channels.length == 0) {
            channelSourcePath.setVisibility(View.GONE);
            return;
        }
        Channel channel = currentChannel();
        if (!displayedChannel.equals(channel.name)) {
            channelSourcePath.setVisibility(View.GONE);
            return;
        }
        String path = channel.sourceUrl(currentSourceIndex);
        if (path == null) {
            channelSourcePath.setVisibility(View.GONE);
            return;
        }
        path = path.trim();
        String webViewPrefix = "webview://";
        if (path.regionMatches(true, 0, webViewPrefix, 0, webViewPrefix.length())) {
            path = path.substring(webViewPrefix.length()).trim();
        }
        String suffixPath = path;
        int query = suffixPath.indexOf('?');
        int fragment = suffixPath.indexOf('#');
        int suffixEnd = query < 0 ? fragment : fragment < 0
                ? query : Math.min(query, fragment);
        if (suffixEnd >= 0) {
            suffixPath = suffixPath.substring(0, suffixEnd);
        }
        suffixPath = suffixPath.toLowerCase(Locale.US);
        if (path.length() == 0 || suffixPath.endsWith(".m3u8")
                || suffixPath.endsWith(".m3u")) {
            channelSourcePath.setVisibility(View.GONE);
            return;
        }
        channelSourcePath.setText(path);
        channelSourcePath.setVisibility(View.VISIBLE);
    }

    private void updateChannelCardEpg(String displayedChannel) {
        if (channelEpg == null || epgManager == null || displayedChannel == null) {
            return;
        }
        Channel channel = currentChannel();
        if (channel == null || !displayedChannel.equals(channel.name)) {
            channelEpg.setVisibility(View.GONE);
            return;
        }
        long now = System.currentTimeMillis();
        java.util.List<EpgManager.Program> programs = epgManager.programsFor(channel);
        for (EpgManager.Program program : programs) {
            if (!program.isPlaying(now)) {
                continue;
            }
            channelEpg.setText(channelEpgTimeFormat.format(new Date(program.startMillis))
                    + "–" + channelEpgTimeFormat.format(new Date(program.stopMillis))
                    + "  " + program.title);
            channelEpg.setVisibility(View.VISIBLE);
            return;
        }
        channelEpg.setVisibility(View.GONE);
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
                loadingActive = true;
                channelBar.removeCallbacks(hideChannelBar);
                channelName.setText(channel);
                statusText.setText(status);
                channelProgress.setVisibility(View.VISIBLE);
                updateChannelSourcePath(channel);
                updateChannelCardEpg(channel);
                refreshVideoInfo();
                showChannelCard();
            }
        });
    }

    private void updateLoadingStatus(final String status) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                statusText.setText(status);
                if (loadingActive) {
                    channelProgress.setVisibility(View.VISIBLE);
                    showChannelCard();
                }
            }
        });
    }

    private void hideLoading() {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                loadingActive = false;
                channelProgress.setVisibility(View.GONE);
                channelBar.removeCallbacks(hideChannelBar);
                if (channelBar.getVisibility() == View.VISIBLE) {
                    channelBar.postDelayed(hideChannelBar, CHANNEL_BAR_TIMEOUT_MS);
                }
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
            abortChannelSwitchAnimation();
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
        refreshCallAudioMute();
        float outputFps = 0f;
        if (player != null) {
            outputFps = player.getVideoOutputFramesPerSecond();
        }
        if (prepared && isActiveCctvWebSource()) {
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
        PlaybackDebugStats stats = collectPlaybackStreamStats(outputFps);
        String resolution = stats.width > 0 && stats.height > 0
                ? stats.width + "×" + stats.height : "-- × --";
        String fps = stats.frameRate > 0.01f
                ? String.format(Locale.US, "%.1f fps", stats.frameRate) : "-- fps";
        String decoderStatus;
        if (activeSoftwareDecode) {
            decoderStatus = "IJK 软解";
        } else if (!HARDWARE_DECODER_AUTO.equals(hardwareDecoder)) {
            decoderStatus = "IJK 硬解 · " + hardwareDecoder;
        } else {
            decoderStatus = "IJK 硬解";
        }
        if (videoInfo != null) {
            videoInfo.setText(resolution + " · " + fps + " · "
                    + formatBitrate(stats.videoBitrate) + "\n" + decoderStatus);
        }
        if (channelBar.getVisibility() == View.VISIBLE) {
            updateChannelCardEpg(channelName.getText().toString());
            updateChannelBarWidth();
        }
        if (debugInfoOverlay != null && showDebugInfo) {
            stats.cpuUsage = sampleSystemCpuUsage();
            stats.cpuLabel = systemCpuMetricLabel;
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

    private PlaybackDebugStats collectPlaybackStreamStats(float measuredOutputFps) {
        PlaybackDebugStats stats = new PlaybackDebugStats();
        stats.width = videoWidth;
        stats.height = videoHeight;
        stats.frameRate = measuredOutputFps;
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

        if (epgList.hasFocus()) {
            int count = epgAdapter.getCount();
            if (count == 0) {
                return;
            }
            int position = epgList.getSelectedItemPosition();
            if (position == AdapterView.INVALID_POSITION) {
                position = Math.max(0, epgAdapter.currentProgramIndex());
            }
            epgList.setSelection(Math.max(0, Math.min(count - 1, position + offset)));
            return;
        }

        int position = channelList.getSelectedItemPosition();
        Channel[] channels = ChannelCatalog.GROUPS[browsingGroupIndex].channels;
        if (channels.length == 0) {
            return;
        }
        if (position == AdapterView.INVALID_POSITION) {
            position = browsingGroupIndex == currentGroupIndex
                    ? currentChannelIndex : ChannelCatalog.defaultChannelIndex(
                            ChannelCatalog.GROUPS[browsingGroupIndex]);
        }
        int nextPosition = Math.max(0, Math.min(channels.length - 1, position + offset));
        channelList.setSelection(nextPosition);
        showEpgForBrowsingChannel(nextPosition);
        updateFavoriteButton();
    }

    private void requestPlaybackAudioFocus() {
        if (playbackAudioManager == null) {
            playbackAudioManager = (AudioManager) getSystemService(AUDIO_SERVICE);
        }
        if (playbackAudioManager == null) {
            return;
        }
        int result = playbackAudioManager.requestAudioFocus(playbackAudioFocusListener,
                AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN);
        mutedByAudioFocus = result != AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
        refreshCallAudioMute();
    }

    private void refreshCallAudioMute() {
        if (playbackAudioManager == null) {
            playbackAudioManager = (AudioManager) getSystemService(AUDIO_SERVICE);
        }
        if (playbackAudioManager == null) {
            mutedByCallMode = false;
            return;
        }
        int mode = playbackAudioManager.getMode();
        boolean callActive = mode == AudioManager.MODE_IN_CALL
                || mode == AudioManager.MODE_IN_COMMUNICATION;
        if (mutedByCallMode != callActive) {
            mutedByCallMode = callActive;
            applyPlaybackMuteState();
        }
    }

    private boolean isPlaybackMuted() {
        return mutedByAudioFocus || mutedByCallMode;
    }

    private void applyPlaybackMuteState() {
        IjkMediaPlayer activePlayer = player;
        if (activePlayer == null) {
            return;
        }
        float volume = isPlaybackMuted() ? 0f : 1f;
        try {
            activePlayer.setVolume(volume, volume);
        } catch (RuntimeException error) {
            Log.w(TAG, "Unable to update playback mute state", error);
        }
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
                && !channelSwitchAnimating
                && !gestureReboundAnimating
                && channelListPanel != null
                && channelListPanel.getVisibility() != View.VISIBLE
                && managementPanel != null
                && managementPanel.getVisibility() != View.VISIBLE
                && backPrompt != null
                && backPrompt.getVisibility() != View.VISIBLE
                && (webSourceView == null || !webSourceView.isPageVisible());
    }

    private void configurePlaybackGestureExclusion() {
        float density = getResources().getDisplayMetrics().density;
        int extraPadding = Math.round(12f * density);
        playbackGestureTopExclusion = systemDimensionPixelSize(
                "status_bar_height", Math.round(24f * density)) + extraPadding;
        playbackGestureBottomExclusion = systemDimensionPixelSize(
                "navigation_bar_height", Math.round(40f * density)) + extraPadding;
    }

    private int systemDimensionPixelSize(String name, int fallback) {
        int identifier = getResources().getIdentifier(name, "dimen", "android");
        if (identifier == 0) {
            return fallback;
        }
        try {
            return Math.max(fallback, getResources().getDimensionPixelSize(identifier));
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }

    private boolean isPlaybackGestureEdgeExcluded(MotionEvent event) {
        int height = root == null ? 0 : root.getHeight();
        if (height <= 0) {
            height = getResources().getDisplayMetrics().heightPixels;
        }
        float y = event.getY();
        return y < playbackGestureTopExclusion
                || y > height - playbackGestureBottomExclusion;
    }

    private void beginPlaybackGesture(MotionEvent event) {
        playbackGestureTracking = true;
        playbackGestureVertical = false;
        playbackGestureHorizontal = false;
        playbackGestureDownX = event.getX();
        playbackGestureDownY = event.getY();
        playbackGestureDeltaX = 0f;
        playbackGestureDeltaY = 0f;
        playbackGestureLeftSide = playbackGestureDownX < root.getWidth() / 2f;
        playbackGestureLastVolume = -1;
        if (playbackGestureLeftSide) {
            AudioManager audio = (AudioManager) getSystemService(AUDIO_SERVICE);
            playbackGestureStartVolume = audio == null ? 0
                    : audio.getStreamVolume(AudioManager.STREAM_MUSIC);
            playbackGestureLastVolume = playbackGestureStartVolume;
        } else {
            prepareChannelSwipeSnapshot();
        }
    }

    private boolean handlePlaybackGesture(MotionEvent event) {
        float deltaX = event.getX() - playbackGestureDownX;
        float deltaY = event.getY() - playbackGestureDownY;
        playbackGestureDeltaX = deltaX;
        playbackGestureDeltaY = deltaY;
        float absoluteX = Math.abs(deltaX);
        float absoluteY = Math.abs(deltaY);
        if (!playbackGestureVertical && !playbackGestureHorizontal
                && absoluteY > playbackGestureTouchSlop
                && absoluteY > absoluteX * 1.25f) {
            playbackGestureVertical = true;
        } else if (!playbackGestureVertical && !playbackGestureHorizontal
                && !playbackGestureLeftSide
                && absoluteX > playbackGestureTouchSlop
                && absoluteX > absoluteY * 1.25f) {
            playbackGestureHorizontal = true;
        }

        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_MOVE:
                if (playbackGestureVertical && playbackGestureLeftSide) {
                    updateGestureVolume(deltaY);
                } else if (playbackGestureVertical) {
                    moveSwitchPreview(0f, dampedGestureDistance(
                            deltaY, Math.max(1f, root.getHeight())));
                } else if (playbackGestureHorizontal) {
                    moveSwitchPreview(dampedGestureDistance(
                            deltaX, Math.max(1f, root.getWidth())), 0f);
                }
                return true;
            case MotionEvent.ACTION_UP:
                if (playbackGestureVertical && playbackGestureLeftSide) {
                    updateGestureVolume(deltaY);
                } else if (playbackGestureVertical) {
                    float channelThreshold = Math.max(playbackGestureTouchSlop * 7f,
                            root.getHeight() * 0.17f);
                    if (absoluteY >= channelThreshold) {
                        // Swipe up advances; swipe down returns to the previous channel.
                        animateRelativeChannelSwitch(deltaY < 0f ? 1 : -1,
                                deltaY < 0f ? -1f : 1f);
                    } else {
                        animateGestureRebound();
                    }
                } else if (playbackGestureHorizontal) {
                    float sourceThreshold = Math.max(playbackGestureTouchSlop * 7f,
                            root.getWidth() * 0.16f);
                    if (absoluteX >= sourceThreshold) {
                        // Swipe left advances to the next source; right returns to previous.
                        animateSourceSwitch(deltaX < 0f ? 1 : -1,
                                deltaX < 0f ? -1f : 1f);
                    } else {
                        animateGestureRebound();
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
                if ((playbackGestureVertical && !playbackGestureLeftSide)
                        || playbackGestureHorizontal) {
                    animateGestureRebound();
                }
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

    private void prepareChannelSwipeSnapshot() {
        clearChannelSwitchVisuals();
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N
                || !videoRenderingStarted || videoView == null
                || !videoView.isSurfaceReady() || videoView.getWidth() <= 0
                || videoView.getHeight() <= 0) {
            return;
        }
        final int generation = ++channelSwipeCaptureGeneration;
        int maximumWidth = lowResourceDevice ? 960 : 1280;
        int width = Math.min(videoView.getWidth(), maximumWidth);
        int height = Math.max(1, Math.round(
                (float) videoView.getHeight() * width / videoView.getWidth()));
        final Bitmap bitmap;
        try {
            bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        } catch (OutOfMemoryError error) {
            Log.w(TAG, "Unable to allocate channel swipe snapshot", error);
            return;
        }
        try {
            PixelCopy.request(videoView, bitmap, new PixelCopy.OnPixelCopyFinishedListener() {
                @Override
                public void onPixelCopyFinished(int result) {
                    if (generation != channelSwipeCaptureGeneration
                            || !playbackGestureTracking
                            || result != PixelCopy.SUCCESS) {
                        bitmap.recycle();
                        return;
                    }
                    channelSwipeBitmap = bitmap;
                    channelSwipeSnapshot.setImageBitmap(bitmap);
                    if (playbackGestureVertical && !playbackGestureLeftSide) {
                        moveSwitchPreview(0f, dampedGestureDistance(
                                playbackGestureDeltaY, Math.max(1f, root.getHeight())));
                    } else if (playbackGestureHorizontal) {
                        moveSwitchPreview(dampedGestureDistance(
                                playbackGestureDeltaX, Math.max(1f, root.getWidth())), 0f);
                    }
                }
            }, new Handler(Looper.getMainLooper()));
        } catch (RuntimeException error) {
            bitmap.recycle();
            Log.w(TAG, "Unable to capture channel swipe snapshot", error);
        }
    }

    private static float dampedGestureDistance(float distance, float viewport) {
        float absolute = Math.abs(distance);
        float damped = absolute * 0.78f / (1f + absolute / (viewport * 1.35f));
        return Math.copySign(Math.min(viewport * 0.82f, damped), distance);
    }

    private void moveSwitchPreview(float translationX, float translationY) {
        if (channelSwipeBitmap != null && channelSwipeSnapshot != null) {
            channelSwitchBlackout.setVisibility(View.VISIBLE);
            channelSwipeSnapshot.setVisibility(View.VISIBLE);
            channelSwipeSnapshot.setAlpha(1f);
            channelSwipeSnapshot.setTranslationX(translationX);
            channelSwipeSnapshot.setTranslationY(translationY);
        } else {
            setPlaybackLayerTranslation(translationX, translationY);
        }
    }

    private void animateGestureRebound() {
        if (gestureReboundAnimating || channelSwitchAnimating) {
            return;
        }
        gestureReboundAnimating = true;
        if (channelSwipeSnapshot != null
                && channelSwipeSnapshot.getVisibility() == View.VISIBLE) {
            channelSwipeSnapshot.animate().cancel();
            channelSwipeSnapshot.animate().translationX(0f).translationY(0f)
                    .alpha(1f).setInterpolator(GESTURE_REBOUND_EASING)
                    .setDuration(GESTURE_REBOUND_ANIMATION_MS).start();
        }
        animatePlaybackLayers(0f, 0f, GESTURE_REBOUND_ANIMATION_MS,
                GESTURE_REBOUND_EASING);
        channelBar.removeCallbacks(finishGestureRebound);
        channelBar.postDelayed(finishGestureRebound, GESTURE_REBOUND_FINISH_MS);
    }

    private void clearChannelSwitchVisuals() {
        channelSwipeCaptureGeneration++;
        if (channelSwipeSnapshot != null) {
            channelSwipeSnapshot.animate().cancel();
            channelSwipeSnapshot.setVisibility(View.GONE);
            channelSwipeSnapshot.setTranslationX(0f);
            channelSwipeSnapshot.setTranslationY(0f);
            channelSwipeSnapshot.setImageDrawable(null);
        }
        if (channelSwitchBlackout != null) {
            channelSwitchBlackout.setVisibility(View.GONE);
        }
        if (channelSwipeBitmap != null) {
            channelSwipeBitmap.recycle();
            channelSwipeBitmap = null;
        }
    }

    private void resetPlaybackGesture() {
        playbackGestureTracking = false;
        playbackGestureVertical = false;
        playbackGestureHorizontal = false;
        playbackGestureLastVolume = -1;
        if (!channelSwitchAnimating && !gestureReboundAnimating) {
            clearChannelSwitchVisuals();
            restorePlaybackLayer();
        }
    }

    private void setPlaybackLayerTranslation(float translationX, float translationY) {
        if (videoView != null) {
            videoView.animate().cancel();
            videoView.setTranslationX(translationX);
            videoView.setTranslationY(translationY);
        }
        if (webSourceView != null) {
            webSourceView.animate().cancel();
            webSourceView.setTranslationX(translationX);
            webSourceView.setTranslationY(translationY);
        }
    }

    private void restorePlaybackLayer() {
        animatePlaybackLayers(0f, 0f, 180L, PLAYBACK_RESTORE_EASING);
    }

    private void animateRelativeChannelSwitch(final int offset, final float direction) {
        if (channelSwitchAnimating) {
            return;
        }

        if (epgList.hasFocus()) {
            int position = epgList.getSelectedItemPosition();
            if (position == AdapterView.INVALID_POSITION) {
                position = Math.max(0, epgAdapter.currentProgramIndex());
            }
            int nextPosition = Math.max(0, Math.min(
                    epgAdapter.getCount() - 1, position + offset));
            if (nextPosition >= 0) {
                epgList.setSelection(nextPosition);
            }
            return;
        }
        channelSwitchAnimating = true;
        channelSwitchDirectionX = 0f;
        channelSwitchDirectionY = direction;
        channelSwitchRequestId = -1;
        float distance = Math.max(1f, root.getHeight()) * direction;
        channelSwitchBlackout.setVisibility(View.VISIBLE);
        if (channelSwipeBitmap != null && channelSwipeSnapshot != null) {
            channelSwipeSnapshot.setVisibility(View.VISIBLE);
            channelSwipeSnapshot.animate().translationY(distance).alpha(1f)
                    .setInterpolator(GESTURE_SWITCH_EASING)
                    .setDuration(GESTURE_SWITCH_ANIMATION_MS).start();
        } else {
            animatePlaybackLayerOut(0f, distance);
        }
        channelBar.postDelayed(new Runnable() {
            @Override
            public void run() {
                positionIncomingChannelOffscreen();
                switchRelative(offset);
            }
        }, GESTURE_SWITCH_ANIMATION_MS);
    }

    private void positionIncomingChannelOffscreen() {
        if (channelSwitchDirectionY == 0f && channelSwitchDirectionX == 0f) {
            return;
        }
        channelSwitchBlackout.setVisibility(View.VISIBLE);
    }

    private boolean isWaitingForIncomingFrame(int requestId) {
        return channelSwitchAnimating
                && (channelSwitchDirectionY != 0f || channelSwitchDirectionX != 0f)
                && (channelSwitchRequestId < 0 || channelSwitchRequestId == requestId);
    }

    private void revealIncomingChannel(int requestId) {
        if (!isWaitingForIncomingFrame(requestId)) {
            return;
        }
        channelSwitchRequestId = requestId;
        clearChannelSwitchVisuals();
        channelSwitchAnimating = false;
        channelSwitchDirectionX = 0f;
        channelSwitchDirectionY = 0f;
        channelSwitchRequestId = -1;
        restorePlaybackLayer();
    }

    private void abortChannelSwitchAnimation() {
        if (!channelSwitchAnimating
                || (channelSwitchDirectionY == 0f && channelSwitchDirectionX == 0f)) {
            return;
        }
        channelSwitchAnimating = false;
        channelSwitchDirectionX = 0f;
        channelSwitchDirectionY = 0f;
        channelSwitchRequestId = -1;
        clearChannelSwitchVisuals();
        restorePlaybackLayer();
    }

    private void animateSourceSwitch(final int offset, float direction) {
        if (channelSwitchAnimating) {
            return;
        }
        if (currentCatalogSource() != ChannelCatalog.SOURCE_CUSTOM
                || currentChannel().sourceCount() <= 1) {
            animateGestureRebound();
            showChannelBar(currentChannel().name, "当前频道没有可切换的备用源");
            return;
        }
        channelSwitchAnimating = true;
        channelSwitchDirectionX = direction;
        channelSwitchDirectionY = 0f;
        channelSwitchRequestId = -1;
        float distance = Math.max(1f, root.getWidth()) * direction;
        channelSwitchBlackout.setVisibility(View.VISIBLE);
        if (channelSwipeBitmap != null && channelSwipeSnapshot != null) {
            channelSwipeSnapshot.setVisibility(View.VISIBLE);
            channelSwipeSnapshot.animate().translationX(distance).alpha(1f)
                    .setInterpolator(GESTURE_SWITCH_EASING)
                    .setDuration(GESTURE_SWITCH_ANIMATION_MS).start();
        } else {
            animatePlaybackLayerOut(distance, 0f);
        }
        channelBar.postDelayed(new Runnable() {
            @Override
            public void run() {
                positionIncomingChannelOffscreen();
                if (!switchCustomSource(offset, false, "")) {
                    abortChannelSwitchAnimation();
                }
            }
        }, GESTURE_SWITCH_ANIMATION_MS);
    }

    private void animatePlaybackLayerOut(float translationX, float translationY) {
        animatePlaybackLayers(translationX, translationY, GESTURE_SWITCH_ANIMATION_MS,
                GESTURE_SWITCH_EASING);
    }

    private void animatePlaybackLayers(float translationX, float translationY,
            long duration, TimeInterpolator interpolator) {
        animatePlaybackLayer(videoView, translationX, translationY, duration, interpolator);
        animatePlaybackLayer(webSourceView, translationX, translationY, duration, interpolator);
    }

    private static void animatePlaybackLayer(View view, float translationX,
            float translationY, long duration, TimeInterpolator interpolator) {
        if (view != null) {
            view.animate().translationX(translationX).translationY(translationY)
                    .alpha(1f).setInterpolator(interpolator).setDuration(duration).start();
        }
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN && isTouchInput(event)) {
            playbackGestureEdgeBlocked = false;
            setRemoteInputMode(false);
            if (channelListPanel != null
                    && channelListPanel.getVisibility() == View.VISIBLE
                    && !isPointInsideView(event, channelListPanel)) {
                closeChannelList();
                return true;
            }
            if (canStartPlaybackGesture()) {
                if (isPlaybackGestureEdgeExcluded(event)) {
                    // The system receives edge gestures before the activity. If an edge
                    // sequence still reaches us, swallow it without triggering a player
                    // tap or swipe so notification/home gestures cannot cause two actions.
                    playbackGestureEdgeBlocked = true;
                    return true;
                }
                beginPlaybackGesture(event);
                return true;
            }
        }
        if (playbackGestureEdgeBlocked) {
            if (event.getActionMasked() == MotionEvent.ACTION_UP
                    || event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
                playbackGestureEdgeBlocked = false;
            }
            return true;
        }
        if (playbackGestureTracking) {
            return handlePlaybackGesture(event);
        }
        return super.dispatchTouchEvent(event);
    }

    private static boolean isPointInsideView(MotionEvent event, View view) {
        Rect bounds = new Rect();
        return view.getGlobalVisibleRect(bounds)
                && bounds.contains((int) event.getRawX(), (int) event.getRawY());
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
                    if (epgList.hasFocus()) {
                        setFavoriteActionFocused(true);
                    } else if (favoriteActionFocused) {
                        setFavoriteActionFocused(false);
                    } else if (channelList.hasFocus()) {
                        groupList.requestFocus();
                        groupList.setSelection(browsingGroupIndex);
                    }
                    return true;
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                    if (groupList.hasFocus()) {
                        if (ChannelCatalog.GROUPS[browsingGroupIndex].channels.length > 0) {
                            favoriteActionFocused = false;
                            channelList.requestFocus();
                        }
                    } else if (favoriteActionFocused && epgAdapter.getCount() > 0) {
                        favoriteActionFocused = false;
                        updateFavoriteButton();
                        epgList.requestFocus();
                        int currentProgram = epgAdapter.currentProgramIndex();
                        if (currentProgram >= 0) {
                            epgList.setSelection(currentProgram);
                        }
                    } else if (channelList.hasFocus()) {
                        setFavoriteActionFocused(true);
                    }
                    return true;
                case KeyEvent.KEYCODE_DPAD_UP:
                    moveChannelMenuSelection(-1);
                    return true;
                case KeyEvent.KEYCODE_DPAD_DOWN:
                    moveChannelMenuSelection(1);
                    return true;
                case KeyEvent.KEYCODE_DPAD_CENTER:
                case KeyEvent.KEYCODE_ENTER:
                    if (favoriteActionFocused) {
                        toggleSelectedChannelFavorite();
                    } else if (channelList.hasFocus()) {
                        int position = channelList.getSelectedItemPosition();
                        if (position != AdapterView.INVALID_POSITION) {
                            switchBrowsingChannel(position);
                        }
                    } else if (groupList.hasFocus()
                            && ChannelCatalog.GROUPS[browsingGroupIndex].channels.length > 0) {
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
        if (managementPanel.getVisibility() == View.VISIBLE) {
            closeManagementPanel();
            return;
        }
        if (channelListPanel.getVisibility() == View.VISIBLE) {
            closeChannelList();
            return;
        }
        long now = SystemClock.elapsedRealtime();
        if (webSourceView != null && webSourceView.isPageVisible()) {
            if (now - lastWebBackPressedAt <= EXIT_CONFIRM_TIMEOUT_MS) {
                closeWebSource();
                hideLoading();
                showChannelBar(currentChannel().name, "网页已关闭");
                return;
            }
            lastBackPressedAt = 0L;
            lastWebBackPressedAt = now;
            showBackPrompt(true);
            return;
        }
        lastWebBackPressedAt = 0L;
        if (now - lastBackPressedAt <= EXIT_CONFIRM_TIMEOUT_MS) {
            finish();
            return;
        }
        lastBackPressedAt = now;
        showBackPrompt(false);
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
        if (hasActivePlayer()) {
            requestPlaybackAudioFocus();
            applyPlaybackMuteState();
        }
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
        clearChannelSwitchVisuals();
        if (playbackAudioManager != null) {
            playbackAudioManager.abandonAudioFocus(playbackAudioFocusListener);
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
