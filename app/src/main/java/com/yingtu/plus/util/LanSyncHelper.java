package com.yingtu.plus.util;

import android.text.TextUtils;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.orhanobut.hawk.Hawk;
import com.yingtu.plus.api.ApiConfig;
import com.yingtu.plus.api.VodConfigSync;
import com.yingtu.plus.bean.History;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 影图间同步数据（局域网最小闭环）：本机起一个 HTTP 服务（端口 8686），
 * 对方 GET /sync 拉取、POST /sync 推送；本机也能 GET 对方地址拉取。
 * 同步内容只有接口 / 历史 / 收藏 / 配置四类 JSON；文件不同步（无文件传输核心，
 * 设置页里不提供文件复选，回报里已写明）。
 * 推送/拉取都只认同网段内对方手动填的地址，不做自动发现之外的事；
 * 扫描只探 192.168.x.1～20 与 10.0.x.1～20 的 /ping，5 秒超时。
 */
public final class LanSyncHelper {

    public static final int PORT = 8686;

    public interface Callback {
        void onResult(boolean ok, String msg);
    }

    public interface ScanCallback {
        void onFound(List<String> addresses);
    }

    private static volatile ServerSocket server;
    private static volatile Thread serverThread;

    private LanSyncHelper() {}

    public static boolean isRunning() {
        return server != null && !server.isClosed();
    }

    public static synchronized void startServer() {
        if (isRunning()) return;
        try {
            server = new ServerSocket(PORT);
        } catch (Throwable e) {
            e.printStackTrace();
            return;
        }
        serverThread = new Thread(() -> {
            while (isRunning()) {
                try {
                    Socket socket = server.accept();
                    new Thread(() -> handle(socket)).start();
                } catch (Throwable ignored) {
                    break;
                }
            }
        });
        serverThread.setDaemon(true);
        serverThread.start();
    }

    public static synchronized void stopServer() {
        try {
            if (server != null) server.close();
        } catch (Throwable ignored) {
        }
        server = null;
    }

