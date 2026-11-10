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
import java.util.Locale;

/**
 * 霓虹主题海报适配器（首页热播行、频道网格共用）。
 * item 用 item_poster_neon.xml，数据 List<Movie>。
 *
 * 角标规则（Movie bean 里没有 vod_tag 字段，SourceViewModel.toMovies
 * 也没把 Vod.vod_tag 传进来，所以两角标只能按备注 note 判断）：
 * - badgeHd：备注含 "HD"（不分大小写）或“高清”时显示；
 * - badgeNew：备注含 "NEW"（不分大小写）时显示；"新"字不用——备注里
 *   “更新至X集”普遍含“新”，按“新”判会把在更剧全标成新片。
 */
public class NeonPosterAdapter extends RecyclerView.Adapter<NeonPosterAdapter.VH> {

    public interface OnClick {
        void onClick(Movie movie);
    }

    private final List<Movie> data = new ArrayList<>();
    private OnClick listener;

    public void setOnClick(OnClick l) {
        this.listener = l;
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
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int position) {
        Movie m = data.get(position);
        h.posterTitle.setText(m.name == null ? "" : m.name);
        if (m.note == null || m.note.isEmpty()) {
            h.tvNote.setVisibility(View.GONE);
        } else {
            h.tvNote.setVisibility(View.VISIBLE);
            h.tvNote.setText(m.note);
        }
        String noteLower = m.note == null ? "" : m.note.toLowerCase(Locale.ROOT);
        h.badgeHd.setVisibility(
                noteLower.contains("hd") || (m.note != null && m.note.contains("高清"))
                        ? View.VISIBLE : View.GONE);
        h.badgeNew.setVisibility(noteLower.contains("new") ? View.VISIBLE : View.GONE);

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
        TextView tvNote;
        TextView posterTitle;
        Object boundUrl;
        boolean retried;

        VH(@NonNull View itemView) {
            super(itemView);
            posterImage = itemView.findViewById(R.id.posterImage);
            badgeHd = itemView.findViewById(R.id.badgeHd);
            badgeNew = itemView.findViewById(R.id.badgeNew);
            tvNote = itemView.findViewById(R.id.tvNote);
            posterTitle = itemView.findViewById(R.id.posterTitle);
        }
    }
}
