package xiao.bu.tv;

import android.Manifest;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.net.NetworkInfo;
import android.net.wifi.WpsInfo;
import android.net.wifi.p2p.WifiP2pConfig;
import android.net.wifi.p2p.WifiP2pDevice;
import android.net.wifi.p2p.WifiP2pDeviceList;
import android.net.wifi.p2p.WifiP2pGroup;
import android.net.wifi.p2p.WifiP2pInfo;
import android.net.wifi.p2p.WifiP2pManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.Closeable;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Collection;
import java.util.Collections;
import java.util.Enumeration;
import java.util.Locale;

/**
 * Establishes an optional Wi-Fi Direct route before the existing takeover protocol starts.
 * Discovery only runs for a pending takeover; media/control never poll this class afterward.
 */
final class WifiDirectCoordinator implements Closeable {
    interface PermissionDelegate {
        void requestWifiDirectPermission();
    }

    static final class Route {
        final String receiverUrl;
        final String controllerUrl;

        Route(String receiverUrl, String controllerUrl) {
            this.receiverUrl = receiverUrl;
            this.controllerUrl = controllerUrl;
        }
    }

    private static final String TAG = "WifiDirect";
    private static final String STATE_UNAVAILABLE = "unavailable";
    private static final String STATE_IDLE = "idle";
    private static final String STATE_PERMISSION = "permission";
    private static final String STATE_PREPARING = "preparing";
    private static final String STATE_DISCOVERING = "discovering";
    private static final String STATE_CONNECTING = "connecting";
    private static final String STATE_CONNECTED = "connected";
    private static final String STATE_FAILED = "failed";

