package com.yingtu.plus.drama;

import android.content.Context;
import android.content.SharedPreferences;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.yingtu.plus.drama.DramaModels.DramaDetail;
import com.yingtu.plus.drama.DramaModels.DramaEpisode;
import com.yingtu.plus.drama.DramaModels.DramaStream;
import com.yingtu.plus.drama.DramaModels.DramaVideo;
import com.yingtu.plus.drama.DramaModels.FeedPage;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Fanqie short-drama client (Java rewrite of the Rust fanqie-plan /
 * fanqie-feed routes plus the video_model / video_detail endpoints).
 *
 * Chain (all anonymous, no login, live-verified 2026-11-08 with the Python
 * mirror ~/workspace/yingtu_drama_work/fqclient.py):
 *   plan/v (from=video_ranklist) -> seed cell_id_str + session_id
 *   cell/change (VideoFlow=2, LoadMore=2, ChangeFilter=2 first page,
 *     GetMore=1 later) -> 15 real videos per page, zero overlap with plan
 *   video_tab/video_model/v GET (video_id + series_id) -> video_list,
 *     base64 plaintext main_url (spade-wrapped entries decrypted with
 *     key_seed), fallback_api kept for encrypted models
 *   video_detail/v1/ POST (series_id) -> 102-episode list, no stream URL
 *
 * Dead on purpose (do NOT re-add): POST /novel/player/multi_video_model/v1/
 * and POST /novel/player/video_model/v1/ both answer HTTP 200 with an empty
 * body (27 single-variable probes); the upstream dropped those endpoints.
 *
 * Signing: every request carries the x-ss-req-ticket / x-khronos / x-ladon /
 * x-argus headers from FqSign. An unsigned plan/v returns HTTP 200 with an
 * empty body, so the signing is not optional.
 *
 * Device identity: one synthetic numeric pair (device_id 16 digits,
 * install_id 19 digits starting with 9, same shape the Rust probe pool
 * uses) generated once and kept in SharedPreferences. No registration call
 * is needed; the upstream accepts the synthetic pair with a valid signature.
 *
 * All methods are synchronous and throw IOException: call them off the main
 * thread. Stream URLs carry a ~5.5h signature expiry: resolve at play time,
 * never persist or hard-code them.
 */
public final class FanqieDramaClient {

    public static final String HOST = "https://api5-normal-sinfonlineb.fqnovel.com";
    private static final String PLAN_PATH = "/reading/bookapi/plan/v";
    private static final String CHANGE_PATH = "/reading/bookapi/bookmall/cell/change/v";
    private static final String VIDEO_MODEL_PATH = "/reading/bookapi/video_tab/video_model/v";
    private static final String VIDEO_DETAIL_PATH = "/novel/player/video_detail/v1/";

    private static final String UA_DRAGON = "com.dragon.read";
    private static final String UA_PHOENIX =
            "com.phoenix.read/71332 (Linux; U; Android 16; zh_CN; 25053RT47C; "
                    + "Build/BP2A.250605.031.A3; Cronet/TTNetVersion:04657795 2026-01-23 "
                    + "QuicVersion:c67e9834 2025-09-08)";

    private static final int CONNECT_TIMEOUT_MS = 30000;
    private static final int READ_TIMEOUT_MS = 40000;
    private static final int MAX_ATTEMPTS = 4;

    private static final String PREFS = "fq_drama_device";
    private static final String KEY_DEVICE_ID = "device_id";
    private static final String KEY_INSTALL_ID = "install_id";

    private final String deviceId;
    private final String installId;

    public FanqieDramaClient(Context context) {
        SharedPreferences prefs = context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String did = prefs.getString(KEY_DEVICE_ID, "");
        String iid = prefs.getString(KEY_INSTALL_ID, "");
        if (did.isEmpty() || iid.isEmpty()) {
            did = randomNumeric(16);
            iid = "9" + randomNumeric(18);
            prefs.edit().putString(KEY_DEVICE_ID, did).putString(KEY_INSTALL_ID, iid).apply();
        }
        this.deviceId = did;
        this.installId = iid;
    }

    /** Test seam: build with a fixed identity instead of SharedPreferences. */
    public FanqieDramaClient(String deviceId, String installId) {
        this.deviceId = deviceId;
        this.installId = installId;
    }

