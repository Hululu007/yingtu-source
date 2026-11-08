package com.yingtu.plus.reader;

import android.content.Context;
import android.content.Intent;
import android.text.TextUtils;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewpager2.widget.ViewPager2;

import com.bumptech.glide.Glide;
import com.github.catvod.crawler.Spider;
import com.yingtu.plus.R;
import com.yingtu.plus.base.BaseActivity;
import com.yingtu.plus.utils.Task;

import java.util.ArrayList;
import java.util.List;

/**
 * 图片 / 漫画浏览页。两种模式：
 * 图片列表模式：ReadingRouter.openPics 传来的 pics:// 多图地址，左右滑动翻图；
 * 漫画书籍模式：按 siteKey + bookId 走漫画源取详情、章节目录，
 * 章节图片走 playerContent 取 pics:// 图链，支持上一话 / 下一话切换。
 * 单图支持双指缩放（ZoomImageView）。
 */
public class PicsViewerActivity extends BaseActivity {

    public static void openBook(Context ctx, String siteKey, String siteApi, String siteExt,
                                String bookId, String title) {
        Intent intent = new Intent(ctx, PicsViewerActivity.class);
        intent.putExtra(ReadingRouter.EXTRA_SITE_KEY, siteKey);
        intent.putExtra(ReadingRouter.EXTRA_SITE_API, siteApi);
        intent.putExtra(ReadingRouter.EXTRA_SITE_EXT, siteExt == null ? "" : siteExt);
        intent.putExtra(ReadingRouter.EXTRA_BOOK_ID, bookId);
        intent.putExtra(ReadingRouter.EXTRA_TITLE, title);
        ctx.startActivity(intent);
    }

    private TextView tvTitle;
    private TextView tvPage;
    private TextView tvChapterTitle;
    private ViewPager2 pager;
    private PicsAdapter adapter;

    private String siteKey = "";
    private String siteApi = "";
    private String siteExt = "";
    private String bookId = "";
    private String bookTitle = "";
    private String playFrom = "";

    private final List<String> images = new ArrayList<>();
    private final List<NovelLoader.Chapter> chapters = new ArrayList<>();
    private int chapterIndex = 0;

    @Override
    protected int getLayoutId() {
        return R.layout.activity_pics_viewer;
    }

