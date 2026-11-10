package com.yingtu.plus.ui.activity;

import android.annotation.SuppressLint;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.yingtu.plus.R;
import com.yingtu.plus.api.SiteApi;
import com.yingtu.plus.api.config.RuleConfig;
import com.yingtu.plus.base.BaseActivity;
import com.yingtu.plus.bean.OnlineSite;
import com.yingtu.plus.bean.Result;
import com.yingtu.plus.impl.ParseCallback;
import com.yingtu.plus.player.ParseJob;
import com.yingtu.plus.util.OnlineSiteStore;
import com.yingtu.plus.util.Sniffer;
import com.yingtu.plus.utils.ImgUtil;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Online website browser: loads a user-added site in a WebView, runs the
 * generic parser (assets/online_site_parse.js) to find cards and episodes,
 * and plays through the existing ParseJob sniff chain.
 * No spider is written for the site; a missing cover shows a placeholder
 * and missing director / actor shows the fallback text, never an error.
 */
public class OnlineSiteBrowserActivity extends BaseActivity {

    private static final String SNIFF_KEY = "online_site";

    private WebView webView;
    private ProgressBar progress;
    private TextView tvTitle;
    private TextView tvToggle;
    private View panel;
    private TextView tvPanelTitle;
    private TextView tvPanelMeta;
    private TextView tvPanelEmpty;
    private RecyclerView rvPanel;

    private OnlineSite site;
    private boolean pureMode;
    private String parseJs;
    private String currentTitle = "";
    private String lastLoadError = "";
    private boolean pageHintShown;

    private final List<Card> lastList = new ArrayList<>();
    private final List<Episode> lastEpisodes = new ArrayList<>();

    private ParseJob sniffJob;
    private String sniffTitle = "";

    private static class Card {
        String title;
        String pic;
        String url;
    }

    private static class Episode {
        String name;
        String url;
        int index;
    }

