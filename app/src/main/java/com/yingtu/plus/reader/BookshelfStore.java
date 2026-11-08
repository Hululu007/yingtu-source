package com.yingtu.plus.reader;

import android.text.TextUtils;

import com.google.gson.reflect.TypeToken;
import com.orhanobut.hawk.Hawk;
import com.yingtu.plus.App;

import java.util.ArrayList;
import java.util.List;

/**
 * 书架：存 Hawk（树里现成的持久化口径，与 HistoryHelper / FavHelper 同款）。
 * 来源无关：任何站的小说 / 漫画 / 听书都能加；
 * 点开走该书自己那个源续读（书上记着 siteKey + siteApi + kind）。
 * 不做云同步。
 */
public class BookshelfStore {

    private static final String KEY_BOOKSHELF = "reader_bookshelf";

    /** 书架上一本书：书 + 它所属的源，续读就靠这两个字段找回源 */
    public static class Book {
        public String siteKey = "";
        public String siteApi = "";
        public String siteExt = "";
        public String kind = ReadingSources.KIND_NOVEL;
        public String bookId = "";
        public String title = "";
        public String pic = "";
        public String author = "";
        public long addedAt = 0;

        public Book() {
        }

        public Book(String siteKey, String siteApi, String kind, String bookId, String title) {
            this.siteKey = siteKey == null ? "" : siteKey;
            this.siteApi = siteApi == null ? "" : siteApi;
            this.kind = kind == null ? ReadingSources.KIND_NOVEL : kind;
            this.bookId = bookId == null ? "" : bookId;
            this.title = title == null ? "" : title;
            this.addedAt = System.currentTimeMillis();
        }
    }

    private BookshelfStore() {
    }

    public static List<Book> list() {
        try {
            String json = Hawk.get(KEY_BOOKSHELF, "");
            if (TextUtils.isEmpty(json)) return new ArrayList<>();
            List<Book> books = App.gson().fromJson(json,
                    new TypeToken<List<Book>>() {
                    }.getType());
            return books == null ? new ArrayList<>() : books;
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    public static boolean contains(String siteKey, String bookId) {
        for (Book book : list()) {
            if (book.siteKey.equals(siteKey) && book.bookId.equals(bookId)) return true;
        }
        return false;
    }

    /** 已在架上返回 false（不重复加） */
    public static boolean add(Book book) {
        if (book == null || TextUtils.isEmpty(book.bookId)) return false;
        List<Book> books = list();
        for (Book item : books) {
            if (item.siteKey.equals(book.siteKey) && item.bookId.equals(book.bookId)) return false;
        }
        book.addedAt = System.currentTimeMillis();
        books.add(0, book);
        save(books);
        return true;
    }

    public static void remove(String siteKey, String bookId) {
        List<Book> books = list();
        for (int i = books.size() - 1; i >= 0; i--) {
            Book book = books.get(i);
            if (book.siteKey.equals(siteKey) && book.bookId.equals(bookId)) books.remove(i);
        }
        save(books);
    }

    private static void save(List<Book> books) {
        try {
            Hawk.put(KEY_BOOKSHELF, App.gson().toJson(books));
        } catch (Exception ignored) {
        }
    }
}
