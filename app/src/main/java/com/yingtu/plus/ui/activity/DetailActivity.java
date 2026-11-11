package com.yingtu.plus.ui.activity;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Log;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.widget.FrameLayout;
import android.widget.PopupMenu;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.lifecycle.ViewModelProvider;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.ui.PlayerView;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.engine.bitmap_recycle.BitmapPool;
import com.bumptech.glide.load.resource.bitmap.BitmapTransformation;
import com.github.catvod.crawler.SpiderDebug;
import com.orhanobut.hawk.Hawk;
import com.yingtu.plus.util.HawkConfig;
import com.yingtu.plus.R;
import com.yingtu.plus.api.SiteApi;
import com.yingtu.plus.api.config.VodConfig;
import com.yingtu.plus.base.BaseActivity;
import com.yingtu.plus.bean.Class;
import com.yingtu.plus.bean.FavVod;
import com.yingtu.plus.bean.History;
import com.yingtu.plus.bean.Movie;
import com.yingtu.plus.bean.Result;
import com.yingtu.plus.bean.Site;
import com.yingtu.plus.bean.Vod;
import com.yingtu.plus.bean.VodInfo;
import com.yingtu.plus.impl.ParseCallback;
import com.yingtu.plus.player.NonVideoRouter;
import com.yingtu.plus.player.ParseJob;
import com.yingtu.plus.player.PlaybackPreload;
import com.yingtu.plus.player.exo.ExoUtil;
import com.yingtu.plus.player.exo.MediaSourceFactory;
import com.yingtu.plus.ui.adapter.SeriesAdapter;
import com.yingtu.plus.ui.adapter.SeriesFlagAdapter;
import com.yingtu.plus.util.FavHelper;
import com.yingtu.plus.util.HistoryHelper;
import com.yingtu.plus.util.OkGo;
import com.yingtu.plus.utils.ImgUtil;
import com.yingtu.plus.utils.ResUtil;
import com.yingtu.plus.utils.UrlUtil;
import com.yingtu.plus.viewmodel.SourceViewModel;

import android.widget.ImageView;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.regex.Pattern;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 详情页（老版影图录像版式，第八版）：最顶上是 16:9 播放器（加载时海报当背景、
 * “正在获取播放信息”转圈，右上角显示播放中分辨率），下面依次是标题 + 来源 + 评述
 * + 标签 + 简介，再下面是操作钮（全屏/快搜/收藏/简介/菜单），再下面是清晰度
 * 行（线路 chip，有几档出几档 + 当前集/总集数），再下面是五列选集网格（当前集标绿）、
 * 本片剧照、相关推荐。播放器与信息块固定在顶部，只有选集以下滚动；全屏时播放器
 * 独占全屏。点海报播第一集，点选集/线路也在顶上播放器里播；播不了弹真实原因
 * 再跳 VideoActivity 兜底。
 *
 * 本片剧照：详情数据 Vod/VodInfo 里没有剧照字段（只有 vod_pic 一张海报），
 * 本节不编图、默认隐藏；背景毛玻璃直接用本片海报模糊后做底。
 * 相关推荐：先按同站同分类取，取不到按同站首页推荐兜底（两步兜底），
 * 两步都空才隐藏该节。
 *
 * 由 dm17 移植到上游原版时的适配（dm17 原文件 1411 行），收尾已接通：
 * 1. 推送（投屏）剥离：CastVideo/CastDialog/castCurrent/btnCast 全部去掉，
 *    投屏属播放器/投屏批（含 DLNA 开关），本次不搬；布局里 btnCast 一并去掉。
 * 2. 小说/图片分流已接通：novel://、pics:// 走播放器批的 NonVideoRouter
 *    （tryShow 弹出小说/图片查看器），不再只弹提示。
 * 3. 磁力已接通：magnet: 不建 Exo 播放器；预加载时暖 TorrServer
 *    （PlaybackPreload.preloadMagnet），点播走详情页播放器建面失败后
 *    的 fallbackToVideoPlayer → 播放页管线（Source 已注册 TorrServerExtractor）。
 * 4. 嗅探兜底已接通：取流失败/地址为空走 ParseJob 嗅探（trySniffFallback），
 *    嗅到真地址继续开播，嗅不到/命中广告预告才报原错；resolveSniffPageUrl
 *    只认 http(s) 与站内相对地址，内部 id 一律不嗅探。
 * 5. 预加载仍是页内小缓存：PlaybackPreload 的共享缓存未接进来，本页仍用
 *    preloadCache（flag + 集地址 → Result），只预加载紧邻下一集的解析结果；
 *    第一集预建播放器（prepare 好暂停着，点播放直接出画面）的逻辑保留；
 *    磁力预加载只暖 TorrServer（见上），不建播放器。
 * 6. 播放器建面改上游现成件：ExoHttpPolicy 已由播放器批接回
 *    MediaSourceFactory（三处重试策略），头信息按上游惯例放 MediaItem
 *    requestMetadata extras（MediaSourceFactory.createMediaSource 里会取出来用）；
 *    ExoUtil.setPlayerView 仍用上游的。注意 dm17 的 8 秒超时/3 次重试退避
 *    随 ExoHttpPolicy 一起没了，改走上游默认策略，待真机验。
 * 7. WebHome 内联片库守卫去掉：loadRelated 里 WebHomeInlineVodStore.KEY
 *    判断删掉（上游无 web 包）。
 * 8. App.logCaught 换成 SpiderDebug.log / Log.e（上游 App 无 logCaught、无 crash.log）。
 */
public class DetailActivity extends BaseActivity {

    public static final String KEY_SOURCE = "sourceKey";
    public static final String KEY_VOD_ID = "vodId";

    private static final int RELATED_MAX = 12;
    private static final int BLUR_RADIUS = 18;
    private static final Pattern QUALITY_FLAG = Pattern.compile(
            "(?i)^\\s*(\\d{3,4}p|[24]k|原画|蓝光|超清|高清|标清|流畅)\\s*$");

    private SourceViewModel viewModel;
    private SeriesFlagAdapter flagAdapter;
    private SeriesAdapter seriesAdapter;
    private RelatedAdapter relatedAdapter;

    private VodInfo vodInfo;
    private String sourceKey;
    private String vodId;
    private boolean relatedLoaded = false;
    private boolean descExpanded = false;

    // 顶上播放器：PlayerView 叠在海报同一框里，点海报播第一集，
    // 点选集/线路也在这个播放器里播；播不了再跳 VideoActivity 兜底。
    // 播放器与信息块固定在顶部，只有选集以下在 ScrollView 里滚动。
    private FrameLayout playerBox;
    private PlayerView pvDetail;
    private ImageView ivPoster;
    private ImageView ivPlayOverlay;
    private View loadingOverlay;
    private TextView tvResolution;
    private View fixedInfo;
    private ScrollView scrollDetail;
    private TextView btnFav;
    private TextView tvEpisodeCount;
    private TextView tvQualityLabel;
    private View qualityRow;
    private TextView tvReview;
    private TextView tvTags;
    private View tagsRow;
    private ExoPlayer detailPlayer;
    private boolean detailFullscreen;
    private boolean detailFallbackDone;
    private int detailPlayerHeight;
    // 预加载的播放器：详情页打开后就把第一集解析好、播放器 prepare 好（暂停着，
    // 只预缓冲、不出声不出画面），点播放直接 setPlayWhenReady(true) 立刻出画面。
    // detailPrebuilt=true 表示 detailPlayer 是预加载建的、还没正式开播；
    // 开播后（或切了集/线路）就不是预加载态了。preloadGeneration 用于丢弃
    // 详情已换（切线路/退页）后才回来的过期预加载结果。
    private boolean detailPrebuilt;
    private String preloadFlagName;
    private String preloadSeriesUrl;
    private String preloadPendingKey;
    private int preloadGeneration;
    private VodInfo.VodSeriesFlag playingFlag;
    private VodInfo.VodSeries playingSeries;
    private int playingIndex;
    private long playingResume;
    // 页内预加载缓存：PlaybackPreload 的替代（上游无该类），flag + 集地址 → 取流结果。
    private final Map<String, Result> preloadCache = new HashMap<>();
    // 嗅探兜底任务：取流失败时用 ParseJob 嗅探真地址；sniffFailReason 存原错，
    // 嗅探失败/命中广告预告时报原错，不报嗅探过程的错。
    private ParseJob sniffJob;
    private String sniffFailReason = "";

