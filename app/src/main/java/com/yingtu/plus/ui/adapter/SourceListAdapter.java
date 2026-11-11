package com.yingtu.plus.ui.adapter;

import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.yingtu.plus.R;
import com.yingtu.plus.bean.SourceBean;

import java.util.ArrayList;
import java.util.List;

/**
 * 视频源列表适配器：按接口配置分组，组头可点折叠/展开。
 * 收起后组头只显示组名 + 站数；展开后组内各站以三列网格显示，当前选中源高亮。
 * 组头视图用代码创建，不新增布局文件；站点格子复用 item_source_grid。
 */
public class SourceListAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    public interface OnSourceClick {
        void onClick(SourceBean source);
    }

    /** 一组站 = 一次 parseConfig 加载的配置。name 为配置来源（订阅地址/文件名），取不到时为“配置 N”。 */
    public static class Group {
        public final String name;
        public final List<SourceBean> sites;
        public boolean collapsed;

        public Group(String name, List<SourceBean> sites) {
            this.name = name == null ? "" : name;
            this.sites = sites == null ? new ArrayList<>() : sites;
            this.collapsed = false;
        }
    }

    private static final int TYPE_GROUP = 0;
    private static final int TYPE_SOURCE = 1;

    private final List<Group> groups = new ArrayList<>();
    private String currentKey = "";
    private OnSourceClick listener;

    /** 组头行占满 3 列、站点格子占 1 列（SourceActivity 的 SpanSizeLookup 用）。 */
    public boolean isGroupPosition(int position) {
        return getItemViewType(position) == TYPE_GROUP;
    }

    public void setOnSourceClick(OnSourceClick l) {
        this.listener = l;
    }

    /** 分组提交：每组一个组头 + 组内站点。 */
    public void submitGroups(List<Group> list) {
        groups.clear();
        if (list != null) groups.addAll(list);
        notifyDataSetChanged();
    }

    /** 兼容旧调用：单组、不显示组头。 */
    public void submit(List<SourceBean> list) {
        List<Group> one = new ArrayList<>();
        one.add(new Group("", list));
        submitGroups(one);
    }

    public void setCurrentKey(String key) {
        this.currentKey = key == null ? "" : key;
        notifyDataSetChanged();
    }

    // ===== 扁平位置映射：组头行（source 为 null）+ 站点行 =====
    private static class Row {
        final Group group;
        final SourceBean source;
        Row(Group g, SourceBean s) {
            group = g;
            source = s;
        }
    }

    private List<Row> flatten() {
        List<Row> rows = new ArrayList<>();
        for (Group g : groups) {
            if (g.name != null && !g.name.isEmpty()) rows.add(new Row(g, null));
            if (!g.collapsed) {
                for (SourceBean s : g.sites) rows.add(new Row(g, s));
            }
        }
        return rows;
    }

    @Override
    public int getItemViewType(int position) {
        return flatten().get(position).source == null ? TYPE_GROUP : TYPE_SOURCE;
    }

    @Override
    public int getItemCount() {
        return flatten().size();
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        if (viewType == TYPE_GROUP) {
            return new GroupVH(buildGroupHeaderView(parent));
        }
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_source_grid, parent, false);
        return new SourceVH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        Row row = flatten().get(position);
        if (holder instanceof GroupVH) {
            GroupVH h = (GroupVH) holder;
            String arrow = row.group.collapsed ? "▶ " : "▼ ";
            h.tvGroup.setText(arrow + row.group.name + "（" + row.group.sites.size() + " 个源）");
            h.itemView.setOnClickListener(v -> {
                row.group.collapsed = !row.group.collapsed;
                notifyDataSetChanged();
            });
        } else {
            SourceVH h = (SourceVH) holder;
            SourceBean s = row.source;
            h.tvName.setText(s.getName());
            boolean selected = currentKey.equals(s.getKey());
            h.tvName.setSelected(selected);
            h.itemView.setSelected(selected);
            h.itemView.setOnClickListener(v -> {
                if (listener != null) listener.onClick(s);
            });
        }
    }

    /** 组头：单行文字（箭头 + 组名 + 站数），可点折叠/展开。 */
    private View buildGroupHeaderView(ViewGroup parent) {
        android.content.Context ctx = parent.getContext();
        LinearLayout layout = new LinearLayout(ctx);
        layout.setOrientation(LinearLayout.HORIZONTAL);
        layout.setGravity(android.view.Gravity.CENTER_VERTICAL);
        int padH = dp(ctx, 14);
        int padV = dp(ctx, 12);
        layout.setPadding(padH, padV, padH, padV);
        layout.setLayoutParams(new RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        TypedValue ripple = new TypedValue();
        if (ctx.getTheme().resolveAttribute(android.R.attr.selectableItemBackground, ripple, true)) {
            layout.setBackgroundResource(ripple.resourceId);
        }
        TextView tv = new TextView(ctx);
        tv.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        tv.setTextColor(ctx.getColor(R.color.text_primary));
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        tv.setTypeface(null, android.graphics.Typeface.BOLD);
        tv.setSingleLine(true);
        tv.setEllipsize(android.text.TextUtils.TruncateAt.END);
        layout.addView(tv);
        layout.setTag(tv);
        return layout;
    }

    private static int dp(android.content.Context ctx, int dp) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp,
                ctx.getResources().getDisplayMetrics()));
    }

    static class GroupVH extends RecyclerView.ViewHolder {
        TextView tvGroup;
        GroupVH(@NonNull View itemView) {
            super(itemView);
            tvGroup = (TextView) itemView.getTag();
        }
    }

    static class SourceVH extends RecyclerView.ViewHolder {
        TextView tvName;
        SourceVH(@NonNull View itemView) {
            super(itemView);
            tvName = itemView.findViewById(R.id.tvName);
        }
    }
}
