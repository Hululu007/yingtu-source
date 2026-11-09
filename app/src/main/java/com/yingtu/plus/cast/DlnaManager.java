package com.yingtu.plus.cast;

import android.content.ComponentName;
import android.content.Context;
import android.content.ServiceConnection;
import android.net.wifi.WifiManager;
import android.os.IBinder;
import android.text.TextUtils;

import com.yingtu.plus.App;
import com.yingtu.plus.bean.CastVideo;
import com.yingtu.plus.bean.Device;

import org.jupnp.android.AndroidUpnpService;
import org.jupnp.android.AndroidUpnpServiceImpl;
import org.jupnp.model.action.ActionInvocation;
import org.jupnp.model.message.UpnpResponse;
import org.jupnp.model.message.header.STAllHeader;
import org.jupnp.model.meta.RemoteDevice;
import org.jupnp.model.meta.Service;
import org.jupnp.model.types.UDAServiceId;
import org.jupnp.registry.DefaultRegistryListener;
import org.jupnp.registry.Registry;
import org.jupnp.support.avtransport.callback.Play;
import org.jupnp.support.avtransport.callback.Seek;
import org.jupnp.support.avtransport.callback.SetAVTransportURI;
import org.jupnp.support.avtransport.callback.Stop;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * 真 DLNA 投屏：基于 jupnp（build.gradle 已声明 org.jupnp:org.jupnp:2.6.1）。
 *
 * 做法参照 TVBoxOSC 的 DLNA 路子，只参照结构、不拷代码：
 * 绑定 AndroidUpnpServiceImpl → RegistryListener 发现 MediaRenderer →
 * AVTransport.SetAVTransportURI + Play 推流，带进度 Seek。
 *
 * 发现前树里只有 Device.get(RemoteDevice) 一个转换方法、没有任何
 * UpnpService 调用：CastDialog 列的只是数据库里配对过的 App 设备，
 * DLNA 渲染器一个都发现不了。本类把这条链补全。
 *
 * 注意两条硬限制（不是 bug，是 DLNA 协议本身）：
 * 1. 播放地址里的 Referer / Cookie 等请求头推不过去，AVTransport 只认
 *    一条 URL；需要 Referer 鉴权的站源在电视上可能播不了。
 * 2. 127.0.0.1 本地代理地址电视访问不到，推之前把回环地址换成本机
 *    局域网 IP（端口不变）。
 */
public class DlnaManager {

    public interface Listener {
        void onDevicesChanged(List<Device> devices);
    }

    public interface Callback {
        void onResult(boolean ok, String message);
    }

    private static volatile DlnaManager instance;

    public static DlnaManager get() {
        if (instance == null) {
            synchronized (DlnaManager.class) {
                if (instance == null) instance = new DlnaManager();
            }
        }
        return instance;
    }

    private final Map<String, RemoteDevice> renderers = new LinkedHashMap<>();
    private final Set<Listener> listeners = new CopyOnWriteArraySet<>();

    private AndroidUpnpService upnpService;
    private ServiceConnection connection;
    private WifiManager.MulticastLock multicastLock;
    private boolean bound;
    private boolean discovering;
    private Device castingDevice;
    private String castingUrl;

    private final DefaultRegistryListener registryListener = new DefaultRegistryListener() {
        @Override
        public void remoteDeviceAdded(Registry registry, RemoteDevice device) {
            if (isRenderer(device)) onRendererFound(device);
        }

        @Override
        public void remoteDeviceUpdated(Registry registry, RemoteDevice device) {
            if (isRenderer(device)) onRendererFound(device);
        }

        @Override
        public void remoteDeviceRemoved(Registry registry, RemoteDevice device) {
            if (device == null || device.getIdentity() == null) return;
            String udn = device.getIdentity().getUdn().getIdentifierString();
            synchronized (renderers) {
                if (renderers.remove(udn) != null) notifyChanged();
            }
        }
    };

    private DlnaManager() {
    }

    public void addListener(Listener listener) {
        if (listener != null) listeners.add(listener);
    }

    public void removeListener(Listener listener) {
        if (listener != null) listeners.remove(listener);
    }

    public boolean isDiscovering() {
        return discovering;
    }

    public boolean isCasting() {
        return castingDevice != null;
    }

    public Device getCastingDevice() {
        return castingDevice;
    }

    public List<Device> getDevices() {
        List<Device> devices = new ArrayList<>();
        synchronized (renderers) {
            for (RemoteDevice remote : renderers.values()) {
                try {
                    devices.add(Device.get(remote));
                } catch (Exception ignored) {
                }
            }
        }
        return devices;
    }

