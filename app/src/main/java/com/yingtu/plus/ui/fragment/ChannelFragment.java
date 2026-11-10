package com.yingtu.plus.ui.fragment;

import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

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
import com.yingtu.plus.ui.adapter.DramaFeedAdapter;
import com.yingtu.plus.ui.adapter.FilterChipAdapter;
import com.yingtu.plus.ui.adapter.NeonPosterAdapter;
import com.yingtu.plus.util.HawkConfig;
import com.yingtu.plus.util.SourceMsgGate;
import com.yingtu.plus.util.ZoneSites;
import com.yingtu.plus.viewmodel.SourceViewModel;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 频道页（潮影霓虹主题）：左侧栏 + 内容区。
 * 入口 getArguments() 的 "group"：all（影视）/ drama（短剧）/ midnight（午夜），无则 all。
 *
 * 左侧栏：首项“热门”+ 当前站 homeContent 的分类（走 SourceViewModel.getSort()，
 * 与 HomeMainFragment 同款取数）+ 分隔后固定三组：短剧 / 午夜 / 直播。
 * 点内容分类 → 网格模式加载该分类；点短剧 → feed 模式（番茄流）；
 * 点午夜 → 网格模式加载午夜站；点直播 → LiveActivity。
 * 午夜分组无境界门禁：树里没有境界 / Realm 代码，按午夜页现状直接加载。
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

    public static ChannelFragment newInstance(String group) {
        ChannelFragment f = new ChannelFragment();
        Bundle args = new Bundle();
        args.putString("group", group);
        f.setArguments(args);
        return f;
    }

    private TextView channelTitle;
    private TextView tvChannelSite;
    private LinearLayout filterRow;
    private TextView chipFilterAll, chipFilterLatest, chipFilterTop, chipFilterFree;
    private RecyclerView rvChannelCategory, rvChannelChips, rvChannelGrid, rvChannelFeed;
    private ProgressBar channelProgress;
    private TextView channelEmpty;

    private ChannelCategoryAdapter categoryAdapter;
    private FilterChipAdapter chipsAdapter;
    private NeonPosterAdapter gridAdapter;
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

            rvChannelGrid.setLayoutManager(new GridLayoutManager(getContext(), 3));
            gridAdapter = new NeonPosterAdapter();
            gridAdapter.setOnClick(this::openMovie);
            rvChannelGrid.setAdapter(gridAdapter);

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

            chipFilterAll.setOnClickListener(v -> {
                curExtend.clear();
                markQuickChip(chipFilterAll);
                reloadCurrent();
            });
            chipFilterLatest.setOnClickListener(v -> onQuickFilter(chipFilterLatest, "最新"));
            chipFilterTop.setOnClickListener(v -> onQuickFilter(chipFilterTop, "最热", "热门", "热播", "排行"));
            chipFilterFree.setOnClickListener(v -> onQuickFilter(chipFilterFree, "免费"));

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
        if (getContext() == null || viewModel == null || gridAdapter == null) return;
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
        // 影视组：影视区当前站优先（断言进全局，取数走它）；没有则认领全局当前源（须是影视区站）。
        SourceBean movie = resolveMovieSource();
        if (movie == null) {
            updateSiteName();
            showGridEmpty("暂无站点，请先选站");
            return;
        }
        ApiConfig.get().setSourceBean(movie);
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
            boolean empty = movies == null || movies.isEmpty();
            if (channelEmpty != null) {
                channelEmpty.setVisibility(empty ? View.VISIBLE : View.GONE);
                if (empty) channelEmpty.setText(curTypeId != null ? "该分类暂无内容" : "该源暂无推荐内容");
            }
            if (gridAdapter != null) gridAdapter.submit(movies);
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

    /** 影视区当前站 → 全局当前源（须是影视区站才认领）；都没有返回 null。 */
    private SourceBean resolveMovieSource() {
        String key = ZoneSites.getCurrentSiteKey(ZoneSites.ZONE_MOVIE);
        if (!TextUtils.isEmpty(key)) {
            SourceBean bean = ApiConfig.get().getSource(key);
            if (bean != null) return bean;
        }
        SourceBean home = ApiConfig.get().getHomeSource();
        if (home != null && ZoneSites.isZoneSite(ZoneSites.ZONE_MOVIE, home.getKey())) return home;
        return null;
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
        tvChannelSite.setText(name == null ? "请选择源" : name);
    }

    private void updateTitle() {
        if (channelTitle == null) return;
        channelTitle.setText("drama".equals(group) ? "短剧" : "midnight".equals(group) ? "午夜" : "频道");
    }

    // ---------- 左侧栏 ----------

    private void rebuildCategories() {
        if (categoryAdapter == null) return;
        List<CatItem> items = new ArrayList<>();
        items.add(new CatItem(KIND_HOT, null, "热门"));
        for (MovieSort s : sorts) items.add(new CatItem(KIND_SORT, s, s.typeName));
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
        boolean showFilter = mode == MODE_GRID_CATEGORY && curSort != null && curSort.hasFilter();
        if (filterRow != null) filterRow.setVisibility(showFilter ? View.VISIBLE : View.GONE);
        if (rvChannelChips != null) {
            rvChannelChips.setVisibility(showFilter && chipsGroup != null ? View.VISIBLE : View.GONE);
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
        if (gridAdapter != null) gridAdapter.submit(null);
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
    }

    /** 点击某组里的筛选 chip：只改该组的 extend 值，其余组保持（HomeMainFragment.onFilterSelected 同款）。 */
    private void onFilterSelected(MovieSort.Filter group, MovieSort.Value value) {
        if (group == null || group.key == null || curTypeId == null) return;
        if (value.v == null || value.v.isEmpty()) curExtend.remove(group.key);
        else curExtend.put(group.key, value.v);
        channelProgress.setVisibility(View.VISIBLE);
        if (gridAdapter != null) gridAdapter.submit(null);
        viewModel.getList(curTypeId, 1, new HashMap<>(curExtend));
    }

    /**
     * 快捷筛选（最新 / 最热 / 免费）→ extend 参数的映射：
     * 先在“排序”组里找（key 含 sort/order 或组名含“排序”），该组的值名含关键词
     * 即命中；没有排序组时全组扫值名。命中则把该组 key 置为该值 v 后重载；
     * 某站不支持该排序（找不到对应值）时退化成重载当前分类，不闪退。
     */
    private void onQuickFilter(TextView chip, String... keywords) {
        markQuickChip(chip);
        if (mode == MODE_FEED || curSort == null || curTypeId == null) {
            reloadCurrent();
            return;
        }
        MovieSort.Filter hitGroup = null;
        MovieSort.Value hitValue = null;
        List<MovieSort.Filter> groups = curSort.filters;
        if (groups != null) {
            for (MovieSort.Filter g : groups) {
                if (g == null) continue;
                String key = g.key == null ? "" : g.key.toLowerCase();
                String name = g.name == null ? "" : g.name;
                if (key.contains("sort") || key.contains("order") || name.contains("排序")) {
                    MovieSort.Value v = findValueByKeywords(g, keywords);
                    if (v != null) { hitGroup = g; hitValue = v; break; }
                }
            }
            if (hitGroup == null) {
                for (MovieSort.Filter g : groups) {
                    if (g == null) continue;
                    MovieSort.Value v = findValueByKeywords(g, keywords);
                    if (v != null) { hitGroup = g; hitValue = v; break; }
                }
            }
        }
        if (hitGroup != null && hitValue != null) {
            if (hitValue.v == null || hitValue.v.isEmpty()) curExtend.remove(hitGroup.key);
            else curExtend.put(hitGroup.key, hitValue.v);
        }
        reloadCurrent();
    }

    private MovieSort.Value findValueByKeywords(MovieSort.Filter group, String... keywords) {
        if (group.values == null) return null;
        for (MovieSort.Value v : group.values) {
            if (v == null || v.n == null) continue;
            for (String kw : keywords) {
                if (v.n.contains(kw)) return v;
            }
        }
        return null;
    }

    private void reloadCurrent() {
        if (viewModel == null) return;
        channelProgress.setVisibility(View.VISIBLE);
        if (gridAdapter != null) gridAdapter.submit(null);
        if (mode == MODE_GRID_CATEGORY && curTypeId != null) {
            viewModel.getList(curTypeId, 1, new HashMap<>(curExtend));
        } else {
            mode = MODE_GRID_HOME;
            viewModel.getSort();
        }
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
            updateSiteName();
        } catch (Throwable e) {
            e.printStackTrace();
            com.yingtu.plus.App.logCaught(e);
        }
    }

    private void markQuickChip(TextView selected) {
        TextView[] chips = {chipFilterAll, chipFilterLatest, chipFilterTop, chipFilterFree};
        for (TextView t : chips) {
            if (t == null) continue;
            boolean on = t == selected;
            t.setTextColor(getResources().getColor(on ? R.color.neon_pink : R.color.text_muted));
            t.setTypeface(null, on ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
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
            if (selected == null && !data.isEmpty()) selected = data.get(0);
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
            boolean sel = item == selected;
            h.tvName.setTextColor(getResources().getColor(
                    sel ? R.color.neon_pink : R.color.text_muted));
            h.tvName.setTypeface(null, sel ? android.graphics.Typeface.BOLD
                    : android.graphics.Typeface.NORMAL);
            h.itemView.setOnClickListener(v -> {
                if (item.kind == KIND_SEP) return;
                if (listener != null) listener.onClick(item);
            });
        }

        @Override
        public int getItemCount() {
            return data.size();
        }

        class VH extends RecyclerView.ViewHolder {
            TextView tvName;

            VH(@NonNull View itemView) {
                super(itemView);
                tvName = itemView.findViewById(R.id.tvChannelCategory);
            }
        }
    }
}
