package com.yingtu.plus.reader;

import android.content.Context;
import android.content.Intent;
import android.text.TextUtils;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 阅读分流入口。影视区 / 播放器拿到非视频地址时先走这里分流，
 * 小说进小说阅读页，漫画 / 图片进图片浏览，音频进听书播放，
 * 三类内容都不送视频解码。
 *
 * 格式口径（与火蚁TV树一致）：
 * 小说：阅读$novel://{title,content}
 * 图片 / 漫画：查看$pics://{url1&&url2}
 * 听书：详情页 vod_play_url 是“标题$专辑id|章节序号”以 # 连接，
 * 经阅读源 playerContent 取到音频直链后再播。
 */
public class ReadingRouter {

    /** 小说阅读页 intent 附加字段：内联正文模式的标题 */
    public static final String EXTRA_TITLE = "reader_title";
    /** 小说阅读页 intent 附加字段：内联正文模式的正文 */
    public static final String EXTRA_CONTENT = "reader_content";
    /** 图片浏览 intent 附加字段：图片地址列表 */
    public static final String EXTRA_IMAGES = "reader_images";
    /** 章节模式：阅读源 key（如 aabook） */
    public static final String EXTRA_SITE_KEY = "reader_site_key";
    /** 章节模式：阅读源 api（py 文件路径或地址） */
    public static final String EXTRA_SITE_API = "reader_site_api";
    /** 章节模式：阅读源 ext */
    public static final String EXTRA_SITE_EXT = "reader_site_ext";
    /** 章节模式：书 / 专辑 id */
    public static final String EXTRA_BOOK_ID = "reader_book_id";
    /** 章节列表 JSON：[{"title": "...", "id": "..."}]，供上下章切换 */
    public static final String EXTRA_CHAPTERS = "reader_chapters";
    /** 音频直链 */
    public static final String EXTRA_AUDIO_URL = "reader_audio_url";

    private ReadingRouter() {
    }

    /** 是否小说地址：novel:// 开头（前面可带“阅读$”播放线路前缀） */
    public static boolean isNovelUrl(String url) {
        return stripFlag(url).startsWith("novel://");
    }

    /** 是否图片 / 漫画地址：pics:// 开头（前面可带“查看$”播放线路前缀） */
    public static boolean isPicsUrl(String url) {
        return stripFlag(url).startsWith("pics://");
    }

    /**
     * 是否音频地址。树里没有独立的音频地址判定口径
     *（Sniffer.isVideoFormat 把 mp3/m4a 当通用直链），
     * 这里按 audio:// 前缀 + 常见音频后缀直白判定。
     */
    public static boolean isAudioUrl(String url) {
        if (TextUtils.isEmpty(url)) return false;
        String value = stripFlag(url).trim().toLowerCase(Locale.US);
        if (value.startsWith("audio://")) return true;
        int query = value.indexOf('?');
        if (query > 0) value = value.substring(0, query);
        int hash = value.indexOf('#');
        if (hash > 0) value = value.substring(0, hash);
        return value.endsWith(".mp3") || value.endsWith(".m4a")
                || value.endsWith(".aac") || value.endsWith(".flac")
                || value.endsWith(".wav") || value.endsWith(".ogg")
                || value.endsWith(".opus") || value.endsWith(".wma")
                || value.endsWith(".ape") || value.endsWith(".m4b");
    }

    /** 打开小说阅读页：解析 novel:// 后面的 {title,content} JSON 后打开 */
    public static void openNovel(Context ctx, String url, String title) {
        if (ctx == null || TextUtils.isEmpty(url)) return;
        String stripped = stripFlag(url);
        String payload = stripped.startsWith("novel://")
                ? stripped.substring("novel://".length()) : stripped;
        String bookTitle = title;
        String content = "";
        try {
            JSONObject json = new JSONObject(payload);
            if (TextUtils.isEmpty(bookTitle)) bookTitle = json.optString("title");
            content = json.optString("content");
        } catch (Exception e) {
            // 不是 JSON 就当纯文本正文直接显示
            content = payload;
        }
        Intent intent = new Intent(ctx, NovelReaderActivity.class);
        intent.putExtra(EXTRA_TITLE, TextUtils.isEmpty(bookTitle) ? "阅读" : bookTitle);
        intent.putExtra(EXTRA_CONTENT, content);
        ctx.startActivity(intent);
    }

