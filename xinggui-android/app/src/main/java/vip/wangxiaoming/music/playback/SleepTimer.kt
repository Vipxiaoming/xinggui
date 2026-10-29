package vip.wangxiaoming.music.playback

import android.content.Context
import androidx.media3.session.MediaController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import vip.wangxiaoming.music.ui.common.PlayerHolder

/**
 * 睡眠定时（P6 遗留功能）。
 *
 * 设计要点：**定时器不绑 Activity 生命周期，暂停动作落到播放服务**。
 *  - 计时协程跑在进程级 [scope] 上，Activity 退出、锁屏、切后台都不影响；
 *  - 到点时通过 [PlayerConnection] 拿到 MediaController 并调 `pause()`，
 *    真正执行暂停的是 [PlaybackService] 里的 ExoPlayer —— 控件只负责传入分钟数。
 *
 * 为什么 controller 只在**命中前 1 秒**才连：MediaController 连上 MediaSessionService
 * 会拉起前台服务并挂通知；提前 90 分钟连等于让用户「还没到点就先看到播放通知」。
 * 到期瞬间再连，此时用户本来就在播放，服务一定在跑，连接耗时在百毫秒级。
 *
 * 为什么不用 AlarmManager：睡眠定时在 15-90 分钟量级，播放期间进程必然存活
 * （前台服务），Handler 级计时足够准确，还省掉 Android 12+ 的精确闹钟权限。
 * 进程被杀的场景下音乐本身就停了，定时器无意义。
 */
object SleepTimer {

    /** 预设档位（分钟）。UI 与校验共用同一份常量，避免两处写死对不上。 */
    val PRESETS: List<Int> = listOf(15, 30, 45, 60, 90)

    /** 到点前多久建立 controller 连接（毫秒）。留 1.5s 余量抵御冷启动抖动。 */
    private const val CONNECT_LEAD_MS = 1_500L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _remainingMs = MutableStateFlow(0L)

    /** 剩余毫秒；0 表示未启用。每秒更新一次，UI 直接 collect。 */
    val remainingMs: StateFlow<Long> = _remainingMs.asStateFlow()

    private val _activeMinutes = MutableStateFlow(0)

    /** 当前档位分钟数；0 表示未启用。 */
    val activeMinutes: StateFlow<Int> = _activeMinutes.asStateFlow()

    private var job: Job? = null

    /**
     * 启动（或覆盖）定时。
     *
     * @param context 任意 Context，内部只取 applicationContext，不会泄漏 Activity。
     * @param minutes 分钟数，必须 > 0；不在 [PRESETS] 内的自定义值同样接受。
     */
    fun start(context: Context, minutes: Int) {
        if (minutes <= 0) return
        cancel()
        val app = context.applicationContext
        _activeMinutes.value = minutes
        _remainingMs.value = minutes * 60_000L

        job = scope.launch {
            var left = minutes * 60_000L
            while (left > CONNECT_LEAD_MS) {
                _remainingMs.value = left
                val step = 1_000L
                delay(step)
                left -= step
            }

            // 最后 1 秒：拿 controller。优先复用页面已有的（不额外起服务），
            // 拿不到再自己连一个，用完立刻 release。
            var owned: MediaController? = null
            val controller = PlayerHolder.controller ?: runCatching {
                PlayerConnection.connect(app).also { owned = it }
            }.getOrNull()

            // 补齐剩余时间，保证总时长与用户选的分钟数一致。
            if (left > 0) {
                _remainingMs.value = left
                delay(left)
            }
            _remainingMs.value = 0L
            _activeMinutes.value = 0

            // 到点暂停。runCatching：controller 可能在计时期间被系统回收。
            runCatching { controller?.pause() }
            runCatching { owned?.release() }
        }
    }

    /** 取消当前定时。未启用时调用是安全的。 */
    fun cancel() {
        job?.cancel()
        job = null
        _remainingMs.value = 0L
        _activeMinutes.value = 0
    }

    /** 剩余时间的 "mm:ss" 文本，未启用时返回空串。 */
    fun formatRemaining(ms: Long): String {
        if (ms <= 0L) return ""
        val totalSec = (ms + 999L) / 1000L
        val m = totalSec / 60
        val s = totalSec % 60
        return "%02d:%02d".format(m, s)
    }

    /** 供后台/无人值守场景直接投递暂停（例如 Service 内部复用），避免重复实现。 */
    suspend fun pauseNow(context: Context) = withContext(Dispatchers.Main) {
        PlayerHolder.controller?.pause()
    }
}