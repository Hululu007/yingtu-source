package com.yingtu.plus.ui.fragment;

import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.GridLayoutManager;
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
import com.yingtu.plus.ui.activity.HomeActivity;
import com.yingtu.plus.ui.activity.VideoActivity;
import com.yingtu.plus.ui.adapter.HeroAdapter;
import com.yingtu.plus.ui.adapter.NeonPosterAdapter;
import com.yingtu.plus.util.HawkConfig;
import com.yingtu.plus.util.MixRules;
import com.yingtu.plus.util.ZoneSites;
import com.yingtu.plus.viewmodel.SourceViewModel;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 「影视」页（底部导航第一项，潮影霓虹主题，结构对齐 moyi 源码 neon_home_view.dart）：
 * 顶部 hero 轮播（前 6 个有封面的，源码 _featured 最多 6 张）+ 热播横滑行
 * （2 排横向网格，卡片 96dp 紧凑宽，一眼约 8 张，本站 homeVideoContent 全量进横行，
 * 不截断；源码热播行是单排 112dp 最多 12 张，用户嫌少才改的 2 排，这里保留 2 排）
 * + 猜你想看（源码同站同一份列表进 3 列网格，不是另一批；角标、标题压图底
 * 与热播行同款卡片结构，源码猜你想看格子本身不带角标）。
 * 取数仍走 SourceViewModel.getSort / getSortMixed（混合规则分支照旧）。
 *
 * 本页当前站（顶层影视页分支）：用户设过的影视区站固定用它；没设过才走
 * 回退链——影视区启动站 → 全局当前源 → 默认站 missavt（MissAV 蜘蛛站，
 * jar 站验唯一 good，分类/卡片/详情/播放都走蜘蛛，不是 web）。
 * 回退解析到的站会钉进影视区当前站键（ZONE_SITE_MOVIE），之后不再跟着
 * 全局当前源变来变去；用户一旦自己选站，选站写键为准，回退不再插手。
 * 内嵌时（getParentFragment()!=null）仍走 ApiConfig 全局当前源不动。
 */
public class HomeMainFragment extends BaseLazyFragment {

    /** 默认站 key：missavt（builtin_midnight.json，MissAV 蜘蛛站，站验唯一 good）。 */
    private static final String FALLBACK_SOURCE_KEY = "missavt";

    private RecyclerView rvHero, rvContent, rvGuess;
    private LinearLayout heroDots;
    private TextView tvHotTitle, tvHotAll, tvGuessTitle;
    private LinearLayout emptyBox;
    private ProgressBar progress;
    private TextView tvHint;
    private HeroAdapter heroAdapter;
    private NeonPosterAdapter adapter;
    private NeonPosterAdapter guessAdapter;
    private SourceViewModel viewModel;

    private String currentSourceKey = "";
    private boolean observersBound = false;
    /** hero 轮播张数（圆点个数，= hero 列表长度，最多 6 张，源码 _featured 上限）。 */
    private int heroCount = 0;

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
            heroDots = root.findViewById(R.id.heroDots);
            tvHotTitle = root.findViewById(R.id.tvHotTitle);
            tvHotAll = root.findViewById(R.id.tvHotAll);
            rvContent = root.findViewById(R.id.rvContent);
            tvGuessTitle = root.findViewById(R.id.tvGuessTitle);
            rvGuess = root.findViewById(R.id.rvGuess);
            emptyBox = root.findViewById(R.id.emptyBox);
            progress = root.findViewById(R.id.progress);
            tvHint = root.findViewById(R.id.tvHint);

            rvHero.setLayoutManager(new LinearLayoutManager(getContext(), RecyclerView.HORIZONTAL, false));
            new PagerSnapHelper().attachToRecyclerView(rvHero);
            heroAdapter = new HeroAdapter();
            heroAdapter.setOnHeroClick(this::openMovie);
            rvHero.setAdapter(heroAdapter);
            // hero 滑动时圆点跟着走（自动轮播也会触发 onScrolled）。
            rvHero.addOnScrollListener(new RecyclerView.OnScrollListener() {
                @Override
                public void onScrolled(@NonNull RecyclerView rv, int dx, int dy) {
                    updateHeroDots(currentHeroPosition());
                }
            });

            // 热播行右上角“全部 >”：切到频道页（影视组），站与首页同站。
            if (tvHotAll != null) {
                tvHotAll.setOnClickListener(v -> openChannelAll());
            }

            // 热播行：2 排横向网格，可横滑；卡片 96dp 紧凑宽（海报 96x144，2:3），
            // 一眼约 2 排 x 4 列 = 8 张；行内是本站 homeVideoContent 全量，不截断。
            // 角标按源码位置轮换（视觉列 % 3 == 0 → HD）。
            rvContent.setLayoutManager(new GridLayoutManager(getContext(), 2, RecyclerView.HORIZONTAL, false));
            adapter = new NeonPosterAdapter();
            adapter.setCompactWidthDp(96);
            adapter.setBadgeMode(NeonPosterAdapter.BADGE_HOME);
            adapter.setOnClick(this::openMovie);
            rvContent.setAdapter(adapter);

