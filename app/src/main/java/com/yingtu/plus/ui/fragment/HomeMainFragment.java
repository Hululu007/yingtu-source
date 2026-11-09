package com.yingtu.plus.ui.fragment;

import android.content.Intent;
import android.util.TypedValue;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.orhanobut.hawk.Hawk;
import com.yingtu.plus.R;
import com.yingtu.plus.api.ApiConfig;
import com.yingtu.plus.base.BaseLazyFragment;
import com.yingtu.plus.bean.FavVod;
import com.yingtu.plus.bean.Movie;
import com.yingtu.plus.bean.MovieSort;
import com.yingtu.plus.bean.SourceBean;
import com.yingtu.plus.ui.activity.DetailActivity;
import com.yingtu.plus.ui.activity.HomeActivity;
import com.yingtu.plus.ui.activity.VideoActivity;
import com.yingtu.plus.ui.adapter.CategoryTabAdapter;
import com.yingtu.plus.ui.adapter.FilterChipAdapter;
import com.yingtu.plus.ui.adapter.GridMovieAdapter;
import com.yingtu.plus.util.FavHelper;
import com.yingtu.plus.util.HawkConfig;
import com.yingtu.plus.util.MixRules;
import com.yingtu.plus.util.ZoneSites;
import com.yingtu.plus.viewmodel.SourceViewModel;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

/**
 * 「影视」页（底部导航第一项）：
 *
 *  - 顶部：当前源的分类标签行（主页 + 各 class；选中且该分类带 filters 时标题显示 ▽）。
 *  - 其下：选中分类的筛选标签区——一组 filter 一行（组名 + 横向 chip 行），
 *    每组独立选中，选中的值按组 key 合进 extend 传给 categoryContent；
 *    无 filter 的分类隐藏整个筛选区。
 *  - 海报墙（推荐/分类列表，上滑到底自动翻页）。
 *  - 未选源：仅引导。
 */
public class HomeMainFragment extends BaseLazyFragment {

    private LinearLayout filterContainer;
    private RecyclerView rvCategory, rvContent;
    private CategoryTabAdapter categoryAdapter;
    private GridMovieAdapter adapter;
    private ProgressBar progress;
    private TextView tvHint;
    private SourceViewModel viewModel;

    private String currentSourceKey = "";
    private boolean observersBound = false;

    // 每组 filter 各一个 chip 适配器（独立选中态）
    private final List<FilterChipAdapter> filterAdapters = new ArrayList<>();

    // 分页/筛选状态
    private String curTypeId = null;          // null = 首页推荐
    private int curPage = 1;
    private boolean loading = false;
    private boolean noMore = false;
    private final HashMap<String, String> curExtend = new HashMap<>();
    /** 混合首页模式：分类 Tab 隐藏，只显合并的推荐列表 */
    private boolean mixedMode = false;

    @Override
    protected int getLayoutId() {
        return R.layout.fragment_home_main;
    }

