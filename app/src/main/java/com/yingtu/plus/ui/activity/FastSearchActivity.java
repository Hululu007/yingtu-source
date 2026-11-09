package com.yingtu.plus.ui.activity;

import android.content.Intent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.TextView;

import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.yingtu.plus.R;
import com.yingtu.plus.api.ApiConfig;
import com.yingtu.plus.api.SiteApi;
import com.yingtu.plus.api.config.VodConfig;
import com.yingtu.plus.base.BaseActivity;
import com.yingtu.plus.bean.Movie;
import com.yingtu.plus.bean.Result;
import com.yingtu.plus.bean.Site;
import com.yingtu.plus.bean.SourceBean;
import com.yingtu.plus.bean.Vod;
import com.yingtu.plus.ui.adapter.GridMovieAdapter;
import com.yingtu.plus.util.HawkConfig;
import com.orhanobut.hawk.Hawk;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 聚合快搜：一个关键词同时搜全部可搜站源（quick=true，只取每站前 5 条），
 * 各站结果流式拼进同一个网格，先回先显示。
 *
 * 与 SearchActivity 的区别：SearchActivity 只搜当前站（SourceViewModel.search），
 * 这里不走 SourceViewModel，直接调同一套底层接口 SiteApi.searchContent 逐站搜，
 * SearchActivity 本身一动不动。当前站排在第一位。
 */
public class FastSearchActivity extends BaseActivity {

    private static final int THREADS = 4;
    private static final int PER_SITE = 5;
    private static final int TOTAL_MAX = 60;

    private EditText etKeyword;
    private TextView tvStatus;
    private View emptyView;
    private GridMovieAdapter adapter;

    private ExecutorService executor;
    /**
     * 搜次代号：每起一次新搜索 +1，退出页也 +1；后台任务只认自己那一代的结果，
     * 旧搜索晚回来的站结果一律丢弃，不会混进新一轮的网格或状态行。
     */
    private final AtomicInteger generation = new AtomicInteger(0);
    private final AtomicInteger finishedSites = new AtomicInteger(0);
    private final AtomicInteger totalFound = new AtomicInteger(0);
    private int siteCount = 0;
    private final Object lock = new Object();

    @Override
    protected int getLayoutId() {
        return R.layout.activity_fast_search;
    }

