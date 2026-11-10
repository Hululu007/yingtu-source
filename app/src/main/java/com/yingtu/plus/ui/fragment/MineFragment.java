package com.yingtu.plus.ui.fragment;

import android.content.Intent;
import android.view.View;
import android.widget.TextView;

import com.yingtu.plus.App;
import com.yingtu.plus.BuildConfig;
import com.yingtu.plus.R;
import com.yingtu.plus.base.BaseLazyFragment;
import com.yingtu.plus.ui.activity.FileActivity;
import com.yingtu.plus.ui.activity.OnlineSiteListActivity;
import com.yingtu.plus.ui.activity.SettingActivity;
import com.yingtu.plus.ui.activity.SourceActivity;
import com.yingtu.plus.ui.activity.WebHomeActivity;
import com.yingtu.plus.util.WebHomeHelper;

/**
 * 我的页：纵向菜单——头部（“我的”+ 版本号）+ 设置 / 文件 / 站源管理 /
 * 在线网站 / MissAV 网页版。行点按跳对应 Activity。
 */
public class MineFragment extends BaseLazyFragment {

    private TextView mineVersion;

    @Override
    protected int getLayoutId() {
        return R.layout.fragment_mine;
    }

    @Override
    protected void init(View root) {
        try {
            mineVersion = root.findViewById(R.id.mineVersion);
            if (mineVersion != null) {
                mineVersion.setText("影图+ v" + BuildConfig.VERSION_NAME);
            }
            bindRow(root, R.id.mineSettings, SettingActivity.class);
            bindRow(root, R.id.mineFiles, FileActivity.class);
            bindRow(root, R.id.mineSources, SourceActivity.class);
            bindRow(root, R.id.mineOnline, OnlineSiteListActivity.class);
            View missav = root.findViewById(R.id.mineMissav);
            if (missav != null) {
                missav.setOnClickListener(v -> openMissavWeb());
            }
        } catch (Throwable e) {
            e.printStackTrace();
            App.logCaught(e);
        }
    }

    private void bindRow(View root, int rowId, final Class<?> activity) {
        View row = root.findViewById(rowId);
        if (row == null) return;
        row.setOnClickListener(v -> {
            try {
                if (getActivity() == null) return;
                startActivity(new Intent(getActivity(), activity));
            } catch (Throwable e) {
                e.printStackTrace();
            }
        });
    }

    /** MissAV 网页版（extras 照 HomeMainFragment.openMissavWeb）。 */
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
}
