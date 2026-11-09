package com.yingtu.plus.player;

import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.DialogFragment;
import androidx.fragment.app.FragmentActivity;

import com.yingtu.plus.utils.ResUtil;

/**
 * 小说查看器：全屏 DialogFragment，不新建 Activity，不动 AndroidManifest。
 * 顶栏（返回 + 标题）+ 可滚动正文，大字可读。v1 不记阅读进度。
 * 返回键 / 点“返回”都是 dismiss，回到下面的播放页，再按返回回到详情页。
 */
public final class NovelViewer extends DialogFragment {

    private static final String TAG = "NonVideoNovel";
    private static final String ARG_TITLE = "title";
    private static final String ARG_CONTENT = "content";

    public static void show(FragmentActivity activity, NonVideoRouter.NovelData data, String fallbackTitle) {
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) return;
        if (activity.getSupportFragmentManager().findFragmentByTag(TAG) != null) return;
        String title = data.title;
        if (TextUtils.isEmpty(title)) title = fallbackTitle == null ? "" : fallbackTitle;
        NovelViewer viewer = new NovelViewer();
        Bundle args = new Bundle();
        args.putString(ARG_TITLE, title);
        args.putString(ARG_CONTENT, data.content);
        viewer.setArguments(args);
        viewer.show(activity.getSupportFragmentManager(), TAG);
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setStyle(STYLE_NORMAL, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
    }

    @Override
    public void onStart() {
        super.onStart();
        Window window = getDialog() == null ? null : getDialog().getWindow();
        if (window == null) return;
        window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        window.setBackgroundDrawable(new ColorDrawable(Color.BLACK));
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        Bundle args = getArguments();
        String title = args == null ? "" : args.getString(ARG_TITLE, "");
        String content = args == null ? "" : args.getString(ARG_CONTENT, "");

        LinearLayout root = new LinearLayout(requireContext());
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.BLACK);
        root.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout topBar = new LinearLayout(requireContext());
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        topBar.setBackgroundColor(0xFF1A1A1A);
        topBar.setPadding(ResUtil.dp2px(8), ResUtil.dp2px(10), ResUtil.dp2px(16), ResUtil.dp2px(10));

        TextView back = new TextView(requireContext());
        back.setText("‹ 返回");
        back.setTextSize(17);
        back.setTextColor(Color.WHITE);
        back.setPadding(ResUtil.dp2px(8), ResUtil.dp2px(4), ResUtil.dp2px(16), ResUtil.dp2px(4));
        back.setOnClickListener(v -> dismiss());
        topBar.addView(back);

        TextView titleView = new TextView(requireContext());
        titleView.setText(TextUtils.isEmpty(title) ? "小说" : title);
        titleView.setTextSize(17);
        titleView.setTextColor(Color.WHITE);
        titleView.setSingleLine(true);
        titleView.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        topBar.addView(titleView, titleParams);
        root.addView(topBar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        ScrollView scroll = new ScrollView(requireContext());
        scroll.setFillViewport(true);
        TextView contentView = new TextView(requireContext());
        contentView.setText(content);
        contentView.setTextSize(18);
        contentView.setTextColor(0xFFE8E8E8);
        contentView.setLineSpacing(0, 1.5f);
        contentView.setPadding(ResUtil.dp2px(16), ResUtil.dp2px(16), ResUtil.dp2px(16), ResUtil.dp2px(32));
        scroll.addView(contentView);
        root.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        return root;
    }
}
