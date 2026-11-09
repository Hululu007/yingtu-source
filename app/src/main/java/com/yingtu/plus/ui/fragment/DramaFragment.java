package com.yingtu.plus.ui.fragment;

import android.text.TextUtils;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.yingtu.plus.R;
import com.yingtu.plus.api.ApiConfig;
import com.yingtu.plus.api.VodConfigSync;
import com.yingtu.plus.base.BaseLazyFragment;
import com.yingtu.plus.bean.SourceBean;
import com.yingtu.plus.drama.BuiltinDramaConfigs;
import com.yingtu.plus.drama.DramaModels.DramaVideo;
import com.yingtu.plus.drama.DramaModels.FeedPage;
import com.yingtu.plus.drama.DramaSession;
import com.yingtu.plus.drama.DramaSites;
import com.yingtu.plus.drama.FanqieDramaClient;
import com.yingtu.plus.ui.activity.DramaPlayerActivity;
import com.yingtu.plus.ui.adapter.DramaFeedAdapter;
import com.yingtu.plus.util.ZoneSites;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Short-drama zone (bottom-nav drama tab, mirrors the Xiuxian drama_page).
 *
 * Two content sources, same page:
 * 1. Fanqie short-drama stream (ORIGINAL_KEY, default): plan/v seed +
 *    cell/change feed, 3-column poster grid, endless scroll by nextOffset.
 *    Tap a card opens the full-screen vertical feed player.
 * 2. TVBox drama sites (py 18 + jar 3 from builtin_drama.json): tapping a
 *    site switches the global current source and embeds a HomeMainFragment
 *    below, reusing the whole existing content chain (same pattern as
 *    MidnightFragment).
 *
 * Channel tabs (all / recommend / watch / manju) come from drama_sites.dart's
 * original-site categories. Only the ranklist cell is verified against the
 * live upstream, so every tab currently loads that same feed; per-channel
 * cells need the server channel table (tab/v) mapped first, which is out of
 * this batch's verified chain and is reported as an open item, not faked
 * with different data.
 */
public class DramaFragment extends BaseLazyFragment {

    private static final String TAG_HOME = "drama_home";

    private TextView tvDramaTitle;
    private TextView tvDramaHint;
    private TextView tvChAll;
    private TextView tvChRecommend;
    private TextView tvChWatch;
    private TextView tvChManju;
    private FrameLayout contentContainer;
    private RecyclerView rvFeed;
    private DramaFeedAdapter feedAdapter;

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Set<String> seenVids = new HashSet<>();
    private String cellId = "";
    private String sessionId = "";
    private String nextOffset = "";
    private boolean hasMore = true;
    private boolean loadingFeed;
    private boolean feedStarted;

    @Override
    protected int getLayoutId() {
        return R.layout.fragment_drama;
    }

