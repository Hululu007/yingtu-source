package com.yingtu.plus.utils;

import static android.widget.ImageView.ScaleType.CENTER_CROP;
import static android.widget.ImageView.ScaleType.FIT_CENTER;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.drawable.Drawable;
import android.text.TextUtils;
import android.view.View;
import android.widget.ImageView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.bumptech.glide.Glide;
import com.bumptech.glide.RequestBuilder;
import com.bumptech.glide.load.DataSource;
import com.bumptech.glide.load.engine.GlideException;
import com.bumptech.glide.load.model.GlideUrl;
import com.bumptech.glide.load.model.LazyHeaders;
import com.bumptech.glide.request.RequestListener;
import com.bumptech.glide.request.target.Target;
import com.yingtu.plus.App;
import com.yingtu.plus.R;
import com.yingtu.plus.api.config.VodConfig;
import com.yingtu.plus.impl.CustomTarget;
import com.github.catvod.utils.Json;
import com.google.common.net.HttpHeaders;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import jahirfiquitiva.libs.textdrawable.TextDrawable;

public class ImgUtil {

    // 加载失败过的封面地址 → 失败时间（毫秒）。慢网下一次超时多半是网络抖动，
    // 不是图死了：以前用永久黑名单，超时一次整次启动都出不了图。现改为 60 秒
    // 冷却：60 秒内不再重复请求（省流量），60 秒后滚动回来/重进可再试。
    private static final Map<String, Long> failed = Collections.synchronizedMap(new HashMap<>());
    private static final long FAILED_RETRY_MS = 60 * 1000L;

    private static boolean isRecentlyFailed(String url) {
        Long t = failed.get(url);
        if (t == null) return false;
        if (System.currentTimeMillis() - t < FAILED_RETRY_MS) return true;
        failed.remove(url);
        return false;
    }

    private static void markFailed(String url) {
        failed.put(url, System.currentTimeMillis());
    }

    private static void clearFailed(String url) {
        failed.remove(url);
    }

    public static void logo(ImageView view) {
        try {
            Glide.with(view).load(UrlUtil.convert(VodConfig.get().getConfig().getLogo())).circleCrop().override(Target.SIZE_ORIGINAL, Target.SIZE_ORIGINAL).error(R.drawable.ic_logo).into(view);
        } catch (Throwable e) {
            e.printStackTrace();
        }
    }

    public static void load(String url, CustomTarget<Bitmap> target) {
        try {
            Glide.with(App.get()).asBitmap().load(getUrl(url)).override(ResUtil.dp2px(96), ResUtil.dp2px(96)).error(R.drawable.artwork).into(target);
        } catch (Throwable e) {
            e.printStackTrace();
        }
    }

    public static void load(Context context, String url, CustomTarget<Drawable> target) {
        try {
            Glide.with(context).load(getUrl(url)).override(ResUtil.getScreenWidth(), ResUtil.getScreenHeight()).error(R.drawable.artwork).into(target);
        } catch (Throwable e) {
            e.printStackTrace();
        }
    }

    public static void load(Context context, String url, int width, int height, CustomTarget<Drawable> target) {
        try {
            Glide.with(context).load(getUrl(url)).override(width, height).error(R.drawable.artwork).into(target);
        } catch (Throwable e) {
            e.printStackTrace();
        }
    }

    public static void preload(Context context, String url) {
        if (TextUtils.isEmpty(url)) return;
        try {
            Glide.with(context).load(getUrl(url)).override(ResUtil.getScreenWidth(), ResUtil.getScreenHeight()).preload();
        } catch (Throwable e) {
            e.printStackTrace();
        }
    }

    public static void load(String text, String url, ImageView view) {
        load(text, url, view, true);
    }

    public static void load(String text, String url, ImageView view, boolean vod) {
        load(text, url, view, vod, 0, 0);
    }

    public static void load(String text, String url, ImageView view, int width, int height) {
        load(text, url, view, true, width, height);
    }

