package com.yingtu.plus.ui.fragment;

import android.content.Intent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.yingtu.plus.App;
import com.yingtu.plus.R;
import com.yingtu.plus.base.BaseLazyFragment;
import com.yingtu.plus.bean.History;
import com.yingtu.plus.ui.activity.DetailActivity;
import com.yingtu.plus.ui.adapter.HistoryAdapter;
import com.yingtu.plus.util.HistoryHelper;

import java.util.List;

/**
 * 片单页：顶部两 Tab——收藏 / 历史。
 * 收藏区嵌 FavVodFragment（childFragmentManager，tag "list_fav"，嵌入写法
 * 照 HomeActivity.showFav）；历史区是 3 列网格，数据 HistoryHelper.getAll()，
 * 点按进详情、长按删确认（照 HistoryActivity.openDetail / confirmDelete 抄）。
 */
public class ListFragment extends BaseLazyFragment {

    private TextView tvTabFav;
    private TextView tvTabHistory;
    private FrameLayout listContainer;
    private RecyclerView rvHistory;
    private HistoryAdapter historyAdapter;
    private boolean showingFav = true;

    @Override
    protected int getLayoutId() {
        return R.layout.fragment_list;
    }

    @Override
    protected void init(View root) {
        try {
            tvTabFav = root.findViewById(R.id.tvTabFav);
            tvTabHistory = root.findViewById(R.id.tvTabHistory);
            listContainer = root.findViewById(R.id.listContainer);
            rvHistory = root.findViewById(R.id.rvHistory);

            tvTabFav.setOnClickListener(v -> showFavTab());
            tvTabHistory.setOnClickListener(v -> showHistoryTab());

            if (getChildFragmentManager().findFragmentByTag("list_fav") == null) {
                getChildFragmentManager().beginTransaction()
                        .replace(R.id.listContainer, new FavVodFragment(), "list_fav")
                        .commit();
            }

            rvHistory.setLayoutManager(new GridLayoutManager(getContext(), 3));
            historyAdapter = new HistoryAdapter();
            historyAdapter.setOnHistoryClick(this::openDetail);
            historyAdapter.setOnHistoryLongClick(this::confirmDelete);
            rvHistory.setAdapter(historyAdapter);

            showFavTab();
        } catch (Throwable e) {
            e.printStackTrace();
            App.logCaught(e);
        }
    }

    @Override
    protected void onVisible() {
        if (historyAdapter == null) return;
        try {
            if (!showingFav) refreshHistory();
        } catch (Throwable e) {
            e.printStackTrace();
            App.logCaught(e);
        }
    }

    private void showFavTab() {
        showingFav = true;
        markTab(tvTabFav, true);
        markTab(tvTabHistory, false);
        if (listContainer != null) listContainer.setVisibility(View.VISIBLE);
        if (rvHistory != null) rvHistory.setVisibility(View.GONE);
    }

    private void showHistoryTab() {
        showingFav = false;
        markTab(tvTabFav, false);
        markTab(tvTabHistory, true);
        if (listContainer != null) listContainer.setVisibility(View.GONE);
        if (rvHistory != null) rvHistory.setVisibility(View.VISIBLE);
        refreshHistory();
    }

    private void markTab(TextView tab, boolean selected) {
        if (tab == null) return;
        tab.setTextColor(getResources().getColor(
                selected ? R.color.neon_pink : R.color.text_muted));
        tab.setTypeface(null, selected ? android.graphics.Typeface.BOLD
                : android.graphics.Typeface.NORMAL);
    }

    private void refreshHistory() {
        if (historyAdapter == null) return;
        List<History> list = HistoryHelper.getAll();
        historyAdapter.submit(list);
    }

    private void openDetail(History h) {
        Intent i = new Intent(getActivity(), DetailActivity.class);
        i.putExtra(DetailActivity.KEY_SOURCE, h.sourceKey);
        i.putExtra(DetailActivity.KEY_VOD_ID, h.vodId);
        startActivity(i);
    }

    private void confirmDelete(History h) {
        if (getActivity() == null) return;
        new AlertDialog.Builder(getActivity())
                .setMessage("删除「" + h.vodName + "」的观看记录？")
                .setPositiveButton("删除", (d, w) -> {
                    HistoryHelper.delete(h.key);
                    refreshHistory();
                })
                .setNegativeButton("取消", null)
                .show();
    }
}
