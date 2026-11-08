package com.yingtu.plus.util;

import android.content.Context;
import android.net.Uri;

import com.orhanobut.hawk.Hawk;

/**
 * 壁纸管理工具：把用户选择的图片 URI 存到 Hawk，
 * 各 Activity/Fragment 调用 getWallpaper() 判断是否要应用。
 *
 * 闪退加固（2.0.0.1108 第三轮）：Hawk.init 没成功时任何 Hawk.get/put 都会抛
 * IllegalStateException("Hawk is not built...")，四个方法全部包 try/catch，
 * 出错只丢壁纸功能，不杀进程。
 */
public final class WallpaperHelper {

    /** 用户选定的壁纸 URI 字符串（null/empty=默认） */
    public static final String KEY = "custom_wallpaper";

    private WallpaperHelper() {}

    /** 设置自定义壁纸 */
    public static void setWallpaper(Context ctx, Uri uri) {
        if (uri == null) return;
        try {
            Hawk.put(KEY, uri.toString());
        } catch (Throwable e) {
            e.printStackTrace();
        }
    }

    /** 恢复默认 */
    public static void resetWallpaper(Context ctx) {
        try {
            Hawk.put(KEY, "");
        } catch (Throwable e) {
            e.printStackTrace();
        }
    }

    /** 返回当前自定义壁纸 URI；null=使用默认渐变 */
    public static Uri getWallpaperUri(Context ctx) {
        try {
            String s = Hawk.get(KEY, "");
            if (s == null || s.isEmpty()) return null;
            return Uri.parse(s);
        } catch (Throwable e) {
            e.printStackTrace();
            return null;
        }
    }

    /** 是否有自定义壁纸 */
    public static boolean hasCustomWallpaper(Context ctx) {
        return getWallpaperUri(ctx) != null;
    }
}
