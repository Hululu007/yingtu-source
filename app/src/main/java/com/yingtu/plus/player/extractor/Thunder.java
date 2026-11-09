package com.yingtu.plus.player.extractor;

import android.net.Uri;
import android.os.SystemClock;
import android.util.Base64;

import com.yingtu.plus.App;
import com.yingtu.plus.bean.Episode;
import com.yingtu.plus.exception.ExtractException;
import com.yingtu.plus.player.Source;
import com.yingtu.plus.player.TorrServerExtractor;
import com.yingtu.plus.utils.Download;
import com.yingtu.plus.utils.UrlUtil;
import com.github.catvod.Init;
import com.github.catvod.utils.Path;
import com.github.catvod.utils.Util;
import com.xunlei.downloadlib.XLTaskHelper;
import com.xunlei.downloadlib.parameter.GetTaskId;
import com.xunlei.downloadlib.parameter.TorrentFileInfo;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Callable;
import java.util.regex.Pattern;

public class Thunder implements Source.Extractor {

    private GetTaskId taskId;

    // 迅雷库不用在 App 里显式 init：XLTaskHelper.get() 是单例，第一次真正建任务时
    // XLDownloadManager 构造里自己拿 Init.context().getFilesDir() 拼 InitParam 调 native init，
    // 所以全树 grep 不到任何 init 调用是正常的、不是缺失。唯一的前提是 Init.context() 已设好
    // （App.onCreate 里 Init.set(this)），这里只做一道懒兜底，不改 App.java。
    private static void ensureInit() {
        try {
            if (Init.context() == null && App.get() != null) Init.set(App.get());
        } catch (Exception ignored) {
        }
    }

    @Override
    public boolean match(Uri uri) {
        // 原来只认 magnet/ed2k：thunder:// 链接展开失败后剩下的原始链接到 fetch 认不出来，
        // 播放器会拿着 thunder:// 裸链直接播，必失败；补上 thunder 才能走下面的解码兜底。
        return List.of("magnet", "ed2k", "thunder").contains(UrlUtil.scheme(uri));
    }

    @Override
    public String fetch(String url) throws Exception {
        ensureInit();
        if (url.startsWith("magnet")) return new TorrServerExtractor().fetch(url);
        if (url.startsWith("thunder://")) return fetchThunderLink(url);
        return addThunderTask(url);
    }

    // thunder:// 链接：先走迅雷库原生解析（它认 thunder:// 协议）；库抛异常才纯 Java 解码兜底。
    private String fetchThunderLink(String thunderUrl) throws Exception {
        try {
            return addThunderTask(thunderUrl);
        } catch (Exception ignored) {
            String real = decodeThunderLink(thunderUrl);
            if (real.startsWith("magnet")) return new TorrServerExtractor().fetch(real);
            if (real.startsWith("ed2k://")) return addThunderTask(real);
            if (real.toLowerCase(Locale.US).endsWith(".torrent")) return expandMagnetViaXunlei(real);
            if (real.startsWith("http://") || real.startsWith("https://")) {
                File folder = Path.thunder(Util.md5(real));
                File save = new File(folder, fileNameOf(real));
                Download.create(real, save).get();
                return "file://" + save.getAbsolutePath();
            }
            throw new ExtractException("迅雷链接解码后不是可播地址：" + real);
        }
    }

    private static String fileNameOf(String url) {
        String name = url.split("\\?")[0];
        int slash = name.lastIndexOf('/');
        name = slash >= 0 ? name.substring(slash + 1) : name;
        return name.isEmpty() ? Util.md5(url) : name;
    }

    // 纯 Java 的 thunder:// 解码：去掉 thunder:// 头、base64 解码、去掉头尾的 AA 与 ZZ，得到真实地址。
    // 迅雷加速拿不到，解出来的只是真实地址，走普通下载再播。
    public static String decodeThunderLink(String thunderUrl) throws Exception {
        if (thunderUrl == null || !thunderUrl.toLowerCase(Locale.US).startsWith("thunder://")) {
            throw new ExtractException("不是 thunder:// 链接");
        }
        String data = thunderUrl.substring("thunder://".length()).trim();
        byte[] raw = Base64.decode(data, Base64.DEFAULT);
        String text = new String(raw, StandardCharsets.UTF_8);
        if (text.startsWith("AA")) text = text.substring(2);
        if (text.endsWith("ZZ")) text = text.substring(0, text.length() - 2);
        if (text.isEmpty()) throw new ExtractException("thunder:// 链接解码为空");
        return text;
    }

