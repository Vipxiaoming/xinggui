package vip.wangxiaoming.music.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** 下载任务。songId 唯一：同一首歌重复加入只保留最新一条任务。 */
@Entity(
    tableName = "download_tasks",
    indices = [
        Index(value = ["song_id"], unique = true),
        Index(value = ["status"]),
    ],
)
data class DownloadTask(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "song_id") val songId: String,
    val name: String,
    val artist: String,
    val album: String? = null,
    val cover: String? = null,
    @ColumnInfo(name = "local_file_path") val localFilePath: String? = null,
    /** 见 DownloadStatus 常量。 */
    val status: Int,
    @ColumnInfo(name = "error_message") val errorMessage: String? = null,
    @ColumnInfo(name = "create_time") val createTime: Long,
)

object DownloadStatus {
    const val QUEUED = 0
    const val RUNNING = 1
    const val SUCCESS = 2
    const val FAILED = 3
}