package vip.wangxiaoming.music.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import vip.wangxiaoming.music.data.local.entity.PlaylistSong

@Dao
abstract class PlaylistSongDao {

    @Query("SELECT * FROM playlist_songs WHERE playlist_id = :playlistId ORDER BY added_time ASC")
    abstract fun observeByPlaylistId(playlistId: Long): Flow<List<PlaylistSong>>

    @Query("SELECT * FROM playlist_songs WHERE playlist_id = :playlistId ORDER BY added_time ASC")
    abstract suspend fun queryByPlaylistId(playlistId: Long): List<PlaylistSong>

    @Query("SELECT COUNT(*) FROM playlist_songs WHERE playlist_id = :playlistId")
    abstract fun observeCountByPlaylistId(playlistId: Long): Flow<Int>

    @Query("SELECT COUNT(*) FROM playlist_songs WHERE playlist_id = :playlistId")
    abstract suspend fun countByPlaylistId(playlistId: Long): Int

    @Query("SELECT * FROM playlist_songs WHERE playlist_id = :playlistId AND song_id = :songId LIMIT 1")
    abstract suspend fun queryOne(playlistId: Long, songId: String): PlaylistSong?

    /** IGNORE：同歌单重复加入同一首歌时静默跳过，不抛唯一约束异常。 */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insert(item: PlaylistSong): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insertAll(items: List<PlaylistSong>): List<Long>

    @Query("DELETE FROM playlist_songs WHERE playlist_id = :playlistId AND song_id = :songId")
    abstract suspend fun delete(playlistId: Long, songId: String): Int

    @Query("DELETE FROM playlist_songs WHERE playlist_id = :playlistId")
    abstract suspend fun deleteByPlaylistId(playlistId: Long): Int

    @Query("DELETE FROM playlist_songs")
    abstract suspend fun clear(): Int
}