package com.yingtu.plus.ui.activity;

import android.content.Intent;
import android.content.res.ColorStateList;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.LayoutInflater;
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
import androidx.recyclerview.widget.RecyclerView;

import com.yingtu.plus.R;
import com.yingtu.plus.api.ApiConfig;
import com.yingtu.plus.api.VodConfigSync;
import com.yingtu.plus.base.BaseActivity;
import com.yingtu.plus.bean.SourceBean;
import com.yingtu.plus.ui.adapter.SourceGridAdapter;
import com.yingtu.plus.ui.fragment.ChannelFragment;
import com.yingtu.plus.ui.fragment.HomeMainFragment;
import com.yingtu.plus.ui.fragment.ListFragment;
import com.yingtu.plus.ui.fragment.MineFragment;
import com.yingtu.plus.util.HawkConfig;
import com.yingtu.plus.util.OkGo;
import com.yingtu.plus.util.SiteFilter;
import com.yingtu.plus.util.SourceMsgGate;
import com.yingtu.plus.util.ZoneSites;
import com.orhanobut.hawk.Hawk;
import com.google.android.material.chip.Chip;

import java.util.ArrayList;
import java.util.List;

/**
 * 首页外壳（潮影霓虹主题）。顶栏：潮影标题 + 搜索 + 消息；
 * 底部悬浮导航：首页 / 频道 / 直播（中央圆钮，点开 LiveActivity）/ 片单 / 我的；
 * 加站入口挪到“我的”页（加站行，进一键写 PY）。
 * 内容区（fragmentContainer）按底栏切四个片段：首页 HomeMainFragment、
 * 频道 ChannelFragment（group 参数 all/drama/midnight）、片单 ListFragment、
 * 我的 MineFragment。点击标题不再弹站源弹窗，站源弹窗改由各分区页
 * 点站名经 openSourcePopupForZone() 打开。
 */
public class HomeActivity extends BaseActivity {

    /** WebHome 桥接的 KEY_TAB 键名（HomeWebBridge.openKeep 沿用，收藏 Tab 已随侧边栏撤下）。 */
    public static final String KEY_TAB = "tab";
    /** 内置区列表点站进来时用：要打开的去向（NAV_MOVIE / NAV_DRAMA / NAV_MIDNIGHT）。 */
    public static final String EXTRA_NAV_TAB = "nav_tab";
    /** 内置区列表点站进来时用：要进的站 key，分区页在 onVisible 里认领。 */
    public static final String EXTRA_SITE_KEY = "site_key";

    public static final int NAV_MOVIE = 1001;
    public static final int NAV_DRAMA = 1002;
    public static final int NAV_LIVE = 1003;
    public static final int NAV_MIDNIGHT = 1004;
    public static final int NAV_FILE = 1005;
    /** 片单（底栏）。 */
    public static final int NAV_LIST = 1006;
    /** 我的（底栏）。 */
    public static final int NAV_MINE = 1007;
    /** 频道·默认组（底栏，ChannelFragment group="all"）。 */
    public static final int NAV_CHANNEL = 1010;

    private TextView tvSourceCount;
    private SourceGridAdapter sourceAdapter;
    private PopupWindow sourcePopup;
    private EditText etSearch;
    private LinearLayout chipContainer;
    // 站源弹窗里的标题 / 网格 / 空态视图：每次打开按当前区重填（标题、站数、空态提示）。
    private TextView tvPopupTitle;
    private View rvSourceView;
    private View popupEmpty;
    private TextView tvPopupEmpty;
    private View btnPopupImport;

