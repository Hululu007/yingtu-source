package com.yingtu.plus.ui.activity;

import android.net.Uri;
import android.os.Environment;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;

import com.google.gson.reflect.TypeToken;
import com.yingtu.plus.App;
import com.yingtu.plus.R;
import com.yingtu.plus.api.ApiConfig;
import com.yingtu.plus.api.VodConfigSync;
import com.yingtu.plus.base.BaseActivity;
import com.yingtu.plus.bean.ParseBean;
import com.yingtu.plus.bean.SourceBean;
import com.yingtu.plus.util.CustomSites;
import com.yingtu.plus.util.HawkConfig;
import com.yingtu.plus.util.MixRules;
import com.yingtu.plus.util.OkGo;
import com.orhanobut.hawk.Hawk;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.Request;
import okhttp3.Response;

/**
 * 高级配置（旧版高级配置弹窗的 10 项左侧菜单），10 项全部是真功能。
 */
public class AdvancedConfigActivity extends BaseActivity {

    private static final String[] MENU = {
            "设备名称", "防失接口", "接口仓库", "常驻站源", "自定站源",
            "网址站源", "自定解析", "混合规则", "存储文件", "直播仓库"
    };

    private FrameLayout contentFrame;

    @Override
    protected int getLayoutId() {
        return R.layout.activity_advanced_config;
    }

