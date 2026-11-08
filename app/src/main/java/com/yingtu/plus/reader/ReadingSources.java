package com.yingtu.plus.reader;

import java.util.ArrayList;
import java.util.List;

/**
 * 内置阅读源登记：照火蚁TV树 lib/content_registry.dart 的 py 阅读源清单
 *（assets/novel_spiders 22 个 py + assets/audio_spiders 5 个 py，
 * 目录实测数；另有 1 个 jar 听书站 csp_TingShu 不在此表，走 JarLoader）。
 *
 * 注意：py 文件本身尚未拷进影图 assets。api 字段写的是火蚁TV树里的
 * assetPath，影图这边要把对应 py 文件拷进 app/src/main/assets/ 下
 * 相同相对路径（或把 api 换成可直取的 py 文件地址）才能加载；
 * NovelLoader 只负责按 api 取蜘蛛，不负责拷文件。
 * kind：novel 小说 / comics 漫画（走 pics://）/ audio 听书。
 */
public class ReadingSources {

    public static final String KIND_NOVEL = "novel";
    public static final String KIND_COMICS = "comics";
    public static final String KIND_AUDIO = "audio";

    public static class Source {
        public final String key;
        public final String name;
        public final String api;
        public final String kind;

        public Source(String key, String name, String api, String kind) {
            this.key = key;
            this.name = name;
            this.api = api;
            this.kind = kind;
        }
    }

    private static final List<Source> ALL = new ArrayList<>();

    static {
        // 小说 14 站
        ALL.add(new Source("aabook", "🔞疯情书库", "assets/novel_spiders/aabook.py", KIND_NOVEL));
        ALL.add(new Source("ylxs", "🔞月亮小说", "assets/novel_spiders/ylxs.py", KIND_NOVEL));
        ALL.add(new Source("cheniyo", "🔞桃色书屋", "assets/novel_spiders/cheniyo.py", KIND_NOVEL));
        ALL.add(new Source("hwg4", "🔞黄文阁", "assets/novel_spiders/hwg4.py", KIND_NOVEL));
        ALL.add(new Source("lbwen", "🔞刘备文", "assets/novel_spiders/lbwen.py", KIND_NOVEL));
        ALL.add(new Source("cjg", "🔞藏精阁", "assets/novel_spiders/cjg.py", KIND_NOVEL));
        ALL.add(new Source("buliang", "🔞不良小说网", "assets/novel_spiders/buliang.py", KIND_NOVEL));
        ALL.add(new Source("yinse", "🔞淫色小说网", "assets/novel_spiders/yinse.py", KIND_NOVEL));
        ALL.add(new Source("ssxxs", "🔞涩涩小说", "assets/novel_spiders/ssxxs.py", KIND_NOVEL));
        ALL.add(new Source("h528", "🔞風月文學網", "assets/novel_spiders/h528.py", KIND_NOVEL));
        ALL.add(new Source("69story", "🔞69成人小說網", "assets/novel_spiders/69story.py", KIND_NOVEL));
        ALL.add(new Source("canovel", "🔞CA情色小說", "assets/novel_spiders/canovel.py", KIND_NOVEL));
        ALL.add(new Source("1000novel", "🔞1000成人小說網", "assets/novel_spiders/1000novel.py", KIND_NOVEL));
        ALL.add(new Source("jywx2", "🔞九一文学网", "assets/novel_spiders/jywx2.py", KIND_NOVEL));
        // 漫画 8 站（走 pics://）
        ALL.add(new Source("mhbl", "🔞漫画部落", "assets/novel_spiders/mhbl.py", KIND_COMICS));
        ALL.add(new Source("ho5ho", "🔞ho5ho", "assets/novel_spiders/ho5ho.py", KIND_COMICS));
        ALL.add(new Source("modu18", "🔞魔都18", "assets/novel_spiders/modu18.py", KIND_COMICS));
        ALL.add(new Source("nnhanman", "🔞鸟鸟韩漫", "assets/novel_spiders/nnhanman.py", KIND_COMICS));
        ALL.add(new Source("55comics", "🔞污污漫书", "assets/novel_spiders/55comics.py", KIND_COMICS));
        ALL.add(new Source("kmh001", "🔞K漫画", "assets/novel_spiders/kmh001.py", KIND_COMICS));
        ALL.add(new Source("csmanhua", "🔞成色漫画", "assets/novel_spiders/csmanhua.py", KIND_COMICS));
        ALL.add(new Source("kxmanhua", "🔞开心看漫画", "assets/novel_spiders/kxmanhua.py", KIND_COMICS));
        // 听书 5 个 py 源
        ALL.add(new Source("ximalaya", "喜马拉雅", "assets/audio_spiders/ximalaya.py", KIND_AUDIO));
        ALL.add(new Source("hongguo_audio", "红果听书", "assets/audio_spiders/hongguo.py", KIND_AUDIO));
        ALL.add(new Source("hsvoice31", "🔞黄色有声小说", "assets/audio_spiders/hsvoice31.py", KIND_AUDIO));
        ALL.add(new Source("tingyou", "听友听书", "assets/audio_spiders/tingyou.py", KIND_AUDIO));
        ALL.add(new Source("hysxs", "🔞H有声小说", "assets/audio_spiders/hysxs.py", KIND_AUDIO));
    }

    public static List<Source> all() {
        return new ArrayList<>(ALL);
    }

    public static List<Source> byKind(String kind) {
        List<Source> result = new ArrayList<>();
        for (Source source : ALL) {
            if (source.kind.equals(kind)) result.add(source);
        }
        return result;
    }

    public static Source byKey(String key) {
        if (key == null) return null;
        for (Source source : ALL) {
            if (source.key.equals(key)) return source;
        }
        return null;
    }
}
