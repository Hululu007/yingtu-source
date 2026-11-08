package com.yingtu.plus.ui.activity;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.ui.PlayerView;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewpager2.widget.ViewPager2;

import com.yingtu.plus.R;
import com.yingtu.plus.drama.DramaModels.DramaDetail;
import com.yingtu.plus.drama.DramaModels.DramaEpisode;
import com.yingtu.plus.drama.DramaModels.DramaStream;
import com.yingtu.plus.drama.DramaModels.DramaVideo;
import com.yingtu.plus.drama.DramaModels.FeedPage;
import com.yingtu.plus.drama.DramaSession;
import com.yingtu.plus.drama.FanqieDramaClient;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Full-screen vertical short-drama player (Java rewrite of the
 * Xiuxian drama_page.dart feed player).
 *
 * - Vertical ViewPager2: swipe up/down switches series; only the on-screen
 *   page holds a player (created on bind, released on recycle).
 * - Each series loads its episode list via video_detail, then resolves the
 *   current episode via video_model at play time (URLs expire, never
 *   cached). The next episode's URL is resolved while the current one plays
 *   (resolve only, no byte pre-download).
 * - Episode ends -> next episode of the same series; last episode ends ->
 *   next series. The episode button opens the episode panel; the next button jumps ahead.
 * - Playback is plain ExoPlayer (media3, the same stack VideoActivity
 *   uses) because the resolved URL is a direct signed MP4.
 *
 * Not done here: danmaku, comments, history/resume position (no history
 * store is wired in this batch), and a dedicated fullscreen button (the
 * activity follows system rotation; PlayerView's own controller handles
 * play/pause/seek).
 */
public class DramaPlayerActivity extends AppCompatActivity {

    public static void start(Activity activity) {
        activity.startActivity(new Intent(activity, DramaPlayerActivity.class));
    }

