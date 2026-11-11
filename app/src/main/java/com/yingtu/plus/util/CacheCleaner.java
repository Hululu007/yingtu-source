package com.yingtu.plus.util;

import android.content.Context;

import com.bumptech.glide.Glide;
import com.github.catvod.utils.Path;

import java.io.File;

/**
 * 自清播放缓存：只清 Glide 磁盘缓存与 Exo 播放缓存目录，不清站源配置与观看历史。
 * 磁盘清理放后台线程跑（Glide.clearDiskCache 要求不在主线程）。
 */
public final class CacheCleaner {

    private CacheCleaner() {}

    public static void clearPlayCache(Context ctx) {
        if (ctx == null) return;
        final Context app = ctx.getApplicationContext();
        try {
            Glide.get(app).clearMemory();
        } catch (Throwable ignored) {
        }
        new Thread(() -> {
            try {
                Glide.get(app).clearDiskCache();
            } catch (Throwable ignored) {
            }
            try {
                deleteDir(Path.exoCache());
            } catch (Throwable ignored) {
            }
            try {
                deleteDir(externalExoCacheDir(app));
            } catch (Throwable ignored) {
            }
        }).start();
    }

    /** 外置缓存目录下的 exo（与“缓存位置=外部存储”同一目录）；播放器侧接线前同样清这里。 */
    private static File externalExoCacheDir(Context app) {
        try {
            File ext = app.getExternalCacheDir();
            if (ext == null) return null;
            return new File(ext, "exo");
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static void deleteDir(File dir) {
        if (dir == null || !dir.exists()) return;
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) {
            if (f.isDirectory()) deleteDir(f);
            else f.delete();
        }
    }
}
