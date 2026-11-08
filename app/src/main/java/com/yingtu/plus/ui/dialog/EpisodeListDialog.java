package com.yingtu.plus.ui.dialog;

import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.bottomsheet.BottomSheetDialogFragment;
import com.yingtu.plus.bean.Episode;
import com.yingtu.plus.bean.Flag;
import com.yingtu.plus.ui.activity.VideoActivity;
import com.yingtu.plus.utils.ResUtil;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

// 按调用点重建
public final class EpisodeListDialog extends BottomSheetDialogFragment {

    private static final int TYPE_FLAG = 0;
    private static final int TYPE_EPISODE = 1;

    private boolean reverse;
    private List<Flag> flags = new ArrayList<>();

    public static EpisodeListDialog create() {
        return new EpisodeListDialog();
    }

    public EpisodeListDialog flags(List<Flag> flags) {
        this.flags = flags == null ? new ArrayList<>() : new ArrayList<>(flags);
        return this;
    }

    public EpisodeListDialog reverse(boolean reverse) {
        this.reverse = reverse;
        return this;
    }

    public void show(FragmentActivity activity) {
        for (Fragment f : activity.getSupportFragmentManager().getFragments()) if (f instanceof EpisodeListDialog) return;
        show(activity.getSupportFragmentManager(), null);
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        LinearLayout root = new LinearLayout(requireContext());
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(ResUtil.dp2px(16), ResUtil.dp2px(16), ResUtil.dp2px(16), ResUtil.dp2px(16));
        TextView title = new TextView(requireContext());
        title.setText("选集");
        title.setTextSize(16);
        title.setTextColor(Color.WHITE);
        root.addView(title, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        RecyclerView recycler = new RecyclerView(requireContext());
        recycler.setLayoutManager(new LinearLayoutManager(requireContext()));
        recycler.setItemAnimator(null);
        recycler.setHasFixedSize(true);
        recycler.setAdapter(new EpisodeListAdapter(buildRows()));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = ResUtil.dp2px(8);
        root.addView(recycler, params);
        return root;
    }

    private List<Object> buildRows() {
        List<Object> rows = new ArrayList<>();
        for (Flag flag : flags) {
            rows.add(flag);
            List<Episode> episodes = new ArrayList<>(flag.getEpisodes());
            if (reverse) Collections.reverse(episodes);
            rows.addAll(episodes);
        }
        return rows;
    }

    private void onEpisodeClick(Episode item) {
        FragmentActivity activity = getActivity();
        if (activity instanceof VideoActivity videoActivity) videoActivity.onItemClick(item);
        dismiss();
    }

    private class EpisodeListAdapter extends RecyclerView.Adapter<EpisodeListAdapter.Holder> {
        private final List<Object> rows;

        EpisodeListAdapter(List<Object> rows) {
            this.rows = rows;
        }

        @Override
        public int getItemViewType(int position) {
            return rows.get(position) instanceof Flag ? TYPE_FLAG : TYPE_EPISODE;
        }

        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            TextView view = new TextView(parent.getContext());
            view.setPadding(ResUtil.dp2px(12), ResUtil.dp2px(10), ResUtil.dp2px(12), ResUtil.dp2px(10));
            view.setTextSize(viewType == TYPE_FLAG ? 14 : 13);
            view.setTextColor(Color.WHITE);
            view.setSingleLine(true);
            if (viewType == TYPE_EPISODE) view.setGravity(Gravity.CENTER_VERTICAL);
            return new Holder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull Holder holder, int position) {
            Object row = rows.get(position);
            if (row instanceof Flag flag) {
                holder.text.setText(flag.getFlag());
                holder.text.setBackgroundColor(0x22FFFFFF);
            } else if (row instanceof Episode episode) {
                holder.text.setText("　" + episode.getDisplayName());
                holder.text.setSelected(episode.isSelected());
                holder.text.setBackgroundColor(episode.isSelected() ? 0xFF4CAF50 : 0x11FFFFFF);
                holder.itemView.setOnClickListener(v -> onEpisodeClick(episode));
            }
        }

        @Override
        public int getItemCount() {
            return rows.size();
        }

        class Holder extends RecyclerView.ViewHolder {
            final TextView text;

            Holder(@NonNull TextView itemView) {
                super(itemView);
                text = itemView;
            }
        }
    }
}