    public String getDeviceId() {
        return deviceId;
    }

    public String getInstallId() {
        return installId;
    }

    private static String randomNumeric(int digits) {
        SecureRandom r = new SecureRandom();
        StringBuilder sb = new StringBuilder(digits);
        sb.append((char) ('1' + r.nextInt(9)));
        for (int i = 1; i < digits; i++) sb.append((char) ('0' + r.nextInt(10)));
        return sb.toString();
    }

    // ------------------------------------------------------------ params

    /** The 7.2.4.32 query bag every reading endpoint starts from. */
    private List<Map.Entry<String, String>> reading724Params() {
        List<Map.Entry<String, String>> p = new ArrayList<>();
        add(p, "iid", installId);
        add(p, "device_id", deviceId);
        add(p, "ac", "wifi");
        add(p, "channel", "xiaomi_1967_64");
        add(p, "aid", "1967");
        add(p, "app_name", "novelapp");
        add(p, "version_code", "72432");
        add(p, "version_name", "7.2.4.32");
        add(p, "device_platform", "android");
        add(p, "os", "android");
        add(p, "ssmix", "a");
        add(p, "device_type", "25053RT47C");
        add(p, "device_brand", "Redmi");
        add(p, "language", "zh");
        add(p, "os_api", "36");
        add(p, "os_version", "16");
        add(p, "manifest_version_code", "72432");
        add(p, "resolution", "1280*2620");
        add(p, "dpi", "520");
        add(p, "update_version_code", "72432");
        add(p, "host_abi", "arm64-v8a");
        add(p, "dragon_device_type", "phone");
        add(p, "pv_player", "72432");
        add(p, "compliance_status", "0");
        add(p, "need_personal_recommend", "1");
        add(p, "player_so_load", "1");
        add(p, "is_android_pad_screen", "0");
        add(p, "rom_version", "miui_V816_OS3.0.9.0.WOLCNXM");
        return p;
    }

    private static void add(List<Map.Entry<String, String>> p, String k, String v) {
        p.add(new java.util.AbstractMap.SimpleEntry<>(k, v));
    }

    /** plan/v params: from is mandatory (missing from -> upstream 101116). */
    private List<Map.Entry<String, String>> planParams() {
        List<Map.Entry<String, String>> p = reading724Params();
        add(p, "scene", "10");
        add(p, "from", "video_ranklist");
        add(p, "bookstore_tab_type", "26");
        add(p, "selected_items", "");
        add(p, "sub_selected_items", "");
        add(p, "session_uuid", UUID.randomUUID().toString());
        add(p, "stick_ids", "");
        return p;
    }

