package com.yingtu.plus.bean;

import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.Ignore;
import androidx.room.PrimaryKey;

import com.yingtu.plus.App;
import com.yingtu.plus.api.config.VodConfig;
import com.yingtu.plus.db.AppDatabase;

import java.io.Serializable;
import java.util.List;

/**
 * 播放/观看历史记录。本地 Hawk 持久化，用于「观看历史」页与续播。
 * 同时是 Room 实体（AppDatabase 登记）：表列与 Migrations 31_32 / 35_36
 * 建表语句一致（key、vodPic、vodName、vodFlag、vodRemarks、episodeUrl、
 * revSort、revPlay、createTime、opening、ending、position、duration、
 * speed、scale、cid、wallPic）；Hawk 时代的字段（sourceKey、vodId、
 * playFlag、playIndex、episodeName、updateTime）不在表里，用 @Ignore
 * 保留，供 HistoryHelper / DetailActivity / HistoryAdapter 直接读字段。
 */
@Entity
public class History implements Serializable {

    /** 唯一键：Room 键为 sourceKey + "@@@" + vodId + "@@@" + cid；Hawk 键见 buildKey */
    @NonNull
    @PrimaryKey
    public String key;
    @Ignore
    public String sourceKey;
    @Ignore
    public String vodId;
    public String vodName;
    public String vodPic;

    /** 当前线路下标 / 剧集下标（仅 Hawk / DetailActivity 用，不进表） */
    @Ignore
    public int playFlag;
    @Ignore
    public int playIndex;
    /** 当前集名，用于历史列表角标显示（如「观看至 第3集」）（不进表） */
    @Ignore
    public String episodeName;

    /** 播放进度与时长（毫秒），用于续播 */
    public long position;
    public long duration;

    /** 当前线路名 / 当前集名 / 当前集地址 */
    public String vodFlag;
    public String vodRemarks;
    public String episodeUrl;
    /** 详情页壁纸图 */
    public String wallPic;

    /** 是否倒序排集 / 是否倒序播放 */
    public boolean revSort;
    public boolean revPlay;

    /** 首次观看时间戳（毫秒） */
    public long createTime;
    /** 片头跳过点 / 片尾跳过点（毫秒） */
    public long opening;
    public long ending;
    /** 倍速 */
    public float speed;
    /** 画面比例 */
    public int scale;
    /** 配置 id（多配置隔离） */
    public int cid;

    /** 最近一次观看时间戳（仅 Hawk 用，不进表） */
    @Ignore
    public long updateTime;

    public History() {}

    public static String buildKey(String sourceKey, String vodId) {
        return sourceKey + "@@" + vodId;
    }

    public static History find(String key) {
        return find(VodConfig.getCid(), key);
    }

    public static History find(int cid, String key) {
        return AppDatabase.get().getHistoryDao().find(cid, key);
    }

    public static void delete(int cid) {
        AppDatabase.get().getHistoryDao().delete(cid);
    }

    public static void delete(String key) {
        AppDatabase.get().getHistoryDao().delete(VodConfig.getCid(), key);
    }

    /**
     * 是否可存播放历史：与 PlaybackActivity.canSavePlaybackHistory 同一口径，
     * 有唯一键即可存；光盘导航那一半判断在调用方（History 不知道播放器状态）。
     */
    public boolean canSave() {
        return !TextUtils.isEmpty(key);
    }

    /**
     * 是否可同步播放历史：与 canSave 同一口径；无痕模式的拦截在调用方的
     * syncHistory() 里（Setting.isIncognito()），不在这里重复判。
     */
    public boolean canSync() {
        return canSave();
    }

    /** 已观看百分比（0-100），无时长时返回 0 */
    public int percent() {
        if (duration <= 0) return 0;
        long p = Math.min(position, duration);
        return (int) (p * 100 / duration);
    }

    @NonNull
    public String getKey() {
        return key == null ? "" : key;
    }

    public void setKey(@NonNull String key) {
        this.key = key;
    }

    public String getSourceKey() {
        return sourceKey;
    }

    public void setSourceKey(String sourceKey) {
        this.sourceKey = sourceKey;
    }

    public String getVodId() {
        if (key == null) return "";
        String[] parts = key.split(AppDatabase.SYMBOL);
        if (parts.length > 1) return parts[1];
        String[] legacy = key.split("@@");
        return legacy.length > 1 ? legacy[1] : "";
    }

    public void setVodId(String vodId) {
        this.vodId = vodId;
    }

    /** 站点键：取 key 的第一段；Hawk 旧键（@@ 分隔）也兼容 */
    public String getSiteKey() {
        if (key == null) return "";
        String[] parts = key.split(AppDatabase.SYMBOL);
        if (parts.length > 1) return parts[0];
        String[] legacy = key.split("@@");
        return legacy.length > 0 ? legacy[0] : "";
    }

    public String getVodName() {
        return vodName == null ? "" : vodName;
    }

    public void setVodName(String vodName) {
        this.vodName = vodName;
    }

    public String getVodPic() {
        return vodPic;
    }

    public void setVodPic(String vodPic) {
        this.vodPic = vodPic;
    }

    public String getWallPic() {
        return wallPic == null ? "" : wallPic;
    }

    public void setWallPic(String wallPic) {
        this.wallPic = wallPic;
    }

    public int getPlayFlag() {
        return playFlag;
    }

