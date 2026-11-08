package com.yingtu.plus.ui.activity;

import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.Build;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.lifecycle.ViewModelProvider;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.ui.PlayerView;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.engine.bitmap_recycle.BitmapPool;
import com.bumptech.glide.load.resource.bitmap.BitmapTransformation;
import com.yingtu.plus.App;
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
import com.yingtu.plus.player.exo.ExoUtil;
import com.yingtu.plus.ui.adapter.SeriesAdapter;
import com.yingtu.plus.ui.adapter.SeriesFlagAdapter;
import com.yingtu.plus.util.FavHelper;
import com.yingtu.plus.util.HistoryHelper;
import com.yingtu.plus.util.OkGo;
import com.yingtu.plus.utils.ImgUtil;
import com.yingtu.plus.viewmodel.SourceViewModel;
import com.yingtu.plus.web.WebHomeInlineVodStore;

import android.widget.ImageView;
import android.widget.ProgressBar;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
* 详情页（FongMiTV 模式）：毛玻璃海报背景 + 头项（标题/海报/主演）
* + 剧情简介 + 播放线路行 + 选集 + 本片剧照 + 相关推荐。
* 点击某一集 → 跳转 VideoActivity 播放；点相关推荐卡片 → 进该片详情页。
*
* 本片剧照：详情数据 Vod/VodInfo 里没有剧照字段（只有 vod_pic 一张海报），
* 本节不编图、默认隐藏；背景毛玻璃直接用本片海报模糊后做底。
* 相关推荐：先按同站同分类取，取不到按同站首页推荐兜底（两步兜底），
* 两步都空才隐藏该节。
*/
public class DetailActivity extends BaseActivity {

public static final String KEY_SOURCE = "sourceKey";
public static final String KEY_VOD_ID = "vodId";

private static final int RELATED_MAX = 12;
private static final int BLUR_RADIUS = 18;

private SourceViewModel viewModel;
private SeriesFlagAdapter flagAdapter;
private SeriesAdapter seriesAdapter;
private RelatedAdapter relatedAdapter;
private ProgressBar progress;

private VodInfo vodInfo;
private String sourceKey;
private String vodId;
private boolean relatedLoaded = false;

// 顶部内联播放器：点选集后在详情页里直接播，不跳走；播不了再跳 VideoActivity 兜底。
private FrameLayout playerBox;
private PlayerView pvDetail;
private ExoPlayer detailPlayer;
private boolean detailFullscreen;
private boolean detailFallbackDone;
private int detailPlayerHeight;
private VodInfo.VodSeriesFlag playingFlag;
private VodInfo.VodSeries playingSeries;
private int playingIndex;
private long playingResume;

@Override
protected int getLayoutId() {
return R.layout.activity_detail;
}

@Override
protected void init() {
sourceKey = getIntent().getStringExtra(KEY_SOURCE);
vodId = getIntent().getStringExtra(KEY_VOD_ID);

progress = find(R.id.progress);
find(R.id.ivBack).setOnClickListener(v -> finish());
find(R.id.ivFav).setOnClickListener(v -> toggleFav());
find(R.id.ivShare).setOnClickListener(v -> shareVod());
refreshFavIcon();

// 顶部播放器接线：16:9 高度按实测宽度算；全屏按钮切横竖屏 + 沉浸栏（短剧页同款接法）。
playerBox = find(R.id.playerBox);
pvDetail = find(R.id.pvDetail);
pvDetail.setFullscreenButtonClickListener(isFullscreen -> toggleDetailFullscreen(isFullscreen));
playerBox.post(() -> {
int w = playerBox.getWidth();
if (w > 0) {
detailPlayerHeight = w * 9 / 16;
ViewGroup.LayoutParams lp = playerBox.getLayoutParams();
lp.height = detailPlayerHeight;
playerBox.setLayoutParams(lp);
}
});

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

progress.setVisibility(View.VISIBLE);
viewModel.getDetail(sourceKey, vodId);
}

private void bind(VodInfo info) {
progress.setVisibility(View.GONE);
if (info == null) {
((TextView) find(R.id.tvName)).setText("加载失败");
return;
}
this.vodInfo = info;

((TextView) find(R.id.tvName)).setText(info.name);
setText(R.id.tvSub, join(" · ", info.year, info.area, info.type));
setText(R.id.tvActor, TextUtils.isEmpty(info.actor)? "": "主演：" + info.actor);
setText(R.id.tvDirector, TextUtils.isEmpty(info.director)? "": "导演：" + info.director);
setText(R.id.tvDesc, TextUtils.isEmpty(info.des)? "暂无简介": info.des);

ImageView iv = find(R.id.ivPoster);
Glide.with(this).load(ImgUtil.getUrl(info.pic))
.placeholder(R.drawable.bg_poster_placeholder)
.centerCrop().into(iv);

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

// 本片剧照：详情数据里无剧照字段，不编图，本节保持隐藏。
bindStills(null);

flagAdapter.submit(info.seriesFlags);
if (!info.seriesFlags.isEmpty()) {
onFlagSelected(0);
}

loadRelated();
}

/** 剧照接线口：有朝一日 Vod/VodInfo 给出剧照地址列表时，传进来即显示；目前无数据，传空保持隐藏。 */
private void bindStills(List<String> stills) {
View section = find(R.id.stillsSection);
if (stills == null || stills.isEmpty()) {
section.setVisibility(View.GONE);
return;
}
section.setVisibility(View.VISIBLE);
}

/** 相关推荐：第一步同站同分类，第二步同站首页推荐兜底。 */
private void loadRelated() {
if (relatedLoaded || vodInfo == null) return;
relatedLoaded = true;
if (WebHomeInlineVodStore.KEY.equals(sourceKey)) return;
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
for (Class c: home.getTypes()) {
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
? new ArrayList<>(list.subList(0, RELATED_MAX)): list;
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
for (Vod vod: vods) {
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
for (Movie m: list) {
if (!TextUtils.isEmpty(selfId) && selfId.equals(m.vodId)) continue;
out.add(m);
}
return out;
}

private void onFlagSelected(int index) {
vodInfo.playFlag = index;
flagAdapter.setSelected(index);
seriesAdapter.submit(vodInfo.seriesFlags.get(index).list);
}

private void playSeries(int seriesIndex) {
VodInfo.VodSeriesFlag flag = vodInfo.seriesFlags.get(vodInfo.playFlag);
VodInfo.VodSeries series = flag.list.get(seriesIndex);

// 若与历史记录是同一集，带上续播进度
long resume = 0;
History h = HistoryHelper.find(History.buildKey(sourceKey, vodId));
if (h!= null && h.playFlag == vodInfo.playFlag && h.playIndex == seriesIndex) {
resume = h.position;
}

// 先在详情页顶部直接播；播不了再跳播放页兜底（VideoActivity 那条路留着，不删）。
playInline(flag, series, seriesIndex, resume);
}

/** 详情页顶部内联播放：走现成的 playerContent 取流 + 建面，不跳走。 */
private void playInline(VodInfo.VodSeriesFlag flag, VodInfo.VodSeries series, int seriesIndex, long resume) {
playingFlag = flag;
playingSeries = series;
playingIndex = seriesIndex;
playingResume = resume;
detailFallbackDone = false;
playerBox.setVisibility(View.VISIBLE);
OkGo.io(() -> {
try {
Result result = SiteApi.playerContent(sourceKey, flag.name, series.url);
String url = result.getRealUrl();
if (TextUtils.isEmpty(url)) throw new IllegalStateException("取流地址为空");
Map<String, String> headers = result.getHeader();
OkGo.main(() -> startDetailPlayer(url, headers));
} catch (Exception e) {
App.logCaught(e);
OkGo.main(() -> {
Toast.makeText(DetailActivity.this, "详情页播放失败，正在用播放页打开…", Toast.LENGTH_SHORT).show();
openVideoActivity(flag, series, seriesIndex, resume);
});
}
});
}

private void startDetailPlayer(String url, Map<String, String> headers) {
releaseDetailPlayer();
ExoPlayer.Builder builder = new ExoPlayer.Builder(this);
if (headers != null && !headers.isEmpty()) {
DefaultHttpDataSource.Factory dataSourceFactory = new DefaultHttpDataSource.Factory();
dataSourceFactory.setDefaultRequestProperties(headers);
builder.setMediaSourceFactory(new DefaultMediaSourceFactory(dataSourceFactory));
}
detailPlayer = builder.build();
// 建面：先建输出面再接播放器（短剧页黑屏同款根因），渲染方式认用户设置。
ExoUtil.setPlayerView(pvDetail);
pvDetail.setPlayer(detailPlayer);
detailPlayer.setMediaItem(MediaItem.fromUri(url));
detailPlayer.addListener(new Player.Listener() {
@Override
public void onPlayerError(PlaybackException error) {
if (detailFallbackDone) return;
detailFallbackDone = true;
Toast.makeText(DetailActivity.this, "详情页播放失败，正在用播放页打开…", Toast.LENGTH_SHORT).show();
openVideoActivity(playingFlag, playingSeries, playingIndex, playingResume);
}
});
detailPlayer.prepare();
detailPlayer.setPlayWhenReady(true);
}

private void releaseDetailPlayer() {
if (detailPlayer != null) {
if (pvDetail != null) pvDetail.setPlayer(null);
detailPlayer.release();
detailPlayer = null;
}
}

/** 全屏：切横竖屏 + 沉浸栏，播放器框全屏高 / 恢复 16:9。 */
private void toggleDetailFullscreen(boolean toFullscreen) {
detailFullscreen = toFullscreen;
if (toFullscreen) {
setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
hideDetailSystemBars();
if (playerBox != null) {
ViewGroup.LayoutParams lp = playerBox.getLayoutParams();
lp.height = getResources().getDisplayMetrics().heightPixels;
playerBox.setLayoutParams(lp);
}
} else {
setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
showDetailSystemBars();
if (playerBox != null && detailPlayerHeight > 0) {
ViewGroup.LayoutParams lp = playerBox.getLayoutParams();
lp.height = detailPlayerHeight;
playerBox.setLayoutParams(lp);
}
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
releaseDetailPlayer();
super.onDestroy();
}

/** 原 playSeries 的 VideoActivity 跳转：内联播不了时的兜底，不删。 */
private void openVideoActivity(VodInfo.VodSeriesFlag flag, VodInfo.VodSeries series, int seriesIndex, long resume) {
if (flag == null || series == null) return;
Intent i = new Intent(this, VideoActivity.class);
i.putExtra(VideoActivity.KEY_TITLE, vodInfo.name + " " + series.name);
i.putExtra(VideoActivity.KEY_URL, series.url);
i.putExtra(VideoActivity.KEY_SOURCE, sourceKey);
i.putExtra(VideoActivity.KEY_VOD_ID, vodId);
i.putExtra(VideoActivity.KEY_VOD_NAME, vodInfo.name);
i.putExtra(VideoActivity.KEY_VOD_PIC, vodInfo.pic);
i.putExtra(VideoActivity.KEY_FLAG, vodInfo.playFlag);
i.putExtra(VideoActivity.KEY_FLAG_NAME, flag.name);
i.putExtra(VideoActivity.KEY_INDEX, seriesIndex);
i.putExtra(VideoActivity.KEY_EPISODE, series.name);
i.putExtra(VideoActivity.KEY_POSITION, resume);
startActivity(i);
}

private void setText(int id, String text) {
((TextView) find(id)).setText(text == null? "": text);
}

private void refreshFavIcon() {
boolean fav = FavHelper.isFav(sourceKey, vodId);
((ImageView) find(R.id.ivFav)).setImageResource(
fav? R.drawable.ic_fav_on: R.drawable.ic_fav_off);
}

private void toggleFav() {
String name = vodInfo!= null? vodInfo.name: "";
String pic = vodInfo!= null? vodInfo.pic: "";
FavHelper.toggleVod(new FavVod(sourceKey, vodId, name, pic));
refreshFavIcon();
boolean fav = FavHelper.isFav(sourceKey, vodId);
android.widget.Toast.makeText(this, fav? "已收藏": "已取消收藏",
android.widget.Toast.LENGTH_SHORT).show();
}

private void shareVod() {
String name = vodInfo!= null? vodInfo.name: "影片";
Intent send = new Intent(Intent.ACTION_SEND);
send.setType("text/plain");
send.putExtra(Intent.EXTRA_SUBJECT, name);
send.putExtra(Intent.EXTRA_TEXT, "" + name
+ "\n来源：" + sourceKey + "\n影片ID：" + vodId);
startActivity(Intent.createChooser(send, "分享影片"));
}

private static String join(String sep, String... parts) {
StringBuilder sb = new StringBuilder();
for (String p: parts) {
if (TextUtils.isEmpty(p)) continue;
if (sb.length() > 0) sb.append(sep);
sb.append(p);
}
return sb.toString();
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
if (list!= null) data.addAll(list);
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
if (listener!= null) listener.onClick(m);
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
