package com.yingtu.plus.ui.fragment;

import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.orhanobut.hawk.Hawk;
import com.yingtu.plus.App;
import com.yingtu.plus.R;
import com.yingtu.plus.api.ApiConfig;
import com.yingtu.plus.api.BuiltinConfigs;
import com.yingtu.plus.api.VodConfigSync;
import com.yingtu.plus.base.BaseLazyFragment;
import com.yingtu.plus.bean.Movie;
import com.yingtu.plus.bean.MovieSort;
import com.yingtu.plus.bean.SourceBean;
import com.yingtu.plus.drama.DramaModels.DramaVideo;
import com.yingtu.plus.drama.DramaModels.FeedPage;
import com.yingtu.plus.drama.DramaSession;
import com.yingtu.plus.drama.DramaSites;
import com.yingtu.plus.drama.FanqieDramaClient;
import com.yingtu.plus.ui.activity.DetailActivity;
import com.yingtu.plus.ui.activity.DramaPlayerActivity;
import com.yingtu.plus.ui.activity.HomeActivity;
import com.yingtu.plus.ui.activity.LiveActivity;
import com.yingtu.plus.ui.activity.VideoActivity;
import com.yingtu.plus.ui.adapter.ChannelWallAdapter;
import com.yingtu.plus.ui.adapter.DramaFeedAdapter;
import com.yingtu.plus.ui.adapter.FilterChipAdapter;
import com.yingtu.plus.util.HawkConfig;
import com.yingtu.plus.util.SourceMsgGate;
import com.yingtu.plus.util.ZoneSites;
import com.yingtu.plus.viewmodel.SourceViewModel;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 频道页（潮影霓虹主题，结构对齐 moyi 源码 neon_channel_view.dart）：
 * 左侧栏（82dp）+ 筛选行 + 右侧交错墙。
 * 入口 getArguments() 的 "group"：all（影视）/ drama（短剧）/ midnight（午夜），无则 all。
 *
 * 固定站：影视组按 影视区当前站 → 频道页上次用过的站（CHANNEL_LAST_SITE_KEY，
 * 不限分区，用户在频道页用过 mjv011 这类午夜站也会被记住）→ 全局当前源
 * → 默认站 missavt（MissAV 蜘蛛站，站验唯一 good）解析；解析到的站记进
 * 频道上次站键，进频道页直接出数据，没选过任何站且默认站也不在才显示“请选择源”。
 * 左侧栏点按按 kind + 分类 id 匹配选中态（重建列表后高亮不丢）。
 *
 * 左侧栏：首项“热门”+ 当前站 homeContent 的分类（走 SourceViewModel.getSort()，
 * 与 HomeMainFragment 同款取数；分类名按源码 categoryName 归一，AI 系补空格，
 * 源码左栏里没有固定的“全部”项——截图里的“全部/推荐”是站自己的分类名，
 * 站回了自然会出现）+ 分隔后固定三组：短剧 / 午夜 / 直播。
 * 点内容分类 → 交错墙模式加载该分类；点短剧 → feed 模式（番茄流）；
 * 点午夜 → 交错墙模式加载午夜站；点直播 → LiveActivity。
 * 午夜分组无境界门禁：树里没有境界 / Realm 代码，按午夜页现状直接加载。
 *
 * 筛选行（源码 NeonChannelView._filterBar + catalog_sort_sheet.dart）：
 * 全部类型▾ / 热门 / 最新 / 完结四颗 pill，点的都是同一张“排序与筛选”底部表
 * （排序：默认顺序/名称/自然季号/上线日期/热度/播放量；状态：全部/连载中/
 * 已完结/状态未知），排序与筛选只作用于已加载的剧集（客户端排，不重新取数，
 * 不支持的站也能用；缺资料的条目排最后）。热门在无排序/筛选时高亮，
 * 一旦排过序或筛过状态就改亮“全部类型”，最新/完结在行里永远不高亮。
 * 右侧交错墙：每 3 张一组，左一张竖长大卡 + 右两张小卡（源码 _group，
 * 组高 306dp、只剩 1 张时 240dp），角标按 (组号+槽位)%4 轮换 HD/NEW。
 * 全程 try/catch：可见时初始化失败不许杀进程，记 App.logCaught，界面显示“加载失败”。
 */
public class ChannelFragment extends BaseLazyFragment {

    private static final int MODE_GRID_HOME = 0;
    private static final int MODE_GRID_CATEGORY = 1;
    private static final int MODE_FEED = 2;

    private static final int KIND_HOT = 0;
    private static final int KIND_SORT = 1;
    private static final int KIND_SEP = 2;
    private static final int KIND_DRAMA = 3;
    private static final int KIND_MIDNIGHT = 4;
    private static final int KIND_LIVE = 5;

    /** 排序模式（源码 CatalogSort：默认顺序/名称/自然季号/上线日期/热度/播放量）。 */
    private static final int SORT_SOURCE = 0;
    private static final int SORT_NAME = 1;
    private static final int SORT_SEASON = 2;
    private static final int SORT_DATE = 3;
    private static final int SORT_HEAT = 4;
    private static final int SORT_VIEWS = 5;
    private static final String[] SORT_LABELS =
            {"默认顺序", "名称", "自然季号", "上线日期", "热度", "播放量"};

    /** 剧集状态（源码 CatalogView.release："" 全部 / ongoing 连载中 /
     * finished 已完结 / unknown 状态未知）。 */
    private static final String RELEASE_ALL = "";
    private static final String RELEASE_ONGOING = "ongoing";
    private static final String RELEASE_FINISHED = "finished";
    private static final String RELEASE_UNKNOWN = "unknown";
    private static final String[] RELEASE_LABELS = {"全部", "连载中", "已完结", "状态未知"};
    private static final String[] RELEASE_VALUES =
            {RELEASE_ALL, RELEASE_ONGOING, RELEASE_FINISHED, RELEASE_UNKNOWN};

    /** 热度/播放量指标解析（源码 catalogMetric：数字 + 亿/万/千/w/k/m/b + 可选后缀）。 */
    private static final Pattern METRIC_PATTERN = Pattern.compile(
            "^(\\d+(?:\\.\\d+)?)([亿万千wkmb]?)(?:\\+)?(?:次播放|次观看|人看过|热度|播放|观看|次)?\\+?$");

