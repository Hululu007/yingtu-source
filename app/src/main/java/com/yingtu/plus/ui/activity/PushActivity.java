package com.yingtu.plus.ui.activity;

import android.content.Context;
import android.text.TextUtils;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.yingtu.plus.R;
import com.yingtu.plus.base.BaseActivity;
import com.yingtu.plus.util.Prefers;

import java.lang.reflect.Type;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 站内推送消息页 + FRP 内网穿透配置。
 *
 * 推送：本机收件箱（Prefers 里一条 JSON，最多留 100 条）。现在没有接任何推送通道，
 * 所以平时这里是空的——pushLocal() 是留给以后推送接收方（或测试按钮）往里写消息的入口，
 * 不是假数据。页面上的“测试”按钮会写一条明确标着“本地测试”的消息，用来验页面本身。
 *
 * FRP：只做到“服务器地址可配置、默认空”。FRP 要用户自己的中转服务器地址才能用，
 * 没地址就跑不起来；本版也没有内置 FRP 客户端二进制，填了地址也不会真的建隧道，
 * 需要后续接入 frp 客户端才行。页面上这两点都明说了，不装作已连通。
 */
public class PushActivity extends BaseActivity {

    private static final String KEY_INBOX = "push_inbox_json";
    private static final String KEY_FRP_SERVER = "push_frp_server";
    private static final String KEY_FRP_PORT = "push_frp_port";
    private static final String KEY_FRP_TOKEN = "push_frp_token";
    private static final String KEY_FRP_REMOTE_PORT = "push_frp_remote_port";
    private static final int MAX_INBOX = 100;

    private static final Gson GSON = new Gson();
    private static final Type LIST_TYPE = new TypeToken<List<PushMessage>>() {
    }.getType();

    public static class PushMessage {
        public String title;
        public String body;
        public long time;

        public PushMessage() {
        }

        public PushMessage(String title, String body, long time) {
            this.title = title;
            this.body = body;
            this.time = time;
        }
    }

    /** 以后推送接收方调这个往收件箱写消息；现在只有测试按钮在用。 */
    public static void pushLocal(Context context, String title, String body) {
        try {
            List<PushMessage> list = loadInbox();
            list.add(0, new PushMessage(title, body, System.currentTimeMillis()));
            while (list.size() > MAX_INBOX) list.remove(list.size() - 1);
            Prefers.put(KEY_INBOX, GSON.toJson(list));
        } catch (Throwable ignored) {
        }
    }

    private static List<PushMessage> loadInbox() {
        try {
            String json = Prefers.getString(KEY_INBOX, "");
            if (TextUtils.isEmpty(json)) return new ArrayList<>();
            List<PushMessage> list = GSON.fromJson(json, LIST_TYPE);
            return list == null ? new ArrayList<>() : list;
        } catch (Throwable ignored) {
            return new ArrayList<>();
        }
    }

    private ListView lvInbox;
    private ArrayAdapter<String> inboxAdapter;
    private TextView tvFrpStatus;
    private TextView tvEmpty;
    private EditText etFrpServer;
    private EditText etFrpPort;
    private EditText etFrpToken;
    private EditText etFrpRemotePort;

    @Override
    protected int getLayoutId() {
        return R.layout.activity_push;
    }

    @Override
    protected void init() {
        find(R.id.ivBack).setOnClickListener(v -> finish());
        lvInbox = find(R.id.lvInbox);
        tvFrpStatus = find(R.id.tvFrpStatus);
        tvEmpty = find(R.id.tvEmpty);
        etFrpServer = find(R.id.etFrpServer);
        etFrpPort = find(R.id.etFrpPort);
        etFrpToken = find(R.id.etFrpToken);
        etFrpRemotePort = find(R.id.etFrpRemotePort);
        inboxAdapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, new ArrayList<>());
        lvInbox.setAdapter(inboxAdapter);
        find(R.id.btnFrpSave).setOnClickListener(v -> saveFrp());
        find(R.id.btnFrpStart).setOnClickListener(v -> startFrp());
        find(R.id.btnPushTest).setOnClickListener(v -> {
            pushLocal(this, "本地测试消息", "这条是页面测试写的本地消息，不是服务器推来的。");
            refreshInbox();
            Toast.makeText(this, "已写一条本地测试消息", Toast.LENGTH_SHORT).show();
        });
        find(R.id.btnPushClear).setOnClickListener(v -> {
            Prefers.put(KEY_INBOX, "");
            refreshInbox();
            Toast.makeText(this, "收件箱已清空", Toast.LENGTH_SHORT).show();
        });
        etFrpServer.setText(Prefers.getString(KEY_FRP_SERVER, ""));
        etFrpPort.setText(Prefers.getString(KEY_FRP_PORT, "7000"));
        etFrpToken.setText(Prefers.getString(KEY_FRP_TOKEN, ""));
        etFrpRemotePort.setText(Prefers.getString(KEY_FRP_REMOTE_PORT, ""));
        refreshFrpStatus();
        refreshInbox();
    }

    private void saveFrp() {
        Prefers.put(KEY_FRP_SERVER, etFrpServer.getText().toString().trim());
        Prefers.put(KEY_FRP_PORT, etFrpPort.getText().toString().trim());
        Prefers.put(KEY_FRP_TOKEN, etFrpToken.getText().toString().trim());
        Prefers.put(KEY_FRP_REMOTE_PORT, etFrpRemotePort.getText().toString().trim());
        Toast.makeText(this, "FRP 配置已保存到本机", Toast.LENGTH_SHORT).show();
        refreshFrpStatus();
    }

    private void startFrp() {
        String server = Prefers.getString(KEY_FRP_SERVER, "").trim();
        if (TextUtils.isEmpty(server)) {
            tvFrpStatus.setText("未配置服务器地址，FRP 无法启动：FRP 要你自己的中转服务器地址才能用，请在上面填地址再保存。");
            Toast.makeText(this, "先填中转服务器地址", Toast.LENGTH_SHORT).show();
            return;
        }
        tvFrpStatus.setText("已保存配置（服务器 " + server + "），但本版未内置 FRP 客户端，填了地址也不会真的建隧道，需后续接入 frp 客户端才能用。");
        Toast.makeText(this, "本版未内置 FRP 客户端，建不了隧道", Toast.LENGTH_LONG).show();
    }

    private void refreshFrpStatus() {
        String server = Prefers.getString(KEY_FRP_SERVER, "").trim();
        if (TextUtils.isEmpty(server)) {
            tvFrpStatus.setText("FRP 未配置：服务器地址为空。FRP 要你自己的中转服务器地址才能用，没地址就跑不起来；本版也未内置 FRP 客户端，填了地址也只是先存着。");
        } else {
            tvFrpStatus.setText("FRP 服务器：" + server + "（已保存；本版未内置 FRP 客户端，暂不能真的建隧道）。");
        }
    }

    private void refreshInbox() {
        List<PushMessage> list = loadInbox();
        List<String> labels = new ArrayList<>();
        SimpleDateFormat fmt = new SimpleDateFormat("MM-dd HH:mm", Locale.getDefault());
        for (PushMessage m : list) {
            labels.add(fmt.format(new Date(m.time)) + "  " + m.title + "\n" + (m.body == null ? "" : m.body));
        }
        inboxAdapter.clear();
        inboxAdapter.addAll(labels);
        inboxAdapter.notifyDataSetChanged();
        tvEmpty.setVisibility(list.isEmpty() ? android.view.View.VISIBLE : android.view.View.GONE);
    }
}
