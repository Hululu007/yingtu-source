package com.yingtu.plus.ui.activity;

import android.text.TextUtils;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.yingtu.plus.R;
import com.yingtu.plus.api.SiteApi;
import com.yingtu.plus.base.BaseActivity;
import com.yingtu.plus.bean.drive.DriveAccount;
import com.yingtu.plus.bean.drive.DriveCheckRequest;
import com.yingtu.plus.bean.drive.DriveCheckResponse;
import com.yingtu.plus.bean.drive.DriveCheckResult;
import com.yingtu.plus.bean.drive.DriveChecker;
import com.yingtu.plus.bean.drive.DriveStore;
import com.yingtu.plus.util.OkGo;
import com.yingtu.plus.utils.ResUtil;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserFactory;

import java.io.StringReader;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import okhttp3.Credentials;
import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * 网盘页：Alist / SMB / WebDAV 三个账号弹窗 + 文件浏览 + 分享链接检测。
 *
 * 账号只存本机（DriveStore → Prefers），不写死任何地址、不造假服务器。
 * 连接测试与浏览都是真请求：
 * Alist 走 /api/public/settings（测通）与 /api/fs/list、/api/fs/get（浏览/取直链）；
 * WebDAV 走 PROPFIND Depth:1（浏览）；
 * SMB 本版未内置协议库（依赖里没有 jcifs/smbj），只做 445 端口连通测试，
 * 文件列表如实显示“未接入”，不编假目录——要浏览须后续接 smbj。
 * 带账号的 WebDAV 直链进播放器时播放器不带认证头，可能播不了，已在点按时明说。
 *
 * 分享链接检测建在 bean/drive 四个数据类之上（DriveChecker），只做 GET 可达性，
 * 不破解提取码、不解析网盘页、不取真实文件地址。
 */
public class DriveActivity extends BaseActivity {

    private static final String[] VIDEO_EXT = {".mp4", ".mkv", ".avi", ".mov", ".flv", ".ts", ".m3u8", ".wmv", ".rmvb", ".webm"};

    private Spinner spAccount;
    private ArrayAdapter<String> accountAdapter;
    private List<DriveAccount> accounts = new ArrayList<>();
    private int accountIndex = -1;
    private TextView tvPath;
    private TextView tvStatus;
    private ListView lvFiles;
    private ArrayAdapter<String> fileAdapter;

    private static class DriveFile {
        String name;
        String path;
        boolean isDir;
        long size;
        String playUrl;

        DriveFile(String name, String path, boolean isDir, long size) {
            this.name = name;
            this.path = path;
            this.isDir = isDir;
            this.size = size;
        }
    }

    private final List<DriveFile> files = new ArrayList<>();
    private String currentPath = "/";

    @Override
    protected int getLayoutId() {
        return R.layout.activity_drive;
    }