    @Override
    protected void init() {
        etKeyword = find(R.id.etKeyword);
        tvStatus = find(R.id.tvStatus);
        emptyView = find(R.id.tvEmpty);

        find(R.id.ivBack).setOnClickListener(v -> finish());
        find(R.id.btnSearch).setOnClickListener(v -> doSearch(etKeyword.getText().toString()));

        etKeyword.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                doSearch(etKeyword.getText().toString());
                return true;
            }
            return false;
        });

        RecyclerView rv = find(R.id.recyclerView);
        rv.setLayoutManager(new GridLayoutManager(this, 3));
        adapter = new GridMovieAdapter();
        adapter.setOnItemClick(movie -> {
            Intent i = new Intent(this, DetailActivity.class);
            i.putExtra(DetailActivity.KEY_SOURCE, movie.sourceKey);
            i.putExtra(DetailActivity.KEY_VOD_ID, movie.vodId);
            startActivity(i);
        });
        rv.setAdapter(adapter);

        String preset = getIntent().getStringExtra("keyword");
        if (preset != null && !preset.trim().isEmpty()) {
            etKeyword.setText(preset);
            etKeyword.setSelection(preset.length());
            doSearch(preset);
        }
    }

    private void doSearch(String keyword) {
        if (keyword == null || keyword.trim().isEmpty()) return;
        keyword = keyword.trim();
        if (executor != null) executor.shutdownNow();
        final int gen = generation.incrementAndGet();
        saveHistory(keyword);

        final String wd = keyword;
        List<Site> sites = searchableSites();
        siteCount = sites.size();
        finishedSites.set(0);
        totalFound.set(0);
        adapter.submit(new ArrayList<>());
        emptyView.setVisibility(View.GONE);
        if (sites.isEmpty()) {
            tvStatus.setText("暂无可用站源");
            emptyView.setVisibility(View.VISIBLE);
            return;
        }
        tvStatus.setText("正在搜 0/" + siteCount + " 个站…");
        executor = Executors.newFixedThreadPool(THREADS);
        for (Site site : sites) {
            executor.execute(() -> searchOneSite(site, wd, gen));
        }
        executor.shutdown();
    }

    /** 可搜站：searchable=1 且 key 非空；当前站排第一位。 */
    private List<Site> searchableSites() {
        List<Site> all = new ArrayList<>();
        try {
            for (Site s : VodConfig.get().getSites()) {
                if (s != null && s.isSearchable() && !s.getKey().isEmpty()) all.add(s);
            }
        } catch (Throwable ignored) {}
        String homeKey = null;
        try {
            SourceBean home = ApiConfig.get().getHomeSource();
            if (home != null) homeKey = home.getKey();
        } catch (Throwable ignored) {}
        if (homeKey != null) {
            for (int i = 1; i < all.size(); i++) {
                if (homeKey.equals(all.get(i).getKey())) {
                    all.add(0, all.remove(i));
                    break;
                }
            }
        }
        return all;
    }

    private void searchOneSite(Site site, String keyword, int gen) {
        List<Movie> found = new ArrayList<>();
        if (gen == generation.get() && totalFound.get() < TOTAL_MAX) {
            try {
                Result result = SiteApi.searchContent(site, keyword, true, "1");
                if (result != null && result.getList() != null) {
                    for (Vod vod : result.getList()) {
                        if (found.size() >= PER_SITE) break;
                        Movie movie = new Movie(vod.getId(), vod.getName(), vod.getPic(), vod.getRemarks());
                        movie.sourceKey = site.getKey();
                        movie.year = vod.getYear();
                        movie.area = vod.getArea();
                        movie.type = vod.getTypeName();
                        movie.actor = vod.getActor();
                        movie.director = vod.getDirector();
                        movie.content = vod.getContent();
                        found.add(movie);
                    }
                }
            } catch (Throwable ignored) {
                // 单站搜失败（站挂了/超时/蜘蛛抛错）只跳过该站，不影响其他站。
            }
        }
        synchronized (lock) {
            if (gen != generation.get()) return;
            int room = TOTAL_MAX - totalFound.get();
            if (room > 0 && !found.isEmpty()) {
                if (found.size() > room) found = found.subList(0, room);
                totalFound.addAndGet(found.size());
                final List<Movie> batch = found;
                runOnUiThread(() -> {
                    if (gen != generation.get()) return;
                    adapter.append(batch);
                });
            }
        }
        int done = finishedSites.incrementAndGet();
        runOnUiThread(() -> {
            if (gen != generation.get()) return;
            if (done >= siteCount) {
                int total = totalFound.get();
                tvStatus.setText("搜完 " + siteCount + " 个站，共 " + total + " 条");
                if (total == 0) emptyView.setVisibility(View.VISIBLE);
            } else {
                tvStatus.setText("正在搜 " + done + "/" + siteCount + " 个站 · 已找到 " + totalFound.get() + " 条");
            }
        });
    }

    @Override
    protected void onDestroy() {
        generation.incrementAndGet();
        if (executor != null) executor.shutdownNow();
        super.onDestroy();
    }

    /** 与 SearchActivity 共用同一份搜索记录（Hawk SEARCH_HISTORY），不在别处另存。 */
    private void saveHistory(String keyword) {
        try {
            List<String> history = Hawk.get(HawkConfig.SEARCH_HISTORY, new ArrayList<>());
            Set<String> set = new LinkedHashSet<>();
            set.add(keyword);
            set.addAll(history);
            List<String> list = new ArrayList<>(set);
            if (list.size() > 12) list = list.subList(0, 12);
            Hawk.put(HawkConfig.SEARCH_HISTORY, list);
        } catch (Throwable ignored) {}
    }
}
