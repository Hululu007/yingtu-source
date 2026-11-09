package com.yingtu.plus.util;

import java.util.Arrays;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import android.os.Handler;
import android.os.Looper;

import com.github.catvod.net.OkHttp;
import com.github.catvod.net.RetryInterceptor;

import okhttp3.ConnectionPool;
import okhttp3.Dispatcher;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;

/**
 * 全局线程池 + OkHttp 单例 + 主线程切换工具。
 *
 * Slow-network tuning: explicit shared connection pool (8 idle / 5 min
 * keep-alive) and dispatcher caps (32 total / 4 per host) so live lists,
 * drive and the web proxy reuse handshakes instead of re-handshaking per
 * request and cannot burst a slow link. HTTP/2 is pinned (multiplexed).
 * DNS goes through catvod's OkDns singleton, so SecureDns (DoH) applies
 * to this chain too. Read timeout is idle time between bytes, not total
 * time, so 45s tolerates a slow trickle without hanging forever.
 */
public class OkGo {

    private static final ConnectionPool POOL = new ConnectionPool(8, 5, TimeUnit.MINUTES);
    private static final Dispatcher DISPATCHER = newDispatcher();
    private static final OkHttpClient CLIENT = newClient(true);
    private static final OkHttpClient PLAIN = newClient(false);
    private static final Executor IO = newFixedIoPool();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private static Dispatcher newDispatcher() {
        Dispatcher dispatcher = new Dispatcher();
        dispatcher.setMaxRequests(32);
        dispatcher.setMaxRequestsPerHost(4);
        return dispatcher;
    }

    private static Executor newFixedIoPool() {
        // Was newCachedThreadPool (unbounded): every slow request could spawn
        // a thread and burst the link. 8 daemon threads queue instead; callers
        // are background jobs (detail/source/drive/web server), never UI.
        AtomicInteger number = new AtomicInteger();
        ThreadFactory factory = runnable -> {
            Thread thread = new Thread(runnable, "okgo-io-" + number.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
        return Executors.newFixedThreadPool(8, factory);
    }

    private static OkHttpClient.Builder baseBuilder() {
        return new OkHttpClient.Builder()
                .connectionPool(POOL)
                .dispatcher(DISPATCHER)
                .protocols(Arrays.asList(Protocol.HTTP_2, Protocol.HTTP_1_1))
                .dns(OkHttp.dns())
                .connectTimeout(20, TimeUnit.SECONDS)
                .readTimeout(45, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true);
    }

    private static OkHttpClient newClient(boolean retry) {
        OkHttpClient.Builder builder = baseBuilder();
        if (retry) builder.addInterceptor(new RetryInterceptor());
        return builder.build();
    }

    public static OkHttpClient client() {
        return CLIENT;
    }

    /**
     * Same pool/dispatcher/DNS as client(), but without the retry interceptor.
     * For diagnostics only (SiteHealthChecker): a retried probe would report
     * inflated latency and mask the 403/503 the probe is trying to classify.
     */
    public static OkHttpClient clientPlain() {
        return PLAIN;
    }

    public static void io(Runnable r) {
        IO.execute(r);
    }

    public static void main(Runnable r) {
        MAIN.post(r);
    }
}
