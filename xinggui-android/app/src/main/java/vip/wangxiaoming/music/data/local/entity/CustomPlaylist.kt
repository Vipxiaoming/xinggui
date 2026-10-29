package vip.wangxiaoming.music.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** 自建歌单。原版只存链接 + 远端数量，这里保留同语义。 */
@Entity(
    tableName = "custom_playlists",
    indices = [Index(value = ["create_time"])],
)
data class CustomPlaylist(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val cover: String? = null,
    @ColumnInfo(name = "link_url") val linkUrl: String? = null,
    val source: String? = null,
    @ColumnInfo(name = "create_time") val createTime: Long,
    @ColumnInfo(name = "remote_count") val remoteCount: Int = 0,
)