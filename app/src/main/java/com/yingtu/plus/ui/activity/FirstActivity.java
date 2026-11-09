package com.yingtu.plus.ui.activity;

import android.content.Intent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.view.animation.Animation;
import android.view.animation.ScaleAnimation;
import android.widget.ImageView;

import com.yingtu.plus.R;
import com.yingtu.plus.base.BaseActivity;

/**
 * 启动页：仅显示 Logo 图标 + 应用名，0.6 秒后跳转到首页。
 */
public class FirstActivity extends BaseActivity {

    @Override
    protected int getLayoutId() {
        return R.layout.activity_first;
    }

    @Override
    protected void init() {
        // 应用自定义壁纸（解码结果进程内缓存，首页复用同一张，不重复解码）
        try {
            android.graphics.Bitmap bmp =
                    com.yingtu.plus.util.WallpaperHelper.getWallpaperBitmap(this);
            if (bmp != null) {
                getWindow().setBackgroundDrawable(
                        new android.graphics.drawable.BitmapDrawable(bmp));
            }
        } catch (Throwable e) {
            e.printStackTrace();
            com.yingtu.plus.App.logCaught(e);
        }
        Window window = getWindow();
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        window.addFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS);

        ImageView ivLogo = find(R.id.ivLogo);

        // Logo 入场动画
        ScaleAnimation anim = new ScaleAnimation(
                0.7f, 1.0f, 0.7f, 1.0f,
                Animation.RELATIVE_TO_SELF, 0.5f,
                Animation.RELATIVE_TO_SELF, 0.5f);
        anim.setDuration(600);
        anim.setFillAfter(true);
        ivLogo.startAnimation(anim);

        // 0.6 秒后跳转：默认启动（设置 → 系统设置）决定进哪个区
        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
            int start = 0;
            try {
                start = com.orhanobut.hawk.Hawk.get(com.yingtu.plus.util.HawkConfig.DEFAULT_START_PAGE, 0);
            } catch (Throwable ignored) {
            }
            switch (start) {
                case 1: { // 直播
                    Intent i = new Intent(this, HomeActivity.class);
                    i.putExtra(HomeActivity.EXTRA_NAV_TAB, HomeActivity.NAV_LIVE);
                    startActivity(i);
                    break;
                }
                case 2: { // 文件
                    Intent i = new Intent(this, HomeActivity.class);
                    i.putExtra(HomeActivity.EXTRA_NAV_TAB, HomeActivity.NAV_FILE);
                    startActivity(i);
                    break;
                }
                case 3: // 历史
                    startActivity(new Intent(this, HistoryActivity.class));
                    break;
                case 4: // 收藏
                    startActivity(new Intent(this, CollectActivity.class));
                    break;
                case 5: // 最近（=历史页）
                    startActivity(new Intent(this, HistoryActivity.class));
                    break;
                default: // 首页
                    startActivity(new Intent(this, HomeActivity.class));
                    break;
            }
            finish();
        }, 600);
    }
}