    @Override
    protected void init(View root) {
        // 闪退加固（2.0.0.1108 第三轮）：启动链兜底，整段包 try/catch，
        // 任何漏网的异常都退化成空首页，不杀进程。顺序与正常路径不变。
        try {
            filterContainer = root.findViewById(R.id.filterContainer);
            rvCategory = root.findViewById(R.id.rvCategory);
            rvContent = root.findViewById(R.id.rvContent);
            progress = root.findViewById(R.id.progress);
            tvHint = root.findViewById(R.id.tvHint);

            rvCategory.setLayoutManager(new LinearLayoutManager(getContext(), RecyclerView.HORIZONTAL, false));
            categoryAdapter = new CategoryTabAdapter();
            categoryAdapter.setOnTabClick(this::onCategorySelected);
            rvCategory.setAdapter(categoryAdapter);

            GridLayoutManager glm = new GridLayoutManager(getContext(), 3);
            rvContent.setLayoutManager(glm);
            adapter = new GridMovieAdapter();
            adapter.setOnItemClick(movie -> {
                // 列表单片自动播放（设置 → 更多设置）：开时点一下直接播，关时进详情
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
            });
            adapter.setOnItemLongClick(movie -> {
                // 分类列表长按菜单（设置 → 更多设置）：开时长按弹详情/直接播放/收藏
                boolean on = true;
                try {
                    on = Hawk.get(HawkConfig.CATE_LONG_PRESS, true);
                } catch (Throwable ignored) {
                }
                if (!on || getActivity() == null) return false;
                new AlertDialog.Builder(getActivity())
                        .setTitle(movie.name)
                        .setItems(new String[]{"详情", "直接播放", "加入收藏"}, (d, which) -> {
                            if (which == 0) {
                                Intent i = new Intent(getActivity(), DetailActivity.class);
                                i.putExtra(DetailActivity.KEY_SOURCE, movie.sourceKey);
                                i.putExtra(DetailActivity.KEY_VOD_ID, movie.vodId);
                                startActivity(i);
                            } else if (which == 1) {
                                Intent i = new Intent(getActivity(), VideoActivity.class);
                                i.putExtra("key", movie.sourceKey);
                                i.putExtra("id", movie.vodId);
                                i.putExtra("name", movie.name);
                                i.putExtra("pic", movie.pic);
                                startActivity(i);
                            } else {
                                FavHelper.toggleVod(new FavVod(movie.sourceKey, movie.vodId, movie.name, movie.pic));
                            }
                        })
                        .show();
                return true;
            });
            rvContent.setAdapter(adapter);
            rvContent.addOnScrollListener(new RecyclerView.OnScrollListener() {
                @Override
                public void onScrolled(RecyclerView rv, int dx, int dy) {
                    // 首页标题栏一直显示关时：下滑隐藏顶栏，上滑恢复
                    if (getActivity() instanceof HomeActivity) {
                        HomeActivity home = (HomeActivity) getActivity();
                        if (!home.isTopBarAlwaysVisible()) {
                            if (dy > 8) home.setTopBarVisible(false);
                            else if (dy < -8) home.setTopBarVisible(true);
                        }
                    }
                    if (dy <= 0 || loading || noMore || curTypeId == null) return;
                    RecyclerView.LayoutManager lm = rvContent.getLayoutManager();
                    int last = -1;
                    if (lm instanceof GridLayoutManager) last = ((GridLayoutManager) lm).findLastVisibleItemPosition();
                    else if (lm instanceof LinearLayoutManager) last = ((LinearLayoutManager) lm).findLastVisibleItemPosition();
                    if (last >= adapter.getDataCount() - 3) loadMore();
                }
            });
            // 首页滑动切屏（设置 → 更多设置）：内容区横向快速滑动切区（不消费事件，列表滚动/点击照常）
            final GestureDetector swipeDetector = new GestureDetector(getContext(), new GestureDetector.SimpleOnGestureListener() {
                @Override
                public boolean onFling(MotionEvent e1, MotionEvent e2, float velocityX, float velocityY) {
                    if (e1 == null || e2 == null) return false;
                    float dx = e2.getX() - e1.getX();
                    float dy = e2.getY() - e1.getY();
                    if (Math.abs(dx) > 120 && Math.abs(dx) > Math.abs(dy) * 2 && Math.abs(velocityX) > 200) {
                        if (getActivity() instanceof HomeActivity) {
                            ((HomeActivity) getActivity()).swipeZone(dx < 0);
                            return true;
                        }
                    }
                    return false;
                }
            });
            rvContent.setOnTouchListener((v, event) -> {
                swipeDetector.onTouchEvent(event);
                return false;
            });

            viewModel = new ViewModelProvider(this).get(SourceViewModel.class);
        } catch (Throwable e) {
            e.printStackTrace();
            com.yingtu.plus.App.logCaught(e);
            try {
                if (tvHint != null) {
                    tvHint.setText("加载失败，请稍后重试");
                    tvHint.setVisibility(View.VISIBLE);
                }
            } catch (Throwable ignored) {}
        }
    }

    private void bindObservers() {
        if (observersBound) return;
        observersBound = true;
        viewModel.sortResult.observe(this, sorts -> {
            progress.setVisibility(View.GONE);
            renderCategories(sorts);
        });
        viewModel.listResult.observe(this, movies -> {
            loading = false;
            progress.setVisibility(View.GONE);
            if (curPage > 1) {
                // 翻页：追加
                if (movies == null || movies.isEmpty()) { noMore = true; return; }
                adapter.append(movies);
            } else {
                // 首页推荐=豆瓣热播时只留带豆瓣热播角标的条目（站源不带角标时列表为空）
                List<Movie> shown = movies;
                if (curTypeId == null && isDoubanRec()) {
                    shown = new ArrayList<>();
                    if (movies != null) {
                        for (Movie m : movies) if (m.douban) shown.add(m);
                    }
                }
                boolean empty = shown == null || shown.isEmpty();
                tvHint.setVisibility(empty ? View.VISIBLE : View.GONE);
                if (empty) tvHint.setText(homeEmptyHint());
                adapter.submit(shown);
                rvContent.scrollToPosition(0);
            }
        });
        // 首页取数报错时（在 listResult 之后注册，后到先显示）：用可读原因盖掉
        // “该源暂无推荐内容”，用户和 crash.log 里都能看到错在哪。
        viewModel.sortError.observe(this, msg -> {
            if (msg == null || msg.isEmpty()) return;
            tvHint.setVisibility(View.VISIBLE);
            tvHint.setText("加载失败：" + msg);
        });
    }

