package com.yingtu.plus.ui.activity;

import android.content.Intent;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import com.bumptech.glide.Glide;
import com.yingtu.plus.R;
import com.yingtu.plus.api.LiveApi;
import com.yingtu.plus.base.BaseActivity;
import com.yingtu.plus.bean.Channel;
import com.yingtu.plus.bean.Epg;
import com.yingtu.plus.bean.EpgChannel;
import com.yingtu.plus.bean.EpgData;
import com.yingtu.plus.bean.EpgDirectory;
import com.yingtu.plus.setting.LiveEpgSetting;
import com.yingtu.plus.util.OkGo;

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

/**
 * 直播节目单：两层。
 * 第一层是频道名录：包内 assets/epg_data.json（老包原样拷过来的数据文件），共 534 条，
 * 每条只有 tvid / epgid / 别名 / note / 台标，没有节目时间——这个文件本身就不是节目表。
 * 第二层是节目时间：点频道后走树里现成的 XMLTV 管线（LiveApi.getEpg + EpgParser，
 * 按 epgid 当 tvg-id 去对节目），节目源地址存在 LiveEpgSetting（live_epg_url，默认空）。
 * 没配地址、地址不对、源挂了，都如实显示，不编假节目单。
 * 点“去直播页播放”带别名跳 LiveActivity，直播页按别名找同名频道播。
 */
public class EpgActivity extends BaseActivity {

    private View listContainer;
    private View progContainer;
    private EditText etSearch;
    private TextView tvInfo;
    private TextView tvEmpty;
    private ListView lvChannels;
    private ArrayAdapter<EpgChannel> channelAdapter;
    private final List<EpgChannel> allChannels = new ArrayList<>();
    private final List<EpgChannel> shownChannels = new ArrayList<>();

    private TextView tvProgTitle;
    private TextView tvProgStatus;
    private ListView lvProg;
    private ArrayAdapter<String> progAdapter;
    private EditText etEpgUrl;
    private EpgChannel currentChannel;

    @Override
    protected int getLayoutId() {
        return R.layout.activity_epg;
    }

    @Override
    protected void init() {
        listContainer = find(R.id.listContainer);
        progContainer = find(R.id.progContainer);
        etSearch = find(R.id.etSearch);
        tvInfo = find(R.id.tvInfo);
        tvEmpty = find(R.id.tvEmpty);
        lvChannels = find(R.id.lvChannels);
        tvProgTitle = find(R.id.tvProgTitle);
        tvProgStatus = find(R.id.tvProgStatus);
        lvProg = find(R.id.lvProg);
        etEpgUrl = find(R.id.etEpgUrl);
        find(R.id.ivBack).setOnClickListener(v -> {
            if (progContainer.getVisibility() == View.VISIBLE) showList();
            else finish();
        });
        find(R.id.btnProgBack).setOnClickListener(v -> showList());
        find(R.id.btnProgPlay).setOnClickListener(v -> playCurrentChannel());
        find(R.id.btnSaveEpgUrl).setOnClickListener(v -> saveEpgUrl());

        channelAdapter = new ArrayAdapter<EpgChannel>(this, R.layout.item_epg_channel, shownChannels) {
            @Override
            public View getView(int position, View convertView, android.view.ViewGroup parent) {
                View view = convertView;
                if (view == null) {
                    view = getLayoutInflater().inflate(R.layout.item_epg_channel, parent, false);
                }
                EpgChannel channel = getItem(position);
                ImageView ivLogo = view.findViewById(R.id.ivLogo);
                TextView tvName = view.findViewById(R.id.tvName);
                TextView tvAlias = view.findViewById(R.id.tvAlias);
                if (channel != null) {
                    tvName.setText(channel.displayName());
                    List<String> aliases = channel.aliases();
                    tvAlias.setText(aliases.isEmpty() ? channel.epgid : TextUtils.join(" · ", aliases));
                    if (!TextUtils.isEmpty(channel.logo)) {
                        Glide.with(EpgActivity.this).load(channel.logo)
                                .placeholder(R.drawable.ic_live).error(R.drawable.ic_live).into(ivLogo);
                    } else {
                        ivLogo.setImageResource(R.drawable.ic_live);
                    }
                }
                return view;
            }
        };
        lvChannels.setAdapter(channelAdapter);
        lvChannels.setOnItemClickListener((parent, view, position, id) -> {
            if (position < 0 || position >= shownChannels.size()) return;
            showProgramme(shownChannels.get(position));
        });
        etSearch.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                applyFilter(s.toString());
            }

