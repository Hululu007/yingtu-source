package com.yingtu.plus.service;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import com.orhanobut.hawk.Hawk;
import com.yingtu.plus.ui.activity.HomeActivity;
import com.yingtu.plus.util.HawkConfig;

/**
 * 开机自动启动（设置 → 更多设置 → 开机自动启动）。
 * 只在开关开时拉起首页；Android 10+ 之后后台拉起 Activity 可能被系统拦截，
 * 拦截时静默失败（不弹错、不崩），属于系统限制，见回报。
 */
public class BootStartReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (context == null || intent == null) return;
        if (!Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) return;
        boolean on = false;
        try {
            on = Hawk.get(HawkConfig.BOOT_START, false);
        } catch (Throwable ignored) {
        }
        if (!on) return;
        try {
            Intent home = new Intent(context, HomeActivity.class);
            home.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(home);
        } catch (Throwable ignored) {
        }
    }
}
