package com.yingtu.plus.ui.adapter;

import android.graphics.Color;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.yingtu.plus.api.config.VodConfig;
import com.yingtu.plus.bean.Parse;
import com.yingtu.plus.utils.ResUtil;

import java.util.ArrayList;
import java.util.List;

// 按调用点重建
public class ParseAdapter extends RecyclerView.Adapter<ParseAdapter.Holder> {

    public interface OnClickListener {
        void onItemClick(Parse item);
    }

    private final OnClickListener listener;
    private final int viewType;
    private final List<Parse> items = new ArrayList<>();

    public ParseAdapter(OnClickListener listener, int viewType) {
        this.listener = listener;
        this.viewType = viewType;
        reload();
    }

    public void reload() {
        items.clear();
        try {
            List<Parse> parses = VodConfig.get().getParses();
            if (parses != null) items.addAll(parses);
        } catch (Exception ignored) {
        }
        notifyDataSetChanged();
    }

    public Parse first() {
        return items.isEmpty() ? new Parse() : items.get(0);
    }

    public Parse get(int position) {
        if (position < 0 || position >= items.size()) return new Parse();
        return items.get(position);
    }

    public int getPosition() {
        Parse current = null;
        try {
            current = VodConfig.get().getParse();
        } catch (Exception ignored) {
        }
        if (current != null) {
            for (int i = 0; i < items.size(); i++) if (items.get(i).equals(current)) return i;
        }
        return 0;
    }

    public List<Parse> getItems() {
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
        view.setGravity(android.view.Gravity.CENTER);
        return new Holder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull Holder holder, int position) {
        Parse item = items.get(position);
        holder.text.setText(item.getName());
        boolean selected = position == getPosition();
        holder.text.setSelected(selected);
        holder.text.setBackgroundColor(selected ? 0xFF4CAF50 : 0x33FFFFFF);
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
