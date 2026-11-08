package com.yingtu.plus.ui.dialog;

import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.media3.common.C;

import com.google.android.material.bottomsheet.BottomSheetDialogFragment;
import com.yingtu.plus.api.config.VodConfig;
import com.yingtu.plus.bean.History;
import com.yingtu.plus.bean.Parse;
import com.yingtu.plus.databinding.ActivityVideoBinding;
import com.yingtu.plus.player.PlayerManager;
import com.yingtu.plus.player.lut.LutPreset;
import com.yingtu.plus.utils.ResUtil;

// 按调用点重建
public final class ControlDialog extends BottomSheetDialogFragment {

    public interface Listener {
        void onTrackPanel(int type);
        void onTitlePanel();
        void onDanmakuPanel();
        void onCodecCapabilityPanel();
        void onPlayParamsPanel();
        ActivityVideoBinding getControlBinding();
        PlayerManager getControlPlayer();
        History getControlHistory();
        boolean isControlParseEnabled();
        boolean isControlAudioContent();
        void onRepeatModeChanged(int repeatMode);
        void onLutPanel();
        void onLutImport();
        void onLutDir();
        void onLutSelected(LutPreset preset);
        void onImmersiveAudioModeChanged();
        void onKaraokeModeChanged();
        void onKaraokeTrackPanel();
        void onScale(int tag);
        void onEpisodeColumn(int column);
        void onCompactEpisodeTitleChanged();
        void onParse(Parse item);
    }

    private ActivityVideoBinding parent;
    private History history;
    private boolean parse;
    private PlayerManager player;
    private TextView parseView;
    private int column = 6;

    public static ControlDialog create() {
        return new ControlDialog();
    }

    public ControlDialog parent(ActivityVideoBinding parent) {
        this.parent = parent;
        return this;
    }

    public ControlDialog history(History history) {
        this.history = history;
        return this;
    }

    public ControlDialog parse(boolean parse) {
        this.parse = parse;
        return this;
    }

    public ControlDialog player(PlayerManager player) {
        this.player = player;
        return this;
    }

    public void show(FragmentActivity activity) {
        for (Fragment f : activity.getSupportFragmentManager().getFragments()) if (f instanceof ControlDialog) return;
        show(activity.getSupportFragmentManager(), null);
    }

    public void setPlayer() {
        if (player == null) return;
        refreshParseView();
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        ScrollView scroll = new ScrollView(requireContext());
        LinearLayout root = new LinearLayout(requireContext());
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(ResUtil.dp2px(16), ResUtil.dp2px(16), ResUtil.dp2px(16), ResUtil.dp2px(16));
        TextView title = new TextView(requireContext());
        title.setText("播放设置");
        title.setTextSize(16);
        title.setTextColor(Color.WHITE);
        root.addView(title, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        parseView = row(root, "解析线路", v -> {
            Listener listener = listener();
            if (listener == null) return;
            Parse current = null;
            try {
                current = VodConfig.get().getParse();
            } catch (Exception ignored) {
            }
            if (current != null) listener.onParse(current);
            dismiss();
        });
        row(root, "音频轨道", v -> call(listener -> listener.onTrackPanel(C.TRACK_TYPE_AUDIO)));
        row(root, "字幕轨道", v -> call(listener -> listener.onTrackPanel(C.TRACK_TYPE_TEXT)));
        row(root, "选择版本", v -> call(Listener::onTitlePanel));
        row(root, "弹幕设置", v -> call(Listener::onDanmakuPanel));
        row(root, "解码能力", v -> call(Listener::onCodecCapabilityPanel));
        row(root, "播放参数", v -> call(Listener::onPlayParamsPanel));
        row(root, "滤镜", v -> call(Listener::onLutPanel));
        row(root, "导入滤镜文件", v -> call(Listener::onLutImport));
        row(root, "选择滤镜目录", v -> call(Listener::onLutDir));
        row(root, "循环播放", v -> call(listener -> listener.onRepeatModeChanged(player != null && player.isRepeatOne() ? 0 : 1)));
        row(root, "画面比例", v -> call(listener -> listener.onScale(0)));
        row(root, "选集列数", v -> {
            column = column >= 10 ? 4 : column + 2;
            call(listener -> listener.onEpisodeColumn(column));
        });
        row(root, "精简集名", v -> call(Listener::onCompactEpisodeTitleChanged));
        row(root, "沉浸音频", v -> call(Listener::onImmersiveAudioModeChanged));
        row(root, "卡拉OK模式", v -> call(Listener::onKaraokeModeChanged));
        row(root, "卡拉OK音轨", v -> call(Listener::onKaraokeTrackPanel));
        scroll.addView(root);
        return scroll;
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        refreshParseView();
    }

    private void refreshParseView() {
        if (parseView == null) return;
        parseView.setText("解析线路：" + (parse ? "开" : "关"));
    }

    private TextView row(LinearLayout root, String text, View.OnClickListener listener) {
        TextView view = new TextView(requireContext());
        view.setText(text);
        view.setTextSize(14);
        view.setTextColor(Color.WHITE);
        view.setPadding(ResUtil.dp2px(12), ResUtil.dp2px(12), ResUtil.dp2px(12), ResUtil.dp2px(12));
        view.setGravity(Gravity.CENTER_VERTICAL);
        view.setBackgroundColor(0x22FFFFFF);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = ResUtil.dp2px(8);
        view.setLayoutParams(params);
        view.setOnClickListener(listener);
        root.addView(view);
        return view;
    }

    private Listener listener() {
        FragmentActivity activity = getActivity();
        return activity instanceof Listener listener ? listener : null;
    }

    private void call(java.util.function.Consumer<Listener> action) {
        Listener listener = listener();
        if (listener == null) return;
        action.accept(listener);
        dismiss();
    }
}
