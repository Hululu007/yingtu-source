package com.yingtu.plus.ui.activity;

import android.content.Intent;
import android.text.TextUtils;
import android.view.View;
import android.widget.EditText;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.yingtu.plus.R;
import com.yingtu.plus.base.BaseActivity;
import com.yingtu.plus.bean.OnlineSite;
import com.yingtu.plus.ui.adapter.OnlineSiteAdapter;
import com.yingtu.plus.util.OnlineSiteStore;

import java.util.List;

/**
 * Online website manager: add / edit / delete sites, tap a site to browse it.
 * Entry: the globe button in the home source-picker popup.
 * Sites here never enter the builtin station tables.
 */
public class OnlineSiteListActivity extends BaseActivity {

    public static final String KEY_SITE_ID = "online_site_id";

    private RecyclerView recyclerView;
    private TextView tvEmpty;
    private OnlineSiteAdapter adapter;

    @Override
    protected int getLayoutId() {
        return R.layout.activity_online_site_list;
    }

    @Override
    protected void init() {
        find(R.id.ivBack).setOnClickListener(v -> finish());
        TextView tvTitle = find(R.id.tvTitle);
        tvTitle.setText(R.string.online_site_title);

        recyclerView = find(R.id.recyclerView);
        tvEmpty = find(R.id.tvEmpty);
        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        adapter = new OnlineSiteAdapter();
        adapter.setOnItemClick(this::openSite);
        adapter.setOnItemLongClick(this::showItemMenu);
        recyclerView.setAdapter(adapter);

        FloatingActionButton btnAdd = find(R.id.btnAdd);
        btnAdd.setOnClickListener(v -> showEditDialog(null));
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    private void refresh() {
        List<OnlineSite> list = OnlineSiteStore.getAll();
        tvEmpty.setVisibility(list.isEmpty() ? View.VISIBLE : View.GONE);
        adapter.submit(list);
    }

    private void openSite(OnlineSite site) {
        if (site == null || TextUtils.isEmpty(site.id)) return;
        Intent i = new Intent(this, OnlineSiteBrowserActivity.class);
        i.putExtra(KEY_SITE_ID, site.id);
        startActivity(i);
    }

    private void showItemMenu(OnlineSite site) {
        if (site == null) return;
        new AlertDialog.Builder(this)
                .setTitle(site.name)
                .setItems(new String[]{
                        getString(R.string.online_site_edit_action),
                        getString(R.string.online_site_delete_action)
                }, (d, which) -> {
                    if (which == 0) showEditDialog(site);
                    else confirmDelete(site);
                })
                .show();
    }

    private void confirmDelete(OnlineSite site) {
        new AlertDialog.Builder(this)
                .setMessage(getString(R.string.online_site_delete_confirm, site.name))
                .setPositiveButton(R.string.online_site_delete_action, (d, w) -> {
                    OnlineSiteStore.delete(site.id);
                    refresh();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void showEditDialog(OnlineSite site) {
        boolean isNew = site == null;
        View form = View.inflate(this, R.layout.dialog_add_online_site, null);
        EditText etName = form.findViewById(R.id.etName);
        EditText etUrl = form.findViewById(R.id.etUrl);
        TextView tvMode = form.findViewById(R.id.tvMode);
        Switch swPure = form.findViewById(R.id.swPure);
        tvMode.setText(R.string.online_site_mode_auto);
        if (!isNew) {
            etName.setText(site.name);
            etUrl.setText(site.url);
            swPure.setChecked(site.pure);
        }

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(isNew ? R.string.online_site_add : R.string.online_site_edit)
                .setView(form)
                .setPositiveButton(android.R.string.ok, null)
                .setNegativeButton(android.R.string.cancel, null)
                .create();

        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(v -> {
                    String name = etName.getText().toString().trim();
                    String url = etUrl.getText().toString().trim();
                    if (TextUtils.isEmpty(url)) {
                        toast(R.string.online_site_need_url);
                        return;
                    }
                    if (!url.startsWith("http://") && !url.startsWith("https://")) {
                        toast(R.string.online_site_need_http);
                        return;
                    }
                    if (TextUtils.isEmpty(name)) name = hostOf(url);
                    boolean pure = swPure.isChecked();
                    if (isNew) {
                        OnlineSiteStore.save(new OnlineSite(name, url, OnlineSite.MODE_AUTO, pure));
                    } else {
                        site.name = name;
                        site.url = url;
                        site.mode = OnlineSite.MODE_AUTO;
                        site.pure = pure;
                        OnlineSiteStore.save(site);
                    }
                    refresh();
                    dialog.dismiss();
                }));

        dialog.show();
    }

    private String hostOf(String url) {
        try {
            String h = url.replaceFirst("^https?://", "");
            int slash = h.indexOf('/');
            if (slash >= 0) h = h.substring(0, slash);
            return TextUtils.isEmpty(h) ? url : h;
        } catch (Throwable e) {
            return url;
        }
    }

    private void toast(int resId) {
        Toast.makeText(this, resId, Toast.LENGTH_SHORT).show();
    }
}
