package com.yingtu.plus.ui.activity;

import android.net.Uri;
import android.text.TextUtils;
import android.view.View;
import android.webkit.WebView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import com.google.gson.JsonObject;
import com.yingtu.plus.R;
import com.yingtu.plus.api.config.VodConfig;
import com.yingtu.plus.base.BaseActivity;
import com.yingtu.plus.bean.Site;
import com.yingtu.plus.setting.Setting;
import com.yingtu.plus.ui.dialog.WebHomeExtensionDialog;
import com.yingtu.plus.web.HomeWebController;

/**
 * WebHome 承载页：WebView 交给 HomeWebController 持有，控制器负责 WebView 配置、
 * SDK/视口注入与扩展注入（按站点 prepare/refresh），Activity 只留顶栏与生命周期转发。
 * 扩展管理入口在顶栏（ivExt 按钮，点开 WebHomeExtensionDialog），不在导入站源屏里。
 */
public class WebHomeActivity extends BaseActivity implements HomeWebController.Listener {

    public static final String KEY_URL = "webhome_url";
    public static final String KEY_TITLE = "webhome_title";
    public static final String KEY_SITE = "webhome_site";

    private WebView webView;
    private ProgressBar progress;
    private TextView tvTitle;
    private View bar;

    private HomeWebController controller;
    private Site site;

    @Override
    protected int getLayoutId() {
        return R.layout.activity_webhome;
    }

    @Override
    protected void init() {
        webView = find(R.id.webView);
        progress = find(R.id.progress);
        tvTitle = find(R.id.tvTitle);
        bar = find(R.id.bar);

        String title = getIntent().getStringExtra(KEY_TITLE);
        String url = getIntent().getStringExtra(KEY_URL);
        String siteKey = getIntent().getStringExtra(KEY_SITE);
        tvTitle.setText(TextUtils.isEmpty(title) ? "网页首页" : title);

        find(R.id.ivBack).setOnClickListener(v -> handleBackPressed());
        find(R.id.ivReload).setOnClickListener(v -> reload());
        find(R.id.ivExt).setOnClickListener(v -> WebHomeExtensionDialog.show(this, null));

        controller = new HomeWebController(this, webView, this, Setting.isDebugLog());

        site = resolveSite(siteKey, url, title);
        if (site == null || !controller.load(site)) {
            Toast.makeText(this, "首页地址为空", Toast.LENGTH_SHORT).show();
            finish();
        }
    }

    /**
     * 站点解析：给站 key 就用 VodConfig 里的站（走该站的扩展与首页）；
     * 只给 URL 时按 host 合成临时站，使 cspKeyRegex 能按 host 匹配、用户自加扩展（空 siteKey 全匹配）也能生效。
     */
    private Site resolveSite(String siteKey, String url, String title) {
        if (!TextUtils.isEmpty(siteKey)) {
            Site s = VodConfig.get().getSite(siteKey);
            if (s != null && s.hasHomePage()) return s;
        }
        if (TextUtils.isEmpty(url)) return null;
        Site s = new Site();
        String host = null;
        try {
            host = Uri.parse(url).getHost();
        } catch (Exception ignored) {
        }
        s.setKey(TextUtils.isEmpty(host) ? url : host);
        s.setName(TextUtils.isEmpty(title) ? s.getKey() : title);
        s.setHomePage(url);
        return s;
    }

    private WebView currentWebView() {
        // 控制器超时恢复时会重建 WebView（同 id 换进父布局），每次用现找的，避免拿旧引用
        WebView current = find(R.id.webView);
        return current != null ? current : webView;
    }

    private void handleBackPressed() {
        WebView current = currentWebView();
        if (current != null && current.canGoBack()) {
            current.goBack();
        } else {
            finish();
        }
    }

    @Override
    public void onBackPressed() {
        handleBackPressed();
    }

    // ===== HomeWebController.Listener =====

    @Override
    public void onWebLoading() {
        progress.setVisibility(View.VISIBLE);
    }

    @Override
    public void onWebReady() {
        progress.setVisibility(View.GONE);
    }

    @Override
    public void onWebError() {
        progress.setVisibility(View.GONE);
    }

    @Override
    public void setToolbar(boolean visible) {
        bar.setVisibility(visible ? View.VISIBLE : View.GONE);
    }

    @Override
    public void applyDefaultChrome(Site site) {
        setChrome(site.getWebHomeChrome());
    }

    @Override
    public void setChrome(JsonObject payload) {
        String mode = payload != null && payload.has("mode") && !payload.get("mode").isJsonNull()
                ? payload.get("mode").getAsString() : "";
        if (!TextUtils.isEmpty(mode)) {
            boolean edge = "edge".equals(mode) || "immersive".equals(mode)
                    || "tv-full".equals(mode) || "fullscreen".equals(mode);
            bar.setVisibility(edge ? View.GONE : View.VISIBLE);
        }
    }

    @Override
    public void restoreChrome() {
        bar.setVisibility(View.VISIBLE);
    }

    @Override
    public void openVod() {
        finish();
    }

    @Override
    public void openSetting() {
        startActivity(new android.content.Intent(this, SettingActivity.class));
    }

    public void reload() {
        progress.setVisibility(View.VISIBLE);
        if (controller != null) controller.reload();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (controller != null) controller.onResume();
    }

    @Override
    protected void onPause() {
        if (controller != null) controller.onPause();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        if (controller != null) {
            controller.destroy();
            controller = null;
        }
        super.onDestroy();
    }
}
