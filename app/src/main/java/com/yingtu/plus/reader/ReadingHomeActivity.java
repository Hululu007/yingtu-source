package com.yingtu.plus.reader;

import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import com.github.catvod.crawler.Spider;
import com.yingtu.plus.R;
import com.yingtu.plus.api.BuiltinOverrides;
import com.yingtu.plus.base.BaseActivity;
import com.yingtu.plus.utils.Task;

import java.util.ArrayList;
import java.util.List;

/**
 * 阅读主页：书架 + 小说 / 漫画 / 听书三个入口。
 * 书架来源无关，点开走该书自己那个源续读；
 * 源列表点进去是该源首页书单，点书进详情（阅读页 / 漫画页 / 听书页
 * 自己再取详情与章节）；搜索走当前源的 searchContent。
 * 底部导航五项已定不改，阅读入口由文件 tab 接线进来（见接线代码片段）。
 */
public class ReadingHomeActivity extends BaseActivity {

    /** 内置区列表点阅读站进来时用：要打开的站 key 与分类（novel / comics / audio）。 */
    public static final String EXTRA_KIND = "reader_kind";
    public static final String EXTRA_SOURCE_KEY = "reader_source_key";

    /** 列表一行：标题 + 副标题 + 动作 */
    private static class Row {
        final String title;
        final String sub;
        final Runnable action;
        final Runnable longAction;

        Row(String title, String sub, Runnable action, Runnable longAction) {
            this.title = title;
            this.sub = sub;
            this.action = action;
            this.longAction = longAction;
        }
    }

    private static class RowAdapter extends BaseAdapter {
        private final List<Row> rows = new ArrayList<>();
        private final LayoutInflater inflater;

        RowAdapter(ReadingHomeActivity activity) {
            inflater = LayoutInflater.from(activity);
        }

        void setRows(List<Row> newRows) {
            rows.clear();
            rows.addAll(newRows);
            notifyDataSetChanged();
        }

        @Override
        public int getCount() {
            return rows.size();
        }

        @Override
        public Row getItem(int position) {
            return rows.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View view = convertView;
            if (view == null) {
                view = inflater.inflate(R.layout.item_reader_book, parent, false);
            }
            Row row = rows.get(position);
            ((TextView) view.findViewById(R.id.tv_book_title)).setText(row.title);
            ((TextView) view.findViewById(R.id.tv_book_sub)).setText(row.sub);
            return view;
        }
    }

    private EditText etSearch;
    private TextView tvListTitle;
    private ListView lvShelf;
    private ListView lvList;
    private RowAdapter shelfAdapter;
    private RowAdapter listAdapter;

    private String currentKind = ReadingSources.KIND_NOVEL;
    /** 当前选中的源；为空表示列表显示的是源列表 */
    private ReadingSources.Source currentSource;

    @Override
    protected int getLayoutId() {
        return R.layout.activity_reading_home;
    }

