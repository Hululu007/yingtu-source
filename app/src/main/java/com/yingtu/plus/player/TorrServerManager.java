// TorrServer 本地子进程管理器：首次播磁力时下载二进制、拉起来、供播放器边下边播。
// 不用 Service、不改 AndroidManifest：就是一个 ProcessBuilder 拉起的普通子进程，由这个单例管生命周期，
// App 退出时 Source.exit() 会调 TorrServerExtractor.exit() 进而停掉它。
// 版本与地址以 2026-11-09 核实为准：最新 release 是 MatriX.148（2026-10-31 发布），
// 网上没找到 MatriX.149/MatriX.150 的任何发布记录，VERSION 钉在 MatriX.148，需要升级时只改这一个常量。
// 安卓 arm64 资产名按官方命名规则 TorrServer-<平台>-<架构>：TorrServer-android-arm64，
// 下载地址是 GitHub 标准格式 https://github.com/YouROK/TorrServer/releases/download/<版本>/<资产名>。
// 二进制大小没在可读的发布页里核实到（只见到 linux-amd64 约 44-46MB 的旁证），代码里不写死大小，
// 下多大都行，下完校验可执行即可。
package com.yingtu.plus.player;

import android.net.Uri;

import com.github.catvod.net.OkHttp;
import com.orhanobut.hawk.Hawk;
import com.yingtu.plus.App;
import com.yingtu.plus.util.HawkConfig;
import com.yingtu.plus.utils.Download;
import com.yingtu.plus.utils.Notify;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class TorrServerManager {

    // 2026-11-09 核实的最新 release；升级时只改这里，旧二进制靠 version.txt 标记自动重下。
    public static final String VERSION = "MatriX.148";
    public static final String ASSET = "TorrServer-android-arm64";
    public static final String DOWNLOAD_URL = "https://github.com/YouROK/TorrServer/releases/download/" + VERSION + "/" + ASSET;

    public static final int PORT = 8090;
    public static final String BASE_URL = "http://127.0.0.1:" + PORT;
    // 健康检查：GET /echo，官方文档写明返回服务器版本；通了就说明服务已起好（地址走 baseUrl()，见下）。

    /** P2P播放使用外部IP：开时用局域网 IP 作播放地址（电视/局域网设备也能直连），关时只用 127.0.0.1。 */
    public static String baseUrl() {
        try {
            if (Hawk.get(HawkConfig.P2P_EXTERNAL_IP, false)) {
                String ip = bindIp();
                if (!"127.0.0.1".equals(ip)) return "http://" + ip + ":" + PORT;
            }
        } catch (Throwable ignored) {
        }
        return BASE_URL;
    }

    /** TorrServer 绑定的 IP：外部IP开时取本机局域网 IP（无局域网 IP 时回落 127.0.0.1），关时 127.0.0.1。 */
    public static String bindIp() {
        try {
            if (Hawk.get(HawkConfig.P2P_EXTERNAL_IP, false)) {
                String ip = com.github.catvod.utils.Util.getIp();
                if (ip != null && !ip.isEmpty() && !"127.0.0.1".equals(ip)) return ip;
            }
        } catch (Throwable ignored) {
        }
        return "127.0.0.1";
    }

    // 子进程启动最多等 20 秒；种子元数据/首字节探测各最多 15/30 秒，见下面常量。
    private static final long START_TIMEOUT_MS = 20000;
    private static final long ECHO_PROBE_MS = 2000;
    private static final long ADD_TIMEOUT_MS = 30000;
    private static final long PLAY_PROBE_MS = 15000;

    private static final Set<String> VIDEO_EXTS = new HashSet<>(Arrays.asList(
            "mp4", "mkv", "avi", "mov", "wmv", "flv", "m2ts", "ts", "webm", "m4v", "rmvb", "mpg", "mpeg", "3gp", "f4v", "vob"));

    private static volatile TorrServerManager instance;

    private final File homeDir;
    private final File binaryFile;
    private final File dataDir;
    private final File logFile;
    private final File versionFile;
    private Process process;

    public static TorrServerManager get() {
        if (instance == null) {
            synchronized (TorrServerManager.class) {
                if (instance == null) instance = new TorrServerManager();
            }
        }
        return instance;
    }

    private TorrServerManager() {
        homeDir = new File(App.get().getFilesDir(), "torrserver");
        binaryFile = new File(homeDir, ASSET);
        dataDir = new File(homeDir, "data");
        logFile = new File(homeDir, "torrserver.log");
        versionFile = new File(homeDir, "version.txt");
    }

    // 保证服务可用：已在跑就直接复用；否则先下二进制（仅首次）、再拉进程、等 /echo 通。
    // 调用方在取流线程上跑，本方法会阻塞；首次下载约几十 MB，耗时看网速，调用前由提取器先给用户提示。
    public synchronized String ensureStarted() throws Exception {
        if (isRunning()) return baseUrl();
        if (process != null && process.isAlive()) {
            if (waitForEcho(START_TIMEOUT_MS)) return baseUrl();
            stopProcess();
        }
        ensureBinary();
        startProcess();
        if (!waitForEcho(START_TIMEOUT_MS)) {
            String tail = readLogTail();
            stopProcess();
            throw new IOException("TorrServer 启动超时（20 秒内 /echo 不通）" + (tail.isEmpty() ? "" : "，日志尾部：" + tail));
        }
        return baseUrl();
    }

    public boolean isRunning() {
        try (Response res = OkHttp.newCall(OkHttp.client(ECHO_PROBE_MS), baseUrl() + "/echo").execute()) {
            return res.isSuccessful();
        } catch (Exception e) {
            return false;
        }
    }

    public synchronized void stop() {
        stopProcess();
    }

    private void stopProcess() {
        if (process == null) return;
        try {
            process.destroy();
            try {
                process.waitFor();
            } catch (Exception ignored) {
            }
            if (process.isAlive()) process.destroyForcibly();
        } catch (Exception ignored) {
        } finally {
            process = null;
        }
    }

    // 二进制只在首次或版本变化时下载一次，不打进 APK；下完校验可执行位。
    private void ensureBinary() throws Exception {
        if (!homeDir.exists() && !homeDir.mkdirs()) throw new IOException("建 TorrServer 目录失败：" + homeDir);
        if (!dataDir.exists() && !dataDir.mkdirs()) throw new IOException("建 TorrServer 数据目录失败：" + dataDir);
        if (binaryFile.exists() && !VERSION.equals(readVersionMarker())) {
            if (!binaryFile.delete()) throw new IOException("旧版 TorrServer 二进制删不掉，请清应用数据重试");
        }
        if (!binaryFile.exists()) {
            toast("正在下载 TorrServer 组件（首次约几十 MB）…");
            try {
                Download.create(DOWNLOAD_URL, binaryFile).get();
            } catch (Exception e) {
                if (binaryFile.exists()) binaryFile.delete();
                throw new IOException("TorrServer 组件下载失败：" + e.getMessage(), e);
            }
            writeVersionMarker();
            toast("TorrServer 组件下载完成，正在启动…");
        }
        if (!binaryFile.canExecute()) {
            binaryFile.setExecutable(true, false);
            if (!binaryFile.canExecute()) {
                try {
                    new ProcessBuilder("/system/bin/chmod", "755", binaryFile.getAbsolutePath()).start().waitFor();
                } catch (Exception ignored) {
                }
            }
        }
        if (!binaryFile.canExecute()) {
            throw new IOException("TorrServer 二进制无法执行（本机可能禁了应用目录执行权限，见回报里的风险说明）");
        }
    }

    private void startProcess() throws Exception {
        List<String> command = new ArrayList<>();
        command.add(binaryFile.getAbsolutePath());
        command.add("--port");
        command.add(String.valueOf(PORT));
        command.add("--ip");
        command.add(bindIp());
        command.add("--path");
        command.add(dataDir.getAbsolutePath());
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.redirectErrorStream(true);
        builder.redirectOutput(ProcessBuilder.Redirect.appendTo(logFile));
        process = builder.start();
    }

    private boolean waitForEcho(long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (isRunning()) return true;
            if (process != null && !process.isAlive()) return false;
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return isRunning();
    }

    private String readLogTail() {
        try {
            if (!logFile.exists()) return "";
            long length = logFile.length();
            long skip = Math.max(0, length - 2048);
            try (FileInputStream in = new FileInputStream(logFile)) {
                in.skip(skip);
                byte[] buf = new byte[(int) (length - skip)];
                int read = in.read(buf);
                return read > 0 ? new String(buf, 0, read, StandardCharsets.UTF_8).trim() : "";
            }
        } catch (Exception e) {
            return "";
        }
    }

    private String readVersionMarker() {
        try {
            if (!versionFile.exists()) return "";
            byte[] buf = new byte[(int) versionFile.length()];
            try (FileInputStream in = new FileInputStream(versionFile)) {
                int read = in.read(buf);
                return read > 0 ? new String(buf, 0, read, StandardCharsets.UTF_8).trim() : "";
            }
        } catch (Exception e) {
            return "";
        }
    }

    private void writeVersionMarker() {
        try (FileOutputStream out = new FileOutputStream(versionFile)) {
            out.write(VERSION.getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignored) {
        }
    }

    // 把磁力链加进 TorrServer，拿回种子信息（含文件列表）；失败就返回 null，调用方用 index=0 的流地址兜底。
    // POST /torrents 是官方接口：{"action":"add","link":...}，save_to_db=false 表示不往服务端库里存。
    public Torrent addTorrent(String magnet) {
        try {
            JSONObject body = new JSONObject();
            body.put("action", "add");
            body.put("link", magnet);
            body.put("save_to_db", false);
            RequestBody requestBody = RequestBody.create(body.toString(), MediaType.parse("application/json; charset=utf-8"));
            try (Response res = OkHttp.newCall(OkHttp.client(ADD_TIMEOUT_MS), baseUrl() + "/torrents", requestBody).execute()) {
                if (!res.isSuccessful() || res.body() == null) return null;
                return Torrent.fromJson(new JSONObject(res.body().string()));
            }
        } catch (Exception e) {
            return null;
        }
    }

    // 直接播某文件的地址：GET /play/<hash>/<文件序号>，官方接口，支持 Range 断点续播，Exo/MPV 可直接播。
    public String playUrl(String hash, int fileId) {
        return baseUrl() + "/play/" + hash.toLowerCase(Locale.US) + "/" + fileId;
    }

    // 不知道 hash 或文件列表时的流地址：GET /stream/<文件名>?link=<磁力>&index=<序号>&play，
    // 官方接口，TorrServer 接到请求才现加种子、现取元数据；文件名一段只是展示用。
    public String streamUrl(String magnet, int index, String fileName) throws Exception {
        String name = fileName == null || fileName.isEmpty() ? "file" : new File(fileName).getName();
        return baseUrl() + "/stream/" + Uri.encode(name)
                + "?link=" + URLEncoder.encode(magnet, StandardCharsets.UTF_8.name())
                + "&index=" + index + "&play";
    }

    // 轻探：只取第 1 个字节，看 TorrServer 是不是真能出流。
    // 返回 HTTP 状态码；超时/断连会抛异常（调用方把这种情况当“还没准备好”，直接把地址交给播放器重试，不判死刑）。
    public int probe(String url) throws Exception {
        Request request = new Request.Builder().url(url).header("Range", "bytes=0-0").build();
        try (Response res = OkHttp.client(PLAY_PROBE_MS).newCall(request).execute()) {
            return res.code();
        }
    }

    // 从 magnet:?xt=urn:btih:<hash> 里取 40 位十六进制 hash；32 位 base32 的不转，直接返回 null 走 /stream 形式。
    public static String extractHash(String magnet) {
        if (magnet == null) return null;
        int start = magnet.indexOf("urn:btih:");
        if (start < 0) return null;
        int hashStart = start + "urn:btih:".length();
        int hashEnd = hashStart;
        while (hashEnd < magnet.length() && magnet.charAt(hashEnd) != '&') hashEnd++;
        String hash = magnet.substring(hashStart, hashEnd).toLowerCase(Locale.US);
        if (hash.length() != 40) return null;
        for (int i = 0; i < hash.length(); i++) {
            char c = hash.charAt(i);
            if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f'))) return null;
        }
        return hash;
    }

    private static void toast(String text) {
        App.post(() -> Notify.show(text));
    }

    public static class Torrent {
        public String hash;
        public String title;
        public final List<FileEntry> files = new ArrayList<>();

        public static Torrent fromJson(JSONObject json) {
            if (json == null) return null;
            if (json.has("torrent") && json.optJSONObject("torrent") != null) json = json.optJSONObject("torrent");
            Torrent torrent = new Torrent();
            torrent.hash = json.optString("hash", null);
            if (torrent.hash != null && torrent.hash.isEmpty()) torrent.hash = null;
            torrent.title = json.optString("title", null);
            JSONArray array = json.optJSONArray("file_stats");
            if (array == null) array = json.optJSONArray("files");
            if (array != null) {
                for (int i = 0; i < array.length(); i++) {
                    JSONObject file = array.optJSONObject(i);
                    if (file == null) continue;
                    FileEntry entry = new FileEntry();
                    entry.id = file.optInt("id", i);
                    entry.path = file.optString("path", "");
                    entry.length = file.optLong("length", 0);
                    torrent.files.add(entry);
                }
            }
            return torrent;
        }

        // 选最大的视频文件播；没有视频文件就选最大的文件；没有文件列表返回 null。
        public FileEntry bestFile() {
            FileEntry bestVideo = null;
            FileEntry bestAny = null;
            for (FileEntry entry : files) {
                if (bestAny == null || entry.length > bestAny.length) bestAny = entry;
                if (isVideo(entry.path) && (bestVideo == null || entry.length > bestVideo.length)) bestVideo = entry;
            }
            return bestVideo != null ? bestVideo : bestAny;
        }

        private static boolean isVideo(String path) {
            if (path == null) return false;
            int dot = path.lastIndexOf('.');
            if (dot < 0 || dot == path.length() - 1) return false;
            return VIDEO_EXTS.contains(path.substring(dot + 1).toLowerCase(Locale.US));
        }
    }

    public static class FileEntry {
        public int id;
        public String path;
        public long length;
    }
}
