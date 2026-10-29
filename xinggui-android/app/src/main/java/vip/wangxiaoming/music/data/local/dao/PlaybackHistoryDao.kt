package vip.wangxiaoming.music.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow
import vip.wangxiaoming.music.data.local.entity.PlaybackHistory

/**
 * 用 abstract class 而不是 interface：Kotlin 的 interface 默认方法会被编译成
 * DefaultImpls 静态调用，Room 的 @Transaction 生成代理在部分 KSP 版本下识别不到，
 * 表现为运行时 Transaction 不生效。abstract class 没有这个问题。
 */
@Dao
abstract class PlaybackHistoryDao {

    /** 最近播放，按 played_time DESC。Room 的 Flow 会在表变更时自动重发。 */
    @Query("SELECT * FROM playback_history ORDER BY played_time DESC LIMIT :limit")
    abstract fun observeRecent(limit: Int): Flow<List<PlaybackHistory>>

    @Query("SELECT * FROM playback_history ORDER BY played_time DESC")
    abstract fun observeAll(): Flow<List<PlaybackHistory>>

    @Query("SELECT * FROM playback_history ORDER BY played_time DESC LIMIT :limit")
    abstract suspend fun queryRecent(limit: Int): List<PlaybackHistory>

    @Query("SELECT * FROM playback_history ORDER BY played_time DESC")
    abstract suspend fun queryAll(): List<PlaybackHistory>

    @Query("SELECT * FROM playback_history WHERE song_id = :songId LIMIT 1")
    abstract suspend fun queryBySongId(songId: String): PlaybackHistory?

    @Query("SELECT COUNT(*) FROM playback_history")
    abstract suspend fun count(): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insertIgnore(item: PlaybackHistory): Long

    @Update
    abstract suspend fun update(item: PlaybackHistory)

    @Query("DELETE FROM playback_history WHERE song_id = :songId")
    abstract suspend fun deleteBySongId(songId: String): Int

    @Query("DELETE FROM playback_history")
    abstract suspend fun clear(): Int

    /**
     * 记录一次播放。同一首歌只保留一行（song_id 唯一），重复播放刷新元数据与 played_time
     * 而保留原主键，避免历史列表出现重复项、也避免 id 漂移导致远端同步对不上。
     */
    @Transaction
    open suspend fun record(item: PlaybackHistory) {
        val existing = queryBySongId(item.songId)
        if (existing == null) {
            insertIgnore(item)
        } else {
            update(
                item.copy(
                    id = existing.id,
                    // 本地已有下载文件路径时不要被新记录里的 null 覆盖掉
                    localFilePath = item.localFilePath ?: existing.localFilePath,
                )
            )
        }
    }
}