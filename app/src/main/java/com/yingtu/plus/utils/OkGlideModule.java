package com.yingtu.plus.utils;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;

import com.bumptech.glide.Glide;
import com.bumptech.glide.GlideBuilder;
import com.bumptech.glide.Registry;
import com.bumptech.glide.annotation.GlideModule;
import com.bumptech.glide.integration.okhttp3.OkHttpUrlLoader;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.bumptech.glide.load.engine.cache.InternalCacheDiskCacheFactory;
import com.bumptech.glide.load.model.GlideUrl;
import com.bumptech.glide.module.AppGlideModule;
import com.bumptech.glide.request.RequestOptions;
import com.github.catvod.net.OkHttp;
import com.orhanobut.hawk.Hawk;
import com.yingtu.plus.util.HawkConfig;

import java.io.InputStream;

@GlideModule
public class OkGlideModule extends AppGlideModule {

    @Override
    public void applyOptions(@NonNull Context context, @NonNull GlideBuilder builder) {
        builder.setLogLevel(Log.ERROR);
        // 磁盘缓存 512MB：慢网下多站封面多，Glide 默认 250MB 淘汰太快，
        // 二次打开又要重新下载。512MB 只占内置存储，封面单张一般几十 KB。
        builder.setDiskCache(new InternalCacheDiskCacheFactory(context, 512L * 1024 * 1024));
        // 图片磁盘缓存策略（设置 → 更多设置）：重启应用后生效。
        // 默认改为 1=AUTOMATIC：用户没选过时也缓存，二次打开直接读磁盘，
        // 不再每次都走慢网重新下载。用户在设置里显式选“关闭”(0)仍走 NONE。
        int mode = 1;
        try {
            mode = Hawk.get(HawkConfig.GLIDE_CACHE, 1);
        } catch (Throwable ignored) {
        }
        DiskCacheStrategy strategy;
        switch (mode) {
            case 1: strategy = DiskCacheStrategy.AUTOMATIC; break;
            case 2: strategy = DiskCacheStrategy.DATA; break;
            case 3: strategy = DiskCacheStrategy.RESOURCE; break;
            default: strategy = DiskCacheStrategy.NONE; break;
        }
        builder.setDefaultRequestOptions(new RequestOptions().diskCacheStrategy(strategy));
    }

    @Override
    public void registerComponents(@NonNull Context context, @NonNull Glide glide, Registry registry) {
        registry.replace(GlideUrl.class, InputStream.class, new OkHttpUrlLoader.Factory(OkHttp.client()));
    }
}