    /** 开始发现：绑定 jupnp 服务、注册监听、发一次全网 SSDP 搜索。 */
    public void startDiscovery() {
        if (bound && upnpService != null) {
            search();
            return;
        }
        if (bound) return;
        try {
            acquireMulticastLock();
            connection = new ServiceConnection() {
                @Override
                public void onServiceConnected(ComponentName name, IBinder binder) {
                    try {
                        upnpService = ((AndroidUpnpServiceImpl.LocalBinder) binder).getService();
                        upnpService.getRegistry().addListener(registryListener);
                        search();
                    } catch (Exception e) {
                        bound = false;
                    }
                }

                @Override
                public void onServiceDisconnected(ComponentName name) {
                    upnpService = null;
                    bound = false;
                    discovering = false;
                }
            };
            android.content.Intent intent = new android.content.Intent(App.get(), AndroidUpnpServiceImpl.class);
            bound = App.get().bindService(intent, connection, Context.BIND_AUTO_CREATE);
            if (!bound) releaseMulticastLock();
        } catch (Exception e) {
            bound = false;
            releaseMulticastLock();
        }
    }

    /** 停止发现：投屏进行中不断开（停投屏还要用 AVTransport），只停搜索。 */
    public void stopDiscovery() {
        discovering = false;
        if (isCasting()) return;
        unbind();
    }

    /** 推流：SetAVTransportURI → Play → 有进度就 Seek 到该位置。 */
    public void play(Device device, CastVideo video, Callback callback) {
        if (device == null || video == null || TextUtils.isEmpty(video.getUrl())) {
            postResult(callback, false, "没有可推的播放地址");
            return;
        }
        RemoteDevice remote;
        synchronized (renderers) {
            remote = renderers.get(device.getUuid());
        }
        if (remote == null) {
            postResult(callback, false, "设备已离开局域网");
            return;
        }
        Service avTransport = remote.findService(new UDAServiceId("AVTransport"));
        if (avTransport == null) {
            postResult(callback, false, "设备不支持投屏播放");
            return;
        }
        if (upnpService == null) {
            postResult(callback, false, "投屏服务未就绪");
            return;
        }
        String url = rewriteLocalUrl(video.getUrl());
        String title = TextUtils.isEmpty(video.getName()) ? "影图" : video.getName();
        String metadata = buildMetadata(title, url);
        long position = Math.max(0, video.getPosition());
        try {
            upnpService.getControlPoint().execute(new SetAVTransportURI(avTransport, url, metadata) {
                @Override
                public void success(ActionInvocation invocation) {
                    upnpService.getControlPoint().execute(new Play(avTransport) {
                        @Override
                        public void success(ActionInvocation invocation) {
                            castingDevice = device;
                            castingUrl = url;
                            if (position > 1000) {
                                upnpService.getControlPoint().execute(new Seek(avTransport, "REL_TIME", formatTime(position)) {
                                    @Override
                                    public void success(ActionInvocation invocation) {
                                        postResult(callback, true, device.getName());
                                    }

                                    @Override
                                    public void failure(ActionInvocation invocation, UpnpResponse operation, String defaultMsg) {
                                        postResult(callback, true, device.getName());
                                    }
                                });
                            } else {
                                postResult(callback, true, device.getName());
                            }
                        }

                        @Override
                        public void failure(ActionInvocation invocation, UpnpResponse operation, String defaultMsg) {
                            postResult(callback, false, defaultMsg == null ? "电视拒绝播放" : defaultMsg);
                        }
                    });
                }

                @Override
                public void failure(ActionInvocation invocation, UpnpResponse operation, String defaultMsg) {
                    postResult(callback, false, defaultMsg == null ? "推送地址失败" : defaultMsg);
                }
            });
        } catch (Exception e) {
            postResult(callback, false, e.getMessage() == null ? "推送失败" : e.getMessage());
        }
    }

    /** 停投屏：AVTransport.Stop，电视停播。 */
    public void stop(Callback callback) {
        Device device = castingDevice;
        castingDevice = null;
        castingUrl = null;
        if (device == null) {
            postResult(callback, true, "");
            return;
        }
        RemoteDevice remote;
        synchronized (renderers) {
            remote = renderers.get(device.getUuid());
        }
        if (remote == null || upnpService == null) {
            postResult(callback, true, "");
            return;
        }
        Service avTransport = remote.findService(new UDAServiceId("AVTransport"));
        if (avTransport == null) {
            postResult(callback, true, "");
            return;
        }
        try {
            upnpService.getControlPoint().execute(new Stop(avTransport) {
                @Override
                public void success(ActionInvocation invocation) {
                    postResult(callback, true, "");
                }

                @Override
                public void failure(ActionInvocation invocation, UpnpResponse operation, String defaultMsg) {
                    postResult(callback, true, "");
                }
            });
        } catch (Exception e) {
            postResult(callback, true, "");
        }
    }