    /** 首页推荐为空时的提示：取数报错显示原因，否则显示“暂无推荐内容”。 */
    private String homeEmptyHint() {
        if (curTypeId != null) return "该分类暂无内容";
        String err = viewModel == null ? null : viewModel.sortError.getValue();
        if (err != null && !err.isEmpty()) return "加载失败：" + err;
        if (isDoubanRec()) return "该源暂无豆瓣热播内容";
        return "该源暂无推荐内容";
    }

    /** 首页推荐=豆瓣热播（设置 → 首页和搜索）：只显示带豆瓣热播角标的条目。 */
    private boolean isDoubanRec() {
        try {
            return "豆瓣热播".equals(Hawk.get(HawkConfig.HOME_REC, "站点推荐"));
        } catch (Throwable e) {
            return false;
        }
    }

    @Override
    protected void onVisible() {
        // init 失败过则控件还没建好，这里直接返回，留空首页，不再崩一次
        if (rvCategory == null || filterContainer == null || tvHint == null || adapter == null) return;
        // 分类视图类型/列数（设置 → 系统设置）：每次回到本页按当前设置重设
        applyCateView();
        SourceBean home = zoneSource();
        String key = home == null ? "" : home.getKey();
        if (home == null) {
            rvCategory.setVisibility(View.GONE);
            filterContainer.setVisibility(View.GONE);
            tvHint.setVisibility(View.VISIBLE);
            tvHint.setText(R.string.home_no_source);
            adapter.submit(null);
            currentSourceKey = "";
            return;
        }
        if (key.equals(currentSourceKey)) return;
        currentSourceKey = key;
        bindObservers();
        loadHome();
    }

    /**
     * 本页当前站：被午夜/短剧区内嵌时用全局当前源（分区页切站时写进去）；
     * 顶层影视页用影视区独立记的站——午夜/短剧区切站不再顶掉影视区的站，
     * 影视区没选站时显示空引导，不拿别的区的站来凑数。
     */
    private SourceBean zoneSource() {
        if (getParentFragment() != null) return ApiConfig.get().getHomeSource();
        String key = ZoneSites.getCurrentSiteKey(ZoneSites.ZONE_MOVIE);
        if (key == null || key.isEmpty()) return null;
        return ApiConfig.get().getSource(key);
    }

    /** 由 HomeActivity 在站源抽屉切源后调用，强制重载首页内容。 */
    public void reloadForSourceChange() {
        if (!isAdded()) return;
        SourceBean home = zoneSource();
        if (home == null) return;
        currentSourceKey = home.getKey();
        bindObservers();
        loadHome();
    }

    private void loadHome() {
        tvHint.setVisibility(View.GONE);
        adapter.submit(null);
        rvCategory.setVisibility(View.GONE);
        filterContainer.setVisibility(View.GONE);
        curTypeId = null;
        curPage = 1;
        noMore = false;
        curExtend.clear();
        progress.setVisibility(View.VISIBLE);
        // 混合规则（高级配置 → 混合规则）：有激活规则时逐站取首页推荐合并，分类 Tab 隐藏
        MixRules.Rule rule = MixRules.getActiveRule();
        if (rule != null) {
            List<SourceBean> members = new ArrayList<>();
            for (String k : rule.keys) {
                SourceBean b = ApiConfig.get().getSource(k);
                if (b != null) members.add(b);
            }
            if (members.size() >= 2) {
                mixedMode = true;
                viewModel.getSortMixed(members);
                return;
            }
        }
        mixedMode = false;
        viewModel.getSort();
    }

    /** 分类视图类型/列数（设置 → 系统设置）：按类型+方案设展示形态与列数。 */
    private void applyCateView() {
        if (adapter == null || rvContent == null || getContext() == null) return;
        int type = 0;
        int cols = 1;
        try {
            type = Hawk.get(HawkConfig.CATE_VIEW_TYPE, 0);
            cols = Hawk.get(HawkConfig.CATE_VIEW_COLUMNS, 1);
        } catch (Throwable ignored) {
        }
        // 视图类型 → 展示形态：文字视图=文字，其余=图文；横图列本页没有对应视图（回报写明）
        int mode = type == 1 ? 0 : 2;
        adapter.setDisplayMode(mode);
        // 列数方案 [竖图|横图|文字|详情]：本页是竖图卡片取第一档，文字视图用线性列表
        int[][] schemes = {{2, 1, 1, 1}, {3, 2, 2, 2}, {3, 2, 3, 2}};
        int[] s = schemes[cols >= 0 && cols < schemes.length ? cols : 1];
        if (mode == 0) {
            rvContent.setLayoutManager(new LinearLayoutManager(getContext()));
        } else {
            rvContent.setLayoutManager(new GridLayoutManager(getContext(), s[0]));
        }
    }