    public static ChannelFragment newInstance(String group) {
        ChannelFragment f = new ChannelFragment();
        Bundle args = new Bundle();
        args.putString("group", group);
        f.setArguments(args);
        return f;
    }

    /** 默认站 key：missavt（MissAV 蜘蛛站，站验唯一 good；与 HomeMainFragment 同款默认站）。 */
    private static final String FALLBACK_SOURCE_KEY = "missavt";

    private TextView channelTitle;
    private TextView tvChannelSite;
    private LinearLayout filterRow;
    private TextView chipFilterAll, chipFilterLatest, chipFilterTop, chipFilterFree;
    private RecyclerView rvChannelCategory, rvChannelChips, rvChannelGrid, rvChannelFeed;
    private ProgressBar channelProgress;
    private TextView channelEmpty;

    private ChannelCategoryAdapter categoryAdapter;
    private FilterChipAdapter chipsAdapter;
    private ChannelWallAdapter wallAdapter;
    private DramaFeedAdapter feedAdapter;
    private SourceViewModel viewModel;

    private String group = "all";
    private String zone = ZoneSites.ZONE_MOVIE;
    private int mode = MODE_GRID_HOME;
    private boolean observersBound = false;

    // 网格模式状态
    private List<MovieSort> sorts = new ArrayList<>();
    private MovieSort curSort;
    private MovieSort.Filter chipsGroup;
    private String curTypeId;
    private final HashMap<String, String> curExtend = new HashMap<>();

    // 排序与筛选视图（源码 CatalogView）：作用于已加载的剧集，不重新取数；
    // 在页内一直有效（切分类/切站不重置，与源码全局 view 同款）。
    private int sortMode = SORT_SOURCE;
    private String releaseFilter = RELEASE_ALL;
    /** 最近一次取到的原始列表（排序/筛选的底数，listResult 回包时更新）。 */
    private List<Movie> baseMovies = new ArrayList<>();

    // 短剧 feed 状态（短剧页同款 feed 逻辑）
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Set<String> seenVids = new HashSet<>();
    private String cellId = "";
    private String sessionId = "";
    private String nextOffset = "";
    private boolean hasMore = true;
    private boolean loadingFeed;
    private boolean feedStarted;

    @Override
    protected int getLayoutId() {
        return R.layout.fragment_channel;
    }

    @Override
    protected void init(View root) {
        try {
            channelTitle = root.findViewById(R.id.channelTitle);
            tvChannelSite = root.findViewById(R.id.tvChannelSite);
            filterRow = root.findViewById(R.id.filterRow);
            chipFilterAll = root.findViewById(R.id.chipFilterAll);
            chipFilterLatest = root.findViewById(R.id.chipFilterLatest);
            chipFilterTop = root.findViewById(R.id.chipFilterTop);
            chipFilterFree = root.findViewById(R.id.chipFilterFree);
            rvChannelCategory = root.findViewById(R.id.rvChannelCategory);
            rvChannelChips = root.findViewById(R.id.rvChannelChips);
            rvChannelGrid = root.findViewById(R.id.rvChannelGrid);
            rvChannelFeed = root.findViewById(R.id.rvChannelFeed);
            channelProgress = root.findViewById(R.id.channelProgress);
            channelEmpty = root.findViewById(R.id.channelEmpty);

            Bundle args = getArguments();
            if (args != null && args.getString("group") != null) group = args.getString("group");
            if ("drama".equals(group)) zone = ZoneSites.ZONE_DRAMA;
            else if ("midnight".equals(group)) zone = ZoneSites.ZONE_MIDNIGHT;
            else { group = "all"; zone = ZoneSites.ZONE_MOVIE; }

            rvChannelCategory.setLayoutManager(new LinearLayoutManager(getContext()));
            categoryAdapter = new ChannelCategoryAdapter();
            categoryAdapter.setOnCatClick(this::onCatItemClick);
            rvChannelCategory.setAdapter(categoryAdapter);

            rvChannelChips.setLayoutManager(new LinearLayoutManager(getContext(), RecyclerView.HORIZONTAL, false));
            chipsAdapter = new FilterChipAdapter();
            chipsAdapter.setOnValueClick(value -> {
                if (chipsGroup != null) onFilterSelected(chipsGroup, value);
            });
            rvChannelChips.setAdapter(chipsAdapter);

            rvChannelGrid.setLayoutManager(new LinearLayoutManager(getContext()));
            wallAdapter = new ChannelWallAdapter();
            wallAdapter.setOnClick(this::openMovie);
            rvChannelGrid.setAdapter(wallAdapter);

            rvChannelFeed.setLayoutManager(new GridLayoutManager(getContext(), 3));
            feedAdapter = new DramaFeedAdapter();
            feedAdapter.setOnItemClick((video, position) -> {
                DramaSession.setFeed(feedAdapter.getData(), position);
                DramaSession.setCursor(cellId, sessionId, nextOffset, hasMore);
                DramaPlayerActivity.start(requireActivity());
            });
            rvChannelFeed.setAdapter(feedAdapter);
            rvChannelFeed.addOnScrollListener(new RecyclerView.OnScrollListener() {
                @Override
                public void onScrolled(@NonNull RecyclerView rv, int dx, int dy) {
                    if (dy <= 0 || loadingFeed || !hasMore) return;
                    GridLayoutManager lm = (GridLayoutManager) rv.getLayoutManager();
                    if (lm == null) return;
                    int last = lm.findLastVisibleItemPosition();
                    if (last >= feedAdapter.getItemCount() - 6) loadMoreFeed();
                }
            });

            // 筛选行四颗 pill（源码 NeonChannelView._filterBar）：点的都是同一张
            // “排序与筛选”底部表，选中态见 markFilterPills()。
            chipFilterAll.setOnClickListener(v -> showSortSheet());
            chipFilterLatest.setOnClickListener(v -> showSortSheet());
            chipFilterTop.setOnClickListener(v -> showSortSheet());
            chipFilterFree.setOnClickListener(v -> showSortSheet());

            if (tvChannelSite != null) {
                tvChannelSite.setOnClickListener(v -> {
                    try {
                        if (getActivity() instanceof HomeActivity) {
                            ((HomeActivity) getActivity()).openSourcePopupForZone(zone);
                        }
                    } catch (Throwable e) {
                        e.printStackTrace();
                    }
                });
            }

            viewModel = new ViewModelProvider(this).get(SourceViewModel.class);
            rebuildCategories();
        } catch (Throwable e) {
            e.printStackTrace();
            App.logCaught(e);
            try {
                if (channelEmpty != null) {
                    channelEmpty.setText("加载失败");
                    channelEmpty.setVisibility(View.VISIBLE);
                }
            } catch (Throwable ignored) {}
        }
    }

