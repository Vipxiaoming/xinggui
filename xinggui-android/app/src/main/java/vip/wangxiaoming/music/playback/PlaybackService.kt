package vip.wangxiaoming.music.playback

import android.app.PendingIntent
import android.content.Intent
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import vip.wangxiaoming.music.MainActivity
import vip.wangxiaoming.music.R

/**
 * 播放服务（P5）。
 *
 * 与原版 MusicPlayerService 的关键差异：
 *  1. 原版在 updateMediaSessionState() 里只在状态切换时调 setPlaybackState(pos)，
 *     播放中不刷新 → `dumpsys media_session` 的 position 恒为 0，锁屏/车机进度条不动。
 *     这里用 Media3 的 MediaSession 并把 setPeriodicPositionUpdateEnabled(true) 显式打开。
 *  2. 原版 MediaItem/MediaDescription 没有 id，active item id 恒为 -1。
 *     这里每个 MediaItem 都写 mediaId，且与 TrackRef.mediaId 一致。
 *  3. 原版手写 NotificationCompat，与 MediaStyle 打架。
 *     这里只用 DefaultMediaNotificationProvider，不自己建 Notification。
 */
class PlaybackService : MediaSessionService() {

    private var mediaSession: MediaSession? = null
    private lateinit var player: ExoPlayer
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** P5 用固定实现；P6 换成服务端 /v1/resolve 的 Provider 即可，调用点不动。 */
    private val playUrlProvider: PlayUrlProvider = StaticPlayUrlProvider()

    private var pendingQueue: List<TrackRef> = emptyList()

    override fun onCreate() {
        super.onCreate()

        val httpFactory = DefaultHttpDataSource.Factory()
            .setUserAgent(USER_AGENT)
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(20_000)
            .setAllowCrossProtocolRedirects(true)

        player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(
                DefaultMediaSourceFactory(DefaultDataSource.Factory(this, httpFactory)),
            )
            // 音频焦点：第二参数 true = 由 ExoPlayer 托管焦点（失焦自动暂停/降音量）
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                /* handleAudioFocus = */ true,
            )
            // 耳机拔出 / 蓝牙断开自动暂停（原版靠 BroadcastReceiver，容易漏注册）
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
            .apply {
                repeatMode = Player.REPEAT_MODE_OFF
                shuffleModeEnabled = false
            }

        val sessionActivity = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        mediaSession = MediaSession.Builder(this, player)
            .setSessionActivity(sessionActivity)
            .setId(SESSION_ID)
            // 关键：让 PlaybackState 周期性带上当前 position，
            // 否则 SystemUI / 车机读到的是最后一次 setPlaybackState 的静态值。
            .setPeriodicPositionUpdateEnabled(true)
            .setCallback(SessionCallback())
            .build()

        setMediaNotificationProvider(
            DefaultMediaNotificationProvider.Builder(this)
                .setChannelId(CHANNEL_ID)
                .setChannelName(R.string.playback_channel_name)
                .setNotificationId(NOTIFICATION_ID)
                .build()
                .apply { setSmallIcon(R.drawable.ic_notification_small) },
        )

        loadQueue(PlaybackQueue.demo, autoPlay = false)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    /** 任务从最近任务划掉：原版会继续播放并卡在通知里，这里选择停止。 */
    override fun onTaskRemoved(rootIntent: Intent?) {
        player.pause()
        stopSelf()
    }

    override fun onDestroy() {
        mediaSession?.run {
            player.release()
            release()
        }
        mediaSession = null
        scope.cancel()
        super.onDestroy()
    }

    // ---------------------------------------------------------------- 队列

    /**
     * 先把所有媒体 URL 解析完，再一次性 setMediaItems。
     *
     * 为什么不在 onAddMediaItems 里懒解析：Media3 的 resolve 回调必须同步返回
     * ListenableFuture，而解析是网络竞速；用 futures 拼装会让「同一批解析失败」
     * 的错误恢复变得很难推理。P5 队列很小（demo 2 条），预先解析更直观。
     */
    private fun loadQueue(tracks: List<TrackRef>, autoPlay: Boolean, startIndex: Int = 0) {
        pendingQueue = tracks
        scope.launch {
            val items = withContext(Dispatchers.IO) {
                tracks.mapNotNull { track ->
                    val url = playUrlProvider.resolve(track.mediaId, track.title, track.artist)
                    if (url == null) {
                        null
                    } else {
                        MediaItem.Builder()
                            .setMediaId(track.mediaId)
                            .setUri(url)
                            .setMediaMetadata(
                                MediaMetadata.Builder()
                                    .setTitle(track.title)
                                    .setArtist(track.artist)
                                    .setIsPlayable(true)
                                    .setIsBrowsable(false)
                                    .build(),
                            )
                            .build()
                    }
                }
            }
            if (items.isEmpty()) return@launch
            player.setMediaItems(items, startIndex.coerceIn(0, items.lastIndex), 0L)
            player.prepare()
            if (autoPlay) player.play()
        }
    }

    // ---------------------------------------------------------------- 模式

    /**
     * 三档模式循环切换：顺序 → 单曲循环 → 随机 → 顺序。
     * 返回切换后的模式，供 UI 更新图标。
     */
    /**
     * 三档模式循环切换：顺序 → 单曲循环 → 随机 → 顺序。
     * 映射逻辑统一在 [PlayMode]，这里只负责推进顺序。
     */
    private fun cycleRepeatMode(): Int {
        val order = PlayMode.ordered
        val next = order[(order.indexOf(PlayMode.fromPlayer(player)) + 1) % order.size]
        PlayMode.applyTo(player, next)
        return next
    }

    // ---------------------------------------------------------------- Session

    private inner class SessionCallback : MediaSession.Callback {

        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): MediaSession.ConnectionResult {
            val available = MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon()
                .add(SessionCommand(CMD_CYCLE_REPEAT, android.os.Bundle.EMPTY))
                .build()
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(available)
                .build()
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: android.os.Bundle,
        ): ListenableFuture<SessionResult> {
            return when (customCommand.customAction) {
                CMD_CYCLE_REPEAT -> {
                    val mode = cycleRepeatMode()
                    val result = android.os.Bundle().apply { putInt(KEY_REPEAT_MODE, mode) }
                    Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS, result))
                }
                else -> Futures.immediateFuture(SessionResult(SessionResult.RESULT_ERROR_NOT_SUPPORTED))
            }
        }
    }

    companion object {
        const val SESSION_ID = "xinggui-playback"
        const val CHANNEL_ID = "xinggui_playback"
        const val NOTIFICATION_ID = 1001

        const val CMD_CYCLE_REPEAT = "vip.wangxiaoming.music.CYCLE_REPEAT"
        const val KEY_REPEAT_MODE = "repeat_mode"

        const val MODE_SEQUENTIAL = 0
        const val MODE_REPEAT_ONE = 1
        const val MODE_SHUFFLE = 2

        const val USER_AGENT = "XingGuiMusic/0.1 (Android)"
    }
}