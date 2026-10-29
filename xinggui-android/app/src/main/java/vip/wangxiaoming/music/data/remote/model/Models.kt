package vip.wangxiaoming.music.data.remote.model

import kotlinx.serialization.Serializable

/**
 * 统一模型层 —— 字段与原版 com.aurora.music.model 对齐，便于后续 UI / 播放层直接复用。
 *
 * 原版 Song 只有 String 字段（含 duration 的 "mm:ss" 文本），这里保留同名字段，
 * 另外补 durationSeconds（毫秒精度用于进度校验）与 albumId/artistId（后续歌手页/专辑页要用）。
 */
@Serializable
data class Song(
    val id: String = "",
    val name: String = "",
    val artist: String = "",
    val album: String = "",
    val cover: String = "",
    val source: String = "",
    /** "mm:ss"，与原版一致；解析失败时为 "03:30" 兜底 */
    val duration: String = "03:30",
    val durationSeconds: Int = 0,
    val albumId: String = "",
    val artistId: String = "",
    var playUrl: String? = null,
    var localFilePath: String? = null,
    var isDownloaded: Boolean = false,
    var isLiked: Boolean = false,
) {
    /** 去重键：同名 + 同歌手（忽略大小写、空格与括号后缀），跨平台合并用 */
    val dedupeKey: String
        get() = (normalizeForMatch(name) + "|" + normalizeForMatch(artist))
}

@Serializable
data class Playlist(
    val id: String = "",
    val name: String = "",
    val coverUrl: String = "",
    val description: String = "",
    val playCount: Long = 0L,
    val trackCount: Int = 0,
    val source: String = "wy",
    val linkUrl: String = "",
)

@Serializable
data class Artist(
    val id: String = "",
    val name: String = "",
    val picUrl: String = "",
    val musicSize: Int = 0,
    val albumSize: Int = 0,
)

@Serializable
data class RankInfo(
    val id: String = "",
    val source: String = "wy",
    val title: String = "",
    val updateFrequency: String = "",
    val coverUrl: String = "",
    val description: String = "",
)

@Serializable
data class LyricLine(
    val timeMs: Long,
    val text: String,
) : Comparable<LyricLine> {
    override fun compareTo(other: LyricLine): Int = timeMs.compareTo(other.timeMs)
}

/**
 * 单平台调用结果。
 *
 * platform 是**平台代码**（wy / kw / kg / tx），用于聚合层区分四家；
 * operation 是**操作名**（search / lyric / toplist ...），用于诊断与测试定位是哪一步失败。
 * 早期版本把操作名塞进了 platform，导致聚合摘要四家全显示 "search"，已修正。
 */
data class ClientResult<T>(
    val platform: String,
    val operation: String,
    val ok: Boolean,
    val status: Int,
    val data: T,
    val rawLength: Int = 0,
    val error: String? = null,
) {
    val count: Int
        get() = when (data) {
            is Collection<*> -> data.size
            else -> if (ok && data != null) 1 else 0
        }
}

/** 四家平台标识，取值与原版 Song.source 完全一致：wy / kw / kg / tx */
enum class Platform(val code: String, val display: String) {
    NETEASE("wy", "网易云"),
    KUWO("kw", "酷我"),
    KUGOU("kg", "酷狗"),
    QQ("tx", "QQ音乐");

    companion object {
        fun of(code: String?): Platform? = entries.firstOrNull { it.code.equals(code, ignoreCase = true) }
    }
}

/** 文本归一化：用于跨平台去重与容错比对 */
internal fun normalizeForMatch(raw: String?): String {
    if (raw.isNullOrBlank()) return ""
    return raw.lowercase()
        .replace(Regex("[\\(（\\[【][^\\)）\\]】]*[\\)）\\]】]"), "")
        .replace(Regex("[\\s\u3000·・\\-—_/\\\\|,，.。'\"“”‘’!！?？]+"), "")
        .trim()
}