    /**
     * cell/change params. offset "0" means first page (ChangeFilter=2),
     * anything else means GetMore=1. selectedItems takes the is_selected
     * group from the plan seed (ranklist landing page: all / ranklist_hot_sc).
     */
    private List<Map.Entry<String, String>> cellChangeParams(
            String cellId, String sessionId, String offset) {
        List<Map.Entry<String, String>> p = reading724Params();
        add(p, "cell_id", cellId);
        add(p, "last_search_query_from_rec", "false");
        add(p, "ClickedContent", "");
        add(p, "client_template", "2");
        add(p, "last_tab_index", "0");
        add(p, "screen_width_px", "1281");
        add(p, "classic_tab_style", "v3");
        add(p, "tab_type", "8");
        add(p, "first_use_category_select", "false");
        add(p, "last_session_video_tab_type", "0");
        add(p, "current_name", "");
        add(p, "disable_digg_stat", "false");
        add(p, "ecom_refresh_type", "0");
        add(p, "pad_column_cover", "0");
        add(p, "last_tab_type", "0");
        add(p, "req_rank_category_id", "0");
        add(p, "video_tab_cold_start", "0");
        add(p, "offset", offset == null ? "0" : offset);
        if (sessionId != null && !sessionId.isEmpty()) add(p, "session_id", sessionId);
        add(p, "selected_items", "all");
        add(p, "sub_selected_items", "ranklist_hot_sc");
        add(p, "video_type_preferences_str", "[]");
        add(p, "auth_aweme", "false");
        add(p, "cold_start_is_double_gd", "false");
        add(p, "page_entry_time", "0");
        add(p, "cold_start_session", "1");
        add(p, "tab_index", "0");
        add(p, "lore_tab_style", "v5");
        add(p, "device_level", "3");
        add(p, "unlimited_short_series_change_type", "0");
        add(p, "unlimited_selector_change_type", "0".equals(offset) ? "2" : "1");
        add(p, "book_id", "0");
        add(p, "ecom_impression_start_time", "0");
        add(p, "enable_search_box_collapse", "false");
        add(p, "landing_bottom_tab_type", "0");
        add(p, "bottom_tab_type", "0");
        add(p, "top_tab_extra", "");
        add(p, "migration_top_tab_enable", "false");
        add(p, "auth_backward", "true");
        add(p, "pad_column_detail", "0");
        add(p, "after_genre_preference_popup", "0");
        add(p, "client_req_type", "2");
        add(p, "req_rank_algo", "101");
        add(p, "unlimited_short_series_next_offset", "0");
        add(p, "normal_session_cnt_in_day", "6");
        add(p, "cold_start_session_cnt_in_day", "2");
        add(p, "sys_mini_window", "1");
        add(p, "app_mini_window", "0");
        add(p, "har_status", "0");
        add(p, "cold_start_session_cnt_in_life", "2");
        add(p, "charging", "0");
        add(p, "normal_session_cnt_in_life", "6");
        add(p, "is_power_save_mode", "0");
        add(p, "app_dark_mode", "0");
        add(p, "screen_brightness", "36");
        add(p, "battery_pct", "10");
        add(p, "down_speed", "51430");
        add(p, "sys_dark_mode", "0");
        add(p, "font_scale", "100");
        add(p, "network_type", "4");
        add(p, "current_volume", "0");
        return p;
    }

    // ------------------------------------------------------------ wire

