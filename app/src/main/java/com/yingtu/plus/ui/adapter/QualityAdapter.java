package com.yingtu.plus.ui.adapter;

import android.graphics.Color;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.yingtu.plus.bean.Result;
import com.yingtu.plus.bean.Value;
import com.yingtu.plus.utils.ResUtil;

import java.util.ArrayList;
import java.util.List;

// 按调用点重建
public class QualityAdapter extends RecyclerView.Adapter<QualityAdapter.Holder> {

    public interface OnClickListener {
        void onItemClick(Result item);
    }

    private final OnClickListener listener;
    private final List<Value> items = new ArrayList<>();
    private Result result;

    public QualityAdapter(OnClickListener listener) {
        this.listener = listener;
    }

    public void addAll(Result result) {
        this.result = result;
        items.clear();
        if (result != null && result.getUrl() != null) items.addAll(result.getUrl().getValues());
        notifyDataSetChanged();
    }

    public int getPosition() {
        return result == null || result.getUrl() == null ? 0 : result.getUrl().getPosition();
    }

    public List<Value> getItems() {
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
        Value item = items.get(position);
        String name = item.getN();
        if (name == null || name.isEmpty()) name = item.getV();
        holder.text.setText(name);
        boolean selected = position == getPosition();
        holder.text.setSelected(selected);
        holder.text.setBackgroundColor(selected ? 0xFF4CAF50 : 0x33FFFFFF);
        holder.itemView.setOnClickListener(v -> {
            if (result != null && result.getUrl() != null) result.getUrl().set(holder.getAdapterPosition());
            if (listener != null && result != null) listener.onItemClick(result);
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
