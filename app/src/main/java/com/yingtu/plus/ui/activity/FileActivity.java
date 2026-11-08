package com.yingtu.plus.ui.activity;

// 按调用点重建：FileChooser 的兜底文件选择器（无系统文件选择器/电视端时启用），返回所选文件的 Uri。
// 另带 py 导入模式：带 EXTRA_PY_IMPORT 启动，或被外部以 ACTION_VIEW / ACTION_SEND
// 分享 .py 文件进来时，显示「导入 py 文件 / 批量扫描文件夹」入口，走 PySiteImporter
// 同一条导入注册链路。默认（无 extra、非分享启动）仍是原来的文件选择器行为。

import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Environment;
import android.provider.OpenableColumns;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;

import com.yingtu.plus.App;
import com.yingtu.plus.api.PySiteImporter;
import com.yingtu.plus.base.BaseActivity;
import com.yingtu.plus.reader.ReadingHomeActivity;
import com.yingtu.plus.utils.FileChooser;
import com.yingtu.plus.utils.Task;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

public class FileActivity extends BaseActivity {

    /** 文件 tab（后续底部导航接线）用此 extra 打开导入模式。 */
    public static final String EXTRA_PY_IMPORT = "py_import";

    private static final int MODE_NONE = 0;
    private static final int MODE_PICK_PY = 1;
    private static final int MODE_PICK_DIR = 2;

    // 必须声明在 pickLauncher 之前：lambda 初始化器引用了它，放后面会 illegal forward reference。
    private int pendingMode = MODE_NONE;

