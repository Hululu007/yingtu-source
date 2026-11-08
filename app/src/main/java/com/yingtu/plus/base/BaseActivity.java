package com.yingtu.plus.base;

import android.os.Bundle;
import android.view.View;

import androidx.annotation.LayoutRes;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.FragmentActivity;

/**
 * 所有 Activity 的基类，统一沉浸式状态栏与布局注入方式。
 */
public abstract class BaseActivity extends AppCompatActivity {

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        int layout = getLayoutId();
        if (layout != 0) {
            setContentView(layout);
        }
        init();
    }

    @LayoutRes
    protected abstract int getLayoutId();

    protected abstract void init();

    /**
     * 与 Fragment 的 getActivity() 对齐：Activity 自身就是宿主，
     * 供 VideoActivity 详情页可点击跳转处直接传宿主调 FolderActivity.start。
     */
    protected FragmentActivity getActivity() {
        return this;
    }

    protected <T extends View> T find(int id) {
        return findViewById(id);
    }
}