    @Override
    protected void onVisible() {
        if (getContext() == null || viewModel == null || wallAdapter == null) return;
        try {
            onVisibleInner();
        } catch (Throwable e) {
            e.printStackTrace();
            App.logCaught(e);
            if (channelEmpty != null) {
                channelEmpty.setText("加载失败");
                channelEmpty.setVisibility(View.VISIBLE);
            }
        }
    }

    private void onVisibleInner() {
        channelTitle.setText("drama".equals(group) ? "短剧" : "midnight".equals(group) ? "午夜" : "频道");
        bindObservers();
        if ("drama".equals(group)) {
            // 短剧组默认番茄 feed；同时 getSort() 拿当前全局站（若是短剧蜘蛛站）的分类进左侧栏。
            updateSiteName();
            viewModel.getSort();
            enterFeedMode();
            if (!feedStarted) {
                feedStarted = true;
                loadFirstFeed();
            }
            return;
        }
        if ("midnight".equals(group)) {
            SourceBean picked = pickMidnightSite();
            if (picked == null) {
                showGridEmpty("暂无午夜站");
                return;
            }
            ZoneSites.setCurrentSiteKey(ZoneSites.ZONE_MIDNIGHT, picked.getKey());
            ApiConfig.get().setSourceBean(picked);
            rememberChannelSite(picked.getKey());
            tvChannelSite.setText(picked.getName());
            mode = MODE_GRID_HOME;
            curSort = null;
            curTypeId = null;
            curExtend.clear();
            showGrid();
            channelProgress.setVisibility(View.VISIBLE);
            viewModel.getSort();
            return;
        }
        // 影视组：影视区当前站 → 频道页上次用过的站 → 全局当前源 → 默认站 missavt；
        // 解析到的站断言进全局，取数走它。都没解析到才显示“请选择源”。
        SourceBean movie = resolveMovieSource();
        if (movie == null) {
            updateSiteName();
            showGridEmpty("暂无站点，请先选站");
            return;
        }
        ApiConfig.get().setSourceBean(movie);
        rememberChannelSite(movie.getKey());
        tvChannelSite.setText(movie.getName());
        if (mode == MODE_FEED) {
            mode = MODE_GRID_HOME;
            curSort = null;
            curTypeId = null;
            curExtend.clear();
        }
        showGrid();
        channelProgress.setVisibility(View.VISIBLE);
        viewModel.getSort();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        io.shutdownNow();
    }

    private void bindObservers() {
        if (observersBound) return;
        if (viewModel == null) return;
        observersBound = true;
        viewModel.sortResult.observe(this, list -> {
            sorts = list == null ? new ArrayList<>() : list;
            rebuildCategories();
        });
        viewModel.listResult.observe(this, movies -> {
            if (mode == MODE_FEED) return;
            if (channelProgress != null) channelProgress.setVisibility(View.GONE);
            // 原始列表收进底数，排序/筛选由 applyCatalogView 客户端算，不重新取数。
            baseMovies = movies == null ? new ArrayList<>() : new ArrayList<>(movies);
            applyCatalogView();
        });
        viewModel.sortError.observe(this, msg -> {
            if (mode == MODE_FEED) return;
            if (msg == null || msg.isEmpty()) return;
            if (channelProgress != null) channelProgress.setVisibility(View.GONE);
            if (channelEmpty != null) {
                channelEmpty.setVisibility(View.VISIBLE);
                channelEmpty.setText("加载失败：" + msg);
            }
        });
    }

    /** 频道页上次用过的站：用户在频道页实际用过的站就记下来（不限分区），
     * 进频道页直接按它出数据，不用再点一次选站；切站、进页解析到站时都改写。 */
    private void rememberChannelSite(String siteKey) {
        if (TextUtils.isEmpty(siteKey)) return;
        try {
            Hawk.put(HawkConfig.CHANNEL_LAST_SITE_KEY, siteKey);
        } catch (Throwable ignored) {
        }
    }

    private String lastChannelSiteKey() {
        try {
            return Hawk.get(HawkConfig.CHANNEL_LAST_SITE_KEY, "");
        } catch (Throwable e) {
            return "";
        }
    }

    /** 影视区当前站 → 频道页上次用过的站（不限分区）→ 全局当前源（不限分区）
     * → 默认站 missavt；都没解析到返回 null（这时才显示“请选择源”）。 */
    private SourceBean resolveMovieSource() {
        String key = ZoneSites.getCurrentSiteKey(ZoneSites.ZONE_MOVIE);
        if (!TextUtils.isEmpty(key)) {
            SourceBean bean = ApiConfig.get().getSource(key);
            if (bean != null) return bean;
        }
        String last = lastChannelSiteKey();
        if (!TextUtils.isEmpty(last)) {
            SourceBean bean = ApiConfig.get().getSource(last);
            if (bean != null) return bean;
        }
        SourceBean home = ApiConfig.get().getHomeSource();
        if (home != null) return home;
        return ApiConfig.get().getSource(FALLBACK_SOURCE_KEY);
    }

