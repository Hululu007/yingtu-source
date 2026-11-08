package com.yingtu.plus.drama;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Short-drama site registry for the drama zone.
 *
 * The "original" site is the Fanqie short-drama stream (drama_sites.dart:
 * DramaSite('original', <fanqie stream>, [all/recommend/watch/manju])).
 * Its feed is served by FanqieDramaClient, not by a TVBox spider.
 *
 * The py/jar drama sites are served by the existing TVBox loaders
 * (PyLoader / JarLoader) through assets/builtin_drama.json; this class only
 * lists their keys so BuiltinDramaConfigs can filter them back out of the
 * shared ApiConfig table. The 5 undecided py sites (abjav,
 * chinaxselecla08, dingjiaseku, 51xz, qipaosp) are deliberately NOT listed
 * here and NOT in builtin_drama.json: they stay unwired until the parent
 * agent decides their placement.
 */
public final class DramaSites {

    private DramaSites() {
    }

    /** Built-in Fanqie stream site key (client-driven, no spider). */
    public static final String ORIGINAL_KEY = "fq_original";
    public static final String ORIGINAL_NAME = "番茄短剧";

    /** Channel tabs for the Fanqie stream, same ids as drama_sites.dart. */
    public static final List<String> ORIGINAL_CHANNEL_IDS =
            Collections.unmodifiableList(Arrays.asList("all", "recommend", "watch", "manju"));
    public static final List<String> ORIGINAL_CHANNEL_NAMES =
            Collections.unmodifiableList(Arrays.asList("全部", "推荐", "看剧", "漫剧"));

    /** py drama sites registered in builtin_drama.json (18). */
    public static final List<String> PY_DRAMA_KEYS = Collections.unmodifiableList(Arrays.asList(
            "ai2026", "ai4k", "aidj", "aiduanju", "aimove", "aisxpc",
            "danai", "dj69", "dj91", "djwo", "duanjuone", "aihi",
            "ficeforand", "aipornx", "aiyinhang", "aiwu", "sexaix", "youzidj"));

    /** jar drama sites registered in builtin_drama.json (3). */
    public static final List<String> JAR_DRAMA_KEYS = Collections.unmodifiableList(Arrays.asList(
            "ZakaHuangdou", "ZakaHgdj", "ZakaHddj"));

    /** All TVBox drama site keys (py + jar), in config order. */
    public static List<String> allSpiderDramaKeys() {
        List<String> all = new java.util.ArrayList<>(PY_DRAMA_KEYS);
        all.addAll(JAR_DRAMA_KEYS);
        return all;
    }
}
