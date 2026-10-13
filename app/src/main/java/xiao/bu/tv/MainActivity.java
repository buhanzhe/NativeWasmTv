package xiao.bu.tv;

import com.bu.cc.tv.NativeCmgDecryptor;
import android.animation.TimeInterpolator;
import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.ActivityManager;
import android.content.Intent;
import android.content.ComponentName;
import android.content.Context;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.media.AudioManager;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
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
import android.webkit.WebView;
import android.webkit.CookieManager;
import android.webkit.URLUtil;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.AdapterView;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.ByteArrayInputStream;
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

import tv.danmaku.ijk.media.player.AndroidMediaPlayer;
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
    private static final String HDR_MODE = "hdr_mode";
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
    private static final String UI_SCALE_SMALL = "small";
    private static final String UI_SCALE_SLIGHTLY_SMALL = "slightly_small";
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
    private static final String WEB_VIEW_AUTO_CLOSE_SNIFFED = "web_view_auto_close_sniffed";
    private static final String WEB_VIEW_USER_AGENT = "web_view_user_agent";
    private static final String WEB_VIEW_USER_AGENT_WINDOWS = "windows";
    private static final String WEB_VIEW_USER_AGENT_MACOS = "macos";
    private static final String WEB_VIEW_USER_AGENT_IPAD = "ipad";
    private static final String WEB_VIEW_USER_AGENT_NATIVE = "native";
    private static final String WEB_VIEW_BROWSER_VERSION = "web_view_browser_version";
    private static final String WEB_VIEW_BROWSER_VERSION_NATIVE = "native";
    private static final String WEB_VIEW_AD_BLOCK = "web_view_ad_block";
    private static final String WEB_VIEW_WEBRTC_ENABLED = "web_view_webrtc_enabled";
    private static final String WEB_VIEW_USER_SCRIPT_ENABLED = "web_view_user_script_enabled";
    private static final String WEB_VIEW_USER_SCRIPT = "web_view_user_script";
    private static final String WEB_VIEW_USER_SCRIPTS = "web_view_user_scripts_v2";
    private static final int MAX_WEB_VIEW_USER_SCRIPTS = 32;
    private static final int MAX_WEB_VIEW_USER_SCRIPT_LENGTH = 262144;
    private static final int MAX_WEB_VIEW_USER_SCRIPTS_TOTAL_LENGTH = 524288;

    private static final String WIFI_DIRECT_EXPERIMENTAL = "wifi_direct_experimental";

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
    private static final String DATE_TIME_TIME_WEEK = "time_week";
    private static final String DATE_TIME_ONLY = "time_only";
    private static final String EPG_URL = "epg_url";
    private static final String EPG_URLS = "epg_urls_v2";
    private static final String SHOW_DEBUG_INFO = "show_debug_info";
    private static final String SHOW_NETWORK_SPEED = "show_network_speed";
    private static final String SHOW_DATE = "show_date";
    private static final String FLY_MOUSE_ENABLED = "fly_mouse_enabled";
    private static final String AUTO_SWITCH_SOURCE = "auto_switch_source";
    private static final String AUTO_SWITCH_SOURCE_SECONDS = "auto_switch_source_seconds";
    private static final String AUTO_UPDATE_CHANNEL_LIST = "auto_update_channel_list";
    private static final String REMOTE_CATALOG_URL = "remote_catalog_url";

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
    private static final long TAKEOVER_SESSION_TIMEOUT_MS = 3000L;
    private static final long PLAYBACK_RECOVERY_HEALTHY_RESET_MS = 30000L;
    private static final int PLAYBACK_RECOVERY_MAX_ATTEMPTS = 5;
    private static final long NUMERIC_CHANNEL_TIMEOUT_MS = 3000L;
    private static final int LOCAL_PLAYLIST_PERMISSION_REQUEST = 4201;

    private static final int CAST_LOCAL_NETWORK_PERMISSION_REQUEST = 4205;
    private static final int WIFI_DIRECT_PERMISSION_REQUEST = 4206;
    private static final int ANDROID_17_API = 37;
    private static final String ACCESS_LOCAL_NETWORK_PERMISSION =
            "android.permission.ACCESS_LOCAL_NETWORK";
    private static final long VIDEO_RENDER_START_TIMEOUT_MS = 10000L;
    private static final long LEGACY_HLS_STARTUP_GRACE_MS = 25000L;
    private static final long LEGACY_HLS_MAX_STARTUP_MS = 45000L;
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
    private static final java.util.concurrent.ExecutorService PLAYER_RELEASE_WORKER =
            java.util.concurrent.Executors.newSingleThreadExecutor();
    private static final java.util.concurrent.ExecutorService LEGACY_PLAYER_RELEASE_WORKER =
            java.util.concurrent.Executors.newSingleThreadExecutor();
    private View channelBar;
    private long channelCardEpgUpdatedAt;
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
    private boolean epgExpanded;
    private TextView epgToggle;
    private TextView epgFavorite;
    private long epgIdleSince;
    private final Runnable deferredEpgRefresh = new Runnable() {
        @Override public void run() {
            if (isFinishing()) return;
            long now = SystemClock.uptimeMillis();
            if (loadingActive) epgIdleSince = 0L;
            else if (epgIdleSince == 0L) epgIdleSince = now;
            if (epgIdleSince == 0L || now - epgIdleSince < 3000L) {
                root.postDelayed(this, 1500L);
                return;
            }
            refreshEpgNow();
        }
    };
    private View epgDivider;
    private TextView epgStatus;
    private ChannelListAdapter groupAdapter;
    private ChannelListAdapter channelAdapter;
    private EpgListAdapter epgAdapter;
    private boolean channelPanelInitialized;
    private EpgManager epgManager;
    private YangshipinWebResolver yangshipinResolver;
    private boolean cjsPluginInstallInProgress;
    private int pendingCjsChannelIndex = -1;
    private CjsSource activeCjsSource;
    private String pendingCjsComponentCheck = "";
    private Ku9ScriptResolver ku9ScriptResolver;
    private CjsSiteResolver cjsSiteResolver;
    private DirectVideoView videoView;

    private View channelSwitchBlackout;
    private ImageView channelSwipeSnapshot;
    private WebSourceView webSourceView;
    private FlyMouseCursorView flyMouseCursor;
    private volatile boolean receiverCursorActive;
    private volatile float receiverCursorX, receiverCursorY;
    private long receiverCursorClick = -1L;
    private String receiverCursorStream = "";
    private long receiverCursorAt;
    private JSONObject pendingReceiverCursor;
    private final Object receiverCursorLock = new Object();
    private boolean receiverCursorPosted;

    private final Runnable expireReceiverCursor = new Runnable() {
        @Override public void run() {
            if (SystemClock.elapsedRealtime() - receiverCursorAt >= 650L) clearReceiverCursor();
        }
    };
    private final Runnable applyReceiverCursor = new Runnable() {
        @Override public void run() {
            JSONObject state;
            synchronized (receiverCursorLock) {
                state = pendingReceiverCursor;
                pendingReceiverCursor = null;
                receiverCursorPosted = false;
            }
            if (state == null || !state.optString("sessionId").equals(remoteTakeoverSessionId)
                    || remoteCatalogUrl.length() == 0 || !prepared
                    || receiverStreamSessionId.length() == 0
                    || !receiverStreamSessionId.equals(state.optString("stream"))) {
                clearReceiverCursor();
                return;
            }
            if (!state.optBoolean("visible")) { clearReceiverCursor(); return; }
            // SurfaceView bounds already include the selected aspect ratio / letterboxing.
            View surface = videoView;
            if (surface == null || surface.getWidth() <= 0 || surface.getHeight() <= 0) return;
            int[] videoLocation = new int[2], cursorLocation = new int[2];
            surface.getLocationOnScreen(videoLocation);
            flyMouseCursor.getLocationOnScreen(cursorLocation);
            float x = (float) state.optDouble("x", .5), y = (float) state.optDouble("y", .5);
            float unit = (float) state.optDouble("unit", .001) * surface.getWidth();
            if (Float.isNaN(x) || Float.isNaN(y) || Float.isNaN(unit)
                    || x < 0 || x > 1 || y < 0 || y > 1 || unit <= 0 || unit > 20) return;
            receiverCursorActive = true;
            if (!receiverCursorStream.equals(state.optString("stream"))) {
                receiverCursorStream = state.optString("stream");
                receiverCursorClick = -1L;
            }
            flyMouseCursor.setVisibility(View.VISIBLE);
            flyMouseCursor.setDrawSuppressed(false);
            flyMouseCursor.showRemotePosition(videoLocation[0] - cursorLocation[0] + x * (surface.getWidth() - 1),
                    videoLocation[1] - cursorLocation[1] + y * (surface.getHeight() - 1), unit, true);
            receiverCursorX = x;
            receiverCursorY = y;
            long click = state.optLong("click", 0);
            if (receiverCursorClick >= 0 && click > receiverCursorClick) flyMouseCursor.pulseClick();
            receiverCursorClick = click;
            receiverCursorAt = SystemClock.elapsedRealtime();
            root.removeCallbacks(expireReceiverCursor);
            root.postDelayed(expireReceiverCursor, 650L);
            ensureFlyMouseOnTop();
        }
    };

    private void receiveCastCursor(JSONObject state) {
        if (!state.optString("sessionId").equals(remoteTakeoverSessionId)
                || remoteCatalogUrl.length() == 0 || root == null) return;
        synchronized (receiverCursorLock) {
            pendingReceiverCursor = state;
            if (receiverCursorPosted) return;
            receiverCursorPosted = true;
        }
        if (Build.VERSION.SDK_INT >= 16) root.postOnAnimation(applyReceiverCursor);
        else root.postDelayed(applyReceiverCursor, 16L);
    }

    private void clearReceiverCursor() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            runOnUiThread(() -> clearReceiverCursor());
            return;
        }
        if (!receiverCursorActive) return;
        receiverCursorActive = false;
        receiverCursorClick = -1L;
        receiverCursorStream = "";
        if (flyMouseCursor != null) {
            flyMouseCursor.showRemotePosition(0, 0, 1f, false);
            flyMouseCursor.setCastVisualScale(1f);
            if (!isFlyMouseInteractionEnabled()) flyMouseCursor.setVisibility(View.GONE);
        }
    }
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

    private AudioArtworkView audioArtwork;
    private boolean channelCardDeferredForArtwork;
    private final android.util.LruCache<String, Boolean> audioChannelTypes = new android.util.LruCache<>(64);
    private int artworkGroupIndex = -1, artworkChannelIndex = -1;
    private int relativeArtworkDirection, pendingArtworkDirection;
    private final AlbumArtLoader albumArtLoader = new AlbumArtLoader();
    private boolean audioOnlyPlayback;

    private volatile HlsProxyServer proxy;
    private boolean proxyStatefulCmgSource;
    private boolean lowResourceDevice;
    private volatile IMediaPlayer player;
    private int androidMp3FallbackRequestId = -1;
    private boolean legacyPlayerReleasePending;
    private int legacyPlayerReleaseGeneration;
    private Runnable pendingChannelSetupAfterRelease;
    private MultimediaReceiver multimedia;

    private boolean multimediaSuspended;

    private Channel multimediaReceiverChannel;
    private boolean prepared;
    private boolean videoRenderingStarted;
    private boolean activeSoftwareDecode;
    private boolean autoSoftwareDecode;
    private Channel activePlayerChannel;
    private volatile String activePlayerStreamUrl;

    private static final int MAX_BROWSER_IMAGE_DOWNLOAD_BYTES = 24 * 1024 * 1024;
    private final Object browserActionLock = new Object();
    private long browserActionId;
    private String browserActionType = "";
    private String browserActionText = "";
    private String browserActionMessage = "";
    private final LinkedHashMap<Long, BrowserImageDownload> browserImageDownloads =
            new LinkedHashMap<Long, BrowserImageDownload>();
    private FrameLayout userScriptInstallOverlay;
    private int userScriptInstallGeneration;

    private String directHttpMediaUrl;
    private boolean activeEmbeddedCctvResolver;
    private boolean activeEmbeddedYangshipinResolver;
    private boolean activeEmbeddedCjsResolver;
    private String webStreamHeaders;
    private HlsMediaTracks.Manifest mediaTrackManifest;
    private HlsSubtitlePlayer hlsSubtitlePlayer;
    private int selectedHlsSubtitle = -1;
    private int selectedClosedCaption = -1;
    private IjkMediaPlayer trackResumePlayer;
    private long trackResumePosition;
    private boolean trackResumePlaying;
    private int mediaTrackChangeGeneration;
    private boolean playingDiscoveredWebStream;
    // Presentation identity is separate from the catalog entry that opened the browser.
    private SniffedResource playingSniffedResource;
    private String playingSniffedTitle = "";
    private String playingSniffedChannelUrl = "";
    private String browserSelectionKey = "";
    private int browserSelectionCatalog = -1;
    private ChannelCatalog.Group[] browserSelectionGroups;
    private int[] browserSelection = {-1, -1, -1};
    private Runnable sniffedOpenTimeout;
    private String playingSniffedGroup = "";
    private String playingSniffedPageUrl = "";
    private long sniffedPlaybackGeneration;
    private final SniffedPlaybackWarmup sniffedPlaybackWarmup = new SniffedPlaybackWarmup();
    private int manualWebPlaybackRequestId = -1;
    private String manualWebPlaybackPageKey = "";
    private PlaybackSeekOverlay playbackSeekOverlay;
    private final LinkedHashMap<String, SniffedResource> sniffedResources =
            new LinkedHashMap<String, SniffedResource>();
    private final LinkedHashMap<String, LinkedHashMap<String, SniffedResource>> tabSniffedResources =
            new LinkedHashMap<String, LinkedHashMap<String, SniffedResource>>();
    private String sniffedPageKey = "";
    // Published snapshots are read-only; replace them only when resources change.
    private JSONArray sniffedResourcesSnapshot;
    private Channel pendingPlayerChannel;
    private String pendingPlayerStreamUrl;
    private boolean pendingForceSoftwareDecode;
    private int pendingPlayerRequestId = -1;
    private int legacyHardwareRetryRequestId = -1;
    private volatile int playRequestId;
    private int tenBitWarningRequestId = -1;
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
    private PlaybackDebugStats latestPlaybackDebugStats;
    private long measuredTransportBytesPerSecond = -1L;
    private final long[] networkSpeedSampleBytes = new long[6];
    private final long[] networkSpeedSampleTimes = new long[6];
    private int networkSpeedSampleNext;
    private int networkSpeedSampleCount;
    private long smoothedNetworkBytesPerSecond = -1L;
    private HlsProxyServer sampledNetworkProxy;
    private long lastCpuSampleAt;
    private float cachedCpuUsage = -1f;
    private Thread remoteResolveThread;
    private Thread httpResolveThread;
    private String receiverStreamSessionId = "";
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

    private PlaylistManager playlistManager;
    private final RemoteCatalogClient remoteCatalogClient = new RemoteCatalogClient();
    private LocalControlServer controlServer;
    private CastDeviceDiscovery castDeviceDiscovery;
    private WifiDirectCoordinator wifiDirectCoordinator;
    private boolean wifiDirectPermissionRequestInFlight;
    private boolean wifiDirectPermissionDenied;
    private volatile boolean wifiDirectActive;

    private final Object receiverRouteLock = new Object();
    private final LinkedHashMap<String, LocalControlServer.Resource> controlPageCache =
            new LinkedHashMap<String, LocalControlServer.Resource>();

    private boolean localNetworkPermissionRequestInFlight;
    private boolean localNetworkPermissionDenied;
    private boolean pendingOpenManagementAfterLocalNetwork;

    private volatile boolean reverseUpDown;
    private volatile boolean autoStart;
    private volatile String decodeMode;
    private volatile String hdrMode;
    private java.util.concurrent.CountDownLatch playerReleaseComplete =
            new java.util.concurrent.CountDownLatch(0);
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
    private volatile boolean webViewAutoCloseSniffed = true;
    private volatile String webViewUserAgent;
    private volatile String webViewBrowserVersion;
    private volatile boolean webViewAdBlock;
    private volatile boolean webViewWebRtcEnabled;
    private volatile boolean webViewUserScriptEnabled;
    private volatile String webViewUserScripts = "[]";

    private volatile boolean wifiDirectExperimental;

    private String receiverCastTransport = RTSP_TRANSPORT_TCP;

    private volatile String clockLocation;
    private volatile boolean showDebugInfo;
    private volatile boolean showNetworkSpeed;
    private volatile boolean showDateTime;
    private volatile String dateTimeFormat;
    private volatile String epgUrl;
    private volatile String[] epgUrls = new String[0];
    private volatile boolean flyMouseEnabled;
    private volatile boolean autoSwitchSource;
    private volatile int autoSwitchSourceSeconds;
    private Runnable customSourceTimeout;
    private volatile boolean autoUpdateChannelList;
    private volatile String remoteCatalogUrl = "";
    private volatile String remoteTakeoverSessionId = "";
    private volatile long lastRemoteTakeoverMessageAt;

    private volatile long remoteNetworkDelayMs = -1L;
    private volatile long remoteEncodeDelayMs = -1L;
    private volatile long remoteCastVideoBitrate = -1L;
    private volatile long remoteCastAudioBitrate = -1L;

    private volatile String remoteEncodeDetail = "";
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
    private long channelSwitchTimingStartedAt;
    private int channelSwitchTimingRequestId = -1;
    private boolean gestureReboundAnimating;
    private float channelSwitchDirectionX;
    private float channelSwitchDirectionY;
    private int channelSwitchRequestId = -1;
    private Bitmap channelSwipeBitmap;
    private int channelSwipeCaptureGeneration;
    private AudioManager playbackAudioManager;
    private ChannelMediaSession channelMediaSession;
    private boolean mutedByAudioFocus;
    private long playbackAudioFocusGeneration;
    private boolean mutedByCallMode;
    private ServiceConnection crashRecoveryConnection;
    private boolean crashRecoveryBound;

    private final SniffedMediaProbe sniffedMediaProbe = new SniffedMediaProbe();

    private static final class SniffedResource {
        final int requestId;
        final String url;
        final String pageUrl;
        final String userAgent;
        final String cookies;
        SniffedMediaProbe.Result metadata;

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
            if (isAudioArtworkInteractive() && !audioArtwork.isTransitionRunning())
                audioArtwork.resetSlide();
            clearChannelSwitchVisuals();
            resetPlaybackLayerImmediately();
        }
    };

    private final AudioManager.OnAudioFocusChangeListener playbackAudioFocusListener =
            new AudioManager.OnAudioFocusChangeListener() {
        @Override
        public void onAudioFocusChange(int focusChange) {
            playbackAudioFocusGeneration++;
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
                if (isAudioArtworkInteractive()) audioArtwork.restoreSlide();
                showChannelBar(channels[channelIndex].name,
                        prepared ? "直播播放中" : "正在准备直播");
                return;
            }
            currentGroupIndex = groupIndex;
            relativeArtworkDirection = pendingArtworkDirection;
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

    private static final class BrowserImageDownload {
        final String url;
        final String referer;
        final String userAgent;
        final String cookies;
        final String fileName;

        BrowserImageDownload(String url, String referer, String userAgent,
                String cookies, String fileName) {
            this.url = url;
            this.referer = referer;
            this.userAgent = userAgent;
            this.cookies = cookies;
            this.fileName = fileName;
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        applyPlaybackOrientation(MultiWindowCompat.isInMultiWindowMode(this));
        if (BuildConfig.DEBUG && Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            WebView.setWebContentsDebuggingEnabled(true);
        }
        LocalPlayerRegistry.attach(this);
        // Context assignment only: no plugin file access, parsing, hashing, network or dlopen.
        NetworkClient.initialize(this);
        CjsPluginRuntime.initialize(this);
        CrashReporter.install(this);
        showCrashRecoveryNotice();
        TlsCompat.install();
        configureResourceProfile();
        if (android.os.Build.VERSION.SDK_INT <= 15) {
            // Some 4.0 devices default to a software window even with a modern target SDK.
            // The audio record's TextureView needs a hardware-accelerated window.
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED);
        }
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
        systemInfoProvider = new SystemInfoProvider(this);
        wifiDirectCoordinator = new WifiDirectCoordinator(this,
                new WifiDirectCoordinator.PermissionDelegate() {
                    @Override public void requestWifiDirectPermission() {
                        MainActivity.this.requestWifiDirectPermission();
                    }
                });
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
        epgToggle = (TextView) findViewById(R.id.epg_toggle);
        epgFavorite = (TextView) findViewById(R.id.epg_favorite);
        epgToggle.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) {
                epgExpanded = !epgExpanded;
                showEpgForBrowsingChannel(browsingChannelPosition());
                scheduleChannelListDismiss();
            }
        });
        epgFavorite.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) {
                toggleSelectedChannelFavorite();
                scheduleChannelListDismiss();
            }
        });
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
        hdrMode = HdrMode.sanitize(preferences.getString(HDR_MODE, HdrMode.DEFAULT));
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
        webViewAutoCloseSniffed = preferences.getBoolean(WEB_VIEW_AUTO_CLOSE_SNIFFED, true);
        webViewUserAgent = sanitizeWebViewUserAgent(preferences.getString(
                WEB_VIEW_USER_AGENT, WEB_VIEW_USER_AGENT_WINDOWS));
        String storedBrowserVersion = preferences.getString(WEB_VIEW_BROWSER_VERSION,
                WEB_VIEW_BROWSER_VERSION_NATIVE);
        webViewBrowserVersion = sanitizeWebViewBrowserVersion(storedBrowserVersion);
        if (!webViewBrowserVersion.equals(storedBrowserVersion)) {
            preferences.edit().putString(WEB_VIEW_BROWSER_VERSION,
                    webViewBrowserVersion).apply();
        }
        webViewAdBlock = preferences.getBoolean(WEB_VIEW_AD_BLOCK, true);
        webViewWebRtcEnabled = preferences.getBoolean(WEB_VIEW_WEBRTC_ENABLED, false);
        webViewUserScriptEnabled = preferences.getBoolean(WEB_VIEW_USER_SCRIPT_ENABLED, false);
        webSourceView.setLegacyTranspilation(preferences.getBoolean("legacy_js_transpilation",false));
        String storedUserScripts = preferences.getString(WEB_VIEW_USER_SCRIPTS, "");
        try {
            if (storedUserScripts.trim().length() > 0) {
                webViewUserScripts = normalizeWebViewUserScripts(new JSONArray(storedUserScripts));
            } else {
                webViewUserScripts = legacyWebViewUserScripts(
                        preferences.getString(WEB_VIEW_USER_SCRIPT, ""));
                preferences.edit().putString(WEB_VIEW_USER_SCRIPTS, webViewUserScripts).apply();
            }
        } catch (JSONException invalidScripts) {
            Log.w(TAG, "Discarding invalid saved web scripts", invalidScripts);
            webViewUserScripts = "[]";
            preferences.edit().putString(WEB_VIEW_USER_SCRIPTS, webViewUserScripts).apply();
        }
        wifiDirectExperimental = preferences.getBoolean(WIFI_DIRECT_EXPERIMENTAL, false);

        webSourceView.applyConfiguration(webViewResolution, webViewLoadImages,
                webViewUserAgent, webViewBrowserVersion, webViewPageScale,
                webViewAdBlock, webViewWebRtcEnabled, webViewUserScriptEnabled,
                webViewUserScripts);
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
        epgUrls = readConfiguredEpgUrls(preferences);
        epgUrl = epgUrls.length == 0 ? "" : epgUrls[0];
        flyMouseEnabled = preferences.getBoolean(FLY_MOUSE_ENABLED, false);
        autoSwitchSourceSeconds = preferences.getInt(AUTO_SWITCH_SOURCE_SECONDS,
                preferences.getBoolean(AUTO_SWITCH_SOURCE, false) ? 5 : 0);
        if (autoSwitchSourceSeconds != 5 && autoSwitchSourceSeconds != 10) autoSwitchSourceSeconds = 0;
        autoSwitchSource = autoSwitchSourceSeconds > 0;
        autoUpdateChannelList = preferences.getBoolean(AUTO_UPDATE_CHANNEL_LIST, false);
        // A socket lease cannot survive process death. Boot with the TV's own
        // saved channel; an online phone can establish a fresh lease afterwards.
        remoteCatalogUrl = "";
        preferences.edit().remove(REMOTE_CATALOG_URL).apply();

        String storedLiveDelayMode = preferences.getString(LIVE_DELAY_MODE, null);
        liveDelayMode = sanitizeLiveDelayMode(storedLiveDelayMode == null
                ? LIVE_DELAY_STABLE : storedLiveDelayMode);
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
        // Cold start uses Java constants only. Opening/parsing the bundled M3U and
        // reading SQLite are reserved for loadCompleteCatalogInBackground(), after
        // the first playback request is already under way.
        ChannelCatalog.setCustomGroups(ChannelCatalog.startupGroups(
                startupSnapshot == null ? null : startupSnapshot.group));
        refreshFavoriteCatalog();
        requestLocalPlaylistPermissionIfNeeded();
        epgManager = new EpgManager(this);
        channelAdapter.setEpgManager(epgManager);
        yangshipinResolver = new YangshipinWebResolver(this, (FrameLayout) root,
                getIntent().getBooleanExtra("cmg_keep_web_trace", false));
        ku9ScriptResolver = new Ku9ScriptResolver(this, (FrameLayout) root);
        cjsSiteResolver = new CjsSiteResolver(this);
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
        audioArtwork = (AudioArtworkView) findViewById(R.id.audio_artwork);
        audioArtwork.setTransitionListener(this::onArtworkTransitionChanged);
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
            public void onVideoSurfaceCreated(SurfaceHolder holder, Surface surface) {
                Log.i(TAG, "Video surface created size=" + videoView.getWidth()
                        + "x" + videoView.getHeight() + " sdk=" + Build.VERSION.SDK_INT
                        + " output=SurfaceView");

                if (pendingPlayerRequestId == playRequestId && pendingPlayerChannel != null) {
                    startPendingPlayer();
                } else if (player != null) {
                    if (holder != null) player.setDisplay(holder);
                    else player.setSurface(surface);
                }
            }

            @Override
            public void onVideoSurfaceDestroyed(SurfaceHolder holder, Surface surface) {
                if (videoView.getVideoSurface() != surface) {
                    return;
                }
                Log.i(TAG, "Physical video surface destroyed sdk=" + Build.VERSION.SDK_INT);
                if (hasActivePlayer() && Build.VERSION.SDK_INT < Build.VERSION_CODES.M
                        && activePlayerChannel != null && activePlayerStreamUrl != null) {
                    queuePendingPlayer(activePlayerChannel, activePlayerStreamUrl,
                            activeSoftwareDecode);
                    releasePlayer();
                } else if (player != null) {
                    if (holder != null) player.setDisplay(null);
                    else player.setSurface(null);
                }
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
            String favoriteKey = preferences.getString("last_channel_favorite_key", "");
            if (favoriteKey.length() > 0) {
                for (int index = 0; index < ChannelCatalog.GROUPS.length; index++) {
                    ChannelCatalog.Group favorites = ChannelCatalog.GROUPS[index];
                    if (favorites.source != ChannelCatalog.SOURCE_FAVORITES) continue;
                    int position = findChannelByKey(favorites, favoriteKey);
                    if (position >= 0) {
                        currentGroupIndex = index;
                        currentChannelIndex = position;
                        break;
                    }
                }
            }
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
        // Configuration is already present in this process. Avoid
        // UiModeManager.getCurrentModeType(), whose synchronous Binder call can
        // stall activity startup on some Android 5.x television firmware.
        int modeType = getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_TYPE_MASK;
        return modeType == Configuration.UI_MODE_TYPE_TELEVISION
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
        return multimediaReceiverChannel != null || remoteCatalogUrl.length() > 0 || restoreReceiverChannelPending;
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
                channelList.setItemChecked(position, true);
                showEpgForBrowsingChannel(position);
                updateFavoriteButton();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
        configureChannelPanelInteraction();
    }

    boolean canPresentLegacyWeb() {
        return !isFinishing() && userScriptInstallOverlay == null
                && managementPanel != null && managementPanel.getVisibility() != View.VISIBLE
                && channelListPanel != null && channelListPanel.getVisibility() != View.VISIBLE
                && backPrompt != null && backPrompt.getVisibility() != View.VISIBLE;
    }

    private void configureWebSourceView() {
        webSourceView.setListener(new WebSourceView.Listener() {
            @Override public void onResourcesReset(int requestId, String url) {
                if (requestId == playRequestId) switchSniffedPage(webSourceView.currentResourcePageKey());
            }

            @Override
            public void onPageStarted(int requestId, String url) {
                if (requestId == playRequestId) {
                    clearWebCloseConfirmation();
                    dismissWebNavigationChannelBar();

                }
            }

            @Override
            public void onPageReady(int requestId, String url, String title) {
                if (requestId != playRequestId || !webSourceView.isPageVisible()) {
                    return;
                }
                Channel channel = currentChannel();
                playbackReadyRequestId = requestId;
                dismissWebNavigationChannelBar();
                revealIncomingChannel(requestId);
                persistPlayingChannel(channel, requestId);

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

            @Override public void onPageUnavailable(int requestId, String message) {
                recoverUnavailableWebChannel(requestId, message);
            }
            @Override public void onBrowserRelativeChannel(int direction) { handleChannelMediaKey(direction); }

            @Override
            public void onStreamDiscovered(int requestId, String streamUrl, String pageUrl,
                    String userAgent, String cookies) {
                if (requestId != playRequestId || !webSourceView.isPageVisible()) {
                    return;
                }
                Log.i(TAG, "Web source stream discovered channel=" + webSourceView.currentChannelTitle()
                        + " url=" + streamUrl);
                SniffedResource resource = new SniffedResource(requestId, streamUrl,
                        pageUrl, userAgent, cookies);
                rememberSniffedResource(resource);
                if (findSniffedResource(streamUrl) != resource) return;
                if (webViewAutoPlaySniffed && !(requestId == manualWebPlaybackRequestId
                        && manualWebPlaybackPageKey.equals(webSourceView.currentResourcePageKey()))) {
                    startSniffedResource(resource);
                }
                // Discovery updates the phone's resource list. It is not a native
                // channel switch and must not overlay the page/captured picture.
            }

            @Override public void onBrowserHome() {
                closeWebSource();
                hideLoading();
                openChannelList(true);
            }

            @Override public void onBrowserChannel(int groupIndex, int channelIndex) {
                if (groupIndex < 0 || groupIndex >= ChannelCatalog.GROUPS.length
                        || channelIndex < 0
                        || channelIndex >= ChannelCatalog.GROUPS[groupIndex].channels.length) {
                    return;
                }
                closeWebSource();
                hideLoading();
                browsingGroupIndex = groupIndex;
                switchBrowsingChannel(channelIndex);
            }

            @Override public void onBrowserDownloadImage(String url) {
                publishBrowserImageDownload(url);
            }
            @Override public void onBrowserClipboard(String text, String message) {
                publishBrowserClipboard(text, message);
            }
            @Override public void onBrowserUserScript(String url) {
                startBrowserUserScriptInstall(url);
            }
            @Override public void onBrowserOverlayShown() {
                ensureFlyMouseOnTop();
            }

            @Override public void onBrowserPoliciesChanged(
                    boolean images, boolean adBlock, boolean webRtc) {
                webViewLoadImages = images;
                webViewAdBlock = adBlock;
                webViewWebRtcEnabled = webRtc;
                getSharedPreferences(PREFERENCES, MODE_PRIVATE).edit()
                        .putBoolean(WEB_VIEW_LOAD_IMAGES, images)
                        .putBoolean(WEB_VIEW_AD_BLOCK, adBlock)
                        .putBoolean(WEB_VIEW_WEBRTC_ENABLED, webRtc)
                        .apply();
            }
        });
    }

    private boolean hasLocalNetworkAccess() {
        return !CastPermissionPolicy.requiresLocalNetworkPermission(Build.VERSION.SDK_INT,
                getApplicationInfo().targetSdkVersion)
                || checkSelfPermission(ACCESS_LOCAL_NETWORK_PERMISSION)
                        == PackageManager.PERMISSION_GRANTED;
    }

    private void requestLocalNetworkPermission(boolean openManagementAfterGrant,
            boolean userInitiated) {
        if (hasLocalNetworkAccess()) {
            localNetworkPermissionDenied = false;
            if (openManagementAfterGrant) openManagement();

            return;
        }
        if (openManagementAfterGrant) pendingOpenManagementAfterLocalNetwork = true;
        if (localNetworkPermissionRequestInFlight
                || !userInitiated && localNetworkPermissionDenied) return;
        localNetworkPermissionRequestInFlight = true;
        requestPermissions(new String[] { ACCESS_LOCAL_NETWORK_PERMISSION },
                CAST_LOCAL_NETWORK_PERMISSION_REQUEST);
    }

    /** Delay every LAN connection until its Android runtime permissions are ready. */

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
        requestPermissions(new String[] { wifiDirectCoordinator.requiredPermission() },
                WIFI_DIRECT_PERMISSION_REQUEST);
    }

    /** Probe recently connected televisions in order, then issue one LAN broadcast. */

    private static String safeMessage(Exception error) {
        String message = error == null ? null : error.getMessage();
        return message == null || message.trim().length() == 0 ? "连接失败" : message;
    }

    private int rememberSniffedResource(SniffedResource resource) {
        synchronized (sniffedResources) {
            if (sniffedResources.containsKey(resource.url) || sniffedResources.size() >= 30) return sniffedResources.size();
            sniffedResources.put(resource.url, resource);
            sniffedResourcesSnapshot = null;
            probeSniffedResource(resource);
            return sniffedResources.size();
        }
    }

    private void probeSniffedResource(SniffedResource resource) {
        sniffedMediaProbe.submit(resource.url, resource.pageUrl, resource.userAgent, resource.cookies, result -> {
            synchronized (sniffedResources) {
                if (sniffedResources.get(resource.url) == resource) {
                    resource.metadata = result;
                    sniffedResourcesSnapshot = null;
                }
            }
        });
    }

    private void switchSniffedPage(String key) {
        synchronized (sniffedResources) {
            if (key.equals(sniffedPageKey)) return;
            sniffedResourcesSnapshot = null;
            sniffedMediaProbe.clear(); // Do not let background-tab probes delay the new page.
            if (sniffedPageKey.length() > 0 && !sniffedResources.isEmpty())
                tabSniffedResources.put(sniffedPageKey, new LinkedHashMap<String, SniffedResource>(sniffedResources));
            sniffedResources.clear();
            LinkedHashMap<String, SniffedResource> restored = tabSniffedResources.remove(key);
            if (restored != null) sniffedResources.putAll(restored);
            sniffedPageKey = key;
            // At most 31 inactive tabs, 30 resources each. Drop previous documents
            // of this tab immediately rather than retaining stale signed URLs.
            String tabPrefix = key.substring(0, key.indexOf(':') + 1);
            java.util.Iterator<String> keys = tabSniffedResources.keySet().iterator();
            while (keys.hasNext()) if (keys.next().startsWith(tabPrefix)) keys.remove();
            while (tabSniffedResources.size() > 31)
                tabSniffedResources.remove(tabSniffedResources.keySet().iterator().next());
            for (SniffedResource resource : sniffedResources.values())
                if (resource.metadata == null) probeSniffedResource(resource);
        }
    }

    private void clearSniffedResources() {
        synchronized (sniffedResources) {
            sniffedMediaProbe.clear();
            sniffedResources.clear();
            tabSniffedResources.clear();
            sniffedPageKey = "";
            sniffedResourcesSnapshot = null;
        }
    }

    private SniffedResource findSniffedResource(String url) {
        synchronized (sniffedResources) {
            return sniffedResources.get(url);
        }
    }

    private String currentSniffedPageKey() {
        synchronized (sniffedResources) { return sniffedPageKey; }
    }

    private JSONArray sniffedResourcesJson() throws JSONException {
        synchronized (sniffedResources) {
            if (sniffedResourcesSnapshot != null) return sniffedResourcesSnapshot;
            JSONArray result = new JSONArray();
            for (SniffedResource resource : sniffedResources.values()) {
                result.put((resource.metadata == null ? new JSONObject().put("probeStatus", "pending") : resource.metadata.json())
                        .put("url", resource.url)
                        .put("pageKey", sniffedPageKey)
                        .put("pageUrl", resource.pageUrl == null ? "" : resource.pageUrl));
            }
            sniffedResourcesSnapshot = result;
            return result;
        }
    }

    private void startSniffedResource(SniffedResource resource) {
        if (resource == null || findSniffedResource(resource.url) != resource || resource.requestId != playRequestId
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
        playingSniffedResource = resource;
        sniffedPlaybackWarmup.reset();
        lastBackPressedAt = 0L;
        playingSniffedTitle = webSourceView.currentChannelTitle();
        playingSniffedChannelUrl = webSourceView.currentChannelUrl();
        playingSniffedGroup = webSourceView.currentChannelGroup();
        if ("网页".equals(playingSniffedGroup)) playingSniffedGroup = "网页资源";
        playingSniffedPageUrl = webSourceView.activePageUrl();
        sniffedPlaybackGeneration++;
        playingDiscoveredWebStream = true;
        cancelCustomSourceTimeout();
        cancelSniffedOpenTimeout();
        final long attempt = sniffedPlaybackGeneration;
        sniffedOpenTimeout = () -> {
            if (attempt != sniffedPlaybackGeneration || !playingDiscoveredWebStream) return;
            sniffedOpenTimeout = null;
            if (!prepared || !audioOnlyPlayback && !videoRenderingStarted)
                returnFromFailedSniffedPlayback("资源打开超时");
        };
        channelBar.postDelayed(sniffedOpenTimeout, 30000L);
        webSourceView.hideForStreamPlayback();
        videoView.setVisibility(View.VISIBLE);
        showLoading(channel.name, "正在打开所选嗅探资源");
        showChannelBar(channel.name, webViewAutoCloseSniffed
                ? "已打开网页资源 · 成功播放 15 秒后关闭网页，清理前可返回"
                : "已打开网页资源 · 返回可回到原网页");
        startResolvedPlayer(channel, resource.url);
    }

    private void openWebSource(Channel channel, String configuredUrl, int requestId) {
        openWebSource(channel, configuredUrl, requestId, "");
    }

    private void openWebSource(Channel channel, String configuredUrl, int requestId, String pageScript) {
        openWebSource(channel, configuredUrl, requestId, pageScript, false);
    }

    private void openWebSource(Channel channel, String configuredUrl, int requestId,
            String pageScript, boolean htmlMimeOverride) {
        if (rejectUnsupportedWebViewSource(channel, configuredUrl)) {
            return;
        }
        dispatchFlyMouseButtonUp(true);
        String pageUrl = configuredUrl.substring("webview://".length());
        String verifiedTarget = VerifiedIappNavigation.targetFor(pageUrl);
        if (!pageUrl.equals(verifiedTarget)) {
            Log.i(TAG, "Verified iapp navigation: " + pageUrl + " -> " + verifiedTarget);
            Toast.makeText(this, "已从旧入口转到同站分享页", Toast.LENGTH_SHORT).show();
            pageUrl = verifiedTarget;
        }
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
        webSourceView.open(requestId, pageUrl, pageScript, htmlMimeOverride);
        applyFlyMouseVisibility();

        ensureFlyMouseOnTop();
    }

    private void closeWebSource() {
        dispatchFlyMouseButtonUp(true);

        playingDiscoveredWebStream = false;
        clearSniffedPlaybackIdentity();
        clearSniffedResources();
        if (webSourceView != null) {
            webSourceView.closePage();
        }
        if (flyMouseCursor != null) flyMouseCursor.hideCursor();
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
                    .putExtra(ManagementActivity.EXTRA_URL, controlServer.getLoopbackUrl());
            {
                startActivity(intent);
            }
        } catch (RuntimeException error) {
            Toast.makeText(this, "无法打开管理网页", Toast.LENGTH_SHORT).show();
        }
    }

    void checkMultimediaReceiver(String session) throws IOException {
        if(remoteCatalogUrl.length()>0 && !remoteTakeoverSessionId.equals(session))
            throw new IOException("电视正在由其他设备接管");
        if (!hasLocalNetworkAccess()) throw new IOException("请先允许局域网访问权限");
    }

    boolean hasActiveMultimedia() { return multimedia!=null && multimedia.active(); }
    boolean backFromMultimedia() {
        if(multimedia==null || !multimedia.active())return false;
        multimedia.returnToPrevious();return true;
    }

    void suspendForMultimedia() {
        multimediaReceiverChannel = null;
        multimediaSuspended = true;
        playRequestId++;
        cancelPendingRelativeSwitch();
        resetPlaybackRecoveryState();
        closeWebSource();
        releasePlayer();
        hideLoading();
        closeManagementPanel();
        ManagementActivity.closeAll();

    }

    void startMultimediaReceiver(String url, String transport, String title) {
        multimediaReceiverChannel = new Channel("", title, "multimedia", url, null, null);
        videoView.setVisibility(View.VISIBLE);
        startResolvedPlayer(multimediaReceiverChannel, url, false, transport);
    }

    void restoreAfterMultimedia() {

        if (!multimediaSuspended) return;
        multimediaSuspended = false;
        multimediaReceiverChannel = null;

        startChannel(currentChannelIndex);
    }

    private void startManagementServer() {
        multimedia = new MultimediaReceiver(this);
        try {
            controlServer = new LocalControlServer(new LocalControlServer.Listener() {
                @Override
                public String multimediaControl(JSONObject request) throws Exception {
                    return multimedia.control(request);
                }

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

                @Override public MediaFileDownload mediaDownload(String sourceKey,
                        String range, String ifRange) throws Exception {
                    return openMediaFileDownload(sourceKey, range, ifRange);
                }

                @Override
                public String browserAction(long afterId) throws Exception {
                    return browserActionResponse(afterId);
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
                public String importUserScript(JSONObject request) throws Exception {
                    if (request.has("source")) {
                        return UserScriptImporter.importLocalScript(request.getString("source"),
                                request.optString("fileName", "")).toString();
                    }
                    return UserScriptImporter.importScript(request.optString("url", "")).toString();
                }

                @Override
                public String installUpdate() throws Exception {
                    if (autoUpdater == null) throw new JSONException("更新服务尚未就绪");
                    return autoUpdater.installLiteUpdate();
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
                    String lowerName = fileName == null ? ""
                            : fileName.toLowerCase(Locale.US);
                    boolean bookmarkHtml = lowerName.endsWith(".html")
                            || lowerName.endsWith(".htm");
                    if (!bookmarkHtml && body != null && body.length > 0) {
                        int probeLength = Math.min(body.length, 4096);
                        String probe = new String(body, 0, probeLength, "UTF-8")
                                .toLowerCase(Locale.US);
                        bookmarkHtml = probe.contains("netscape-bookmark-file")
                                || probe.contains("<a href=") && probe.contains("<dl");
                    }
                    int bookmarkCount = 0;
                    if (bookmarkHtml) {
                        ChromeBookmarkImporter.Result converted = ChromeBookmarkImporter.convert(
                                new ByteArrayInputStream(body));
                        body = converted.playlist;
                        bookmarkCount = converted.count;
                        fileName = "Chrome 书签.m3u";
                    }
                    PlaylistManager.ImportedFile imported = playlistManager.importLocalPlaylist(
                            sourceId, fileName, body);
                    return new JSONObject().put("ok", true)
                            .put("name", imported.displayName)
                            .put("location", imported.location)
                            .put("bookmarkCount", bookmarkCount).toString();
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
                                request.optJSONArray("sources"), text.getBytes("UTF-8"),
                                request.optJSONArray("sourcePlaylists"));
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
                public LocalControlServer.Resource screenshot(boolean localOnly,
                        boolean preview) throws Exception {

                    VideoScreenshot.Source source = new VideoScreenshot.Source() {
                        @Override public VideoScreenshot.Target current() throws IOException {
                            if (videoView != null && !videoView.isSurfaceReady()) {
                                throw new IOException("请保持播放设备的视频界面在前台，再从另一台设备的网页截屏");
                            }
                            if (!isVideoScreenshotAvailable()) {
                                throw new IOException("当前没有可截取的视频画面，请在节目出画后重试");
                            }
                            return new VideoScreenshot.Target(videoView, player,
                                    videoWidth, videoHeight,
                                    proxy == null ? null : proxy.screenshotSegment(), getCacheDir());
                        }
                    };
                    byte[] image = preview
                            ? videoScreenshot.capturePreview(source)
                            : videoScreenshot.capture(source);
                    return new LocalControlServer.Resource("image/jpeg", image);
                }

                @Override
                public LocalControlServer.Resource artwork(String key, boolean localOnly) throws Exception {

                    final AtomicReference<Bitmap> cover = new AtomicReference<Bitmap>();
                    runOnMainThreadAndWait(() -> {
                        if (key != null && key.length() > 0 && key.equals(mediaArtworkKey()))
                            cover.set(audioArtwork.cover());
                    });
                    Bitmap bitmap = cover.get();
                    if (bitmap == null) throw new IOException("当前节目没有封面");
                    // Bounded to 512px by AlbumArtLoader; compress off the UI thread.
                    ByteArrayOutputStream output = new ByteArrayOutputStream();
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, output);
                    return new LocalControlServer.Resource("image/png", output.toByteArray());
                }

                @Override
                public LocalControlServer.Resource browserDownload(long eventId) throws Exception {
                    return browserImageDownload(eventId);
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
        HttpURLConnection connection = NetworkClient.open(new URL(
                GithubProxy.apply(sourceUrl)));
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
                            .put("logoUrl", channel.logoUrl).put("subtitleUrls", channel.subtitleUrlsText())
                            .put("radio", channel.radio)
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
        return null;
    }

    private int remotePlaybackSourceIndex(Channel channel) {
        return currentSourceIndex;
    }

    private JSONObject remotePlaybackJson(Channel channel) throws JSONException {

        String remoteStreamUrl = remotePlaybackStreamUrl(channel);
        boolean activePlaybackMatches = remoteStreamUrl != null;
        boolean remoteDirect = activePlaybackMatches
                && (isRemoteDirectSource(remoteStreamUrl)
                    || (activePlayerChannel == channel && remoteStreamUrl.equals(directHttpMediaUrl))
);
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
            if (full) root.put("castCursor", new JSONObject()
                    .put("receiverReady", false)
                    .put("drawnLocally", receiverCursorActive)
                    .put("x", receiverCursorX).put("y", receiverCursorY));
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
            root.put("canInitiateTakeover", false);
            root.put("networkTransport", SystemInfoProvider.activeNetworkTransport(this));
            root.put("wifiDirect", wifiDirectCoordinator == null ? new JSONObject()
                    : wifiDirectCoordinator.stateJson());
            root.getJSONObject("wifiDirect").put("active", wifiDirectActive);
            if (full) {
                root.put("castBackground", new JSONObject().put("active", false));
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
                root.put("cjsPlugin", CjsPluginRuntime.statusJson());
                root.put("update", autoUpdater == null ? new JSONObject()
                        : autoUpdater.stateJson());
            }
            if (full || "cast".equals(view) || "flymouse".equals(view)) {
                JSONObject castState = new JSONObject().put("available", false)
                        .put("running", false);
                castState.put("webPageActive", false);
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
            applyBrowserChannelIdentity(current);
            root.put("current", current);
            if (full) {
                String recordingStreamUrl = activePlayerStreamUrl;
                boolean directRecording = recordingStreamUrl != null
                        && isDirectThirdPartyRecordingSource(recordingStreamUrl);
                boolean recordingAvailable = recordingStreamUrl != null
                        && recordingStreamUrl.length() > 0
                        && (directRecording || proxy != null);
                root.put("recording", new JSONObject()
                        .put("available", recordingAvailable)
                        .put("name", current.optString("name", channel.name))
                        .put("group", current.optString("group", group.title))
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
                root.put("browserAction", browserActionJson());
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
                                .put("logoUrl", item.logoUrl).put("subtitleUrls", item.subtitleUrlsText())
                                .put("radio", item.radio)
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
        if (full || "advanced".equals(view) || "channels".equals(view)) {
            settings.put("githubProxyBaseUrl", GithubProxy.baseUrl());
            settings.put("githubProxyEnabled", GithubProxy.isEnabled());
        }
        if (full || "advanced".equals(view)) {
            settings.put("reverseKeys", reverseUpDown)
                    .put("dnsMode", NetworkClient.getDnsMode())
                    .put("autoStart", autoStart)
                    .put("decodeMode", decodeMode)
                    .put("hdrMode", hdrMode)
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
                    .put("siteQualities", CjsPluginRuntime.qualityOptions(
                            cjsComponentForChannel(currentChannel(),
                                    catalogSource(currentGroup(), currentChannel()))))
                    .put("clockLocation", clockLocation)
                    .put("showDebugInfo", showDebugInfo)
                    .put("showNetworkSpeed", showNetworkSpeed)
                    .put("showDate", showDateTime)
                    .put("showDateTime", showDateTime)
                    .put("dateTimeFormat", dateTimeFormat)
                    .put("liveDelayMode", liveDelayMode);
        }
        if (full || "browser".equals(view) || "script".equals(view)) {
            settings.put("webViewResolution", webViewResolution)
                    .put("webViewPageScale", Math.round(webViewPageScale * 100f) / 100.0d)
                    .put("webViewLoadImages", webViewLoadImages)
                    .put("webViewAutoPlaySniffed", webViewAutoPlaySniffed)
                    .put("webViewAutoCloseSniffed", webViewAutoCloseSniffed)
                    .put("webViewLegacyTranspilation", getSharedPreferences(PREFERENCES,MODE_PRIVATE).getBoolean("legacy_js_transpilation",false))
                    .put("webViewUserAgent", webViewUserAgent)
                    .put("webViewBrowserVersion", webViewBrowserVersion)
                    .put("webViewAdBlock", webViewAdBlock)
                    .put("webViewAdBlockRuleCount", WebAdBlocker.ruleCount())
                    .put("webViewAdBlockUpdating", WebAdBlocker.isUpdating())
                    .put("webViewAdBlockLastUpdatedAt", WebAdBlocker.lastUpdatedAt())
                    .put("webViewAdBlockVersion", WebAdBlocker.version())
                    .put("webViewAdBlockError", WebAdBlocker.lastError())
                     .put("webViewAdBlockSource", WebAdBlocker.sourceUrl())
                     .put("webViewWebRtcEnabled", webViewWebRtcEnabled)
                     .put("webViewUserScriptEnabled", webViewUserScriptEnabled)
                     .put("webViewUserScripts", new JSONArray(webViewUserScripts))
                     .put("webViewCacheBytes", webSourceView == null
                            ? 0L : webSourceView.browserCacheSizeBytes());
        }

        if (full || "flymouse".equals(view)) {
            settings.put("flyMouseEnabled", flyMouseEnabled)
                    .put("remoteCatalogUrl", remoteCatalogUrl);
        }
        if (full || "channels".equals(view)) {
            settings.put("autoUpdateChannelList", autoUpdateChannelList)
                    .put("epgUrl", epgUrl)
                    .put("epgUrls", epgUrlsJson(epgUrls))
                    .put("effectiveEpgUrl", effectiveEpgUrl())
                    .put("effectiveEpgUrls", epgUrlsJson(effectiveEpgUrls()))
                    .put("recommendedEpgUrl", EpgManager.DEFAULT_URL)
                    .put("playlistUrl", playlistManager.getPlaylistUrl())
                    .put("playlistSources", playlistManager.getSourcesJson())
                    .put("playlistGroups", playlistManager.getGroupSettingsJson())
                    .put("playlistGroupStats", playlistManager.getVisibleGroupStatsJson())
                    .put("mobileMergedPlaylist", playlistManager.hasMobileMerge())
                    .put("recommendedPlaylistUrl", playlistManager.getRecommendedUrl())
                    .put("recommendedPlaylistSources",
                            playlistManager.getRecommendedSourcesJson());
        } else if ("groups".equals(view)) {
            settings.put("playlistGroups", playlistManager.getGroupSettingsJson());
        }
        if (full) {
            settings.put("autoSwitchSource", autoSwitchSource)
                    .put("autoSwitchSourceSeconds", autoSwitchSourceSeconds)
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
        if ("volume".equals(action)) {
            final int direction = request.optInt("direction", 0);
            if (direction != -1 && direction != 1) throw new JSONException("音量方向无效");
            runOnUiThread(new Runnable() { @Override public void run() {
                adjustRemoteVolume(direction > 0 ? KeyEvent.KEYCODE_VOLUME_UP
                        : KeyEvent.KEYCODE_VOLUME_DOWN);
            }});
            return new JSONObject().put("ok", true).toString();
        }
        if ("play".equals(action) && request.has("controllerCatalogGeneration")) {
            awaitControllerCatalog(request);
        }
        final int requestedGroup = request.optInt("group", -1);
        final int requestedChannel = request.optInt("channel", -1);
        final int requestedSource = request.optInt("source", 0);
        if (request.optBoolean("receiver", false)) {
            throw new JSONException("此版本仅支持接收投屏");
        }
        if ("backFromPlayback".equals(action) || "returnToWeb".equals(action)) {
            final boolean[] returned = {false};
            try { runOnMainThreadAndWait(() -> returned[0] = returnToRetainedWebPage()); }
            catch (IOException error) { throw new JSONException(error.getMessage()); }
            return new JSONObject().put("ok", true).put("returned", returned[0]).toString();
        }
        final String requestedUrl = request.optString("url", "");
        final SniffedResource requestedResource = "playSniffed".equals(action)
                ? findSniffedResource(requestedUrl) : null;
        if (!"next".equals(action) && !"previous".equals(action)
                && !"toggle".equals(action) && !"play".equals(action)
                && !"sourcePrevious".equals(action) && !"sourceNext".equals(action)
                && !"playSniffed".equals(action)
                && !"endTakeover".equals(action)) {
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
        if ("playSniffed".equals(action) && (requestedResource == null
                || (request.has("pageKey") && !request.optString("pageKey").equals(currentSniffedPageKey())))) {
            throw new JSONException("嗅探资源已失效，请刷新资源列表");
        }

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
                } else if ("endTakeover".equals(action)) {
                    synchronized (receiverRouteLock) {
                        if (CastRouteHandover.canEnd(remoteTakeoverSessionId,
                                request.optString("sessionId", ""))) {
                            exitRemoteCatalogTakeover("已结束接管");
                        }
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

                    switchChannel(requestedChannel, requestedSource);
                    closeChannelList();
                }
            }
        };

        runOnUiThread(command);
        return new JSONObject().put("ok", true).toString();
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
        } else if ("release".equals(action)) {
            if (remoteCatalogUrl.length() == 0)
                wifiDirectCoordinator.releaseGroupForReuse();
        } else if (!"status".equals(action)) {
            throw new IOException("不支持的 Wi-Fi Direct 操作");
        }
        JSONObject state = wifiDirectCoordinator.stateJson();
        state.put("ok", true);
        return state.toString();
    }

    /** Runs after the LAN claim has completed; never holds up the casting page. */

    private void awaitControllerCatalog(JSONObject request) throws JSONException {
        final int expected = request.optInt("controllerCatalogGeneration", -1);
        final String session = request.optString("controllerSessionId", "");
        if (expected < 0 || session.length() == 0
                || !session.equals(remoteTakeoverSessionId) || remoteCatalogUrl.length() == 0) {
            throw new JSONException("接管会话已失效，请重新连接电视");
        }
        if (expected == appliedRemoteCatalogGeneration) return;
        if (expected != remoteCatalogGeneration) {
            remoteCatalogGeneration = expected;
            loadCompleteCatalogInBackground();
        }
        // HTTP worker only: the UI must stay free to apply the downloaded catalog.
        long deadline = SystemClock.elapsedRealtime() + 5000L;
        while (expected != appliedRemoteCatalogGeneration
                && session.equals(remoteTakeoverSessionId)
                && SystemClock.elapsedRealtime() < deadline) {
            try {
                Thread.sleep(25L);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new JSONException("频道同步已取消");
            }
        }
        if (expected != appliedRemoteCatalogGeneration || !session.equals(remoteTakeoverSessionId)) {
            throw new JSONException("电视正在同步新频道，请稍后重试");
        }
    }

    private String handleApkPush(String requestedReceiverUrl, String fileName, byte[] body)
            throws Exception {
        String receiverUrl = requestedReceiverUrl == null
                ? "" : requestedReceiverUrl.trim();
        receiverUrl = RemoteCatalogClient.normalizeServerUrl(receiverUrl);
        if (receiverUrl.length() == 0) {
            throw new IOException("请填写电视 IP，再发送 APK");
        }
        JSONObject result = remoteCatalogClient.pushApk(receiverUrl, fileName, body);
        if (!result.optBoolean("ok", false)) {
            throw new IOException(result.optString("message", "电视拒绝接收 APK"));
        }

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
                .put("message", "APK 已发送，设备正在打开安装界面")
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

    private String buildMediaStateJson() throws Exception {
        return buildMediaStateJson(true);
    }

    private String buildMediaStateJson(final boolean detailed) throws Exception {

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

    private MediaFileDownload openMediaFileDownload(final String sourceKey,
            String range, String ifRange) throws Exception {

        final String[] snapshot = new String[3];
        runOnMainThreadAndWait(new Runnable() {
            @Override public void run() {
                if (!mediaSourceKey().equals(sourceKey) || player == null || !prepared) return;
                long duration;
                try { duration = player.getDuration(); } catch (RuntimeException error) { return; }
                if (!MediaFileDownload.isFile(activePlayerStreamUrl, duration,
                        activePlayerStreamUrl != null && activePlayerStreamUrl.equals(directHttpMediaUrl))) return;
                snapshot[0] = activePlayerStreamUrl;
                snapshot[1] = webStreamHeaders;
                snapshot[2] = hasRetainedWebPlayback() && playingSniffedResource != null
                        ? playingSniffedTitle : activePlayerChannel == null ? "nTv-media" : activePlayerChannel.name;
            }
        });
        if (snapshot[0] == null) throw new IOException("播放内容已变化或当前为直播流，请刷新后重试");
        return MediaFileDownload.open(snapshot[0], snapshot[1], snapshot[2], range, ifRange);
    }

    private boolean isMediaWebPage() {
        if (webSourceView != null && webSourceView.hasRetainedPage()) return true;
        Channel channel = currentChannel();
        return channel != null && isWebViewSource(channel.sourceUrl(currentSourceIndex));
    }

    private void applyBrowserChannelIdentity(JSONObject result) throws JSONException {
        boolean visible = webSourceView != null && webSourceView.isPageVisible();
        boolean sniffed = !visible && playingDiscoveredWebStream && playingSniffedResource != null;
        if (!visible && !sniffed) return;
        int[] location = displayedChannelLocation();
        result.put("name", visible ? webSourceView.currentChannelTitle() : playingSniffedTitle)
                .put("group", visible ? webSourceView.currentChannelGroup() : playingSniffedGroup)
                .put("channelUrl", visible ? webSourceView.activePageUrl() : playingSniffedPageUrl)
                .put("channelType", "web").put("groupIndex", location[0]).put("channelIndex", location[1])
                .put("sourceCount", 0).put("sourceIndex", 0);
    }

    private void applyVisibleWebPageState(JSONObject result) throws JSONException {
        result.put("webMedia", false).put("webMediaToken", "")
                .put("webArtworkUrl", "").put("artist", "").put("album", "").put("webMediaError", "");
        boolean visible = webSourceView != null && webSourceView.hasRetainedPage() && webSourceView.isPageVisible();
        boolean sniffed = !visible && hasRetainedWebPlayback() && playingSniffedResource != null;
        result.put("webPageVisible", visible).put("webPageKey", currentSniffedPageKey())
                .put("pageUrl", visible ? webSourceView.activePageUrl() : sniffed ? playingSniffedPageUrl : "");
        if (!visible && !sniffed) return;
        applyBrowserChannelIdentity(result);
        result.put("webPage", true)
                .put("sourceCount", 0).put("sourceIndex", 0)
                .put("favoriteAvailable", false).put("favorite", false);
        // Keep native playback controls, audio artwork and download capability.
        if (!visible) return;
        JSONObject media = webSourceView.currentWebMediaState();
        boolean available = media.optBoolean("available");
        result.put("webMedia", available).put("webMediaToken", media.optString("token"))
                .put("available", available).put("prepared", media.optBoolean("prepared"))
                .put("playing", media.optBoolean("playing"))
                .put("playAvailable", media.optBoolean("canPlay"))
                .put("pauseAvailable", media.optBoolean("canPause"))
                .put("artist", media.optString("artist")).put("album", media.optString("album"))
                .put("webArtworkUrl", media.optString("artwork"))
                .put("webMediaError", media.optString("error"))
                .put("positionMs", Math.max(0L, media.optLong("positionMs")))
                .put("durationMs", Math.max(0L, media.optLong("durationMs")))
                .put("seekable", false).put("speed", 1d)
                .put("previousAvailable", false).put("nextAvailable", false)
                .put("screenshotAvailable", false);
        if (media.optString("title").length() > 0) result.put("name", media.optString("title"));
        result.put("fileDownloadAvailable", false)
                .put("audioOnly", media.optBoolean("audioOnly")).put("artworkKey", "");
    }

    private String mediaSourceKey() {
        // Compact session identity prevents a stale sheet from switching another channel.
        return playRequestId + ":" + currentGroupIndex + ":" + currentChannelIndex
                + ":" + sniffedPlaybackGeneration;
    }

    private String mediaArtworkKey() {
        return audioOnlyPlayback && audioArtwork != null && audioArtwork.cover() != null
                ? mediaSourceKey() + ":" + audioArtwork.coverRevision() : "";
    }

    private boolean isVideoScreenshotAvailable() {
        return (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N
                || (proxy != null && proxy.screenshotSegment() != null))
                && player != null && prepared && videoRenderingStarted && !audioOnlyPlayback
                && videoView != null && videoView.isSurfaceReady()
                && videoWidth > 0 && videoHeight > 0;
    }

    private JSONObject buildLocalMediaState(boolean detailed) throws JSONException {
        JSONObject result = new JSONObject().put("ok", true);
        AudioManager volumeManager = (AudioManager) getSystemService(AUDIO_SERVICE);
        int volumeMax = volumeManager == null ? 0
                : volumeManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        result.put("volumeMax", volumeMax)
                .put("volume", volumeManager == null ? 0
                        : volumeManager.getStreamVolume(AudioManager.STREAM_MUSIC))
                .put("volumeAvailable", volumeMax > 0 && (Build.VERSION.SDK_INT < 21
                        || !volumeManager.isVolumeFixed()));
        result.put("sniffedResources", sniffedResourcesJson());
        result.put("lowResource", lowResourceDevice || Runtime.getRuntime().availableProcessors() <= 2);
        ChannelCatalog.Group group = currentGroup();
        Channel channel = currentChannel();
        IMediaPlayer activePlayer = player;
        boolean canReturn = canReturnToSniffedPage();
        result.put("canReturnToWeb", canReturn)
                .put("backExitsApp", playingDiscoveredWebStream && !canReturn);
        result.put("webPage", isMediaWebPage())
                .put("sourceCount", channel == null ? 0 : channel.sourceCount())
                .put("sourceIndex", currentSourceIndex)
                .put("sourceKey", mediaSourceKey());
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
                if (activePlayer instanceof IjkMediaPlayer
                        && IjkStreamMetrics.extendedMetricsAvailable((IjkMediaPlayer) activePlayer)) {
                    outputFps = validFrameRate(IjkStreamMetrics.outputFrameRate((IjkMediaPlayer) activePlayer));
                    videoCachedDurationMs = Math.max(0L,
                            ((IjkMediaPlayer) activePlayer).getVideoCachedDuration());
                }
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
                .put("fileDownloadAvailable", prepared && MediaFileDownload.isFile(activePlayerStreamUrl, duration,
                        activePlayerStreamUrl != null && activePlayerStreamUrl.equals(directHttpMediaUrl)))
                .put("audioOnly", audioOnlyPlayback)
                .put("screenshotAvailable", isVideoScreenshotAvailable())
                .put("artworkKey", mediaArtworkKey())
                .put("outputFps", Math.round(outputFps * 10f) / 10.0d)
                .put("videoCachedDurationMs", videoCachedDurationMs)
                .put("hlsDiskCacheBytes", proxy == null ? 0 : proxy.vodDiskCachedBytes())
                .put("hlsDiskCachePreparing", proxy != null && proxy.isVodCachePreparing())
                .put("seekable", prepared && duration > 0L)
                .put("speed", Math.round(playbackSpeed * 100f) / 100.0d)
                .put("previousAvailable", adjacentChannelLocation(
                        currentGroupIndex, currentChannelIndex, -1) != null)
                .put("nextAvailable", adjacentChannelLocation(
                        currentGroupIndex, currentChannelIndex, 1) != null);
        PlaybackDebugStats sourceStats = activePlayer != null && prepared
                ? collectPlaybackStreamStats(outputFps) : null;
        JSONObject currentSourceStats = new JSONObject()
                .put("audioOnly", audioOnlyPlayback)
                .put("width", sourceStats == null || audioOnlyPlayback
                        ? 0 : Math.max(0, sourceStats.width))
                .put("height", sourceStats == null || audioOnlyPlayback
                        ? 0 : Math.max(0, sourceStats.height))
                .put("bitrate", sourceStats == null ? 0L : Math.max(0L,
                        audioOnlyPlayback ? sourceStats.audioBitrate : sourceStats.videoBitrate))
                .put("frameRate", sourceStats == null || audioOnlyPlayback ? 0d
                        : Math.round(sourceStats.frameRate * 10f) / 10.0d)
                .put("actualFrameRate", audioOnlyPlayback ? 0d
                        : Math.round(outputFps * 10f) / 10.0d)
                .put("videoStreamFrameRate", sourceStats == null || audioOnlyPlayback ? 0d
                        : Math.round(sourceStats.nominalFrameRate * 10f) / 10.0d)
                .put("actualFrameRateAvailable", player instanceof IjkMediaPlayer
                        && IjkStreamMetrics.extendedMetricsAvailable((IjkMediaPlayer) player))
                .put("sourceFrameRate", sourceStats != null && sourceStats.sourceFrameRate);
        VideoStreamDescription description = sourceStats == null || audioOnlyPlayback
                ? VideoStreamDescription.UNKNOWN : sourceStats.videoDescription;
        currentSourceStats.put("dynamicRange", description.dynamicRange)
                .put("bitDepth", description.bitDepth)
                .put("colorPrimaries", description.colorPrimaries)
                .put("colorSpace", description.colorSpace)
                .put("colorTransfer", description.colorTransfer)
                .put("videoDecoderName", sourceStats == null ? "未知" : sourceStats.videoDecoderName);
        result.put("currentSourceStats", currentSourceStats);
        result.put("revision", mediaTrackChangeGeneration);
        applyVisibleWebPageState(result);
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
                IjkMediaPlayer ijkPlayer = activePlayer instanceof IjkMediaPlayer
                        ? (IjkMediaPlayer) activePlayer : null;
                if (ijkPlayer != null) {
                    selectedAudio = ijkPlayer.getSelectedTrack(
                            ITrackInfo.MEDIA_TRACK_TYPE_AUDIO);
                    selectedVideo = ijkPlayer.getSelectedTrack(ITrackInfo.MEDIA_TRACK_TYPE_VIDEO);
                    selectedSubtitle = ijkPlayer.getSelectedTrack(
                            ITrackInfo.MEDIA_TRACK_TYPE_SUBTITLE);
                    if (selectedSubtitle < 0) {
                        selectedSubtitle = ijkPlayer.getSelectedTrack(
                                ITrackInfo.MEDIA_TRACK_TYPE_TIMEDTEXT);
                    }
                }
                // Framework track enumeration was introduced after Android 4.0.
                ITrackInfo[] tracks = ijkPlayer == null ? null : ijkPlayer.getTrackInfo();
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
                if (selectedHlsSubtitle >= 0) selectedSubtitle = selectedHlsSubtitle;
                for (int i=0;i<manifest.subtitles.size();i++) {
                    HlsMediaTracks.Track track=manifest.subtitles.get(i);
                    int index=HlsMediaTracks.SUBTITLE_BASE+i;
                    subtitleTracks.put(new JSONObject().put("index",index)
                            .put("label",track.name + (track.language.isEmpty() ? "" : " · " + track.language) + " · " + track.info)
                            .put("language",track.language).put("selected",index==selectedSubtitle));
                }
            }
            if (!manifest.closedCaptions.isEmpty()) {
                if (selectedClosedCaption >= 0) selectedSubtitle = selectedClosedCaption;
                for (int i=0;i<manifest.closedCaptions.size();i++) {
                    HlsMediaTracks.Track track=manifest.closedCaptions.get(i);
                    int index=HlsMediaTracks.CLOSED_CAPTION_BASE+i;
                    subtitleTracks.put(new JSONObject().put("index",index)
                            .put("label",track.name
                                    + (track.language.isEmpty() ? "" : " · " + track.language)
                                    + " · " + track.info)
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

        final String action = request.optString("action", "");
        if (!"seek".equals(action) && !"speed".equals(action)
                && !"audioTrack".equals(action) && !"subtitleTrack".equals(action) && !"videoTrack".equals(action)
                && !"subtitleStyle".equals(action) && !"subtitleEnabled".equals(action) && !"previous".equals(action)
                && !"next".equals(action) && !"toggle".equals(action) && !"play".equals(action) && !"pause".equals(action)
                && !"favorite".equals(action) && !"source".equals(action)
                && !"volume".equals(action)) {
            throw new JSONException("未知的媒体控制指令");
        }
        final AtomicReference<Exception> failure = new AtomicReference<Exception>();
        final CountDownLatch webControl = new CountDownLatch(1);
        final boolean[] pendingWebControl = {false};
        runOnMainThreadAndWait(new Runnable() {
            @Override
            public void run() {
                try {
                    if (!"volume".equals(action) && (request.has("webPageKey")
                            || webSourceView != null && webSourceView.isPageVisible())) {
                        if (webSourceView == null) throw new IOException("网页已关闭");
                        pendingWebControl[0] = true;
                        webSourceView.controlWebMedia(action, request.optString("webPageKey"),
                                request.optString("webMediaToken"), error -> {
                            if (error != null) failure.set(new IOException(error));
                            webControl.countDown();
                        });
                        return;
                    }
                    applyMediaControl(action, request);
                } catch (Exception error) {
                    failure.set(error);
                }
            }
        });
        if (failure.get() != null) throw failure.get();
        if (pendingWebControl[0] && !webControl.await(2500L, TimeUnit.MILLISECONDS))
            throw new IOException("网页媒体控制响应超时");
        if (failure.get() != null) throw failure.get();
        return buildMediaStateJson();
    }

    private void applyMediaControl(String action, JSONObject request) throws Exception {
        if ("volume".equals(action)) {
            AudioManager manager = (AudioManager) getSystemService(AUDIO_SERVICE);
            if (manager == null || (Build.VERSION.SDK_INT >= 21 && manager.isVolumeFixed())) {
                throw new IOException("当前设备不支持调节系统音量");
            }
            int level = request.getInt("volume");
            int maximum = manager.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
            if (level < 0 || level > maximum) throw new JSONException("音量超出范围");
            // This runs on the receiver during takeover, leaving the phone's capture volume intact.
            manager.setStreamVolume(AudioManager.STREAM_MUSIC, level, 0);
            return;
        }
        if ("source".equals(action)) {
            Channel channel = currentChannel();
            int index = request.optInt("index", -1);
            if (!mediaSourceKey().equals(request.optString("sourceKey", ""))) {
                throw new JSONException("频道已变化，请重新选择线路");
            }
            if (channel == null || index < 0 || index >= channel.sourceCount()) {
                throw new JSONException("该线路已不存在，请刷新后重试");
            }
            if (index != currentSourceIndex) switchCustomSource(index - currentSourceIndex, false, "");
            return;
        }
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
        if ("toggle".equals(action) || "play".equals(action) || "pause".equals(action)) {
            mediaTrackChangeGeneration++;
            if ("toggle".equals(action) || player != null && prepared
                    && player.isPlaying() != "play".equals(action)) togglePlayback();
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
        IMediaPlayer activePlayer = player;
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
            if (playbackSeekOverlay != null) playbackSeekOverlay.dismiss();
            activePlayer.seekTo(position);
            showPlaybackProgress(position);
        } else if ("speed".equals(action)) {
            mediaTrackChangeGeneration++;
            float speed = (float) request.optDouble("speed", 1d);
            if (speed < 0.25f || speed > 3f) {
                throw new IOException("播放倍速应为 0.25 到 3 倍");
            }
            if (activePlayer instanceof IjkMediaPlayer) {
                playbackSpeed = speed;
                ((IjkMediaPlayer) activePlayer).setSpeed(playbackSpeed);
            } else if (speed != 1f) {
                throw new IOException("Android 4.0 系统播放器不支持倍速");
            } else {
                playbackSpeed = 1f;
            }
        } else if ("videoTrack".equals(action)) {
            if (!(activePlayer instanceof IjkMediaPlayer)) throw new IOException("系统播放器不支持切换视频轨道");
            selectVideoTrack((IjkMediaPlayer) activePlayer,request.optInt("index",-1));
        } else if ("audioTrack".equals(action)) {
            if (!(activePlayer instanceof IjkMediaPlayer)) throw new IOException("系统播放器不支持切换音轨");
            selectMediaTrack((IjkMediaPlayer) activePlayer, request.optInt("index", -1), true);
        } else if ("subtitleTrack".equals(action)) {
            if (!(activePlayer instanceof IjkMediaPlayer)) throw new IOException("系统播放器不支持切换字幕轨道");
            selectMediaTrack((IjkMediaPlayer) activePlayer, request.optInt("index", -1), false);
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
        if ("cursor".equals(request.optString("type"))) {
            receiveCastCursor(request);
            return;
        }
        final String hostUrl = RemoteCatalogClient.normalizeServerUrl(
                request.optString("hostUrl", ""));
        final String sessionId = request.optString("sessionId", "").trim();
        if (hostUrl.length() == 0 || sessionId.length() == 0) throw new IOException("接管会话缺少地址或标识");
        final boolean changingRoute;
        final boolean preservePlayback;
        synchronized (receiverRouteLock) {
            String activeSession = remoteTakeoverSessionId;
            changingRoute = !hostUrl.equalsIgnoreCase(remoteCatalogUrl);
            boolean sameSelection = false;
            if (changingRoute) {
                JSONObject playingSelection = currentReceiverSelection();
                sameSelection = request.optString("channelName", "").length() > 0
                        && request.optString("channelName", "").equals(playingSelection.optString("channelName", ""))
                        && request.optString("groupName", "").equals(playingSelection.optString("groupName", ""))
                        && request.optInt("source", -1) == playingSelection.optInt("source", -2);
            }
            preservePlayback = CastRouteHandover.preservePlayback(changingRoute,
                    request.optInt("catalogGeneration", -1), appliedRemoteCatalogGeneration, sameSelection);
            if (changingRoute) {
                String previousHost = RemoteCatalogClient.normalizeServerUrl(request.optString("previousHostUrl", ""));
                if (!CastRouteHandover.accepts(opened, remoteCatalogUrl, activeSession,
                        previousHost, request.optString("previousSessionId", ""), sessionId,
                        SystemClock.elapsedRealtime() - lastRemoteTakeoverMessageAt, TAKEOVER_SESSION_TIMEOUT_MS)) {
                    throw new IOException("直连切换会话已失效");
                }
                // Same controller and live lease: keep the TV's original channel snapshot.
                remoteCatalogUrl = hostUrl;
                remoteCatalogClient.changePlaybackRoute(previousHost, hostUrl);
                wifiDirectCoordinator.useGroup();
                // Same authenticated controller: an IP change does not invalidate
                // its catalog. Existing media sockets remain alive; new requests
                // use the Direct address. Do not restart playback just to change IP.
                if (request.optInt("catalogGeneration", -1) != appliedRemoteCatalogGeneration) {
                    // A previous LAN catalog download can still be in flight.
                    // Fetch that generation from the new endpoint before selecting.
                    remoteCatalogGeneration = -1;
                }
                remoteNetworkDelayMs = remoteEncodeDelayMs = -1L;
                remoteVideoQueueDelayMs = remoteVideoSendDelayMs = -1L;
                request.put("networkDelayMs", -1L).put("encodeDelayMs", -1L)
                        .put("videoQueueDelayMs", -1L).put("videoSendDelayMs", -1L);
                Log.i(TAG, "Receiver route changed to " + hostUrl);
            }
            if (hostUrl.length() == 0 || sessionId.length() == 0
                    || !changingRoute && !sessionId.equals(activeSession)) {
                throw new IOException("接管会话与当前控制端不匹配");
            }
            remoteTakeoverSessionId = sessionId;
        }
        lastRemoteTakeoverMessageAt = SystemClock.elapsedRealtime();
        if (request.has("networkDelayMs")) {
            remoteNetworkDelayMs = request.optLong("networkDelayMs", -1L);
        }
        remoteCastVideoBitrate = request.optLong("castVideoBitrate", -1L);
        remoteCastAudioBitrate = request.optLong("castAudioBitrate", -1L);
        if (request.has("encodeDelayMs")) {
            remoteEncodeDelayMs = request.optLong("encodeDelayMs", -1L);
        }
        remoteEncodeDetail = request.optString("encodeDetail", "");
        if (request.has("videoQueueDelayMs")) {
            remoteVideoQueueDelayMs = request.optLong("videoQueueDelayMs", -1L);
        }
        if (request.has("videoSendDelayMs")) {
            remoteVideoSendDelayMs = request.optLong("videoSendDelayMs", -1L);
        }
        final int generation = request.optInt("catalogGeneration", -1);
        if (opened && !preservePlayback && request.optInt("group", -1) >= 0
                && request.optInt("channel", -1) >= 0) {
            pendingTakeoverChannelSelection =
                    new TakeoverChannelSelection(sessionId, request);
        }
        if (generation >= 0 && generation != remoteCatalogGeneration) {
            remoteCatalogGeneration = generation;
            loadCompleteCatalogInBackground();
        } else if (opened && !preservePlayback && generation >= 0
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

    /** Compare the receiver's current selection across a same-session route change. */
    private JSONObject currentReceiverSelection() throws JSONException {
        JSONObject selection = new JSONObject();
        ChannelCatalog.Group[] groups = ChannelCatalog.GROUPS;
        int groupIndex = currentGroupIndex;
        if (groupIndex < 0 || groupIndex >= groups.length) return selection;
        ChannelCatalog.Group group = groups[groupIndex];
        selection.put("groupName", group.title);
        if (group.channels.length == 0) return selection;
        int channelIndex = ChannelCatalog.wrapIndex(group.channels, currentChannelIndex);
        return selection.put("channelName", group.channels[channelIndex].name)
                .put("source", currentSourceIndex);
    }

    private String handleWebPointer(JSONObject request) throws Exception {
        return dispatchWebPointer(request);
    }

    /** A remote browser Back never closes the controller or the cast session. */

    boolean ownsLocalPointerPage(String pageUrl) {
        if (isFinishing() || controlServer == null || remoteCatalogUrl.length() > 0
                || pageUrl == null) return false;
        // Management may be opened via our advertised LAN address, not just
        // 127.0.0.1. Both must use the local bridge, never loop back through HTTP.
        return controlServer.ownsOrigin(pageUrl);
    }

    String handleLocalPointer(JSONObject request) throws Exception {
        if (remoteCatalogUrl.length() > 0) throw new JSONException("当前设备被接管，请勿使用飞鼠");
        if (isFinishing()) return new JSONObject().put("ok", false)
                .put("message", "电视端已关闭").toString();
        if ("move".equals(request.optString("action", "move"))) {
            // The in-app bridge runs off the UI thread. Merge motion here so a
            // 120 Hz touch panel cannot enqueue 120 separate main-looper jobs.
            enqueueFlyMouseMove(
                    clampPointerDelta((float) request.optDouble("dx", 0d)),
                    clampPointerDelta((float) request.optDouble("dy", 0d)));
            return new JSONObject().put("ok", true).toString();
        }
        return dispatchWebPointer(request);
    }

    private String dispatchWebPointer(JSONObject request) throws Exception {
        // Receiver-side management pages must not relay input back to the owner.
        // Cursor state arrives separately on the authenticated cast channel.
        if (remoteCatalogUrl.length() > 0) {
            throw new JSONException("当前设备被接管，请勿使用飞鼠");
        }
        if (!flyMouseEnabled) {
            throw new JSONException("请先在操作与启动中开启手机飞鼠");
        }
        final String action = request.optString("action", "move");
        if ("context".equals(action)) return smartWebContextResponse();
        if ("contextAction".equals(action)) return handleSmartWebContextAction(request);
        if ("webSwipe".equals(action)) {
            // Cached management pages may still send webSwipe. Treat it only as scrolling.
            final int scroll = Math.max(-1440, Math.min(1440, request.optInt("scrollX", 0)));
            runOnUiThread(() -> {
                dispatchFlyMouseButtonUp(true);
                if (scroll != 0) handleRemoteScroll(scroll, 0);
            });
            return new JSONObject().put("ok", true).toString();
        }

        if (!"move".equals(action) && !"click".equals(action) && !"rightclick".equals(action)
                && !"down".equals(action) && !"up".equals(action)
                && !"cancel".equals(action)
                && !"scroll".equals(action) && !"zoom".equals(action)
                && !"back".equals(action) && !"webBack".equals(action) && !"webForward".equals(action)
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
                : (float) Math.max(0.1d, Math.min(10d, rawZoomFactor));
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

    private String smartWebContextResponse() throws Exception {
        if (webSourceView == null || !webSourceView.isPageVisible()) {
            return new JSONObject().put("ok", true)
                    .put("displayedOnTv", false).toString();
        }
        runOnUiThread(new Runnable() {
            @Override public void run() {
                root.removeCallbacks(applyPendingFlyMouseMove);
                applyPendingFlyMouseMove.run();
                int[] rootLocation = new int[2];
                root.getLocationOnScreen(rootLocation);
                float screenX = rootLocation[0] + flyMouseCursor.cursorX();
                float screenY = rootLocation[1] + flyMouseCursor.cursorY();
                if (webSourceView.isBrowserNativeContextPoint(screenX, screenY)) {
                    dispatchFlyMouseButtonUp(true);
                    flyMouseActionButton = MotionEvent.BUTTON_SECONDARY;
                    try { dispatchFlyMouseClick(); }
                    finally { flyMouseActionButton = MotionEvent.BUTTON_PRIMARY; }
                } else {
                    webSourceView.showSmartContextAt(screenX, screenY);
                }
                ensureFlyMouseOnTop();
            }
        });
        return new JSONObject().put("ok", true).put("displayedOnTv", true).toString();
    }

    private String handleSmartWebContextAction(JSONObject request) throws Exception {
        final String command = request.optString("command", "");
        final String url = request.optString("url", "").trim();
        if (!isHttpUrl(url)) throw new JSONException("链接地址无效");
        if (!"openTab".equals(command) && !"markAd".equals(command)
                && !"downloadImage".equals(command)) {
            throw new JSONException("不支持的右键操作");
        }
        runOnUiThread(new Runnable() {
            @Override public void run() {
                if ("openTab".equals(command)) {
                    if (webSourceView != null) webSourceView.openLinkInNewTab(url);
                } else if ("markAd".equals(command)) {
                    boolean added = webSourceView != null && webSourceView.markImageAsAd(url);
                    Toast.makeText(MainActivity.this,
                            added ? "已标记为广告" : "该图片已在广告规则中",
                            Toast.LENGTH_SHORT).show();
                } else {
                    publishBrowserImageDownload(url);
                }
            }
        });
        String message = "openTab".equals(command) ? "已在新标签打开"
                : "markAd".equals(command) ? "已标记为广告" : "已加入下载任务";
        return new JSONObject().put("ok", true).put("message", message).toString();
    }

    private static boolean isHttpUrl(String value) {
        try {
            Uri uri = Uri.parse(value);
            return uri.getHost() != null && ("http".equalsIgnoreCase(uri.getScheme())
                    || "https".equalsIgnoreCase(uri.getScheme()));
        } catch (RuntimeException ignored) { return false; }
    }

    private void publishBrowserImageDownload(String url) {
        if (!isHttpUrl(url)) {
            Toast.makeText(this, "图片地址无效", Toast.LENGTH_SHORT).show();
            return;
        }
        String referer = webSourceView == null ? "" : webSourceView.activePageUrl();
        String userAgent = webSourceView == null ? "" : webSourceView.activeUserAgent();
        String cookies = CookieManager.getInstance().getCookie(url);
        synchronized (browserActionLock) {
            long id = ++browserActionId;
            String guessed = URLUtil.guessFileName(url, null, null);
            String extension = "";
            int dot = guessed == null ? -1 : guessed.lastIndexOf('.');
            if (dot >= 0 && guessed.length() - dot <= 10) {
                extension = guessed.substring(dot).replaceAll("[^A-Za-z0-9.]", "");
            }
            String fileName = "ntv-image-" + id + (extension.length() > 1 ? extension : ".jpg");
            browserImageDownloads.put(id, new BrowserImageDownload(url,
                    referer == null ? "" : referer,
                    userAgent == null ? "" : userAgent,
                    cookies == null ? "" : cookies, fileName));
            while (browserImageDownloads.size() > 8) {
                Long oldest = browserImageDownloads.keySet().iterator().next();
                browserImageDownloads.remove(oldest);
            }
            browserActionType = "download";
            browserActionText = "/api/browser/download?id=" + id;
            browserActionMessage = "已在手机打开图片下载";
        }
        Toast.makeText(this, "已发送到手机管理网页", Toast.LENGTH_SHORT).show();
    }

    private void publishBrowserClipboard(String text, String message) {
        synchronized (browserActionLock) {
            browserActionId++;
            browserActionType = "clipboard";
            browserActionText = text == null ? "" : text;
            browserActionMessage = message == null || message.length() == 0
                    ? "已复制到剪切板" : message;
        }
    }

    private JSONObject browserActionJson() throws JSONException {
        synchronized (browserActionLock) {
            return new JSONObject().put("id", browserActionId)
                    .put("type", browserActionType)
                    .put("value", browserActionText)
                    .put("message", browserActionMessage);
        }
    }

    private String browserActionResponse(long afterId) throws JSONException {
        JSONObject response = new JSONObject().put("ok", true);
        synchronized (browserActionLock) {
            response.put("event", browserActionId > afterId
                    ? new JSONObject().put("id", browserActionId)
                            .put("type", browserActionType)
                            .put("value", browserActionText)
                            .put("message", browserActionMessage)
                    : JSONObject.NULL);
        }
        return response.toString();
    }

    private LocalControlServer.Resource browserImageDownload(long eventId) throws IOException {
        final BrowserImageDownload item;
        synchronized (browserActionLock) {
            item = browserImageDownloads.get(eventId);
        }
        if (item == null) throw new IOException("图片下载任务已失效，请重新点击下载");
        HttpURLConnection connection = NetworkClient.open(new URL(item.url));
        connection.setConnectTimeout(15000);
        connection.setReadTimeout(30000);
        connection.setInstanceFollowRedirects(true);
        if (item.referer.length() > 0) connection.setRequestProperty("Referer", item.referer);
        if (item.userAgent.length() > 0) connection.setRequestProperty("User-Agent", item.userAgent);
        if (item.cookies.length() > 0) connection.setRequestProperty("Cookie", item.cookies);
        try {
            int status = connection.getResponseCode();
            if (status < 200 || status >= 300) throw new IOException("图片服务器返回 HTTP " + status);
            int declaredLength = connection.getContentLength();
            if (declaredLength > MAX_BROWSER_IMAGE_DOWNLOAD_BYTES) {
                throw new IOException("图片超过 24MB，已取消下载");
            }
            InputStream input = connection.getInputStream();
            ByteArrayOutputStream output = new ByteArrayOutputStream(
                    declaredLength > 0 ? Math.min(declaredLength, 1024 * 1024) : 32768);
            try {
                byte[] buffer = new byte[16384];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    if (output.size() + count > MAX_BROWSER_IMAGE_DOWNLOAD_BYTES) {
                        throw new IOException("图片超过 24MB，已取消下载");
                    }
                    output.write(buffer, 0, count);
                }
            } finally {
                input.close();
            }
            String contentType = connection.getContentType();
            if (contentType == null || contentType.length() == 0
                    || contentType.indexOf('\r') >= 0 || contentType.indexOf('\n') >= 0) {
                contentType = "application/octet-stream";
            }
            return new LocalControlServer.Resource(contentType, output.toByteArray(), item.fileName);
        } finally {
            connection.disconnect();
        }
    }

    /** Shared final dispatch for HTTP and local WebView controls; caller is on the main thread. */
    private void applyLocalPointer(String action, float dx, float dy,
            int scrollX, int scrollY, float zoomFactor,
            int keyCode, int metaState, String text) {
        if ("back".equals(action) || "key".equals(action) && keyCode == KeyEvent.KEYCODE_BACK) {
            dispatchFlyMouseButtonUp(true);
            onBackPressed();
            return;
        }
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
        } else if ("rightclick".equals(action)) {
            dispatchFlyMouseButtonUp(true);
            flyMouseActionButton = MotionEvent.BUTTON_SECONDARY;
            try { dispatchFlyMouseClick(); }
            finally { flyMouseActionButton = MotionEvent.BUTTON_PRIMARY; }
            showFlyMouseSmartContext();
        } else if ("down".equals(action)) {
            dispatchFlyMouseButtonDown();
        } else if ("up".equals(action)) {
            dispatchFlyMouseButtonUp(false);
        } else if ("cancel".equals(action)) {
            dispatchFlyMouseButtonUp(true);
        } else if ("scroll".equals(action)) {
            dispatchFlyMouseButtonUp(true);
            handleRemoteScroll(scrollX, scrollY);
        } else if ("webBack".equals(action) || "webForward".equals(action)) {
            dispatchFlyMouseButtonUp(true);
            if (webSourceView != null && webSourceView.isPageVisible()) {
                if ("webBack".equals(action)) webSourceView.goBackIfPossible();
                else webSourceView.goForwardIfPossible();
            }
        } else if ("zoom".equals(action)) {
            dispatchFlyMouseButtonUp(true);
            adjustRemoteWebPageScale(zoomFactor);
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

    private void showFlyMouseSmartContext() {
        if (webSourceView == null || !webSourceView.isPageVisible()
                || flyMouseCursor == null || root == null) return;
        int[] rootLocation = new int[2];
        root.getLocationOnScreen(rootLocation);
        float screenX = rootLocation[0] + flyMouseCursor.cursorX();
        float screenY = rootLocation[1] + flyMouseCursor.cursorY();
        if (!webSourceView.isBrowserNativeContextPoint(screenX, screenY)) {
            webSourceView.showSmartContextAt(screenX, screenY);
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
            if (Build.VERSION.SDK_INT >= 16) {
                root.postOnAnimation(applyPendingFlyMouseMove);
            } else {
                root.postDelayed(applyPendingFlyMouseMove, 16L);
            }
        }
    }

    /** Receiver-side lease expiry. The old device immediately gives ownership of
     * input and channels back to itself after the controller goes silent. */
    private void exitRemoteCatalogTakeover(String message) {
        if (remoteCatalogUrl.length() == 0) {
            return;
        }
        restoreReceiverChannelPending = receiverChannelBeforeTakeover != null;
        final String endedSession = remoteTakeoverSessionId;
        remoteCatalogUrl = "";
        remoteCatalogClient.clearPlaybackRoutes();
        if (wifiDirectCoordinator != null) wifiDirectCoordinator.releaseGroupForReuse();
        remoteTakeoverSessionId = "";
        if (controlServer != null) controlServer.closeTakeoverSession(endedSession);
        root.removeCallbacks(receiverTakeoverWatchdog);
        dispatchFlyMouseButtonUp(true);
        resetReceiverTelemetry();
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

        hideLoading();
        loadCompleteCatalogInBackground();
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
        Log.w(TAG, message);
    }

    private void resetReceiverTelemetry() {
        clearReceiverCursor();
        remoteNetworkDelayMs = -1L;
        remoteEncodeDelayMs = -1L;
        remoteCastVideoBitrate = -1L;
        remoteCastAudioBitrate = -1L;
        remoteVideoQueueDelayMs = -1L;
        remoteVideoSendDelayMs = -1L;
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
        String activeUrl = activePlayerStreamUrl;
        if (activeProxy == null || activeUrl == null || activeUrl.length() == 0) {
            throw new IOException("当前频道还没有可录制的视频流");
        }
        HlsProxyServer.ProxyResponse response = activeProxy.fetchForRecording(
                activeUrl, token, "/api/recording/resource/");
        return new LocalControlServer.Resource(response.contentType, response.body);
    }

    private void handleRemoteScroll(int scrollX, int scrollY) {
        if (channelListPanel != null && channelListPanel.getVisibility() == View.VISIBLE) {
            // The menu is above a playing WebView. Hit-test the cursor, not the
            // keyboard focus, and do not leak wheel input into the page below it.
            ListView target = listUnderFlyMouse(epgList) ? epgList
                    : listUnderFlyMouse(groupList) ? groupList
                    : listUnderFlyMouse(channelList) ? channelList : null;
            if (target != null && scrollY != 0) {
                channelListPanel.removeCallbacks(hideChannelList);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                    target.scrollListBy(scrollY);
                } else {
                    // API 14-18 also preserve small pixel deltas and partial rows.
                    target.smoothScrollBy(scrollY, 0);
                }
                scheduleChannelListDismiss();
            }
            return;
        }
        if (webSourceView != null && webSourceView.isPageVisible()) {
            if (scrollX != 0 && flyMouseCursor != null && root != null) {
                int[] rootLocation = new int[2];
                root.getLocationOnScreen(rootLocation);
                float screenX = rootLocation[0] + flyMouseCursor.cursorX();
                float screenY = rootLocation[1] + flyMouseCursor.cursorY();
                if (webSourceView.scrollBrowserChromeAt(screenX, screenY, scrollX)) return;
            }
            // Wheel events are hit-tested at the cursor (including nested players,
            // iframes and scroll panes), not an unconditional scroll of the document.
            dispatchFlyMouseMotionEvent(MotionEvent.ACTION_SCROLL,
                    SystemClock.uptimeMillis(), scrollX, scrollY);
            webSourceView.settleRemoteWebScroll();
            return;
        }
    }

    private boolean listUnderFlyMouse(ListView list) {
        if (list == null || !list.isShown() || flyMouseCursor == null || root == null) return false;
        Rect bounds = new Rect();
        if (!list.getLocalVisibleRect(bounds)) return false;
        ((ViewGroup) root).offsetDescendantRectToMyCoords(list, bounds);
        return bounds.contains((int) flyMouseCursor.cursorX(), (int) flyMouseCursor.cursorY());
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
            // Restoring the flymouse setting must not reveal a pointer over TV/radio.
            // Non-web channels reveal it only when pointer input arrives.
            if (webSourceView != null && webSourceView.isPageVisible()) {
                flyMouseCursor.resetPosition();
            }
            ensureFlyMouseOnTop();
        }
    }

    private boolean isFlyMouseInteractionEnabled() {
        return flyMouseEnabled;
    }

    private void ensureFlyMouseOnTop() {
        if ((isFlyMouseInteractionEnabled() || receiverCursorActive) && flyMouseCursor != null) {
            // bringToFront requests layout even when this child is already last.
            // Pointer motion must not relayout the WebView on every sample.
            android.view.ViewParent parent = flyMouseCursor.getParent();
            if (parent instanceof ViewGroup) {
                ViewGroup group = (ViewGroup) parent;
                if (group.getChildAt(group.getChildCount() - 1) != flyMouseCursor) {
                    flyMouseCursor.bringToFront();
                }
            }
        }
    }

    private int flyMouseActionButton = MotionEvent.BUTTON_PRIMARY;

    private void dispatchFlyMouseClick() {
        if (flyMouseButtonDown) return;
        dispatchFlyMouseButtonDown();
        dispatchFlyMouseButtonUp(false);
    }

    private String handleWebSettings(JSONObject request) throws Exception {
        if (request.has("githubProxyBaseUrl")) {
            GithubProxy.setBaseUrl(request.optString("githubProxyBaseUrl", ""));
            getSharedPreferences(PREFERENCES, MODE_PRIVATE).edit()
                    .putString(GithubProxy.PREFERENCE, GithubProxy.baseUrl()).apply();
        }
        if (request.has("githubProxyEnabled")) {
            GithubProxy.setEnabled(request.getBoolean("githubProxyEnabled"));
            getSharedPreferences(PREFERENCES, MODE_PRIVATE).edit()
                    .putBoolean(GithubProxy.ENABLED_PREFERENCE, GithubProxy.isEnabled()).apply();
        }
        if (request.has("wifiDirectExperimental")) {
            boolean enabled = request.optBoolean("wifiDirectExperimental", false);
            if (enabled != wifiDirectExperimental) {
                if (remoteCatalogUrl.length() > 0 || hasActiveMultimedia()) {
                    throw new IOException("请先结束接管，再更改 Wi-Fi Direct 开关");
                }
                wifiDirectExperimental = enabled;
                getSharedPreferences(PREFERENCES, MODE_PRIVATE).edit()
                        .putBoolean(WIFI_DIRECT_EXPERIMENTAL, enabled).apply();
                if (!enabled && remoteCatalogUrl.length() == 0 && wifiDirectCoordinator != null) {
                    wifiDirectCoordinator.removeGroup();
                }
            }
        }
        boolean restartPlayback = false;
        boolean recreateSurface = false;
        boolean applyWebViewSettings = false;
        if (request.has("cjsPluginManifestUrl")) {
            CjsPluginRuntime.setManifestUrl(request.optString("cjsPluginManifestUrl", ""));
        }
        final boolean updateCjsPlugin = request.optBoolean("updateCjsPlugin", false);
        if (request.has("dnsMode")) {
            String rawDns = request.optString("dnsMode", NetworkClient.DEFAULT_DNS);
            String requestedDns = NetworkClient.sanitizeDnsMode(rawDns);
            if (!requestedDns.equals(rawDns)) {
                throw new JSONException("不支持的 DNS 配置");
            }
            restartPlayback |= !requestedDns.equals(NetworkClient.getDnsMode());
            NetworkClient.setDnsMode(requestedDns);
        }
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
        if (request.has("hdrMode")) {
            String raw = request.optString("hdrMode", "");
            String requested = HdrMode.sanitize(raw);
            if (!requested.equals(raw)) throw new JSONException("不支持的 HDR 模式");
            restartPlayback |= !requested.equals(hdrMode);
            hdrMode = requested;
            autoSoftwareDecode = false;
            getSharedPreferences(PREFERENCES, MODE_PRIVATE).edit()
                    .putString(HDR_MODE, hdrMode).apply();
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
        if (request.has("webViewAutoCloseSniffed")) {
            final boolean autoClose = request.optBoolean("webViewAutoCloseSniffed", true);
            runOnMainThreadAndWait(() -> {
                if (webViewAutoCloseSniffed != autoClose) sniffedPlaybackWarmup.reset();
                webViewAutoCloseSniffed = autoClose;
            });
            getSharedPreferences(PREFERENCES, MODE_PRIVATE).edit()
                    .putBoolean(WEB_VIEW_AUTO_CLOSE_SNIFFED, autoClose).apply();
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
        if (request.has("webViewBrowserVersion")) {
            String rawVersion = request.optString("webViewBrowserVersion",
                    WEB_VIEW_BROWSER_VERSION_NATIVE);
            String requestedVersion = sanitizeWebViewBrowserVersion(rawVersion);
            if (!requestedVersion.equals(rawVersion)) {
                throw new JSONException("不支持的浏览器版本");
            }
            webViewBrowserVersion = requestedVersion;
            getSharedPreferences(PREFERENCES, MODE_PRIVATE).edit()
                    .putString(WEB_VIEW_BROWSER_VERSION, webViewBrowserVersion).apply();
            applyWebViewSettings = true;
        }
        if (request.has("webViewAdBlock")) {
            webViewAdBlock = request.optBoolean("webViewAdBlock", true);
            getSharedPreferences(PREFERENCES, MODE_PRIVATE).edit()
                    .putBoolean(WEB_VIEW_AD_BLOCK, webViewAdBlock).apply();
            applyWebViewSettings = true;
        }
        final boolean refreshWebAdBlockRules =
                request.optBoolean("refreshWebAdBlockRules", false);
        if (refreshWebAdBlockRules) {
            WebAdBlocker.initialize(this);
            WebAdBlocker.refreshAsync(true);
        }
        if (request.has("webViewWebRtcEnabled")) {
            webViewWebRtcEnabled = request.optBoolean("webViewWebRtcEnabled", false);
            getSharedPreferences(PREFERENCES, MODE_PRIVATE).edit()
                    .putBoolean(WEB_VIEW_WEBRTC_ENABLED, webViewWebRtcEnabled).apply();
            applyWebViewSettings = true;
        }
        if (request.has("webViewUserScriptEnabled")) {
            webViewUserScriptEnabled = request.optBoolean("webViewUserScriptEnabled", false);
            getSharedPreferences(PREFERENCES, MODE_PRIVATE).edit()
                    .putBoolean(WEB_VIEW_USER_SCRIPT_ENABLED, webViewUserScriptEnabled).apply();
            applyWebViewSettings = true;
        }
        if(request.has("webViewLegacyTranspilation")){
            final boolean enabled=request.optBoolean("webViewLegacyTranspilation",false);
            getSharedPreferences(PREFERENCES,MODE_PRIVATE).edit().putBoolean("legacy_js_transpilation",enabled).apply();
            runOnUiThread(()->{if(webSourceView!=null)webSourceView.setLegacyTranspilation(enabled);});
        }
        if (request.has("webViewUserScripts")) {
            JSONArray requestedScripts = request.optJSONArray("webViewUserScripts");
            if (requestedScripts == null) throw new JSONException("脚本列表格式无效");
            webViewUserScripts = normalizeWebViewUserScripts(requestedScripts);
            getSharedPreferences(PREFERENCES, MODE_PRIVATE).edit()
                    .putString(WEB_VIEW_USER_SCRIPTS, webViewUserScripts).apply();
            applyWebViewSettings = true;
        } else if (request.has("webViewUserScript")) {
            // Keep older controllers working and migrate their single script.
            webViewUserScripts = legacyWebViewUserScripts(
                    request.optString("webViewUserScript", ""));
            getSharedPreferences(PREFERENCES, MODE_PRIVATE).edit()
                    .putString(WEB_VIEW_USER_SCRIPTS, webViewUserScripts).apply();
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

        if (applyWebViewSettings) {
            final String requestedWebViewResolution = webViewResolution;
            final boolean requestedWebViewLoadImages = webViewLoadImages;
            final String requestedWebViewUserAgent = webViewUserAgent;
            final String requestedWebViewBrowserVersion = webViewBrowserVersion;
            final float requestedWebViewPageScale = webViewPageScale;
            final boolean requestedWebViewAdBlock = webViewAdBlock;
            final boolean requestedWebViewWebRtc = webViewWebRtcEnabled;
            final boolean requestedWebViewUserScriptEnabled = webViewUserScriptEnabled;
            final String requestedWebViewUserScripts = webViewUserScripts;
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    if (webSourceView != null) {
                        webSourceView.applyConfiguration(requestedWebViewResolution,
                                requestedWebViewLoadImages, requestedWebViewUserAgent,
                                requestedWebViewBrowserVersion, requestedWebViewPageScale,
                                requestedWebViewAdBlock, requestedWebViewWebRtc,
                                requestedWebViewUserScriptEnabled, requestedWebViewUserScripts);
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
        if (request.has("epgUrls") || request.has("epgUrl")) {
            String[] requestedEpgUrls = request.has("epgUrls")
                    ? sanitizeEpgUrls(request.optJSONArray("epgUrls"))
                    : sanitizeEpgUrls(new String[] { request.optString("epgUrl", "") });
            epgUrls = requestedEpgUrls;
            epgUrl = epgUrls.length == 0 ? "" : epgUrls[0];
            SharedPreferences.Editor editor = getSharedPreferences(
                    PREFERENCES, MODE_PRIVATE).edit();
            if (epgUrls.length == 0) {
                editor.remove(EPG_URL).remove(EPG_URLS);
            } else {
                editor.putString(EPG_URL, epgUrl)
                        .putString(EPG_URLS, epgUrlsJson(epgUrls).toString());
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
        if (request.has("autoSwitchSourceSeconds") || request.has("autoSwitchSource")) {
            final int seconds = request.has("autoSwitchSourceSeconds")
                    ? request.getInt("autoSwitchSourceSeconds")
                    : request.optBoolean("autoSwitchSource", false)
                    ? (autoSwitchSourceSeconds > 0 ? autoSwitchSourceSeconds : 5) : 0;
            if (seconds != 0 && seconds != 5 && seconds != 10) {
                throw new JSONException("自动切换线路仅支持关闭、5 秒或 10 秒");
            }
            autoSwitchSourceSeconds = seconds;
            autoSwitchSource = seconds > 0;
            getSharedPreferences(PREFERENCES, MODE_PRIVATE).edit()
                    .putInt(AUTO_SWITCH_SOURCE_SECONDS, seconds)
                    .putBoolean(AUTO_SWITCH_SOURCE, autoSwitchSource).apply();
            runOnUiThread(() -> scheduleCustomSourceTimeout(currentChannel(), playRequestId));
            Log.i(TAG, "Automatic source switching seconds=" + seconds);
        }
        if (request.has("autoUpdateChannelList")) {
            autoUpdateChannelList = request.optBoolean("autoUpdateChannelList", false);
            getSharedPreferences(PREFERENCES, MODE_PRIVATE).edit()
                    .putBoolean(AUTO_UPDATE_CHANNEL_LIST, autoUpdateChannelList).apply();
            Log.i(TAG, "Automatic channel list update=" + autoUpdateChannelList);
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
                        // Invalidate local callbacks before accepting the sender's catalog.
                        ++playRequestId;
                        cancelPendingRelativeSwitch();
                        pendingCjsChannelIndex = -1;
                        clearPendingPlayer();
                        releasePlayer();
                        if (proxy != null) {
                            proxy.close();
                            proxy = null;
                        }
                        if (ku9ScriptResolver != null) ku9ScriptResolver.cancel();
                        if (cjsSiteResolver != null) cjsSiteResolver.cancel();
                        if (yangshipinResolver != null) yangshipinResolver.destroy();
                        if (webSourceView != null) webSourceView.closePage();
                        clearSniffedResources();
                        abortChannelSwitchAnimation();
                        hideLoading();
                        closeChannelList();
                        Log.i(TAG, "Receiver claim: stopped previous playback and web page");
                        closeManagementPanel();
                        ManagementActivity.closeAll();
                    }
                });
            }
            if (endingTakeover) {
                restoreReceiverChannelPending = receiverChannelBeforeTakeover != null;
            }
            if (controlServer != null && !previousRemoteSessionId.equals(
                    request.optString("claimSessionId", ""))) {
                controlServer.closeTakeoverSession(previousRemoteSessionId);
            }
            boolean changed = !requestedRemoteUrl.equals(remoteCatalogUrl);
            if (changed) remoteCatalogClient.clearPlaybackRoutes();
            remoteCatalogUrl = requestedRemoteUrl;
            if (enteringTakeover && wifiDirectCoordinator != null) wifiDirectCoordinator.useGroup();
            if (endingTakeover && wifiDirectCoordinator != null) wifiDirectCoordinator.releaseGroupForReuse();
            remoteTakeoverSessionId = requestedRemoteUrl.length() == 0 ? ""
                    : request.optString("claimSessionId", "");
            resetReceiverTelemetry();
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
        String message = clearWebCache ? "网页缓存已清除"
                : refreshWebAdBlockRules ? "anti-AD 规则正在更新"
                : "设置已保存";
        if (updateCjsPlugin) {
            String siteId = request.optString("cjsSiteId", "");
            String version = siteId.length() == 0 ? CjsPluginRuntime.installOrUpdate()
                    : CjsPluginRuntime.installOrUpdate(siteId);
            message = siteId.length() == 0 ? version : siteId + " v" + version + " 已下载";
        }
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
            if (!granted) {
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
                Toast.makeText(this, "未允许局域网设备访问权限", Toast.LENGTH_LONG).show();
            } else if (openManagementAfterGrant) {
                openManagement();
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
            int[] displayed = displayedChannelLocation();
            showChannelMenu(displayed[0] >= 0 ? displayed[0] : ChannelCatalog.firstPlayableGroupIndex());
        }
        epgAdapter.notifyDataSetChanged();
        refreshEpg();
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
                // The catalog cursor is not the current browser/bookmark channel.
                // Refreshing its old entry must not replace the page or sniffed playback.
                boolean browserChannelActive = webSourceView != null
                        && webSourceView.hasRetainedPage();
                String activeGroupTitle = null;
                String activeChannelKey = null;
                String activeSourceUrl = null;
                int activeCatalogSource = -1;
                int activeSourceIndex = currentSourceIndex;
                Channel activeChannel = null;
                boolean wasCustom = false;
                if (currentGroupIndex >= 0 && currentGroupIndex < before.length) {
                    ChannelCatalog.Group activeGroup = before[currentGroupIndex];
                    if (activeGroup.channels.length > 0) {
                        int activeIndex = ChannelCatalog.wrapIndex(
                                activeGroup.channels, currentChannelIndex);
                        activeChannel = activeGroup.channels[activeIndex];
                        activeGroupTitle = activeGroup.title;
                        activeChannelKey = favoriteKey(
                                activeGroup, activeChannel);
                        activeCatalogSource = catalogSource(activeGroup, activeChannel);
                        wasCustom = activeCatalogSource == ChannelCatalog.SOURCE_CUSTOM;
                        if (activeChannel.sourceCount() > 0) {
                            activeSourceUrl = activeChannel.sourceUrl(activeSourceIndex);
                        }
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
                    Channel restoredChannel = currentChannel();
                    int restoredSource = findSourceByUrl(restoredChannel, activeSourceUrl);
                    int sourceCount = Math.max(1, restoredChannel.sourceCount());
                    currentSourceIndex = restoredSource >= 0 ? restoredSource
                            : (activeSourceIndex % sourceCount + sourceCount) % sourceCount;
                    saveLastChannelSnapshot(currentGroup(), restoredChannel);
                }
                boolean playbackConfigurationChanged = hasPlayableChannel
                        && (activeCatalogSource != catalogSource(currentGroup(), currentChannel())
                        || !sameSourceUrl(activeSourceUrl,
                                currentChannel().sourceUrl(currentSourceIndex))
                        || !sameSourceConfiguration(activeChannel, currentChannel()));
                boolean selectionChanged = receiverRestoreApplied
                        || takeoverSelectionApplied || !hasPlayableChannel
                        || activeChannelKey == null
                        || !activeChannelKey.equals(favoriteKey(currentGroup(),
                                currentGroup().channels[ChannelCatalog.wrapIndex(
                                        currentGroup().channels, currentChannelIndex)]))
                        || playbackConfigurationChanged;
                browsingGroupIndex = currentGroupIndex;
                if (hasPlayableChannel && (!browserChannelActive || receiverRestoreApplied
                        || takeoverSelectionApplied) && (selectionChanged
                        || (restartActiveCustom && wasCustom))) {
                    Log.i(TAG, "Restarting active channel after catalog replacement: "
                            + currentChannel().name + " source=" + (currentSourceIndex + 1)
                            + "/" + Math.max(1, currentChannel().sourceCount()));
                    switchChannel(currentChannelIndex, currentSourceIndex);
                }
                if (channelListPanel.getVisibility() == View.VISIBLE) {
                    int[] displayed = displayedChannelLocation();
                    showChannelMenu(displayed[0] >= 0 ? displayed[0] : ChannelCatalog.firstPlayableGroupIndex());
                }
                refreshEpg();
                applied.countDown();
            }
        });
        applied.await(5L, TimeUnit.SECONDS);
    }

    private void loadCompleteCatalogInBackground() {
        final int loadGeneration = catalogLoadGeneration.incrementAndGet();
        final int requestedRemoteGeneration = remoteCatalogGeneration;
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
                            appliedRemoteCatalogGeneration = requestedRemoteGeneration;
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

    private static int findSourceByUrl(Channel channel, String sourceUrl) {
        if (channel == null || sourceUrl == null) return -1;
        for (int index = 0; index < channel.sourceCount(); index++) {
            if (Channel.sameSourceUrl(sourceUrl, channel.sourceUrl(index))) return index;
        }
        return -1;
    }

    private static boolean sameSourceUrl(String first, String second) {
        if (first == null || second == null) return first == second;
        return Channel.sameSourceUrl(first, second);
    }

    private static boolean sameSourceConfiguration(Channel first, Channel second) {
        if (first == null || second == null || first.sourceCount() != second.sourceCount()) {
            return first == second;
        }
        for (int index = 0; index < first.sourceCount(); index++) {
            if (!sameSourceUrl(first.sourceUrl(index), second.sourceUrl(index))) return false;
        }
        return true;
    }

    private boolean restoreReceiverChannelAfterTakeover() {
        LastChannelSnapshot snapshot = receiverChannelBeforeTakeover;
        restoreReceiverChannelPending = false;
        receiverChannelBeforeTakeover = null;
        return restoreChannelSelection(snapshot);
    }

    private boolean restoreChannelSelection(LastChannelSnapshot snapshot) {
        if (snapshot == null || snapshot.group == null
                || snapshot.group.channels == null
                || snapshot.group.channels.length == 0) {
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
                    emptyToNull(value.optString("epgId", ""))).withLogo(value.optString("logoUrl", "")).withSubtitles(value.optString("subtitleUrls", "")).withRadio(value.optBoolean("radio", false));
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
                    .put("logoUrl", channel.logoUrl).put("subtitleUrls", channel.subtitleUrlsText())
                    .put("radio", channel.radio)
                    .put("sourceIndex", currentSourceIndex);
            getSharedPreferences(PREFERENCES, MODE_PRIVATE).edit()
                    .putString(LAST_CHANNEL_SNAPSHOT, value.toString())
                    .putString("last_channel_favorite_key",
                            group.source == ChannelCatalog.SOURCE_FAVORITES
                                    && channel.favoriteKey != null ? channel.favoriteKey : "")
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
        if (MultiWindowCompat.isInMultiWindowMode(this)) {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_FORCE_NOT_FULLSCREEN);
            getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
            return;
        }
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_FORCE_NOT_FULLSCREEN);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        int flags = View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_FULLSCREEN;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            flags |= View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY;
        }
        getWindow().getDecorView().setSystemUiVisibility(flags);
    }

    private ChannelCatalog.Group currentGroup() {
        ChannelCatalog.Group[] groups = ChannelCatalog.GROUPS;
        int groupIndex = ChannelCatalog.playableGroupIndex(groups, currentGroupIndex);
        if (groupIndex < 0) {
            groups = ChannelCatalog.restorePlayableGroups();
            groupIndex = ChannelCatalog.playableGroupIndex(groups, currentGroupIndex);
        }
        currentGroupIndex = groupIndex;
        ChannelCatalog.Group group = groups[groupIndex];
        if (group.channels != null && group.channels.length > 0) {
            currentChannelIndex = ChannelCatalog.wrapIndex(
                    group.channels, currentChannelIndex);
        }
        return group;
    }

    private Channel currentChannel() {
        if (multimediaReceiverChannel != null) return multimediaReceiverChannel;
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

    private final LinkedHashSet<String> unavailableWebChannels = new LinkedHashSet<>();
    private boolean recoveringWebChannel;

    private void recoverUnavailableWebChannel(int requestId, String reason) {
        if (requestId != playRequestId || isFinishing()) return;
        String failed = currentGroupIndex + ":" + currentChannelIndex;
        unavailableWebChannels.add(failed);
        closeWebSource();
        abortChannelSwitchAnimation(); hideLoading();
        int group = currentGroupIndex, channel = currentChannelIndex;
        int count = 0;
        for (ChannelCatalog.Group item : ChannelCatalog.GROUPS) count += item.channels.length;
        for (int visited = 0; visited < count; visited++) {
            int[] next = adjacentChannelLocation(group, channel, 1);
            if (next == null) break;
            group = next[0]; channel = next[1];
            if (unavailableWebChannels.contains(group + ":" + channel)) continue;
            Log.w(TAG, "Web channel unavailable request=" + requestId + " reason=" + reason
                    + "; next=" + group + ":" + channel);
            currentGroupIndex = group;
            startActivity(new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT));
            recoveringWebChannel = true;
            try { switchChannel(channel); } finally { recoveringWebChannel = false; }
            Toast.makeText(this, "网页不可用，已切换到下一频道", Toast.LENGTH_LONG).show();
            return;
        }
        showChannelBar(currentChannel().name, "网页不可用，请选择其他频道");
        startActivity(new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT));
        openChannelList(true);
    }

    private void switchChannel(int index) {
        switchChannel(index, 0);
    }

    private void switchChannel(int index, int sourceIndex) {
        if (!recoveringWebChannel) unavailableWebChannels.clear();
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

    private void beginChannelSwitchTiming(int requestId, long startedAt) {
        channelSwitchTimingRequestId = requestId;
        channelSwitchTimingStartedAt = startedAt;
        Log.i(TAG, "Channel switch begin request=" + requestId
                + " sdk=" + Build.VERSION.SDK_INT
                + " delayMode=" + liveDelayMode
                + " channel=" + currentChannel().name);
    }

    private void logChannelSwitchStage(int requestId, String stage) {
        if (requestId != channelSwitchTimingRequestId || channelSwitchTimingStartedAt <= 0L) return;
        Log.i(TAG, "Channel switch stage=" + stage + " request=" + requestId
                + " elapsedMs=" + (SystemClock.elapsedRealtime() - channelSwitchTimingStartedAt));
    }

    private void startChannel(int index) {
        final long switchStartedAt = SystemClock.elapsedRealtime();
        if (multimediaSuspended) return;
        cancelHttpResolve();
        cancelCustomSourceTimeout();
        multimediaReceiverChannel = null;
        pendingCjsChannelIndex = -1;
        armCrashRecovery();
        if (navigateReceivedCastPage(index)) return;
        final boolean committedGestureSwitch = channelSwitchAnimating
                && (channelSwitchDirectionY != 0f || channelSwitchDirectionX != 0f);
        closeWebSource();
        webStreamHeaders = null;
        final ChannelCatalog.Group group = currentGroup();
        currentChannelIndex = ChannelCatalog.wrapIndex(group.channels, index);
        final Channel channel = group.channels[currentChannelIndex];
        if (artworkGroupIndex >= 0 && (artworkGroupIndex != currentGroupIndex
                || artworkChannelIndex != currentChannelIndex)) {
            int direction = relativeArtworkDirection != 0 ? relativeArtworkDirection
                    : currentGroupIndex != artworkGroupIndex
                    ? (currentGroupIndex > artworkGroupIndex ? 1 : -1)
                    : (currentChannelIndex > artworkChannelIndex ? 1 : -1);
            audioArtwork.beginChannelSwitch(direction);
            if (isAudioArtworkChannel(channel, currentSourceIndex)) {
                audioArtwork.showPending(channel.name, channel.logoUrl, AlbumArtLoader.cachedLogo(channel.logoUrl));
                prefetchAdjacentArtwork();
            } else {
                audioArtwork.finishChannelSwitch();
                albumArtLoader.cancelNeighbors();
            }
        }
        relativeArtworkDirection = 0;
        artworkGroupIndex = currentGroupIndex;
        artworkChannelIndex = currentChannelIndex;

        final int source = catalogSource(group, channel);
        activeCjsSource = null;
        try {
            if (source == ChannelCatalog.SOURCE_CUSTOM)
                activeCjsSource = CjsSource.parse(channel.sourceUrl(currentSourceIndex));
            if (activeCjsSource != null) {
                if (!CjsPluginRuntime.knowsSource(activeCjsSource)) {
                    installCjsPluginAndStart(currentChannelIndex, channel.name, activeCjsSource.url);
                    return;
                }
                CjsPluginRuntime.playbackUrl(activeCjsSource.url);
            }
        } catch (Exception error) {
            abortChannelSwitchAnimation();
            hideLoading();
            showChannelBar(channel.name, "CJS 频道配置错误：" + error.getMessage());
            return;
        }
        if (requiresCjsPlugin(channel, source) && !CjsPluginRuntime.hasCatalog()) {
            installCjsPluginAndStart(currentChannelIndex, channel.name, "");
            return;
        }
        String cjsComponent = cjsComponentForChannel(channel, source);
        if (cjsComponent.length() > 0 && !CjsPluginRuntime.isInstalled(cjsComponent)) {
            installCjsPluginAndStart(currentChannelIndex, channel.name, cjsComponent);
            return;
        }
        pendingCjsComponentCheck = CjsPluginRuntime.needsComponentCheck(cjsComponent)
                ? cjsComponent : "";
        syncPlaybackRecoveryTarget();
        saveLastChannelSnapshot(group, channel);
        configureEmbeddedResolverMode(group, channel);
        updatePlayingChannelSelection();
        final int requestId = ++playRequestId;
        beginChannelSwitchTiming(requestId, switchStartedAt);

        if (channelSwitchAnimating
                && (channelSwitchDirectionY != 0f || channelSwitchDirectionX != 0f)) {
            channelSwitchRequestId = requestId;
            positionIncomingChannelOffscreen();
        }
        playerStartRetryCount = 0;
        legacyHardwareRetryRequestId = -1;
        clearPendingPlayer();
        releasePlayer(true);
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
        Runnable setup = new Runnable() {
            @Override public void run() {
                if (requestId != playRequestId || isFinishing()) return;
                pendingChannelSetupAfterRelease = null;
                try {
                    logChannelSwitchStage(requestId, "proxy-reset-begin");
                    // The outgoing HLS proxy must stay alive until MediaPlayer.release()
                    // has stopped the old LiveSession. Closing it first makes Android
                    // 4.0 retry the dead loopback connection for tens of seconds.
                    resetProxyForChannelSwitch();
                    logChannelSwitchStage(requestId, "proxy-ready");
                } catch (IOException error) {
                    Log.e(TAG, "Unable to reset proxy for channel switch", error);
                    abortChannelSwitchAnimation();
                    hideLoading();
                    showChannelBar(channel.name, "切换失败: " + error.getMessage());
                    return;
                }
                logChannelSwitchStage(requestId, "resolve-begin");
                if (source == ChannelCatalog.SOURCE_CCTV_WEB
                        || source == ChannelCatalog.SOURCE_CUSTOM) {
                    resolveFallbackUrl(channel, requestId);
                } else {
                    resolveYangshipinUrl(channel, requestId);
                }
            }
        };
        if (legacyPlayerReleasePending) {
            pendingChannelSetupAfterRelease = setup;
            updateLoadingStatus("等待上一频道播放器退出");
        } else {
            setup.run();
        }
    }

    /** Keep the decoder only when the sender confirms the exact same encoder session. */
    private boolean navigateReceivedCastPage(int index) {
        if (remoteCatalogUrl.length() == 0 || player == null || !prepared
                || !videoRenderingStarted || receiverStreamSessionId.length() == 0
                || !isNtVCastSource(activePlayerStreamUrl)) return false;
        final ChannelCatalog.Group group = currentGroup();
        final int nextIndex = ChannelCatalog.wrapIndex(group.channels, index);
        final Channel channel = group.channels[nextIndex];
        final String source = channel.sourceUrl(currentSourceIndex);
        if (!RemoteCatalogClient.isRemoteSource(source)) return false;
        cancelRemoteResolve();
        currentChannelIndex = nextIndex;
        final IMediaPlayer retained = player;
        final int requestId = playRequestId;
        abortChannelSwitchAnimation();
        updatePlayingChannelSelection();
        saveLastChannelSnapshot(group, channel);
        remoteResolveThread = new Thread(new Runnable() {
            @Override public void run() {
                final Thread worker = Thread.currentThread();
                try {
                    final RemoteCatalogClient.Result result = remoteCatalogClient.resolve(source,
                            getResources().getDisplayMetrics().widthPixels,
                            getResources().getDisplayMetrics().heightPixels, lowResourceDevice,
                            controlServer == null ? "" : controlServer.getLanUrlForPeer(remoteCatalogUrl));
                    if (worker.isInterrupted()) return;
                    runOnUiThread(new Runnable() {
                        @Override public void run() {
                            if (remoteResolveThread != worker || requestId != playRequestId) return;
                            remoteResolveThread = null;
                            if (player == retained && prepared && player.isPlaying()
                                    && receiverStreamSessionId.equals(result.castSessionId)
                                    && activePlayerStreamUrl.equals(result.url)
                                    && receiverCastTransport.equals(result.castTransport)) {
                                activePlayerChannel = channel;
                                dismissWebNavigationChannelBar();
                                Log.i(TAG, "Remote webpage switched with retained decoder: " + channel.name);
                            } else {
                                // A new encoder or a non-cast source needs the complete
                                // proxy/player reset path. Clear the reuse token first.
                                receiverStreamSessionId = "";
                                startChannel(currentChannelIndex);
                            }
                        }
                    });
                } catch (final Exception error) {
                    if (worker.isInterrupted()) return;
                    runOnUiThread(new Runnable() {
                        @Override public void run() {
                            if (remoteResolveThread != worker || requestId != playRequestId) return;
                            remoteResolveThread = null;
                            hideLoading();
                            showChannelBar(channel.name, "网页切换失败：" + error.getMessage());
                            Log.w(TAG, "Retained cast navigation failed", error);
                        }
                    });
                }
            }
        }, "remote-channel-resolve");
        remoteResolveThread.start();
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

    private String configuredPlaybackUrl(Channel channel) {
        String source = channel.sourceUrl(currentSourceIndex);
        try { return CjsPluginRuntime.playbackUrl(source); }
        catch (Exception ignored) { return source; } // startChannel reports invalid CJS inputs.
    }

    private String playbackResolutionMode() {
        String quality = activeCjsSource == null ? null : activeCjsSource.parameters.get("quality");
        return quality == null ? resolutionMode : quality;
    }

    private void configureEmbeddedResolverMode(ChannelCatalog.Group group, Channel channel) {
        activeEmbeddedCctvResolver = false;
        activeEmbeddedYangshipinResolver = false;
        activeEmbeddedCjsResolver = false;
        if (catalogSource(group, channel) != ChannelCatalog.SOURCE_CUSTOM) {
            return;
        }
        String configuredUrl = configuredPlaybackUrl(channel);
        if (!isWebViewSource(configuredUrl)) {
            activeEmbeddedCctvResolver = isCctvDirectStream(configuredUrl);
            return;
        }
        activeEmbeddedYangshipinResolver = extractYangshipinPid(configuredUrl) != null;
        String page = webViewPage(configuredUrl);
        activeEmbeddedCctvResolver = extractCctvWebChannel(configuredUrl) != null;
        activeEmbeddedCjsResolver = !activeEmbeddedYangshipinResolver && !activeEmbeddedCctvResolver
                && page != null && CjsPluginRuntime.supportsSite(page);
        activeEmbeddedCctvResolver = !activeEmbeddedYangshipinResolver
                && !activeEmbeddedCjsResolver
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

    private static boolean requiresCjsPlugin(Channel channel, int source) {
        if (source == ChannelCatalog.SOURCE_CCTV_WEB
                || source == ChannelCatalog.SOURCE_YSP_CCTV
                || source == ChannelCatalog.SOURCE_YSP_SATELLITE) {
            return true;
        }
        if (source != ChannelCatalog.SOURCE_CUSTOM || channel == null) {
            return false;
        }
        for (int index = 0; index < channel.sourceCount(); index++) {
            String url = channel.sourceUrl(index);
            if (isCctvDirectStream(url) || extractYangshipinPid(url) != null
                    || isWebViewSource(url) || CjsSource.isSource(url)) {
                return true;
            }
        }
        return false;
    }

    private String cjsComponentForChannel(Channel channel, int source) {
        if (source == ChannelCatalog.SOURCE_CCTV_WEB) return "tv.cctv.com";
        if (source == ChannelCatalog.SOURCE_YSP_CCTV
                || source == ChannelCatalog.SOURCE_YSP_SATELLITE) return "yangshipin.cn";
        if (source != ChannelCatalog.SOURCE_CUSTOM || channel == null) return "";
        String url = configuredPlaybackUrl(channel);
        if (extractYangshipinPid(url) != null) return "yangshipin.cn";
        if (isCctvDirectStream(url) || extractCctvWebChannel(url) != null) return "tv.cctv.com";
        if (isWebViewSource(url)) {
            String page = webViewPage(url);
            return page == null ? "" : CjsPluginRuntime.componentForUrl(page);
        }
        return "";
    }

    private void scheduleCjsComponentCheck(final String component) {
        // Let cached scripts/native code start playback first. The descriptor is tiny, but
        // even a DNS timeout must not lengthen the cold-start path on an Android 4.4 TV.
        root.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (cjsPluginInstallInProgress
                        || !CjsPluginRuntime.needsComponentCheck(component)) return;
                cjsPluginInstallInProgress = true;
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        boolean updated = false;
                        try {
                            updated = CjsPluginRuntime.ensureComponentCurrent(component);
                        } catch (Throwable error) {
                            // A version probe must never make a cached plugin unavailable.
                            CjsPluginRuntime.componentCheckFailed(component);
                            Log.w(TAG, "Component update check failed: " + component, error);
                        }
                        final boolean updateAvailable = updated;
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                cjsPluginInstallInProgress = false;
                                if (pendingCjsChannelIndex >= 0) {
                                    startChannel(currentChannelIndex);
                                    return;
                                }
                                if (updateAvailable) {
                                    showChannelBar(currentChannel().name,
                                            "播放插件已更新，重启后启用");
                                }
                            }
                        });
                    }
                }, "cjs-component-check").start();
            }
        }, 250L);
    }

    private void installCjsPluginAndStart(int channelIndex, String channelName, final String siteId) {
        pendingCjsChannelIndex = channelIndex;
        showLoading(channelName, "正在下载播放兼容插件");
        showChannelBar(channelName, "首次使用正在安装兼容插件");
        if (cjsPluginInstallInProgress) {
            return;
        }
        cjsPluginInstallInProgress = true;
        new Thread(new Runnable() {
            @Override
            public void run() {
                String installedVersion = null;
                Throwable failure = null;
                final long installStartedAt = SystemClock.elapsedRealtime();
                final String component = siteId.length() == 0 ? "catalog" : siteId;
                Log.i(TAG, "CJS install begin component=" + component);
                try {
                    if (CjsSource.isSource(siteId)) {
                        CjsPluginRuntime.prepareSource(siteId);
                        installedVersion = "CJS 频道入口";
                    } else if (siteId.length() == 0) {
                        CjsPluginRuntime.ensureCatalog();
                        installedVersion = "站点目录";
                    } else {
                        installedVersion = CjsPluginRuntime.installOrUpdate(siteId);
                    }
                } catch (Throwable error) {
                    failure = error;
                    Log.e(TAG, "Unable to install CJS plugin", error);
                }
                final String version = installedVersion;
                final Throwable error = failure;
                Log.i(TAG, "CJS install complete component=" + component
                        + " elapsedMs=" + (SystemClock.elapsedRealtime() - installStartedAt)
                        + " success=" + (failure == null));
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        cjsPluginInstallInProgress = false;
                        int requestedIndex = pendingCjsChannelIndex;
                        pendingCjsChannelIndex = -1;
                        if (requestedIndex < 0) return;
                        if (error != null) {
                            abortChannelSwitchAnimation();
                            hideLoading();
                            if (isDeviceNetworkDisconnected()) {
                                showChannelBar(currentChannel().name,
                                        "未连接网络，请连接网络后重试");
                                Toast.makeText(MainActivity.this,
                                        "未连接网络，请连接网络后重试",
                                        Toast.LENGTH_LONG).show();
                                return;
                            }
                            String reason = error.getMessage();
                            if (isPluginDownloadNetworkError(error)) {
                                showChannelBar(currentChannel().name,
                                        "无法连接插件下载服务器，请检查网络后重试");
                                Toast.makeText(MainActivity.this,
                                        "无法连接插件下载服务器，请检查网络后重试",
                                        Toast.LENGTH_LONG).show();
                                return;
                            }
                            showChannelBar(currentChannel().name,
                                    "兼容插件安装失败" + (reason == null ? "" : "：" + reason));
                            return;
                        }
                        Log.i(TAG, "CJS plugin activated version=" + version);
                        startChannel(currentChannelIndex);
                    }
                });
            }
        }, "cjs-plugin-install").start();
    }

    private static boolean isPluginDownloadNetworkError(Throwable error) {
        for (Throwable current = error; current != null; current = current.getCause()) {
            if (current instanceof java.net.UnknownHostException
                    || current instanceof java.net.SocketTimeoutException
                    || current instanceof java.net.ConnectException
                    || current instanceof java.net.NoRouteToHostException) {
                return true;
            }
            String message = current.getMessage();
            if (message == null) continue;
            message = message.toLowerCase(Locale.US);
            if (message.contains("unable to resolve host")
                    || message.contains("network is unreachable")
                    || message.contains("connection timed out")
                    || message.contains("failed to connect")
                    || message.contains("网络不可用")
                    || message.contains("无法解析主机")) {
                return true;
            }
        }
        return false;
    }

    private boolean isActiveCctvWebSource() {
        return currentCatalogSource() == ChannelCatalog.SOURCE_CCTV_WEB
                || activeEmbeddedCctvResolver;
    }

    private String customSourceStatus(String prefix) {
        Channel channel = currentChannel();
        String status = prefix == null ? "" : prefix.trim();
        if (status.endsWith("·")) {
            status = status.substring(0, status.length() - 1).trim();
        }
        return withSourceLineStatus(channel == null ? null : channel.name, status);
    }

    private android.app.AlertDialog sourceUrlErrorDialog;

    private void startBrowserUserScriptInstall(final String url) {
        if (!WebSourceView.isUserScriptInstallUrl(url) || isFinishing()
                || Build.VERSION.SDK_INT >= 17 && isDestroyed()) return;
        final int generation = ++userScriptInstallGeneration;
        showUserScriptLoadingOverlay(url, generation);
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    final JSONObject imported = UserScriptImporter.importScript(url);
                    runOnUiThread(new Runnable() {
                        @Override public void run() {
                            if (generation != userScriptInstallGeneration || isFinishing()
                                    || Build.VERSION.SDK_INT >= 17 && isDestroyed()) return;
                            showUserScriptConfirmationOverlay(imported, generation);
                        }
                    });
                } catch (final Exception error) {
                    Log.w(TAG, "Unable to read clicked userscript " + url, error);
                    runOnUiThread(new Runnable() {
                        @Override public void run() {
                            if (generation != userScriptInstallGeneration || isFinishing()
                                    || Build.VERSION.SDK_INT >= 17 && isDestroyed()) return;
                            String message = error.getMessage();
                            showUserScriptErrorOverlay(message == null || message.trim().length() == 0
                                    ? "无法读取这个脚本" : message.trim(), generation);
                        }
                    });
                }
            }
        }, "userscript-install").start();
    }

    private void showUserScriptLoadingOverlay(String url, final int generation) {
        LinearLayout card = beginUserScriptOverlay();
        card.addView(userScriptOverlayText("正在读取脚本", 24, Color.rgb(28, 31, 36), true));
        TextView explanation = userScriptOverlayText(
                "正在安全下载并检查 UserScript 元数据…", 16,
                Color.rgb(92, 98, 108), false);
        explanation.setPadding(0, userScriptOverlayDp(10), 0, userScriptOverlayDp(12));
        card.addView(explanation);
        TextView address = userScriptOverlayText(url, 14, Color.rgb(75, 82, 94), false);
        address.setMaxLines(3);
        address.setTextIsSelectable(true);
        card.addView(address);
        Button cancel = userScriptOverlayButton("取消");
        cancel.setOnClickListener(v -> {
            if (generation == userScriptInstallGeneration) dismissUserScriptInstallOverlay(true);
        });
        card.addView(userScriptOverlayActions(cancel, null));
        finishUserScriptOverlay(card);
        cancel.requestFocus();
    }

    private void showUserScriptConfirmationOverlay(final JSONObject imported,
            final int generation) {
        final JSONObject script = imported.optJSONObject("script");
        if (script == null) {
            showUserScriptErrorOverlay("脚本信息格式无效", generation);
            return;
        }
        final boolean updating = installedUserScriptIndex(script.optString("installUrl", "")) >= 0;
        LinearLayout card = beginUserScriptOverlay();
        card.addView(userScriptOverlayText(updating ? "更新用户脚本" : "安装用户脚本",
                24, Color.rgb(28, 31, 36), true));

        LinearLayout details = new LinearLayout(this);
        details.setOrientation(LinearLayout.VERTICAL);
        String name = script.optString("name", "导入的脚本");
        TextView nameView = userScriptOverlayText(name, 21, Color.rgb(20, 23, 28), true);
        nameView.setPadding(0, userScriptOverlayDp(12), 0, userScriptOverlayDp(6));
        details.addView(nameView);
        String version = script.optString("version", "").trim();
        if (version.length() > 0) {
            details.addView(userScriptOverlayText("版本：" + version, 15,
                    Color.rgb(78, 84, 94), false));
        }
        String description = script.optString("description", "").trim();
        if (description.length() > 0) {
            TextView descriptionView = userScriptOverlayText(description, 16,
                    Color.rgb(54, 59, 68), false);
            descriptionView.setPadding(0, userScriptOverlayDp(8), 0, 0);
            details.addView(descriptionView);
        }
        JSONArray matches = script.optJSONArray("matches");
        if (matches != null && matches.length() > 0) {
            StringBuilder matchText = new StringBuilder("适用网页：");
            int visible = Math.min(matches.length(), 6);
            for (int index = 0; index < visible; index++) {
                matchText.append("\n• ").append(matches.optString(index));
            }
            if (matches.length() > visible) {
                matchText.append("\n• 以及另外 ").append(matches.length() - visible).append(" 项");
            }
            TextView matchView = userScriptOverlayText(matchText.toString(), 14,
                    Color.rgb(67, 73, 84), false);
            matchView.setPadding(0, userScriptOverlayDp(10), 0, 0);
            details.addView(matchView);
        }
        JSONArray warnings = imported.optJSONArray("warnings");
        if (warnings != null && warnings.length() > 0) {
            StringBuilder warningText = new StringBuilder("兼容性提示：");
            for (int index = 0; index < warnings.length(); index++) {
                warningText.append("\n• ").append(warnings.optString(index));
            }
            TextView warningView = userScriptOverlayText(warningText.toString(), 14,
                    Color.rgb(174, 92, 0), false);
            warningView.setPadding(0, userScriptOverlayDp(10), 0, 0);
            details.addView(warningView);
        }
        TextView security = userScriptOverlayText(
                "脚本可以读取和修改匹配网页的内容。请只安装你信任的脚本。",
                14, Color.rgb(176, 43, 43), false);
        security.setPadding(0, userScriptOverlayDp(12), 0, userScriptOverlayDp(4));
        details.addView(security);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(false);
        scroll.addView(details, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT));
        int availableHeight = getResources().getDisplayMetrics().heightPixels
                - userScriptOverlayDp(290);
        card.addView(scroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                Math.max(userScriptOverlayDp(120), Math.min(userScriptOverlayDp(280),
                        availableHeight))));

        Button cancel = userScriptOverlayButton("取消");
        cancel.setOnClickListener(v -> {
            if (generation == userScriptInstallGeneration) dismissUserScriptInstallOverlay(true);
        });
        Button install = userScriptOverlayButton(updating ? "更新并启用" : "安装并启用");
        install.setOnClickListener(v -> {
            if (generation != userScriptInstallGeneration) return;
            try {
                boolean updated = installImportedUserScript(script);
                dismissUserScriptInstallOverlay(false);
                Toast.makeText(MainActivity.this,
                        updated ? "脚本已更新并启用" : "脚本已安装并启用",
                        Toast.LENGTH_SHORT).show();
            } catch (Exception error) {
                String message = error.getMessage();
                showUserScriptErrorOverlay(message == null ? "脚本保存失败" : message,
                        generation);
            }
        });
        card.addView(userScriptOverlayActions(cancel, install));
        finishUserScriptOverlay(card);
        install.requestFocus();
    }

    private void showUserScriptErrorOverlay(String message, final int generation) {
        LinearLayout card = beginUserScriptOverlay();
        card.addView(userScriptOverlayText("无法安装脚本", 24,
                Color.rgb(28, 31, 36), true));
        TextView detail = userScriptOverlayText(message, 16, Color.rgb(176, 43, 43), false);
        detail.setPadding(0, userScriptOverlayDp(12), 0, userScriptOverlayDp(8));
        detail.setTextIsSelectable(true);
        card.addView(detail);
        Button close = userScriptOverlayButton("关闭");
        close.setOnClickListener(v -> {
            if (generation == userScriptInstallGeneration) dismissUserScriptInstallOverlay(true);
        });
        card.addView(userScriptOverlayActions(close, null));
        finishUserScriptOverlay(card);
        close.requestFocus();
    }

    private LinearLayout beginUserScriptOverlay() {
        removeUserScriptInstallOverlay();
        userScriptInstallOverlay = new FrameLayout(this);
        userScriptInstallOverlay.setBackgroundColor(0xb8000000);
        userScriptInstallOverlay.setClickable(true);
        userScriptInstallOverlay.setFocusable(true);
        userScriptInstallOverlay.setContentDescription("用户脚本安装弹窗");
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(userScriptOverlayDp(28), userScriptOverlayDp(24),
                userScriptOverlayDp(28), userScriptOverlayDp(22));
        GradientDrawable background = new GradientDrawable();
        background.setColor(Color.rgb(250, 251, 253));
        background.setCornerRadius(userScriptOverlayDp(18));
        background.setStroke(userScriptOverlayDp(1), Color.rgb(210, 214, 222));
        card.setBackgroundDrawable(background);
        return card;
    }

    private void finishUserScriptOverlay(LinearLayout card) {
        if (userScriptInstallOverlay == null || !(root instanceof ViewGroup)) return;
        int screenWidth = getResources().getDisplayMetrics().widthPixels;
        int cardWidth = Math.max(userScriptOverlayDp(300),
                Math.min(userScriptOverlayDp(680), screenWidth - userScriptOverlayDp(48)));
        FrameLayout.LayoutParams cardParams = new FrameLayout.LayoutParams(
                cardWidth, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER);
        userScriptInstallOverlay.addView(card, cardParams);
        ((ViewGroup) root).addView(userScriptInstallOverlay,
                new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT));
        userScriptInstallOverlay.bringToFront();
        ensureFlyMouseOnTop();
    }

    private LinearLayout userScriptOverlayActions(Button first, Button second) {
        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        actions.setPadding(0, userScriptOverlayDp(18), 0, 0);
        if (first != null) actions.addView(first);
        if (second != null) {
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            params.leftMargin = userScriptOverlayDp(14);
            actions.addView(second, params);
        }
        return actions;
    }

    private Button userScriptOverlayButton(String label) {
        Button button = new Button(this);
        button.setText(label);
        button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        button.setMinWidth(userScriptOverlayDp(150));
        button.setMinHeight(userScriptOverlayDp(58));
        button.setPadding(userScriptOverlayDp(20), userScriptOverlayDp(8),
                userScriptOverlayDp(20), userScriptOverlayDp(8));
        button.setClickable(true);
        button.setFocusable(true);
        button.setFocusableInTouchMode(true);
        return button;
    }

    private TextView userScriptOverlayText(String value, int sizeSp, int color,
            boolean bold) {
        TextView text = new TextView(this);
        text.setText(value == null ? "" : value);
        text.setTextColor(color);
        text.setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp);
        text.setLineSpacing(0f, 1.12f);
        if (bold) text.setTypeface(android.graphics.Typeface.DEFAULT,
                android.graphics.Typeface.BOLD);
        return text;
    }

    private int userScriptOverlayDp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void removeUserScriptInstallOverlay() {
        if (userScriptInstallOverlay == null) return;
        ViewParent parent = userScriptInstallOverlay.getParent();
        if (parent instanceof ViewGroup) {
            ((ViewGroup) parent).removeView(userScriptInstallOverlay);
        }
        userScriptInstallOverlay = null;
    }

    private void dismissUserScriptInstallOverlay(boolean cancelPending) {
        if (cancelPending) userScriptInstallGeneration++;
        removeUserScriptInstallOverlay();
        if (webSourceView != null && webSourceView.isPageVisible()) webSourceView.requestFocus();
        ensureFlyMouseOnTop();
    }

    private int installedUserScriptIndex(String installUrl) {
        if (installUrl == null || installUrl.trim().length() == 0) return -1;
        try {
            JSONArray scripts = new JSONArray(webViewUserScripts);
            for (int index = 0; index < scripts.length(); index++) {
                JSONObject item = scripts.optJSONObject(index);
                if (item != null && installUrl.trim().equals(item.optString("installUrl", "").trim())) {
                    return index;
                }
            }
        } catch (JSONException ignored) { }
        return -1;
    }

    private boolean installImportedUserScript(JSONObject imported) throws Exception {
        String installUrl = imported.optString("installUrl", "").trim();
        JSONArray scripts = new JSONArray(webViewUserScripts);
        int existingIndex = installedUserScriptIndex(installUrl);
        if (existingIndex < 0 && scripts.length() >= MAX_WEB_VIEW_USER_SCRIPTS) {
            throw new JSONException("最多可配置 " + MAX_WEB_VIEW_USER_SCRIPTS + " 个脚本");
        }
        JSONObject existing = existingIndex < 0 ? null : scripts.optJSONObject(existingIndex);
        String id = existing == null ? "script-" + Long.toString(System.currentTimeMillis(), 36)
                : existing.optString("id", "script-" + (existingIndex + 1));
        JSONObject saved = new JSONObject()
                .put("id", id)
                .put("name", imported.optString("name", "导入的脚本"))
                .put("enabled", true)
                .put("source", imported.optString("source", ""))
                .put("installUrl", installUrl)
                .put("version", imported.optString("version", ""));
        if (existingIndex >= 0) scripts.put(existingIndex, saved); else scripts.put(saved);
        webViewUserScripts = normalizeWebViewUserScripts(scripts);
        webViewUserScriptEnabled = true;
        getSharedPreferences(PREFERENCES, MODE_PRIVATE).edit()
                .putString(WEB_VIEW_USER_SCRIPTS, webViewUserScripts)
                .putBoolean(WEB_VIEW_USER_SCRIPT_ENABLED, true)
                .apply();
        if (webSourceView != null) {
            webSourceView.applyConfiguration(webViewResolution, webViewLoadImages,
                    webViewUserAgent, webViewBrowserVersion, webViewPageScale,
                    webViewAdBlock, webViewWebRtcEnabled, true, webViewUserScripts);
        }
        return existingIndex >= 0;
    }

    private void showSourceUrlError(HttpStreamResolver.InvalidSourceUrlException error) {
        String message = "频道：" + currentChannel().name + " · 线路 "
                + (currentSourceIndex + 1) + "\n\n" + error.userMessage();
        // An invalid URL cannot recover by waiting; this is independent of the timeout setting.
        switchCustomSource(1, true, "地址格式错误", true);
        if (isFinishing() || (Build.VERSION.SDK_INT >= 17 && isDestroyed())) return;
        if (sourceUrlErrorDialog != null) sourceUrlErrorDialog.dismiss();
        sourceUrlErrorDialog = new android.app.AlertDialog.Builder(this)
                .setTitle("频道地址格式错误")
                .setMessage(message)
                .setPositiveButton("知道了", null)
                .setNeutralButton("复制错误详情", (dialog, which) -> {
                    android.content.ClipboardManager clipboard = (android.content.ClipboardManager)
                            getSystemService(CLIPBOARD_SERVICE);
                    if (clipboard != null) {
                        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("频道地址错误", message));
                        Toast.makeText(this, "错误详情已复制", Toast.LENGTH_SHORT).show();
                    }
                }).create();
        sourceUrlErrorDialog.show();
        TextView content = (TextView) sourceUrlErrorDialog.findViewById(android.R.id.message);
        if (content != null) content.setTextIsSelectable(true);
    }

    private void handleUnavailableSource(String label, String detail) {
        if (PlaybackHttpError.isForbidden(detail)) {
            showPlaybackForbidden(currentChannel());
            return;
        }
        if (returnFromFailedSniffedPlayback(label)) return;
        String message = detail == null ? "" : detail.toLowerCase(java.util.Locale.US);
        boolean disconnected = isDeviceNetworkDisconnected();
        if (disconnected || message.contains("unknownhost") || message.contains("unable to resolve host")
                || message.contains("timeout") || message.contains("timed out")
                || message.contains("network is unreachable") || message.contains("enetunreach")
                || message.contains("connection refused") || message.contains("connection reset")
                || message.contains("网络") || message.contains("超时")
                || message.contains("err_name_not_resolved") || message.contains("err_internet_disconnected")) {
            abortChannelSwitchAnimation();
            hideLoading();
            showChannelBar(currentChannel().name, "网络连接异常，请检查网络后重试");
            Toast.makeText(this, "网络连接异常，请检查网络连接", Toast.LENGTH_LONG).show();
            return;
        }
        switchCustomSource(1, true, label, true);
    }

    private boolean isDeviceNetworkDisconnected() {
        try {
            android.net.ConnectivityManager connectivity = (android.net.ConnectivityManager)
                    getSystemService(CONNECTIVITY_SERVICE);
            if (connectivity == null) return false;
            android.net.NetworkInfo network = connectivity.getActiveNetworkInfo();
            return network == null || !network.isConnected();
        } catch (RuntimeException ignored) { }
        return false;
    }

    private void showPlaybackForbidden(Channel channel) {
        if (returnFromFailedSniffedPlayback(PlaybackHttpError.FORBIDDEN_MESSAGE)) return;
        cancelCustomSourceTimeout();
        clearPendingPlayer();
        releasePlayer();
        abortChannelSwitchAnimation();
        hideLoading();
        showChannelBar(channel.name, PlaybackHttpError.FORBIDDEN_MESSAGE);
        Toast.makeText(this, "请更新授权地址，或从原网页重新嗅探资源", Toast.LENGTH_LONG).show();
    }

    private boolean switchCustomSource(int offset, boolean automatic, String reason) {
        return switchCustomSource(offset, automatic, reason, false);
    }

    private boolean switchCustomSource(int offset, boolean automatic, String reason,
            boolean confirmedUnavailable) {
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
        if (automatic && !autoSwitchSource && !confirmedUnavailable) {
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
        flyMouseCursor.revealCursor();
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
        boolean touchFallback = touchAction && !MouseButtonCompat.supported()
                && flyMouseActionButton == MotionEvent.BUTTON_PRIMARY;
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
            coords.setAxisValue(MotionEvent.AXIS_HSCROLL, -scrollX / Math.max(1f, factor));
        }
        MotionEvent event = MotionEvent.obtain(downTime, eventTime, action, 1,
                new MotionEvent.PointerProperties[]{properties},
                new MotionEvent.PointerCoords[]{coords}, 0,
                pressed ? flyMouseActionButton : 0, 1f, 1f, 0, 0,
                touchFallback ? InputDevice.SOURCE_TOUCHSCREEN : InputDevice.SOURCE_MOUSE, 0);
        if (Build.VERSION.SDK_INT >= 23 && (action == MotionEvent.ACTION_BUTTON_PRESS
                || action == MotionEvent.ACTION_BUTTON_RELEASE)) {
            if (!MouseButtonCompat.setButton(event, flyMouseActionButton)) {
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
                {
                    root.dispatchTouchEvent(event);
                }
            } else if (userScriptInstallOverlay == null
                    && webSourceView != null && webSourceView.isPageVisible()
                    && (channelListPanel == null || channelListPanel.getVisibility() != View.VISIBLE)
                    && (action == MotionEvent.ACTION_HOVER_MOVE
                        || action == MotionEvent.ACTION_HOVER_EXIT)) {
                webSourceView.dispatchRemoteMouseHover(root, event);
            } else {
                root.dispatchGenericMotionEvent(event);
            }
        } finally {
            event.recycle();
            ensureFlyMouseOnTop();
        }
    }

    private void cancelCustomSourceTimeout() {
        if (customSourceTimeout != null && channelBar != null) {
            channelBar.removeCallbacks(customSourceTimeout);
        }
        customSourceTimeout = null;
    }

    private void scheduleCustomSourceTimeout(final Channel channel, final int requestId) {
        cancelCustomSourceTimeout();
        final int timeoutSeconds = autoSwitchSourceSeconds;
        if (!autoSwitchSource || timeoutSeconds <= 0 || channelBar == null
                || playbackReadyRequestId == requestId
                || currentCatalogSource() != ChannelCatalog.SOURCE_CUSTOM
                || channel.sourceCount() <= 1) return;
        customSourceTimeout = new Runnable() {
            @Override
            public void run() {
                if (customSourceTimeout != this) return;
                customSourceTimeout = null;
                if (requestId != playRequestId || playbackReadyRequestId == requestId
                        || !autoSwitchSource || autoSwitchSourceSeconds != timeoutSeconds
                        || currentCatalogSource() != ChannelCatalog.SOURCE_CUSTOM
                        || currentChannel().sourceCount() <= 1) {
                    return;
                }
                if (hasRetainedWebPlayback()) {
                    returnFromFailedSniffedPlayback("资源打开超时");
                    return;
                }
                switchCustomSource(1, true, "连接超过 " + timeoutSeconds + " 秒");
            }
        };
        channelBar.postDelayed(customSourceTimeout, timeoutSeconds * 1000L);
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
                playbackResolutionMode(),
                cctvStartupDownloadSegments(), cctvStartupDecryptSegments(),
                genericStartupPrefetchSegments());
        if (currentCatalogSource() == ChannelCatalog.SOURCE_CUSTOM) {
            next.configureVodDiskCache(this, LIVE_DELAY_STABLE.equals(liveDelayMode) ? 2
                    : LIVE_DELAY_BALANCED.equals(liveDelayMode) ? 1 : 0);
            final int warmupRequestId = playRequestId;
            next.setVodWarmupListener((milliseconds, enough) -> {
                IMediaPlayer current = player;
                if (proxy == next && playRequestId == warmupRequestId && current instanceof IjkMediaPlayer)
                    IjkStreamMetrics.excludeProxyWarmup((IjkMediaPlayer) current, milliseconds, enough);
            });
        }
        next.start();
        proxy = next;
        proxyStatefulCmgSource = statefulCmgSource;
        if (!LIVE_DELAY_LOW.equals(liveDelayMode)) {
            final int cacheRequestId = playRequestId;
            channelBar.postDelayed(new Runnable() {
                @Override public void run() {
                    if (proxy != next || playRequestId != cacheRequestId || isFinishing()
                            || videoRenderingStarted || audioOnlyPlayback && prepared) return;
                    if (next.isVodCachePreparing()) {
                        int remaining = next.vodCacheEstimatedWaitSeconds();
                        updateLoadingStatus(remaining > 0
                                ? "网速太慢，稳定播放预计还需 " + remaining + " 秒"
                                : "正在预缓冲：已下载 " + next.vodCachePreparingSeconds() + " 秒视频");
                    }
                    channelBar.postDelayed(this, 1000L);
                }
            }, 1000L);
        }
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
                    handleUnavailableSource("央视频解析失败", reason);
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
        cancelHttpResolve();
        final boolean directCustomSource = currentCatalogSource()
                == ChannelCatalog.SOURCE_CUSTOM;
        final String configuredUrl = directCustomSource
                ? configuredPlaybackUrl(channel) : channel.url;
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
        String cjsSitePage = webViewPage(configuredUrl);
        if (cjsSitePage != null && !CjsPluginRuntime.supportsSite(cjsSitePage)) {
            cjsSitePage = null;
        }
        if (cjsSitePage == null && cjsSiteResolver != null) {
            cjsSiteResolver.cancel();
        }
        if (isWebViewSource(configuredUrl)) {
            if (cjsSitePage != null) {
                resolveCjsSite(channel, cjsSitePage, requestId);
                return;
            }
            // Resolve supported providers before applying the visible-browser device gate.
            // Their internal authorization page feeds native playback; openWebSource
            // applies the gate only when we actually display an ordinary webpage.
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
        if (!directCustomSource) {
            resolveCjsSite(channel, "https://tv.cctv.com/live/" + channel.streamId + "/", requestId);
            return;
        }
        updateLoadingStatus(directCustomSource
                ? customSourceStatus("正在连接") : "正在获取高清线路");
        showChannelBar(channel.name, directCustomSource
                ? customSourceStatus("正在连接") : "正在解析备用源");
        httpResolveThread = new Thread(new Runnable() {
            @Override
            public void run() {
                final Thread worker = Thread.currentThread();
                String streamUrl = configuredUrl;
                boolean directHttpMedia = directCustomSource
                        && isDirectHttpMediaSource(streamUrl);
                boolean webPage = false;
                boolean htmlMimeOverride = false;
                if (HttpStreamResolver.shouldResolve(streamUrl)) {
                    try {
                        HttpStreamResolver.Result result = HttpStreamResolver.resolve(streamUrl);
                        streamUrl = result.url;
                        directHttpMedia = result.directMedia;
                        webPage = result.webPage;
                        htmlMimeOverride = result.htmlMimeOverride;
                    } catch (final IOException error) {
                        Log.w(TAG, "Unable to resolve dynamic source " + streamUrl, error);
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                if (httpResolveThread == worker && requestId == playRequestId) {
                                    httpResolveThread = null;
                                    if (error instanceof HttpStreamResolver.InvalidSourceUrlException) {
                                        showSourceUrlError((HttpStreamResolver.InvalidSourceUrlException) error);
                                    } else {
                                        handleUnavailableSource("线路解析失败", error.toString());
                                    }
                                }
                            }
                        });
                        return;
                    }
                }
                final String resolvedUrl = streamUrl;
                // Preserve MIME-based media detection for extensionless radio URLs.
                // startIjkPlayer routes HTTPS through the streaming TLS proxy.
                final boolean resolvedDirectHttpMedia = directHttpMedia;
                final boolean resolvedWebPage = webPage;
                final boolean resolvedHtmlMimeOverride = htmlMimeOverride;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (httpResolveThread != worker || requestId != playRequestId) {
                            return;
                        }
                        httpResolveThread = null;
                        if (resolvedWebPage) {
                            openWebSource(channel, "webview://" + resolvedUrl, requestId,
                                    "", resolvedHtmlMimeOverride);
                        } else {
                            startResolvedPlayer(channel, resolvedUrl, resolvedDirectHttpMedia);
                        }
                    }
                });
            }
        }, "live-url-resolve");
        httpResolveThread.start();
    }

    private void cancelHttpResolve() {
        Thread previous = httpResolveThread;
        httpResolveThread = null;
        if (previous != null) previous.interrupt();
    }

    private void cancelRemoteResolve() {
        Thread previous = remoteResolveThread;
        remoteResolveThread = null;
        if (previous != null) previous.interrupt();
    }

    private void resolveRemoteSource(final Channel channel, final String configuredUrl,
            final int requestId) {
        updateLoadingStatus("正在请求手机解析频道");
        showChannelBar(channel.name, customSourceStatus("正在连接手机"));
        cancelRemoteResolve();
        remoteResolveThread = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    if (Thread.currentThread().isInterrupted()) return;
                    final RemoteCatalogClient.Result result =
                            remoteCatalogClient.resolve(configuredUrl,
                                    getResources().getDisplayMetrics().widthPixels,
                                    getResources().getDisplayMetrics().heightPixels,
                                    lowResourceDevice,
                                    controlServer == null ? ""
                                            : controlServer.getLanUrlForPeer(remoteCatalogUrl));
                    if (Thread.currentThread().isInterrupted()) return;
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            if (requestId == playRequestId) {
                                startResolvedPlayer(channel, result.url,
                                        result.directDataSource, result.castTransport);
                                receiverStreamSessionId = result.castSessionId;
                            }
                        }
                    });
                } catch (final Exception error) {
                    if (Thread.currentThread().isInterrupted()) return;
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
        }, "remote-channel-resolve");
        remoteResolveThread.start();
    }

    private void resolveEmbeddedCctvUrl(final Channel playbackChannel,
            final Channel resolverChannel, final int requestId) {
        resolveCjsSite(playbackChannel,
                "https://tv.cctv.com/live/" + resolverChannel.streamId + "/", requestId);
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
                        if (result.webViewUrl.length() > 0) {
                            openWebSource(channel, "webview://" + result.webViewUrl,
                                    resolvedRequestId, result.pageScript);
                            return;
                        }
                        if (proxy != null) {
                            proxy.setWebRequestHeaders(result.referer, result.userAgent, result.cookies);
                        }
                        webStreamHeaders = buildWebStreamHeaders(result.referer,
                                result.userAgent, result.cookies);
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

    private void resolveCjsSite(final Channel channel, final String pageUrl,
            final int requestId) {
        updateLoadingStatus("正在执行在线站点插件");
        showChannelBar(channel.name, customSourceStatus("正在解析站点视频源"));
        cjsSiteResolver.resolve(requestId, channel.name, pageUrl, playbackResolutionMode(),
                activeCjsSource == null ? "" : activeCjsSource.url,
                new CjsSiteResolver.Callback() {
                    @Override
                    public void onResolved(int resolvedRequestId, CjsSiteResolver.Result result) {
                        if (resolvedRequestId != playRequestId) {
                            return;
                        }
                        if (proxy != null) {
                            proxy.setWebRequestHeaders(result.referer,
                                    "Mozilla/5.0 (Linux; Android TV) AppleWebKit/537.36", null);
                            if (result.transformer.length() > 0) {
                                proxy.configureCjsTransformer(result.transformer,
                                        result.transformerArgs, result.mediaHosts);
                            }
                        }
                        startResolvedPlayer(channel, result.url, result.directDataSource);
                    }

                    @Override
                    public void onFailed(int failedRequestId, String reason) {
                        if (failedRequestId != playRequestId) {
                            return;
                        }
                        Log.w(TAG, "CJS site resolve failed for " + pageUrl + ": " + reason);
                        handleUnavailableSource("站点插件解析失败", reason);
                    }
                });
    }

    private void startResolvedPlayer(Channel channel, String streamUrl,
            boolean directHttpMedia) {
        startResolvedPlayer(channel, streamUrl, directHttpMedia, RTSP_TRANSPORT_TCP);
    }

    private void startResolvedPlayer(Channel channel, String streamUrl,
            boolean directHttpMedia, String castTransport) {
        logChannelSwitchStage(playRequestId, "url-resolved");
        receiverCastTransport = sanitizeRtspTransport(castTransport);

        updateLoadingStatus("正在连接视频");
        try {
            startPlayer(channel, streamUrl, false, directHttpMedia);
        } catch (IOException | IllegalArgumentException | IllegalStateException error) {
            Log.e(TAG, "Unable to play " + channel.name, error);
            if (returnFromFailedSniffedPlayback("资源连接失败")) return;
            if (currentCatalogSource() == ChannelCatalog.SOURCE_CUSTOM) {
                handleUnavailableSource(currentSourceFailureReason("线路连接失败"), error.toString());
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

    private static String webViewPage(String configuredUrl) {
        Uri pageUri = parseWebViewPageUri(configuredUrl);
        if (pageUri == null || pageUri.getHost() == null) {
            return null;
        }
        return pageUri.toString();
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
        startPlayer(channel, streamUrl, forceSoftwareDecode,
                streamUrl != null && streamUrl.equals(directHttpMediaUrl));
    }

    private void startPlayer(final Channel channel, final String streamUrl,
            boolean forceSoftwareDecode, boolean directHttpMedia) throws IOException {
        directHttpMediaUrl = directHttpMedia ? streamUrl : null;
        if (legacyPlayerReleasePending) {
            queuePendingPlayer(channel, streamUrl, forceSoftwareDecode);
            updateLoadingStatus("等待系统解码器释放");
            return;
        }
        boolean systemHls = useAndroidHlsPlayer(Build.VERSION.SDK_INT, streamUrl);
        if (systemHls || androidMp3FallbackRequestId != playRequestId
                && useAndroidMp3Player(Build.VERSION.SDK_INT, streamUrl)) {
            startAndroidMediaPlayer(channel, streamUrl, forceSoftwareDecode, systemHls);
        } else {
            startIjkPlayer(channel, streamUrl, forceSoftwareDecode, directHttpMedia);
        }
    }

    private static boolean useAndroidHlsPlayer(int sdkInt, String streamUrl) {
        return sdkInt <= Build.VERSION_CODES.ICE_CREAM_SANDWICH_MR1
                && isHttpHlsSource(streamUrl);
    }

    private static boolean useAndroidMp3Player(int sdkInt, String streamUrl) {
        if (sdkInt > Build.VERSION_CODES.ICE_CREAM_SANDWICH_MR1
                || streamUrl == null) return false;
        String value = streamUrl.trim().toLowerCase(Locale.US);
        if (!value.startsWith("http://") && !value.startsWith("https://")) return false;
        String path = Uri.parse(value).getPath();
        return path != null && (path.endsWith(".mp3") || path.endsWith("-mp3")
                || value.contains("format=mp3") || value.contains("type=mp3"));
    }

    /** Android 4.0's platform player avoids IJK's costly MP3 software pipeline. */
    private void startAndroidMediaPlayer(final Channel channel, final String streamUrl,
            boolean forceSoftwareDecode, final boolean hls) throws IOException {
        if (!videoView.isSurfaceReady()) {
            queuePendingPlayer(channel, streamUrl, forceSoftwareDecode);
            updateLoadingStatus("等待视频输出界面");
            return;
        }
        clearPendingPlayer();
        releasePlayer(true);
        if (legacyPlayerReleasePending) {
            queuePendingPlayer(channel, streamUrl, forceSoftwareDecode);
            updateLoadingStatus("等待系统解码器释放");
            return;
        }
        if (proxy != null) {
            proxy.beginPlaybackAttempt();
            if (hls) proxy.enableSourceVideoProbe();
        }
        resetVideoLayout();
        if (!videoView.isSurfaceReady()) {
            queuePendingPlayer(channel, streamUrl, forceSoftwareDecode);
            return;
        }
        final AndroidMediaPlayer nextPlayer = new AndroidMediaPlayer();
        final int sourceRequestId = playRequestId;
        final boolean customSource = currentCatalogSource() == ChannelCatalog.SOURCE_CUSTOM;
        final boolean radioChannel = channel.radio;
        final Bitmap[] initialArtwork = new Bitmap[1];
        player = nextPlayer;
        if (channel.logoUrl.length() > 0) {
            albumArtLoader.load(this, null, null, channel.logoUrl, art -> {
                if (player == nextPlayer && sourceRequestId == playRequestId) {
                    initialArtwork[0] = art;
                    if (audioOnlyPlayback) audioArtwork.setCover(art);
                }
            });
        }
        activePlayerChannel = channel;
        activePlayerStreamUrl = streamUrl;
        activeSoftwareDecode = false;
        playbackSpeed = 1f;
        // The adapter only normalizes callbacks; decoding uses android.media.MediaPlayer.
        android.media.MediaPlayer systemPlayer = nextPlayer.getInternalMediaPlayer();
        systemPlayer.setAudioStreamType(AudioManager.STREAM_MUSIC);
        nextPlayer.setScreenOnWhilePlaying(true);
        SurfaceHolder videoSurfaceHolder = videoView.getVideoSurfaceHolder();
        if (videoSurfaceHolder != null) nextPlayer.setDisplay(videoSurfaceHolder);
        else nextPlayer.setSurface(videoView.getVideoSurface());
        nextPlayer.setOnVideoSizeChangedListener((mediaPlayer, width, height, sarNum, sarDen) -> {
            if (player == mediaPlayer) updateVideoLayout(mediaPlayer);
        });
        nextPlayer.setOnPreparedListener(mediaPlayer -> {
            if (player != mediaPlayer || sourceRequestId != playRequestId) return;
            prepared = true;
            audioOnlyPlayback = radioChannel || !hls;
            buffering = false;
            lastPlaybackProgressAt = SystemClock.elapsedRealtime();
            lastPlaybackPosition = -1L;
            playbackProgressObserved = false;
            updateVideoLayout(mediaPlayer);
            applyPlaybackMuteState();
            mediaPlayer.start();
            mediaTrackManifest = proxy == null ? null : proxy.mediaTracks(streamUrl);
            scheduleVideoInfoRefresh();
            if (audioOnlyPlayback) {
                audioArtwork.show(channelCardTitle(channel.name), mediaPlayer.getDuration() <= 0,
                        initialArtwork[0]);
                audioArtwork.setPlaying(true);
                playbackReadyRequestId = sourceRequestId;
                hideLoading();
                revealIncomingChannel(sourceRequestId);
                persistPlayingChannel(channel, sourceRequestId);
            } else {
                // Android 4.0 does not consistently send VIDEO_RENDERING_START for HLS.
                // Wait briefly for the first frame, then use its playback clock as fallback.
                channelBar.postDelayed(() -> {
                    if (player != nextPlayer || sourceRequestId != playRequestId
                            || videoRenderingStarted || !prepared) return;
                    if (nextPlayer.getVideoWidth() > 0 && nextPlayer.getVideoHeight() > 0
                            && nextPlayer.isPlaying())
                        markAndroidHlsVideoReady(nextPlayer, channel, sourceRequestId);
                }, 1500L);
                final long preparedAt = SystemClock.elapsedRealtime();
                channelBar.postDelayed(new Runnable() {
                    @Override public void run() {
                        if (player != nextPlayer || sourceRequestId != playRequestId
                                || !prepared || playbackProgressObserved) return;
                        long elapsed = SystemClock.elapsedRealtime() - preparedAt;
                        if (elapsed < LEGACY_HLS_MAX_STARTUP_MS
                                && (buffering || proxy != null
                                        && proxy.servedMediaSegmentRecently(12000L))) {
                            channelBar.postDelayed(this, 5000L);
                            return;
                        }
                        Log.w(TAG, "System HLS first frame timed out after " + elapsed
                                + "ms channel=" + channel.name);
                        recoverStalledPlayback(sourceRequestId, nextPlayer,
                                "system HLS startup stalled");
                    }
                }, LEGACY_HLS_STARTUP_GRACE_MS);
            }
            showChannelBar(channel.name, customSource
                    ? customSourceStatus("系统播放器播放中 · ") : "系统播放器播放中");
        });
        nextPlayer.setOnInfoListener((mediaPlayer, what, extra) -> {
            if (player != mediaPlayer) return false;
            if (what == IMediaPlayer.MEDIA_INFO_BUFFERING_START) {
                buffering = true;
            } else if (what == IMediaPlayer.MEDIA_INFO_BUFFERING_END) {
                buffering = false;
            } else if (hls && !audioOnlyPlayback
                    && what == IMediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) {
                markAndroidHlsVideoReady(nextPlayer, channel, sourceRequestId);
            }
            return true;
        });
        nextPlayer.setOnCompletionListener(mediaPlayer -> {
            channelBar.post(() -> {
                if (player == mediaPlayer && sourceRequestId == playRequestId)
                    recoverStalledPlayback(sourceRequestId, mediaPlayer,
                            "system " + (hls ? "HLS" : "MP3") + " player completed");
            });
        });
        nextPlayer.setOnErrorListener((mediaPlayer, what, extra) -> {
            if (player != mediaPlayer || sourceRequestId != playRequestId) return true;
            Log.w(TAG, "Android MediaPlayer " + (hls ? "HLS" : "MP3")
                    + " error=" + what + "/" + extra
                    + " channel=" + channel.name
                    + (hls && proxy != null ? " variant=" + proxy.selectedVariantDescription()
                            + " probed=" + proxy.sourceVideoWidth() + "x"
                            + proxy.sourceVideoHeight() + " audio=" + proxy.sourceAudioCodec()
                            : ""));
            channelBar.post(() -> {
                if (player == mediaPlayer && sourceRequestId == playRequestId) {
                    if (hls && proxy != null && proxy.wasPlaybackForbidden()) {
                        showPlaybackForbidden(channel);
                        return;
                    }
                    if (!hls) androidMp3FallbackRequestId = sourceRequestId;
                    if (hls && what == 1 && extra == -2147483648 && proxy != null)
                        LegacyBackgroundMemoryRelief.afterHighResolutionPlayerError(this,
                                proxy.sourceVideoWidth(), proxy.sourceVideoHeight());
                    recoverStalledPlayback(sourceRequestId, mediaPlayer,
                            "system " + (hls ? "HLS" : "MP3") + " error " + what + "/" + extra);
                }
            });
            return true;
        });
        try {
            // The local proxy handles legacy TLS, stream headers and HLS encryption.
            nextPlayer.setDataSource(proxy == null ? streamUrl : hls
                    ? proxy.systemPlayerHlsUrl(streamUrl) : proxy.systemPlayerMp3Url(streamUrl));
            nextPlayer.prepareAsync();
            Log.i(TAG, "Android MediaPlayer " + (hls ? "HLS" : "MP3")
                    + " sdk=" + Build.VERSION.SDK_INT
                    + " channel=" + channel.name);
        } catch (IOException | RuntimeException error) {
            if (player == nextPlayer) releasePlayer(true);
            if (!hls) {
                androidMp3FallbackRequestId = sourceRequestId;
                Log.w(TAG, "Android MP3 player unavailable; retrying with IJK", error);
                startIjkPlayer(channel, streamUrl, forceSoftwareDecode,
                        streamUrl.equals(directHttpMediaUrl));
                return;
            }
            if (error instanceof IOException) throw (IOException) error;
            throw new IOException("系统播放器无法打开" + (hls ? " HLS" : " MP3"), error);
        }
    }

    private void markAndroidHlsVideoReady(AndroidMediaPlayer expected, Channel channel,
            int requestId) {
        if (player != expected || requestId != playRequestId || videoRenderingStarted) return;
        videoRenderingStarted = true;
        lastVideoOutputAt = SystemClock.elapsedRealtime();
        playbackReadyRequestId = requestId;
        hideLoading();
        revealIncomingChannel(requestId);
        persistPlayingChannel(channel, requestId);
    }

    private void startIjkPlayer(final Channel channel, final String streamUrl,
            boolean forceSoftwareDecode, boolean directHttpMedia) throws IOException {
        startIjkPlayer(channel, streamUrl, forceSoftwareDecode, directHttpMedia, null);
    }

    private void startIjkPlayer(final Channel channel, final String streamUrl,
            boolean forceSoftwareDecode, boolean directHttpMedia, int[] initialTracks) throws IOException {
        startIjkPlayer(channel, streamUrl, forceSoftwareDecode, directHttpMedia, initialTracks, 0L);
    }

    private void startIjkPlayer(final Channel channel, final String streamUrl,
            boolean forceSoftwareDecode, boolean directHttpMedia, int[] initialTracks,
            long initialPositionMs) throws IOException {

        if (!videoView.isSurfaceReady()) {
            queuePendingPlayer(channel, streamUrl, forceSoftwareDecode);
            updateLoadingStatus("等待视频输出界面");
            Log.i(TAG, "Deferring player until Surface is ready channel=" + channel.name);
            return;
        }
        clearPendingPlayer();
        releasePlayer(true);
        if (legacyPlayerReleasePending) {
            queuePendingPlayer(channel, streamUrl, forceSoftwareDecode);
            updateLoadingStatus("等待系统解码器释放");
            return;
        }
        if (proxy != null) proxy.beginPlaybackAttempt();
        resetVideoLayout();
        final IjkMediaPlayer nextPlayer;
        try {
            IjkMediaPlayer.loadLibrariesOnce(null);
            nextPlayer = new IjkMediaPlayer();
        } catch (LinkageError error) {
            // Wrong ABI, damaged ELF or missing JNI symbols must use the same
            // error/return-to-web path as a failed source, including Surface callbacks.
            throw new IOException("播放器组件加载失败，请安装与设备架构匹配的完整安装包", error);
        }
        // Direct HTTP streams bypass our proxy. IJK's generic onError may lose
        // their HTTP status, so retain it from the native network callback.
        final java.util.concurrent.atomic.AtomicBoolean nativeForbidden =
                new java.util.concurrent.atomic.AtomicBoolean();
        nextPlayer.setOnNativeInvokeListener(new IjkMediaPlayer.OnNativeInvokeListener() {
            @Override public boolean onNativeInvoke(int what, Bundle args) {
                if ((what == EVENT_DID_HTTP_OPEN || what == EVENT_DID_HTTP_SEEK)
                        && args != null && args.getInt(ARG_HTTP_CODE) == 403) {
                    nativeForbidden.set(true);
                }
                return false;
            }
        });
        if (initialPositionMs > 0L) {
            // Seek before the demux loop starts filling MediaCodec queues. Seeking
            // after onPrepared/start can wedge old codecs while flushing frames.
            nextPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER,
                    "seek-at-start", initialPositionMs);
        }
        // Explicit M3U radio channels (including HLS) never need a video decoder.
        // MP3 embedded covers likewise belong to the artwork view.
        String mediaPath = Uri.parse(streamUrl).getPath();
        final boolean sniffedPresentation = playingDiscoveredWebStream && playingSniffedResource != null;
        final String artworkTitle = channelCardTitle(channel.name);
        final String artworkLogo = sniffedPresentation ? "" : channel.logoUrl;
        final boolean radioChannel = !sniffedPresentation && channel.radio;
        if (radioChannel || mediaPath != null && mediaPath.toLowerCase(Locale.US).endsWith(".mp3"))
            nextPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "vn", 1);
        if (initialTracks != null) {
            nextPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "ntv-initial-audio", initialTracks[0]);
            nextPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "ntv-initial-video", initialTracks[1]);
            nextPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "ntv-initial-subtitle", initialTracks[2]);
        }
        player = nextPlayer;
        final boolean customSource = currentCatalogSource() == ChannelCatalog.SOURCE_CUSTOM;
        final int sourceRequestId = playRequestId;
        // Fetch station artwork alongside player preparation, not after its network
        // buffer is ready. Keep it private until audio-only playback is confirmed.
        final Bitmap[] initialArtwork = new Bitmap[1];
        if (artworkLogo.length() > 0) {
            albumArtLoader.load(this, null, null, artworkLogo, art -> {
                if (player == nextPlayer && sourceRequestId == playRequestId) {
                    initialArtwork[0] = art;
                    if (audioOnlyPlayback || audioArtwork.hasPendingPresentation()) audioArtwork.setCover(art);
                }
            });
        }
        final boolean softwareDecode = forceSoftwareDecode || shouldUseSoftwareDecode();
        if (Build.VERSION.SDK_INT >= 17) NativeHdrOutput.initialize(this, videoView);
        // Keep software YUV on the GPU too; mixing CPU and EGL producers on one
        // Surface prevents old Android versions from reconnecting the output.
        nextPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER,"overlay-format","fcc-_es2");
        nextPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER,"ntv-hdr-mode",
                HdrMode.nativeValue(hdrMode));
        Log.i(TAG,"HDR mode=" + hdrMode + " softwareDecode=" + softwareDecode);
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
        // Old devices can fall back to software even in automatic hardware mode.
        // Use the same late-frame budget as explicit software playback so costly
        // video conversion does not keep falling further behind the audio clock.
        nextPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "framedrop",
                realtimeCastSource ? 0 : (softwareDecode
                        || Build.VERSION.SDK_INT <= Build.VERSION_CODES.KITKAT) ? 5 : 1);
        if (realtimeCastSource) receiverNetworkLease.acquire(this);
        nextPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "ntv-live-video",
                realtimeCastSource ? 1 : 0);
        nextPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "ntv-frame-sync",
                !softwareDecode && !realtimeCastSource ? 1 : 0);
        if (!softwareDecode && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            nextPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "max-fps", -1);
        }
        nextPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "ntv-trace-latency",
                realtimeCastSource && BuildConfig.DEBUG && BuildConfig.CAST_LATENCY_TRACE ? 1 : 0);
        final boolean remoteCatalogPlayback = isRemoteCatalogPlayback();
        nextPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "packet-buffering",
                realtimeCastSource ? 0 : 1);
        final boolean cctvSource = isActiveCctvWebSource();
        final boolean yangshipinSource = activeEmbeddedYangshipinResolver
                || proxyStatefulCmgSource && channel.yangshipinPid != null;
        // Probe known CCTV/Yangshipin tracks quickly on KitKat. Startup and refill
        // buffering still follow the selected live-delay mode on every SDK.
        final boolean legacyCctvStartup = (cctvSource || yangshipinSource)
                && Build.VERSION.SDK_INT <= Build.VERSION_CODES.KITKAT;
        final boolean genericLiveHls = !cctvSource && isHttpHlsSource(streamUrl);
        // Native demux verifies ENDLIST/duration before changing buffering;
        // live HLS and realtime casting retain their existing latency profile.
        final int vodBufferMode = genericLiveHls && !realtimeCastSource && !remoteCatalogPlayback
                ? LIVE_DELAY_STABLE.equals(liveDelayMode) ? 2
                : LIVE_DELAY_BALANCED.equals(liveDelayMode) ? 1 : 0 : 0;
        nextPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "ntv-vod-buffer-mode", vodBufferMode);
        final boolean bufferedHttpMedia = customSource && !cctvSource && !yangshipinSource
                && !realtimeCastSource && !remoteCatalogPlayback
                && (streamUrl.startsWith("http://") || streamUrl.startsWith("https://"));
        nextPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "min-frames",
                realtimeCastSource ? 2
                        : remoteCatalogPlayback ? 20
                        : cctvSource || genericLiveHls ? liveIjkMinFrames()
                        : (bufferedHttpMedia ? 360 : 60));
        if (bufferedHttpMedia || cctvSource || genericLiveHls) {
            int bufferMiB = lowResourceDevice
                    ? (cctvSource || genericLiveHls ? 15 : 8)
                    : LIVE_DELAY_STABLE.equals(liveDelayMode) && (cctvSource || genericLiveHls) ? 64 : 48;
            // Other ABI packages may still carry the upstream native limit.
            if (!IjkStreamMetrics.extendedMetricsAvailable(nextPlayer)) bufferMiB = Math.min(bufferMiB, 15);
            nextPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER,
                    "max-buffer-size", bufferMiB * 1024 * 1024);
        }
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
                        : (genericLiveHls ? 30000 : 45000));
        nextPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "first-high-water-mark-ms",
                realtimeCastSource ? 100
                        : remoteCatalogPlayback ? 800
                        : cctvSource || genericLiveHls ? liveIjkFirstBufferMs()
                        : 5000);
        nextPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "next-high-water-mark-ms",
                realtimeCastSource ? 80 : remoteCatalogPlayback ? 3000
                        : cctvSource || genericLiveHls ? liveIjkNextBufferMs() : 5000);
        nextPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "last-high-water-mark-ms",
                realtimeCastSource ? 150 : remoteCatalogPlayback ? 5000
                        : cctvSource || genericLiveHls ? liveIjkLastBufferMs() : 5000);
        nextPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "reconnect", 1);
        if (isRtspSource(streamUrl)) {
            // Casting follows the sender's explicit choice for both codecs and
            // every receiver version. Missing protocol fields default to TCP.
            String effectiveRtspTransport = realtimeCastSource
                    ? receiverCastTransport : rtspTransport;
            if (realtimeCastSource) Log.i(TAG, "Cast RTSP transport=" + effectiveRtspTransport);
            nextPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT,
                    "rtsp_transport", effectiveRtspTransport);
        }
        /* Every channel switch creates a localhost proxy on a new port. IJK 0.8.8
         * can retain an empty localhost DNS-cache entry from the closed proxy,
         * making the first connection to the new port fail spuriously. */
        nextPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "dns_cache_clear", 1);
        nextPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "http-detect-range-support", 0);
        boolean genericThirdPartySource = customSource && !cctvSource && !yangshipinSource
                && !realtimeCastSource && !remoteCatalogPlayback;
        nextPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "probesize",
                realtimeCastSource ? 128 * 1024
                        : remoteCatalogPlayback ? 1024 * 1024
                        : genericThirdPartySource ? 4 * 1024 * 1024 : 256 * 1024);
        if (realtimeCastSource) {
            // Keep enough socket space for one paced IDR on old kernels. The sender
            // now spreads large access units over a few milliseconds, preventing
            // this safety window from receiving a single destructive packet burst.
            nextPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT,
                    "buffer_size", 512 * 1024);
            nextPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT,
                    "analyzeduration", 100000);
            nextPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT,
                    "fflags", "nobuffer");
            nextPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT,
                    "max_delay", 30000);
            nextPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT,
                    "reorder_queue_size", 16);
        }
        if (legacyCctvStartup) {
            // Known H.264/AAC live TS streams expose both tracks in the first segment.
            // Don't wait for a multi-second generic stream probe before showing video.
            nextPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "analyzeduration", 1000000);
        } else if (genericThirdPartySource) {
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

        if (!videoView.isSurfaceReady()) {
            queuePendingPlayer(channel, streamUrl, forceSoftwareDecode);
            nextPlayer.release();
            player = null;
            return;
        }
        SurfaceHolder videoSurfaceHolder = videoView.getVideoSurfaceHolder();
        if (videoSurfaceHolder != null) nextPlayer.setDisplay(videoSurfaceHolder);
        else nextPlayer.setSurface(videoView.getVideoSurface());
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
                logChannelSwitchStage(sourceRequestId, "player-prepared");
                prepared = true;
                audioOnlyPlayback = radioChannel || isAudioOnly(nextPlayer);
                String artworkSource = channel.sourceUrl(currentSourceIndex);
                if (!sniffedPresentation && artworkSource != null) audioChannelTypes.put(artworkSource, audioOnlyPlayback);
                if (!audioOnlyPlayback && audioArtwork.hasPendingPresentation()) audioArtwork.clear();
                lastPlaybackProgressAt = SystemClock.elapsedRealtime();
                lastPlaybackPosition = -1L;
                playbackProgressObserved = false;
                updateVideoLayout(mediaPlayer);
                nextPlayer.setSpeed(playbackSpeed);
                mediaPlayer.start();
                if (audioOnlyPlayback) {
                    audioArtwork.show(artworkTitle, mediaPlayer.getDuration() <= 0, initialArtwork[0]);
                    audioArtwork.setPlaying(true);
                    if (!sniffedPresentation) prefetchAdjacentArtwork();
                    playbackReadyRequestId = sourceRequestId;
                    hideLoading();
                    revealIncomingChannel(sourceRequestId);
                    persistPlayingChannel(channel, sourceRequestId);
                    String artworkUrl = streamUrl;
                    albumArtLoader.load(MainActivity.this, artworkUrl, webStreamHeaders, artworkLogo, art -> {
                        if (player == nextPlayer && sourceRequestId == playRequestId && audioOnlyPlayback)
                            audioArtwork.setCover(art);
                    });
                    Log.i(TAG, "Audio channel ready: " + artworkTitle);
                }

                mediaTrackManifest = proxy == null ? null : proxy.mediaTracks(streamUrl);
                if (channel.subtitleUrls.length > 0) {
                    HlsMediaTracks.Manifest merged = new HlsMediaTracks.Manifest(streamUrl);
                    if (mediaTrackManifest != null) {
                        merged.videos.addAll(mediaTrackManifest.videos);
                        merged.subtitles.addAll(mediaTrackManifest.subtitles);
                        merged.closedCaptions.addAll(mediaTrackManifest.closedCaptions);
                        merged.selectedVideoUrl = mediaTrackManifest.selectedVideoUrl;
                    }
                    for (int i = 0; i < channel.subtitleUrls.length; i++) {
                        String url = channel.subtitleUrls[i];
                        boolean duplicate = false;
                        for (HlsMediaTracks.Track track : merged.subtitles) if (url.equals(track.url)) duplicate = true;
                        if (!duplicate) {
                            String name = Uri.parse(url).getLastPathSegment();
                            merged.subtitles.add(new HlsMediaTracks.Track(url,
                                    "外挂字幕 " + (i + 1) + (name == null ? "" : " · " + name),
                                    "", "SRT / WebVTT", "external", false));
                        }
                    }
                    mediaTrackManifest = merged;
                }
                restoreRememberedTracks(nextPlayer, channel);
                warnUnsupportedTenBitVideo(nextPlayer, sourceRequestId);
                if (trackResumePlayer == nextPlayer) {
                    // Reopened VOD players receive their resume clock through
                    // seek-at-start before the demux loop begins.  Keep this
                    // post-prepare path only for restoring the paused state;
                    // seeking an Apple byte-range fMP4 here makes old IJK reopen
                    // the same large media object indefinitely.
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
                showPlaybackProgress(mediaPlayer.getCurrentPosition());
                if (!audioOnlyPlayback) scheduleVideoRenderWatchdog(channel, streamUrl, nextPlayer,
                        sourceRequestId, softwareDecode);
                if (!isWaitingForIncomingFrame(sourceRequestId)) {
                    hideLoading();
                }
                String playingStatus = audioOnlyPlayback
                        ? (mediaPlayer.getDuration() > 0 ? "音乐播放中" : "电台直播中")
                        : (mediaPlayer.getDuration() > 0 ? "视频播放中"
                                : (softwareDecode ? "直播播放中 · 兼容软解" : "直播播放中"));
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
                if (what == MEDIA_INFO_VIDEO_RENDERING_START && !audioOnlyPlayback) {
                    logChannelSwitchStage(sourceRequestId, "first-frame");
                    videoRenderingStarted = true;
                    lastVideoOutputAt = SystemClock.elapsedRealtime();
                    playbackReadyRequestId = sourceRequestId;
                    hideLoading();
                    revealIncomingChannel(sourceRequestId);
                    persistPlayingChannel(channel, sourceRequestId);
                    Log.i(TAG, "First video frame rendered decoder="
                            + (softwareDecode ? "software" : "hardware")
                            + " channel=" + channel.name);
                    String component = pendingCjsComponentCheck;
                    pendingCjsComponentCheck = "";
                    if (component.length() > 0) scheduleCjsComponentCheck(component);
                } else if (what == IMediaPlayer.MEDIA_INFO_BUFFERING_START) {
                    buffering = true;
                    if (audioOnlyPlayback) audioArtwork.setPlaying(false);
                    bufferingStartedAt = SystemClock.elapsedRealtime();
                    final int eventId = ++bufferingEventId;
                    final int requestId = playRequestId;
                    final IjkMediaPlayer watchedPlayer = nextPlayer;
                    // A finite film can resume its current range. Allow the bounded
                    // proxy retries to finish before tearing down decoder/seek state.
                    // Adaptive finite-HLS refill may intentionally wait for up to
                    // 60 seconds of media; do not restart it at the old 30s deadline.
                    final long bufferingRecoveryMs = watchedPlayer.getDuration() > 0L
                            ? (vodBufferMode > 0 ? 150000L : 30000L) : PLAYBACK_BUFFERING_RECOVERY_MS;
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
                                                + bufferingRecoveryMs + "ms");
                            }
                        }
                    }, bufferingRecoveryMs);
                } else if (what == IMediaPlayer.MEDIA_INFO_BUFFERING_END) {
                    long elapsed = buffering
                            ? SystemClock.elapsedRealtime() - bufferingStartedAt : 0L;
                    buffering = false;
                    if (audioOnlyPlayback) audioArtwork.setPlaying(mediaPlayer.isPlaying());
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
        nextPlayer.setOnCompletionListener(new IMediaPlayer.OnCompletionListener() {
            @Override public void onCompletion(final IMediaPlayer endedPlayer) {
                // Live socket closure can be reported as EOF, not onError.
                if (sourceRequestId == playRequestId && player == endedPlayer) {
                    // Old FFmpeg HLS can drain cached frames and report completion
                    // after a playlist/segment returns 403, without onError.
                    if (nativeForbidden.get() || proxy != null && proxy.wasPlaybackForbidden()) {
                        showPlaybackForbidden(channel);
                        return;
                    }
                    if (!prepared && returnFromFailedSniffedPlayback("资源未能开始播放")) return;
                    if (audioArtwork != null) audioArtwork.setPlaying(false);
                    if (realtimeCastSource)
                        recoverStalledPlayback(sourceRequestId, endedPlayer, "cast connection reached EOF");
                }
            }
        });
        nextPlayer.setOnErrorListener(new IMediaPlayer.OnErrorListener() {
            @Override
            public boolean onError(IMediaPlayer mediaPlayer, int what, int extra) {
                if (sourceRequestId == playRequestId && player == mediaPlayer) {
                    albumArtLoader.clear();
                    if (audioArtwork != null) audioArtwork.clear();

                    if (nativeForbidden.get() || PlaybackHttpError.isForbidden(what, extra)
                            || proxy != null && proxy.wasPlaybackForbidden()) {
                        showPlaybackForbidden(channel);
                        return true;
                    }

                    if (what == -20001 || extra == -20001) {
                        if (returnFromFailedSniffedPlayback("设备不支持此 Dolby Vision 格式")) return true;
                        abortChannelSwitchAnimation();
                        hideLoading();
                        showChannelBar(channel.name, "设备不支持此 Dolby Vision 格式，请切换普通视频轨道或线路");
                        return true;
                    }
                    if (returnFromFailedSniffedPlayback("资源播放失败（" + what + "/" + extra + "）")) return true;
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
                || (directHttpMedia && streamUrl.startsWith("http://"));
        if (directHttpMedia) {
            // Some radio servers reject FFmpeg 3.4's legacy default user agent.
            // Explicit resource headers below still take precedence.
            nextPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "user_agent",
                    "nTv/" + BuildConfig.VERSION_NAME);
        }
        if (directHttpMedia) {
            Log.i(TAG, "Opening resolved media " + (directDataSource ? "directly: " : "through TLS proxy: ") + streamUrl);
        }
        if (directDataSource && (streamUrl.startsWith("http://") || streamUrl.startsWith("https://"))
                && !TextUtils.isEmpty(webStreamHeaders)) {
            nextPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "headers", webStreamHeaders);
        }
        nextPlayer.setDataSource(directDataSource ? streamUrl
                : directHttpMedia ? proxy.mediaUrl(streamUrl) : proxy.proxyUrl(streamUrl));
        logChannelSwitchStage(sourceRequestId, "player-open-requested");
        prepareIjkPlayerAfterRelease(nextPlayer, sourceRequestId);
    }

    private void prepareIjkPlayerAfterRelease(final IjkMediaPlayer nextPlayer,
            final int requestId) {
        final java.util.concurrent.CountDownLatch pending = playerReleaseComplete;
        final long deadline = SystemClock.elapsedRealtime() + 2000L;
        // Detaching a Surface does not disconnect the old codec/EGL producer.
        // Wait asynchronously before configuring its replacement; never block the UI.
        new Runnable() {
            @Override public void run() {
                if (player != nextPlayer || playRequestId != requestId || isFinishing()) return;
                if (pending.getCount() != 0 && SystemClock.elapsedRealtime() < deadline) {
                    channelBar.postDelayed(this, 16L);
                    return;
                }
                if (pending.getCount() != 0) Log.w(TAG,"Player release wait timed out request=" + requestId);
                try { nextPlayer.prepareAsync(); }
                catch (RuntimeException error) {
                    Log.w(TAG,"Unable to prepare replacement player",error);
                    recoverStalledPlayback(requestId,nextPlayer,"player prepare failed");
                }
            }
        }.run();
    }

    private boolean isDirectThirdPartyRecordingSource(String streamUrl) {
        return currentCatalogSource() == ChannelCatalog.SOURCE_CUSTOM
                && !activeEmbeddedCctvResolver && !activeEmbeddedYangshipinResolver
                && !activeEmbeddedCjsResolver
                && webStreamHeaders == null && isHttpHlsSource(streamUrl)
                && !HlsProxyServer.needsSpecialDecrypt(streamUrl);
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

    /** Non-HLS media; HTTPS uses the Java streaming/TLS proxy on the playing device. */
    private static boolean isDirectHttpMediaSource(String sourceUrl) {
        if (sourceUrl == null) {
            return false;
        }
        String value = sourceUrl.trim().toLowerCase(Locale.US);
        if (!value.startsWith("http://") && !value.startsWith("https://")) {
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

    private void warnUnsupportedTenBitVideo(IjkMediaPlayer mediaPlayer, int requestId) {
        if (player != mediaPlayer || requestId != playRequestId
                || tenBitWarningRequestId == requestId) return;
        try {
            IjkMediaMeta meta = IjkMediaMeta.parse(mediaPlayer.getMediaMeta());
            IjkMediaMeta.IjkStreamMeta video = meta == null ? null : meta.mVideoStream;
            if (video == null || !TenBitVideoSupport.isTenBit(
                    video.getString(IjkMediaMeta.IJKM_KEY_CODEC_PIXEL_FORMAT), video.mCodecProfile)) return;
            if (mediaPlayer.getVideoDecoder() == IjkMediaPlayer.FFP_PROPV_DECODER_MEDIACODEC) return;
            Boolean supported = TenBitVideoSupport.hasHardwareDecoder(
                    TenBitVideoSupport.mime(video.mCodecName), video.mCodecProfile);
            if (!Boolean.FALSE.equals(supported)) return;
            tenBitWarningRequestId = requestId;
            String message = "当前设备不支持 " + readableCodec(video.mCodecName, null)
                    + " 10bit 硬解，软解播放可能卡顿";
            Toast.makeText(this, message, Toast.LENGTH_LONG).show();
            Log.i(TAG, "Ten-bit hardware unavailable: " + video.mCodecName
                    + " profile=" + video.mCodecProfile + " request=" + requestId);
        } catch (RuntimeException error) {
            Log.w(TAG, "Unable to inspect 10-bit playback support", error);
        }
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
        if (UI_SCALE_SMALL.equals(mode) || UI_SCALE_SLIGHTLY_SMALL.equals(mode)
                || UI_SCALE_STANDARD.equals(mode)
                || UI_SCALE_LARGE.equals(mode)
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

    private static String legacyWebViewUserScripts(String source) throws JSONException {
        JSONArray scripts = new JSONArray();
        String safeSource = source == null ? "" : source;
        if (safeSource.trim().length() > 0) {
            scripts.put(new JSONObject()
                    .put("id", "script-1")
                    .put("name", webViewUserScriptName(safeSource, "脚本 1"))
                    .put("enabled", true)
                    .put("source", safeSource));
        }
        return normalizeWebViewUserScripts(scripts);
    }

    private static String normalizeWebViewUserScripts(JSONArray requested) throws JSONException {
        if (requested.length() > MAX_WEB_VIEW_USER_SCRIPTS) {
            throw new JSONException("最多可配置 " + MAX_WEB_VIEW_USER_SCRIPTS + " 个脚本");
        }
        JSONArray normalized = new JSONArray();
        Set<String> ids = new LinkedHashSet<String>();
        int totalLength = 0;
        for (int index = 0; index < requested.length(); index++) {
            JSONObject item = requested.optJSONObject(index);
            if (item == null) throw new JSONException("第 " + (index + 1) + " 个脚本格式无效");
            String source = item.optString("source", "");
            if (source.length() > MAX_WEB_VIEW_USER_SCRIPT_LENGTH) {
                throw new JSONException("单个脚本不能超过 256KB");
            }
            totalLength += source.length();
            if (totalLength > MAX_WEB_VIEW_USER_SCRIPTS_TOTAL_LENGTH) {
                throw new JSONException("全部脚本总计不能超过 512KB");
            }
            String id = item.optString("id", "").trim();
            if (!id.matches("[A-Za-z0-9._-]{1,64}")) id = "script-" + (index + 1);
            String baseId = id;
            int suffix = 2;
            while (ids.contains(id)) id = baseId + "-" + suffix++;
            ids.add(id);
            String name = item.optString("name", "").trim();
            if (name.length() == 0) {
                name = webViewUserScriptName(source, "脚本 " + (index + 1));
            }
            if (name.length() > 80) throw new JSONException("脚本名称不能超过 80 个字符");
            String installUrl = item.optString("installUrl", "").trim();
            if (installUrl.length() > 4096 || installUrl.length() > 0
                    && !installUrl.toLowerCase(Locale.US).startsWith("https://")) {
                throw new JSONException("脚本安装地址无效");
            }
            String version = item.optString("version", "").trim();
            if (version.length() > 80) version = version.substring(0, 80);
            normalized.put(new JSONObject()
                    .put("id", id)
                    .put("name", name)
                    .put("enabled", item.optBoolean("enabled", true))
                    .put("source", source)
                    .put("installUrl", installUrl)
                    .put("version", version));
        }
        return normalized.toString();
    }

    private static String webViewUserScriptName(String source, String fallback) {
        boolean metadata = false;
        for (String raw : source.replace("\r", "").split("\n", -1)) {
            String line = raw.trim();
            if ("// ==UserScript==".equals(line)) { metadata = true; continue; }
            if ("// ==/UserScript==".equals(line)) break;
            if (!metadata || !line.startsWith("// @name")) continue;
            String name = line.substring("// @name".length()).trim();
            if (name.length() > 0) return name.length() > 80 ? name.substring(0, 80) : name;
        }
        return fallback;
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

    private static String sanitizeWebViewBrowserVersion(String version) {
        if ("118".equals(version) || "128".equals(version) || "138".equals(version)) {
            return version;
        }
        return WEB_VIEW_BROWSER_VERSION_NATIVE;
    }

    private static float sanitizeWebViewPageScale(float scale) {
        return Math.max(0.5f, Math.min(3f, scale));
    }

    private static String sanitizeDateTimeFormat(String format) {
        if (DATE_TIME_TIME_FIRST.equals(format) || DATE_TIME_WEEK_FIRST.equals(format)
                || DATE_TIME_TIME_WEEK.equals(format) || DATE_TIME_ONLY.equals(format)) {
            return format;
        }
        return DATE_TIME_DATE_FIRST;
    }

    private String formatDateTime(Date date) {
        String pattern;
        if (DATE_TIME_ONLY.equals(dateTimeFormat)) {
            pattern = "HH:mm:ss";
        } else if (DATE_TIME_TIME_WEEK.equals(dateTimeFormat)) {
            pattern = "HH:mm:ss EEEE";
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
        if (!audio && index >= HlsMediaTracks.CLOSED_CAPTION_BASE
                && index < HlsMediaTracks.VIDEO_BASE) {
            selectClosedCaption(mediaPlayer, index);
            HlsMediaTracks.Track track = mediaTrackManifest.closedCaptions.get(
                    index-HlsMediaTracks.CLOSED_CAPTION_BASE);
            rememberMediaTrack(false, MediaTrackSelection.subtitleChoice(track.language, track.name));
            return;
        }
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
        sampledMetadataPlayer = null;
        cachedIjkMetadata = null;
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
            if (player instanceof IjkMediaPlayer && prepared) {
                deselectTrackType((IjkMediaPlayer) player, ITrackInfo.MEDIA_TRACK_TYPE_SUBTITLE);
                deselectTrackType((IjkMediaPlayer) player, ITrackInfo.MEDIA_TRACK_TYPE_TIMEDTEXT);
            }
        } else if (player instanceof IjkMediaPlayer && prepared) {
            IjkMediaPlayer ijkPlayer = (IjkMediaPlayer) player;
            int previous = ijkPlayer.getSelectedTrack(ITrackInfo.MEDIA_TRACK_TYPE_TIMEDTEXT);
            long position = player.getDuration() > 0 ? player.getCurrentPosition() : -1;
            boolean playing = player.isPlaying();
            restoreRememberedSubtitles(ijkPlayer, activePlayerChannel);
            int selected = ijkPlayer.getSelectedTrack(ITrackInfo.MEDIA_TRACK_TYPE_TIMEDTEXT);
            if (selected >= 0 && selected != previous) {
                restoreTrackProgress(ijkPlayer, position, playing);
                scheduleMediaTrackRecovery(ijkPlayer, playing);
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
            boolean byteRangePlaylist=proxy.usesByteRangeMediaPlaylist();
            long initialPosition=byteRangePlaylist?0L:resume;
            boolean playing=activePlayer.isPlaying();
            Channel channel=activePlayerChannel;
            String source=activePlayerStreamUrl;
            boolean software=activeSoftwareDecode;
            boolean direct=source != null && source.equals(directHttpMediaUrl);
            proxy.selectVideoVariant(track.url);
            showLoading(channel.name,"正在切换视轨");
            // Old IJK/FFmpeg corrupts its fMP4 read position when a rendition
            // change seeks into EXT-X-BYTERANGE media (the Apple HEVC sample is
            // one such stream). Start those rare playlists cleanly instead of
            // leaving playback permanently black. Ordinary segmented HLS keeps
            // its position through seek-at-start.
            startIjkPlayer(channel,source,software,direct,null,initialPosition);
            trackResumePlayer=player instanceof IjkMediaPlayer ? (IjkMediaPlayer) player : null;
            trackResumePosition=0L;trackResumePlaying=playing;
            if(byteRangePlaylist&&resume>0L) Toast.makeText(this,
                    "此视频切换清晰度后将从头播放",Toast.LENGTH_SHORT).show();
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
                        startIjkPlayer(channel, url, software, direct, tracks, resume);
                        trackResumePlayer = player instanceof IjkMediaPlayer ? (IjkMediaPlayer) player : null;
                        trackResumePosition = 0L;
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
            @Override public void error() {
                if (player == mediaPlayer) Toast.makeText(MainActivity.this,
                        "外挂字幕加载失败，请检查字幕地址或格式；视频继续播放", Toast.LENGTH_LONG).show();
            }
            @Override public void text(String text){
                if(player!=mediaPlayer||subtitleText==null)return;
                subtitleText.setText(text);subtitleText.setVisibility(text.isEmpty()?View.GONE:View.VISIBLE);
            }
        });
    }

    private void selectClosedCaption(IjkMediaPlayer mediaPlayer, int index) throws IOException {
        int position = index-HlsMediaTracks.CLOSED_CAPTION_BASE;
        if (mediaTrackManifest == null || position < 0
                || position >= mediaTrackManifest.closedCaptions.size()) {
            throw new IOException("所选内嵌字幕不存在");
        }
        if (selectedClosedCaption == index) return;
        HlsMediaTracks.Track wanted = mediaTrackManifest.closedCaptions.get(position);
        stopHlsSubtitle();
        selectedClosedCaption = index;
        // Some FFmpeg builds expose CEA captions as a native timed-text track.
        // Select it when available. Otherwise the in-stream timed-text callback
        // remains enabled and can receive captions emitted by the demuxer.
        ITrackInfo[] tracks = mediaPlayer.getTrackInfo();
        if (tracks != null) {
            for (int i=0;i<tracks.length;i++) {
                ITrackInfo track = tracks[i];
                if (track == null) continue;
                int type = track.getTrackType();
                if (type != ITrackInfo.MEDIA_TRACK_TYPE_SUBTITLE
                        && type != ITrackInfo.MEDIA_TRACK_TYPE_TIMEDTEXT) continue;
                String language = normalizeTrackLanguage(track.getLanguage());
                if (wanted.language.length() == 0 || wanted.language.equalsIgnoreCase(language)) {
                    mediaPlayer.selectTrack(i);
                    break;
                }
            }
        }
        clearSubtitleText();
    }

    private void stopHlsSubtitle() {
        if(hlsSubtitlePlayer!=null){hlsSubtitlePlayer.close();hlsSubtitlePlayer=null;}
        selectedHlsSubtitle=-1;
        selectedClosedCaption=-1;
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
        return genericStartupPrefetchSegments();
    }

    private int cctvStartupDecryptSegments() {
        return genericStartupPrefetchSegments();
    }

    private int genericStartupPrefetchSegments() {
        if (LIVE_DELAY_LOW.equals(liveDelayMode)) {
            return 0;
        }
        return LIVE_DELAY_BALANCED.equals(liveDelayMode) ? 1 : 2;
    }

    private int liveIjkMinFrames() {
        // This stops background prefetch, not the first-frame startup gate.
        // 40 packets only cover 800 ms at 50 fps and starve segment-based HLS.
        if (LIVE_DELAY_LOW.equals(liveDelayMode)) return 360;
        return LIVE_DELAY_BALANCED.equals(liveDelayMode) ? 480 : 720;
    }

    private int liveIjkFirstBufferMs() {
        if (LIVE_DELAY_LOW.equals(liveDelayMode)) {
            return 1000;
        }
        if (LIVE_DELAY_BALANCED.equals(liveDelayMode)) {
            return 3000;
        }
        // IJK caps first-high-water-mark-ms at 5000; larger values are rejected.
        return 5000;
    }

    private int liveIjkNextBufferMs() {
        if (LIVE_DELAY_LOW.equals(liveDelayMode)) {
            return 3000;
        }
        return 5000;
    }

    private int liveIjkLastBufferMs() {
        // IJK's last-high-water-mark-ms option is capped at 5000 ms.
        return 5000;
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
        return UiScalePolicy.resolve(viewportWidth, viewportHeight, uiScaleMode,
                detectedDisplayInches, metrics.density);
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
                    ? Math.round(textSizePx * 3.8f) : 0);
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
        if (returnFromFailedSniffedPlayback("资源播放中断，请在网页中重试")) return;
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
        boolean legacyHlsFailure = watchedPlayer instanceof AndroidMediaPlayer
                && useAndroidHlsPlayer(Build.VERSION.SDK_INT, activePlayerStreamUrl);
        if (legacyHlsFailure && reason.startsWith("system HLS error ")) {
            // A generic framework error can mean ION/OMX exhaustion. Creating
            // another decoder before the old one exits compounds that failure.
            Log.w(TAG, "Stopping failed legacy HLS player without automatic retry"
                    + " channel=" + currentChannel().name + " reason=" + reason);
            abortChannelSwitchAnimation();
            releasePlayer(true);
            hideLoading();
            showChannelBar(currentChannel().name,
                    "系统播放器出错，请稍后重试或切换线路");
            return;
        }
        int recoveryLimit = legacyHlsFailure ? 2 : PLAYBACK_RECOVERY_MAX_ATTEMPTS;
        if (playbackRecoveryAttempts < recoveryLimit) {
            playbackRecoveryAttempts++;
            lastPlaybackRecoveryAt = SystemClock.elapsedRealtime();
            Channel retryChannel = activePlayerChannel;
            String retryUrl = activePlayerStreamUrl;
            boolean retrySoftwareDecode = activeSoftwareDecode;
            Log.w(TAG, "Recovering stalled playback attempt="
                    + playbackRecoveryAttempts + "/" + recoveryLimit
                    + " reason=" + reason);
            showLoading(currentChannel().name,
                    (isDeviceNetworkDisconnected() ? "网络已断开，正在重连（" : "播放异常，正在重试（")
                    + playbackRecoveryAttempts + "/"
                    + recoveryLimit + "）");
            if (retryChannel != null && retryUrl != null && retryUrl.length() > 0) {
                if (watchedPlayer instanceof AndroidMediaPlayer) {
                    // A failed framework player may still own the old Surface for a
                    // moment. Immediate retries can exhaust all five attempts and
                    // the device's video buffers before it disconnects.
                    final Channel delayedChannel = retryChannel;
                    final String delayedUrl = retryUrl;
                    final boolean delayedSoftwareDecode = retrySoftwareDecode;
                    long delayMs = Math.min(1500L, 300L * playbackRecoveryAttempts);
                    channelBar.postDelayed(() -> {
                        if (requestId != playRequestId || player != watchedPlayer) return;
                        try {
                            restartPlayerPreservingPosition(delayedChannel, delayedUrl,
                                    delayedSoftwareDecode);
                        } catch (IOException error) {
                            Log.w(TAG, "Unable to restart stalled system player", error);
                            startChannel(currentChannelIndex);
                        }
                    }, delayMs);
                    return;
                }
                try {
                    restartPlayerPreservingPosition(retryChannel, retryUrl, retrySoftwareDecode);
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

        if (legacyHlsFailure) {
            abortChannelSwitchAnimation();
            hideLoading();
            String detail = proxy == null ? "unknown" : proxy.selectedVariantDescription();
            Log.w(TAG, "Legacy HLS playback abandoned channel=" + channel.name
                    + " selected=" + detail + " reason=" + reason);
            releasePlayer(true);
            showChannelBar(channel.name, isDeviceNetworkDisconnected()
                    ? "网络已断开，请检查连接" : "此线路无法在当前设备播放，请切换线路");
            return;
        }
        int[] next = adjacentChannelLocation(currentGroupIndex, currentChannelIndex, 1);
        if (next[0] == currentGroupIndex && next[1] == currentChannelIndex) {
            abortChannelSwitchAnimation();
            hideLoading();
            showChannelBar(channel.name, isDeviceNetworkDisconnected()
                    ? "网络连接尚未恢复，请稍后重试" : "播放暂时无法恢复，请稍后重试");
            return;
        }
        Log.w(TAG, "Playback recovery exhausted; moving to next channel group="
                + next[0] + " channel=" + next[1]);
        currentGroupIndex = next[0];
        switchChannel(next[1]);
        Toast.makeText(this, "当前频道无法恢复，已切换到下一频道",
                Toast.LENGTH_LONG).show();
    }

    private void restartPlayerPreservingPosition(Channel channel, String url,
            boolean softwareDecode) throws IOException {
        // Capture before releasePlayer clears the clock. The initial seek belongs
        // to this new player only; later channel switches still start normally.
        IMediaPlayer previous = player;
        long duration = 0L, position = 0L;
        if (previous != null) {
            try {
                duration = previous.getDuration();
                position = previous.getCurrentPosition();
            } catch (RuntimeException error) {
                // A framework player can reject clock queries after onError.
                Log.w(TAG, "Unable to retain failed player's position", error);
            }
        }
        long resume = duration > 0L
                ? Math.min(Math.max(0L, duration - 1000L), Math.max(0L, position)) : 0L;
        boolean systemHls = useAndroidHlsPlayer(Build.VERSION.SDK_INT, url);
        if (systemHls || androidMp3FallbackRequestId != playRequestId
                && useAndroidMp3Player(Build.VERSION.SDK_INT, url)) {
            startAndroidMediaPlayer(channel, url, softwareDecode, systemHls);
        } else {
            startIjkPlayer(channel, url, softwareDecode,
                    url != null && url.equals(directHttpMediaUrl), null, resume);
        }
        if (resume > 0L && player != null && player != previous) {
            Log.i(TAG, "Resuming reconnected VOD positionMs=" + resume);
        }
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

    private void switchRelative(int offset) {
        if (offset == 0 || ChannelCatalog.GROUPS.length == 0) {
            return;
        }
        pendingArtworkDirection = offset > 0 ? 1 : -1;
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
        if (group.source == ChannelCatalog.SOURCE_FAVORITES && group.channels.length > 0) {
            return new int[] { normalizedGroup,
                    ChannelCatalog.wrapIndex(group.channels, candidate) };
        }
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
        closeChannelList();
        switchChannel(position);
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
        int position = browsingChannelPosition();
        if (group.channels.length == 0 || position == AdapterView.INVALID_POSITION
                || position >= group.channels.length) {
            favoriteActionFocused = false;
            channelAdapter.setFavoriteFocusIndex(-1);
            return;
        }
        epgFavorite.setText(isBrowsingChannelFavorite(position) ? "★ 已收藏" : "☆ 收藏");
        epgFavorite.setSelected(false);
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
        if (focused || epgFavorite.hasFocus() || epgList.hasFocus()) {
            channelList.requestFocus();
        }
    }

    private void toggleSelectedChannelFavorite() {
        toggleBrowsingChannelFavorite(browsingChannelPosition());
    }

    private int browsingChannelPosition() {
        if (browsingGroupIndex < 0 || browsingGroupIndex >= ChannelCatalog.GROUPS.length)
            return AdapterView.INVALID_POSITION;
        Channel[] channels = ChannelCatalog.GROUPS[browsingGroupIndex].channels;
        if (channels == null || channels.length == 0) return AdapterView.INVALID_POSITION;
        // Keyboard selection and pointer hover both update checked state.
        int position = channelList.getCheckedItemPosition();
        if (position < 0 || position >= channels.length) position = channelList.getSelectedItemPosition();
        if (position < 0 || position >= channels.length)
            position = browsingGroupIndex == currentGroupIndex ? currentChannelIndex : 0;
        return Math.max(0, Math.min(position, channels.length - 1));
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

    /** Catalog coordinates are resolved from the displayed page, never its opener. */
    private int[] displayedChannelLocation() {
        boolean visible = webSourceView != null && webSourceView.isPageVisible();
        if (!visible && (!playingDiscoveredWebStream || playingSniffedResource == null))
            return new int[]{currentGroupIndex, currentChannelIndex, currentSourceIndex};
        String url = visible ? webSourceView.currentChannelUrl() : playingSniffedChannelUrl;
        String title = visible ? webSourceView.currentChannelTitle() : playingSniffedTitle;
        String groupTitle = visible ? webSourceView.currentChannelGroup() : playingSniffedGroup;
        String key = url + "\n" + title + "\n" + groupTitle;
        if (catalogGeneration == browserSelectionCatalog && browserSelectionGroups == ChannelCatalog.GROUPS
                && key.equals(browserSelectionKey)) return browserSelection;
        int groupIndex = -1, channelIndex = -1, sourceIndex = -1, bestScore = -1;
        for (int g = 0; g < ChannelCatalog.GROUPS.length; g++) {
            ChannelCatalog.Group group = ChannelCatalog.GROUPS[g];
            boolean sameGroup = group.title.equals(groupTitle);
            for (int c = 0; c < group.channels.length; c++) {
                Channel channel = group.channels[c];
                for (int s = 0; s < channel.sourceCount(); s++) {
                    String candidate = channel.sourceUrl(s);
                    if (isWebViewSource(candidate)) candidate = candidate.substring("webview://".length());
                    if (!sameSourceUrl(url, candidate)) continue;
                    int score = (sameGroup ? 2 : 0) + (channel.name.equals(title) ? 1 : 0);
                    if (score > bestScore) { bestScore = score; groupIndex = g; channelIndex = c; sourceIndex = s; }
                }
            }
        }
        browserSelectionKey = key;
        browserSelectionCatalog = catalogGeneration;
        browserSelectionGroups = ChannelCatalog.GROUPS;
        browserSelection = new int[]{groupIndex, channelIndex, sourceIndex};
        return browserSelection;
    }

    private void openChannelList(boolean keepVisibleOnBlackScreen) {
        ensureChannelPanelInitialized();
        epgExpanded = false;
        setEpgColumnVisible(false);
        keepChannelListVisibleOnWebExit = keepVisibleOnBlackScreen;
        cancelPendingRelativeSwitch();
        clearNumericChannelInput();
        lastBackPressedAt = 0L;
        backPrompt.removeCallbacks(hideBackPrompt);
        backPrompt.setVisibility(View.GONE);
        closeManagementPanel();
        channelListPanel.setVisibility(View.VISIBLE);
        channelBar.animate().cancel();
        channelBar.setVisibility(View.GONE);
        // WebSourceView raises itself while a page is active. Raise the channel menu again
        // so the remote OK key remains usable on both video and WebView channels.
        channelListPanel.bringToFront();
        updateChannelPanelWidth();
        ensureFlyMouseOnTop();
        int[] displayed = displayedChannelLocation();
        final int menuGroup = displayed[0] >= 0 ? displayed[0] : ChannelCatalog.firstPlayableGroupIndex();
        final int menuChannel = Math.max(0, displayed[1]);
        showChannelMenu(menuGroup);
        applyClockLocation();
        channelList.post(new Runnable() {
            @Override
            public void run() {
                setFavoriteActionFocused(false);
                if (channelListPanel.getVisibility() != View.VISIBLE || browsingGroupIndex != menuGroup) return;
                channelList.setItemChecked(menuChannel, true);
                restoreGroupListPosition(false);
                channelList.requestFocusFromTouch();
                channelList.requestFocus();
                centerCurrentChannel();
            }
        });
        scheduleChannelListDismiss();
    }

    private void showChannelMenu(int groupIndex) {
        favoriteActionFocused = false;
        browsingGroupIndex = ChannelCatalog.wrapGroupIndex(groupIndex);
        ChannelCatalog.Group group = ChannelCatalog.GROUPS[browsingGroupIndex];
        int[] displayed = displayedChannelLocation();
        boolean showingPlayingGroup = browsingGroupIndex == displayed[0];
        final int selectedIndex = showingPlayingGroup ? displayed[1] : 0;
        groupAdapter.showGroups(ChannelCatalog.GROUPS, browsingGroupIndex);
        int playingIndex = showingPlayingGroup ? displayed[1] : -1;
        channelAdapter.showChannels(browsingGroupIndex, group.channels, selectedIndex,
                playingIndex, Math.max(0, displayed[2]));
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

    private void centerCurrentChannel() {
        int[] displayed = displayedChannelLocation();
        if (channelListPanel.getVisibility() != View.VISIBLE
                || browsingGroupIndex != displayed[0] || displayed[1] < 0) return;
        int rowHeight = Math.round(46f * effectiveUiDensity()) + channelList.getDividerHeight();
        int centerOffset = Math.max(0, (channelList.getHeight() - rowHeight) / 2);
        // Keep context where there are preceding rows, but never manufacture empty
        // space above the first channel. ListView also clamps naturally at the end.
        int offset = Math.min(centerOffset, displayed[1] * rowHeight);
        channelList.setSelectionFromTop(displayed[1], offset);
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
        epgToggle.setOnTouchListener(touchListener);
        epgFavorite.setOnTouchListener(touchListener);
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
                    if (position != AdapterView.INVALID_POSITION
                            && (position != channelList.getCheckedItemPosition()
                            || position != channelList.getSelectedItemPosition())) {
                        // Move the native focus selector together with the checked
                        // row, preserving its current top instead of jumping the list.
                        View row = channelList.getChildAt(
                                position - channelList.getFirstVisiblePosition());
                        int rowTop = row == null ? 0 : row.getTop();
                        channelList.setItemChecked(position, true);
                        showEpgForBrowsingChannel(position);
                        updateFavoriteButton();
                        // setItemChecked remembers the old selection's top. Apply
                        // the new anchor last so that sync cannot move this row.
                        if (row != null) {
                            channelList.setSelectionFromTop(position,
                                    rowTop - channelList.getPaddingTop());
                        }
                    }
                }
                // Do not let ListView's native hover handler move selection either.
                return true;
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
        int screenWidth = root.getWidth() > 0
                ? root.getWidth() : getResources().getDisplayMetrics().widthPixels;
        float density = effectiveUiDensity();
        int maximumPanelWidth = Math.max(1, screenWidth - Math.round(24f * density));

        // Keep a usable physical touch target without letting a high-density 720p
        // device expand this handle to the unscaled full-screen dp size.
        int epgTouchSize = Math.max(32, (int) Math.ceil(40f * density));
        setExactWidth(epgToggle, epgTouchSize);
        ViewGroup.LayoutParams epgToggleParams = epgToggle.getLayoutParams();
        if (epgToggleParams.height < epgTouchSize) {
            epgToggleParams.height = epgTouchSize;
            epgToggle.setLayoutParams(epgToggleParams);
        }

        int groupDesired = desiredGroupColumnWidth(density);
        int channelDesired = desiredChannelColumnWidth(density);
        boolean showEpg = epgColumn != null && epgColumn.getVisibility() == View.VISIBLE;
        if (!showEpg) {
            // Reserve the handle's actual width so it cannot overlap the channel row.
            int fixedWidth = Math.round(45f * density) + epgTouchSize;
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
        // Padding 28dp, two 17dp separators, and the touch-sized EPG handle.
        int fixedWidth = Math.round(62f * density) + epgTouchSize;
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
        // Some Android 4.0 vendor builds can stall in Paint.native_measureText while
        // the system font is initialized. This method runs synchronously from
        // onCreate, so use the existing maximum width instead of risking an ANR.
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.ICE_CREAM_SANDWICH_MR1) {
            return Math.round(250f * density);
        }
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
        return Math.round(280f * density);
    }

    private int desiredEpgColumnWidth(float density) {
        // Keep all channel-panel sizing off the affected legacy font path. Unlike
        // the group column, this is reached when the EPG pane is first displayed.
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.ICE_CREAM_SANDWICH_MR1) {
            return Math.round(440f * density);
        }
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
        int screenWidth = root.getWidth() > 0
                ? root.getWidth() : getResources().getDisplayMetrics().widthPixels;
        float density = effectiveUiDensity();
        // Stable geometry: status, bitrate and EPG text must not resize the card.
        int width = Math.min(Math.round(440f * density),
                Math.max(1, screenWidth - Math.round(32f * density)));
        int height = Math.round(118f * density);
        ViewGroup.LayoutParams params = channelBar.getLayoutParams();
        if (params.width != width || params.height != height) {
            params.width = width;
            params.height = height;
            channelBar.setLayoutParams(params);
        }
        updateChannelBarBottomMargin();
    }

    private void updateChannelBarBottomMargin() {
        if (channelBar == null || debugInfoOverlay == null) return;
        FrameLayout.LayoutParams bar = (FrameLayout.LayoutParams) channelBar.getLayoutParams();
        FrameLayout.LayoutParams debug = (FrameLayout.LayoutParams) debugInfoOverlay.getLayoutParams();
        int gap = Math.max(8, Math.round(12f * effectiveUiDensity()));
        // Reserve all three lines at their full configured font size, even when the
        // long codec line is temporarily shrunk to fit. Never use a stale layout top.
        int lines = Math.max(debugInfoOverlay.getMeasuredHeight(),
                (int)Math.ceil(debugInfoTextSizePx * 3.8f));
        int margin = Math.max(Math.round(18f * effectiveUiDensity()),
                showDebugInfo ? debug.bottomMargin + lines + gap : 0);
        if (bar.bottomMargin != margin) {
            bar.bottomMargin = margin;
            channelBar.setLayoutParams(bar);
        }
    }

    private static String[] readConfiguredEpgUrls(SharedPreferences preferences) {
        String saved = preferences.getString(EPG_URLS, "");
        if (saved.length() > 0) {
            try {
                return sanitizeEpgUrls(new JSONArray(saved));
            } catch (JSONException ignored) {
                // Fall through to the legacy single-address setting.
            }
        }
        try {
            return sanitizeEpgUrls(new String[] { preferences.getString(EPG_URL, "") });
        } catch (JSONException ignored) {
            return new String[0];
        }
    }

    private static String[] sanitizeEpgUrls(JSONArray values) throws JSONException {
        if (values == null) throw new JSONException("节目单地址列表格式错误");
        String[] raw = new String[Math.min(values.length(), 8)];
        for (int index = 0; index < raw.length; index++) raw[index] = values.optString(index, "");
        return sanitizeEpgUrls(raw);
    }

    private static String[] sanitizeEpgUrls(String[] values) throws JSONException {
        LinkedHashSet<String> unique = new LinkedHashSet<String>();
        if (values != null) {
            for (String raw : values) {
                String value = raw == null ? "" : raw.trim();
                if (value.length() == 0) continue;
                String lower = value.toLowerCase(Locale.US);
                if (!lower.startsWith("http://") && !lower.startsWith("https://")) {
                    throw new JSONException("节目单地址仅支持 HTTP 或 HTTPS");
                }
                unique.add(value);
                if (unique.size() >= 8) break;
            }
        }
        return unique.toArray(new String[unique.size()]);
    }

    private static JSONArray epgUrlsJson(String[] values) {
        JSONArray result = new JSONArray();
        if (values != null) for (String value : values) result.put(value);
        return result;
    }

    private String effectiveEpgUrl() {
        return effectiveEpgUrls()[0];
    }

    private String[] effectiveEpgUrls() {
        if (epgUrls != null && epgUrls.length > 0) return epgUrls.clone();
        String embedded = playlistManager == null ? "" : playlistManager.getEmbeddedEpgUrl();
        return new String[] { embedded.length() > 0 ? embedded : EpgManager.DEFAULT_URL };
    }

    private void refreshEpg() {
        if (root == null) return;
        root.removeCallbacks(deferredEpgRefresh);
        epgIdleSince = 0L;
        root.postDelayed(deferredEpgRefresh, 1500L);
    }

    private void refreshEpgNow() {
        if (epgManager == null) {
            return;
        }
        epgManager.refresh(effectiveEpgUrls(), new EpgManager.Listener() {
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
                        if (channelListPanel.getVisibility() == View.VISIBLE) {
                            channelAdapter.notifyDataSetChanged();
                        }
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
        epgToggle.setText(epgExpanded ? "节\n目\n单\n‹" : "节\n目\n单\n›");
        epgToggle.setContentDescription(epgExpanded ? "收起节目单" : "展开节目单");
        setEpgColumnVisible(epgExpanded);
        if (!epgExpanded || channelListPanel.getVisibility() != View.VISIBLE) return;
        Channel[] channels = ChannelCatalog.GROUPS[browsingGroupIndex].channels;
        if (channels == null || channels.length == 0) {
            epgAdapter.showPrograms(null);
            epgStatus.setText("暂无频道");
            epgFavorite.setEnabled(false);
            return;
        }
        int safePosition = position >= 0 && position < channels.length
                ? position : browsingChannelPosition();
        Channel channel = channels[safePosition];
        epgFavorite.setEnabled(true);
        epgFavorite.setText(isBrowsingChannelFavorite(safePosition) ? "★ 已收藏" : "☆ 收藏");
        java.util.List<EpgManager.Program> programs = epgManager.programsFor(channel);
        epgAdapter.showPrograms(programs);
        if (programs.isEmpty()) {
            String error = epgManager.getLastError();
            epgStatus.setText(epgManager.isLoading() ? channel.name + " · 正在加载节目单"
                    : error.length() > 0 ? channel.name + " · 加载失败"
                    : channel.name + " · 暂无节目单");
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
        if (!visible && (epgList.hasFocus() || epgFavorite.hasFocus())) {
            setFavoriteActionFocused(false);
            channelList.requestFocus();
        }
        if (changed && channelListPanel != null
                && channelListPanel.getVisibility() == View.VISIBLE) {
            updateChannelPanelWidth();
        }
    }

    private static void setCardText(TextView view, CharSequence text) {
        if (!android.text.TextUtils.equals(view.getText(), text)) view.setText(text);
    }

    private boolean isBrowserChannelCard(String displayedChannel) {
        if (displayedChannel == null) return false;
        boolean visible = webSourceView != null && webSourceView.isPageVisible();
        if (!visible && (!playingDiscoveredWebStream || playingSniffedResource == null)) return false;
        Channel channel = currentChannel();
        return displayedChannel.equals(visible ? webSourceView.currentChannelTitle() : playingSniffedTitle)
                || channel != null && displayedChannel.equals(channel.name)
                || !visible && activePlayerChannel != null && displayedChannel.equals(activePlayerChannel.name);
    }

    private String channelCardTitle(String requestedTitle) {
        // Playback callbacks retain the catalog entry for navigation/persistence.
        // Present the captured page instead, even after its WebView is drained.
        if (!isBrowserChannelCard(requestedTitle)) return requestedTitle;
        if (webSourceView != null && webSourceView.isPageVisible()) return webSourceView.currentChannelTitle();
        return playingSniffedTitle.length() > 0 ? playingSniffedTitle : playingSniffedPageUrl;
    }

    private void showChannelBar(final String channel, final String status) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                channelBar.removeCallbacks(hideChannelBar);
                String title = channelCardTitle(channel);
                setCardText(channelName, title);
                setCardText(statusText, withSourceLineStatus(channel, status));
                channelProgress.setVisibility(loadingActive ? View.VISIBLE : View.INVISIBLE);
                updateChannelCardEpg(title);
                showChannelCard();
                if (!loadingActive) {
                    channelBar.postDelayed(hideChannelBar, CHANNEL_BAR_TIMEOUT_MS);
                }
            }
        });
    }

    private void showChannelCard() {
        if (shouldDeferChannelCardForArtwork()) {
            channelCardDeferredForArtwork = true;
            channelBar.removeCallbacks(hideChannelBar);
            channelBar.setVisibility(View.GONE);
            return;
        }
        channelCardDeferredForArtwork = false;
        if (channelListPanel.getVisibility() == View.VISIBLE) return;
        updateChannelBarWidth();
        if (channelBar.getVisibility() == View.VISIBLE) {
            return;
        }
        channelBar.setAlpha(1f);
        channelBar.setTranslationY(0f);
        channelBar.setVisibility(View.VISIBLE);

    }

    private void updateChannelCardEpg(String displayedChannel) {
        if (channelEpg == null || epgManager == null || displayedChannel == null) {
            return;
        }
        Channel channel = currentChannel();
        channelCardEpgUpdatedAt = SystemClock.elapsedRealtime();
        TextView number = (TextView) findViewById(R.id.channel_card_number);
        if (isBrowserChannelCard(displayedChannel)) {
            setCardText(number, "");
            setCardText(channelEpg, "");
            channelEpg.setVisibility(View.INVISIBLE);
            statusText.setVisibility(View.VISIBLE);
            return;
        }
        setCardText(number, channel != null && displayedChannel.equals(channel.name)
                ? ChannelCatalog.displayNumber(currentGroupIndex, currentChannelIndex) + " |" : "");
        if (channel == null || !displayedChannel.equals(channel.name)) {
            statusText.setVisibility(View.VISIBLE);
            channelEpg.setVisibility(View.INVISIBLE);
            return;
        }
        long now = System.currentTimeMillis();
        java.util.List<EpgManager.Program> programs = epgManager.programsFor(channel);
        for (int index = 0; index < programs.size(); index++) {
            EpgManager.Program program = programs.get(index);
            if (!program.isPlaying(now)) {
                continue;
            }
            String text = channelEpgTimeFormat.format(new Date(program.startMillis))
                    + "–" + channelEpgTimeFormat.format(new Date(program.stopMillis))
                    + "  " + program.title;
            if (index + 1 < programs.size()) {
                EpgManager.Program next = programs.get(index + 1);
                text += "\n" + channelEpgTimeFormat.format(new Date(next.startMillis))
                        + "–" + channelEpgTimeFormat.format(new Date(next.stopMillis))
                        + "  " + next.title;
            }
            setCardText(channelEpg, text);
            statusText.setVisibility(!loadingActive && statusText.getText().toString().contains("播放中")
                    ? View.INVISIBLE : View.VISIBLE);
            channelEpg.setVisibility(View.VISIBLE);
            return;
        }
        channelEpg.setVisibility(View.INVISIBLE);
        statusText.setVisibility(View.VISIBLE);
    }

    private String yangshipinDefinition(Channel channel) {
        return CjsPluginRuntime.quality("yangshipin.cn", playbackResolutionMode(),
                channel.yangshipinMaxDefinition);
    }

    private void showLoading(final String channel, final String status) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                loadingActive = true;
                channelBar.removeCallbacks(hideChannelBar);
                String title = channelCardTitle(channel);
                setCardText(channelName, title);
                setCardText(statusText, withSourceLineStatus(channel, status));
                channelProgress.setVisibility(View.VISIBLE);
                updateChannelCardEpg(title);
                refreshVideoInfo();
                showChannelCard();
            }
        });
    }

    /** Keeps the selected backup line visible throughout loading, buffering and playback. */
    private String withSourceLineStatus(String displayedChannel, String status) {
        String value = status == null ? "" : status.trim();
        if (isBrowserChannelCard(displayedChannel)) return value;
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

    private void dismissWebNavigationChannelBar() {
        channelCardDeferredForArtwork = false;
        hideLoading();
        channelBar.removeCallbacks(hideChannelBar);
        hideChannelBar.run();
    }

    private void hideLoading() {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                loadingActive = false;
                if (audioArtwork != null) audioArtwork.finishChannelSwitch();
                channelProgress.setVisibility(View.INVISIBLE);
                channelBar.removeCallbacks(hideChannelBar);
                if (channelBar.getVisibility() == View.VISIBLE) {
                    channelBar.postDelayed(hideChannelBar, CHANNEL_BAR_TIMEOUT_MS);
                }
            }
        });
    }

    private final CastNetworkLease receiverNetworkLease = new CastNetworkLease();

    private void releasePlayer() {
        releasePlayer(false);
    }

    private void releasePlayer(boolean keepPendingArtwork) {
        videoScreenshot.clear();
        sniffedPlaybackWarmup.reset();
        albumArtLoader.clear();
        audioOnlyPlayback = false;
        if (audioArtwork != null) audioArtwork.clear(keepPendingArtwork);
        if (playbackSeekOverlay != null) playbackSeekOverlay.dismiss();
        cancelRemoteResolve();
        receiverStreamSessionId = "";
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
        latestPlaybackDebugStats = null;
        measuredTransportBytesPerSecond = -1L;
        resetNetworkSpeedSamples();
        clearSubtitleText();
        if (networkSpeedOverlay != null && showNetworkSpeed) {
            networkSpeedOverlay.setText("--");
        }
        if (videoInfo != null) {
            videoInfo.removeCallbacks(updateVideoInfo);
            setCardText(videoInfo, "--");
        }
        if (player != null) {
            final IMediaPlayer oldPlayer = player;
            player = null;
            long releaseStartedAt = SystemClock.elapsedRealtime();
            try {
                oldPlayer.setSurface(null);
                oldPlayer.setDisplay(null);
            } catch (RuntimeException error) {
                Log.w(TAG, "Unable to detach old player", error);
            }
            final int releaseRequestId = playRequestId;
            final java.util.concurrent.CountDownLatch releaseComplete =
                    new java.util.concurrent.CountDownLatch(1);
            playerReleaseComplete = releaseComplete;
            final long releaseQueuedAt = SystemClock.elapsedRealtime();
            Runnable release = new Runnable() {
                @Override public void run() {
                    long started = SystemClock.elapsedRealtime();
                    if (oldPlayer instanceof AndroidMediaPlayer) {
                        try {
                            oldPlayer.reset();
                            Log.i(TAG, "Background system player reset took "
                                    + (SystemClock.elapsedRealtime() - started) + "ms");
                        } catch (RuntimeException error) {
                            Log.w(TAG, "Unable to reset old system player", error);
                        }
                    }
                    try {
                        oldPlayer.release();
                    } catch (RuntimeException error) {
                        Log.w(TAG, "Unable to release old player", error);
                    } finally {
                        releaseComplete.countDown();
                    }
                    long finished = SystemClock.elapsedRealtime();
                    Log.i(TAG, "Background player release request=" + releaseRequestId
                            + " queueMs=" + (started - releaseQueuedAt)
                            + " runMs=" + (finished - started)
                            + " elapsedMs=" + (finished - releaseQueuedAt));
                }
            };
            if (oldPlayer instanceof AndroidMediaPlayer) {
                legacyPlayerReleasePending = true;
                final int releaseGeneration = ++legacyPlayerReleaseGeneration;
                LEGACY_PLAYER_RELEASE_WORKER.execute(new Runnable() {
                    @Override public void run() {
                        release.run();
                        channelBar.post(new Runnable() {
                            @Override public void run() {
                                if (releaseGeneration != legacyPlayerReleaseGeneration) return;
                                legacyPlayerReleasePending = false;
                                if (isFinishing()) return;
                                Runnable setup = pendingChannelSetupAfterRelease;
                                if (setup != null) {
                                    pendingChannelSetupAfterRelease = null;
                                    setup.run();
                                } else if (pendingPlayerChannel != null) {
                                    startPendingPlayer();
                                }
                            }
                        });
                    }
                });
                channelBar.postDelayed(new Runnable() {
                    @Override public void run() {
                        if (!legacyPlayerReleasePending
                                || releaseGeneration != legacyPlayerReleaseGeneration) return;
                        // Starting another decoder before this release completes can
                        // exhaust the device's video buffers and freeze Android 4.0.
                        Log.w(TAG, "Legacy system player release still pending after 8s");
                        if ((pendingChannelSetupAfterRelease != null
                                || pendingPlayerChannel != null) && !isFinishing()) {
                            hideLoading();
                            showChannelBar(currentChannel().name,
                                    "系统解码器释放超时，请重启应用或稍后重试");
                        }
                    }
                }, 8000L);
            } else PLAYER_RELEASE_WORKER.execute(release);
            Log.i(TAG, "Player detach request=" + releaseRequestId + " tookMs="
                    + (SystemClock.elapsedRealtime() - releaseStartedAt));
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
            if (returnFromFailedSniffedPlayback("资源连接失败")) return;
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
        {
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
        {
            videoView.setVideoSize(videoWidth, videoHeight, videoSarNum, videoSarDen);
        }
        refreshVideoInfo();
        Log.i(TAG, "Video source=" + videoWidth + "x" + videoHeight
                + " sar=" + videoSarNum + "/" + videoSarDen);
    }

    private static boolean isAudioOnly(IjkMediaPlayer mediaPlayer) {
        try {
            IjkMediaMeta meta = IjkMediaMeta.parse(mediaPlayer.getMediaMeta());
            // MP3 APIC pictures can be exposed as an MJPEG video track. They are album art.
            return meta != null && meta.mAudioStream != null
                    && (meta.mVideoStream == null || "mp3".equals(meta.mFormat));
        } catch (RuntimeException error) {
            return false;
        }
    }

    private long seekableDuration() {
        if (!prepared || player == null || (webSourceView != null && webSourceView.isPageVisible())) return 0L;
        return Math.max(0L, player.getDuration());
    }

    private void showPlaybackProgress(long position) {
        if (seekableDuration() <= 0L) return;
        if (playbackSeekOverlay == null) {
            playbackSeekOverlay = new PlaybackSeekOverlay(this, new PlaybackSeekOverlay.Playback() {
                public long duration() { return seekableDuration(); }
                public long position() { return player == null ? 0L : Math.max(0L, player.getCurrentPosition()); }
                public void seek(long value) {
                    if (seekableDuration() > 0L) player.seekTo(Math.min(seekableDuration() - 1L, Math.max(0L, value)));
                }
            });
            playbackSeekOverlay.attach((android.widget.FrameLayout) root);
        }
        playbackSeekOverlay.showProgress(position);
    }

    private void scheduleVideoInfoRefresh() {
        videoInfo.removeCallbacks(updateVideoInfo);
        videoInfo.post(updateVideoInfo);
    }

    @SuppressLint("SetTextI18n")
    private void refreshVideoInfo() {
        if (audioArtwork != null && audioOnlyPlayback)
            audioArtwork.setPlaying(prepared && player != null && player.isPlaying() && !buffering);
        if (videoInfo == null && debugInfoOverlay == null) {
            return;
        }
        refreshCallAudioMute();
        float outputFps = 0f;
        if (player != null) {
            if (player instanceof IjkMediaPlayer)
                outputFps = validFrameRate(IjkStreamMetrics.outputFrameRate((IjkMediaPlayer) player));
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
            if (sniffedPlaybackWarmup.sample(now, playbackPosition,
                    webViewAutoCloseSniffed && canReturnToSniffedPage() && playingSniffedResource != null
                    && !webSourceView.isPageVisible() && !buffering && player.isPlaying()
                    && (audioOnlyPlayback || videoRenderingStarted && outputFps > 0.1f))) {
                suspendSniffedWebAudio();
            }
            // A live HLS window can rebase the reported position when older
            // segments leave the manifest.  A backwards jump is still playback
            // progress; treating it as a stall causes a false reconnect roughly once
            // per playlist-history window.
            if (playbackPosition >= 0L && playbackPosition != lastPlaybackPosition) {
                // The first clock sample is only a baseline, not proof of playback.
                // In particular -1 -> 0 during prepare must not put a cold start into
                // the stalled-playback retry / next-channel recovery path.
                boolean clockAdvanced = lastPlaybackPosition >= 0L;
                if (clockAdvanced) playbackProgressObserved = true;
                lastPlaybackPosition = playbackPosition;
                lastPlaybackProgressAt = now;
                if (clockAdvanced && playbackRecoveryAttempts > 0 && lastPlaybackRecoveryAt > 0L
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
            // API 14-15 MediaPlayer can render HLS without reporting video dimensions
            // or MEDIA_INFO_VIDEO_RENDERING_START. An advancing playback clock is the
            // fallback proof that startup finished, so the loading card can dismiss.
            if (player instanceof AndroidMediaPlayer && !audioOnlyPlayback && prepared
                    && !videoRenderingStarted && playbackProgressObserved && !buffering
                    && player.isPlaying() && activePlayerChannel != null) {
                markAndroidHlsVideoReady((AndroidMediaPlayer) player,
                        activePlayerChannel, playRequestId);
            }
        }
        PlaybackDebugStats stats = collectPlaybackStreamStats(outputFps);
        latestPlaybackDebugStats = stats;
        String resolution = stats.width > 0 && stats.height > 0
                ? stats.width + "×" + stats.height : "--×--";
        String fps = formatDebugFrameRate(stats);
        if (videoInfo != null && channelBar.getVisibility() == View.VISIBLE) {
            setCardText(videoInfo, audioOnlyPlayback
                    ? stats.audioCodec + " · " + formatBitrate(stats.audioBitrate)
                    : stats.videoDescription.rangeLabel() + " · " + resolution
                            + " · " + fps + " · " + formatBitrate(stats.videoBitrate));
        }
        if (channelBar.getVisibility() == View.VISIBLE) {
            if (SystemClock.elapsedRealtime() - channelCardEpgUpdatedAt >= 60_000L) {
                updateChannelCardEpg(channelName.getText().toString());
            }
        }
        if (debugInfoOverlay != null && showDebugInfo) {
            stats.cpuUsage = sampleSystemCpuUsage();
            stats.cpuLabel = systemCpuMetricLabel;
            String debugResolution = stats.width > 0 && stats.height > 0
                    ? stats.width + "x" + stats.height : "--";
            String debugFps = formatDebugFrameRate(stats);
            String gap = "\u2009";
            String details = debugResolution + gap + debugFps + gap + formatDebugVideoCodec(stats)
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
                if (stats.encodeDetail.length() > 0) details += gap + stats.encodeDetail;
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
            String colorDetails = stats.videoDescription.debugLabel()
                    + " · 解码器 " + stats.videoDecoderName;
            debugInfoOverlay.setText(TextUtils.ellipsize(source, debugInfoOverlay.getPaint(),
                    availableWidth, TextUtils.TruncateAt.END) + "\n" + details
                    + (audioOnlyPlayback ? "" : "\n" + TextUtils.ellipsize(colorDetails,
                            debugInfoOverlay.getPaint(), availableWidth, TextUtils.TruncateAt.END)));
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

    private static float validFrameRate(float value) {
        // Some legacy emulator/player combinations report Infinity or NaN.
        // Neither can represent a measured frame rate or reach metadata fallback.
        return Float.isNaN(value) || Float.isInfinite(value) || value < 0f ? 0f : value;
    }

    private boolean shouldDeferChannelCardForArtwork() {
        return audioArtwork != null && (audioArtwork.isTransitionRunning()
                || isAudioArtworkInteractive() && pendingRelativeChannelIndex >= 0);
    }

    private void onArtworkTransitionChanged() {
        if (channelBar == null || isFinishing()) return;
        if (shouldDeferChannelCardForArtwork()) {
            channelCardDeferredForArtwork |= channelBar.getVisibility() == View.VISIBLE;
            channelBar.removeCallbacks(hideChannelBar);
            channelBar.setVisibility(View.GONE);
        } else if (channelCardDeferredForArtwork) {
            // Keep updating the hidden card while loading. Reveal the latest
            // channel/status only after the actual animation completion callback.
            showChannelCard();
            channelBar.removeCallbacks(hideChannelBar);
            if (!loadingActive && channelBar.getVisibility() == View.VISIBLE)
                channelBar.postDelayed(hideChannelBar, CHANNEL_BAR_TIMEOUT_MS);
        }
    }

    private PlaybackDebugStats collectPlaybackStreamStats(float measuredOutputFps) {
        PlaybackDebugStats stats = new PlaybackDebugStats();
        stats.width = videoWidth;
        stats.height = videoHeight;
        stats.frameRate = validFrameRate(measuredOutputFps);
        if (player instanceof AndroidMediaPlayer) stats.videoDecoderName = "系统解码器（名称未知）";
        if (player instanceof AndroidMediaPlayer && !audioOnlyPlayback && proxy != null) {
            if (stats.width <= 0 || stats.height <= 0) {
                stats.width = proxy.sourceVideoWidth();
                stats.height = proxy.sourceVideoHeight();
            }
            stats.nominalFrameRate = validFrameRate(proxy.sourceVideoFrameRate());
            stats.sourceFrameRate = stats.nominalFrameRate > 0.01f;
            stats.audioCodec = proxy.sourceAudioCodec();
            long videoBitrate = proxy.measuredMediaBitrate(true);
            long audioBitrate = proxy.measuredMediaBitrate(false);
            if (videoBitrate > 0L) stats.videoBitrate = videoBitrate;
            if (audioBitrate > 0L) stats.audioBitrate = audioBitrate;
        }
        if (player instanceof IjkMediaPlayer) {
            IjkMediaPlayer ijkPlayer = (IjkMediaPlayer) player;
            try {
                stats.videoDecoder = ijkPlayer.getVideoDecoder();
                stats.videoDecoderName = IjkStreamMetrics.videoDecoderName(ijkPlayer);
                stats.videoDescription = IjkStreamMetrics.videoDescription(ijkPlayer);
            } catch (RuntimeException error) {
                Log.w(TAG, "Unable to read active video decoder", error);
            }
            if (!IjkStreamMetrics.extendedMetricsAvailable(ijkPlayer)) stats.frameRate = 0f;
            applyIjkMetadata(stats);
            // Count access units against their media timestamps. SPS/VUI may
            // advertise 50 fields/s for 25 interlaced frames/s.
            stats.nominalFrameRate = validFrameRate(IjkStreamMetrics.sourceFrameRate(ijkPlayer));
            applyIjkRuntimeBitrates(stats);
            if (isNtVCastSource(activePlayerStreamUrl)) {
                // Empty decode queues are normal here. The sender counts actual RTP
                // bytes for both TCP and UDP; never substitute configured bitrate.
                if (remoteCatalogUrl.length() > 0 && SystemClock.elapsedRealtime()
                        - lastRemoteTakeoverMessageAt < TAKEOVER_SESSION_TIMEOUT_MS) {
                    if (remoteCastVideoBitrate >= 0L) stats.videoBitrate = remoteCastVideoBitrate;
                    if (remoteCastAudioBitrate >= 0L) stats.audioBitrate = remoteCastAudioBitrate;
                }
                stats.networkDelayMs = remoteNetworkDelayMs;
                stats.encodeDelayMs = remoteEncodeDelayMs;
                stats.encodeDetail = remoteEncodeDetail;
                stats.videoQueueDelayMs = remoteVideoQueueDelayMs;
                stats.videoSendDelayMs = remoteVideoSendDelayMs;
                try {
                    // For the low-buffer RTSP path this is the compressed video
                    // duration waiting for decode/render, which is the useful
                    // receiver-side decode queue delay.
                    stats.decodeDelayMs = Math.max(0L,
                            Math.min(9999L, ijkPlayer.getVideoCachedDuration()));
                } catch (RuntimeException ignored) {
                }
            }
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
        long now = SystemClock.elapsedRealtime();
        if (lastCpuSampleAt > 0L && now - lastCpuSampleAt < 2000L) return cachedCpuUsage;
        lastCpuSampleAt = now;
        cachedCpuUsage = readSystemCpuUsage();
        return cachedCpuUsage;
    }

    private float readSystemCpuUsage() {
        float usage = sampleProcStatCpuUsage();
        if (usage >= 0f) {
            useSystemCpuMetric("proc-stat", "CPU（系统）");
            return usage;
        }
        // A readable proc counter needs two samples. Do not scan every CPU
        // idle-state file merely because this call has no elapsed jiffies yet.
        if (!procStatCpuUnavailable && lastSystemCpuTotalJiffies > 0L) return cachedCpuUsage;
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
        IjkMediaPlayer activePlayer = player instanceof IjkMediaPlayer ? (IjkMediaPlayer) player : null;
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
        // A stalled or paused output must remain zero, even with valid metadata.
        stats.videoCodec = cached.videoCodec;
        stats.videoBitrate = cached.videoBitrate;
        stats.audioCodec = cached.audioCodec;
        stats.audioBitrate = cached.audioBitrate;
    }

    /** Uses encoded packet bytes/media duration, with transport bytes as RTSP fallback. */
    private void applyIjkRuntimeBitrates(PlaybackDebugStats stats) {
        IjkMediaPlayer activePlayer = player instanceof IjkMediaPlayer ? (IjkMediaPlayer) player : null;
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
            // Complete HLS segments measure media time, unlike the decoder's shrinking
            // packet queue or burst downloads. Prefer elementary payload measurements.
            if (!realtimeTransport && proxy != null) {
                long video = proxy.measuredMediaBitrate(true);
                long audio = proxy.measuredMediaBitrate(false);
                if (video > 0L) stats.videoBitrate = video;
                if (audio > 0L) stats.audioBitrate = audio;
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

    private static String formatDebugFrameRate(PlaybackDebugStats stats) {
        if (stats.sourceFrameRate) {
            return stats.nominalFrameRate > 0.01f
                    ? String.format(Locale.US, "--/%.1f fps(源)", stats.nominalFrameRate) : "--fps";
        }
        if (stats.nominalFrameRate > 0.01f) {
            return String.format(Locale.US, "%.1f/%.1f fps",
                    stats.frameRate, stats.nominalFrameRate);
        }
        return stats.frameRate > 0.01f
                ? String.format(Locale.US, "%.1f/-- fps", stats.frameRate) : "--fps";
    }

    private static String formatDebugVideoCodec(PlaybackDebugStats stats) {
        if (stats.videoCodec == null || "--".equals(stats.videoCodec)) return "--";
        String codec = stats.videoCodec.replace("H.264", "H264")
                .replace("H.265", "H265");
        if (stats.videoDecoder == IjkMediaPlayer.FFP_PROPV_DECODER_MEDIACODEC) {
            return codec + "(硬解)";
        }
        if (stats.videoDecoder == IjkMediaPlayer.FFP_PROPV_DECODER_AVCODEC) {
            return codec + "(软解)";
        }
        return codec;
    }

    private static final class PlaybackDebugStats {
        int width;
        int height;
        float frameRate;
        float nominalFrameRate;
        boolean sourceFrameRate;
        String videoCodec = "--";
        int videoDecoder = IjkMediaPlayer.FFP_PROPV_DECODER_UNKNOWN;
        String videoDecoderName = "未知";
        VideoStreamDescription videoDescription = VideoStreamDescription.UNKNOWN;
        long videoBitrate = -1L;
        String audioCodec = "--";
        long audioBitrate = -1L;
        float cpuUsage = -1f;
        String cpuLabel = "CPU（系统）";
        long networkDelayMs = -1L;
        long decodeDelayMs = -1L;
        long encodeDelayMs = -1L;
        String encodeDetail = "";
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
        if (nextPosition == position) return;
        View nextRow = channelList.getChildAt(
                nextPosition - channelList.getFirstVisiblePosition());
        View selectedRow = channelList.getSelectedView();
        int rowHeight = nextRow != null ? nextRow.getHeight()
                : selectedRow != null ? selectedRow.getHeight()
                : Math.round(46f * effectiveUiDensity());
        int top = channelList.getPaddingTop();
        int bottom = channelList.getHeight() - channelList.getPaddingBottom();
        // setSelection() lets ListView reuse the old selection's sync anchor,
        // pinning focus at the top while the viewport moves underneath it.
        // Preserve visible row geometry; scroll only enough to reveal a new row.
        int rowTop = nextRow != null ? nextRow.getTop()
                : offset > 0 ? bottom - rowHeight : top;
        rowTop = Math.max(top, Math.min(rowTop, bottom - rowHeight));
        channelList.setItemChecked(nextPosition, true);
        channelList.setSelectionFromTop(nextPosition, rowTop - top);
        showEpgForBrowsingChannel(nextPosition);
        updateFavoriteButton();
    }

    private void suspendSniffedWebAudio() {
        if (!webViewAutoCloseSniffed || !canReturnToSniffedPage()) return;
        webSourceView.suspendForStreamPlayback(sniffedWebAudioCompletion());
    }

    private WebViewShutdown.Completion sniffedWebAudioCompletion() {
        final IMediaPlayer expectedPlayer = player;
        final int expectedRequest = playRequestId;
        final long expectedPlayback = sniffedPlaybackGeneration;
        final long expectedFocus = playbackAudioFocusGeneration;
        refreshCallAudioMute();
        final boolean ownedFocus = playbackAudioManager != null
                && !mutedByAudioFocus && !mutedByCallMode;
        return stopped -> {
            Log.i(TAG, "Web audio drain stopped=" + stopped + " ownedFocus=" + ownedFocus
                    + " focusUnchanged=" + (playbackAudioFocusGeneration == expectedFocus));
            // Focus callbacks and cleanup callbacks share the main looper. A loss
            // has no public owner identity: never assume it came from our WebView,
            // and never steal focus back from a call/another app on a timer.
            if (!stopped || !ownedFocus || player != expectedPlayer
                    || playRequestId != expectedRequest
                    || sniffedPlaybackGeneration != expectedPlayback
                    || playbackAudioFocusGeneration != expectedFocus
                    || !playingDiscoveredWebStream || playingSniffedResource == null
                    || !prepared || isFinishing()) return;
            refreshCallAudioMute();
            if (mutedByAudioFocus || mutedByCallMode || !player.isPlaying()) return;
            requestPlaybackAudioFocus();
            applyPlaybackMuteState(); // A denied request keeps volume at zero.
            Log.i(TAG, "Native audio handoff granted=" + !mutedByAudioFocus);
        };
    }

    private void requestPlaybackAudioFocus() {
        playbackAudioFocusGeneration++;
        if (playbackAudioManager == null) {
            playbackAudioManager = (AudioManager) getSystemService(AUDIO_SERVICE);
        }
        if (playbackAudioManager == null) {
            return;
        }
        try {
            int result = playbackAudioManager.requestAudioFocus(playbackAudioFocusListener,
                    AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN);
            mutedByAudioFocus = result != AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
        } catch (RuntimeException error) {
            mutedByAudioFocus = true;
            Log.w(TAG, "Audio service unavailable while requesting focus", error);
        }
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
        boolean callActive;
        try {
            int mode = playbackAudioManager.getMode();
            callActive = mode == AudioManager.MODE_IN_CALL
                    || mode == AudioManager.MODE_IN_COMMUNICATION;
        } catch (RuntimeException error) {
            // Unknown service state is not permission to unmute or acquire focus.
            callActive = true;
            Log.w(TAG, "Audio service unavailable while checking call state", error);
        }
        if (mutedByCallMode != callActive) {
            mutedByCallMode = callActive;
            applyPlaybackMuteState();
        }
    }

    private boolean isPlaybackMuted() {
        return mutedByAudioFocus || mutedByCallMode;
    }

    private void abandonPlaybackAudioFocus() {
        if (playbackAudioManager == null) return;
        try {
            playbackAudioManager.abandonAudioFocus(playbackAudioFocusListener);
        } catch (RuntimeException error) {
            Log.w(TAG, "Audio service unavailable while abandoning focus", error);
        }
    }

    private void applyPlaybackMuteState() {
        IMediaPlayer activePlayer = player;
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
        if (webSourceView != null && webSourceView.isPageVisible()) {
            return webSourceView.activePageUrl();
        }
        if (hasRetainedWebPlayback() && playingSniffedResource != null) {
            return activePlayerStreamUrl != null ? activePlayerStreamUrl : playingSniffedResource.url;
        }
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
            case KeyEvent.KEYCODE_CHANNEL_UP:
                return KeyEvent.KEYCODE_DPAD_UP;
            case KeyEvent.KEYCODE_CHANNEL_DOWN:
                return KeyEvent.KEYCODE_DPAD_DOWN;
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

    private boolean isAudioArtworkInteractive() {
        // Decoder readiness is reset on every switch. The visible pending record
        // must still accept the next gesture while the new audio is connecting.
        return audioArtwork != null && audioArtwork.getVisibility() == View.VISIBLE
                && (audioOnlyPlayback || audioArtwork.hasPendingPresentation());
    }

    private boolean canStartPlaybackGesture() {
        return root != null
                && !channelSwitchAnimating
                && (!gestureReboundAnimating || isAudioArtworkInteractive())
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
        if (gestureReboundAnimating && isAudioArtworkInteractive()) {
            channelBar.removeCallbacks(finishGestureRebound);
            gestureReboundAnimating = false;
        }
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
            if (!playbackGestureLeftSide) prepareChannelSwipeSnapshot();
            playbackGestureVertical = true;
        } else if (!playbackGestureVertical && !playbackGestureHorizontal
                && !playbackGestureLeftSide
                && absoluteX > playbackGestureTouchSlop
                && absoluteX > absoluteY * 1.25f) {
            prepareChannelSwipeSnapshot();
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
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q
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
            PixelCopy.request(videoView.getSurfaceView(), bitmap, new PixelCopy.OnPixelCopyFinishedListener() {
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

    private boolean isAudioArtworkChannel(Channel channel, int sourceIndex) {
        String url = channel.sourceUrl(sourceIndex);
        if (url == null) return false;
        if (channel.radio) return true;
        Boolean known = audioChannelTypes.get(url);
        if (known != null) return known;
        String path = Uri.parse(url).getPath();
        if (path == null) return false;
        path = path.toLowerCase(Locale.US);
        return path.endsWith(".mp3") || path.endsWith("-mp3") || path.endsWith(".aac")
                || path.endsWith("-aac") || path.endsWith(".m4a") || path.endsWith(".flac")
                || path.endsWith(".wav") || path.endsWith(".opus");
    }

    private void prefetchAdjacentArtwork() {
        int[] previous = adjacentChannelLocation(currentGroupIndex, currentChannelIndex, -1);
        int[] next = adjacentChannelLocation(currentGroupIndex, currentChannelIndex, 1);
        Channel before = previous == null ? null : ChannelCatalog.GROUPS[previous[0]].channels[previous[1]];
        Channel after = next == null ? null : ChannelCatalog.GROUPS[next[0]].channels[next[1]];
        albumArtLoader.prefetchNeighbors(this,
                before != null && isAudioArtworkChannel(before, 0) ? before.logoUrl : "",
                after != null && isAudioArtworkChannel(after, 0) ? after.logoUrl : "",
                (url, art) -> audioArtwork.updateNeighborCover(url, art));
    }

    private void moveSwitchPreview(float translationX, float translationY) {
        if (isAudioArtworkInteractive() && translationX == 0f) {
            int[] location = adjacentChannelLocation(currentGroupIndex, currentChannelIndex, translationY < 0f ? 1 : -1);
            Channel neighbor = location == null ? null : ChannelCatalog.GROUPS[location[0]].channels[location[1]];
            boolean music = neighbor != null && isAudioArtworkChannel(neighbor, 0);
            String logo = music ? neighbor.logoUrl : "";
            audioArtwork.previewSlide(translationY, music, logo, AlbumArtLoader.cachedLogo(logo));
            return;
        }
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
        if (isAudioArtworkInteractive())
            audioArtwork.restoreSlide(GESTURE_REBOUND_ANIMATION_MS, GESTURE_REBOUND_EASING);
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
        boolean movedPlayback = (playbackGestureVertical && !playbackGestureLeftSide)
                || playbackGestureHorizontal;
        playbackGestureTracking = false;
        playbackGestureVertical = false;
        playbackGestureHorizontal = false;
        playbackGestureLastVolume = -1;
        if (!channelSwitchAnimating && !gestureReboundAnimating) {
            if (movedPlayback) {
                clearChannelSwitchVisuals();
                restorePlaybackLayer();
            }
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
            if (webSourceView.isPageVisible()) {
                webSourceView.setTranslationX(0f);
                webSourceView.setTranslationY(0f);
            } else {
                webSourceView.setTranslationX(translationX);
                webSourceView.setTranslationY(translationY);
            }
        }
    }

    private void restorePlaybackLayer() {
        if (isAudioArtworkInteractive()) {
            if (pendingRelativeChannelIndex >= 0 || audioArtwork.isTransitionRunning()) return;
            audioArtwork.restoreSlide();
        }
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
        if (isAudioArtworkInteractive()) {
            // Audio uses its existing record texture: no Surface snapshot, blackout,
            // or extra delay before starting the next channel's network request.
            switchRelative(offset);
            // A committed finger gesture already chose one channel. Do not leave
            // its preview frozen for the repeat-key debounce interval.
            channelBar.removeCallbacks(commitRelativeChannelSwitch);
            commitRelativeChannelSwitch.run();
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
        if (view != null && (view.getTranslationX() != translationX
                || view.getTranslationY() != translationY || view.getAlpha() != 1f)) {
            view.animate().translationX(translationX).translationY(translationY)
                    .alpha(1f).setInterpolator(interpolator).setDuration(duration).start();
        }
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        int action = event.getActionMasked();
        // This phone-only overlay owns the complete gesture. Player gestures
        // underneath must not consume its first DOWN or the stop button's tap.

        // During takeover the receiver is only a display and an input relay. Its
        // local View tree must never interpret taps or gestures intended for the
        // controller's virtual WebView.
        if (remoteCatalogUrl.length() > 0) {
            return true;
        }
        if (action == MotionEvent.ACTION_DOWN && webSourceView != null
                && webSourceView.isBrowserChromeTouch(event)) {
            // Browser tabs, address controls and the bookmark strip own the whole
            // toolbar, including spacing between controls. Do not start a player
            // tap/swipe here or the same click can open the channel list below it.
            playbackGestureTracking = false;
            playbackGestureEdgeBlocked = false;
            setRemoteInputMode(false);
            return super.dispatchTouchEvent(event);
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

    private static boolean isPointInsideView(MotionEvent event, View view) {
        Rect bounds = new Rect();
        if (!view.getGlobalVisibleRect(bounds)) return false;
        // Global visible bounds are relative to the window's root, while raw
        // touch coordinates include the screen offset (e.g. landscape cutouts).
        int[] rootLocation = new int[2];
        view.getRootView().getLocationOnScreen(rootLocation);
        bounds.offset(rootLocation[0], rootLocation[1]);
        return bounds.contains((int) event.getRawX(), (int) event.getRawY());
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (userScriptInstallOverlay != null) {
            int keyCode = normalizeRemoteKeyCode(event.getKeyCode());
            if (event.getAction() == KeyEvent.ACTION_DOWN
                    && (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_MENU)) {
                dismissUserScriptInstallOverlay(true);
                return true;
            }
            // Keep channel, playback and browser shortcuts from reaching the page
            // while the modal card is open. DPAD/ENTER still use normal View focus.
            return super.dispatchKeyEvent(event);
        }
        if (event.getKeyCode() == KeyEvent.KEYCODE_MEDIA_PREVIOUS
                || event.getKeyCode() == KeyEvent.KEYCODE_MEDIA_NEXT) {
            if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
                handleChannelMediaKey(event.getKeyCode() == KeyEvent.KEYCODE_MEDIA_NEXT ? 1 : -1);
            }
            return true;
        }

        int rawKeyCode = event.getKeyCode();
        int keyCode = normalizeRemoteKeyCode(rawKeyCode);
        boolean seekKey = keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT
                || keyCode == KeyEvent.KEYCODE_MEDIA_REWIND || keyCode == KeyEvent.KEYCODE_MEDIA_FAST_FORWARD;
        if (seekKey && managementPanel.getVisibility() != View.VISIBLE
                && channelListPanel.getVisibility() != View.VISIBLE && seekableDuration() > 0L) {
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                showPlaybackProgress(player.getCurrentPosition());
                playbackSeekOverlay.step(keyCode == KeyEvent.KEYCODE_DPAD_LEFT
                        || keyCode == KeyEvent.KEYCODE_MEDIA_REWIND ? -10000L : 10000L);
            }
            return true;
        }

        if (remoteCatalogUrl.length() > 0) {
            if (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
                if (event.getAction() == KeyEvent.ACTION_DOWN) adjustRemoteVolume(keyCode);
                return true;
            }
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
                    if (epgExpanded && (epgList.hasFocus() || favoriteActionFocused)) {
                        epgExpanded = false;
                        showEpgForBrowsingChannel(channelList.getSelectedItemPosition());
                        setFavoriteActionFocused(true);
                    } else if (favoriteActionFocused) {
                        setFavoriteActionFocused(false);
                        epgExpanded = false;
                        showEpgForBrowsingChannel(channelList.getSelectedItemPosition());
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
                    } else if (favoriteActionFocused) {
                        epgExpanded = true;
                        showEpgForBrowsingChannel(channelList.getSelectedItemPosition());
                        if (epgAdapter.getCount() == 0) return true;
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
        if (numericChannelInput.length() > 0 && (keyCode == KeyEvent.KEYCODE_DPAD_CENTER
                || keyCode == KeyEvent.KEYCODE_ENTER)) {
            commitNumericChannel();
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

    private void handleChannelMediaKey(int direction) {
        if (isFinishing()) return;
        if (remoteCatalogUrl.length() > 0) {
            boolean down = (direction > 0) != reverseUpDown;
            forwardReceiverRemoteKey(down ? KeyEvent.KEYCODE_DPAD_DOWN : KeyEvent.KEYCODE_DPAD_UP);
        } else {
            switchRelative(direction);
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
                        command.put("action", reverseUpDown ? "next" : "previous").put("type", "control");
                        if (controlServer != null
                                && controlServer.sendTakeoverSessionMessage(command)) return;
                        remoteCatalogClient.controlReceiver(hostUrl, command);
                    } else if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                        command.put("action", reverseUpDown ? "previous" : "next").put("type", "control");
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
        // Visible overlays own BACK before any playback/exit shortcut, including
        // sniffed streams whose original WebView has already been cleared.
        if (userScriptInstallOverlay != null) {
            dismissUserScriptInstallOverlay(true);
            return;
        }
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
        if (webSourceView != null && webSourceView.isPageVisible()
                && webSourceView.exitBrowserFullscreen()) return;
        if (backFromMultimedia()) return;
        if (restoreSniffedWebPage()) return;
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
        requestAppExitFromBack();
    }

    private void requestAppExitFromBack() {
        long now = SystemClock.elapsedRealtime();
        if (lastBackPressedAt > 0L && now - lastBackPressedAt <= EXIT_CONFIRM_TIMEOUT_MS) {
            finish();
            return;
        }
        lastBackPressedAt = now;
        showBackPrompt(false);
    }

    boolean returnToRetainedWebPage() {
        // Legacy bridge/API entry point shares overlay-first BACK handling.
        if (!playingDiscoveredWebStream) {
            return false;
        }
        onBackPressed();
        return true;
    }

    private boolean canReturnToSniffedPage() {
        return playingDiscoveredWebStream && playingSniffedResource != null
                && webSourceView != null && webSourceView.canRestoreAfterStreamPlayback();
    }

    private void cancelSniffedOpenTimeout() {
        if (sniffedOpenTimeout != null && channelBar != null) channelBar.removeCallbacks(sniffedOpenTimeout);
        sniffedOpenTimeout = null;
    }

    private boolean returnFromFailedSniffedPlayback(String reason) {
        if (!playingDiscoveredWebStream || playingSniffedResource == null) return false;
        cancelSniffedOpenTimeout();
        cancelCustomSourceTimeout();
        abortChannelSwitchAnimation();
        if (canReturnToSniffedPage()) {
            closeChannelList();
            closeManagementPanel();
        }
        if (restoreSniffedWebPage()) {
            Toast.makeText(this, reason + "，已返回网页，可在网页中播放或重新选择资源", Toast.LENGTH_LONG).show();
            Log.i(TAG, "Sniffed playback failed; restored retained page: " + reason);
        } else {
            // A page already drained after successful playback must not be silently reloaded.
            clearPendingPlayer();
            releasePlayer();
            hideLoading();
            showChannelBar(playingSniffedTitle, reason + "；网页已清理，请重新打开网页");
            Toast.makeText(this, reason + "；网页已清理，请重新打开网页", Toast.LENGTH_LONG).show();
        }
        return true;
    }

    private boolean restoreSniffedWebPage() {
        if (!canReturnToSniffedPage()) return false;
        cancelSniffedOpenTimeout();
        // Invalidate delayed cleanup/focus callbacks before making the document visible.
        sniffedPlaybackGeneration++;
        manualWebPlaybackRequestId = playRequestId;
        manualWebPlaybackPageKey = webSourceView.currentResourcePageKey();
        clearPendingPlayer();
        if (player != null) player.setVolume(0f, 0f);
        releasePlayer();
        playbackAudioFocusGeneration++;
        abandonPlaybackAudioFocus();
        playingDiscoveredWebStream = false;
        clearSniffedPlaybackIdentity();
        hideLoading();
        channelBar.removeCallbacks(hideChannelBar);
        channelBar.setVisibility(View.GONE);
        backPrompt.removeCallbacks(hideBackPrompt);
        backPrompt.setVisibility(View.GONE);
        lastBackPressedAt = 0L;
        clearWebCloseConfirmation();
        videoView.setVisibility(View.INVISIBLE);
        webSourceView.restoreAfterStreamPlayback();
        applyFlyMouseVisibility();
        ensureFlyMouseOnTop();
        return true;
    }

    boolean hasRetainedWebPlayback() {
        return playingDiscoveredWebStream && webSourceView != null
                && webSourceView.hasRetainedPage();
    }

    private void clearSniffedPlaybackIdentity() {
        cancelSniffedOpenTimeout();
        playingSniffedResource = null;
        playingSniffedChannelUrl = "";
        playingSniffedTitle = "";
        playingSniffedGroup = "";
        playingSniffedPageUrl = "";
    }

    @Override
    protected void onPause() {
        if (playbackSeekOverlay != null) playbackSeekOverlay.dismiss();
        dispatchFlyMouseButtonUp(true);
        // Android 9 pauses the non-focused half of a split screen even though it
        // remains visible. Keep rendering and WebView media alive until onStop.
        if (!MultiWindowCompat.isInMultiWindowMode(this)) {
            setWindowContentActive(false);
        }
        super.onPause();
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (MultiWindowCompat.isInMultiWindowMode(this)) {
            setWindowContentActive(true);
        }
    }

    @Override
    protected void onStop() {
        setWindowContentActive(false);
        super.onStop();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (Build.VERSION.SDK_INT >= 21) {
            if (channelMediaSession == null) {
                channelMediaSession = new ChannelMediaSession(this,
                        new Runnable() { @Override public void run() { handleChannelMediaKey(-1); } },
                        new Runnable() { @Override public void run() { handleChannelMediaKey(1); } });
            }
        }
        if (hasActivePlayer()) {
            requestPlaybackAudioFocus();
            applyPlaybackMuteState();
        }
        setWindowContentActive(true);
        refreshManagementAddress();
        applySystemUiVisibility();
    }

    private void setWindowContentActive(boolean active) {
        if (audioArtwork != null) audioArtwork.setActive(active);
        if (channelMediaSession != null) channelMediaSession.setActive(active);
        if (webSourceView != null) {
            if (active) webSourceView.resumePage();
            else webSourceView.pausePage();
        }
    }

    private void applyPlaybackOrientation(boolean inMultiWindowMode) {
        // API 24+ keeps the manifest orientation unspecified so split-screen can
        // resize freely. Full-screen playback still starts in landscape.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return;
        int orientation = inMultiWindowMode
                ? ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                : ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE;
        if (getRequestedOrientation() != orientation) setRequestedOrientation(orientation);
    }

    @Override
    public void onConfigurationChanged(Configuration configuration) {
        super.onConfigurationChanged(configuration);
        applySystemUiVisibility();
        if (root != null) {
            root.post(new Runnable() {
                @Override public void run() {
                    refreshUiScaleForViewport(root.getWidth(), root.getHeight(), true);
                    configurePlaybackGestureExclusion();
                    applySubtitleManualOffset();
                }
            });
        }
    }

    @android.annotation.TargetApi(Build.VERSION_CODES.N)
    @Override
    public void onMultiWindowModeChanged(boolean inMultiWindowMode,
            Configuration configuration) {
        super.onMultiWindowModeChanged(inMultiWindowMode, configuration);
        applyPlaybackOrientation(inMultiWindowMode);
        applySystemUiVisibility();
        if (inMultiWindowMode) setWindowContentActive(true);
        if (root != null) {
            root.post(new Runnable() {
                @Override public void run() {
                    refreshUiScaleForViewport(root.getWidth(), root.getHeight(), true);
                }
            });
        }
        Log.i(TAG, "Multi-window mode changed active=" + inMultiWindowMode);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (intent != null && LocalPlayerRegistry.OPEN_MANAGEMENT.equals(intent.getAction())) {
            openManagementPage();
        }
    }

    @Override public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        if (level >= TRIM_MEMORY_RUNNING_LOW) trimUiCaches();
    }

    @Override public void onLowMemory() {
        super.onLowMemory();
        trimUiCaches();
    }

    private void trimUiCaches() {
        albumArtLoader.cancelNeighbors();
        if (audioArtwork != null) audioArtwork.trimMemory();
        if (webSourceView != null) webSourceView.trimMemory();
    }

    @Override
    protected void onDestroy() {
        cancelHttpResolve();
        userScriptInstallGeneration++;
        removeUserScriptInstallOverlay();
        cancelCustomSourceTimeout();
        albumArtLoader.close();
        sniffedMediaProbe.close();
        if (sourceUrlErrorDialog != null) {
            sourceUrlErrorDialog.dismiss();
            sourceUrlErrorDialog = null;
        }
        if (channelMediaSession != null) channelMediaSession.release();
        multimediaReceiverChannel = null;
        if (multimedia != null) multimedia.close();
        if (root != null) root.removeCallbacks(deferredEpgRefresh);

        LocalPlayerRegistry.detach(this);
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
        if (castDeviceDiscovery != null) {
            castDeviceDiscovery.close();
            castDeviceDiscovery = null;
        }
        if (wifiDirectCoordinator != null) {
            wifiDirectCoordinator.close();
            wifiDirectCoordinator = null;
        }

        if (webSourceView != null) {
            webSourceView.destroyPage();
        }
        clearChannelSwitchVisuals();
        abandonPlaybackAudioFocus();
        releasePlayer();

        if (yangshipinResolver != null) {
            yangshipinResolver.destroy();
        }
        if (ku9ScriptResolver != null) {
            ku9ScriptResolver.destroy();
        }
        if (cjsSiteResolver != null) {
            cjsSiteResolver.destroy();
        }
        if (proxy != null) {
            proxy.close();
        }
        if (videoView != null) videoView.releaseOutput();
        super.onDestroy();
    }
}
