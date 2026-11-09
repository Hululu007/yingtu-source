package com.yingtu.plus.util;

import android.content.Context;
import android.widget.Toast;

import com.orhanobut.hawk.Hawk;

/**
 * 源消息开关门：只管“源加载失败/源相关提示”这两类 toast，其余用户操作确认 toast 不走这里。
 * 关时对应提示直接不弹（不是弹个“已关闭”来代替）。
 */
public final class SourceMsgGate {

    private SourceMsgGate() {}

    /** 源错误消息显示（默认开） */
    public static boolean showError() {
        try {
            return Hawk.get(HawkConfig.SRC_ERR_MSG, true);
        } catch (Throwable e) {
            return true;
        }
    }

    /** 源消息显示（默认开） */
    public static boolean showInfo() {
        try {
            return Hawk.get(HawkConfig.SRC_MSG, true);
        } catch (Throwable e) {
            return true;
        }
    }

    public static void error(Context ctx, String msg) {
        if (ctx == null || !showError()) return;
        Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show();
    }

    public static void errorShort(Context ctx, String msg) {
        if (ctx == null || !showError()) return;
        Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show();
    }

    public static void info(Context ctx, String msg) {
        if (ctx == null || !showInfo()) return;
        Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show();
    }
}