    @Override
    protected int getLayoutId() {
        return R.layout.activity_detail;
    }

    @Override
    protected void init() {
        sourceKey = getIntent().getStringExtra(KEY_SOURCE);
        vodId = getIntent().getStringExtra(KEY_VOD_ID);

        // 返回（播放器左上角浮层）：全屏时先退全屏，否则退出详情页。
        find(R.id.ivBack).setOnClickListener(v -> onBackPressed());
        find(R.id.btnFullscreen).setOnClickListener(v -> toggleDetailFullscreen(!detailFullscreen));
        find(R.id.btnQuickSearch).setOnClickListener(v -> quickSearch());
        find(R.id.btnFav).setOnClickListener(v -> toggleFav());
        find(R.id.btnDesc).setOnClickListener(v -> toggleDesc());
        find(R.id.btnMenu).setOnClickListener(v -> showDetailMenu(v));
        btnFav = find(R.id.btnFav);
        tvEpisodeCount = find(R.id.tvEpisodeCount);
        tvQualityLabel = find(R.id.tvQualityLabel);
        qualityRow = find(R.id.qualityRow);
        tvReview = find(R.id.tvReview);
        tvTags = find(R.id.tvTags);
        tagsRow = find(R.id.tagsRow);
        fixedInfo = find(R.id.fixedInfo);
        scrollDetail = find(R.id.scrollDetail);
        loadingOverlay = find(R.id.loadingOverlay);
        tvResolution = find(R.id.tvResolution);
        refreshFavButton();

        // 顶上播放器接线：16:9 高度按全屏宽算（播放器全宽贴边，与录像一致）；
        // 全屏按钮切横竖屏 + 沉浸栏（短剧页同款接法）。
        playerBox = find(R.id.playerBox);
        pvDetail = find(R.id.pvDetail);
        ivPlayOverlay = find(R.id.ivPlayOverlay);
        pvDetail.setFullscreenButtonClickListener(isFullscreen -> toggleDetailFullscreen(isFullscreen));
        detailPlayerHeight = ResUtil.getScreenWidth() * 9 / 16;
        ViewGroup.LayoutParams wallLp = playerBox.getLayoutParams();
        wallLp.height = detailPlayerHeight;
        playerBox.setLayoutParams(wallLp);
        // 点海报（或播放器上的播放钮）播第一集；已有播放器在播时点按由 PlayerView 自己接。
        playerBox.setOnClickListener(v -> playFirstEpisode());

        RecyclerView rvFlag = find(R.id.rvFlag);
        rvFlag.setLayoutManager(new LinearLayoutManager(
                this, RecyclerView.HORIZONTAL, false));
        flagAdapter = new SeriesFlagAdapter();
        flagAdapter.setOnFlagSelected(this::onFlagSelected);
        rvFlag.setAdapter(flagAdapter);

        RecyclerView rvSeries = find(R.id.rvSeries);
        rvSeries.setLayoutManager(new GridLayoutManager(this, 5));
        seriesAdapter = new SeriesAdapter();
        seriesAdapter.setOnSeriesClick(this::playSeries);
        rvSeries.setAdapter(seriesAdapter);

        RecyclerView rvRelated = find(R.id.rvRelated);
        rvRelated.setLayoutManager(new LinearLayoutManager(
                this, RecyclerView.HORIZONTAL, false));
        relatedAdapter = new RelatedAdapter();
        relatedAdapter.setOnRelatedClick(this::openRelated);
        rvRelated.setAdapter(relatedAdapter);

        viewModel = new ViewModelProvider(this).get(SourceViewModel.class);
        viewModel.detailResult.observe(this, this::bind);

        // 详情加载中：播放器里转圈（背景就是海报），加载完 bind() 里关掉。
        setLoading(true);
        viewModel.getDetail(sourceKey, vodId);
    }

    private void bind(VodInfo info) {
        setLoading(false);
        TextView tvTitle = find(R.id.tvTitle);
        if (info == null) {
            // 详情取失败：标题改成可点的重试入口，点后重调详情接口；不另加布局。
            setText(R.id.tvTitle, "加载失败，点击重试");
            if (tvTitle != null) tvTitle.setOnClickListener(v -> retryDetail());
            return;
        }
        // 取成功后清掉失败时挂在标题上的重试点击，不让正常标题可点。
        if (tvTitle != null) tvTitle.setOnClickListener(null);
        this.vodInfo = info;

        // 标题（录像里标题前有个 ⊕）+ 来源（站名）+ 评述 + 标签 + 简介。
        setText(R.id.tvTitle, "⊕ " + info.name);
        setText(R.id.tvSource, "来源：" + siteName());
        updateReview();
        setText(R.id.tvDesc, TextUtils.isEmpty(info.des) ? "暂无简介" : info.des);
        bindTags(info.type);

        ivPoster = find(R.id.ivPoster);
        Glide.with(this).load(ImgUtil.getUrl(info.pic))
                .placeholder(R.drawable.bg_poster_placeholder)
                .centerCrop().into(ivPoster);

        // 毛玻璃背景：本片海报缩小模糊后做底，上层半透明遮罩提可读性。
        ImageView ivBg = find(R.id.ivBg);
        if (!TextUtils.isEmpty(info.pic)) {
            Glide.with(this).load(ImgUtil.getUrl(info.pic))
                    .override(240, 360)
                    .centerCrop()
                    .transform(new BlurTransformation(BLUR_RADIUS))
                    .placeholder(R.drawable.bg_poster_placeholder)
                    .error(R.drawable.bg_poster_placeholder)
                    .into(ivBg);
        }

        // 本片剧照：滚动区最下面一节（选集网格之后）。
        bindStills(info.stills);
        // 详情视图1只显标题 / 详情页样式（设置 → 更多设置 / 播放设置）
        applyDetailViewOptions();

        flagAdapter.submit(info.seriesFlags);
        updateQualityRow();
        if (!info.seriesFlags.isEmpty()) {
            onFlagSelected(0);
        }
        // 详情一打开就开始预加载第一集：后台解析取流地址、播放器 prepare 预缓冲，
        // 点播放直接出画面；同时解析结果进页内缓存，跳播放页兜底也不用重取。
        preloadDetailPlayback();

        loadRelated();
    }

    /** 详情取失败重试：先清掉标题上的重试点击、亮加载圈，再重调详情接口。 */
    private void retryDetail() {
        TextView tvTitle = find(R.id.tvTitle);
        if (tvTitle != null) tvTitle.setOnClickListener(null);
        setText(R.id.tvTitle, "加载中…");
        setLoading(true);
        viewModel.getDetail(sourceKey, vodId);
    }