    /** 午夜选站优先级（照午夜页 onVisible 原逻辑抄）：Intent 站 → 本区当前站 → 全局当前源（是午夜站才用）→ 首站。 */
    private SourceBean pickMidnightSite() {
        BuiltinConfigs.ensureLoaded(getContext());
        VodConfigSync.sync();
        List<SourceBean> sites = BuiltinConfigs.getMidnightSites();
        if (sites == null || sites.isEmpty()) return null;
        String pickKey = null;
        if (getActivity() != null && getActivity().getIntent() != null) {
            String extra = getActivity().getIntent()
                    .getStringExtra(HomeActivity.EXTRA_SITE_KEY);
            if (extra != null && BuiltinConfigs.isMidnightSite(extra)) {
                pickKey = extra;
                getActivity().getIntent().removeExtra(HomeActivity.EXTRA_SITE_KEY);
            }
        }
        if (pickKey == null) {
            String zoneKey = ZoneSites.getCurrentSiteKey(ZoneSites.ZONE_MIDNIGHT);
            if (!TextUtils.isEmpty(zoneKey) && BuiltinConfigs.isMidnightSite(zoneKey)) pickKey = zoneKey;
        }
        if (pickKey == null) {
            SourceBean home = ApiConfig.get().getHomeSource();
            if (home != null && BuiltinConfigs.isMidnightSite(home.getKey())) pickKey = home.getKey();
        }
        if (pickKey == null) pickKey = sites.get(0).getKey();
        for (SourceBean s : sites) {
            if (s.getKey().equals(pickKey)) return s;
        }
        return sites.get(0);
    }

    private void updateSiteName() {
        if (tvChannelSite == null) return;
        if ("drama".equals(group)) {
            tvChannelSite.setText("番茄短剧");
            return;
        }
        String name = ZoneSites.getCurrentSiteName(zone);
        if (name == null) {
            // 本区没记过站时显示频道页上次用过的站（与 resolveMovieSource 同款回退），
            // 不再一进频道就只剩“请选择源”。
            String last = lastChannelSiteKey();
            if (!TextUtils.isEmpty(last)) {
                SourceBean bean = ApiConfig.get().getSource(last);
                if (bean != null) name = bean.getName();
            }
        }
        tvChannelSite.setText(name == null ? "请选择源" : name);
    }

    private void updateTitle() {
        if (channelTitle == null) return;
        channelTitle.setText("drama".equals(group) ? "短剧" : "midnight".equals(group) ? "午夜" : "频道");
    }

    // ---------- 左侧栏 ----------

    /** 左侧栏：首项“热门”+ 当前站 homeContent 的全部分类
     * （走 SourceViewModel.getSort()，站回多少列多少，漫剧/AI 剧/小说/
     * 听书/冒险/剧情/古装… 都是站的真实分类；分类名按源码 categoryName 归一，
     * 只改显示文案）+ 分隔后固定三组：短剧 / 午夜 / 直播。
     * 没有固定的“全部”项（源码左栏 = 热门 + 站分类；截图里的“全部/推荐”
     * 是站自己的分类名，站回了自然会出现）。 */
    private void rebuildCategories() {
        if (categoryAdapter == null) return;
        List<CatItem> items = new ArrayList<>();
        items.add(new CatItem(KIND_HOT, null, "热门"));
        for (MovieSort s : sorts) items.add(new CatItem(KIND_SORT, s, normalizeCategoryName(s.typeName)));
        items.add(new CatItem(KIND_SEP, null, "— 分区 —"));
        items.add(new CatItem(KIND_DRAMA, null, "短剧"));
        items.add(new CatItem(KIND_MIDNIGHT, null, "午夜"));
        items.add(new CatItem(KIND_LIVE, null, "直播"));
        categoryAdapter.submit(items);
    }

    private void onCatItemClick(CatItem item) {
        if (item == null) return;
        try {
            // 左栏切组时同步 group/zone（站名与切站弹窗按它取区，旧实现只在 init 里设一次）。
            if (item.kind == KIND_DRAMA) { group = "drama"; zone = ZoneSites.ZONE_DRAMA; }
            else if (item.kind == KIND_MIDNIGHT) { group = "midnight"; zone = ZoneSites.ZONE_MIDNIGHT; }
            else if (item.kind == KIND_HOT || item.kind == KIND_SORT) { group = "all"; zone = ZoneSites.ZONE_MOVIE; }
            updateTitle();
            switch (item.kind) {
                case KIND_HOT:
                    categoryAdapter.setSelected(item);
                    mode = MODE_GRID_HOME;
                    curSort = null;
                    curTypeId = null;
                    curExtend.clear();
                    showGrid();
                    channelProgress.setVisibility(View.VISIBLE);
                    viewModel.getSort();
                    break;
                case KIND_SORT:
                    categoryAdapter.setSelected(item);
                    mode = MODE_GRID_CATEGORY;
                    curSort = item.sort;
                    curTypeId = item.sort.typeId;
                    curExtend.clear();
                    buildChips(item.sort);
                    showGrid();
                    channelProgress.setVisibility(View.VISIBLE);
                    viewModel.getList(curTypeId, 1, new HashMap<>(curExtend));
                    break;
                case KIND_DRAMA:
                    categoryAdapter.setSelected(item);
                    ZoneSites.setCurrentSiteKey(ZoneSites.ZONE_DRAMA, DramaSites.ORIGINAL_KEY);
                    tvChannelSite.setText("番茄短剧");
                    enterFeedMode();
                    if (!feedStarted) {
                        feedStarted = true;
                        loadFirstFeed();
                    }
                    break;
                case KIND_MIDNIGHT: {
                    categoryAdapter.setSelected(item);
                    SourceBean picked = pickMidnightSite();
                    if (picked == null) {
                        showGridEmpty("暂无午夜站");
                        break;
                    }
                    ZoneSites.setCurrentSiteKey(ZoneSites.ZONE_MIDNIGHT, picked.getKey());
                    ApiConfig.get().setSourceBean(picked);
                    rememberChannelSite(picked.getKey());
                    tvChannelSite.setText(picked.getName());
                    mode = MODE_GRID_HOME;
                    curSort = null;
                    curTypeId = null;
                    curExtend.clear();
                    showGrid();
                    channelProgress.setVisibility(View.VISIBLE);
                    viewModel.getSort();
                    break;
                }
                case KIND_LIVE:
                    startActivity(new Intent(getActivity(), LiveActivity.class));
                    break;
                default:
                    break;
            }
        } catch (Throwable e) {
            e.printStackTrace();
            App.logCaught(e);
            if (channelEmpty != null) {
                channelEmpty.setText("加载失败");
                channelEmpty.setVisibility(View.VISIBLE);
            }
        }
    }