    public void setPlayFlag(int playFlag) {
        this.playFlag = playFlag;
    }

    public int getPlayIndex() {
        return playIndex;
    }

    public void setPlayIndex(int playIndex) {
        this.playIndex = playIndex;
    }

    public String getEpisodeName() {
        return episodeName;
    }

    public void setEpisodeName(String episodeName) {
        this.episodeName = episodeName;
    }

    public long getPosition() {
        return position;
    }

    public void setPosition(long position) {
        this.position = position;
    }

    public long getDuration() {
        return duration;
    }

    public void setDuration(long duration) {
        this.duration = duration;
    }

    public String getVodFlag() {
        return vodFlag == null ? "" : vodFlag;
    }

    public void setVodFlag(String vodFlag) {
        this.vodFlag = vodFlag;
    }

    public String getVodRemarks() {
        return vodRemarks == null ? "" : vodRemarks;
    }

    public void setVodRemarks(String vodRemarks) {
        this.vodRemarks = vodRemarks;
    }

    public String getEpisodeUrl() {
        return episodeUrl;
    }

    public void setEpisodeUrl(String episodeUrl) {
        this.episodeUrl = episodeUrl;
    }

    /** 当前集：由 vodRemarks（集名）+ episodeUrl 拼出 */
    public Episode getEpisode() {
        return Episode.create(getVodRemarks(), getEpisodeUrl());
    }

    /** 当前线路：由 vodFlag（线路名）+ episodeUrl 拼出（单集线路） */
    public Flag getFlag() {
        return Flag.create(getVodFlag(), getEpisodeUrl());
    }

    public boolean isRevSort() {
        return revSort;
    }

    public void setRevSort(boolean revSort) {
        this.revSort = revSort;
    }

    public boolean isRevPlay() {
        return revPlay;
    }

    public void setRevPlay(boolean revPlay) {
        this.revPlay = revPlay;
    }

    public long getCreateTime() {
        return createTime;
    }

    public void setCreateTime(long createTime) {
        this.createTime = createTime;
    }

    public long getOpening() {
        return opening;
    }

    public void setOpening(long opening) {
        this.opening = opening;
    }

    public long getEnding() {
        return ending;
    }

    public void setEnding(long ending) {
        this.ending = ending;
    }

    public float getSpeed() {
        return speed;
    }

    public void setSpeed(float speed) {
        this.speed = speed;
    }

    public int getScale() {
        return scale;
    }

    public void setScale(int scale) {
        this.scale = scale;
    }

    public int getCid() {
        return cid;
    }

    public void setCid(int cid) {
        this.cid = cid;
    }

    public long getUpdateTime() {
        return updateTime;
    }

    public void setUpdateTime(long updateTime) {
        this.updateTime = updateTime;
    }

    /**
     * 按当前 vodRemarks / episodeUrl 在线路列表里定位剧集，命中后把线路名、
     * 集名、集地址回填到本记录；新建记录时 vodRemarks 为空，取首条线路首集。
     */
    public void findEpisode(List<Flag> flags) {
        if (flags == null) return;
        for (Flag flag : flags) {
            Episode episode = flag.find(getEpisode(), false);
            if (episode != null) {
                setVodFlag(flag.getFlag());
                setVodRemarks(episode.getName());
                setEpisodeUrl(episode.getUrl());
                break;
            }
        }
    }

    /** 逐字段深拷贝（供 saveHistory / syncHistory 快照后丢后台线程写盘） */
    public History copy() {
        History history = new History();
        history.key = key;
        history.sourceKey = sourceKey;
        history.vodId = vodId;
        history.vodName = vodName;
        history.vodPic = vodPic;
        history.playFlag = playFlag;
        history.playIndex = playIndex;
        history.episodeName = episodeName;
        history.position = position;
        history.duration = duration;
        history.vodFlag = vodFlag;
        history.vodRemarks = vodRemarks;
        history.episodeUrl = episodeUrl;
        history.wallPic = wallPic;
        history.revSort = revSort;
        history.revPlay = revPlay;
        history.createTime = createTime;
        history.opening = opening;
        history.ending = ending;
        history.speed = speed;
        history.scale = scale;
        history.cid = cid;
        history.updateTime = updateTime;
        return history;
    }

    /**
     * 与库里同 key 记录合并：库里那条时长更长（记录更完整）时，沿用库里的
     * 进度与时长，否则保留当前值；返回 this 供链式 save()。
     */
    public History merge() {
        History item = find(getCid(), getKey());
        if (item != null && item.getDuration() > getDuration()) {
            setPosition(item.getPosition());
            setDuration(item.getDuration());
        }
        return this;
    }

    public void save() {
        AppDatabase.get().getHistoryDao().insertOrUpdate(this);
    }

    public void save(int cid) {
        setCid(cid);
        save();
    }

    public History delete() {
        AppDatabase.get().getHistoryDao().delete(getCid(), getKey());
        return this;
    }

    /** 换键：删旧 key 的行、改新 key、写盘（详情重取到真实 vodId 时用） */
    public History replace(String key) {
        delete();
        setKey(key);
        save();
        return this;
    }

    /** 是否接近播完：有总时长且剩余不足 5 秒（与 VideoActivity 播完判定同口径） */
    public boolean isNearEnding() {
        return duration > 0 && duration - position <= 5000;
    }

    /** 播完后进度归零，下次从头播 */
    public void resetPlaybackPosition() {
        setPosition(0);
    }
}
