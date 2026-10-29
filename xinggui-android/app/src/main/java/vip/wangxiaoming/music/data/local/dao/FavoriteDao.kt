package vip.wangxiaoming.music.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import vip.wangxiaoming.music.data.local.entity.Favorite

/**
 * 收藏表 DAO。
 *
 * 全部按 suspend 暴露（除 Flow），UI 侧统一在 IO 线程调用；
 * 不用 @Transaction 组合方法，因为「切换收藏」在 Repository 里用
 * 「先查后写」两步实现，语义更清晰且便于单元测试。
 */
@Dao
abstract class FavoriteDao {

    @Query("SELECT * FROM favorites ORDER BY added_at DESC")
    abstract fun observeAll(): Flow<List<Favorite>>

    @Query("SELECT * FROM favorites ORDER BY added_at DESC")
    abstract suspend fun queryAll(): List<Favorite>

    @Query("SELECT COUNT(*) FROM favorites")
    abstract fun observeCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM favorites")
    abstract suspend fun count(): Int

    @Query("SELECT * FROM favorites WHERE song_id = :songId LIMIT 1")
    abstract suspend fun queryBySongId(songId: String): Favorite?

    @Query("SELECT EXISTS(SELECT 1 FROM favorites WHERE song_id = :songId)")
    abstract suspend fun exists(songId: String): Boolean

    @Query("SELECT EXISTS(SELECT 1 FROM favorites WHERE song_id = :songId)")
    abstract fun observeExists(songId: String): Flow<Boolean>

    /** 重复收藏同一首：保留原 id，只更新展示字段并刷新时间。 */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun upsert(item: Favorite): Long

    @Query("DELETE FROM favorites WHERE song_id = :songId")
    abstract suspend fun deleteBySongId(songId: String): Int

    @Query("DELETE FROM favorites")
    abstract suspend fun clear(): Int
}