    private void showGrid() {
        if (rvChannelGrid != null) rvChannelGrid.setVisibility(View.VISIBLE);
        if (rvChannelFeed != null) rvChannelFeed.setVisibility(View.GONE);
        if (channelEmpty != null) channelEmpty.setVisibility(View.GONE);
        // 筛选行在网格模式常驻（源码频道页顶上那行一直都在，不只在有筛选的分类里）。
        if (filterRow != null) filterRow.setVisibility(View.VISIBLE);
        markFilterPills();
        boolean showChips = mode == MODE_GRID_CATEGORY && curSort != null && curSort.hasFilter();
        if (rvChannelChips != null) {
            rvChannelChips.setVisibility(showChips && chipsGroup != null ? View.VISIBLE : View.GONE);
        }
    }

    private void showGridEmpty(String hint) {
        mode = MODE_GRID_HOME;
        curSort = null;
        curTypeId = null;
        if (rvChannelGrid != null) rvChannelGrid.setVisibility(View.VISIBLE);
        if (rvChannelFeed != null) rvChannelFeed.setVisibility(View.GONE);
        if (filterRow != null) filterRow.setVisibility(View.GONE);
        if (rvChannelChips != null) rvChannelChips.setVisibility(View.GONE);
        if (channelProgress != null) channelProgress.setVisibility(View.GONE);
        if (wallAdapter != null) wallAdapter.submit(null);
        if (channelEmpty != null) {
            channelEmpty.setVisibility(View.VISIBLE);
            channelEmpty.setText(hint);
        }
    }

    private void enterFeedMode() {
        mode = MODE_FEED;
        if (rvChannelFeed != null) rvChannelFeed.setVisibility(View.VISIBLE);
        if (rvChannelGrid != null) rvChannelGrid.setVisibility(View.GONE);
        if (filterRow != null) filterRow.setVisibility(View.GONE);
        if (rvChannelChips != null) rvChannelChips.setVisibility(View.GONE);
        if (channelProgress != null) channelProgress.setVisibility(View.GONE);
        if (channelEmpty != null) channelEmpty.setVisibility(View.GONE);
    }

    // ---------- 筛选 ----------

    /** chips 行：取当前分类的第一个 filter 组（照 HomeMainFragment.showFilters 的组内写法）。 */
    private void buildChips(MovieSort sort) {
        chipsGroup = null;
        if (sort == null || !sort.hasFilter()) {
            if (rvChannelChips != null) rvChannelChips.setVisibility(View.GONE);
            return;
        }
        chipsGroup = sort.filters.get(0);
        if (chipsAdapter != null) chipsAdapter.submit(chipsGroup.values);
        if (rvChannelChips != null) rvChannelChips.setVisibility(View.VISIBLE);
        markFilterPills();
    }

    /** 点击某组里的筛选 chip：只改该组的 extend 值，其余组保持（HomeMainFragment.onFilterSelected 同款）。 */
    private void onFilterSelected(MovieSort.Filter group, MovieSort.Value value) {
        if (group == null || group.key == null || curTypeId == null) return;
        if (value.v == null || value.v.isEmpty()) curExtend.remove(group.key);
        else curExtend.put(group.key, value.v);
        channelProgress.setVisibility(View.VISIBLE);
        if (wallAdapter != null) wallAdapter.submit(null);
        viewModel.getList(curTypeId, 1, new HashMap<>(curExtend));
    }

    // ---------- 排序与筛选（源码 CatalogView：只作用于已加载的剧集） ----------

    /** 把当前排序/筛选视图作用于底数并交墙显示（List.sort 是稳定排序，
     * 缺资料的条目自然排最后；筛空时提示“该筛选下暂无内容”）。 */
    private void applyCatalogView() {
        if (wallAdapter == null) return;
        List<Movie> view = new ArrayList<>(baseMovies);
        if (!releaseFilter.isEmpty()) {
            List<Movie> kept = new ArrayList<>();
            for (Movie m : view) {
                if (releaseFilter.equals(releaseStatusOf(m))) kept.add(m);
            }
            view = kept;
        }
        if (sortMode != SORT_SOURCE) {
            final int mode = sortMode;
            view.sort((a, b) -> compareBySort(a, b, mode));
        }
        wallAdapter.submit(view);
        if (channelEmpty != null) {
            boolean empty = view.isEmpty();
            channelEmpty.setVisibility(empty ? View.VISIBLE : View.GONE);
            if (empty) {
                channelEmpty.setText(baseMovies.isEmpty()
                        ? (curTypeId != null ? "该分类暂无内容" : "该源暂无推荐内容")
                        : "该筛选下暂无内容");
            }
        }
    }

    private boolean filtersActive() {
        return sortMode != SORT_SOURCE || !releaseFilter.isEmpty();
    }

    private int compareBySort(Movie a, Movie b, int mode) {
        switch (mode) {
            case SORT_NAME: return normalizedTitle(a.name).compareTo(normalizedTitle(b.name));
            case SORT_SEASON: return naturalCompare(a.name, b.name);
            case SORT_DATE: return compareDateDesc(a, b);
            case SORT_HEAT: return compareHeatDesc(a, b);
            case SORT_VIEWS: return compareMetricDesc(a, b);
            default: return 0;
        }
    }

    /** 上线日期：备注里的 YYYY-MM-DD 优先，年份次之；都没的排最后。 */
    private int compareDateDesc(Movie a, Movie b) {
        Long da = dateValueOf(a);
        Long db = dateValueOf(b);
        if (da != null && db != null) return Long.compare(db, da);
        if (da != null) return -1;
        if (db != null) return 1;
        return 0;
    }

    private Long dateValueOf(Movie m) {
        if (m.note != null) {
            Matcher d = Pattern.compile("(\\d{4})[-/](\\d{1,2})[-/](\\d{1,2})").matcher(m.note);
            if (d.find()) {
                try {
                    return Long.parseLong(d.group(1)) * 10000L
                            + Long.parseLong(d.group(2)) * 100L
                            + Long.parseLong(d.group(3));
                } catch (NumberFormatException ignored) {
                }
            }
        }
        if (m.year != null) {
            try {
                return Long.parseLong(m.year.trim()) * 10000L;
            } catch (NumberFormatException ignored) {
            }
        }
        return null;
    }

