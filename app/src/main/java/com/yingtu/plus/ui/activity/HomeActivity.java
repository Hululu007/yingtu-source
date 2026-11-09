package com.yingtu.plus.ui.activity;

import android.content.Intent;
import android.content.res.ColorStateList;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.GridLayoutManager;

import com.yingtu.plus.BuildConfig;
import com.yingtu.plus.R;
import com.yingtu.plus.api.ApiConfig;
import com.yingtu.plus.api.VodConfigSync;
import com.yingtu.plus.base.BaseActivity;
import com.yingtu.plus.bean.SourceBean;
import com.yingtu.plus.ui.adapter.SourceGridAdapter;
import com.yingtu.plus.ui.dialog.CastDialog;
import com.yingtu.plus.ui.fragment.DramaFragment;
import com.yingtu.plus.ui.fragment.FavVodFragment;
import com.yingtu.plus.ui.fragment.HomeMainFragment;
import com.yingtu.plus.ui.fragment.MidnightFragment;
import com.yingtu.plus.util.OkGo;
import com.yingtu.plus.utils.ResUtil;
import com.yingtu.plus.util.ZoneSites;
import com.google.android.material.chip.Chip;

import java.util.ArrayList;
import java.util.List;

/**
 * 首页外壳。顶栏：头像 + 站点名 + 居中搜索框 + 历史/收藏/放映/网页/设置五个图标；
 * 顶栏下方是常驻区域 Tab 行（影视 / 短剧 / 直播 / 午夜 / 文件，点 Tab 切区），
 * 再下方是内容区（全屏，底部导航已删）。六个工具行（快搜 / 收藏 / 应用 / 网盘 /
 * 推送 / 节目单）收进左侧边栏（第十批，标准模态抽屉）：顶栏左端头像开栏、左缘向右
 * 划入，点遮罩或左滑收起，不自动收回；点行先关栏再动作。
 * 点击标题弹出站源选择小窗（带搜索 + 分类筛选）。
 * 点击标题/Logo 弹出站源选择小窗（带搜索 + 分类筛选）。
 * 影视区只列用户自己导入的站：内置站（午夜区、短剧区的内置配置站）由
 * ApiConfig.isBuiltinSite(key) 判定后过滤，该方法是 H 批（午夜区）在 ApiConfig 里加的。
 */
public class HomeActivity extends BaseActivity {

    /**
     * 兼容 HomeWebBridge.openKeep()：它用 KEY_TAB=4 打开“分享收藏”。
     * 顶部 tab 已删，只剩 4（收藏）还有效，其余值忽略。
     */
    public static final String KEY_TAB = "tab";
    /** 内置区列表点站进来时用：要打开的侧边栏去向（NAV_MOVIE / NAV_DRAMA / NAV_MIDNIGHT）。 */
    public static final String EXTRA_NAV_TAB = "nav_tab";
    /** 内置区列表点站进来时用：要进的站 key，分区页在 onVisible 里认领。 */
    public static final String EXTRA_SITE_KEY = "site_key";

    public static final int NAV_MOVIE = 1001;
    public static final int NAV_DRAMA = 1002;
    public static final int NAV_LIVE = 1003;
    public static final int NAV_MIDNIGHT = 1004;
    public static final int NAV_FILE = 1005;
    /** 第九批第二路：三个独立页直达（快搜 / 收藏 / 应用），接法与直播/文件同一路。 */
    public static final int NAV_FASTSEARCH = 1006;
    public static final int NAV_COLLECT = 1007;
    public static final int NAV_APPS = 1008;
    /** 第九批第三路：三个独立页直达（网盘 / 推送 / 节目单），接法与直播/文件同一路。 */
    public static final int NAV_DRIVE = 1009;
    public static final int NAV_PUSH = 1010;
    public static final int NAV_EPG = 1011;

