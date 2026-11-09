// 磁力链接提取器：主路走 TorrServer（本地子进程边下边播），
// TorrServer 明确回 4xx/5xx 时才落到迅雷库展开；探测超时不断连，直接把流地址交给播放器重试。
// 优先级（磁力）：TorrServer > 迅雷库展开。理由：迅雷库是闭源老 SDK，
// 在本机从没验通过，且它要先把种子元数据下完才能出地址；TorrServer 是现役开源实现，
// 流地址支持 Range，Exo/MPV 可直接播，冷门种子也能边下边播。
// 注意 30 秒超时只卡 Source.parse 里的展开阶段（Thunder.Parser），本提取器跑在 Source.fetch 的点播链上，
// 那里没有 30 秒上限；parse 阶段展开失败/超时时 Source.parse 会保留原始磁力链接，点播再走这里，
// 所以 TorrServer 启动 + 连种子就算超过 30 秒也不丢集、不报错，只是用户要多等一会儿，
// 等的过程中先弹 Toast 提示“正在连接 P2P…”。
package com.yingtu.plus.player;

import android.net.Uri;

import com.yingtu.plus.App;
import com.yingtu.plus.exception.ExtractException;
import com.yingtu.plus.player.extractor.Thunder;
import com.yingtu.plus.utils.Notify;
import com.yingtu.plus.utils.UrlUtil;

public class TorrServerExtractor implements Source.Extractor {

    @Override
    public boolean match(Uri uri) {
        return "magnet".equals(UrlUtil.scheme(uri));
    }

    @Override
    public String fetch(String url) throws Exception {
        App.post(() -> Notify.show("正在连接 P2P…"));
        TorrServerManager server = TorrServerManager.get();
        server.ensureStarted();
        String playUrl = buildPlayUrl(server, url);
        int code;
        try {
            code = server.probe(playUrl);
        } catch (Exception e) {
            // 探测超时/断连：TorrServer 可能还在取元数据、连 peer，不判死刑，地址直接交给播放器重试。
            return playUrl;
        }
        if (code < 400) return playUrl;
        // TorrServer 明确说不行（404/500 等）：落到迅雷库展开兜底。
        try {
            return Thunder.expandMagnetViaXunlei(url);
        } catch (Exception fallbackError) {
            throw new ExtractException("P2P 播放失败：TorrServer 返回 HTTP " + code + "；迅雷兜底也失败：" + fallbackError.getMessage());
        }
    }

    private String buildPlayUrl(TorrServerManager server, String magnet) throws Exception {
        TorrServerManager.Torrent torrent = server.addTorrent(magnet);
        if (torrent != null) {
            TorrServerManager.FileEntry best = torrent.bestFile();
            if (best != null) {
                String hash = torrent.hash != null ? torrent.hash : TorrServerManager.extractHash(magnet);
                if (hash != null) return server.playUrl(hash, best.id);
                return server.streamUrl(magnet, best.id, best.path);
            }
        }
        // 没拿到文件列表（种子刚加、接口异常等）：默认播第 0 个文件，单文件种子就是它。
        return server.streamUrl(magnet, 0, "file");
    }

    @Override
    public void stop() {
        // 换集不杀服务：下次播同一批种子不用重新启动，TorrServer 进程常驻到 App 退出。
    }

    @Override
    public void exit() {
        TorrServerManager.get().stop();
    }
}
