package com.yingtu.plus.util;

import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.orhanobut.hawk.Hawk;
import com.yingtu.plus.ui.adapter.GridMovieAdapter;

/**
 * 搜索展示（SEARCH_SHOW：前半=搜索页，后半=聚搜页；每半 0=文字 1=图片 2=图文）。
 * 文字模式用纵向线性列表（只显片名行），图片/图文模式用 3 列网格。
 */
public final class SearchDisplay {

    private SearchDisplay() {}

    /** 搜索页展示模式：0=文字 1=图片。 */
    public static int searchMode() {
        int v = 1;
        try {
            v = Hawk.get(HawkConfig.SEARCH_SHOW, 1);
        } catch (Throwable ignored) {
        }
        return v >= 3 ? 1 : 0;
    }

    /** 聚搜页展示模式：0=文字 1=图片 2=图文。 */
    public static int fastMode() {
        int v = 1;
        try {
            v = Hawk.get(HawkConfig.SEARCH_SHOW, 1);
        } catch (Throwable ignored) {
        }
        int m = v % 3;
        return m < 0 ? 0 : m;
    }

    public static void applySearch(RecyclerView rv, GridMovieAdapter adapter) {
        apply(rv, adapter, searchMode());
    }

    public static void applyFast(RecyclerView rv, GridMovieAdapter adapter) {
        apply(rv, adapter, fastMode());
    }

    private static void apply(RecyclerView rv, GridMovieAdapter adapter, int mode) {
        if (rv == null || adapter == null) return;
        adapter.setDisplayMode(mode);
        if (mode == 0) {
            rv.setLayoutManager(new LinearLayoutManager(rv.getContext(), RecyclerView.VERTICAL, false));
        } else {
            rv.setLayoutManager(new GridLayoutManager(rv.getContext(), 3));
        }
    }
}
