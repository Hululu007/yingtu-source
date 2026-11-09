package com.github.catvod.net;

import androidx.annotation.NonNull;

import com.github.catvod.crawler.SpiderDebug;

import java.io.IOException;

import okhttp3.Interceptor;
import okhttp3.Request;
import okhttp3.Response;

/**
 * Slow-network retry with exponential backoff.
 *
 * Only GET/HEAD are retried (idempotent, safe to re-issue). Retried on
 * transport failure (connect/read timeout included) and on retryable
 * statuses 429/502/503/504. At most 2 retries (3 attempts total), backoff
 * 1s then 2s, honouring Retry-After up to 5s. Never retries POST, so a
 * request that may already have reached the server is never duplicated.
 * OkHttp's own retryOnConnectionFailure still covers pre-send route
 * failures; this interceptor adds the delay that the built-in retry lacks.
 */
public final class RetryInterceptor implements Interceptor {

    private static final int MAX_RETRIES = 2;
    private static final long BASE_DELAY_MS = 1000;
    private static final long MAX_DELAY_MS = 5000;

    @NonNull
    @Override
    public Response intercept(@NonNull Chain chain) throws IOException {
        Request request = chain.request();
        if (!isIdempotent(request)) return chain.proceed(request);
        IOException failure = null;
        for (int attempt = 0; attempt <= MAX_RETRIES; attempt++) {
            try {
                Response response = chain.proceed(request);
                if (!isRetryable(response) || attempt == MAX_RETRIES) return response;
                long delay = delayFor(response, attempt);
                int code = response.code();
                response.close();
                SpiderDebug.log("http-retry", "retry code=%d url=%s attempt=%d delayMs=%d", code, request.url(), attempt + 1, delay);
                sleep(delay);
            } catch (IOException e) {
                failure = e;
                if (attempt == MAX_RETRIES) throw e;
                long delay = delayFor(attempt);
                SpiderDebug.log("http-retry", "retry error=%s url=%s attempt=%d delayMs=%d", e.getMessage(), request.url(), attempt + 1, delay);
                sleep(delay);
            }
        }
        throw failure;
    }

    private static boolean isIdempotent(Request request) {
        String method = request.method();
        return "GET".equals(method) || "HEAD".equals(method);
    }

    private static boolean isRetryable(Response response) {
        int code = response.code();
        return code == 429 || code == 502 || code == 503 || code == 504;
    }

    private static long delayFor(int attempt) {
        return Math.min(BASE_DELAY_MS * (1L << attempt), MAX_DELAY_MS);
    }

    private static long delayFor(Response response, int attempt) {
        long delay = delayFor(attempt);
        String retryAfter = response.header("Retry-After");
        if (retryAfter != null) {
            try {
                delay = Math.max(delay, Long.parseLong(retryAfter.trim()) * 1000L);
            } catch (NumberFormatException ignored) {
            }
        }
        return Math.min(delay, MAX_DELAY_MS);
    }

    private static void sleep(long delayMs) throws IOException {
        try {
            Thread.sleep(delayMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted during retry backoff", e);
        }
    }
}
