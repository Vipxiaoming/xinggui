package vip.wangxiaoming.music.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 歌单内歌曲。相对原版的两处修正：
 *  1. 外键 -> custom_playlists(id) ON DELETE CASCADE，删歌单不再留孤儿行；
 *  2. (playlist_id, song_id) 复合唯一，重复加入同一首只保留一条。
 */
@Entity(
    tableName = "playlist_songs",
    foreignKeys = [
        ForeignKey(
            entity = CustomPlaylist::class,
            parentColumns = ["id"],
            childColumns = ["playlist_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["playlist_id", "song_id"], unique = true),
        Index(value = ["playlist_id"]),
    ],
)
data class PlaylistSong(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "playlist_id") val playlistId: Long,
    @ColumnInfo(name = "song_id") val songId: String,
    val name: String,
    val artist: String,
    val album: String? = null,
    val cover: String? = null,
    val source: String,
    val duration: String = "0",
    @ColumnInfo(name = "play_url") val playUrl: String? = null,
    @ColumnInfo(name = "local_file_path") val localFilePath: String? = null,
    @ColumnInfo(name = "added_time") val addedTime: Long,
)