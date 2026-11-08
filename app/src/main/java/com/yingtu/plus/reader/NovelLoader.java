package com.yingtu.plus.reader;

import android.text.TextUtils;

import com.github.catvod.crawler.Spider;
import com.yingtu.plus.api.loader.BaseLoader;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;

/**
 * 阅读源加载：复用影图现成的 PyLoader 加载链路。
 * PyLoader 本身没有小说 / 听书专用方法，但 Spider 基类已有
 * homeContent / categoryContent / detailContent / searchContent /
 * playerContent 全套接口，阅读源 py 与视频源 py 共用同一套，
 * 所以这里不再另建加载器，只做一层门面：取蜘蛛 + 解析 JSON。
 *
 * 接口口径（按火蚁TV树 assets/novel_spiders、audio_spiders 样本 py 实测）：
 * 小说源（如 ssxxs.py、aabook.py）：
 * homeContent -> {class:[{type_id,type_name}], list:[书]}
 * categoryContent(tid, pg) -> {list:[书], page, pagecount}
 * detailContent([书id]) -> {list:[{vod_id, vod_name, vod_pic, vod_actor,
 *   vod_content, vod_play_from, vod_play_url}]}
 * 其中 vod_play_url 有两种形态：单篇（ssxxs）是“阅读$novel://{title,content}”；
 * 多章（aabook）是“标题$章节id#标题$章节id”，章节正文再走
 * playerContent(flag, 章节id) -> {url:"novel://{title,content}"} 取。
 * 漫画源（如 csmanhua.py、55comics.py）：
 * detailContent 同上，章节正文走 playerContent(flag, 章节id)
 * -> {url:"pics://图1&&图2..."}，header 里带 Referer。
 * 听书源（如 tingyou.py）：
 * detailContent -> vod_play_url 是“标题$专辑id|章节序号”以 # 连接，
 * playerContent(flag, "专辑id|章节序号") -> {url/playUrl: 音频直链, header:{...}}。
 * 书字段统一按 vod_id / vod_name / vod_pic / vod_actor / vod_content 取。
 */
public class NovelLoader {

    /** 一本书 / 一个专辑 */
    public static class Book {
        public String id = "";
        public String title = "";
        public String pic = "";
        public String author = "";
        public String desc = "";
        public String remarks = "";
        public String playFrom = "";
        /** detailContent 返回的原始 vod_play_url（章节串或内联 novel://） */
        public String playUrl = "";
        public String siteKey = "";
        public String siteApi = "";
        public String siteExt = "";
    }

    /** 一章：标题 + 章节 id（playerContent 的 id 参数） */
    public static class Chapter {
        public String title = "";
        public String id = "";

        public Chapter(String title, String id) {
            this.title = title == null ? "" : title;
            this.id = id == null ? "" : id;
        }
    }

    /** 章节正文：playerContent 返回的 novel:// JSON 解析结果 */
    public static class ChapterContent {
        public String title = "";
        public String content = "";
    }

    /** 音频章节：playerContent 返回的直链 + 请求头 */
    public static class AudioResult {
        public String url = "";
        public HashMap<String, String> header = new HashMap<>();
    }

    /** 详情：书信息 + 章节列表（单篇小说章节列表为空，正文在 playUrl 里） */
    public static class BookDetail {
        public Book book = new Book();
        public List<Chapter> chapters = new ArrayList<>();
    }

    private NovelLoader() {
    }

    /** 取阅读源蜘蛛：走 BaseLoader -> PyLoader，与视频 py 源同一条加载链路 */
    public static Spider getSpider(String siteKey, String api, String ext) {
        return BaseLoader.get().getSpider(siteKey, api, ext == null ? "" : ext, "");
    }

