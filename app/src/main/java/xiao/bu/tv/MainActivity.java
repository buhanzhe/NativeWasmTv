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
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.CpuUsageInfo;
import android.os.Handler;
import android.os.HardwarePropertiesManager;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.Log;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.Gravity;
import android.view.PixelCopy;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.AdapterView;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import tv.danmaku.ijk.media.player.IMediaPlayer;
import tv.danmaku.ijk.media.player.IjkMediaMeta;
import tv.danmaku.ijk.media.player.IjkMediaPlayer;
import tv.danmaku.ijk.media.player.IjkTimedText;
import tv.danmaku.ijk.media.player.misc.ITrackInfo;

public final class MainActivity extends Activity {
    private static final String TAG = "MainActivity";
    static final String PREFERENCES = "tv_player";
    // The next catalog changes group/channel ordering substantially. Use a fresh set
    // of keys so indices and snapshots written by the previous catalog are ignored
    // exactly once; all later launches restore the new name-based snapshot.
    private static final String LAST_GROUP_INDEX = "last_group_index_v2";
    private static final String LAST_CHANNEL_INDEX = "last_channel_index_v2";
    private static final String LAST_CHANNEL_SNAPSHOT = "last_channel_snapshot_v2";
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
    private static final String RTSP_TRANSPORT = "rtsp_transport";
    private static final String RTSP_TRANSPORT_TCP = "tcp";
    private static final String RTSP_TRANSPORT_UDP = "udp";
    private static final String VIDEO_SCALE_MODE = "video_scale_mode";
    private static final String VIDEO_SCALE_FIT = "fit";
    private static final String VIDEO_SCALE_STRETCH = "stretch";
    private static final String UI_SCALE_MODE = "ui_scale_mode";
    private static final String UI_SCALE_AUTO = "auto";
    private static final String UI_SCALE_STANDARD = "standard";
    private static final String UI_SCALE_LARGE = "large";
    private static final String UI_SCALE_EXTRA_LARGE = "extra_large";
    private static final String UI_SCALE_EXTRA_EXTRA_LARGE = "extra_extra_large";
    private static final String RESOLUTION_MODE = "resolution_mode";
    private static final String RESOLUTION_MODE_HIGH = "high";
    private static final String RESOLUTION_MODE_MEDIUM = "medium";
    private static final String RESOLUTION_MODE_LOW = "low";
    private static final String WEB_VIEW_RESOLUTION = "web_view_resolution";
    private static final String WEB_VIEW_RESOLUTION_1080P = "1080p";
    private static final String WEB_VIEW_RESOLUTION_720P = "720p";
    private static final String WEB_VIEW_RESOLUTION_2K = "2k";
    private static final String WEB_VIEW_RESOLUTION_4K = "4k";
    private static final String WEB_VIEW_PAGE_SCALE = "web_view_page_scale";
    private static final String WEB_VIEW_LOAD_IMAGES = "web_view_load_images";
    private static final String WEB_VIEW_AUTO_PLAY_SNIFFED =
            "web_view_auto_play_sniffed";
    private static final String WEB_VIEW_USER_AGENT = "web_view_user_agent";
    private static final String WEB_VIEW_USER_AGENT_WINDOWS = "windows";
    private static final String WEB_VIEW_USER_AGENT_MACOS = "macos";
    private static final String WEB_VIEW_USER_AGENT_IPAD = "ipad";
    private static final String WEB_VIEW_USER_AGENT_NATIVE = "native";
    private static final String WEB_CAST_RESOLUTION = "web_cast_resolution";
    private static final String WEB_CAST_RESOLUTION_720P = "1280x720";
    private static final String WEB_CAST_RESOLUTION_1080P = "1920x1080";
    private static final String WEB_CAST_RESOLUTION_2K = "2560x1440";
    private static final String WEB_CAST_RESOLUTION_4K = "3840x2160";
    private static final String WEB_CAST_FPS = "web_cast_fps";
    private static final String WEB_CAST_CODEC = "web_cast_codec";
    private static final String WEB_CAST_BITRATE = "web_cast_bitrate_mbps";
    private static final String WEB_CAST_AUDIO = "web_cast_audio";
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
    private static final String DATE_TIME_ONLY = "time_only";
    private static final String EPG_URL = "epg_url";
    private static final String SHOW_DEBUG_INFO = "show_debug_info";
    private static final String SHOW_NETWORK_SPEED = "show_network_speed";
    private static final String SHOW_DATE = "show_date";
    private static final String FLY_MOUSE_ENABLED = "fly_mouse_enabled";
    private static final String AUTO_SWITCH_SOURCE = "auto_switch_source";
    private static final String AUTO_UPDATE_CHANNEL_LIST = "auto_update_channel_list";
    private static final String REMOTE_CATALOG_URL = "remote_catalog_url";
    private static final String LAST_TAKEOVER_RECEIVER_URL = "last_takeover_receiver_url";
    private static final String RECENT_TAKEOVER_RECEIVER_URLS =
            "recent_takeover_receiver_urls_v1";
    private static final int MAX_RECENT_TAKEOVER_RECEIVERS = 10;
    private static final String LIVE_DELAY_MODE = "live_delay_mode";
    private static final String LIVE_DELAY_LOW = "low";
    private static final String LIVE_DELAY_BALANCED = "balanced";
    private static final String LIVE_DELAY_STABLE = "stable";
    private static final String SUBTITLE_SIZE_PERCENT = "subtitle_size_percent";
    private static final String SUBTITLE_POSITION = "subtitle_position";
    private static final String SUBTITLE_POSITION_MANUAL = "manual";
    private static final String SUBTITLE_OFFSET_PERCENT = "subtitle_offset_percent";
    private static final String SUBTITLE_POSITION_TOP = "top";
    private static final String SUBTITLE_POSITION_CENTER = "center";
    private static final String SUBTITLE_POSITION_BOTTOM = "bottom";
    private static final String SUBTITLE_SHADOW = "subtitle_shadow";
    private static final String SUBTITLE_SHADOW_NONE = "none";
    private static final String SUBTITLE_SHADOW_STANDARD = "standard";
    private static final String SUBTITLE_SHADOW_STRONG = "strong";
    private static final String MEDIA_TRACK_DISABLED = MediaTrackSelection.DISABLED;
    private static final String GITHUB_URL = "https://github.com/buhanzhe/NativeWasmTv";
    private static final String FIRST_LAUNCH_GROUP_TITLE = "央视频道";
    private static final String FIRST_LAUNCH_CHANNEL_NUMBER = "1";
    private static final String FIRST_LAUNCH_CHANNEL_PID = "600001859";
    private static final long CHANNEL_BAR_TIMEOUT_MS = 3000L;
    private static final long CHANNEL_SWITCH_DEBOUNCE_MS = 250L;
    private static final long PANEL_TIMEOUT_MS = 5000L;
    private static final long BACK_PROMPT_TIMEOUT_MS = 5000L;
    private static final long EXIT_CONFIRM_TIMEOUT_MS = BACK_PROMPT_TIMEOUT_MS;
    private static final long WEB_FORCE_CLOSE_WINDOW_MS = 2000L;
    private static final long CHANNEL_PREFETCH_DELAY_MS = 1500L;
    private static final long PLAYBACK_BUFFERING_RECOVERY_MS = 10000L;
    private static final long PLAYBACK_STALL_RECOVERY_MS = 10000L;
    private static final long NTV_CAST_STALL_RECOVERY_MS = 5000L;
    private static final long TAKEOVER_SESSION_TIMEOUT_MS = 15000L;
    private static final long PLAYBACK_RECOVERY_HEALTHY_RESET_MS = 30000L;
    private static final int PLAYBACK_RECOVERY_MAX_ATTEMPTS = 5;
    private static final long CUSTOM_SOURCE_TIMEOUT_MS = 5000L;
    private static final long CARRIER_IPTV_SOURCE_TIMEOUT_MS = 12000L;
    private static final long NUMERIC_CHANNEL_TIMEOUT_MS = 1200L;
    private static final int LOCAL_PLAYLIST_PERMISSION_REQUEST = 4201;
    private static final int CAST_AUDIO_PERMISSION_REQUEST = 4202;
    private static final int CAST_MEDIA_PROJECTION_REQUEST = 4203;
    private static final int TAKEOVER_MANAGEMENT_REQUEST = 4204;
    private static final int CAST_LOCAL_NETWORK_PERMISSION_REQUEST = 4205;
    private static final int WIFI_DIRECT_PERMISSION_REQUEST = 4206;
    private static final int ANDROID_17_API = 37;
    private static final String ACCESS_LOCAL_NETWORK_PERMISSION =
            "android.permission.ACCESS_LOCAL_NETWORK";
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
            if (keepChannelListVisibleOnWebExit) {
                return;
            }
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
            webRapidBackStartedAt = 0L;
            webBackPressCount = 0;
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
    private LoadingSpinnerView channelProgress;
    private TextView videoClock;
    private TextView videoDate;
    private TextView debugInfoOverlay;
    private float debugInfoTextSizePx = 12f;
    private final VideoScreenshot videoScreenshot = new VideoScreenshot();
    private TextView networkSpeedOverlay;
    private TextView channelName;
    private TextView statusText;
    private TextView channelEpg;
    private TextView videoInfo;
    private TextView numericChannelOverlay;
    private TextView subtitleText;
    private TextView managementUrl;
    private ListView groupList;
    private ListView channelList;
    private ListView epgList;
    private View epgColumn;
    private View epgDivider;
    private TextView epgStatus;
    private ChannelListAdapter groupAdapter;
    private ChannelListAdapter channelAdapter;
    private EpgListAdapter epgAdapter;
    private boolean channelPanelInitialized;
    private EpgManager epgManager;
    private LiveUrlResolver liveUrlResolver;
    private YangshipinWebResolver yangshipinResolver;
    private Ku9ScriptResolver ku9ScriptResolver;
    private DirectVideoView videoView;
    private Drawable castRootBackground;
    private View channelSwitchBlackout;
    private ImageView channelSwipeSnapshot;
    private WebSourceView webSourceView;
    private FlyMouseCursorView flyMouseCursor;
    private boolean flyMouseButtonDown;
    private boolean flyMouseCancelling;
    private long flyMouseButtonDownTime;
    private long flyMouseButtonLastEventTime;
    private final Object flyMouseMoveLock = new Object();
    private float pendingFlyMouseDx;
    private float pendingFlyMouseDy;
    private boolean flyMouseMovePosted;
    private final Runnable applyPendingFlyMouseMove = new Runnable() {
        @Override
        public void run() {
            float dx;
            float dy;
            synchronized (flyMouseMoveLock) {
                dx = pendingFlyMouseDx;
                dy = pendingFlyMouseDy;
                pendingFlyMouseDx = 0f;
                pendingFlyMouseDy = 0f;
                flyMouseMovePosted = false;
            }
            if (!isFlyMouseInteractionEnabled() || flyMouseCursor == null
                    || dx == 0f && dy == 0f) {
                return;
            }
            flyMouseCursor.moveBy(dx, dy);
            if (flyMouseButtonDown) flyMouseButtonLastEventTime = SystemClock.uptimeMillis();
            dispatchFlyMouseMotionEvent(flyMouseButtonDown ? MotionEvent.ACTION_MOVE
                    : MotionEvent.ACTION_HOVER_MOVE, SystemClock.uptimeMillis());
            ensureFlyMouseOnTop();
        }
    };
    private static final long FLY_MOUSE_BUTTON_STALE_TIMEOUT_MS = 15000L;
    private final Runnable flyMouseButtonWatchdog = new Runnable() {
        @Override
        public void run() {
            if (!flyMouseButtonDown || root == null) {
                return;
            }
            long elapsed = SystemClock.uptimeMillis() - flyMouseButtonLastEventTime;
            if (elapsed < FLY_MOUSE_BUTTON_STALE_TIMEOUT_MS) {
                root.postDelayed(this, FLY_MOUSE_BUTTON_STALE_TIMEOUT_MS - elapsed);
                return;
            }
            dispatchFlyMouseButtonUp(true);
        }
    };
    private final Runnable receiverTakeoverWatchdog = new Runnable() {
        @Override
        public void run() {
            if (root == null || isFinishing()) {
                return;
            }
            String hostUrl = remoteCatalogUrl;
            if (hostUrl.length() > 0) {
                long silentFor = SystemClock.elapsedRealtime()
                        - lastRemoteTakeoverMessageAt;
                if (lastRemoteTakeoverMessageAt > 0L
                        && silentFor >= TAKEOVER_SESSION_TIMEOUT_MS) {
                    exitRemoteCatalogTakeover("接管端已断开，已恢复本机频道");
                    return;
                }
                long remaining = lastRemoteTakeoverMessageAt <= 0L
                        ? TAKEOVER_SESSION_TIMEOUT_MS
                        : TAKEOVER_SESSION_TIMEOUT_MS - silentFor;
                root.postDelayed(this, Math.max(250L, remaining));
            }
        }
    };

    private void scheduleReceiverTakeoverWatchdog() {
        if (root == null) {
            return;
        }
        root.removeCallbacks(receiverTakeoverWatchdog);
        if (remoteCatalogUrl.length() > 0) {
            root.postDelayed(receiverTakeoverWatchdog, TAKEOVER_SESSION_TIMEOUT_MS);
        }
    }
    private SurfaceHolder videoSurfaceHolder;
    private boolean castSurfaceRestartPending;
    private HlsProxyServer proxy;
    private boolean proxyStatefulCmgSource;
    private boolean lowResourceDevice;
    private IjkMediaPlayer player;
    private boolean prepared;
    private boolean videoRenderingStarted;
    private boolean activeSoftwareDecode;
    private boolean autoSoftwareDecode;
    private Channel activePlayerChannel;
    private volatile String activePlayerStreamUrl;
    private Channel remoteGatewayChannel;
    private String remoteGatewayStreamUrl;
    private int remoteGatewaySourceIndex = -1;
    private volatile boolean nextPlaybackRequestedByReceiver;
    private volatile int remoteReceiverRequestId = -1;
    private CastConfig remoteReceiverCastConfig;
    private volatile String remoteReceiverControlUrl = "";
    private volatile String lastTakeoverReceiverUrl = "";
    private final ArrayList<String> recentTakeoverReceiverUrls = new ArrayList<String>();
    private volatile String castEdgeReceiverUrl = "";
    private volatile boolean castEdgeBusy;
    private boolean castEdgeTouchTracking;
    private volatile boolean takeoverProgressActive;
    private volatile String takeoverProgressTitle = "";
    private volatile String takeoverProgressDetail = "";
    private volatile int takeoverProgressPercent;
    private volatile boolean castDiscoveryRunning;
    private boolean castDiscoveryRetryPosted;
    private volatile long lastCastDiscoveryAt;
    private int castDiscoveryGeneration;
    private volatile boolean remoteWebViewCastActive;
    private volatile Channel remoteWebViewCastChannel;
    private volatile int remoteWebViewCastSourceIndex = -1;
    private String directHttpMediaUrl;
    private boolean activeEmbeddedCctvResolver;
    private boolean activeEmbeddedYangshipinResolver;
    private String webStreamHeaders;
    private HlsMediaTracks.Manifest mediaTrackManifest;
    private HlsSubtitlePlayer hlsSubtitlePlayer;
    private int selectedHlsSubtitle = -1;
    private IjkMediaPlayer trackResumePlayer;
    private long trackResumePosition;
    private boolean trackResumePlaying;
    private int mediaTrackChangeGeneration;
    private boolean playingDiscoveredWebStream;
    private final LinkedHashMap<String, SniffedResource> sniffedResources =
            new LinkedHashMap<String, SniffedResource>();
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
    private int playbackReadyRequestId = -1;
    private int browsingGroupIndex;
    private int pendingRelativeGroupIndex = -1;
    private int pendingRelativeChannelIndex = -1;
    private int videoWidth;
    private int videoHeight;
    private int videoSarNum = 1;
    private int videoSarDen = 1;
    private long lastBackPressedAt;
    private long lastWebBackPressedAt;
    private long webRapidBackStartedAt;
    private int webBackPressCount;
    private boolean webClosePrompt;
    private long bufferingStartedAt;
    private long lastPlaybackProgressAt;
    private long lastVideoOutputAt;
    private long lastPlaybackPosition = -1L;
    private long estimatedVideoBitrate = -1L;
    private long estimatedAudioBitrate = -1L;
    private final MediaBitrateEstimator playerTransportBitrate =
            new MediaBitrateEstimator();
    private IjkMediaPlayer sampledBitratePlayer;
    private IjkMediaPlayer sampledMetadataPlayer;
    private PlaybackDebugStats cachedIjkMetadata;
    private long measuredTransportBytesPerSecond = -1L;
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
    private int playbackRecoveryAttempts;
    private int playbackRecoverySourcesTried;
    private long lastPlaybackRecoveryAt;
    private String playbackRecoveryTarget = "";
    private AutoUpdater autoUpdater;
    private SystemInfoProvider systemInfoProvider;
    private QrCodeView managementQr;
    private View managementPanel;
    private View backPrompt;
    private TextView backPromptText;
    private Button backPromptOk;
    private View receiverTakeoverOverlay;
    private PlaylistManager playlistManager;
    private final RemoteCatalogClient remoteCatalogClient = new RemoteCatalogClient();
    private LocalControlServer controlServer;
    private CastDeviceDiscovery castDeviceDiscovery;
    private WifiDirectCoordinator wifiDirectCoordinator;
    private boolean wifiDirectPermissionRequestInFlight;
    private boolean wifiDirectPermissionDenied;
    private volatile boolean wifiDirectActive;
    private final LinkedHashMap<String, LocalControlServer.Resource> controlPageCache =
            new LinkedHashMap<String, LocalControlServer.Resource>();
    private WebViewCastManager webViewCastManager;
    private boolean stoppingBackgroundCast;
    private CastConfig pendingCastConfig;
    private MediaProjection castAudioProjection;
    private int pendingRemoteAudioRequestId = -1;
    private boolean castAudioPermissionDeclined;
    private MediaProjectionManager mediaProjectionManager;
    private final Object pendingTakeoverLock = new Object();
    private String pendingTakeoverReceiverUrl = "";
    private boolean pendingTakeoverStayOnContent;
    private boolean pendingTakeoverWifiDirectEligible;
    private boolean localNetworkPermissionRequestInFlight;
    private boolean localNetworkPermissionDenied;
    private boolean pendingOpenManagementAfterLocalNetwork;
    private boolean pendingStandaloneCastAfterLocalNetwork;
    private volatile boolean reverseUpDown;
    private volatile boolean autoStart;
    private volatile String decodeMode;
    private volatile String hardwareDecoder;
    private volatile Set<String> cachedHardwareDecoderNames;
    private volatile String surfaceMode;
    private volatile String rtspTransport;
    private volatile boolean h264SpsCompatibility;
    private volatile String videoScaleMode;
    private volatile String uiScaleMode = UI_SCALE_AUTO;
    private volatile String resolutionMode;
    private volatile String webViewResolution;
    private volatile float webViewPageScale = 1f;
    private volatile boolean webViewLoadImages;
    private volatile boolean webViewAutoPlaySniffed;
    private volatile String webViewUserAgent;
    private volatile String webCastResolution = WEB_CAST_RESOLUTION_720P;
    private volatile int webCastFps = 25;
    private volatile String webCastCodec = CastConfig.CODEC_H264;
    private volatile int webCastBitrateMbps = 3;
    private volatile boolean webCastAudio;
    private volatile String clockLocation;
    private volatile boolean showDebugInfo;
    private volatile boolean showNetworkSpeed;
    private volatile boolean showDateTime;
    private volatile String dateTimeFormat;
    private volatile String epgUrl;
    private volatile boolean flyMouseEnabled;
    private volatile boolean autoSwitchSource;
    private volatile boolean autoUpdateChannelList;
    private volatile String remoteCatalogUrl = "";
    private volatile String remoteTakeoverSessionId = "";
    private volatile long lastRemoteTakeoverMessageAt;
    private volatile long takeoverNetworkDelayMs = -1L;
    private volatile long remoteNetworkDelayMs = -1L;
    private volatile long remoteEncodeDelayMs = -1L;
    private volatile long remoteVideoQueueDelayMs = -1L;
    private volatile long remoteVideoSendDelayMs = -1L;
    private volatile int remoteCatalogGeneration = -1;
    private volatile int appliedRemoteCatalogGeneration = -1;
    private volatile TakeoverChannelSelection pendingTakeoverChannelSelection;
    private volatile LastChannelSnapshot receiverChannelBeforeTakeover;
    private volatile boolean restoreReceiverChannelPending;
    private volatile int catalogGeneration;
    private final AtomicInteger catalogLoadGeneration = new AtomicInteger();
    private volatile String liveDelayMode;
    private volatile int subtitleSizePercent = 100;
    private volatile String subtitlePosition = SUBTITLE_POSITION_BOTTOM;
    private volatile int subtitleOffsetPercent = SubtitlePlacement.DEFAULT_PERCENT;
    private volatile String subtitleShadow = SUBTITLE_SHADOW_STANDARD;
    private volatile float playbackSpeed = 1f;
    private int clockViewportWidth;
    private int clockViewportHeight;
    private int uiScaleViewportWidth;
    private int uiScaleViewportHeight;
    private float effectiveUiScale = 1f;
    private float detectedDisplayInches = -1f;
    private final UiScaleHelper uiScaleHelper = new UiScaleHelper();
    private boolean remoteInputMode;
    private String numericChannelInput = "";
    private boolean playbackGestureTracking;
    private boolean loadingActive;
    private final LinkedHashSet<String> favoriteChannelKeys =
            new LinkedHashSet<String>();
    private final Paint columnMeasurePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private boolean favoriteActionFocused;
    private boolean suppressChannelItemClick;
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
    private boolean keepChannelListVisibleOnWebExit;
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

    private static final class SniffedResource {
        final int requestId;
        final String url;
        final String pageUrl;
        final String userAgent;
        final String cookies;

