package com.yingtu.plus.ui.activity;

import android.content.Intent;
import android.view.View;

import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.yingtu.plus.R;
import com.yingtu.plus.base.BaseActivity;
import com.yingtu.plus.bean.FavVod;
import com.yingtu.plus.bean.Keep;
import com.yingtu.plus.ui.adapter.FavVodAdapter;
import com.yingtu.plus.util.FavHelper;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 收藏页：网格展示全部收藏，点击进详情，长按分享 / 删除收藏，右上角清空全部。
 *
 * 不另起存法，读的就是现有的两处收藏：
 *   ① FavHelper/FavVod（Hawk）：详情页点“收藏”写进来的，首页顶栏收藏图标看的也是它；
 *   ② Keep 表（Room，type=0 点播收藏）：视频页收藏写进来的。
 * 两处按 sourceKey@@vodId 去重拼成一个列表（FavVod 在前，Keep 补后面没有的）。
 * 删除时两处都删；清空时两处都清（Keep 只清点播收藏，直播收藏不动）。
 */
public class CollectActivity extends BaseActivity {

    private FavVodAdapter adapter;
    private View emptyView;
    /** 合并列表里每条对应的 Keep（只有来自 Keep 表的条目才有），删除时用。 */
    private final Map<String, Keep> keepByKey = new HashMap<>();

    @Override
    protected int getLayoutId() {
        return R.layout.activity_collect;
    }

    @Override
    protected void init() {
        emptyView = find(R.id.tvEmpty);
        find(R.id.ivBack).setOnClickListener(v -> finish());
        find(R.id.ivClear).setOnClickListener(v -> confirmClear());

        RecyclerView rv = find(R.id.recyclerView);
        rv.setLayoutManager(new GridLayoutManager(this, 3));
        adapter = new FavVodAdapter();
        adapter.setOnItemClick(this::openDetail);
        adapter.setOnItemLongClick(this::showMenu);
        rv.setAdapter(adapter);

        refresh();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    private void refresh() {
        List<FavVod> list = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        keepByKey.clear();
        try {
            for (FavVod f : FavHelper.getVods()) {
                if (f == null || f.key() == null) continue;
                if (seen.add(f.key())) list.add(f);
            }
        } catch (Throwable ignored) {}
        try {
            for (Keep k : Keep.getVod()) {
                if (k == null || k.getKey() == null) continue;
                String key;
                try {
                    key = k.getSiteKey() + "@@" + k.getVodId();
                } catch (Throwable ignored) {
                    continue;
                }
                if (seen.add(key)) {
                    FavVod f = new FavVod(k.getSiteKey(), k.getVodId(), k.getVodName(), k.getVodPic());
                    f.createTime = k.getCreateTime();
                    list.add(f);
                    keepByKey.put(key, k);
                }
            }
        } catch (Throwable ignored) {}
        emptyView.setVisibility(list.isEmpty() ? View.VISIBLE : View.GONE);
        adapter.submit(list);
    }

    private void openDetail(FavVod f) {
        Intent i = new Intent(this, DetailActivity.class);
        i.putExtra(DetailActivity.KEY_SOURCE, f.sourceKey);
        i.putExtra(DetailActivity.KEY_VOD_ID, f.vodId);
        startActivity(i);
    }

    private void showMenu(FavVod f) {
        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle(f.name)
                .setItems(new String[]{"分享", "删除收藏"}, (d, which) -> {
                    if (which == 0) share(f);
                    else {
                        deleteFav(f);
                        refresh();
                    }
                })
                .show();
    }

    private void share(FavVod f) {
        Intent send = new Intent(Intent.ACTION_SEND);
        send.setType("text/plain");
        send.putExtra(Intent.EXTRA_SUBJECT, f.name);
        send.putExtra(Intent.EXTRA_TEXT, "" + f.name
                + "\n来源：" + f.sourceKey + "\n影片ID：" + f.vodId);
        startActivity(Intent.createChooser(send, "分享影片"));
    }

    /** 两处收藏都删：FavHelper 那条 + Keep 表里同 key 的那条（有才删）。 */
    private void deleteFav(FavVod f) {
        try {
            FavHelper.deleteVod(f.key());
        } catch (Throwable ignored) {}
        Keep keep = keepByKey.get(f.key());
        if (keep != null) {
            try {
                keep.delete();
            } catch (Throwable ignored) {}
        }
    }

    private void confirmClear() {
        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setMessage("确定清空全部收藏？")
                .setPositiveButton("清空", (d, w) -> {
                    try {
                        for (FavVod f : FavHelper.getVods()) FavHelper.deleteVod(f.key());
                    } catch (Throwable ignored) {}
                    try {
                        Keep.deleteAll();
                    } catch (Throwable ignored) {}
                    refresh();
                })
                .setNegativeButton("取消", null)
                .show();
    }
}
