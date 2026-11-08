package com.yingtu.plus.reader;

import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.github.catvod.crawler.Spider;
import com.orhanobut.hawk.Hawk;
import com.yingtu.plus.R;
import com.yingtu.plus.base.BaseActivity;
import com.yingtu.plus.utils.Task;

import java.util.ArrayList;
import java.util.List;

/**
 * 小说阅读页。两种模式：
 * 内联模式：ReadingRouter.openNovel 传来的单篇 {title,content}（如 ssxxs 详情）；
 * 书籍模式：按 siteKey + bookId 走阅读源取详情、章节目录，章节正文走
 * playerContent 取 novel:// JSON（aabook 式多章小说）。
 * 排版思路参照火蚁学堂 Legado M 阅读器（字体、背景、目录、记忆书签），
 * 代码全部 Java 重写。
 * 书签：只记最后章节 + 页内 scrollY，切章、滚动停下、退出都保存，
 * 下次打开回到上次位置。
 */
public class NovelReaderActivity extends BaseActivity {

    private static final String KEY_FONT_SIZE = "reader_font_size";
    private static final String KEY_BG_MODE = "reader_bg_mode";

    /** 背景三档：米色 / 白 / 黑 */
    private static final int[][] BG_MODES = {
            {0xFFF8F0E4, 0xFF333333},
            {0xFFFFFFFF, 0xFF222222},
            {0xFF1E1E1E, 0xFFCCCCCC},
    };

    public static void openBook(Context ctx, String siteKey, String siteApi, String siteExt,
                                String bookId, String title) {
        Intent intent = new Intent(ctx, NovelReaderActivity.class);
        intent.putExtra(ReadingRouter.EXTRA_SITE_KEY, siteKey);
        intent.putExtra(ReadingRouter.EXTRA_SITE_API, siteApi);
        intent.putExtra(ReadingRouter.EXTRA_SITE_EXT, siteExt == null ? "" : siteExt);
        intent.putExtra(ReadingRouter.EXTRA_BOOK_ID, bookId);
        intent.putExtra(ReadingRouter.EXTRA_TITLE, title);
        ctx.startActivity(intent);
    }

    private TextView tvTitle;
    private TextView tvContent;
    private TextView tvChapterTitle;
    private ScrollView scrollContent;
    private ListView lvChapters;

    private String siteKey = "";
    private String siteApi = "";
    private String siteExt = "";
    private String bookId = "";
    private String bookTitle = "";
    private String playFrom = "";

    private final List<NovelLoader.Chapter> chapters = new ArrayList<>();
    private int chapterIndex = 0;
    private int fontSize = 18;
    private int bgMode = 0;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable saveBookmarkRunnable = this::saveBookmark;

    @Override
    protected int getLayoutId() {
        return R.layout.activity_novel_reader;
    }

