package com.yingtu.plus.ui.activity;

import android.app.Activity;
import android.content.Intent;
import android.view.MenuItem;

import androidx.annotation.NonNull;
import androidx.appcompat.widget.Toolbar;

import com.yingtu.plus.R;
import com.yingtu.plus.base.BaseActivity;
import com.yingtu.plus.bean.Class;
import com.yingtu.plus.bean.Result;
import com.yingtu.plus.ui.fragment.FolderFragment;

/**
 * 文件夹分类页：详情页可点击跳转的分类结果入口。
 * 结构照 webhtv mobile 版 FolderActivity 自己重写，走树里 BaseActivity
 * 的 getLayoutId/init 契约（树里 BaseActivity 没有 ViewBinding 入口）。
 */
public class FolderActivity extends BaseActivity {

    public static void start(Activity activity, String key, Result result) {
        if (activity == null || result == null || result.getTypes().isEmpty()) return;
        Intent intent = new Intent(activity, FolderActivity.class);
        intent.putExtra("key", key);
        intent.putExtra("result", result);
        activity.startActivity(intent);
    }

    private String getKey() {
        return getIntent().getStringExtra("key");
    }

    private Result getResult() {
        return getIntent().getParcelableExtra("result");
    }

    @Override
    protected int getLayoutId() {
        return R.layout.activity_folder;
    }

    @Override
    protected void init() {
        Toolbar toolbar = find(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }
        Class type = getResult().getTypes().get(0);
        setTitle(type.getTypeName());
        getSupportFragmentManager().beginTransaction().replace(R.id.container, FolderFragment.newInstance(getKey(), type, 8), "0").commit();
    }

    private FolderFragment getFragment() {
        return (FolderFragment) getSupportFragmentManager().findFragmentByTag("0");
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        if (item.getItemId() == android.R.id.home) onBackPressed();
        return super.onOptionsItemSelected(item);
    }

    @Override
    public void onBackPressed() {
        FolderFragment fragment = getFragment();
        if (fragment != null && fragment.canBack()) fragment.goBack();
        else super.onBackPressed();
    }
}
