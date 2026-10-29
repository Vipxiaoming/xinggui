package vip.wangxiaoming.music.playback

import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 验证三档播放模式的映射与队列约定。
 *
 * 这里刻意不复制映射逻辑 —— 生产代码里 MainActivity、PlaybackService、
 * MediaSession 自定义命令三条路径都调 [PlayMode.applyTo]，
 * 单测也调同一个函数，测到的是真代码而不是副本。
 *
 * 用 [PlayMode.PlayModeTarget] 的内存实现，避免构造 SimpleBasePlayer
 * （它的 setter 走异步状态机，断言时机不稳）。
 */
class PlaybackModeTest {

    private class Recorder : PlayMode.PlayModeTarget {
        var repeatMode: Int = -999
        var shuffleEnabled: Boolean? = null

        override fun onRepeatMode(mode: Int) {
            repeatMode = mode
        }

        override fun onShuffleEnabled(enabled: Boolean) {
            shuffleEnabled = enabled
        }
    }

    @Test
    fun sequentialModeTurnsEverythingOff() {
        val r = Recorder()
        PlayMode.applyTo(r, PlayMode.SEQUENTIAL)
        assertEquals(Player.REPEAT_MODE_OFF, r.repeatMode)
        assertEquals(false, r.shuffleEnabled)
    }

    @Test
    fun repeatOneKeepsSingleTrackLooping() {
        val r = Recorder()
        PlayMode.applyTo(r, PlayMode.REPEAT_ONE)
        assertEquals(Player.REPEAT_MODE_ONE, r.repeatMode)
        assertEquals("单曲循环不应同时开启随机", false, r.shuffleEnabled)
    }

    @Test
    fun shuffleEnablesShuffleAndRepeatsAll() {
        val r = Recorder()
        PlayMode.applyTo(r, PlayMode.SHUFFLE)
        assertTrue(r.shuffleEnabled!!)
        assertEquals(Player.REPEAT_MODE_ALL, r.repeatMode)
    }

    @Test
    fun unknownModeFallsBackToSequential() {
        val r = Recorder()
        PlayMode.applyTo(r, 42)
        assertEquals(Player.REPEAT_MODE_OFF, r.repeatMode)
        assertEquals(false, r.shuffleEnabled)
    }

    @Test
    fun switchingModesIsNotAccumulative() {
        val r = Recorder()
        PlayMode.applyTo(r, PlayMode.SHUFFLE)
        PlayMode.applyTo(r, PlayMode.SEQUENTIAL)
        // 从随机切回顺序必须把 shuffle 关掉，否则队列仍然是乱的。
        assertEquals(false, r.shuffleEnabled)
        assertEquals(Player.REPEAT_MODE_OFF, r.repeatMode)
    }

    @Test
    fun orderedListCoversAllThreeModesExactlyOnce() {
        assertEquals(3, PlayMode.ordered.size)
        assertEquals(PlayMode.ordered.size, PlayMode.ordered.distinct().size)
        assertTrue(PlayMode.ordered.contains(PlayMode.SEQUENTIAL))
        assertTrue(PlayMode.ordered.contains(PlayMode.REPEAT_ONE))
        assertTrue(PlayMode.ordered.contains(PlayMode.SHUFFLE))
    }

    @Test
    fun cyclingFromSequentialGoesToRepeatOneThenShuffleThenBack() {
        // 复刻 PlaybackService.cycleRepeatMode 的推进规则
        fun next(current: Int): Int {
            val order = PlayMode.ordered
            return order[(order.indexOf(current) + 1) % order.size]
        }
        assertEquals(PlayMode.REPEAT_ONE, next(PlayMode.SEQUENTIAL))
        assertEquals(PlayMode.SHUFFLE, next(PlayMode.REPEAT_ONE))
        assertEquals(PlayMode.SEQUENTIAL, next(PlayMode.SHUFFLE))
    }

    @Test
    fun demoQueueMediaIdsAreUniqueAndStable() {
        val ids = PlaybackQueue.demo.map { it.mediaId }
        assertEquals("队列内 mediaId 必须唯一", ids.size, ids.distinct().size)
        ids.forEach {
            assertTrue("mediaId 不能为空，否则 MediaSession 报 active item id=-1", it.isNotBlank())
            assertNotEquals("mediaId 不能是 -1", "-1", it)
        }
    }

    @Test
    fun mediaItemCarriesMediaIdAndMetadata() {
        // 复刻 PlaybackService.loadQueue 里构造 MediaItem 的方式
        val track = PlaybackQueue.demo.first()
        val item = MediaItem.Builder()
            .setMediaId(track.mediaId)
            .setUri(StaticPlayUrlProvider.DEFAULT_URL)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(track.title)
                    .setArtist(track.artist)
                    .build(),
            )
            .build()
        assertEquals(track.mediaId, item.mediaId)
        assertEquals(track.title, item.mediaMetadata.title)
        assertEquals(track.artist, item.mediaMetadata.artist)
    }

    @Test
    fun staticProviderReturnsPlayableHttpsUrl() {
        val url = StaticPlayUrlProvider.DEFAULT_URL
        assertTrue("必须是 https，避免明文流量策略拦截", url.startsWith("https://"))
        assertTrue(url.endsWith(".mp3"))
    }
}