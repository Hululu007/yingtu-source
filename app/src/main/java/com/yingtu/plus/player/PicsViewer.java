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
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.DialogFragment;
import androidx.fragment.app.FragmentActivity;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewpager2.widget.ViewPager2;

import com.bumptech.glide.Glide;
import com.yingtu.plus.App;
import com.yingtu.plus.R;
import com.yingtu.plus.utils.ImgUtil;
import com.yingtu.plus.utils.ResUtil;

import java.util.ArrayList;
import java.util.List;

/**
 * 图片 / 漫画查看器：全屏 DialogFragment，不新建 Activity，不动 AndroidManifest。
 * ViewPager2 左右滑动翻页，图片走 Glide + ImgUtil.getUrl() 加载，
 * 与午夜区封面同一条加载路（@Headers= / @Cookie= / @Referer= / @User-Agent=
 * 后缀与代理转换都在 ImgUtil.getUrl() 里处理）。
 * 限度：v1 只做滑动翻页。树里没有现成的缩放图片控件（无 PhotoView /
 * SubsamplingScaleImageView 之类，也无相关依赖），捏合缩放本次没做。
 * 返回键 / 点“返回”都是 dismiss，回到下面的播放页，再按返回回到详情页。
 */
public final class PicsViewer extends DialogFragment {

    private static final String TAG = "NonVideoPics";
    private static final String ARG_TITLE = "title";
    private static final String ARG_IMAGES = "images";

    private List<String> images = new ArrayList<>();
    private TextView counter;

    public static void show(FragmentActivity activity, List<String> images, String fallbackTitle) {
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) return;
        if (activity.getSupportFragmentManager().findFragmentByTag(TAG) != null) return;
        if (images == null || images.isEmpty()) return;
        PicsViewer viewer = new PicsViewer();
        Bundle args = new Bundle();
        args.putString(ARG_TITLE, fallbackTitle == null ? "" : fallbackTitle);
        args.putStringArrayList(ARG_IMAGES, new ArrayList<>(images));
        viewer.setArguments(args);
        viewer.show(activity.getSupportFragmentManager(), TAG);
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setStyle(STYLE_NORMAL, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        Bundle args = getArguments();
        if (args != null) {
            ArrayList<String> list = args.getStringArrayList(ARG_IMAGES);
            if (list != null) images = list;
        }
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

        FrameLayout root = new FrameLayout(requireContext());
        root.setBackgroundColor(Color.BLACK);
        root.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        ViewPager2 pager = new ViewPager2(requireContext());
        pager.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        pager.setOffscreenPageLimit(1);
        pager.setAdapter(new PagerAdapter(images));
        root.addView(pager);

        LinearLayout topBar = new LinearLayout(requireContext());
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        topBar.setBackgroundColor(0x80000000);
        topBar.setPadding(ResUtil.dp2px(8), ResUtil.dp2px(10), ResUtil.dp2px(16), ResUtil.dp2px(10));
        FrameLayout.LayoutParams topParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        topParams.gravity = Gravity.TOP;

        TextView back = new TextView(requireContext());
        back.setText("‹ 返回");
        back.setTextSize(17);
        back.setTextColor(Color.WHITE);
        back.setPadding(ResUtil.dp2px(8), ResUtil.dp2px(4), ResUtil.dp2px(16), ResUtil.dp2px(4));
        back.setOnClickListener(v -> dismiss());
        topBar.addView(back);

        TextView titleView = new TextView(requireContext());
        titleView.setText(TextUtils.isEmpty(title) ? "图片浏览" : title);
        titleView.setTextSize(17);
        titleView.setTextColor(Color.WHITE);
        titleView.setSingleLine(true);
        titleView.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        topBar.addView(titleView, titleParams);
        root.addView(topBar, topParams);

        counter = new TextView(requireContext());
        counter.setTextSize(15);
        counter.setTextColor(Color.WHITE);
        counter.setBackgroundColor(0x80000000);
        counter.setPadding(ResUtil.dp2px(12), ResUtil.dp2px(6), ResUtil.dp2px(12), ResUtil.dp2px(6));
        FrameLayout.LayoutParams counterParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        counterParams.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        counterParams.bottomMargin = ResUtil.dp2px(16);
        root.addView(counter, counterParams);
        updateCounter(0);

        pager.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
            @Override
            public void onPageSelected(int position) {
                updateCounter(position);
            }
        });
        return root;
    }

    private void updateCounter(int position) {
        if (counter == null) return;
        counter.setText(images.isEmpty() ? "" : (position + 1) + " / " + images.size());
    }

    private static final class PagerAdapter extends RecyclerView.Adapter<PagerAdapter.Holder> {

        private final List<String> images;

        PagerAdapter(List<String> images) {
            this.images = images == null ? new ArrayList<>() : images;
        }

        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            ImageView view = new ImageView(parent.getContext());
            view.setLayoutParams(new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            view.setScaleType(ImageView.ScaleType.FIT_CENTER);
            view.setBackgroundColor(Color.BLACK);
            return new Holder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull Holder holder, int position) {
            String url = images.get(position);
            try {
                Glide.with(holder.image).load(ImgUtil.getUrl(url)).fitCenter()
                        .error(R.drawable.artwork).into(holder.image);
            } catch (Throwable e) {
                App.logCaught(e);
            }
        }

        @Override
        public int getItemCount() {
            return images.size();
        }

        static final class Holder extends RecyclerView.ViewHolder {
            final ImageView image;

            Holder(ImageView image) {
                super(image);
                this.image = image;
            }
        }
    }
}