    @Override
    protected void init() {
        find(R.id.ivBack).setOnClickListener(v -> finish());
        contentFrame = find(R.id.contentFrame);
        ListView menu = find(R.id.listMenu);
        menu.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, MENU));
        menu.setOnItemClickListener((parent, view, position, id) -> showPage(position));
        showPage(0);
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }

    private void showPage(int index) {
        contentFrame.removeAllViews();
        switch (index) {
            case 0: showDeviceName(); break;
            case 1: showBackupApi(); break;
            case 2: showConfigGroups(); break;
            case 3: showResidentSites(); break;
            case 4: showCustomSites(); break;
            case 5: showUrlSites(); break;
            case 6: showParses(); break;
            case 7: showMixRules(); break;
            case 8: showStoredFiles(); break;
            case 9: showLiveRepo(); break;
            default: showPending(MENU[index]);
        }
    }

    /** 待接入：没有对应核心，不做假功能。 */
    private void showPending(String name) {
        View view = LayoutInflater.from(this).inflate(R.layout.page_advanced_pending, contentFrame, false);
        ((TextView) view.findViewById(R.id.tvPendingTitle)).setText(name);
        ((TextView) view.findViewById(R.id.tvPendingNote)).setText(
                "待接入：咱们树里没有「" + name + "」对应的核心（接口/存储/规则），\n这一项现在只做展示，不做假功能。");
        contentFrame.addView(view);
    }

    // ===== 设备名称：输入框，存 Hawk，同步弹窗里当设备名展示 =====
    private void showDeviceName() {
        View view = LayoutInflater.from(this).inflate(R.layout.page_advanced_device, contentFrame, false);
        EditText et = view.findViewById(R.id.etDeviceName);
        et.setText(Hawk.get(HawkConfig.DEVICE_NAME, ""));
        view.findViewById(R.id.btnSaveDevice).setOnClickListener(v -> {
            Hawk.put(HawkConfig.DEVICE_NAME, et.getText().toString().trim());
            toast("设备名称已保存");
        });
        contentFrame.addView(view);
    }

    // ===== 防失接口：备用订阅地址，存 Hawk；主接口拉取/解析失败时 SourceActivity.loadConfig 自动改拉它 =====
    private void showBackupApi() {
        View view = LayoutInflater.from(this).inflate(R.layout.page_advanced_input, contentFrame, false);
        ((TextView) view.findViewById(R.id.tvInputTitle)).setText("防失接口");
        EditText et = view.findViewById(R.id.etInput);
        et.setHint("备用订阅地址（http:// 或 https://）");
        et.setText(Hawk.get(HawkConfig.BACKUP_API_URL, ""));
        ((TextView) view.findViewById(R.id.tvInputHint)).setText(
                "主接口拉取失败或解析失败时，自动改拉这个备用地址（与主地址不同才生效）；留空为不设。");
        view.findViewById(R.id.btnSaveInput).setOnClickListener(v -> {
            Hawk.put(HawkConfig.BACKUP_API_URL, et.getText().toString().trim());
            toast("防失接口已保存");
        });
        contentFrame.addView(view);
    }

    // ===== 常驻站源：多选当前站表，存 CustomSites；换订阅后 ApiConfig.applyCustomAndResidentSites 保留 =====
    private void showResidentSites() {
        View view = LayoutInflater.from(this).inflate(R.layout.page_advanced_list_add, contentFrame, false);
        ((TextView) view.findViewById(R.id.tvListTitle)).setText("常驻站源");
        Button btnAdd = view.findViewById(R.id.btnAddItem);
        btnAdd.setText("添加站源…");
        btnAdd.setOnClickListener(v -> showPickResidentDialog());
        List<SourceBean> resident = CustomSites.getResidentSites();
        List<String> names = new ArrayList<>();
        for (SourceBean s : resident) names.add(s.getName());
        ListView list = view.findViewById(R.id.listItems);
        if (names.isEmpty()) {
            ((TextView) view.findViewById(R.id.tvListEmpty)).setText("暂无常驻站源：添加后换订阅也不丢");
        }
        list.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, names));
        list.setOnItemClickListener((parent, v, position, id) -> {
            SourceBean s = resident.get(position);
            new AlertDialog.Builder(this)
                    .setTitle("移除常驻站源？")
                    .setMessage(s.getName())
                    .setPositiveButton("移除", (d, w) -> {
                        CustomSites.removeResidentSite(s.getKey());
                        toast("已移除常驻：" + s.getName());
                        showPage(3);
                    })
                    .setNegativeButton("取消", null)
                    .show();
        });
        contentFrame.addView(view);
    }

    private void showPickResidentDialog() {
        List<SourceBean> all = ApiConfig.get().getSourceBeanList();
        if (all.isEmpty()) {
            toast("当前没有可选站源");
            return;
        }
        List<SourceBean> resident = CustomSites.getResidentSites();
        Set<String> keys = new HashSet<>();
        for (SourceBean s : resident) keys.add(s.getKey());
        String[] names = new String[all.size()];
        boolean[] checked = new boolean[all.size()];
        for (int i = 0; i < all.size(); i++) {
            names[i] = all.get(i).getName();
            checked[i] = keys.contains(all.get(i).getKey());
        }
        new AlertDialog.Builder(this)
                .setTitle("常驻站源（换订阅后仍保留）")
                .setMultiChoiceItems(names, checked, (d, which, isChecked) -> checked[which] = isChecked)
                .setPositiveButton("保存", (d, w) -> {
                    List<SourceBean> sel = new ArrayList<>();
                    for (int i = 0; i < all.size(); i++) if (checked[i]) sel.add(all.get(i));
                    CustomSites.saveResidentSites(sel);
                    toast(sel.isEmpty() ? "已清空常驻站源" : "已设 " + sel.size() + " 个常驻站源");
                    showPage(3);
                })
                .setNegativeButton("取消", null)
                .show();
    }

    // ===== 自定站源：新增（名称、接口地址、类型 xml/json/js），立即生效，可删除 =====
    private void showCustomSites() {
        View view = LayoutInflater.from(this).inflate(R.layout.page_advanced_list_add, contentFrame, false);
        ((TextView) view.findViewById(R.id.tvListTitle)).setText("自定站源");
        Button btnAdd = view.findViewById(R.id.btnAddItem);
        btnAdd.setText("新增站源…");
        btnAdd.setOnClickListener(v -> showAddCustomSiteDialog());
        List<SourceBean> custom = CustomSites.getCustomSites();
        List<String> names = new ArrayList<>();
        for (SourceBean s : custom) names.add(s.getName() + "（" + s.getApi() + "）");
        ListView list = view.findViewById(R.id.listItems);
        if (names.isEmpty()) {
            ((TextView) view.findViewById(R.id.tvListEmpty)).setText("暂无自定站源：点“新增站源”添加");
        }
        list.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, names));
        list.setOnItemClickListener((parent, v, position, id) -> {
            SourceBean s = custom.get(position);
            new AlertDialog.Builder(this)
                    .setTitle("删除自定站源？")
                    .setMessage(s.getName() + "\n" + s.getApi())
                    .setPositiveButton("删除", (d, w) -> {
                        CustomSites.removeCustomSite(s.getKey());
                        ApiConfig.get().applyCustomAndResidentSites();
                        VodConfigSync.sync();
                        toast("已删除：" + s.getName());
                        showPage(4);
                    })
                    .setNegativeButton("取消", null)
                    .show();
        });
        contentFrame.addView(view);
    }

    private void showAddCustomSiteDialog() {
        String[] typeNames = {"xml", "json", "js"};
        int[] typeValues = {0, 1, 3};
        new AlertDialog.Builder(this)
                .setTitle("选择站源类型")
                .setSingleChoiceItems(typeNames, 1, (d, which) -> {
                    d.dismiss();
                    showAddCustomSiteInput(typeValues[which], typeNames[which]);
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void showAddCustomSiteInput(int type, String typeName) {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        layout.setPadding(pad, pad / 2, pad, pad / 2);
        EditText etName = new EditText(this);
        etName.setHint("站源名称");
        EditText etApi = new EditText(this);
        etApi.setHint("接口地址");
        layout.addView(etName);
        layout.addView(etApi);
        new AlertDialog.Builder(this)
                .setTitle("新增自定站源（" + typeName + "）")
                .setView(layout)
                .setPositiveButton("添加", (d, w) -> {
                    String name = etName.getText().toString().trim();
                    String api = etApi.getText().toString().trim();
                    if (name.isEmpty() || api.isEmpty()) {
                        toast("名称和接口地址都要填");
                        return;
                    }
                    SourceBean bean = new SourceBean();
                    bean.setKey("custom_" + System.currentTimeMillis());
                    bean.setName(name);
                    bean.setApi(api);
                    bean.setType(type);
                    CustomSites.addCustomSite(bean);
                    ApiConfig.get().applyCustomAndResidentSites();
                    VodConfigSync.sync();
                    toast("已添加：" + name);
                    showPage(4);
                })
                .setNegativeButton("取消", null)
                .show();
    }

    // ===== 网址站源：贴 URL，联网验证一次后按响应内容自动判类型，加入自定站源 =====
    private void showUrlSites() {
        View view = LayoutInflater.from(this).inflate(R.layout.page_advanced_input, contentFrame, false);
        ((TextView) view.findViewById(R.id.tvInputTitle)).setText("网址站源");
        EditText et = view.findViewById(R.id.etInput);
        et.setHint("站源地址（TVBox 配置 JSON 或 XML）");
        ((TextView) view.findViewById(R.id.tvInputHint)).setText(
                "贴一个地址，联网验证一次后按响应内容自动判类型（JSON 含 sites/class → json；含 <rss> → xml），加入自定站源。");
        Button btn = view.findViewById(R.id.btnSaveInput);
        btn.setText("验证并添加");
        btn.setOnClickListener(v -> {
            String url = et.getText().toString().trim();
            if (url.isEmpty()) {
                toast("请先贴一个地址");
                return;
            }
            verifyAndAddUrlSite(url);
        });
        contentFrame.addView(view);
    }

    private void verifyAndAddUrlSite(String url) {
        OkGo.client().newCall(new Request.Builder().url(url).build()).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                OkGo.main(() -> toast("验证失败：" + e.getMessage()));
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                String body = response.body() != null ? response.body().string() : "";
                OkGo.main(() -> {
                    String t = body.trim();
                    int type;
                    String typeName;
                    if (t.startsWith("<?xml") || t.contains("<rss")) {
                        type = 0;
                        typeName = "xml";
                    } else if (t.startsWith("{") || t.contains("\"sites\"") || t.contains("\"class\"")) {
                        type = 1;
                        typeName = "json";
                    } else {
                        type = 1;
                        typeName = "json（未识别内容，默认）";
                    }
                    String host = "";
                    try {
                        host = Uri.parse(url).getHost();
                    } catch (Throwable ignored) {
                    }
                    String name = TextUtils.isEmpty(host) ? "网址站源" : host;
                    SourceBean bean = new SourceBean();
                    bean.setKey("url_" + System.currentTimeMillis());
                    bean.setName(name);
                    bean.setApi(url);
                    bean.setType(type);
                    CustomSites.addCustomSite(bean);
                    ApiConfig.get().applyCustomAndResidentSites();
                    VodConfigSync.sync();
                    toast("已加入：" + name + "（" + typeName + "）");
                });
            }
        });
    }

    // ===== 混合规则：新建（名称 + 成员站多选，≥2 个站），可激活/删除；激活后首页推荐合并显示 =====
    private void showMixRules() {
        View view = LayoutInflater.from(this).inflate(R.layout.page_advanced_list_add, contentFrame, false);
        ((TextView) view.findViewById(R.id.tvListTitle)).setText("混合规则");
        Button btnAdd = view.findViewById(R.id.btnAddItem);
        btnAdd.setText("新建规则…");
        btnAdd.setOnClickListener(v -> showAddMixRuleDialog());
        List<MixRules.Rule> rules = MixRules.getRules();
        String active = MixRules.getActiveName();
        List<String> names = new ArrayList<>();
        for (MixRules.Rule r : rules) {
            names.add(r.name + "（" + r.keys.size() + " 站）" + (r.name.equals(active) ? "（已激活）" : ""));
        }
        ListView list = view.findViewById(R.id.listItems);
        if (names.isEmpty()) {
            ((TextView) view.findViewById(R.id.tvListEmpty)).setText("暂无混合规则：新建时至少选 2 个站，激活后首页推荐合并显示");
        }
        list.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, names));
        list.setOnItemClickListener((parent, v, position, id) -> {
            MixRules.Rule r = rules.get(position);
            boolean isActive = r.name.equals(active);
            new AlertDialog.Builder(this)
                    .setTitle(r.name)
                    .setItems(isActive
                            ? new String[]{"取消激活", "删除规则"}
                            : new String[]{"激活", "删除规则"}, (d, which) -> {
                        if (which == 0) {
                            if (isActive) {
                                MixRules.setActiveName("");
                                toast("已取消激活");
                            } else {
                                MixRules.setActiveName(r.name);
                                toast("已激活混合规则：" + r.name);
                            }
                        } else {
                            MixRules.removeRule(r.name);
                            toast("已删除规则：" + r.name);
                        }
                        showPage(7);
                    })
                    .setNegativeButton("取消", null)
                    .show();
        });
        contentFrame.addView(view);
    }

    private void showAddMixRuleDialog() {
        List<SourceBean> all = ApiConfig.get().getSourceBeanList();
        if (all.size() < 2) {
            toast("当前站源不足 2 个，混合规则至少要 2 个站");
            return;
        }
        String[] names = new String[all.size()];
        boolean[] checked = new boolean[all.size()];
        for (int i = 0; i < all.size(); i++) names[i] = all.get(i).getName();
        new AlertDialog.Builder(this)
                .setTitle("选择成员站（至少 2 个）")
                .setMultiChoiceItems(names, checked, (d, which, isChecked) -> checked[which] = isChecked)
                .setPositiveButton("下一步", (d, w) -> {
                    List<String> keys = new ArrayList<>();
                    for (int i = 0; i < all.size(); i++) if (checked[i]) keys.add(all.get(i).getKey());
                    if (keys.size() < 2) {
                        toast("至少选 2 个站");
                        return;
                    }
                    showMixRuleNameDialog(keys);
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void showMixRuleNameDialog(List<String> keys) {
        EditText et = new EditText(this);
        et.setHint("规则名称");
        new AlertDialog.Builder(this)
                .setTitle("新建混合规则")
                .setView(et)
                .setPositiveButton("保存", (d, w) -> {
                    String name = et.getText().toString().trim();
                    if (name.isEmpty()) {
                        toast("请填规则名称");
                        return;
                    }
                    MixRules.addRule(new MixRules.Rule(name, keys));
                    toast("已新建混合规则：" + name);
                    showPage(7);
                })
                .setNegativeButton("取消", null)
                .show();
    }

    // ===== 存储文件：应用 files 目录的 json + Download/影图+备份*.json + 当前接口缓存；点一行加载，长按删除文件 =====
    private void showStoredFiles() {
        View view = LayoutInflater.from(this).inflate(R.layout.page_advanced_list, contentFrame, false);
        ((TextView) view.findViewById(R.id.tvListTitle)).setText("存储文件");
        List<File> files = new ArrayList<>();
        File dir = getFilesDir();
        File[] inner = dir == null ? null : dir.listFiles();
        if (inner != null) {
            for (File f : inner) if (f.isFile() && f.getName().endsWith(".json")) files.add(f);
        }
        File dl = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
        File[] downs = dl == null ? null : dl.listFiles();
        if (downs != null) {
            for (File f : downs) {
                if (f.isFile() && f.getName().startsWith("影图+备份") && f.getName().endsWith(".json")) files.add(f);
            }
        }
        List<String> names = new ArrayList<>();
        String apiUrl = "";
        try {
            apiUrl = Hawk.get(HawkConfig.API_URL, "");
        } catch (Throwable ignored) {
        }
        names.add("当前接口缓存" + (TextUtils.isEmpty(apiUrl) ? "（未设置订阅地址）" : "（" + apiUrl + "）"));
        for (File f : files) names.add(f.getName() + "（" + (f.length() / 1024) + " KB）");
        ListView list = view.findViewById(R.id.listItems);
        if (files.isEmpty()) {
            ((TextView) view.findViewById(R.id.tvListEmpty)).setText("暂无本机配置文件：数据备份导出的文件在 Download/影图+备份*.json");
        }
        list.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, names));
        list.setOnItemClickListener((parent, v, position, id) -> {
            if (position == 0) {
                int count = 0;
                try {
                    count = ApiConfig.get().getSourceBeanList().size();
                } catch (Throwable ignored) {
                }
                new AlertDialog.Builder(this)
                        .setTitle("当前接口缓存")
                        .setMessage("订阅地址：" + (TextUtils.isEmpty(apiUrl) ? "未设置" : apiUrl)
                                + "\n当前站源：" + count + " 个\n（已在使用的配置，无需重复加载）")
                        .setPositiveButton("知道了", null)
                        .show();
                return;
            }
            loadStoredFile(files.get(position - 1));
        });
        list.setOnItemLongClickListener((parent, v, position, id) -> {
            if (position == 0) return false;
            File f = files.get(position - 1);
            new AlertDialog.Builder(this)
                    .setTitle("删除这个文件？")
                    .setMessage(f.getName())
                    .setPositiveButton("删除", (d, w) -> {
                        if (f.delete()) {
                            toast("已删除：" + f.getName());
                            showPage(8);
                        } else {
                            toast("删除失败");
                        }
                    })
                    .setNegativeButton("取消", null)
                    .show();
            return true;
        });
        contentFrame.addView(view);
    }

    /** 加载一个本机配置文件：顶层数组=SITES 文件（站列表，并入站表并记 key 供禁用位判断），
     * 对象=标准 TVBox 配置（直接解析成当前配置）。 */
    private void loadStoredFile(File f) {
        try {
            String body = readFileText(f);
            String t = body.trim();
            if (t.startsWith("[")) {
                List<SourceBean> sites = App.gson().fromJson(body,
                        new TypeToken<List<SourceBean>>() {}.getType());
                if (sites == null || sites.isEmpty()) {
                    toast("文件里没有站源");
                    return;
                }
                ApiConfig.get().parseConfigMerge("{\"sites\":" + App.gson().toJson(sites) + "}");
                List<String> keys = new ArrayList<>();
                for (SourceBean s : sites) {
                    if (s.getKey() != null && !s.getKey().isEmpty()) keys.add(s.getKey());
                }
                CustomSites.addSitesFileKeys(keys);
                VodConfigSync.sync();
                toast("已导入 SITES 文件，共 " + sites.size() + " 个站");
            } else {
                ApiConfig.get().parseConfig(body);
                Hawk.put(HawkConfig.API_CONFIG_CACHE, body);
                SourceActivity.recordConfigGroup(f.getName(), body);
                VodConfigSync.sync();
                toast("已加载：" + f.getName());
            }
        } catch (Throwable e) {
            toast("加载失败：" + e.getMessage());
        }
    }

    private static String readFileText(File f) throws IOException {
        try (InputStream in = new FileInputStream(f);
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toString("UTF-8");
        }
    }

    // ===== 接口仓库：配置组列表（复用站源管理的分组记录），点一行切到该组 =====
    private void showConfigGroups() {
        View view = LayoutInflater.from(this).inflate(R.layout.page_advanced_list, contentFrame, false);
        ((TextView) view.findViewById(R.id.tvListTitle)).setText("接口仓库（配置组）");
        List<SourceActivity.ConfigGroup> groups = SourceActivity.loadStoredGroups();
        List<String> names = new ArrayList<>();
        for (SourceActivity.ConfigGroup g : groups) names.add(g.name);
        ListView list = view.findViewById(R.id.listItems);
        if (names.isEmpty()) {
            ((TextView) view.findViewById(R.id.tvListEmpty)).setText("暂无配置组：去站源管理加载一个订阅地址");
        }
        list.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, names));
        list.setOnItemClickListener((parent, v, position, id) -> {
            SourceActivity.ConfigGroup g = groups.get(position);
            try {
                ApiConfig.get().parseConfig(g.body);
                Hawk.put(HawkConfig.API_CONFIG_CACHE, g.body);
                VodConfigSync.sync();
                toast("已切换到：" + g.name);
            } catch (Throwable e) {
                toast("切换失败：该组配置已损坏");
            }
        });
        contentFrame.addView(view);
    }

    // ===== 自定解析：解析接口列表（复用 ApiConfig 的 parse 列表），点一行设为默认 =====
    private void showParses() {
        View view = LayoutInflater.from(this).inflate(R.layout.page_advanced_list, contentFrame, false);
        ((TextView) view.findViewById(R.id.tvListTitle)).setText("自定解析");
        List<ParseBean> parses = ApiConfig.get().getParseBeanList();
        List<String> names = new ArrayList<>();
        for (ParseBean p : parses) names.add(p.getName() + (p.isSelected() ? "（当前默认）" : ""));
        ListView list = view.findViewById(R.id.listItems);
        if (names.isEmpty()) {
            ((TextView) view.findViewById(R.id.tvListEmpty)).setText("当前源未配置解析接口");
        }
        list.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, names));
        list.setOnItemClickListener((parent, v, position, id) -> {
            ApiConfig.get().setDefaultParse(parses.get(position));
            toast("默认解析已设为：" + parses.get(position).getName());
            showPage(6);
        });
        contentFrame.addView(view);
    }

    // ===== 直播仓库：每行“名称,列表链接”，存在 Hawk，直播页 getEffectiveSources 时并进源列表 =====
    private void showLiveRepo() {
        View view = LayoutInflater.from(this).inflate(R.layout.page_advanced_live_repo, contentFrame, false);
        ListView list = view.findViewById(R.id.listRepo);
        EditText et = view.findViewById(R.id.etRepoLine);
        Runnable refresh = () -> {
            List<String> lines = getRepoLines();
            list.setAdapter(new ArrayAdapter<>(AdvancedConfigActivity.this,
                    android.R.layout.simple_list_item_1, lines));
        };
        refresh.run();
        view.findViewById(R.id.btnAddRepo).setOnClickListener(v -> {
            String line = et.getText().toString().trim();
            if (!line.contains(",") && !line.contains("，")) {
                toast("格式不对，应为“名称,列表链接”，每行一个");
                return;
            }
            line = line.replace("，", ",");
            String[] parts = line.split(",", 2);
            if (parts.length < 2 || TextUtils.isEmpty(parts[0].trim()) || TextUtils.isEmpty(parts[1].trim())) {
                toast("格式不对，应为“名称,列表链接”");
                return;
            }
            List<String> lines = getRepoLines();
            lines.add(parts[0].trim() + "," + parts[1].trim());
            saveRepoLines(lines);
            et.setText("");
            refresh.run();
            toast("已加入直播仓库，打开直播页即有这条源");
        });
        list.setOnItemClickListener((parent, v, position, id) -> {
            List<String> lines = getRepoLines();
            String line = lines.get(position);
            new AlertDialog.Builder(this)
                    .setTitle("删除这条？")
                    .setMessage(line)
                    .setPositiveButton("删除", (d, w) -> {
                        lines.remove(position);
                        saveRepoLines(lines);
                        refresh.run();
                    })
                    .setNegativeButton("取消", null)
                    .show();
        });
        contentFrame.addView(view);
    }

    @SuppressWarnings("unchecked")
    private List<String> getRepoLines() {
        try {
            List<String> list = Hawk.get(HawkConfig.LIVE_REPO, new ArrayList<String>());
            return list == null ? new ArrayList<>() : new ArrayList<>(list);
        } catch (Throwable e) {
            return new ArrayList<>();
        }
    }

    private void saveRepoLines(List<String> lines) {
        try {
            Hawk.put(HawkConfig.LIVE_REPO, lines);
        } catch (Throwable ignored) {
        }
    }
}
