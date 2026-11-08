package com.yingtu.plus.ui.fragment;

import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.yingtu.plus.R;
import com.yingtu.plus.api.ApiConfig;
import com.yingtu.plus.api.BuiltinConfigs;
import com.yingtu.plus.api.VodConfigSync;
import com.yingtu.plus.base.BaseLazyFragment;
import com.yingtu.plus.bean.SourceBean;
import com.yingtu.plus.ui.adapter.SourceGridAdapter;

import java.util.List;

/**
 * 「午夜」页（底部导航第四项，H 批）。
 *
 * 上半是内置午夜站列表（156 站，来自 assets/builtin_midnight.json），
 * 点站后下半内嵌一个 HomeMainFragment，复用影视页整条内容链路
 * （分类行 / 筛选 / 海报墙 / 翻页 / 点卡进详情），不另写取数。
 * 注意：切站走全局当前源（ApiConfig.setSourceBean），与影视页共用同一个
 * 当前源；切回影视 tab 时内容仍是该午夜站，直到在影视区重选站。
 * 分区独立记站不在本批范围，需总协调另定。
 */
public class MidnightFragment extends BaseLazyFragment {

    private static final String TAG_HOME = "midnight_home";

    private TextView tvMidnightTitle;
    private TextView tvMidnightHint;
    private FrameLayout contentContainer;
    private RecyclerView rvSites;
    private SourceGridAdapter siteAdapter;

    private String selectedKey = "";
    private boolean autoPicked = false;

    @Override
    protected int getLayoutId() {
        return R.layout.fragment_midnight;
    }

    @Override
    protected void init(View root) {
        tvMidnightTitle = root.findViewById(R.id.tvMidnightTitle);
        tvMidnightHint = root.findViewById(R.id.tvMidnightHint);
        contentContainer = root.findViewById(R.id.midnightContent);
        rvSites = root.findViewById(R.id.rvMidnightSites);

        rvSites.setLayoutManager(new GridLayoutManager(getContext(), 2));
        siteAdapter = new SourceGridAdapter();
        siteAdapter.setOnSourceClick(this::onSitePicked);
        rvSites.setAdapter(siteAdapter);
    }

    @Override
    protected void onVisible() {
        if (getContext() == null) return;
        // 订阅加载会清空内置站，缺失时在这里补回（幂等，已在就不动）。
        BuiltinConfigs.ensureLoaded(getContext());
        // J 批：内置站是合并进 ApiConfig 的，补回后也要同步进 VodConfig，
        // 否则午夜站走 VodConfig.getSite(key) 读不到（replaceSites 整体替换，不叠加）
        VodConfigSync.sync();
        refreshSites();
        // 选站优先级：内置区列表点进来的站 > 当前已选的午夜站 > 首次打开默认首站。
        // 原先每次进 tab 都默认首站，会把用户上次选的站顶掉。
        String pickKey = null;
        if (getActivity() != null && getActivity().getIntent() != null) {
            String extra = getActivity().getIntent()
                    .getStringExtra(com.yingtu.plus.ui.activity.HomeActivity.EXTRA_SITE_KEY);
            if (extra != null && BuiltinConfigs.isMidnightSite(extra)) {
                pickKey = extra;
                getActivity().getIntent()
                        .removeExtra(com.yingtu.plus.ui.activity.HomeActivity.EXTRA_SITE_KEY);
            }
        }
        if (pickKey == null) {
            SourceBean home = ApiConfig.get().getHomeSource();
            if (home != null && BuiltinConfigs.isMidnightSite(home.getKey())) {
                pickKey = home.getKey();
            }
        }
        List<SourceBean> sites = BuiltinConfigs.getMidnightSites();
        if (pickKey == null && !autoPicked && !sites.isEmpty()) {
            pickKey = sites.get(0).getKey();
        }
        if (pickKey != null) {
            for (SourceBean s : sites) {
                if (s.getKey().equals(pickKey)) {
                    autoPicked = true;
                    onSitePicked(s);
                    break;
                }
            }
        }
    }

    /** 刷新站列表（数量标题 + 选中高亮）。 */
    private void refreshSites() {
        List<SourceBean> sites = BuiltinConfigs.getMidnightSites();
        siteAdapter.submit(sites);
        siteAdapter.setCurrentKey(selectedKey);
        tvMidnightTitle.setText("午夜 · " + sites.size() + " 个站");
    }

    /** 点站：切全局当前源，下半内嵌的 HomeMainFragment 加载该站内容。 */
    private void onSitePicked(SourceBean source) {
        if (source == null) return;
        selectedKey = source.getKey();
        siteAdapter.setCurrentKey(selectedKey);
        ApiConfig.get().setSourceBean(source);
        showContent();
    }

    private void showContent() {
        tvMidnightHint.setVisibility(View.GONE);
        contentContainer.setVisibility(View.VISIBLE);
        HomeMainFragment child = (HomeMainFragment)
                getChildFragmentManager().findFragmentByTag(TAG_HOME);
        if (child == null) {
            child = new HomeMainFragment();
            getChildFragmentManager().beginTransaction()
                    .replace(R.id.midnightContent, child, TAG_HOME)
                    .commit();
        } else {
            child.reloadForSourceChange();
        }
    }
}
