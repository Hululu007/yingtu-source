package com.yingtu.plus.ui.dialog;

import android.graphics.Color;
import android.os.Bundle;
import android.text.TextUtils;
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

import com.google.android.material.bottomsheet.BottomSheetDialogFragment;
import com.yingtu.plus.bean.History;
import com.yingtu.plus.event.CastEvent;
import com.yingtu.plus.ui.activity.VideoActivity;
import com.yingtu.plus.utils.ResUtil;

// 按调用点重建
public final class ReceiveDialog extends BottomSheetDialogFragment {

    private CastEvent event;

    public static ReceiveDialog create() {
        return new ReceiveDialog();
    }

    public ReceiveDialog event(CastEvent event) {
        this.event = event;
        return this;
    }

    public void show(FragmentActivity activity) {
        for (Fragment f : activity.getSupportFragmentManager().getFragments()) if (f instanceof ReceiveDialog) return;
        show(activity.getSupportFragmentManager(), null);
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        LinearLayout root = new LinearLayout(requireContext());
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(ResUtil.dp2px(16), ResUtil.dp2px(16), ResUtil.dp2px(16), ResUtil.dp2px(16));
        TextView title = new TextView(requireContext());
        title.setText("收到投屏");
        title.setTextSize(16);
        title.setTextColor(Color.WHITE);
        root.addView(title, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        History history = event == null ? null : event.history();
        String deviceName = event == null || event.device() == null ? "" : event.device().getName();
        if (!TextUtils.isEmpty(deviceName)) {
            TextView device = new TextView(requireContext());
            device.setText("来自：" + deviceName);
            device.setTextSize(14);
            device.setTextColor(0xFFCBD5E1);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            params.topMargin = ResUtil.dp2px(10);
            root.addView(device, params);
        }
        if (history != null && !TextUtils.isEmpty(history.vodName)) {
            TextView name = new TextView(requireContext());
            name.setText(history.vodName);
            name.setTextSize(14);
            name.setTextColor(Color.WHITE);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            params.topMargin = ResUtil.dp2px(6);
            root.addView(name, params);
        }
        TextView play = new TextView(requireContext());
        play.setText("播放");
        play.setTextSize(14);
        play.setTextColor(Color.WHITE);
        play.setGravity(Gravity.CENTER);
        play.setPadding(ResUtil.dp2px(12), ResUtil.dp2px(12), ResUtil.dp2px(12), ResUtil.dp2px(12));
        play.setBackgroundColor(0xFF4CAF50);
        LinearLayout.LayoutParams playParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        playParams.topMargin = ResUtil.dp2px(16);
        play.setLayoutParams(playParams);
        play.setOnClickListener(v -> {
            FragmentActivity activity = getActivity();
            if (activity != null && history != null) {
                VideoActivity.start(activity, history.sourceKey, history.vodId, history.vodName, history.vodPic);
            }
            dismiss();
        });
        root.addView(play);
        TextView ignore = new TextView(requireContext());
        ignore.setText("忽略");
        ignore.setTextSize(14);
        ignore.setTextColor(Color.WHITE);
        ignore.setGravity(Gravity.CENTER);
        ignore.setPadding(ResUtil.dp2px(12), ResUtil.dp2px(12), ResUtil.dp2px(12), ResUtil.dp2px(12));
        ignore.setBackgroundColor(0x33FFFFFF);
        LinearLayout.LayoutParams ignoreParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ignoreParams.topMargin = ResUtil.dp2px(8);
        ignore.setLayoutParams(ignoreParams);
        ignore.setOnClickListener(v -> dismiss());
        root.addView(ignore);
        return root;
    }
}
