package com.yingtu.plus.ui.adapter;

import android.graphics.Color;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.yingtu.plus.bean.Vod;
import com.yingtu.plus.utils.ResUtil;

import java.util.ArrayList;
import java.util.List;

// 按调用点重建
public class QuickAdapter extends RecyclerView.Adapter<QuickAdapter.Holder> {

    public interface OnClickListener {
        void onItemClick(Vod item);
    }

    private final OnClickListener listener;
    private final List<Vod> items = new ArrayList<>();

    public QuickAdapter(OnClickListener listener) {
        this.listener = listener;
    }

    public void addAll(List<Vod> list) {
        if (list == null) return;
        int start = items.size();
        items.addAll(list);
        notifyItemRangeInserted(start, list.size());
    }

    public void clear() {
        items.clear();
        notifyDataSetChanged();
    }

    public void remove(int position) {
        if (position < 0 || position >= items.size()) return;
        items.remove(position);
        notifyItemRemoved(position);
    }

    public Vod get(int position) {
        if (position < 0 || position >= items.size()) return null;
        return items.get(position);
    }

    public int getBestPosition() {
        return items.isEmpty() ? -1 : 0;
    }

    public List<Vod> getItems() {
        return items;
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
        Vod item = items.get(position);
        holder.text.setText(item.getName() + "  " + item.getSiteName());
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
