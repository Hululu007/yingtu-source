package com.yingtu.plus.ui.activity;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.TextView;

import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.yingtu.plus.R;
import com.yingtu.plus.base.BaseActivity;
import com.yingtu.plus.ui.adapter.GridMovieAdapter;
import com.yingtu.plus.util.HawkConfig;
import com.yingtu.plus.viewmodel.SourceViewModel;
import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.orhanobut.hawk.Hawk;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 搜索页：输入关键词 → 当前源搜索 → 网格展示结果；保留本地搜索历史。
 */
public class SearchActivity extends BaseActivity {

    private EditText etKeyword;
    private ChipGroup chipHistory;
    private ChipGroup chipHot;
    private TextView tvHistoryTitle;
    private TextView tvHotTitle;
    private View emptyView;
    private GridMovieAdapter adapter;
    private SourceViewModel viewModel;

    @Override
    protected int getLayoutId() {
        return R.layout.activity_search;
    }

    @Override
    protected void init() {
        etKeyword = find(R.id.etKeyword);
        chipHistory = find(R.id.chipHistory);
        chipHot = find(R.id.chipHot);
        tvHistoryTitle = find(R.id.tvHistoryTitle);
        tvHotTitle = find(R.id.tvHotTitle);
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
        adapter = new GridMovieAdapter();
        // 搜索展示（设置 → 首页和搜索）：前半段决定搜索页形态，后半段决定聚搜页形态
        com.yingtu.plus.util.SearchDisplay.applySearch(rv, adapter);
        adapter.setOnItemClick(movie -> {
            Intent i = new Intent(this, DetailActivity.class);
            i.putExtra(DetailActivity.KEY_SOURCE, movie.sourceKey);
            i.putExtra(DetailActivity.KEY_VOD_ID, movie.vodId);
            startActivity(i);
        });
        rv.setAdapter(adapter);

        viewModel = new ViewModelProvider(this).get(SourceViewModel.class);
        viewModel.searchResult.observe(this, movies -> {
            boolean empty = movies == null || movies.isEmpty();
            emptyView.setVisibility(empty ? View.VISIBLE : View.GONE);
            adapter.submit(movies);
        });

        renderHistory();
        renderHot();

        // 支持从 WebHome / 快捷入口带入关键词直接搜索
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
        saveHistory(keyword);
        renderHistory();
        emptyView.setVisibility(View.GONE);
        viewModel.search(keyword);
    }

    private void renderHistory() {
        List<String> history = getHistory();
        tvHistoryTitle.setVisibility(history.isEmpty() ? View.GONE : View.VISIBLE);
        chipHistory.removeAllViews();
        for (String h : history) {
            Chip chip = new Chip(this);
            chip.setText(h);
            chip.setCheckable(false);
            chip.setOnClickListener(v -> {
                etKeyword.setText(h);
                etKeyword.setSelection(h.length());
                doSearch(h);
            });
            chipHistory.addView(chip);
        }
    }

    /**
     * 热门搜索：树里目前没有热门词数据源（无上游接口、无内置榜单），不编假词，
     * 该区保持隐藏；等热门词来源落地后，在这里填充 chipHot 并显示 tvHotTitle/chipHot。
     */
    private void renderHot() {
        tvHotTitle.setVisibility(View.GONE);
        chipHot.setVisibility(View.GONE);
    }

    private List<String> getHistory() {
        return Hawk.get(HawkConfig.SEARCH_HISTORY, new ArrayList<>());
    }

    private void saveHistory(String keyword) {
        Set<String> set = new LinkedHashSet<>();
        set.add(keyword);
        set.addAll(getHistory());
        List<String> list = new ArrayList<>(set);
        int max = com.yingtu.plus.util.HistoryHelper.getSearchLimit();
        if (list.size() > max) list = list.subList(0, max);
        Hawk.put(HawkConfig.SEARCH_HISTORY, list);
    }
}
