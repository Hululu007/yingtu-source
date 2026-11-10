package com.yingtu.plus.ui.activity;

import com.yingtu.plus.R;
import com.yingtu.plus.base.BaseActivity;
import com.yingtu.plus.ui.fragment.WebHomeFragment;

/**
 * 网页首页列表页：只托管 WebHomeFragment（原来内置的 web 条目列表）。
 *
 * 背景：WebHomeFragment 列出 WebHomeHelper 全部条目（内置示例 + 用户自加），
 * 但改版后没有任何 Activity/Fragment 托管它，入口丢了，用户在 App 里
 * 再也进不去原来内置的 web。入口：我的页 → 网页首页。
 */
public class WebHomeListActivity extends BaseActivity {

    @Override
    protected int getLayoutId() {
        return R.layout.activity_webhome_list;
    }

    @Override
    protected void init() {
        find(R.id.ivBack).setOnClickListener(v -> finish());
        if (getSupportFragmentManager().findFragmentByTag("webhome") == null) {
            getSupportFragmentManager().beginTransaction()
                    .replace(R.id.container, new WebHomeFragment(), "webhome")
                    .commit();
        }
    }
}