    @Override
    protected void init(View root) {
        tvDramaTitle = root.findViewById(R.id.tvDramaTitle);
        tvDramaHint = root.findViewById(R.id.tvDramaHint);
        tvChAll = root.findViewById(R.id.tvChAll);
        tvChRecommend = root.findViewById(R.id.tvChRecommend);
        tvChWatch = root.findViewById(R.id.tvChWatch);
        tvChManju = root.findViewById(R.id.tvChManju);
        contentContainer = root.findViewById(R.id.dramaContent);
        rvFeed = root.findViewById(R.id.rvDramaFeed);

        rvFeed.setLayoutManager(new GridLayoutManager(getContext(), 3));
        feedAdapter = new DramaFeedAdapter();
        feedAdapter.setOnItemClick((video, position) -> {
            DramaSession.setFeed(feedAdapter.getData(), position);
            DramaSession.setCursor(cellId, sessionId, nextOffset, hasMore);
            DramaPlayerActivity.start(requireActivity());
        });
        rvFeed.setAdapter(feedAdapter);
        rvFeed.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView rv, int dx, int dy) {
                if (dy <= 0 || loadingFeed || !hasMore) return;
                GridLayoutManager lm = (GridLayoutManager) rv.getLayoutManager();
                if (lm == null) return;
                int last = lm.findLastVisibleItemPosition();
                if (last >= feedAdapter.getItemCount() - 6) loadMoreFeed();
            }
        });

        View.OnClickListener channelClick = v -> {
            markChannel((TextView) v);
            // 点频道就是回番茄流：本区当前站记回番茄短剧，下次进 tab 不再跳回蜘蛛站。
            ZoneSites.setCurrentSiteKey(ZoneSites.ZONE_DRAMA, DramaSites.ORIGINAL_KEY);
            reloadFeed();
        };
        tvChAll.setOnClickListener(channelClick);
        tvChRecommend.setOnClickListener(channelClick);
        tvChWatch.setOnClickListener(channelClick);
        tvChManju.setOnClickListener(channelClick);
    }

    @Override
    protected void onVisible() {
        if (getContext() == null) return;
        BuiltinDramaConfigs.ensureLoaded(getContext());
        VodConfigSync.sync();
        List<SourceBean> sites = BuiltinDramaConfigs.getDramaSites();
        tvDramaTitle.setText("短剧 · " + sites.size() + " 个站 + 番茄短剧");
        // 内置区列表点短剧站进来：直接进该站（否则只显示番茄流，进不了站）。
        boolean picked = false;
        if (getActivity() != null && getActivity().getIntent() != null) {
            String extra = getActivity().getIntent()
                    .getStringExtra(com.yingtu.plus.ui.activity.HomeActivity.EXTRA_SITE_KEY);
            if (extra != null && BuiltinDramaConfigs.isDramaSite(extra)) {
                getActivity().getIntent()
                        .removeExtra(com.yingtu.plus.ui.activity.HomeActivity.EXTRA_SITE_KEY);
                for (SourceBean s : sites) {
                    if (s.getKey().equals(extra)) {
                        pickSite(s);
                        picked = true;
                        break;
                    }
                }
            }
        }
        if (!picked) {
            // 分区独立记站：本区上次选的站（或启动站）优先；没选过默认番茄流。
            String zoneKey = ZoneSites.getCurrentSiteKey(ZoneSites.ZONE_DRAMA);
            if (!TextUtils.isEmpty(zoneKey)) {
                if (DramaSites.ORIGINAL_KEY.equals(zoneKey)) {
                    showFeed();
                    picked = true;
                    if (!feedStarted) {
                        feedStarted = true;
                        loadFirstFeed();
                    }
                } else {
                    for (SourceBean s : sites) {
                        if (s.getKey().equals(zoneKey)) {
                            pickSite(s);
                            picked = true;
                            break;
                        }
                    }
                }
            }
        }
        if (!picked && !feedStarted) {
            feedStarted = true;
            loadFirstFeed();
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        io.shutdownNow();
    }

    /** 点站（分区弹窗与内置区列表点进来共用）：番茄短剧显示信息流，
     * 蜘蛛站内嵌 HomeMainFragment；同时记进短剧区自己的键。 */
    public void pickSite(SourceBean source) {
        if (source == null) return;
        ZoneSites.setCurrentSiteKey(ZoneSites.ZONE_DRAMA, source.getKey());
        if (DramaSites.ORIGINAL_KEY.equals(source.getKey())) {
            showFeed();
            if (!feedStarted) {
                feedStarted = true;
                loadFirstFeed();
            }
            return;
        }
        ApiConfig.get().setSourceBean(source);
        showSpiderContent();
    }

    private void markChannel(TextView selected) {
        TextView[] tabs = {tvChAll, tvChRecommend, tvChWatch, tvChManju};
        for (TextView t : tabs) {
            boolean on = t == selected;
            t.setTextColor(getResources().getColor(
                    on ? R.color.text_primary : R.color.text_secondary));
            t.setTypeface(null, on ? android.graphics.Typeface.BOLD
                    : android.graphics.Typeface.NORMAL);
        }
    }

    private void showFeed() {
        tvDramaHint.setVisibility(View.GONE);
        contentContainer.setVisibility(View.GONE);
        rvFeed.setVisibility(View.VISIBLE);
    }

    private void showSpiderContent() {
        tvDramaHint.setVisibility(View.GONE);
        rvFeed.setVisibility(View.GONE);
        contentContainer.setVisibility(View.VISIBLE);
        HomeMainFragment child = (HomeMainFragment)
                getChildFragmentManager().findFragmentByTag(TAG_HOME);
        if (child == null) {
            child = new HomeMainFragment();
            getChildFragmentManager().beginTransaction()
                    .replace(R.id.dramaContent, child, TAG_HOME)
                    .commit();
        } else {
            child.reloadForSourceChange();
        }
    }

    private void reloadFeed() {
        showFeed();
        loadFirstFeed();
    }

    private void loadFirstFeed() {
        if (getContext() == null || loadingFeed) return;
        loadingFeed = true;
        tvDramaHint.setVisibility(View.VISIBLE);
        tvDramaHint.setText("正在加载短剧…");
        io.execute(() -> {
            try {
                FanqieDramaClient client = new FanqieDramaClient(requireContext());
                FeedPage page = client.feedFirstPage();
                postFeed(page, true, null);
            } catch (Exception e) {
                postError(e);
            }
        });
    }

    private void loadMoreFeed() {
        if (getContext() == null || loadingFeed) return;
        loadingFeed = true;
        final String c = cellId;
        final String s = sessionId;
        final String o = nextOffset;
        io.execute(() -> {
            try {
                FanqieDramaClient client = new FanqieDramaClient(requireContext());
                FeedPage page = client.feedNextPage(c, s, o);
                postFeed(page, false, null);
            } catch (Exception e) {
                postFeed(null, false, e);
            }
        });
    }

    private void postFeed(FeedPage page, boolean first, Exception error) {
        if (!isAdded() || getActivity() == null) return;
        getActivity().runOnUiThread(() -> {
            loadingFeed = false;
            if (error != null) {
                if (first) {
                    tvDramaHint.setVisibility(View.VISIBLE);
                    tvDramaHint.setText("短剧加载失败，点“全部”重试");
                    Toast.makeText(getContext(), "短剧加载失败：" + error.getMessage(),
                            Toast.LENGTH_SHORT).show();
                }
                return;
            }
            if (page == null) return;
            if (first) {
                cellId = page.cellId;
                sessionId = page.sessionId;
                seenVids.clear();
            }
            nextOffset = page.nextOffset;
            hasMore = page.hasMore;
            List<DramaVideo> fresh = new java.util.ArrayList<>();
            for (DramaVideo v : page.items) {
                if (v.vid != null && !v.vid.isEmpty() && seenVids.add(v.vid)) fresh.add(v);
            }
            if (first) {
                feedAdapter.submit(fresh);
            } else {
                feedAdapter.append(fresh);
            }
            if (feedAdapter.getItemCount() == 0) {
                tvDramaHint.setVisibility(View.VISIBLE);
                tvDramaHint.setText("暂无短剧");
            } else {
                tvDramaHint.setVisibility(View.GONE);
            }
        });
    }

    private void postError(Exception e) {
        postFeed(null, true, e);
    }
}
