package com.yingtu.plus.server;

import android.text.TextUtils;

import com.github.catvod.utils.Util;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * 本地 HTTP 服务（极简版，仅提供地址与启动）。
 *
 * 调用点只用到 start() 与 getAddress() 三种重载：Core 取 /tvbus 前缀、
 * Device 取本机地址、ScanTask 比对本地地址、UrlUtil 拼 proxy/file 地址。
 * 本类沿用 webhtv 的地址规则：无参取局域网 IP，true 取 127.0.0.1，
 * String 参数直接拼在 127.0.0.1 地址后；端口在 9978..9998 间抢占，
 * 并注册给 CatVod Proxy。
 *
 * 注意：本类不实现任何业务路由（/tvbus 等中继未重建），请求一律回 404。
 */
public class Server {

    private static volatile Server instance;

    private ServerSocket serverSocket;
    private int port = -1;
    private volatile boolean running;

    private Server() {}

    public static Server get() {
        if (instance == null) {
            synchronized (Server.class) {
                if (instance == null) instance = new Server();
            }
        }
        return instance;
    }

    public boolean isRunning() {
        return running && port > 0;
    }

    public String getAddress() {
        return getAddress(false);
    }

    public String getAddress(String path) {
        return getAddress(true) + (path == null ? "" : path);
    }

    public String getAddress(boolean local) {
        if (port <= 0) return "";
        return "http://" + (local ? "127.0.0.1" : Util.getIp()) + ":" + port;
    }

    /** 启动服务（幂等）。失败不抛出，port 保持 -1，地址返回空串。 */
    public synchronized void start() {
        if (running) return;
        for (int i = 9978; i < 9999; i++) {
            try {
                serverSocket = new ServerSocket(i, 50, InetAddress.getByName("0.0.0.0"));
                port = i;
                running = true;
                try { com.github.catvod.Proxy.set(i); } catch (Throwable ignored) {}
                Thread accept = new Thread(this::loop, "yingtu-server");
                accept.setDaemon(true);
                accept.start();
                return;
            } catch (Throwable e) {
                serverSocket = null;
                port = -1;
            }
        }
    }

    public synchronized void stop() {
        running = false;
        try {
            if (serverSocket != null) serverSocket.close();
        } catch (IOException ignored) {
        }
        serverSocket = null;
        port = -1;
    }

    private void loop() {
        while (running) {
            try {
                Socket socket = serverSocket.accept();
                handle(socket);
            } catch (Throwable e) {
                if (!running) break;
            }
        }
    }

    private void handle(Socket socket) {
        try {
            OutputStream out = socket.getOutputStream();
            String body = "Not Found";
            out.write(("HTTP/1.1 404 Not Found\r\nContent-Length: " + body.length() + "\r\nConnection: close\r\n\r\n" + body).getBytes(StandardCharsets.UTF_8));
            out.flush();
        } catch (Throwable ignored) {
        } finally {
            try { socket.close(); } catch (IOException ignored) {}
        }
    }
}
