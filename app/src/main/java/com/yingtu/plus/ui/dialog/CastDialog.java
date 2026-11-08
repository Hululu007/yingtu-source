package com.yingtu.plus.ui.dialog;

import android.graphics.Color;
import android.os.Bundle;
import android.text.TextUtils;
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
import com.yingtu.plus.bean.CastVideo;
import com.yingtu.plus.bean.Config;
import com.yingtu.plus.bean.Device;
import com.yingtu.plus.bean.History;
import com.yingtu.plus.event.CastEvent;
import com.yingtu.plus.utils.ResUtil;

import java.util.ArrayList;
import java.util.List;

// 按调用点重建
public final class CastDialog extends BottomSheetDialogFragment {

    public interface Listener {
        void onCasted();
    }

    private History history;
    private CastVideo video;
    private boolean fm;

    public static CastDialog create() {
        return new CastDialog();
    }

    public CastDialog history(History history) {
        this.history = history;
        return this;
    }

    public CastDialog video(CastVideo video) {
        this.video = video;
        return this;
    }

    public CastDialog fm(boolean fm) {
        this.fm = fm;
        return this;
    }

    public void show(FragmentActivity activity) {
        for (Fragment f : activity.getSupportFragmentManager().getFragments()) if (f instanceof CastDialog) return;
        show(activity.getSupportFragmentManager(), null);
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        LinearLayout root = new LinearLayout(requireContext());
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(ResUtil.dp2px(16), ResUtil.dp2px(16), ResUtil.dp2px(16), ResUtil.dp2px(16));
        TextView title = new TextView(requireContext());
        title.setText("投屏");
        title.setTextSize(16);
        title.setTextColor(Color.WHITE);
        root.addView(title, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        if (video != null && !TextUtils.isEmpty(video.getName())) {
            TextView name = new TextView(requireContext());
            name.setText(video.getName());
            name.setTextSize(13);
            name.setTextColor(0xFFCBD5E1);
            name.setSingleLine(true);
            name.setEllipsize(TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            params.topMargin = ResUtil.dp2px(6);
            root.addView(name, params);
        }
        RecyclerView recycler = new RecyclerView(requireContext());
        recycler.setLayoutManager(new LinearLayoutManager(requireContext()));
        recycler.setItemAnimator(null);
        recycler.setHasFixedSize(true);
        LinearLayout.LayoutParams recyclerParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        recyclerParams.topMargin = ResUtil.dp2px(8);
        root.addView(recycler, recyclerParams);
        List<Device> devices = new ArrayList<>();
        try {
            devices.addAll(Device.getAll());
        } catch (Exception ignored) {
        }
        if (devices.isEmpty()) {
            TextView empty = new TextView(requireContext());
            empty.setText("未发现可投屏设备");
            empty.setTextSize(14);
            empty.setTextColor(0xFF94A3B8);
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(0, ResUtil.dp2px(16), 0, ResUtil.dp2px(16));
            root.addView(empty);
        }
        recycler.setAdapter(new DeviceAdapter(devices));
        return root;
    }

    private void onDeviceClick(Device device) {
        if (history != null) CastEvent.post(Config.vod(), device, history);
        FragmentActivity activity = getActivity();
        if (activity instanceof Listener listener) listener.onCasted();
        dismiss();
    }

    private class DeviceAdapter extends RecyclerView.Adapter<DeviceAdapter.Holder> {
        private final List<Device> items;

        DeviceAdapter(List<Device> items) {
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
            Device device = items.get(position);
            String label = device.getName();
            if (device.isDLNA()) label += "（DLNA）";
            else if (device.isApp()) label += "（App）";
            holder.text.setText(label);
            holder.itemView.setOnClickListener(v -> onDeviceClick(device));
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
