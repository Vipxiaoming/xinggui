package vip.wangxiaoming.music.data.remote.http

import vip.wangxiaoming.music.data.remote.model.LyricLine

/**
 * LRC 解析。同时兼容网易的 "[mm:ss.mmm]" 与酷我的 "[mm:ss.xx]"（两位小数）。
 *
 * 原版不解析 LRC，直接把整段字符串丢给 UI；这里多做一步结构化，
 * 便于后续歌词页按行滚动高亮（原版靠定时器 + 正则每帧扫，性能更差）。
 */
object LrcParser {

    private val TIME_TAG = Regex("\\[(\\d{1,2}):(\\d{1,2})(?:[.:](\\d{1,3}))?]")
    private val OFFSET_TAG = Regex("\\[offset:\\s*([-+]?\\d+)]", RegexOption.IGNORE_CASE)

    fun parse(lrc: String?): List<LyricLine> {
        if (lrc.isNullOrBlank()) return emptyList()
        val offsetMs = OFFSET_TAG.find(lrc)?.groupValues?.get(1)?.toLongOrNull() ?: 0L

        val out = ArrayList<LyricLine>()
        for (rawLine in lrc.lineSequence()) {
            val line = rawLine.trim()
            if (line.isEmpty()) continue
            if (OFFSET_TAG.containsMatchIn(line)) continue

            val tags = TIME_TAG.findAll(line).toList()
            if (tags.isEmpty()) continue
            val text = line.substring(tags.last().range.last + 1).trim()
            for (tag in tags) {
                val min = tag.groupValues[1].toLong()
                val sec = tag.groupValues[2].toLong()
                val fracRaw = tag.groupValues[3]
                val fracMs = when (fracRaw.length) {
                    0 -> 0L
                    1 -> fracRaw.toLong() * 100
                    2 -> fracRaw.toLong() * 10
                    else -> fracRaw.take(3).toLong()
                }
                val ms = (min * 60 + sec) * 1000 + fracMs + offsetMs
                out.add(LyricLine(ms.coerceAtLeast(0), text))
            }
        }
        return out.sortedBy { it.timeMs }
    }

    /** 结构化行 → LRC 文本（酷我/酷狗只给结构，需要转回统一格式） */
    fun toLrc(lines: List<LyricLine>): String = lines.joinToString("\n") { line ->
        val totalMs = line.timeMs
        val min = totalMs / 60000
        val sec = (totalMs % 60000) / 1000
        val cs = (totalMs % 1000) / 10
        "[%02d:%02d.%02d]%s".format(min, sec, cs, line.text)
    }
}