    public String getCastingUrl() {
        return castingUrl;
    }

    private void search() {
        if (upnpService == null) return;
        try {
            discovering = true;
            upnpService.getControlPoint().search(new STAllHeader());
        } catch (Exception ignored) {
            discovering = false;
        }
    }

    private void onRendererFound(RemoteDevice device) {
        if (device == null || device.getIdentity() == null) return;
        String udn = device.getIdentity().getUdn().getIdentifierString();
        synchronized (renderers) {
            renderers.put(udn, device);
        }
        try {
            Device.get(device).save();
        } catch (Exception ignored) {
        }
        notifyChanged();
    }

    private void notifyChanged() {
        List<Device> snapshot = getDevices();
        App.post(() -> {
            for (Listener listener : listeners) {
                try {
                    listener.onDevicesChanged(snapshot);
                } catch (Exception ignored) {
                }
            }
        });
    }

    private void postResult(Callback callback, boolean ok, String message) {
        if (callback == null) return;
        App.post(() -> callback.onResult(ok, message));
    }

    private boolean isRenderer(RemoteDevice device) {
        try {
            if (device == null || device.getType() == null) return false;
            if (!"MediaRenderer".equals(device.getType().getType())) return false;
            return device.findService(new UDAServiceId("AVTransport")) != null;
        } catch (Exception e) {
            return false;
        }
    }

    private void unbind() {
        if (!bound) return;
        bound = false;
        discovering = false;
        try {
            if (upnpService != null) upnpService.getRegistry().removeListener(registryListener);
        } catch (Exception ignored) {
        }
        upnpService = null;
        try {
            if (connection != null) App.get().unbindService(connection);
        } catch (Exception ignored) {
        }
        connection = null;
        releaseMulticastLock();
    }

    private void acquireMulticastLock() {
        try {
            WifiManager manager = (WifiManager) App.get().getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (manager == null) return;
            multicastLock = manager.createMulticastLock("yingtu-dlna");
            multicastLock.setReferenceCounted(false);
            multicastLock.acquire();
        } catch (Exception ignored) {
            multicastLock = null;
        }
    }

    private void releaseMulticastLock() {
        if (multicastLock == null) return;
        try {
            if (multicastLock.isHeld()) multicastLock.release();
        } catch (Exception ignored) {
        }
        multicastLock = null;
    }

    /** 回环地址电视访问不到，换成本机局域网 IP，端口与路径不变。 */
    static String rewriteLocalUrl(String url) {
        if (TextUtils.isEmpty(url)) return url;
        try {
            URI uri = new URI(url);
            String host = uri.getHost();
            if (host == null) return url;
            if (!host.equals("127.0.0.1") && !host.equalsIgnoreCase("localhost") && !host.equals("::1")) return url;
            String lanIp = com.github.catvod.utils.Util.getIp();
            if (TextUtils.isEmpty(lanIp)) return url;
            URI fixed = new URI(uri.getScheme(), uri.getUserInfo(), lanIp, uri.getPort(), uri.getPath(), uri.getQuery(), uri.getFragment());
            return fixed.toString();
        } catch (Exception e) {
            return url;
        }
    }

    /** 手写 DIDL-Lite：只依赖字符串，不碰 DIDLContent 解析 API，少一路编译风险。 */
    static String buildMetadata(String title, String url) {
        String protocolInfo = "http-get:*:video/*:*";
        String lower = url.toLowerCase(Locale.US);
        if (lower.contains(".m3u8")) protocolInfo = "http-get:*:application/vnd.apple.mpegurl:*";
        else if (lower.contains(".mp4")) protocolInfo = "http-get:*:video/mp4:*";
        else if (lower.contains(".mkv")) protocolInfo = "http-get:*:video/x-matroska:*";
        return "<DIDL-Lite xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\" "
                + "xmlns:dc=\"http://purl.org/dc/elements/1.1/\" "
                + "xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\">"
                + "<item id=\"0\" parentID=\"0\" restricted=\"1\">"
                + "<dc:title>" + escapeXml(title) + "</dc:title>"
                + "<upnp:class>object.item.videoItem</upnp:class>"
                + "<res protocolInfo=\"" + protocolInfo + "\">" + escapeXml(url) + "</res>"
                + "</item></DIDL-Lite>";
    }

    static String formatTime(long positionMs) {
        long totalSeconds = positionMs / 1000;
        long hours = totalSeconds / 3600;
        long minutes = (totalSeconds % 3600) / 60;
        long seconds = totalSeconds % 60;
        return String.format(Locale.US, "%02d:%02d:%02d", hours, minutes, seconds);
    }

    private static String escapeXml(String text) {
        if (text == null) return "";
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;");
    }
}
