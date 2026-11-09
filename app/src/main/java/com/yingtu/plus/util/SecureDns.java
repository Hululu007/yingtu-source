package com.yingtu.plus.util;

import com.github.catvod.bean.Doh;
import com.github.catvod.net.OkHttp;
import com.orhanobut.hawk.Hawk;

/**
 * 安全DNS：把设置里的选择应用到 catvod OkHttp 的 OkDns（DoH 核心本来就在树里，
 * VodConfig 只备了 Doh 列表、从没真正往 OkDns 里设过，本类是第一个真正应用它的人）。
 * 0=关闭（DoH 置空，走系统 DNS）；其余走对应 DoH 地址。腾讯可选1/2 共用 doh.pub，
 * 区别只在 bootstrap IP（119.29.29.29 / 182.254.116.116），用 Doh.objectFrom 带 ips。
 */
public final class SecureDns {

    public static final String[] NAMES = {
            "关闭", "腾讯", "阿里", "360", "Google", "AdGuard",
            "Quad9", "cloudflare", "opendns", "腾讯可选1", "腾讯可选2"
    };

    private static final String[] URLS = {
            "", "https://doh.pub/dns-query", "https://dns.alidns.com/dns-query",
            "https://doh.360.cn/dns-query", "https://dns.google/dns-query",
            "https://dns.adguard-dns.com/dns-query", "https://dns.quad9.net/dns-query",
            "https://cloudflare-dns.com/dns-query", "https://doh.opendns.com/dns-query",
            "https://doh.pub/dns-query", "https://doh.pub/dns-query"
    };

    private SecureDns() {}

    public static int get() {
        int v = Hawk.get(HawkConfig.SECURE_DNS, 0);
        return v >= 0 && v < NAMES.length ? v : 0;
    }

    public static String nameOf(int v) {
        return NAMES[v >= 0 && v < NAMES.length ? v : 0];
    }

    /** 按当前存值应用一次（App 启动时调）。 */
    public static void apply() {
        apply(get());
    }

    /** 应用指定档位：选了就设 DoH，关闭就把 DoH 置空回系统 DNS。 */
    public static void apply(int index) {
        try {
            if (index <= 0 || index >= URLS.length) {
                OkHttp.dns().setDoh(new Doh().name("").url(""));
                return;
            }
            Doh doh;
            if (index == 9) {
                doh = Doh.objectFrom("{\"name\":\"腾讯可选1\",\"url\":\"https://doh.pub/dns-query\",\"ips\":[\"119.29.29.29\"]}");
            } else if (index == 10) {
                doh = Doh.objectFrom("{\"name\":\"腾讯可选2\",\"url\":\"https://doh.pub/dns-query\",\"ips\":[\"182.254.116.116\"]}");
            } else {
                doh = new Doh().name(NAMES[index]).url(URLS[index]);
            }
            OkHttp.dns().setDoh(doh);
        } catch (Throwable e) {
            e.printStackTrace();
        }
    }
}