    private int currentNav = NAV_MOVIE;
    private RecyclerView rvSourceGrid;

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
            // 顶栏：标题静态显示“潮影”，不点；搜索进搜索页，消息进推送页。
            TextView tvAppTitle = find(R.id.tvAppTitle);
            if (tvAppTitle != null) tvAppTitle.setText("潮影");
            View btnSearch = find(R.id.btnSearch);
            if (btnSearch != null) btnSearch.setOnClickListener(v ->
                    startActivity(new Intent(this, SearchActivity.class)));
            View btnMessage = find(R.id.btnMessage);
            if (btnMessage != null) btnMessage.setOnClickListener(v ->
                    startActivity(new Intent(this, PushActivity.class)));
            // 底部悬浮导航：首页 / 频道 / 直播 / 片单 / 我的。
            View navHome = find(R.id.navHome);
            if (navHome != null) navHome.setOnClickListener(v -> showFragment(NAV_MOVIE));
            View navChannel = find(R.id.navChannel);
            if (navChannel != null) navChannel.setOnClickListener(v -> showFragment(NAV_CHANNEL));
            // 中央圆钮不再是加站，改成点开直播；加站入口在“我的”页。
            View navAdd = find(R.id.navAdd);
            if (navAdd != null) navAdd.setOnClickListener(v ->
                    startActivity(new Intent(this, LiveActivity.class)));
            View navList = find(R.id.navList);
            if (navList != null) navList.setOnClickListener(v -> showFragment(NAV_LIST));
            View navMine = find(R.id.navMine);
            if (navMine != null) navMine.setOnClickListener(v -> showFragment(NAV_MINE));

            // 各区启动站：设置里按区设好的启动站，冷启动时写进各区当前站。
            ZoneSites.applyStartupSites();
            showFragment(NAV_MOVIE);
            handleTabExtra(getIntent());