    private ViewPager2 viewPager;
    private TextView tvHint;
    private PageAdapter adapter;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private volatile boolean destroyed;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_drama_player);
        viewPager = findViewById(R.id.vpDrama);
        tvHint = findViewById(R.id.tvDramaPlayerHint);
        viewPager.setOrientation(ViewPager2.ORIENTATION_VERTICAL);
        viewPager.setOffscreenPageLimit(1);
        adapter = new PageAdapter();
        viewPager.setAdapter(adapter);
        viewPager.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
            @Override
            public void onPageSelected(int position) {
                DramaSession.setPosition(position);
                adapter.pauseExcept(position);
                adapter.maybeAppendFeed(position);
            }
        });
        List<DramaVideo> feed = DramaSession.getFeed();
        if (feed.isEmpty()) {
            tvHint.setVisibility(View.VISIBLE);
            tvHint.setText("正在加载短剧…");
            io.execute(() -> {
                try {
                    FanqieDramaClient client = new FanqieDramaClient(this);
                    FeedPage page = client.feedFirstPage();
                    if (destroyed) return;
                    DramaSession.setCursor(page.cellId, page.sessionId, page.nextOffset, page.hasMore);
                    runOnUiThread(() -> {
                        tvHint.setVisibility(View.GONE);
                        adapter.setData(page.items);
                        viewPager.setCurrentItem(0, false);
                    });
                } catch (Exception e) {
                    if (destroyed) return;
                    runOnUiThread(() -> {
                        tvHint.setVisibility(View.VISIBLE);
                        tvHint.setText("短剧加载失败：" + e.getMessage());
                    });
                }
            });
        } else {
            adapter.setData(feed);
            viewPager.setCurrentItem(DramaSession.getPosition(), false);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        adapter.pauseAll();
    }

    @Override
    protected void onResume() {
        super.onResume();
        adapter.resumeCurrent(viewPager.getCurrentItem());
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        io.shutdownNow();
        adapter.releaseAll();
        super.onDestroy();
    }

    /** Per-series playback state, kept while its page is alive. */
    private static final class PageState {
        DramaDetail detail;
        int episodeIndex;
        DramaStream currentStream;
        boolean detailLoading;
        boolean streamLoading;
    }

    private final class PageAdapter extends RecyclerView.Adapter<PageAdapter.PageVH> {

        private final List<DramaVideo> data = new ArrayList<>();
        private final Map<Integer, PageState> states = new HashMap<>();
        private final Map<Integer, PageVH> holders = new HashMap<>();
        private final Set<String> seenVids = new HashSet<>();
        private boolean appendingFeed;

        void setData(List<DramaVideo> list) {
            data.clear();
            seenVids.clear();
            if (list != null) {
                for (DramaVideo v : list) {
                    if (v.vid != null && seenVids.add(v.vid)) data.add(v);
                }
            }
            notifyDataSetChanged();
        }

        void maybeAppendFeed(int position) {
            if (appendingFeed || !DramaSession.hasMore()) return;
            if (position < data.size() - 3) return;
            appendingFeed = true;
            final String c = DramaSession.getCellId();
            final String s = DramaSession.getSessionId();
            final String o = DramaSession.getNextOffset();
            io.execute(() -> {
                try {
                    FanqieDramaClient client = new FanqieDramaClient(DramaPlayerActivity.this);
                    FeedPage page = client.feedNextPage(c, s, o);
                    if (destroyed) return;
                    DramaSession.setCursor(page.cellId, page.sessionId, page.nextOffset, page.hasMore);
                    runOnUiThread(() -> {
                        appendingFeed = false;
                        int start = data.size();
                        int added = 0;
                        for (DramaVideo v : page.items) {
                            if (v.vid != null && seenVids.add(v.vid)) {
                                data.add(v);
                                added++;
                            }
                        }
                        if (added > 0) notifyItemRangeInserted(start, added);
                    });
                } catch (Exception ignored) {
                    if (!destroyed) runOnUiThread(() -> appendingFeed = false);
                }
            });
        }

        void pauseExcept(int position) {
            for (Map.Entry<Integer, PageVH> e : holders.entrySet()) {
                if (e.getKey() != position && e.getValue().player != null) {
                    e.getValue().player.setPlayWhenReady(false);
                }
            }
            PageVH current = holders.get(position);
            if (current != null && current.player != null) {
                current.player.setPlayWhenReady(true);
            }
        }

        void pauseAll() {
            for (PageVH h : holders.values()) {
                if (h.player != null) h.player.setPlayWhenReady(false);
            }
        }

        void resumeCurrent(int position) {
            PageVH h = holders.get(position);
            if (h != null && h.player != null) h.player.setPlayWhenReady(true);
        }

        void releaseAll() {
            for (PageVH h : holders.values()) h.releasePlayer();
            holders.clear();
        }

        @NonNull
        @Override
        public PageVH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_drama_page, parent, false);
            return new PageVH(v);
        }

        @Override
        public void onBindViewHolder(@NonNull PageVH h, int position) {
            DramaVideo video = data.get(position);
            holders.values().remove(h);
            holders.put(position, h);
            h.bind(video, position);
        }

        @Override
        public void onViewRecycled(@NonNull PageVH h) {
            holders.remove(h.pagePosition);
            h.releasePlayer();
            super.onViewRecycled(h);
        }

        @Override
        public int getItemCount() {
            return data.size();
        }

        private PageState stateFor(int position) {
            PageState st = states.get(position);
            if (st == null) {
                st = new PageState();
                states.put(position, st);
            }
            return st;
        }

        /** Resolve and play episodeIndex of the series at position. */
        private void playEpisode(int position, int episodeIndex) {
            PageVH h = holders.get(position);
            PageState st = stateFor(position);
            if (h == null || st.detail == null) return;
            if (episodeIndex < 0 || episodeIndex >= st.detail.episodes.size()) return;
            if (st.streamLoading) return;
            st.episodeIndex = episodeIndex;
            st.streamLoading = true;
            DramaEpisode ep = st.detail.episodes.get(episodeIndex);
            final DramaVideo video = data.get(position);
            h.tvEpisode.setText("第 " + ep.index + " 集 / 共 " + st.detail.episodes.size() + " 集");
            io.execute(() -> {
                try {
                    FanqieDramaClient client = new FanqieDramaClient(DramaPlayerActivity.this);
                    DramaStream stream = client.videoModel(ep.vid, ep.seriesId);
                    if (destroyed) return;
                    runOnUiThread(() -> {
                        st.streamLoading = false;
                        st.currentStream = stream;
                        PageVH holder = holders.get(position);
                        if (holder == null || holder.pagePosition != position) return;
                        holder.playStream(stream);
                        // Resolve the next episode's URL while this one plays.
                        preloadNextEpisode(position);
                    });
                } catch (Exception e) {
                    if (destroyed) return;
                    runOnUiThread(() -> {
                        st.streamLoading = false;
                        PageVH holder = holders.get(position);
                        if (holder == null) return;
                        Toast.makeText(DramaPlayerActivity.this,
                                "取流失败：" + e.getMessage(), Toast.LENGTH_SHORT).show();
                    });
                }
            });
        }

        /** Next-episode URL resolve only (no byte download). */
        private void preloadNextEpisode(int position) {
            PageState st = stateFor(position);
            if (st.detail == null) return;
            int next = st.episodeIndex + 1;
            if (next >= st.detail.episodes.size()) return;
            DramaEpisode ep = st.detail.episodes.get(next);
            io.execute(() -> {
                try {
                    FanqieDramaClient client = new FanqieDramaClient(DramaPlayerActivity.this);
                    client.videoModel(ep.vid, ep.seriesId);
                } catch (Exception ignored) {
                    // Preload failure is silent: the episode resolves on demand.
                }
            });
        }

        /** Episode ended: next episode, else next series. */
        private void onEpisodeEnded(int position) {
            PageState st = stateFor(position);
            if (st.detail == null) return;
            if (st.episodeIndex + 1 < st.detail.episodes.size()) {
                playEpisode(position, st.episodeIndex + 1);
                return;
            }
            if (position + 1 < data.size()) {
                viewPager.setCurrentItem(position + 1, true);
            } else {
                Toast.makeText(DramaPlayerActivity.this, "已经是最后一部", Toast.LENGTH_SHORT).show();
            }
        }

        private void showEpisodePanel(int position) {
            PageState st = stateFor(position);
            if (st.detail == null || st.detail.episodes.isEmpty()) {
                Toast.makeText(DramaPlayerActivity.this, "选集加载中…", Toast.LENGTH_SHORT).show();
                return;
            }
            String[] titles = new String[st.detail.episodes.size()];
            for (int i = 0; i < titles.length; i++) {
                DramaEpisode ep = st.detail.episodes.get(i);
                titles[i] = "第 " + ep.index + " 集";
            }
            new AlertDialog.Builder(DramaPlayerActivity.this)
                    .setTitle(st.detail.title)
                    .setItems(titles, (dialog, which) -> playEpisode(position, which))
                    .show();
        }

        final class PageVH extends RecyclerView.ViewHolder {
            final PlayerView playerView;
            final TextView tvTitle;
            final TextView tvEpisode;
            final Button btnEpisodes;
            final Button btnNextEpisode;
            ExoPlayer player;
            int pagePosition = -1;

            PageVH(@NonNull View itemView) {
                super(itemView);
                playerView = itemView.findViewById(R.id.pvDrama);
                tvTitle = itemView.findViewById(R.id.tvDramaPageTitle);
                tvEpisode = itemView.findViewById(R.id.tvDramaPageEpisode);
                btnEpisodes = itemView.findViewById(R.id.btnDramaEpisodes);
                btnNextEpisode = itemView.findViewById(R.id.btnDramaNextEpisode);
            }

            void bind(DramaVideo video, int position) {
                pagePosition = position;
                tvTitle.setText(video.displayTitle());
                tvEpisode.setText("加载中…");
                btnEpisodes.setOnClickListener(v -> showEpisodePanel(position));
                btnNextEpisode.setOnClickListener(v -> onEpisodeEnded(position));
                PageState st = stateFor(position);
                if (st.detail != null) {
                    playEpisode(position, st.episodeIndex);
                    return;
                }
                if (st.detailLoading) return;
                st.detailLoading = true;
                io.execute(() -> {
                    try {
                        FanqieDramaClient client = new FanqieDramaClient(DramaPlayerActivity.this);
                        DramaDetail detail = client.videoDetail(video.seriesId);
                        if (destroyed) return;
                        runOnUiThread(() -> {
                            st.detailLoading = false;
                            if (detail.episodes.isEmpty()) {
                                tvEpisode.setText("该剧暂无选集");
                                return;
                            }
                            st.detail = detail;
                            if (detail.title != null && !detail.title.isEmpty()) {
                                tvTitle.setText(detail.title);
                            }
                            playEpisode(position, 0);
                        });
                    } catch (Exception e) {
                        if (destroyed) return;
                        runOnUiThread(() -> {
                            st.detailLoading = false;
                            tvEpisode.setText("选集加载失败");
                        });
                    }
                });
            }

            void playStream(DramaStream stream) {
                releasePlayer();
                player = new ExoPlayer.Builder(DramaPlayerActivity.this).build();
                playerView.setPlayer(player);
                player.setMediaItem(MediaItem.fromUri(stream.url));
                final int pos = pagePosition;
                player.addListener(new Player.Listener() {
                    @Override
                    public void onPlaybackStateChanged(int state) {
                        if (state == Player.STATE_ENDED) onEpisodeEnded(pos);
                    }
                });
                player.prepare();
                player.setPlayWhenReady(true);
            }

            void releasePlayer() {
                if (player != null) {
                    playerView.setPlayer(null);
                    player.release();
                    player = null;
                }
            }
        }
    }
}
