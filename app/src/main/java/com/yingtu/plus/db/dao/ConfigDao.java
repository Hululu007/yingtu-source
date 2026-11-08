package com.yingtu.plus.db.dao;

import androidx.room.Dao;
import androidx.room.Query;

import com.yingtu.plus.bean.Config;

import java.util.List;

@Dao
public abstract class ConfigDao extends BaseDao<Config> {

    @Query("SELECT * FROM Config")
    public abstract List<Config> findAll();

    @Query("SELECT * FROM Config WHERE type = :type ORDER BY time DESC")
    public abstract List<Config> findByType(int type);

    // 2026-11-08 云构建第一轮失败修法：Room 2.6.1 的 RoomWarnings 里没有 QUERY_MISMATCH 常量，
    // 改用字符串字面量（@SuppressWarnings 本来就接受字符串），效果相同。
    @SuppressWarnings("QUERY_MISMATCH")
    @Query("SELECT id, name, url, type, time FROM Config WHERE type = :type ORDER BY time DESC")
    public abstract List<Config> findUrlByType(int type);

    @Query("SELECT * FROM Config WHERE id = :id")
    public abstract Config findById(int id);

    @Query("SELECT * FROM Config WHERE type = :type ORDER BY time DESC LIMIT 1")
    public abstract Config findOne(int type);

    @Query("SELECT * FROM Config WHERE url = :url AND type = :type")
    public abstract Config find(String url, int type);

    @Query("DELETE FROM Config WHERE url = :url AND type = :type")
    public abstract void delete(String url, int type);

    @Query("DELETE FROM Config WHERE url = :url")
    public abstract void delete(String url);

    @Query("DELETE FROM Config")
    public abstract void delete();
}
