package com.yingtu.plus.ui.fragment;

import android.content.Intent;
import android.view.View;
import android.widget.TextView;

import com.yingtu.plus.App;
import com.yingtu.plus.BuildConfig;
import com.yingtu.plus.R;
import com.yingtu.plus.base.BaseLazyFragment;
import com.yingtu.plus.ui.activity.FileActivity;
import com.yingtu.plus.ui.activity.OneKeyPyActivity;
import com.yingtu.plus.ui.activity.OnlineSiteListActivity;
import com.yingtu.plus.ui.activity.SettingActivity;
import com.yingtu.plus.ui.activity.SourceActivity;
import com.yingtu.plus.ui.activity.WebHomeListActivity;

/**
 * 我的页：纵向菜单——头部（“我的”+ 版本号）+ 设置 / 文件 / 站源管理 /
 * 在线网站 / 加站（一键写 PY，底栏中央钮改直播后加站入口挪到这里）/
 * 网页首页（原来内置的 web 条目从这里进）。
 * MissAV 网页版行已撤：用户不要 web 套壳，MissAV 改走 missavt 蜘蛛站（默认站）。
 * 行点按跳对应 Activity。
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
            bindRow(root, R.id.mineAddSite, OneKeyPyActivity.class);
            bindRow(root, R.id.mineWebHome, WebHomeListActivity.class);
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
}