            setupSourcePopup();
        } catch (Throwable e) {
            e.printStackTrace();
            com.yingtu.plus.App.logCaught(e);
            try {
                SourceMsgGate.error(this, "首页加载失败，请重启应用");
            } catch (Throwable ignored) {}
        }
    }

    /** 内置区列表点站进来带 EXTRA_NAV_TAB：切到对应分区，站 key 留给分区页认领
     * （分区页从本 Activity 的 intent 里读 EXTRA_SITE_KEY）。
     * 直播 / 文件是独立页，直接打开。 */
    private void handleTabExtra(Intent intent) {
        if (intent == null) return;
        int navTab = intent.getIntExtra(EXTRA_NAV_TAB, -1);
        if (navTab == NAV_MOVIE || navTab == NAV_DRAMA || navTab == NAV_MIDNIGHT) {
            showFragment(navTab);
        } else if (navTab == NAV_LIVE) {
            startActivity(new Intent(this, LiveActivity.class));
        } else if (navTab == NAV_FILE) {
            startActivity(new Intent(this, FileActivity.class));
        }
        // WebHome 桥接 openKeep 发的 KEY_TAB=4（分享收藏）：收藏已并入“片单”Tab。
        if (intent.getIntExtra(KEY_TAB, -1) == 4) {
            showFragment(NAV_LIST);
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // FLAG_ACTIVITY_CLEAR_TOP 回来的新 intent 要先换进来，分区页才读得到 EXTRA_SITE_KEY。
        setIntent(intent);
        handleTabExtra(intent);
    }

    /** 底部导航当前项高亮：选中项图标与文字 tint 霓虹粉，其余暗紫灰；
     * 频道组（默认 / 短剧 / 午夜）都算“频道”选中；中央直播钮不参与高亮。 */
    private void updateBottomNav(int navId) {
        setNavItem(find(R.id.ivNavHome), find(R.id.tvNavHome), navId == NAV_MOVIE);
        setNavItem(find(R.id.ivNavChannel), find(R.id.tvNavChannel),
                navId == NAV_CHANNEL || navId == NAV_DRAMA || navId == NAV_MIDNIGHT);
        setNavItem(find(R.id.ivNavList), find(R.id.tvNavList), navId == NAV_LIST);
        setNavItem(find(R.id.ivNavMine), find(R.id.tvNavMine), navId == NAV_MINE);
    }

    private void setNavItem(ImageView iv, TextView tv, boolean selected) {
        if (iv == null || tv == null) return;
        int color = getColor(selected ? R.color.neon_pink : R.color.text_muted);
        tv.setTextColor(color);
        tv.setTypeface(null, selected ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
        iv.setImageTintList(ColorStateList.valueOf(color));
    }

    private void showFragment(int navId) {
        try {
            currentNav = navId;
            updateBottomNav(navId);
            String tag;
            Fragment fragment;
            if (navId == NAV_CHANNEL || navId == NAV_DRAMA || navId == NAV_MIDNIGHT) {
                tag = "channel";
                String group = navId == NAV_DRAMA ? "drama"
                        : navId == NAV_MIDNIGHT ? "midnight" : "all";
                fragment = getSupportFragmentManager().findFragmentByTag(tag);
                boolean reuse = false;
                if (fragment instanceof ChannelFragment) {
                    Bundle args = fragment.getArguments();
                    reuse = group.equals(args != null ? args.getString("group", "") : "");
                }
                if (!reuse) {
                    ChannelFragment cf = new ChannelFragment();
                    Bundle args = new Bundle();
                    args.putString("group", group);
                    cf.setArguments(args);
                    fragment = cf;
                }
            } else if (navId == NAV_LIST) {
                tag = "list";
                fragment = getSupportFragmentManager().findFragmentByTag(tag);
                if (fragment == null) fragment = new ListFragment();
            } else if (navId == NAV_MINE) {
                tag = "mine";
                fragment = getSupportFragmentManager().findFragmentByTag(tag);
                if (fragment == null) fragment = new MineFragment();
            } else {
                tag = "home";
                fragment = getSupportFragmentManager().findFragmentByTag(tag);
                if (fragment == null) fragment = new HomeMainFragment();
            }
            // 切区必须当下就生效：commit() 在状态已保存时抛错（被 catch 吃掉就只剩高亮变了、
            // 内容不动），且异步执行还依赖主线程消息队列；改成允许状态丢失 + 立刻同步执行，
            // 点 tab 的同一帧里分区就换过去，不靠下一轮消息。
            getSupportFragmentManager().beginTransaction()
                    .replace(R.id.fragmentContainer, fragment, tag)
                    .commitAllowingStateLoss();
            getSupportFragmentManager().executePendingTransactions();
        } catch (Throwable e) {
            e.printStackTrace();
            com.yingtu.plus.App.logCaught(e);
            try {
                SourceMsgGate.error(this, "页面切换失败，请重启应用");
            } catch (Throwable ignored) {}
        }
    }

    /** 首页“热播推荐”右上角“全部 >”：切到频道页（影视组），站与首页同站
     *（频道页按影视区当前站解析，与首页同一站）。 */
    public void openChannelAll() {
        showFragment(NAV_CHANNEL);
    }

    /** 供 ChannelFragment 点站名切站：按指定分区弹出选站弹窗。
     * openSourcePopup() 按 currentNav 取区（zoneOf），所以先把 currentNav
     * 临时换成该区对应的导航值再打开，弹窗里已按该区取好站并 captured
     * zone，打开后立即恢复 currentNav；"all" 不对应独立分区，
     * 按 openSourcePopup 原有默认走影视区。 */
    public void openSourcePopupForZone(String zone) {
        int savedNav = currentNav;
        if (ZoneSites.ZONE_DRAMA.equals(zone)) currentNav = NAV_DRAMA;
        else if (ZoneSites.ZONE_MIDNIGHT.equals(zone)) currentNav = NAV_MIDNIGHT;
        else if (ZoneSites.ZONE_MOVIE.equals(zone)) currentNav = NAV_MOVIE;
        try {
            openSourcePopup();
        } finally {
            currentNav = savedNav;
        }
    }

    private void setupSourcePopup() {
        View popupView = LayoutInflater.from(this)
                .inflate(R.layout.dialog_source_picker, null);

        sourcePopup = new PopupWindow(popupView,
                (int) (getResources().getDisplayMetrics().widthPixels * 0.90),
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
        rvSourceGrid = rv;
        rv.setLayoutManager(new GridLayoutManager(this, sourcePopupColumns()));
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

        // Online website entry (globe icon): opens the online website manager.
        popupView.findViewById(R.id.btnManageSource).setOnClickListener(v -> {
            if (sourcePopup.isShowing()) sourcePopup.dismiss();
            startActivity(new Intent(this, OnlineSiteListActivity.class));
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
        List<SourceBean> sources = new ArrayList<>(ZoneSites.getZoneSites(zone));
        // 站源置顶 / 站源禁用（设置 → 首页和搜索）：置顶的排前面，被禁用的来源不列
        SiteFilter.sortPinnedFirst(sources);
        SiteFilter.filterDisabled(sources);
        // 首页选源列数（设置 → 首页和搜索）：打开时按当前设置重设列数
        if (rvSourceGrid != null) {
            rvSourceGrid.setLayoutManager(new GridLayoutManager(this, sourcePopupColumns()));
        }
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
            sourcePopup.setWidth((int) (screenW * 0.90));
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
        if (sourcePopup.isShowing()) sourcePopup.dismiss();
        showFragment(NAV_MOVIE);
        notifyHomeReload();
    }

    /** 分区弹窗里点站：只换本区的站，其他区不动；不切 tab，留在本区看内容。 */
    private void onZoneSourcePicked(String zone, SourceBean source) {
        // 切死站闪退加固：pickSite 里走片段事务 + 同步执行，任一步抛错
        // （含子片段 onVisible/loadHome）都不许杀进程，留在本区给一句提示。
        try {
            onZoneSourcePickedInner(zone, source);
        } catch (Throwable e) {
            e.printStackTrace();
            com.yingtu.plus.App.logCaught(e);
            try {
                Toast.makeText(this, "切站失败，请稍后重试", Toast.LENGTH_SHORT).show();
            } catch (Throwable ignored) {}
            try {
                if (sourcePopup != null && sourcePopup.isShowing()) sourcePopup.dismiss();
            } catch (Throwable ignored) {}
        }
    }

    private void onZoneSourcePickedInner(String zone, SourceBean source) {
        if (source == null) return;
        ZoneSites.setCurrentSiteKey(zone, source.getKey());
        ApiConfig.get().setSourceBean(source);
        Fragment f = getSupportFragmentManager().findFragmentByTag("channel");
        if (f instanceof ChannelFragment) {
            ((ChannelFragment) f).reloadForSourceChange(zone);
        } else {
            Fragment h = getSupportFragmentManager().findFragmentByTag("home");
            if (h instanceof HomeMainFragment) ((HomeMainFragment) h).reloadForSourceChange();
        }
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

    @Override
    protected void onResume() {
        super.onResume();
        // 标题栏一直显示开时回首页总显示顶栏；关时由内容区滑动控制
        if (isTopBarAlwaysVisible()) setTopBarVisible(true);
    }

    @Override
    public void onBackPressed() {
        if (currentNav != NAV_MOVIE) {
            showFragment(NAV_MOVIE);
        } else {
            super.onBackPressed();
        }
    }

    /** 首页选源列数（设置 → 首页和搜索）：1/2/3 列，默认 3 列。 */
    private int sourcePopupColumns() {
        try {
            int c = Hawk.get(HawkConfig.HOME_SOURCE_COLUMNS, 3);
            return c >= 1 && c <= 3 ? c : 3;
        } catch (Throwable e) {
            return 3;
        }
    }

    /** 首页标题栏一直显示（设置 → 更多设置）：关时内容下滑隐藏顶栏、上滑恢复（HomeMainFragment 调）。 */
    public void setTopBarVisible(boolean visible) {
        View topBar = find(R.id.topBar);
        if (topBar == null) return;
        topBar.setVisibility(visible ? View.VISIBLE : View.GONE);
    }

    public boolean isTopBarAlwaysVisible() {
        try {
            return Hawk.get(HawkConfig.HOME_TITLE_BAR, true);
        } catch (Throwable e) {
            return true;
        }
    }

    private void applyCustomWallpaper() {
        // 包一层：Hawk 没起来时读壁纸会抛异常，接住只丢壁纸，不闪退；
        // 解码结果进程内缓存（启动页已解过就直接复用，不重复解码）。
        try {
            android.graphics.Bitmap bmp =
                    com.yingtu.plus.util.WallpaperHelper.getWallpaperBitmap(this);
            if (bmp != null) {
                getWindow().setBackgroundDrawable(
                        new android.graphics.drawable.BitmapDrawable(bmp));
            }
        } catch (Throwable ignored) {
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
