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
import com.yingtu.plus.drama.DramaModels.DramaVideo;
import com.yingtu.plus.utils.ImgUtil;

import java.util.ArrayList;
import java.util.List;

/** Feed grid for the Fanqie short-drama stream (reuses item_grid_movie). */
public class DramaFeedAdapter extends RecyclerView.Adapter<DramaFeedAdapter.VH> {

    public interface OnItemClick {
        void onClick(DramaVideo video, int position);
    }

    private final List<DramaVideo> data = new ArrayList<>();
    private OnItemClick listener;

    public void setOnItemClick(OnItemClick l) {
        listener = l;
    }

    public void submit(List<DramaVideo> list) {
        data.clear();
        if (list != null) data.addAll(list);
        notifyDataSetChanged();
    }

    public void append(List<DramaVideo> list) {
        if (list == null || list.isEmpty()) return;
        int start = data.size();
        data.addAll(list);
        notifyItemRangeInserted(start, list.size());
    }

    public DramaVideo getItem(int position) {
        return data.get(position);
    }

    public List<DramaVideo> getData() {
        return new ArrayList<>(data);
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_grid_movie, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int position) {
        DramaVideo m = data.get(position);
        h.tvName.setText(m.displayTitle());
        String note = m.episodeRemark();
        if (note.isEmpty()) {
            h.tvNote.setVisibility(View.GONE);
        } else {
            h.tvNote.setVisibility(View.VISIBLE);
            h.tvNote.setText(note);
        }
        if (m.score > 0) {
            h.tvRating.setVisibility(View.VISIBLE);
            h.tvRating.setText(String.valueOf(m.score));
        } else {
            h.tvRating.setVisibility(View.GONE);
        }
        h.tvBadge.setVisibility(View.GONE);
        Glide.with(h.itemView.getContext())
                .load(ImgUtil.getUrl(m.cover))
                .placeholder(R.drawable.bg_poster_placeholder)
                .error(R.drawable.bg_poster_placeholder)
                .centerCrop()
                .into(h.ivPoster);
        h.itemView.setOnClickListener(v -> {
            if (listener != null) listener.onClick(m, h.getBindingAdapterPosition());
        });
    }

    @Override
    public int getItemCount() {
        return data.size();
    }

    static class VH extends RecyclerView.ViewHolder {
        ImageView ivPoster;
        TextView tvName;
        TextView tvNote;
        TextView tvRating;
        TextView tvBadge;

        VH(@NonNull View itemView) {
            super(itemView);
            ivPoster = itemView.findViewById(R.id.ivPoster);
            tvName = itemView.findViewById(R.id.tvName);
            tvNote = itemView.findViewById(R.id.tvNote);
            tvRating = itemView.findViewById(R.id.tvRating);
            tvBadge = itemView.findViewById(R.id.tvBadge);
        }
    }
}
