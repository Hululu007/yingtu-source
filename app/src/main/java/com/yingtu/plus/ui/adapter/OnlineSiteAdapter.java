package com.yingtu.plus.ui.adapter;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.yingtu.plus.R;
import com.yingtu.plus.bean.OnlineSite;

import java.util.ArrayList;
import java.util.List;

/**
 * Online website list adapter. Same shape as WebHomeAdapter.
 */
public class OnlineSiteAdapter extends RecyclerView.Adapter<OnlineSiteAdapter.VH> {

    public interface OnItemClick {
        void onClick(OnlineSite site);
    }

    public interface OnItemLongClick {
        void onLongClick(OnlineSite site);
    }

    private final List<OnlineSite> data = new ArrayList<>();
    private OnItemClick onItemClick;
    private OnItemLongClick onItemLongClick;

    public void setOnItemClick(OnItemClick l) {
        this.onItemClick = l;
    }

    public void setOnItemLongClick(OnItemLongClick l) {
        this.onItemLongClick = l;
    }

    public void submit(List<OnlineSite> list) {
        data.clear();
        if (list != null) data.addAll(list);
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_online_site, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int position) {
        OnlineSite site = data.get(position);
        h.tvName.setText(site.name);
        h.tvSub.setText(site.subtitle());
        h.tvBadge.setVisibility(site.pure ? View.VISIBLE : View.GONE);
        h.ivIcon.setImageResource(R.drawable.ic_web);

        h.itemView.setOnClickListener(v -> {
            if (onItemClick != null) onItemClick.onClick(site);
        });
        h.itemView.setOnLongClickListener(v -> {
            if (onItemLongClick != null) onItemLongClick.onLongClick(site);
            return true;
        });
    }

    @Override
    public int getItemCount() {
        return data.size();
    }

    static class VH extends RecyclerView.ViewHolder {
        final ImageView ivIcon;
        final TextView tvName;
        final TextView tvSub;
        final TextView tvBadge;

        VH(@NonNull View itemView) {
            super(itemView);
            ivIcon = itemView.findViewById(R.id.ivIcon);
            tvName = itemView.findViewById(R.id.tvName);
            tvSub = itemView.findViewById(R.id.tvSub);
            tvBadge = itemView.findViewById(R.id.tvBadge);
        }
    }
}