    /** 热度：备注里的播放量指标优先，其次评分；都没的排最后。 */
    private int compareHeatDesc(Movie a, Movie b) {
        int c = compareMetricDesc(a, b);
        if (c != 0) return c;
        Double ra = rateOf(a);
        Double rb = rateOf(b);
        if (ra != null && rb != null) return Double.compare(rb, ra);
        if (ra != null) return -1;
        if (rb != null) return 1;
        return 0;
    }

    /** 播放量：备注里的指标（源码 catalogMetric）；没指标的排最后。 */
    private int compareMetricDesc(Movie a, Movie b) {
        Double ma = catalogMetric(a.note);
        Double mb = catalogMetric(b.note);
        if (ma != null && mb != null) return Double.compare(mb, ma);
        if (ma != null) return -1;
        if (mb != null) return 1;
        return 0;
    }

    /** 备注里的播放量指标（源码 catalogMetric：如“1.2万次播放”→ 12000）；没有返回 null。 */
    private Double catalogMetric(String note) {
        if (note == null) return null;
        String cleaned = note.toLowerCase(Locale.ROOT).replaceAll("[,，\\s]", "");
        Matcher m = METRIC_PATTERN.matcher(cleaned);
        if (!m.matches()) return null;
        double num;
        try {
            num = Double.parseDouble(m.group(1));
        } catch (NumberFormatException e) {
            return null;
        }
        String unit = m.group(2);
        double mult = 1;
        if ("亿".equals(unit)) mult = 100000000;
        else if ("万".equals(unit) || "w".equals(unit)) mult = 10000;
        else if ("千".equals(unit) || "k".equals(unit)) mult = 1000;
        else if ("m".equals(unit)) mult = 1000000;
        else if ("b".equals(unit)) mult = 1000000000;
        return num * mult;
    }

