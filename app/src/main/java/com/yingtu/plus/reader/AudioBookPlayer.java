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
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackParameters;
import androidx.media3.common.Player;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.exoplayer.source.ProgressiveMediaSource;

import com.github.catvod.crawler.Spider;
import com.yingtu.plus.R;
import com.yingtu.plus.base.BaseActivity;
import com.yingtu.plus.utils.Task;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 听书播放页。两种模式：
 * 单集模式：ReadingRouter.openAudio 传来的音频直链，直接播；
 * 专辑模式：按 siteKey + bookId 走听书源取详情、章节目录，
 * 章节音频走 playerContent("专辑id|章节序号") 取直链 + 请求头后播。
 * 走树里现成的 ExoPlayer 音频能力（androidx.media3），不另写解码。
 * 播放 / 暂停、进度、倍速、上一章 / 下一章；播完一章自动播下一章。
 * 书签：只记最后章节 + 播放毫秒数，切章、退出都保存，下次打开续播。
 */
public class AudioBookPlayer extends BaseActivity {

    public static void openAlbum(Context ctx, String siteKey, String siteApi, String siteExt,
                                 String bookId, String title) {
        Intent intent = new Intent(ctx, AudioBookPlayer.class);
        intent.putExtra(ReadingRouter.EXTRA_SITE_KEY, siteKey);
        intent.putExtra(ReadingRouter.EXTRA_SITE_API, siteApi);
        intent.putExtra(ReadingRouter.EXTRA_SITE_EXT, siteExt == null ? "" : siteExt);
        intent.putExtra(ReadingRouter.EXTRA_BOOK_ID, bookId);
        intent.putExtra(ReadingRouter.EXTRA_TITLE, title);
        ctx.startActivity(intent);
    }

    /** 倍速档位 */
    private static final float[] SPEEDS = {1.0f, 1.25f, 1.5f, 2.0f};

    private ExoPlayer player;
    private TextView tvTitle;
    private TextView tvChapter;
    private TextView tvCurrent;
    private TextView tvTotal;
    private SeekBar seekBar;
    private Button btnPlay;
    private Button btnSpeed;
    private ListView lvChapters;

    private String siteKey = "";
    private String siteApi = "";
    private String siteExt = "";
    private String bookId = "";
    private String bookTitle = "";
    private String playFrom = "";

    private final List<NovelLoader.Chapter> chapters = new ArrayList<>();
    private int chapterIndex = 0;
    private int speedIndex = 0;
    private boolean seeking = false;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable progressRunnable = new Runnable() {
        @Override
        public void run() {
            updateProgress();
            handler.postDelayed(this, 500);
        }
    };

    @Override
    protected int getLayoutId() {
        return R.layout.activity_audio_book;
    }