    /** 详情视图1只显标题：只留标题行，隐藏简介与剧照区；详情页样式“简洁文字”：藏剧照与毛玻璃背景。 */
    private void applyDetailViewOptions() {
        boolean titleOnly = false;
        int style = 0;
        try {
            titleOnly = Hawk.get(HawkConfig.DETAIL_VIEW1_TITLE_ONLY, false);
            style = Hawk.get(HawkConfig.DETAIL_STYLE, 0);
        } catch (Throwable ignored) {
        }
        View stills = find(R.id.stillsSection);
        View desc = find(R.id.tvDesc);
        View bg = find(R.id.ivBg);
        if (titleOnly) {
            if (desc != null) desc.setVisibility(View.GONE);
            if (stills != null) stills.setVisibility(View.GONE);
            return;
        }
        if (desc != null) desc.setVisibility(View.VISIBLE);
        if (style == 1) {
            if (stills != null) stills.setVisibility(View.GONE);
            if (bg != null) bg.setVisibility(View.GONE);
        } else if (bg != null) {
            bg.setVisibility(View.VISIBLE);
        }
    }

    /** 剧照：滚动区最下面一节（选集网格之后）；无剧照时本节隐藏。 */
    private void bindStills(List<String> stills) {
        View section = find(R.id.stillsSection);
        if (stills == null || stills.isEmpty()) {
            section.setVisibility(View.GONE);
            return;
        }
        section.setVisibility(View.VISIBLE);
        RecyclerView rvStills = find(R.id.rvStills);
        if (rvStills.getAdapter() == null) {
            rvStills.setAdapter(new StillsAdapter());
        }
        ((StillsAdapter) rvStills.getAdapter()).submit(stills);
    }

    /** 剧照横向列表：只放图。 */
    private static class StillsAdapter extends RecyclerView.Adapter<StillsAdapter.VH> {

        private final List<String> data = new ArrayList<>();

