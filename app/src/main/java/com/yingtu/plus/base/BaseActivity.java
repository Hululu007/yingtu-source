package com.yingtu.plus.base;

import android.os.Bundle;
import android.view.View;
import android.view.WindowManager;

import androidx.annotation.LayoutRes;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.FragmentActivity;

import com.orhanobut.hawk.Hawk;
import com.yingtu.plus.util.HawkConfig;

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
        applyImmersive();
        init();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        // 沉浸式标志会被系统手势清掉，拿到焦点时按开关重设
        if (hasFocus) applyImmersive();
    }

    /** 沉浸式适配（设置 → 更多设置）：开时全屏沉浸（状态栏/导航栏滑出后自动隐藏）；
     * 关时不碰各页自己的标志（播放器等页自己管全屏，不被这里顶掉）。 */
    protected void applyImmersive() {
        boolean on = false;
        try {
            on = Hawk.get(HawkConfig.IMMERSIVE, false);
        } catch (Throwable ignored) {
        }
        if (!on) return;
        try {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS);
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        } catch (Throwable ignored) {
        }
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
