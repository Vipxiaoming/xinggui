package vip.wangxiaoming.music.data.model

/** 与 DataStore 一一对应的用户偏好快照。不可变，改动走 PrefsRepository.update{}。 */
data class UserPrefs(
    /** 128k / 320k / flac */
    val quality: String = DEFAULT_QUALITY,
    /** 1..10 */
    val maxConcurrentDownloads: Int = DEFAULT_MAX_CONCURRENT,
    val showBatchDownloadBtn: Boolean = true,
    val darkMode: Boolean = false,
    /** 悬浮按钮位置，归一化 0f..1f */
    val floatingX: Float = 0f,
    val floatingY: Float = 0f,
    /** 默认音源标识：kw / kg / mg / wy / tx */
    val globalSource: String = DEFAULT_SOURCE,
    /** 上次同步成功的服务端时间戳（ms），0 表示从未同步 */
    val lastSyncAt: Long = 0L,
    /** 用户「跳过此版本」的 versionCode，0 表示未跳过 */
    val updateSkippedVersion: Int = 0,
) {
    companion object {
        const val DEFAULT_QUALITY = "320k"
        const val DEFAULT_MAX_CONCURRENT = 3
        const val DEFAULT_SOURCE = "kw"

        val QUALITIES = listOf("128k", "320k", "flac")
        val SOURCES = listOf("kw", "kg", "mg", "wy", "tx")

        fun maxConcurrent(value: Int): Int = value.coerceIn(1, 10)

        fun qualityOrDefault(value: String?): String =
            if (value != null && value in QUALITIES) value else DEFAULT_QUALITY

        fun sourceOrDefault(value: String?): String =
            if (value != null && value in SOURCES) value else DEFAULT_SOURCE
    }
}