    /** 打开图片 / 漫画浏览页：pics:// 后面是 && 连接的多图地址 */
    public static void openPics(Context ctx, String url, String title) {
        if (ctx == null || TextUtils.isEmpty(url)) return;
        String stripped = stripFlag(url);
        String payload = stripped.startsWith("pics://")
                ? stripped.substring("pics://".length()) : stripped;
        ArrayList<String> images = new ArrayList<>();
        for (String item : payload.split("&&")) {
            String image = item.trim();
            if (!TextUtils.isEmpty(image)) images.add(image);
        }
        if (images.isEmpty()) return;
        Intent intent = new Intent(ctx, PicsViewerActivity.class);
        intent.putExtra(EXTRA_TITLE, TextUtils.isEmpty(title) ? "图片" : title);
        intent.putStringArrayListExtra(EXTRA_IMAGES, images);
        ctx.startActivity(intent);
    }

    /** 打开听书播放页：直链音频直接播 */
    public static void openAudio(Context ctx, String url, String title) {
        if (ctx == null || TextUtils.isEmpty(url)) return;
        Intent intent = new Intent(ctx, AudioBookPlayer.class);
        intent.putExtra(EXTRA_TITLE, TextUtils.isEmpty(title) ? "听书" : title);
        intent.putExtra(EXTRA_AUDIO_URL, stripFlag(url));
        ctx.startActivity(intent);
    }

    /** 去掉“阅读$”“查看$”这类播放线路前缀，返回裸地址 */
    public static String stripFlag(String url) {
        if (TextUtils.isEmpty(url)) return "";
        String value = url.trim();
        int dollar = value.indexOf('$');
        if (dollar >= 0) {
            String rest = value.substring(dollar + 1);
            if (rest.startsWith("novel://") || rest.startsWith("pics://")
                    || rest.startsWith("audio://") || rest.startsWith("http")) {
                return rest;
            }
        }
        return value;
    }

    /** 解析 novel:// 的 {title,content}，失败返回 null */
    public static JSONObject parseNovelPayload(String url) {
        try {
            String payload = stripFlag(url).substring("novel://".length());
            return new JSONObject(payload);
        } catch (Exception e) {
            return null;
        }
    }

    /** 解析 pics:// 的图片地址列表 */
    public static List<String> parsePicsUrls(String url) {
        List<String> images = new ArrayList<>();
        if (TextUtils.isEmpty(url)) return images;
        String payload = stripFlag(url);
        if (payload.startsWith("pics://")) payload = payload.substring("pics://".length());
        for (String item : payload.split("&&")) {
            String image = item.trim();
            if (!TextUtils.isEmpty(image)) images.add(image);
        }
        return images;
    }

    /** 解析 vod_play_url 的章节串“标题$章节id#标题$章节id”，返回章节列表 */
    public static List<NovelLoader.Chapter> parsePlayUrlChapters(String playUrl) {
        List<NovelLoader.Chapter> chapters = new ArrayList<>();
        if (TextUtils.isEmpty(playUrl)) return chapters;
        for (String part : playUrl.split("#")) {
            if (TextUtils.isEmpty(part)) continue;
            int dollar = part.lastIndexOf('$');
            if (dollar > 0) {
                chapters.add(new NovelLoader.Chapter(
                        part.substring(0, dollar), part.substring(dollar + 1)));
            }
        }
        return chapters;
    }

    /** 章节列表转 JSON 字符串，供 intent 跨页传递 */
    public static String chaptersToJson(List<NovelLoader.Chapter> chapters) {
        try {
            JSONArray array = new JSONArray();
            for (NovelLoader.Chapter chapter : chapters) {
                JSONObject item = new JSONObject();
                item.put("title", chapter.title);
                item.put("id", chapter.id);
                array.put(item);
            }
            return array.toString();
        } catch (Exception e) {
            return "[]";
        }
    }

    /** intent 里取回章节列表 JSON */
    public static List<NovelLoader.Chapter> chaptersFromJson(String json) {
        List<NovelLoader.Chapter> chapters = new ArrayList<>();
        if (TextUtils.isEmpty(json)) return chapters;
        try {
            JSONArray array = new JSONArray(json);
            for (int i = 0; i < array.length(); i++) {
                JSONObject item = array.optJSONObject(i);
                if (item == null) continue;
                chapters.add(new NovelLoader.Chapter(
                        item.optString("title"), item.optString("id")));
            }
        } catch (Exception ignored) {
        }
        return chapters;
    }
}
