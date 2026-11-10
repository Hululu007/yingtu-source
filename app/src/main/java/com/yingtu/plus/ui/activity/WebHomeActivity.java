package com.yingtu.plus.ui.activity;

import android.annotation.SuppressLint;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.graphics.Color;
import android.os.Build;
import android.text.TextUtils;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.ConsoleMessage;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.CookieManager;
import android.widget.FrameLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;

import com.yingtu.plus.R;
import com.yingtu.plus.base.BaseActivity;
import com.yingtu.plus.utils.Util;
import com.yingtu.plus.web.HomeWebBridge;
import com.yingtu.plus.web.WebHomeSdk;
import com.yingtu.plus.web.WebServer;
import com.google.gson.JsonObject;

/**
 * WebHome 承载页：用内置 WebView 加载一个「网页首页」（远程 URL / 本地 HTML / 内置示例），
 * 并注入 App Native SDK（window.fm / window.fongmi），实现 webhtv 的 WebHome 能力。
 *
 * 这是本次按 https://github.com/webhtv/webhtv 补全的核心功能：
 * 让 HTML 文件（含用户自己的本地 .html）成为可调用原生搜索/播放/网络能力的首页应用。
 */
public class WebHomeActivity extends BaseActivity implements HomeWebBridge.UiCallback {

    public static final String KEY_URL = "webhome_url";
    public static final String KEY_TITLE = "webhome_title";

    private WebView webView;
    private ProgressBar progress;
    private TextView tvTitle;
    private View bar;

    private String chromeMode = "normal";
    private boolean sdkReady = false;

    // WebView 内嵌 <video> 全屏：WebChromeClient 回调进来的 custom view
    private View customView;
    private WebChromeClient.CustomViewCallback customViewCallback;

    // 缓存视口度量（在 UI 线程更新），供后台 bridge 线程安全读取
    private volatile int vpWidth = 0;
    private volatile int vpHeight = 0;
    private volatile int vpSafeTop = 0;

    @Override
    protected int getLayoutId() {
        return R.layout.activity_webhome;
    }

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    @Override
    protected void init() {
        // web 测试闪退加固：WebView 初始化（容器 WebView 缺失、find 拿不到 view、
        // 网关启动、loadUrl）任一步抛错都不许杀进程，提示一句直接退出本页。
        try {
            initInner();
        } catch (Throwable e) {
            e.printStackTrace();
            try {
                Toast.makeText(this, "网页打开失败", Toast.LENGTH_SHORT).show();
            } catch (Throwable ignored) {}
            try { finish(); } catch (Throwable ignored) {}
        }
    }

    private void initInner() {
        webView = find(R.id.webView);
        progress = find(R.id.progress);
        tvTitle = find(R.id.tvTitle);
        bar = find(R.id.bar);

        String title = getIntent().getStringExtra(KEY_TITLE);
        String url = getIntent().getStringExtra(KEY_URL);
        tvTitle.setText(TextUtils.isEmpty(title) ? "网页首页" : title);

        find(R.id.ivBack).setOnClickListener(v -> handleBackPressed());
        find(R.id.ivReload).setOnClickListener(v -> reload());

        // 确保本地资源网关已启动（fm.res 依赖）
        WebServer.get().start();

        setupWebView();

        if (TextUtils.isEmpty(url)) {
            Toast.makeText(this, "首页地址为空", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }
        webView.loadUrl(url);
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void setupWebView() {
        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setAllowFileAccess(true);
        s.setAllowContentAccess(true);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        }
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(true);
        s.setSupportZoom(false);
        s.setBuiltInZoomControls(false);
        s.setTextZoom(100);
        s.setJavaScriptCanOpenWindowsAutomatically(true);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);

        webView.setBackgroundColor(Color.TRANSPARENT);
        webView.setOverScrollMode(View.OVER_SCROLL_NEVER);

        // 第三方 Cookie
        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            cm.setAcceptThirdPartyCookies(webView, true);
        }

        webView.addJavascriptInterface(
                new HomeWebBridge(this, webView, this), WebHomeSdk.BRIDGE);

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return false; // 让 WebView 自己加载
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                try {
                    injectSdk();
                } catch (Throwable ignored) {}
                if (progress != null) progress.setVisibility(View.GONE);
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onConsoleMessage(ConsoleMessage msg) {
                android.util.Log.d("webview", msg.message() + " @" + msg.lineNumber());
                return true;
            }

            @Override
            public void onShowCustomView(View view, CustomViewCallback callback) {
                // 全屏 custom view：Activity 正在退出时 getWindow() 可能为 null，
                // bar 也可能没建好，任一步抛错都不许杀进程。
                try {
                    if (customView != null) {
                        callback.onCustomViewHidden();
                        return;
                    }
                    if (view == null || getWindow() == null) {
                        if (callback != null) callback.onCustomViewHidden();
                        return;
                    }
                    customView = view;
                    customViewCallback = callback;
                    ((ViewGroup) getWindow().getDecorView()).addView(customView,
                            new FrameLayout.LayoutParams(
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                    ViewGroup.LayoutParams.MATCH_PARENT));
                    if (bar != null) bar.setVisibility(View.GONE);
                    Util.hideSystemUI(WebHomeActivity.this);
                    setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
                } catch (Throwable e) {
                    e.printStackTrace();
                    try {
                        if (callback != null) callback.onCustomViewHidden();
                    } catch (Throwable ignored) {}
                    customView = null;
                    customViewCallback = null;
                }
            }

