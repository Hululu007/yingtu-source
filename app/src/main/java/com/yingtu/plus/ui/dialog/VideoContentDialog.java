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

import com.google.android.material.bottomsheet.BottomSheetDialogFragment;
import com.yingtu.plus.utils.ResUtil;
import com.yingtu.plus.utils.Util;

// 按调用点重建
public final class VideoContentDialog extends BottomSheetDialogFragment {

    private CharSequence content;

    public static VideoContentDialog create() {
        return new VideoContentDialog();
    }

    public VideoContentDialog content(CharSequence content) {
        this.content = content;
        return this;
    }

    public void show(FragmentActivity activity) {
        for (Fragment f : activity.getSupportFragmentManager().getFragments()) if (f instanceof VideoContentDialog) return;
        show(activity.getSupportFragmentManager(), null);
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        LinearLayout root = new LinearLayout(requireContext());
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(ResUtil.dp2px(16), ResUtil.dp2px(16), ResUtil.dp2px(16), ResUtil.dp2px(16));
        TextView title = new TextView(requireContext());
        title.setText("简介");
        title.setTextSize(16);
        title.setTextColor(Color.WHITE);
        root.addView(title, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        ScrollView scroll = new ScrollView(requireContext());
        TextView body = new TextView(requireContext());
        body.setText(content == null ? "" : content);
        body.setTextSize(14);
        body.setTextColor(0xFFCBD5E1);
        body.setLineSpacing(0, 1.4f);
        scroll.addView(body);
        LinearLayout.LayoutParams scrollParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ResUtil.dp2px(320));
        scrollParams.topMargin = ResUtil.dp2px(8);
        root.addView(scroll, scrollParams);
        TextView copy = new TextView(requireContext());
        copy.setText("复制");
        copy.setTextSize(14);
        copy.setTextColor(Color.WHITE);
        copy.setGravity(Gravity.CENTER);
        copy.setPadding(ResUtil.dp2px(12), ResUtil.dp2px(12), ResUtil.dp2px(12), ResUtil.dp2px(12));
        copy.setBackgroundColor(0xFF4CAF50);
        LinearLayout.LayoutParams copyParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        copyParams.topMargin = ResUtil.dp2px(12);
        copy.setLayoutParams(copyParams);
        copy.setOnClickListener(v -> {
            if (content != null) Util.copy(content.toString());
            dismiss();
        });
        root.addView(copy);
        return root;
    }
}
