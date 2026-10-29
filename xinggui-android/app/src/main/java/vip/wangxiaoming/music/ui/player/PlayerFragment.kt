package vip.wangxiaoming.music.ui.player

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.SeekBar
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.Player
import kotlinx.coroutines.launch
import vip.wangxiaoming.music.R
import vip.wangxiaoming.music.data.remote.MetadataRepository
import vip.wangxiaoming.music.data.remote.model.Song
import vip.wangxiaoming.music.databinding.FragmentPlayerBinding
import vip.wangxiaoming.music.playback.PlayMode
import vip.wangxiaoming.music.playback.SleepTimer
import vip.wangxiaoming.music.ui.common.CoverLoader
import vip.wangxiaoming.music.ui.common.FavoritesRepository
import vip.wangxiaoming.music.ui.common.HostActions
import vip.wangxiaoming.music.ui.common.LyricStore
import vip.wangxiaoming.music.ui.common.PlayerHolder
import vip.wangxiaoming.music.ui.common.UiKit

/**
 * 全屏播放器。
 *
 * 进度/状态全部从 MediaController 读，不复刻一份本地状态（原版在 Activity 里另存
 * position 并只在切歌时刷新，导致锁屏进度恒 0）。seek 用「拖动中暂停刷新、松手才 seek」
 * 的标准做法，避免每来一个 position 回调就把用户拖动的滑块拽回去。
 *
 * 歌词：进入时按当前 MediaItem 的 mediaId 反查 Song（写死在这首歌的 cover/artist 里），
 * 走 MetadataRepository.lyric；酷我/网易有歌词接口，酷狗/QQ 本阶段返回空并显示提示，
 * 不伪造歌词行。全屏歌词由 [LyricFragment] 覆盖在碟片区之上（运行时注入容器，
 * 不改 fragment_player.xml，避免与并行阶段改同一份布局冲突）。
 *
 * 收藏：走 [FavoritesRepository]，以 mediaId（source:id）为主键落 Room。
 * 按钮态用 Flow 订阅，切歌/取消收藏都会即时反映，不做「点了变色但没落库」。
 */
class PlayerFragment : Fragment() {

    private var _b: FragmentPlayerBinding? = null
    private val b get() = _b!!

    private val handler = Handler(Looper.getMainLooper())
    private val repo = MetadataRepository()
    private val favorites = FavoritesRepository()

    private var dragging = false
    private var lyricSong: Song? = null
    private var lyricRequested = false

    /** 全屏歌词的容器。懒创建：大多数用户不会点进歌词模式。 */
    private var lyricHost: FrameLayout? = null
    private var lyricShowing = false

    private val host: HostActions get() = requireActivity() as HostActions

