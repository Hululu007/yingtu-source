package com.fongmi.chaquo;

import android.content.Context;

import com.chaquo.python.PyObject;
import com.github.catvod.crawler.SpiderDebug;
import com.github.catvod.utils.Path;
import com.github.catvod.utils.UriUtil;
import com.github.catvod.utils.Util;
import com.google.gson.Gson;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class Spider extends com.github.catvod.crawler.Spider {

    private final PyObject app;
    private final PyObject obj;
    private final String api;
    private final Gson gson;

    public Spider(PyObject app, PyObject obj, String api) {
        this.gson = new Gson();
        this.app = app;
        this.obj = obj;
        this.api = api;
    }

    public String getName() {
        return str(app.callAttr("getName", obj));
    }

    public List<String> getDependence() {
        List<String> result = new ArrayList<>();
        PyObject dependence = app.callAttr("getDependence", obj);
        if (dependence == null) return result;
        for (PyObject item : dependence.asList()) result.add(item.toString());
        return result;
    }

    @Override
    public void init(Context context) throws Exception {
        init(context, "");
    }

    @Override
    public void init(Context context, String extend) throws Exception {
        for (String name : getDependence()) download(name + ".py");
        obj.put("siteKey", siteKey);
        app.callAttr("init", obj, extend);
    }

    @Override
    public String homeContent(boolean filter) throws Exception {
        return str(app.callAttr("homeContent", obj, filter));
    }

    @Override
    public String homeVideoContent() throws Exception {
        return str(app.callAttr("homeVideoContent", obj));
    }

    @Override
    public String categoryContent(String tid, String pg, boolean filter, HashMap<String, String> extend) throws Exception {
        return str(app.callAttr("categoryContent", obj, tid, pg, filter, gson.toJson(extend)));
    }

    @Override
    public String detailContent(List<String> ids) throws Exception {
        return str(app.callAttr("detailContent", obj, gson.toJson(ids)));
    }

    @Override
    public String searchContent(String key, boolean quick) throws Exception {
        return str(app.callAttr("searchContent", obj, key, quick));
    }

    @Override
    public String searchContent(String key, boolean quick, String pg) throws Exception {
        return str(app.callAttr("searchContent", obj, key, quick, pg));
    }

    @Override
    public String playerContent(String flag, String id, List<String> vipFlags) throws Exception {
        return str(app.callAttr("playerContent", obj, flag, id, gson.toJson(vipFlags)));
    }

    @Override
    public String liveContent(String url) throws Exception {
        return str(app.callAttr("liveContent", obj, url));
    }

    @Override
    public boolean manualVideoCheck() throws Exception {
        return bool(app.callAttr("manualVideoCheck", obj));
    }

    @Override
    public boolean isVideoFormat(String url) throws Exception {
        return bool(app.callAttr("isVideoFormat", obj, url));
    }

    @Override
    public Object[] proxy(Map<String, String> params) throws Exception {
        PyObject result = app.callAttr("localProxy", obj, gson.toJson(params));
        if (result == null) return null;
        List<PyObject> list = result.asList();
        if (list == null || list.size() < 3) return null;
        boolean base64 = list.size() > 4 && list.get(4) != null && list.get(4).toInt() == 1;
        boolean header = list.size() > 3 && list.get(3) != null;
        Object[] value = new Object[4];
        value[0] = list.get(0).toInt();
        value[1] = list.get(1).toString();
        value[2] = getStream(list.get(2), base64);
        value[3] = header ? getHeader(list.get(3)) : null;
        return value;
    }

    @Override
    public String action(String action) throws Exception {
        return str(app.callAttr("action", obj, action));
    }

    @Override
    public void destroy() {
        try {
            app.callAttr("destroy", obj);
        } catch (Exception ignored) {
        }
    }

    private String str(PyObject value) {
        return value == null ? "" : value.toString();
    }

    private boolean bool(PyObject value) {
        return value != null && value.toBoolean();
    }

    private Map<String, String> getHeader(PyObject obj) {
        try {
            Map<String, String> header = new HashMap<>();
            for (Map.Entry<PyObject, PyObject> entry : obj.asMap().entrySet()) header.put(entry.getKey().toString(), entry.getValue().toString());
            return header;
        } catch (Exception e) {
            return null;
        }
    }

    private ByteArrayInputStream getStream(PyObject o, boolean base64) {
        if (o == null) return null;
        if (o.type().toString().contains("bytes")) return new ByteArrayInputStream(o.toJava(byte[].class));
        String content = o.toString();
        if (base64 && content.contains("base64,")) content = content.split("base64,")[1];
        return new ByteArrayInputStream(base64 ? Util.decode(content) : content.getBytes());
    }

    private void download(String name) {
        try {
            String path = Path.py(name).getAbsolutePath();
            String url = UriUtil.resolve(api, name);
            app.callAttr("download", path, url);
        } catch (Exception e) {
            SpiderDebug.log("py-spider", e);
        }
    }
}
