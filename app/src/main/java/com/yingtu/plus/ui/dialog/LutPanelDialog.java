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
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.bottomsheet.BottomSheetDialogFragment;
import com.yingtu.plus.player.PlayerManager;
import com.yingtu.plus.player.lut.LutPreset;
import com.yingtu.plus.player.lut.LutStore;
import com.yingtu.plus.ui.activity.VideoActivity;
import com.yingtu.plus.utils.ResUtil;

import java.util.ArrayList;
import java.util.List;

// 按调用点重建
public final class LutPanelDialog extends BottomSheetDialogFragment {

    private PlayerManager player;

    public static LutPanelDialog create() {
        return new LutPanelDialog();
    }

    public LutPanelDialog player(PlayerManager player) {
        this.player = player;
        return this;
    }

    public void show(FragmentActivity activity) {
        for (Fragment f : activity.getSupportFragmentManager().getFragments()) if (f instanceof LutPanelDialog) return;
        show(activity.getSupportFragmentManager(), null);
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        LinearLayout root = new LinearLayout(requireContext());
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(ResUtil.dp2px(16), ResUtil.dp2px(16), ResUtil.dp2px(16), ResUtil.dp2px(16));
        TextView title = new TextView(requireContext());
        title.setText("滤镜");
        title.setTextSize(16);
        title.setTextColor(Color.WHITE);
        root.addView(title, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        RecyclerView recycler = new RecyclerView(requireContext());
        recycler.setLayoutManager(new LinearLayoutManager(requireContext()));
        recycler.setItemAnimator(null);
        recycler.setHasFixedSize(true);
        List<LutPreset> presets = new ArrayList<>();
        try {
            presets.addAll(LutStore.getPresets());
        } catch (Exception ignored) {
        }
        recycler.setAdapter(new LutInnerAdapter(presets));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = ResUtil.dp2px(8);
        root.addView(recycler, params);
        return root;
    }

    private void onPresetClick(@Nullable LutPreset preset) {
        FragmentActivity activity = getActivity();
        if (activity instanceof VideoActivity videoActivity) {
            videoActivity.onLutSelected(preset);
        } else if (player != null) {
            player.selectLut(preset, preset != null);
        }
        dismiss();
    }

    private class LutInnerAdapter extends RecyclerView.Adapter<LutInnerAdapter.Holder> {
        private final List<LutPreset> items;

        LutInnerAdapter(List<LutPreset> items) {
            this.items = items;
        }

        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            TextView view = new TextView(parent.getContext());
            view.setPadding(ResUtil.dp2px(12), ResUtil.dp2px(12), ResUtil.dp2px(12), ResUtil.dp2px(12));
            view.setTextSize(14);
            view.setTextColor(Color.WHITE);
            return new Holder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull Holder holder, int position) {
            if (position == 0) {
                holder.text.setText("原画");
                boolean selected = false;
                try {
                    selected = LutStore.getSelectedPreset() == null;
                } catch (Exception ignored) {
                }
                holder.text.setSelected(selected);
                holder.text.setBackgroundColor(selected ? 0xFF4CAF50 : 0x22FFFFFF);
                holder.itemView.setOnClickListener(v -> onPresetClick(null));
                return;
            }
            LutPreset preset = items.get(position - 1);
            holder.text.setText(preset.getName());
            boolean selected = false;
            try {
                selected = preset.equals(LutStore.getSelectedPreset());
            } catch (Exception ignored) {
            }
            holder.text.setSelected(selected);
            holder.text.setBackgroundColor(selected ? 0xFF4CAF50 : 0x22FFFFFF);
            holder.itemView.setOnClickListener(v -> onPresetClick(preset));
        }

        @Override
        public int getItemCount() {
            return items.size() + 1;
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
