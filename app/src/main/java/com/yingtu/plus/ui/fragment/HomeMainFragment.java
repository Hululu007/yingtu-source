package com.yingtu.plus.ui.fragment;

import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.PagerSnapHelper;
import androidx.recyclerview.widget.RecyclerView;

import com.orhanobut.hawk.Hawk;
import com.yingtu.plus.R;
import com.yingtu.plus.api.ApiConfig;
import com.yingtu.plus.base.BaseLazyFragment;
import com.yingtu.plus.bean.Movie;
import com.yingtu.plus.bean.SourceBean;
import com.yingtu.plus.ui.activity.DetailActivity;
import com.yingtu.plus.ui.activity.VideoActivity;
import com.yingtu.plus.ui.activity.WebHomeActivity;
import com.yingtu.plus.ui.adapter.HeroAdapter;
import com.yingtu.plus.ui.adapter.NeonPosterAdapter;
import com.yingtu.plus.util.HawkConfig;
import com.yingtu.plus.util.MixRules;
import com.yingtu.plus.util.WebHomeHelper;
import com.yingtu.plus.util.ZoneSites;
import com.yingtu.plus.viewmodel.SourceViewModel;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 「影视」页（底部导航第一项，潮影霓虹主题）：
 * 顶部 hero 轮播（前 3 个）+ 热播横滑行，无分类 Tab、无筛选区、无翻页。
 * 取数仍走 SourceViewModel.getSort / getSortMixed（混合规则分支照旧）。
 *
 * 本页当前站（顶层影视页分支）：影视区当前站 → 影视区启动站 → 全局当前源
 * → 硬编码回退站 ZakaTgMovie（TG电影）。回退站只用来取数，不写进任何
 * Hawk 键（不顶掉用户自己的选站）；内嵌时（getParentFragment()!=null）
 * 仍走 ApiConfig 全局当前源不动。
 */
public class HomeMainFragment extends BaseLazyFragment {

    /** 回退站 key：TG电影（builtin_midnight.json，验过 good、通用电影内容）。 */
    private static final String FALLBACK_SOURCE_KEY = "ZakaTgMovie";

    private RecyclerView rvHero, rvContent;
    private TextView tvHotTitle;
    private LinearLayout emptyBox;
    private ProgressBar progress;
    private TextView tvHint;
    /** 影视区没选站时显示的 MissAV 网页版入口按钮（有站时隐藏）。 */
    private Button btnMissavWeb;
    private HeroAdapter heroAdapter;
    private NeonPosterAdapter adapter;
    private SourceViewModel viewModel;

    private String currentSourceKey = "";
    private boolean observersBound = false;

    // hero 轮播：每 5 秒滑到下一页（取模循环）
    private final Handler heroHandler = new Handler(Looper.getMainLooper());
    private final Runnable heroRunnable = new Runnable() {
        @Override
        public void run() {
            autoScrollHero();
            heroHandler.postDelayed(this, 5000);
        }
    };

    @Override
    protected int getLayoutId() {
        return R.layout.fragment_home_main;
    }