    private TextView tvTitle, tvSourceCount;
    private SourceGridAdapter sourceAdapter;
    private PopupWindow sourcePopup;
    private EditText etSearch;
    private LinearLayout chipContainer;
    // 左侧边栏（第十批）：面板 / 遮罩 / 左缘划入区 + 六个工具行；区域 Tab 行在顶栏下方常驻。
    private View sidebarPanel;
    private View sidebarScrim;
    private TextView tvSbVersion;
    private TextView tvSbFastSearch, tvSbCollect, tvSbApps;
    private TextView tvSbDrive, tvSbPush, tvSbEpg;
    private View sbFastSearchRow, sbCollectRow, sbAppsRow;
    private View sbDriveRow, sbPushRow, sbEpgRow;
    private View tabMovieRow, tabDramaRow, tabLiveRow, tabMidnightRow, tabFileRow;
    private TextView tvTabMovie, tvTabDrama, tvTabLive, tvTabMidnight, tvTabFile;
    private ImageView ivTabMovie, ivTabDrama, ivTabLive, ivTabMidnight, ivTabFile;
    private boolean sidebarOpen = false;
    private static final int SIDEBAR_WIDTH_DP = 304;
    private static final int EDGE_SWIPE_MIN_DX_DP = 40;
    private float edgeDownX;
    private float edgeDownY;
    private boolean edgeSwiping;
    private float sidebarDownX;
    private float sidebarDownY;
    private boolean sidebarSwiping;
    // 站源弹窗里的标题 / 网格 / 空态视图：每次打开按当前区重填（标题、站数、空态提示）。
    private TextView tvPopupTitle;
    private View rvSourceView;
    private View popupEmpty;
    private TextView tvPopupEmpty;
    private View btnPopupImport;

    private int currentNav = NAV_MOVIE;
    private boolean showingFav = false;

    @Override
    protected int getLayoutId() {
        return R.layout.activity_home;
    }

    @Override
    protected void init() {
        // 闪退加固（2.0.0.1108 第三轮）：启动链兜底，整段包 try/catch，
        // 任何漏网的异常都退化成空首页 + 一句提示，不杀进程。顺序与正常路径不变。
        try {
            // 应用自定义壁纸
            applyCustomWallpaper();
            tvTitle = find(R.id.tvTitle);

            // 顶栏：标题点开站源弹窗，左端头像点开左侧栏；搜索框进搜索页；右上角五个图标入口
            tvTitle.setOnClickListener(v -> openSourcePopup());
            find(R.id.ivLogo).setOnClickListener(v -> openSidebar());
            find(R.id.tvSearchBox).setOnClickListener(v ->
                    startActivity(new Intent(this, SearchActivity.class)));
            find(R.id.ivHistory).setOnClickListener(v ->
                    startActivity(new Intent(this, HistoryActivity.class)));
            find(R.id.ivFav).setOnClickListener(v -> showFav());
            find(R.id.ivCast).setOnClickListener(v ->
                    CastDialog.create().show(this));
            find(R.id.ivWeb).setOnClickListener(v -> {
                // 网页首页默认进内置示例主页：WebHomeActivity 没带 KEY_URL 会弹
                // “首页地址为空”并直接 finish，之前这里一个参数都没带。
                Intent web = new Intent(this, WebHomeActivity.class);
                web.putExtra(WebHomeActivity.KEY_URL, "file:///android_asset/webhome/showcase.html");
                web.putExtra(WebHomeActivity.KEY_TITLE, "网页首页");
                startActivity(web);
            });
            // 设置入口在顶栏右上角（底部导航已删，见 setupSidebar）。
            find(R.id.ivSetting).setOnClickListener(v ->
                    startActivity(new Intent(this, SettingActivity.class)));

            setupSidebar();
            // 各区启动站：设置里按区设好的启动站，冷启动时写进各区当前站。
            ZoneSites.applyStartupSites();
            showFragment(NAV_MOVIE);
            handleTabExtra(getIntent());

            setupSourcePopup();
            refreshTitle();
        } catch (Throwable e) {
            e.printStackTrace();
            com.yingtu.plus.App.logCaught(e);
            try {
                Toast.makeText(this, "首页加载失败，请重启应用", Toast.LENGTH_LONG).show();
            } catch (Throwable ignored) {}
        }
    }