    @Override
    protected void init() {
        find(R.id.ivBack).setOnClickListener(v -> finish());
        spAccount = find(R.id.spAccount);
        tvPath = find(R.id.tvPath);
        tvStatus = find(R.id.tvStatus);
        lvFiles = find(R.id.lvFiles);
        accountAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, new ArrayList<>());
        accountAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spAccount.setAdapter(accountAdapter);
        spAccount.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                accountIndex = position;
                currentPath = "/";
                browse();
            }

            @Override
            public void onNothingSelected(android.widget.AdapterView<?> parent) {
            }
        });
        fileAdapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, new ArrayList<>());
        lvFiles.setAdapter(fileAdapter);
        lvFiles.setOnItemClickListener((parent, view, position, id) -> {
            if (position < 0 || position >= files.size()) return;
            onFileClick(files.get(position));
        });
        find(R.id.btnTest).setOnClickListener(v -> testCurrent());
        find(R.id.btnAddAlist).setOnClickListener(v -> showAddDialog(DriveAccount.TYPE_ALIST));
        find(R.id.btnAddSmb).setOnClickListener(v -> showAddDialog(DriveAccount.TYPE_SMB));
        find(R.id.btnAddWebdav).setOnClickListener(v -> showAddDialog(DriveAccount.TYPE_WEBDAV));
        find(R.id.btnCheckLink).setOnClickListener(v -> showCheckLinkDialog());
        find(R.id.btnUp).setOnClickListener(v -> goUp());
        find(R.id.btnDelAccount).setOnClickListener(v -> deleteCurrentAccount());
        reloadAccounts();
    }

    private void reloadAccounts() {
        accounts = DriveStore.load();
        List<String> names = new ArrayList<>();
        for (DriveAccount a : accounts) names.add(a.typeName() + " · " + a.getName());
        accountAdapter.clear();
        accountAdapter.addAll(names);
        accountAdapter.notifyDataSetChanged();
        if (accounts.isEmpty()) {
            accountIndex = -1;
            setStatus("未添加网盘账号，点上面三个按钮添加。账号只存本机。");
            files.clear();
            refreshFileList();
            tvPath.setText("/");
        } else {
            if (accountIndex < 0 || accountIndex >= accounts.size()) accountIndex = 0;
            spAccount.setSelection(accountIndex);
        }
    }

    private DriveAccount currentAccount() {
        if (accountIndex < 0 || accountIndex >= accounts.size()) return null;
        return accounts.get(accountIndex);
    }

    private void setStatus(String msg) {
        tvStatus.setText(msg);
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }

    // ---------------- 添加账号（三个弹窗） ----------------

    private void showAddDialog(String type) {
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        int pad = ResUtil.dp2px(16);
        form.setPadding(pad, pad / 2, pad, 0);
        EditText etName = addField(form, "名称（必填，如：家里的 Alist）");
        EditText etAddress = addField(form, addressHint(type));
        EditText etUser = addField(form, userHint(type));
        EditText etPass = addField(form, "密码 / 令牌（可空）");
        EditText etExtra = null;
        if (DriveAccount.TYPE_ALIST.equals(type)) {
            etExtra = addField(form, "Alist 令牌 token（可空，有则浏览走鉴权）");
        } else if (DriveAccount.TYPE_SMB.equals(type)) {
            etExtra = addField(form, "共享名（必填，如：video）");
        }
        final EditText extra = etExtra;
        new AlertDialog.Builder(this)
                .setTitle("添加" + DriveAccount.typeName(type) + "账号")
                .setView(form)
                .setPositiveButton("保存", (d, w) -> {
                    String name = etName.getText().toString().trim();
                    String address = etAddress.getText().toString().trim();
                    if (TextUtils.isEmpty(name) || TextUtils.isEmpty(address)) {
                        toast("名称和地址都要填");
                        return;
                    }
                    if (DriveAccount.TYPE_SMB.equals(type) && (extra == null || TextUtils.isEmpty(extra.getText().toString().trim()))) {
                        toast("SMB 共享名必填");
                        return;
                    }
                    DriveAccount account = new DriveAccount(type, name, address,
                            etUser.getText().toString().trim(), etPass.getText().toString().trim(),
                            DriveAccount.TYPE_ALIST.equals(type) && extra != null ? extra.getText().toString().trim() : "",
                            DriveAccount.TYPE_SMB.equals(type) && extra != null ? extra.getText().toString().trim() : "");
                    DriveStore.add(account);
                    toast("已保存到本机");
                    reloadAccounts();
                    spAccount.setSelection(accounts.size() - 1);
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private String addressHint(String type) {
        if (DriveAccount.TYPE_SMB.equals(type)) return "地址（必填，如：192.168.1.2 或 smb://192.168.1.2）";
        if (DriveAccount.TYPE_WEBDAV.equals(type)) return "地址（必填，如：http://192.168.1.2:8080/dav）";
        return "地址（必填，如：http://192.168.1.2:5244）";
    }

    private String userHint(String type) {
        if (DriveAccount.TYPE_SMB.equals(type)) return "用户名（可空，匿名留空）";
        if (DriveAccount.TYPE_WEBDAV.equals(type)) return "用户名（可空）";
        return "用户名（可空，Alist 一般只用令牌）";
    }

    private EditText addField(LinearLayout form, String hint) {
        EditText et = new EditText(this);
        et.setHint(hint);
        et.setTextSize(14);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = ResUtil.dp2px(8);
        form.addView(et, lp);
        return et;
    }

    private void deleteCurrentAccount() {
        DriveAccount account = currentAccount();
        if (account == null) {
            toast("没有可删的账号");
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("删除账号")
                .setMessage("删掉「" + account.getName() + "」？只删本机保存的账号，不动服务器上的东西。")
                .setPositiveButton("删除", (d, w) -> {
                    DriveStore.remove(accountIndex);
                    accountIndex = -1;
                    currentPath = "/";
                    reloadAccounts();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    // ---------------- 连接测试 ----------------

    private void testCurrent() {
        DriveAccount account = currentAccount();
        if (account == null) {
            toast("先添加账号");
            return;
        }
        setStatus("正在测 " + account.typeName() + " " + account.getAddress() + " …");
        OkGo.io(() -> {
            String result;
            try {
                if (DriveAccount.TYPE_ALIST.equals(account.getType())) result = testAlist(account);
                else if (DriveAccount.TYPE_WEBDAV.equals(account.getType())) result = testWebdav(account);
                else result = testSmb(account);
            } catch (Throwable e) {
                result = "测试失败：" + e.getMessage();
            }
            final String msg = result;
            OkGo.main(() -> setStatus(msg));
        });
    }

    private String testAlist(DriveAccount account) throws Exception {
        Request request = new Request.Builder().url(account.getBaseUrl() + "/api/public/settings").build();
        try (Response response = OkGo.client().newCall(request).execute()) {
            if (response.code() == 200) return "Alist 通了 · HTTP 200（" + account.getAddress() + "）";
            return "Alist 没通 · HTTP " + response.code() + "（地址或服务可能不对）";
        }
    }

    private String testWebdav(DriveAccount account) throws Exception {
        Request.Builder builder = new Request.Builder()
                .url(joinUrl(account.getBaseUrl(), "/"))
                .method("PROPFIND", RequestBody.create(new byte[0], null))
                .header("Depth", "0");
        addBasicAuth(builder, account);
        try (Response response = OkGo.client().newCall(builder.build()).execute()) {
            if (response.code() == 207 || response.code() == 200) return "WebDAV 通了 · HTTP " + response.code();
            if (response.code() == 401) return "WebDAV 地址通了，但账号或密码不对 · HTTP 401";
            return "WebDAV 没通 · HTTP " + response.code();
        }
    }

    private String testSmb(DriveAccount account) {
        String host = account.getSmbHost();
        int port = account.getSmbPort();
        if (TextUtils.isEmpty(host)) return "SMB 地址里没有主机名";
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), 5000);
            return "SMB 主机 " + host + ":" + port + " 能连上（只测到端口通；本版未内置 SMB 协议库，文件列表还看不了）";
        } catch (Throwable e) {
            return "SMB 连不上 " + host + ":" + port + "（" + e.getMessage() + "）";
        }
    }

    // ---------------- 浏览 ----------------

    private void browse() {
        DriveAccount account = currentAccount();
        tvPath.setText(currentPath);
        if (account == null) return;
        if (DriveAccount.TYPE_SMB.equals(account.getType())) {
            files.clear();
            files.add(new DriveFile("SMB 浏览未接入：本版未内置 SMB 协议库（需接 smbj），只做了 445 端口连通测试。账号已保存，等接库后才能看目录。", "/", true, 0));
            refreshFileList();
            setStatus("SMB 只能测端口通不通，看不了目录。");
            return;
        }
        setStatus("正在拉 " + account.typeName() + " " + currentPath + " …");
        OkGo.io(() -> {
            try {
                List<DriveFile> list;
                if (DriveAccount.TYPE_ALIST.equals(account.getType())) list = listAlist(account, currentPath);
                else list = listWebdav(account, currentPath);
                OkGo.main(() -> {
                    files.clear();
                    files.addAll(list);
                    refreshFileList();
                    setStatus(account.typeName() + " " + currentPath + " · " + list.size() + " 项");
                });
            } catch (Throwable e) {
                OkGo.main(() -> setStatus("拉目录失败：" + e.getMessage()));
            }
        });
    }

    private void refreshFileList() {
        List<String> labels = new ArrayList<>();
        for (DriveFile f : files) {
            labels.add(f.isDir ? "📁 " + f.name : "🎬 " + f.name + (f.size > 0 ? " · " + formatSize(f.size) : ""));
        }
        fileAdapter.clear();
        fileAdapter.addAll(labels);
        fileAdapter.notifyDataSetChanged();
    }

    private void onFileClick(DriveFile file) {
        if (file.isDir) {
            if (file.path.equals(currentPath)) {
                toast(file.name);
                return;
            }
            currentPath = file.path;
            browse();
            return;
        }
        if (TextUtils.isEmpty(file.playUrl)) {
            toast("这个文件没有可播地址");
            return;
        }
        if (!isVideo(file.name)) {
            toast("只支持点播视频文件，其它文件播不了");
            return;
        }
        DriveAccount account = currentAccount();
        if (account != null && DriveAccount.TYPE_ALIST.equals(account.getType())
                && !TextUtils.isEmpty(account.token)) {
            setStatus("正在取 Alist 直链…");
            OkGo.io(() -> {
                final String url = alistPlayUrl(account, file.path);
                OkGo.main(() -> VideoActivity.start(this, SiteApi.PUSH, url, file.name));
            });
            return;
        }
        if (account != null && DriveAccount.TYPE_WEBDAV.equals(account.getType())
                && !TextUtils.isEmpty(account.username)) {
            toast("带账号的 WebDAV 直链：播放器不带认证头，可能播不了");
        }
        VideoActivity.start(this, SiteApi.PUSH, file.playUrl, file.name);
    }

    private void goUp() {
        if ("/".equals(currentPath)) return;
        int slash = currentPath.lastIndexOf('/');
        currentPath = slash <= 0 ? "/" : currentPath.substring(0, slash);
        browse();
    }

    private List<DriveFile> listAlist(DriveAccount account, String path) throws Exception {
        String json = "{\"path\":\"" + path.replace("\\", "\\\\").replace("\"", "\\\"") + "\"}";
        Request.Builder builder = new Request.Builder()
                .url(account.getBaseUrl() + "/api/fs/list")
                .post(RequestBody.create(json, MediaType.parse("application/json; charset=utf-8")));
        if (!TextUtils.isEmpty(account.token)) builder.header("Authorization", account.token);
        try (Response response = OkGo.client().newCall(builder.build()).execute()) {
            String body = response.body() != null ? response.body().string() : "";
            JsonObject root = JsonParser.parseString(body).getAsJsonObject();
            if (root.has("code") && root.get("code").getAsInt() != 200) {
                throw new Exception("Alist 回 " + root.get("code").getAsInt() + "：" + (root.has("message") ? root.get("message").getAsString() : ""));
            }
            JsonObject data = root.getAsJsonObject("data");
            JsonArray content = data != null && data.has("content") ? data.getAsJsonArray("content") : new JsonArray();
            List<DriveFile> list = new ArrayList<>();
            for (JsonElement element : content) {
                JsonObject obj = element.getAsJsonObject();
                String name = obj.has("name") ? obj.get("name").getAsString() : "";
                boolean isDir = obj.has("is_dir") && obj.get("is_dir").getAsBoolean();
                long size = obj.has("size") ? obj.get("size").getAsLong() : 0;
                String childPath = ("/".equals(path) ? "/" : path + "/") + name;
                DriveFile file = new DriveFile(name, childPath, isDir, size);
                // 列表里先给公开 /d 直链；有令牌时点播那一下再取 raw_url（不在列表里逐个请求）。
                if (!isDir) file.playUrl = account.getBaseUrl() + "/d" + childPath;
                list.add(file);
            }
            return list;
        }
    }

    /** Alist 直链：有令牌先走 /api/fs/get 取 raw_url，拿不到就回落到公开 /d 直链。 */
    private String alistPlayUrl(DriveAccount account, String path) {
        if (!TextUtils.isEmpty(account.token)) {
            try {
                String json = "{\"path\":\"" + path.replace("\\", "\\\\").replace("\"", "\\\"") + "\"}";
                Request request = new Request.Builder()
                        .url(account.getBaseUrl() + "/api/fs/get")
                        .post(RequestBody.create(json, MediaType.parse("application/json; charset=utf-8")))
                        .header("Authorization", account.token)
                        .build();
                try (Response response = OkGo.client().newCall(request).execute()) {
                    String body = response.body() != null ? response.body().string() : "";
                    JsonObject root = JsonParser.parseString(body).getAsJsonObject();
                    JsonObject data = root.has("data") ? root.getAsJsonObject("data") : null;
                    if (data != null && data.has("raw_url") && !TextUtils.isEmpty(data.get("raw_url").getAsString())) {
                        return data.get("raw_url").getAsString();
                    }
                }
            } catch (Throwable ignored) {
            }
        }
        return account.getBaseUrl() + "/d" + path;
    }

    private List<DriveFile> listWebdav(DriveAccount account, String path) throws Exception {
        Request.Builder builder = new Request.Builder()
                .url(joinUrl(account.getBaseUrl(), path))
                .method("PROPFIND", RequestBody.create(new byte[0], null))
                .header("Depth", "1");
        addBasicAuth(builder, account);
        try (Response response = OkGo.client().newCall(builder.build()).execute()) {
            if (response.code() == 401) throw new Exception("WebDAV 账号或密码不对（HTTP 401）");
            if (response.code() != 207 && response.code() != 200) throw new Exception("WebDAV 回 HTTP " + response.code());
            String body = response.body() != null ? response.body().string() : "";
            return parsePropfind(body, account, path);
        }
    }

    /** 解析 PROPFIND 的 multistatus：每条 response 取 href / displayname / 有无 collection。 */
    private List<DriveFile> parsePropfind(String xml, DriveAccount account, String currentDir) throws Exception {
        List<DriveFile> list = new ArrayList<>();
        XmlPullParserFactory factory = XmlPullParserFactory.newInstance();
        factory.setNamespaceAware(false);
        XmlPullParser parser = factory.newPullParser();
        parser.setInput(new StringReader(xml));
        String href = null;
        String displayName = null;
        boolean isDir = false;
        boolean inResponse = false;
        int event = parser.getEventType();
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG) {
                String tag = parser.getName();
                if (tag != null && tag.endsWith("response")) {
                    inResponse = true;
                    href = null;
                    displayName = null;
                    isDir = false;
                } else if (inResponse && tag != null && tag.endsWith("href")) {
                    href = parser.nextText();
                } else if (inResponse && tag != null && tag.endsWith("displayname")) {
                    displayName = parser.nextText();
                } else if (inResponse && tag != null && tag.endsWith("collection")) {
                    isDir = true;
                }
            } else if (event == XmlPullParser.END_TAG) {
                String tag = parser.getName();
                if (tag != null && tag.endsWith("response") && href != null) {
                    String decoded = URLDecoder.decode(href, StandardCharsets.UTF_8.name());
                    String basePath = pathOf(account.getBaseUrl());
                    String rel = decoded;
                    if (!TextUtils.isEmpty(basePath) && rel.startsWith(basePath)) rel = rel.substring(basePath.length());
                    if (TextUtils.isEmpty(rel)) rel = "/";
                    if (!rel.startsWith("/")) rel = "/" + rel;
                    if (isDir && rel.length() > 1 && rel.endsWith("/")) rel = rel.substring(0, rel.length() - 1);
                    // 第一条是当前目录自己，跳过。
                    if (!rel.equals(currentDir)) {
                        String name = TextUtils.isEmpty(displayName) ? lastSegment(rel) : displayName;
                        DriveFile file = new DriveFile(name, rel, isDir, 0);
                        if (!isDir) file.playUrl = joinUrl(account.getBaseUrl(), rel);
                        list.add(file);
                    }
                    inResponse = false;
                }
            }
            event = parser.next();
        }
        return list;
    }

    private void addBasicAuth(Request.Builder builder, DriveAccount account) {
        if (!TextUtils.isEmpty(account.username)) {
            builder.header("Authorization", Credentials.basic(account.username, account.password == null ? "" : account.password));
        }
    }

    private String joinUrl(String base, String path) {
        if (TextUtils.isEmpty(path)) return base;
        return base + (path.startsWith("/") ? path : "/" + path);
    }

    private String pathOf(String url) {
        try {
            return new java.net.URL(url).getPath();
        } catch (Throwable ignored) {
            return "";
        }
    }

    private String lastSegment(String path) {
        int slash = path.lastIndexOf('/');
        return slash >= 0 ? path.substring(slash + 1) : path;
    }

    private boolean isVideo(String name) {
        String lower = name.toLowerCase();
        for (String ext : VIDEO_EXT) if (lower.endsWith(ext)) return true;
        return false;
    }

    private String formatSize(long size) {
        if (size < 1024) return size + "B";
        if (size < 1024 * 1024) return (size / 1024) + "KB";
        if (size < 1024L * 1024 * 1024) return String.format("%.1fMB", size / 1024.0 / 1024.0);
        return String.format("%.1fGB", size / 1024.0 / 1024.0 / 1024.0);
    }

    // ---------------- 分享链接检测 ----------------

    private void showCheckLinkDialog() {
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        int pad = ResUtil.dp2px(16);
        form.setPadding(pad, pad / 2, pad, 0);
        Spinner spType = new Spinner(this);
        ArrayAdapter<String> typeAdapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item,
                new String[]{"阿里云盘", "123网盘", "移动云盘"});
        typeAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spType.setAdapter(typeAdapter);
        form.addView(spType);
        EditText etUrl = addField(form, "分享链接（必填）");
        EditText etPwd = addField(form, "提取码（可空）");
        new AlertDialog.Builder(this)
                .setTitle("检测分享链接")
                .setMessage("只测链接能不能打开，不破解提取码、不取文件。")
                .setView(form)
                .setPositiveButton("检测", (d, w) -> {
                    String url = etUrl.getText().toString().trim();
                    if (TextUtils.isEmpty(url)) {
                        toast("链接不能为空");
                        return;
                    }
                    String type = spType.getSelectedItemPosition() == 1 ? "123" : spType.getSelectedItemPosition() == 2 ? "mobile" : "aliyun";
                    // 走 bean/drive 的请求模型：DriveCheckItem → DriveCheckRequest → DriveChecker。
                    String json = "{\"items\":[{\"type\":\"" + type + "\",\"url\":\""
                            + url.replace("\\", "\\\\").replace("\"", "\\\"")
                            + "\",\"password\":\"" + etPwd.getText().toString().trim() + "\"}]}";
                    DriveCheckRequest request = new com.google.gson.Gson().fromJson(json, DriveCheckRequest.class);
                    setStatus("正在检测链接…");
                    DriveChecker.check(request, (DriveCheckResponse response) -> {
                        if (response.getResults().isEmpty()) {
                            setStatus("没测出结果");
                            return;
                        }
                        DriveCheckResult result = response.getResults().get(0);
                        setStatus("[" + result.getDiskType() + "] " + result.getState() + " · " + result.getSummary());
                    });
                })
                .setNegativeButton("取消", null)
                .show();
    }
}
