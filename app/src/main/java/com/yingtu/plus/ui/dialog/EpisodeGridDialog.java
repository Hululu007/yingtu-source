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
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.bottomsheet.BottomSheetDialogFragment;
import com.yingtu.plus.bean.Episode;
import com.yingtu.plus.ui.activity.VideoActivity;
import com.yingtu.plus.ui.custom.SpaceItemDecoration;
import com.yingtu.plus.utils.ResUtil;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

// 按调用点重建
public final class EpisodeGridDialog extends BottomSheetDialogFragment {

    private boolean reverse;
    private List<Episode> episodes = new ArrayList<>();

    public static EpisodeGridDialog create() {
        return new EpisodeGridDialog();
    }

    public EpisodeGridDialog reverse(boolean reverse) {
        this.reverse = reverse;
        return this;
    }

    public EpisodeGridDialog episodes(List<Episode> episodes) {
        this.episodes = episodes == null ? new ArrayList<>() : new ArrayList<>(episodes);
        return this;
    }

    public void show(FragmentActivity activity) {
        for (Fragment f : activity.getSupportFragmentManager().getFragments()) if (f instanceof EpisodeGridDialog) return;
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
        recycler.setLayoutManager(new GridLayoutManager(requireContext(), 6));
        recycler.setItemAnimator(null);
        recycler.setHasFixedSize(true);
        recycler.addItemDecoration(new SpaceItemDecoration(6, 8));
        List<Episode> items = new ArrayList<>(episodes);
        if (reverse) Collections.reverse(items);
        recycler.setAdapter(new EpisodeInnerAdapter(items));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = ResUtil.dp2px(8);
        root.addView(recycler, params);
        return root;
    }

    private void onEpisodeClick(Episode item) {
        FragmentActivity activity = getActivity();
        if (activity instanceof VideoActivity videoActivity) videoActivity.onItemClick(item);
        dismiss();
    }

    private class EpisodeInnerAdapter extends RecyclerView.Adapter<EpisodeInnerAdapter.Holder> {
        private final List<Episode> items;

        EpisodeInnerAdapter(List<Episode> items) {
            this.items = items;
        }

        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            TextView view = new TextView(parent.getContext());
            view.setPadding(ResUtil.dp2px(4), ResUtil.dp2px(10), ResUtil.dp2px(4), ResUtil.dp2px(10));
            view.setTextSize(13);
            view.setTextColor(Color.WHITE);
            view.setSingleLine(true);
            view.setGravity(Gravity.CENTER);
            return new Holder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull Holder holder, int position) {
            Episode item = items.get(position);
            holder.text.setText(item.getDisplayName());
            holder.text.setSelected(item.isSelected());
            holder.text.setBackgroundColor(item.isSelected() ? 0xFF4CAF50 : 0x33FFFFFF);
            holder.itemView.setOnClickListener(v -> onEpisodeClick(item));
        }

        @Override
        public int getItemCount() {
            return items.size();
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