    @Override
    protected void init(View root) {
        // 闪退加固（dm16 第三轮）：启动链兜底，整段包 try/catch，
        // 任何漏网的异常都退化成空首页，不杀进程。顺序与正常路径不变。
        try {
            rvHero = root.findViewById(R.id.rvHero);
            tvHotTitle = root.findViewById(R.id.tvHotTitle);
            rvContent = root.findViewById(R.id.rvContent);
            emptyBox = root.findViewById(R.id.emptyBox);
            progress = root.findViewById(R.id.progress);
            tvHint = root.findViewById(R.id.tvHint);
            btnMissavWeb = root.findViewById(R.id.btnMissavWeb);
            if (btnMissavWeb != null) {
                btnMissavWeb.setOnClickListener(v -> openMissavWeb());
            }

            rvHero.setLayoutManager(new LinearLayoutManager(getContext(), RecyclerView.HORIZONTAL, false));
            new PagerSnapHelper().attachToRecyclerView(rvHero);
            heroAdapter = new HeroAdapter();
            heroAdapter.setOnHeroClick(this::openMovie);
            rvHero.setAdapter(heroAdapter);

            rvContent.setLayoutManager(new LinearLayoutManager(getContext(), RecyclerView.HORIZONTAL, false));
            adapter = new NeonPosterAdapter();
            adapter.setOnClick(this::openMovie);
            rvContent.setAdapter(adapter);

            viewModel = new ViewModelProvider(this).get(SourceViewModel.class);
        } catch (Throwable e) {
            e.printStackTrace();
            com.yingtu.plus.App.logCaught(e);
            try {
                if (tvHint != null) {
                    tvHint.setText("加载失败，请稍后重试");
                    tvHint.setVisibility(View.VISIBLE);
                }
                if (emptyBox != null) emptyBox.setVisibility(View.VISIBLE);
            } catch (Throwable ignored) {}
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        startHeroAutoScroll();
    }

    @Override
    public void onPause() {
        stopHeroAutoScroll();
        super.onPause();
    }

    @Override
    public void onDestroyView() {
        stopHeroAutoScroll();
        super.onDestroyView();
    }

    private void bindObservers() {
        if (observersBound) return;
        if (viewModel == null) return;
        observersBound = true;
        viewModel.sortResult.observe(this, sorts -> {
            // 分类 Tab 已删：sortResult 只用来收进度，不再渲染分类。
            if (progress != null) progress.setVisibility(View.GONE);
        });
        viewModel.listResult.observe(this, movies -> {
            if (progress != null) progress.setVisibility(View.GONE);
            // 首页推荐=豆瓣热播时只留带豆瓣热播角标的条目（站源不带角标时列表为空）
            List<Movie> shown = movies;
            if (isDoubanRec()) {
                shown = new ArrayList<>();
                if (movies != null) {
                    for (Movie m : movies) if (m.douban) shown.add(m);
                }
            }
            boolean empty = shown == null || shown.isEmpty();
            if (emptyBox != null) emptyBox.setVisibility(empty ? View.VISIBLE : View.GONE);
            if (tvHint != null) {
                tvHint.setVisibility(empty ? View.VISIBLE : View.GONE);
                if (empty) tvHint.setText(homeEmptyHint());
            }
            if (heroAdapter != null) {
                List<Movie> hero = new ArrayList<>();
                if (shown != null) {
                    for (int i = 0; i < shown.size() && i < 3; i++) hero.add(shown.get(i));
                }
                heroAdapter.submit(hero);
            }
            if (adapter != null) adapter.submit(shown);
            if (rvContent != null) rvContent.scrollToPosition(0);
            if (empty) stopHeroAutoScroll();
            else startHeroAutoScroll();
        });
        // 首页取数报错时（在 listResult 之后注册，后到先显示）：用可读原因盖掉
        // “该源暂无推荐内容”，用户和 crash.log 里都能看到错在哪。
        viewModel.sortError.observe(this, msg -> {
            if (msg == null || msg.isEmpty()) return;
            if (emptyBox != null) emptyBox.setVisibility(View.VISIBLE);
            if (tvHint != null) {
                tvHint.setVisibility(View.VISIBLE);
                tvHint.setText("加载失败：" + msg);
            }
        });
    }

    /** 首页推荐为空时的提示：取数报错显示原因，否则显示“暂无推荐内容”。 */
    private String homeEmptyHint() {
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
        // init 失败过则控件还没建好，这里直接返回，留空首页，不再崩一次。
        if (rvHero == null || rvContent == null || tvHint == null || emptyBox == null
                || heroAdapter == null || adapter == null || viewModel == null) return;
        // 可见时初始化失败不许杀进程（dm16 闪退修复口径）：记 crash.log，界面显示加载失败。
        try {
            SourceBean home = zoneSource();
            String key = home == null ? "" : home.getKey();
            if (home == null) {
                if (emptyBox != null) emptyBox.setVisibility(View.VISIBLE);
                tvHint.setVisibility(View.VISIBLE);
                tvHint.setText(R.string.home_no_source);
                // 影视区没选站：露出 MissAV 网页版入口，空状态不再只有一个“请选择源”。
                if (btnMissavWeb != null) btnMissavWeb.setVisibility(View.VISIBLE);
                heroAdapter.submit(null);
                adapter.submit(null);
                stopHeroAutoScroll();
                currentSourceKey = "";
                return;
            }
            if (btnMissavWeb != null) btnMissavWeb.setVisibility(View.GONE);
            if (key.equals(currentSourceKey)) return;
            currentSourceKey = key;
            bindObservers();
            loadHome();
        } catch (Throwable e) {
            e.printStackTrace();
            com.yingtu.plus.App.logCaught(e);
            try {
                if (progress != null) progress.setVisibility(View.GONE);
                if (emptyBox != null) emptyBox.setVisibility(View.VISIBLE);
                if (tvHint != null) {
                    tvHint.setText("加载失败，请稍后重试");
                    tvHint.setVisibility(View.VISIBLE);
                }
            } catch (Throwable ignored) {}
        }
    }

    /** 打开 MissAV 网页版（WebHomeActivity 内置 WebView，站内浏览/播放）。 */
    private void openMissavWeb() {
        try {
            if (getActivity() == null) return;
            Intent i = new Intent(getActivity(), WebHomeActivity.class);
            i.putExtra(WebHomeActivity.KEY_URL, "file:///android_asset/" + WebHomeHelper.MISSAV_TARGET);
            i.putExtra(WebHomeActivity.KEY_TITLE, WebHomeHelper.MISSAV_NAME);
            startActivity(i);
        } catch (Throwable e) {
            e.printStackTrace();
        }
    }

    /**
     * 本页当前站：被午夜/短剧区内嵌时用全局当前源（分区页切站时写进去）；
     * 顶层影视页用回退链——影视区当前站 → 影视区启动站 → 全局当前源
     * → 硬编码回退站 ZakaTgMovie。回退站只用来取数，不写进任何 Hawk 键。
     */
    private SourceBean zoneSource() {
        if (getParentFragment() != null) return ApiConfig.get().getHomeSource();
        String key = ZoneSites.getCurrentSiteKey(ZoneSites.ZONE_MOVIE);
        if (key != null && !key.isEmpty()) {
            SourceBean bean = ApiConfig.get().getSource(key);
            if (bean != null) return bean;
        }
        String startup = ZoneSites.getStartupSiteKey(ZoneSites.ZONE_MOVIE);
        if (startup != null && !startup.isEmpty()) {
            SourceBean bean = ApiConfig.get().getSource(startup);
            if (bean != null) return bean;
        }
        SourceBean home = ApiConfig.get().getHomeSource();
        if (home != null) return home;
        return ApiConfig.get().getSource(FALLBACK_SOURCE_KEY);
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
        // 切死站闪退加固：viewModel 为空或中途抛错都不许杀进程，退化成空首页 + 提示。
        if (viewModel == null || tvHint == null || adapter == null) return;
        try {
            if (emptyBox != null) emptyBox.setVisibility(View.GONE);
            tvHint.setVisibility(View.GONE);
            heroAdapter.submit(null);
            adapter.submit(null);
            stopHeroAutoScroll();
            progress.setVisibility(View.VISIBLE);
            // 混合规则（高级配置 → 混合规则）：有激活规则时逐站取首页推荐合并
            MixRules.Rule rule = MixRules.getActiveRule();
            if (rule != null) {
                List<SourceBean> members = new ArrayList<>();
                for (String k : rule.keys) {
                    SourceBean b = ApiConfig.get().getSource(k);
                    if (b != null) members.add(b);
                }
                if (members.size() >= 2) {
                    viewModel.getSortMixed(members);
                    return;
                }
            }
            // 解析到的站与全局当前源不一致时（回退站、或分区切站后全局未同步），
            // 用单站 getSortMixed 直接取该站首页：不调 setSourceBean，
            // 不写 Hawk，不顶掉用户自己的选站；一致时走原来的 getSort()。
            SourceBean resolved = zoneSource();
            SourceBean global = ApiConfig.get().getHomeSource();
            if (resolved != null && (global == null || !resolved.getKey().equals(global.getKey()))) {
                viewModel.getSortMixed(Collections.singletonList(resolved));
                return;
            }
            viewModel.getSort();
        } catch (Throwable e) {
            e.printStackTrace();
            com.yingtu.plus.App.logCaught(e);
            try {
                if (progress != null) progress.setVisibility(View.GONE);
                if (emptyBox != null) emptyBox.setVisibility(View.VISIBLE);
                if (tvHint != null) {
                    tvHint.setText("加载失败，请稍后重试");
                    tvHint.setVisibility(View.VISIBLE);
                }
            } catch (Throwable ignored) {}
        }
    }

    /** 海报点按：列表单片自动播放开时点一下直接播，关时进详情（与原首页点按目标一致）。 */
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

    private void autoScrollHero() {
        if (rvHero == null || heroAdapter == null) return;
        int count = heroAdapter.getItemCount();
        if (count <= 1) return;
        int cur = 0;
        RecyclerView.LayoutManager lm = rvHero.getLayoutManager();
        if (lm instanceof LinearLayoutManager) {
            cur = ((LinearLayoutManager) lm).findFirstVisibleItemPosition();
            if (cur < 0) cur = 0;
        }
        rvHero.smoothScrollToPosition((cur + 1) % count);
    }

    private void startHeroAutoScroll() {
        stopHeroAutoScroll();
        if (heroAdapter != null && heroAdapter.getItemCount() > 1 && isResumed()) {
            heroHandler.postDelayed(heroRunnable, 5000);
        }
    }

    private void stopHeroAutoScroll() {
        heroHandler.removeCallbacks(heroRunnable);
    }
}
