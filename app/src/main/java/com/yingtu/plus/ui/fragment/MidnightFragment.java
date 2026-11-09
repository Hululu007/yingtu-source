package com.yingtu.plus.ui.fragment;

import android.text.TextUtils;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;

import com.yingtu.plus.App;
import com.yingtu.plus.R;
import com.yingtu.plus.api.ApiConfig;
import com.yingtu.plus.api.BuiltinConfigs;
import com.yingtu.plus.api.VodConfigSync;
import com.yingtu.plus.base.BaseLazyFragment;
import com.yingtu.plus.bean.SourceBean;
import com.yingtu.plus.util.ZoneSites;

import java.util.List;

/**
 * 「午夜」页（底部导航第四项，H 批）。
 *
 * 上半只剩标题（站数），下半内嵌一个 HomeMainFragment，复用影视页整条内容链路
 * （分类行 / 筛选 / 海报墙 / 翻页 / 点卡进详情），不另写取数。
 * 站点网格已移进顶栏站名点的分区弹窗（第八版）：切站统一走弹窗，这里不再列站。
 * 分区独立记站：当前站记在午夜区自己的 Hawk 键里，切站只改本区，
 * 影视/短剧区的站不动；顶栏也按本区显示站名。
 */
public class MidnightFragment extends BaseLazyFragment {

    private static final String TAG_HOME = "midnight_home";

    private TextView tvMidnightTitle;
    private TextView tvMidnightHint;
    private FrameLayout contentContainer;

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
    }

    @Override
    protected void onVisible() {
        if (getContext() == null) return;
        try {
            // 订阅加载会清空内置站，缺失时在这里补回（幂等，已在就不动）。
            BuiltinConfigs.ensureLoaded(getContext());
            // J 批：内置站是合并进 ApiConfig 的，补回后也要同步进 VodConfig，
            // 否则午夜站走 VodConfig.getSite(key) 读不到（replaceSites 整体替换，不叠加）
            VodConfigSync.sync();
            List<SourceBean> sites = BuiltinConfigs.getMidnightSites();
            tvMidnightTitle.setText("午夜 · " + sites.size() + " 个站");
            // 选站优先级：内置区列表点进来的站 > 本区上次选的站（或启动站）
            // > 全局当前源（若正好是午夜站，老数据认领）> 首次打开默认首站。
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
                String zoneKey = ZoneSites.getCurrentSiteKey(ZoneSites.ZONE_MIDNIGHT);
                if (!TextUtils.isEmpty(zoneKey) && BuiltinConfigs.isMidnightSite(zoneKey)) {
                    pickKey = zoneKey;
                }
            }
            if (pickKey == null) {
                SourceBean home = ApiConfig.get().getHomeSource();
                if (home != null && BuiltinConfigs.isMidnightSite(home.getKey())) {
                    pickKey = home.getKey();
                }
            }
            if (pickKey == null && !autoPicked && !sites.isEmpty()) {
                pickKey = sites.get(0).getKey();
            }
            if (pickKey != null) {
                for (SourceBean s : sites) {
                    if (s.getKey().equals(pickKey)) {
                        autoPicked = true;
                        pickSite(s);
                        break;
                    }
                }
            }
        } catch (Throwable e) {
            // 可见时初始化失败不许杀进程：记 crash.log，界面显示加载失败。
            App.logCaught(e);
            if (tvMidnightHint != null) {
                tvMidnightHint.setText("加载失败");
                tvMidnightHint.setVisibility(View.VISIBLE);
            }
        }
    }

    /**
     * 点站（分区弹窗与内置区列表点进来共用）：切全局当前源，
     * 下半内嵌的 HomeMainFragment 加载该站内容；同时记进午夜区自己的键。
     */
    public void pickSite(SourceBean source) {
        if (source == null) return;
        ZoneSites.setCurrentSiteKey(ZoneSites.ZONE_MIDNIGHT, source.getKey());
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
                    .commitAllowingStateLoss();
            getChildFragmentManager().executePendingTransactions();
        } else {
            child.reloadForSourceChange();
        }
    }
}