    @Override
    protected void init() {
        tvTitle = find(R.id.tv_novel_title);
        tvContent = find(R.id.tv_novel_content);
        tvChapterTitle = find(R.id.tv_novel_chapter_title);
        scrollContent = find(R.id.scroll_novel_content);
        lvChapters = find(R.id.lv_novel_chapters);

        fontSize = Hawk.get(KEY_FONT_SIZE, 18);
        bgMode = Hawk.get(KEY_BG_MODE, 0);
        applyStyle();

        bookTitle = getIntent().getStringExtra(ReadingRouter.EXTRA_TITLE);
        siteKey = getIntent().getStringExtra(ReadingRouter.EXTRA_SITE_KEY);
        siteApi = getIntent().getStringExtra(ReadingRouter.EXTRA_SITE_API);
        siteExt = getIntent().getStringExtra(ReadingRouter.EXTRA_SITE_EXT);
        bookId = getIntent().getStringExtra(ReadingRouter.EXTRA_BOOK_ID);
        if (bookTitle == null) bookTitle = "";
        if (siteKey == null) siteKey = "";
        if (siteApi == null) siteApi = "";
        if (siteExt == null) siteExt = "";
        if (bookId == null) bookId = "";
        tvTitle.setText(TextUtils.isEmpty(bookTitle) ? "阅读" : bookTitle);

        Button btnChapters = find(R.id.btn_novel_chapters);
        Button btnFontDec = find(R.id.btn_novel_font_dec);
        Button btnFontInc = find(R.id.btn_novel_font_inc);
        Button btnBg = find(R.id.btn_novel_bg);
        Button btnShelf = find(R.id.btn_novel_shelf);
        Button btnPrev = find(R.id.btn_novel_prev);
        Button btnNext = find(R.id.btn_novel_next);

        btnChapters.setOnClickListener(v -> toggleChapterList());
        btnFontDec.setOnClickListener(v -> changeFontSize(-2));
        btnFontInc.setOnClickListener(v -> changeFontSize(2));
        btnBg.setOnClickListener(v -> changeBgMode());
        btnShelf.setOnClickListener(v -> addToShelf());
        btnPrev.setOnClickListener(v -> openChapter(chapterIndex - 1, 0));
        btnNext.setOnClickListener(v -> openChapter(chapterIndex + 1, 0));

        scrollContent.setOnScrollChangeListener((v, scrollX, scrollY, oldX, oldY) -> {
            handler.removeCallbacks(saveBookmarkRunnable);
            handler.postDelayed(saveBookmarkRunnable, 800);
        });

        lvChapters.setOnItemClickListener((parent, view, position, id) -> {
            lvChapters.setVisibility(View.GONE);
            openChapter(position, 0);
        });

        if (!TextUtils.isEmpty(bookId) && !TextUtils.isEmpty(siteApi)) {
            loadBookDetail();
        } else {
            // 内联单篇模式
            String content = getIntent().getStringExtra(ReadingRouter.EXTRA_CONTENT);
            tvContent.setText(content == null ? "" : content);
            tvChapterTitle.setText(bookTitle);
            btnPrev.setVisibility(View.GONE);
            btnNext.setVisibility(View.GONE);
            btnChapters.setVisibility(View.GONE);
        }
    }

    private void loadBookDetail() {
        tvContent.setText("加载中…");
        Task.execute(() -> {
            try {
                Spider spider = NovelLoader.getSpider(siteKey, siteApi, siteExt);
                NovelLoader.BookDetail detail = NovelLoader.bookDetail(spider, bookId);
                runOnUiThread(() -> onBookDetail(detail));
            } catch (Exception e) {
                runOnUiThread(() -> tvContent.setText("加载失败：" + e.getMessage()));
            }
        });
    }

    private void onBookDetail(NovelLoader.BookDetail detail) {
        if (detail == null || TextUtils.isEmpty(detail.book.title)
                && detail.chapters.isEmpty() && TextUtils.isEmpty(detail.book.playUrl)) {
            tvContent.setText("没有取到内容");
            return;
        }
        playFrom = detail.book.playFrom;
        if (TextUtils.isEmpty(bookTitle)) {
            bookTitle = detail.book.title;
            tvTitle.setText(bookTitle);
        }
        chapters.clear();
        chapters.addAll(detail.chapters);
        if (chapters.isEmpty()) {
            // 单篇小说：正文在 vod_play_url 的 novel:// 里
            String playUrl = detail.book.playUrl;
            if (ReadingRouter.isNovelUrl(playUrl)) {
                org.json.JSONObject payload = ReadingRouter.parseNovelPayload(playUrl);
                if (payload != null) {
                    tvContent.setText(payload.optString("content"));
                    tvChapterTitle.setText(payload.optString("title"));
                }
            } else {
                tvContent.setText(detail.book.desc == null ? "" : detail.book.desc);
            }
            find(R.id.btn_novel_prev).setVisibility(View.GONE);
            find(R.id.btn_novel_next).setVisibility(View.GONE);
            find(R.id.btn_novel_chapters).setVisibility(View.GONE);
            return;
        }
        refreshChapterList();
        BookmarkStore.Bookmark bookmark = BookmarkStore.load(siteKey, bookId);
        int index = 0;
        int scrollY = 0;
        if (bookmark != null) {
            index = Math.max(0, Math.min(bookmark.chapterIndex, chapters.size() - 1));
            scrollY = bookmark.position;
        }
        openChapter(index, scrollY);
    }

