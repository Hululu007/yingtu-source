package com.yingtu.plus.ui.adapter;

import android.graphics.Color;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.yingtu.plus.bean.Episode;
import com.yingtu.plus.bean.Flag;
import com.yingtu.plus.utils.ResUtil;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

// 按调用点重建
public class FlagAdapter extends RecyclerView.Adapter<FlagAdapter.Holder> {

    public interface OnClickListener {
        void onItemClick(Flag item);
    }

    private final OnClickListener listener;
    private final List<Flag> items = new ArrayList<>();

    public FlagAdapter(OnClickListener listener) {
        this.listener = listener;
    }

    public void addAll(List<Flag> list) {
        items.clear();
        if (list != null) items.addAll(list);
        notifyDataSetChanged();
    }

    public void add(Flag item) {
        if (item == null) return;
        items.add(item);
        notifyItemInserted(items.size() - 1);
    }

    public List<Flag> getItems() {
        return items;
    }

    public Flag get(int position) {
        if (position < 0 || position >= items.size()) return null;
        return items.get(position);
    }

    public int getPosition() {
        for (int i = 0; i < items.size(); i++) if (items.get(i).isSelected()) return i;
        return 0;
    }

    public Flag getActivated() {
        for (Flag item : items) if (item.isSelected()) return item;
        return items.isEmpty() ? null : items.get(0);
    }

    public void setSelected(Flag item) {
        for (Flag flag : items) flag.setSelected(item);
        notifyDataSetChanged();
    }

    public void toggle(Episode episode) {
        Flag flag = getActivated();
        if (flag == null || episode == null) return;
        flag.toggle(true, episode);
        notifyDataSetChanged();
    }

    public void reverse() {
        for (Flag flag : items) Collections.reverse(flag.getEpisodes());
        notifyDataSetChanged();
    }

    public boolean isEmpty() {
        return items.isEmpty();
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        TextView view = new TextView(parent.getContext());
        view.setPadding(ResUtil.dp2px(12), ResUtil.dp2px(8), ResUtil.dp2px(12), ResUtil.dp2px(8));
        view.setTextSize(13);
        view.setTextColor(Color.WHITE);
        view.setSingleLine(true);
        return new Holder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull Holder holder, int position) {
        Flag item = items.get(position);
        holder.text.setText(item.getFlag());
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
