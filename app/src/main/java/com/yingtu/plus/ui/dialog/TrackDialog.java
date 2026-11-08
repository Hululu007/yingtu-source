package com.yingtu.plus.ui.dialog;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.Tracks;
import androidx.media3.ui.DefaultTrackNameProvider;
import androidx.media3.ui.TrackNameProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.bottomsheet.BottomSheetDialogFragment;
import com.yingtu.plus.bean.Sub;
import com.yingtu.plus.bean.Track;
import com.yingtu.plus.player.PlayerManager;
import com.yingtu.plus.ui.custom.SpaceItemDecoration;
import com.yingtu.plus.utils.FileChooser;
import com.yingtu.plus.utils.ResUtil;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

// 对照 webhtv 重写
public final class TrackDialog extends BottomSheetDialogFragment {

    public interface Listener {
        void onSubtitleClick();
    }

    private final TrackNameProvider provider;
    private PlayerManager player;
    private boolean secondarySubtitle;
    private int type;
    private TextView titleView;
    private TextView secondaryButton;
    private TextView chooseButton;
    private TextView subtitleButton;
    private RecyclerView recycler;
    private TrackInnerAdapter adapter;

    public static TrackDialog create() {
        return new TrackDialog();
    }

    public TrackDialog() {
        this.adapter = new TrackInnerAdapter();
        this.provider = new DefaultTrackNameProvider(com.yingtu.plus.App.get().getResources());
    }

    public TrackDialog player(PlayerManager player) {
        this.player = player;
        return this;
    }

    public TrackDialog type(int type) {
        this.type = type;
        return this;
    }

