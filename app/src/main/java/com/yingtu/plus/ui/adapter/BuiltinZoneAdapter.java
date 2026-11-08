package com.yingtu.plus.ui.adapter;

import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.widget.SwitchCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.yingtu.plus.R;
import com.yingtu.plus.api.SiteHealthResults;

import java.util.ArrayList;
import java.util.List;

/**
 * 内置区列表适配器：按分区（午夜/短剧/直播）分组，组头可点折叠/展开；
 * 每站一行带显示开关（照火蚁TV源显示列表的 SwitchListTile），长按出改名/删除/单站检测菜单。
 * 行右侧显示上次检测结果（正常/异常/超时/空/待定），没测过显示“待定”。
 */
public class BuiltinZoneAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    /** 一站。 */
    public static class Item {
        public final String zone;
        public final String key;
        public final String name;
        public final String sub;
        public boolean visible;
        public SiteHealthResults.Result health;

        public Item(String zone, String key, String name, String sub, boolean visible,
                    SiteHealthResults.Result health) {
            this.zone = zone;
            this.key = key;
            this.name = name;
            this.sub = sub;
            this.visible = visible;
            this.health = health;
        }

        public String id() {
            return zone + ":" + key;
        }
    }

    /** 一组站。 */
    public static class Group {
        public final String title;
        public final List<Item> items;
        public boolean collapsed;

        public Group(String title, List<Item> items) {
            this.title = title == null ? "" : title;
            this.items = items == null ? new ArrayList<>() : items;
            this.collapsed = false;
        }

        public int hiddenCount() {
            int n = 0;
            for (Item item : items) if (!item.visible) n++;
            return n;
        }
    }

    public interface OnItemClick {
        void onClick(Item item);
    }

    public interface OnItemLongClick {
        void onLongClick(Item item);
    }

    public interface OnVisibilityChanged {
        void onChanged(Item item, boolean visible);
    }

    private static final int TYPE_GROUP = 0;
    private static final int TYPE_ITEM = 1;

    private final List<Group> groups = new ArrayList<>();
    private OnItemClick clickListener;
    private OnItemLongClick longClickListener;
    private OnVisibilityChanged visibilityListener;

    public void setOnItemClick(OnItemClick l) {
        this.clickListener = l;
    }

    public void setOnItemLongClick(OnItemLongClick l) {
        this.longClickListener = l;
    }

    public void setOnVisibilityChanged(OnVisibilityChanged l) {
        this.visibilityListener = l;
    }

    public void submitGroups(List<Group> list) {
        groups.clear();
        if (list != null) groups.addAll(list);
        notifyDataSetChanged();
    }

    public List<Group> getGroups() {
        return groups;
    }

    /** 检测跑完一站：刷新该行的状态徽标（开关状态由自动屏蔽另行刷新）。 */
    public void updateHealth(String zone, String key, SiteHealthResults.Result result) {
        for (Group g : groups) {
            for (Item item : g.items) {
                if (item.zone.equals(zone) && item.key.equals(key)) {
                    item.health = result;
                    notifyDataSetChanged();
                    return;
                }
            }
        }
    }

    /** 开关状态变化后刷新整组（组头的隐藏计数会变）。 */
    public void refreshGroup(Item item) {
        notifyDataSetChanged();
    }

    private static class Row {
        final Group group;
        final Item item;

        Row(Group g, Item i) {
            group = g;
            item = i;
        }
    }

    private List<Row> flatten() {
        List<Row> rows = new ArrayList<>();
        for (Group g : groups) {
            rows.add(new Row(g, null));
            if (!g.collapsed) {
                for (Item item : g.items) rows.add(new Row(g, item));
            }
        }
        return rows;
    }

    @Override
    public int getItemViewType(int position) {
        return flatten().get(position).item == null ? TYPE_GROUP : TYPE_ITEM;
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
                .inflate(R.layout.item_builtin_zone, parent, false);
        return new ItemVH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        Row row = flatten().get(position);
        if (holder instanceof GroupVH) {
            GroupVH h = (GroupVH) holder;
            String arrow = row.group.collapsed ? "▶ " : "▼ ";
            int hidden = row.group.hiddenCount();
            h.tvGroup.setText(arrow + row.group.title + "（" + row.group.items.size() + " 个站"
                    + (hidden > 0 ? " · " + hidden + " 个隐藏" : "") + "）");
            h.itemView.setOnClickListener(v -> {
                row.group.collapsed = !row.group.collapsed;
                notifyDataSetChanged();
            });
        } else {
            ItemVH h = (ItemVH) holder;
            Item item = row.item;
            h.tvName.setText(item.name);
            h.tvSub.setText(item.sub);
            if (item.health == null) {
                h.tvStatus.setText("待定");
                h.tvStatus.setTextColor(h.itemView.getContext()
                        .getColor(android.R.color.darker_gray));
            } else {
                h.tvStatus.setText(SiteHealthResults.label(item.health.status));
                int color;
                if (SiteHealthResults.OK.equals(item.health.status)) {
                    color = h.itemView.getContext().getColor(android.R.color.holo_green_dark);
                } else if (SiteHealthResults.EMPTY.equals(item.health.status)) {
                    color = h.itemView.getContext().getColor(android.R.color.holo_orange_dark);
                } else {
                    color = h.itemView.getContext().getColor(android.R.color.holo_red_dark);
                }
                h.tvStatus.setTextColor(color);
            }
            // 先断掉监听再设值，避免复用时误触发。
            h.swVisible.setOnCheckedChangeListener(null);
            h.swVisible.setChecked(item.visible);
            h.swVisible.setOnCheckedChangeListener((button, checked) -> {
                item.visible = checked;
                if (visibilityListener != null) visibilityListener.onChanged(item, checked);
            });
            h.itemView.setOnClickListener(v -> {
                if (clickListener != null) clickListener.onClick(item);
            });
            h.itemView.setOnLongClickListener(v -> {
                if (longClickListener != null) longClickListener.onLongClick(item);
                return true;
            });
        }
    }

    /** 组头：单行文字（箭头 + 组名 + 站数 + 隐藏数），可点折叠/展开。 */
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

    static class ItemVH extends RecyclerView.ViewHolder {
        TextView tvName, tvSub, tvStatus;
        SwitchCompat swVisible;

        ItemVH(@NonNull View itemView) {
            super(itemView);
            tvName = itemView.findViewById(R.id.tvName);
            tvSub = itemView.findViewById(R.id.tvSub);
            tvStatus = itemView.findViewById(R.id.tvStatus);
            swVisible = itemView.findViewById(R.id.swVisible);
        }
    }
}
