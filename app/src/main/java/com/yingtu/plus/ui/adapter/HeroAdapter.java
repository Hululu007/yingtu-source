package com.yingtu.plus.ui.adapter;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.yingtu.plus.R;
import com.yingtu.plus.bean.Movie;
import com.yingtu.plus.utils.ImgUtil;

import java.util.ArrayList;
import java.util.List;

/**
 * 首页 hero 轮播适配器（layout_home_hero.xml，一页三张海报）。
 * 每页：中间海报为 position 对应项，两侧为上一项 / 下一项（取模循环）；
 * 标题与标签取中间项；播放键点按回调给 HomeMainFragment 进详情 / 播放。
 */
public class HeroAdapter extends RecyclerView.Adapter<HeroAdapter.VH> {

    public interface OnHeroClick {
        void onHeroClick(Movie movie);
    }

    private final List<Movie> data = new ArrayList<>();
    private OnHeroClick listener;

    public void setOnHeroClick(OnHeroClick l) {
        this.listener = l;
    }

    public void submit(List<Movie> list) {
        data.clear();
        if (list != null) data.addAll(list);
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.layout_home_hero, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int position) {
        int count = data.size();
        if (count == 0) return;
        Movie center = data.get(position % count);
        Movie left = data.get((position - 1 + count) % count);
        Movie right = data.get((position + 1) % count);
        loadPoster(h.heroPosterCenter, center.pic);
        loadPoster(h.heroPosterLeft, left.pic);
        loadPoster(h.heroPosterRight, right.pic);
        h.heroTitle.setText(center.name == null ? "" : center.name);
        h.heroTags.setText(buildTags(center));
        View.OnClickListener play = v -> {
            if (listener != null) listener.onHeroClick(center);
        };
        h.btnHeroPlay.setOnClickListener(play);
        h.itemView.setOnClickListener(play);
    }

    /** 标签：类型 · 备注 · 年份，有什么拼什么（Movie 字段：type / note / year）。 */
    private String buildTags(Movie m) {
        StringBuilder sb = new StringBuilder();
        if (m.type != null && !m.type.isEmpty()) sb.append(m.type);
        if (m.note != null && !m.note.isEmpty()) {
            if (sb.length() > 0) sb.append(" · ");
            sb.append(m.note);
        }
        if (m.year != null && !m.year.isEmpty()) {
            if (sb.length() > 0) sb.append(" · ");
            sb.append(m.year);
        }
        return sb.toString();
    }

    private void loadPoster(ImageView iv, String pic) {
        Object url;
        try {
            url = ImgUtil.getUrl(pic);
        } catch (Throwable e) {
            url = pic;
        }
        Glide.with(iv.getContext())
                .load(url)
                .placeholder(R.drawable.bg_poster_placeholder)
                .error(R.drawable.bg_poster_placeholder)
                .centerCrop()
                .into(iv);
    }

    @Override
    public void onViewRecycled(@NonNull VH h) {
        super.onViewRecycled(h);
        Glide.with(h.itemView.getContext()).clear(h.heroPosterCenter);
        Glide.with(h.itemView.getContext()).clear(h.heroPosterLeft);
        Glide.with(h.itemView.getContext()).clear(h.heroPosterRight);
    }

    @Override
    public int getItemCount() {
        return data.size();
    }

    static class VH extends RecyclerView.ViewHolder {
        ImageView heroPosterLeft;
        ImageView heroPosterCenter;
        ImageView heroPosterRight;
        TextView heroTitle;
        TextView heroTags;
        View btnHeroPlay;

        VH(@NonNull View itemView) {
            super(itemView);
            heroPosterLeft = itemView.findViewById(R.id.heroPosterLeft);
            heroPosterCenter = itemView.findViewById(R.id.heroPosterCenter);
            heroPosterRight = itemView.findViewById(R.id.heroPosterRight);
            heroTitle = itemView.findViewById(R.id.heroTitle);
            heroTags = itemView.findViewById(R.id.heroTags);
            btnHeroPlay = itemView.findViewById(R.id.btnHeroPlay);
        }
    }
}
