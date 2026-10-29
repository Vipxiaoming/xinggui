package vip.wangxiaoming.music.data.repository

import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import vip.wangxiaoming.music.data.local.AppDatabase
import vip.wangxiaoming.music.data.local.dao.CustomPlaylistDao
import vip.wangxiaoming.music.data.local.dao.PlaylistSongDao
import vip.wangxiaoming.music.data.local.entity.CustomPlaylist
import vip.wangxiaoming.music.data.local.entity.PlaylistSong

/** 自建歌单 + 歌单内歌曲。两者总是一起变化，所以放在同一个 Repository 里。 */
class PlaylistRepository(
    private val db: AppDatabase,
    private val playlistDao: CustomPlaylistDao,
    private val songDao: PlaylistSongDao,
) {

    fun observePlaylists(): Flow<List<CustomPlaylist>> = playlistDao.observeAll()

    fun observePlaylist(id: Long): Flow<CustomPlaylist?> = playlistDao.observeById(id)

    fun observeSongs(playlistId: Long): Flow<List<PlaylistSong>> =
        songDao.observeByPlaylistId(playlistId)

    fun observeSongCount(playlistId: Long): Flow<Int> =
        songDao.observeCountByPlaylistId(playlistId)

    suspend fun playlists(): List<CustomPlaylist> = playlistDao.queryAll()

    suspend fun playlist(id: Long): CustomPlaylist? = playlistDao.queryById(id)

    suspend fun songs(playlistId: Long): List<PlaylistSong> = songDao.queryByPlaylistId(playlistId)

    suspend fun songCount(playlistId: Long): Int = songDao.countByPlaylistId(playlistId)

    /** 建歌单，返回新 id。 */
    suspend fun create(name: String, cover: String? = null, source: String? = null): Long =
        playlistDao.insert(
            CustomPlaylist(
                name = name.trim().ifEmpty { "未命名歌单" },
                cover = cover,
                source = source,
                createTime = System.currentTimeMillis(),
            )
        )

    suspend fun rename(id: Long, name: String): Boolean =
        playlistDao.rename(id, name.trim().ifEmpty { "未命名歌单" }) > 0

    /** 删除歌单，歌曲由 CASCADE 清理。整个过程在事务里，避免半删状态。 */
    suspend fun delete(id: Long) = db.withTransaction { playlistDao.deleteById(id) }

    /** 加歌。已存在时返回 false（IGNORE 语义），不抛异常。 */
    suspend fun addSong(song: PlaylistSong): Boolean = songDao.insert(song) != -1L

    suspend fun addSongs(songs: List<PlaylistSong>): Int =
        songDao.insertAll(songs).count { it != -1L }

    suspend fun removeSong(playlistId: Long, songId: String): Boolean =
        songDao.delete(playlistId, songId) > 0

    suspend fun clearSongs(playlistId: Long) = songDao.deleteByPlaylistId(playlistId)

    /** 歌单页展示用：歌单 + 歌曲数一起取，避免 UI 层再发一次查询。 */
    data class PlaylistWithCount(val playlist: CustomPlaylist, val songCount: Int)

    fun observeWithCounts(): Flow<List<PlaylistWithCount>> =
        playlistDao.observeAll().map { list ->
            list.map { PlaylistWithCount(it, songDao.countByPlaylistId(it.id)) }
        }
}