    private Double rateOf(Movie m) {
        if (m.rate == null) return null;
        try {
            return Double.parseDouble(m.rate.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 剧集状态（备注判）：含 完结/全集/大结局 → 已完结；含 更新/连载/至 → 连载中；
     * 其余（只有年份/HD 之类）→ 状态未知。 */
    private String releaseStatusOf(Movie m) {
        String note = m.note == null ? "" : m.note;
        if (note.contains("完结") || note.contains("全集") || note.contains("大结局")) return RELEASE_FINISHED;
        if (note.contains("更新") || note.contains("连载") || note.contains("至")) return RELEASE_ONGOING;
        return RELEASE_UNKNOWN;
    }

    /** 标题归一（源码 normalizedSearchText 简化版）：去标点/空白、小写后比较。 */
    private String normalizedTitle(String title) {
        if (title == null) return "";
        return title.toLowerCase(Locale.ROOT)
                .replaceAll("[\\s\\p{Punct}，。！？、：；·「」『』（）【】《》“”‘’]", "");
    }

    /** 自然季号比较（源码 naturalTitleCompare 简化版）：标题按数字/非数字分段，
     * 数字段按数值比（“第2季” < “第10季”），其余按归一后字典序；只认阿拉伯数字。 */
    private int naturalCompare(String a, String b) {
        List<String> ra = splitRuns(normalizedTitle(a));
        List<String> rb = splitRuns(normalizedTitle(b));
        int n = Math.min(ra.size(), rb.size());
        for (int i = 0; i < n; i++) {
            String x = ra.get(i);
            String y = rb.get(i);
            int c;
            if (isDigits(x) && isDigits(y)) c = Long.compare(parseLongSafe(x), parseLongSafe(y));
            else c = x.compareTo(y);
            if (c != 0) return c;
        }
        return Integer.compare(ra.size(), rb.size());
    }

    private List<String> splitRuns(String s) {
        List<String> out = new ArrayList<>();
        int i = 0;
        while (i < s.length()) {
            int j = i;
            boolean digit = Character.isDigit(s.charAt(i));
            while (j < s.length() && Character.isDigit(s.charAt(j)) == digit) j++;
            out.add(s.substring(i, j));
            i = j;
        }
        return out;
    }

    private boolean isDigits(String s) {
        return s != null && !s.isEmpty() && Character.isDigit(s.charAt(0));
    }

    private long parseLongSafe(String s) {
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** 左栏分类名归一（源码 catalog_browser.categoryName）：AI 系补空格、空名变“未分类”。
     * 只改显示文案，匹配仍按 typeId，不影响取数。 */
    private String normalizeCategoryName(String name) {
        if (name == null) return "未分类";
        String compact = name.trim().replaceAll("\\s+", "");
        switch (compact) {
            case "AI成人短剧":
            case "AI短剧": return "AI 短剧";
            case "AI成人漫剧":
            case "AI漫剧": return "AI 漫剧";
            case "AI换脸": return "AI 换脸";
            case "AI魔改": return "AI 魔改";
            case "AI剧": return "AI 剧";
            case "": return "未分类";
            default: return name.trim();
        }
    }

    /** “排序与筛选”底部表（源码 catalog_sort_sheet.dart）：排序 6 项 + 状态 4 项，
     * 点“应用”才生效并回到顶部；表内改选只改待定值，不动当前视图。 */
    private void showSortSheet() {
        if (getContext() == null) return;
        final int[] pendingSort = {sortMode};
        final String[] pendingRelease = {releaseFilter};
        BottomSheetDialog dialog = new BottomSheetDialog(requireContext());
        View sheet = LayoutInflater.from(getContext()).inflate(R.layout.dialog_catalog_sort, null);
        LinearLayout sortRow1 = sheet.findViewById(R.id.sortRow1);
        LinearLayout sortRow2 = sheet.findViewById(R.id.sortRow2);
        LinearLayout releaseRow = sheet.findViewById(R.id.releaseRow);
        fillSheetChips(sortRow1, sortRow2, releaseRow, pendingSort, pendingRelease);
        sheet.findViewById(R.id.btnApplySort).setOnClickListener(v -> {
            sortMode = pendingSort[0];
            releaseFilter = pendingRelease[0];
            dialog.dismiss();
            markFilterPills();
            applyCatalogView();
            if (rvChannelGrid != null) rvChannelGrid.scrollToPosition(0);
        });
        dialog.setContentView(sheet);
        dialog.show();
    }

    private void fillSheetChips(LinearLayout sortRow1, LinearLayout sortRow2,
                                LinearLayout releaseRow, final int[] pendingSort,
                                final String[] pendingRelease) {
        sortRow1.removeAllViews();
        sortRow2.removeAllViews();
        releaseRow.removeAllViews();
        for (int i = 0; i < SORT_LABELS.length; i++) {
            final int index = i;
            TextView chip = makeSheetChip(SORT_LABELS[i], pendingSort[0] == i,
                    v -> {
                        pendingSort[0] = index;
                        fillSheetChips(sortRow1, sortRow2, releaseRow, pendingSort, pendingRelease);
                    });
            (i < 3 ? sortRow1 : sortRow2).addView(chip);
        }
        for (int i = 0; i < RELEASE_LABELS.length; i++) {
            final int index = i;
            TextView chip = makeSheetChip(RELEASE_LABELS[i],
                    RELEASE_VALUES[i].equals(pendingRelease[0]),
                    v -> {
                        pendingRelease[0] = RELEASE_VALUES[index];
                        fillSheetChips(sortRow1, sortRow2, releaseRow, pendingSort, pendingRelease);
                    });
            releaseRow.addView(chip);
        }
    }

    private TextView makeSheetChip(String text, boolean selected, View.OnClickListener onClick) {
        TextView chip = new TextView(getContext());
        chip.setText(text);
        chip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        chip.setGravity(Gravity.CENTER);
        chip.setClickable(true);
        chip.setFocusable(true);
        int padH = dp(12);
        int padV = dp(8);
        chip.setPadding(padH, padV, padH, padV);
        chip.setBackgroundResource(selected ? R.drawable.bg_chip_neon_selected : R.drawable.bg_chip_neon);
        chip.setTextColor(getResources().getColor(
                selected ? R.color.badge_text_dark : R.color.text_primary));
        chip.setTypeface(null, selected ? Typeface.BOLD : Typeface.NORMAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        int m = dp(4);
        lp.setMargins(m, 0, m, 0);
        chip.setLayoutParams(lp);
        chip.setOnClickListener(onClick);
        return chip;
    }

    private int dp(int value) {
        float density = getResources().getDisplayMetrics().density;
        return (int) (value * density + 0.5f);
    }

    /** 筛选行选中态（源码 NeonChannelView._filterBar）：热门在无任何排序/筛选时高亮，
     * 一旦排过序或筛过状态就改亮“全部类型”；最新/完结在行里永远不高亮（状态在表里看）。 */
    private void markFilterPills() {
        setPill(chipFilterAll, filtersActive());
        setPill(chipFilterTop, !filtersActive());
        setPill(chipFilterLatest, false);
        setPill(chipFilterFree, false);
    }

    private void setPill(TextView pill, boolean on) {
        if (pill == null) return;
        pill.setBackgroundResource(on ? R.drawable.bg_chip_neon_selected : R.drawable.bg_chip_neon);
        pill.setTextColor(getResources().getColor(on ? R.color.badge_text_dark : R.color.text_muted));
        pill.setTypeface(null, on ? Typeface.BOLD : Typeface.NORMAL);
    }

    /** 分区弹窗切站后由 HomeActivity 调：若切的是当前区的站，按新站重载当前视图。 */
    public void reloadForSourceChange(String pickedZone) {
        if (!isAdded() || viewModel == null) return;
        try {
            if (!zone.equals(pickedZone)) return;
            if (ZoneSites.ZONE_DRAMA.equals(zone)) {
                updateSiteName();
                return;
            }
            mode = MODE_GRID_HOME;
            curSort = null;
            curTypeId = null;
            curExtend.clear();
            showGrid();
            if (channelProgress != null) channelProgress.setVisibility(View.VISIBLE);
            viewModel.getSort();
            rememberChannelSite(ZoneSites.getCurrentSiteKey(zone));
            updateSiteName();
        } catch (Throwable e) {
            e.printStackTrace();
            com.yingtu.plus.App.logCaught(e);
        }
    }

    // ---------- 短剧 feed（短剧页同款 feed 逻辑，站用 ZONE_DRAMA 番茄流） ----------

    private void loadFirstFeed() {
        if (getContext() == null || loadingFeed) return;
        loadingFeed = true;
        if (channelEmpty != null) {
            channelEmpty.setVisibility(View.VISIBLE);
            channelEmpty.setText("正在加载短剧…");
        }
        io.execute(() -> {
            try {
                FanqieDramaClient client = new FanqieDramaClient(requireContext());
                FeedPage page = client.feedFirstPage();
                postFeed(page, true, null);
            } catch (Exception e) {
                postError(e);
            }
        });
    }

    private void loadMoreFeed() {
        if (getContext() == null || loadingFeed) return;
        loadingFeed = true;
        final String c = cellId;
        final String s = sessionId;
        final String o = nextOffset;
        io.execute(() -> {
            try {
                FanqieDramaClient client = new FanqieDramaClient(requireContext());
                FeedPage page = client.feedNextPage(c, s, o);
                postFeed(page, false, null);
            } catch (Exception e) {
                postFeed(null, false, e);
            }
        });
    }

    private void postFeed(FeedPage page, boolean first, Exception error) {
        if (!isAdded() || getActivity() == null) return;
        getActivity().runOnUiThread(() -> {
            loadingFeed = false;
            if (error != null) {
                if (first && channelEmpty != null) {
                    channelEmpty.setVisibility(View.VISIBLE);
                    channelEmpty.setText("短剧加载失败，点“短剧”重试");
                    SourceMsgGate.errorShort(getContext(), "短剧加载失败：" + error.getMessage());
                }
                return;
            }
            if (page == null) return;
            if (first) {
                cellId = page.cellId;
                sessionId = page.sessionId;
                seenVids.clear();
            }
            nextOffset = page.nextOffset;
            hasMore = page.hasMore;
            List<DramaVideo> fresh = new ArrayList<>();
            for (DramaVideo v : page.items) {
                if (v.vid != null && !v.vid.isEmpty() && seenVids.add(v.vid)) fresh.add(v);
            }
            if (first) feedAdapter.submit(fresh);
            else feedAdapter.append(fresh);
            if (channelEmpty != null) {
                if (feedAdapter.getItemCount() == 0) {
                    channelEmpty.setVisibility(View.VISIBLE);
                    channelEmpty.setText("暂无短剧");
                } else {
                    channelEmpty.setVisibility(View.GONE);
                }
            }
        });
    }

    private void postError(Exception e) {
        postFeed(null, true, e);
    }

    // ---------- 海报点按 ----------

    /** 海报点按：列表单片自动播放开时直接播，关时进详情（与 HomeMainFragment 同款目标）。 */
    private void openMovie(Movie movie) {
        if (movie == null || getActivity() == null) return;
        try {
            boolean autoPlay = false;
            try {
                autoPlay = Hawk.get(HawkConfig.LIST_AUTO_PLAY, false);
            } catch (Throwable ignored) {
            }
            if (autoPlay) {
                Intent i = new Intent(getActivity(), VideoActivity.class);
                i.putExtra("key", movie.sourceKey);
                i.putExtra("id", movie.vodId);
                i.putExtra("name", movie.name);
                i.putExtra("pic", movie.pic);
                startActivity(i);
                return;
            }
            Intent i = new Intent(getActivity(), DetailActivity.class);
            i.putExtra(DetailActivity.KEY_SOURCE, movie.sourceKey);
            i.putExtra(DetailActivity.KEY_VOD_ID, movie.vodId);
            startActivity(i);
        } catch (Throwable e) {
            e.printStackTrace();
        }
    }

    // ---------- 左侧栏数据与适配器 ----------

    private static class CatItem {
        final int kind;
        final MovieSort sort;
        final String label;

        CatItem(int kind, MovieSort sort, String label) {
            this.kind = kind;
            this.sort = sort;
            this.label = label;
        }

        /** 按值判同项：分类按 kind + typeId，分组/热门按 kind + 文案。
         * rebuildCategories() 每次都 new 新对象，旧实现拿引用判选中，
         * sortResult 一回包高亮就丢了，用户看着像“点按没反应”。 */
        boolean sameAs(CatItem o) {
            if (o == null || kind != o.kind) return false;
            if (kind == KIND_SORT) {
                String a = sort == null ? null : sort.typeId;
                String b = o.sort == null ? null : o.sort.typeId;
                return a != null && a.equals(b);
            }
            return label != null && label.equals(o.label);
        }
    }

    private class ChannelCategoryAdapter extends RecyclerView.Adapter<ChannelCategoryAdapter.VH> {

        public interface OnCatClick {
            void onClick(CatItem item);
        }

        private final List<CatItem> data = new ArrayList<>();
        private CatItem selected;
        private OnCatClick listener;

        public void setOnCatClick(OnCatClick l) {
            this.listener = l;
        }

        public void submit(List<CatItem> list) {
            data.clear();
            if (list != null) data.addAll(list);
            if (!data.isEmpty()) {
                if (selected == null) {
                    selected = data.get(0);
                } else {
                    // 列表重建后把选中态按值接回新对象上（旧对象已不在列表里），
                    // 接不回（换站后分类变了）才回落到首项“热门”。
                    CatItem keep = null;
                    for (CatItem it : data) {
                        if (it.sameAs(selected)) { keep = it; break; }
                    }
                    selected = keep != null ? keep : data.get(0);
                }
            }
            notifyDataSetChanged();
        }

        public void setSelected(CatItem item) {
            if (item == null || item.kind == KIND_SEP) return;
            selected = item;
            notifyDataSetChanged();
        }

        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = getLayoutInflater().inflate(R.layout.item_channel_category, parent, false);
            return new VH(v);
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int position) {
            CatItem item = data.get(position);
            h.tvName.setText(item.label == null ? "" : item.label);
            boolean sel = item.sameAs(selected);
            // 选中态：青色底 pill + 深色字（参考图）；未选中透明底 + 暗紫灰字。
            h.itemView.setBackgroundResource(sel ? R.drawable.bg_chip_neon_selected : 0);
            h.tvName.setTextColor(getResources().getColor(
                    sel ? R.color.bg_dark : R.color.text_muted));
            h.tvName.setTypeface(null, sel ? android.graphics.Typeface.BOLD
                    : android.graphics.Typeface.NORMAL);
            // 左栏小图标：分隔行不显示；热门/分类/三组各取一个现成图标。
            if (item.kind == KIND_SEP) {
                h.ivIcon.setVisibility(View.GONE);
            } else {
                h.ivIcon.setVisibility(View.VISIBLE);
                h.ivIcon.setImageResource(iconFor(item.kind));
                h.ivIcon.setImageTintList(ColorStateList.valueOf(getResources().getColor(
                        sel ? R.color.bg_dark : R.color.text_muted)));
            }
            h.itemView.setOnClickListener(v -> {
                if (item.kind == KIND_SEP) return;
                if (listener != null) listener.onClick(item);
            });
        }

        /** 左栏图标（源码左栏只用两种：热门火焰、其余场记板；树里没有火焰/场记板，
         * 用现成的播圈/文件夹顶上）：热门播圈、分类统一文件夹；短剧/午夜/直播
         * 是影图自有的分区项（源码没有），各取一个现成图标区分。 */
        private int iconFor(int kind) {
            switch (kind) {
                case KIND_HOT: return R.drawable.ic_play_circle;
                case KIND_SORT: return R.drawable.ic_folder;
                case KIND_DRAMA: return R.drawable.ic_play;
                case KIND_MIDNIGHT: return R.drawable.ic_history;
                case KIND_LIVE: return R.drawable.ic_live;
                default: return R.drawable.ic_folder;
            }
        }

        @Override
        public int getItemCount() {
            return data.size();
        }

        class VH extends RecyclerView.ViewHolder {
            ImageView ivIcon;
            TextView tvName;

            VH(@NonNull View itemView) {
                super(itemView);
                ivIcon = itemView.findViewById(R.id.ivChannelCategoryIcon);
                tvName = itemView.findViewById(R.id.tvChannelCategory);
            }
        }
    }
}