    @Override
    protected void init() {
        tvTitle = find(R.id.tv_audio_title);
        tvChapter = find(R.id.tv_audio_chapter);
        tvCurrent = find(R.id.tv_audio_current);
        tvTotal = find(R.id.tv_audio_total);
        seekBar = find(R.id.seek_audio);
        btnPlay = find(R.id.btn_audio_play);
        btnSpeed = find(R.id.btn_audio_speed);
        lvChapters = find(R.id.lv_audio_chapters);

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
        tvTitle.setText(TextUtils.isEmpty(bookTitle) ? "听书" : bookTitle);

        player = new ExoPlayer.Builder(this).build();
        player.addListener(new Player.Listener() {
            @Override
            public void onIsPlayingChanged(boolean isPlaying) {
                btnPlay.setText(isPlaying ? "暂停" : "播放");
            }

            @Override
            public void onPlaybackStateChanged(int playbackState) {
                if (playbackState == Player.STATE_ENDED) {
                    openChapter(chapterIndex + 1, 0);
                }
            }
        });

        btnPlay.setOnClickListener(v -> togglePlay());
        find(R.id.btn_audio_prev).setOnClickListener(v -> openChapter(chapterIndex - 1, 0));
        find(R.id.btn_audio_next).setOnClickListener(v -> openChapter(chapterIndex + 1, 0));
        btnSpeed.setOnClickListener(v -> changeSpeed());
        find(R.id.btn_audio_chapters).setOnClickListener(v -> toggleChapterList());
        find(R.id.btn_audio_shelf).setOnClickListener(v -> addToShelf());

        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser && player != null) {
                    tvCurrent.setText(formatTime(progress));
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
                seeking = true;
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                seeking = false;
                if (player != null) player.seekTo(seekBar.getProgress());
            }
        });

        lvChapters.setOnItemClickListener((parent, view, position, id) -> {
            lvChapters.setVisibility(View.GONE);
            openChapter(position, 0);
        });

        String directUrl = getIntent().getStringExtra(ReadingRouter.EXTRA_AUDIO_URL);
        if (!TextUtils.isEmpty(directUrl)) {
            // 单集模式：没有章节，隐藏选集与上下章
            tvChapter.setText(bookTitle);
            find(R.id.btn_audio_prev).setVisibility(View.GONE);
            find(R.id.btn_audio_next).setVisibility(View.GONE);
            find(R.id.btn_audio_chapters).setVisibility(View.GONE);
            playUrl(directUrl, null, 0);
        } else if (!TextUtils.isEmpty(bookId) && !TextUtils.isEmpty(siteApi)) {
            loadAlbumDetail();
        } else {
            tvChapter.setText("没有音频");
        }
    }

    private void loadAlbumDetail() {
        tvChapter.setText("加载中…");
        Task.execute(() -> {
            try {
                Spider spider = NovelLoader.getSpider(siteKey, siteApi, siteExt);
                NovelLoader.BookDetail detail = NovelLoader.bookDetail(spider, bookId);
                runOnUiThread(() -> onAlbumDetail(detail));
            } catch (Exception e) {
                runOnUiThread(() -> tvChapter.setText("加载失败：" + e.getMessage()));
            }
        });
    }

    private void onAlbumDetail(NovelLoader.BookDetail detail) {
        if (detail == null || detail.chapters.isEmpty()) {
            tvChapter.setText("没有取到章节");
            return;
        }
        playFrom = detail.book.playFrom;
        if (TextUtils.isEmpty(bookTitle)) {
            bookTitle = detail.book.title;
            tvTitle.setText(bookTitle);
        }
        chapters.clear();
        chapters.addAll(detail.chapters);
        refreshChapterList();
        BookmarkStore.Bookmark bookmark = BookmarkStore.load(siteKey, bookId);
        int index = 0;
        int position = 0;
        if (bookmark != null) {
            index = Math.max(0, Math.min(bookmark.chapterIndex, chapters.size() - 1));
            position = bookmark.position;
        }
        openChapter(index, position);
    }

    private void refreshChapterList() {
        List<String> titles = new ArrayList<>();
        for (NovelLoader.Chapter chapter : chapters) titles.add(chapter.title);
        lvChapters.setAdapter(new ArrayAdapter<>(this,
                R.layout.item_reader_chapter, R.id.tv_chapter_title, titles));
    }

    private void openChapter(int index, int positionMs) {
        if (chapters.isEmpty()) return;
        if (index < 0 || index >= chapters.size()) {
            if (index >= chapters.size()) {
                Toast.makeText(this, "已经是最后一章", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, "已经是第一章", Toast.LENGTH_SHORT).show();
            }
            return;
        }
        chapterIndex = index;
        NovelLoader.Chapter chapter = chapters.get(index);
        tvChapter.setText(chapter.title);
        Task.execute(() -> {
            try {
                Spider spider = NovelLoader.getSpider(siteKey, siteApi, siteExt);
                NovelLoader.AudioResult audio =
                        NovelLoader.chapterAudio(spider, playFrom, chapter.id);
                runOnUiThread(() -> {
                    if (TextUtils.isEmpty(audio.url)) {
                        tvChapter.setText(chapter.title + "（没有取到音频）");
                        return;
                    }
                    playUrl(audio.url, audio.header, positionMs);
                    saveBookmark();
                });
            } catch (Exception e) {
                runOnUiThread(() -> tvChapter.setText("加载失败：" + e.getMessage()));
            }
        });
    }

    /** 直链 + 阅读源给的请求头（Referer / UA）一起交给 ExoPlayer */
    private void playUrl(String url, java.util.HashMap<String, String> header, int positionMs) {
        if (player == null) return;
        DefaultHttpDataSource.Factory factory = new DefaultHttpDataSource.Factory();
        if (header != null && !header.isEmpty()) {
            factory.setDefaultRequestProperties(header);
        }
        MediaSource source = new ProgressiveMediaSource.Factory(factory)
                .createMediaSource(MediaItem.fromUri(url));
        player.setMediaSource(source);
        player.prepare();
        if (positionMs > 0) player.seekTo(positionMs);
        player.setPlaybackParameters(new PlaybackParameters(SPEEDS[speedIndex]));
        player.play();
    }

    private void togglePlay() {
        if (player == null) return;
        if (player.isPlaying()) player.pause();
        else player.play();
    }

    private void changeSpeed() {
        speedIndex = (speedIndex + 1) % SPEEDS.length;
        btnSpeed.setText(String.format(Locale.US, "倍速 %.2fx", SPEEDS[speedIndex]));
        if (player != null) {
            player.setPlaybackParameters(new PlaybackParameters(SPEEDS[speedIndex]));
        }
    }

    private void toggleChapterList() {
        if (chapters.isEmpty()) return;
        lvChapters.setVisibility(lvChapters.getVisibility() == View.VISIBLE
                ? View.GONE : View.VISIBLE);
    }

    private void addToShelf() {
        if (TextUtils.isEmpty(bookId)) {
            Toast.makeText(this, "单集音频无法加入书架", Toast.LENGTH_SHORT).show();
            return;
        }
        BookshelfStore.Book book = new BookshelfStore.Book(
                siteKey, siteApi, ReadingSources.KIND_AUDIO, bookId, bookTitle);
        boolean added = BookshelfStore.add(book);
        Toast.makeText(this, added ? "已加入书架" : "已在书架里",
                Toast.LENGTH_SHORT).show();
    }

    private void updateProgress() {
        if (player == null || seeking) return;
        long duration = player.getDuration();
        long position = player.getCurrentPosition();
        if (duration > 0) {
            seekBar.setMax((int) duration);
            seekBar.setProgress((int) position);
            tvTotal.setText(formatTime(duration));
        }
        tvCurrent.setText(formatTime(position));
    }

    private static String formatTime(long ms) {
        long totalSeconds = Math.max(0, ms / 1000);
        long minutes = totalSeconds / 60;
        long seconds = totalSeconds % 60;
        return String.format(Locale.US, "%02d:%02d", minutes, seconds);
    }

    private void saveBookmark() {
        if (TextUtils.isEmpty(bookId) || chapters.isEmpty() || player == null) return;
        NovelLoader.Chapter chapter = chapters.get(chapterIndex);
        BookmarkStore.save(siteKey, bookId, chapterIndex, chapter.id,
                chapter.title, (int) player.getCurrentPosition());
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(progressRunnable);
        saveBookmark();
        if (player != null) player.pause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        handler.post(progressRunnable);
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacks(progressRunnable);
        if (player != null) {
            player.release();
            player = null;
        }
        super.onDestroy();
    }
}