    private void refreshChapterList() {
        List<String> titles = new ArrayList<>();
        for (NovelLoader.Chapter chapter : chapters) titles.add(chapter.title);
        lvChapters.setAdapter(new ArrayAdapter<>(this,
                R.layout.item_reader_chapter, R.id.tv_chapter_title, titles));
    }

    private void openChapter(int index, int scrollY) {
        if (chapters.isEmpty()) return;
        if (index < 0 || index >= chapters.size()) {
            Toast.makeText(this, index < 0 ? "已经是第一章" : "已经是最后一章",
                    Toast.LENGTH_SHORT).show();
            return;
        }
        chapterIndex = index;
        NovelLoader.Chapter chapter = chapters.get(index);
        tvChapterTitle.setText(chapter.title);
        tvContent.setText("加载中…");
        scrollContent.scrollTo(0, 0);
        Task.execute(() -> {
            try {
                Spider spider = NovelLoader.getSpider(siteKey, siteApi, siteExt);
                NovelLoader.ChapterContent content =
                        NovelLoader.chapterContent(spider, playFrom, chapter.id);
                runOnUiThread(() -> {
                    tvContent.setText(TextUtils.isEmpty(content.content)
                            ? "本章没有取到正文" : content.content);
                    if (scrollY > 0) scrollContent.post(() -> scrollContent.scrollTo(0, scrollY));
                    saveBookmark();
                });
            } catch (Exception e) {
                runOnUiThread(() -> tvContent.setText("加载失败：" + e.getMessage()));
            }
        });
    }

    private void toggleChapterList() {
        if (chapters.isEmpty()) return;
        lvChapters.setVisibility(lvChapters.getVisibility() == View.VISIBLE
                ? View.GONE : View.VISIBLE);
    }

    private void changeFontSize(int delta) {
        fontSize = Math.max(14, Math.min(26, fontSize + delta));
        Hawk.put(KEY_FONT_SIZE, fontSize);
        applyStyle();
    }

    private void changeBgMode() {
        bgMode = (bgMode + 1) % BG_MODES.length;
        Hawk.put(KEY_BG_MODE, bgMode);
        applyStyle();
    }

    private void applyStyle() {
        tvContent.setTextSize(fontSize);
        int[] mode = BG_MODES[Math.max(0, Math.min(bgMode, BG_MODES.length - 1))];
        scrollContent.setBackgroundColor(mode[0]);
        tvContent.setTextColor(mode[1]);
    }

    private void addToShelf() {
        if (TextUtils.isEmpty(bookId)) {
            Toast.makeText(this, "单篇内容无法加入书架", Toast.LENGTH_SHORT).show();
            return;
        }
        BookshelfStore.Book book = new BookshelfStore.Book(
                siteKey, siteApi, ReadingSources.KIND_NOVEL, bookId, bookTitle);
        boolean added = BookshelfStore.add(book);
        Toast.makeText(this, added ? "已加入书架" : "已在书架里",
                Toast.LENGTH_SHORT).show();
    }

    private void saveBookmark() {
        if (TextUtils.isEmpty(bookId) || chapters.isEmpty()) return;
        NovelLoader.Chapter chapter = chapters.get(chapterIndex);
        BookmarkStore.save(siteKey, bookId, chapterIndex, chapter.id,
                chapter.title, scrollContent.getScrollY());
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(saveBookmarkRunnable);
        saveBookmark();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacks(saveBookmarkRunnable);
        super.onDestroy();
    }
}