    /** WebHome 桥接的 KEY_TAB=4 → 打开收藏页；其余旧 tab 值已废弃，忽略。
     * 内置区列表点站进来带 EXTRA_NAV_TAB：切到对应分区，站 key 留给分区页认领。 */
    private void handleTabExtra(Intent intent) {
        if (intent == null) return;
        if (intent.getIntExtra(KEY_TAB, -1) == 4) showFav();
        int navTab = intent.getIntExtra(EXTRA_NAV_TAB, -1);
        if (navTab == NAV_MOVIE || navTab == NAV_DRAMA || navTab == NAV_MIDNIGHT) {
            showFragment(navTab);
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // FLAG_ACTIVITY_CLEAR_TOP 回来的新 intent 要先换进来，分区页才读得到 EXTRA_SITE_KEY。
        setIntent(intent);
        handleTabExtra(intent);
    }

    /** 左侧边栏接线（第十批，标准模态抽屉）：顶栏左端头像 + 左缘向右划滑出面板，
     * 面板里六个工具行，点行先关栏再动作；点遮罩或面板左滑收回，不自动收回。
     * 区域 Tab 行在顶栏下方常驻，点 Tab 切区（与侧栏无关）。
     * 边缘划入只认最左 16dp（顶栏 56dp 不占），不抢内容区分类 chips 的横滑。 */
    private void setupSidebar() {
        sidebarPanel = find(R.id.sidebarPanel);
        sidebarScrim = find(R.id.sidebarScrim);
        sbFastSearchRow = find(R.id.sbFastSearch);
        sbCollectRow = find(R.id.sbCollect);
        sbAppsRow = find(R.id.sbApps);
        sbDriveRow = find(R.id.sbDrive);
        sbPushRow = find(R.id.sbPush);
        sbEpgRow = find(R.id.sbEpg);
        tvSbFastSearch = find(R.id.tvSbFastSearch);
        tvSbCollect = find(R.id.tvSbCollect);
        tvSbApps = find(R.id.tvSbApps);
        tvSbDrive = find(R.id.tvSbDrive);
        tvSbPush = find(R.id.tvSbPush);
        tvSbEpg = find(R.id.tvSbEpg);
        tvSbVersion = find(R.id.tvSbVersion);
        if (tvSbVersion != null) tvSbVersion.setText("v" + BuildConfig.VERSION_NAME);
        tabMovieRow = find(R.id.tabMovie);
        tabDramaRow = find(R.id.tabDrama);
        tabLiveRow = find(R.id.tabLive);
        tabMidnightRow = find(R.id.tabMidnight);
        tabFileRow = find(R.id.tabFile);
        tvTabMovie = find(R.id.tvTabMovie);
        tvTabDrama = find(R.id.tvTabDrama);
        tvTabLive = find(R.id.tvTabLive);
        tvTabMidnight = find(R.id.tvTabMidnight);
        tvTabFile = find(R.id.tvTabFile);
        ivTabMovie = find(R.id.ivTabMovie);
        ivTabDrama = find(R.id.ivTabDrama);
        ivTabLive = find(R.id.ivTabLive);
        ivTabMidnight = find(R.id.ivTabMidnight);
        ivTabFile = find(R.id.ivTabFile);

        if (sidebarScrim != null) sidebarScrim.setOnClickListener(v -> closeSidebar());
        if (sidebarPanel != null) {
            sidebarPanel.setOnTouchListener((v, e) -> {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        sidebarDownX = e.getX();
                        sidebarDownY = e.getY();
                        sidebarSwiping = false;
                        break;
                    case MotionEvent.ACTION_MOVE:
                        if (!sidebarSwiping) {
                            float dx = e.getX() - sidebarDownX;
                            float dy = e.getY() - sidebarDownY;
                            if (dx < -ResUtil.dp2px(EDGE_SWIPE_MIN_DX_DP)
                                    && Math.abs(dx) > Math.abs(dy) * 2) {
                                sidebarSwiping = true;
                                closeSidebar();
                            }
                        }
                        break;
                    default:
                        break;
                }
                return false;
            });
        }
        if (sbFastSearchRow != null) sbFastSearchRow.setOnClickListener(v -> onNavGo(NAV_FASTSEARCH));
        if (sbCollectRow != null) sbCollectRow.setOnClickListener(v -> onNavGo(NAV_COLLECT));
        if (sbAppsRow != null) sbAppsRow.setOnClickListener(v -> onNavGo(NAV_APPS));
        if (sbDriveRow != null) sbDriveRow.setOnClickListener(v -> onNavGo(NAV_DRIVE));
        if (sbPushRow != null) sbPushRow.setOnClickListener(v -> onNavGo(NAV_PUSH));
        if (sbEpgRow != null) sbEpgRow.setOnClickListener(v -> onNavGo(NAV_EPG));
        if (tabMovieRow != null) tabMovieRow.setOnClickListener(v -> onNavGo(NAV_MOVIE));
        if (tabDramaRow != null) tabDramaRow.setOnClickListener(v -> onNavGo(NAV_DRAMA));
        if (tabLiveRow != null) tabLiveRow.setOnClickListener(v -> onNavGo(NAV_LIVE));
        if (tabMidnightRow != null) tabMidnightRow.setOnClickListener(v -> onNavGo(NAV_MIDNIGHT));
        if (tabFileRow != null) tabFileRow.setOnClickListener(v -> onNavGo(NAV_FILE));

        View edgeZone = find(R.id.edgeSwipeZone);
        if (edgeZone != null) {
            final int minDx = ResUtil.dp2px(EDGE_SWIPE_MIN_DX_DP);
            edgeZone.setOnTouchListener((v, e) -> {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        edgeDownX = e.getX();
                        edgeDownY = e.getY();
                        edgeSwiping = false;
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        if (!edgeSwiping) {
                            float dx = e.getX() - edgeDownX;
                            float dy = e.getY() - edgeDownY;
                            if (dx > minDx && Math.abs(dx) > Math.abs(dy) * 2) {
                                edgeSwiping = true;
                                openSidebar();
                            }
                        }
                        return true;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        return true;
                    default:
                        return true;
                }
            });
        }
    }

    /** 侧边栏滑出：遮罩露出来，面板从左边缘滑进来。 */
    private void openSidebar() {
        if (sidebarOpen) return;
        if (sidebarPanel == null || sidebarScrim == null) return;
        sidebarOpen = true;
        sidebarScrim.setVisibility(View.VISIBLE);
        sidebarPanel.setVisibility(View.VISIBLE);
        sidebarPanel.setTranslationX(-ResUtil.dp2px(SIDEBAR_WIDTH_DP));
        sidebarPanel.animate().translationX(0).setDuration(250).start();
    }

    /** 侧边栏收回：面板滑回屏幕外再藏掉，遮罩藏掉。 */
    private void closeSidebar() {
        if (!sidebarOpen) return;
        sidebarOpen = false;
        if (sidebarPanel == null || sidebarScrim == null) return;
        sidebarPanel.animate().translationX(-ResUtil.dp2px(SIDEBAR_WIDTH_DP)).setDuration(250)
                .withEndAction(() -> {
                    if (sidebarPanel != null) sidebarPanel.setVisibility(View.GONE);
                }).start();
        sidebarScrim.setVisibility(View.GONE);
    }

    /** 点一个去向：先收回面板；直播/文件/快搜/收藏/应用是独立页直接打开，其余切内容区。
     * 切区逻辑与原来底部导航那路一致（showFragment），顶栏站名与各区站点弹窗
     * 仍按当前选中去向所在区来。 */
    private void onNavGo(int navId) {
        closeSidebar();
        if (navId == NAV_LIVE) {
            startActivity(new Intent(this, LiveActivity.class));
            return;
        }
        if (navId == NAV_FILE) {
            startActivity(new Intent(this, FileActivity.class));
            return;
        }
        if (navId == NAV_FASTSEARCH) {
            startActivity(new Intent(this, FastSearchActivity.class));
            return;
        }
        if (navId == NAV_COLLECT) {
            startActivity(new Intent(this, CollectActivity.class));
            return;
        }
        if (navId == NAV_APPS) {
            startActivity(new Intent(this, AppsActivity.class));
            return;
        }
        if (navId == NAV_DRIVE) {
            startActivity(new Intent(this, DriveActivity.class));
            return;
        }
        if (navId == NAV_PUSH) {
            startActivity(new Intent(this, PushActivity.class));
            return;
        }
        if (navId == NAV_EPG) {
            startActivity(new Intent(this, EpgActivity.class));
            return;
        }
        showFragment(navId);
    }

    /** 区域 Tab 行当前区高亮：选中 Tab 用胶囊底 + 白字白图标，其余常规色；
     * 直播/文件是独立页，点后不在 Tab 行标当前（切走后 Tab 行仍按内容区当前区标）。 */
    private void updateTabSelected(int navId) {
        if (tvTabMovie == null) return;
        setTabRow(tabMovieRow, tvTabMovie, ivTabMovie, navId == NAV_MOVIE);
        setTabRow(tabDramaRow, tvTabDrama, ivTabDrama, navId == NAV_DRAMA);
        setTabRow(tabLiveRow, tvTabLive, ivTabLive, false);
        setTabRow(tabMidnightRow, tvTabMidnight, ivTabMidnight, navId == NAV_MIDNIGHT);
        setTabRow(tabFileRow, tvTabFile, ivTabFile, false);
    }

    private void setTabRow(View row, TextView tv, ImageView iv, boolean selected) {
        if (row == null || tv == null || iv == null) return;
        row.setSelected(selected);
        int color = getColor(selected ? android.R.color.white : R.color.text_primary);
        tv.setTextColor(color);
        tv.setTypeface(null, selected ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
        iv.setImageTintList(ColorStateList.valueOf(color));
    }

    private void showFragment(int navId) {
        try {
            currentNav = navId;
            showingFav = false;
            // 区域 Tab 行当前区高亮跟着切（从内置区列表、返回键等路子切过来也一样）。
            updateTabSelected(navId);
            // 切 tab 就按新区刷新顶栏站名（之前顶栏一直显示全局当前源，
            // 在午夜区会错显示短剧站名）。
            refreshTitle();
            String tag;
            Fragment fragment;
            if (navId == NAV_DRAMA) {
                tag = "drama";
                fragment = getSupportFragmentManager().findFragmentByTag(tag);
                if (fragment == null) fragment = new DramaFragment();
            } else if (navId == NAV_MIDNIGHT) {
                tag = "midnight";
                fragment = getSupportFragmentManager().findFragmentByTag(tag);
                if (fragment == null) fragment = new MidnightFragment();
            } else {
                tag = "home";
                fragment = getSupportFragmentManager().findFragmentByTag(tag);
                if (fragment == null) fragment = new HomeMainFragment();
            }
            getSupportFragmentManager().beginTransaction()
                    .replace(R.id.fragmentContainer, fragment, tag)
                    .commit();
        } catch (Throwable e) {
            e.printStackTrace();
            com.yingtu.plus.App.logCaught(e);
            try {
                Toast.makeText(this, "页面切换失败，请重启应用", Toast.LENGTH_LONG).show();
            } catch (Throwable ignored) {}
        }
    }

    /** 顶栏收藏图标：收藏页（FavVodFragment）直接宿在首页内容区。 */
    private void showFav() {
        showingFav = true;
        Fragment fragment = getSupportFragmentManager().findFragmentByTag("fav");
        if (fragment == null) fragment = new FavVodFragment();
        getSupportFragmentManager().beginTransaction()
                .replace(R.id.fragmentContainer, fragment, "fav")
                .commit();
    }

    private void setupSourcePopup() {
        View popupView = LayoutInflater.from(this)
                .inflate(R.layout.dialog_source_picker, null);

        sourcePopup = new PopupWindow(popupView,
                (int) (getResources().getDisplayMetrics().widthPixels * 0.68),
                ViewGroup.LayoutParams.WRAP_CONTENT, true);
        sourcePopup.setOutsideTouchable(true);
        sourcePopup.setBackgroundDrawable(null);
        sourcePopup.setAnimationStyle(android.R.style.Animation_Dialog);

        etSearch = popupView.findViewById(R.id.etSearch);
        chipContainer = popupView.findViewById(R.id.chipContainer);
        tvSourceCount = popupView.findViewById(R.id.tvSourceCount);
        tvPopupTitle = popupView.findViewById(R.id.tvPopupTitle);
        rvSourceView = popupView.findViewById(R.id.rvSource);
        popupEmpty = popupView.findViewById(R.id.popupEmpty);
        tvPopupEmpty = popupView.findViewById(R.id.tvPopupEmpty);
        btnPopupImport = popupView.findViewById(R.id.btnPopupImport);

        androidx.recyclerview.widget.RecyclerView rv = popupView.findViewById(R.id.rvSource);
        rv.setLayoutManager(new GridLayoutManager(this, 2));
        sourceAdapter = new SourceGridAdapter();
        sourceAdapter.setOnSourceClick(this::onSourcePicked);
        rv.setAdapter(sourceAdapter);

        // 构建 chip
        buildChips();

        // 搜索
        etSearch.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable s) {
                sourceAdapter.setSearch(s.toString());
            }
        });

        // 管理源按钮
        popupView.findViewById(R.id.btnManageSource).setOnClickListener(v -> {
            if (sourcePopup.isShowing()) sourcePopup.dismiss();
            startActivity(new Intent(this, SourceActivity.class));
        });

        // 空态里的“去导入”按钮：用户自己点才跳订阅页，绝不自动跳。
        if (btnPopupImport != null) {
            btnPopupImport.setOnClickListener(v -> {
                if (sourcePopup.isShowing()) sourcePopup.dismiss();
                startActivity(new Intent(this, SourceActivity.class));
            });
        }
    }

    private void buildChips() {
        chipContainer.removeAllViews();
        final List<String> chips = sourceAdapter.getChipList();
        final String currentChip = sourceAdapter.getFilterChip();
        for (int i = 0; i < chips.size(); i++) {
            final String chipText = chips.get(i);
            final boolean isChecked = chipText.equals(currentChip);
            Chip c = new Chip(this);
            c.setText(chipText);
            c.setCheckable(true);
            c.setChecked(isChecked);
            c.setOnClickListener(v -> {
                sourceAdapter.setFilterChip(chipText);
                // Rebuild chips to update checked states
                buildChips();
            });
            chipContainer.addView(c);
        }
    }

    private void openSourcePopup() {
        // 闪退防御（2.0.0.1108 第四轮补）：setupSourcePopup() 在 init() 里跑，
        // init 任何一步失败都会留下一堆 null 字段，点站名/图标就空指针。
        // 先判空，懒执行一次还不行就直接跳站源管理页，绝不在这里崩。
        if (sourcePopup == null || sourceAdapter == null) {
            try {
                setupSourcePopup();
            } catch (Throwable e) {
                e.printStackTrace();
                com.yingtu.plus.App.logCaught(e);
            }
        }
        if (sourcePopup == null || sourceAdapter == null) {
            startActivity(new Intent(this, SourceActivity.class));
            return;
        }
        // 按当前所在区分区列站：午夜列午夜站、短剧列短剧站 + 番茄短剧、
        // 影视列用户自己导入的站（内置站不进影视区，老口径不动）。
        String zone = zoneOf(currentNav);
        List<SourceBean> sources = ZoneSites.getZoneSites(zone);
        if (currentNav == NAV_MOVIE) {
            sourceAdapter.setOnSourceClick(this::onSourcePicked);
            if (tvPopupTitle != null) tvPopupTitle.setText("站源选择");
            // 影视区没自有站是新装常态：弹窗里给空态提示 +“去导入”按钮，
            // 用户自己点才跳订阅页，绝不自动跳。
            setPopupEmptyState(sources.isEmpty(),
                    "影视区暂无自有站点：内置站都在午夜/短剧区，影视区只列你自己导入的站",
                    true);
            sourceAdapter.setCurrentKey(ZoneSites.getCurrentSiteKey(ZoneSites.ZONE_MOVIE));
            if (tvSourceCount != null) tvSourceCount.setText(sources.size() + " 个源");
        } else {
            sourceAdapter.setOnSourceClick(s -> onZoneSourcePicked(zone, s));
            if (tvPopupTitle != null) {
                tvPopupTitle.setText(currentNav == NAV_DRAMA ? "短剧站点" : "午夜站点");
            }
            setPopupEmptyState(sources.isEmpty(), "该区暂无站点", false);
            sourceAdapter.setCurrentKey(ZoneSites.getCurrentSiteKey(zone));
            if (tvSourceCount != null) tvSourceCount.setText(sources.size() + " 个站");
        }
        sourceAdapter.submit(sources);
        if (etSearch != null) etSearch.setText("");
        sourceAdapter.setSearch("");
        sourceAdapter.setFilterChip("全部");
        buildChips();

        if (sourcePopup.isShowing()) {
            sourcePopup.dismiss();
        } else {
            // 弹窗改窄（2.0.0.1108 第五轮）：宽度按弹出时的实际屏宽算，不用 setup
            // 时的 DisplayMetrics（那份在个别机型上会取到横屏长边，0.8 也会被压成满宽）。
            View content = find(android.R.id.content);
            int screenW = content != null && content.getWidth() > 0
                    ? content.getWidth()
                    : getResources().getDisplayMetrics().widthPixels;
            sourcePopup.setWidth((int) (screenW * 0.68));
            sourcePopup.showAtLocation(find(android.R.id.content),
                    Gravity.TOP | Gravity.CENTER_HORIZONTAL,
                    0, 100);
        }
    }

    private void onSourcePicked(SourceBean source) {
        ApiConfig.get().setSourceBean(source);
        // 影视区独立记站：这次选的站记进影视区键，午夜/短剧区的站不动。
        ZoneSites.setCurrentSiteKey(ZoneSites.ZONE_MOVIE, source.getKey());
        // J 批：同配置换当前源，同步 VodConfig 的 home 站（站表本身不变，
        // replaceSites 整体替换，不叠加；jar 已加载过，parseJar 直接返回）
        VodConfigSync.sync();
        sourceAdapter.setCurrentKey(source.getKey());
        final String jar = ApiConfig.get().getSpider();
        if (jar != null && !jar.isEmpty()) {
            OkGo.io(() -> com.yingtu.plus.api.SpiderManager.get().parseJar(jar));
        }
        refreshTitle();
        if (sourcePopup.isShowing()) sourcePopup.dismiss();
        showFragment(NAV_MOVIE);
        notifyHomeReload();
    }

    /** 分区弹窗里点站：只换本区的站，其他区不动；不切 tab，留在本区看内容。 */
    private void onZoneSourcePicked(String zone, SourceBean source) {
        if (source == null) return;
        ZoneSites.setCurrentSiteKey(zone, source.getKey());
        if (ZoneSites.ZONE_DRAMA.equals(zone)) {
            Fragment f = getSupportFragmentManager().findFragmentByTag("drama");
            if (f instanceof DramaFragment) ((DramaFragment) f).pickSite(source);
        } else if (ZoneSites.ZONE_MIDNIGHT.equals(zone)) {
            Fragment f = getSupportFragmentManager().findFragmentByTag("midnight");
            if (f instanceof MidnightFragment) ((MidnightFragment) f).pickSite(source);
        }
        refreshTitle();
        if (sourcePopup != null && sourcePopup.isShowing()) sourcePopup.dismiss();
    }

    /** 当前底部 tab 对应的分区键。 */
    private static String zoneOf(int navId) {
        if (navId == NAV_DRAMA) return ZoneSites.ZONE_DRAMA;
        if (navId == NAV_MIDNIGHT) return ZoneSites.ZONE_MIDNIGHT;
        return ZoneSites.ZONE_MOVIE;
    }

    /** 弹窗空态：列表空时藏起网格/搜索/chip，只显示提示 +（影视区）“去导入”按钮。 */
    private void setPopupEmptyState(boolean empty, String hint, boolean showImport) {
        if (rvSourceView != null) rvSourceView.setVisibility(empty ? View.GONE : View.VISIBLE);
        if (popupEmpty != null) popupEmpty.setVisibility(empty ? View.VISIBLE : View.GONE);
        if (tvPopupEmpty != null) tvPopupEmpty.setText(hint);
        if (btnPopupImport != null) btnPopupImport.setVisibility(showImport ? View.VISIBLE : View.GONE);
        if (etSearch != null) etSearch.setVisibility(empty ? View.GONE : View.VISIBLE);
        if (chipContainer != null) chipContainer.setVisibility(empty ? View.GONE : View.VISIBLE);
    }

    private void notifyHomeReload() {
        Fragment f = getSupportFragmentManager().findFragmentByTag("home");
        if (f instanceof HomeMainFragment) {
            ((HomeMainFragment) f).reloadForSourceChange();
        }
    }

    private void refreshTitle() {
        // init 失败后 tvTitle 可能还没拿到手，onResume 还会调这里，先守住空指针
        if (tvTitle == null) return;
        // 顶栏显示当前所在区的站名，不再显示全局当前源（之前在午夜区会错显示短剧站名）。
        // Hawk 没起来时 ZoneSites 会抛，接住只显示“请选择源”，不拖死切 tab。
        String name = null;
        try {
            name = ZoneSites.getCurrentSiteName(zoneOf(currentNav));
        } catch (Throwable ignored) {}
        tvTitle.setText(name != null ? name : getString(R.string.source_default));
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshTitle();
    }

    @Override
    public void onBackPressed() {
        if (sidebarOpen) {
            closeSidebar();
        } else if (sourcePopup != null && sourcePopup.isShowing()) {
            sourcePopup.dismiss();
        } else if (showingFav || currentNav != NAV_MOVIE) {
            showFragment(NAV_MOVIE);
        } else {
            super.onBackPressed();
        }
    }

    private void applyCustomWallpaper() {
        // 包一层：Hawk 没起来时 getWallpaperUri 会抛异常，接住只丢壁纸，不闪退
        android.net.Uri uri;
        try {
            uri = com.yingtu.plus.util.WallpaperHelper.getWallpaperUri(this);
        } catch (Throwable ignored) {
            return;
        }
        if (uri != null) {
            try {
                android.graphics.Bitmap bmp =
                        android.graphics.BitmapFactory.decodeStream(
                                getContentResolver().openInputStream(uri));
                if (bmp != null) {
                    getWindow().setBackgroundDrawable(
                            new android.graphics.drawable.BitmapDrawable(bmp));
                }
            } catch (Exception ignored) {}
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (sourcePopup != null && sourcePopup.isShowing()) sourcePopup.dismiss();
    }

    /** 短剧 / 午夜区的临时占位页：等对应批次接手后替换成真正的页。 */
    public static class PlaceholderFragment extends Fragment {
        private static final String ARG_TEXT = "text";

        public static PlaceholderFragment newInstance(String text) {
            PlaceholderFragment f = new PlaceholderFragment();
            Bundle args = new Bundle();
            args.putString(ARG_TEXT, text);
            f.setArguments(args);
            return f;
        }

        @Nullable
        @Override
        public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                                 @Nullable Bundle savedInstanceState) {
            TextView tv = new TextView(requireContext());
            String text = getArguments() != null ? getArguments().getString(ARG_TEXT, "") : "";
            tv.setText(text);
            tv.setGravity(Gravity.CENTER);
            tv.setTextColor(getResources().getColor(R.color.text_secondary));
            tv.setTextSize(14);
            return tv;
        }
    }
}
