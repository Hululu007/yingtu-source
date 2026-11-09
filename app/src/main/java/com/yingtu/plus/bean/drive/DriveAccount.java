package com.yingtu.plus.bean.drive;

import android.text.TextUtils;

/**
 * 网盘账号：Alist / SMB / WebDAV 三种。
 * 存 Prefers（key 见 DriveStore），密码与 token 只存本地，不上传、不写死地址。
 */
public class DriveAccount {

    public static final String TYPE_ALIST = "alist";
    public static final String TYPE_SMB = "smb";
    public static final String TYPE_WEBDAV = "webdav";

    public String type;
    public String name;
    public String address;
    public String username;
    public String password;
    public String token;
    public String share;

    public DriveAccount() {
    }

    public DriveAccount(String type, String name, String address, String username, String password, String token, String share) {
        this.type = type;
        this.name = name;
        this.address = address;
        this.username = username;
        this.password = password;
        this.token = token;
        this.share = share;
    }

    public String getType() {
        return TextUtils.isEmpty(type) ? TYPE_ALIST : type.trim().toLowerCase();
    }

    public String getName() {
        return TextUtils.isEmpty(name) ? typeName() : name.trim();
    }

    public String getAddress() {
        return TextUtils.isEmpty(address) ? "" : address.trim();
    }

    /** 去掉末尾斜杠，拼接口路径时用。 */
    public String getBaseUrl() {
        String url = getAddress();
        while (url.endsWith("/")) url = url.substring(0, url.length() - 1);
        if (!url.startsWith("http://") && !url.startsWith("https://") && !url.startsWith("smb://")) {
            url = "http://" + url;
        }
        return url;
    }

    /** SMB 地址里只取主机名（去协议头、去路径、去端口后的部分不含端口）。 */
    public String getSmbHost() {
        String url = getAddress().replace("smb://", "").replace("SMB://", "");
        int slash = url.indexOf('/');
        if (slash >= 0) url = url.substring(0, slash);
        int colon = url.indexOf(':');
        if (colon >= 0) url = url.substring(0, colon);
        return url.trim();
    }

    public int getSmbPort() {
        String url = getAddress().replace("smb://", "").replace("SMB://", "");
        int slash = url.indexOf('/');
        if (slash >= 0) url = url.substring(0, slash);
        int colon = url.indexOf(':');
        if (colon >= 0) {
            try {
                return Integer.parseInt(url.substring(colon + 1));
            } catch (NumberFormatException ignored) {
            }
        }
        return 445;
    }

    public static String typeName(String type) {
        if (TYPE_SMB.equals(type)) return "SMB";
        if (TYPE_WEBDAV.equals(type)) return "WebDAV";
        return "Alist";
    }

    public String typeName() {
        return typeName(getType());
    }
}