        void submit(List<String> list) {
            data.clear();
            if (list != null) data.addAll(list);
            notifyDataSetChanged();
        }

        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_still, parent, false);
            return new VH(v);
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int position) {
            String url = data.get(position);
            Glide.with(h.itemView.getContext())
                    .load(ImgUtil.getUrl(url))
                    .placeholder(R.drawable.bg_poster_placeholder)
                    .error(R.drawable.bg_poster_placeholder)
                    .centerCrop()
                    .into(h.ivStill);
        }

        @Override
        public int getItemCount() {
            return data.size();
        }

        static class VH extends RecyclerView.ViewHolder {
            ImageView ivStill;

            VH(@NonNull View itemView) {
                super(itemView);
                ivStill = itemView.findViewById(R.id.ivStill);
            }
        }
    }

    /** 相关推荐：第一步同站同分类，第二步同站首页推荐兜底。 */
    private void loadRelated() {
        if (relatedLoaded || vodInfo == null) return;
        relatedLoaded = true;
        final String typeName = vodInfo.type;
        final String selfId = vodInfo.id;
        OkGo.io(() -> {
            List<Movie> related = null;
            try {
                Site site = VodConfig.get().getSite(sourceKey);
                if (site == null || site.isEmpty()) return;
                Result home = SiteApi.homeContent(site);
                String typeId = null;
                if (!TextUtils.isEmpty(typeName)) {
                    for (Class c : home.getTypes()) {
                        if (typeName.equals(c.getTypeName())) {
                            typeId = c.getTypeId();
                            break;
                        }
                    }
                }
                if (!TextUtils.isEmpty(typeId)) {
                    try {
                        Result cat = SiteApi.categoryContent(
                                sourceKey, typeId, "1", false, new HashMap<>());
                        related = filterSelf(toMovies(cat.getList()), selfId);
                    } catch (Exception ignored) {
                        related = null;
                    }
                }
                if (related == null || related.isEmpty()) {
                    related = filterSelf(toMovies(home.getList()), selfId);
                }
            } catch (Exception ignored) {
                related = null;
            }
            final List<Movie> result = related;
            OkGo.main(() -> showRelated(result));
        });
    }

    private void showRelated(List<Movie> list) {
        if (isFinishing() || isDestroyed()) return;
        View section = find(R.id.relatedSection);
        if (list == null || list.isEmpty()) {
            section.setVisibility(View.GONE);
            return;
        }
        List<Movie> show = list.size() > RELATED_MAX
                ? new ArrayList<>(list.subList(0, RELATED_MAX)) : list;
        relatedAdapter.submit(show);
        section.setVisibility(View.VISIBLE);
    }

    private void openRelated(Movie movie) {
        if (movie == null || TextUtils.isEmpty(movie.vodId)) return;
        Intent i = new Intent(this, DetailActivity.class);
        i.putExtra(KEY_SOURCE, sourceKey);
        i.putExtra(KEY_VOD_ID, movie.vodId);
        startActivity(i);
    }

    private List<Movie> toMovies(List<Vod> vods) {
        List<Movie> list = new ArrayList<>();
        if (vods == null) return list;
        for (Vod vod : vods) {
            Movie movie = new Movie(vod.getId(), vod.getName(), vod.getPic(), vod.getRemarks());
            movie.sourceKey = sourceKey;
            movie.year = vod.getYear();
            movie.area = vod.getArea();
            movie.type = vod.getTypeName();
            movie.actor = vod.getActor();
            movie.director = vod.getDirector();
            movie.content = vod.getContent();
            list.add(movie);
        }
        return list;
    }

    private static List<Movie> filterSelf(List<Movie> list, String selfId) {
        List<Movie> out = new ArrayList<>();
        for (Movie m : list) {
            if (!TextUtils.isEmpty(selfId) && selfId.equals(m.vodId)) continue;
            out.add(m);
        }
        return out;
    }

    private void onFlagSelected(int index) {
        vodInfo.playFlag = index;
        flagAdapter.setSelected(index);
        seriesAdapter.submit(vodInfo.seriesFlags.get(index).list);
        // 切线路后当前集复位（高亮清掉）；集数行回“共 N 集”，播了某集再显示“M / N”。
        seriesAdapter.setPlaying(-1);
        playingIndex = -1;
        int total = vodInfo.seriesFlags.get(index).list.size();
        if (tvEpisodeCount != null) tvEpisodeCount.setText("共 " + total + " 集");
        updateReview();
        // 切了线路：旧的预加载播放器（如果有）作废，新线路第一集重新预加载；
        // 正在播的播放器不动，不打断播放。
        if (detailPlayer != null && detailPrebuilt) releaseDetailPlayer();
        preloadDetailPlayback();
    }

    private void updateQualityRow() {
        if (qualityRow == null) return;
        if (vodInfo == null || vodInfo.seriesFlags == null || vodInfo.seriesFlags.isEmpty()) {
            qualityRow.setVisibility(View.GONE);
            return;
        }
        qualityRow.setVisibility(View.VISIBLE);
        boolean quality = true;
        for (VodInfo.VodSeriesFlag flag : vodInfo.seriesFlags) {
            if (flag == null || TextUtils.isEmpty(flag.name) || !QUALITY_FLAG.matcher(flag.name).matches()) {
                quality = false;
                break;
            }
        }
        if (tvQualityLabel != null) tvQualityLabel.setVisibility(quality ? View.VISIBLE : View.GONE);
    }

    /** 评述行：详情自带 note（如“全254集”/“更新至254集”）就用它；没有就按当前线路
     * 的总集数写“全 N 集”；连集数都没有（无线路）就藏起这一行。 */
    private void updateReview() {
        if (tvReview == null) return;
        if (vodInfo == null) {
            tvReview.setVisibility(View.GONE);
            return;
        }
        if (!TextUtils.isEmpty(vodInfo.note)) {
            tvReview.setText("评述：" + vodInfo.note);
            tvReview.setVisibility(View.VISIBLE);
            return;
        }
        int total = 0;
        if (vodInfo.seriesFlags != null && !vodInfo.seriesFlags.isEmpty()
                && vodInfo.playFlag >= 0 && vodInfo.playFlag < vodInfo.seriesFlags.size()
                && vodInfo.seriesFlags.get(vodInfo.playFlag).list != null) {
            total = vodInfo.seriesFlags.get(vodInfo.playFlag).list.size();
        }
        if (total > 0) {
            tvReview.setText("评述：全" + total + "集");
            tvReview.setVisibility(View.VISIBLE);
        } else {
            tvReview.setVisibility(View.GONE);
        }
    }

    /** 标签行：类型字段里有几个就出几个（按 空格 / / / ，/ 、 切）；类型为空藏起整行。 */
    private void bindTags(String type) {
        if (tagsRow == null || tvTags == null) return;
        if (TextUtils.isEmpty(type)) {
            tagsRow.setVisibility(View.GONE);
            return;
        }
        String[] parts = type.split("[\\s/,，、]+");
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (TextUtils.isEmpty(p)) continue;
            if (sb.length() > 0) sb.append("  ");
            sb.append(p);
        }
        if (sb.length() == 0) {
            tagsRow.setVisibility(View.GONE);
            return;
        }
        tvTags.setText(sb.toString());
        tagsRow.setVisibility(View.VISIBLE);
    }

    /** 站名：VodConfig 里该站的显示名，取不到回退站 key。 */
    private String siteName() {
        try {
            Site site = VodConfig.get().getSite(sourceKey);
            if (site != null && !TextUtils.isEmpty(site.getName())) return site.getName();
        } catch (Exception ignored) {
        }
        return sourceKey != null ? sourceKey : "";
    }

    /** 播放器加载态：转圈 +“正在获取播放信息”，背景就是下面的海报；关掉时
     * 没在播就把播放钮露出来。 */
    private void setLoading(boolean loading) {
        if (loadingOverlay != null) loadingOverlay.setVisibility(loading ? View.VISIBLE : View.GONE);
        if (ivPlayOverlay != null) {
            ivPlayOverlay.setVisibility(loading || detailPlayer != null ? View.GONE : View.VISIBLE);
        }
    }

    private void playSeries(int seriesIndex) {
        VodInfo.VodSeriesFlag flag = vodInfo.seriesFlags.get(vodInfo.playFlag);
        VodInfo.VodSeries series = flag.list.get(seriesIndex);

        // 若与历史记录是同一集，带上续播进度
        long resume = 0;
        History h = HistoryHelper.find(History.buildKey(sourceKey, vodId));
        if (h != null && h.playFlag == vodInfo.playFlag && h.playIndex == seriesIndex) {
            resume = h.position;
        }

        // 先在详情页顶部直接播；顶上播放器播不起来再跳播放页兜底（VideoActivity 那条路留着，不删），取流失败留在详情页报真实错。
        // 当前集标绿 + 集数行显示“M / N”（点选集本身也会调 setPlaying，这里补上是
        // 为了 playFirstEpisode 这种程序触发的播放同样高亮）。
        seriesAdapter.setPlaying(seriesIndex);
        if (tvEpisodeCount != null) tvEpisodeCount.setText((seriesIndex + 1) + " / " + flag.list.size());
        // 预加载好的播放器正好是这一集：直接开播，不用现取；同时把下一集的解析
        // 也在后台排上，点“下一集”同样立刻出画面。
        if (tryStartPrebuilt(flag, series, seriesIndex, resume)) {
            preloadNextEpisode(flag, seriesIndex);
            return;
        }
        playInline(flag, series, seriesIndex, resume);
        preloadNextEpisode(flag, seriesIndex);
    }

    /** 点海报播第一集：没取到详情（选集为空）先提示“正在加载…”，不空播。
     * 已有播放器在播时点按由 PlayerView 自己接；只有预加载建的（还没开播的）
     * 播放器才允许在这里直接开播。 */
    private void playFirstEpisode() {
        if (detailPlayer != null && !detailPrebuilt) return;
        if (vodInfo == null || vodInfo.seriesFlags == null || vodInfo.seriesFlags.isEmpty()) {
            Toast.makeText(this, "正在加载…", Toast.LENGTH_SHORT).show();
            return;
        }
        VodInfo.VodSeriesFlag flag = vodInfo.seriesFlags.get(vodInfo.playFlag);
        if (flag == null || flag.list == null || flag.list.isEmpty()) {
            Toast.makeText(this, "正在加载…", Toast.LENGTH_SHORT).show();
            return;
        }
        playSeries(0);
    }

    /** 详情页顶上播放器内联播放：走现成的 playerContent 取流 + 建面，不跳走。
     * 点播时先看预加载缓存：详情打开时已解析好第一集、上一集播时已解析好下一集，
     * 命中就直接开播，不用现取现等；没命中才按原流程现取。 */
    private void playInline(VodInfo.VodSeriesFlag flag, VodInfo.VodSeries series, int seriesIndex, long resume) {
        playingFlag = flag;
        playingSeries = series;
        playingIndex = seriesIndex;
        playingResume = resume;
        detailFallbackDone = false;
        setLoading(true);
        Result preloaded = preloadCache.remove(preloadKey(flag.name, series.url));
        if (preloaded != null && !TextUtils.isEmpty(preloaded.getRealUrl())) {
            onPlayerResult(preloaded, flag, series, seriesIndex, resume);
            return;
        }
        OkGo.io(() -> {
            try {
                Result result = SiteApi.playerContent(sourceKey, flag.name, series.url);
                String url = result.getRealUrl();
                if (TextUtils.isEmpty(url)) throw new IllegalStateException("取流地址为空");
                OkGo.main(() -> onPlayerResult(result, flag, series, seriesIndex, resume));
            } catch (Exception e) {
                SpiderDebug.log("detail", "playInline failed key=%s err=%s", sourceKey, e.getMessage());
                OkGo.main(() -> trySniffFallback(e.getMessage(), flag, series, seriesIndex, resume));
            }
        });
    }

    /** 取流成功后的统一处理（预加载命中与现取两条路共用）：非视频走小说/图片
     * 分流；磁力不建 Exo 播放器——直接进顶上播放器建面，播不起来走
     * onPlayerError → fallbackToVideoPlayer → 播放页管线（TorrServerExtractor
     * 在那条管线里把 magnet: 解析成可播流）；地址为空走嗅探兜底。 */
    private void onPlayerResult(Result result, VodInfo.VodSeriesFlag flag, VodInfo.VodSeries series, int seriesIndex, long resume) {
        String url = result.getRealUrl();
        if (TextUtils.isEmpty(url)) {
            trySniffFallback("取流地址为空", flag, series, seriesIndex, resume);
            return;
        }
        if (NonVideoRouter.isNonVideo(url)) {
            setLoading(false);
            NonVideoRouter.tryShow(url, vodInfo.name);
            return;
        }
        startDetailPlayer(url, result.getHeader());
    }

    private static boolean isNonVideo(String url) {
        return url.startsWith("novel://") || url.startsWith("pics://");
    }

    private static boolean isMagnet(String url) {
        return url.startsWith("magnet:");
    }

    /** 取流失败后的嗅探兜底：先停旧嗅探，再拿页面地址开 WebView 嗅探真地址。
     * 页面地址取不到直接报原错，不空等；嗅探到真地址走正常路开播；
     * 抓到广告/预告或超时同样报原错。取流成功与预加载命中完全不走这里。 */
    private void trySniffFallback(String reason, VodInfo.VodSeriesFlag flag, VodInfo.VodSeries series, int seriesIndex, long resume) {
        stopSniff();
        String pageUrl = resolveSniffPageUrl(series == null ? "" : series.url);
        if (TextUtils.isEmpty(pageUrl)) {
            showPlayError(reason);
            return;
        }
        sniffFailReason = TextUtils.isEmpty(reason) ? "未知错误" : reason;
        Toast.makeText(DetailActivity.this, "正在嗅探播放地址…", Toast.LENGTH_SHORT).show();
        Map<String, String> header = new HashMap<>();
        try {
            Site site = VodConfig.get().getSite(sourceKey);
            if (site != null && site.getHeader() != null) header.putAll(site.getHeader());
        } catch (Throwable ignored) {
        }
        header.put("Referer", pageUrl);
        Result r = new Result();
        r.setKey(sourceKey);
        r.setFlag(flag == null ? "" : flag.name);
        r.setUrl(pageUrl);
        r.setHeader(header);
        sniffJob = ParseJob.create(new ParseCallback() {
            @Override
            public void onParseSuccess(Map<String, String> headers, String url, String from) {
                OkGo.main(() -> {
                    if (isFinishing() || isDestroyed()) {
                        stopSniff();
                        return;
                    }
                    if (isSniffedAdOrPreview(url)) {
                        SpiderDebug.log("sniff", "sniff hit ad/preview, give up url=%s", url);
                        showPlayError(sniffFailReason);
                        sniffJob = null;
                        return;
                    }
                    Result s = new Result();
                    s.setUrl(url);
                    s.setHeader(headers == null ? new HashMap<>() : headers);
                    sniffJob = null;
                    onPlayerResult(s, flag, series, seriesIndex, resume);
                });
            }

            @Override
            public void onParseError() {
                OkGo.main(() -> {
                    if (isFinishing() || isDestroyed()) {
                        stopSniff();
                        return;
                    }
                    showPlayError(sniffFailReason);
                    sniffJob = null;
                });
            }
        }).start(r, false);
    }

    /** 从集地址里取嗅探用的页面地址：TVBox“集名$地址”取最后一个 $ 之后；
     * http(s) 直接回；/ 开头用 vodId（是完整 URL 时）或站点 api（是 URL 时）
     * 做基底拼；其余（集 id、内部标识等非 URL）返回空串，调用方直接报原错，不嗅探。 */
    private String resolveSniffPageUrl(String seriesUrl) {
        if (TextUtils.isEmpty(seriesUrl)) return "";
        String u = seriesUrl;
        int dollar = u.lastIndexOf('$');
        if (dollar >= 0) u = u.substring(dollar + 1);
        u = u.trim();
        if (u.startsWith("http://") || u.startsWith("https://")) return u;
        if (u.startsWith("/")) {
            String base = "";
            if (!TextUtils.isEmpty(vodId) && (vodId.startsWith("http://") || vodId.startsWith("https://"))) {
                base = vodId;
            } else {
                try {
                    Site site = VodConfig.get().getSite(sourceKey);
                    String api = site == null ? "" : site.getApi();
                    if (!TextUtils.isEmpty(api) && (api.startsWith("http://") || api.startsWith("https://"))) base = api;
                } catch (Throwable ignored) {
                }
            }
            if (TextUtils.isEmpty(base)) return "";
            String resolved = UrlUtil.resolve(base, u);
            if (resolved.startsWith("http://") || resolved.startsWith("https://")) return resolved;
            return "";
        }
        return "";
    }

    /** 嗅到的地址是不是广告/预告：含 advert、preroll、pre-roll、/ads/、
     * preview、trailer、预告任一即 true。不拦单独的 ad 与 gg，防误伤正常地址。
     * ParseJob 首次命中即胜出，被拦的首个命中意味着本次嗅探失败，不再等第二个。 */
    private boolean isSniffedAdOrPreview(String url) {
        if (TextUtils.isEmpty(url)) return true;
        String u = url.toLowerCase();
        return u.contains("advert") || u.contains("preroll") || u.contains("pre-roll")
                || u.contains("/ads/") || u.contains("preview") || u.contains("trailer") || u.contains("预告");
    }

    private void stopSniff() {
        if (sniffJob != null) {
            sniffJob.stop();
            sniffJob = null;
        }
    }

    /** 取流失败留在详情页报真实错：取流地址为空就是取流地址为空、Source error 就是
     * Source error。取流失败时根本没东西可播，起播放页也是白起（VideoActivity 重取
     * 还是空），不跳走、不说“正在用播放页打开”。清理后海报墙恢复原样（播放钮还在），
     * 用户点播放可再试。 */
    private void showPlayError(String reason) {
        if (detailFullscreen) toggleDetailFullscreen(false);
        releaseDetailPlayer();
        setLoading(false);
        if (TextUtils.isEmpty(reason)) reason = "未知错误";
        Toast.makeText(DetailActivity.this, "播放失败：" + reason, Toast.LENGTH_LONG).show();
    }

    /** 内联播放器报错后的兜底（取流已成功、但顶上播放器播不起来时才走这里）：退全屏、
     * 放掉死播放器、toast 带真实原因、跳播放页用另一条管线重试。取流失败不走这里，
     * 取流失败走 showPlayError（留在详情页报真实错、不跳播放页）。 */
    private void fallbackToVideoPlayer(String reason, VodInfo.VodSeriesFlag flag, VodInfo.VodSeries series, int seriesIndex, long resume) {
        if (detailFullscreen) toggleDetailFullscreen(false);
        releaseDetailPlayer();
        setLoading(false);
        if (TextUtils.isEmpty(reason)) reason = "未知错误";
        Toast.makeText(DetailActivity.this, "详情页播放失败（" + reason + "），正在用播放页重试…", Toast.LENGTH_LONG).show();
        openVideoActivity(flag, series, seriesIndex, resume);
    }

    /** 顶上播放器切到播放态：PlayerView 露出来，播放钮藏起来（别压在画面上），转圈关掉。
     * 海报也藏掉：横屏下屏幕比 16:9 更宽时视频两侧会留黑边，海报会从黑边处漏出来
     * 造成分屏；海报只是播出前的占位，播出后不需要，收起时 showPosterWall 会恢复。 */
    private void showPlayerWall() {
        if (pvDetail != null) pvDetail.setVisibility(View.VISIBLE);
        if (ivPoster != null) ivPoster.setVisibility(View.GONE);
        setLoading(false);
    }

    /** 顶上播放器回到海报态：PlayerView 藏起来，海报 + 播放钮露出来，分辨率角标藏掉。 */
    private void showPosterWall() {
        if (pvDetail != null) pvDetail.setVisibility(View.GONE);
        if (ivPoster != null) ivPoster.setVisibility(View.VISIBLE);
        if (tvResolution != null) tvResolution.setVisibility(View.GONE);
        setLoading(false);
    }

    private void startDetailPlayer(String url, Map<String, String> headers) {
        releaseDetailPlayer();
        buildDetailPlayer(url, headers);
        detailPlayer.setPlayWhenReady(true);
        showPlayerWall();
    }

    /** 建播放器 + 挂监听 + prepare（只预缓冲，不自动播、不露画面）：现播与
     * 预加载共用。预加载调它时播放器暂停着，海报墙还盖在上面；点播放时只
     * 要 setPlayWhenReady(true) + 露出画面，立刻出画面。
     * 上游适配：用上游现成的 MediaSourceFactory，头信息按上游惯例放进
     * MediaItem requestMetadata extras（createMediaSource 里会取出来用）。 */
    private void buildDetailPlayer(String url, Map<String, String> headers) {
        ExoPlayer.Builder builder = new ExoPlayer.Builder(this);
        builder.setMediaSourceFactory(new MediaSourceFactory());
        detailPlayer = builder.build();
        // 建面：先建输出面再接播放器（短剧页黑屏同款根因），渲染方式认用户设置。
        ExoUtil.setPlayerView(pvDetail);
        pvDetail.setPlayer(detailPlayer);
        detailPlayer.setMediaItem(buildMediaItem(url, headers));
        detailPlayer.addListener(new Player.Listener() {
            @Override
            public void onVideoSizeChanged(androidx.media3.common.VideoSize videoSize) {
                // 播放器右上角分辨率角标（录像里 1920x1080 那种）：按实际视频尺寸写。
                if (tvResolution != null && videoSize.width > 0 && videoSize.height > 0) {
                    tvResolution.setText(videoSize.width + "x" + videoSize.height);
                    tvResolution.setVisibility(View.VISIBLE);
                }
            }

            @Override
            public void onPlayerError(PlaybackException error) {
                if (detailFallbackDone) return;
                detailFallbackDone = true;
                Log.e("DetailActivity", "detail player error", error);
                // 预加载阶段（还没点播放）就报错：只静默放掉这个预建的播放器，不跳播放页、
                // 不弹错——用户点播放时按原流程现取现播，不被一次预加载失败带偏。
                if (detailPrebuilt) {
                    releaseDetailPlayer();
                    return;
                }
                String reason = error.getMessage();
                if (TextUtils.isEmpty(reason)) reason = error.getErrorCodeName();
                if (TextUtils.isEmpty(reason)) reason = error.toString();
                fallbackToVideoPlayer(reason, playingFlag, playingSeries, playingIndex, playingResume);
            }
        });
        detailPlayer.prepare();
    }

    /** 头信息按上游惯例走 MediaItem requestMetadata extras。 */
    private static MediaItem buildMediaItem(String url, Map<String, String> headers) {
        Bundle extras = new Bundle();
        if (headers != null) {
            for (Map.Entry<String, String> e : headers.entrySet()) {
                if (e.getKey() != null && e.getValue() != null) extras.putString(e.getKey(), e.getValue());
            }
        }
        return new MediaItem.Builder().setUri(url)
                .setRequestMetadata(new MediaItem.RequestMetadata.Builder()
                        .setMediaUri(Uri.parse(url)).setExtras(extras).build())
                .build();
    }

    /** 详情页预加载：打开详情（或切线路）后，后台解析第一集的播放地址，
     * 解析结果进页内缓存（跳播放页兜底也能复用），再把播放器 prepare 好暂停着。
     * 磁力/小说/图片不建 Exo 播放器，只缓存解析结果，点播时按分流提示处理。
     * 失败全静默：解析失败、地址为空、详情已换页，都不弹错、不阻塞点播。 */
    private void preloadDetailPlayback() {
        if (vodInfo == null || vodInfo.seriesFlags == null || vodInfo.seriesFlags.isEmpty()) return;
        if (detailPlayer != null) return;
        VodInfo.VodSeriesFlag flag = vodInfo.seriesFlags.get(vodInfo.playFlag);
        if (flag == null || flag.list == null || flag.list.isEmpty()) return;
        final VodInfo.VodSeries series = flag.list.get(0);
        final String flagName = flag.name;
        final String seriesUrl = series.url;
        if (TextUtils.isEmpty(seriesUrl)) return;
        // 同一集正在预加载就别重复排队。
        final String pendingKey = preloadKey(flagName, seriesUrl);
        if (pendingKey.equals(preloadPendingKey)) return;
        preloadPendingKey = pendingKey;
        final int generation = ++preloadGeneration;
        // 磁力集地址本身就是 magnet:：不取流、不建播放器，只暖 TorrServer，
        // 点播时播放页管线直接用暖好的服务与种子。
        if (isMagnet(seriesUrl)) {
            PlaybackPreload.preloadMagnet(seriesUrl);
            preloadPendingKey = null;
            return;
        }
        OkGo.io(() -> {
            try {
                Result result = SiteApi.playerContent(sourceKey, flagName, seriesUrl);
                String url = result == null ? "" : result.getRealUrl();
                if (TextUtils.isEmpty(url)) return;
                // 解析结果进页内缓存：点播放时 playInline 先取缓存，不用重取。
                preloadCache.put(preloadKey(flagName, seriesUrl), result);
                if (isMagnet(url)) {
                    // 磁力只暖 TorrServer（播放页管线用），不建播放器。
                    PlaybackPreload.preloadMagnet(url);
                    return;
                }
                if (isNonVideo(url)) {
                    // 小说/图片不建播放器，点播时按分流处理。
                    return;
                }
                final Map<String, String> headers = result.getHeader();
                final String playUrl = url;
                OkGo.main(() -> {
                    if (generation != preloadGeneration || detailPlayer != null || isFinishing() || isDestroyed()) return;
                    preloadPendingKey = null;
                    buildDetailPlayer(playUrl, headers);
                    detailPrebuilt = true;
                    preloadFlagName = flagName;
                    preloadSeriesUrl = seriesUrl;
                });
            } catch (Exception e) {
                // 预加载失败静默：点播时按原流程现取。
                SpiderDebug.log("preload", "detail preload failed key=%s err=%s", sourceKey, e.getMessage());
            } finally {
                if (pendingKey.equals(preloadPendingKey)) preloadPendingKey = null;
            }
        });
    }

    private static String preloadKey(String flagName, String seriesUrl) {
        return flagName + "\n" + seriesUrl;
    }

    /** 预加载的播放器正好是要点播的这一集：直接开播（续播位置照常接上）。 */
    private boolean tryStartPrebuilt(VodInfo.VodSeriesFlag flag, VodInfo.VodSeries series, int seriesIndex, long resume) {
        if (detailPlayer == null || !detailPrebuilt) return false;
        if (flag == null || series == null) return false;
        if (!TextUtils.equals(preloadFlagName, flag.name) || !TextUtils.equals(preloadSeriesUrl, series.url)) return false;
        playingFlag = flag;
        playingSeries = series;
        playingIndex = seriesIndex;
        playingResume = resume;
        detailFallbackDone = false;
        detailPrebuilt = false;
        if (resume > 0) detailPlayer.seekTo(resume);
        setLoading(false);
        detailPlayer.setPlayWhenReady(true);
        showPlayerWall();
        return true;
    }

    /** 播完当前集（或点开某集）后，把下一集的解析在后台排上，点“下一集”不用现等。
     * 只预加载紧邻的下一集，不整季扫，避免把站打爆、把流量撑爆。 */
    private void preloadNextEpisode(VodInfo.VodSeriesFlag flag, int seriesIndex) {
        if (flag == null || flag.list == null) return;
        int next = seriesIndex + 1;
        if (next < 0 || next >= flag.list.size()) return;
        VodInfo.VodSeries nextSeries = flag.list.get(next);
        if (nextSeries == null || TextUtils.isEmpty(nextSeries.url)) return;
        final String key = preloadKey(flag.name, nextSeries.url);
        if (preloadCache.containsKey(key)) return;
        OkGo.io(() -> {
            try {
                Result result = SiteApi.playerContent(sourceKey, flag.name, nextSeries.url);
                if (result != null && !TextUtils.isEmpty(result.getRealUrl())) {
                    preloadCache.put(key, result);
                }
            } catch (Exception ignored) {
                // 下一集预加载失败静默：点播时按原流程现取。
            }
        });
    }

    private void releaseDetailPlayer() {
        if (detailPlayer != null) {
            if (pvDetail != null) pvDetail.setPlayer(null);
            detailPlayer.release();
            detailPlayer = null;
        }
        // 预加载态一起清掉：换集/切线路/退页后，之前排队的预加载结果一律作废。
        detailPrebuilt = false;
        preloadFlagName = null;
        preloadSeriesUrl = null;
        preloadPendingKey = null;
        preloadGeneration++;
        showPosterWall();
    }

    /** 全屏：切横竖屏 + 沉浸栏，播放器独占全屏（信息块与滚动区都藏掉）/ 恢复 16:9。 */
    private void toggleDetailFullscreen(boolean toFullscreen) {
        detailFullscreen = toFullscreen;
        if (toFullscreen) {
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
            hideDetailSystemBars();
            if (fixedInfo != null) fixedInfo.setVisibility(View.GONE);
            if (scrollDetail != null) scrollDetail.setVisibility(View.GONE);
            if (playerBox != null) {
                // 横屏高度取屏幕短边：setRequestedOrientation 是异步的，此时读到的
                // heightPixels 还是竖屏的长边；直接用它会让播放器框比横屏高出一截，
                // 16:9 视频在框里居中留出上下黑边、海报从上面漏出来，造成上下分屏。
                android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
                ViewGroup.LayoutParams lp = playerBox.getLayoutParams();
                lp.height = Math.min(dm.widthPixels, dm.heightPixels);
                playerBox.setLayoutParams(lp);
            }
            if (ivPlayOverlay != null) ivPlayOverlay.setVisibility(View.GONE);
        } else {
            // 退出全屏不锁竖屏：跟 WebHomeActivity.hideCustomView 学，用 UNSPECIFIED
            // 交回系统/传感器，否则退出后转屏与自动旋转永久失效。
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
            showDetailSystemBars();
            if (fixedInfo != null) fixedInfo.setVisibility(View.VISIBLE);
            if (scrollDetail != null) scrollDetail.setVisibility(View.VISIBLE);
            if (playerBox != null && detailPlayerHeight > 0) {
                ViewGroup.LayoutParams lp = playerBox.getLayoutParams();
                lp.height = detailPlayerHeight;
                playerBox.setLayoutParams(lp);
            }
            if (detailPlayer == null) showPosterWall();
        }
        if (pvDetail != null) pvDetail.setFullscreenButtonState(toFullscreen);
    }

    private void hideDetailSystemBars() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            WindowInsetsController c = getWindow().getInsetsController();
            if (c != null) {
                c.hide(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
                c.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        } else {
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        }
    }

    private void showDetailSystemBars() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            WindowInsetsController c = getWindow().getInsetsController();
            if (c != null) c.show(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
        } else {
            getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_VISIBLE);
        }
    }

    @Override
    public void onBackPressed() {
        if (detailFullscreen) {
            toggleDetailFullscreen(false);
            return;
        }
        super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        stopSniff();
        preloadCache.clear();
        releaseDetailPlayer();
        super.onDestroy();
    }

    /** 原 playSeries 的 VideoActivity 跳转：内联播不了时的兜底，不删。 */
    private void openVideoActivity(VodInfo.VodSeriesFlag flag, VodInfo.VodSeries series, int seriesIndex, long resume) {
        if (flag == null || series == null) return;
        Intent i = new Intent(this, VideoActivity.class);
        // 传参加裸 key：VideoActivity 只读 "key"/"id"/"name"/"pic"（getKey/getId/getName/getPic），
        // 它定义的 KEY_SOURCE/KEY_VOD_ID/KEY_VOD_NAME/KEY_VOD_PIC 常量（"sourceKey"/"vodId"/
        // "vodName"/"vodPic"）全树没人读；之前用它们传参，兜底起来的播放页收到的 key/id/name
        // 全是空，只能走 setEmpty 显示错误页，从来没拿到过能播的东西。裸 key 才是全树标准
        // （VideoActivity.start、列表/收藏/推送/直播/网盘/续播全是裸 key）。
        i.putExtra("key", sourceKey);
        i.putExtra("id", vodId);
        i.putExtra("name", vodInfo.name);
        i.putExtra("pic", vodInfo.pic);
        i.putExtra(VideoActivity.KEY_TITLE, vodInfo.name + " " + series.name);
        i.putExtra(VideoActivity.KEY_URL, series.url);
        i.putExtra(VideoActivity.KEY_FLAG, vodInfo.playFlag);
        i.putExtra(VideoActivity.KEY_FLAG_NAME, flag.name);
        i.putExtra(VideoActivity.KEY_INDEX, seriesIndex);
        i.putExtra(VideoActivity.KEY_EPISODE, series.name);
        i.putExtra(VideoActivity.KEY_POSITION, resume);
        startActivity(i);
    }

    private void setText(int id, String text) {
        ((TextView) find(id)).setText(text == null ? "" : text);
    }

    private void refreshFavButton() {
        if (btnFav == null) return;
        boolean fav = FavHelper.isFav(sourceKey, vodId);
        btnFav.setText(fav ? "已收藏" : "收藏");
    }

    private void toggleFav() {
        String name = vodInfo != null ? vodInfo.name : "";
        String pic = vodInfo != null ? vodInfo.pic : "";
        FavHelper.toggleVod(new FavVod(sourceKey, vodId, name, pic));
        refreshFavButton();
        boolean fav = FavHelper.isFav(sourceKey, vodId);
        android.widget.Toast.makeText(this, fav ? "已收藏" : "已取消收藏",
                android.widget.Toast.LENGTH_SHORT).show();
    }

    /** 快搜：按本片片名跨站搜（SearchActivity 接 keyword 预置词）。 */
    private void quickSearch() {
        if (vodInfo == null || TextUtils.isEmpty(vodInfo.name)) {
            Toast.makeText(this, "片名还没加载出来", Toast.LENGTH_SHORT).show();
            return;
        }
        Intent i = new Intent(this, SearchActivity.class);
        i.putExtra("keyword", vodInfo.name);
        startActivity(i);
    }

    /** 简介：简介行三行收起 / 全文展开来回切。 */
    private void toggleDesc() {
        descExpanded = !descExpanded;
        TextView tvDesc = find(R.id.tvDesc);
        if (tvDesc != null) tvDesc.setMaxLines(descExpanded ? Integer.MAX_VALUE : 3);
    }

    /** 菜单：分享影片、复制片名（都是现成的能力，不造新入口）。 */
    private void showDetailMenu(View anchor) {
        PopupMenu menu = new PopupMenu(this, anchor);
        menu.getMenu().add(Menu.NONE, 1, Menu.NONE, "分享");
        menu.getMenu().add(Menu.NONE, 2, Menu.NONE, "复制片名");
        menu.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == 1) {
                shareVod();
            } else if (item.getItemId() == 2) {
                copyVodName();
            }
            return true;
        });
        menu.show();
    }

    private void copyVodName() {
        String name = vodInfo != null ? vodInfo.name : "";
        if (TextUtils.isEmpty(name)) {
            Toast.makeText(this, "片名还没加载出来", Toast.LENGTH_SHORT).show();
            return;
        }
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) {
            cm.setPrimaryClip(ClipData.newPlainText("影片名", name));
            Toast.makeText(this, "已复制片名", Toast.LENGTH_SHORT).show();
        }
    }

    private void shareVod() {
        String name = vodInfo != null ? vodInfo.name : "影片";
        Intent send = new Intent(Intent.ACTION_SEND);
        send.setType("text/plain");
        send.putExtra(Intent.EXTRA_SUBJECT, name);
        send.putExtra(Intent.EXTRA_TEXT, "" + name
                + "\n来源：" + sourceKey + "\n影片ID：" + vodId);
        startActivity(Intent.createChooser(send, "分享影片"));
    }

    /** 相关推荐横排卡片：复用 item_grid_movie，定宽 120dp 横向滑动。 */
    private static class RelatedAdapter extends RecyclerView.Adapter<RelatedAdapter.VH> {

        interface OnRelatedClick {
            void onClick(Movie movie);
        }

        private final List<Movie> data = new ArrayList<>();
        private OnRelatedClick listener;

        void setOnRelatedClick(OnRelatedClick l) {
            this.listener = l;
        }

        void submit(List<Movie> list) {
            data.clear();
            if (list != null) data.addAll(list);
            notifyDataSetChanged();
        }

        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_grid_movie, parent, false);
            int width = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 120,
                    parent.getContext().getResources().getDisplayMetrics());
            v.setLayoutParams(new RecyclerView.LayoutParams(
                    width, ViewGroup.LayoutParams.WRAP_CONTENT));
            return new VH(v);
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int position) {
            Movie m = data.get(position);
            h.tvName.setText(m.name);
            if (m.note == null || m.note.isEmpty()) {
                h.tvNote.setVisibility(View.GONE);
            } else {
                h.tvNote.setVisibility(View.VISIBLE);
                h.tvNote.setText(m.note);
            }
            h.tvRating.setVisibility(View.GONE);
            h.tvBadge.setVisibility(View.GONE);
            Glide.with(h.itemView.getContext())
                    .load(ImgUtil.getUrl(m.pic))
                    .placeholder(R.drawable.bg_poster_placeholder)
                    .error(R.drawable.bg_poster_placeholder)
                    .centerCrop()
                    .into(h.ivPoster);
            h.itemView.setOnClickListener(v -> {
                if (listener != null) listener.onClick(m);
            });
        }

        @Override
        public int getItemCount() {
            return data.size();
        }

        static class VH extends RecyclerView.ViewHolder {
            ImageView ivPoster;
            TextView tvName;
            TextView tvNote;
            TextView tvRating;
            TextView tvBadge;

            VH(@NonNull View itemView) {
                super(itemView);
                ivPoster = itemView.findViewById(R.id.ivPoster);
                tvName = itemView.findViewById(R.id.tvName);
                tvNote = itemView.findViewById(R.id.tvNote);
                tvRating = itemView.findViewById(R.id.tvRating);
                tvBadge = itemView.findViewById(R.id.tvBadge);
            }
        }
    }

    /** Glide 模糊变换：纯 Java 堆栈模糊，不引新依赖。 */
    private static class BlurTransformation extends BitmapTransformation {

        private final int radius;

        BlurTransformation(int radius) {
            this.radius = radius;
        }

        @Override
        protected Bitmap transform(@NonNull BitmapPool pool, @NonNull Bitmap toTransform,
                                   int outWidth, int outHeight) {
            Bitmap bitmap = pool.get(toTransform.getWidth(), toTransform.getHeight(),
                    Bitmap.Config.ARGB_8888);
            new Canvas(bitmap).drawBitmap(toTransform, 0, 0, null);
            stackBlur(bitmap, radius);
            return bitmap;
        }

        @Override
        public void updateDiskCacheKey(@NonNull MessageDigest messageDigest) {
            messageDigest.update(("blur" + radius).getBytes(StandardCharsets.UTF_8));
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof BlurTransformation
                    && ((BlurTransformation) o).radius == radius;
        }

        @Override
        public int hashCode() {
            return ("blur" + radius).hashCode();
        }

        /** 堆栈模糊（radius 小于 1 时原图返回）。 */
        private static void stackBlur(Bitmap bitmap, int radius) {
            if (radius < 1) return;
            int w = bitmap.getWidth();
            int h = bitmap.getHeight();
            int[] pix = new int[w * h];
            bitmap.getPixels(pix, 0, w, 0, 0, w, h);
            int wm = w - 1;
            int hm = h - 1;
            int wh = w * h;
            int div = radius + radius + 1;
            int[] r = new int[wh];
            int[] g = new int[wh];
            int[] b = new int[wh];
            int rsum, gsum, bsum, x, y, i, p, yp, yi, yw;
            int[] vmin = new int[Math.max(w, h)];
            int divsum = (div + 1) >> 1;
            divsum *= divsum;
            int[] dv = new int[256 * divsum];
            for (i = 0; i < 256 * divsum; i++) dv[i] = i / divsum;
            yw = yi = 0;
            int[][] stack = new int[div][3];
            int stackpointer;
            int stackstart;
            int[] sir;
            int rbs;
            int r1 = radius + 1;
            int routsum, goutsum, boutsum;
            int rinsum, ginsum, binsum;
            for (y = 0; y < h; y++) {
                rinsum = ginsum = binsum = routsum = goutsum = boutsum = rsum = gsum = bsum = 0;
                for (i = -radius; i <= radius; i++) {
                    p = pix[yi + Math.min(wm, Math.max(i, 0))];
                    sir = stack[i + radius];
                    sir[0] = (p & 0xff0000) >> 16;
                    sir[1] = (p & 0x00ff00) >> 8;
                    sir[2] = p & 0x0000ff;
                    rbs = r1 - Math.abs(i);
                    rsum += sir[0] * rbs;
                    gsum += sir[1] * rbs;
                    bsum += sir[2] * rbs;
                    if (i > 0) {
                        rinsum += sir[0];
                        ginsum += sir[1];
                        binsum += sir[2];
                    } else {
                        routsum += sir[0];
                        goutsum += sir[1];
                        boutsum += sir[2];
                    }
                }
                stackpointer = radius;
                for (x = 0; x < w; x++) {
                    r[yi] = dv[rsum];
                    g[yi] = dv[gsum];
                    b[yi] = dv[bsum];
                    rsum -= routsum;
                    gsum -= goutsum;
                    bsum -= boutsum;
                    stackstart = stackpointer - radius + div;
                    sir = stack[stackstart % div];
                    routsum -= sir[0];
                    goutsum -= sir[1];
                    boutsum -= sir[2];
                    if (y == 0) vmin[x] = Math.min(x + radius + 1, wm);
                    p = pix[yw + vmin[x]];
                    sir[0] = (p & 0xff0000) >> 16;
                    sir[1] = (p & 0x00ff00) >> 8;
                    sir[2] = p & 0x0000ff;
                    rinsum += sir[0];
                    ginsum += sir[1];
                    binsum += sir[2];
                    rsum += rinsum;
                    gsum += ginsum;
                    bsum += binsum;
                    stackpointer = (stackpointer + 1) % div;
                    sir = stack[stackpointer];
                    routsum += sir[0];
                    goutsum += sir[1];
                    boutsum += sir[2];
                    rinsum -= sir[0];
                    ginsum -= sir[1];
                    binsum -= sir[2];
                    yi++;
                }
                yw += w;
            }
            for (x = 0; x < w; x++) {
                rinsum = ginsum = binsum = routsum = goutsum = boutsum = rsum = gsum = bsum = 0;
                yp = -radius * w;
                for (i = -radius; i <= radius; i++) {
                    yi = Math.max(0, yp) + x;
                    sir = stack[i + radius];
                    sir[0] = r[yi];
                    sir[1] = g[yi];
                    sir[2] = b[yi];
                    rbs = r1 - Math.abs(i);
                    rsum += r[yi] * rbs;
                    gsum += g[yi] * rbs;
                    bsum += b[yi] * rbs;
                    if (i > 0) {
                        rinsum += sir[0];
                        ginsum += sir[1];
                        binsum += sir[2];
                    } else {
                        routsum += sir[0];
                        goutsum += sir[1];
                        boutsum += sir[2];
                    }
                    if (i < hm) yp += w;
                }
                yi = x;
                stackpointer = radius;
                for (y = 0; y < h; y++) {
                    pix[yi] = (0xff000000 & pix[yi]) | (dv[rsum] << 16) | (dv[gsum] << 8) | dv[bsum];
                    rsum -= routsum;
                    gsum -= goutsum;
                    bsum -= boutsum;
                    stackstart = stackpointer - radius + div;
                    sir = stack[stackstart % div];
                    routsum -= sir[0];
                    goutsum -= sir[1];
                    boutsum -= sir[2];
                    if (x == 0) vmin[y] = Math.min(y + r1, hm) * w;
                    p = x + vmin[y];
                    sir[0] = r[p];
                    sir[1] = g[p];
                    sir[2] = b[p];
                    rinsum += sir[0];
                    ginsum += sir[1];
                    binsum += sir[2];
                    rsum += rinsum;
                    gsum += ginsum;
                    bsum += binsum;
                    stackpointer = (stackpointer + 1) % div;
                    sir = stack[stackpointer];
                    routsum += sir[0];
                    goutsum += sir[1];
                    boutsum += sir[2];
                    rinsum -= sir[0];
                    ginsum -= sir[1];
                    binsum -= sir[2];
                    yi += w;
                }
            }
            bitmap.setPixels(pix, 0, w, 0, 0, w, h);
        }
    }
}
