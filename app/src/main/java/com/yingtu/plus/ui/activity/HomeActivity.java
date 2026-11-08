package com.yingtu.plus.ui.activity;

import android.content.Intent;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.GridLayoutManager;

import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.google.android.material.bottomnavigation.LabelVisibilityMode;
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
import com.google.android.material.chip.Chip;

import java.util.ArrayList;
import java.util.List;

/**
 * 首页外壳。顶栏：Logo + 站点名 + 居中搜索框 + 历史/收藏/放映/网页/设置五个图标；
 * 下方是内容区 + 底部导航（影视 / 短剧 / 直播 / 午夜 / 文件）。
 * 注意：BottomNavigationView 最多只能放 5 项（material 1.11.0 硬上限），
 * 第 6 项“设置”加不上，所以设置入口搬到了顶栏右上角（ivSetting）。
 * 点击标题/Logo 弹出站源选择小窗（带搜索 + 分类筛选）。
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
    /** 内置区列表点站进来时用：要打开的底部 tab（NAV_MOVIE / NAV_DRAMA / NAV_MIDNIGHT）。 */
    public static final String EXTRA_NAV_TAB = "nav_tab";
    /** 内置区列表点站进来时用：要进的站 key，分区页在 onVisible 里认领。 */
    public static final String EXTRA_SITE_KEY = "site_key";

    public static final int NAV_MOVIE = 1001;
    public static final int NAV_DRAMA = 1002;
    public static final int NAV_LIVE = 1003;
    public static final int NAV_MIDNIGHT = 1004;
    public static final int NAV_FILE = 1005;

    private TextView tvTitle, tvSourceCount;
    private BottomNavigationView bottomNav;
    private SourceGridAdapter sourceAdapter;
    private PopupWindow sourcePopup;
    private EditText etSearch;
    private LinearLayout chipContainer;

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
            bottomNav = find(R.id.bottomNav);

            // 顶栏：标题/Logo 点开站源弹窗；搜索框进搜索页；右上角五个图标入口
            tvTitle.setOnClickListener(v -> openSourcePopup());
            find(R.id.ivLogo).setOnClickListener(v -> openSourcePopup());
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
            // 设置入口在顶栏（底部导航最多 5 项，第 6 项加不上，见 setupBottomNav）。
            find(R.id.ivSetting).setOnClickListener(v ->
                    startActivity(new Intent(this, SettingActivity.class)));

            setupBottomNav();
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
     * 内置区列表点站进来带 EXTRA_NAV_TAB：切到对应分区 tab，站 key 留给分区页认领。 */
    private void handleTabExtra(Intent intent) {
        if (intent == null) return;
        if (intent.getIntExtra(KEY_TAB, -1) == 4) showFav();
        int navTab = intent.getIntExtra(EXTRA_NAV_TAB, -1);
        if (navTab == NAV_MOVIE || navTab == NAV_DRAMA || navTab == NAV_MIDNIGHT) {
            showFragment(navTab);
            bottomNav.setSelectedItemId(navTab);
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // FLAG_ACTIVITY_CLEAR_TOP 回来的新 intent 要先换进来，分区页才读得到 EXTRA_SITE_KEY。
        setIntent(intent);
        handleTabExtra(intent);
    }

    private void setupBottomNav() {
        // 底部导航栏颜色与主题背景一致（2.0.0.1108 第四轮追加）：
        // 主题背景是纵向渐变 #3AA6CC → #227FA6 → #145E7E（themes.xml 的
        // windowBackground=@drawable/bg_main_gradient），导航栏在屏幕最底部，
        // 那里正好是渐变末尾色 #145E7E，用它做纯色背景才与背景融为一体。
        // 注意不能只在布局里写 android:background：NavigationBarView 构造时
        // 会把纯色背景包进 MaterialShapeDrawable 再叠一层深色提亮，颜色偏亮；
        // 渐变 drawable 则会被整段压进导航栏高度，顶部发亮、接缝明显。
        // 代码里 setBackgroundColor 直接换掉构造时装的背景，颜色分毫不差。
        try {
            bottomNav.setBackgroundColor(getColor(R.color.gradient_bottom));
        } catch (Throwable e) {
            e.printStackTrace();
            com.yingtu.plus.App.logCaught(e);
        }
        // 闪退加固（2.0.0.1108 第四轮）：BottomNavigationView 最多只能放 5 项，
        // 第 6 项 menu.add 会抛 IllegalArgumentException，之前它拖死了整个 init
        //（监听没挂上、首页没加载）。先挂监听、再逐项加，每项各包 try/catch，
        // 某项坏了记一笔、继续加下一项，标签模式也包一层。
        bottomNav.setOnItemSelectedListener(item -> {
            int id = item.getItemId();
            if (id == NAV_LIVE) {
                startActivity(new Intent(this, LiveActivity.class));
                return false;
            }
            if (id == NAV_FILE) {
                startActivity(new Intent(this, FileActivity.class));
                return false;
            }
            showFragment(id);
            return true;
        });
        try {
            bottomNav.setLabelVisibilityMode(LabelVisibilityMode.LABEL_VISIBILITY_LABELED);
        } catch (Throwable e) {
            e.printStackTrace();
            com.yingtu.plus.App.logCaught(e);
        }
        addNavItem(NAV_MOVIE, "影视", R.drawable.ic_play_circle);
        addNavItem(NAV_DRAMA, "短剧", R.drawable.ic_play);
        addNavItem(NAV_LIVE, "直播", R.drawable.ic_live);
        addNavItem(NAV_MIDNIGHT, "午夜", R.drawable.ic_fav_on);
        addNavItem(NAV_FILE, "文件", R.drawable.ic_file);
        // “设置”不在这里加：BottomNavigationView 最多 5 项，第 6 项一加就抛，
        // 设置入口已搬到顶栏右上角（ivSetting），见 init()。
    }

    /** 底部导航加一项：坏了只记一笔（进 crash.log），不拖死整个导航和首页。 */
    private void addNavItem(int id, String title, int iconRes) {
        try {
            bottomNav.getMenu().add(Menu.NONE, id, Menu.NONE, title).setIcon(iconRes);
        } catch (Throwable e) {
            e.printStackTrace();
            com.yingtu.plus.App.logCaught(e);
        }
    }

    private void showFragment(int navId) {
        try {
            currentNav = navId;
            showingFav = false;
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
        // 影视区只列用户自己导入的站，内置站（午夜区、短剧区）不过滤进这里
        List<SourceBean> sources = new ArrayList<>();
        for (SourceBean s : ApiConfig.get().getSourceBeanList()) {
            if (!ApiConfig.get().isBuiltinSite(s.getKey())) sources.add(s);
        }
        if (sources.isEmpty()) {
            // 影视区只列用户自己导入的站，内置站不进这里；新装没有自有站是常态，
            // 不能一 tap 就跳到视频源订阅页。只提示去向，留在原地。
            Toast.makeText(this, "影视区暂无自有站点：内置站都在午夜/短剧区，影视区只列你自己导入的站（设置 → 导入本地 JSON 文件）", Toast.LENGTH_LONG).show();
            return;
        }
        sourceAdapter.submit(sources);
        SourceBean home = ApiConfig.get().getHomeSource();
        if (home != null) sourceAdapter.setCurrentKey(home.getKey());
        if (tvSourceCount != null) tvSourceCount.setText(sources.size() + " 个源");
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
        bottomNav.setSelectedItemId(NAV_MOVIE);
        notifyHomeReload();
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
        SourceBean home = ApiConfig.get().getHomeSource();
        tvTitle.setText(home != null ? home.getName() : getString(R.string.source_default));
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshTitle();
    }

    @Override
    public void onBackPressed() {
        if (sourcePopup != null && sourcePopup.isShowing()) {
            sourcePopup.dismiss();
        } else if (showingFav || currentNav != NAV_MOVIE) {
            showFragment(NAV_MOVIE);
            bottomNav.setSelectedItemId(NAV_MOVIE);
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
