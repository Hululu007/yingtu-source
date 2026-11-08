package com.yingtu.plus.ui.fragment;

import android.content.Intent;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.yingtu.plus.R;
import com.yingtu.plus.api.ApiConfig;
import com.yingtu.plus.base.BaseLazyFragment;
import com.yingtu.plus.bean.MovieSort;
import com.yingtu.plus.bean.SourceBean;
import com.yingtu.plus.ui.activity.DetailActivity;
import com.yingtu.plus.ui.adapter.CategoryTabAdapter;
import com.yingtu.plus.ui.adapter.FilterChipAdapter;
import com.yingtu.plus.ui.adapter.GridMovieAdapter;
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
                Intent i = new Intent(getActivity(), DetailActivity.class);
                i.putExtra(DetailActivity.KEY_SOURCE, movie.sourceKey);
                i.putExtra(DetailActivity.KEY_VOD_ID, movie.vodId);
                startActivity(i);
            });
            rvContent.setAdapter(adapter);
            rvContent.addOnScrollListener(new RecyclerView.OnScrollListener() {
                @Override
                public void onScrolled(RecyclerView rv, int dx, int dy) {
                    if (dy <= 0 || loading || noMore || curTypeId == null) return;
                    int last = glm.findLastVisibleItemPosition();
                    if (last >= adapter.getDataCount() - 3) loadMore();
                }
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
                boolean empty = movies == null || movies.isEmpty();
                tvHint.setVisibility(empty ? View.VISIBLE : View.GONE);
                if (empty) tvHint.setText(homeEmptyHint());
                adapter.submit(movies);
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
        return "该源暂无推荐内容";
    }

    @Override
    protected void onVisible() {
        // init 失败过则控件还没建好，这里直接返回，留空首页，不再崩一次
        if (rvCategory == null || filterContainer == null || tvHint == null || adapter == null) return;
        SourceBean home = ApiConfig.get().getHomeSource();
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

    /** 由 HomeActivity 在站源抽屉切源后调用，强制重载首页内容。 */
    public void reloadForSourceChange() {
        if (!isAdded()) return;
        SourceBean home = ApiConfig.get().getHomeSource();
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
        viewModel.getSort();
    }

    private void renderCategories(List<MovieSort> sorts) {
        java.util.List<MovieSort> list = new java.util.ArrayList<>();
        MovieSort homeTab = new MovieSort(null, "主页"); // typeId=null → 推荐
        list.add(homeTab);
        if (sorts != null) list.addAll(sorts);

        categoryAdapter.submit(list);
        rvCategory.setVisibility(View.VISIBLE);
        // 默认选中「主页」：无筛选
        filterContainer.setVisibility(View.GONE);
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
            rv.setLayoutManager(new LinearLayoutManager(getContext(), RecyclerView.HORIZONTAL, false));
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