            @Override
            public void afterTextChanged(Editable s) {
            }
        });
        progAdapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, new ArrayList<>());
        lvProg.setAdapter(progAdapter);
        etEpgUrl.setText(LiveEpgSetting.getUrl());
        loadDirectory();
    }

    private void loadDirectory() {
        allChannels.clear();
        allChannels.addAll(EpgDirectory.load(this));
        if (allChannels.isEmpty()) {
            String err = EpgDirectory.getLastError();
            tvInfo.setText("节目单数据读不到" + (TextUtils.isEmpty(err) ? "" : "：" + err));
            tvEmpty.setVisibility(View.VISIBLE);
            tvEmpty.setText("epg_data.json 没读到频道，节目单是空的。");
        } else {
            tvInfo.setText("共 " + allChannels.size() + " 个频道 · 名录来自老包 epg_data.json；节目时间要配 XMLTV 节目源地址才能拉到，点频道查看。");
            tvEmpty.setVisibility(View.GONE);
        }
        applyFilter(etSearch.getText().toString());
    }

    private void applyFilter(String query) {
        shownChannels.clear();
        for (EpgChannel c : allChannels) {
            if (c.matches(query)) shownChannels.add(c);
        }
        channelAdapter.notifyDataSetChanged();
        if (allChannels.isEmpty()) return;
        tvEmpty.setVisibility(shownChannels.isEmpty() ? View.VISIBLE : View.GONE);
        if (shownChannels.isEmpty()) tvEmpty.setText("没找到这个频道，换个名字试试。");
    }

    private void showList() {
        currentChannel = null;
        progContainer.setVisibility(View.GONE);
        listContainer.setVisibility(View.VISIBLE);
    }

    private void showProgramme(EpgChannel channel) {
        currentChannel = channel;
        listContainer.setVisibility(View.GONE);
        progContainer.setVisibility(View.VISIBLE);
        tvProgTitle.setText(channel.displayName());
        progAdapter.clear();
        progAdapter.notifyDataSetChanged();
        String url = LiveEpgSetting.getUrl();
        if (TextUtils.isEmpty(url)) {
            tvProgStatus.setText("未配置节目源地址：这个文件只有频道名录，没有节目时间。填一个 XMLTV 节目源地址（支持 {date} 占位）保存后再看；不填就看不到节目时间。");
            return;
        }
        tvProgStatus.setText("正在从节目源拉今天的节目…（" + url + "）");
        OkGo.io(() -> {
            try {
                Channel item = new Channel();
                item.setName(channel.displayName());
                item.setTvgId(channel.epgid);
                item.setEpg(url);
                Epg epg = LiveApi.getEpg(item, ZoneId.systemDefault());
                List<String> lines = new ArrayList<>();
                for (EpgData data : epg.getList()) {
                    lines.add((data.isInRange() ? "▶ 正在播  " : "") + data.format());
                }
                OkGo.main(() -> {
                    if (currentChannel != channel) return;
                    if (lines.isEmpty()) {
                        tvProgStatus.setText("节目源没拉到节目：地址可能不对、源挂了，或这个台在源里没有节目。");
                    } else {
                        tvProgStatus.setText("今天共 " + lines.size() + " 档节目（▶ 是正在播的）");
                    }
                    progAdapter.clear();
                    progAdapter.addAll(lines);
                    progAdapter.notifyDataSetChanged();
                });
            } catch (Throwable e) {
                OkGo.main(() -> {
                    if (currentChannel != channel) return;
                    tvProgStatus.setText("拉节目单失败：" + e.getMessage());
                });
            }
        });
    }

    private void saveEpgUrl() {
        String url = etEpgUrl.getText().toString().trim();
        LiveEpgSetting.putUrl(url);
        if (!TextUtils.isEmpty(url)) LiveEpgSetting.addHistory(url);
        Toast.makeText(this, TextUtils.isEmpty(url) ? "节目源地址已清空" : "节目源地址已保存", Toast.LENGTH_SHORT).show();
        if (currentChannel != null) showProgramme(currentChannel);
    }

    private void playCurrentChannel() {
        if (currentChannel == null) return;
        Intent intent = new Intent(this, LiveActivity.class);
        intent.putExtra(LiveActivity.EXTRA_CHANNEL_NAME, currentChannel.displayName());
        intent.putExtra(LiveActivity.EXTRA_CHANNEL_ALIASES, TextUtils.join(",", currentChannel.aliases()));
        startActivity(intent);
    }
}