    @Override
    protected void init() {
        etSearch = find(R.id.et_reader_search);
        tvListTitle = find(R.id.tv_reader_list_title);
        lvShelf = find(R.id.lv_reader_shelf);
        lvList = find(R.id.lv_reader_list);

        shelfAdapter = new RowAdapter(this);
        listAdapter = new RowAdapter(this);
        lvShelf.setAdapter(shelfAdapter);
        lvList.setAdapter(listAdapter);

        lvShelf.setOnItemClickListener((parent, view, position, id) -> {
            Row row = shelfAdapter.getItem(position);
            if (row.action != null) row.action.run();
        });
        lvShelf.setOnItemLongClickListener((parent, view, position, id) -> {
            Row row = shelfAdapter.getItem(position);
            if (row.longAction != null) row.longAction.run();
            return true;
        });
        lvList.setOnItemClickListener((parent, view, position, id) -> {
            Row row = listAdapter.getItem(position);
            if (row.action != null) row.action.run();
        });

        Button btnNovel = find(R.id.btn_kind_novel);
        Button btnComics = find(R.id.btn_kind_comics);
        Button btnAudio = find(R.id.btn_kind_audio);
        btnNovel.setOnClickListener(v -> switchKind(ReadingSources.KIND_NOVEL));
        btnComics.setOnClickListener(v -> switchKind(ReadingSources.KIND_COMICS));
        btnAudio.setOnClickListener(v -> switchKind(ReadingSources.KIND_AUDIO));
        find(R.id.btn_reader_search).setOnClickListener(v -> search());

        refreshShelf();
        showSources();
        // 内置区列表点阅读站进来：直接打开该站首页书单（删掉的站不进）。
        String wantKey = getIntent().getStringExtra(EXTRA_SOURCE_KEY);
        if (wantKey != null) {
            ReadingSources.Source source = ReadingSources.byKey(wantKey);
            if (source != null && !BuiltinOverrides.isDeleted(
                    BuiltinOverrides.ZONE_READING, wantKey)) {
                currentKind = source.kind;
                openSource(source);
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshShelf();
    }

    private void switchKind(String kind) {
        currentKind = kind;
        currentSource = null;
        etSearch.setText("");
        showSources();
    }

    /** 书架：点开走该书自己那个源续读；长按移出书架 */
    private void refreshShelf() {
        List<BookshelfStore.Book> books = BookshelfStore.list();
        List<Row> rows = new ArrayList<>();
        if (books.isEmpty()) {
            rows.add(new Row("书架还是空的", "在书页点“书架”加入", null, null));
        }
        for (BookshelfStore.Book book : books) {
            String kindName = ReadingSources.KIND_COMICS.equals(book.kind) ? "漫画"
                    : ReadingSources.KIND_AUDIO.equals(book.kind) ? "听书" : "小说";
            rows.add(new Row(book.title, kindName + " · " + book.siteKey,
                    () -> openShelfBook(book),
                    () -> {
                        BookshelfStore.remove(book.siteKey, book.bookId);
                        refreshShelf();
                        Toast.makeText(this, "已移出书架", Toast.LENGTH_SHORT).show();
                    }));
        }
        shelfAdapter.setRows(rows);
    }

    private void openShelfBook(BookshelfStore.Book book) {
        if (ReadingSources.KIND_COMICS.equals(book.kind)) {
            PicsViewerActivity.openBook(this, book.siteKey, book.siteApi,
                    book.siteExt, book.bookId, book.title);
        } else if (ReadingSources.KIND_AUDIO.equals(book.kind)) {
            AudioBookPlayer.openAlbum(this, book.siteKey, book.siteApi,
                    book.siteExt, book.bookId, book.title);
        } else {
            NovelReaderActivity.openBook(this, book.siteKey, book.siteApi,
                    book.siteExt, book.bookId, book.title);
        }
    }

    /** 源列表：点源进该源首页书单（隐藏/删掉的站不列，改过名的显示新名）。 */
    private void showSources() {
        List<ReadingSources.Source> sources = ReadingSources.byKind(currentKind);
        String kindName = ReadingSources.KIND_COMICS.equals(currentKind) ? "漫画源"
                : ReadingSources.KIND_AUDIO.equals(currentKind) ? "听书源" : "小说源";
        tvListTitle.setText(kindName);
        List<Row> rows = new ArrayList<>();
        for (ReadingSources.Source source : sources) {
            if (!BuiltinOverrides.isVisible(BuiltinOverrides.ZONE_READING, source.key)) continue;
            rows.add(new Row(displayName(source), source.key,
                    () -> openSource(source), null));
        }
        listAdapter.setRows(rows);
    }

    /** 显示名：覆盖清单里改过就用新名。 */
    private static String displayName(ReadingSources.Source source) {
        return BuiltinOverrides.displayName(BuiltinOverrides.ZONE_READING,
                source.key, source.name);
    }

    private void openSource(ReadingSources.Source source) {
        currentSource = source;
        tvListTitle.setText(displayName(source) + " · 首页");
        listAdapter.setRows(singleRow("加载中…", ""));
        Task.execute(() -> {
            try {
                Spider spider = NovelLoader.getSpider(source.key, source.api, "");
                List<NovelLoader.Book> books = NovelLoader.homeBooks(spider);
                runOnUiThread(() -> showBooks(books, source));
            } catch (Exception e) {
                runOnUiThread(() -> listAdapter.setRows(
                        singleRow("加载失败：" + e.getMessage(), "检查 py 文件是否已拷进 assets")));
            }
        });
    }

    private void showBooks(List<NovelLoader.Book> books, ReadingSources.Source source) {
        List<Row> rows = new ArrayList<>();
        if (books.isEmpty()) {
            rows.add(new Row("这个源首页没有书", "换个源或用搜索试试", null, null));
        }
        for (NovelLoader.Book book : books) {
            book.siteKey = source.key;
            book.siteApi = source.api;
            rows.add(new Row(book.title,
                    TextUtils.isEmpty(book.author) ? book.remarks : book.author,
                    () -> openBook(source, book), null));
        }
        listAdapter.setRows(rows);
    }

    private void openBook(ReadingSources.Source source, NovelLoader.Book book) {
        if (ReadingSources.KIND_COMICS.equals(source.kind)) {
            PicsViewerActivity.openBook(this, source.key, source.api,
                    "", book.id, book.title);
        } else if (ReadingSources.KIND_AUDIO.equals(source.kind)) {
            AudioBookPlayer.openAlbum(this, source.key, source.api,
                    "", book.id, book.title);
        } else {
            NovelReaderActivity.openBook(this, source.key, source.api,
                    "", book.id, book.title);
        }
    }

    private void search() {
        String key = etSearch.getText().toString().trim();
        if (TextUtils.isEmpty(key)) {
            Toast.makeText(this, "先输入书名或作者", Toast.LENGTH_SHORT).show();
            return;
        }
        if (currentSource == null) {
            Toast.makeText(this, "先选一个源再搜索", Toast.LENGTH_SHORT).show();
            return;
        }
        final ReadingSources.Source source = currentSource;
        tvListTitle.setText(source.name + " · 搜索：" + key);
        listAdapter.setRows(singleRow("搜索中…", ""));
        Task.execute(() -> {
            try {
                Spider spider = NovelLoader.getSpider(source.key, source.api, "");
                List<NovelLoader.Book> books = NovelLoader.searchBooks(spider, key);
                runOnUiThread(() -> showBooks(books, source));
            } catch (Exception e) {
                runOnUiThread(() -> listAdapter.setRows(
                        singleRow("搜索失败：" + e.getMessage(), "")));
            }
        });
    }

    private List<Row> singleRow(String title, String sub) {
        List<Row> rows = new ArrayList<>();
        rows.add(new Row(title, sub, null, null));
        return rows;
    }
}