    @Override
    protected void init() {
        tvTitle = find(R.id.tv_pics_title);
        tvPage = find(R.id.tv_pics_page);
        tvChapterTitle = find(R.id.tv_pics_chapter_title);
        pager = find(R.id.pager_pics);

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
        tvTitle.setText(TextUtils.isEmpty(bookTitle) ? "图片" : bookTitle);

        adapter = new PicsAdapter();
        pager.setAdapter(adapter);
        pager.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
            @Override
            public void onPageSelected(int position) {
                updatePageText();
                saveBookmark();
            }
        });

        Button btnPrev = find(R.id.btn_pics_prev_chapter);
        Button btnNext = find(R.id.btn_pics_next_chapter);
        btnPrev.setOnClickListener(v -> openChapter(chapterIndex - 1));
        btnNext.setOnClickListener(v -> openChapter(chapterIndex + 1));

        ArrayList<String> directImages =
                getIntent().getStringArrayListExtra(ReadingRouter.EXTRA_IMAGES);
        if (directImages != null && !directImages.isEmpty()) {
            // 图片列表模式：没有章节，隐藏上下话按钮
            images.addAll(directImages);
            adapter.notifyDataSetChanged();
            updatePageText();
            btnPrev.setVisibility(View.GONE);
            btnNext.setVisibility(View.GONE);
            tvChapterTitle.setText(bookTitle);
        } else if (!TextUtils.isEmpty(bookId) && !TextUtils.isEmpty(siteApi)) {
            loadBookDetail();
        } else {
            tvPage.setText("没有图片");
        }
    }

    private void loadBookDetail() {
        tvChapterTitle.setText("加载中…");
        Task.execute(() -> {
            try {
                Spider spider = NovelLoader.getSpider(siteKey, siteApi, siteExt);
                NovelLoader.BookDetail detail = NovelLoader.bookDetail(spider, bookId);
                runOnUiThread(() -> onBookDetail(detail));
            } catch (Exception e) {
                runOnUiThread(() -> tvChapterTitle.setText("加载失败：" + e.getMessage()));
            }
        });
    }

    private void onBookDetail(NovelLoader.BookDetail detail) {
        if (detail == null) {
            tvChapterTitle.setText("没有取到内容");
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
            // 单话漫画：图片直接在 vod_play_url 的 pics:// 里
            List<String> direct = ReadingRouter.parsePicsUrls(detail.book.playUrl);
            if (!direct.isEmpty()) {
                images.addAll(direct);
                adapter.notifyDataSetChanged();
                updatePageText();
            } else {
                tvChapterTitle.setText("没有取到图片");
            }
            find(R.id.btn_pics_prev_chapter).setVisibility(View.GONE);
            find(R.id.btn_pics_next_chapter).setVisibility(View.GONE);
            return;
        }
        BookmarkStore.Bookmark bookmark = BookmarkStore.load(siteKey, bookId);
        int index = 0;
        int page = 0;
        if (bookmark != null) {
            index = Math.max(0, Math.min(bookmark.chapterIndex, chapters.size() - 1));
            page = bookmark.position;
        }
        openChapterAt(index, page);
    }

    private void openChapter(int index) {
        openChapterAt(index, 0);
    }

    private void openChapterAt(int index, int page) {
        if (chapters.isEmpty()) return;
        if (index < 0 || index >= chapters.size()) {
            Toast.makeText(this, index < 0 ? "已经是第一话" : "已经是最后一话",
                    Toast.LENGTH_SHORT).show();
            return;
        }
        chapterIndex = index;
        NovelLoader.Chapter chapter = chapters.get(index);
        tvChapterTitle.setText(chapter.title);
        tvPage.setText("加载中…");
        Task.execute(() -> {
            try {
                Spider spider = NovelLoader.getSpider(siteKey, siteApi, siteExt);
                List<String> urls = NovelLoader.chapterImages(spider, playFrom, chapter.id);
                runOnUiThread(() -> {
                    images.clear();
                    images.addAll(urls);
                    adapter.notifyDataSetChanged();
                    if (images.isEmpty()) {
                        tvPage.setText("本话没有取到图片");
                    } else {
                        pager.setCurrentItem(Math.max(0, Math.min(page, images.size() - 1)), false);
                        updatePageText();
                    }
                    saveBookmark();
                });
            } catch (Exception e) {
                runOnUiThread(() -> tvPage.setText("加载失败：" + e.getMessage()));
            }
        });
    }

    private void updatePageText() {
        if (images.isEmpty()) {
            tvPage.setText("");
            return;
        }
        tvPage.setText((pager.getCurrentItem() + 1) + "/" + images.size());
    }

    private void saveBookmark() {
        if (TextUtils.isEmpty(bookId) || chapters.isEmpty()) return;
        NovelLoader.Chapter chapter = chapters.get(chapterIndex);
        BookmarkStore.save(siteKey, bookId, chapterIndex, chapter.id,
                chapter.title, pager.getCurrentItem());
    }

    @Override
    protected void onPause() {
        super.onPause();
        saveBookmark();
    }

    private class PicsAdapter extends RecyclerView.Adapter<PicsAdapter.Holder> {

        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            ZoomImageView view = new ZoomImageView(parent.getContext());
            view.setLayoutParams(new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT));
            view.setBackgroundColor(0xFF000000);
            return new Holder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull Holder holder, int position) {
            Glide.with(holder.image.getContext())
                    .load(images.get(position))
                    .into(holder.image);
        }

        @Override
        public int getItemCount() {
            return images.size();
        }

        class Holder extends RecyclerView.ViewHolder {
            final ZoomImageView image;

            Holder(@NonNull View itemView) {
                super(itemView);
                image = (ZoomImageView) itemView;
            }
        }
    }
}