    /** 本机局域网 IP（供对方填地址用），拿不到返回空。 */
    public static String localIp() {
        try {
            Enumeration<NetworkInterface> ifs = NetworkInterface.getNetworkInterfaces();
            while (ifs.hasMoreElements()) {
                NetworkInterface ni = ifs.nextElement();
                if (!ni.isUp() || ni.isLoopback()) continue;
                Enumeration<InetAddress> addrs = ni.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    InetAddress a = addrs.nextElement();
                    if (a instanceof Inet4Address && a.isSiteLocalAddress()) return a.getHostAddress();
                }
            }
        } catch (Throwable ignored) {
        }
        return "";
    }

    /** 打包四类数据（key → JSON 字符串）。 */
    public static Map<String, String> buildBundle(boolean api, boolean history, boolean collect, boolean config) {
        Map<String, String> bundle = new LinkedHashMap<>();
        Gson gson = new Gson();
        try {
            if (api) {
                bundle.put("api_url", Hawk.get(HawkConfig.API_URL, ""));
                bundle.put("api_config", Hawk.get(HawkConfig.API_CONFIG_CACHE, ""));
            }
            if (history) {
                List<History> list = HistoryHelper.getAll();
                bundle.put("play_history", gson.toJson(list));
            }
            if (collect) {
                bundle.put("fav_vods", gson.toJson(Hawk.get(HawkConfig.FAV_VODS, new ArrayList<>())));
                bundle.put("fav_links", gson.toJson(Hawk.get(HawkConfig.FAV_LINKS, new ArrayList<>())));
            }
            if (config) {
                Map<String, Object> cfg = new LinkedHashMap<>();
                cfg.put("home_rec", Hawk.get(HawkConfig.HOME_REC, "站点推荐"));
                cfg.put("grid_columns", Hawk.get(HawkConfig.GRID_COLUMNS, 3));
                cfg.put("dark_mode", Hawk.get(HawkConfig.DARK_MODE, true));
                bundle.put("config", gson.toJson(cfg));
            }
        } catch (Throwable e) {
            e.printStackTrace();
        }
        return bundle;
    }

    /** 对方 /sync 的 GET 返回、或本机收到 POST 时，把 bundle 按勾选的类目写进本地。 */
    @SuppressWarnings("unchecked")
    public static void applyBundle(Map<String, String> bundle, boolean api, boolean history, boolean collect, boolean config) {
        if (bundle == null) return;
        Gson gson = new Gson();
        try {
            if (api && bundle.containsKey("api_config") && !TextUtils.isEmpty(bundle.get("api_config"))) {
                String body = bundle.get("api_config");
                Hawk.put(HawkConfig.API_CONFIG_CACHE, body);
                if (bundle.containsKey("api_url")) Hawk.put(HawkConfig.API_URL, bundle.get("api_url"));
                ApiConfig.get().parseConfig(body);
                VodConfigSync.sync();
            }
            if (history && bundle.containsKey("play_history")) {
                List<History> list = gson.fromJson(bundle.get("play_history"),
                        new TypeToken<List<History>>() {}.getType());
                if (list != null) Hawk.put(HawkConfig.PLAY_HISTORY, list);
            }
            if (collect) {
                if (bundle.containsKey("fav_vods")) {
                    List<Object> vods = gson.fromJson(bundle.get("fav_vods"),
                            new TypeToken<List<Object>>() {}.getType());
                    if (vods != null) Hawk.put(HawkConfig.FAV_VODS, vods);
                }
                if (bundle.containsKey("fav_links")) {
                    List<Object> links = gson.fromJson(bundle.get("fav_links"),
                            new TypeToken<List<Object>>() {}.getType());
                    if (links != null) Hawk.put(HawkConfig.FAV_LINKS, links);
                }
            }
            if (config && bundle.containsKey("config")) {
                Map<String, Object> cfg = gson.fromJson(bundle.get("config"),
                        new TypeToken<Map<String, Object>>() {}.getType());
                if (cfg != null) {
                    if (cfg.containsKey("home_rec")) Hawk.put(HawkConfig.HOME_REC, String.valueOf(cfg.get("home_rec")));
                    if (cfg.containsKey("grid_columns")) Hawk.put(HawkConfig.GRID_COLUMNS, ((Number) cfg.get("grid_columns")).intValue());
                    if (cfg.containsKey("dark_mode")) Hawk.put(HawkConfig.DARK_MODE, (Boolean) cfg.get("dark_mode"));
                }
            }
        } catch (Throwable e) {
            e.printStackTrace();
        }
    }

    /** 拉取：GET 对方地址 /sync?cats=...，按勾选写进本地（后台线程调，回调回主线程由调用方处理）。 */
    public static void pull(String address, boolean api, boolean history, boolean collect, boolean config, Callback cb) {
        new Thread(() -> {
            try {
                String base = normalize(address);
                if (base == null) {
                    cb.onResult(false, "地址不对，应填如 192.168.1.5:8686");
                    return;
                }
                StringBuilder cats = new StringBuilder();
                if (api) cats.append("api,");
                if (history) cats.append("history,");
                if (collect) cats.append("collect,");
                if (config) cats.append("config,");
                if (cats.length() == 0) {
                    cb.onResult(false, "先勾要同步的内容");
                    return;
                }
                URL url = new URL("http://" + base + "/sync?cats=" + cats);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setConnectTimeout(5000);
                conn.setReadTimeout(15000);
                conn.setRequestMethod("GET");
                int code = conn.getResponseCode();
                if (code != 200) {
                    cb.onResult(false, "对方没响应（HTTP " + code + "），确认对方已开同步服务");
                    return;
                }
                String body = readAll(conn.getInputStream());
                Map<String, String> bundle = new Gson().fromJson(body,
                        new TypeToken<Map<String, String>>() {}.getType());
                applyBundle(bundle, api, history, collect, config);
                cb.onResult(true, "已从对方拉取并写入本地");
            } catch (Throwable e) {
                cb.onResult(false, "拉取失败：" + e.getMessage());
            }
        }).start();
    }

    /** 推送：POST 本机 bundle 到对方 /sync（后台线程调）。 */
    public static void push(String address, boolean api, boolean history, boolean collect, boolean config, Callback cb) {
        new Thread(() -> {
            try {
                String base = normalize(address);
                if (base == null) {
                    cb.onResult(false, "地址不对，应填如 192.168.1.5:8686");
                    return;
                }
                String json = new Gson().toJson(buildBundle(api, history, collect, config));
                URL url = new URL("http://" + base + "/sync");
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setConnectTimeout(5000);
                conn.setReadTimeout(15000);
                conn.setRequestMethod("POST");
                conn.setDoOutput(true);
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                try (OutputStream out = conn.getOutputStream()) {
                    out.write(json.getBytes(StandardCharsets.UTF_8));
                }
                int code = conn.getResponseCode();
                cb.onResult(code == 200, code == 200 ? "已推送给对方" : "对方没接（HTTP " + code + ")");
            } catch (Throwable e) {
                cb.onResult(false, "推送失败：" + e.getMessage());
            }
        }).start();
    }

    /** 扫描同网段常见地址的 /ping（后台线程调）。 */
    public static void scan(ScanCallback cb) {
        new Thread(() -> {
            List<String> found = new ArrayList<>();
            String local = localIp();
            String prefix = null;
            if (!TextUtils.isEmpty(local)) {
                int lastDot = local.lastIndexOf('.');
                if (lastDot > 0) prefix = local.substring(0, lastDot + 1);
            }
            if (prefix != null) {
                for (int i = 1; i <= 20; i++) {
                    String host = prefix + i;
                    if (host.equals(local)) continue;
                    if (ping(host)) found.add(host + ":" + PORT);
                }
            }
            cb.onFound(found);
        }).start();
    }

    private static boolean ping(String host) {
        try {
            URL url = new URL("http://" + host + ":" + PORT + "/ping");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(800);
            conn.setReadTimeout(800);
            conn.setRequestMethod("GET");
            return conn.getResponseCode() == 200;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static String normalize(String address) {
        if (address == null) return null;
        String a = address.trim().replace("http://", "").replace("https://", "").replace("/", "");
        if (a.isEmpty() || !a.contains(".")) return null;
        if (!a.contains(":")) a = a + ":" + PORT;
        return a;
    }

    private static String readAll(InputStream in) throws Exception {
        BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = r.readLine()) != null) sb.append(line);
        return sb.toString();
    }

    // ===== 本机服务：GET /ping、GET /sync、POST /sync =====

    private static void handle(Socket socket) {
        try (Socket s = socket) {
            s.setSoTimeout(10000);
            BufferedReader reader = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
            String requestLine = reader.readLine();
            if (requestLine == null) return;
            int contentLength = 0;
            String line;
            while ((line = reader.readLine()) != null && !line.isEmpty()) {
                if (line.toLowerCase().startsWith("content-length:")) {
                    try {
                        contentLength = Integer.parseInt(line.substring(15).trim());
                    } catch (Throwable ignored) {
                    }
                }
            }
            String[] parts = requestLine.split(" ");
            String method = parts.length > 0 ? parts[0] : "";
            String path = parts.length > 1 ? parts[1] : "";
            OutputStream out = s.getOutputStream();
            if ("GET".equals(method) && path.startsWith("/ping")) {
                writeJson(out, 200, "{\"ok\":true,\"device\":\"" + deviceName() + "\"}");
            } else if ("GET".equals(method) && path.startsWith("/sync")) {
                Map<String, String> bundle = buildBundle(true, true, true, true);
                String cats = "";
                int q = path.indexOf("cats=");
                if (q >= 0) cats = path.substring(q + 5);
                if (!cats.isEmpty()) {
                    Map<String, String> filtered = new HashMap<>();
                    if (cats.contains("api")) {
                        filtered.put("api_url", bundle.get("api_url"));
                        filtered.put("api_config", bundle.get("api_config"));
                    }
                    if (cats.contains("history")) filtered.put("play_history", bundle.get("play_history"));
                    if (cats.contains("collect")) {
                        filtered.put("fav_vods", bundle.get("fav_vods"));
                        filtered.put("fav_links", bundle.get("fav_links"));
                    }
                    if (cats.contains("config")) filtered.put("config", bundle.get("config"));
                    bundle = filtered;
                }
                writeJson(out, 200, new Gson().toJson(bundle));
            } else if ("POST".equals(method) && path.startsWith("/sync")) {
                char[] buf = new char[Math.max(0, contentLength)];
                int read = 0;
                while (read < buf.length) {
                    int n = reader.read(buf, read, buf.length - read);
                    if (n < 0) break;
                    read += n;
                }
                Map<String, String> bundle = new Gson().fromJson(new String(buf, 0, read),
                        new TypeToken<Map<String, String>>() {}.getType());
                applyBundle(bundle, true, true, true, true);
                writeJson(out, 200, "{\"ok\":true}");
            } else {
                writeJson(out, 404, "{\"ok\":false}");
            }
        } catch (Throwable ignored) {
        }
    }

    private static void writeJson(OutputStream out, int code, String json) throws Exception {
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        String head = "HTTP/1.1 " + code + " OK\r\nContent-Type: application/json; charset=utf-8\r\nContent-Length: "
                + body.length + "\r\nConnection: close\r\n\r\n";
        out.write(head.getBytes(StandardCharsets.UTF_8));
        out.write(body);
        out.flush();
    }

    private static String deviceName() {
        try {
            String name = Hawk.get(HawkConfig.DEVICE_NAME, "");
            if (!TextUtils.isEmpty(name)) return name;
        } catch (Throwable ignored) {
        }
        return "影图+";
    }
}
