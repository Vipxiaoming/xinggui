package vip.wangxiaoming.music.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 收藏（P6 遗留功能补齐）。
 *
 * 为什么单独建表而不是复用 playback_history：
 *  - 历史表按 song_id 唯一但会被「再次播放」不断重写，收藏需要稳定的 added_at 排序；
 *  - 历史表里存了 play_url / local_file_path 这类易失字段，收藏只关心展示所需的元数据；
 *  - 收藏与历史是两个独立集合，混表后「清空历史」会误删收藏。
 *
 * song_id 全局唯一（格式与 MediaItem.mediaId 一致，即 "平台:id"），
 * 因此同一首歌跨平台收藏不会重复；重复收藏走 REPLACE 只刷新 added_at 之外的内容。
 */
@Entity(
    tableName = "favorites",
    indices = [
        Index(value = ["song_id"], unique = true),
        Index(value = ["added_at"]),
    ],
)
data class Favorite(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "song_id") val songId: String,
    val name: String,
    val artist: String,
    val album: String? = null,
    val cover: String? = null,
    val source: String,
    val duration: String = "0",
    @ColumnInfo(name = "added_at") val addedAt: Long,
)