package com.yingtu.plus.ui.activity;

import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.yingtu.plus.R;
import com.yingtu.plus.base.BaseActivity;
import com.yingtu.plus.util.OkGo;

import java.text.Collator;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 应用页：列出手机上已装的可启动应用（桌面能看到的那些），点一个就打开它。
 *
 * 取数走 PackageManager.queryIntentActivities(ACTION_MAIN + CATEGORY_LAUNCHER)，
 * Manifest 里配了对应的 <queries> 声明（Android 11+ 没有它只能看到少量应用），
 * 不申请 QUERY_ALL_PACKAGES。图标与名称都取系统自带的，不另外存。
 */
public class AppsActivity extends BaseActivity {

    private AppAdapter adapter;
    private View emptyView;
    private TextView tvCount;

    @Override
    protected int getLayoutId() {
        return R.layout.activity_apps;
    }

    @Override
    protected void init() {
        emptyView = find(R.id.tvEmpty);
        tvCount = find(R.id.tvCount);
        find(R.id.ivBack).setOnClickListener(v -> finish());

        RecyclerView rv = find(R.id.recyclerView);
        rv.setLayoutManager(new LinearLayoutManager(this));
        adapter = new AppAdapter();
        adapter.setOnItemClick(this::openApp);
        rv.setAdapter(adapter);

        loadApps();
    }

    private void loadApps() {
        OkGo.io(() -> {
            List<AppItem> items = new ArrayList<>();
            try {
                PackageManager pm = getPackageManager();
                Intent main = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
                List<ResolveInfo> infos = pm.queryIntentActivities(main, 0);
                Set<String> seen = new HashSet<>();
                for (ResolveInfo info : infos) {
                    if (info.activityInfo == null) continue;
                    String pkg = info.activityInfo.packageName;
                    if (pkg == null || !seen.add(pkg)) continue;
                    CharSequence label = info.loadLabel(pm);
                    items.add(new AppItem(
                            label == null ? pkg : label.toString(),
                            pkg,
                            info.loadIcon(pm)));
                }
                Collator collator = Collator.getInstance(Locale.CHINA);
                items.sort((a, b) -> collator.compare(a.label, b.label));
            } catch (Throwable ignored) {}
            final List<AppItem> result = items;
            runOnUiThread(() -> {
                emptyView.setVisibility(result.isEmpty() ? View.VISIBLE : View.GONE);
                tvCount.setText("共 " + result.size() + " 个应用");
                adapter.submit(result);
            });
        });
    }

    private void openApp(AppItem item) {
        try {
            Intent launch = getPackageManager().getLaunchIntentForPackage(item.pkg);
            if (launch == null) {
                Toast.makeText(this, "该应用无法直接打开", Toast.LENGTH_SHORT).show();
                return;
            }
            startActivity(launch);
        } catch (Throwable e) {
            Toast.makeText(this, "打开失败", Toast.LENGTH_SHORT).show();
        }
    }

    private static class AppItem {
        final String label;
        final String pkg;
        final Drawable icon;

        AppItem(String label, String pkg, Drawable icon) {
            this.label = label;
            this.pkg = pkg;
            this.icon = icon;
        }
    }

    private static class AppAdapter extends RecyclerView.Adapter<AppAdapter.VH> {

        interface OnItemClick {
            void onClick(AppItem item);
        }

        private final List<AppItem> data = new ArrayList<>();
        private OnItemClick listener;

        void setOnItemClick(OnItemClick l) {
            this.listener = l;
        }

        void submit(List<AppItem> list) {
            data.clear();
            if (list != null) data.addAll(list);
            notifyDataSetChanged();
        }

        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_app, parent, false);
            return new VH(v);
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int position) {
            AppItem item = data.get(position);
            h.tvName.setText(item.label);
            h.tvPkg.setText(item.pkg);
            h.ivIcon.setImageDrawable(item.icon);
            h.itemView.setOnClickListener(v -> {
                if (listener != null) listener.onClick(item);
            });
        }

        @Override
        public int getItemCount() {
            return data.size();
        }

        static class VH extends RecyclerView.ViewHolder {
            final ImageView ivIcon;
            final TextView tvName;
            final TextView tvPkg;

            VH(@NonNull View itemView) {
                super(itemView);
                ivIcon = itemView.findViewById(R.id.ivIcon);
                tvName = itemView.findViewById(R.id.tvName);
                tvPkg = itemView.findViewById(R.id.tvPkg);
            }
        }
    }
}
