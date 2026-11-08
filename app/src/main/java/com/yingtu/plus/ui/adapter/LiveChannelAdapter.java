package com.yingtu.plus.ui.adapter;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.yingtu.plus.R;
import com.yingtu.plus.bean.LiveChannel;

import java.util.ArrayList;
import java.util.List;

/**
 * 直播频道列表（右侧）。显示频道名 + 多线路角标。
 */
public class LiveChannelAdapter extends RecyclerView.Adapter<LiveChannelAdapter.VH> {

    public interface OnChannelClick {
        void onClick(LiveChannel channel);
    }

    private final List<LiveChannel> data = new ArrayList<>();
    private int selected = -1;
    private OnChannelClick listener;

    public void setOnChannelClick(OnChannelClick l) {
        this.listener = l;
    }

    /** 高亮正在播放的频道（-1 为不亮）。 */
    public void setSelected(int position) {
        int old = selected;
        selected = position;
        if (old >= 0) notifyItemChanged(old);
        if (selected >= 0) notifyItemChanged(selected);
    }

    public void submit(List<LiveChannel> list) {
        data.clear();
        if (list != null) data.addAll(list);
        selected = -1;
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_live_channel, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int position) {
        LiveChannel c = data.get(position);
        h.tvName.setText(c.name);
        int color = h.itemView.getContext().getResources().getColor(
                position == selected ? R.color.colorPrimary : R.color.text_primary, null);
        h.tvName.setTextColor(color);
        if (c.hasMultiLine()) {
            h.tvLine.setVisibility(View.VISIBLE);
            h.tvLine.setText(c.urls.size() + "线路");
        } else {
            h.tvLine.setVisibility(View.GONE);
        }
        h.itemView.setOnClickListener(v -> {
            if (listener != null) listener.onClick(c);
        });
    }

    @Override
    public int getItemCount() {
        return data.size();
    }

    static class VH extends RecyclerView.ViewHolder {
        TextView tvName;
        TextView tvLine;
        VH(@NonNull View itemView) {
            super(itemView);
            tvName = itemView.findViewById(R.id.tvName);
            tvLine = itemView.findViewById(R.id.tvLine);
        }
    }
}
