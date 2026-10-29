package vip.wangxiaoming.music.ui.common

import vip.wangxiaoming.music.data.remote.model.Platform

/**
 * 歌单链接 → (平台, 歌单 id)。
 *
 * 只覆盖四家官方分享域名，未命中的一律返回 null，由 UI 提示「无法识别」，
 * 不猜测、不把整串 URL 当 id 去请求（原版对未识别链接静默失败，用户以为卡住）。
 *
 * 实测形态：
 *  - 网易：y.music.163.com/m/playlist?id=24381616 / music.163.com/#/playlist?id=24381616
 *  - 酷我：m.kuwo.cn/h5app/playlist/1234567 / kuwo.cn/playlist_detail/1234567
 *  - 酷狗：m.kugou.com/songlist/gcid_3xxxx / kugou.com/yy/special/single/1234567.html
 *  - QQ：y.qq.com/n/ryqq/playlist/8643484132
 */
object PlaylistLink {

    data class Target(val source: String, val id: String, val platform: Platform)

    fun parse(raw: String?): Target? {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return null

        // 纯数字：按网易处理（原版行为）
        if (text.all { it.isDigit() }) {
            return Target(Platform.NETEASE.code, text, Platform.NETEASE)
        }

        val lower = text.lowercase()

        if (lower.contains("163.com")) {
            findId(lower, "id")?.let { return Target(Platform.NETEASE.code, it, Platform.NETEASE) }
            findPathSegment(lower, "playlist")?.let { return Target(Platform.NETEASE.code, it, Platform.NETEASE) }
        }
        if (lower.contains("kuwo.cn")) {
            findPathSegment(lower, "playlist_detail")?.let { return Target(Platform.KUWO.code, it, Platform.KUWO) }
            findPathSegment(lower, "playlist")?.let { return Target(Platform.KUWO.code, it, Platform.KUWO) }
            findId(lower, "pid")?.let { return Target(Platform.KUWO.code, it, Platform.KUWO) }
        }
        if (lower.contains("kugou.com")) {
            // gcid_xxx 需先反查 specialid，本阶段不移植（服务端同样未接），明确返回 null
            findPathSegment(lower, "single")?.let { return Target(Platform.KUGOU.code, it, Platform.KUGOU) }
            findId(lower, "specialid")?.let { return Target(Platform.KUGOU.code, it, Platform.KUGOU) }
        }
        if (lower.contains("qq.com")) {
            findPathSegment(lower, "playlist")?.let { return Target(Platform.QQ.code, it, Platform.QQ) }
            findId(lower, "id")?.let { return Target(Platform.QQ.code, it, Platform.QQ) }
        }
        return null
    }

    /** ?id=123 或 &id=123 */
    private fun findId(url: String, key: String): String? {
        val patterns = listOf("$key=", "&$key=", "?$key=")
        for (p in patterns) {
            val i = url.indexOf(p)
            if (i < 0) continue
            val start = i + p.length
            val end = url.indexOfAny(charArrayOf('&', '#', '/', '?'), start).let {
                if (it < 0) url.length else it
            }
            val v = url.substring(start, end)
            if (v.isNotEmpty() && v.all { it.isDigit() }) return v
        }
        return null
    }

    /** /playlist/123 或 /single/123.html */
    private fun findPathSegment(url: String, key: String): String? {
        val i = url.indexOf("/$key/")
        if (i < 0) return null
        var start = i + key.length + 2
        var end = start
        while (end < url.length && url[end].isDigit()) end++
        return url.substring(start, end).takeIf { it.isNotEmpty() }
    }
}