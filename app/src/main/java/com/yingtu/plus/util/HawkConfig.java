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

    /** 频道页最后一次实际用过的站（不限分区）：进频道页时按它直接出数据，
     * 影视区没记过站、全局当前源又不是影视区站时就靠它不断档；用户切站即改写 */
    public static final String CHANNEL_LAST_SITE_KEY = "channel_last_site_key";

    /** 各区启动站（设置里按区独立设）：影视 / 短剧 / 午夜，空字符串 = 未设 */
    public static final String STARTUP_SITE_MOVIE = "startup_site_movie";
    public static final String STARTUP_SITE_DRAMA = "startup_site_drama";
    public static final String STARTUP_SITE_MIDNIGHT = "startup_site_midnight";

    /** WebHome 网页主页条目列表（List&lt;WebHomeEntry&gt;） */
    public static final String WEBHOME_ENTRIES = "webhome_entries";
    /** WebHome 内置示例主页是否已初始化 */
    public static final String WEBHOME_SEEDED = "webhome_seeded";

    /** 在线网站条目列表（List<OnlineSite>） */
    public static final String ONLINE_SITES = "online_sites";

    // ===== 旧版设置移植（2026-11-09）：只收有真实核心的项，没核心的不建键 =====

    /** 接口缓存：开时启动读本地已缓存的订阅配置，关时每次现取（默认开，与现行为一致） */
    public static final String API_CACHE_ENABLED = "api_cache_enabled";
    /** 进度标识：0=名称（片名+进度），1=唯一ID（vod_id+进度） */
    public static final String PROGRESS_TAG = "progress_tag";
    /** 首页选源弹窗列数：1/2/3 */
    public static final String HOME_SOURCE_COLUMNS = "home_source_columns";
    /** 搜索展示：0=文字|文字 1=文字|图片 2=文字|图文 3=图片|文字 4=图片|图片 5=图片|图文（前为搜索页，后为聚搜页） */
    public static final String SEARCH_SHOW = "search_show";
    /** 搜索站点范围：0=全部 1=白名单 2=黑名单 3=当前源 4=选择源 5=名称包含 */
    public static final String SEARCH_SITE_MODE = "search_site_mode";
    /** 搜索白名单站 key 列表 */
    public static final String SEARCH_SITE_WHITELIST = "search_site_whitelist";
    /** 搜索黑名单站 key 列表 */
    public static final String SEARCH_SITE_BLACKLIST = "search_site_blacklist";
    /** 搜索“选择源”挑中的站 key 列表 */
    public static final String SEARCH_SITE_PICKED = "search_site_picked";
    /** 搜索“名称包含”过滤子串 */
    public static final String SEARCH_SITE_NAME_FILTER = "search_site_name_filter";
    /** 站源置顶的站 key 列表（置顶的排在列表前面） */
    public static final String SOURCE_PINNED = "source_pinned";
    /** 站源禁用位掩码：1=网络接口 2=直播接口 4=内部配置 8=JS文件 16=DRPY文件 32=JSON文件 64=SITES文件 128=网址站源 */
    public static final String SOURCE_DISABLED = "source_disabled";
    public static final int DISABLE_NET = 1;
    public static final int DISABLE_LIVE = 2;
    public static final int DISABLE_BUILTIN = 4;
    public static final int DISABLE_JS = 8;
    public static final int DISABLE_DRPY = 16;
    public static final int DISABLE_JSON = 32;
    /** 默认启动：0=首页 1=直播 2=文件 3=历史 4=收藏 5=最近 */
    public static final String DEFAULT_START_PAGE = "default_start_page";
    /** 安全DNS：0=关闭 1=腾讯 2=阿里 3=360 4=Google 5=AdGuard 6=Quad9 7=cloudflare 8=opendns 9=腾讯可选1 10=腾讯可选2 */
    public static final String SECURE_DNS = "secure_dns";
    /** 壁纸虚化 0～25 */
    public static final String WALLPAPER_BLUR = "wallpaper_blur";
    /** 壁纸亮度 -100～100，0=不变 */
    public static final String WALLPAPER_BRIGHTNESS = "wallpaper_brightness";
    /** 分类视图类型：0=普通视图1 1=文字视图 2=详情视图1 3=详情视图2 4=普通视图2 */
    public static final String CATE_VIEW_TYPE = "cate_view_type";
    /** 分类视图列数方案：0=方案1 1=方案2 2=方案3 */
    public static final String CATE_VIEW_COLUMNS = "cate_view_columns";
    /** 自清播放缓存：退出/切后台时清 Glide 与 Exo 播放缓存（不清站源与历史） */
    public static final String AUTO_CLEAR_PLAY_CACHE = "auto_clear_play_cache";
    /** 首页LOGO显示 */
    public static final String HOME_LOGO = "home_logo";
    /** 首页LOGO圆形化 */
    public static final String HOME_LOGO_ROUND = "home_logo_round";
    /** 首页滑动切屏：内容区左右滑动切换分区 */
    public static final String HOME_SWIPE_SWITCH = "home_swipe_switch";
    /** 列表单片自动播放：点条目直接播，不进详情 */
    public static final String LIST_AUTO_PLAY = "list_auto_play";
    /** 沉浸式适配 */
    public static final String IMMERSIVE = "immersive";
    /** 站源短按多仓选：点站源时先选它来自哪个配置组 */
    public static final String SOURCE_TAP_MULTI = "source_tap_multi";
    /** 图片磁盘缓存策略：0=关闭/NONE 1=自动 2=仅缓存原图 3=仅缓存缩略 */
    public static final String GLIDE_CACHE = "glide_cache";
    /** 详情视图1只显标题：详情页只留标题行，隐藏简介与剧照区 */
    public static final String DETAIL_VIEW1_TITLE_ONLY = "detail_view1_title_only";
    /** 允许DLNA投屏 */
    public static final String DLNA_ALLOW = "dlna_allow";
    /** 源错误消息显示 */
    public static final String SRC_ERR_MSG = "src_err_msg";
    /** 源消息显示 */
    public static final String SRC_MSG = "src_msg";
    /** 歌词转换显示模式：0=单行 1=双行交替显示 2=关闭 */
    public static final String LYRICS_MODE = "lyrics_mode";
    /** 观看历史记录数档位：0=100|10 1=200|20 2=300|30 3=500|50 */
    public static final String HISTORY_LIMIT = "history_limit";
    /** 智能去除切片广告：HLS 清单里按广告特征跳过广告分片 */
    public static final String STRIP_AD_SEGMENTS = "strip_ad_segments";
    /** 播放错误时自动下集 */
    public static final String AUTO_NEXT_ON_ERROR = "auto_next_on_error";
    /** 设备名称（高级配置用，展示用） */
    public static final String DEVICE_NAME = "device_name";
    /** 直播仓库：每行“名称,列表链接” */
    public static final String LIVE_REPO = "live_repo";

    // ===== 旧版设置移植第二批（2026-11-09）：缺核心的连核心一起补 =====

    /** 嗅探View：0=系统自带 WebView（现行嗅探路径），1=XWalkView（需另下 Crosswalk 运行时，未接入，选了不切换） */
    public static final String SNIFFER_VIEW = "sniffer_view";
    /** 全局嗅探器：关时 ParseJob 不建 WebView 嗅探，解析直接失败（默认开） */
    public static final String GLOBAL_SNIFFER = "global_sniffer";
    /** 解析跳过防盗：开时解析结果不再走二次 WebView 嗅探校验，直接放行直链（默认开） */
    public static final String SKIP_ANTI_LEECH = "skip_anti_leech";
    /** 自动悬浮：开时切后台自动进画中画（默认关） */
    public static final String AUTO_FLOAT = "auto_float";
    /** 详情页样式：0=影音图片（海报毛玻璃+剧照） 1=简洁文字（藏剧照与毛玻璃背景） */
    public static final String DETAIL_STYLE = "detail_style";
    /** 播放缓存位置：0=默认（内置存储） 1=外部存储（外置缓存目录） */
    public static final String CACHE_LOCATION = "cache_location";
    /** 首页标题栏一直显示：关时内容下滑隐藏顶栏、上滑恢复 */
    public static final String HOME_TITLE_BAR = "home_title_bar";
    /** 直播短按转点播：开时点直播频道按台名进搜索页找点播，关时换台（长按始终直接换台） */
    public static final String LIVE_TO_VOD = "live_to_vod";
    /** 站源选择使用LOGO视图：开时站源列表显示站源 LOGO（取 api 域名 favicon），关时文字 */
    public static final String SOURCE_LOGO_VIEW = "source_logo_view";
    /** 投屏视频链接2：开时投屏走本地 /webResource 代理链接（电视拉不到直链/需防盗头时用），关时直链 */
    public static final String CAST_LINK2 = "cast_link2";
    /** P2P播放使用外部IP：开时 TorrServer 绑局域网 IP 并用它作播放地址，关时只绑 127.0.0.1 */
    public static final String P2P_EXTERNAL_IP = "p2p_external_ip";
    /** IJK内置字幕：开时 IJK 内嵌字幕渲染到播放器字幕层 */
    public static final String IJK_SUBTITLE = "ijk_subtitle";
    /** IJK-Surface重播：开时画面 Surface 重建后从断点重播，关时只重绑画面 */
    public static final String IJK_SURFACE_REPLAY = "ijk_surface_replay";
    /** 源数据缓存-分类和推荐 / -分类的列表 / -视频详情页：开时命中内存缓存，关时每次现取 */
    public static final String VOD_CACHE_HOME = "vod_cache_home";
    public static final String VOD_CACHE_CATE = "vod_cache_cate";
    public static final String VOD_CACHE_DETAIL = "vod_cache_detail";
    /** 后台刷新源数据缓存间隔（小时）：0=关闭 1/6/12/24 */
    public static final String VOD_CACHE_REFRESH_HOURS = "vod_cache_refresh_hours";
    /** 上次后台刷新源数据缓存的时间戳（毫秒） */
    public static final String VOD_CACHE_REFRESH_LAST = "vod_cache_refresh_last";
    /** 开机自动启动（Android 10+ 后台拉起可能被系统拦截，见回报） */
    public static final String BOOT_START = "boot_start";
    /** 分类筛选单行视图：开时每组筛选 chip 单行横滑，关时多行网格排布 */
    public static final String CATE_FILTER_SINGLE = "cate_filter_single";
    /** 分类筛选首页导航：关时分类 Tab 不含“主页”项 */
    public static final String CATE_HOME_NAV = "cate_home_nav";
    /** 分类列表长按菜单：开时长按卡片弹详情/直接播放/收藏菜单 */
    public static final String CATE_LONG_PRESS = "cate_long_press";
    /** 防失接口：主接口拉取失败时兜底用的备用订阅地址 */
    public static final String BACKUP_API_URL = "backup_api_url";
    /** 常驻站源：换订阅配置后仍保留的站（SourceBean JSON 列表） */
    public static final String RESIDENT_SITES = "resident_sites";
    /** 自定站源：用户手填的站（SourceBean JSON 列表），换配置后仍保留 */
    public static final String CUSTOM_SITES = "custom_sites";
    /** 自定站源的 key 集合（JSON 字符串列表，供禁用判断用） */
    public static final String CUSTOM_SITE_KEYS = "custom_site_keys";
    /** SITES 文件导入的站 key 集合（JSON 字符串列表，供禁用判断用） */
    public static final String SITES_FILE_KEYS = "sites_file_keys";
    /** 混合规则：每条“名称|站key1,站key2”（JSON 字符串列表），激活的规则名 */
    public static final String MIX_RULES = "mix_rules";
    public static final String MIX_ACTIVE = "mix_active";
    /** 站源禁用新增位：64=SITES文件 128=网址站源（自定/网址添加的站） */
    public static final int DISABLE_SITES_FILE = 64;
    public static final int DISABLE_URL_SITE = 128;
}
