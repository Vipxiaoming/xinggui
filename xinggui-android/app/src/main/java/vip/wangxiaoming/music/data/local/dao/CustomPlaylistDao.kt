package vip.wangxiaoming.music.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow
import vip.wangxiaoming.music.data.local.entity.CustomPlaylist

@Dao
abstract class CustomPlaylistDao {

    @Query("SELECT * FROM custom_playlists ORDER BY create_time DESC")
    abstract fun observeAll(): Flow<List<CustomPlaylist>>

    @Query("SELECT * FROM custom_playlists ORDER BY create_time DESC")
    abstract suspend fun queryAll(): List<CustomPlaylist>

    @Query("SELECT * FROM custom_playlists WHERE id = :id")
    abstract suspend fun queryById(id: Long): CustomPlaylist?

    @Query("SELECT * FROM custom_playlists WHERE id = :id")
    abstract fun observeById(id: Long): Flow<CustomPlaylist?>

    @Query("SELECT COUNT(*) FROM custom_playlists")
    abstract suspend fun count(): Int

    @Insert(onConflict = OnConflictStrategy.ABORT)
    abstract suspend fun insert(item: CustomPlaylist): Long

    @Insert(onConflict = OnConflictStrategy.ABORT)
    abstract suspend fun insertAll(items: List<CustomPlaylist>): List<Long>

    @Update
    abstract suspend fun update(item: CustomPlaylist): Int

    /** 删除歌单；playlist_songs 由外键 CASCADE 一并清掉。 */
    @Delete
    abstract suspend fun delete(item: CustomPlaylist): Int

    @Query("DELETE FROM custom_playlists WHERE id = :id")
    abstract suspend fun deleteById(id: Long): Int

    @Query("DELETE FROM custom_playlists")
    abstract suspend fun clear(): Int

    @Query("UPDATE custom_playlists SET name = :name WHERE id = :id")
    abstract suspend fun rename(id: Long, name: String): Int

    @Query("UPDATE custom_playlists SET remote_count = :count WHERE id = :id")
    abstract suspend fun updateRemoteCount(id: Long, count: Int): Int
}