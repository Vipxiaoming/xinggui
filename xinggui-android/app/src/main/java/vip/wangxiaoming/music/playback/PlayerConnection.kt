package vip.wangxiaoming.music.playback

import android.content.ComponentName
import android.content.Context
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import vip.wangxiaoming.music.R

/**
 * Activity/UI 侧到 PlaybackService 的唯一通道。
 *
 * 刻意不把 MediaController 存成单例：它绑定的是 Activity 生命周期
 * （Media3 官方推荐 onStart 连接 / onStop 释放），泄漏会导致通知点回 App 时
 * 拿到一个已经失效的 controller。
 */
object PlayerConnection {

    /**
     * 连接服务并返回 controller。
     * 失败时抛异常，由调用方决定是「静默降级到空态」还是「显示错误」。
     */
    suspend fun connect(context: Context): MediaController =
        suspendCancellableCoroutine { cont ->
            val token = SessionToken(
                context,
                ComponentName(context, PlaybackService::class.java),
            )
            val future: ListenableFuture<MediaController> =
                MediaController.Builder(context, token).buildAsync()

            future.addListener(
                {
                    try {
                        val controller = future.get()
                        if (cont.isActive) {
                            cont.resume(controller)
                        } else {
                            controller.release()
                        }
                    } catch (t: Throwable) {
                        if (cont.isActive) cont.resumeWithException(t)
                    }
                },
                MoreExecutors.directExecutor(),
            )

            cont.invokeOnCancellation { future.cancel(false) }
        }

    /**
     * 三档模式 → 资源 id，UI 与通知共用同一套映射。
     *
     * 注意：原版只搬了 loop / repeat 两个图标，随机档曾复用 loop，
     * 用户看不出「顺序」和「随机」的区别。这里已补 ic_play_mode_shuffle。
     */
    fun modeIconRes(mode: Int): Int = when (mode) {
        PlayMode.REPEAT_ONE -> R.drawable.ic_play_mode_repeat
        PlayMode.SHUFFLE -> R.drawable.ic_play_mode_shuffle
        else -> R.drawable.ic_play_mode_loop
    }

    /** 从 controller 的当前状态反推模式（委托 [PlayMode]，不重复实现）。 */
    fun currentMode(player: Player): Int = PlayMode.fromPlayer(player)
}