    private final ActivityResultLauncher<Intent> pickLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() != RESULT_OK || result.getData() == null) return;
                Uri uri = result.getData().getData();
                if (uri == null) return;
                if (pendingMode == MODE_PICK_PY) onPyFilePicked(uri);
                else if (pendingMode == MODE_PICK_DIR) onDirPicked(uri);
                pendingMode = MODE_NONE;
            });

    private TextView pathView;
    private ListView listView;
    private ArrayAdapter<String> adapter;
    private final List<File> items = new ArrayList<>();
    private File currentDir;
    private boolean selectDir;

    private boolean importMode;
    private TextView resultView;

    @Override
    protected int getLayoutId() {
        return 0;
    }

    @Override
    protected void init() {
        Intent intent = getIntent();
        importMode = intent != null && (intent.getBooleanExtra(EXTRA_PY_IMPORT, false) || isExternalPyIntent(intent));
        if (importMode) {
            buildImportUi();
            handleExternalIntent(intent);
            return;
        }
        selectDir = intent != null && intent.getBooleanExtra("select_dir", false);
        currentDir = Environment.getExternalStorageDirectory();

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.MATCH_PARENT));

        pathView = new TextView(this);
        pathView.setPadding(24, 24, 24, 16);
        root.addView(pathView);

        // 正常模式下的可见入口：py 导入与阅读（此前导入按钮只在 importMode 下出现）
        LinearLayout topRow = new LinearLayout(this);
        topRow.setOrientation(LinearLayout.HORIZONTAL);
        Button importPyBtn = new Button(this);
        importPyBtn.setText("导入 py 文件");
        importPyBtn.setOnClickListener(v -> startActivity(
                new Intent(this, FileActivity.class).putExtra(EXTRA_PY_IMPORT, true)));
        topRow.addView(importPyBtn);
        Button oneKeyBtn = new Button(this);
        oneKeyBtn.setText("一键写PY");
        oneKeyBtn.setOnClickListener(v -> startActivity(
                new Intent(this, OneKeyPyActivity.class)));
        topRow.addView(oneKeyBtn);
        Button readingBtn = new Button(this);
        readingBtn.setText("阅读");
        readingBtn.setOnClickListener(v -> startActivity(
                new Intent(this, ReadingHomeActivity.class)));
        topRow.addView(readingBtn);
        root.addView(topRow);

        if (selectDir) {
            Button pickDir = new Button(this);
            pickDir.setText("选择当前目录");
            pickDir.setOnClickListener(v -> returnResult(currentDir));
            root.addView(pickDir);
        }

        listView = new ListView(this);
        listView.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.MATCH_PARENT, 1f));
        adapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, new ArrayList<>());
        listView.setAdapter(adapter);
        listView.setOnItemClickListener((parent, view, position, id) -> onItemClicked(items.get(position)));
        root.addView(listView);

        setContentView(root);
        showDir(currentDir);
    }

    // ===== py 导入模式 =====

    private static boolean isExternalPyIntent(Intent intent) {
        String action = intent.getAction();
        if (!Intent.ACTION_VIEW.equals(action) && !Intent.ACTION_SEND.equals(action)) return false;
        if (Intent.ACTION_VIEW.equals(action)) return intent.getData() != null;
        if (intent.getParcelableExtra(Intent.EXTRA_STREAM) != null) return true;
        String text = intent.getStringExtra(Intent.EXTRA_TEXT);
        return text != null && text.contains("class Spider");
    }

    private void buildImportUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.MATCH_PARENT));
        root.setPadding(24, 24, 24, 24);

        TextView title = new TextView(this);
        title.setText("导入 py 文件成站点");
        title.setTextSize(18);
        title.setPadding(0, 0, 0, 24);
        root.addView(title);

        Button importPy = new Button(this);
        importPy.setText("导入 py 文件");
        importPy.setOnClickListener(v -> {
            pendingMode = MODE_PICK_PY;
            FileChooser.from(pickLauncher).show("text/x-python",
                    new String[]{"text/x-python", "application/x-python", "text/plain", "*/*"});
        });
        root.addView(importPy);

        Button scanDir = new Button(this);
        scanDir.setText("批量扫描文件夹");
        scanDir.setOnClickListener(v -> {
            pendingMode = MODE_PICK_DIR;
            FileChooser.from(pickLauncher).showDirectory();
        });
        root.addView(scanDir);

        Button oneKeyPy = new Button(this);
        oneKeyPy.setText("一键写PY（输网址生成）");
        oneKeyPy.setOnClickListener(v -> startActivity(
                new Intent(this, OneKeyPyActivity.class)));
        root.addView(oneKeyPy);

        TextView hint = new TextView(this);
        hint.setText("只读你选中的文件/文件夹，不扫描整机。导入的站记进视频源分组「本地 py 导入」，去视频源页点站即可看内容。");
        hint.setPadding(0, 16, 0, 16);
        root.addView(hint);

        resultView = new TextView(this);
        resultView.setPadding(0, 8, 0, 8);
        ScrollView scroll = new ScrollView(this);
        scroll.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.MATCH_PARENT, 1f));
        scroll.addView(resultView);
        root.addView(scroll);

        setContentView(root);
    }

    /** 外部 App 分享 .py 进来：ACTION_VIEW 取 data，ACTION_SEND 取 EXTRA_STREAM（或直接分享的源码文本）。 */
    private void handleExternalIntent(Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        if (Intent.ACTION_VIEW.equals(action) && intent.getData() != null) {
            onPyFilePicked(intent.getData());
        } else if (Intent.ACTION_SEND.equals(action)) {
            Uri uri = intent.getParcelableExtra(Intent.EXTRA_STREAM);
            if (uri != null) {
                onPyFilePicked(uri);
            } else {
                String text = intent.getStringExtra(Intent.EXTRA_TEXT);
                if (text != null && text.contains("class Spider")) {
                    importOneText("shared.py", text);
                }
            }
        }
    }

    private void onPyFilePicked(Uri uri) {
        appendResult("正在导入：" + displayName(uri));
        Task.execute(() -> {
            List<PySiteImporter.Result> results;
            try {
                String source = readText(uri);
                results = PySiteImporter.importFiles(this, Collections.singletonList(
                        new PySiteImporter.Input(displayName(uri), source)));
            } catch (Exception e) {
                results = Collections.singletonList(
                        PySiteImporter.Result.failure(displayName(uri), "读取失败：" + e.getMessage()));
            }
            List<PySiteImporter.Result> done = results;
            App.post(() -> showResults(done));
        });
    }

    private void onDirPicked(Uri uri) {
        String path = FileChooser.getPathFromUri(uri);
        if (path == null || path.isEmpty()) {
            Toast.makeText(this, "无法取到文件夹路径", Toast.LENGTH_LONG).show();
            return;
        }
        appendResult("正在扫描文件夹：" + path);
        Task.execute(() -> {
            List<PySiteImporter.Result> results = PySiteImporter.importDirectory(this, new File(path));
            App.post(() -> showResults(results));
        });
    }

    private void importOneText(String fileName, String source) {
        appendResult("正在导入：" + fileName);
        Task.execute(() -> {
            List<PySiteImporter.Result> results = PySiteImporter.importFiles(this, Collections.singletonList(
                    new PySiteImporter.Input(fileName, source)));
            App.post(() -> showResults(results));
        });
    }

    /** 逐个汇报成功/失败，不因一个失败停全批。 */
    private void showResults(List<PySiteImporter.Result> results) {
        if (results == null || results.isEmpty()) return;
        int ok = 0;
        StringBuilder sb = new StringBuilder();
        for (PySiteImporter.Result r : results) {
            if (r.ok) {
                ok++;
                sb.append("✓ ").append(r.name).append("（").append(r.key).append("）\n");
            } else {
                sb.append("✗ ").append(r.fileName).append("：").append(r.error).append("\n");
            }
        }
        appendResult(sb.toString().trim());
        Toast.makeText(this, "导入完成：成功 " + ok + " 个，失败 " + (results.size() - ok) + " 个",
                Toast.LENGTH_LONG).show();
    }

    private void appendResult(String text) {
        if (resultView == null) return;
        String old = resultView.getText().toString();
        resultView.setText(old.isEmpty() ? text : old + "\n" + text);
    }

    private String readText(Uri uri) throws Exception {
        try (InputStream in = getContentResolver().openInputStream(uri);
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            if (in == null) throw new java.io.IOException("无法打开文件");
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
            return out.toString("UTF-8");
        }
    }

    private String displayName(Uri uri) {
        String name = null;
        try (Cursor c = getContentResolver().query(uri,
                new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (idx >= 0) name = c.getString(idx);
            }
        } catch (Exception ignored) {
        }
        if (name == null || name.isEmpty()) name = uri.getLastPathSegment();
        if (name == null || name.isEmpty()) name = "unknown.py";
        return name;
    }

    // ===== 原有文件选择器（行为不动） =====

    private void showDir(File dir) {
        File[] files = dir == null ? null : dir.listFiles();
        if (files == null) {
            pathView.setText("无法访问该目录");
            items.clear();
            adapter.clear();
            return;
        }
        currentDir = dir;
        pathView.setText(dir.getAbsolutePath());
        Arrays.sort(files, Comparator.comparing((File f) -> !f.isDirectory()).thenComparing(File::getName));
        items.clear();
        List<String> names = new ArrayList<>();
        File parent = dir.getParentFile();
        if (parent != null) {
            items.add(parent);
            names.add("../");
        }
        for (File file : files) {
            items.add(file);
            names.add(file.isDirectory() ? file.getName() + "/" : file.getName());
        }
        adapter.clear();
        adapter.addAll(names);
        adapter.notifyDataSetChanged();
    }

    private void onItemClicked(File file) {
        if (file.isDirectory()) {
            showDir(file);
            return;
        }
        if (selectDir) return;
        returnResult(file);
    }

    private void returnResult(File file) {
        Intent result = new Intent();
        result.setData(Uri.fromFile(file));
        setResult(RESULT_OK, result);
        finish();
    }

    @Override
    public void onBackPressed() {
        if (importMode) {
            super.onBackPressed();
            return;
        }
        File parent = currentDir == null ? null : currentDir.getParentFile();
        if (parent != null && parent.canRead()) {
            showDir(parent);
            return;
        }
        super.onBackPressed();
    }
}
