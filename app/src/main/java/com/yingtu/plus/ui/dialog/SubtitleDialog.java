package com.yingtu.plus.ui.dialog;

import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.media3.ui.SubtitleView;

import com.google.android.material.bottomsheet.BottomSheetDialogFragment;
import com.yingtu.plus.player.PlayerManager;
import com.yingtu.plus.setting.PlayerSetting;
import com.yingtu.plus.utils.ResUtil;

// 对照 webhtv 重写
public final class SubtitleDialog extends BottomSheetDialogFragment {

    private static final float DEFAULT_TEXT_SIZE = 0.0533f;
    private static final float TEXT_STEP = 0.002f;
    private static final int POSITION_STEP = 1;

    private SubtitleView subtitleView;
    private PlayerManager player;
    private float textSize;
    private int position;

    public static SubtitleDialog create() {
        return new SubtitleDialog();
    }

    public SubtitleDialog view(SubtitleView subtitleView) {
        this.subtitleView = subtitleView;
        return this;
    }

    public SubtitleDialog player(PlayerManager player) {
        this.player = player;
        return this;
    }

    public void show(FragmentActivity activity) {
        for (Fragment f : activity.getSupportFragmentManager().getFragments()) if (f instanceof SubtitleDialog) return;
        show(activity.getSupportFragmentManager(), null);
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        textSize = currentTextSize();
        position = PlayerSetting.getSubtitlePosition();
        LinearLayout root = new LinearLayout(requireContext());
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(ResUtil.dp2px(16), ResUtil.dp2px(16), ResUtil.dp2px(16), ResUtil.dp2px(16));
        TextView title = new TextView(requireContext());
        title.setText("字幕设置");
        title.setTextSize(16);
        title.setTextColor(Color.WHITE);
        root.addView(title, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(button("上移", v -> onUp()));
        root.addView(button("下移", v -> onDown()));
        root.addView(button("放大", v -> onLarge()));
        root.addView(button("缩小", v -> onSmall()));
        root.addView(button("重置", v -> onReset()));
        return root;
    }

    private TextView button(String text, View.OnClickListener listener) {
        TextView view = new TextView(requireContext());
        view.setText(text);
        view.setTextSize(14);
        view.setTextColor(Color.WHITE);
        view.setPadding(ResUtil.dp2px(12), ResUtil.dp2px(10), ResUtil.dp2px(12), ResUtil.dp2px(10));
        view.setGravity(Gravity.CENTER);
        view.setBackgroundColor(0x33FFFFFF);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = ResUtil.dp2px(8);
        view.setLayoutParams(params);
        view.setOnClickListener(listener);
        return view;
    }

    private void onUp() {
        if (isNativeSubtitleStyle()) {
            setNativePosition(position + POSITION_STEP);
            return;
        }
        if (subtitleView == null) return;
        position = position + POSITION_STEP;
        subtitleView.setBottomPosition(position);
    }

    private void onDown() {
        if (isNativeSubtitleStyle()) {
            setNativePosition(position - POSITION_STEP);
            return;
        }
        if (subtitleView == null) return;
        position = position - POSITION_STEP;
        subtitleView.setBottomPosition(position);
    }

    private void onLarge() {
        if (isNativeSubtitleStyle()) {
            setNativeTextSize(currentTextSize() + TEXT_STEP);
            return;
        }
        if (subtitleView == null) return;
        textSize = textSize + TEXT_STEP;
        subtitleView.setFractionalTextSize(textSize);
    }

    private void onSmall() {
        if (isNativeSubtitleStyle()) {
            setNativeTextSize(currentTextSize() - TEXT_STEP);
            return;
        }
        if (subtitleView == null) return;
        textSize = Math.max(0.01f, textSize - TEXT_STEP);
        subtitleView.setFractionalTextSize(textSize);
    }

    private void onReset() {
        textSize = 0.0f;
        position = 0;
        if (isNativeSubtitleStyle()) {
            player.setSubtitleStyle(0.0f, 0.0f);
        } else if (subtitleView != null) {
            subtitleView.setFractionalTextSize(DEFAULT_TEXT_SIZE);
            subtitleView.setBottomPosition(0);
        }
    }

    private boolean isNativeSubtitleStyle() {
        return player != null && player.supportsSubtitleStyle();
    }

    private float currentTextSize() {
        float value = PlayerSetting.getSubtitleTextSize();
        return value <= 0 ? DEFAULT_TEXT_SIZE : value;
    }

    private void setNativeTextSize(float value) {
        value = Math.max(0.02f, Math.min(0.12f, value));
        textSize = value;
        player.setSubtitleStyle(value, position);
    }

    private void setNativePosition(float value) {
        value = Math.max(-50f, Math.min(100f, value));
        position = Math.round(value);
        player.setSubtitleStyle(textSize, value);
    }

    @Override
    public void onResume() {
        super.onResume();
        if (getDialog() != null && getDialog().getWindow() != null) {
            getDialog().getWindow().setLayout(ResUtil.dp2px(216), ViewGroup.LayoutParams.WRAP_CONTENT);
        }
    }
}
