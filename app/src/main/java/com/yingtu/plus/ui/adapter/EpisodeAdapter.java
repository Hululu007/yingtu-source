package com.yingtu.plus.ui.adapter;

import android.graphics.Color;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.yingtu.plus.bean.Episode;
import com.yingtu.plus.utils.ResUtil;

import java.util.ArrayList;
import java.util.List;

// 按调用点重建
public class EpisodeAdapter extends RecyclerView.Adapter<EpisodeAdapter.Holder> {

    public interface OnClickListener {
        void onItemClick(Episode item);
    }

    private final OnClickListener listener;
    private final int viewType;
    private final List<Episode> items = new ArrayList<>();

    public EpisodeAdapter(OnClickListener listener, int viewType) {
        this.listener = listener;
        this.viewType = viewType;
    }

    public void addAll(List<Episode> list) {
        items.clear();
        if (list != null) items.addAll(list);
        notifyDataSetChanged();
    }

    public List<Episode> getItems() {
        return items;
    }

    public Episode get(int position) {
        if (position < 0 || position >= items.size()) return new Episode();
        return items.get(position);
    }

    public int getPosition() {
        for (int i = 0; i < items.size(); i++) if (items.get(i).isSelected()) return i;
        return 0;
    }

    public Episode getActivated() {
        for (Episode item : items) if (item.isSelected()) return item;
        return items.isEmpty() ? new Episode() : items.get(0);
    }

    public boolean isEmpty() {
        return items.isEmpty();
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        TextView view = new TextView(parent.getContext());
        view.setPadding(ResUtil.dp2px(8), ResUtil.dp2px(8), ResUtil.dp2px(8), ResUtil.dp2px(8));
        view.setTextSize(13);
        view.setTextColor(Color.WHITE);
        view.setSingleLine(true);
        view.setGravity(android.view.Gravity.CENTER);
        return new Holder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull Holder holder, int position) {
        Episode item = items.get(position);
        holder.text.setText(item.getDisplayName());
        holder.text.setSelected(item.isSelected());
        holder.text.setBackgroundColor(item.isSelected() ? 0xFF4CAF50 : 0x33FFFFFF);
        holder.itemView.setOnClickListener(v -> {
            if (listener != null) listener.onItemClick(item);
        });
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static class Holder extends RecyclerView.ViewHolder {
        final TextView text;

        Holder(@NonNull TextView itemView) {
            super(itemView);
            text = itemView;
        }
    }
}
