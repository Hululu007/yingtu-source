package com.yingtu.plus.update;

import android.text.TextUtils;

/** OCI 制品描述（Update.oci 字段）：更新检查用到的仓库/标签/摘要。 */
public class OciArtifact {

    public String registry;
    public String repository;
    public String tag;
    public String digest;
    public String mediaType;
    public long size;

    public boolean isValid() {
        return !TextUtils.isEmpty(digest) || !TextUtils.isEmpty(tag);
    }
}
