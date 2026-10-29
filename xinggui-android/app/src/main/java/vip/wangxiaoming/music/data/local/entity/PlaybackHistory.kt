package vip.wangxiaoming.music.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 播放历史。songId 唯一：同一首歌只保留一行，重复播放刷新 played_time。
 * 索引：played_time DESC 供「最近播放」列表用。
 */
@Entity(
    tableName = "playback_history",
    indices = [
        Index(value = ["song_id"], unique = true),
        Index(value = ["played_time"]),
    ],
)
data class PlaybackHistory(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "song_id") val songId: String,
    val name: String,
    val artist: String,
    val album: String? = null,
    val cover: String? = null,
    val source: String,
    val duration: String = "0",
    @ColumnInfo(name = "play_url") val playUrl: String? = null,
    @ColumnInfo(name = "local_file_path") val localFilePath: String? = null,
    @ColumnInfo(name = "played_time") val playedTime: Long,
)