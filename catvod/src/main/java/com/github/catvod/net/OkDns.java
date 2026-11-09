package com.github.catvod.net;

import androidx.annotation.NonNull;

import com.github.catvod.bean.Doh;
import com.github.catvod.utils.Util;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import okhttp3.ConnectionPool;
import okhttp3.Dns;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.dnsoverhttps.DnsOverHttps;

public class OkDns implements Dns {

    // DoH queries go through this one shared client instead of a throwaway
    // `new OkHttpClient()` per setDoh() (which had 10s defaults, no pool,
    // and re-handshaked on every SecureDns switch). It deliberately uses
    // system DNS (no .dns() here) so DoH resolution cannot recurse into itself;
    // bootstrap hosts resolve via system DNS. Slow net: 15s connect / 20s read.
    private static final OkHttpClient DOH_CLIENT = new OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .connectionPool(new ConnectionPool(2, 5, TimeUnit.MINUTES))
            .protocols(Arrays.asList(Protocol.HTTP_2, Protocol.HTTP_1_1))
            .retryOnConnectionFailure(true)
            .build();

    private final ConcurrentHashMap<String, String> map;
    private volatile Supplier<Doh> supplier;
    private volatile DnsOverHttps doh;

    public OkDns() {
        this.map = new ConcurrentHashMap<>();
    }

    public synchronized void setDoh(Doh item) {
        HttpUrl url = HttpUrl.parse(item.getUrl());
        this.doh = url == null ? null : new DnsOverHttps.Builder().client(DOH_CLIENT).url(url).bootstrapDnsHosts(item.getHosts()).build();
        this.supplier = null;
    }

    public synchronized void setDoh(Supplier<Doh> supplier) {
        this.supplier = supplier;
    }

    public void clear() {
        map.clear();
    }

    public void addAll(List<String> hosts) {
        map.putAll(hosts.stream().filter(Objects::nonNull).map(host -> host.split("=", 2)).filter(splits -> splits.length == 2).collect(Collectors.toMap(s -> s[0].trim(), s -> s[1].trim(), (oldHost, newHost) -> newHost)));
    }

    private String get(String hostname) {
        String target = map.get(hostname);
        if (target != null) return target;
        for (Map.Entry<String, String> entry : map.entrySet()) if (Util.containOrMatch(hostname, entry.getKey())) return entry.getValue();
        return hostname;
    }

    @NonNull
    @Override
    public List<InetAddress> lookup(@NonNull String hostname) throws UnknownHostException {
        Supplier<Doh> supplier = this.supplier;
        if (supplier != null) initDoh(supplier);
        return (doh != null ? doh : Dns.SYSTEM).lookup(get(hostname));
    }

    private synchronized void initDoh(Supplier<Doh> supplier) {
        if (supplier != this.supplier) return;
        setDoh(supplier.get());
    }
}