            @Override
            public void onHideCustomView() {
                hideCustomView();
            }
        });
    }

    private void injectSdk() {
        if (webView == null) return;
        webView.evaluateJavascript(WebHomeSdk.script(isLeanback()), value -> {
            sdkReady = true;
            pushViewport();
        });
    }

    private void pushViewport() {
        if (webView == null) return;
        vpWidth = px2dp(webView.getWidth());
        vpHeight = px2dp(webView.getHeight());
        vpSafeTop = px2dp(getStatusBarHeight());
        String js = WebHomeSdk.viewportVars(vpWidth, vpHeight, vpSafeTop, 0, vpSafeTop, chromeMode);
        webView.evaluateJavascript(js, null);
    }

    private void hideCustomView() {
        if (customView == null) return;
        try {
            if (getWindow() != null) {
                ((ViewGroup) getWindow().getDecorView()).removeView(customView);
            }
        } catch (Throwable ignored) {}
        customView = null;
        boolean edge = "edge".equals(chromeMode) || "immersive".equals(chromeMode)
                || "tv-full".equals(chromeMode) || "fullscreen".equals(chromeMode);
        if (bar != null) bar.setVisibility(edge ? View.GONE : View.VISIBLE);
        try {
            Util.showSystemUI(this);
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
        } catch (Throwable ignored) {}
        if (customViewCallback != null) {
            try {
                customViewCallback.onCustomViewHidden();
            } catch (Throwable ignored) {}
            customViewCallback = null;
        }
    }

    private int px2dp(int px) {
        float d = getResources().getDisplayMetrics().density;
        return d <= 0 ? px : Math.round(px / d);
    }

    private int getStatusBarHeight() {
        int id = getResources().getIdentifier("status_bar_height", "dimen", "android");
        return id > 0 ? getResources().getDimensionPixelSize(id) : 0;
    }

    private boolean isLeanback() {
        return getPackageManager().hasSystemFeature("android.software.leanback");
    }

    private void handleBackPressed() {
        if (customView != null) {
            hideCustomView();
            return;
        }
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
        } else {
            finish();
        }
    }

    @Override
    public void onConfigurationChanged(@NonNull Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        // 转屏后 WebView 宽高变了，视口变量要重推；post 到布局完成后再量，避免量到旧尺寸
        if (webView != null) webView.post(() -> pushViewport());
    }

    @Override
    public void onBackPressed() {
        handleBackPressed();
    }

    // ===== HomeWebBridge.UiCallback =====

    @Override
    public void setToolbar(boolean visible) {
        if (bar != null) bar.setVisibility(visible ? View.VISIBLE : View.GONE);
    }

    @Override
    public void setChrome(JsonObject payload) {
        String mode = payload != null && payload.has("mode") && !payload.get("mode").isJsonNull()
                ? payload.get("mode").getAsString() : "";
        if (!TextUtils.isEmpty(mode)) {
            chromeMode = mode;
            boolean edge = "edge".equals(mode) || "immersive".equals(mode)
                    || "tv-full".equals(mode) || "fullscreen".equals(mode);
            if (bar != null) bar.setVisibility(edge ? View.GONE : View.VISIBLE);
        }
        pushViewport();
    }

    @Override
    public void restoreChrome() {
        chromeMode = "normal";
        if (bar != null) bar.setVisibility(View.VISIBLE);
        pushViewport();
    }

    @Override
    public String getViewportJson() {
        // 该方法可能被 bridge 后台线程调用，读缓存值避免跨线程访问 View
        JsonObject o = new JsonObject();
        o.addProperty("webWidth", vpWidth);
        o.addProperty("webHeight", vpHeight);
        o.addProperty("safeTop", vpSafeTop);
        o.addProperty("safeBottom", 0);
        o.addProperty("statusBarHeight", vpSafeTop);
        o.addProperty("chromeMode", chromeMode);
        return o.toString();
    }

    @Override
    public void back() {
        handleBackPressed();
    }

    @Override
    public void reload() {
        if (progress != null) progress.setVisibility(View.VISIBLE);
        if (webView != null) {
            webView.clearCache(true);
            webView.reload();
        }
    }

    @Override
    public void openVod() {
        finish();
    }

    @Override
    public void openSetting() {
        startActivity(new android.content.Intent(this, SettingActivity.class));
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (webView != null) {
            webView.onResume();
            if (sdkReady) {
                webView.evaluateJavascript(
                        WebHomeSdk.dispatchEvent("fmresume", "{\"time\":" + System.currentTimeMillis() + "}"), null);
            }
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (webView != null) {
            if (sdkReady) {
                webView.evaluateJavascript(
                        WebHomeSdk.dispatchEvent("fmpause", "{\"time\":" + System.currentTimeMillis() + "}"), null);
            }
            webView.onPause();
        }
    }

    @Override
    protected void onDestroy() {
        if (webView != null) {
            try {
                webView.loadUrl("about:blank");
            } catch (Throwable ignored) {}
            try {
                webView.removeJavascriptInterface(WebHomeSdk.BRIDGE);
            } catch (Throwable ignored) {}
            try {
                webView.destroy();
            } catch (Throwable ignored) {}
            webView = null;
        }
        super.onDestroy();
    }
}
