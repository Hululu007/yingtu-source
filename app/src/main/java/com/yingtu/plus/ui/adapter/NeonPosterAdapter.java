package com.yingtu.plus.ui.adapter;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.DataSource;
import com.bumptech.glide.load.engine.GlideException;
import com.bumptech.glide.request.RequestListener;
import com.bumptech.glide.request.target.Target;
import com.yingtu.plus.R;
import com.yingtu.plus.bean.Movie;
import com.yingtu.plus.utils.ImgUtil;

import java.util.ArrayList;
import java.util.List;

/**
 * 霓虹主题海报适配器（首页热播行、猜你想看共用）。
 * item 用 item_poster_neon.xml，数据 List<Movie>；标题/副标题压在海报底部
 * （源码 NeonPoster 结构：封面满填 + 底部渐隐 + 标题白 11.5sp / 副标题 9sp）。
 *
 * 角标按源码位置轮换（不是按备注判——源码 neon_home_view 里热播行就是
 * index % 3 == 0 → HD，角标是装饰节奏；备注里“更新至X集”普遍含“新”，
 * 按备注判会把在更剧全标成 NEW）：
 * - BADGE_HOME：热播行，视觉列 (position / 2) % 3 == 0 显示 HD（2 排横向网格，
 *   position 0/1 是第 0 列，2/3 是第 1 列…，与源码横排 index 对齐）；
 * - BADGE_NONE：猜你想看，源码 SliverGrid 的 NeonPoster 不带角标。
 * 副标题：类型优先（参考图卡片第二行就是类型），无类型时用备注（集数/状态），
 * 都没有则隐藏（源码是 tags → 集数 → 分类；Movie 没有集数字段，备注顶上）。
 */
public class NeonPosterAdapter extends RecyclerView.Adapter<NeonPosterAdapter.VH> {

    public static final int BADGE_HOME = 0;
    public static final int BADGE_NONE = 1;

    public interface OnClick {
        void onClick(Movie movie);
    }

    private final List<Movie> data = new ArrayList<>();
    private OnClick listener;

    /** 首页热播行紧凑宽（dp）：0 = 不固定（猜你想看按列宽走）；>0 时卡片定宽，
     * 海报高按 2:3 配（宽 * 3 / 2），一眼能多放几张。 */
    private int compactWidthDp = 0;
    private int badgeMode = BADGE_HOME;

    public void setOnClick(OnClick l) {
        this.listener = l;
    }

    public void setCompactWidthDp(int dp) {
        this.compactWidthDp = dp;
    }

    public void setBadgeMode(int mode) {
        this.badgeMode = mode;
    }

    public void submit(List<Movie> list) {
        data.clear();
        if (list != null) data.addAll(list);
        notifyDataSetChanged();
    }

    public int getDataCount() {
        return data.size();
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_poster_neon, parent, false);
        VH h = new VH(v);
        // 紧凑模式（首页热播行）：卡片定宽，海报高按 2:3 配好，不随格子拉伸。
        if (compactWidthDp > 0) {
            float density = parent.getContext().getResources().getDisplayMetrics().density;
            int w = (int) (compactWidthDp * density + 0.5f);
            ViewGroup.LayoutParams lp = v.getLayoutParams();
            if (lp != null) lp.width = w;
            else v.setLayoutParams(new ViewGroup.LayoutParams(w, ViewGroup.LayoutParams.WRAP_CONTENT));
            ViewGroup.LayoutParams imgLp = h.posterImage.getLayoutParams();
            imgLp.height = w * 3 / 2;
            h.posterImage.setLayoutParams(imgLp);
        }
        return h;
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int position) {
        Movie m = data.get(position);
        // 网格模式（猜你想看 3 列）：卡片宽由列宽定，海报高按实测宽 * 3 / 2
        // 配成 2:3，宽不变时只设一次，不循环触发布局。
        if (compactWidthDp <= 0) {
            h.posterImage.post(() -> {
                int w = h.itemView.getWidth();
                if (w <= 0) return;
                int targetH = w * 3 / 2;
                ViewGroup.LayoutParams lp = h.posterImage.getLayoutParams();
                if (lp != null && lp.height != targetH) {
                    lp.height = targetH;
                    h.posterImage.setLayoutParams(lp);
                }
            });
        }
        // 标题字号：热播行 10.5sp、猜你想看 10sp（源码 NeonPoster titleSize）。
        h.posterTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, compactWidthDp > 0 ? 10.5f : 10f);
        h.posterTitle.setText(m.name == null ? "" : m.name);
        // 副标题：类型优先，无类型时用备注（集数/状态，如“更新至20集”）。
        String sub = m.type == null ? "" : m.type.trim();
        if (sub.isEmpty() && m.note != null) sub = m.note.trim();
        if (sub.isEmpty()) {
            h.tvSubtitle.setVisibility(View.GONE);
        } else {
            h.tvSubtitle.setVisibility(View.VISIBLE);
            h.tvSubtitle.setText(sub);
        }
        // 角标：位置轮换（源码节奏），NEW 在首页行不用。
        boolean showHd = badgeMode == BADGE_HOME && (position / 2) % 3 == 0;
        h.badgeHd.setVisibility(showHd ? View.VISIBLE : View.GONE);
        h.badgeNew.setVisibility(View.GONE);

        Context ctx = h.itemView.getContext();
        Object url;
        try {
            url = ImgUtil.getUrl(m.pic);
        } catch (Throwable e) {
            e.printStackTrace();
            url = m.pic;
        }
        h.boundUrl = url;
        h.retried = false;
        final Object coverUrl = url;
        Glide.with(ctx)
                .load(url)
                .placeholder(R.drawable.bg_poster_placeholder)
                .error(R.drawable.bg_poster_placeholder)
                .centerCrop()
                .listener(new RequestListener<Drawable>() {
                    @Override
                    public boolean onLoadFailed(@Nullable GlideException e, Object model, @NonNull Target<Drawable> target, boolean isFirstResource) {
                        if (!h.retried && coverUrl != null && coverUrl.equals(h.boundUrl)) {
                            h.retried = true;
                            Glide.with(ctx)
                                    .load(coverUrl)
                                    .placeholder(R.drawable.bg_poster_placeholder)
                                    .error(R.drawable.bg_poster_placeholder)
                                    .centerCrop()
                                    .into(h.posterImage);
                        }
                        return false;
                    }

                    @Override
                    public boolean onResourceReady(@NonNull Drawable resource, @NonNull Object model, @NonNull Target<Drawable> target, @NonNull DataSource dataSource, boolean isFirstResource) {
                        return false;
                    }
                })
                .into(h.posterImage);

        h.itemView.setOnClickListener(v -> {
            if (listener != null) listener.onClick(m);
        });
    }

    @Override
    public void onViewRecycled(@NonNull VH h) {
        super.onViewRecycled(h);
        Glide.with(h.itemView.getContext()).clear(h.posterImage);
        h.boundUrl = null;
    }

    @Override
    public int getItemCount() {
        return data.size();
    }

    static class VH extends RecyclerView.ViewHolder {
        ImageView posterImage;
        View badgeHd;
        View badgeNew;
        TextView posterTitle;
        TextView tvSubtitle;
        Object boundUrl;
        boolean retried;

        VH(@NonNull View itemView) {
            super(itemView);
            posterImage = itemView.findViewById(R.id.posterImage);
            badgeHd = itemView.findViewById(R.id.badgeHd);
            badgeNew = itemView.findViewById(R.id.badgeNew);
            posterTitle = itemView.findViewById(R.id.posterTitle);
            tvSubtitle = itemView.findViewById(R.id.tvSubtitle);
        }
    }
}