    private val ticker = object : Runnable {
        override fun run() {
            syncProgress()
            handler.postDelayed(this, 250)
        }
    }

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            render(player)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _b = FragmentPlayerBinding.inflate(inflater, container, false)
        return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        bindControls()
        render(PlayerHolder.controller)
    }

    override fun onStart() {
        super.onStart()
        PlayerHolder.controller?.addListener(listener)
        handler.post(ticker)
    }

    override fun onStop() {
        handler.removeCallbacks(ticker)
        PlayerHolder.controller?.removeListener(listener)
        super.onStop()
    }

    override fun onDestroyView() {
        handler.removeCallbacks(ticker)
        _b = null
        super.onDestroyView()
    }

    // ------------------------------------------------------------ 交互

    private fun bindControls() {
        b.btnCloseFull.setOnClickListener { host.closePlayer() }

        b.btnFullPlayPause.setOnClickListener {
            val c = PlayerHolder.controller ?: return@setOnClickListener
            if (c.isPlaying) c.pause() else c.play()
        }
        b.btnFullPrev.setOnClickListener {
            val c = PlayerHolder.controller ?: return@setOnClickListener
            if (c.currentPosition > 3000) c.seekTo(0) else c.seekToPreviousMediaItem()
        }
        b.btnFullNext.setOnClickListener {
            PlayerHolder.controller?.seekToNextMediaItem()
        }
        b.btnFullPlayMode.setOnClickListener {
            val c = PlayerHolder.controller ?: return@setOnClickListener
            val order = PlayMode.ordered
            val next = order[(order.indexOf(PlayMode.fromPlayer(c)) + 1) % order.size]
            PlayMode.applyTo(c, next)
            b.btnFullPlayMode.setImageResource(vip.wangxiaoming.music.playback.PlayerConnection.modeIconRes(next))
            UiKit.toast(requireContext(), modeLabel(next))
        }
        b.btnFullPlaylist.setOnClickListener {
            QueueSheet.show(requireContext())
        }

        b.fullSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                val c = PlayerHolder.controller ?: return
                val dur = c.duration.coerceAtLeast(0L)
                if (dur > 0) b.tvTimeCurrent.text = UiKit.mmss(dur * progress / 1000)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                dragging = true
            }

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                val c = PlayerHolder.controller
                val dur = c?.duration ?: 0L
                if (c != null && dur > 0) {
                    c.seekTo(dur * (seekBar?.progress ?: 0) / 1000)
                }
                dragging = false
            }
        })

        // 收藏：点一次落库/删库，图标与 toast 都反映真实结果（不是先变色再失败）。
        b.btnVinylLike.setOnClickListener { toggleFavorite() }
        b.btnVinylDownload.setOnClickListener {
            UiKit.toast(requireContext(), "下载器属 P7（下载任务表已就绪）")
        }
        b.btnVinylAlarm.setOnClickListener {
            SleepTimerSheet.show(requireContext(), viewLifecycleOwner)
        }
        b.btnVinylComment.setOnClickListener { UiKit.toast(requireContext(), "评论回顾属 P8") }

        // 点黑胶/封面进歌词模式（原版 behavior 一致）。
        b.vinylDiscContainer.setOnClickListener { showLyric() }
        b.lyricContainer.setOnClickListener { showLyric() }
        // 顶栏显式「歌词」入口：封面点击容易误触（想把封面放大的人不少），
        // 顶部给一个确定的入口，同时它也是退出歌词模式的唯一可见按钮。
        b.btnFullLyric.setOnClickListener { showLyric() }
    }

    private fun modeLabel(mode: Int): String = when (mode) {
        PlayMode.REPEAT_ONE -> getString(R.string.play_mode_repeat_one)
        PlayMode.SHUFFLE -> getString(R.string.play_mode_shuffle)
        else -> getString(R.string.play_mode_sequential)
    }

    // ------------------------------------------------------------ 收藏

    private fun toggleFavorite() {
        val c = PlayerHolder.controller ?: return
        val item = c.currentMediaItem ?: return
        val mediaId = item.mediaId
        if (mediaId.isBlank()) return
        val source = mediaId.substringBefore(':', "")
        val id = mediaId.substringAfter(':', "")
        if (source.isBlank() || id.isBlank()) return

        val meta = item.mediaMetadata
        val song = Song(
            id = id,
            name = meta.title?.toString().orEmpty(),
            artist = meta.artist?.toString().orEmpty(),
            cover = meta.artworkUri?.toString().orEmpty(),
            source = source,
        )
        viewLifecycleOwner.lifecycleScope.launch {
            val nowFavorite = runCatching { favorites.toggle(song) }.getOrDefault(false)
            if (_b == null) return@launch
            renderFavorite(nowFavorite)
            UiKit.toast(requireContext(), if (nowFavorite) "已加入我的喜欢" else "已取消收藏")
        }
    }

    private fun renderFavorite(favorite: Boolean) {
        if (_b == null) return
        b.btnVinylLike.setImageResource(
            if (favorite) R.drawable.ic_vinyl_liked else R.drawable.ic_vinyl_like,
        )
        b.btnVinylLike.setColorFilter(
            ContextCompat.getColor(requireContext(), if (favorite) R.color.brand else R.color.ink_500),
        )
    }

    /** 订阅当前曲目的收藏态；切歌时取消旧订阅再订阅新 mediaId。 */
    private var favoriteJob: kotlinx.coroutines.Job? = null

    private fun observeFavorite(mediaId: String) {
        favoriteJob?.cancel()
        if (mediaId.isBlank()) {
            renderFavorite(false)
            return
        }
        favoriteJob = viewLifecycleOwner.lifecycleScope.launch {
            favorites.observeIsFavorite(mediaId).collect { renderFavorite(it) }
        }
    }

    // ------------------------------------------------------------ 歌词全屏

    /**
     * 运行时创建歌词容器。
     *
     * 为什么不写进 fragment_player.xml：该布局正被并行阶段（图标/品牌）复用，
     * 两边同时改会互相覆盖。容器只依赖 playerRoot，z-order 由 addView 顺序决定。
     */
    private fun ensureLyricHost(): FrameLayout {
        lyricHost?.let { return it }
        val view = FrameLayout(requireContext()).apply {
            // 必须给一个真实 id：FragmentTransaction.replace 需要一个容器 id，
            // 用 View.generateViewId() 生成即可（不占 R.id 命名空间，避免与并行改布局冲突）。
            id = View.generateViewId()
            // playerRoot 是 RelativeLayout，必须配 RelativeLayout.LayoutParams，
            // 否则 addView 时抛 ClassCastException（FrameLayout.LayoutParams 不是它的子类）。
            layoutParams = android.widget.RelativeLayout.LayoutParams(
                android.widget.RelativeLayout.LayoutParams.MATCH_PARENT,
                android.widget.RelativeLayout.LayoutParams.MATCH_PARENT,
            )
        }
        b.playerRoot.addView(view)
        lyricHost = view
        return view
    }

    private fun showLyric() {
        if (lyricShowing) return
        val container = ensureLyricHost()
        container.visibility = View.VISIBLE
        container.bringToFront()
        lyricShowing = true
        childFragmentManager.beginTransaction()
            .replace(container.id, LyricFragment(), TAG_LYRIC)
            .commitAllowingStateLoss()
        // 歌词全屏时隐藏下方控制区，避免与底部提示文字挤在一起。
        b.playerBottomControl.visibility = View.GONE
        b.fullTopBar.visibility = View.GONE
        childFragmentManager.setFragmentResultListener(KEY_CLOSE_LYRIC, viewLifecycleOwner) { _, _ ->
            hideLyric()
        }
    }

    private fun hideLyric() {
        if (!lyricShowing) return
        val f = childFragmentManager.findFragmentByTag(TAG_LYRIC)
        if (f != null) childFragmentManager.beginTransaction().remove(f).commitAllowingStateLoss()
        lyricHost?.visibility = View.GONE
        lyricShowing = false
        b.playerBottomControl.visibility = View.VISIBLE
        b.fullTopBar.visibility = View.VISIBLE
    }

    // ------------------------------------------------------------ 渲染

    private fun render(c: Player?) {
        if (_b == null) return
        if (c == null) {
            b.fullSongTitle.text = "未连接播放服务"
            b.fullArtistTitle.text = ""
            return
        }
        val meta = c.currentMediaItem?.mediaMetadata
        b.fullSongTitle.text = meta?.title ?: "未知曲目"
        b.fullArtistTitle.text = meta?.artist ?: "未知歌手"
        b.btnFullPlayPause.setImageResource(
            if (c.isPlaying) R.drawable.ic_player_pause else R.drawable.ic_player_play_line,
        )
        b.btnFullPlayMode.setImageResource(
            vip.wangxiaoming.music.playback.PlayerConnection.modeIconRes(PlayMode.fromPlayer(c)),
        )

        val art = meta?.artworkUri?.toString()
        if (!art.isNullOrBlank()) CoverLoader.load(b.fullAlbumCover, art)

        val mediaId = c.currentMediaItem?.mediaId.orEmpty()
        b.tvPlayerSource.text = UiKit.sourceLabel(mediaId.substringBefore(':', "").takeIf { it.isNotBlank() })

        syncProgress()
        maybeLoadLyric(mediaId, meta?.title?.toString().orEmpty(), meta?.artist?.toString().orEmpty())
        renderLyric(mediaId, c.currentPosition)
    }

    private fun syncProgress() {
        val c = PlayerHolder.controller ?: return
        if (_b == null || dragging) return
        val dur = c.duration
        val pos = c.currentPosition
        if (dur > 0) {
            b.fullSeekBar.progress = (pos * 1000 / dur).toInt().coerceIn(0, 1000)
            b.tvTimeDuration.text = UiKit.mmss(dur)
        } else {
            b.fullSeekBar.progress = 0
            b.tvTimeDuration.text = "00:00"
        }
        b.tvTimeCurrent.text = UiKit.mmss(pos.coerceAtLeast(0))
        renderLyric(c.currentMediaItem?.mediaId.orEmpty(), pos)
    }

    private var lastFavoriteMediaId: String = ""

    private fun renderLyric(mediaId: String, positionMs: Long) {
        if (mediaId.isBlank()) return
        if (mediaId != lastFavoriteMediaId) {
            lastFavoriteMediaId = mediaId
            observeFavorite(mediaId)
        }
        val (cur, next) = LyricStore.at(mediaId, positionMs)
        val fallback = LyricStore.status
        b.tvLyricCurrent.text = cur ?: fallback
        b.tvLyricNext.text = next ?: ""
    }

    /** 每首歌只拉一次歌词；返回空时把原因写进 LyricStore.status，UI 直接显示。 */
    private fun maybeLoadLyric(mediaId: String, title: String, artist: String) {
        if (mediaId.isBlank() || lyricRequested) return
        if (LyricStore.size(mediaId) > 0) {
            lyricRequested = true
            return
        }
        val source = mediaId.substringBefore(':', "")
        val id = mediaId.substringAfter(':', "")
        if (source.isBlank() || id.isBlank()) return

        lyricRequested = true
        lyricSong = Song(id = id, name = title, artist = artist, source = source)
        viewLifecycleOwner.lifecycleScope.launch {
            val song = lyricSong ?: return@launch
            val res = runCatching { repo.lyric(song) }.getOrNull()
            val raw = res?.data.orEmpty()
            val parsed = vip.wangxiaoming.music.data.remote.http.LrcParser.parse(raw)
            LyricStore.put(mediaId, raw, parsed)
            if (_b == null) return@launch
            val c = PlayerHolder.controller
            renderLyric(mediaId, c?.currentPosition ?: 0L)
        }
    }

    private companion object {
        const val TAG_LYRIC = "player_lyric"
        const val KEY_CLOSE_LYRIC = "close_lyric"
    }
}