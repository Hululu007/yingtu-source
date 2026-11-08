package com.fongmi.chaquo;

import android.text.TextUtils;

import com.chaquo.python.PyObject;
import com.chaquo.python.Python;
import com.chaquo.python.android.AndroidPlatform;
import com.github.catvod.crawler.SpiderDebug;
import com.github.catvod.net.OkHttp;
import com.github.catvod.utils.Path;
import com.github.catvod.utils.Util;
import com.yingtu.plus.App;

import java.io.File;
import java.nio.charset.StandardCharsets;

import okhttp3.Response;

public class Loader {

    private volatile PyObject app;

    public Loader() {
    }

    public Spider spider(String api) {
        ensurePython();
        String name = scriptName(api);
        String source = source(api, name);
        PyObject obj = app.callAttr("spider", Path.py().getAbsolutePath(), source, name);
        return new Spider(app, obj, api);
    }

    private synchronized void ensurePython() {
        if (app != null) return;
        if (App.get() == null) throw new IllegalStateException("App not initialized");
        if (!Python.isStarted()) Python.start(new AndroidPlatform(App.get()));
        app = Python.getInstance().getModule("app");
    }

    private String source(String api, String name) {
        if (!api.startsWith("http")) return api;
        File cache = Path.py(name);
        try (Response response = OkHttp.newCall(OkHttp.client(15000), api).execute()) {
            if (!response.isSuccessful()) throw new IllegalStateException("HTTP " + response.code());
            if (response.body() == null) throw new IllegalStateException("Empty python script");
            String source = response.body().string();
            if (TextUtils.isEmpty(source)) throw new IllegalStateException("Empty python script");
            Path.write(cache, source.getBytes(StandardCharsets.UTF_8));
            return source;
        } catch (Exception e) {
            String cached = Path.read(cache);
            if (!TextUtils.isEmpty(cached)) return cached;
            SpiderDebug.log("py-spider", e);
            throw new IllegalStateException("Unable to download python script: " + api, e);
        }
    }

    private String scriptName(String api) {
        String suffix = api.toLowerCase().contains(".py") ? ".py" : ".script";
        return Util.md5(api) + suffix;
    }
}
