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
 * 首页外壳。顶栏：Logo + 站点名 + 居中搜索框 + 历史/收藏/放映/网页四个图标；
 * 下方是内容区 + 底部导航（影视 / 短剧 / 直播 / 午夜 / 文件 / 设置）。
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
    public static final int NAV_SETTING = 1006;

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

            // 顶栏：标题/Logo 点开站源弹窗；搜索框进搜索页；右上角四个图标入口
            tvTitle.setOnClickListener(v -> openSourcePopup());
            find(R.id.ivLogo).setOnClickListener(v -> openSourcePopup());
            find(R.id.tvSearchBox).setOnClickListener(v ->
                    startActivity(new Intent(this, SearchActivity.class)));
            find(R.id.ivHistory).setOnClickListener(v ->
                    startActivity(new Intent(this, HistoryActivity.class)));
            find(R.id.ivFav).setOnClickListener(v -> showFav());
            find(R.id.ivCast).setOnClickListener(v ->
                    CastDialog.create().show(this));
            find(R.id.ivWeb).setOnClickListener(v ->
                    startActivity(new Intent(this, WebHomeActivity.class)));

            setupBottomNav();
            showFragment(NAV_MOVIE);
            handleTabExtra(getIntent());

            setupSourcePopup();
            refreshTitle();
        } catch (Throwable e) {
            e.printStackTrace();
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
        Menu menu = bottomNav.getMenu();
        menu.add(Menu.NONE, NAV_MOVIE, Menu.NONE, "影视").setIcon(R.drawable.ic_play_circle);
        menu.add(Menu.NONE, NAV_DRAMA, Menu.NONE, "短剧").setIcon(R.drawable.ic_play);
        menu.add(Menu.NONE, NAV_LIVE, Menu.NONE, "直播").setIcon(R.drawable.ic_live);
        menu.add(Menu.NONE, NAV_MIDNIGHT, Menu.NONE, "午夜").setIcon(R.drawable.ic_fav_on);
        menu.add(Menu.NONE, NAV_FILE, Menu.NONE, "文件").setIcon(R.drawable.ic_file);
        menu.add(Menu.NONE, NAV_SETTING, Menu.NONE, "设置").setIcon(R.drawable.ic_setting);
        bottomNav.setLabelVisibilityMode(LabelVisibilityMode.LABEL_VISIBILITY_LABELED);
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
            if (id == NAV_SETTING) {
                startActivity(new Intent(this, SettingActivity.class));
                return false;
            }
            showFragment(id);
            return true;
        });
    }

    private void showFragment(int navId) {
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
                (int) (getResources().getDisplayMetrics().widthPixels * 0.8),
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
        // 影视区只列用户自己导入的站，内置站（午夜区、短剧区）不过滤进这里
        List<SourceBean> sources = new ArrayList<>();
        for (SourceBean s : ApiConfig.get().getSourceBeanList()) {
            if (!ApiConfig.get().isBuiltinSite(s.getKey())) sources.add(s);
        }
        if (sources.isEmpty()) {
            startActivity(new Intent(this, SourceActivity.class));
            return;
        }
        sourceAdapter.submit(sources);
        SourceBean home = ApiConfig.get().getHomeSource();
        if (home != null) sourceAdapter.setCurrentKey(home.getKey());
        tvSourceCount.setText(sources.size() + " 个源");
        etSearch.setText("");
        sourceAdapter.setSearch("");
        sourceAdapter.setFilterChip("全部");
        buildChips();

        if (sourcePopup.isShowing()) {
            sourcePopup.dismiss();
        } else {
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