    /** 空串 / 非 JSON 一律返回空对象，不抛异常（蜘蛛未取到内容时走这条） */
    private static JSONObject parse(String raw) {
        try {
            if (TextUtils.isEmpty(raw)) return new JSONObject();
            return new JSONObject(raw);
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    /** 首页分类：homeContent 的 class 数组 */
    public static List<Chapter> homeClasses(Spider spider) throws Exception {
        List<Chapter> classes = new ArrayList<>();
        JSONObject json = parse(spider.homeContent(true));
        JSONArray array = json.optJSONArray("class");
        if (array == null) return classes;
        for (int i = 0; i < array.length(); i++) {
            JSONObject item = array.optJSONObject(i);
            if (item == null) continue;
            classes.add(new Chapter(
                    item.optString("type_name"),
                    item.optString("type_id")));
        }
        return classes;
    }

    /** 分类书单：categoryContent 的 list 数组 */
    public static List<Book> categoryBooks(Spider spider, String tid, int pg) throws Exception {
        JSONObject json = new JSONObject(
                spider.categoryContent(tid, String.valueOf(pg), false, new HashMap<>()));
        return parseBookList(json.optJSONArray("list"));
    }

    /** 首页推荐：homeContent 的 list 数组 */
    public static List<Book> homeBooks(Spider spider) throws Exception {
        JSONObject json = parse(spider.homeContent(true));
        return parseBookList(json.optJSONArray("list"));
    }

    /** 搜索：searchContent 的 list 数组 */
    public static List<Book> searchBooks(Spider spider, String key) throws Exception {
        JSONObject json = parse(spider.searchContent(key, false));
        return parseBookList(json.optJSONArray("list"));
    }

    /** 详情：detailContent，解析书信息与章节列表 */
    public static BookDetail bookDetail(Spider spider, String bookId) throws Exception {
        BookDetail detail = new BookDetail();
        JSONObject json = parse(spider.detailContent(
                Collections.singletonList(bookId)));
        JSONArray array = json.optJSONArray("list");
        if (array == null || array.length() == 0) return detail;
        JSONObject vod = array.optJSONObject(0);
        if (vod == null) return detail;
        Book book = parseBook(vod);
        detail.book = book;
        detail.chapters = ReadingRouter.parsePlayUrlChapters(book.playUrl);
        return detail;
    }

    /**
     * 小说章节正文：playerContent(flag, 章节id) 返回 novel:// JSON。
     * flag 用详情的 vod_play_from（如“小说”），传空则用默认。
     */
    public static ChapterContent chapterContent(Spider spider, String flag, String chapterId) throws Exception {
        ChapterContent result = new ChapterContent();
        JSONObject json = parse(spider.playerContent(
                TextUtils.isEmpty(flag) ? "小说" : flag, chapterId, new ArrayList<>()));
        String url = firstNonEmpty(json.optString("url"), json.optString("playUrl"));
        if (TextUtils.isEmpty(url)) return result;
        JSONObject payload = ReadingRouter.parseNovelPayload(url);
        if (payload != null) {
            result.title = payload.optString("title");
            result.content = payload.optString("content");
        } else if (url.startsWith("novel://")) {
            result.content = url.substring("novel://".length());
        }
        return result;
    }

    /** 漫画章节图片：playerContent(flag, 章节id) 返回 pics:// 图链 */
    public static List<String> chapterImages(Spider spider, String flag, String chapterId) throws Exception {
        JSONObject json = parse(spider.playerContent(
                TextUtils.isEmpty(flag) ? "画" : flag, chapterId, new ArrayList<>()));
        String url = firstNonEmpty(json.optString("url"), json.optString("playUrl"));
        return ReadingRouter.parsePicsUrls(url);
    }

    /** 听书章节音频：playerContent(flag, "专辑id|章节序号") 返回直链 + 请求头 */
    public static AudioResult chapterAudio(Spider spider, String flag, String chapterId) throws Exception {
        AudioResult result = new AudioResult();
        JSONObject json = parse(spider.playerContent(
                TextUtils.isEmpty(flag) ? "听书" : flag, chapterId, new ArrayList<>()));
        result.url = firstNonEmpty(json.optString("url"), json.optString("playUrl"));
        JSONObject header = json.optJSONObject("header");
        if (header != null) {
            JSONArray names = header.names();
            if (names != null) {
                for (int i = 0; i < names.length(); i++) {
                    String name = names.optString(i);
                    result.header.put(name, header.optString(name));
                }
            }
        }
        return result;
    }

    /** 章节 id 是纯数字的简写：playerContent 有的源只认数字 id */
    public static String numericId(String chapterId) {
        if (TextUtils.isEmpty(chapterId)) return "";
        String digits = chapterId.replaceAll("\\D", "");
        return TextUtils.isEmpty(digits) ? chapterId : digits;
    }

    private static List<Book> parseBookList(JSONArray array) {
        List<Book> books = new ArrayList<>();
        if (array == null) return books;
        for (int i = 0; i < array.length(); i++) {
            JSONObject item = array.optJSONObject(i);
            if (item == null) continue;
            books.add(parseBook(item));
        }
        return books;
    }

    private static Book parseBook(JSONObject vod) {
        Book book = new Book();
        book.id = vod.optString("vod_id");
        book.title = vod.optString("vod_name");
        book.pic = vod.optString("vod_pic");
        book.author = firstNonEmpty(vod.optString("vod_actor"), vod.optString("vod_director"));
        book.desc = vod.optString("vod_content");
        book.remarks = vod.optString("vod_remarks");
        book.playFrom = vod.optString("vod_play_from");
        book.playUrl = vod.optString("vod_play_url");
        return book;
    }

    private static String firstNonEmpty(String a, String b) {
        return TextUtils.isEmpty(a) ? (b == null ? "" : b) : a;
    }
}
