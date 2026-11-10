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
 * 频道页交错墙适配器（源码 NeonChannelView._wall / _group 结构）：
 * 每 3 张一组——左一张竖长大卡 + 右两张小卡；组高 306dp（满 3 张或 2 张），
 * 只剩 1 张时 240dp（左卡半宽 + 右半留空）。海报按槽满填裁切（centerCrop），
 * 不是 2:3——参考图里竖长卡就是比 2:3 更长、小卡比 2:3 更方。
 *
 * 角标按源码位置轮换（(组号 + 槽位) % 4：0 → HD 青、1 → NEW 粉、其余无）；
 * 副标题类型优先、无类型用备注；标题字号竖长卡 12sp、小卡 10.5sp。
 * 数据按组切分后提交（submit(List<Movie>) 内部每 3 个一组）。
 */
public class ChannelWallAdapter extends RecyclerView.Adapter<ChannelWallAdapter.VH> {

    private static final int GROUP_HEIGHT_FULL_DP = 306;
    private static final int GROUP_HEIGHT_SINGLE_DP = 240;

    public interface OnClick {
        void onClick(Movie movie);
    }

    private final List<List<Movie>> groups = new ArrayList<>();
    private OnClick listener;

    public void setOnClick(OnClick l) {
        this.listener = l;
    }

    /** 按 3 个一组切分提交；null/空则清空。 */
    public void submit(List<Movie> list) {
        groups.clear();
        if (list != null) {
            for (int i = 0; i < list.size(); i += 3) {
                groups.add(new ArrayList<>(list.subList(i, Math.min(i + 3, list.size()))));
            }
        }
        notifyDataSetChanged();
    }

    public int getDataCount() {
        int n = 0;
        for (List<Movie> g : groups) n += g.size();
        return n;
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_channel_wall_group, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int position) {
        List<Movie> group = groups.get(position);
        int size = group.size();
        float density = h.itemView.getContext().getResources().getDisplayMetrics().density;
        int heightDp = size <= 1 ? GROUP_HEIGHT_SINGLE_DP : GROUP_HEIGHT_FULL_DP;
        ViewGroup.LayoutParams lp = h.itemView.getLayoutParams();
        if (lp != null) {
            lp.height = (int) (heightDp * density + 0.5f);
            h.itemView.setLayoutParams(lp);
        }
        // 1 张：右列留空（左卡半宽）；2 张：右上卡撑满整列；3 张：右列两张。
        h.colRight.setVisibility(View.VISIBLE);
        h.gapMid.setVisibility(size == 3 ? View.VISIBLE : View.GONE);
        h.slotTop.setVisibility(size >= 2 ? View.VISIBLE : View.GONE);
        h.slotBottom.setVisibility(size == 3 ? View.VISIBLE : View.GONE);
        bindSlot(h.tall, group.get(0), position, 0, true);
        if (size >= 2) bindSlot(h.top, group.get(1), position, 1, false);
        else h.top.clear();
        if (size == 3) bindSlot(h.bottom, group.get(2), position, 2, false);
        else h.bottom.clear();
    }

    /** 槽位角标：(组号 + 槽位) % 4，0 → HD（青）、1 → NEW（粉），其余不显示。 */
    private void bindSlot(CardSlot slot, Movie m, int groupIndex, int slotIndex, boolean tall) {
        slot.title.setTextSize(TypedValue.COMPLEX_UNIT_SP, tall ? 12f : 10.5f);
        slot.title.setText(m.name == null ? "" : m.name);
        String sub = m.type == null ? "" : m.type.trim();
        if (sub.isEmpty() && m.note != null) sub = m.note.trim();
        if (sub.isEmpty()) {
            slot.subtitle.setVisibility(View.GONE);
        } else {
            slot.subtitle.setVisibility(View.VISIBLE);
            slot.subtitle.setText(sub);
        }
        int badge = (groupIndex + slotIndex) % 4;
        if (badge == 0) {
            slot.badge.setVisibility(View.VISIBLE);
            slot.badge.setText("HD");
            slot.badge.setBackgroundResource(R.drawable.bg_badge_hd);
        } else if (badge == 1) {
            slot.badge.setVisibility(View.VISIBLE);
            slot.badge.setText("NEW");
            slot.badge.setBackgroundResource(R.drawable.bg_badge_new);
        } else {
            slot.badge.setVisibility(View.GONE);
        }
        Context ctx = slot.poster.getContext();
        Object url;
        try {
            url = ImgUtil.getUrl(m.pic);
        } catch (Throwable e) {
            e.printStackTrace();
            url = m.pic;
        }
        slot.boundUrl = url;
        slot.retried = false;
        final Object coverUrl = url;
        final CardSlot s = slot;
        Glide.with(ctx)
                .load(url)
                .placeholder(R.drawable.bg_poster_placeholder)
                .error(R.drawable.bg_poster_placeholder)
                .centerCrop()
                .listener(new RequestListener<Drawable>() {
                    @Override
                    public boolean onLoadFailed(@Nullable GlideException e, Object model, @NonNull Target<Drawable> target, boolean isFirstResource) {
                        if (!s.retried && coverUrl != null && coverUrl.equals(s.boundUrl)) {
                            s.retried = true;
                            Glide.with(ctx)
                                    .load(coverUrl)
                                    .placeholder(R.drawable.bg_poster_placeholder)
                                    .error(R.drawable.bg_poster_placeholder)
                                    .centerCrop()
                                    .into(s.poster);
                        }
                        return false;
                    }

                    @Override
                    public boolean onResourceReady(@NonNull Drawable resource, @NonNull Object model, @NonNull Target<Drawable> target, @NonNull DataSource dataSource, boolean isFirstResource) {
                        return false;
                    }
                })
                .into(slot.poster);
        slot.root.setOnClickListener(v -> {
            if (listener != null) listener.onClick(m);
        });
    }

    @Override
    public void onViewRecycled(@NonNull VH h) {
        super.onViewRecycled(h);
        h.tall.clear();
        h.top.clear();
        h.bottom.clear();
    }

    @Override
    public int getItemCount() {
        return groups.size();
    }

    /** 一张墙卡的四个 view + 本次绑定的图（Glide 重试用）。 */
    static class CardSlot {
        final View root;
        final ImageView poster;
        final TextView title;
        final TextView subtitle;
        final TextView badge;
        Object boundUrl;
        boolean retried;

        CardSlot(View slotRoot) {
            this.root = slotRoot;
            poster = slotRoot.findViewById(R.id.wallPoster);
            title = slotRoot.findViewById(R.id.wallTitle);
            subtitle = slotRoot.findViewById(R.id.wallSubtitle);
            badge = slotRoot.findViewById(R.id.wallBadge);
        }

        void clear() {
            Glide.with(poster.getContext()).clear(poster);
            boundUrl = null;
        }
    }

    static class VH extends RecyclerView.ViewHolder {
        final View colRight;
        final View gapMid;
        final View slotTop;
        final View slotBottom;
        final CardSlot tall;
        final CardSlot top;
        final CardSlot bottom;

        VH(@NonNull View itemView) {
            super(itemView);
            colRight = itemView.findViewById(R.id.colRight);
            gapMid = itemView.findViewById(R.id.gapMid);
            slotTop = itemView.findViewById(R.id.slotTop);
            slotBottom = itemView.findViewById(R.id.slotBottom);
            tall = new CardSlot(itemView.findViewById(R.id.slotTall));
            top = new CardSlot(slotTop);
            bottom = new CardSlot(slotBottom);
        }
    }
}
