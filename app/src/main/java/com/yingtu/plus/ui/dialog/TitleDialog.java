package com.yingtu.plus.ui.dialog;

import android.graphics.Color;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.media3.common.MediaEdition;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.bottomsheet.BottomSheetDialogFragment;
import com.yingtu.plus.player.PlayerManager;
import com.yingtu.plus.ui.custom.SpaceItemDecoration;
import com.yingtu.plus.utils.ResUtil;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

// 对照 webhtv 重写
public final class TitleDialog extends BottomSheetDialogFragment {

    private PlayerManager player;
    private TitleInnerAdapter adapter;

    public static TitleDialog create() {
        return new TitleDialog();
    }

    public TitleDialog() {
        this.adapter = new TitleInnerAdapter();
    }

    public TitleDialog player(PlayerManager player) {
        this.player = player;
        return this;
    }

    public void show(FragmentActivity activity) {
        for (Fragment f : activity.getSupportFragmentManager().getFragments()) if (f instanceof TitleDialog) return;
        show(activity.getSupportFragmentManager(), null);
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        LinearLayout root = new LinearLayout(requireContext());
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(ResUtil.dp2px(16), ResUtil.dp2px(16), ResUtil.dp2px(16), ResUtil.dp2px(16));
        TextView title = new TextView(requireContext());
        title.setText("选择版本");
        title.setTextSize(16);
        title.setTextColor(Color.WHITE);
        root.addView(title, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        RecyclerView recycler = new RecyclerView(requireContext());
        recycler.setLayoutManager(new LinearLayoutManager(requireContext()));
        recycler.setItemAnimator(null);
        recycler.setHasFixedSize(true);
        recycler.addItemDecoration(new SpaceItemDecoration(1, 16));
        recycler.setAdapter(adapter);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = ResUtil.dp2px(8);
        root.addView(recycler, params);
        return root;
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        if (player == null) return;
        List<MediaEdition> editions = player.getCurrentMediaEditions();
        adapter.addAll(editions);
    }

    private class TitleInnerAdapter extends RecyclerView.Adapter<TitleInnerAdapter.Holder> {
        private final List<MediaEdition> items = new ArrayList<>();

        void addAll(List<MediaEdition> list) {
            items.clear();
            if (list != null) items.addAll(list);
            notifyDataSetChanged();
        }

        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            TextView view = new TextView(parent.getContext());
            view.setPadding(ResUtil.dp2px(12), ResUtil.dp2px(10), ResUtil.dp2px(12), ResUtil.dp2px(10));
            view.setTextSize(14);
            view.setTextColor(Color.WHITE);
            return new Holder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull Holder holder, int position) {
            MediaEdition item = items.get(position);
            String label = item.label == null ? "" : item.label.toString();
            if (item.durationUs > 0) label += " [" + formatDuration(item.durationUs / 1000) + "]";
            holder.text.setText(label);
            holder.text.setSelected(item.selected);
            holder.text.setBackgroundColor(item.selected ? 0xFF4CAF50 : 0x22FFFFFF);
            holder.itemView.setOnClickListener(v -> {
                player.setTitle(item);
                dismiss();
            });
        }

        private static String formatDuration(long ms) {
            long totalSeconds = Math.max(0, ms / 1000);
            long minutes = totalSeconds / 60;
            long seconds = totalSeconds % 60;
            return String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds);
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
