package com.yingtu.plus.ui.adapter;

import android.content.Context;
import android.graphics.drawable.Drawable;
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
 * 影片网格适配器（海报墙）。首页推荐、分类列表、搜索结果共用。
 */
public class GridMovieAdapter extends RecyclerView.Adapter<GridMovieAdapter.VH> {

    public interface OnItemClick {
        void onClick(Movie movie);
    }

    /** 长按回调（分类列表长按菜单用）：返回 true 表示已消费。 */
    public interface OnItemLongClick {
        boolean onLongClick(Movie movie);
    }

    private final List<Movie> data = new ArrayList<>();
    private OnItemClick listener;
    private OnItemLongClick longListener;

    /** 展示形态：0=文字（只显片名，不加载封面） 1=图片（只显封面） 2=图文（封面+片名） */
    private int displayMode = 2;

    public void setDisplayMode(int mode) {
        displayMode = mode >= 0 && mode <= 2 ? mode : 2;
        notifyDataSetChanged();
    }

    public int getDisplayMode() {
        return displayMode;
    }

    public void setOnItemClick(OnItemClick l) {
        this.listener = l;
    }

    public void setOnItemLongClick(OnItemLongClick l) {
        this.longListener = l;
    }

    public void submit(List<Movie> list) {
        data.clear();
        if (list != null) data.addAll(list);
        notifyDataSetChanged();
    }

    public void append(List<Movie> list) {
        if (list == null || list.isEmpty()) return;
        int start = data.size();
        data.addAll(list);
        notifyItemRangeInserted(start, list.size());
    }

    public int getDataCount() {
        return data.size();
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_grid_movie, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int position) {
        Movie m = data.get(position);
        h.itemView.setOnLongClickListener(v -> longListener != null && longListener.onLongClick(m));
        h.tvName.setText(m.name);
        h.tvName.setVisibility(displayMode == 1 ? View.GONE : View.VISIBLE);
        h.ivPoster.setVisibility(displayMode == 0 ? View.GONE : View.VISIBLE);

        if (displayMode == 1) {
            h.tvNote.setVisibility(View.GONE);
            h.tvRating.setVisibility(View.GONE);
            h.tvBadge.setVisibility(View.GONE);
        } else if (m.note == null || m.note.isEmpty()) {
            h.tvNote.setVisibility(View.GONE);
        } else {
            h.tvNote.setVisibility(View.VISIBLE);
            h.tvNote.setText(m.note);
        }

        if (displayMode == 1) {
            // 图片形态不加载文字行，封面照常加载
        } else if (m.rate == null || m.rate.isEmpty()) {
            h.tvRating.setVisibility(View.GONE);
        } else {
            h.tvRating.setVisibility(View.VISIBLE);
            h.tvRating.setText(m.rate);
        }

        if (displayMode != 1) h.tvBadge.setVisibility(m.douban ? View.VISIBLE : View.GONE);

        if (displayMode == 0) {
            h.itemView.setOnClickListener(v -> {
                if (listener != null) listener.onClick(m);
            });
            return;
        }
        Context ctx = h.itemView.getContext();
        Object url;
        try {
            url = ImgUtil.getUrl(m.pic);
        } catch (Throwable e) {
            // 封面带畸形 @Headers= JSON 时 ImgUtil.getUrl 会抛错：一行封面不许崩整页，回退原图。
            // （ImgUtil.getUrl 内部现已兜底不再抛错，这里是双保险。）
            e.printStackTrace();
            url = m.pic;
        }
        h.boundUrl = url;
        h.retried = false;
        final Object coverUrl = url;
        // 不加 .thumbnail()：默认 AUTOMATIC 策略下原图不进磁盘缓存，缩略图请求
        // 会让同一张图下载两次，慢网反效果。先出图靠占位图，二次打开靠磁盘缓存。
        // 也不加 .override()：Glide 按 ImageView 实测尺寸（168dp 高）解码，已是合适尺寸。
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
                                    .into(h.ivPoster);
                        }
                        return false;
                    }

                    @Override
                    public boolean onResourceReady(@NonNull Drawable resource, @NonNull Object model, @NonNull Target<Drawable> target, @NonNull DataSource dataSource, boolean isFirstResource) {
                        return false;
                    }
                })
                .into(h.ivPoster);

        h.itemView.setOnClickListener(v -> {
            if (listener != null) listener.onClick(m);
        });
    }

    @Override
    public void onViewRecycled(@NonNull VH h) {
        super.onViewRecycled(h);
        // 滚出屏幕立即取消封面请求：慢网下离屏的图不再继续占带宽，
        // 可视区的图才能尽快下来；同时清掉绑定地址，防失败重试串位到复用的格子。
        Glide.with(h.itemView.getContext()).clear(h.ivPoster);
        h.boundUrl = null;
    }

    @Override
    public int getItemCount() {
        return data.size();
    }

    static class VH extends RecyclerView.ViewHolder {
        ImageView ivPoster;
        TextView tvName;
        TextView tvNote;
        TextView tvRating;
        TextView tvBadge;
        Object boundUrl;
        boolean retried;

        VH(@NonNull View itemView) {
            super(itemView);
            ivPoster = itemView.findViewById(R.id.ivPoster);
            tvName = itemView.findViewById(R.id.tvName);
            tvNote = itemView.findViewById(R.id.tvNote);
            tvRating = itemView.findViewById(R.id.tvRating);
            tvBadge = itemView.findViewById(R.id.tvBadge);
        }
    }
}
