package com.yingtu.plus.ui.adapter;

import android.graphics.drawable.Drawable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.DataSource;
import com.bumptech.glide.load.engine.GlideException;
import com.bumptech.glide.request.RequestListener;
import com.bumptech.glide.request.target.Target;
import com.orhanobut.hawk.Hawk;
import com.yingtu.plus.R;
import com.yingtu.plus.bean.SourceBean;
import com.yingtu.plus.util.HawkConfig;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 站源选择弹窗里的 3 列网格适配器。
 * 支持搜索过滤和分类 chip 筛选。
 */
public class SourceGridAdapter extends RecyclerView.Adapter<SourceGridAdapter.VH> {

    public interface OnSourceClick {
        void onClick(SourceBean source);
    }

    private final List<SourceBean> allData = new ArrayList<>();
    private final List<SourceBean> displayData = new ArrayList<>();
    private String currentKey = "";
    private String searchKeyword = "";
    private String filterChip = "全部";
    private final List<String> chipList = Arrays.asList("全部", "短剧", "综艺", "动漫", "影视", "其他");
    private OnSourceClick listener;

    public void setOnSourceClick(OnSourceClick l) {
        this.listener = l;
    }

    public List<String> getChipList() {
        return chipList;
    }

    public void submit(List<SourceBean> list) {
        allData.clear();
        if (list != null) allData.addAll(list);
        applyFilter();
        notifyDataSetChanged();
    }

    /** 当前选中的分类 chip 文本，供 UI 同步选中状态 */
    public String getFilterChip() {
        return filterChip;
    }

    public void setSearch(String keyword) {
        this.searchKeyword = keyword == null ? "" : keyword.trim().toLowerCase();
        applyFilter();
        notifyDataSetChanged();
    }

    public void setFilterChip(String chip) {
        this.filterChip = chip == null ? "全部" : chip;
        applyFilter();
        notifyDataSetChanged();
    }

    private void applyFilter() {
        displayData.clear();
        for (SourceBean s : allData) {
            String name = (s.getName() == null ? "" : s.getName()).toLowerCase();
            boolean matchSearch = searchKeyword.isEmpty() || name.contains(searchKeyword);
            boolean matchChip = "全部".equals(filterChip) || name.contains(filterChip.toLowerCase());
            if (matchSearch && matchChip) {
                displayData.add(s);
            }
        }
    }

    public void setCurrentKey(String key) {
        this.currentKey = key == null ? "" : key;
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_source_grid, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int position) {
        SourceBean s = displayData.get(position);
        h.tvName.setText(s.getName());
        bindLogo(h, s);
        boolean selected = currentKey.equals(s.getKey());
        h.tvName.setSelected(selected);
        h.itemView.setSelected(selected);
        h.itemView.setOnClickListener(v -> {
            if (listener != null) listener.onClick(s);
        });
    }

    @Override
    public int getItemCount() {
        return displayData.size();
    }

    /** 站源 LOGO 视图：开关关时整个图标区隐藏；开时显示首字母，api 是 http(s) 时再试加载域名 favicon，失败留首字母。 */
    private static void bindLogo(VH h, SourceBean s) {
        if (h.logoFrame == null) return;
        boolean show = false;
        try { show = Hawk.get(HawkConfig.SOURCE_LOGO_VIEW, true); } catch (Exception ignored) {}
        if (!show) {
            h.logoFrame.setVisibility(View.GONE);
            return;
        }
        h.logoFrame.setVisibility(View.VISIBLE);
        String name = s.getName() == null ? "" : s.getName();
        h.tvLogoLetter.setText(name.isEmpty() ? "?" : name.substring(0, 1));
        String key = s.getKey() == null ? "" : s.getKey();
        h.ivLogo.setTag(key);
        Glide.with(h.ivLogo.getContext()).clear(h.ivLogo);
        h.ivLogo.setVisibility(View.GONE);
        String favicon = faviconUrl(s.getApi());
        if (favicon == null) return;
        Glide.with(h.ivLogo.getContext())
                .load(favicon)
                .centerCrop()
                .listener(new RequestListener<Drawable>() {
                    @Override
                    public boolean onLoadFailed(@Nullable GlideException e, Object model, @NonNull Target<Drawable> target, boolean isFirstResource) {
                        return false;
                    }

                    @Override
                    public boolean onResourceReady(@NonNull Drawable resource, @NonNull Object model, @NonNull Target<Drawable> target, @NonNull DataSource dataSource, boolean isFirstResource) {
                        if (key.equals(h.ivLogo.getTag())) h.ivLogo.setVisibility(View.VISIBLE);
                        return false;
                    }
                })
                .into(h.ivLogo);
    }

    /** 只认 http(s) 接口地址，取 host 拼 favicon 地址；csp_/本地等非网址接口返回 null。 */
    private static String faviconUrl(String api) {
        if (api == null) return null;
        try {
            java.net.URI uri = new java.net.URI(api.trim());
            String host = uri.getHost();
            if (host == null || host.isEmpty()) return null;
            return "https://" + host + "/favicon.ico";
        } catch (Exception e) {
            return null;
        }
    }

    static class VH extends RecyclerView.ViewHolder {
        TextView tvName;
        View logoFrame;
        TextView tvLogoLetter;
        ImageView ivLogo;
        VH(@NonNull View itemView) {
            super(itemView);
            tvName = itemView.findViewById(R.id.tvName);
            logoFrame = itemView.findViewById(R.id.logoFrame);
            tvLogoLetter = itemView.findViewById(R.id.tvLogoLetter);
            ivLogo = itemView.findViewById(R.id.ivLogo);
        }
    }
}