    private void renderCategories(List<MovieSort> sorts) {
        java.util.List<MovieSort> list = new java.util.ArrayList<>();
        // 分类筛选首页导航（设置 → 更多设置）：关时分类 Tab 不含“主页”项
        boolean homeNav = true;
        try {
            homeNav = Hawk.get(HawkConfig.CATE_HOME_NAV, true);
        } catch (Throwable ignored) {
        }
        // 混合首页没有分类（各站分类 id 会撞），Tab 行直接隐藏
        if (mixedMode) {
            rvCategory.setVisibility(View.GONE);
            filterContainer.setVisibility(View.GONE);
            return;
        }
        if (homeNav) {
            MovieSort homeTab = new MovieSort(null, "主页"); // typeId=null → 推荐
            list.add(homeTab);
        }
        if (sorts != null) list.addAll(sorts);

        categoryAdapter.submit(list);
        rvCategory.setVisibility(View.VISIBLE);
        // 默认选中「主页」：无筛选；没“主页”项时直接进第一个分类
        filterContainer.setVisibility(View.GONE);
        if (!homeNav && !list.isEmpty() && curTypeId == null) {
            onCategorySelected(0, list.get(0));
        }
    }

    /** 点击顶部分类标签。 */
    private void onCategorySelected(int position, MovieSort sort) {
        categoryAdapter.setSelected(position);
        curExtend.clear();
        noMore = false;
        curPage = 1;

        if (sort.typeId == null) {
            // 回到首页推荐：隐藏筛选
            curTypeId = null;
            filterContainer.setVisibility(View.GONE);
            progress.setVisibility(View.VISIBLE);
            adapter.submit(null);
            viewModel.getSort();
            return;
        }

        curTypeId = sort.typeId;
        // 一组 filter 一行；无 filter 的分类隐藏整个筛选区
        showFilters(sort);
        progress.setVisibility(View.VISIBLE);
        adapter.submit(null);
        viewModel.getList(curTypeId, 1, new HashMap<>(curExtend));
    }

    /** 按选中分类的 filters 动态建筛选行：一组 filter 一行（组名 + 横向 chip 行）。 */
    private void showFilters(MovieSort sort) {
        filterContainer.removeAllViews();
        filterAdapters.clear();
        if (sort == null || !sort.hasFilter()) {
            filterContainer.setVisibility(View.GONE);
            return;
        }
        filterContainer.setVisibility(View.VISIBLE);
        float density = getResources().getDisplayMetrics().density;
        int labelWidth = (int) (48 * density);
        for (MovieSort.Filter group : sort.filters) {
            LinearLayout row = new LinearLayout(getContext());
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, (int) (3 * density), 0, (int) (3 * density));

            TextView label = new TextView(getContext());
            label.setText(group.name);
            label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            label.setTextColor(getResources().getColor(R.color.text_secondary));
            label.setMaxLines(1);
            LinearLayout.LayoutParams labelLp =
                    new LinearLayout.LayoutParams(labelWidth, LinearLayout.LayoutParams.WRAP_CONTENT);
            row.addView(label, labelLp);

            RecyclerView rv = new RecyclerView(getContext());
            // 分类筛选单行视图（设置 → 更多设置）：开时每组 chip 单行横滑，关时 4 列网格多行排布
            boolean singleRow = false;
            try {
                singleRow = Hawk.get(HawkConfig.CATE_FILTER_SINGLE, false);
            } catch (Throwable ignored) {
            }
            if (singleRow) {
                rv.setLayoutManager(new LinearLayoutManager(getContext(), RecyclerView.HORIZONTAL, false));
            } else {
                rv.setLayoutManager(new GridLayoutManager(getContext(), 4));
            }
            FilterChipAdapter filterAdapter = new FilterChipAdapter();
            final MovieSort.Filter g = group;
            filterAdapter.setOnValueClick(value -> onFilterSelected(g, value));
            filterAdapter.submit(group.values);
            filterAdapters.add(filterAdapter);
            rv.setAdapter(filterAdapter);
            LinearLayout.LayoutParams rvLp = new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            row.addView(rv, rvLp);

            filterContainer.addView(row);
        }
    }

    /** 点击某组里的筛选 chip：只改该组的 extend 值，其余组保持。 */
    private void onFilterSelected(MovieSort.Filter group, MovieSort.Value value) {
        if (group == null || group.key == null) return;
        if (value.v == null || value.v.isEmpty()) curExtend.remove(group.key);
        else curExtend.put(group.key, value.v);
        curPage = 1;
        noMore = false;
        progress.setVisibility(View.VISIBLE);
        adapter.submit(null);
        viewModel.getList(curTypeId, 1, new HashMap<>(curExtend));
    }

    private void loadMore() {
        if (curTypeId == null) return;
        loading = true;
        curPage++;
        viewModel.getList(curTypeId, curPage, new HashMap<>(curExtend));
    }
}
