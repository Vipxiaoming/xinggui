package vip.wangxiaoming.music.playback

import androidx.media3.common.Player

/**
 * 三档播放模式的唯一映射来源。
 *
 * 为什么单独抽出来：最初 MainActivity、PlaybackService、单测各写了一份
 * `when(mode)` 映射，改一处漏两处。原版缺陷「模式只换图标不改 Player」
 * 正是这类重复映射漏改的典型后果。
 *
 * 现在只有一个 `when`，在 [applyTo] 里；三条调用路径都走它：
 *  - 服务内自定义命令（通知/车机/耳机键）
 *  - MainActivity 的模式对话框
 *  - 单元测试（用 [PlayModeTarget] 的内存实现）
 */
object PlayMode {

    const val SEQUENTIAL = PlaybackService.MODE_SEQUENTIAL
    const val REPEAT_ONE = PlaybackService.MODE_REPEAT_ONE
    const val SHUFFLE = PlaybackService.MODE_SHUFFLE

    /** 模式在 UI 上的固定展示顺序（对话框 / 长按循环共用）。 */
    val ordered: List<Int> = listOf(SEQUENTIAL, REPEAT_ONE, SHUFFLE)

    /**
     * 模式写入目标。抽这一层是为了让测试能验证「映射本身」，
     * 而不必构造 SimpleBasePlayer —— 它的 setter 走异步状态机，
     * 断言时机会飘。
     */
    interface PlayModeTarget {
        fun onRepeatMode(mode: Int)
        fun onShuffleEnabled(enabled: Boolean)
    }

    /** 唯一写入口。顺序 / 单曲 / 随机，其他值一律视为顺序。 */
    fun applyTo(target: PlayModeTarget, mode: Int) {
        when (mode) {
            REPEAT_ONE -> {
                target.onShuffleEnabled(false)
                target.onRepeatMode(Player.REPEAT_MODE_ONE)
            }
            SHUFFLE -> {
                target.onShuffleEnabled(true)
                target.onRepeatMode(Player.REPEAT_MODE_ALL)
            }
            else -> {
                target.onShuffleEnabled(false)
                target.onRepeatMode(Player.REPEAT_MODE_OFF)
            }
        }
    }

    /** Player 适配器：把 Media3 的 setter 桥到 [PlayModeTarget]。 */
    fun applyTo(player: Player, mode: Int) = applyTo(
        object : PlayModeTarget {
            override fun onRepeatMode(mode: Int) {
                player.repeatMode = mode
            }

            override fun onShuffleEnabled(enabled: Boolean) {
                player.shuffleModeEnabled = enabled
            }
        },
        mode,
    )

    /** 从 Player 反推当前模式，避免 UI 另存一份易漂移的状态。 */
    fun fromPlayer(player: Player): Int = when {
        player.shuffleModeEnabled -> SHUFFLE
        player.repeatMode == Player.REPEAT_MODE_ONE -> REPEAT_ONE
        else -> SEQUENTIAL
    }
}