    // 迅雷库展开磁力/.torrent：parse -> 等元数据 -> 取文件列表 -> 返回最大视频文件的本地播放地址。
    // 这是原来 addTorrentTask 想干的事，但那份实现要求 magnet 链接自带 path + ?name=&index= 参数，
    // 原生 magnet:?xt=urn:btih:... 没有 path（Uri.getPath() 为 null，new File(null) 直接抛空指针），
    // 也没有 name/index 参数（Integer.parseInt(null) 抛数字格式异常），而且树里没有任何地方生产这种带参链接，
    // 所以那条路对真实磁力链接是死的，已删掉，改走 parse 展开（和 Parser.call() 同一套逻辑）。
    public static String expandMagnetViaXunlei(String url) throws Exception {
        ensureInit();
        boolean torrent = isTorrentFile(url);
        GetTaskId taskId = XLTaskHelper.get().parse(url, Path.thunder(Util.md5(url)));
        if (torrent && url.startsWith("http")) Download.create(url, taskId.getSaveFile()).get();
        if (!torrent) waitDone(taskId);
        try {
            List<TorrentFileInfo> medias = XLTaskHelper.get().getTorrentInfo(taskId.getSaveFile()).getMedias();
            TorrentFileInfo best = pickBest(medias);
            if (best == null) throw new ExtractException("种子里没有可播文件");
            return best.getPlayUrl();
        } finally {
            XLTaskHelper.get().stopTask(taskId);
        }
    }

    private static boolean isTorrentFile(String url) {
        return !url.startsWith("magnet") && url.split(";")[0].toLowerCase(Locale.US).endsWith(".torrent");
    }

    // 等迅雷任务出元数据：最多等 10 秒（100 次 × 100 毫秒），任务状态变为 2 即返回。
    private static void waitDone(GetTaskId taskId) {
        for (int i = 0; i < 100; i++) {
            if (XLTaskHelper.get().getTaskInfo(taskId).getTaskStatus() == 2) return;
            SystemClock.sleep(100);
        }
    }

    // 优先最大的视频文件；没有视频文件就取最大的文件；没有文件返回 null。
    private static TorrentFileInfo pickBest(List<TorrentFileInfo> medias) {
        if (medias == null || medias.isEmpty()) return null;
        TorrentFileInfo bestVideo = null;
        TorrentFileInfo bestAny = null;
        for (TorrentFileInfo info : medias) {
            if (bestAny == null || info.getFileSize() > bestAny.getFileSize()) bestAny = info;
            if (isVideoExt(info) && (bestVideo == null || info.getFileSize() > bestVideo.getFileSize())) bestVideo = info;
        }
        return bestVideo != null ? bestVideo : bestAny;
    }

    private static boolean isVideoExt(TorrentFileInfo info) {
        String ext = info.getExt();
        if (ext == null || ext.isEmpty()) {
            String name = info.getFileName();
            int dot = name == null ? -1 : name.lastIndexOf('.');
            ext = dot >= 0 ? name.substring(dot + 1) : "";
        }
        if (ext.startsWith(".")) ext = ext.substring(1);
        return List.of("mp4", "mkv", "avi", "mov", "wmv", "flv", "m2ts", "ts", "webm", "m4v", "rmvb", "mpg", "mpeg", "3gp").contains(ext.toLowerCase(Locale.US));
    }

    private String addThunderTask(String url) {
        File folder = Path.thunder(Util.md5(url));
        taskId = XLTaskHelper.get().addThunderTask(url, folder);
        return XLTaskHelper.get().getLocalUrl(taskId.getSaveFile());
    }

    @Override
    public void stop() {
        if (taskId == null) return;
        XLTaskHelper.get().deleteTask(taskId);
        XLTaskHelper.get().release();
        taskId = null;
    }

    @Override
    public void exit() {
        XLTaskHelper.get().release();
    }

    public record Parser(String url) implements Callable<List<Episode>> {

        private static final Pattern PATTERN = Pattern.compile("(magnet|thunder|ed2k):.*");

        public static boolean match(String url) {
            return PATTERN.matcher(url).find() || isTorrent(url);
        }

        public static Parser get(String url) {
            return new Parser(url);
        }

        private static boolean isTorrent(String url) {
            return !url.startsWith("magnet") && url.split(";")[0].toLowerCase().endsWith(".torrent");
        }

        private Episode create(GetTaskId taskId) {
            return Episode.create(taskId.getFileName(), taskId.getRealUrl());
        }

        private Episode create(TorrentFileInfo info) {
            return Episode.create(info.getFileName(), info.getSize(), info.getPlayUrl());
        }

        @Override
        public List<Episode> call() {
            ensureInit();
            boolean torrent = isTorrent(url);
            GetTaskId taskId = XLTaskHelper.get().parse(url, Path.thunder(Util.md5(url)));
            if (!torrent && !taskId.getRealUrl().startsWith("magnet")) return Arrays.asList(create(taskId));
            if (torrent && url.startsWith("http")) Download.create(url, taskId.getSaveFile()).get();
            if (!torrent) waitDone(taskId);
            try {
                return XLTaskHelper.get().getTorrentInfo(taskId.getSaveFile()).getMedias().stream().map(this::create).toList();
            } finally {
                XLTaskHelper.get().stopTask(taskId);
            }
        }

        private static void waitDone(GetTaskId taskId) {
            for (int i = 0; i < 100; i++) {
                if (XLTaskHelper.get().getTaskInfo(taskId).getTaskStatus() == 2) return;
                SystemClock.sleep(100);
            }
        }
    }
}