    @Override
    protected int getLayoutId() {
        return R.layout.activity_online_site_browser;
    }

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    @Override
    protected void init() {
        String siteId = getIntent().getStringExtra(OnlineSiteListActivity.KEY_SITE_ID);
        site = OnlineSiteStore.find(siteId);
        if (site == null || TextUtils.isEmpty(site.url)) {
            Toast.makeText(this, R.string.online_site_need_url, Toast.LENGTH_SHORT).show();
            finish();
            return;
        }
        pureMode = site.pure;

        webView = find(R.id.webView);
        progress = find(R.id.progress);
        tvTitle = find(R.id.tvTitle);
        tvToggle = find(R.id.tvToggle);
        panel = find(R.id.panel);
        tvPanelTitle = find(R.id.tvPanelTitle);
        tvPanelMeta = find(R.id.tvPanelMeta);
        tvPanelEmpty = find(R.id.tvPanelEmpty);
        rvPanel = find(R.id.rvPanel);

        tvTitle.setText(site.name);
        currentTitle = site.name;
        find(R.id.ivBack).setOnClickListener(v -> handleBackPressed());
        find(R.id.ivReload).setOnClickListener(v -> reload());
        tvToggle.setOnClickListener(v -> togglePanel());

        setupWebView();
        webView.loadUrl(site.url);
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void setupWebView() {
        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setAllowFileAccess(true);
        s.setAllowContentAccess(true);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(true);
        s.setSupportZoom(false);
        s.setBuiltInZoomControls(false);
        s.setTextZoom(100);
        s.setJavaScriptCanOpenWindowsAutomatically(true);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);

        webView.setOverScrollMode(View.OVER_SCROLL_NEVER);

        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        cm.setAcceptThirdPartyCookies(webView, true);

        webView.addJavascriptInterface(new OnlineBridge(), "OnlineSite");

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                String target = request.getUrl().toString();
                if (Sniffer.isVideoFormat(target)) {
                    playUrl(target, currentTitle);
                    return true;
                }
                return false;
            }

            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                if (isAdRequest(request.getUrl().toString())) return emptyResponse();
                return super.shouldInterceptRequest(view, request);
            }

            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, String url) {
                if (isAdRequest(url)) return emptyResponse();
                return super.shouldInterceptRequest(view, url);
            }

            @Override
            public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
                super.onPageStarted(view, url, favicon);
                if (progress != null) progress.setVisibility(View.VISIBLE);
                pageHintShown = false;
                lastLoadError = "";
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                if (progress != null) progress.setVisibility(View.GONE);
                runParse(url);
            }

            @Override
            @SuppressWarnings("deprecation")
            public void onReceivedError(WebView view, int errorCode, String description, String failingUrl) {
                super.onReceivedError(view, errorCode, description, failingUrl);
                lastLoadError = description == null ? "" : description;
            }
        });
    }

    private void reload() {
        if (progress != null) progress.setVisibility(View.VISIBLE);
        if (webView != null) webView.reload();
    }

    private void togglePanel() {
        if (panel == null) return;
        boolean show = panel.getVisibility() != View.VISIBLE;
        panel.setVisibility(show ? View.VISIBLE : View.GONE);
        tvToggle.setText(show ? R.string.online_site_hide : R.string.online_site_list);
    }

    private void showPanel() {
        if (panel != null && panel.getVisibility() != View.VISIBLE) {
            panel.setVisibility(View.VISIBLE);
            tvToggle.setText(R.string.online_site_hide);
        }
    }

    /** Run the generic parser shortly after load so JS-rendered lists appear. */
    private void runParse(String url) {
        if (webView == null) return;
        if (TextUtils.isEmpty(url) || (!url.startsWith("http://") && !url.startsWith("https://"))) return;
        webView.postDelayed(() -> {
            if (webView == null) return;
            String js = loadParseJs();
            if (TextUtils.isEmpty(js)) return;
            try {
                webView.evaluateJavascript(js + "\nwindow.__onlineRun();", null);
            } catch (Throwable ignored) {}
        }, 800);
    }

    private String loadParseJs() {
        if (parseJs != null) return parseJs;
        parseJs = "";
        try (InputStream in = getAssets().open("online_site_parse.js")) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
            parseJs = out.toString("UTF-8");
        } catch (Throwable ignored) {}
        return parseJs;
    }

    /** Pure mode reuses the existing ad rules (VodConfig + LiveConfig ads). */
    private boolean isAdRequest(String url) {
        if (!pureMode || TextUtils.isEmpty(url)) return false;
        try {
            for (String ad : RuleConfig.get().getAds()) {
                if (!TextUtils.isEmpty(ad) && url.contains(ad)) return true;
            }
        } catch (Throwable ignored) {}
        return false;
    }

    private WebResourceResponse emptyResponse() {
        try {
            return new WebResourceResponse("text/plain", "utf-8", new ByteArrayInputStream(new byte[0]));
        } catch (Throwable e) {
            return null;
        }
    }

    private class OnlineBridge {
        @JavascriptInterface
        public void onList(String json) {
            runOnUiThread(() -> handleList(json));
        }

        @JavascriptInterface
        public void onEpisodes(String json) {
            runOnUiThread(() -> handleEpisodes(json));
        }

        @JavascriptInterface
        public void onMeta(String json) {
            runOnUiThread(() -> handleMeta(json));
        }

        @JavascriptInterface
        public void onError(String msg) {
            runOnUiThread(() -> handleParseError(msg));
        }
    }

    private void handleList(String json) {
        List<Card> cards = parseCards(json);
        lastList.clear();
        lastList.addAll(cards);
        if (!lastEpisodes.isEmpty()) return;
        if (cards.isEmpty()) {
            showPanelEmpty(getString(R.string.online_site_no_list));
            return;
        }
        showCards(cards);
    }

    private void handleEpisodes(String json) {
        List<Episode> eps = parseEpisodes(json);
        lastEpisodes.clear();
        lastEpisodes.addAll(eps);
        if (!eps.isEmpty()) {
            showEpisodes(eps);
            return;
        }
        if (!lastList.isEmpty()) {
            showCards(lastList);
            return;
        }
        showPanelEmpty(getString(R.string.online_site_no_episode));
    }

    private void handleMeta(String json) {
        try {
            JSONObject o = new JSONObject(json);
            String director = o.optString("director", "");
            String actor = o.optString("actor", "");
            String title = o.optString("title", "");
            if (TextUtils.isEmpty(currentTitle) && !TextUtils.isEmpty(title)) currentTitle = title;
            String unknown = getString(R.string.online_site_unknown);
            String meta = "导演：" + (TextUtils.isEmpty(director) ? unknown : director)
                    + "  演员：" + (TextUtils.isEmpty(actor) ? unknown : actor);
            if (tvPanelMeta != null) tvPanelMeta.setText(meta);
        } catch (Throwable ignored) {}
    }

    private void handleParseError(String msg) {
        if (pageHintShown) return;
        pageHintShown = true;
        if (lastList.isEmpty() && lastEpisodes.isEmpty()) {
            showPanelEmpty(getString(R.string.online_site_no_list));
        }
    }

    private List<Card> parseCards(String json) {
        List<Card> out = new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                Card c = new Card();
                c.title = o.optString("title", "");
                c.pic = o.optString("pic", "");
                c.url = o.optString("url", "");
                if (TextUtils.isEmpty(c.title) || TextUtils.isEmpty(c.url)) continue;
                out.add(c);
            }
        } catch (Throwable ignored) {}
        return out;
    }

    private List<Episode> parseEpisodes(String json) {
        List<Episode> out = new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                Episode e = new Episode();
                e.name = o.optString("name", "");
                e.url = o.optString("url", "");
                e.index = o.optInt("index", out.size());
                if (TextUtils.isEmpty(e.name)) continue;
                out.add(e);
            }
        } catch (Throwable ignored) {}
        return out;
    }

    private void showCards(List<Card> cards) {
        showPanel();
        tvPanelTitle.setText(getString(R.string.online_site_list) + " · " + cards.size());
        tvPanelEmpty.setVisibility(View.GONE);
        rvPanel.setVisibility(View.VISIBLE);
        rvPanel.setLayoutManager(new GridLayoutManager(this, 3));
        rvPanel.setAdapter(new CardAdapter(cards));
    }

    private void showEpisodes(List<Episode> eps) {
        showPanel();
        tvPanelTitle.setText(getString(R.string.online_site_episodes) + " · " + eps.size());
        tvPanelEmpty.setVisibility(View.GONE);
        rvPanel.setVisibility(View.VISIBLE);
        rvPanel.setLayoutManager(new GridLayoutManager(this, 4));
        rvPanel.setAdapter(new EpisodeAdapter(eps));
    }

    private void showPanelEmpty(String hint) {
        showPanel();
        tvPanelTitle.setText(site == null ? "" : site.name);
        tvPanelEmpty.setText(hint);
        tvPanelEmpty.setVisibility(View.VISIBLE);
        rvPanel.setVisibility(View.GONE);
    }

    private void onCardClick(Card card) {
        if (card == null || TextUtils.isEmpty(card.url)) return;
        currentTitle = card.title;
        lastEpisodes.clear();
        if (webView != null) webView.loadUrl(card.url);
        showPanelEmpty(getString(R.string.online_site_loading));
    }

    private void onEpisodeClick(Episode ep) {
        if (ep == null) return;
        String title = currentTitle + " " + ep.name;
        if (!TextUtils.isEmpty(ep.url)) {
            sniffPage(ep.url, title);
            return;
        }
        if (webView == null) return;
        try {
            webView.evaluateJavascript("window.__onlineClickEpisode(" + ep.index + ");", null);
        } catch (Throwable ignored) {}
        webView.postDelayed(() -> {
            if (webView == null) return;
            sniffPage(webView.getUrl(), title);
        }, 1500);
    }

    /** Playback goes through the existing ParseJob sniff chain (same as DetailActivity). */
    private void sniffPage(String pageUrl, String title) {
        if (TextUtils.isEmpty(pageUrl)) {
            showPlayError();
            return;
        }
        if (Sniffer.isVideoFormat(pageUrl)) {
            playUrl(pageUrl, title);
            return;
        }
        stopSniff();
        sniffTitle = title == null ? "" : title;
        Toast.makeText(this, R.string.online_site_sniffing, Toast.LENGTH_SHORT).show();
        Map<String, String> header = new HashMap<>();
        header.put("Referer", pageUrl);
        Result r = new Result();
        r.setKey(SNIFF_KEY);
        r.setFlag("");
        r.setUrl(pageUrl);
        r.setHeader(header);
        sniffJob = ParseJob.create(new ParseCallback() {
            @Override
            public void onParseSuccess(Map<String, String> headers, String url, String from) {
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) {
                        stopSniff();
                        return;
                    }
                    if (isSniffedAdOrPreview(url)) {
                        showPlayError();
                        sniffJob = null;
                        return;
                    }
                    sniffJob = null;
                    playUrl(url, sniffTitle);
                });
            }

            @Override
            public void onParseError() {
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) {
                        stopSniff();
                        return;
                    }
                    showPlayError();
                    sniffJob = null;
                });
            }
        }).start(r, false);
    }

    /** Same ad / preview filter as DetailActivity's sniff fallback. */
    private boolean isSniffedAdOrPreview(String url) {
        if (TextUtils.isEmpty(url)) return true;
        String u = url.toLowerCase();
        return u.contains("advert") || u.contains("preroll") || u.contains("pre-roll")
                || u.contains("/ads/") || u.contains("preview") || u.contains("trailer") || u.contains("预告");
    }

    private void playUrl(String url, String title) {
        if (TextUtils.isEmpty(url)) {
            showPlayError();
            return;
        }
        String name = TextUtils.isEmpty(title) ? url : title;
        VideoActivity.start(this, SiteApi.PUSH, url, name);
    }

    private void showPlayError() {
        String reason = getString(R.string.online_site_no_video);
        if (!TextUtils.isEmpty(lastLoadError)) reason += "（" + lastLoadError + "）";
        Toast.makeText(this, reason, Toast.LENGTH_LONG).show();
    }

    private void stopSniff() {
        if (sniffJob != null) {
            sniffJob.stop();
            sniffJob = null;
        }
    }

    private void handleBackPressed() {
        if (panel != null && panel.getVisibility() == View.VISIBLE) {
            panel.setVisibility(View.GONE);
            tvToggle.setText(R.string.online_site_list);
            return;
        }
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
        } else {
            finish();
        }
    }

    @Override
    public void onBackPressed() {
        handleBackPressed();
    }

    @Override
    protected void onDestroy() {
        stopSniff();
        if (webView != null) {
            try {
                webView.loadUrl("about:blank");
            } catch (Throwable ignored) {}
            try {
                webView.removeJavascriptInterface("OnlineSite");
            } catch (Throwable ignored) {}
            try {
                webView.destroy();
            } catch (Throwable ignored) {}
            webView = null;
        }
        super.onDestroy();
    }

    private class CardAdapter extends RecyclerView.Adapter<CardAdapter.VH> {
        private final List<Card> data;

        CardAdapter(List<Card> data) {
            this.data = data;
        }

        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_online_card, parent, false);
            return new VH(v);
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int position) {
            Card card = data.get(position);
            h.tvTitle.setText(card.title);
            if (TextUtils.isEmpty(card.pic)) {
                h.ivCover.setImageResource(R.drawable.bg_poster_placeholder);
            } else {
                Glide.with(h.itemView.getContext())
                        .load(ImgUtil.getUrl(card.pic))
                        .placeholder(R.drawable.bg_poster_placeholder)
                        .error(R.drawable.bg_poster_placeholder)
                        .centerCrop()
                        .into(h.ivCover);
            }
            h.itemView.setOnClickListener(v -> onCardClick(card));
        }

        @Override
        public int getItemCount() {
            return data.size();
        }

        class VH extends RecyclerView.ViewHolder {
            final ImageView ivCover;
            final TextView tvTitle;

            VH(@NonNull View itemView) {
                super(itemView);
                ivCover = itemView.findViewById(R.id.ivCover);
                tvTitle = itemView.findViewById(R.id.tvTitle);
            }
        }
    }

    private class EpisodeAdapter extends RecyclerView.Adapter<EpisodeAdapter.VH> {
        private final List<Episode> data;

        EpisodeAdapter(List<Episode> data) {
            this.data = data;
        }

        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_series, parent, false);
            return new VH(v);
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int position) {
            Episode ep = data.get(position);
            h.tvSeries.setText(ep.name);
            h.itemView.setOnClickListener(v -> onEpisodeClick(ep));
        }

        @Override
        public int getItemCount() {
            return data.size();
        }

        class VH extends RecyclerView.ViewHolder {
            final TextView tvSeries;

            VH(@NonNull View itemView) {
                super(itemView);
                tvSeries = itemView.findViewById(R.id.tvSeries);
            }
        }
    }
}