    private final Activity activity;
    private final PermissionDelegate permissionDelegate;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Object signal = new Object();
    private final WifiP2pManager manager;
    private final WifiP2pManager.Channel channel;
    private final boolean supported;
    private boolean registered;
    private boolean receiverPrepareRequested;
    private boolean receiverGroupCreationIssued;
    private boolean receiverControllerGroupOwner;
    private String receiverControllerDeviceAddress = "";
    private String receiverControllerDeviceName = "";
    private int connectGroupOwnerIntent = 15;
    private boolean connectIssued;
    private volatile boolean controllerGroupResetComplete;
    private volatile int operationGeneration;
    private volatile boolean enabled = true;
    private volatile String state;
    private volatile String detail = "";
    private volatile String ownDeviceAddress = "";
    private volatile String ownDeviceName = "";
    private volatile String wantedDeviceAddress = "";
    private volatile WifiP2pInfo connectionInfo;
    private volatile WifiP2pGroup groupInfo;
    private volatile String localAddress = "";
    private final Runnable receiverDiscoveryPulse = new Runnable() {
        @Override public void run() {
            WifiP2pInfo info = connectionInfo;
            if (!receiverPrepareRequested || !supported
                    || (info != null && info.groupFormed)) return;
            // Do not restart an active scan here. Android 7 can label a freshly
            // observed group as an old result when discoverPeers is repeatedly
            // issued, then never deliver that group to requestPeers().
            requestPeers();
            mainHandler.postDelayed(this, 500L);
        }
    };
    private final Runnable receiverGroupFallback = new Runnable() {
        @Override public void run() {
            WifiP2pInfo info = connectionInfo;
            if (!receiverPrepareRequested || receiverGroupCreationIssued || !supported
                    || (info != null && info.groupFormed)) return;
            receiverGroupCreationIssued = true;
            mainHandler.removeCallbacks(receiverDiscoveryPulse);
            try {
                manager.createGroup(channel, new WifiP2pManager.ActionListener() {
                    @Override public void onSuccess() {
                        requestConnectionInfo();
                        requestGroupInfo();
                    }

                    @Override public void onFailure(int reason) {
                        requestConnectionInfo();
                        requestGroupInfo();
                        if (reason != WifiP2pManager.BUSY) {
                            setState(STATE_FAILED, failureText("电视建组失败", reason));
                        }
                    }
                });
            } catch (RuntimeException error) {
                setState(STATE_FAILED, "电视建组失败，继续使用局域网");
            }
        }
    };

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            String action = intent == null ? "" : intent.getAction();
            if (WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION.equals(action)) {
                enabled = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE,
                        WifiP2pManager.WIFI_P2P_STATE_DISABLED)
                        == WifiP2pManager.WIFI_P2P_STATE_ENABLED;
                if (!enabled) setState(STATE_UNAVAILABLE, "Wi-Fi Direct 未开启");
            } else if (WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION.equals(action)) {
                WifiP2pDevice device = intent.getParcelableExtra(
                        WifiP2pManager.EXTRA_WIFI_P2P_DEVICE);
                rememberOwnDevice(device);
            } else if (WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION.equals(action)) {
                requestPeers();
            } else if (WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION.equals(action)) {
                NetworkInfo network = intent.getParcelableExtra(
                        WifiP2pManager.EXTRA_NETWORK_INFO);
                if (network == null || network.isConnected()) {
                    requestConnectionInfo();
                    requestGroupInfo();
                } else {
                    connectionInfo = null;
                    groupInfo = null;
                    localAddress = "";
                    if (STATE_CONNECTED.equals(state)) setState(STATE_IDLE, "");
                }
            }
        }
    };

    WifiDirectCoordinator(Activity activity, PermissionDelegate permissionDelegate) {
        this.activity = activity;
        this.permissionDelegate = permissionDelegate;
        WifiP2pManager service = (WifiP2pManager) activity
                .getSystemService(Context.WIFI_P2P_SERVICE);
        supported = service != null && activity.getPackageManager().hasSystemFeature(
                PackageManager.FEATURE_WIFI_DIRECT);
        manager = supported ? service : null;
        channel = supported ? manager.initialize(activity, Looper.getMainLooper(),
                new WifiP2pManager.ChannelListener() {
                    @Override public void onChannelDisconnected() {
                        setState(STATE_FAILED, "Wi-Fi Direct 通道已断开");
                    }
                }) : null;
        state = supported ? STATE_IDLE : STATE_UNAVAILABLE;
        if (supported) register();
    }

    boolean isSupported() {
        return supported;
    }

    boolean isEnabled() {
        return enabled;
    }

    boolean hasPermission() {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.M
                || activity.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                        == PackageManager.PERMISSION_GRANTED;
    }

    /** Receiver side: become discoverable and publish the exact device address. */
    JSONObject prepareReceiver(boolean controllerGroupOwner, String controllerDeviceAddress,
            String controllerDeviceName) {
        receiverPrepareRequested = true;
        receiverGroupCreationIssued = false;
        receiverControllerGroupOwner = controllerGroupOwner;
        receiverControllerDeviceAddress = normalizeAddress(controllerDeviceAddress);
        receiverControllerDeviceName = normalizeDeviceName(controllerDeviceName);
        wantedDeviceAddress = receiverControllerDeviceAddress;
        connectIssued = false;
        connectGroupOwnerIntent = controllerGroupOwner ? 0 : 15;
        mainHandler.removeCallbacks(receiverGroupFallback);
        if (!supported || !enabled) return stateJson();
        if (!hasPermission()) {
            setState(STATE_PERMISSION, "请在电视上允许附近设备发现权限");
            mainHandler.post(new Runnable() {
                @Override public void run() {
                    permissionDelegate.requestWifiDirectPermission();
                }
            });
            return stateJson();
        }
        requestGroupInfo();
        WifiP2pInfo current = connectionInfo;
        if (!controllerGroupOwner && current != null
                && current.groupFormed && current.isGroupOwner
                && ownDeviceAddress.length() > 0) {
            setState(STATE_CONNECTED, "电视已建立 Wi-Fi Direct 通道");
            return stateJson();
        }
        setState(STATE_PREPARING, "电视正在等待 Wi-Fi Direct 连接");
        mainHandler.post(new Runnable() {
            @Override public void run() {
                try {
                    // Both peers must participate in discovery on older Android.
                    // Creating an autonomous group first makes some Android 7
                    // devices disappear from an Android 17 peer scan.
                    // Old Android keeps discovery marked active for hours and a
                    // second discoverPeers call may return success without a new
                    // scan. Stop the stale session so the phone's new group appears.
                    restartPeerDiscovery();
                    mainHandler.removeCallbacks(receiverDiscoveryPulse);
                    mainHandler.postDelayed(receiverDiscoveryPulse, 350L);
                    if (receiverControllerGroupOwner
                            && receiverControllerDeviceAddress.length() > 0) {
                        // The controller publishes its p2p0 hardware address over
                        // the already-authenticated LAN request. Connecting by
                        // address skips the slow/occasionally stale Android 7 scan.
                        mainHandler.postDelayed(new Runnable() {
                            @Override public void run() {
                                connectTo(receiverControllerDeviceAddress);
                            }
                        }, 180L);
                    }
                    mainHandler.removeCallbacks(receiverGroupFallback);
                    if (!controllerGroupOwner) {
                        mainHandler.postDelayed(receiverGroupFallback, 8000L);
                    }
                } catch (SecurityException error) {
                    setState(STATE_PERMISSION, "电视缺少附近设备发现权限");
                }
            }
        });
        return stateJson();
    }

    void onPermissionResult(boolean granted) {
        if (!granted) {
            receiverPrepareRequested = false;
            setState(STATE_FAILED, "未允许附近设备发现，继续使用局域网");
        } else if (receiverPrepareRequested) {
            prepareReceiver(receiverControllerGroupOwner, receiverControllerDeviceAddress,
                    receiverControllerDeviceName);
        }
    }

    /** Publish this controller's Direct address before asking the receiver to
     * discover it. This lets older receivers initiate the negotiation when the
     * modern phone's peer scan misses them. */
    String controllerDeviceAddress(long timeoutMs) {
        if (!supported || !enabled || !hasPermission()) return "";
        String interfaceAddress = localP2pHardwareAddress();
        if (interfaceAddress.length() > 0) {
            ownDeviceAddress = interfaceAddress;
            return interfaceAddress;
        }
        if (ownDeviceAddress.length() > 0) return ownDeviceAddress;
        requestOwnDeviceInfo();
        discoverPeersAgain();
        requestGroupInfo();
        long deadline = SystemClock.elapsedRealtime() + Math.max(0L, timeoutMs);
        while (ownDeviceAddress.length() == 0 && SystemClock.elapsedRealtime() < deadline) {
            synchronized (signal) {
                try {
                    signal.wait(Math.min(250L, Math.max(1L,
                            deadline - SystemClock.elapsedRealtime())));
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return "";
                }
            }
        }
        return ownDeviceAddress;
    }

    String controllerDeviceName() {
        if (ownDeviceName.length() > 0) return ownDeviceName;
        requestOwnDeviceInfo();
        requestGroupInfo();
        return ownDeviceName.length() > 0 ? ownDeviceName : Build.MODEL;
    }

    /** Controller side. This method must run off the main thread. */
    Route connect(String deviceAddress, int receiverPort, int controllerPort,
            long timeoutMs) {
        if (!supported || !enabled || !hasPermission()) {
            return null;
        }
        // Form the group on the phone first. This guarantees the phone owns the
        // stable 192.168.49.1 endpoint even when an old receiver has a persistent
        // group whose saved role would otherwise override groupOwnerIntent.
        wantedDeviceAddress = "";
        connectIssued = true;
        connectGroupOwnerIntent = 15;
        connectionInfo = null;
        controllerGroupResetComplete = false;
        final int generation = ++operationGeneration;
        final String targetAddress = normalizeAddress(deviceAddress);
        setState(STATE_CONNECTING, "手机正在建立 Wi-Fi Direct 通道");
        mainHandler.post(new Runnable() {
            @Override public void run() {
                try {
                    // A stale persistent group is the common cause of BUSY and
                    // role reversal. Remove it before asking the phone to be owner.
                    manager.removeGroup(channel, new WifiP2pManager.ActionListener() {
                        @Override public void onSuccess() {
                            scheduleControllerConnect(generation, targetAddress, 120L);
                        }
                        @Override public void onFailure(int reason) {
                            scheduleControllerConnect(generation, targetAddress, 80L);
                        }
                    });
                } catch (SecurityException error) {
                    setState(STATE_PERMISSION, "缺少附近设备发现权限");
                } catch (RuntimeException error) {
                    scheduleControllerConnect(generation, targetAddress, 0L);
                }
            }
        });

        long deadline = SystemClock.elapsedRealtime() + Math.max(1000L, timeoutMs);
        while (SystemClock.elapsedRealtime() < deadline) {
            WifiP2pInfo info = connectionInfo;
            if (controllerGroupResetComplete && info != null
                    && info.groupFormed && info.isGroupOwner) {
                String localAddress = localP2pIpv4(info.groupOwnerAddress);
                if (localAddress.length() > 0) {
                    setState(STATE_CONNECTED, "投屏正在使用 Wi-Fi Direct");
                    // The television reports its DHCP client address over the
                    // original LAN after joining; the phone already knows its
                    // own stable P2P owner address here.
                    return new Route("", url(localAddress, controllerPort));
                }
            }
            requestConnectionInfo();
            synchronized (signal) {
                try {
                    signal.wait(Math.min(350L, Math.max(1L,
                            deadline - SystemClock.elapsedRealtime())));
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return null;
                }
            }
        }
        setState(STATE_FAILED, "Wi-Fi Direct 连接超时，继续使用局域网");
        return null;
    }

    private void scheduleControllerConnect(final int generation, final String deviceAddress,
            long delayMs) {
        if (deviceAddress.length() == 0) {
            scheduleControllerGroupCreate(generation, delayMs, 0);
            return;
        }
        mainHandler.postDelayed(new Runnable() {
            @Override public void run() {
                if (generation != operationGeneration || !connectIssued) return;
                connectionInfo = null;
                groupInfo = null;
                controllerGroupResetComplete = true;
                WifiP2pConfig config = new WifiP2pConfig();
                config.deviceAddress = deviceAddress;
                config.wps.setup = WpsInfo.PBC;
                config.groupOwnerIntent = 15;
                try {
                    // The receiver publishes its real P2P address over LAN. An
                    // addressed negotiation is much faster and more reliable than
                    // waiting for an old Android peer scan to notice an autonomous group.
                    manager.connect(channel, config, new WifiP2pManager.ActionListener() {
                        @Override public void onSuccess() {
                            requestConnectionInfo();
                            requestGroupInfo();
                        }

                        @Override public void onFailure(int reason) {
                            if (generation != operationGeneration) return;
                            scheduleControllerGroupCreate(generation, 250L, 0);
                        }
                    });
                } catch (SecurityException error) {
                    setState(STATE_PERMISSION, "缺少附近设备发现权限");
                } catch (RuntimeException error) {
                    scheduleControllerGroupCreate(generation, 0L, 0);
                }
            }
        }, Math.max(0L, delayMs));
    }

    private void scheduleControllerGroupCreate(final int generation, long delayMs,
            final int retry) {
        mainHandler.postDelayed(new Runnable() {
            @Override public void run() {
                if (generation != operationGeneration || !connectIssued) return;
                connectionInfo = null;
                groupInfo = null;
                controllerGroupResetComplete = true;
                try {
                    manager.createGroup(channel, new WifiP2pManager.ActionListener() {
                        @Override public void onSuccess() {
                            requestConnectionInfo();
                            requestGroupInfo();
                        }

                        @Override public void onFailure(int reason) {
                            requestConnectionInfo();
                            requestGroupInfo();
                            if (reason == WifiP2pManager.BUSY && retry < 1) {
                                scheduleControllerGroupCreate(generation, 450L, retry + 1);
                            } else if (reason != WifiP2pManager.BUSY) {
                                setState(STATE_FAILED,
                                        failureText("手机建立直连组失败", reason));
                            }
                        }
                    });
                } catch (SecurityException error) {
                    setState(STATE_PERMISSION, "缺少附近设备发现权限");
                } catch (RuntimeException error) {
                    setState(STATE_FAILED, "手机建立直连组失败，继续使用局域网");
                }
            }
        }, Math.max(0L, delayMs));
    }

    JSONObject stateJson() {
        JSONObject result = new JSONObject();
        try {
            result.put("supported", supported);
            result.put("enabled", enabled);
            result.put("permissionGranted", hasPermission());
            result.put("state", state);
            result.put("message", detail);
            result.put("deviceAddress", ownDeviceAddress);
            result.put("deviceName", ownDeviceName);
            WifiP2pInfo info = connectionInfo;
            result.put("groupFormed", info != null && info.groupFormed);
            result.put("groupOwner", info != null && info.isGroupOwner);
            result.put("groupOwnerAddress", info == null || info.groupOwnerAddress == null
                    ? "" : info.groupOwnerAddress.getHostAddress());
            result.put("localAddress", localAddress);
        } catch (JSONException ignored) {
        }
        return result;
    }

    String localAddress() {
        String value = localAddress;
        return value == null ? "" : value;
    }

    void removeGroup() {
        operationGeneration++;
        receiverPrepareRequested = false;
        receiverGroupCreationIssued = false;
        receiverControllerGroupOwner = false;
        receiverControllerDeviceAddress = "";
        receiverControllerDeviceName = "";
        connectGroupOwnerIntent = 15;
        mainHandler.removeCallbacks(receiverDiscoveryPulse);
        mainHandler.removeCallbacks(receiverGroupFallback);
        wantedDeviceAddress = "";
        connectIssued = false;
        controllerGroupResetComplete = false;
        connectionInfo = null;
        groupInfo = null;
        localAddress = "";
        if (!supported) return;
        mainHandler.post(new Runnable() {
            @Override public void run() {
                try {
                    manager.removeGroup(channel, new WifiP2pManager.ActionListener() {
                        @Override public void onSuccess() {
                            setState(STATE_IDLE, "");
                        }

                        @Override public void onFailure(int reason) {
                            setState(STATE_IDLE, "");
                        }
                    });
                } catch (RuntimeException ignored) {
                    setState(STATE_IDLE, "");
                }
            }
        });
    }

    private void register() {
        if (registered) return;
        IntentFilter filter = new IntentFilter();
        filter.addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION);
        filter.addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION);
        filter.addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION);
        filter.addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION);
        activity.registerReceiver(receiver, filter);
        registered = true;
        requestOwnDeviceInfo();
        requestConnectionInfo();
        requestGroupInfo();
    }

    private void requestPeers() {
        if (!supported || !hasPermission()) return;
        mainHandler.post(new Runnable() {
            @Override public void run() {
                try {
                    manager.requestPeers(channel, new WifiP2pManager.PeerListListener() {
                        @Override public void onPeersAvailable(WifiP2pDeviceList list) {
                            Collection<WifiP2pDevice> devices = list == null
                                    ? Collections.<WifiP2pDevice>emptyList()
                                    : list.getDeviceList();
                            for (WifiP2pDevice device : devices) {
                                boolean exactController = wantedDeviceAddress.length() > 0
                                        && wantedDeviceAddress.equals(normalizeAddress(
                                                device.deviceAddress));
                                String peerName = normalizeDeviceName(device.deviceName);
                                boolean visibleControllerGroup = wantedDeviceAddress.length() == 0
                                        && receiverControllerGroupOwner && device.isGroupOwner()
                                        && peerName.length() > 0
                                        && receiverControllerDeviceName.length() > 0
                                        && (peerName.equals(receiverControllerDeviceName)
                                                || peerName.contains(receiverControllerDeviceName)
                                                || receiverControllerDeviceName.contains(peerName));
                                if (exactController || visibleControllerGroup) {
                                    connectTo(device.deviceAddress);
                                    break;
                                }
                            }
                            wakeWaiters();
                        }
                    });
                } catch (SecurityException error) {
                    setState(STATE_PERMISSION, "缺少附近设备发现权限");
                }
            }
        });
    }

    private void discoverPeersAgain() {
        if (!supported || !hasPermission()) return;
        mainHandler.post(new Runnable() {
            @Override public void run() {
                try {
                    manager.discoverPeers(channel, new WifiP2pManager.ActionListener() {
                        @Override public void onSuccess() {
                            requestPeers();
                        }

                        @Override public void onFailure(int reason) {
                            Log.d(TAG, "Peer discovery retry failed: " + reason);
                        }
                    });
                } catch (RuntimeException error) {
                    Log.d(TAG, "Unable to retry peer discovery", error);
                }
            }
        });
    }

    private void restartPeerDiscovery() {
        if (!supported || !hasPermission()) return;
        mainHandler.post(new Runnable() {
            @Override public void run() {
                try {
                    manager.stopPeerDiscovery(channel, new WifiP2pManager.ActionListener() {
                        @Override public void onSuccess() {
                            mainHandler.postDelayed(new Runnable() {
                                @Override public void run() { discoverPeersAgain(); }
                            }, 100L);
                        }

                        @Override public void onFailure(int reason) {
                            discoverPeersAgain();
                        }
                    });
                } catch (RuntimeException error) {
                    discoverPeersAgain();
                }
            }
        });
    }

    private void connectTo(final String deviceAddress) {
        if (connectIssued) return;
        connectIssued = true;
        setState(STATE_CONNECTING, "正在建立电视直连通道");
        WifiP2pConfig config = new WifiP2pConfig();
        config.deviceAddress = deviceAddress;
        config.wps.setup = WpsInfo.PBC;
        // The controller is normally the phone. Prefer it as group owner so its
        // management and RTSP addresses stay stable at the owner endpoint.
        config.groupOwnerIntent = connectGroupOwnerIntent;
        try {
            manager.connect(channel, config, new WifiP2pManager.ActionListener() {
                @Override public void onSuccess() {
                    requestConnectionInfo();
                }

                @Override public void onFailure(int reason) {
                    connectIssued = false;
                    setState(STATE_FAILED, failureText("连接电视失败", reason));
                }
            });
        } catch (SecurityException error) {
            connectIssued = false;
            setState(STATE_PERMISSION, "缺少附近设备发现权限");
        }
    }

    private void requestConnectionInfo() {
        if (!supported) return;
        mainHandler.post(new Runnable() {
            @Override public void run() {
                try {
                    manager.requestConnectionInfo(channel,
                            new WifiP2pManager.ConnectionInfoListener() {
                        @Override public void onConnectionInfoAvailable(WifiP2pInfo info) {
                            connectionInfo = info;
                            localAddress = info != null && info.groupFormed
                                    ? localP2pIpv4(info.groupOwnerAddress) : "";
                            if (info != null && info.groupFormed) {
                                mainHandler.removeCallbacks(receiverDiscoveryPulse);
                                mainHandler.removeCallbacks(receiverGroupFallback);
                                setState(STATE_CONNECTED, info.isGroupOwner
                                        ? "本机已成为 Wi-Fi Direct 组主"
                                        : "投屏正在使用 Wi-Fi Direct");
                                wakeWaiters();
                            }
                        }
                    });
                } catch (RuntimeException error) {
                    Log.d(TAG, "Unable to read connection info", error);
                }
            }
        });
    }

    private void requestGroupInfo() {
        if (!supported) return;
        mainHandler.post(new Runnable() {
            @Override public void run() {
                try {
                    manager.requestGroupInfo(channel, new WifiP2pManager.GroupInfoListener() {
                        @Override public void onGroupInfoAvailable(WifiP2pGroup group) {
                            groupInfo = group;
                            if (group != null && group.isGroupOwner()) {
                                rememberOwnDevice(group.getOwner());
                            }
                            wakeWaiters();
                        }
                    });
                } catch (RuntimeException error) {
                    Log.d(TAG, "Unable to read group info", error);
                }
            }
        });
    }

    @android.annotation.SuppressLint("NewApi")
    private void requestOwnDeviceInfo() {
        if (!supported || Build.VERSION.SDK_INT < 29 || !hasPermission()) return;
        mainHandler.post(new Runnable() {
            @Override public void run() {
                try {
                    manager.requestDeviceInfo(channel,
                            new WifiP2pManager.DeviceInfoListener() {
                        @Override public void onDeviceInfoAvailable(WifiP2pDevice device) {
                            rememberOwnDevice(device);
                        }
                    });
                } catch (RuntimeException error) {
                    Log.d(TAG, "Unable to read local Direct device", error);
                }
            }
        });
    }

    private void rememberOwnDevice(WifiP2pDevice device) {
        if (device == null) return;
        String deviceName = normalizeDeviceName(device.deviceName);
        if (deviceName.length() > 0) ownDeviceName = deviceName;
        if (device.deviceAddress == null) return;
        String address = device.deviceAddress.trim();
        if (address.length() > 0 && !"02:00:00:00:00:00".equals(address)) {
            ownDeviceAddress = address;
            wakeWaiters();
        }
    }

    private void setState(String value, String message) {
        if (!value.equals(state) || !(message == null ? "" : message).equals(detail)) {
            Log.i(TAG, value + (message == null || message.length() == 0
                    ? "" : ": " + message));
        }
        state = value;
        detail = message == null ? "" : message;
        wakeWaiters();
    }

    private void wakeWaiters() {
        synchronized (signal) {
            signal.notifyAll();
        }
    }

    private static String localP2pIpv4(InetAddress owner) {
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            if (interfaces == null) return "";
            String fallback = "";
            String matchingInterface = "";
            for (NetworkInterface network : Collections.list(interfaces)) {
                String name = network.getName() == null ? ""
                        : network.getName().toLowerCase(Locale.US);
                if (!network.isUp() || network.isLoopback()) continue;
                for (InetAddress address : Collections.list(network.getInetAddresses())) {
                    if (!(address instanceof Inet4Address) || address.isLoopbackAddress()) continue;
                    String value = address.getHostAddress();
                    if (sameIpv4Prefix(address, owner)) {
                        if (name.contains("p2p")) return value;
                        matchingInterface = value;
                    }
                    if (name.contains("p2p") && fallback.length() == 0) fallback = value;
                }
            }
            return matchingInterface.length() > 0 ? matchingInterface : fallback;
        } catch (Exception error) {
            Log.d(TAG, "Unable to find P2P interface address", error);
            return "";
        }
    }

    /** Android 13+ redacts WifiP2pDevice.deviceAddress even after permission is
     * granted. The active p2p interface still exposes the address needed by the
     * already-authorized receiver for an addressed reconnect. */
    private static String localP2pHardwareAddress() {
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            if (interfaces == null) return "";
            for (NetworkInterface network : Collections.list(interfaces)) {
                String name = network.getName() == null ? ""
                        : network.getName().toLowerCase(Locale.US);
                if (!network.isUp() || !name.contains("p2p")) continue;
                byte[] address = network.getHardwareAddress();
                if (address == null || address.length != 6) continue;
                StringBuilder value = new StringBuilder(17);
                for (int i = 0; i < address.length; i++) {
                    if (i > 0) value.append(':');
                    value.append(String.format(Locale.US, "%02x", address[i] & 0xff));
                }
                String result = normalizeAddress(value.toString());
                if (!"02:00:00:00:00:00".equals(result)) return result;
            }
        } catch (Exception error) {
            Log.d(TAG, "Unable to read P2P interface address", error);
        }
        return "";
    }

    private static boolean sameIpv4Prefix(InetAddress left, InetAddress right) {
        if (left == null || right == null) return false;
        byte[] a = left.getAddress();
        byte[] b = right.getAddress();
        return a.length == 4 && b.length == 4
                && a[0] == b[0] && a[1] == b[1] && a[2] == b[2];
    }

    private static String normalizeAddress(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.US);
    }

    private static String normalizeDeviceName(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.US);
    }

    private static String url(String address, int port) {
        return String.format(Locale.US, "http://%s:%d", address, port);
    }

    private static String failureText(String prefix, int reason) {
        String suffix = reason == WifiP2pManager.P2P_UNSUPPORTED ? "设备不支持"
                : reason == WifiP2pManager.BUSY ? "系统正忙"
                : "系统错误 " + reason;
        return prefix + "（" + suffix + "），继续使用局域网";
    }

    @Override public void close() {
        removeGroup();
        if (registered) {
            try {
                activity.unregisterReceiver(receiver);
            } catch (RuntimeException ignored) {
            }
            registered = false;
        }
    }
}