    /** Go's url.Values.Encode: keys sorted byte-wise, Go query escaping. */
    public static String sortedQuery(List<Map.Entry<String, String>> params) {
        List<Map.Entry<String, String>> sorted = new ArrayList<>(params);
        Collections.sort(sorted, (a, b) -> a.getKey().compareTo(b.getKey()));
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : sorted) {
            if (sb.length() > 0) sb.append('&');
            sb.append(goEscape(e.getKey())).append('=').append(goEscape(e.getValue()));
        }
        return sb.toString();
    }

    private static String goEscape(String s) {
        if (s == null) return "";
        StringBuilder out = new StringBuilder();
        for (byte b : s.getBytes(StandardCharsets.UTF_8)) {
            int c = b & 0xFF;
            if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z')
                    || (c >= '0' && c <= '9') || c == '-' || c == '_' || c == '.' || c == '~') {
                out.append((char) c);
            } else if (c == ' ') {
                out.append('+');
            } else {
                out.append(String.format("%%%02X", c));
            }
        }
        return out.toString();
    }

    private String httpGet(String path, List<Map.Entry<String, String>> params) throws IOException {
        String query = sortedQuery(params);
        String url = HOST + path + "?" + query;
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("User-Agent", UA_DRAGON);
        headers.putAll(FqSign.generateHeaders(query, null));
        return executeWithRetry(url, headers, null);
    }

    private String httpPostJson(String rawUrl, String jsonBody, Map<String, String> headers)
            throws IOException {
        byte[] body = jsonBody.getBytes(StandardCharsets.UTF_8);
        String query = rawUrl.substring(rawUrl.indexOf('?') + 1);
        headers.putAll(FqSign.generateHeaders(query, body));
        return executeWithRetry(rawUrl, headers, body);
    }

    /**
     * GET (body null) or POST (body non-null) with up to MAX_ATTEMPTS tries.
     * The egress path to this upstream truncates large bodies mid-stream, so
     * a short read against Content-Length is retried, not accepted.
     */
    private String executeWithRetry(String url, Map<String, String> headers, byte[] body)
            throws IOException {
        IOException lastError = null;
        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            HttpURLConnection conn = null;
            try {
                conn = (HttpURLConnection) new URL(url).openConnection();
                conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
                conn.setReadTimeout(READ_TIMEOUT_MS);
                conn.setInstanceFollowRedirects(true);
                for (Map.Entry<String, String> h : headers.entrySet()) {
                    conn.setRequestProperty(h.getKey(), h.getValue());
                }
                if (body != null) {
                    conn.setRequestMethod("POST");
                    conn.setDoOutput(true);
                    conn.getOutputStream().write(body);
                }
                int status = conn.getResponseCode();
                if (status != 200) {
                    throw new IOException("fanqie HTTP " + status + " for " + url);
                }
                long want = conn.getContentLengthLong();
                byte[] data = readFully(conn.getInputStream());
                if (want > 0 && data.length < want) {
                    throw new IOException("truncated body: got " + data.length + " of " + want);
                }
                return new String(data, StandardCharsets.UTF_8);
            } catch (IOException e) {
                lastError = e;
                try {
                    Thread.sleep(2000L * (attempt + 1));
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                    throw new IOException("interrupted", e);
                }
            } finally {
                if (conn != null) conn.disconnect();
            }
        }
        throw lastError;
    }

    private static byte[] readFully(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
        return out.toByteArray();
    }

    // ------------------------------------------------------------ parsing

    private static String str(JsonObject o, String key) {
        if (o == null || !o.has(key) || o.get(key).isJsonNull()) return "";
        JsonElement e = o.get(key);
        if (e.isJsonPrimitive()) return e.getAsString();
        return e.toString();
    }

    private static long lng(JsonObject o, String key) {
        if (o == null || !o.has(key) || o.get(key).isJsonNull()) return 0;
        try {
            return o.get(key).getAsLong();
        } catch (Exception e) {
            try {
                return (long) Double.parseDouble(o.get(key).getAsString());
            } catch (Exception ignored) {
                return 0;
            }
        }
    }

    private static double dbl(JsonObject o, String key) {
        if (o == null || !o.has(key) || o.get(key).isJsonNull()) return 0;
        try {
            return o.get(key).getAsDouble();
        } catch (Exception e) {
            return 0;
        }
    }

    /** Recursively collect every non-empty video_data array in a response. */
    private static void collectVideoData(JsonElement el, List<JsonObject> out) {
        if (el == null || el.isJsonNull()) return;
        if (el.isJsonArray()) {
            for (JsonElement item : el.getAsJsonArray()) collectVideoData(item, out);
            return;
        }
        if (!el.isJsonObject()) return;
        JsonObject o = el.getAsJsonObject();
        if (o.has("video_data") && o.get("video_data").isJsonArray()
                && o.getAsJsonArray("video_data").size() > 0) {
            for (JsonElement item : o.getAsJsonArray("video_data")) {
                if (item.isJsonObject()) out.add(item.getAsJsonObject());
            }
            return;
        }
        for (Map.Entry<String, JsonElement> e : o.entrySet()) collectVideoData(e.getValue(), out);
    }

    private static DramaVideo toVideo(JsonObject o) {
        DramaVideo v = new DramaVideo();
        v.vid = str(o, "vid");
        v.seriesId = str(o, "series_id");
        v.title = str(o, "title");
        v.cover = str(o, "cover");
        v.desc = str(o, "video_desc");
        v.episodeCount = (int) lng(o, "episode_cnt");
        v.duration = lng(o, "duration");
        v.playCount = lng(o, "play_cnt");
        v.score = dbl(o, "score");
        return v;
    }

    private static void checkCode(JsonObject root, String what) throws IOException {
        long code = lng(root, "code");
        if (code != 0) {
            throw new IOException("fanqie " + what + " code=" + code);
        }
    }

    // ------------------------------------------------------------ feed

    /**
     * First feed page: plan/v seeds the cell, then cell/change returns the
     * page. If change comes back without videos, the plan page itself (10
     * real videos) is returned instead of a blank page. Mirrors Rust
     * fanqie_video_feed.
     */
    public FeedPage feedFirstPage() throws IOException {
        String planRaw = httpGet(PLAN_PATH, planParams());
        JsonObject plan = JsonParser.parseString(planRaw).getAsJsonObject();
        checkCode(plan, "plan/v");
        JsonArray data = plan.getAsJsonArray("data");
        if (data == null || data.size() == 0) throw new IOException("fanqie plan/v: no data");
        JsonObject first = data.get(0).getAsJsonObject();
        String cellId = str(first, "cell_id_str");
        String sessionId = str(first, "session_id");
        if (cellId.isEmpty() || sessionId.isEmpty()) {
            throw new IOException("fanqie plan/v: cell without cell_id/session_id");
        }
        FeedPage page = feedNextPage(cellId, sessionId, "0");
        if (!page.items.isEmpty()) return page;
        FeedPage fallback = new FeedPage();
        fallback.cellId = cellId;
        fallback.sessionId = sessionId;
        fallback.nextOffset = str(first, "next_offset");
        fallback.hasMore = true;
        List<JsonObject> rawItems = new ArrayList<>();
        collectVideoData(plan, rawItems);
        for (JsonObject o : rawItems) fallback.items.add(toVideo(o));
        return fallback;
    }

    /**
     * Next feed page. offset "0" is the first page (ChangeFilter=2); later
     * pages pass the previous page's nextOffset (GetMore=1).
     */
    public FeedPage feedNextPage(String cellId, String sessionId, String offset) throws IOException {
        String raw = httpGet(CHANGE_PATH, cellChangeParams(cellId, sessionId, offset));
        JsonObject root = JsonParser.parseString(raw).getAsJsonObject();
        checkCode(root, "cell/change");
        JsonObject data = root.has("data") && root.get("data").isJsonObject()
                ? root.getAsJsonObject("data") : new JsonObject();
        FeedPage page = new FeedPage();
        page.cellId = cellId;
        page.sessionId = str(data, "session_id");
        if (page.sessionId.isEmpty()) page.sessionId = sessionId == null ? "" : sessionId;
        page.nextOffset = str(data, "next_offset");
        if (data.has("has_more")) {
            try {
                page.hasMore = data.get("has_more").getAsBoolean();
            } catch (Exception ignored) {
                page.hasMore = !"false".equals(str(data, "has_more"));
            }
        } else {
            page.hasMore = !page.nextOffset.isEmpty();
        }
        List<JsonObject> rawItems = new ArrayList<>();
        collectVideoData(root, rawItems);
        for (JsonObject o : rawItems) page.items.add(toVideo(o));
        return page;
    }

    // ------------------------------------------------------------ playback

    /**
     * Resolve a playable stream for one episode. Picks the tallest
     * video_list entry whose URL resolves (plaintext base64 or spade).
     * The returned URL is signature-time-limited: resolve at play time.
     */
    public DramaStream videoModel(String videoId, String seriesId) throws IOException {
        List<Map.Entry<String, String>> p = reading724Params();
        add(p, "video_id", videoId);
        add(p, "series_id", seriesId);
        String raw = httpGet(VIDEO_MODEL_PATH, p);
        JsonObject root = JsonParser.parseString(raw).getAsJsonObject();
        checkCode(root, "video_model");
        JsonObject data = root.has("data") && root.get("data").isJsonObject()
                ? root.getAsJsonObject("data") : new JsonObject();
        JsonObject model = parseVideoModelObject(data);
        if (model == null) throw new IOException("fanqie video_model: no model");
        byte[] keySeed = null;
        String keySeedB64 = str(model, "key_seed");
        if (!keySeedB64.isEmpty()) {
            try {
                keySeed = Base64.getDecoder().decode(padB64(keySeedB64));
            } catch (Exception ignored) {
            }
        }
        DramaStream best = null;
        if (model.has("video_list") && model.get("video_list").isJsonObject()) {
            JsonObject list = model.getAsJsonObject("video_list");
            List<String> keys = new ArrayList<>(list.keySet());
            Collections.sort(keys);
            for (String key : keys) {
                JsonObject info = list.getAsJsonObject(key);
                String url = FqSign.resolveVariantUrl(
                        str(info, "main_url"), str(info, "backup_url_1"), keySeed);
                if (url == null || !url.startsWith("http")) continue;
                DramaStream s = new DramaStream();
                s.url = url;
                s.definition = str(info, "definition");
                s.width = (int) lng(info, "video_width");
                s.height = (int) lng(info, "video_height");
                s.bitrate = lng(info, "bitrate");
                s.size = lng(info, "size");
                s.duration = dbl(model, "video_duration");
                s.videoId = str(model, "video_id");
                s.fallbackApi = str(model, "fallback_api");
                if (best == null || s.height > best.height) best = s;
            }
        }
        if (best == null) {
            throw new IOException("fanqie video_model: no resolvable variant"
                    + " (fallback_api=" + str(model, "fallback_api") + ")");
        }
        return best;
    }

    private static JsonObject parseVideoModelObject(JsonObject data) {
        if (!data.has("video_model")) return null;
        JsonElement vm = data.get("video_model");
        if (vm.isJsonObject()) return vm.getAsJsonObject();
        if (vm.isJsonPrimitive() && vm.getAsJsonPrimitive().isString()) {
            try {
                JsonElement parsed = JsonParser.parseString(vm.getAsString());
                if (parsed.isJsonObject()) return parsed.getAsJsonObject();
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    private static String padB64(String s) {
        String cleaned = s.trim().replace("\r", "").replace("\n", "");
        int pad = (4 - cleaned.length() % 4) % 4;
        StringBuilder sb = new StringBuilder(cleaned);
        for (int i = 0; i < pad; i++) sb.append('=');
        return sb.toString();
    }

    /**
     * Full episode list for a series (title/intro/cover + vid per episode).
     * Carries no playback URL: call videoModel per episode at play time.
     */
    public DramaDetail videoDetail(String seriesId) throws IOException {
        String url = detailUrl();
        JsonObject body = new JsonObject();
        body.addProperty("series_id", seriesId);
        JsonObject biz = new JsonObject();
        biz.addProperty("device_level", 3);
        biz.addProperty("need_all_video_definition", true);
        biz.addProperty("need_mp4_align", false);
        biz.addProperty("source", 5);
        biz.addProperty("use_os_player", false);
        biz.addProperty("video_id_type", 1);
        body.add("biz_param", biz);
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("User-Agent", UA_PHOENIX);
        headers.put("Accept", "application/json; charset=utf-8,application/x-protobuf");
        headers.put("Content-Type", "application/json");
        headers.put("x-xs-from-web", "0");
        headers.put("sdk-version", "2");
        String raw = httpPostJson(url, body.toString(), headers);
        JsonObject root = JsonParser.parseString(raw).getAsJsonObject();
        checkCode(root, "video_detail");
        JsonObject data = root.has("data") && root.get("data").isJsonObject()
                ? root.getAsJsonObject("data") : new JsonObject();
        JsonObject vd = data.has("video_data") && data.get("video_data").isJsonObject()
                ? data.getAsJsonObject("video_data") : new JsonObject();
        DramaDetail detail = new DramaDetail();
        detail.seriesId = seriesId;
        detail.title = str(vd, "series_title");
        detail.intro = str(vd, "series_intro");
        detail.cover = str(vd, "series_cover");
        detail.episodeCount = (int) lng(vd, "episode_cnt");
        if (vd.has("video_list") && vd.get("video_list").isJsonArray()) {
            JsonArray list = vd.getAsJsonArray("video_list");
            for (JsonElement e : list) {
                if (!e.isJsonObject()) continue;
                JsonObject o = e.getAsJsonObject();
                DramaEpisode ep = new DramaEpisode();
                ep.vid = str(o, "vid");
                ep.seriesId = str(o, "series_id");
                ep.title = str(o, "title");
                ep.cover = str(o, "cover");
                ep.index = (int) lng(o, "vid_index");
                ep.duration = lng(o, "duration");
                detail.episodes.add(ep);
            }
        }
        return detail;
    }

    private String detailUrl() {
        try {
            StringBuilder sb = new StringBuilder(HOST);
            sb.append(VIDEO_DETAIL_PATH).append('?');
            sb.append("iid=").append(URLEncoder.encode(installId, "UTF-8"));
            sb.append("&device_id=").append(URLEncoder.encode(deviceId, "UTF-8"));
            sb.append("&ac=wifi&channel=update_64&aid=8662&app_name=novelread");
            sb.append("&version_code=71332&version_name=7.1.3.32&device_platform=android");
            sb.append("&os=android&ssmix=a&device_type=25053RT47C&device_brand=Redmi");
            sb.append("&language=zh&os_api=36&os_version=16&manifest_version_code=71332");
            sb.append("&resolution=1280*2772&dpi=520&update_version_code=71332");
            sb.append("&host_abi=arm64-v8a&dragon_device_type=phone&pv_player=71332");
            sb.append("&compliance_status=0&need_personal_recommend=1&player_so_load=1");
            sb.append("&is_android_pad_screen=0");
            return sb.toString();
        } catch (UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }
}