    public void show(FragmentActivity activity) {
        for (Fragment f : activity.getSupportFragmentManager().getFragments()) if (f instanceof TrackDialog) return;
        show(activity.getSupportFragmentManager(), null);
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        LinearLayout root = new LinearLayout(requireContext());
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(ResUtil.dp2px(16), ResUtil.dp2px(16), ResUtil.dp2px(16), ResUtil.dp2px(16));
        titleView = new TextView(requireContext());
        titleView.setTextSize(16);
        titleView.setTextColor(Color.WHITE);
        root.addView(titleView, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        secondaryButton = button("双字幕");
        root.addView(secondaryButton);
        chooseButton = button("选择外挂字幕文件");
        root.addView(chooseButton);
        subtitleButton = button("字幕样式设置");
        root.addView(subtitleButton);
        recycler = new RecyclerView(requireContext());
        recycler.setLayoutManager(new LinearLayoutManager(requireContext()));
        recycler.setItemAnimator(null);
        recycler.setHasFixedSize(true);
        recycler.addItemDecoration(new SpaceItemDecoration(1, 16));
        recycler.setAdapter(adapter);
        root.addView(recycler, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return root;
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        secondaryButton.setOnClickListener(v -> {
            secondarySubtitle = !secondarySubtitle;
            refreshTrackList();
        });
        chooseButton.setOnClickListener(v -> {
            launcher.launch(new Intent(Intent.ACTION_GET_CONTENT).setType("*/*"));
            if (player != null) player.pause();
        });
        subtitleButton.setOnClickListener(v -> {
            FragmentActivity activity = getActivity();
            if (activity instanceof Listener listener) {
                com.yingtu.plus.App.post(listener::onSubtitleClick, 100);
            }
            dismiss();
        });
        refreshTrackList();
    }

    private TextView button(String text) {
        TextView view = new TextView(requireContext());
        view.setText(text);
        view.setTextSize(14);
        view.setTextColor(Color.WHITE);
        view.setPadding(ResUtil.dp2px(12), ResUtil.dp2px(10), ResUtil.dp2px(12), ResUtil.dp2px(10));
        view.setGravity(Gravity.CENTER);
        view.setBackgroundColor(0x33FFFFFF);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = ResUtil.dp2px(8);
        view.setLayoutParams(params);
        return view;
    }

    private void refreshTrackList() {
        if (player == null) return;
        adapter.replaceAll(getTrack());
        titleView.setText(getTitle());
        secondaryButton.setText(secondarySubtitle ? "主字幕" : "双字幕");
        secondaryButton.setVisibility(hasSecondarySubtitle() ? View.VISIBLE : View.GONE);
        chooseButton.setVisibility(hasChoose() ? View.VISIBLE : View.GONE);
        subtitleButton.setVisibility(hasText() ? View.VISIBLE : View.GONE);
        recycler.setVisibility(adapter.getItemCount() == 0 ? View.GONE : View.VISIBLE);
        recycler.post(() -> recycler.scrollToPosition(adapter.getSelected()));
    }

    private boolean hasChoose() {
        return !secondarySubtitle && type == C.TRACK_TYPE_TEXT && player.isVod();
    }

    private boolean hasText() {
        return !secondarySubtitle && type == C.TRACK_TYPE_TEXT && player.haveTrack(type);
    }

    private boolean hasSecondarySubtitle() {
        return type == C.TRACK_TYPE_TEXT && player.supportsSecondarySubtitle() && player.haveTrack(type);
    }

    private String getTitle() {
        if (secondarySubtitle) return "选择双字幕轨道";
        if (type == C.TRACK_TYPE_VIDEO) return "视频轨道";
        if (type == C.TRACK_TYPE_AUDIO) return "音频轨道";
        return "字幕轨道";
    }

    private List<Track> getTrack() {
        List<Track> items = new ArrayList<>();
        addTrack(items);
        addDisableTrack(items);
        return items;
    }

    private void addTrack(List<Track> items) {
        List<Tracks.Group> groups = player.getCurrentTracks().getGroups();
        for (int i = 0; i < groups.size(); i++) {
            Tracks.Group trackGroup = groups.get(i);
            if (trackGroup.getType() != type) continue;
            for (int j = 0; j < trackGroup.length; j++) {
                Format format = trackGroup.getTrackFormat(j);
                if (type == C.TRACK_TYPE_TEXT && (secondarySubtitle ? player.isPrimarySubtitleSelected(format) : player.isSecondarySubtitleSelected(format))) continue;
                String name = provider.getTrackName(format);
                Track item = new Track(type, name, describeFormat(format)).playerId(format.id);
                item.setSelected(type == C.TRACK_TYPE_TEXT
                        ? secondarySubtitle ? player.isSecondarySubtitleSelected(format) : player.isPrimarySubtitleSelected(format)
                        : trackGroup.isTrackSelected(j));
                items.add(item);
            }
        }
    }

    private void addDisableTrack(List<Track> items) {
        if (type != C.TRACK_TYPE_TEXT) return;
        Track item = Track.disabled(type, secondarySubtitle ? "关闭双字幕" : "关闭字幕");
        item.setSelected(items.stream().noneMatch(Track::isSelected));
        items.add(0, item);
    }

    private static String describeFormat(Format format) {
        StringBuilder builder = new StringBuilder();
        if (!TextUtils.isEmpty(format.label)) builder.append(format.label);
        if (!TextUtils.isEmpty(format.language)) {
            if (builder.length() > 0) builder.append(" ");
            builder.append(format.language);
        }
        if (!TextUtils.isEmpty(format.codecs)) {
            if (builder.length() > 0) builder.append(" ");
            builder.append(format.codecs);
        }
        return builder.toString();
    }

    private final ActivityResultLauncher<Intent> launcher = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
        if (result.getResultCode() != Activity.RESULT_OK || result.getData() == null || result.getData().getData() == null) return;
        player.setSub(Sub.from(FileChooser.getPathFromUri(result.getData().getData())));
        dismiss();
    });

    private class TrackInnerAdapter extends RecyclerView.Adapter<TrackInnerAdapter.Holder> {
        private final List<Track> items = new ArrayList<>();

        void replaceAll(List<Track> list) {
            items.clear();
            if (list != null) items.addAll(list);
            notifyDataSetChanged();
        }

        int getSelected() {
            for (int i = 0; i < items.size(); i++) if (items.get(i).isSelected()) return i;
            return 0;
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
            Track item = items.get(position);
            holder.text.setText(item.getName());
            holder.text.setSelected(item.isSelected());
            holder.text.setBackgroundColor(item.isSelected() ? 0xFF4CAF50 : 0x22FFFFFF);
            holder.itemView.setOnClickListener(v -> {
                if (secondarySubtitle) {
                    player.setSecondarySubtitleTrack(item);
                    dismiss();
                    return;
                }
                player.setTrack(Arrays.asList(item.key(player.getKey()).save()));
                dismiss();
            });
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
