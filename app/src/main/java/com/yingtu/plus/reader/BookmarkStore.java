package com.yingtu.plus.reader;

import android.text.TextUtils;

import com.orhanobut.hawk.Hawk;

import org.json.JSONObject;

/**
 * 记忆书签：只记最后一处——最后章节 + 页内位置，切章、翻页、退出都保存，
 * 打开回到上次位置。不做手动多书签，不做云同步。
 * 存 Hawk，一本书一个 key（siteKey:bookId）。
 * 小说 / 漫画记章节序号 + 页内滚动位置；听书记章节序号 + 播放毫秒数。
 */
public class BookmarkStore {

    private static final String KEY_PREFIX = "reader_bookmark_";

    public static class Bookmark {
        /** 最后章节序号（章节列表里的下标） */
        public int chapterIndex = 0;
        /** 最后章节 id */
        public String chapterId = "";
        /** 最后章节标题 */
        public String chapterTitle = "";
        /** 页内位置：小说是 ScrollView 的 scrollY，听书是播放毫秒数，漫画是图片下标 */
        public int position = 0;
        public long updatedAt = 0;
    }

    private BookmarkStore() {
    }

    public static void save(String siteKey, String bookId, int chapterIndex,
                            String chapterId, String chapterTitle, int position) {
        if (TextUtils.isEmpty(bookId)) return;
        try {
            JSONObject json = new JSONObject();
            json.put("chapterIndex", chapterIndex);
            json.put("chapterId", chapterId == null ? "" : chapterId);
            json.put("chapterTitle", chapterTitle == null ? "" : chapterTitle);
            json.put("position", position);
            json.put("updatedAt", System.currentTimeMillis());
            Hawk.put(KEY_PREFIX + keyOf(siteKey, bookId), json.toString());
        } catch (Exception ignored) {
        }
    }

    /** 没有书签返回 null */
    public static Bookmark load(String siteKey, String bookId) {
        if (TextUtils.isEmpty(bookId)) return null;
        try {
            String json = Hawk.get(KEY_PREFIX + keyOf(siteKey, bookId), "");
            if (TextUtils.isEmpty(json)) return null;
            JSONObject obj = new JSONObject(json);
            Bookmark bookmark = new Bookmark();
            bookmark.chapterIndex = obj.optInt("chapterIndex", 0);
            bookmark.chapterId = obj.optString("chapterId");
            bookmark.chapterTitle = obj.optString("chapterTitle");
            bookmark.position = obj.optInt("position", 0);
            bookmark.updatedAt = obj.optLong("updatedAt", 0);
            return bookmark;
        } catch (Exception e) {
            return null;
        }
    }

    public static void clear(String siteKey, String bookId) {
        try {
            Hawk.delete(KEY_PREFIX + keyOf(siteKey, bookId));
        } catch (Exception ignored) {
        }
    }

    private static String keyOf(String siteKey, String bookId) {
        return (siteKey == null ? "" : siteKey) + ":" + bookId;
    }
}