            // 猜你想看：3 列网格，跟整页一起滑（嵌套滚动关掉），数据见 listResult；
            // 角标按源码不显示（猜你想看格子本身不带角标）。
            rvGuess.setLayoutManager(new GridLayoutManager(getContext(), 3));
            rvGuess.setNestedScrollingEnabled(false);
            guessAdapter = new NeonPosterAdapter();
            guessAdapter.setBadgeMode(NeonPosterAdapter.BADGE_NONE);
            guessAdapter.setOnClick(this::openMovie);
            rvGuess.setAdapter(guessAdapter);

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
                // hero：前 6 个有封面的（源码 _featured：跳过无封面的，最多 6 张；
                // 全无封面时回退取前 6 张）。
                List<Movie> hero = new ArrayList<>();
                if (shown != null) {
                    for (Movie m : shown) {
                        if (m.pic != null && !m.pic.trim().isEmpty()) {
                            hero.add(m);
                            if (hero.size() >= 6) break;
                        }
                    }
                    if (hero.isEmpty()) {
                        for (int i = 0; i < shown.size() && i < 6; i++) hero.add(shown.get(i));
                    }
                }
                heroAdapter.submit(hero);
                rebuildHeroDots(hero.size());
            }
            if (adapter != null) adapter.submit(shown);
            if (rvContent != null) rvContent.scrollToPosition(0);
            // 猜你想看：源码用的是同一份首页列表（gridItems），不是另一批；
            // 有内容就整行显示（源码非空即出网格，不设“少于 3 张隐藏”）。
            if (guessAdapter != null) {
                guessAdapter.submit(shown);
                boolean showGuess = shown != null && !shown.isEmpty();
                if (tvGuessTitle != null) tvGuessTitle.setVisibility(showGuess ? View.VISIBLE : View.GONE);
                if (rvGuess != null) rvGuess.setVisibility(showGuess ? View.VISIBLE : View.GONE);
            }
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
                heroAdapter.submit(null);
                adapter.submit(null);
                rebuildHeroDots(0);
                if (guessAdapter != null) guessAdapter.submit(null);
                if (tvGuessTitle != null) tvGuessTitle.setVisibility(View.GONE);
                if (rvGuess != null) rvGuess.setVisibility(View.GONE);
                stopHeroAutoScroll();
                currentSourceKey = "";
                return;
            }
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

    /**
     * 本页当前站：被午夜/短剧区内嵌时用全局当前源（分区页切站时写进去）；
     * 顶层影视页用回退链——影视区当前站 → 影视区启动站 → 全局当前源
     * → 默认站 missavt。回退解析到的站钉进影视区当前站键，之后固定用它，
     * 不再跟着全局当前源变；用户自己选站写键为准。
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
            if (bean != null) {
                pinMovieSite(bean.getKey());
                return bean;
            }
        }
        SourceBean home = ApiConfig.get().getHomeSource();
        if (home != null) {
            pinMovieSite(home.getKey());
            return home;
        }
        SourceBean fallback = ApiConfig.get().getSource(FALLBACK_SOURCE_KEY);
        if (fallback != null) pinMovieSite(fallback.getKey());
        return fallback;
    }

    /** 把回退解析到的站钉成影视区当前站（固定默认站，用户选站会改写它）。 */
    private void pinMovieSite(String siteKey) {
        try {
            ZoneSites.setCurrentSiteKey(ZoneSites.ZONE_MOVIE, siteKey);
        } catch (Throwable ignored) {
        }
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
            rebuildHeroDots(0);
            if (guessAdapter != null) guessAdapter.submit(null);
            if (tvGuessTitle != null) tvGuessTitle.setVisibility(View.GONE);
            if (rvGuess != null) rvGuess.setVisibility(View.GONE);
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

    /** hero 当前页（取模到圆点下标）。 */
    private int currentHeroPosition() {
        if (heroCount <= 0) return 0;
        int cur = 0;
        if (rvHero != null) {
            RecyclerView.LayoutManager lm = rvHero.getLayoutManager();
            if (lm instanceof LinearLayoutManager) {
                cur = ((LinearLayoutManager) lm).findFirstVisibleItemPosition();
                if (cur < 0) cur = 0;
            }
        }
        return cur % heroCount;
    }

    /** 按轮播张数重建圆点（源码 _dots：当前页 16x4 粉紫渐变长条，其余 5x4 白 22%）。 */
    private void rebuildHeroDots(int count) {
        heroCount = count;
        if (heroDots == null) return;
        heroDots.removeAllViews();
        if (count <= 1) return;
        float density = getResources().getDisplayMetrics().density;
        for (int i = 0; i < count; i++) {
            View dot = new View(getContext());
            int w = (int) (i == 0 ? 16 * density + 0.5f : 5 * density + 0.5f);
            int h = (int) (4 * density + 0.5f);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(w, h);
            lp.setMargins((int) (2.5f * density + 0.5f), 0, (int) (2.5f * density + 0.5f), 0);
            dot.setLayoutParams(lp);
            dot.setBackgroundResource(i == 0
                    ? R.drawable.bg_hero_dot_selected : R.drawable.bg_hero_dot_normal);
            heroDots.addView(dot);
        }
    }

    /** 圆点跟随当前页：选中变粉紫长条，其余变白 22% 小圆点。 */
    private void updateHeroDots(int position) {
        if (heroDots == null || heroCount <= 1) return;
        float density = getResources().getDisplayMetrics().density;
        for (int i = 0; i < heroDots.getChildCount() && i < heroCount; i++) {
            View dot = heroDots.getChildAt(i);
            boolean on = i == position;
            int w = (int) (on ? 16 * density + 0.5f : 5 * density + 0.5f);
            int h = (int) (4 * density + 0.5f);
            LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) dot.getLayoutParams();
            if (lp != null) {
                lp.width = w;
                lp.height = h;
                dot.setLayoutParams(lp);
            }
            dot.setBackgroundResource(on
                    ? R.drawable.bg_hero_dot_selected : R.drawable.bg_hero_dot_normal);
        }
    }

    /** 热播推荐右上角“全部 >”：切到频道页（影视组），站与首页同站。 */
    private void openChannelAll() {
        try {
            if (getActivity() instanceof HomeActivity) {
                ((HomeActivity) getActivity()).openChannelAll();
            }
        } catch (Throwable e) {
            e.printStackTrace();
        }
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
