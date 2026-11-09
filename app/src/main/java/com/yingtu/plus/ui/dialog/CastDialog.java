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
import com.yingtu.plus.R;
import com.yingtu.plus.bean.CastVideo;
import com.yingtu.plus.bean.Config;
import com.yingtu.plus.bean.Device;
import com.yingtu.plus.bean.History;
import com.yingtu.plus.cast.DlnaManager;
import com.yingtu.plus.event.CastEvent;
import com.yingtu.plus.utils.Notify;
import com.yingtu.plus.utils.ResUtil;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

// 按调用点重建；本轮接真 DLNA：打开即用 DlnaManager 做 SSDP 发现，
// 把局域网里的 MediaRenderer（电视/盒子）与数据库里配对过的 App 设备
// 合并列出。DLNA 设备点选后直接 AVTransport 推流；App 设备仍走老路
// 发 CastEvent（对端 App 收到后弹 ReceiveDialog）。
public final class CastDialog extends BottomSheetDialogFragment {

    public interface Listener {
        void onCasted();
    }

    private History history;
    private CastVideo video;
    private boolean fm;

    private DeviceAdapter adapter;
    private TextView statusView;
    private TextView stopView;
    private final List<Device> dlnaDevices = new ArrayList<>();

    private final DlnaManager.Listener dlnaListener = devices -> {
        dlnaDevices.clear();
        dlnaDevices.addAll(devices);
        refreshDevices();
    };

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
        statusView = new TextView(requireContext());
        statusView.setText(R.string.cast_searching);
        statusView.setTextSize(13);
        statusView.setTextColor(0xFF94A3B8);
        LinearLayout.LayoutParams statusParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        statusParams.topMargin = ResUtil.dp2px(8);
        root.addView(statusView, statusParams);
        RecyclerView recycler = new RecyclerView(requireContext());
        recycler.setLayoutManager(new LinearLayoutManager(requireContext()));
        recycler.setItemAnimator(null);
        recycler.setHasFixedSize(true);
        LinearLayout.LayoutParams recyclerParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        recyclerParams.topMargin = ResUtil.dp2px(8);
        root.addView(recycler, recyclerParams);
        adapter = new DeviceAdapter(new ArrayList<>());
        recycler.setAdapter(adapter);
        if (DlnaManager.get().isCasting()) {
            stopView = new TextView(requireContext());
            stopView.setText(R.string.cast_stop);
            stopView.setTextSize(14);
            stopView.setTextColor(0xFFF87171);
            stopView.setGravity(Gravity.CENTER);
            stopView.setPadding(ResUtil.dp2px(12), ResUtil.dp2px(12), ResUtil.dp2px(12), ResUtil.dp2px(12));
            stopView.setBackgroundColor(0x33FFFFFF);
            LinearLayout.LayoutParams stopParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            stopParams.topMargin = ResUtil.dp2px(12);
            root.addView(stopView, stopParams);
            stopView.setOnClickListener(v -> DlnaManager.get().stop((ok, message) -> {
                Notify.show(R.string.cast_stopped);
                refreshDevices();
            }));
        }
        refreshDevices();
        DlnaManager.get().addListener(dlnaListener);
        DlnaManager.get().startDiscovery();
        return root;
    }

    @Override
    public void onDismiss(@NonNull android.content.DialogInterface dialog) {
        DlnaManager.get().removeListener(dlnaListener);
        DlnaManager.get().stopDiscovery();
        super.onDismiss(dialog);
    }

    /** 数据库配对设备 + 实时发现的 DLNA 设备合并，DLNA 同 uuid 的覆盖旧记录。 */
    private void refreshDevices() {
        if (adapter == null) return;
        Map<String, Device> merged = new LinkedHashMap<>();
        try {
            for (Device device : Device.getAll()) merged.put(device.getUuid(), device);
        } catch (Exception ignored) {
        }
        for (Device device : dlnaDevices) merged.put(device.getUuid(), device);
        List<Device> devices = new ArrayList<>(merged.values());
        Collections.sort(devices);
        adapter.setItems(devices);
        if (statusView != null) {
            boolean anyDlna = !dlnaDevices.isEmpty();
            statusView.setVisibility(anyDlna ? View.GONE : View.VISIBLE);
            // 对话框开着期间 SSDP 发现一直在跑，没发现设备就是“正在搜索”，不是“搜完了没有”。
            if (!anyDlna && devices.isEmpty()) statusView.setText(R.string.cast_searching);
        }
    }

    private void onDeviceClick(Device device) {
        if (device.isDLNA()) {
            if (video == null || TextUtils.isEmpty(video.getUrl())) {
                Notify.show(R.string.cast_need_video);
                return;
            }
            DlnaManager.get().play(device, video, (ok, message) -> {
                if (ok) {
                    Notify.show(ResUtil.getString(R.string.cast_pushed, device.getName()));
                    FragmentActivity activity = getActivity();
                    if (activity instanceof Listener listener) listener.onCasted();
                    dismiss();
                } else {
                    Notify.show(ResUtil.getString(R.string.cast_push_failed, message == null ? "" : message));
                }
            });
            return;
        }
        if (history != null) CastEvent.post(Config.vod(), device, history);
        FragmentActivity activity = getActivity();
        if (activity instanceof Listener listener) listener.onCasted();
        dismiss();
    }

    private class DeviceAdapter extends RecyclerView.Adapter<DeviceAdapter.Holder> {
        private List<Device> items;

        DeviceAdapter(List<Device> items) {
            this.items = items;
        }

        void setItems(List<Device> items) {
            this.items = items;
            notifyDataSetChanged();
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
