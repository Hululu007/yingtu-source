package com.yingtu.plus.ui.dialog;

import android.graphics.Color;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.bottomsheet.BottomSheetDialogFragment;
import com.yingtu.plus.bean.Vod;
import com.yingtu.plus.ui.adapter.QuickAdapter;
import com.yingtu.plus.utils.ResUtil;

import java.util.ArrayList;
import java.util.List;

// 按调用点重建
public final class QuickSearchDialog extends BottomSheetDialogFragment {

    public interface SearchListener {
        void onSearch(String keyword);
    }

    private String title;
    private String keyword = "";
    private QuickAdapter.OnClickListener listener;
    private SearchListener searchListener;
    private QuickAdapter adapter;
    private EditText input;
    private final List<Vod> pendingItems = new ArrayList<>();

    public static QuickSearchDialog create() {
        return new QuickSearchDialog();
    }

    public QuickSearchDialog title(String title) {
        this.title = title;
        return this;
    }

    public QuickSearchDialog keyword(String keyword) {
        this.keyword = keyword == null ? "" : keyword;
        return this;
    }

    public QuickSearchDialog listener(QuickAdapter.OnClickListener listener) {
        this.listener = listener;
        return this;
    }

    public QuickSearchDialog searchListener(SearchListener searchListener) {
        this.searchListener = searchListener;
        return this;
    }

    public QuickSearchDialog items(List<Vod> items) {
        if (items == null) return this;
        if (adapter != null) adapter.addAll(items);
        else pendingItems.addAll(items);
        return this;
    }

    public void show(FragmentActivity activity) {
        for (Fragment f : activity.getSupportFragmentManager().getFragments()) if (f instanceof QuickSearchDialog) return;
        show(activity.getSupportFragmentManager(), null);
    }

    public boolean isActive() {
        return isAdded() && getDialog() != null && getDialog().isShowing();
    }

    public void clear() {
        pendingItems.clear();
        if (adapter != null) adapter.clear();
    }

    public void addAll(List<Vod> items) {
        if (items == null) return;
        if (adapter != null) adapter.addAll(items);
        else pendingItems.addAll(items);
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        adapter = new QuickAdapter(listener);
        if (!pendingItems.isEmpty()) {
            adapter.addAll(pendingItems);
            pendingItems.clear();
        }
        LinearLayout root = new LinearLayout(requireContext());
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(ResUtil.dp2px(16), ResUtil.dp2px(16), ResUtil.dp2px(16), ResUtil.dp2px(16));
        TextView titleView = new TextView(requireContext());
        titleView.setText(TextUtils.isEmpty(title) ? "搜索" : title);
        titleView.setTextSize(16);
        titleView.setTextColor(Color.WHITE);
        root.addView(titleView, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        input = new EditText(requireContext());
        input.setText(keyword);
        input.setHint("输入关键词搜索");
        input.setSingleLine(true);
        input.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        input.setTextSize(14);
        input.setTextColor(Color.WHITE);
        input.setHintTextColor(0xFF9E9E9E);
        input.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH || (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER)) {
                doSearch();
                return true;
            }
            return false;
        });
        LinearLayout.LayoutParams inputParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ResUtil.dp2px(44));
        inputParams.topMargin = ResUtil.dp2px(8);
        root.addView(input, inputParams);
        RecyclerView recycler = new RecyclerView(requireContext());
        recycler.setLayoutManager(new LinearLayoutManager(requireContext()));
        recycler.setItemAnimator(null);
        recycler.setHasFixedSize(true);
        recycler.setAdapter(adapter);
        LinearLayout.LayoutParams recyclerParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        recyclerParams.topMargin = ResUtil.dp2px(8);
        root.addView(recycler, recyclerParams);
        return root;
    }

    private void doSearch() {
        if (searchListener == null || input == null) return;
        String text = input.getText() == null ? "" : input.getText().toString().trim();
        if (TextUtils.isEmpty(text)) return;
        keyword = text;
        clear();
        searchListener.onSearch(text);
    }
}
