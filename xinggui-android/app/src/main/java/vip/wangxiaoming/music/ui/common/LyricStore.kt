package vip.wangxiaoming.music.ui.common

import vip.wangxiaoming.music.data.remote.model.LyricLine

/** 进程内歌词缓存。只缓存「当前一次点击」拉到的歌词，不做持久化。 */
object LyricStore {

    @Volatile
    private var mediaId: String = ""

    @Volatile
    private var lines: List<LyricLine> = emptyList()

    @Volatile
    var status: String = ""
        private set

    fun put(songMediaId: String, raw: String, parsed: List<LyricLine>) {
        mediaId = songMediaId
        lines = parsed
        status = if (parsed.isEmpty()) "该曲目暂无歌词" else ""
    }

    fun clear() {
        mediaId = ""
        lines = emptyList()
        status = ""
    }

    fun linesFor(songMediaId: String): List<LyricLine> =
        if (mediaId == songMediaId) lines else emptyList()

    /** 返回 (当前行, 下一行)，按播放位置二分。没有歌词时返回 (null, null)。 */
    fun at(songMediaId: String, positionMs: Long): Pair<String?, String?> {
        val all = linesFor(songMediaId)
        if (all.isEmpty()) return null to null
        var lo = 0
        var hi = all.lastIndex
        var hit = -1
        while (lo <= hi) {
            val mid = (lo + hi) / 2
            if (all[mid].timeMs <= positionMs) {
                hit = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        if (hit < 0) return null to all.firstOrNull()?.text
        val cur = all[hit].text
        val next = all.getOrNull(hit + 1)?.text
        return cur to next
    }

    fun size(songMediaId: String): Int = linesFor(songMediaId).size
}