    public static void load(String text, String url, ImageView view, boolean vod, int width, int height) {
        view.setScaleType(vod ? CENTER_CROP : FIT_CENTER);
        if (!vod) view.setVisibility(TextUtils.isEmpty(url) ? View.GONE : View.VISIBLE);
        if (TextUtils.isEmpty(url) || isRecentlyFailed(url)) view.setImageDrawable(getTextDrawable(text, vod));
        else try {
            RequestBuilder<Drawable> builder = Glide.with(view).load(getUrl(url)).listener(getListener(text, url, view, vod));
            // 加载中先显文字占位：慢网下图半天出不来时不黑屏、不留上一张复用的旧图。
            builder.placeholder(getTextDrawable(text, vod));
            if (width > 0 && height > 0) builder.override(width, height);
            if (vod) builder.centerCrop().into(view);
            else builder.fitCenter().into(view);
        } catch (Throwable e) {
            e.printStackTrace();
        }
    }

    public static Object getUrl(String url) {
        if (TextUtils.isEmpty(url)) return null;
        try {
            return getUrlInternal(url);
        } catch (Throwable e) {
            // 兜底：封面带畸形 @Headers=/@Cookie= 时绝不向调用方抛错。
            // 多处调用方（DetailActivity、FavVodAdapter、HistoryAdapter、
            // DramaFeedAdapter、PicsViewer）没有包 try/catch，抛出去就是闪退。
            // 退化为不带头信息的原地址加载，图能出、页不崩。
            e.printStackTrace();
            try {
                String clean = UrlUtil.convert(url);
                int at = clean.indexOf('@');
                if (at >= 0) clean = clean.substring(0, at);
                return TextUtils.isEmpty(clean) ? null : new GlideUrl(clean);
            } catch (Throwable ignored) {
                return url;
            }
        }
    }

    private static Object getUrlInternal(String url) {
        String param = null;
        url = UrlUtil.convert(url);
        if (url.startsWith("data:")) return url;
        LazyHeaders.Builder builder = new LazyHeaders.Builder();
        if (url.contains("@Headers=")) addHeader(builder, param = url.split("@Headers=")[1].split("@")[0]);
        if (url.contains("@Cookie=")) builder.addHeader(HttpHeaders.COOKIE, param = url.split("@Cookie=")[1].split("@")[0]);
        if (url.contains("@Referer=")) builder.addHeader(HttpHeaders.REFERER, param = url.split("@Referer=")[1].split("@")[0]);
        if (url.contains("@User-Agent=")) builder.addHeader(HttpHeaders.USER_AGENT, param = url.split("@User-Agent=")[1].split("@")[0]);
        url = param == null ? url : url.split("@")[0];
        return TextUtils.isEmpty(url) ? null : new GlideUrl(url, builder.build());
    }

    private static void addHeader(LazyHeaders.Builder builder, String header) {
        try {
            Map<String, String> map = Json.toMap(Json.parse(header));
            if (map == null) return;
            for (Map.Entry<String, String> entry : map.entrySet()) builder.addHeader(UrlUtil.fixHeader(entry.getKey()), entry.getValue());
        } catch (Throwable e) {
            // 畸形 @Headers= JSON（如午夜站封面）：只丢这一个头，不抛错、不崩页。
            e.printStackTrace();
        }
    }

    private static Drawable getTextDrawable(String text, boolean vod) {
        TextDrawable.Builder builder = new TextDrawable.Builder();
        text = TextUtils.isEmpty(text) ? "！" : text.substring(0, 1);
        // Local TextDrawable stub does not extend Drawable; its toShapeDrawable()
        // returns a GradientDrawable (a real Drawable). The stub cannot draw the
        // letter itself, so the placeholder is a solid color shape.
        builder.color(ColorGenerator.get400(text));
        if (vod) return builder.buildRect(text).toShapeDrawable();
        return builder.buildRoundRect(text, ResUtil.dp2px(4)).toShapeDrawable();
    }

    private static RequestListener<Drawable> getListener(String text, String url, ImageView view, boolean vod) {
        return new RequestListener<>() {
            @Override
            public boolean onLoadFailed(@Nullable GlideException e, Object model, @NonNull Target<Drawable> target, boolean isFirstResource) {
                view.setImageDrawable(getTextDrawable(text, vod));
                markFailed(url);
                return true;
            }

            @Override
            public boolean onResourceReady(Drawable resource, Object model, Target<Drawable> target, DataSource dataSource, boolean isFirstResource) {
                clearFailed(url);
                return false;
            }
        };
    }
}
