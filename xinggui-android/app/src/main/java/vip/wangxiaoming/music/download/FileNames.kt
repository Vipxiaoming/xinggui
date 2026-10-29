package vip.wangxiaoming.music.download

/**
 * 落盘文件名生成。
 *
 * 目标格式 `<歌手> - <歌名>.mp3`（与原版一致），但原版直接把 artist/name 拼进 File 名字，
 * 遇到 `/`、`:`、`?`、`|` 或换行会产生非法路径（Windows/Android 都会踩）。
 * 这里做四件事：非法字符替换、连续空白折叠、去掉首尾的点与空格（Windows 会吃掉结尾点）、
 * 按 UTF-8 字节长度截断（MediaStore 的 DISPLAY_NAME 上限 255 字节）。
 */
object FileNames {

    /** 文件系统与 MediaStore 都拒绝的字符，外加全部控制字符 */
    private val ILLEGAL = Regex("[\\\\/:*?\"<>|\\p{Cntrl}]")

    private val WINDOWS_RESERVED = setOf(
        "CON", "PRN", "AUX", "NUL",
        "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9",
        "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9",
    )

    /** 单段最大字节数。留余量给 " - " 与 ".mp3"。 */
    private const val SEGMENT_MAX_BYTES = 100

    fun sanitize(raw: String?, fallback: String): String {
        val replaced = ILLEGAL.replace(raw.orEmpty(), "_")
            .replace(Regex("\\s+"), " ")
            .trim()
            .trim('.', ' ')
        val safe = replaced.ifBlank { fallback }
        val head = safe.substringBefore('.').uppercase()
        val named = if (head in WINDOWS_RESERVED) "_" + safe else safe
        return truncateUtf8(named, SEGMENT_MAX_BYTES)
    }

    /** `<歌手> - <歌名>.mp3` */
    fun buildFileName(artist: String?, name: String?): String {
        val a = sanitize(artist, "未知歌手")
        val n = sanitize(name, "未知曲目")
        return "$a - $n.mp3"
    }

    /** 按 UTF-8 字节截断，不切断多字节字符。 */
    fun truncateUtf8(value: String, maxBytes: Int): String {
        if (value.toByteArray(Charsets.UTF_8).size <= maxBytes) return value
        var bytes = 0
        val sb = StringBuilder()
        var i = 0
        while (i < value.length) {
            val cp = value.codePointAt(i)
            val chunk = String(Character.toChars(cp))
            val len = chunk.toByteArray(Charsets.UTF_8).size
            if (bytes + len > maxBytes) break
            bytes += len
            sb.append(chunk)
            i += Character.charCount(cp)
        }
        return sb.toString().trimEnd('.', ' ').ifBlank { "track" }
    }
}