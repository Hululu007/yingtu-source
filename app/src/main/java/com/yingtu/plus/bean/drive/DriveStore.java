package com.yingtu.plus.bean.drive;

import android.text.TextUtils;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.yingtu.plus.App;
import com.yingtu.plus.util.Prefers;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;

/**
 * 网盘账号存取：Prefers 里一条 JSON（key drive_accounts），只存本地。
 */
public class DriveStore {

    private static final String KEY = "drive_accounts";
    private static final Gson GSON = new Gson();
    private static final Type LIST_TYPE = new TypeToken<List<DriveAccount>>() {
    }.getType();

    public static List<DriveAccount> load() {
        try {
            String json = Prefers.getString(KEY, "");
            if (TextUtils.isEmpty(json)) return new ArrayList<>();
            List<DriveAccount> list = GSON.fromJson(json, LIST_TYPE);
            return list == null ? new ArrayList<>() : list;
        } catch (Throwable e) {
            App.logCaught(e);
            return new ArrayList<>();
        }
    }

    public static void save(List<DriveAccount> accounts) {
        try {
            Prefers.put(KEY, GSON.toJson(accounts == null ? new ArrayList<>() : accounts));
        } catch (Throwable e) {
            App.logCaught(e);
        }
    }

    public static void add(DriveAccount account) {
        List<DriveAccount> list = load();
        list.add(account);
        save(list);
    }

    public static void remove(int index) {
        List<DriveAccount> list = load();
        if (index >= 0 && index < list.size()) {
            list.remove(index);
            save(list);
        }
    }
}
