package com.yingtu.plus.ui.dialog;

import android.graphics.Color;
import android.os.Bundle;
import android.text.TextUtils;
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

import java.util.LinkedHashMap;
import java.util.Map;

// 按调用点重建
public final class InfoDialog extends BottomSheetDialogFragment {

    public interface Listener {
        void onShare(CharSequence title);
    }

    private CharSequence title;
    private Map<String, String> headers = new LinkedHashMap<>();
    private String url;

    public static InfoDialog create() {
        return new InfoDialog();
    }

    public InfoDialog title(CharSequence title) {
        this.title = title;
        return this;
    }

    public InfoDialog headers(Map<String, String> headers) {
        this.headers = headers == null ? new LinkedHashMap<>() : headers;
        return this;
    }

    public InfoDialog url(String url) {
        this.url = url;
        return this;
    }

    public void show(FragmentActivity activity) {
        for (Fragment f : activity.getSupportFragmentManager().getFragments()) if (f instanceof InfoDialog) return;
        show(activity.getSupportFragmentManager(), null);
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        ScrollView scroll = new ScrollView(requireContext());
        LinearLayout root = new LinearLayout(requireContext());
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(ResUtil.dp2px(16), ResUtil.dp2px(16), ResUtil.dp2px(16), ResUtil.dp2px(16));
        TextView titleView = new TextView(requireContext());
        titleView.setText(title == null ? "" : title);
        titleView.setTextSize(16);
        titleView.setTextColor(Color.WHITE);
        root.addView(titleView, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        if (!TextUtils.isEmpty(url)) {
            TextView urlView = new TextView(requireContext());
            urlView.setText(url);
            urlView.setTextSize(13);
            urlView.setTextColor(0xFFCBD5E1);
            urlView.setOnClickListener(v -> Util.copy(url));
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            params.topMargin = ResUtil.dp2px(10);
            root.addView(urlView, params);
        }
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            TextView header = new TextView(requireContext());
            header.setText(entry.getKey() + ": " + entry.getValue());
            header.setTextSize(13);
            header.setTextColor(0xFFCBD5E1);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            params.topMargin = ResUtil.dp2px(4);
            root.addView(header, params);
        }
        TextView share = new TextView(requireContext());
        share.setText("分享");
        share.setTextSize(14);
        share.setTextColor(Color.WHITE);
        share.setGravity(Gravity.CENTER);
        share.setPadding(ResUtil.dp2px(12), ResUtil.dp2px(12), ResUtil.dp2px(12), ResUtil.dp2px(12));
        share.setBackgroundColor(0xFF4CAF50);
        LinearLayout.LayoutParams shareParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        shareParams.topMargin = ResUtil.dp2px(16);
        share.setLayoutParams(shareParams);
        share.setOnClickListener(v -> {
            FragmentActivity activity = getActivity();
            if (activity instanceof Listener listener) listener.onShare(title);
            dismiss();
        });
        root.addView(share);
        scroll.addView(root);
        return scroll;
    }
}