        SniffedResource(int requestId, String url, String pageUrl,
                String userAgent, String cookies) {
            this.requestId = requestId;
            this.url = url;
            this.pageUrl = pageUrl;
            this.userAgent = userAgent;
            this.cookies = cookies;
        }
    }

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
            resetPlaybackLayerImmediately();
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
            if (groupIndex < 0 || groupIndex >= ChannelCatalog.GROUPS.length
                    || channelIndex < 0) {
                return;
            }
            Channel[] channels = ChannelCatalog.GROUPS[groupIndex].channels;
            if (channels == null || channels.length == 0) {
                Log.w(TAG, "Ignoring channel switch because group is empty index="
                        + groupIndex);
                return;
            }
            channelIndex = ChannelCatalog.wrapIndex(channels, channelIndex);
            if (groupIndex == currentGroupIndex && channelIndex == currentChannelIndex) {
                // An immediate UP/DOWN or DOWN/UP pair has returned to the active
                // channel. Avoid tearing down and recreating the decoder/proxy.
                abortChannelSwitchAnimation();
                showChannelBar(channels[channelIndex].name,
                        prepared ? "直播播放中" : "正在准备直播");
                return;
            }
            currentGroupIndex = groupIndex;
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
        CastKeepAliveService.attach(this);
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
        channelProgress = (LoadingSpinnerView) findViewById(R.id.channel_progress);
        videoClock = (TextView) findViewById(R.id.video_clock);
        videoDate = (TextView) findViewById(R.id.video_date);
        debugInfoOverlay = (TextView) findViewById(R.id.debug_info_overlay);
        networkSpeedOverlay = (TextView) findViewById(R.id.network_speed_overlay);
        channelName = (TextView) findViewById(R.id.channel_name);
        statusText = (TextView) findViewById(R.id.status_text);
        channelEpg = (TextView) findViewById(R.id.channel_epg);
        videoInfo = (TextView) findViewById(R.id.video_info);
        numericChannelOverlay = (TextView) findViewById(R.id.numeric_channel_overlay);
        subtitleText = (TextView) findViewById(R.id.subtitle_text);
        View.OnLayoutChangeListener subtitleLayoutListener = new View.OnLayoutChangeListener() {
            @Override
            public void onLayoutChange(View view, int left, int top, int right, int bottom,
                    int oldLeft, int oldTop, int oldRight, int oldBottom) {
                applySubtitleManualOffset();
            }
        };
        subtitleText.addOnLayoutChangeListener(subtitleLayoutListener);
        ((View) subtitleText.getParent()).addOnLayoutChangeListener(subtitleLayoutListener);
        webSourceView = (WebSourceView) findViewById(R.id.web_source);
        webViewCastManager = new WebViewCastManager(this);
        systemInfoProvider = new SystemInfoProvider(this);
        wifiDirectCoordinator = new WifiDirectCoordinator(this,
                new WifiDirectCoordinator.PermissionDelegate() {
                    @Override public void requestWifiDirectPermission() {
                        MainActivity.this.requestWifiDirectPermission();
                    }
                });
        flyMouseCursor = (FlyMouseCursorView) findViewById(R.id.fly_mouse_cursor);
        flyMouseCursor.setCursorVisibilityListener(
                new FlyMouseCursorView.CursorVisibilityListener() {
                    @Override public void onCursorVisibilityChanged(boolean visible) {
                        if (webSourceView != null) {
                            webSourceView.setCastPointerVisible(visible);
                        }
                    }
                });
        managementUrl = (TextView) findViewById(R.id.management_url);
        managementQr = (QrCodeView) findViewById(R.id.management_qr);
        managementPanel = findViewById(R.id.management_panel);
        backPrompt = findViewById(R.id.back_navigation_prompt);
        backPromptText = (TextView) findViewById(R.id.back_prompt_text);
        backPromptOk = (Button) findViewById(R.id.back_prompt_ok);
        receiverTakeoverOverlay = getLayoutInflater().inflate(
                R.layout.receiver_takeover_overlay, null);
        addContentView(receiverTakeoverOverlay, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        receiverTakeoverOverlay.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                openManagementPage();
            }
        });
        root.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
            @Override
            public void onLayoutChange(View view, int left, int top, int right, int bottom,
                    int oldLeft, int oldTop, int oldRight, int oldBottom) {
                int width = right - left;
                int height = bottom - top;
                refreshUiScaleForViewport(width, height, false);
                if (width != clockViewportWidth || height != clockViewportHeight) {
                    configureVideoClockForViewport(width, height);
                }
            }
        });
        groupList = (ListView) findViewById(R.id.channel_group_list);
        channelList = (ListView) findViewById(R.id.channel_list);
        epgList = (ListView) findViewById(R.id.epg_list);
        epgColumn = findViewById(R.id.epg_column);
        epgDivider = findViewById(R.id.channel_epg_divider);
        epgStatus = (TextView) findViewById(R.id.epg_status);
        groupAdapter = new ChannelListAdapter(this, uiScaleHelper);
        channelAdapter = new ChannelListAdapter(this, uiScaleHelper);
        channelAdapter.setFavoriteListener(new ChannelListAdapter.FavoriteListener() {
            @Override
            public boolean isFavorite(int position) {
                return isBrowsingChannelFavorite(position);
            }

            @Override
            public void onFavoriteClick(int position) {
                suppressChannelItemClick = true;
                channelListPanel.removeCallbacks(hideChannelList);
                channelList.setSelection(position);
                favoriteActionFocused = true;
                updateFavoriteButton();
                toggleBrowsingChannelFavorite(position);
                channelListPanel.bringToFront();
                channelList.post(new Runnable() {
                    @Override
                    public void run() {
                        suppressChannelItemClick = false;
                        scheduleChannelListDismiss();
                    }
                });
            }
        });
        epgAdapter = new EpgListAdapter(this, uiScaleHelper);
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
        rtspTransport = sanitizeRtspTransport(preferences.getString(
                RTSP_TRANSPORT, RTSP_TRANSPORT_TCP));
        h264SpsCompatibility = preferences.getBoolean(H264_SPS_COMPATIBILITY, true);
        videoScaleMode = sanitizeVideoScaleMode(
                preferences.getString(VIDEO_SCALE_MODE, VIDEO_SCALE_FIT));
        uiScaleMode = sanitizeUiScaleMode(
                preferences.getString(UI_SCALE_MODE, UI_SCALE_AUTO));
        resolutionMode = sanitizeResolutionMode(
                preferences.getString(RESOLUTION_MODE, RESOLUTION_MODE_HIGH));
        String storedWebViewResolution = preferences.getString(
                WEB_VIEW_RESOLUTION, WEB_VIEW_RESOLUTION_720P);
        webViewResolution = sanitizeWebViewResolution(storedWebViewResolution);
        if (!webViewResolution.equals(storedWebViewResolution)) {
            preferences.edit().putString(WEB_VIEW_RESOLUTION, webViewResolution).apply();
        }
        webViewLoadImages = preferences.getBoolean(WEB_VIEW_LOAD_IMAGES, true);
        webViewPageScale = sanitizeWebViewPageScale(
                preferences.getFloat(WEB_VIEW_PAGE_SCALE, 1f));
        webViewAutoPlaySniffed = preferences.getBoolean(
                WEB_VIEW_AUTO_PLAY_SNIFFED, true);
        webViewUserAgent = sanitizeWebViewUserAgent(preferences.getString(
                WEB_VIEW_USER_AGENT, WEB_VIEW_USER_AGENT_WINDOWS));
        webCastResolution = sanitizeWebCastResolution(preferences.getString(
                WEB_CAST_RESOLUTION, WEB_CAST_RESOLUTION_720P));
        webCastFps = sanitizeWebCastFps(preferences.getInt(WEB_CAST_FPS, 25));
        webCastCodec = sanitizeWebCastCodec(preferences.getString(
                WEB_CAST_CODEC, CastConfig.CODEC_H264));
        webCastBitrateMbps = sanitizeWebCastBitrate(
                preferences.getInt(WEB_CAST_BITRATE, 3));
        webCastAudio = preferences.getBoolean(WEB_CAST_AUDIO, false);
        webSourceView.applyConfiguration(webViewResolution, webViewLoadImages,
                webViewUserAgent, webViewPageScale);
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
        autoSwitchSource = preferences.getBoolean(AUTO_SWITCH_SOURCE, false);
        autoUpdateChannelList = preferences.getBoolean(AUTO_UPDATE_CHANNEL_LIST, false);
        try {
            remoteCatalogUrl = RemoteCatalogClient.normalizeServerUrl(
                    preferences.getString(REMOTE_CATALOG_URL, ""));
        } catch (IOException ignored) {
            remoteCatalogUrl = "";
            preferences.edit().remove(REMOTE_CATALOG_URL).apply();
        }
        loadRecentTakeoverReceivers(preferences);
        if (remoteCatalogUrl.length() > 0) {
            lastRemoteTakeoverMessageAt = SystemClock.elapsedRealtime();
            scheduleReceiverTakeoverWatchdog();
        }
        liveDelayMode = sanitizeLiveDelayMode(
                preferences.getString(LIVE_DELAY_MODE, LIVE_DELAY_STABLE));
        subtitleSizePercent = sanitizeSubtitleSizePercent(
                preferences.getInt(SUBTITLE_SIZE_PERCENT, 100));
        subtitlePosition = sanitizeSubtitlePosition(preferences.getString(
                SUBTITLE_POSITION, SUBTITLE_POSITION_BOTTOM));
        subtitleOffsetPercent = SubtitlePlacement.clamp(preferences.getInt(
                SUBTITLE_OFFSET_PERCENT, SubtitlePlacement.DEFAULT_PERCENT));
        subtitleShadow = sanitizeSubtitleShadow(preferences.getString(
                SUBTITLE_SHADOW, SUBTITLE_SHADOW_STANDARD));
        applySubtitleStyle();
        refreshUiScaleForViewport(root.getWidth(), root.getHeight(), true);
        remoteInputMode = hasTelevisionUi();
        playlistManager = new PlaylistManager(this);
        loadFavoriteChannels(preferences);
        final LastChannelSnapshot startupSnapshot = loadLastChannelSnapshot(preferences);
        if (remoteCatalogUrl.length() > 0 && startupSnapshot != null) {
            // A receiver can restart while it is still leased by a phone. The
            // persisted snapshot is the television's own channel because remote
            // playback is never allowed to replace it.
            receiverChannelBeforeTakeover = startupSnapshot;
        }
        // Cold start uses Java constants only. Opening/parsing the bundled M3U and
        // reading SQLite are reserved for loadCompleteCatalogInBackground(), after
        // the first playback request is already under way.
        ChannelCatalog.setCustomGroups(ChannelCatalog.startupGroups(
                startupSnapshot == null ? null : startupSnapshot.group));
        refreshFavoriteCatalog();
        requestLocalPlaylistPermissionIfNeeded();
        epgManager = new EpgManager(this);
        liveUrlResolver = new LiveUrlResolver(getSharedPreferences("live_url_resolver", MODE_PRIVATE));
        yangshipinResolver = new YangshipinWebResolver(this, (FrameLayout) root,
                getIntent().getBooleanExtra("cmg_keep_web_trace", false));
        ku9ScriptResolver = new Ku9ScriptResolver(this, (FrameLayout) root);
        if (lowResourceDevice) {
            root.postDelayed(new Runnable() {
                @Override
                public void run() {
                    if (!isFinishing()) {
                        ensureChannelPanelInitialized();
                    }
                }
            }, 650L);
        } else {
            ensureChannelPanelInitialized();
        }

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
                Log.i(TAG, "Physical video surface created size=" + videoView.getWidth()
                        + "x" + videoView.getHeight() + " sdk=" + Build.VERSION.SDK_INT);
                if (castSurfaceRestartPending) {
                    castSurfaceRestartPending = false;
                    startChannel(currentChannelIndex);
                    return;
                }
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
                Log.i(TAG, "Physical video surface destroyed sdk=" + Build.VERSION.SDK_INT);
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
                    preferences.getInt(LAST_GROUP_INDEX,
                            ChannelCatalog.firstPlayableGroupIndex()));
            if (currentGroup().channels.length == 0) {
                currentGroupIndex = ChannelCatalog.firstPlayableGroupIndex();
            }
            currentChannelIndex = ChannelCatalog.wrapIndex(currentGroup().channels,
                    preferences.getInt(LAST_CHANNEL_INDEX, 0));
        } else {
            selectFirstLaunchChannel();
        }
        browsingGroupIndex = currentGroupIndex;
        if (!lowResourceDevice) {
            showChannelMenu(currentGroupIndex);
        } else {
            // Rendering three populated columns before the first video frame is
            // disproportionately expensive on Android 4.x. The list is populated
            // normally as soon as the user opens it with OK/tap.
            channelListPanel.setVisibility(View.GONE);
        }

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
        if (lowResourceDevice) {
            // The control server and EPG do not participate in native playback.
            // Starting both during onCreate delayed the first window by about 70 ms
            // on API 19 hardware, so run them after the first frame and after the
            // catalog task below has had time to finish.
            root.postDelayed(new Runnable() {
                @Override
                public void run() {
                    if (!isFinishing()) {
                        startManagementServer();
                        refreshEpg();
                    }
                }
            }, 900L);
        } else {
            startManagementServer();
            refreshEpg();
        }
        if (lowResourceDevice) {
            // Do not let the full SQLite catalog load and its UI merge compete with
            // the first window frame on Android 4.x / low-memory hardware. The tiny
            // last-channel snapshot above is already sufficient to start playback.
            root.postDelayed(new Runnable() {
                @Override
                public void run() {
                    if (!isFinishing()) {
                        loadCompleteCatalogInBackground();
                    }
                }
            }, 700L);
        } else {
            loadCompleteCatalogInBackground();
        }
    }

    private boolean hasTelevisionUi() {
        UiModeManager manager = (UiModeManager) getSystemService(UI_MODE_SERVICE);
        return (manager != null && manager.getCurrentModeType()
                == Configuration.UI_MODE_TYPE_TELEVISION)
                || getPackageManager().hasSystemFeature("android.software.leanback");
    }

    private static final class TakeoverChannelSelection {
        final String sessionId;
        final int groupIndex;
        final int channelIndex;
        final int sourceIndex;
        final String groupName;
        final String channelName;
        final String channelEpgId;

        TakeoverChannelSelection(String sessionId, JSONObject state) {
            this.sessionId = sessionId;
            groupIndex = state.optInt("group", -1);
            channelIndex = state.optInt("channel", -1);
            sourceIndex = Math.max(0, state.optInt("source", 0));
            groupName = state.optString("groupName", "").trim();
            channelName = state.optString("channelName", "").trim();
            channelEpgId = state.optString("channelEpgId", "").trim();
        }
    }

    private LastChannelSnapshot snapshotCurrentReceiverChannel() {
        ChannelCatalog.Group[] groups = ChannelCatalog.GROUPS;
        if (groups == null || currentGroupIndex < 0
                || currentGroupIndex >= groups.length) {
            return null;
        }
        ChannelCatalog.Group group = groups[currentGroupIndex];
        if (group == null || group.channels == null || group.channels.length == 0) {
            return null;
        }
        int channelIndex = ChannelCatalog.wrapIndex(group.channels, currentChannelIndex);
        Channel channel = group.channels[channelIndex];
        String groupTitle = group.title;
        int source = catalogSource(group, channel);
        if (group.source == ChannelCatalog.SOURCE_FAVORITES
                && channel.favoriteKey != null) {
            int separator = channel.favoriteKey.indexOf('\u001f');
            if (separator > 0) {
                groupTitle = channel.favoriteKey.substring(0, separator);
            }
        }
        return new LastChannelSnapshot(new ChannelCatalog.Group(
                groupTitle, source, new Channel[] { channel }), currentSourceIndex);
    }

    private void rememberReceiverChannelBeforeTakeover() {
        if (receiverChannelBeforeTakeover == null) {
            receiverChannelBeforeTakeover = snapshotCurrentReceiverChannel();
        }
    }

    private boolean shouldFreezeReceiverChannelHistory() {
        return remoteCatalogUrl.length() > 0 || restoreReceiverChannelPending;
    }

    private boolean isTelevisionDevice() {
        if (hasTelevisionUi()) {
            return true;
        }
        String identity = (Build.MODEL + " " + Build.DEVICE + " " + Build.PRODUCT)
                .toLowerCase(java.util.Locale.US);
        return identity.contains("tv");
    }

    private void ensureChannelPanelInitialized() {
        if (channelPanelInitialized) {
            return;
        }
        channelPanelInitialized = true;
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
                if (!suppressChannelItemClick) {
                    switchBrowsingChannel(position);
                }
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
    }

    private void configureWebSourceView() {
        webSourceView.setListener(new WebSourceView.Listener() {
            @Override
            public void onPageStarted(int requestId, String url) {
                if (requestId == playRequestId) {
                    clearSniffedResources();
                    clearWebCloseConfirmation();
                    updateLoadingStatus("正在加载网页直播");
                    discoverCastReceiver(false);
                }
            }

            @Override
            public void onPageReady(int requestId, String url, String title) {
                if (requestId != playRequestId || !webSourceView.isPageVisible()) {
                    return;
                }
                Channel channel = currentChannel();
                playbackReadyRequestId = requestId;
                hideLoading();
                revealIncomingChannel(requestId);
                persistPlayingChannel(channel, requestId);
                showChannelBar(channel.name, flyMouseEnabled
                        ? "网页已打开 · 手机飞鼠可操作"
                        : "网页已打开 · 可在管理页开启飞鼠");
                updateCastEdgeState();
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
                Log.i(TAG, "Web source stream discovered channel=" + channel.name
                        + " url=" + streamUrl);
                SniffedResource resource = new SniffedResource(requestId, streamUrl,
                        pageUrl, userAgent, cookies);
                int resourceCount = rememberSniffedResource(resource);
                if (webViewAutoPlaySniffed) {
                    startSniffedResource(resource);
                } else {
                    showChannelBar(channel.name, "已发现 " + resourceCount
                            + " 个可播放资源 · 可在手机飞鼠中选择");
                }
            }

            @Override public void onCastEdgeStart() {
                startCastFromEdge();
            }

            @Override public void onCastEdgePrevious() {
                controlCastFromEdge("previous");
            }

            @Override public void onCastEdgeNext() {
                controlCastFromEdge("next");
            }

            @Override public void onCastEdgeStop() {
                stopCastFromEdge();
            }
        });
    }

    private boolean hasLocalNetworkAccess() {
        return Build.VERSION.SDK_INT < ANDROID_17_API
                || checkSelfPermission(ACCESS_LOCAL_NETWORK_PERMISSION)
                        == PackageManager.PERMISSION_GRANTED;
    }

    private void requestLocalNetworkPermission(boolean openManagementAfterGrant,
            boolean userInitiated) {
        if (hasLocalNetworkAccess()) {
            localNetworkPermissionDenied = false;
            if (openManagementAfterGrant) openManagement();
            else if (hasPendingTakeover()) requestNextTakeoverPermission();
            else if (pendingStandaloneCastAfterLocalNetwork) {
                continueStandaloneCastAfterLocalNetwork();
            }
            else discoverCastReceiver(true);
            return;
        }
        if (openManagementAfterGrant) pendingOpenManagementAfterLocalNetwork = true;
        if (localNetworkPermissionRequestInFlight
                || !userInitiated && localNetworkPermissionDenied) return;
        localNetworkPermissionRequestInFlight = true;
        requestPermissions(new String[] { ACCESS_LOCAL_NETWORK_PERMISSION },
                CAST_LOCAL_NETWORK_PERMISSION_REQUEST);
    }

    private boolean hasPendingTakeover() {
        synchronized (pendingTakeoverLock) {
            return pendingTakeoverReceiverUrl.length() > 0;
        }
    }

    /** Delay every LAN connection until its Android runtime permissions are ready. */
    private boolean queueTakeoverForPermissions(String receiverUrl,
            boolean stayOnContent) {
        boolean wifiDirectEligible = shouldTryWifiDirectForReceiver(receiverUrl);
        boolean wifiDirectPermissionMissing = wifiDirectEligible
                && !wifiDirectPermissionDenied
                && !wifiDirectCoordinator.hasPermission();
        boolean audioPermissionMissing = webCastAudio
                && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                && (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                        != PackageManager.PERMISSION_GRANTED
                    || castAudioProjection == null);
        if (hasLocalNetworkAccess() && !wifiDirectPermissionMissing
                && !audioPermissionMissing) return false;
        synchronized (pendingTakeoverLock) {
            pendingTakeoverReceiverUrl = receiverUrl;
            pendingTakeoverStayOnContent = stayOnContent;
            pendingTakeoverWifiDirectEligible = wifiDirectEligible;
        }
        runOnUiThread(new Runnable() {
            @Override public void run() {
                castEdgeBusy = true;
                updateCastEdgeState();
                requestNextTakeoverPermission();
            }
        });
        return true;
    }

    private void requestNextTakeoverPermission() {
        if (!hasPendingTakeover() || isFinishing()) return;
        if (!hasLocalNetworkAccess()) {
            requestLocalNetworkPermission(false, true);
            return;
        }
        boolean directEligible;
        synchronized (pendingTakeoverLock) {
            directEligible = pendingTakeoverWifiDirectEligible;
        }
        if (directEligible && !wifiDirectPermissionDenied
                && !wifiDirectCoordinator.hasPermission()) {
            requestWifiDirectPermission();
            return;
        }
        if (webCastAudio && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                    != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[] { Manifest.permission.RECORD_AUDIO },
                        CAST_AUDIO_PERMISSION_REQUEST);
                return;
            }
            if (castAudioProjection == null) {
                // Android 14+ requires a mediaProjection foreground service before
                // converting consent data into a MediaProjection token.
                CastKeepAliveService.setActive(this, true);
                requestCastMediaProjection();
                return;
            }
        }
        resumePendingTakeover();
    }

    private void resumePendingTakeover() {
        final String receiverUrl;
        final boolean stayOnContent;
        synchronized (pendingTakeoverLock) {
            receiverUrl = pendingTakeoverReceiverUrl;
            stayOnContent = pendingTakeoverStayOnContent;
            pendingTakeoverReceiverUrl = "";
            pendingTakeoverStayOnContent = false;
            pendingTakeoverWifiDirectEligible = false;
        }
        if (receiverUrl.length() == 0) return;
        new Thread(new Runnable() {
            @Override public void run() {
                Exception failure = null;
                String connectedReceiver = receiverUrl;
                try {
                    String response = handleWebTakeover(new JSONObject()
                            .put("receiverUrl", receiverUrl)
                            .put("stayOnContent", stayOnContent));
                    connectedReceiver = new JSONObject(response)
                            .optString("receiverUrl", receiverUrl);
                } catch (Exception error) {
                    failure = error;
                    setTakeoverProgress(false, "接管失败", safeMessage(error), 100);
                }
                final Exception result = failure;
                final String effectiveReceiver = connectedReceiver;
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        castEdgeBusy = false;
                        if (result == null) {
                            castEdgeReceiverUrl = effectiveReceiver;
                            Toast.makeText(MainActivity.this, wifiDirectActive
                                            ? "已通过 Wi-Fi Direct 连接电视" : "已连接电视",
                                    Toast.LENGTH_SHORT).show();
                        } else {
                            if (!isReceiverTakeoverActive()) releaseCastAudioProjection();
                            Toast.makeText(MainActivity.this,
                                    "连接电视失败：" + safeMessage(result),
                                    Toast.LENGTH_LONG).show();
                        }
                        updateCastEdgeState();
                    }
                });
            }
        }, "cast-permission-resume").start();
    }

    private void cancelPendingTakeover(String message) {
        synchronized (pendingTakeoverLock) {
            pendingTakeoverReceiverUrl = "";
            pendingTakeoverStayOnContent = false;
            pendingTakeoverWifiDirectEligible = false;
        }
        castEdgeBusy = false;
        setTakeoverProgress(false, "接管已取消", message, 100);
        if (!isReceiverTakeoverActive()) {
            releaseCastAudioProjection();
            CastKeepAliveService.setActive(this, false);
        }
        updateCastEdgeState();
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }

    private boolean shouldTryWifiDirect() {
        return wifiDirectCoordinator != null && wifiDirectCoordinator.isSupported()
                && wifiDirectCoordinator.isEnabled()
                && !isTelevisionDevice()
                && "wifi".equals(SystemInfoProvider.activeNetworkTransport(this));
    }

    private boolean shouldTryWifiDirectForReceiver(String receiverUrl) {
        if (!shouldTryWifiDirect() || !hasLocalNetworkAccess()
                || !SystemInfoProvider.isPeerOnActiveWifi(this, receiverUrl)
                || Looper.myLooper() == Looper.getMainLooper()) {
            return false;
        }
        try {
            JSONObject state = remoteCatalogClient.receiverState(receiverUrl);
            String transport = state.optString("networkTransport", "");
            if (transport.length() == 0) {
                JSONObject system = state.optJSONObject("system");
                transport = system == null ? ""
                        : system.optString("networkTransport", "");
            }
            return "wifi".equals(transport);
        } catch (Exception error) {
            // The normal claim path supplies the precise update/connect error.
            return false;
        }
    }

    private void requestWifiDirectPermission() {
        if (wifiDirectCoordinator == null || wifiDirectCoordinator.hasPermission()
                || wifiDirectPermissionRequestInFlight || isFinishing()) {
            if (wifiDirectCoordinator != null && wifiDirectCoordinator.hasPermission()) {
                wifiDirectCoordinator.onPermissionResult(true);
            }
            return;
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            wifiDirectCoordinator.onPermissionResult(true);
            return;
        }
        wifiDirectPermissionRequestInFlight = true;
        requestPermissions(new String[] { Manifest.permission.ACCESS_FINE_LOCATION },
                WIFI_DIRECT_PERMISSION_REQUEST);
    }

    private void continueStandaloneCastAfterLocalNetwork() {
        if (!pendingStandaloneCastAfterLocalNetwork || pendingCastConfig == null) return;
        pendingStandaloneCastAfterLocalNetwork = false;
        final CastConfig requested = pendingCastConfig;
        if (requested.audio && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                    != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[] { Manifest.permission.RECORD_AUDIO },
                        CAST_AUDIO_PERMISSION_REQUEST);
            } else {
                CastKeepAliveService.setActive(this, true);
                requestCastMediaProjection();
            }
            return;
        }
        pendingCastConfig = null;
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    startWebViewCast(requested, null);
                    runOnUiThread(new Runnable() {
                        @Override public void run() {
                            Toast.makeText(MainActivity.this, "电视界面投送已启动",
                                    Toast.LENGTH_SHORT).show();
                        }
                    });
                } catch (final Exception error) {
                    runOnUiThread(new Runnable() {
                        @Override public void run() {
                            Toast.makeText(MainActivity.this,
                                    "电视界面投送失败：" + safeMessage(error),
                                    Toast.LENGTH_LONG).show();
                        }
                    });
                }
            }
        }, "cast-local-network-resume").start();
    }

    private void loadRecentTakeoverReceivers(SharedPreferences preferences) {
        ArrayList<String> loaded = new ArrayList<String>();
        String encoded = preferences.getString(RECENT_TAKEOVER_RECEIVER_URLS, "");
        if (encoded.length() > 0) {
            try {
                JSONArray values = new JSONArray(encoded);
                for (int i = 0; i < values.length()
                        && loaded.size() < MAX_RECENT_TAKEOVER_RECEIVERS; i++) {
                    addUniqueReceiverUrl(loaded, values.optString(i, ""), false);
                }
            } catch (JSONException ignored) {
                // A malformed value is replaced below with the valid legacy entry.
            }
        }
        // Keep the old single-address preference readable so upgrades and temporary
        // downgrades retain the most recently connected television.
        addUniqueReceiverUrl(loaded,
                preferences.getString(LAST_TAKEOVER_RECEIVER_URL, ""), true);
        synchronized (recentTakeoverReceiverUrls) {
            recentTakeoverReceiverUrls.clear();
            recentTakeoverReceiverUrls.addAll(loaded);
            lastTakeoverReceiverUrl = recentTakeoverReceiverUrls.isEmpty()
                    ? "" : recentTakeoverReceiverUrls.get(0);
            persistRecentTakeoverReceiversLocked(preferences);
        }
    }

    private static void addUniqueReceiverUrl(ArrayList<String> receivers,
            String candidate, boolean addFirst) {
        String normalized;
        try {
            normalized = RemoteCatalogClient.normalizeServerUrl(candidate);
        } catch (IOException invalid) {
            return;
        }
        if (normalized.length() == 0) return;
        for (int i = receivers.size() - 1; i >= 0; i--) {
            if (normalized.equalsIgnoreCase(receivers.get(i))) receivers.remove(i);
        }
        if (addFirst) receivers.add(0, normalized);
        else receivers.add(normalized);
        while (receivers.size() > MAX_RECENT_TAKEOVER_RECEIVERS) {
            receivers.remove(receivers.size() - 1);
        }
    }

    private void rememberTakeoverReceiver(String receiverUrl) {
        synchronized (recentTakeoverReceiverUrls) {
            addUniqueReceiverUrl(recentTakeoverReceiverUrls, receiverUrl, true);
            lastTakeoverReceiverUrl = recentTakeoverReceiverUrls.isEmpty()
                    ? "" : recentTakeoverReceiverUrls.get(0);
            persistRecentTakeoverReceiversLocked(
                    getSharedPreferences(PREFERENCES, MODE_PRIVATE));
        }
    }

    private void persistRecentTakeoverReceiversLocked(SharedPreferences preferences) {
        JSONArray values = new JSONArray();
        for (String receiver : recentTakeoverReceiverUrls) values.put(receiver);
        SharedPreferences.Editor editor = preferences.edit()
                .putString(RECENT_TAKEOVER_RECEIVER_URLS, values.toString());
        if (lastTakeoverReceiverUrl.length() > 0) {
            editor.putString(LAST_TAKEOVER_RECEIVER_URL, lastTakeoverReceiverUrl);
        } else {
            editor.remove(LAST_TAKEOVER_RECEIVER_URL);
        }
        editor.apply();
    }

    private ArrayList<String> recentTakeoverReceiversSnapshot() {
        synchronized (recentTakeoverReceiverUrls) {
            return new ArrayList<String>(recentTakeoverReceiverUrls);
        }
    }

    /** Probe recently connected televisions in order, then issue one LAN broadcast. */
    private void discoverCastReceiver(boolean force) {
        if (isFinishing() || webSourceView == null || isTelevisionDevice()
                || remoteCatalogUrl.length() > 0) {
            castEdgeReceiverUrl = "";
            updateCastEdgeState();
            return;
        }
        if (!hasLocalNetworkAccess()) {
            castEdgeReceiverUrl = "";
            updateCastEdgeState();
            requestLocalNetworkPermission(false, false);
            return;
        }
        if (remoteReceiverControlUrl.length() > 0) {
            castEdgeReceiverUrl = remoteReceiverControlUrl;
            updateCastEdgeState();
            return;
        }
        long now = SystemClock.elapsedRealtime();
        if (castDiscoveryRunning || !force && now - lastCastDiscoveryAt < 30000L) {
            updateCastEdgeState();
            return;
        }
        if (controlServer == null || controlServer.getPort() <= 0) {
            if (!castDiscoveryRetryPosted) {
                castDiscoveryRetryPosted = true;
                root.postDelayed(new Runnable() {
                    @Override public void run() {
                        castDiscoveryRetryPosted = false;
                        discoverCastReceiver(false);
                    }
                }, 1200L);
            }
            return;
        }
        castDiscoveryRunning = true;
        lastCastDiscoveryAt = now;
        final int generation = ++castDiscoveryGeneration;
        final ArrayList<String> remembered = recentTakeoverReceiversSnapshot();
        new Thread(new Runnable() {
            @Override public void run() {
                String found = "";
                for (String receiver : remembered) {
                    if (remoteCatalogClient.isTelevisionAvailable(receiver)) {
                        found = receiver;
                        break;
                    }
                }
                if (found.length() == 0) {
                    String discovered = CastDeviceDiscovery.discoverTelevision(700);
                    if (discovered.length() > 0
                            && remoteCatalogClient.isTelevisionAvailable(discovered)) {
                        found = discovered;
                    }
                }
                final String result = found;
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        if (generation != castDiscoveryGeneration || isFinishing()) return;
                        castDiscoveryRunning = false;
                        if (remoteReceiverControlUrl.length() == 0) {
                            castEdgeReceiverUrl = result;
                        }
                        updateCastEdgeState();
                    }
                });
            }
        }, "cast-device-scan").start();
    }

    private void updateCastEdgeState() {
        if (webSourceView == null) return;
        boolean casting = remoteReceiverControlUrl.length() > 0;
        // A phone WebView always offers the shortcut for five seconds. When no
        // receiver has been discovered yet, the shortcut opens the management page.
        boolean available = !isTelevisionDevice();
        String name = "";
        try {
            Channel channel = currentChannel();
            if (channel != null) name = channel.name;
        } catch (RuntimeException ignored) {
        }
        webSourceView.setCastEdgeState(available, casting, castEdgeBusy, name);
    }

    private void startCastFromEdge() {
        if (castEdgeBusy) return;
        if (remoteReceiverControlUrl.length() > 0) {
            updateCastEdgeState();
            return;
        }
        final String receiverUrl = castEdgeReceiverUrl;
        if (receiverUrl.length() == 0) {
            discoverCastReceiver(true);
            openManagementCastPage();
            return;
        }
        castEdgeBusy = true;
        updateCastEdgeState();
        new Thread(new Runnable() {
            @Override public void run() {
                Exception failure = null;
                boolean permissionPending = false;
                String connectedReceiver = receiverUrl;
                try {
                    String response = handleWebTakeover(new JSONObject()
                            .put("receiverUrl", receiverUrl)
                            .put("stayOnContent", true));
                    JSONObject resultState = new JSONObject(response);
                    permissionPending = resultState.optBoolean("pending", false);
                    connectedReceiver = resultState.optString("receiverUrl", receiverUrl);
                } catch (Exception error) {
                    failure = error;
                    setTakeoverProgress(false, "接管失败", safeMessage(error), 100);
                }
                final Exception result = failure;
                final boolean pending = permissionPending;
                final String effectiveReceiver = connectedReceiver;
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        castEdgeBusy = pending;
                        if (pending) {
                            updateCastEdgeState();
                            return;
                        } else if (result == null) {
                            castEdgeReceiverUrl = effectiveReceiver;
                            Toast.makeText(MainActivity.this, wifiDirectActive
                                            ? "已通过 Wi-Fi Direct 连接电视" : "已连接电视",
                                    Toast.LENGTH_SHORT).show();
                        } else {
                            castEdgeReceiverUrl = "";
                            Toast.makeText(MainActivity.this,
                                    "连接电视失败：" + safeMessage(result),
                                    Toast.LENGTH_LONG).show();
                        }
                        updateCastEdgeState();
                        if (result != null) discoverCastReceiver(true);
                    }
                });
            }
        }, "cast-edge-connect").start();
    }

    private void controlCastFromEdge(final String action) {
        if (castEdgeBusy || remoteReceiverControlUrl.length() == 0) return;
        final String receiverUrl = remoteReceiverControlUrl;
        castEdgeBusy = true;
        updateCastEdgeState();
        new Thread(new Runnable() {
            @Override public void run() {
                Exception failure = null;
                try {
                    remoteCatalogClient.controlReceiver(receiverUrl,
                            new JSONObject().put("action", action));
                } catch (Exception error) {
                    failure = error;
                }
                final Exception result = failure;
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        castEdgeBusy = false;
                        updateCastEdgeState();
                        if (result != null) {
                            Toast.makeText(MainActivity.this,
                                    "切换失败：" + safeMessage(result),
                                    Toast.LENGTH_LONG).show();
                        }
                    }
                });
            }
        }, "cast-edge-channel").start();
    }

    private void stopCastFromEdge() {
        if (castEdgeBusy || remoteReceiverControlUrl.length() == 0) return;
        castEdgeBusy = true;
        updateCastEdgeState();
        new Thread(new Runnable() {
            @Override public void run() {
                Exception failure = null;
                try {
                    handleWebTakeover(new JSONObject().put("receiverUrl", ""));
                } catch (Exception error) {
                    failure = error;
                }
                final Exception result = failure;
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        castEdgeBusy = false;
                        updateCastEdgeState();
                        Toast.makeText(MainActivity.this, result == null
                                        ? "已结束投屏" : "结束投屏失败：" + safeMessage(result),
                                result == null ? Toast.LENGTH_SHORT : Toast.LENGTH_LONG).show();
                    }
                });
            }
        }, "cast-edge-stop").start();
    }

    private static String safeMessage(Exception error) {
        String message = error == null ? null : error.getMessage();
        return message == null || message.trim().length() == 0 ? "连接失败" : message;
    }

    private void setTakeoverProgress(boolean active, String title,
            String detail, int percent) {
        takeoverProgressTitle = title == null ? "" : title;
        takeoverProgressDetail = detail == null ? "" : detail;
        takeoverProgressPercent = Math.max(0, Math.min(100, percent));
        takeoverProgressActive = active;
    }

    private int rememberSniffedResource(SniffedResource resource) {
        synchronized (sniffedResources) {
            sniffedResources.put(resource.url, resource);
            while (sniffedResources.size() > 30) {
                String oldest = sniffedResources.keySet().iterator().next();
                sniffedResources.remove(oldest);
            }
            return sniffedResources.size();
        }
    }

    private void clearSniffedResources() {
        synchronized (sniffedResources) {
            sniffedResources.clear();
        }
    }

    private SniffedResource findSniffedResource(String url) {
        synchronized (sniffedResources) {
            return sniffedResources.get(url);
        }
    }

    private JSONArray sniffedResourcesJson() throws JSONException {
        JSONArray result = new JSONArray();
        synchronized (sniffedResources) {
            for (SniffedResource resource : sniffedResources.values()) {
                result.put(new JSONObject()
                        .put("url", resource.url)
                        .put("pageUrl", resource.pageUrl == null ? "" : resource.pageUrl));
            }
        }
        return result;
    }

    private void startSniffedResource(SniffedResource resource) {
        if (resource == null || resource.requestId != playRequestId
                || webSourceView == null || !webSourceView.hasRetainedPage()) {
            Toast.makeText(this, "该嗅探资源已失效，请重新打开网页",
                    Toast.LENGTH_SHORT).show();
            return;
        }
        Channel channel = currentChannel();
        webStreamHeaders = buildWebStreamHeaders(resource.pageUrl,
                resource.userAgent, resource.cookies);
        if (proxy != null) {
            proxy.setWebRequestHeaders(resource.pageUrl,
                    resource.userAgent, resource.cookies);
        }
        playingDiscoveredWebStream = true;
        webSourceView.hideForStreamPlayback();
        videoView.setVisibility(View.VISIBLE);
        showLoading(channel.name, "正在打开所选嗅探资源");
        showChannelBar(channel.name, "已从网页打开视频 · 按返回键回网页");
        startResolvedPlayer(channel, resource.url);
    }

    private void openWebSource(Channel channel, String configuredUrl, int requestId) {
        if (rejectUnsupportedWebViewSource(channel, configuredUrl)) {
            return;
        }
        dispatchFlyMouseButtonUp(true);
        String pageUrl = configuredUrl.substring("webview://".length());
        if (!pageUrl.startsWith("http://") && !pageUrl.startsWith("https://")) {
            abortChannelSwitchAnimation();
            hideLoading();
            showChannelBar(channel.name, "WebView 地址无效");
            return;
        }
        releasePlayer();
        clearSniffedResources();
        videoView.setVisibility(View.INVISIBLE);
        showLoading(channel.name, "正在打开网页直播");
        webSourceView.open(requestId, pageUrl);
        startRemoteWebViewCast(channel, requestId);
        ensureFlyMouseOnTop();
    }

    private void closeWebSource() {
        dispatchFlyMouseButtonUp(true);
        stopRemoteWebViewCast();
        playingDiscoveredWebStream = false;
        clearSniffedResources();
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

    private static boolean isWebViewUnsupportedOnDevice(int sdkInt, int cpuCount) {
        return sdkInt < Build.VERSION_CODES.LOLLIPOP && cpuCount <= 2;
    }

    private boolean rejectUnsupportedWebViewSource(Channel channel, String configuredUrl) {
        if (!isWebViewSource(configuredUrl)
                || !isWebViewUnsupportedOnDevice(Build.VERSION.SDK_INT,
                        Runtime.getRuntime().availableProcessors())) {
            return false;
        }
        abortChannelSwitchAnimation();
        hideLoading();
        String message = "设备性能太弱，无法加载网页";
        showChannelBar(channel == null ? "网页频道" : channel.name, message);
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
        Log.w(TAG, "Blocked WebView source on Android " + Build.VERSION.RELEASE
                + " with " + Runtime.getRuntime().availableProcessors() + " CPU cores");
        return true;
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
        webRapidBackStartedAt = 0L;
        webBackPressCount = 0;
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
        if (!hasLocalNetworkAccess()) {
            requestLocalNetworkPermission(true, true);
            return;
        }
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
            Intent intent = new Intent(this, ManagementActivity.class)
                    .putExtra(ManagementActivity.EXTRA_URL, controlServer.getLoopbackUrl())
                    .putExtra(ManagementActivity.EXTRA_TAKEOVER,
                            isReceiverTakeoverActive());
            if (isReceiverTakeoverActive()) {
                startActivityForResult(intent, TAKEOVER_MANAGEMENT_REQUEST);
            } else {
                startActivity(intent);
            }
        } catch (RuntimeException error) {
            Toast.makeText(this, "无法打开管理网页", Toast.LENGTH_SHORT).show();
        }
    }

    private void startManagementServer() {
        try {
            controlServer = new LocalControlServer(new LocalControlServer.Listener() {
                @Override
                public String stateJson(String view) {
                    return buildControlState(view);
                }

                @Override
                public String catalogJson() {
                    return buildRemoteCatalogState();
                }

                @Override
                public String playbackJson() {
                    return buildRemotePlaybackState();
                }

                @Override
                public String mediaJson(boolean detailed) throws Exception {
                    return buildMediaStateJson(detailed);
                }

                @Override
                public String control(JSONObject request) throws Exception {
                    return handleWebControl(request);
                }

                @Override
                public String mediaControl(JSONObject request) throws Exception {
                    return handleMediaControl(request);
                }

                @Override
                public String pointer(JSONObject request) throws Exception {
                    return handleWebPointer(request);
                }

                @Override
                public String startCast(JSONObject request) throws Exception {
                    return handleWebCastStart(request);
                }

                @Override
                public String stopCast() throws Exception {
                    return handleWebCastStop();
                }

                @Override
                public String takeover(JSONObject request) throws Exception {
                    try {
                        return handleWebTakeover(request);
                    } catch (Exception error) {
                        setTakeoverProgress(false, "接管失败", safeMessage(error), 100);
                        throw error;
                    }
                }

                @Override
                public String wifiDirect(JSONObject request) throws Exception {
                    return handleWifiDirect(request);
                }

                @Override
                public void takeoverSessionOpened(JSONObject request) throws Exception {
                    handleTakeoverSessionMessage(request, true);
                }

                @Override
                public void takeoverSessionMessage(JSONObject request) throws Exception {
                    handleTakeoverSessionMessage(request, false);
                }

                @Override
                public void takeoverSessionClosed(String sessionId) {
                    // Do not exit immediately: the same session may reconnect after
                    // a brief Wi-Fi handover. The receiver watchdog owns expiry.
                }

                @Override
                public String settings(JSONObject request) throws Exception {
                    return handleWebSettings(request);
                }

                @Override
                public String checkUpdate() throws Exception {
                    if (autoUpdater == null) {
                        return new JSONObject().put("ok", false)
                                .put("message", "更新服务尚未启动").toString();
                    }
                    return autoUpdater.checkLiteForUpdates();
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
                public String uploadKu9Script(String fileName, byte[] body) throws Exception {
                    Ku9ScriptLoader.SavedScript saved = Ku9ScriptLoader.saveUserScript(
                            MainActivity.this, fileName, body);
                    return new JSONObject().put("ok", true)
                            .put("name", saved.name)
                            .put("replaced", saved.replaced)
                            .put("path", saved.path).toString();
                }

                @Override
                public String pushApk(String receiverUrl, String fileName, byte[] body)
                        throws Exception {
                    return handleApkPush(receiverUrl, fileName, body);
                }

                @Override
                public String installApk(String sessionId, String fileName, byte[] body)
                        throws Exception {
                    return handleIncomingApk(sessionId, fileName, body);
                }

                @Override
                public LocalControlServer.Resource playlistSource(String location)
                        throws Exception {
                    return new LocalControlServer.Resource(
                            "text/plain; charset=utf-8", playlistManager.readForMobile(location));
                }

                @Override
                public String mergePlaylist(JSONObject request) throws Exception {
                    IMediaPlayer pausedPlayer = pausePlaybackForCatalogRefresh();
                    try {
                        String text = request.optString("playlist", "");
                        PlaylistManager.UpdateResult result = playlistManager.applyMobileMerge(
                                request.optJSONArray("sources"), text.getBytes("UTF-8"));
                        int mergedSourceCount = Math.max(0, request.optInt(
                                "mergedSourceCount", result.enabledCount));
                        final ChannelCatalog.Group[] customGroups = result.groups;
                        applyPlaylistGroups(customGroups);
                        int channelCount = 0;
                        for (ChannelCatalog.Group group : customGroups) {
                            channelCount += group.channels.length;
                        }
                        return new JSONObject().put("ok", true)
                                .put("groupCount", customGroups.length)
                                .put("channelCount", channelCount)
                                .put("sourceCount", mergedSourceCount)
                                .put("message", "已接收手机合并的 " + mergedSourceCount
                                        + " 个源、" + customGroups.length + " 个分组、"
                                        + channelCount + " 个频道").toString();
                    } finally {
                        resumePlaybackAfterCatalogRefresh(pausedPlayer);
                    }
                }

                @Override
                public LocalControlServer.Resource recording(String token) throws Exception {
                    return handleRecordingResource(token);
                }

                @Override
                public LocalControlServer.Resource screenshot(boolean localOnly) throws Exception {
                    if (!localOnly && isReceiverTakeoverActive()
                            && remoteReceiverControlUrl.length() > 0) {
                        return new LocalControlServer.Resource("image/png",
                                VideoScreenshot.download(RemoteCatalogClient.normalizeServerUrl(
                                        remoteReceiverControlUrl) + VideoScreenshot.PATH + "?local=1"));
                    }
                    byte[] image = videoScreenshot.capture(new VideoScreenshot.Source() {
                        @Override public VideoScreenshot.Target current() throws IOException {
                            if (videoView != null && !videoView.isSurfaceReady()) {
                                throw new IOException("请保持播放设备的视频界面在前台，再从另一台设备的网页截屏");
                            }
                            if (player == null || !prepared || !videoRenderingStarted
                                    || videoView == null || !videoView.isSurfaceReady()
                                    || videoWidth <= 0 || videoHeight <= 0
                                    || (webViewCastManager != null
                                        && webViewCastManager.videoInputSurface() != null)) {
                                throw new IOException("当前没有可截取的视频画面，请在节目出画后重试");
                            }
                            int width = lowResourceDevice ? Math.min(1280, videoWidth) : videoWidth;
                            int height = Math.max(1, Math.round((float) videoHeight * width / videoWidth));
                            return new VideoScreenshot.Target(videoView, player, width, height);
                        }
                    });
                    return new LocalControlServer.Resource("image/png", image);
                }

                @Override
                public LocalControlServer.Resource page(String path) throws Exception {
                    return handleControlPage(path);
                }
            });
            controlServer.start();
            if (isTelevisionDevice()) {
                if (castDeviceDiscovery != null) castDeviceDiscovery.close();
                castDeviceDiscovery = new CastDeviceDiscovery(controlServer.getPort());
                castDeviceDiscovery.startTelevisionResponder();
            }
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

    private LocalControlServer.Resource handleControlPage(String path) throws IOException {
        // Management pages and their dependencies are always bundled, including Release.
        // The standalone online recorder/flymouse pages retain their existing delivery path.
        if (ControlSite.contains("/" + path)) {
            synchronized (controlPageCache) {
                LocalControlServer.Resource cached = controlPageCache.get(path);
                if (cached != null) {
                    return cached;
                }
            }
            InputStream input = getAssets().open(ControlSite.assetPath("/" + path));
            try {
                LocalControlServer.Resource resource = new LocalControlServer.Resource(
                        ControlSite.contentType(path), readStream(input));
                synchronized (controlPageCache) {
                    controlPageCache.put(path, resource);
                }
                return resource;
            } finally {
                input.close();
            }
        }
        String contentType = path.endsWith(".js")
                ? "application/javascript; charset=utf-8" : "text/html; charset=utf-8";
        if (BuildConfig.EMBED_CONTROL_PAGES) {
            InputStream input = getAssets().open(path);
            try {
                return new LocalControlServer.Resource(contentType, readStream(input));
            } finally {
                input.close();
            }
        }
        String sourceUrl;
        if ("flymouse.html".equals(path)) {
            sourceUrl = BuildConfig.FLY_MOUSE_PAGE_SOURCE_URL;
        } else if ("video-recorder.html".equals(path)) {
            sourceUrl = BuildConfig.VIDEO_RECORDER_PAGE_SOURCE_URL;
        } else if ("mp4-finalizer.js".equals(path)) {
            sourceUrl = BuildConfig.MP4_FINALIZER_SOURCE_URL;
        } else {
            throw new IOException("网页资源不存在");
        }
        HttpURLConnection connection = (HttpURLConnection) new URL(
                GithubProxy.apply(this, sourceUrl)).openConnection();
        connection.setConnectTimeout(12000);
        connection.setReadTimeout(18000);
        connection.setInstanceFollowRedirects(true);
        connection.setRequestProperty("User-Agent", "nTv/" + BuildConfig.VERSION_NAME);
        try {
            int status = connection.getResponseCode();
            if (status < 200 || status >= 300) {
                throw new IOException("在线网页下载失败：HTTP " + status);
            }
            int length = connection.getContentLength();
            if (length > 2 * 1024 * 1024) {
                throw new IOException("在线网页文件过大");
            }
            InputStream input = connection.getInputStream();
            try {
                byte[] body = readStream(input);
                if (body.length > 2 * 1024 * 1024) {
                    throw new IOException("在线网页文件过大");
                }
                return new LocalControlServer.Resource(contentType, body);
            } finally {
                input.close();
            }
        } finally {
            connection.disconnect();
        }
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

    private String buildRemoteCatalogState() {
        try {
            JSONObject root = new JSONObject().put("ok", true)
                    .put("remoteCatalogUrl", remoteCatalogUrl);
            JSONArray jsonGroups = new JSONArray();
            ChannelCatalog.Group[] groups = ChannelCatalog.GROUPS;
            for (ChannelCatalog.Group group : groups) {
                JSONObject jsonGroup = new JSONObject().put("name", group.title);
                JSONArray channels = new JSONArray();
                for (Channel channel : group.channels) {
                    channels.put(new JSONObject()
                            .put("number", channel.number)
                            .put("name", channel.name)
                            .put("epgId", channel.epgId == null ? "" : channel.epgId)
                            .put("sourceCount", Math.max(1, channel.sourceCount())));
                }
                jsonGroup.put("channels", channels);
                jsonGroups.put(jsonGroup);
            }
            root.put("groups", jsonGroups);
            return root.toString();
        } catch (JSONException error) {
            return "{\"ok\":false,\"message\":\"频道目录生成失败\"}";
        }
    }

    private String remotePlaybackStreamUrl(Channel channel) {
        if (activePlayerChannel == channel && activePlayerStreamUrl != null
                && activePlayerStreamUrl.length() > 0) {
            return activePlayerStreamUrl;
        }
        if (remoteGatewayChannel == channel && remoteGatewayStreamUrl != null
                && remoteGatewayStreamUrl.length() > 0) {
            return remoteGatewayStreamUrl;
        }
        return null;
    }

    private int remotePlaybackSourceIndex(Channel channel) {
        return remoteGatewayChannel == channel && remoteGatewaySourceIndex >= 0
                ? remoteGatewaySourceIndex : currentSourceIndex;
    }

    private void detachPlayerForRemotePlayback() {
        if (activePlayerChannel == null || activePlayerStreamUrl == null
                || activePlayerStreamUrl.length() == 0) {
            return;
        }
        remoteGatewayChannel = activePlayerChannel;
        remoteGatewayStreamUrl = activePlayerStreamUrl;
        remoteGatewaySourceIndex = currentSourceIndex;
        releasePlayer();
        if (playbackAudioManager != null) {
            playbackAudioManager.abandonAudioFocus(playbackAudioFocusListener);
        }
        Log.i(TAG, "Phone decoder released; keeping remote playback gateway for "
                + remoteGatewayChannel.name);
    }

    private void clearRemotePlaybackGateway() {
        remoteGatewayChannel = null;
        remoteGatewayStreamUrl = null;
        remoteGatewaySourceIndex = -1;
    }

    private boolean isRemoteWebViewCastReady(Channel channel) {
        return remoteWebViewCastActive && remoteWebViewCastChannel == channel
                && remoteReceiverRequestId == playRequestId
                && webViewCastManager != null && webViewCastManager.isReady();
    }

    private JSONObject remotePlaybackJson(Channel channel) throws JSONException {
        if (isRemoteWebViewCastReady(channel)) {
            return new JSONObject()
                    .put("available", true)
                    .put("sourceIndex", remoteWebViewCastSourceIndex)
                    .put("sourceMode", "cast")
                    .put("sourceUrl", webViewCastManager.rtspUrl())
                    .put("playlistPath", "");
        }
        String remoteStreamUrl = remotePlaybackStreamUrl(channel);
        boolean activePlaybackMatches = remoteStreamUrl != null;
        boolean remoteDirect = activePlaybackMatches
                && isRemoteDirectSource(remoteStreamUrl);
        return new JSONObject()
                .put("available", activePlaybackMatches
                        && (remoteDirect || proxy != null))
                .put("sourceIndex", remotePlaybackSourceIndex(channel))
                .put("sourceMode", remoteDirect ? "direct" : "proxy")
                .put("sourceUrl", remoteDirect ? remoteStreamUrl : "")
                .put("playlistPath", "/api/recording/playlist");
    }

    private String buildRemotePlaybackState() {
        try {
            ChannelCatalog.Group[] groups = ChannelCatalog.GROUPS;
            int groupIndex = Math.max(0, Math.min(currentGroupIndex, groups.length - 1));
            ChannelCatalog.Group group = groups[groupIndex];
            int channelIndex = ChannelCatalog.wrapIndex(group.channels, currentChannelIndex);
            Channel channel = group.channels[channelIndex];
            return new JSONObject().put("ok", true)
                    .put("current", new JSONObject()
                            .put("groupIndex", groupIndex)
                            .put("channelIndex", channelIndex)
                            .put("sourceIndex", currentSourceIndex)
                            .put("name", channel.name))
                    .put("remotePlayback", remotePlaybackJson(channel))
                    .put("playRequestId", playRequestId)
                    .toString();
        } catch (JSONException error) {
            return "{\"ok\":false,\"message\":\"播放状态生成失败\"}";
        }
    }

    private String buildControlState() {
        return buildControlState("");
    }

    private void openManagementCastPage() {
        if (controlServer == null || controlServer.getPort() == 0) {
            Toast.makeText(this, "管理服务尚未启动", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            String url = Uri.parse(controlServer.getLoopbackUrl()).buildUpon()
                    .path("/pages/cast.html").clearQuery().fragment(null).build().toString();
            startActivity(new Intent(this, ManagementActivity.class)
                    .putExtra(ManagementActivity.EXTRA_URL, url)
                    .putExtra(ManagementActivity.EXTRA_TAKEOVER, false));
        } catch (RuntimeException error) {
            Toast.makeText(this, "无法打开投屏与互联", Toast.LENGTH_SHORT).show();
        }
    }

    private String buildControlState(String requestedView) {
        try {
            String view = requestedView == null ? ""
                    : requestedView.trim().toLowerCase(Locale.US);
            boolean scoped = "home".equals(view) || "advanced".equals(view)
                    || "browser".equals(view) || "cast".equals(view)
                    || "channels".equals(view) || "flymouse".equals(view)
                    || "groups".equals(view) || "playback".equals(view)
                    || "system".equals(view);
            boolean full = !scoped;
            ChannelCatalog.Group[] groups = ChannelCatalog.GROUPS;
            int groupIndex = Math.max(0, Math.min(currentGroupIndex, groups.length - 1));
            ChannelCatalog.Group group = groups[groupIndex];
            int channelIndex = ChannelCatalog.wrapIndex(group.channels, currentChannelIndex);
            Channel channel = group.channels[channelIndex];
            JSONObject root = new JSONObject();
            root.put("ok", true);
            root.put("takeoverProtocol", RemoteCatalogClient.TAKEOVER_PROTOCOL);
            root.put("apkTransferProtocol", RemoteCatalogClient.APK_TRANSFER_PROTOCOL);
            root.put("apkTransferMaxBytes", LocalControlServer.maxRequestBytes());
            root.put("githubUrl", GITHUB_URL);
            String managementPage = controlServer == null ? null : controlServer.getLanUrl();
            if (managementPage != null && managementPage.endsWith("index.html")) {
                managementPage = managementPage.substring(
                        0, managementPage.length() - "index.html".length());
            }
            root.put("managementUrl", managementPage == null ? "" : managementPage);
            root.put("isTelevision", isTelevisionDevice());
            root.put("canInitiateTakeover", canInitiateTakeover());
            root.put("takeoverReceiverUrl", remoteReceiverControlUrl);
            root.put("lastTakeoverReceiverUrl", lastTakeoverReceiverUrl);
            root.put("recentTakeoverReceiverUrls",
                    new JSONArray(recentTakeoverReceiversSnapshot()));
            root.put("takeoverProgress", new JSONObject()
                    .put("active", takeoverProgressActive)
                    .put("title", takeoverProgressTitle)
                    .put("detail", takeoverProgressDetail)
                    .put("percent", takeoverProgressPercent));
            root.put("networkTransport", SystemInfoProvider.activeNetworkTransport(this));
            root.put("wifiDirect", wifiDirectCoordinator == null ? new JSONObject()
                    : wifiDirectCoordinator.stateJson());
            root.getJSONObject("wifiDirect").put("active", wifiDirectActive);
            if (full) {
                root.put("castBackground", CastKeepAliveService.stateJson());
                root.put("takeoverSessionConnected",
                        remoteCatalogUrl.length() > 0
                                && remoteTakeoverSessionId.length() > 0
                                && SystemClock.elapsedRealtime() - lastRemoteTakeoverMessageAt
                                        < TAKEOVER_SESSION_TIMEOUT_MS);
                root.put("takeoverSessionSilenceMs", remoteCatalogUrl.length() == 0
                        || lastRemoteTakeoverMessageAt <= 0L ? 0L
                        : Math.max(0L, SystemClock.elapsedRealtime()
                                - lastRemoteTakeoverMessageAt));
            }
            DisplayMetrics displayMetrics = getResources().getDisplayMetrics();
            int displayWidth = Math.max(0, MainActivity.this.root.getWidth());
            int displayHeight = Math.max(0, MainActivity.this.root.getHeight());
            float density = Math.max(0.1f, displayMetrics.density);
            root.put("display", new JSONObject()
                    .put("width", displayWidth)
                    .put("height", displayHeight)
                    .put("widthDp", Math.round(displayWidth / density))
                    .put("heightDp", Math.round(displayHeight / density))
                    .put("densityDpi", displayMetrics.densityDpi)
                    .put("diagonalInches", detectedDisplayInches > 0f
                            ? Math.round(detectedDisplayInches * 10f) / 10.0d : 0d));
            if (full || "system".equals(view)) {
                root.put("system", systemInfoProvider == null ? new JSONObject()
                        : systemInfoProvider.snapshot());
                root.put("update", autoUpdater == null ? new JSONObject()
                        : autoUpdater.stateJson());
            }
            if (full || "cast".equals(view) || "flymouse".equals(view)) {
                JSONObject castState = webViewCastManager == null ? new JSONObject()
                        : webViewCastManager.stateJson();
                castState.put("permissionPending", pendingCastConfig != null);
                castState.put("webPageActive", isCastingWebPage());
                root.put("cast", castState);
            }
            JSONObject current = new JSONObject();
            current.put("groupIndex", groupIndex);
            current.put("channelIndex", channelIndex);
            current.put("group", group.title);
            current.put("name", channel.name);
            current.put("sourceIndex", catalogSource(group, channel)
                    == ChannelCatalog.SOURCE_CUSTOM
                    ? currentSourceIndex : 0);
            current.put("sourceCount", Math.max(1, channel.sourceCount()));
            current.put("webPageActive", webSourceView != null
                    && webSourceView.hasRetainedPage() && webSourceView.isPageVisible());
            root.put("current", current);
            if (full) {
                String recordingStreamUrl = activePlayerStreamUrl != null
                        ? activePlayerStreamUrl : remoteGatewayStreamUrl;
                boolean directRecording = recordingStreamUrl != null
                        && isDirectThirdPartyRecordingSource(recordingStreamUrl);
                boolean recordingAvailable = recordingStreamUrl != null
                        && recordingStreamUrl.length() > 0
                        && (directRecording || proxy != null);
                root.put("recording", new JSONObject()
                        .put("available", recordingAvailable)
                        .put("name", channel.name)
                        .put("group", group.title)
                        .put("width", Math.max(0, videoWidth))
                        .put("height", Math.max(0, videoHeight))
                        .put("sourceMode", directRecording ? "direct" : "proxy")
                        .put("sourceUrl", directRecording ? recordingStreamUrl : "")
                        .put("playlistPath", "/api/recording/playlist"));
                root.put("playRequestId", playRequestId);
                root.put("sniffedResources", sniffedResourcesJson());
            }
            if (full || "flymouse".equals(view)) {
                root.put("remotePlayback", remotePlaybackJson(channel));
            }
            if (full || "home".equals(view)) {
                JSONArray jsonGroups = new JSONArray();
                for (int groupPosition = 0; groupPosition < groups.length; groupPosition++) {
                    JSONObject jsonGroup = new JSONObject();
                    jsonGroup.put("name", groups[groupPosition].title);
                    JSONArray channels = new JSONArray();
                    for (Channel item : groups[groupPosition].channels) {
                        channels.put(new JSONObject().put("number", item.number)
                                .put("name", item.name)
                                .put("epgId", item.epgId == null ? "" : item.epgId)
                                .put("sourceCount", Math.max(1, item.sourceCount())));
                    }
                    jsonGroup.put("channels", channels);
                    jsonGroups.put(jsonGroup);
                }
                root.put("groups", jsonGroups);
            }
            root.put("settings", buildControlSettings(view, full));
            return root.toString();
        } catch (JSONException error) {
            return "{\"ok\":false,\"message\":\"状态生成失败\"}";
        }
    }

    private JSONObject buildControlSettings(String view, boolean full) throws JSONException {
        JSONObject settings = new JSONObject();
        if (full || "advanced".equals(view)) {
            settings.put("reverseKeys", reverseUpDown)
                    .put("autoStart", autoStart)
                    .put("decodeMode", decodeMode)
                    .put("hardwareDecoder", hardwareDecoder)
                    .put("hardwareDecoders", availableHardwareDecodersJson())
                    .put("surfaceMode", surfaceMode)
                    .put("rtspTransport", rtspTransport)
                    .put("h264SpsCompatibility", h264SpsCompatibility);
        }
        if (full || "playback".equals(view)) {
            settings.put("videoScaleMode", videoScaleMode)
                    .put("uiScaleMode", uiScaleMode)
                    .put("uiScaleFactor", Math.round(effectiveUiScale * 100f) / 100.0d)
                    .put("resolutionMode", resolutionMode)
                    .put("clockLocation", clockLocation)
                    .put("showDebugInfo", showDebugInfo)
                    .put("showNetworkSpeed", showNetworkSpeed)
                    .put("showDate", showDateTime)
                    .put("showDateTime", showDateTime)
                    .put("dateTimeFormat", dateTimeFormat)
                    .put("liveDelayMode", liveDelayMode);
        }
        if (full || "browser".equals(view)) {
            settings.put("webViewResolution", webViewResolution)
                    .put("webViewPageScale", Math.round(webViewPageScale * 100f) / 100.0d)
                    .put("webViewLoadImages", webViewLoadImages)
                    .put("webViewAutoPlaySniffed", webViewAutoPlaySniffed)
                    .put("webViewUserAgent", webViewUserAgent)
                    .put("webViewCacheBytes", webSourceView == null
                            ? 0L : webSourceView.browserCacheSizeBytes());
        }
        if (full || "cast".equals(view)) {
            settings.put("webCastResolution", webCastResolution)
                    .put("webCastFps", webCastFps)
                    .put("webCastCodec", webCastCodec)
                    .put("webCastBitrateMbps", webCastBitrateMbps)
                    .put("webCastAudio", webCastAudio);
        }
        if (full || "flymouse".equals(view)) {
            settings.put("flyMouseEnabled", flyMouseEnabled)
                    .put("remoteCatalogUrl", remoteCatalogUrl);
        }
        if (full || "channels".equals(view)) {
            settings.put("autoUpdateChannelList", autoUpdateChannelList)
                    .put("epgUrl", epgUrl)
                    .put("effectiveEpgUrl", effectiveEpgUrl())
                    .put("recommendedEpgUrl", EpgManager.DEFAULT_URL)
                    .put("playlistUrl", playlistManager.getPlaylistUrl())
                    .put("playlistSources", playlistManager.getSourcesJson())
                    .put("playlistGroups", playlistManager.getGroupSettingsJson())
                    .put("mobileMergedPlaylist", playlistManager.hasMobileMerge())
                    .put("githubProxyMode", GithubProxy.getMode(this))
                    .put("githubProxyPrefix", GithubProxy.getEffectivePrefix(this))
                    .put("githubProxyCustomPrefix", GithubProxy.getCustomPrefix(this))
                    .put("recommendedPlaylistUrl", playlistManager.getRecommendedUrl())
                    .put("recommendedPlaylistSources",
                            playlistManager.getRecommendedSourcesJson());
        } else if ("groups".equals(view)) {
            settings.put("playlistGroups", playlistManager.getGroupSettingsJson());
        }
        if (full) {
            settings.put("autoSwitchSource", autoSwitchSource)
                    .put("subtitleSizePercent", subtitleSizePercent)
                    .put("subtitlePosition", subtitlePosition)
                    .put("subtitleOffsetPercent", subtitleOffsetPercent)
                    .put("subtitleShadow", subtitleShadow);
        }
        if ("system".equals(view)) {
            settings.put("uiScaleFactor",
                    Math.round(effectiveUiScale * 100f) / 100.0d);
        }
        return settings;
    }

    private String handleWebControl(JSONObject request) throws JSONException {
        final String action = request.optString("action", "");
        final int requestedGroup = request.optInt("group", -1);
        final int requestedChannel = request.optInt("channel", -1);
        final int requestedSource = request.optInt("source", 0);
        final boolean requestedByReceiver = request.optBoolean("receiver", false);
        String receiverUrl = "";
        if (requestedByReceiver) {
            try {
                receiverUrl = RemoteCatalogClient.normalizeServerUrl(
                        request.optString("receiverUrl", ""));
            } catch (IOException ignored) {
                // Older receivers do not identify their management endpoint.
            }
        }
        final String requestedReceiverUrl = receiverUrl;
        final CastConfig requestedReceiverCast = requestedByReceiver
                ? buildRemoteReceiverCastConfig(request) : null;
        final String requestedUrl = request.optString("url", "");
        final SniffedResource requestedResource = "playSniffed".equals(action)
                ? findSniffedResource(requestedUrl) : null;
        if (!"next".equals(action) && !"previous".equals(action)
                && !"toggle".equals(action) && !"play".equals(action)
                && !"sourcePrevious".equals(action) && !"sourceNext".equals(action)
                && !"playSniffed".equals(action)
                && !"detachRemote".equals(action)
                && !"releaseReceiver".equals(action)) {
            throw new JSONException("未知的控制指令");
        }
        if ("play".equals(action)) {
            ChannelCatalog.Group[] groups = ChannelCatalog.GROUPS;
            if (requestedGroup < 0 || requestedGroup >= groups.length
                    || requestedChannel < 0
                    || requestedChannel >= groups[requestedGroup].channels.length
                    || requestedSource < 0
                    || requestedSource >= Math.max(1,
                            groups[requestedGroup].channels[requestedChannel].sourceCount())) {
                throw new JSONException("频道不存在");
            }
        }
        if ("playSniffed".equals(action) && requestedResource == null) {
            throw new JSONException("嗅探资源已失效，请刷新资源列表");
        }
        if (!requestedByReceiver && isReceiverTakeoverActive()
                && remoteReceiverControlUrl.length() > 0
                && isTakeoverRelayAction(action)) {
            try {
                return remoteCatalogClient.controlReceiver(
                        remoteReceiverControlUrl, request).toString();
            } catch (Exception error) {
                throw new JSONException("无法控制被接管电视："
                        + (error.getMessage() == null ? "连接失败" : error.getMessage()));
            }
        }
        final int[] acceptedRequestId = {-1};
        Runnable command = new Runnable() {
            @Override
            public void run() {
                if ("next".equals(action)) {
                    switchRelative(1);
                } else if ("previous".equals(action)) {
                    switchRelative(-1);
                } else if ("sourcePrevious".equals(action)) {
                    switchCustomSource(-1, false, "");
                } else if ("sourceNext".equals(action)) {
                    switchCustomSource(1, false, "");
                } else if ("toggle".equals(action)) {
                    togglePlayback();
                } else if ("playSniffed".equals(action)) {
                    startSniffedResource(requestedResource);
                } else if ("detachRemote".equals(action)) {
                    detachPlayerForRemotePlayback();
                } else if ("releaseReceiver".equals(action)) {
                    // A delayed release from a previous lease must not kill a
                    // newly established connection to the same receiver.
                    if (remoteCatalogClient.acceptsRelease(request.optString("sessionId", ""))) {
                        releaseReceiverPlayback();
                    }
                } else {
                    ChannelCatalog.Group[] groups = ChannelCatalog.GROUPS;
                    if (requestedGroup < 0 || requestedGroup >= groups.length
                            || requestedChannel < 0
                            || requestedChannel >= groups[requestedGroup].channels.length
                            || requestedSource < 0
                            || requestedSource >= Math.max(1,
                                    groups[requestedGroup].channels[requestedChannel]
                                            .sourceCount())) {
                        return;
                    }
                    currentGroupIndex = requestedGroup;
                    // Keep the active selection valid while takeover mode closes the
                    // channel panel. closeChannelList() refreshes the debug overlay,
                    // which reads currentChannel() before switchChannel() runs below.
                    // The previous group's index may not exist in the requested group.
                    currentChannelIndex = requestedChannel;
                    browsingGroupIndex = requestedGroup;
                    nextPlaybackRequestedByReceiver = requestedByReceiver;
                    remoteReceiverCastConfig = requestedReceiverCast;
                    if (requestedByReceiver) {
                        enterReceiverTakeoverMode(requestedReceiverUrl);
                    }
                    switchChannel(requestedChannel, requestedSource);
                    acceptedRequestId[0] = playRequestId;
                    closeChannelList();
                }
            }
        };
        if (requestedByReceiver && "play".equals(action)) {
            // Commit the channel generation before acknowledging. A receiver
            // must not mistake the previous channel's ready URL for this request.
            try { runOnMainThreadAndWait(command, 10000L); }
            catch (IOException error) { throw new JSONException(error.getMessage()); }
            if (acceptedRequestId[0] < 0) throw new JSONException("频道目录已变化，请重试");
            return new JSONObject().put("ok", true)
                    .put("playRequestId", acceptedRequestId[0]).toString();
        }
        runOnUiThread(command);
        return new JSONObject().put("ok", true).toString();
    }

    private static boolean isTakeoverRelayAction(String action) {
        return "play".equals(action) || "next".equals(action)
                || "previous".equals(action) || "sourcePrevious".equals(action)
                || "sourceNext".equals(action) || "toggle".equals(action);
    }

    private boolean canInitiateTakeover() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP || isTelevisionDevice()
                || remoteCatalogUrl.length() > 0) {
            return false;
        }
        return true;
    }

    private String handleWifiDirect(JSONObject request) throws Exception {
        if (wifiDirectCoordinator == null) {
            return new JSONObject().put("ok", false)
                    .put("message", "设备不支持 Wi-Fi Direct").toString();
        }
        String action = request.optString("action", "status");
        if ("prepare".equals(action)) {
            JSONObject state = wifiDirectCoordinator.prepareReceiver(
                    request.optBoolean("controllerGroupOwner", false),
                    request.optString("controllerDeviceAddress", ""),
                    request.optString("controllerDeviceName", ""));
            state.put("ok", true);
            return state.toString();
        }
        if ("stop".equals(action)) {
            final WifiDirectCoordinator coordinator = wifiDirectCoordinator;
            root.postDelayed(new Runnable() {
                @Override public void run() {
                    coordinator.removeGroup();
                }
            }, 500L);
        } else if (!"status".equals(action)) {
            throw new IOException("不支持的 Wi-Fi Direct 操作");
        }
        JSONObject state = wifiDirectCoordinator.stateJson();
        state.put("ok", true);
        return state.toString();
    }

    /** Try P2P only when both endpoints use Wi-Fi. Ethernet and phone hotspot paths
     * already have a direct, stable local route and should keep it. */
    private WifiDirectCoordinator.Route tryWifiDirectRoute(String receiverUrl) {
        if (!shouldTryWifiDirect() || wifiDirectPermissionDenied
                || !SystemInfoProvider.isPeerOnActiveWifi(this, receiverUrl)
                || !wifiDirectCoordinator.hasPermission() || controlServer == null) {
            return null;
        }
        boolean prepared = false;
        boolean controllerStarted = false;
        boolean routeAccepted = false;
        try {
            setTakeoverProgress(true, "正在连接电视", "检查电视网络", 18);
            JSONObject receiverState = remoteCatalogClient.receiverState(receiverUrl);
            String transport = receiverState.optString("networkTransport", "");
            if (transport.length() == 0) {
                JSONObject system = receiverState.optJSONObject("system");
                transport = system == null ? ""
                        : system.optString("networkTransport", "");
            }
            if (!"wifi".equals(transport)) {
                return null;
            }
            URL parsed = new URL(receiverUrl);
            int receiverPort = parsed.getPort() > 0
                    ? parsed.getPort() : parsed.getDefaultPort();
            // Put the phone's owner group on-air before restarting the old
            // receiver's discovery. Otherwise Android 7 can spend a full scan
            // cycle looking for a group that did not exist when scanning began.
            setTakeoverProgress(true, "正在建立 Wi-Fi Direct", "手机创建直连组", 26);
            controllerStarted = true;
            WifiDirectCoordinator.Route route = wifiDirectCoordinator.connect(
                    "", receiverPort, controlServer.getPort(), 6000L);
            if (route == null) return null;
            setTakeoverProgress(true, "正在建立 Wi-Fi Direct", "通知电视加入直连组", 42);
            String controllerDeviceAddress = wifiDirectCoordinator.controllerDeviceAddress(0L);
            String controllerDeviceName = wifiDirectCoordinator.controllerDeviceName();
            JSONObject direct = remoteCatalogClient.wifiDirect(receiverUrl, "prepare",
                    true, controllerDeviceAddress, controllerDeviceName);
            prepared = direct.optBoolean("ok", false);
            if (!prepared) return null;
            if (route.receiverUrl.length() == 0) {
                setTakeoverProgress(true, "正在建立 Wi-Fi Direct", "等待电视加入并获取地址", 50);
                route = waitForWifiDirectClientAddress(receiverUrl, route.controllerUrl,
                        receiverPort, 9000L);
            }
            // Android may publish the P2P connection before DHCP has installed the
            // route. Give the new 192.168.49.x path a moment to become usable.
            setTakeoverProgress(true, "正在建立 Wi-Fi Direct", "验证电视管理通道", 62);
            JSONObject p2pState = waitForWifiDirectReceiver(route.receiverUrl, 3000L);
            // The receiver may be an Android phone used as a TV endpoint. The
            // takeover protocol identifies nTv more reliably than form factor.
            if (p2pState.optInt("takeoverProtocol", 0)
                    < RemoteCatalogClient.TAKEOVER_PROTOCOL) {
                return null;
            }
            routeAccepted = true;
            return route;
        } catch (Exception error) {
            Log.i(TAG, "Wi-Fi Direct unavailable; keeping LAN route: "
                    + safeMessage(error));
            setTakeoverProgress(true, "正在连接电视", "Direct 未连接，改用局域网", 66);
            return null;
        } finally {
            if ((prepared || controllerStarted) && !routeAccepted) {
                if (prepared) {
                    try {
                        remoteCatalogClient.wifiDirect(receiverUrl, "stop");
                    } catch (Exception ignored) {
                    }
                }
                if (wifiDirectCoordinator != null) wifiDirectCoordinator.removeGroup();
            }
        }
    }

    private WifiDirectCoordinator.Route waitForWifiDirectClientAddress(String lanReceiverUrl,
            String controllerUrl, int receiverPort, long timeoutMs) throws Exception {
        long deadline = SystemClock.elapsedRealtime() + Math.max(500L, timeoutMs);
        Exception lastError = null;
        do {
            try {
                JSONObject direct = remoteCatalogClient.wifiDirectStatus(lanReceiverUrl);
                String clientAddress = direct.optString("localAddress", "");
                if (direct.optBoolean("groupFormed", false)
                        && !direct.optBoolean("groupOwner", true)
                        && clientAddress.length() > 0) {
                    return new WifiDirectCoordinator.Route(
                            "http://" + clientAddress + ":" + receiverPort, controllerUrl);
                }
            } catch (Exception error) {
                lastError = error;
            }
            try {
                Thread.sleep(160L);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IOException("Wi-Fi Direct 连接已取消");
            }
        } while (SystemClock.elapsedRealtime() < deadline);
        throw new IOException(lastError == null ? "电视 Wi-Fi Direct 地址尚未就绪"
                : "电视网络切换尚未完成：" + safeMessage(lastError));
    }

    private JSONObject waitForWifiDirectReceiver(String receiverUrl, long timeoutMs)
            throws Exception {
        long deadline = SystemClock.elapsedRealtime() + Math.max(500L, timeoutMs);
        Exception lastError = null;
        do {
            try {
                return remoteCatalogClient.receiverState(receiverUrl);
            } catch (Exception error) {
                lastError = error;
            }
            try {
                Thread.sleep(250L);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IOException("Wi-Fi Direct 连接已取消");
            }
        } while (SystemClock.elapsedRealtime() < deadline);
        throw lastError == null ? new IOException("Wi-Fi Direct 管理端口未就绪") : lastError;
    }

    private String handleWebTakeover(JSONObject request) throws Exception {
        if (!canInitiateTakeover()) {
            throw new IOException("当前设备只可接受控制，不能接管其他设备");
        }
        final String receiverUrl = RemoteCatalogClient.normalizeServerUrl(
                request.optString("receiverUrl", ""));
        final boolean stayOnContent = request.optBoolean("stayOnContent", false);
        if (receiverUrl.length() == 0) {
            setTakeoverProgress(true, "正在退出接管", "恢复手机和电视原有内容", 30);
            final String activeReceiver = remoteReceiverControlUrl;
            if (activeReceiver.length() > 0) {
                remoteCatalogClient.disconnectReceiver(activeReceiver);
            }
            if (wifiDirectActive && activeReceiver.length() > 0) {
                try {
                    remoteCatalogClient.wifiDirect(activeReceiver, "stop");
                } catch (Exception ignored) {
                }
            }
            wifiDirectActive = false;
            takeoverNetworkDelayMs = -1L;
            if (wifiDirectCoordinator != null) wifiDirectCoordinator.removeGroup();
            if (webViewCastManager != null) webViewCastManager.setAdvertisedAddress("");
            runOnMainThreadAndWait(new Runnable() {
                @Override
                public void run() {
                    releaseReceiverPlayback();
                    // Recreate the same local channel after its decoder/WebView was
                    // detached for the receiver. This covers native and web sources.
                    startChannel(currentChannelIndex);
                    updateCastEdgeState();
                }
            });
            setTakeoverProgress(false, "已退出接管", "电视已恢复原有频道", 100);
            return new JSONObject().put("ok", true)
                    .put("receiverUrl", "")
                    .put("rememberedReceiverUrl", lastTakeoverReceiverUrl).toString();
        }
        setTakeoverProgress(true, "正在连接电视", "检查权限和设备状态", 8);
        if (queueTakeoverForPermissions(receiverUrl, stayOnContent)) {
            setTakeoverProgress(true, "等待系统授权", !hasLocalNetworkAccess()
                    ? "请允许局域网设备访问"
                    : "请完成系统权限操作", 12);
            return new JSONObject().put("ok", true)
                    .put("pending", true)
                    .put("receiverUrl", receiverUrl)
                    .put("message", !hasLocalNetworkAccess()
                            ? "请允许局域网设备访问权限"
                            : shouldTryWifiDirect() && !wifiDirectPermissionDenied
                                    && !wifiDirectCoordinator.hasPermission()
                            ? "请允许附近设备发现，以尝试 Wi-Fi Direct"
                            : "请完成系统声音捕获授权").toString();
        }
        String hostUrl = controlServer == null ? ""
                : controlServer.getLanUrlForPeer(receiverUrl);
        hostUrl = RemoteCatalogClient.normalizeServerUrl(hostUrl);
        if (hostUrl.length() == 0) {
            throw new IOException("无法识别手机局域网地址");
        }
        WifiDirectCoordinator.Route directRoute = tryWifiDirectRoute(receiverUrl);
        takeoverNetworkDelayMs = -1L;
        final String effectiveReceiverUrl = directRoute == null
                ? receiverUrl : directRoute.receiverUrl;
        final String effectiveHostUrl = directRoute == null
                ? hostUrl : directRoute.controllerUrl;
        if (webViewCastManager != null) {
            webViewCastManager.setAdvertisedAddress(new URL(effectiveHostUrl).getHost());
        }
        try {
            setTakeoverProgress(true, "正在接管电视", directRoute == null
                    ? "建立局域网控制通道" : "建立 Direct 控制通道", 74);
            remoteCatalogClient.claimReceiver(effectiveReceiverUrl, effectiveHostUrl,
                new RemoteCatalogClient.TakeoverStateProvider() {
                    @Override
                    public JSONObject snapshot() throws JSONException {
                        return buildTakeoverSessionState();
                    }

                    @Override
                    public int catalogGeneration() {
                        return MainActivity.this.catalogGeneration;
                    }

                    @Override public long networkDelayMs() {
                        return takeoverNetworkDelayMs;
                    }

                    @Override public long encodeDelayMs() {
                        return webViewCastManager == null
                                ? -1L : webViewCastManager.encodeDelayMs();
                    }

                    @Override public long videoQueueDelayMs() {
                        return webViewCastManager == null
                                ? -1L : webViewCastManager.videoQueueDelayMs();
                    }

                    @Override public long videoSendDelayMs() {
                        return webViewCastManager == null
                                ? -1L : webViewCastManager.videoSendDelayMs();
                    }

                    @Override public void onRoundTrip(long delayMs) {
                        takeoverNetworkDelayMs = takeoverNetworkDelayMs < 0L ? delayMs
                                : Math.round(takeoverNetworkDelayMs * 0.75d
                                        + delayMs * 0.25d);
                    }

                    @Override
                    public void onMessage(JSONObject message) throws Exception {
                        handleControllerSessionMessage(message);
                    }
                });
            wifiDirectActive = directRoute != null;
        } catch (Exception directFailure) {
            if (directRoute == null) throw directFailure;
            Log.w(TAG, "P2P takeover failed; retrying the LAN route", directFailure);
            try {
                remoteCatalogClient.wifiDirect(effectiveReceiverUrl, "stop");
            } catch (Exception ignored) {
            }
            wifiDirectCoordinator.removeGroup();
            wifiDirectActive = false;
            setTakeoverProgress(true, "正在接管电视", "Direct 通道失败，切换局域网", 78);
            if (webViewCastManager != null) {
                webViewCastManager.setAdvertisedAddress(new URL(hostUrl).getHost());
            }
            remoteCatalogClient.claimReceiver(receiverUrl, hostUrl,
                    new RemoteCatalogClient.TakeoverStateProvider() {
                        @Override public JSONObject snapshot() throws JSONException {
                            return buildTakeoverSessionState();
                        }

                        @Override public int catalogGeneration() {
                            return MainActivity.this.catalogGeneration;
                        }

                        @Override public long networkDelayMs() {
                            return takeoverNetworkDelayMs;
                        }

                        @Override public long encodeDelayMs() {
                            return webViewCastManager == null
                                    ? -1L : webViewCastManager.encodeDelayMs();
                        }

                        @Override public long videoQueueDelayMs() {
                            return webViewCastManager == null
                                    ? -1L : webViewCastManager.videoQueueDelayMs();
                        }

                        @Override public long videoSendDelayMs() {
                            return webViewCastManager == null
                                    ? -1L : webViewCastManager.videoSendDelayMs();
                        }

                        @Override public void onRoundTrip(long delayMs) {
                            takeoverNetworkDelayMs = takeoverNetworkDelayMs < 0L ? delayMs
                                    : Math.round(takeoverNetworkDelayMs * 0.75d
                                            + delayMs * 0.25d);
                        }

                        @Override public void onMessage(JSONObject message) throws Exception {
                            handleControllerSessionMessage(message);
                        }
                    });
        }
        rememberTakeoverReceiver(receiverUrl);
        castEdgeReceiverUrl = wifiDirectActive ? effectiveReceiverUrl : receiverUrl;
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                enterReceiverTakeoverMode(wifiDirectActive
                        ? effectiveReceiverUrl : receiverUrl, !stayOnContent);
                updateCastEdgeState();
            }
        });
        setTakeoverProgress(false, "接管完成", wifiDirectActive
                ? "已通过 Wi-Fi Direct 连接" : "已通过局域网连接", 100);
        return new JSONObject().put("ok", true)
                .put("receiverUrl", wifiDirectActive ? effectiveReceiverUrl : receiverUrl)
                .put("wifiDirect", wifiDirectActive)
                .put("rememberedReceiverUrl", lastTakeoverReceiverUrl).toString();
    }

    private String handleApkPush(String requestedReceiverUrl, String fileName, byte[] body)
            throws Exception {
        String receiverUrl = requestedReceiverUrl == null
                ? "" : requestedReceiverUrl.trim();
        if (receiverUrl.length() == 0) {
            receiverUrl = remoteReceiverControlUrl.length() > 0
                    ? remoteReceiverControlUrl : lastTakeoverReceiverUrl;
        }
        receiverUrl = RemoteCatalogClient.normalizeServerUrl(receiverUrl);
        if (receiverUrl.length() == 0) {
            throw new IOException("请填写电视 IP，再发送 APK");
        }
        JSONObject result = remoteCatalogClient.pushApk(receiverUrl, fileName, body);
        if (!result.optBoolean("ok", false)) {
            throw new IOException(result.optString("message", "电视拒绝接收 APK"));
        }
        rememberTakeoverReceiver(receiverUrl);
        result.put("receiverUrl", receiverUrl);
        return result.toString();
    }

    private String handleIncomingApk(String sessionId, String fileName, byte[] body)
            throws Exception {
        String requestedSession = sessionId == null ? "" : sessionId.trim();
        if (requestedSession.length() > 0) {
            boolean sessionActive = remoteCatalogUrl.length() > 0
                    && requestedSession.equals(remoteTakeoverSessionId)
                    && lastRemoteTakeoverMessageAt > 0L
                    && SystemClock.elapsedRealtime() - lastRemoteTakeoverMessageAt
                            < TAKEOVER_SESSION_TIMEOUT_MS;
            if (!sessionActive) {
                throw new IOException("接管会话已失效，请重新接管电视");
            }
        }
        final ApkTransferInstaller.ReceivedApk received = ApkTransferInstaller.save(
                this, fileName, body);
        root.postDelayed(new Runnable() {
            @Override
            public void run() {
                beginReceivedApkInstall(received.file);
            }
        }, 650L);
        return new JSONObject().put("ok", true)
                .put("name", received.originalName)
                .put("packageName", received.packageName)
                .put("label", received.label)
                .put("versionName", received.versionName)
                .put("versionCode", received.versionCode)
                .put("message", "APK 已发送，电视正在打开安装界面")
                .toString();
    }

    private void beginReceivedApkInstall(File apk) {
        if (apk == null || !apk.isFile()) {
            Toast.makeText(this, "接收的 APK 文件已不存在", Toast.LENGTH_LONG).show();
            return;
        }
        launchReceivedApkInstaller(apk);
    }

    private void launchReceivedApkInstaller(File apk) {
        try {
            ApkTransferInstaller.launchInstaller(this, apk);
        } catch (Exception error) {
            Toast.makeText(this, "无法打开安装界面：" + error.getMessage(),
                    Toast.LENGTH_LONG).show();
        }
    }

    private JSONObject buildTakeoverSessionState() throws JSONException {
        JSONObject state = new JSONObject()
                .put("catalogGeneration", catalogGeneration)
                .put("networkDelayMs", takeoverNetworkDelayMs)
                .put("encodeDelayMs", webViewCastManager == null
                        ? -1L : webViewCastManager.encodeDelayMs())
                .put("videoQueueDelayMs", webViewCastManager == null
                        ? -1L : webViewCastManager.videoQueueDelayMs())
                .put("videoSendDelayMs", webViewCastManager == null
                        ? -1L : webViewCastManager.videoSendDelayMs());
        ChannelCatalog.Group[] groups = ChannelCatalog.GROUPS;
        int groupIndex = currentGroupIndex;
        if (groupIndex < 0 || groupIndex >= groups.length) {
            return state;
        }
        ChannelCatalog.Group group = groups[groupIndex];
        state.put("group", groupIndex).put("groupName", group.title);
        if (group.channels.length == 0) {
            return state;
        }
        int channelIndex = ChannelCatalog.wrapIndex(group.channels, currentChannelIndex);
        state.put("channel", channelIndex)
                .put("channelName", group.channels[channelIndex].name)
                .put("channelEpgId", group.channels[channelIndex].epgId == null
                        ? "" : group.channels[channelIndex].epgId)
                .put("source", currentSourceIndex);
        return state;
    }

    private void handleControllerSessionMessage(JSONObject message) throws Exception {
        String type = message.optString("type", "");
        if ("pointer".equals(type)) {
            handleWebPointer(message);
        } else if ("control".equals(type)) {
            handleWebControl(message);
        }
    }

    private String buildMediaStateJson() throws Exception {
        return buildMediaStateJson(true);
    }

    private String buildMediaStateJson(final boolean detailed) throws Exception {
        if (isReceiverTakeoverActive() && remoteReceiverControlUrl.length() > 0) {
            return remoteCatalogClient.mediaState(
                    remoteReceiverControlUrl, detailed).toString();
        }
        final AtomicReference<String> result = new AtomicReference<String>();
        final AtomicReference<Exception> failure = new AtomicReference<Exception>();
        Runnable task = new Runnable() {
            @Override
            public void run() {
                try {
                    result.set(buildLocalMediaState(detailed).toString());
                } catch (Exception error) {
                    failure.set(error);
                }
            }
        };
        runOnMainThreadAndWait(task);
        if (failure.get() != null) {
            throw failure.get();
        }
        return result.get();
    }

    private JSONObject buildLocalMediaState() throws JSONException {
        return buildLocalMediaState(true);
    }

    private JSONObject buildLocalMediaState(boolean detailed) throws JSONException {
        JSONObject result = new JSONObject().put("ok", true);
        result.put("lowResource", Runtime.getRuntime().availableProcessors() <= 2);
        ChannelCatalog.Group group = currentGroup();
        Channel channel = currentChannel();
        IjkMediaPlayer activePlayer = player;
        long duration = 0L;
        long position = 0L;
        boolean playing = false;
        float outputFps = 0f;
        long videoCachedDurationMs = 0L;
        if (activePlayer != null && prepared) {
            try {
                duration = Math.max(0L, activePlayer.getDuration());
                position = Math.max(0L, activePlayer.getCurrentPosition());
                playing = activePlayer.isPlaying();
                outputFps = Math.max(0f, activePlayer.getVideoOutputFramesPerSecond());
                videoCachedDurationMs = Math.max(0L,
                        activePlayer.getVideoCachedDuration());
            } catch (RuntimeException error) {
                Log.w(TAG, "Unable to read media controller state", error);
            }
        }
        result.put("available", activePlayer != null)
                .put("prepared", prepared)
                .put("playing", playing)
                .put("name", channel == null ? "" : channel.name)
                .put("group", group == null ? "" : group.title)
                .put("favoriteAvailable", group != null && channel != null)
                .put("favorite", group != null && channel != null
                        && favoriteChannelKeys.contains(favoriteKey(group, channel)))
                .put("positionMs", position)
                .put("durationMs", duration)
                .put("outputFps", Math.round(outputFps * 10f) / 10.0d)
                .put("videoCachedDurationMs", videoCachedDurationMs)
                .put("seekable", prepared && duration > 0L)
                .put("speed", Math.round(playbackSpeed * 100f) / 100.0d)
                .put("previousAvailable", adjacentChannelLocation(
                        currentGroupIndex, currentChannelIndex, -1) != null)
                .put("nextAvailable", adjacentChannelLocation(
                        currentGroupIndex, currentChannelIndex, 1) != null);
        result.put("revision", mediaTrackChangeGeneration);
        if (!detailed) {
            return result;
        }

        JSONArray audioTracks = new JSONArray();
        JSONArray videoTracks = new JSONArray();
        JSONArray subtitleTracks = new JSONArray();
        int selectedVideo = -1;
        int selectedAudio = -1;
        int selectedSubtitle = -1;
        if (activePlayer != null && prepared) {
            try {
                selectedAudio = activePlayer.getSelectedTrack(
                        ITrackInfo.MEDIA_TRACK_TYPE_AUDIO);
                selectedVideo = activePlayer.getSelectedTrack(ITrackInfo.MEDIA_TRACK_TYPE_VIDEO);
                selectedSubtitle = activePlayer.getSelectedTrack(
                        ITrackInfo.MEDIA_TRACK_TYPE_SUBTITLE);
                if (selectedSubtitle < 0) {
                    selectedSubtitle = activePlayer.getSelectedTrack(
                            ITrackInfo.MEDIA_TRACK_TYPE_TIMEDTEXT);
                }
                ITrackInfo[] tracks = activePlayer.getTrackInfo();
                if (tracks != null) {
                    int audioOrdinal = 0;
                    int videoOrdinal = 0;
                    int subtitleOrdinal = 0;
                    for (int index = 0; index < tracks.length; index++) {
                        ITrackInfo track = tracks[index];
                        if (track == null) {
                            continue;
                        }
                        int type = track.getTrackType();
                        if (type == ITrackInfo.MEDIA_TRACK_TYPE_VIDEO) {
                            videoTracks.put(mediaTrackJson(track,index,++videoOrdinal,"视轨",index==selectedVideo));
                        } else if (type == ITrackInfo.MEDIA_TRACK_TYPE_AUDIO) {
                            audioOrdinal++;
                            audioTracks.put(mediaTrackJson(track, index, audioOrdinal,
                                    "音轨", index == selectedAudio));
                        } else if (type == ITrackInfo.MEDIA_TRACK_TYPE_SUBTITLE
                                || type == ITrackInfo.MEDIA_TRACK_TYPE_TIMEDTEXT) {
                            subtitleOrdinal++;
                            subtitleTracks.put(mediaTrackJson(track, index,
                                    subtitleOrdinal, "字幕", index == selectedSubtitle));
                        }
                    }
                }
            } catch (RuntimeException error) {
                Log.w(TAG, "Unable to enumerate media tracks", error);
            }
        }
        HlsMediaTracks.Manifest manifest = mediaTrackManifest;
        if (manifest != null) {
            if (!manifest.videos.isEmpty()) {
                videoTracks = new JSONArray();
                selectedVideo = -1;
                for (int i=0;i<manifest.videos.size();i++) {
                    HlsMediaTracks.Track track=manifest.videos.get(i);
                    int index=HlsMediaTracks.VIDEO_BASE+i;
                    boolean selected=track.url.equals(manifest.selectedVideoUrl);
                    if(selected)selectedVideo=index;
                    videoTracks.put(new JSONObject().put("index",index).put("label",track.name)
                            .put("info",track.info).put("selected",selected));
                }
            }
            if (!manifest.subtitles.isEmpty()) {
                subtitleTracks = new JSONArray();
                selectedSubtitle = selectedHlsSubtitle;
                for (int i=0;i<manifest.subtitles.size();i++) {
                    HlsMediaTracks.Track track=manifest.subtitles.get(i);
                    int index=HlsMediaTracks.SUBTITLE_BASE+i;
                    subtitleTracks.put(new JSONObject().put("index",index)
                            .put("label",track.name+" · "+track.language+" · WebVTT")
                            .put("language",track.language).put("selected",index==selectedSubtitle));
                }
            }
        }
        result.put("audioTracks", audioTracks)
                .put("videoTracks", videoTracks).put("selectedVideoTrack", selectedVideo)
                .put("subtitleTracks", subtitleTracks)
                .put("selectedAudioTrack", selectedAudio)
                .put("selectedSubtitleTrack", selectedSubtitle)
                .put("subtitlesEnabled", subtitlesEnabled())
                .put("subtitleStyle", new JSONObject()
                        .put("sizePercent", subtitleSizePercent)
                        .put("position", subtitlePosition)
                        .put("offsetPercent", subtitleOffsetPercent)
                        .put("shadow", subtitleShadow));
        return result;
    }

    private static JSONObject mediaTrackJson(ITrackInfo track, int index, int ordinal,
            String fallback, boolean selected) throws JSONException {
        String language = normalizeTrackLanguage(track.getLanguage());
        String inline = track.getInfoInline();
        if (inline == null) {
            inline = "";
        }
        inline = inline.replace('\n', ' ').replace('\r', ' ').trim();
        if (inline.length() > 72) {
            inline = inline.substring(0, 69) + "…";
        }
        String label = fallback + " " + ordinal;
        if (language.length() > 0) {
            label += " · " + language;
        }
        if (inline.length() > 0 && (language.length() == 0 || track.getTrackType()==ITrackInfo.MEDIA_TRACK_TYPE_VIDEO)) {
            label += " · " + inline;
        }
        return new JSONObject().put("index", index)
                .put("label", label)
                .put("language", language)
                .put("info", inline)
                .put("selected", selected);
    }

    private static String normalizeTrackLanguage(String language) {
        if (language == null) {
            return "";
        }
        String value = language.trim();
        if (value.length() == 0 || "und".equalsIgnoreCase(value)) {
            return "";
        }
        if ("chi".equalsIgnoreCase(value) || "zho".equalsIgnoreCase(value)
                || "zh".equalsIgnoreCase(value)) {
            return "中文";
        }
        if ("yue".equalsIgnoreCase(value)) {
            return "粤语";
        }
        if ("eng".equalsIgnoreCase(value) || "en".equalsIgnoreCase(value)) {
            return "英语";
        }
        return value;
    }

    private String handleMediaControl(final JSONObject request) throws Exception {
        if (isReceiverTakeoverActive() && remoteReceiverControlUrl.length() > 0) {
            return remoteCatalogClient.mediaControl(
                    remoteReceiverControlUrl, request).toString();
        }
        final String action = request.optString("action", "");
        if (!"seek".equals(action) && !"speed".equals(action)
                && !"audioTrack".equals(action) && !"subtitleTrack".equals(action) && !"videoTrack".equals(action)
                && !"subtitleStyle".equals(action) && !"subtitleEnabled".equals(action) && !"previous".equals(action)
                && !"next".equals(action) && !"toggle".equals(action)
                && !"favorite".equals(action)) {
            throw new JSONException("未知的媒体控制指令");
        }
        final AtomicReference<Exception> failure = new AtomicReference<Exception>();
        runOnMainThreadAndWait(new Runnable() {
            @Override
            public void run() {
                try {
                    applyMediaControl(action, request);
                } catch (Exception error) {
                    failure.set(error);
                }
            }
        });
        if (failure.get() != null) {
            throw failure.get();
        }
        return buildMediaStateJson();
    }

    private void applyMediaControl(String action, JSONObject request) throws Exception {
        if ("subtitleEnabled".equals(action)) {
            setSubtitlesEnabled(request.optBoolean("enabled", true));
            return;
        }
        if ("favorite".equals(action)) {
            toggleCurrentChannelFavorite();
            return;
        }
        if ("previous".equals(action)) {
            switchRelative(-1);
            return;
        }
        if ("next".equals(action)) {
            switchRelative(1);
            return;
        }
        if ("toggle".equals(action)) {
            mediaTrackChangeGeneration++;
            togglePlayback();
            return;
        }
        if ("subtitleStyle".equals(action)) {
            int requestedSize = sanitizeSubtitleSizePercent(
                    request.optInt("sizePercent", subtitleSizePercent));
            String requestedPosition = sanitizeSubtitlePosition(
                    request.optString("position", subtitlePosition));
            String requestedShadow = sanitizeSubtitleShadow(
                    request.optString("shadow", subtitleShadow));
            subtitleSizePercent = requestedSize;
            subtitlePosition = requestedPosition;
            subtitleOffsetPercent = SubtitlePlacement.clamp(
                    request.optInt("offsetPercent", subtitleOffsetPercent));
            subtitleShadow = requestedShadow;
            getSharedPreferences(PREFERENCES, MODE_PRIVATE).edit()
                    .putInt(SUBTITLE_SIZE_PERCENT, subtitleSizePercent)
                    .putString(SUBTITLE_POSITION, subtitlePosition)
                    .putInt(SUBTITLE_OFFSET_PERCENT, subtitleOffsetPercent)
                    .putString(SUBTITLE_SHADOW, subtitleShadow).apply();
            applySubtitleStyle();
            return;
        }
        IjkMediaPlayer activePlayer = player;
        if (activePlayer == null || !prepared) {
            throw new IOException("当前节目尚未准备完成");
        }
        if ("seek".equals(action)) {
            mediaTrackChangeGeneration++;
            long duration = Math.max(0L, activePlayer.getDuration());
            if (duration <= 0L) {
                throw new IOException("当前直播节目不支持进度拖动");
            }
            long position = Math.max(0L, Math.min(duration,
                    request.optLong("positionMs", 0L)));
            activePlayer.seekTo(position);
        } else if ("speed".equals(action)) {
            mediaTrackChangeGeneration++;
            float speed = (float) request.optDouble("speed", 1d);
            if (speed < 0.25f || speed > 3f) {
                throw new IOException("播放倍速应为 0.25 到 3 倍");
            }
            playbackSpeed = speed;
            activePlayer.setSpeed(playbackSpeed);
        } else if ("videoTrack".equals(action)) {
            selectVideoTrack(activePlayer,request.optInt("index",-1));
        } else if ("audioTrack".equals(action)) {
            selectMediaTrack(activePlayer, request.optInt("index", -1), true);
        } else if ("subtitleTrack".equals(action)) {
            selectMediaTrack(activePlayer, request.optInt("index", -1), false);
        }
    }

    private void runOnMainThreadAndWait(Runnable task) throws IOException {
        runOnMainThreadAndWait(task, 3000L);
    }

    private void runOnMainThreadAndWait(Runnable task, long timeoutMs) throws IOException {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            task.run();
            return;
        }
        final CountDownLatch latch = new CountDownLatch(1);
        final Runnable wrapped = task;
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                try {
                    wrapped.run();
                } finally {
                    latch.countDown();
                }
            }
        });
        try {
            if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS)) {
                throw new IOException("电视主界面响应超时");
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IOException("媒体控制已取消");
        }
    }

    private void handleTakeoverSessionMessage(JSONObject request, boolean opened)
            throws Exception {
        final String hostUrl = RemoteCatalogClient.normalizeServerUrl(
                request.optString("hostUrl", ""));
        final String sessionId = request.optString("sessionId", "").trim();
        if (hostUrl.length() == 0 || !hostUrl.equalsIgnoreCase(remoteCatalogUrl)
                || sessionId.length() == 0) {
            throw new IOException("接管会话与当前控制端不匹配");
        }
        String activeSession = remoteTakeoverSessionId;
        if (!opened && activeSession.length() > 0 && !sessionId.equals(activeSession)) {
            throw new IOException("接管会话已被替换");
        }
        remoteTakeoverSessionId = sessionId;
        lastRemoteTakeoverMessageAt = SystemClock.elapsedRealtime();
        if (request.has("networkDelayMs")) {
            remoteNetworkDelayMs = request.optLong("networkDelayMs", -1L);
        }
        if (request.has("encodeDelayMs")) {
            remoteEncodeDelayMs = request.optLong("encodeDelayMs", -1L);
        }
        if (request.has("videoQueueDelayMs")) {
            remoteVideoQueueDelayMs = request.optLong("videoQueueDelayMs", -1L);
        }
        if (request.has("videoSendDelayMs")) {
            remoteVideoSendDelayMs = request.optLong("videoSendDelayMs", -1L);
        }
        final int generation = request.optInt("catalogGeneration", -1);
        if (opened && request.optInt("group", -1) >= 0
                && request.optInt("channel", -1) >= 0) {
            pendingTakeoverChannelSelection =
                    new TakeoverChannelSelection(sessionId, request);
        }
        if (generation >= 0 && generation != remoteCatalogGeneration) {
            remoteCatalogGeneration = generation;
            loadCompleteCatalogInBackground();
        } else if (opened && generation >= 0
                && generation == appliedRemoteCatalogGeneration) {
            runOnMainThreadAndWait(new Runnable() {
                @Override
                public void run() {
                    if (applyPendingTakeoverChannelSelection()) {
                        switchChannel(currentChannelIndex, currentSourceIndex);
                        closeChannelList();
                    }
                }
            });
        }
        scheduleReceiverTakeoverWatchdog();
    }

    private CastConfig buildRemoteReceiverCastConfig() {
        return buildRemoteReceiverCastConfig(null);
    }

    private CastConfig buildRemoteReceiverCastConfig(JSONObject receiverRequest) {
        int separator = webCastResolution.indexOf('x');
        int width = Integer.parseInt(webCastResolution.substring(0, separator));
        int height = Integer.parseInt(webCastResolution.substring(separator + 1));
        int fps = webCastFps;
        String codec = webCastCodec;
        int bitrate = webCastBitrateMbps * 1_000_000;
        if (receiverRequest != null) {
            // A receiver knows its display/resource profile better than the phone.
            // Treat its values as upper bounds, never as a request to upscale.
            int maximumWidth = receiverRequest.optInt("castWidth", 1280);
            int maximumHeight = receiverRequest.optInt("castHeight", 720);
            if (maximumWidth > 0 && maximumHeight > 0
                    && (width > maximumWidth || height > maximumHeight)) {
                width = maximumWidth & ~1;
                height = maximumHeight & ~1;
            }
            boolean lowResourceReceiver = receiverRequest.optBoolean(
                    "castLowResource", receiverRequest.optInt("castFps", 30) <= 25);
            if (CastConfig.CODEC_H265.equals(codec)
                    && !receiverRequest.optBoolean("castH265", false)) {
                codec = CastConfig.CODEC_H264;
            }
            int[][] profiles = receiverCastProfiles(receiverRequest, codec);
            int[] selected = CastReceiverProfileSelector.select(width, height, fps, profiles);
            if (selected != null) {
                width = selected[0];
                height = selected[1];
                fps = selected[2];
            } else {
                fps = Math.min(fps, Math.max(15,
                        receiverRequest.optInt("castFps", 30)));
            }
            if (lowResourceReceiver) {
                fps = Math.min(fps, Math.max(15,
                        receiverRequest.optInt("castFps", 25)));
                bitrate = Math.min(bitrate, Math.max(2_000_000,
                        receiverRequest.optInt("castBitrate", 2_500_000)));
            }
        }
        return new CastConfig("", width, height, fps, bitrate, webCastAudio, codec);
    }

    private static int[][] receiverCastProfiles(JSONObject request, String codec) {
        JSONArray values = request.optJSONArray("castProfiles");
        if (values == null || values.length() == 0) return null;
        ArrayList<int[]> profiles = new ArrayList<int[]>();
        for (int index = 0; index < values.length(); index++) {
            JSONObject value = values.optJSONObject(index);
            if (value == null || !codec.equals(value.optString("codec", "h264"))) continue;
            int width = value.optInt("width", 0);
            int height = value.optInt("height", 0);
            int fps = value.optInt("fps", 0);
            if (width > 0 && height > 0 && fps > 0) {
                profiles.add(new int[] {width, height, fps});
            }
        }
        return profiles.isEmpty() ? null : profiles.toArray(new int[profiles.size()][]);
    }

    @SuppressLint("NewApi")
    private String handleWebCastStart(JSONObject request) throws Exception {
        final CastConfig requested = CastConfig.fromJson(request);
        if (!hasLocalNetworkAccess()) {
            pendingCastConfig = requested;
            pendingStandaloneCastAfterLocalNetwork = true;
            runOnUiThread(new Runnable() {
                @Override public void run() {
                    requestLocalNetworkPermission(false, true);
                }
            });
            return new JSONObject().put("ok", true).put("pending", true)
                    .put("message", "请允许局域网设备访问权限").toString();
        }
        if (requested.audio && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            pendingCastConfig = requested;
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                    != PackageManager.PERMISSION_GRANTED) {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        requestPermissions(new String[] { Manifest.permission.RECORD_AUDIO },
                                CAST_AUDIO_PERMISSION_REQUEST);
                    }
                });
                return new JSONObject().put("ok", true).put("pending", true)
                        .put("message", "请在电视上允许录音权限，以捕获电视声音").toString();
            }
            requestCastMediaProjection();
            return new JSONObject().put("ok", true).put("pending", true)
                    .put("message", "请在电视上允许捕获应用声音").toString();
        }
        return startWebViewCast(requested, null);
    }

    private String handleWebCastStop() throws Exception {
        pendingCastConfig = null;
        final CountDownLatch stopped = new CountDownLatch(1);
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                try {
                    boolean restartPlayback = preparePlayerForDisplayMove();
                    if (webViewCastManager != null) {
                        webViewCastManager.stop();
                    }
                    releaseCastAudioProjection();
                    clearRemoteWebViewCastState();
                    endCastUiDrawing();
                    finishPlayerDisplayMove(restartPlayback);
                    applyFlyMouseVisibility();
                } finally {
                    stopped.countDown();
                }
            }
        });
        if (!stopped.await(10, TimeUnit.SECONDS)) {
            throw new IOException("停止网页投送超时");
        }
        return new JSONObject().put("ok", true).put("message", "网页投送已停止")
                .toString();
    }

    private String startWebViewCast(final CastConfig requested,
            final MediaProjection projection) throws Exception {
        final CountDownLatch started = new CountDownLatch(1);
        final AtomicReference<Exception> failure = new AtomicReference<Exception>();
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                boolean restartPlayback = preparePlayerForDisplayMove();
                try {
                    webViewCastManager.start(requested, projection);
                    beginCastUiDrawing();
                    finishPlayerDisplayMove(restartPlayback);
                    applyFlyMouseVisibility();
                } catch (Exception error) {
                    failure.set(error);
                    endCastUiDrawing();
                    finishPlayerDisplayMove(restartPlayback);
                    applyFlyMouseVisibility();
                } finally {
                    started.countDown();
                }
            }
        });
        if (!started.await(20, TimeUnit.SECONDS)) {
            throw new IOException("启动网页投送超时");
        }
        if (failure.get() != null) {
            throw failure.get();
        }
        JSONObject result = webViewCastManager.stateJson();
        result.put("ok", true);
        return result.toString();
    }

    private void beginCastUiDrawing() {
        if (root == null || castRootBackground != null) {
            return;
        }
        float castVisualScale = 1f;
        if (root instanceof CastRootLayout && webViewCastManager != null) {
            int outputWidth = webViewCastManager.outputWidth();
            int outputHeight = webViewCastManager.outputHeight();
            ((CastRootLayout) root).setCastViewport(outputWidth, outputHeight);
            castVisualScale = Math.min(outputWidth / 3840f, outputHeight / 2160f);
            if (flyMouseCursor != null) {
                flyMouseCursor.setCastVisualScale(castVisualScale);
            }
        }
        castRootBackground = root.getBackground();
        root.setBackground(null);
        if (webSourceView != null) {
            // Capture belongs to the session, including web channels opened later.
            webSourceView.setCastVisualScale(castVisualScale);
            webSourceView.setCastCaptureActive(true,
                    webViewCastManager == null ? webCastFps : webViewCastManager.outputFps());
        }
        videoView.setVisibility(View.INVISIBLE);
        updateCastKeepAlive();
    }

    private void endCastUiDrawing() {
        updateCastKeepAlive();
        if (flyMouseCursor != null) flyMouseCursor.setCastVisualScale(1f);
        if (webSourceView != null) {
            webSourceView.setCastVisualScale(1f);
            webSourceView.setCastCaptureActive(false, 0);
        }
        if (root == null || castRootBackground == null) {
            return;
        }
        root.setBackground(castRootBackground);
        castRootBackground = null;
        if (root instanceof CastRootLayout) {
            ((CastRootLayout) root).setCastViewport(0, 0);
        }
        root.requestLayout();
    }

    void handleWebViewCastFailure() {
        boolean restartPlayback = preparePlayerForDisplayMove();
        webViewCastManager.stop();
        releaseCastAudioProjection();
        clearRemoteWebViewCastState();
        endCastUiDrawing();
        finishPlayerDisplayMove(restartPlayback);
        applyFlyMouseVisibility();
    }

    void drawCastUi(Canvas canvas, int width, int height) {
        if (root == null) {
            return;
        }
        boolean fixedCastViewport = root instanceof CastRootLayout
                && ((CastRootLayout) root).hasCastViewport();
        // Complete the first encoder-sized layout immediately. CastRootLayout then
        // rejects later portrait measure specs from the freely rotating management
        // Activity, so this does not fight the system on every encoded frame.
        // A stopped Activity no longer services normal ViewRoot traversals. New
        // pages/fullscreen views can otherwise remain 0x0: JS runs, but no pixels
        // or mouse hit targets exist. Service pending layout on the cast clock.
        if (fixedCastViewport && (root.isLayoutRequested()
                || root.getWidth() != width || root.getHeight() != height)) {
            int widthSpec = View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY);
            int heightSpec = View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY);
            root.measure(widthSpec, heightSpec);
            root.layout(0, 0, width, height);
        }
        if (root.getWidth() <= 0 || root.getHeight() <= 0) {
            return;
        }
        canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR);
        int save = canvas.save();
        float scale = fixedCastViewport ? 1f : Math.min((float) width / root.getWidth(),
                (float) height / root.getHeight());
        canvas.translate((width - root.getWidth() * scale) / 2f,
                (height - root.getHeight() * scale) / 2f);
        canvas.scale(scale, scale);
        root.draw(canvas);
        canvas.restoreToCount(save);
    }

    private void startRemoteWebViewCast(Channel channel, int requestId) {
        if (requestId != playRequestId || requestId != remoteReceiverRequestId
                || webViewCastManager == null) {
            return;
        }
        try {
            CastConfig castConfig = remoteReceiverCastConfig;
            if (castConfig == null) {
                castConfig = buildRemoteReceiverCastConfig();
            }
            // A remote WebView channel has no decoded IJK video layer. Avoiding the
            // unused full-resolution video SurfaceTexture saves several 4K buffers.
            if (castConfig.audio && Build.VERSION.SDK_INT >= 29
                    && castAudioProjection == null && !castAudioPermissionDeclined) {
                pendingRemoteAudioRequestId = requestId;
                boolean alreadyPending = pendingCastConfig != null;
                pendingCastConfig = castConfig;
                if (!alreadyPending) {
                    if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                            != PackageManager.PERMISSION_GRANTED) {
                        requestPermissions(new String[] {Manifest.permission.RECORD_AUDIO},
                                CAST_AUDIO_PERMISSION_REQUEST);
                    } else requestCastMediaProjection();
                }
                updateLoadingStatus("请在手机上允许应用声音捕获");
                return;
            }
            webViewCastManager.start(castConfig, castAudioProjection, false);
            remoteWebViewCastActive = true;
            remoteWebViewCastChannel = channel;
            remoteWebViewCastSourceIndex = currentSourceIndex;
            beginCastUiDrawing();
            applyFlyMouseVisibility();
            Log.i(TAG, "Remote WebView cast ready: " + webViewCastManager.rtspUrl());
        } catch (Exception error) {
            clearRemoteWebViewCastState();
            endCastUiDrawing();
            Log.e(TAG, "Unable to start remote WebView cast", error);
            abortChannelSwitchAnimation();
            hideLoading();
            showChannelBar(channel.name, "网页投送失败：" + error.getMessage());
        }
    }

    private void stopRemoteWebViewCast() {
        if (!remoteWebViewCastActive) {
            return;
        }
        if (webViewCastManager != null) {
            webViewCastManager.stop();
        }
        clearRemoteWebViewCastState();
        endCastUiDrawing();
        applyFlyMouseVisibility();
    }

    private void clearRemoteWebViewCastState() {
        remoteWebViewCastActive = false;
        remoteWebViewCastChannel = null;
        remoteWebViewCastSourceIndex = -1;
        remoteReceiverRequestId = -1;
        remoteReceiverCastConfig = null;
    }

    private void releaseReceiverPlayback() {
        remoteCatalogClient.stopTakeoverSession();
        releaseCastAudioProjection();
        if (remoteReceiverRequestId < 0 && !remoteWebViewCastActive
                && remoteGatewayChannel == null
                && remoteReceiverControlUrl.length() == 0) {
            return;
        }
        stopRemoteWebViewCast();
        closeWebSource();
        releasePlayer();
        clearRemotePlaybackGateway();
        remoteReceiverRequestId = -1;
        remoteReceiverControlUrl = "";
        updateCastKeepAlive();
        updateCastEdgeState();
        hideLoading();
        setReceiverTakeoverOverlayVisible(false);
    }

    private void enterReceiverTakeoverMode(String receiverUrl) {
        enterReceiverTakeoverMode(receiverUrl, true);
    }

    private void enterReceiverTakeoverMode(String receiverUrl, boolean openManagementPage) {
        boolean wasActive = isReceiverTakeoverActive();
        String activeReceiverUrl = wifiDirectActive && castEdgeReceiverUrl.length() > 0
                ? castEdgeReceiverUrl : receiverUrl;
        if (activeReceiverUrl != null && activeReceiverUrl.length() > 0) {
            remoteReceiverControlUrl = activeReceiverUrl;
        }
        updateCastKeepAlive();
        closeChannelList();
        closeManagementPanel();
        clearWebCloseConfirmation();
        setReceiverTakeoverOverlayVisible(true);
        if (openManagementPage && !wasActive && !remoteInputMode) {
            root.post(new Runnable() {
                @Override
                public void run() {
                    openManagementPage();
                }
            });
        }
    }

    private boolean isReceiverTakeoverActive() {
        return remoteReceiverControlUrl.length() > 0
                || remoteReceiverRequestId >= 0
                || remoteWebViewCastActive
                || remoteGatewayChannel != null;
    }

    private void updateCastKeepAlive() {
        if (!stoppingBackgroundCast) {
            CastKeepAliveService.setActive(this, isReceiverTakeoverActive()
                    || webViewCastManager != null && webViewCastManager.isRunning());
        }
    }

    /** Called on the UI thread; do not restart local playback after a background timeout. */
    void stopBackgroundCast(String reason) {
        if (stoppingBackgroundCast) return;
        stoppingBackgroundCast = true;
        try {
            playRequestId++;
            cancelPendingRelativeSwitch();
            clearPendingPlayer();
            pendingCastConfig = null;
            exitReceiverTakeover(false, false);
            if (webViewCastManager != null) webViewCastManager.stop();
            clearRemoteWebViewCastState();
            endCastUiDrawing();
            closeWebSource();
            releasePlayer();
            hideLoading();
            applyFlyMouseVisibility();
            showChannelBar("投屏与互联", reason);
        } finally {
            stoppingBackgroundCast = false;
            CastKeepAliveService.setActive(this, false);
        }
    }

    private void setReceiverTakeoverOverlayVisible(boolean visible) {
        if (receiverTakeoverOverlay == null) {
            return;
        }
        receiverTakeoverOverlay.setVisibility(visible ? View.VISIBLE : View.GONE);
        if (visible) {
            receiverTakeoverOverlay.bringToFront();
            receiverTakeoverOverlay.requestFocus();
        } else if (root != null) {
            root.requestFocus();
        }
    }

    private void exitReceiverTakeover(boolean openManagementAfterExit) {
        exitReceiverTakeover(openManagementAfterExit, true);
    }

    private void exitReceiverTakeover(boolean openManagementAfterExit,
            boolean resumeLocalContent) {
        final String receiverUrl = remoteReceiverControlUrl;
        releaseReceiverPlayback();
        if (resumeLocalContent && !isFinishing()) {
            startChannel(currentChannelIndex);
        }
        if (receiverUrl.length() > 0) {
            new Thread(new Runnable() {
                @Override
                public void run() {
                    remoteCatalogClient.disconnectReceiver(receiverUrl);
                }
            }, "remote-receiver-disconnect").start();
        }
        if (openManagementAfterExit) {
            if (remoteInputMode) {
                openManagementPanel();
            } else {
                openManagementPage();
            }
        }
    }

    private boolean preparePlayerForDisplayMove() {
        boolean restartPlayback = hasActivePlayer()
                || pendingPlayerRequestId == playRequestId && pendingPlayerChannel != null;
        if (!restartPlayback || videoView == null) {
            return false;
        }
        castSurfaceRestartPending = true;
        clearPendingPlayer();
        releasePlayer();
        videoSurfaceHolder = null;
        // Stop feeding the physical buffer queue before the decoder is rebound to the
        // GPU compositor input surface.
        videoView.setVisibility(View.INVISIBLE);
        return true;
    }

    private void finishPlayerDisplayMove(boolean restartPlayback) {
        if (!restartPlayback || videoView == null) {
            return;
        }
        final boolean casting = webViewCastManager != null
                && webViewCastManager.isRunning();
        final View outputView = casting ? root : videoView;
        if (!casting) {
            videoView.setVisibility(View.VISIBLE);
        }
        outputView.postDelayed(new Runnable() {
            @Override
            public void run() {
                boolean stillCasting = webViewCastManager != null
                        && webViewCastManager.isRunning();
                Surface castSurface = stillCasting
                        ? webViewCastManager.videoInputSurface() : null;
                boolean outputReady = stillCasting
                        ? castSurface != null && castSurface.isValid()
                        : videoView.isSurfaceReady();
                if (!castSurfaceRestartPending || !outputReady) {
                    return;
                }
                if (!stillCasting) {
                    videoSurfaceHolder = videoView.getVideoSurfaceHolder();
                }
                castSurfaceRestartPending = false;
                startChannel(currentChannelIndex);
            }
        }, 300L);
    }

    private void requestCastMediaProjection() {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q
                        || (pendingCastConfig == null && !hasPendingTakeover())) {
                    return;
                }
                mediaProjectionManager = (MediaProjectionManager) getSystemService(
                        MEDIA_PROJECTION_SERVICE);
                if (mediaProjectionManager == null) {
                    if (hasPendingTakeover()) {
                        cancelPendingTakeover("设备不支持系统声音捕获，无法投屏");
                    } else {
                        pendingCastConfig = null;
                        Toast.makeText(MainActivity.this, "设备不支持应用声音捕获",
                                Toast.LENGTH_SHORT).show();
                    }
                    return;
                }
                CastKeepAliveService.setActive(MainActivity.this, true);
                startActivity(new Intent(MainActivity.this, CastAudioPermissionActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            }
        });
    }

    private void releaseCastAudioProjection() {
        pendingCastConfig = null;
        pendingStandaloneCastAfterLocalNetwork = false;
        pendingRemoteAudioRequestId = -1;
        castAudioPermissionDeclined = false;
        MediaProjection projection = castAudioProjection;
        castAudioProjection = null;
        if (projection != null) {
            try { projection.stop(); } catch (RuntimeException ignored) { }
        }
        CastKeepAliveService.setProjectionActive(false);
    }

    private boolean resumePendingRemoteAudio() {
        int requestId = pendingRemoteAudioRequestId;
        pendingRemoteAudioRequestId = -1;
        if (requestId < 0) return false;
        if (requestId == playRequestId && requestId == remoteReceiverRequestId) {
            startRemoteWebViewCast(currentChannel(), requestId);
        }
        return true;
    }

    private String handleWebPointer(JSONObject request) throws Exception {
        return dispatchWebPointer(request);
    }

    private boolean isCastingWebPage() {
        return webViewCastManager != null && webViewCastManager.isRunning()
                && webSourceView != null && webSourceView.hasRetainedPage()
                && webSourceView.isPageVisible();
    }

    /** A remote browser Back never closes the controller or the cast session. */
    boolean backCastWebPage() {
        if (!isCastingWebPage()) return false;
        dispatchFlyMouseButtonUp(true);
        webSourceView.goBackIfPossible(); // First history entry: consume without exiting.
        return true;
    }

    boolean ownsLocalPointerPage(String pageUrl) {
        if (isFinishing() || controlServer == null || remoteCatalogUrl.length() > 0
                || pageUrl == null) return false;
        // Management may be opened via our advertised LAN address, not just
        // 127.0.0.1. Both must use the local bridge, never loop back through HTTP.
        return controlServer.ownsOrigin(pageUrl);
    }

    void handleLocalPointer(JSONObject request) throws Exception {
        if (remoteCatalogUrl.length() > 0 || isFinishing()) return;
        if ("move".equals(request.optString("action", "move"))) {
            // The in-app bridge runs off the UI thread. Merge motion here so a
            // 120 Hz touch panel cannot enqueue 120 separate main-looper jobs.
            enqueueFlyMouseMove(
                    clampPointerDelta((float) request.optDouble("dx", 0d)),
                    clampPointerDelta((float) request.optDouble("dy", 0d)));
            return;
        }
        dispatchWebPointer(request);
    }

    boolean isLocalCastPointerActive() {
        return hasLocalPointerPage();
    }

    private boolean hasLocalPointerPage() {
        // This runs per input frame: role/session fields only, no PackageManager/Binder calls.
        return !isFinishing() && remoteCatalogUrl.length() == 0 && isReceiverTakeoverActive()
                && remoteWebViewCastActive && webViewCastManager != null
                && webViewCastManager.isRunning() && webSourceView != null
                && webSourceView.localPointerIdentity() != null;
    }

    private String dispatchWebPointer(JSONObject request) throws Exception {
        // A controlled receiver never owns the interactive page. Forward every
        // pointer event to the controller even while the RTSP stream is starting or
        // reconnecting; otherwise the old device can briefly consume (or reject)
        // mouse input locally and the phone-rendered WebView appears to freeze.
        if (remoteCatalogUrl.length() > 0) {
            if (controlServer != null) {
                request.put("type", "pointer");
                if (controlServer.sendTakeoverSessionMessage(request)) {
                    return new JSONObject().put("ok", true).toString();
                }
            }
            return remoteCatalogClient.pointer(remoteCatalogUrl, request).toString();
        }
        final boolean castRunning = webViewCastManager != null
                && webViewCastManager.isRunning();
        if (!flyMouseEnabled && !castRunning) {
            throw new JSONException("请先在操作与启动中开启手机飞鼠");
        }
        final String action = request.optString("action", "move");
        if (!"move".equals(action) && !"click".equals(action)
                && !"down".equals(action) && !"up".equals(action)
                && !"cancel".equals(action)
                && !"scroll".equals(action) && !"zoom".equals(action)
                && !"back".equals(action)
                && !"reset".equals(action) && !"key".equals(action)
                && !"text".equals(action) && !"menu".equals(action)) {
            throw new JSONException("未知的飞鼠指令");
        }
        final float dx = clampPointerDelta((float) request.optDouble("dx", 0d));
        final float dy = clampPointerDelta((float) request.optDouble("dy", 0d));
        final double scrollValue = request.optDouble("scrollY", 0d);
        final int scrollY = Double.isNaN(scrollValue) || Double.isInfinite(scrollValue) ? 0
                : (int) Math.max(-1440d, Math.min(1440d, scrollValue));
        final double horizontalScrollValue = request.optDouble("scrollX", 0d);
        final int scrollX = Double.isNaN(horizontalScrollValue)
                || Double.isInfinite(horizontalScrollValue) ? 0
                : (int) Math.max(-1440d, Math.min(1440d, horizontalScrollValue));
        final double rawZoomFactor = request.optDouble("zoomFactor", 1d);
        final float zoomFactor = Double.isNaN(rawZoomFactor)
                || Double.isInfinite(rawZoomFactor) ? 1f
                : (float) Math.max(0.5d, Math.min(2d, rawZoomFactor));
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
                applyLocalPointer(action, dx, dy, scrollX, scrollY,
                        zoomFactor, keyCode, metaState, text);
            }
        });
        return new JSONObject().put("ok", true).toString();
    }

    /** Shared final dispatch for HTTP and local WebView controls; caller is on the main thread. */
    private void applyLocalPointer(String action, float dx, float dy,
            int scrollX, int scrollY, float zoomFactor,
            int keyCode, int metaState, String text) {
        if (!isFlyMouseInteractionEnabled()) {
            return;
        }
        if ("move".equals(action)) {
            enqueueFlyMouseMove(dx, dy);
            return;
        }
        // A queued VSYNC move must reach its final coordinate before any
        // button/wheel/key boundary. Otherwise a quick tap hits the old point.
        root.removeCallbacks(applyPendingFlyMouseMove);
        applyPendingFlyMouseMove.run();
        if ("click".equals(action)) {
            dispatchFlyMouseClick();
        } else if ("down".equals(action)) {
            dispatchFlyMouseButtonDown();
        } else if ("up".equals(action)) {
            dispatchFlyMouseButtonUp(false);
        } else if ("cancel".equals(action)) {
            dispatchFlyMouseButtonUp(true);
        } else if ("scroll".equals(action)) {
            dispatchFlyMouseButtonUp(true);
            handleRemoteScroll(scrollX, scrollY);
        } else if ("zoom".equals(action)) {
            dispatchFlyMouseButtonUp(true);
            adjustRemoteWebPageScale(zoomFactor);
        } else if ("back".equals(action)) {
            dispatchFlyMouseButtonUp(true);
            if (!backCastWebPage()) onBackPressed();
        } else if ("menu".equals(action)) {
            dispatchFlyMouseButtonUp(true);
            openManagement();
        } else if ("key".equals(action)) {
            if (keyCode == KeyEvent.KEYCODE_VOLUME_UP
                    || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
                adjustRemoteVolume(keyCode);
            } else if (webSourceView != null && webSourceView.isPageVisible()
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
            dispatchFlyMouseButtonUp(true);
            flyMouseCursor.resetPosition();
            dispatchFlyMouseMotionEvent(MotionEvent.ACTION_HOVER_MOVE,
                    SystemClock.uptimeMillis());
            ensureFlyMouseOnTop();
        }
    }

    private void enqueueFlyMouseMove(float dx, float dy) {
        if (root == null) {
            return;
        }
        boolean post;
        synchronized (flyMouseMoveLock) {
            pendingFlyMouseDx = clampPointerDelta(pendingFlyMouseDx + dx);
            pendingFlyMouseDy = clampPointerDelta(pendingFlyMouseDy + dy);
            post = !flyMouseMovePosted;
            if (post) {
                flyMouseMovePosted = true;
            }
        }
        if (post) {
            if (webViewCastManager != null && webViewCastManager.isRunning()) {
                // Apply input on the next main-loop turn instead of making the
                // cursor wait for a potentially expensive WebView capture. The
                // capture path still flushes any coordinate that arrived between
                // this task and root.draw(), so encoded frames stay current too.
                root.post(applyPendingFlyMouseMove);
            } else if (Build.VERSION.SDK_INT >= 16) {
                root.postOnAnimation(applyPendingFlyMouseMove);
            } else {
                root.postDelayed(applyPendingFlyMouseMove, 16L);
            }
        }
    }

    /** Apply the newest coalesced pointer coordinate before capturing this frame. */
    void prepareCastUiFrame() {
        if (root == null) return;
        boolean pending;
        synchronized (flyMouseMoveLock) {
            pending = flyMouseMovePosted;
        }
        if (!pending) return;
        root.removeCallbacks(applyPendingFlyMouseMove);
        applyPendingFlyMouseMove.run();
    }

    /** Receiver-side lease expiry. The old device immediately gives ownership of
     * input and channels back to itself after the controller goes silent. */
    private void exitRemoteCatalogTakeover(String message) {
        if (remoteCatalogUrl.length() == 0) {
            return;
        }
        restoreReceiverChannelPending = receiverChannelBeforeTakeover != null;
        remoteCatalogUrl = "";
        remoteTakeoverSessionId = "";
        remoteNetworkDelayMs = -1L;
        remoteEncodeDelayMs = -1L;
        remoteVideoQueueDelayMs = -1L;
        remoteVideoSendDelayMs = -1L;
        lastRemoteTakeoverMessageAt = 0L;
        remoteCatalogGeneration = -1;
        appliedRemoteCatalogGeneration = -1;
        pendingTakeoverChannelSelection = null;
        catalogLoadGeneration.incrementAndGet();
        getSharedPreferences(PREFERENCES, MODE_PRIVATE).edit()
                .remove(REMOTE_CATALOG_URL).apply();
        playRequestId++;
        closeWebSource();
        releasePlayer();
        clearRemotePlaybackGateway();
        hideLoading();
        loadCompleteCatalogInBackground();
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
        Log.w(TAG, message);
    }

    private void adjustRemoteVolume(int keyCode) {
        AudioManager audioManager = (AudioManager) getSystemService(AUDIO_SERVICE);
        if (audioManager == null) {
            return;
        }
        int direction = keyCode == KeyEvent.KEYCODE_VOLUME_UP
                ? AudioManager.ADJUST_RAISE : AudioManager.ADJUST_LOWER;
        audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction,
                AudioManager.FLAG_SHOW_UI | AudioManager.FLAG_PLAY_SOUND);
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
        String activeUrl = activePlayerStreamUrl != null
                ? activePlayerStreamUrl : remoteGatewayStreamUrl;
        if (activeProxy == null || activeUrl == null || activeUrl.length() == 0) {
            throw new IOException("当前频道还没有可录制的视频流");
        }
        HlsProxyServer.ProxyResponse response = activeProxy.fetchForRecording(
                activeUrl, token, "/api/recording/resource/");
        return new LocalControlServer.Resource(response.contentType, response.body);
    }

    private void handleRemoteScroll(int scrollX, int scrollY) {
        if (webSourceView != null && webSourceView.isPageVisible()) {
            // Wheel events are hit-tested at the cursor (including nested players,
            // iframes and scroll panes), not an unconditional scroll of the document.
            dispatchFlyMouseMotionEvent(MotionEvent.ACTION_SCROLL,
                    SystemClock.uptimeMillis(), scrollX, scrollY);
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

    private void adjustRemoteWebPageScale(float factor) {
        if (webSourceView == null || !webSourceView.isPageVisible()) return;
        webSourceView.adjustCurrentPageScale(factor);
    }

    private static float clampPointerDelta(float value) {
        if (Float.isNaN(value) || Float.isInfinite(value)) return 0f;
        return Math.max(-240f, Math.min(240f, value));
    }

    private void applyFlyMouseVisibility() {
        if (flyMouseCursor == null) {
            return;
        }
        boolean active = isFlyMouseInteractionEnabled();
        if (!active) {
            dispatchFlyMouseButtonUp(true);
            dispatchFlyMouseMotionEvent(MotionEvent.ACTION_HOVER_EXIT,
                    SystemClock.uptimeMillis());
        }
        flyMouseCursor.setVisibility(active ? View.VISIBLE : View.GONE);
        if (active) {
            flyMouseCursor.resetPosition();
            ensureFlyMouseOnTop();
        }
    }

    private boolean isFlyMouseInteractionEnabled() {
        return flyMouseEnabled || webViewCastManager != null
                && webViewCastManager.isRunning();
    }

    private void ensureFlyMouseOnTop() {
        if (isFlyMouseInteractionEnabled() && flyMouseCursor != null) {
            flyMouseCursor.bringToFront();
        }
    }

    private void dispatchFlyMouseClick() {
        if (flyMouseButtonDown) return;
        dispatchFlyMouseButtonDown();
        dispatchFlyMouseButtonUp(false);
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
        if (request.has("rtspTransport")) {
            String rawTransport = request.optString(
                    "rtspTransport", RTSP_TRANSPORT_TCP);
            String requestedTransport = sanitizeRtspTransport(rawTransport);
            if (!requestedTransport.equals(rawTransport)) {
                throw new JSONException("不支持的 RTSP 传输协议");
            }
            restartPlayback |= !requestedTransport.equals(rtspTransport);
            rtspTransport = requestedTransport;
            getSharedPreferences(PREFERENCES, MODE_PRIVATE).edit()
                    .putString(RTSP_TRANSPORT, rtspTransport).apply();
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
        if (request.has("uiScaleMode")) {
            String rawMode = request.optString("uiScaleMode", UI_SCALE_AUTO);
            final String requestedMode = sanitizeUiScaleMode(rawMode);
            if (!requestedMode.equals(rawMode)) {
                throw new JSONException("不支持的界面大小档位");
            }
            uiScaleMode = requestedMode;
            getSharedPreferences(PREFERENCES, MODE_PRIVATE).edit()
                    .putString(UI_SCALE_MODE, uiScaleMode).apply();
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    refreshUiScaleForViewport(root.getWidth(), root.getHeight(), true);
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
                    "webViewResolution", WEB_VIEW_RESOLUTION_720P);
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
        if (request.has("webViewAutoPlaySniffed")) {
            webViewAutoPlaySniffed = request.optBoolean(
                    "webViewAutoPlaySniffed", true);
            getSharedPreferences(PREFERENCES, MODE_PRIVATE).edit()
                    .putBoolean(WEB_VIEW_AUTO_PLAY_SNIFFED,
                            webViewAutoPlaySniffed).apply();
        }
        if (request.has("webViewUserAgent")) {
            String rawUserAgent = request.optString(
                    "webViewUserAgent", WEB_VIEW_USER_AGENT_WINDOWS);
            String requestedUserAgent = sanitizeWebViewUserAgent(rawUserAgent);
            if (!requestedUserAgent.equals(rawUserAgent)) {
                throw new JSONException("不支持的浏览器标识");
            }
            webViewUserAgent = requestedUserAgent;
            getSharedPreferences(PREFERENCES, MODE_PRIVATE).edit()
                    .putString(WEB_VIEW_USER_AGENT, webViewUserAgent).apply();
            applyWebViewSettings = true;
        }
        if (request.has("webViewPageScale")) {
            float rawScale = (float) request.optDouble("webViewPageScale", 1d);
            float requestedScale = sanitizeWebViewPageScale(rawScale);
            if (Math.abs(requestedScale - rawScale) > 0.001f) {
                throw new JSONException("屏幕缩放系数应为 50% 到 300%");
            }
            webViewPageScale = requestedScale;
            getSharedPreferences(PREFERENCES, MODE_PRIVATE).edit()
                    .putFloat(WEB_VIEW_PAGE_SCALE, webViewPageScale).apply();
            applyWebViewSettings = true;
        }
        if (request.has("webCastResolution")) {
            String rawResolution = request.optString(
                    "webCastResolution", WEB_CAST_RESOLUTION_720P);
            String requestedResolution = sanitizeWebCastResolution(rawResolution);
            if (!requestedResolution.equals(rawResolution)) {
                throw new JSONException("不支持的网页投送分辨率");
            }
            webCastResolution = requestedResolution;
            getSharedPreferences(PREFERENCES, MODE_PRIVATE).edit()
                    .putString(WEB_CAST_RESOLUTION, webCastResolution).apply();
        }
        if (request.has("webCastFps")) {
            int rawFps = request.optInt("webCastFps", 25);
            int requestedFps = sanitizeWebCastFps(rawFps);
            if (requestedFps != rawFps) {
                throw new JSONException("不支持的网页投送帧率");
            }
            webCastFps = requestedFps;
            getSharedPreferences(PREFERENCES, MODE_PRIVATE).edit()
                    .putInt(WEB_CAST_FPS, webCastFps).apply();
        }
        if (request.has("webCastCodec")) {
            String rawCodec = request.optString("webCastCodec", CastConfig.CODEC_H264);
            String requestedCodec = sanitizeWebCastCodec(rawCodec);
            if (!requestedCodec.equals(rawCodec)) {
                throw new JSONException("不支持的网页投送编码");
            }
            webCastCodec = requestedCodec;
            getSharedPreferences(PREFERENCES, MODE_PRIVATE).edit()
                    .putString(WEB_CAST_CODEC, webCastCodec).apply();
        }
        if (request.has("webCastBitrateMbps")) {
            int rawBitrate = request.optInt("webCastBitrateMbps", 3);
            int requestedBitrate = sanitizeWebCastBitrate(rawBitrate);
            if (requestedBitrate != rawBitrate) {
                throw new JSONException("网页投送码率应为 2 到 40 Mbps");
            }
            webCastBitrateMbps = requestedBitrate;
            getSharedPreferences(PREFERENCES, MODE_PRIVATE).edit()
                    .putInt(WEB_CAST_BITRATE, webCastBitrateMbps).apply();
        }
        if (request.has("webCastAudio")) {
            webCastAudio = request.optBoolean("webCastAudio", false);
            getSharedPreferences(PREFERENCES, MODE_PRIVATE).edit()
                    .putBoolean(WEB_CAST_AUDIO, webCastAudio).apply();
        }
        if (applyWebViewSettings) {
            final String requestedWebViewResolution = webViewResolution;
            final boolean requestedWebViewLoadImages = webViewLoadImages;
            final String requestedWebViewUserAgent = webViewUserAgent;
            final float requestedWebViewPageScale = webViewPageScale;
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    if (webSourceView != null) {
                        webSourceView.applyConfiguration(requestedWebViewResolution,
                                requestedWebViewLoadImages, requestedWebViewUserAgent,
                                requestedWebViewPageScale);
                    }
                }
            });
        }
        if (request.has("dateTimeFormat")) {
            String rawFormat = request.optString("dateTimeFormat", DATE_TIME_DATE_FIRST);
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
        if (request.has("autoSwitchSource")) {
            autoSwitchSource = request.optBoolean("autoSwitchSource", false);
            getSharedPreferences(PREFERENCES, MODE_PRIVATE).edit()
                    .putBoolean(AUTO_SWITCH_SOURCE, autoSwitchSource).apply();
            Log.i(TAG, "Automatic source switching=" + autoSwitchSource);
        }
        if (request.has("autoUpdateChannelList")) {
            autoUpdateChannelList = request.optBoolean("autoUpdateChannelList", false);
            getSharedPreferences(PREFERENCES, MODE_PRIVATE).edit()
                    .putBoolean(AUTO_UPDATE_CHANNEL_LIST, autoUpdateChannelList).apply();
            Log.i(TAG, "Automatic channel list update=" + autoUpdateChannelList);
        }
        if (request.has("githubProxyMode")) {
            try {
                GithubProxy.save(this, request.optString("githubProxyMode", ""),
                        request.optString("githubProxyCustomPrefix", ""));
            } catch (IllegalArgumentException error) {
                throw new JSONException(error.getMessage());
            }
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
        if (request.has("remoteCatalogUrl")) {
            final String previousRemoteUrl = remoteCatalogUrl;
            final String previousRemoteSessionId = remoteTakeoverSessionId;
            String requestedRemoteUrl;
            try {
                requestedRemoteUrl = RemoteCatalogClient.normalizeServerUrl(
                        request.optString("remoteCatalogUrl", ""));
            } catch (IOException error) {
                throw new JSONException(error.getMessage());
            }
            final boolean enteringTakeover = previousRemoteUrl.length() == 0
                    && requestedRemoteUrl.length() > 0;
            final boolean endingTakeover = previousRemoteUrl.length() > 0
                    && requestedRemoteUrl.length() == 0;
            if (enteringTakeover) {
                runOnMainThreadAndWait(new Runnable() {
                    @Override
                    public void run() {
                        rememberReceiverChannelBeforeTakeover();
                    }
                });
            }
            if (endingTakeover) {
                restoreReceiverChannelPending = receiverChannelBeforeTakeover != null;
            }
            boolean changed = !requestedRemoteUrl.equals(remoteCatalogUrl);
            remoteCatalogUrl = requestedRemoteUrl;
            remoteTakeoverSessionId = "";
            remoteNetworkDelayMs = -1L;
            remoteEncodeDelayMs = -1L;
            remoteVideoQueueDelayMs = -1L;
            remoteVideoSendDelayMs = -1L;
            remoteCatalogGeneration = -1;
            appliedRemoteCatalogGeneration = -1;
            pendingTakeoverChannelSelection = null;
            SharedPreferences.Editor editor = getSharedPreferences(
                    PREFERENCES, MODE_PRIVATE).edit();
            if (remoteCatalogUrl.length() == 0) {
                editor.remove(REMOTE_CATALOG_URL);
                lastRemoteTakeoverMessageAt = 0L;
                scheduleReceiverTakeoverWatchdog();
            } else {
                editor.putString(REMOTE_CATALOG_URL, remoteCatalogUrl);
                lastRemoteTakeoverMessageAt = SystemClock.elapsedRealtime();
                scheduleReceiverTakeoverWatchdog();
            }
            editor.apply();
            if (changed) {
                if (previousRemoteUrl.length() > 0) {
                    new Thread(new Runnable() {
                        @Override
                        public void run() {
                            remoteCatalogClient.release(previousRemoteUrl, previousRemoteSessionId);
                        }
                    }, "remote-receiver-release").start();
                }
            }
            // A repeated claim is a new lease and must refresh a startup catalog
            // that may have been obtained before the controller finished loading.
            if (changed || remoteCatalogUrl.length() > 0) {
                loadCompleteCatalogInBackground();
            }
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
            IMediaPlayer pausedPlayer = pausePlaybackForCatalogRefresh();
            try {
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
            } finally {
                resumePlaybackAfterCatalogRefresh(pausedPlayer);
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
        if (requestCode == WIFI_DIRECT_PERMISSION_REQUEST) {
            wifiDirectPermissionRequestInFlight = false;
            boolean granted = grantResults.length > 0
                    && grantResults[0] == PackageManager.PERMISSION_GRANTED;
            wifiDirectPermissionDenied = !granted;
            if (wifiDirectCoordinator != null) {
                wifiDirectCoordinator.onPermissionResult(granted);
            }
            if (hasPendingTakeover()) {
                // P2P is an optimization. A denial must still allow the LAN route.
                requestNextTakeoverPermission();
            } else if (!granted) {
                Toast.makeText(this, "未允许附近设备发现，将继续使用局域网连接",
                        Toast.LENGTH_LONG).show();
            }
            return;
        }
        if (requestCode == CAST_LOCAL_NETWORK_PERMISSION_REQUEST) {
            localNetworkPermissionRequestInFlight = false;
            boolean granted = grantResults.length > 0
                    && grantResults[0] == PackageManager.PERMISSION_GRANTED;
            localNetworkPermissionDenied = !granted;
            boolean openManagementAfterGrant = pendingOpenManagementAfterLocalNetwork;
            pendingOpenManagementAfterLocalNetwork = false;
            if (!granted) {
                if (hasPendingTakeover()) {
                    cancelPendingTakeover("未允许局域网设备访问权限，无法投屏");
                } else if (pendingStandaloneCastAfterLocalNetwork) {
                    pendingStandaloneCastAfterLocalNetwork = false;
                    pendingCastConfig = null;
                    CastKeepAliveService.setActive(this, false);
                    Toast.makeText(this, "未允许局域网设备访问权限，无法投屏",
                            Toast.LENGTH_LONG).show();
                } else {
                    Toast.makeText(this, "未允许局域网设备访问，无法发现或连接电视",
                            Toast.LENGTH_LONG).show();
                }
            } else if (hasPendingTakeover()) {
                // Re-enter from a worker so the receiver transport can be checked
                // before deciding whether Wi-Fi Direct permission is needed.
                new Thread(new Runnable() {
                    @Override public void run() {
                        resumePendingTakeover();
                    }
                }, "takeover-network-preflight").start();
            } else if (pendingStandaloneCastAfterLocalNetwork) {
                continueStandaloneCastAfterLocalNetwork();
            } else if (openManagementAfterGrant) {
                openManagement();
            } else {
                discoverCastReceiver(true);
            }
            return;
        }
        if (requestCode == CAST_AUDIO_PERMISSION_REQUEST) {
            if (hasPendingTakeover()) {
                if (grantResults.length > 0
                        && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    requestNextTakeoverPermission();
                } else {
                    cancelPendingTakeover("未允许录音权限，无法捕获系统声音并投屏");
                }
                return;
            }
            if (grantResults.length > 0
                    && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                requestCastMediaProjection();
            } else {
                pendingCastConfig = null;
                castAudioPermissionDeclined = true;
                pendingRemoteAudioRequestId = -1;
                Toast.makeText(this, "未允许录音权限，带声音投屏未启动",
                        Toast.LENGTH_SHORT).show();
            }
            return;
        }
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

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == TAKEOVER_MANAGEMENT_REQUEST) {
            if (resultCode == RESULT_OK) {
                exitReceiverTakeover(false);
            } else if (isReceiverTakeoverActive()) {
                setReceiverTakeoverOverlayVisible(true);
            }
            return;
        }
        if (requestCode != CAST_MEDIA_PROJECTION_REQUEST) {
            return;
        }
        onCastAudioConsentResult(resultCode, data);
    }

    void onCastAudioConsentResult(int resultCode, Intent data) {
        final CastConfig requested = pendingCastConfig;
        pendingCastConfig = null;
        final boolean takeoverPending = hasPendingTakeover();
        // The session may have ended while the system consent UI was open.
        // A late result must never start an unrelated standalone capture.
        if (requested == null && !takeoverPending) return;
        if (resultCode != RESULT_OK || data == null
                || mediaProjectionManager == null) {
            castAudioPermissionDeclined = true;
            pendingRemoteAudioRequestId = -1;
            if (takeoverPending) {
                cancelPendingTakeover("未允许系统声音捕获，投屏未启动");
            } else {
                if (!isReceiverTakeoverActive()
                        && (webViewCastManager == null || !webViewCastManager.isRunning())) {
                    CastKeepAliveService.setActive(this, false);
                }
                Toast.makeText(this, "未允许系统声音捕获，带声音投屏未启动",
                        Toast.LENGTH_SHORT).show();
            }
            return;
        }
        MediaProjection projection = null;
        try {
            CastKeepAliveService.setProjectionActive(true);
            projection = mediaProjectionManager.getMediaProjection(resultCode, data);
            if (projection == null) throw new IOException("系统未返回声音捕获授权");
            castAudioProjection = projection;
            castAudioPermissionDeclined = false;
            if (takeoverPending) {
                resumePendingTakeover();
                return;
            }
            if (resumePendingRemoteAudio()) return;
            startWebViewCast(requested, projection);
            Toast.makeText(this, requested.audio && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                    ? "电视界面音画投送已启动" : "电视界面投送已启动",
                    Toast.LENGTH_SHORT).show();
        } catch (Exception error) {
            if (takeoverPending) {
                cancelPendingTakeover("系统声音捕获启动失败：" + safeMessage(error));
            } else {
                releaseCastAudioProjection();
                Toast.makeText(this, "电视界面投送失败：" + safeMessage(error),
                        Toast.LENGTH_LONG).show();
            }
        }
    }

    private void applyPlaylistGroups(final ChannelCatalog.Group[] customGroups)
            throws InterruptedException {
        applyPlaylistGroups(customGroups, true, false);
    }

    private void applyPlaylistGroupVisibility(final ChannelCatalog.Group[] customGroups)
            throws InterruptedException {
        applyPlaylistGroups(customGroups, false, false);
    }

    private void applyPlaylistGroups(final ChannelCatalog.Group[] customGroups,
            final boolean restartActiveCustom, final boolean remoteCatalogLoaded)
            throws InterruptedException {
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
                catalogGeneration++;
                boolean receiverRestoreApplied = !remoteCatalogLoaded
                        && restoreReceiverChannelPending
                        && restoreReceiverChannelAfterTakeover();
                if (!receiverRestoreApplied) {
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
                }
                refreshFavoriteCatalog();
                boolean takeoverSelectionApplied = remoteCatalogLoaded
                        && applyPendingTakeoverChannelSelection();
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
                boolean selectionChanged = receiverRestoreApplied
                        || takeoverSelectionApplied || !hasPlayableChannel
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
        final int loadGeneration = catalogLoadGeneration.incrementAndGet();
        new Thread(new Runnable() {
            @Override
            public void run() {
                long catalogStartedAt = SystemClock.elapsedRealtime();
                try {
                    ChannelCatalog.Group[] groups;
                    String remoteUrl = remoteCatalogUrl;
                    boolean remoteCatalogLoaded = false;
                    if (remoteUrl.length() > 0) {
                        try {
                            groups = remoteCatalogClient.loadCatalog(remoteUrl);
                            remoteCatalogLoaded = true;
                            Log.i(TAG, "Loaded remote channel catalog from " + remoteUrl);
                        } catch (IOException error) {
                            Log.w(TAG, "Remote channel catalog unavailable; using local cache",
                                    error);
                            groups = playlistManager.loadCached();
                        } catch (JSONException error) {
                            Log.w(TAG, "Remote channel catalog response is invalid; using cache",
                                    error);
                            groups = playlistManager.loadCached();
                        }
                    } else if (autoUpdateChannelList) {
                        IMediaPlayer pausedPlayer = pausePlaybackForCatalogRefresh();
                        try {
                            groups = playlistManager.updateSources(
                                    playlistManager.getSourcesJson()).groups;
                            Log.i(TAG, "Channel list refreshed automatically on cold start");
                        } catch (IOException error) {
                            Log.w(TAG, "Automatic channel list refresh failed; using cache", error);
                            groups = playlistManager.loadCached();
                        } catch (JSONException error) {
                            Log.w(TAG, "Automatic channel list source data is invalid; using cache",
                                    error);
                            groups = playlistManager.loadCached();
                        } finally {
                            resumePlaybackAfterCatalogRefresh(pausedPlayer);
                        }
                    } else {
                        IMediaPlayer pausedPlayer = playlistManager.hasCatalogSnapshot()
                                ? null : pausePlaybackForCatalogRefresh();
                        try {
                            groups = playlistManager.loadCached();
                        } finally {
                            resumePlaybackAfterCatalogRefresh(pausedPlayer);
                        }
                    }
                    if (!isFinishing()
                            && loadGeneration == catalogLoadGeneration.get()
                            && remoteUrl.equals(remoteCatalogUrl)) {
                        applyPlaylistGroups(groups, false, remoteUrl.length() > 0
                                && remoteCatalogLoaded);
                        if (remoteUrl.length() > 0 && remoteCatalogLoaded) {
                            appliedRemoteCatalogGeneration = remoteCatalogGeneration;
                        }
                    }
                    int channelCount = 0;
                    for (ChannelCatalog.Group group : groups) {
                        channelCount += group.channels.length;
                    }
                    Log.i(TAG, "Channel catalog ready in "
                            + (SystemClock.elapsedRealtime() - catalogStartedAt)
                            + " ms: " + groups.length + " groups, "
                            + channelCount + " channels");
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                } catch (RuntimeException error) {
                    Log.w(TAG, "Unable to load complete channel catalog", error);
                }
            }
        }, "channel-catalog-startup").start();
    }

    private IMediaPlayer pausePlaybackForCatalogRefresh() throws InterruptedException {
        if (Build.VERSION.SDK_INT > Build.VERSION_CODES.KITKAT
                && Runtime.getRuntime().availableProcessors() > 2) {
            return null;
        }
        final IMediaPlayer[] paused = new IMediaPlayer[1];
        final CountDownLatch applied = new CountDownLatch(1);
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                try {
                    if (player != null && prepared && player.isPlaying()) {
                        player.pause();
                        paused[0] = player;
                        showChannelBar(currentChannel().name, "正在刷新频道列表");
                    }
                } finally {
                    applied.countDown();
                }
            }
        });
        applied.await(1L, TimeUnit.SECONDS);
        return paused[0];
    }

    private void resumePlaybackAfterCatalogRefresh(final IMediaPlayer pausedPlayer) {
        if (pausedPlayer == null) {
            return;
        }
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (player == pausedPlayer && prepared && !pausedPlayer.isPlaying()) {
                    pausedPlayer.start();
                }
            }
        });
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

    private boolean restoreReceiverChannelAfterTakeover() {
        LastChannelSnapshot snapshot = receiverChannelBeforeTakeover;
        if (snapshot == null || snapshot.group == null
                || snapshot.group.channels == null
                || snapshot.group.channels.length == 0) {
            restoreReceiverChannelPending = false;
            receiverChannelBeforeTakeover = null;
            return false;
        }
        Channel wanted = snapshot.group.channels[0];
        ChannelCatalog.Group[] groups = ChannelCatalog.GROUPS;
        int matchedGroup = findGroupByTitle(groups, snapshot.group.title);
        int matchedChannel = matchedGroup < 0 ? -1
                : findRestoredReceiverChannel(groups[matchedGroup], wanted);
        if (matchedChannel < 0) {
            for (int groupIndex = 0; groupIndex < groups.length; groupIndex++) {
                if (groups[groupIndex].source == ChannelCatalog.SOURCE_FAVORITES) {
                    continue;
                }
                int channelIndex = findRestoredReceiverChannel(groups[groupIndex], wanted);
                if (channelIndex >= 0) {
                    matchedGroup = groupIndex;
                    matchedChannel = channelIndex;
                    break;
                }
            }
        }
        restoreReceiverChannelPending = false;
        receiverChannelBeforeTakeover = null;
        if (matchedGroup < 0 || matchedChannel < 0) {
            Log.w(TAG, "Unable to restore receiver channel after takeover: "
                    + wanted.name);
            return false;
        }
        currentGroupIndex = matchedGroup;
        currentChannelIndex = matchedChannel;
        browsingGroupIndex = matchedGroup;
        int sourceCount = Math.max(1, groups[matchedGroup].channels[matchedChannel].sourceCount());
        currentSourceIndex = snapshot.sourceIndex % sourceCount;
        Log.i(TAG, "Restored receiver channel after takeover group="
                + groups[matchedGroup].title + " channel="
                + groups[matchedGroup].channels[matchedChannel].name
                + " source=" + currentSourceIndex);
        return true;
    }

    private static int findRestoredReceiverChannel(ChannelCatalog.Group group,
            Channel wanted) {
        if (group == null || group.channels == null || wanted == null) {
            return -1;
        }
        for (int index = 0; index < group.channels.length; index++) {
            if (sameChannelIdentity(group.channels[index], wanted)) {
                return index;
            }
        }
        if (wanted.epgId != null && wanted.epgId.length() > 0) {
            for (int index = 0; index < group.channels.length; index++) {
                if (wanted.epgId.equals(group.channels[index].epgId)) {
                    return index;
                }
            }
        }
        for (int index = 0; index < group.channels.length; index++) {
            if (wanted.name.equals(group.channels[index].name)) {
                return index;
            }
        }
        return -1;
    }

    private boolean applyPendingTakeoverChannelSelection() {
        TakeoverChannelSelection selection = pendingTakeoverChannelSelection;
        if (selection == null || remoteCatalogUrl.length() == 0
                || !selection.sessionId.equals(remoteTakeoverSessionId)) {
            return false;
        }
        ChannelCatalog.Group[] groups = ChannelCatalog.GROUPS;
        int matchedGroup = findGroupByTitle(groups, selection.groupName);
        if (matchedGroup >= 0
                && groups[matchedGroup].source == ChannelCatalog.SOURCE_FAVORITES) {
            matchedGroup = -1;
        }
        int matchedChannel = matchedGroup < 0 ? -1
                : findTakeoverChannel(groups[matchedGroup], selection);
        if (matchedChannel < 0) {
            for (int groupIndex = 0; groupIndex < groups.length; groupIndex++) {
                if (groups[groupIndex].source == ChannelCatalog.SOURCE_FAVORITES) {
                    continue;
                }
                int channelIndex = findTakeoverChannel(groups[groupIndex], selection);
                if (channelIndex >= 0) {
                    matchedGroup = groupIndex;
                    matchedChannel = channelIndex;
                    break;
                }
            }
        }
        if (matchedChannel < 0 && selection.groupName.length() == 0
                && selection.channelName.length() == 0
                && selection.groupIndex >= 0 && selection.groupIndex < groups.length
                && selection.channelIndex >= 0
                && selection.channelIndex < groups[selection.groupIndex].channels.length) {
            matchedGroup = selection.groupIndex;
            matchedChannel = selection.channelIndex;
        }
        if (matchedGroup < 0 || matchedChannel < 0) {
            Log.w(TAG, "Controller channel is absent from receiver catalog group="
                    + selection.groupName + " channel=" + selection.channelName);
            return false;
        }
        currentGroupIndex = matchedGroup;
        currentChannelIndex = matchedChannel;
        browsingGroupIndex = matchedGroup;
        int sourceCount = Math.max(1,
                groups[matchedGroup].channels[matchedChannel].sourceCount());
        currentSourceIndex = selection.sourceIndex % sourceCount;
        pendingTakeoverChannelSelection = null;
        Log.i(TAG, "Receiver synchronized controller channel group="
                + groups[matchedGroup].title + " channel="
                + groups[matchedGroup].channels[matchedChannel].name
                + " source=" + currentSourceIndex);
        return true;
    }

    private static int findTakeoverChannel(ChannelCatalog.Group group,
            TakeoverChannelSelection selection) {
        if (group == null || group.channels == null) {
            return -1;
        }
        if (selection.channelEpgId.length() > 0) {
            for (int index = 0; index < group.channels.length; index++) {
                if (selection.channelEpgId.equals(group.channels[index].epgId)) {
                    return index;
                }
            }
        }
        if (selection.channelName.length() > 0) {
            for (int index = 0; index < group.channels.length; index++) {
                if (selection.channelName.equals(group.channels[index].name)) {
                    return index;
                }
            }
        }
        return -1;
    }

    private void selectFirstLaunchChannel() {
        int groupIndex = findGroupByTitle(
                ChannelCatalog.GROUPS, FIRST_LAUNCH_GROUP_TITLE);
        if (groupIndex < 0 || ChannelCatalog.GROUPS[groupIndex].channels.length == 0) {
            groupIndex = ChannelCatalog.firstPlayableGroupIndex();
        }
        currentGroupIndex = groupIndex;
        Channel[] channels = currentGroup().channels;
        currentChannelIndex = 0;
        for (int index = 0; index < channels.length; index++) {
            Channel channel = channels[index];
            if (FIRST_LAUNCH_CHANNEL_PID.equals(channel.yangshipinPid)
                    || FIRST_LAUNCH_CHANNEL_NUMBER.equals(channel.number)) {
                currentChannelIndex = index;
                break;
            }
        }
        currentSourceIndex = 0;
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
        if (group == null || channel == null || shouldFreezeReceiverChannelHistory()) {
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
        ChannelCatalog.Group[] groups = ChannelCatalog.GROUPS;
        if (groups == null || groups.length == 0) {
            throw new IllegalStateException("频道目录为空");
        }
        int groupIndex = currentGroupIndex;
        if (groupIndex < 0 || groupIndex >= groups.length
                || groups[groupIndex] == null
                || groups[groupIndex].channels == null
                || groups[groupIndex].channels.length == 0) {
            groupIndex = 0;
            for (int index = 0; index < groups.length; index++) {
                if (groups[index] != null && groups[index].channels != null
                        && groups[index].channels.length > 0) {
                    groupIndex = index;
                    break;
                }
            }
            currentGroupIndex = groupIndex;
        }
        ChannelCatalog.Group group = groups[groupIndex];
        if (group.channels != null && group.channels.length > 0) {
            currentChannelIndex = ChannelCatalog.wrapIndex(
                    group.channels, currentChannelIndex);
        }
        return group;
    }

    private Channel currentChannel() {
        ChannelCatalog.Group group = currentGroup();
        if (group.channels == null || group.channels.length == 0) {
            throw new IllegalStateException("当前分组没有频道");
        }
        currentChannelIndex = ChannelCatalog.wrapIndex(
                group.channels, currentChannelIndex);
        return group.channels[currentChannelIndex];
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
        switchChannel(index, 0);
    }

    private void switchChannel(int index, int sourceIndex) {
        cancelPendingRelativeSwitch();
        clearNumericChannelInput();
        resetPlaybackRecoveryState();
        ChannelCatalog.Group group = currentGroup();
        int channelIndex = ChannelCatalog.wrapIndex(group.channels, index);
        int sourceCount = Math.max(1, group.channels[channelIndex].sourceCount());
        currentSourceIndex = (sourceIndex % sourceCount + sourceCount) % sourceCount;
        triedCustomSources = 1;
        startChannel(index);
    }

    private void startChannel(int index) {
        armCrashRecovery();
        if (navigateExistingCastPage(index)) return;
        final boolean committedGestureSwitch = channelSwitchAnimating
                && (channelSwitchDirectionY != 0f || channelSwitchDirectionX != 0f);
        final CastConfig pendingReceiverCast = nextPlaybackRequestedByReceiver
                ? remoteReceiverCastConfig : null;
        stopRemoteWebViewCast();
        closeWebSource();
        if (pendingReceiverCast != null) {
            remoteReceiverCastConfig = pendingReceiverCast;
        }
        webStreamHeaders = null;
        final ChannelCatalog.Group group = currentGroup();
        currentChannelIndex = ChannelCatalog.wrapIndex(group.channels, index);
        final Channel channel = group.channels[currentChannelIndex];
        updateCastEdgeState();
        clearRemotePlaybackGateway();
        final int source = catalogSource(group, channel);
        syncPlaybackRecoveryTarget();
        saveLastChannelSnapshot(group, channel);
        configureEmbeddedResolverMode(group, channel);
        updatePlayingChannelSelection();
        final int requestId = ++playRequestId;
        remoteReceiverRequestId = nextPlaybackRequestedByReceiver ? requestId : -1;
        if (remoteReceiverRequestId < 0) {
            remoteReceiverCastConfig = null;
        }
        nextPlaybackRequestedByReceiver = false;
        if (channelSwitchAnimating
                && (channelSwitchDirectionY != 0f || channelSwitchDirectionX != 0f)) {
            channelSwitchRequestId = requestId;
            positionIncomingChannelOffscreen();
        }
        playerStartRetryCount = 0;
        legacyHardwareRetryRequestId = -1;
        clearPendingPlayer();
        releasePlayer();
        if (committedGestureSwitch) {
            discardOutgoingChannelFrame();
        }
        stallRecoveryRequestId = -1;
        resetVideoLayout();
        showLoading(channel.name, source == ChannelCatalog.SOURCE_CUSTOM
                ? customSourceStatus("正在连接") : "正在准备直播");
        if (source == ChannelCatalog.SOURCE_CUSTOM && channel.sourceCount() > 1) {
            scheduleCustomSourceTimeout(channel, requestId);
        }
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

    private boolean navigateExistingCastPage(int index) {
        if (!isCastingWebPage()) return false;
        if (nextPlaybackRequestedByReceiver && !remoteWebViewCastActive) return false;
        ChannelCatalog.Group group = currentGroup();
        if (group.channels.length == 0) return false;
        int nextIndex = ChannelCatalog.wrapIndex(group.channels, index);
        Channel channel = group.channels[nextIndex];
        String url = channel.sourceUrl(currentSourceIndex);
        if (rejectUnsupportedWebViewSource(channel, url)) {
            nextPlaybackRequestedByReceiver = false;
            return true;
        }
        // Embedded CCTV resolvers still use the ordinary native-player path.
        if (!isWebViewSource(url) || extractYangshipinPid(url) != null
                || extractCctvWebChannel(url) != null) return false;
        dispatchFlyMouseButtonUp(true);
        clearPendingPlayer();
        clearSniffedResources();
        clearWebCloseConfirmation();
        abortChannelSwitchAnimation();
        currentChannelIndex = nextIndex;
        int requestId = ++playRequestId;
        if (remoteWebViewCastActive) {
            remoteReceiverRequestId = requestId;
            remoteWebViewCastChannel = channel;
            remoteWebViewCastSourceIndex = currentSourceIndex;
        }
        nextPlaybackRequestedByReceiver = false;
        webStreamHeaders = null;
        playingDiscoveredWebStream = false;
        syncPlaybackRecoveryTarget();
        saveLastChannelSnapshot(group, channel);
        configureEmbeddedResolverMode(group, channel);
        updatePlayingChannelSelection();
        showLoading(channel.name, "正在加载网页");
        // Do not close the encoder, proxy, RTSP socket or WebView. Changing the
        // document is independent of the already-running transport session.
        webSourceView.navigateCastPage(requestId, url.substring("webview://".length()));
        ensureFlyMouseOnTop();
        return true;
    }

    private void updatePlayingChannelSelection() {
        if (channelListPanel.getVisibility() != View.VISIBLE) return;
        groupAdapter.setSelectedIndex(currentGroupIndex);
        channelAdapter.setChannelState(currentChannelIndex,
                browsingGroupIndex == currentGroupIndex ? currentChannelIndex : -1,
                currentSourceIndex);
        groupList.setSelection(currentGroupIndex);
        channelList.setSelection(currentChannelIndex);
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
        String status = prefix == null ? "" : prefix.trim();
        if (status.endsWith("·")) {
            status = status.substring(0, status.length() - 1).trim();
        }
        return status + " · 线路 " + (currentSourceIndex + 1) + "/" + count;
    }

    private boolean switchCustomSource(int offset, boolean automatic, String reason) {
        cancelPendingRelativeSwitch();
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
        if (automatic && !autoSwitchSource) {
            abortChannelSwitchAnimation();
            hideLoading();
            showChannelBar(channel.name, reason + "，请按左右方向键切换线路");
            Toast.makeText(this, "当前线路不可用，请按左右方向键切换线路",
                    Toast.LENGTH_LONG).show();
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
            resetPlaybackRecoveryState();
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

    private void dispatchFlyMouseButtonDown() {
        if (flyMouseButtonDown || flyMouseCursor == null
                || flyMouseCursor.getVisibility() != View.VISIBLE) {
            return;
        }
        flyMouseButtonDown = true;
        flyMouseButtonDownTime = SystemClock.uptimeMillis();
        flyMouseButtonLastEventTime = flyMouseButtonDownTime;
        dispatchFlyMouseMotionEvent(MotionEvent.ACTION_DOWN, flyMouseButtonDownTime);
        if (MouseButtonCompat.supported()) {
            dispatchFlyMouseMotionEvent(MotionEvent.ACTION_BUTTON_PRESS, flyMouseButtonDownTime);
        }
        root.removeCallbacks(flyMouseButtonWatchdog);
        root.postDelayed(flyMouseButtonWatchdog, FLY_MOUSE_BUTTON_STALE_TIMEOUT_MS);
    }

    private void dispatchFlyMouseButtonUp(boolean cancelled) {
        if (root != null) {
            root.removeCallbacks(flyMouseButtonWatchdog);
        }
        if (!flyMouseButtonDown) {
            return;
        }
        if (MouseButtonCompat.supported()) {
            flyMouseCancelling = cancelled;
            try {
                dispatchFlyMouseMotionEvent(MotionEvent.ACTION_BUTTON_RELEASE,
                        SystemClock.uptimeMillis());
            } finally {
                flyMouseCancelling = false;
            }
        }
        dispatchFlyMouseMotionEvent(cancelled ? MotionEvent.ACTION_CANCEL : MotionEvent.ACTION_UP,
                SystemClock.uptimeMillis());
        flyMouseButtonDown = false;
        flyMouseButtonDownTime = 0L;
        flyMouseButtonLastEventTime = 0L;
        dispatchFlyMouseMotionEvent(MotionEvent.ACTION_HOVER_MOVE, SystemClock.uptimeMillis());
        if (!cancelled && flyMouseCursor != null) {
            flyMouseCursor.pulseClick();
        }
    }

    private void dispatchFlyMouseMotionEvent(int action, long eventTime) {
        dispatchFlyMouseMotionEvent(action, eventTime, 0, 0);
    }

    private void dispatchFlyMouseMotionEvent(int action, long eventTime,
            int scrollX, int scrollY) {
        if (flyMouseCursor == null || root == null) {
            return;
        }
        long downTime = flyMouseButtonDownTime > 0L ? flyMouseButtonDownTime : eventTime;
        boolean pressed = flyMouseButtonDown && action != MotionEvent.ACTION_UP
                && action != MotionEvent.ACTION_CANCEL && action != MotionEvent.ACTION_BUTTON_RELEASE;
        MotionEvent.PointerProperties properties = new MotionEvent.PointerProperties();
        properties.id = 0;
        boolean touchAction = action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_MOVE
                || action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL;
        boolean touchFallback = touchAction && !MouseButtonCompat.supported();
        properties.toolType = touchFallback ? MotionEvent.TOOL_TYPE_FINGER : MotionEvent.TOOL_TYPE_MOUSE;
        MotionEvent.PointerCoords coords = new MotionEvent.PointerCoords();
        coords.x = flyMouseCursor.cursorX();
        coords.y = flyMouseCursor.cursorY();
        coords.pressure = pressed ? 1f : 0f;
        if (action == MotionEvent.ACTION_SCROLL) {
            float factor = 48f * getResources().getDisplayMetrics().density;
            if (Build.VERSION.SDK_INT >= 26) {
                factor = android.view.ViewConfiguration.get(this).getScaledVerticalScrollFactor();
            }
            coords.setAxisValue(MotionEvent.AXIS_VSCROLL, -scrollY / Math.max(1f, factor));
            coords.setAxisValue(MotionEvent.AXIS_HSCROLL, scrollX / Math.max(1f, factor));
        }
        MotionEvent event = MotionEvent.obtain(downTime, eventTime, action, 1,
                new MotionEvent.PointerProperties[]{properties},
                new MotionEvent.PointerCoords[]{coords}, 0,
                pressed ? MotionEvent.BUTTON_PRIMARY : 0, 1f, 1f, 0, 0,
                touchFallback ? InputDevice.SOURCE_TOUCHSCREEN : InputDevice.SOURCE_MOUSE, 0);
        if (Build.VERSION.SDK_INT >= 23 && (action == MotionEvent.ACTION_BUTTON_PRESS
                || action == MotionEvent.ACTION_BUTTON_RELEASE)) {
            if (!MouseButtonCompat.setPrimary(event)) {
                event.recycle();
                return;
            }
        }
        try {
            if (flyMouseCancelling && webSourceView != null && webSourceView.isPageVisible()
                    && webSourceView.cancelRemoteMouseButton(event)) {
                return;
            }
            // ViewGroup applies each child's inverse matrix, including the desktop
            // WebView's scale. Keep native hit-testing instead of injecting DOM JS.
            if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_MOVE
                    || action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                if (!dispatchCastEdgeTouchIfNeeded(event)) {
                    root.dispatchTouchEvent(event);
                }
            } else {
                root.dispatchGenericMotionEvent(event);
            }
        } finally {
            event.recycle();
            ensureFlyMouseOnTop();
        }
    }

    private void scheduleCustomSourceTimeout(final Channel channel, final int requestId) {
        final boolean carrierIptv = CarrierNetworkRoute.isCarrierIptvUrl(
                channel.sourceUrl(currentSourceIndex));
        final long timeoutMs = carrierIptv
                ? CARRIER_IPTV_SOURCE_TIMEOUT_MS : CUSTOM_SOURCE_TIMEOUT_MS;
        final long timeoutSeconds = timeoutMs / 1000L;
        channelBar.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (requestId != playRequestId || playbackReadyRequestId == requestId
                        || currentCatalogSource() != ChannelCatalog.SOURCE_CUSTOM
                        || currentChannel().sourceCount() <= 1) {
                    return;
                }
                if (hasRetainedWebPlayback()) {
                    showChannelBar(channel.name, "视频加载较慢，按返回键回到原网页");
                    return;
                }
                if (autoSwitchSource) {
                    switchCustomSource(1, true, "连接超过 " + timeoutSeconds + " 秒");
                } else {
                    showChannelBar(channel.name, "加载较慢，请按左右方向键切换线路");
                    Toast.makeText(MainActivity.this,
                            timeoutSeconds + " 秒仍未加载，请按左右方向键切换线路",
                            Toast.LENGTH_LONG).show();
                }
            }
        }, timeoutMs);
    }

    private String currentSourceFailureReason(String fallback) {
        String sourceUrl = currentChannel().sourceUrl(currentSourceIndex);
        return CarrierNetworkRoute.isCarrierIptvUrl(sourceUrl)
                ? "运营商内网线路连接失败，请确认已开启对应 SIM 卡的移动数据"
                : fallback;
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
                this, statefulCmgSource, lowResourceDevice,
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
        if (RemoteCatalogClient.isRemoteSource(configuredUrl)) {
            resolveRemoteSource(channel, configuredUrl, requestId);
            return;
        }
        if (!Ku9ScriptResolver.isKu9Source(configuredUrl) && ku9ScriptResolver != null) {
            ku9ScriptResolver.cancel();
        }
        if (isWebViewSource(configuredUrl)) {
            if (rejectUnsupportedWebViewSource(channel, configuredUrl)) {
                return;
            }
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
        if (Ku9ScriptResolver.isKu9Source(configuredUrl)) {
            resolveKu9Source(channel, configuredUrl, requestId);
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
                boolean directHttpMedia = directCustomSource
                        && isDirectHttpMediaSource(streamUrl);
                if (!directCustomSource) {
                    try {
                        streamUrl = liveUrlResolver.resolve(channel);
                    } catch (IOException error) {
                        Log.w(TAG, "Falling back to static HLS for " + channel.name, error);
                    }
                } else if (HttpStreamResolver.shouldResolve(streamUrl)) {
                    try {
                        HttpStreamResolver.Result result = HttpStreamResolver.resolve(streamUrl);
                        streamUrl = result.url;
                        directHttpMedia = result.directMedia;
                    } catch (final IOException error) {
                        Log.w(TAG, "Unable to resolve dynamic source " + streamUrl, error);
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                if (requestId == playRequestId) {
                                    switchCustomSource(1, true, error.getMessage());
                                }
                            }
                        });
                        return;
                    }
                }
                final String resolvedUrl = streamUrl;
                // The compact IJK build can open plain HTTP media directly, but a
                // script-style HTTP endpoint may redirect to HTTPS. Keep the final
                // HTTPS object behind the Java proxy so TLS remains available.
                final boolean resolvedDirectHttpMedia = directHttpMedia
                        && resolvedUrl != null
                        && resolvedUrl.toLowerCase(Locale.US).startsWith("http://");
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (requestId != playRequestId) {
                            return;
                        }
                        startResolvedPlayer(channel, resolvedUrl, resolvedDirectHttpMedia);
                    }
                });
            }
        }, "live-url-resolve").start();
    }

    private void resolveRemoteSource(final Channel channel, final String configuredUrl,
            final int requestId) {
        updateLoadingStatus("正在请求手机解析频道");
        showChannelBar(channel.name, customSourceStatus("正在连接手机"));
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    final RemoteCatalogClient.Result result =
                            remoteCatalogClient.resolve(configuredUrl,
                                    getResources().getDisplayMetrics().widthPixels,
                                    getResources().getDisplayMetrics().heightPixels,
                                    lowResourceDevice,
                                    controlServer == null ? ""
                                            : controlServer.getLanUrl());
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            if (requestId == playRequestId) {
                                startResolvedPlayer(channel, result.url,
                                        result.directDataSource);
                            }
                        }
                    });
                } catch (final Exception error) {
                    Log.w(TAG, "Unable to resolve remote channel " + channel.name, error);
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            if (requestId != playRequestId) {
                                return;
                            }
                            abortChannelSwitchAnimation();
                            hideLoading();
                            showChannelBar(channel.name, "手机解析失败："
                                    + (error.getMessage() == null
                                            ? "未知错误" : error.getMessage()));
                        }
                    });
                }
            }
        }, "remote-channel-resolve").start();
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
        startResolvedPlayer(channel, streamUrl, false);
    }

    private void resolveKu9Source(final Channel channel, String configuredUrl,
            final int requestId) {
        updateLoadingStatus("正在执行酷9源脚本");
        showChannelBar(channel.name, customSourceStatus("正在解析酷9源"));
        ku9ScriptResolver.resolve(requestId, channel.name, configuredUrl,
                new Ku9ScriptResolver.Callback() {
                    @Override
                    public void onResolved(int resolvedRequestId,
                            Ku9ScriptResolver.Result result) {
                        if (resolvedRequestId != playRequestId) {
                            return;
                        }
                        startResolvedPlayer(channel, result.url, result.directDataSource);
                    }

                    @Override
                    public void onFailed(int failedRequestId, String reason) {
                        if (failedRequestId != playRequestId) {
                            return;
                        }
                        abortChannelSwitchAnimation();
                        hideLoading();
                        showChannelBar(channel.name, reason);
                    }
                });
    }

    private void startResolvedPlayer(Channel channel, String streamUrl,
            boolean directHttpMedia) {
        if (prepareRemoteReceiverGateway(channel, streamUrl)) {
            return;
        }
        updateLoadingStatus("正在连接视频");
        try {
            startPlayer(channel, streamUrl, false, directHttpMedia);
        } catch (IOException error) {
            Log.e(TAG, "Unable to play " + channel.name, error);
            if (hasRetainedWebPlayback()) {
                abortChannelSwitchAnimation();
                hideLoading();
                showChannelBar(channel.name, "视频连接失败，按返回键回到原网页");
                return;
            }
            if (currentCatalogSource() == ChannelCatalog.SOURCE_CUSTOM) {
                switchCustomSource(1, true, currentSourceFailureReason("线路连接失败"));
                return;
            }
            abortChannelSwitchAnimation();
            hideLoading();
            showChannelBar(channel.name, "连接失败: " + error.getMessage());
        }
    }

    private boolean prepareRemoteReceiverGateway(Channel channel, String streamUrl) {
        if (remoteReceiverRequestId != playRequestId || channel == null
                || streamUrl == null || streamUrl.length() == 0) {
            return false;
        }
        remoteGatewayChannel = channel;
        remoteGatewayStreamUrl = streamUrl;
        remoteGatewaySourceIndex = currentSourceIndex;
        if (proxy != null) {
            proxy.setRemoteConsumer(true);
        }
        playbackReadyRequestId = playRequestId;
        abortChannelSwitchAnimation();
        hideLoading();
        showChannelBar(channel.name, "已交由接管设备播放");
        Log.i(TAG, "Remote playback gateway ready without starting phone decoder for "
                + channel.name);
        return true;
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
        startIjkPlayer(channel, streamUrl, forceSoftwareDecode,
                streamUrl != null && streamUrl.equals(directHttpMediaUrl));
    }

    private void startPlayer(final Channel channel, final String streamUrl,
            boolean forceSoftwareDecode, boolean directHttpMedia) throws IOException {
        directHttpMediaUrl = directHttpMedia ? streamUrl : null;
        startIjkPlayer(channel, streamUrl, forceSoftwareDecode, directHttpMedia);
    }

    private void startIjkPlayer(final Channel channel, final String streamUrl,
            boolean forceSoftwareDecode, boolean directHttpMedia) throws IOException {
        startIjkPlayer(channel, streamUrl, forceSoftwareDecode, directHttpMedia, null);
    }

    private void startIjkPlayer(final Channel channel, final String streamUrl,
            boolean forceSoftwareDecode, boolean directHttpMedia, int[] initialTracks) throws IOException {
        final boolean castOutput = webViewCastManager != null
                && webViewCastManager.isRunning();
        Surface requestedCastSurface = castOutput
                ? webViewCastManager.videoInputSurface() : null;
        if (castOutput ? requestedCastSurface == null || !requestedCastSurface.isValid()
                : !videoView.isSurfaceReady()) {
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
        if (initialTracks != null) {
            nextPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "ntv-initial-audio", initialTracks[0]);
            nextPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "ntv-initial-video", initialTracks[1]);
            nextPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "ntv-initial-subtitle", initialTracks[2]);
        }
        player = nextPlayer;
        final boolean customSource = currentCatalogSource() == ChannelCatalog.SOURCE_CUSTOM;
        final int sourceRequestId = playRequestId;
        final boolean softwareDecode = forceSoftwareDecode || shouldUseSoftwareDecode();
        activeSoftwareDecode = softwareDecode;
        activePlayerChannel = channel;
        activePlayerStreamUrl = streamUrl;
        if (proxy != null) {
            String savedVideo = getSharedPreferences(PREFERENCES, MODE_PRIVATE).getString(
                    MediaTrackSelection.urlKey("video", streamUrl), "");
            proxy.selectVideoVariant(savedVideo.startsWith("hls:") ? savedVideo.substring(4) : "");
        }
        final boolean realtimeCastSource = isNtVCastSource(streamUrl);
        final boolean legacyMediaCodec = Build.VERSION.SDK_INT <= Build.VERSION_CODES.KITKAT
                || lowResourceDevice
                || realtimeCastSource
                        && Build.VERSION.SDK_INT <= Build.VERSION_CODES.N_MR1;
        nextPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "mediacodec",
                softwareDecode ? 0 : 1);
        nextPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "mediacodec-hevc",
                !softwareDecode && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP ? 1 : 0);
        nextPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "mediacodec-mpeg2",
                softwareDecode ? 0 : 1);
        if (Build.VERSION.SDK_INT >= 21) DolbyAudioOutput.initialize(this);
        nextPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "ntv-audio-passthrough", 1);
        if (!softwareDecode) {
            nextPlayer.setOnMediaCodecSelectListener(
                    new IjkMediaPlayer.OnMediaCodecSelectListener() {
                        @Override
                        public String onMediaCodecSelect(IMediaPlayer mediaPlayer,
                                String mimeType, int profile, int level) {
                            if ("video/dolby-vision".equalsIgnoreCase(mimeType)) {
                                return DolbyVisionSupport.selectDecoder(profile, level);
                            }
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
        nextPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "subtitle", 1);
        requestPlaybackAudioFocus();
        float playbackVolume = isPlaybackMuted() ? 0f : 1f;
        nextPlayer.setVolume(playbackVolume, playbackVolume);
        // Keep every compressed reference frame for realtime casting. IJK's
        // generic framedrop can skip a HEVC reference before MediaCodec sees it,
        // leaving old receivers gray until the next IDR.
        nextPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "framedrop",
                realtimeCastSource ? 0 : softwareDecode ? 5 : 1);
        if (realtimeCastSource) receiverNetworkLease.acquire(this);
        nextPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "ntv-live-video",
                realtimeCastSource ? 1 : 0);
        nextPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "ntv-trace-latency",
                realtimeCastSource && BuildConfig.DEBUG && BuildConfig.CAST_LATENCY_TRACE ? 1 : 0);
        final boolean remoteCatalogPlayback = isRemoteCatalogPlayback();
        nextPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "packet-buffering",
                realtimeCastSource ? 0 : 1);
        final boolean cctvSource = isActiveCctvWebSource();
        final boolean directThirdPartyHls = shouldPlayThirdPartyHlsDirectly(streamUrl);
        final boolean genericThirdPartyHls = customSource && !cctvSource
                && isHttpHlsSource(streamUrl);
        nextPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "min-frames",
                realtimeCastSource ? 2
                        : remoteCatalogPlayback ? 20
                        : cctvSource ? cctvIjkMinFrames()
                        : (genericThirdPartyHls ? 20 : 60));
        nextPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "infbuf", 0);
        // The cast sender already starts both RTP tracks from the same session.
        // Waiting for IJK's generic A/V startup gate can retain the first burst on
        // old televisions before either clock begins advancing.
        nextPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "sync-av-start",
                realtimeCastSource ? 0 : 1);
        nextPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "max_cached_duration",
                realtimeCastSource ? 250
                        : remoteCatalogPlayback ? 30000
                        : cctvSource ? 45000
                        : (genericThirdPartyHls ? 30000 : 45000));
        nextPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "first-high-water-mark-ms",
                realtimeCastSource ? 40
                        : remoteCatalogPlayback ? 800
                        : cctvSource ? cctvIjkFirstBufferMs()
                        : (genericThirdPartyHls ? 2000 : 5000));
        nextPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "next-high-water-mark-ms",
                realtimeCastSource ? 80 : remoteCatalogPlayback ? 3000 : 5000);
        nextPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "last-high-water-mark-ms",
                realtimeCastSource ? 150 : remoteCatalogPlayback ? 5000 : 5000);
        nextPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "reconnect", 1);
        if (directThirdPartyHls) {
            // Let FFmpeg keep the CDN connection alive between playlist and segment
            // requests. The old Java proxy opened additional upstream connections.
            nextPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT,
                    "http_persistent", 1);
            nextPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT,
                    "multiple_requests", 1);
        }
        if (isRtspSource(streamUrl)) {
            // TCP is substantially more tolerant of congested Wi-Fi and is the default.
            // Keep UDP available for low-latency LAN cameras and multicast gateways.
            // Old receivers can stop reading the interleaved TCP socket while their
            // MediaCodec is busy. That back-pressures the phone for 1-2 seconds and
            // makes the cursor lag even though encode/decode queues stay short.
            // nTv casting is local and event-driven, so use UDP on legacy/low-resource
            // receivers; leave ordinary RTSP sources and modern devices configurable.
            String effectiveRtspTransport = realtimeCastSource
                    && (Build.VERSION.SDK_INT <= Build.VERSION_CODES.N_MR1
                            || lowResourceDevice)
                    ? "udp" : rtspTransport;
            nextPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT,
                    "rtsp_transport", effectiveRtspTransport);
        }
        /* Every channel switch creates a localhost proxy on a new port. IJK 0.8.8
         * can retain an empty localhost DNS-cache entry from the closed proxy,
         * making the first connection to the new port fail spuriously. */
        nextPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "dns_cache_clear", 1);
        nextPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "http-detect-range-support", 0);
        boolean genericThirdPartySource = customSource && !cctvSource
                && !realtimeCastSource && !remoteCatalogPlayback;
        nextPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "probesize",
                realtimeCastSource ? 128 * 1024
                        : remoteCatalogPlayback ? 1024 * 1024
                        : genericThirdPartySource ? 4 * 1024 * 1024 : 256 * 1024);
        if (realtimeCastSource) {
            // A cast IDR arrives as a short RTP burst. KitKat's default UDP receive
            // buffer is only about 160 KiB and can lose the middle of that burst,
            // producing green/gray macroblocks until the next key frame. This is a
            // socket buffer only; packet-buffering remains disabled below.
            nextPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT,
                    "buffer_size", 512 * 1024);
            nextPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT,
                    "analyzeduration", 100000);
            nextPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT,
                    "fflags", "nobuffer");
            nextPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT,
                    "max_delay", 100000);
            nextPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT,
                    "reorder_queue_size", 0);
        }
        if (genericThirdPartySource) {
            // Legacy TS services may announce audio late or begin between GOPs. The
            // previous 256 KiB probe could therefore produce picture without sound.
            nextPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT,
                    "analyzeduration", 3000000);
        } else if (remoteCatalogPlayback) {
            nextPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT,
                    "analyzeduration", 1500000);
        }
        /* Start at the first segment exposed by the selected startup policy. Using a
         * negative index would discard already prepared data in the two-segment modes. */
        nextPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "live_start_index",
                cctvSource ? 0 : -3);
        Surface activeCastSurface = castOutput
                ? webViewCastManager.videoInputSurface() : null;
        if (!castOutput) {
            videoSurfaceHolder = videoView.getVideoSurfaceHolder();
        }
        if (castOutput ? activeCastSurface == null || !activeCastSurface.isValid()
                : videoSurfaceHolder == null) {
            queuePendingPlayer(channel, streamUrl, forceSoftwareDecode);
            nextPlayer.release();
            player = null;
            return;
        }
        // The cast path feeds the GPU compositor directly; no decoder frame is read back.
        if (castOutput) {
            nextPlayer.setSurface(activeCastSurface);
        } else {
            nextPlayer.setDisplay(videoSurfaceHolder);
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
        nextPlayer.setOnTimedTextListener(new IMediaPlayer.OnTimedTextListener() {
            @Override
            public void onTimedText(IMediaPlayer mediaPlayer, IjkTimedText text) {
                final IMediaPlayer timedTextPlayer = mediaPlayer;
                final String value = text == null ? "" : text.getText();
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (player != timedTextPlayer || subtitleText == null || selectedHlsSubtitle >= 0 || !subtitlesEnabled()) {
                            return;
                        }
                        if (value == null || value.trim().length() == 0) {
                            clearSubtitleText();
                            return;
                        }
                        subtitleText.setText(value);
                        subtitleText.setVisibility(View.VISIBLE);
                    }
                });
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
                nextPlayer.setSpeed(playbackSpeed);
                mediaPlayer.start();
                mediaTrackManifest = proxy == null ? null : proxy.mediaTracks(streamUrl);
                restoreRememberedTracks(nextPlayer, channel);
                if (trackResumePlayer == nextPlayer) {
                    if (trackResumePosition > 0) nextPlayer.seekTo(trackResumePosition);
                    if (!trackResumePlaying) nextPlayer.pause();
                    trackResumePlayer = null;
                }
                root.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        restoreRememberedTracks(nextPlayer, channel);
                    }
                }, 500L);
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
                    lastVideoOutputAt = SystemClock.elapsedRealtime();
                    playbackReadyRequestId = sourceRequestId;
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
                    channelBar.postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            if (buffering && eventId == bufferingEventId
                                    && requestId == playRequestId
                                    && player == watchedPlayer
                                    && watchedPlayer.isPlaying()
                                    && (videoRenderingStarted
                                            || playbackProgressObserved
                                            || playbackRecoveryAttempts > 0)) {
                                recoverStalledPlayback(requestId, watchedPlayer,
                                        "buffering for "
                                                + PLAYBACK_BUFFERING_RECOVERY_MS + "ms");
                            }
                        }
                    }, PLAYBACK_BUFFERING_RECOVERY_MS);
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
        if (realtimeCastSource) nextPlayer.setOnCompletionListener(new IMediaPlayer.OnCompletionListener() {
            @Override public void onCompletion(final IMediaPlayer endedPlayer) {
                // Live socket closure can be reported as EOF, not onError.
                if (sourceRequestId == playRequestId && player == endedPlayer) {
                    recoverStalledPlayback(sourceRequestId, endedPlayer, "cast connection reached EOF");
                }
            }
        });
        nextPlayer.setOnErrorListener(new IMediaPlayer.OnErrorListener() {
            @Override
            public boolean onError(IMediaPlayer mediaPlayer, int what, int extra) {
                if (player == mediaPlayer) {
                    if (what == -20001 || extra == -20001) {
                        abortChannelSwitchAnimation();
                        hideLoading();
                        showChannelBar(channel.name, "设备不支持此 Dolby Vision 格式，请切换普通视频轨道或线路");
                        return true;
                    }
                    if (videoRenderingStarted || playbackProgressObserved
                            || playbackRecoveryAttempts > 0) {
                        final IMediaPlayer failedPlayer = mediaPlayer;
                        final int errorWhat = what;
                        final int errorExtra = extra;
                        channelBar.postDelayed(new Runnable() {
                            @Override
                            public void run() {
                                if (sourceRequestId == playRequestId
                                        && player == failedPlayer) {
                                    recoverStalledPlayback(sourceRequestId, failedPlayer,
                                            "player error " + errorWhat + "/" + errorExtra);
                                }
                            }
                        }, 1000L);
                        return true;
                    }
                    if (customSource) {
                        if (hasRetainedWebPlayback()) {
                            abortChannelSwitchAnimation();
                            hideLoading();
                            showChannelBar(channel.name,
                                    "视频播放失败，按返回键回到原网页");
                            return true;
                        }
                        final IMediaPlayer failedPlayer = mediaPlayer;
                        channelBar.post(new Runnable() {
                            @Override
                            public void run() {
                                if (sourceRequestId == playRequestId
                                        && player == failedPlayer) {
                                    switchCustomSource(1, true,
                                            currentSourceFailureReason("线路播放失败"));
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
        // Native RTMP/RTSP sources can be handed to IJK directly. HLS keeps using the
        // lightweight generic proxy because the compact IJK profile has no crypto
        // protocol and encryption cannot be known until the playlist has been read.
        boolean directDataSource = isNativeStreamingSource(streamUrl)
                || directThirdPartyHls || directHttpMedia;
        if (directThirdPartyHls) {
            Log.i(TAG, "Opening third-party HLS directly: " + streamUrl);
        } else if (directHttpMedia) {
            Log.i(TAG, "Opening resolved HTTP media directly: " + streamUrl);
        }
        nextPlayer.setDataSource(directDataSource ? streamUrl : proxy.proxyUrl(streamUrl));
        nextPlayer.prepareAsync();
    }

    private boolean shouldPlayThirdPartyHlsDirectly(String streamUrl) {
        /* The compact IJK profile omits FFmpeg's crypto protocol. Encryption is only
         * known after reading the playlist, so an HTTP URL cannot safely bypass the
         * proxy: an EXT-X-KEY/AES-128 source would otherwise fail in IJK. The generic
         * proxy path now streams clear segments and adds no live-edge holdback. */
        return false;
    }

    private boolean isDirectThirdPartyRecordingSource(String streamUrl) {
        return currentCatalogSource() == ChannelCatalog.SOURCE_CUSTOM
                && !activeEmbeddedCctvResolver && !activeEmbeddedYangshipinResolver
                && webStreamHeaders == null && isHttpHlsSource(streamUrl)
                && !HlsProxyServer.needsSpecialDecrypt(streamUrl);
    }

    private static boolean requiresParallelHlsPrefetch(String streamUrl) {
        if (streamUrl == null) {
            return false;
        }
        String value = streamUrl.toLowerCase(Locale.US);
        // This IPTV server family publishes ~5 MB/5 s segments but throttles each
        // connection. Two bounded Java downloads keep one upcoming segment ready.
        return value.contains(":9901/tsfile/live/") || value.contains("key=txiptv");
    }

    private static boolean isHttpHlsSource(String sourceUrl) {
        if (sourceUrl == null) {
            return false;
        }
        String value = sourceUrl.trim().toLowerCase(Locale.US);
        if (!value.startsWith("http://") && !value.startsWith("https://")) {
            return false;
        }
        return value.contains(".m3u8") || value.contains("format=m3u8")
                || value.contains("type=m3u8");
    }

    /** Plain HTTP media is already supported natively by the compact IJK build. */
    private static boolean isDirectHttpMediaSource(String sourceUrl) {
        if (sourceUrl == null) {
            return false;
        }
        String value = sourceUrl.trim().toLowerCase(Locale.US);
        if (!value.startsWith("http://")) {
            // This IJK profile intentionally relies on the Java proxy for HTTPS/TLS.
            return false;
        }
        String path = Uri.parse(value).getPath();
        if (path == null) {
            return false;
        }
        return path.endsWith(".flv") || path.endsWith(".mp4")
                || path.endsWith(".mkv") || path.endsWith(".webm")
                || path.endsWith(".mov") || path.endsWith(".avi")
                || path.endsWith(".ts") || path.endsWith(".aac")
                || path.endsWith(".mp3");
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
        Set<String> cached = cachedHardwareDecoderNames;
        if (cached != null) {
            return cached;
        }
        Set<String> decoders = new LinkedHashSet<String>();
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.JELLY_BEAN) {
            cachedHardwareDecoderNames = decoders;
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
        cachedHardwareDecoderNames = decoders;
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

    private static String sanitizeUiScaleMode(String mode) {
        if (UI_SCALE_STANDARD.equals(mode) || UI_SCALE_LARGE.equals(mode)
                || UI_SCALE_EXTRA_LARGE.equals(mode)
                || UI_SCALE_EXTRA_EXTRA_LARGE.equals(mode)) {
            return mode;
        }
        return UI_SCALE_AUTO;
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

    private static boolean isRtspSource(String sourceUrl) {
        return sourceUrl != null
                && sourceUrl.trim().toLowerCase(Locale.US).startsWith("rtsp://");
    }

    private static boolean isNativeStreamingSource(String sourceUrl) {
        return isRtmpSource(sourceUrl) || isRtspSource(sourceUrl);
    }

    private boolean isRemoteCatalogPlayback() {
        if (currentCatalogSource() != ChannelCatalog.SOURCE_CUSTOM) {
            return false;
        }
        Channel channel = currentChannel();
        return channel != null && RemoteCatalogClient.isRemoteSource(
                channel.sourceUrl(currentSourceIndex));
    }

    private static boolean isRemoteDirectSource(String sourceUrl) {
        if (sourceUrl == null) {
            return false;
        }
        String value = sourceUrl.trim().toLowerCase(Locale.US);
        return isNativeStreamingSource(value) || isDirectHttpMediaSource(value)
                || value.startsWith("udp://") || value.startsWith("rtp://");
    }

    private static boolean isNtVCastSource(String sourceUrl) {
        if (!isRtspSource(sourceUrl)) {
            return false;
        }
        try {
            String path = Uri.parse(sourceUrl).getPath();
            return path != null && ("/cast".equals(path) || path.endsWith("/cast"));
        } catch (RuntimeException error) {
            return false;
        }
    }

    private static String sanitizeRtspTransport(String transport) {
        return RTSP_TRANSPORT_UDP.equals(transport)
                ? RTSP_TRANSPORT_UDP : RTSP_TRANSPORT_TCP;
    }

    private static String sanitizeWebViewResolution(String mode) {
        if (WEB_VIEW_RESOLUTION_720P.equals(mode)
                || WEB_VIEW_RESOLUTION_1080P.equals(mode)
                || WEB_VIEW_RESOLUTION_2K.equals(mode)
                || WEB_VIEW_RESOLUTION_4K.equals(mode)) {
            return mode;
        }
        // Migrate the removed 480P value, and use 720P for missing/invalid settings.
        return WEB_VIEW_RESOLUTION_720P;
    }

    private static String sanitizeWebViewUserAgent(String mode) {
        if (WEB_VIEW_USER_AGENT_MACOS.equals(mode)
                || WEB_VIEW_USER_AGENT_IPAD.equals(mode)
                || WEB_VIEW_USER_AGENT_NATIVE.equals(mode)) {
            return mode;
        }
        return WEB_VIEW_USER_AGENT_WINDOWS;
    }

    private static float sanitizeWebViewPageScale(float scale) {
        return Math.max(0.5f, Math.min(3f, scale));
    }

    private static String sanitizeWebCastResolution(String resolution) {
        if (WEB_CAST_RESOLUTION_1080P.equals(resolution)
                || WEB_CAST_RESOLUTION_2K.equals(resolution)
                || WEB_CAST_RESOLUTION_4K.equals(resolution)) {
            return resolution;
        }
        return WEB_CAST_RESOLUTION_720P;
    }

    private static int sanitizeWebCastFps(int fps) {
        return fps == 30 || fps == 60 || fps == 120 ? fps : 25;
    }

    private static String sanitizeWebCastCodec(String codec) {
        return CastConfig.CODEC_H265.equals(codec)
                ? CastConfig.CODEC_H265 : CastConfig.CODEC_H264;
    }

    private static int sanitizeWebCastBitrate(int bitrateMbps) {
        return Math.max(2, Math.min(40, bitrateMbps));
    }

    private static String sanitizeDateTimeFormat(String format) {
        if (DATE_TIME_TIME_FIRST.equals(format) || DATE_TIME_WEEK_FIRST.equals(format)
                || DATE_TIME_ONLY.equals(format)) {
            return format;
        }
        return DATE_TIME_DATE_FIRST;
    }

    private String formatDateTime(Date date) {
        String pattern;
        if (DATE_TIME_ONLY.equals(dateTimeFormat)) {
            pattern = "HH:mm:ss";
        } else if (DATE_TIME_TIME_FIRST.equals(dateTimeFormat)) {
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

    private static int sanitizeSubtitleSizePercent(int percent) {
        if (percent == 75 || percent == 100 || percent == 125
                || percent == 150 || percent == 200) {
            return percent;
        }
        return 100;
    }

    private static String sanitizeSubtitlePosition(String position) {
        if (SUBTITLE_POSITION_TOP.equals(position)
                || SUBTITLE_POSITION_CENTER.equals(position)
                || SUBTITLE_POSITION_MANUAL.equals(position)) {
            return position;
        }
        return SUBTITLE_POSITION_BOTTOM;
    }

    private static String sanitizeSubtitleShadow(String shadow) {
        if (SUBTITLE_SHADOW_NONE.equals(shadow)
                || SUBTITLE_SHADOW_STRONG.equals(shadow)) {
            return shadow;
        }
        return SUBTITLE_SHADOW_STANDARD;
    }

    private void applySubtitleStyle() {
        if (subtitleText == null) {
            return;
        }
        float sizeSp = 28f * subtitleSizePercent / 100f;
        subtitleText.setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp);
        if (SUBTITLE_SHADOW_NONE.equals(subtitleShadow)) {
            subtitleText.setShadowLayer(0f, 0f, 0f, Color.TRANSPARENT);
        } else if (SUBTITLE_SHADOW_STRONG.equals(subtitleShadow)) {
            subtitleText.setShadowLayer(7f, 2.5f, 2.5f, Color.BLACK);
        } else {
            subtitleText.setShadowLayer(4f, 2f, 2f, Color.BLACK);
        }
        FrameLayout.LayoutParams params = (FrameLayout.LayoutParams)
                subtitleText.getLayoutParams();
        params.gravity = Gravity.CENTER_HORIZONTAL;
        params.topMargin = 0;
        params.bottomMargin = 0;
        int margin = Math.round(52f * getResources().getDisplayMetrics().density);
        if (SUBTITLE_POSITION_TOP.equals(subtitlePosition)) {
            params.gravity |= Gravity.TOP;
            params.topMargin = margin;
        } else if (SUBTITLE_POSITION_CENTER.equals(subtitlePosition)
                || SUBTITLE_POSITION_MANUAL.equals(subtitlePosition)) {
            params.gravity |= Gravity.CENTER_VERTICAL;
        } else {
            params.gravity |= Gravity.BOTTOM;
            params.bottomMargin = margin;
        }
        subtitleText.setLayoutParams(params);
        applySubtitleManualOffset();
    }

    private void applySubtitleManualOffset() {
        if (subtitleText == null) return;
        float translation = 0;
        if (SUBTITLE_POSITION_MANUAL.equals(subtitlePosition)) {
            View parent = (View) subtitleText.getParent();
            // Position against the whole playback view, not the preset 52dp inset.
            // Subtract the actual layout top so padding/gravity cannot leave an extra gap.
            translation = SubtitlePlacement.top(parent.getHeight(), subtitleText.getHeight(),
                    subtitleOffsetPercent) - subtitleText.getTop();
        }
        subtitleText.setTranslationY(translation);
    }

    private void selectMediaTrack(IjkMediaPlayer mediaPlayer, int index, boolean audio)
            throws IOException {
        if (!audio && index >= HlsMediaTracks.SUBTITLE_BASE) {
            selectHlsSubtitle(mediaPlayer,index);
            HlsMediaTracks.Track track = mediaTrackManifest.subtitles.get(index-HlsMediaTracks.SUBTITLE_BASE);
            rememberMediaTrack(false, MediaTrackSelection.subtitleChoice(track.language, track.name));
            return;
        }
        ITrackInfo[] tracks = mediaPlayer.getTrackInfo();
        if (!audio && index < 0) {
            setSubtitlesEnabled(false);
            return;
        }
        if (tracks == null || index < 0 || index >= tracks.length
                || tracks[index] == null) {
            throw new IOException(audio ? "所选音轨不存在" : "所选字幕不存在");
        }
        int type = tracks[index].getTrackType();
        if (audio && type != ITrackInfo.MEDIA_TRACK_TYPE_AUDIO) {
            throw new IOException("所选轨道不是音轨");
        }
        if (!audio && type != ITrackInfo.MEDIA_TRACK_TYPE_SUBTITLE
                && type != ITrackInfo.MEDIA_TRACK_TYPE_TIMEDTEXT) {
            throw new IOException("所选轨道不是字幕");
        }
        if (mediaPlayer.getSelectedTrack(type) == index) {
            if (!audio) stopHlsSubtitle();
            rememberMediaTrack(audio, audio ? trackSignature(tracks[index])
                    : MediaTrackSelection.subtitleChoice(tracks[index].getLanguage(), tracks[index].getInfoInline()));
            return;
        }
        long resumePosition = mediaPlayer.getDuration() > 0 ? mediaPlayer.getCurrentPosition() : -1;
        boolean resumePlaying = mediaPlayer.isPlaying();
        // IJK replaces the selected stream itself. Deselecting first loses the
        // running audio clock (and the fallback track if the new decoder fails).
        mediaPlayer.selectTrack(index);
        if (mediaPlayer.getSelectedTrack(type) != index) {
            throw new IOException(audio ? "设备暂不支持此音轨，已保留原音轨"
                    : "设备暂不支持此字幕，已保留原字幕");
        }
        if (!audio) stopHlsSubtitle();
        // A previously discarded embedded subtitle stream starts at the demuxer's
        // read-ahead position. Seek VOD back to the current frame for its first cue.
        // Sidecar HLS subtitles return above and never seek the A/V player.
        restoreTrackProgress(mediaPlayer, resumePosition, resumePlaying);
        scheduleMediaTrackRecovery(mediaPlayer, resumePlaying);
        rememberMediaTrack(audio, audio ? trackSignature(tracks[index])
                : MediaTrackSelection.subtitleChoice(tracks[index].getLanguage(), tracks[index].getInfoInline()));
    }

    private static void deselectTrackType(IjkMediaPlayer mediaPlayer, int type) {
        try {
            int selected = mediaPlayer.getSelectedTrack(type);
            if (selected >= 0) {
                mediaPlayer.deselectTrack(selected);
            }
        } catch (RuntimeException error) {
            Log.w(TAG, "Unable to deselect media track type=" + type, error);
        }
    }

    private void rememberMediaTrack(boolean audio, String signature) {
        android.content.SharedPreferences.Editor editor = getSharedPreferences(PREFERENCES, MODE_PRIVATE).edit()
                .putString(mediaTrackPreferenceKey(audio), signature);
        if (!audio) editor.putBoolean(MediaTrackSelection.SUBTITLE_ENABLED_KEY, true);
        editor.apply();
    }

    private boolean subtitlesEnabled() {
        return getSharedPreferences(PREFERENCES, MODE_PRIVATE).getBoolean(
                MediaTrackSelection.SUBTITLE_ENABLED_KEY, true);
    }

    private void setSubtitlesEnabled(boolean enabled) {
        getSharedPreferences(PREFERENCES, MODE_PRIVATE).edit().putBoolean(
                MediaTrackSelection.SUBTITLE_ENABLED_KEY, enabled).apply();
        if (!enabled) {
            stopHlsSubtitle();
            if (player != null && prepared) {
                deselectTrackType(player, ITrackInfo.MEDIA_TRACK_TYPE_SUBTITLE);
                deselectTrackType(player, ITrackInfo.MEDIA_TRACK_TYPE_TIMEDTEXT);
            }
        } else if (player != null && prepared) {
            int previous = player.getSelectedTrack(ITrackInfo.MEDIA_TRACK_TYPE_TIMEDTEXT);
            long position = player.getDuration() > 0 ? player.getCurrentPosition() : -1;
            boolean playing = player.isPlaying();
            restoreRememberedSubtitles(player, activePlayerChannel);
            int selected = player.getSelectedTrack(ITrackInfo.MEDIA_TRACK_TYPE_TIMEDTEXT);
            if (selected >= 0 && selected != previous) {
                restoreTrackProgress(player, position, playing);
                scheduleMediaTrackRecovery(player, playing);
            }
        }
    }

    private String mediaTrackPreferenceKey(boolean audio) {
        return audio ? MediaTrackSelection.urlKey("audio", activePlayerStreamUrl)
                : MediaTrackSelection.SUBTITLE_KEY;
    }

    private static String trackSignature(ITrackInfo track) {
        return MediaTrackSelection.signature(track.getLanguage(), track.getInfoInline());
    }

    private void restoreRememberedTracks(final IjkMediaPlayer mediaPlayer,
            final Channel channel) {
        if (mediaPlayer == null || channel == null || player != mediaPlayer) {
            return;
        }
        restoreRememberedTrack(mediaPlayer, channel, true);
        restoreRememberedVideoTrack(mediaPlayer);
        restoreRememberedSubtitles(mediaPlayer, channel);
    }

    private void restoreRememberedSubtitles(final IjkMediaPlayer mediaPlayer,
            final Channel channel) {
        if (mediaPlayer == null || channel == null || player != mediaPlayer) return;
        if (!subtitlesEnabled()) {
            stopHlsSubtitle();
            deselectTrackType(mediaPlayer, ITrackInfo.MEDIA_TRACK_TYPE_SUBTITLE);
            deselectTrackType(mediaPlayer, ITrackInfo.MEDIA_TRACK_TYPE_TIMEDTEXT);
            return;
        }
        if (mediaTrackManifest != null && !mediaTrackManifest.subtitles.isEmpty()) {
            if (selectedHlsSubtitle >= 0) return;
            String wanted=getSharedPreferences(PREFERENCES,MODE_PRIVATE).getString(mediaTrackPreferenceKey(false),"");
            if(MEDIA_TRACK_DISABLED.equals(wanted))return;
            int selected=0;
            for(int i=0;i<mediaTrackManifest.subtitles.size();i++) {
                HlsMediaTracks.Track track=mediaTrackManifest.subtitles.get(i);
                if(track.defaultTrack)selected=i;
            }
            int bestMatch = 0;
            for(int i=0;i<mediaTrackManifest.subtitles.size();i++) {
                HlsMediaTracks.Track track=mediaTrackManifest.subtitles.get(i);
                int match=MediaTrackSelection.subtitleMatch(wanted,track.language,track.name);
                if(match>bestMatch){selected=i;bestMatch=match;}
            }
            try { selectHlsSubtitle(mediaPlayer,HlsMediaTracks.SUBTITLE_BASE+selected); }
            catch(IOException error){Log.w(TAG,"Unable to start HLS subtitle",error);}
        } else restoreRememberedTrack(mediaPlayer, channel, false);
    }

    private void restoreRememberedTrack(IjkMediaPlayer mediaPlayer, Channel channel,
            boolean audio) {
        String wanted = getSharedPreferences(PREFERENCES, MODE_PRIVATE).getString(
                mediaTrackPreferenceKey(audio), "");
        if (audio && wanted.length() == 0) {
            return;
        }
        if (!audio && MEDIA_TRACK_DISABLED.equals(wanted)) {
            deselectTrackType(mediaPlayer, ITrackInfo.MEDIA_TRACK_TYPE_SUBTITLE);
            deselectTrackType(mediaPlayer, ITrackInfo.MEDIA_TRACK_TYPE_TIMEDTEXT);
            clearSubtitleText();
            return;
        }
        ITrackInfo[] tracks = mediaPlayer.getTrackInfo();
        if (tracks == null) {
            return;
        }
        for (int index = 0; index < tracks.length; index++) {
            ITrackInfo track = tracks[index];
            if (track == null || (audio ? !wanted.equals(trackSignature(track))
                    : !wanted.isEmpty() && MediaTrackSelection.subtitleMatch(wanted, track.getLanguage(), track.getInfoInline()) == 0)) {
                continue;
            }
            int type = track.getTrackType();
            if (audio && type == ITrackInfo.MEDIA_TRACK_TYPE_AUDIO
                    || !audio && (type == ITrackInfo.MEDIA_TRACK_TYPE_SUBTITLE
                            || type == ITrackInfo.MEDIA_TRACK_TYPE_TIMEDTEXT)) {
                try {
                    if(mediaPlayer.getSelectedTrack(type)==index)return;
                    // Restoring a preference must not replace the user's global language choice.
                    mediaPlayer.selectTrack(index);
                } catch (RuntimeException error) {
                    Log.w(TAG, "Unable to restore remembered media track", error);
                }
                return;
            }
        }
    }

    private void selectVideoTrack(IjkMediaPlayer activePlayer,int index) throws IOException {
        if(index>=HlsMediaTracks.VIDEO_BASE) {
            int position=index-HlsMediaTracks.VIDEO_BASE;
            if(proxy==null||mediaTrackManifest==null||position<0||position>=mediaTrackManifest.videos.size())
                throw new IOException("所选视轨不存在");
            HlsMediaTracks.Track track=mediaTrackManifest.videos.get(position);
            rememberVideoTrack("hls:" + track.url);
            if(track.url.equals(mediaTrackManifest.selectedVideoUrl))return;
            long resume=activePlayer.getDuration()>0?activePlayer.getCurrentPosition():0;
            boolean playing=activePlayer.isPlaying();
            Channel channel=activePlayerChannel;
            String source=activePlayerStreamUrl;
            proxy.selectVideoVariant(track.url);
            showLoading(channel.name,"正在切换视轨");
            startPlayer(channel,source);
            trackResumePlayer=player;trackResumePosition=resume;trackResumePlaying=playing;
            return;
        }
        ITrackInfo[] tracks=activePlayer.getTrackInfo();
        if(tracks==null||index<0||index>=tracks.length||tracks[index]==null
                ||tracks[index].getTrackType()!=ITrackInfo.MEDIA_TRACK_TYPE_VIDEO)
            throw new IOException("所选视轨不存在");
        if(activePlayer.getSelectedTrack(ITrackInfo.MEDIA_TRACK_TYPE_VIDEO)!=index) {
            long position = activePlayer.getDuration() > 0 ? activePlayer.getCurrentPosition() : -1;
            boolean playing = activePlayer.isPlaying();
            activePlayer.selectTrack(index);
            if (activePlayer.getSelectedTrack(ITrackInfo.MEDIA_TRACK_TYPE_VIDEO) != index)
                throw new IOException("设备暂不支持此视轨，已保留原视轨");
            restoreTrackProgress(activePlayer, position, playing);
            scheduleMediaTrackRecovery(activePlayer, playing);
        }
        rememberVideoTrack("native:" + trackSignature(tracks[index]));
    }

    private static void restoreTrackProgress(IjkMediaPlayer mediaPlayer, long position, boolean playing) {
        if (position >= 0) mediaPlayer.seekTo(position);
        if (!playing) mediaPlayer.pause();
        else if (!mediaPlayer.isPlaying()) mediaPlayer.start();
    }

    private void scheduleMediaTrackRecovery(final IjkMediaPlayer changed, boolean wasPlaying) {
        final int generation = ++mediaTrackChangeGeneration;
        if (!wasPlaying) return;
        final Channel channel = activePlayerChannel;
        final String url = activePlayerStreamUrl;
        final boolean software = activeSoftwareDecode;
        final boolean direct = url != null && url.equals(directHttpMediaUrl);
        final int[] tracks = {
                changed.getSelectedTrack(ITrackInfo.MEDIA_TRACK_TYPE_AUDIO),
                changed.getSelectedTrack(ITrackInfo.MEDIA_TRACK_TYPE_VIDEO),
                changed.getSelectedTrack(ITrackInfo.MEDIA_TRACK_TYPE_TIMEDTEXT) };
        final long started = SystemClock.elapsedRealtime();
        root.postDelayed(new Runnable() {
            long position = changed.getCurrentPosition();
            long progressAt = started;
            @Override public void run() {
                if (generation != mediaTrackChangeGeneration || player != changed || !prepared
                        || channel == null || url == null || !changed.isPlaying()) return;
                long now = SystemClock.elapsedRealtime(), current = changed.getCurrentPosition();
                boolean videoAdvancing = tracks[1] < 0 || changed.getVideoOutputFramesPerSecond() > 1f;
                if (current > position + 100 && videoAdvancing) progressAt = now;
                position = current;
                if (now - progressAt >= 8000L) {
                    // Some legacy MediaCodec/HLS demuxers get stuck after a seek/track
                    // change. Reopen this stream once, not another channel, and retain
                    // VOD position and the chosen tracks from the first decoded frame.
                    try {
                        long resume = changed.getDuration() > 0 ? Math.max(0L, current) : 0;
                        Log.w(TAG, "Recovering stalled media track switch at " + resume);
                        showLoading(channel.name, "正在恢复轨道播放");
                        startIjkPlayer(channel, url, software, direct, tracks);
                        trackResumePlayer = player;
                        trackResumePosition = resume;
                        trackResumePlaying = true;
                    } catch (IOException error) {
                        Log.w(TAG, "Unable to recover media track switch", error);
                        updateLoadingStatus("轨道恢复失败，请切换其他音轨或线路");
                    }
                    return;
                }
                if (now - started < 20000L) root.postDelayed(this, 800L);
            }
        }, 800L);
    }

    private void rememberVideoTrack(String choice) {
        getSharedPreferences(PREFERENCES, MODE_PRIVATE).edit().putString(
                MediaTrackSelection.urlKey("video", activePlayerStreamUrl), choice).apply();
    }

    private void restoreRememberedVideoTrack(IjkMediaPlayer mediaPlayer) {
        String wanted = getSharedPreferences(PREFERENCES, MODE_PRIVATE).getString(
                MediaTrackSelection.urlKey("video", activePlayerStreamUrl), "");
        if (!wanted.startsWith("native:")) return;
        ITrackInfo[] tracks = mediaPlayer.getTrackInfo();
        if (tracks == null) return;
        for (int i=0;i<tracks.length;i++) {
            ITrackInfo track=tracks[i];
            if (track != null && track.getTrackType()==ITrackInfo.MEDIA_TRACK_TYPE_VIDEO
                    && wanted.equals("native:" + trackSignature(track))) {
                if (mediaPlayer.getSelectedTrack(ITrackInfo.MEDIA_TRACK_TYPE_VIDEO)!=i) mediaPlayer.selectTrack(i);
                return;
            }
        }
    }

    private void selectHlsSubtitle(final IjkMediaPlayer mediaPlayer,int index) throws IOException {
        int position=index-HlsMediaTracks.SUBTITLE_BASE;
        if(mediaTrackManifest==null||position<0||position>=mediaTrackManifest.subtitles.size())throw new IOException("所选字幕不存在");
        if(selectedHlsSubtitle==index)return;
        stopHlsSubtitle();
        deselectTrackType(mediaPlayer,ITrackInfo.MEDIA_TRACK_TYPE_SUBTITLE);
        deselectTrackType(mediaPlayer,ITrackInfo.MEDIA_TRACK_TYPE_TIMEDTEXT);
        selectedHlsSubtitle=index;
        hlsSubtitlePlayer=new HlsSubtitlePlayer(mediaTrackManifest.subtitles.get(position).url,webStreamHeaders,new HlsSubtitlePlayer.Output(){
            @Override public long positionMs(){
                return player==mediaPlayer&&prepared?Math.max(0L,mediaPlayer.getCurrentPosition()):0;
            }
            @Override public void text(String text){
                if(player!=mediaPlayer||subtitleText==null)return;
                subtitleText.setText(text);subtitleText.setVisibility(text.isEmpty()?View.GONE:View.VISIBLE);
            }
        });
    }

    private void stopHlsSubtitle() {
        if(hlsSubtitlePlayer!=null){hlsSubtitlePlayer.close();hlsSubtitlePlayer=null;}
        selectedHlsSubtitle=-1;
        clearSubtitleText();
    }

    private void clearSubtitleText() {
        if (subtitleText != null) {
            subtitleText.setText("");
            subtitleText.setVisibility(View.GONE);
        }
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

    private void refreshUiScaleForViewport(int viewportWidth, int viewportHeight,
            boolean force) {
        if (root == null || channelBar == null || channelListPanel == null) {
            return;
        }
        DisplayMetrics metrics = getResources().getDisplayMetrics();
        if (viewportWidth <= 0 || viewportHeight <= 0) {
            viewportWidth = metrics.widthPixels;
            viewportHeight = metrics.heightPixels;
        }
        float nextScale = resolveUiScale(viewportWidth, viewportHeight, metrics);
        boolean viewportChanged = viewportWidth != uiScaleViewportWidth
                || viewportHeight != uiScaleViewportHeight;
        if (!force && !viewportChanged
                && Math.abs(nextScale - effectiveUiScale) < 0.001f) {
            return;
        }
        effectiveUiScale = nextScale;
        uiScaleViewportWidth = viewportWidth;
        uiScaleViewportHeight = viewportHeight;

        // These overlays are independent from the video and WebView surfaces. Their
        // original dimensions are cached once, so changing a preset is reversible
        // and performs no work during playback frames.
        uiScaleHelper.apply(channelBar, nextScale);
        uiScaleHelper.apply(channelListPanel, nextScale);
        uiScaleHelper.apply(managementPanel, nextScale);
        uiScaleHelper.apply(backPrompt, nextScale);
        uiScaleHelper.apply(numericChannelOverlay, nextScale);
        uiScaleHelper.apply(networkSpeedOverlay, nextScale);
        groupAdapter.setUiScale(nextScale);
        channelAdapter.setUiScale(nextScale);
        epgAdapter.setUiScale(nextScale);
        if (webSourceView != null) {
            webSourceView.setInterfaceScale(nextScale);
        }
        updateChannelPanelWidth();
        updateChannelBarWidth();
        configureVideoClockForViewport(viewportWidth, viewportHeight);
        Log.i(TAG, "Native UI scale mode=" + uiScaleMode + " factor=" + nextScale
                + " viewport=" + viewportWidth + "x" + viewportHeight
                + " densityDpi=" + metrics.densityDpi
                + " diagonal=" + detectedDisplayInches);
    }

    private float resolveUiScale(int viewportWidth, int viewportHeight,
            DisplayMetrics metrics) {
        DisplayMetrics physicalMetrics = physicalDisplayMetrics();
        detectedDisplayInches = estimateDisplayDiagonal(
                physicalMetrics.widthPixels, physicalMetrics.heightPixels,
                physicalMetrics);
        if (UI_SCALE_STANDARD.equals(uiScaleMode)) {
            return 1f;
        }
        if (UI_SCALE_LARGE.equals(uiScaleMode)) {
            return 1.25f;
        }
        if (UI_SCALE_EXTRA_LARGE.equals(uiScaleMode)) {
            return 1.50f;
        }
        if (UI_SCALE_EXTRA_EXTRA_LARGE.equals(uiScaleMode)) {
            return 2.00f;
        }
        float diagonal = detectedDisplayInches;
        if (diagonal >= 32f && diagonal <= 100f) {
            return roundUiScale(Math.max(0.95f, Math.min(1.30f, 65f / diagonal)));
        }
        float density = Math.max(0.1f, metrics.density);
        float shortSideDp = Math.min(viewportWidth, viewportHeight) / density;
        if (shortSideDp >= 1500f) {
            return 1.15f;
        }
        if (shortSideDp >= 1250f) {
            return 1.08f;
        }
        return 1f;
    }

    private DisplayMetrics physicalDisplayMetrics() {
        DisplayMetrics metrics = new DisplayMetrics();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
            RealDisplayMetrics.read(getWindowManager(), metrics);
        } else {
            getWindowManager().getDefaultDisplay().getMetrics(metrics);
        }
        return metrics;
    }

    @android.annotation.TargetApi(Build.VERSION_CODES.JELLY_BEAN_MR1)
    private static final class RealDisplayMetrics {
        static void read(WindowManager manager, DisplayMetrics metrics) {
            manager.getDefaultDisplay().getRealMetrics(metrics);
        }
    }

    private static float estimateDisplayDiagonal(int width, int height,
            DisplayMetrics metrics) {
        if (width <= 0 || height <= 0 || metrics.xdpi < 20f || metrics.ydpi < 20f
                || metrics.xdpi > 400f || metrics.ydpi > 400f) {
            return -1f;
        }
        double widthInches = width / metrics.xdpi;
        double heightInches = height / metrics.ydpi;
        float diagonal = (float) Math.sqrt(widthInches * widthInches
                + heightInches * heightInches);
        return diagonal >= 32f && diagonal <= 100f ? diagonal : -1f;
    }

    private static float roundUiScale(float value) {
        return Math.round(value * 100f) / 100f;
    }

    private float effectiveUiDensity() {
        return getResources().getDisplayMetrics().density * effectiveUiScale;
    }

    private void applyDisplaySettings() {
        videoView.setLegacySurfaceMode(SURFACE_MODE_LEGACY.equals(surfaceMode));
        videoView.setStretchVideo(VIDEO_SCALE_STRETCH.equals(videoScaleMode));
        applySubtitleStyle();
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
        float textSizePx = Math.max(18f, Math.min(52f, shortSide * 0.03f))
                * effectiveUiScale;
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
        configureDebugInfoForViewport(viewportWidth, viewportHeight);
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
        float textSizePx = Math.max(18f, Math.min(54f, shortSide * 0.03f))
                * effectiveUiScale;
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

    private void configureDebugInfoForViewport(int viewportWidth, int viewportHeight) {
        if (debugInfoOverlay == null) {
            return;
        }
        int shortSide = Math.min(viewportWidth, viewportHeight);
        float textSizePx = Math.max(14f, Math.min(48f, shortSide * 0.022f))
                * effectiveUiScale;
        debugInfoTextSizePx = textSizePx;
        float shadowRadiusPx = Math.max(1.5f, textSizePx * 0.09f);
        float shadowOffsetPx = Math.max(1f, textSizePx * 0.045f);
        debugInfoOverlay.setTextSize(TypedValue.COMPLEX_UNIT_PX, textSizePx);
        debugInfoOverlay.setShadowLayer(shadowRadiusPx, shadowOffsetPx, shadowOffsetPx,
                0xe6000000);
        debugInfoOverlay.setLineSpacing(0f, 1.03f);

        FrameLayout.LayoutParams params =
                (FrameLayout.LayoutParams) debugInfoOverlay.getLayoutParams();
        params.width = FrameLayout.LayoutParams.MATCH_PARENT;
        params.height = FrameLayout.LayoutParams.WRAP_CONTENT;
        params.gravity = Gravity.BOTTOM | Gravity.LEFT;
        params.leftMargin = Math.max(8, Math.round(viewportWidth * 0.01f));
        params.rightMargin = params.leftMargin;
        params.topMargin = 0;
        params.bottomMargin = Math.max(6, Math.round(viewportHeight * 0.01f));
        debugInfoOverlay.setGravity(Gravity.LEFT);
        debugInfoOverlay.setLayoutParams(params);
        updateChannelBarBottomMargin();
        if (networkSpeedOverlay != null) {
            FrameLayout.LayoutParams networkParams =
                    (FrameLayout.LayoutParams) networkSpeedOverlay.getLayoutParams();
            networkParams.bottomMargin = params.bottomMargin + (showDebugInfo
                    ? Math.round(textSizePx * 2.6f) : 0);
            networkSpeedOverlay.setLayoutParams(networkParams);
        }
    }

    private void applyDebugInfoVisibility() {
        if (debugInfoOverlay == null) {
            return;
        }
        debugInfoOverlay.setVisibility(showDebugInfo ? View.VISIBLE : View.GONE);
        configureDebugInfoForViewport(Math.max(1, root.getWidth()), Math.max(1, root.getHeight()));
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

    private void recoverStalledPlayback(int requestId, IMediaPlayer watchedPlayer,
            String reason) {
        if (requestId != playRequestId || player != watchedPlayer
                || stallRecoveryRequestId == requestId) {
            return;
        }
        stallRecoveryRequestId = requestId;
        if (isNtVCastSource(activePlayerStreamUrl)) {
            final int castRequest = requestId;
            final IMediaPlayer castPlayer = watchedPlayer;
            final Channel castChannel = activePlayerChannel;
            final String castUrl = activePlayerStreamUrl;
            final boolean software = activeSoftwareDecode;
            Log.w(TAG, "Recovering interrupted cast reason=" + reason
                    + " sdk=" + Build.VERSION.SDK_INT);
            showLoading(currentChannel().name, "投屏连接中断，正在重连");
            channelBar.postDelayed(new Runnable() {
                @Override public void run() {
                    if (castRequest != playRequestId || player != castPlayer) return;
                    try { startPlayer(castChannel, castUrl, software); }
                    catch (IOException error) {
                        Log.w(TAG, "Unable to reconnect cast", error);
                        stallRecoveryRequestId = -1;
                        startChannel(currentChannelIndex);
                    }
                }
            }, 500L);
            return; // Never advance the controlled TV to an unrelated channel.
        }
        syncPlaybackRecoveryTarget();
        if (playbackRecoveryAttempts < PLAYBACK_RECOVERY_MAX_ATTEMPTS) {
            playbackRecoveryAttempts++;
            lastPlaybackRecoveryAt = SystemClock.elapsedRealtime();
            Channel retryChannel = activePlayerChannel;
            String retryUrl = activePlayerStreamUrl;
            boolean retrySoftwareDecode = activeSoftwareDecode;
            Log.w(TAG, "Recovering stalled playback attempt="
                    + playbackRecoveryAttempts + "/" + PLAYBACK_RECOVERY_MAX_ATTEMPTS
                    + " reason=" + reason + " url=" + retryUrl);
            showLoading(currentChannel().name, "网络中断，正在重新连接（"
                    + playbackRecoveryAttempts + "/"
                    + PLAYBACK_RECOVERY_MAX_ATTEMPTS + "）");
            if (retryChannel != null && retryUrl != null && retryUrl.length() > 0) {
                try {
                    startPlayer(retryChannel, retryUrl, retrySoftwareDecode);
                    return;
                } catch (IOException error) {
                    Log.w(TAG, "Unable to restart stalled stream", error);
                }
            }
            startChannel(currentChannelIndex);
            return;
        }

        playbackRecoveryAttempts = 0;
        Channel channel = currentChannel();
        int sourceCount = Math.max(1, channel.sourceCount());
        if (sourceCount > 1
                && playbackRecoverySourcesTried < sourceCount - 1) {
            playbackRecoverySourcesTried++;
            currentSourceIndex = (currentSourceIndex + 1) % sourceCount;
            triedCustomSources = 1;
            syncPlaybackRecoveryTarget();
            Log.w(TAG, "Playback recovery exhausted; using backup source "
                    + (currentSourceIndex + 1) + "/" + sourceCount);
            startChannel(currentChannelIndex);
            showChannelBar(channel.name, "当前线路无法恢复，切换至线路 "
                    + (currentSourceIndex + 1) + "/" + sourceCount);
            return;
        }

        int[] next = adjacentChannelLocation(currentGroupIndex, currentChannelIndex, 1);
        if (next[0] == currentGroupIndex && next[1] == currentChannelIndex) {
            abortChannelSwitchAnimation();
            hideLoading();
            showChannelBar(channel.name, "网络连接尚未恢复，请稍后重试");
            return;
        }
        Log.w(TAG, "Playback recovery exhausted; moving to next channel group="
                + next[0] + " channel=" + next[1]);
        currentGroupIndex = next[0];
        switchChannel(next[1]);
        Toast.makeText(this, "当前频道无法恢复，已切换到下一频道",
                Toast.LENGTH_LONG).show();
    }

    private void resetPlaybackRecoveryState() {
        playbackRecoveryAttempts = 0;
        playbackRecoverySourcesTried = 0;
        lastPlaybackRecoveryAt = 0L;
        playbackRecoveryTarget = "";
        stallRecoveryRequestId = -1;
    }

    private void syncPlaybackRecoveryTarget() {
        String target = currentGroupIndex + ":" + currentChannelIndex
                + ":" + currentSourceIndex;
        if (!target.equals(playbackRecoveryTarget)) {
            playbackRecoveryTarget = target;
            playbackRecoveryAttempts = 0;
            lastPlaybackRecoveryAt = 0L;
            stallRecoveryRequestId = -1;
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
        if (shouldFreezeReceiverChannelHistory() || requestId != playRequestId
                || currentGroupIndex < 0
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
        if (offset == 0 || ChannelCatalog.GROUPS.length == 0) {
            return;
        }
        int baseGroupIndex = pendingRelativeGroupIndex >= 0
                ? pendingRelativeGroupIndex : currentGroupIndex;
        int baseChannelIndex = pendingRelativeChannelIndex >= 0
                ? pendingRelativeChannelIndex : currentChannelIndex;
        int[] target = adjacentChannelLocation(baseGroupIndex, baseChannelIndex,
                offset > 0 ? 1 : -1);
        if (target == null) {
            Log.w(TAG, "Ignoring relative switch because the catalog is empty");
            return;
        }
        pendingRelativeGroupIndex = target[0];
        pendingRelativeChannelIndex = target[1];
        channelBar.removeCallbacks(commitRelativeChannelSwitch);
        Channel targetChannel = ChannelCatalog.GROUPS[target[0]].channels[target[1]];
        showChannelBar(targetChannel.name, "正在切换频道");
        channelBar.postDelayed(commitRelativeChannelSwitch, CHANNEL_SWITCH_DEBOUNCE_MS);
    }

    /**
     * Walk the real channel catalog instead of wrapping inside the current group.
     * Favorites are an alternate view of existing channels, so they are not inserted
     * between the last channel of one group and the first channel of the next group.
     */
    private int[] adjacentChannelLocation(int groupIndex, int channelIndex, int direction) {
        ChannelCatalog.Group[] groups = ChannelCatalog.GROUPS;
        if (groups.length == 0) {
            return null;
        }
        int normalizedGroup = ChannelCatalog.wrapGroupIndex(groupIndex);
        ChannelCatalog.Group group = groups[normalizedGroup];
        int normalizedChannel = group.channels.length == 0 ? 0
                : ChannelCatalog.wrapIndex(group.channels, channelIndex);
        int candidate = normalizedChannel + direction;
        if (candidate >= 0 && candidate < group.channels.length) {
            return new int[] { normalizedGroup, candidate };
        }

        int nextGroup = normalizedGroup;
        for (int visited = 0; visited < groups.length; visited++) {
            nextGroup = ChannelCatalog.wrapGroupIndex(nextGroup + direction);
            ChannelCatalog.Group next = groups[nextGroup];
            if (next.channels.length == 0
                    || next.source == ChannelCatalog.SOURCE_FAVORITES) {
                continue;
            }
            return new int[] { nextGroup, direction > 0 ? 0 : next.channels.length - 1 };
        }

        // A catalog containing only favorites should remain operable.
        if (group.channels.length > 0) {
            return new int[] { normalizedGroup,
                    direction > 0 ? 0 : group.channels.length - 1 };
        }
        return null;
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
        int[] location = ChannelCatalog.findGlobalChannel(channelNumber);
        if (location != null) {
            currentGroupIndex = location[0];
            switchChannel(location[1]);
            return;
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

    private void toggleCurrentChannelFavorite() throws IOException {
        ChannelCatalog.Group group = currentGroup();
        Channel channel = currentChannel();
        if (group == null || channel == null) {
            throw new IOException("当前没有可收藏的频道");
        }
        String key = favoriteKey(group, channel);
        if (favoriteChannelKeys.contains(key)) {
            favoriteChannelKeys.remove(key);
        } else {
            favoriteChannelKeys.add(key);
        }
        saveFavoriteChannels();
        refreshFavoriteCatalog();
        if (channelListPanel != null && channelListPanel.getVisibility() == View.VISIBLE) {
            browsingGroupIndex = currentGroupIndex;
            showChannelMenu(currentGroupIndex);
        }
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
        String activeGroupTitle = null;
        String activeChannelKey = null;
        int activeGroupSource = -1;
        if (currentGroupIndex >= 0 && currentGroupIndex < before.length) {
            ChannelCatalog.Group activeGroup = before[currentGroupIndex];
            activeGroupTitle = activeGroup.title;
            activeGroupSource = activeGroup.source;
            if (activeGroup.channels.length > 0) {
                int activeIndex = ChannelCatalog.wrapIndex(
                        activeGroup.channels, currentChannelIndex);
                activeChannelKey = favoriteKey(activeGroup,
                        activeGroup.channels[activeIndex]);
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

        ChannelCatalog.Group[] after = ChannelCatalog.GROUPS;
        if (activeChannelKey != null) {
            int titledGroup = findGroupByTitle(after, activeGroupTitle);
            if (titledGroup >= 0) {
                int channelIndex = findChannelByKey(after[titledGroup], activeChannelKey);
                if (channelIndex >= 0) {
                    currentGroupIndex = titledGroup;
                    currentChannelIndex = channelIndex;
                    return;
                }
            }
            if (activeGroupSource == ChannelCatalog.SOURCE_FAVORITES) {
                for (int groupIndex = 0; groupIndex < after.length; groupIndex++) {
                    ChannelCatalog.Group group = after[groupIndex];
                    for (int channelIndex = 0;
                            channelIndex < group.channels.length; channelIndex++) {
                        if (activeChannelKey.equals(favoriteKey(
                                group, group.channels[channelIndex]))) {
                            currentGroupIndex = groupIndex;
                            currentChannelIndex = channelIndex;
                            return;
                        }
                    }
                }
            }
        }
        currentGroupIndex = ChannelCatalog.firstPlayableGroupIndex();
        currentChannelIndex = ChannelCatalog.defaultChannelIndex(currentGroup());
    }

    private void migrateFavoriteGroupIndex(SharedPreferences preferences) {
        if (preferences.getBoolean(FAVORITE_GROUP_INDEX_MIGRATED, false)) {
            return;
        }
        SharedPreferences.Editor editor = preferences.edit()
                .putBoolean(FAVORITE_GROUP_INDEX_MIGRATED, true);
        // The new catalog intentionally starts from CCTV-1 and no longer restores
        // the group index saved by catalog versions that always inserted favorites.
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
        channelList.setSelector(favoriteActionFocused
                ? R.drawable.channel_favorite_focus_list_selector
                : R.drawable.channel_item_background);
        channelList.invalidate();
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
        String browsingGroupTitle = group.title;
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
        int restoredBrowsingGroup = findGroupByTitle(
                ChannelCatalog.GROUPS, browsingGroupTitle);
        browsingGroupIndex = restoredBrowsingGroup >= 0
                ? restoredBrowsingGroup : currentGroupIndex;
        groupAdapter.showGroups(ChannelCatalog.GROUPS, browsingGroupIndex);
        if (ChannelCatalog.GROUPS[browsingGroupIndex].source
                == ChannelCatalog.SOURCE_FAVORITES) {
            showChannelMenu(browsingGroupIndex);
            setFavoriteActionFocused(true);
        } else {
            setFavoriteActionFocused(true);
        }
        showChannelBar(channel.name, added ? "已添加到我的收藏" : "已取消收藏");
    }

    private void openChannelList() {
        openChannelList(false);
    }

    private void openChannelList(boolean keepVisibleOnBlackScreen) {
        ensureChannelPanelInitialized();
        keepChannelListVisibleOnWebExit = keepVisibleOnBlackScreen;
        cancelPendingRelativeSwitch();
        clearNumericChannelInput();
        lastBackPressedAt = 0L;
        backPrompt.removeCallbacks(hideBackPrompt);
        backPrompt.setVisibility(View.GONE);
        closeManagementPanel();
        channelListPanel.setVisibility(View.VISIBLE);
        // WebSourceView raises itself while a page is active. Raise the channel menu again
        // so the remote OK key remains usable on both video and WebView channels.
        channelListPanel.bringToFront();
        updateChannelPanelWidth();
        ensureFlyMouseOnTop();
        showChannelMenu(currentGroupIndex);
        applyClockLocation();
        channelList.post(new Runnable() {
            @Override
            public void run() {
                setFavoriteActionFocused(false);
                channelList.setSelection(currentChannelIndex);
                channelList.setItemChecked(currentChannelIndex, true);
                restoreGroupListPosition(false);
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
        boolean showingPlayingGroup = browsingGroupIndex == currentGroupIndex;
        final int selectedIndex = showingPlayingGroup ? currentChannelIndex : 0;
        groupAdapter.showGroups(ChannelCatalog.GROUPS, browsingGroupIndex);
        int playingIndex = showingPlayingGroup ? currentChannelIndex : -1;
        channelAdapter.showChannels(browsingGroupIndex, group.channels, selectedIndex,
                playingIndex, currentSourceIndex);
        // Do not call setSelection() here. On a mouse/touch click ListView would
        // scroll the newly selected group to the top, making every group look pinned.
        // The adapter and checked state are sufficient to update its highlight.
        groupList.setItemChecked(browsingGroupIndex, true);
        if (showingPlayingGroup) {
            channelList.setSelection(selectedIndex);
        } else {
            final int expectedGroupIndex = browsingGroupIndex;
            channelList.setSelectionFromTop(0, 0);
            channelList.post(new Runnable() {
                @Override
                public void run() {
                    if (browsingGroupIndex == expectedGroupIndex
                            && channelListPanel.getVisibility() == View.VISIBLE) {
                        channelList.setSelectionFromTop(0, 0);
                    }
                }
            });
        }
        channelList.setItemChecked(selectedIndex, true);
        showEpgForBrowsingChannel(selectedIndex);
        updateFavoriteButton();
        scheduleChannelListDismiss();
    }

    private void restoreGroupListPosition(final boolean requestFocus) {
        final int position = ChannelCatalog.wrapGroupIndex(browsingGroupIndex);
        groupList.setSelection(position);
        groupList.setItemChecked(position, true);
        if (requestFocus) {
            groupList.requestFocus();
        }
        groupList.post(new Runnable() {
            @Override
            public void run() {
                if (channelListPanel.getVisibility() != View.VISIBLE
                        || position != browsingGroupIndex) {
                    return;
                }
                groupList.setSelection(position);
                groupList.setItemChecked(position, true);
                if (requestFocus) {
                    groupList.requestFocus();
                }
            }
        });
    }

    private void closeChannelList() {
        channelListPanel.removeCallbacks(hideChannelList);
        keepChannelListVisibleOnWebExit = false;
        channelPanelTouching = false;
        channelPanelHovering = false;
        favoriteActionFocused = false;
        channelListPanel.setVisibility(View.GONE);
        applyClockLocation();
        root.requestFocus();
    }

    private void scheduleChannelListDismiss() {
        channelListPanel.removeCallbacks(hideChannelList);
        if (keepChannelListVisibleOnWebExit) {
            return;
        }
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
        float density = effectiveUiDensity();
        int maximumPanelWidth = Math.max(1, screenWidth - Math.round(24f * density));

        int groupDesired = desiredGroupColumnWidth(density);
        int channelDesired = desiredChannelColumnWidth(density);
        boolean showEpg = epgColumn != null && epgColumn.getVisibility() == View.VISIBLE;
        if (!showEpg) {
            // Panel padding is 28dp and only the group/channel separator remains (17dp).
            int fixedWidth = Math.round(45f * density);
            int panelWidth = Math.min(maximumPanelWidth,
                    groupDesired + channelDesired + fixedWidth);
            int[] widths = fitTwoColumns(Math.max(2, panelWidth - fixedWidth),
                    groupDesired, channelDesired,
                    Math.round(150f * density), Math.round(215f * density));
            setExactWidth(groupList, widths[0]);
            setExactWidth(channelList, widths[1]);
            setPanelWidth(panelWidth);
            return;
        }
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

        setPanelWidth(panelWidth);
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
                textSizeSp * effectiveUiScale, getResources().getDisplayMetrics()));
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

    private static int[] fitTwoColumns(int available,
            int desiredFirst, int desiredSecond, int minimumFirst, int minimumSecond) {
        int desiredTotal = desiredFirst + desiredSecond;
        if (desiredTotal <= available) {
            return new int[] { desiredFirst, desiredSecond };
        }
        int minimumTotal = minimumFirst + minimumSecond;
        if (minimumTotal >= available) {
            int first = Math.max(1, Math.round(available
                    * (minimumFirst / (float) minimumTotal)));
            return new int[] { first, Math.max(1, available - first) };
        }
        int extra = available - minimumTotal;
        int needFirst = Math.max(0, desiredFirst - minimumFirst);
        int needSecond = Math.max(0, desiredSecond - minimumSecond);
        int needTotal = Math.max(1, needFirst + needSecond);
        int first = minimumFirst + extra * needFirst / needTotal;
        return new int[] { first, available - first };
    }

    private void setPanelWidth(int width) {
        ViewGroup.LayoutParams params = channelListPanel.getLayoutParams();
        if (params.width != width) {
            params.width = width;
            channelListPanel.setLayoutParams(params);
        }
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
        float density = effectiveUiDensity();
        int titleContent = measuredTextWidth(channelName) + measuredTextWidth(videoInfo)
                + Math.round(12f * density);
        int leftContent = Math.max(titleContent,
                Math.max(measuredTextWidth(statusText), measuredTextWidth(channelEpg)));
        leftContent = Math.max(Math.round(390f * density),
                leftContent + Math.round(8f * density));

        int fixedSpace = Math.round(61f * density);
        if (channelProgress.getVisibility() == View.VISIBLE) {
            fixedSpace += Math.round(34f * density);
        }
        int preferred = leftContent + fixedSpace;
        int widthStep = Math.max(1, Math.round(8f * density));
        preferred = ((preferred + widthStep - 1) / widthStep) * widthStep;
        int margin = Math.round(32f * density);
        ViewGroup.LayoutParams params = channelBar.getLayoutParams();
        params.width = Math.min(preferred, Math.max(1, screenWidth - margin));
        channelBar.setLayoutParams(params);
        updateChannelBarBottomMargin();
    }

    private void updateChannelBarBottomMargin() {
        if (channelBar == null || debugInfoOverlay == null) return;
        FrameLayout.LayoutParams bar = (FrameLayout.LayoutParams) channelBar.getLayoutParams();
        FrameLayout.LayoutParams debug = (FrameLayout.LayoutParams) debugInfoOverlay.getLayoutParams();
        int gap = Math.max(8, Math.round(12f * effectiveUiDensity()));
        // Reserve both lines at their full configured font size, even when the
        // long codec line is temporarily shrunk to fit. Never use a stale layout top.
        int lines = Math.max(debugInfoOverlay.getMeasuredHeight(),
                (int)Math.ceil(debugInfoTextSizePx * 2.8f));
        int margin = Math.max(Math.round(18f * effectiveUiDensity()),
                showDebugInfo ? debug.bottomMargin + lines + gap : 0);
        if (bar.bottomMargin != margin) {
            bar.bottomMargin = margin;
            channelBar.setLayoutParams(bar);
        }
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
                epgAdapter.showPrograms(null);
                setEpgColumnVisible(false);
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
            setEpgColumnVisible(false);
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
            setEpgColumnVisible(false);
        } else {
            setEpgColumnVisible(true);
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

    private void setEpgColumnVisible(boolean visible) {
        int visibility = visible ? View.VISIBLE : View.GONE;
        boolean changed = epgColumn.getVisibility() != visibility;
        epgColumn.setVisibility(visibility);
        if (epgDivider != null) {
            epgDivider.setVisibility(visibility);
        }
        if (!visible && epgList.hasFocus()) {
            setFavoriteActionFocused(true);
        }
        if (changed && channelListPanel != null
                && channelListPanel.getVisibility() == View.VISIBLE) {
            updateChannelPanelWidth();
        }
    }

    private void showChannelBar(final String channel, final String status) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                channelBar.removeCallbacks(hideChannelBar);
                channelName.setText(channel);
                statusText.setText(withSourceLineStatus(channel, status));
                channelProgress.setVisibility(loadingActive ? View.VISIBLE : View.GONE);
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
                statusText.setText(withSourceLineStatus(channel, status));
                channelProgress.setVisibility(View.VISIBLE);
                updateChannelCardEpg(channel);
                refreshVideoInfo();
                showChannelCard();
            }
        });
    }

    /** Keeps the selected backup line visible throughout loading, buffering and playback. */
    private String withSourceLineStatus(String displayedChannel, String status) {
        String value = status == null ? "" : status.trim();
        Channel channel = currentChannel();
        int selectedSourceIndex = currentSourceIndex;
        if (displayedChannel != null && (channel == null
                || !displayedChannel.equals(channel.name))) {
            if (pendingRelativeGroupIndex >= 0 && pendingRelativeChannelIndex >= 0) {
                ChannelCatalog.Group pendingGroup = ChannelCatalog.GROUPS[
                        ChannelCatalog.wrapGroupIndex(pendingRelativeGroupIndex)];
                Channel pendingChannel = pendingGroup.channels[ChannelCatalog.wrapIndex(
                        pendingGroup.channels, pendingRelativeChannelIndex)];
                if (displayedChannel.equals(pendingChannel.name)) {
                    channel = pendingChannel;
                    selectedSourceIndex = 0;
                }
            }
        }
        if (channel == null || displayedChannel == null
                || !displayedChannel.equals(channel.name) || hasSourceLinePosition(value)) {
            return value;
        }
        int count = Math.max(1, channel.sourceCount());
        int source = (selectedSourceIndex % count + count) % count + 1;
        return value.length() == 0 ? "线路 " + source + "/" + count
                : value + " · 线路 " + source + "/" + count;
    }

    private static boolean hasSourceLinePosition(String value) {
        if (value == null) {
            return false;
        }
        int lineStart = value.indexOf("线路");
        while (lineStart >= 0) {
            int cursor = lineStart + 2;
            while (cursor < value.length() && Character.isWhitespace(value.charAt(cursor))) {
                cursor++;
            }
            int firstDigit = cursor;
            while (cursor < value.length() && Character.isDigit(value.charAt(cursor))) {
                cursor++;
            }
            if (cursor > firstDigit) {
                while (cursor < value.length()
                        && Character.isWhitespace(value.charAt(cursor))) {
                    cursor++;
                }
                if (cursor < value.length() && value.charAt(cursor) == '/') {
                    cursor++;
                    while (cursor < value.length()
                            && Character.isWhitespace(value.charAt(cursor))) {
                        cursor++;
                    }
                    int secondDigit = cursor;
                    while (cursor < value.length()
                            && Character.isDigit(value.charAt(cursor))) {
                        cursor++;
                    }
                    if (cursor > secondDigit) {
                        return true;
                    }
                }
            }
            lineStart = value.indexOf("线路", lineStart + 2);
        }
        return false;
    }

    private void updateLoadingStatus(final String status) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                Channel channel = currentChannel();
                statusText.setText(withSourceLineStatus(
                        channel == null ? null : channel.name, status));
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

    private final CastNetworkLease receiverNetworkLease = new CastNetworkLease();

    private void releasePlayer() {
        receiverNetworkLease.release();
        mediaTrackChangeGeneration++;
        stopHlsSubtitle();
        mediaTrackManifest=null;
        trackResumePlayer=null;
        prepared = false;
        videoRenderingStarted = false;
        stallRecoveryRequestId = -1;
        activeSoftwareDecode = false;
        buffering = false;
        bufferingStatusVisible = false;
        bufferingEventId++;
        playbackProgressObserved = false;
        lastPlaybackPosition = -1L;
        lastPlaybackProgressAt = 0L;
        lastVideoOutputAt = 0L;
        estimatedVideoBitrate = -1L;
        estimatedAudioBitrate = -1L;
        playerTransportBitrate.reset();
        sampledBitratePlayer = null;
        sampledMetadataPlayer = null;
        cachedIjkMetadata = null;
        measuredTransportBytesPerSecond = -1L;
        resetNetworkSpeedSamples();
        clearSubtitleText();
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
                oldPlayer.setSurface(null);
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
        if (webViewCastManager != null && webViewCastManager.isRunning()) {
            webViewCastManager.setVideoSize(0, 0, 1, 1);
        } else {
            videoView.resetSurfaceBufferSizePreservingAspect();
        }
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
        if (webViewCastManager != null && webViewCastManager.isRunning()) {
            webViewCastManager.setVideoSize(
                    videoWidth, videoHeight, videoSarNum, videoSarDen);
        } else {
            videoView.setVideoSize(videoWidth, videoHeight, videoSarNum, videoSarDen);
        }
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
        if (prepared && player != null) {
            long now = SystemClock.elapsedRealtime();
            if (outputFps > 0.1f) {
                lastVideoOutputAt = now;
            } else if (Build.VERSION.SDK_INT > Build.VERSION_CODES.N_MR1
                    && videoRenderingStarted && !buffering
                    && isNtVCastSource(activePlayerStreamUrl)
                    && lastVideoOutputAt > 0L
                    && now - lastVideoOutputAt >= NTV_CAST_STALL_RECOVERY_MS) {
                recoverStalledPlayback(playRequestId, player,
                        "cast video frames stopped for "
                                + (now - lastVideoOutputAt) + "ms");
                return;
            }
            long playbackPosition = player.getCurrentPosition();
            // A live HLS window can rebase the reported position when older
            // segments leave the manifest.  A backwards jump is still playback
            // progress; treating it as a stall causes a false reconnect roughly once
            // per playlist-history window.
            if (playbackPosition >= 0L && playbackPosition != lastPlaybackPosition) {
                playbackProgressObserved = true;
                lastPlaybackPosition = playbackPosition;
                lastPlaybackProgressAt = now;
                if (playbackRecoveryAttempts > 0 && lastPlaybackRecoveryAt > 0L
                        && now - lastPlaybackRecoveryAt
                                >= PLAYBACK_RECOVERY_HEALTHY_RESET_MS) {
                    playbackRecoveryAttempts = 0;
                    playbackRecoverySourcesTried = 0;
                    lastPlaybackRecoveryAt = 0L;
                    Log.i(TAG, "Playback recovery counter reset after healthy playback");
                }
            } else if (!isNtVCastSource(activePlayerStreamUrl)
                    && playbackProgressObserved && !buffering && lastPlaybackProgressAt > 0L
                    && player.isPlaying()
                    && now - lastPlaybackProgressAt >= PLAYBACK_STALL_RECOVERY_MS) {
                recoverStalledPlayback(playRequestId, player,
                        "playback clock stopped for "
                                + (now - lastPlaybackProgressAt) + "ms");
            }
        }
        PlaybackDebugStats stats = collectPlaybackStreamStats(outputFps);
        String resolution = stats.width > 0 && stats.height > 0
                ? stats.width + "×" + stats.height : "--×--";
        String fps = stats.frameRate > 0.01f
                ? String.format(Locale.US, "%.0ffps", stats.frameRate) : "--fps";
        if (videoInfo != null) {
            videoInfo.setText(resolution + " · " + fps + " · "
                    + formatBitrate(stats.videoBitrate));
        }
        if (channelBar.getVisibility() == View.VISIBLE) {
            updateChannelCardEpg(channelName.getText().toString());
            updateChannelBarWidth();
        }
        if (debugInfoOverlay != null && showDebugInfo) {
            stats.cpuUsage = sampleSystemCpuUsage();
            stats.cpuLabel = systemCpuMetricLabel;
            String debugResolution = stats.width > 0 && stats.height > 0
                    ? stats.width + "x" + stats.height : "--";
            String debugFps = stats.frameRate > 0.01f
                    ? String.format(Locale.US, "%.1ffps", stats.frameRate) : "--fps";
            String gap = "\u2009";
            String details = debugResolution + gap + debugFps + gap + stats.videoCodec
                    + gap + formatBitrate(stats.videoBitrate) + gap + stats.audioCodec
                    + gap + formatBitrate(stats.audioBitrate) + gap
                    + ("loadavg".equals(systemCpuMetricSource) ? "CPU负载" : "CPU")
                    + formatCpuUsage(stats.cpuUsage) + gap + "IP" + localDebugIpAddress();
            if (remoteCatalogUrl.length() > 0) {
                details += gap + "delay(ms):net" + formatDelayValue(stats.networkDelayMs)
                        + gap + "enc" + formatDelayValue(stats.encodeDelayMs)
                        + gap + "q" + formatDelayValue(stats.videoQueueDelayMs)
                        + gap + "tx" + formatDelayValue(stats.videoSendDelayMs)
                        + gap + "decq" + formatDelayValue(stats.decodeDelayMs)
                        + gap + "sum~" + formatDelayValue(estimatedCastDelayMs(stats));
            }
            FrameLayout.LayoutParams debugParams =
                    (FrameLayout.LayoutParams) debugInfoOverlay.getLayoutParams();
            int availableWidth = Math.max(1, root.getWidth()
                    - debugParams.leftMargin - debugParams.rightMargin - 4);
            debugInfoOverlay.setTextSize(TypedValue.COMPLEX_UNIT_PX, debugInfoTextSizePx);
            float detailsWidth = debugInfoOverlay.getPaint().measureText(details);
            if (detailsWidth > availableWidth) {
                debugInfoOverlay.setTextSize(TypedValue.COMPLEX_UNIT_PX,
                        debugInfoTextSizePx * availableWidth / detailsWidth);
            }
            String source = currentDebugSourcePath().replace('\n', ' ').replace('\r', ' ');
            debugInfoOverlay.setText(TextUtils.ellipsize(source, debugInfoOverlay.getPaint(),
                    availableWidth, TextUtils.TruncateAt.END) + "\n" + details);
        }
        if (networkSpeedOverlay != null && showNetworkSpeed) {
            networkSpeedOverlay.setText(
                    formatNetworkSpeed(sampleNetworkBytesPerSecond()));
        }
    }

    private String localDebugIpAddress() {
        if (wifiDirectCoordinator != null) {
            String directAddress = wifiDirectCoordinator.localAddress();
            if (directAddress.length() > 0) return directAddress;
        }
        if (controlServer == null) {
            return "--";
        }
        String address = controlServer.getAdvertisedLanAddress();
        if (address.length() > 0) {
            return address;
        }
        String url = controlServer.getLanUrl();
        String host = url == null ? null : Uri.parse(url).getHost();
        return host == null || host.length() == 0 ? "--" : host;
    }

    private long sampleNetworkBytesPerSecond() {
        HlsProxyServer activeProxy = proxy;
        if (activeProxy == null) {
            resetNetworkSpeedSamples();
            return isNativeStreamingSource(activePlayerStreamUrl)
                    ? measuredTransportBytesPerSecond : -1L;
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
            if (isNtVCastSource(activePlayerStreamUrl)) {
                stats.networkDelayMs = remoteNetworkDelayMs;
                stats.encodeDelayMs = remoteEncodeDelayMs;
                stats.videoQueueDelayMs = remoteVideoQueueDelayMs;
                stats.videoSendDelayMs = remoteVideoSendDelayMs;
                try {
                    // For the low-buffer RTSP path this is the compressed video
                    // duration waiting for decode/render, which is the useful
                    // receiver-side decode queue delay.
                    stats.decodeDelayMs = Math.max(0L,
                            Math.min(9999L, player.getVideoCachedDuration()));
                } catch (RuntimeException ignored) {
                }
            }
        }
        if (webViewCastManager != null && webViewCastManager.isRunning()) {
            stats.networkDelayMs = takeoverNetworkDelayMs;
            stats.encodeDelayMs = webViewCastManager.encodeDelayMs();
            stats.videoQueueDelayMs = webViewCastManager.videoQueueDelayMs();
            stats.videoSendDelayMs = webViewCastManager.videoSendDelayMs();
        }
        return stats;
    }

    private static String formatDelayValue(long milliseconds) {
        return milliseconds < 0L ? "--" : Long.toString(milliseconds);
    }

    private static long estimatedCastDelayMs(PlaybackDebugStats stats) {
        if (stats.networkDelayMs < 0L || stats.encodeDelayMs < 0L
                || stats.videoQueueDelayMs < 0L || stats.videoSendDelayMs < 0L
                || stats.decodeDelayMs < 0L) {
            return -1L;
        }
        // The control RTT is the closest clock-independent network sample. Half
        // of it approximates one-way delivery; the tilde makes that limit clear.
        return stats.encodeDelayMs + stats.videoQueueDelayMs
                + stats.videoSendDelayMs + (stats.networkDelayMs + 1L) / 2L
                + stats.decodeDelayMs;
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
        IjkMediaPlayer activePlayer = player;
        if (activePlayer == null) {
            return;
        }
        if (sampledMetadataPlayer != activePlayer) {
            sampledMetadataPlayer = activePlayer;
            cachedIjkMetadata = null;
        }
        if (cachedIjkMetadata != null) {
            applyCachedIjkMetadata(stats, cachedIjkMetadata);
            return;
        }
        try {
            IjkMediaMeta meta = IjkMediaMeta.parse(activePlayer.getMediaMeta());
            if (meta == null) {
                return;
            }
            PlaybackDebugStats parsed = new PlaybackDebugStats();
            IjkMediaMeta.IjkStreamMeta video = meta.mVideoStream;
            if (video != null) {
                if (video.mWidth > 0 && video.mHeight > 0) {
                    parsed.width = video.mWidth;
                    parsed.height = video.mHeight;
                }
                if (video.mFpsNum > 0 && video.mFpsDen > 0) {
                    parsed.frameRate = (float) video.mFpsNum / video.mFpsDen;
                }
                parsed.videoCodec = readableCodec(video.mCodecName, null);
                parsed.videoBitrate = video.mBitrate;
            }
            IjkMediaMeta.IjkStreamMeta audio = meta.mAudioStream;
            if (audio != null) {
                parsed.audioCodec = readableCodec(audio.mCodecName, null);
                parsed.audioBitrate = audio.mBitrate;
            }
            if (video != null || audio != null) {
                cachedIjkMetadata = parsed;
                applyCachedIjkMetadata(stats, parsed);
            }
        } catch (RuntimeException error) {
            Log.w(TAG, "Unable to read IJK stream metadata", error);
        }
    }

    private static void applyCachedIjkMetadata(PlaybackDebugStats stats,
            PlaybackDebugStats cached) {
        // onVideoSizeChanged is authoritative for adaptive streams. Metadata
        // dimensions are only a fallback when the decoder has not reported them.
        if ((stats.width <= 0 || stats.height <= 0)
                && cached.width > 0 && cached.height > 0) {
            stats.width = cached.width;
            stats.height = cached.height;
        }
        if (stats.frameRate <= 0.01f && cached.frameRate > 0.01f) {
            stats.frameRate = cached.frameRate;
        }
        stats.videoCodec = cached.videoCodec;
        stats.videoBitrate = cached.videoBitrate;
        stats.audioCodec = cached.audioCodec;
        stats.audioBitrate = cached.audioBitrate;
    }

    /** Uses encoded packet bytes/media duration, with transport bytes as RTSP fallback. */
    private void applyIjkRuntimeBitrates(PlaybackDebugStats stats) {
        IjkMediaPlayer activePlayer = player;
        if (activePlayer == null) {
            return;
        }
        try {
            if (sampledBitratePlayer != activePlayer) {
                playerTransportBitrate.reset();
                sampledBitratePlayer = activePlayer;
                measuredTransportBytesPerSecond = -1L;
            }
            String normalizedStreamUrl = activePlayerStreamUrl == null
                    ? "" : activePlayerStreamUrl.toLowerCase(Locale.US);
            boolean realtimeTransport = isNativeStreamingSource(activePlayerStreamUrl)
                    || normalizedStreamUrl.startsWith("udp://")
                    || normalizedStreamUrl.startsWith("rtp://");
            long minimumDurationMs = realtimeTransport ? 40L : 250L;
            long videoSample = MediaBitrateEstimator.fromPayload(
                    activePlayer.getVideoCachedBytes(), activePlayer.getVideoCachedDuration(),
                    minimumDurationMs, 32000L, 200000000L);
            long audioSample = MediaBitrateEstimator.fromPayload(
                    activePlayer.getAudioCachedBytes(), activePlayer.getAudioCachedDuration(),
                    minimumDurationMs, 4000L, 10000000L);
            long transportBitrate = playerTransportBitrate.sampleCumulativeBytes(
                    activePlayer.getTrafficStatisticByteCount(), SystemClock.elapsedRealtime());
            if (transportBitrate >= 0L) {
                measuredTransportBytesPerSecond = transportBitrate / 8L;
            }
            if (realtimeTransport && videoSample <= 0L && transportBitrate > 0L) {
                long knownAudio = audioSample > 0L ? audioSample : stats.audioBitrate;
                videoSample = knownAudio > 0L && transportBitrate > knownAudio
                        ? transportBitrate - knownAudio : transportBitrate;
            }

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

            // Packet measurements describe the active stream; metadata is only a fallback.
            if (estimatedVideoBitrate > 0L) {
                stats.videoBitrate = estimatedVideoBitrate;
            }
            if (estimatedAudioBitrate > 0L) {
                stats.audioBitrate = estimatedAudioBitrate;
            }
        } catch (RuntimeException error) {
            Log.w(TAG, "Unable to estimate IJK stream bitrates", error);
        }
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
        long networkDelayMs = -1L;
        long decodeDelayMs = -1L;
        long encodeDelayMs = -1L;
        long videoQueueDelayMs = -1L;
        long videoSendDelayMs = -1L;
    }

    private void moveChannelMenuSelection(int offset) {
        if (groupList.hasFocus()) {
            int position = browsingGroupIndex;
            int nextPosition = Math.max(0, Math.min(
                    ChannelCatalog.GROUPS.length - 1, position + offset));
            if (nextPosition != browsingGroupIndex) {
                showChannelMenu(nextPosition);
                restoreGroupListPosition(true);
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
        keyCode = normalizeRemoteKeyCode(keyCode);
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

    private String currentDebugSourcePath() {
        if (currentGroupIndex < 0 || currentGroupIndex >= ChannelCatalog.GROUPS.length) {
            return "--";
        }
        ChannelCatalog.Group group = currentGroup();
        if (group.channels == null || group.channels.length == 0) {
            return "--";
        }
        String path = currentChannel().sourceUrl(currentSourceIndex);
        if (path == null) {
            return "--";
        }
        path = path.trim();
        String webViewPrefix = "webview://";
        if (path.regionMatches(true, 0, webViewPrefix, 0, webViewPrefix.length())) {
            path = path.substring(webViewPrefix.length()).trim();
        }
        return path.length() == 0 ? "--" : path;
    }

    /**
     * Android TV vendors do not consistently report the physical OK and source-navigation
     * buttons. Normalizing the common alternatives here keeps the rest of the input state
     * machine identical for DPAD remotes, USB controllers and older television firmware.
     */
    private static int normalizeRemoteKeyCode(int keyCode) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
            case KeyEvent.KEYCODE_BUTTON_A:
            case KeyEvent.KEYCODE_BUTTON_SELECT:
                return KeyEvent.KEYCODE_DPAD_CENTER;
            case KeyEvent.KEYCODE_MEDIA_REWIND:
                return KeyEvent.KEYCODE_DPAD_LEFT;
            case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD:
                return KeyEvent.KEYCODE_DPAD_RIGHT;
            default:
                return keyCode;
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
        discardChannelSwipeSnapshot();
        if (channelSwitchBlackout != null) {
            channelSwitchBlackout.setVisibility(View.GONE);
        }
    }

    private void discardChannelSwipeSnapshot() {
        channelSwipeCaptureGeneration++;
        if (channelSwipeSnapshot != null) {
            channelSwipeSnapshot.animate().cancel();
            channelSwipeSnapshot.setVisibility(View.GONE);
            channelSwipeSnapshot.setTranslationX(0f);
            channelSwipeSnapshot.setTranslationY(0f);
            channelSwipeSnapshot.setImageDrawable(null);
        }
        if (channelSwipeBitmap != null) {
            channelSwipeBitmap.recycle();
            channelSwipeBitmap = null;
        }
    }

    /** Called only after a swipe has crossed the commit threshold. */
    private void discardOutgoingChannelFrame() {
        discardChannelSwipeSnapshot();
        if (videoView != null && !videoView.clearLastFrame()) {
            Log.d(TAG, "Outgoing Surface frame could not be cleared");
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

    private void resetPlaybackLayerImmediately() {
        resetPlaybackLayerImmediately(videoView);
        resetPlaybackLayerImmediately(webSourceView);
    }

    private static void resetPlaybackLayerImmediately(View view) {
        if (view == null) {
            return;
        }
        view.animate().cancel();
        view.setTranslationX(0f);
        view.setTranslationY(0f);
        view.setAlpha(1f);
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
        resetPlaybackLayerImmediately();
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
        resetPlaybackLayerImmediately();
    }

    private void animateSourceSwitch(final int offset, float direction) {
        if (channelSwitchAnimating) {
            return;
        }
        if (currentChannel().sourceCount() <= 1) {
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
        int action = event.getActionMasked();
        if (dispatchCastEdgeTouchIfNeeded(event)) {
            return true;
        }
        // During takeover the receiver is only a display and an input relay. Its
        // local View tree must never interpret taps or gestures intended for the
        // controller's virtual WebView.
        if (remoteCatalogUrl.length() > 0) {
            return true;
        }
        if (action == MotionEvent.ACTION_DOWN && isTouchInput(event)) {
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

    /** Captures both physical touch input and the synthetic fly-mouse path. */
    private boolean dispatchCastEdgeTouchIfNeeded(MotionEvent event) {
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN && webSourceView != null
                && webSourceView.isCastEdgeTouch(event)) {
            castEdgeTouchTracking = true;
        }
        if (castEdgeTouchTracking && webSourceView != null) {
            webSourceView.dispatchCastEdgeTouch(event);
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                castEdgeTouchTracking = false;
            }
            // Never let a cast-control click reach the underlying website.
            return true;
        }
        return false;
    }

    private static boolean isPointInsideView(MotionEvent event, View view) {
        Rect bounds = new Rect();
        return view.getGlobalVisibleRect(bounds)
                && bounds.contains((int) event.getRawX(), (int) event.getRawY());
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        int rawKeyCode = event.getKeyCode();
        int keyCode = normalizeRemoteKeyCode(rawKeyCode);
        if (remoteCatalogUrl.length() > 0) {
            if (event.getAction() == KeyEvent.ACTION_DOWN
                    && (event.getRepeatCount() == 0
                            || keyCode == KeyEvent.KEYCODE_DPAD_UP
                            || keyCode == KeyEvent.KEYCODE_DPAD_DOWN)) {
                forwardReceiverRemoteKey(keyCode);
            }
            return true;
        }
        if (event.getAction() == KeyEvent.ACTION_DOWN && rawKeyCode != keyCode) {
            Log.d(TAG, "Normalized remote key " + rawKeyCode + " to " + keyCode);
        }
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

        if (isReceiverTakeoverActive()) {
            if (keyCode == KeyEvent.KEYCODE_MENU) {
                openManagement();
            } else if (keyCode == KeyEvent.KEYCODE_BACK) {
                openManagement();
            }
            return isHandledRemoteKey(keyCode) || super.dispatchKeyEvent(event);
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
                        setFavoriteActionFocused(false);
                        restoreGroupListPosition(true);
                    }
                    return true;
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                    if (groupList.hasFocus()) {
                        if (ChannelCatalog.GROUPS[browsingGroupIndex].channels.length > 0) {
                            setFavoriteActionFocused(false);
                            channelList.requestFocus();
                        }
                    } else if (favoriteActionFocused && epgAdapter.getCount() > 0) {
                        setFavoriteActionFocused(false);
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
                        setFavoriteActionFocused(false);
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

    private void forwardReceiverRemoteKey(final int keyCode) {
        final String hostUrl = remoteCatalogUrl;
        if (hostUrl.length() == 0 || !isHandledRemoteKey(keyCode)) {
            return;
        }
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    JSONObject command = new JSONObject();
                    if (keyCode == KeyEvent.KEYCODE_DPAD_UP) {
                        command.put("action", "previous").put("type", "control");
                        if (controlServer != null
                                && controlServer.sendTakeoverSessionMessage(command)) return;
                        remoteCatalogClient.controlReceiver(hostUrl, command);
                    } else if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                        command.put("action", "next").put("type", "control");
                        if (controlServer != null
                                && controlServer.sendTakeoverSessionMessage(command)) return;
                        remoteCatalogClient.controlReceiver(hostUrl, command);
                    } else if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT
                            || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                        command.put("action", keyCode == KeyEvent.KEYCODE_DPAD_LEFT
                                ? "sourcePrevious" : "sourceNext")
                                .put("type", "control");
                        if (controlServer != null
                                && controlServer.sendTakeoverSessionMessage(command)) return;
                        remoteCatalogClient.controlReceiver(hostUrl, command);
                    } else if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER
                            || keyCode == KeyEvent.KEYCODE_ENTER
                            || keyCode == KeyEvent.KEYCODE_MENU) {
                        command.put("action", "menu").put("type", "pointer");
                        if (controlServer != null
                                && controlServer.sendTakeoverSessionMessage(command)) return;
                        remoteCatalogClient.pointer(hostUrl, command);
                    } else if (keyCode == KeyEvent.KEYCODE_BACK) {
                        command.put("action", "back").put("type", "pointer");
                        if (controlServer != null
                                && controlServer.sendTakeoverSessionMessage(command)) return;
                        remoteCatalogClient.pointer(hostUrl, command);
                    }
                } catch (Exception error) {
                    Log.w(TAG, "Unable to forward receiver remote key", error);
                }
            }
        }, "receiver-key-forward").start();
    }

    @Override
    public void onBackPressed() {
        cancelPendingRelativeSwitch();
        clearNumericChannelInput();
        if (isReceiverTakeoverActive()) {
            openManagement();
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
        if (returnToRetainedWebPage()) {
            return;
        }
        long now = SystemClock.elapsedRealtime();
        if (webSourceView != null && webSourceView.isPageVisible()) {
            if (webRapidBackStartedAt == 0L
                    || now - webRapidBackStartedAt > WEB_FORCE_CLOSE_WINDOW_MS) {
                webRapidBackStartedAt = now;
                webBackPressCount = 1;
            } else {
                webBackPressCount++;
            }
            if (webBackPressCount >= 3) {
                closeWebSource();
                hideLoading();
                openChannelList(true);
                return;
            }
            if (webSourceView.goBackIfPossible()) {
                lastWebBackPressedAt = 0L;
                return;
            }
            if (lastWebBackPressedAt > 0L
                    && now - lastWebBackPressedAt <= EXIT_CONFIRM_TIMEOUT_MS) {
                closeWebSource();
                hideLoading();
                openChannelList(true);
                return;
            }
            lastBackPressedAt = 0L;
            lastWebBackPressedAt = now;
            showBackPrompt(true);
            return;
        }
        clearWebCloseConfirmation();
        if (now - lastBackPressedAt <= EXIT_CONFIRM_TIMEOUT_MS) {
            finish();
            return;
        }
        lastBackPressedAt = now;
        showBackPrompt(false);
    }

    private boolean returnToRetainedWebPage() {
        if (!hasRetainedWebPlayback()) {
            return false;
        }
        clearPendingPlayer();
        releasePlayer();
        hideLoading();
        videoView.setVisibility(View.INVISIBLE);
        if (!webSourceView.restoreAfterStreamPlayback()) {
            videoView.setVisibility(View.VISIBLE);
            playingDiscoveredWebStream = false;
            return false;
        }
        playingDiscoveredWebStream = false;
        clearWebCloseConfirmation();
        showChannelBar(currentChannel().name, "已返回原网页");
        ensureFlyMouseOnTop();
        return true;
    }

    private boolean hasRetainedWebPlayback() {
        return playingDiscoveredWebStream && webSourceView != null
                && webSourceView.hasRetainedPage();
    }

    @Override
    protected void onPause() {
        dispatchFlyMouseButtonUp(true);
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
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (intent != null && CastKeepAliveService.OPEN_MANAGEMENT.equals(intent.getAction())) {
            openManagementPage();
        }
    }

    @Override
    protected void onDestroy() {
        releaseCastAudioProjection();
        CastKeepAliveService.detach(this);
        dispatchFlyMouseButtonUp(true);
        playRequestId++;
        cancelPendingRelativeSwitch();
        releaseCrashRecovery(isFinishing());
        if (root != null) {
            root.removeCallbacks(applyPendingFlyMouseMove);
            root.removeCallbacks(updateClock);
            root.removeCallbacks(receiverTakeoverWatchdog);
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
        castDiscoveryGeneration++;
        if (castDeviceDiscovery != null) {
            castDeviceDiscovery.close();
            castDeviceDiscovery = null;
        }
        if (wifiDirectCoordinator != null) {
            wifiDirectCoordinator.close();
            wifiDirectCoordinator = null;
        }
        remoteCatalogClient.stopTakeoverSession();
        if (webViewCastManager != null) {
            webViewCastManager.close();
            webViewCastManager = null;
        }
        if (webSourceView != null) {
            webSourceView.destroyPage();
        }
        clearChannelSwitchVisuals();
        if (playbackAudioManager != null) {
            playbackAudioManager.abandonAudioFocus(playbackAudioFocusListener);
        }
        releasePlayer();
        clearRemotePlaybackGateway();
        if (yangshipinResolver != null) {
            yangshipinResolver.destroy();
        }
        if (ku9ScriptResolver != null) {
            ku9ScriptResolver.destroy();
        }
        if (proxy != null) {
            proxy.close();
        }
        super.onDestroy();
    }
}
