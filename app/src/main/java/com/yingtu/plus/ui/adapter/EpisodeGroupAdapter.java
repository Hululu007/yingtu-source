package com.yingtu.plus.ui.adapter;

import android.graphics.Color;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.yingtu.plus.utils.ResUtil;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

// 按调用点重建
public class EpisodeGroupAdapter extends RecyclerView.Adapter<EpisodeGroupAdapter.Holder> {

    public static class Group {
        public final int start;
        public final int end;

        public Group(int start, int end) {
            this.start = start;
            this.end = end;
        }

        public String getName() {
            return (start + 1) + "-" + end;
        }
    }

    public interface OnClickListener {
        void onItemClick(Group item);
    }

    private static final int GROUP_SIZE = 30;

    private final OnClickListener listener;
    private final List<Group> items = new ArrayList<>();
    private int selected = 0;

    public EpisodeGroupAdapter(OnClickListener listener) {
        this.listener = listener;
    }

    public static List<Group> build(int size, int selectedPosition, boolean reverse) {
        List<Group> groups = new ArrayList<>();
        for (int start = 0; start < size; start += GROUP_SIZE) {
            groups.add(new Group(start, Math.min(size, start + GROUP_SIZE)));
        }
        if (reverse) Collections.reverse(groups);
        return groups;
    }

    public void addAll(List<Group> list) {
        items.clear();
        if (list != null) items.addAll(list);
        if (selected >= items.size()) selected = 0;
        notifyDataSetChanged();
    }

    public List<Group> getItems() {
        return items;
    }

    public Group get(int position) {
        if (position < 0 || position >= items.size()) return null;
        return items.get(position);
    }

    public int getPosition() {
        return selected;
    }

    public void setSelected(Group item) {
        int position = items.indexOf(item);
        if (position < 0) return;
        int old = selected;
        selected = position;
        notifyItemChanged(old);
        notifyItemChanged(selected);
    }

    public boolean isEmpty() {
        return items.isEmpty();
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        TextView view = new TextView(parent.getContext());
        view.setPadding(ResUtil.dp2px(12), ResUtil.dp2px(6), ResUtil.dp2px(12), ResUtil.dp2px(6));
        view.setTextSize(13);
        view.setTextColor(Color.WHITE);
        view.setSingleLine(true);
        view.setGravity(android.view.Gravity.CENTER);
        return new Holder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull Holder holder, int position) {
        Group item = items.get(position);
        holder.text.setText(item.getName());
        holder.text.setSelected(position == selected);
        holder.text.setBackgroundColor(position == selected ? 0xFF4CAF50 : 0x33FFFFFF);
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
