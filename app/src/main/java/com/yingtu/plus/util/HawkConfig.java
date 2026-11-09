package com.yingtu.plus.util;

/**
 * Hawk 持久化存储用到的所有 key 常量。
 */
public class HawkConfig {
    public static final String API_CONFIG_CACHE = "api_config_cache";
    public static final String HOME_API = "home_api";
    public static final String API_URL = "api_url";
    public static final String PLAY_HISTORY = "play_history";
    public static final String SEARCH_HISTORY = "search_history";
    public static final String DARK_MODE = "dark_mode";
    public static final String HOME_REC = "home_rec";
    public static final String DEFAULT_PARSE = "default_parse";
    public static final String GRID_COLUMNS = "grid_columns";
    public static final String FAV_LINKS = "fav_links";
    public static final String FAV_VODS = "fav_vods";

    /** 播放内核：0=ExoPlayer 1=IJK 2=MPV */
    public static final String PLAYER_CORE = "player_core";

    /** 短剧预加载：播当前集时提前解析后面几集的流地址（只解析地址，不下载视频字节），默认开 */
    public static final String DRAMA_PRELOAD = "drama_preload";

    /** 各区当前站（分区独立记站）：影视 / 短剧 / 午夜，各区切站只改自己区的键 */
    public static final String ZONE_SITE_MOVIE = "zone_site_movie";
    public static final String ZONE_SITE_DRAMA = "zone_site_drama";
    public static final String ZONE_SITE_MIDNIGHT = "zone_site_midnight";

    /** 各区启动站（设置里按区独立设）：影视 / 短剧 / 午夜，空字符串 = 未设 */
    public static final String STARTUP_SITE_MOVIE = "startup_site_movie";
    public static final String STARTUP_SITE_DRAMA = "startup_site_drama";
    public static final String STARTUP_SITE_MIDNIGHT = "startup_site_midnight";

    /** WebHome 网页主页条目列表（List&lt;WebHomeEntry&gt;） */
    public static final String WEBHOME_ENTRIES = "webhome_entries";
    /** WebHome 内置示例主页是否已初始化 */
    public static final String WEBHOME_SEEDED = "webhome_seeded";
}
