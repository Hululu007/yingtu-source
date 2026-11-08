package com.yingtu.plus.ui.dialog;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.bottomsheet.BottomSheetDialogFragment;
import com.yingtu.plus.bean.Danmaku;
import com.yingtu.plus.player.PlayerManager;
import com.yingtu.plus.setting.DanmakuSetting;
import com.yingtu.plus.ui.custom.SpaceItemDecoration;
import com.yingtu.plus.utils.FileChooser;
import com.yingtu.plus.utils.ResUtil;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

// 对照 webhtv 重写
public final class DanmakuDialog extends BottomSheetDialogFragment {

    public interface Host {
        boolean isDanmakuFullscreen();
    }

    private PlayerManager player;
    private DanmakuInnerAdapter adapter;
    private RecyclerView recycler;
    private EditText search;
    private CheckBox load;
    private String keyword = "";

    public static DanmakuDialog create() {
        return new DanmakuDialog();
    }

    public DanmakuDialog() {
        this.adapter = new DanmakuInnerAdapter();
    }

    public DanmakuDialog player(PlayerManager player) {
        this.player = player;
        return this;
    }

    public void show(FragmentActivity activity) {
        for (Fragment f : activity.getSupportFragmentManager().getFragments()) if (f instanceof DanmakuDialog) return;
        show(activity.getSupportFragmentManager(), null);
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        LinearLayout root = new LinearLayout(requireContext());
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(ResUtil.dp2px(16), ResUtil.dp2px(16), ResUtil.dp2px(16), ResUtil.dp2px(16));
        TextView title = new TextView(requireContext());
        title.setText("弹幕");
        title.setTextSize(16);
        title.setTextColor(Color.WHITE);
        root.addView(title, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        search = new EditText(requireContext());
        search.setHint("搜索弹幕源");
        search.setSingleLine(true);
        search.setTextSize(14);
        search.setTextColor(Color.WHITE);
        search.setHintTextColor(0xFF9E9E9E);
        LinearLayout.LayoutParams searchParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ResUtil.dp2px(42));
        searchParams.topMargin = ResUtil.dp2px(8);
        root.addView(search, searchParams);
        LinearLayout row = new LinearLayout(requireContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        load = new CheckBox(requireContext());
        load.setText("加载弹幕");
        load.setTextColor(Color.WHITE);
        row.addView(load);
        TextView choose = new TextView(requireContext());
        choose.setText("选择弹幕文件");
        choose.setTextSize(14);
        choose.setTextColor(Color.WHITE);
        choose.setPadding(ResUtil.dp2px(12), ResUtil.dp2px(10), ResUtil.dp2px(12), ResUtil.dp2px(10));
        choose.setBackgroundColor(0x33FFFFFF);
        choose.setOnClickListener(v -> {
            launcher.launch(new Intent(Intent.ACTION_GET_CONTENT).setType("text/*"));
            if (player != null) player.pause();
        });
        row.addView(choose, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(row);
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
        if (player == null) return;
        adapter.addAll(player.getDanmakus());
        search.setVisibility(player.getMetadata() == null || !DanmakuSetting.hasValidApiUrl() ? View.GONE : View.VISIBLE);
        load.setChecked(DanmakuSetting.isLoad());
        updateLoadUi(DanmakuSetting.isLoad());
        load.setOnCheckedChangeListener((button, checked) -> onLoadChanged(checked));
        search.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                keyword = s == null ? "" : s.toString();
                adapter.filter(keyword);
            }

            @Override
            public void afterTextChanged(Editable s) {
            }
        });
        recycler.post(() -> recycler.scrollToPosition(adapter.getSelected()));
        recycler.setVisibility(adapter.getItemCount() == 0 ? View.GONE : View.VISIBLE);
    }

    private void onLoadChanged(boolean enabled) {
        DanmakuSetting.putLoad(enabled);
        DanmakuSetting.putShow(enabled);
        if (player != null) player.setDanmakuEnabled(enabled);
        updateLoadUi(enabled);
    }

    private void updateLoadUi(boolean enabled) {
        if (recycler != null) recycler.setAlpha(enabled ? 1f : 0.45f);
    }

    private final ActivityResultLauncher<Intent> launcher = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
        if (result.getResultCode() != Activity.RESULT_OK || result.getData() == null || result.getData().getData() == null) return;
        player.setDanmaku(Danmaku.from(FileChooser.getPathFromUri(result.getData().getData())));
        dismiss();
    });

    private class DanmakuInnerAdapter extends RecyclerView.Adapter<DanmakuInnerAdapter.Holder> {
        private final List<Danmaku> all = new ArrayList<>();
        private final List<Danmaku> items = new ArrayList<>();

        void addAll(List<Danmaku> list) {
            all.clear();
            items.clear();
            if (list != null) {
                all.addAll(list);
                items.addAll(list);
            }
            notifyDataSetChanged();
        }

        void filter(String keyword) {
            items.clear();
            if (TextUtils.isEmpty(keyword)) {
                items.addAll(all);
            } else {
                String lower = keyword.toLowerCase(Locale.getDefault());
                for (Danmaku item : all) {
                    if (item.getName() != null && item.getName().toLowerCase(Locale.getDefault()).contains(lower)) items.add(item);
                }
            }
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
            Danmaku item = items.get(position);
            holder.text.setText(item.getName());
            holder.text.setSelected(item.isSelected());
            holder.text.setBackgroundColor(item.isSelected() ? 0xFF4CAF50 : 0x22FFFFFF);
            holder.itemView.setOnClickListener(v -> {
                player.setDanmaku(item.isSelected() ? Danmaku.empty() : item);
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
