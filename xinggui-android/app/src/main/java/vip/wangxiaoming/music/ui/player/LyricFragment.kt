package vip.wangxiaoming.music.ui.player

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.Player
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.launch
import vip.wangxiaoming.music.R
import vip.wangxiaoming.music.data.remote.MetadataRepository
import vip.wangxiaoming.music.data.remote.http.LrcParser
import vip.wangxiaoming.music.data.remote.model.LyricLine
import vip.wangxiaoming.music.data.remote.model.Song
import vip.wangxiaoming.music.databinding.FragmentLyricBinding
import vip.wangxiaoming.music.databinding.ItemLyricLineBinding
import vip.wangxiaoming.music.ui.common.LyricStore
import vip.wangxiaoming.music.ui.common.PlayerHolder
import vip.wangxiaoming.music.ui.common.UiKit

/**
 * 全屏歌词（P6 遗留功能）。
 *
 * 三条行为约束，都来自原版「歌词模式」的实际交互：
 *  1. **当前行居中高亮**：品牌蓝 + medium 字重，上下行由透明度渐变淡出；
 *  2. **点击任意一行 seek**：按该行的 timeMs 跳转，不暂停播放；
 *  3. **拖拽浏览**：手指按下即停止自动跟随，松手 4 秒后回到当前行。
 *     4 秒是刻意的取舍 —— 太短用户还没看完，太长会显得「不跟唱了」。
 *
 * 歌词来源：先用 [LyricStore] 的进程缓存（播放器全屏页已经拉过就不会重复请求），
 * 缓存为空时自己按 mediaId 反查 Song 再走 [MetadataRepository.lyric]。
 *
 * 高亮更新走 250ms 轮询而不是 Player.Listener：
 * 播放位置是连续变化的，Listener 只在状态跳变时触发，无法驱动逐行滚动。
 */
class LyricFragment : Fragment() {

    private var _b: FragmentLyricBinding? = null
    private val b get() = _b!!

    private val handler = Handler(Looper.getMainLooper())
    private val repo = MetadataRepository()
    private val adapter = LyricAdapter { line -> seekTo(line) }

    /** 用户正在拖拽浏览：暂停自动跟随，避免和手指抢滚动。 */
    private var userBrowsing = false

    /** 当前高亮的行下标，用于避免每 250ms 重复触发平滑滚动（会抖）。 */
    private var currentIndex = -1

    private var mediaId: String = ""
    private var loadingFor: String = ""
    private var pendingReturn: Runnable? = null

    private val ticker = object : Runnable {
        override fun run() {
            syncPosition()
            handler.postDelayed(this, 250)
        }
    }

    private val listener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: androidx.media3.common.MediaItem?, reason: Int) {
            // 切歌后旧歌词必须立刻失效，否则会短暂显示上一首的句子。
            currentIndex = -1
            adapter.submitList(emptyList())
            b.tvLyricTitle.text = mediaItem?.mediaMetadata?.title?.toString().orEmpty()
            loadLyricFor(mediaItem?.mediaId.orEmpty(), mediaItem?.mediaMetadata?.title?.toString().orEmpty(), mediaItem?.mediaMetadata?.artist?.toString().orEmpty())
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _b = FragmentLyricBinding.inflate(inflater, container, false)
        return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        // 关闭交给宿主（PlayerFragment）处理：它需要同时恢复底部控制区与顶栏。
        // 直接 remove(this) 会让宿主以为仍在歌词模式，控制区永远不回来。
        b.btnLyricClose.setOnClickListener {
            parentFragmentManager.setFragmentResult(RESULT_CLOSE, Bundle.EMPTY)
        }

        val lm = LinearLayoutManager(requireContext())
        b.rvLyric.layoutManager = lm
        b.rvLyric.adapter = adapter
        // 上下各留半屏 padding：让第一行和最后一行也能滚到正中。
        b.rvLyric.clipToPadding = false
        b.rvLyric.post {
            val half = (b.rvLyric.height - rowHeight()) / 2
            b.rvLyric.setPadding(0, half.coerceAtLeast(0), 0, half.coerceAtLeast(0))
        }

        attachDragWatcher()

        val c = PlayerHolder.controller
        b.tvLyricTitle.text = c?.currentMediaItem?.mediaMetadata?.title?.toString().orEmpty()
        loadLyricFor(
            c?.currentMediaItem?.mediaId.orEmpty(),
            c?.currentMediaItem?.mediaMetadata?.title?.toString().orEmpty(),
            c?.currentMediaItem?.mediaMetadata?.artist?.toString().orEmpty(),
        )
    }

    override fun onStart() {
        super.onStart()
        PlayerHolder.controller?.addListener(listener)
        handler.post(ticker)
    }

    override fun onStop() {
        handler.removeCallbacks(ticker)
        handler.removeCallbacksAndMessages(null)
        PlayerHolder.controller?.removeListener(listener)
        super.onStop()
    }

    override fun onDestroyView() {
        handler.removeCallbacksAndMessages(null)
        b.rvLyric.adapter = null
        _b = null
        super.onDestroyView()
    }

    // ------------------------------------------------------------ 数据

    private fun loadLyricFor(id: String, title: String, artist: String) {
        if (id.isBlank()) {
            showEmpty("未在播放")
            return
        }
        mediaId = id
        val cached = LyricStore.linesFor(id)
        if (cached.isNotEmpty()) {
            submit(cached)
            return
        }
        if (loadingFor == id) return
        loadingFor = id

        val source = id.substringBefore(':', "")
        val songId = id.substringAfter(':', "")
        if (source.isBlank() || songId.isBlank()) {
            showEmpty("该曲目暂无歌词")
            return
        }
        viewLifecycleOwner.lifecycleScope.launch {
            val song = Song(id = songId, name = title, artist = artist, source = source)
            val raw = runCatching { repo.lyric(song) }.getOrNull()?.data.orEmpty()
            val parsed = LrcParser.parse(raw)
            LyricStore.put(id, raw, parsed)
            if (_b == null || mediaId != id) return@launch
            if (parsed.isEmpty()) showEmpty(LyricStore.status.ifBlank { "该曲目暂无歌词" }) else submit(parsed)
        }
    }

    private fun submit(lines: List<LyricLine>) {
        if (_b == null) return
        b.tvLyricEmpty.visibility = View.GONE
        adapter.submitList(lines) {
            // 列表换了之后重新定位到当前行，避免停在旧下标。
            currentIndex = -1
            syncPosition()
        }
    }

    private fun showEmpty(text: String) {
        if (_b == null) return
        adapter.submitList(emptyList())
        b.tvLyricEmpty.text = text
        b.tvLyricEmpty.visibility = View.VISIBLE
    }

    // ------------------------------------------------------------ 跟随

    private fun syncPosition() {
        if (_b == null) return
        val c = PlayerHolder.controller ?: return
        val pos = c.currentPosition
        b.tvLyricTime.text = UiKit.mmss(pos.coerceAtLeast(0))

        val lines = adapter.currentList
        if (lines.isEmpty()) return
        val idx = indexAt(lines, pos)
        if (idx == currentIndex) return
        currentIndex = idx
        adapter.highlight(idx)
        if (!userBrowsing) scrollToCenter(idx)
    }

    /** 二分找「最后一个 timeMs <= pos」的行；pos 早于第一行时返回 0。 */
    private fun indexAt(lines: List<LyricLine>, pos: Long): Int {
        var lo = 0
        var hi = lines.lastIndex
        var hit = 0
        while (lo <= hi) {
            val mid = (lo + hi) / 2
            if (lines[mid].timeMs <= pos) {
                hit = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        return hit
    }

    private fun scrollToCenter(index: Int) {
        val lm = b.rvLyric.layoutManager as? LinearLayoutManager ?: return
        // 偏移必须是 0：列表本身已有「半屏 padding」（见 onViewCreated 的 setPadding），
        // 这里再加一次半屏会把当前行推到屏幕最底部（实测现象：高亮行贴着底部提示文字）。
        // scrollToPositionWithOffset 的 offset 相对 paddingTop 计算，0 即「顶部内边距处」= 视觉正中。
        lm.scrollToPositionWithOffset(index, 0)
    }

    private fun rowHeight(): Int = UiKit.dp(requireContext(), 56)

    // ------------------------------------------------------------ 交互

    private fun seekTo(line: LyricLine) {
        val c = PlayerHolder.controller ?: return
        c.seekTo(line.timeMs)
        // 点击后立刻回正，让用户看到自己跳到的位置。
        currentIndex = -1
        userBrowsing = false
        syncPosition()
    }

    /**
     * 拖拽检测。
     *
     * 用 addOnItemTouchListener 而不是 OnScrollListener 的原因：
     * 自动滚动本身也会触发 onScrollStateChanged(IDLE)，无法区分「手指松手」和
     * 「程序滚完了」。触摸事件能精确切出用户意图的边界。
     */
    private fun attachDragWatcher() {
        b.rvLyric.addOnItemTouchListener(object : RecyclerView.SimpleOnItemTouchListener() {
            override fun onInterceptTouchEvent(rv: RecyclerView, e: MotionEvent): Boolean {
                if (e.actionMasked == MotionEvent.ACTION_DOWN) {
                    userBrowsing = true
                    pendingReturn?.let { handler.removeCallbacks(it) }
                    pendingReturn = null
                }
                return false
            }
        })

        b.rvLyric.setOnTouchListener { _, e ->
            if (e.actionMasked == MotionEvent.ACTION_UP || e.actionMasked == MotionEvent.ACTION_CANCEL) {
                scheduleReturn()
            }
            false
        }
    }

    /** 松手 4 秒后恢复跟随；期间再次按下会取消（见 attachDragWatcher）。 */
    private fun scheduleReturn() {
        pendingReturn?.let { handler.removeCallbacks(it) }
        val r = Runnable {
            userBrowsing = false
            currentIndex = -1
            syncPosition()
        }
        pendingReturn = r
        handler.postDelayed(r, 4_000L)
    }

    // ------------------------------------------------------------ 列表

    companion object {
        /** 与 PlayerFragment 约定的关闭信号 key。 */
        const val RESULT_CLOSE = "close_lyric"
    }

    private class LyricAdapter(
        private val onClick: (LyricLine) -> Unit,
    ) : ListAdapter<LyricLine, LyricAdapter.VH>(DIFF) {

        private var activeIndex = -1

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
            VH(ItemLyricLineBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(getItem(position), position == activeIndex)

        /** 只刷新旧行和新行两个 item，避免整表重绑导致滚动条跳动。 */
        fun highlight(index: Int) {
            val previous = activeIndex
            activeIndex = index
            if (previous in 0 until itemCount) notifyItemChanged(previous)
            if (index in 0 until itemCount) notifyItemChanged(index)
        }

        inner class VH(private val b: ItemLyricLineBinding) : RecyclerView.ViewHolder(b.root) {
            fun bind(line: LyricLine, active: Boolean) {
                b.tvLyricText.text = line.text.ifBlank { "…" }
                if (active) {
                    b.tvLyricText.setTextColor(UiKit.color(b.root.context, R.color.brand))
                    b.tvLyricText.textSize = 17f
                    b.tvLyricText.alpha = 1f
                    b.tvLyricText.background = androidx.core.content.ContextCompat.getDrawable(b.root.context, R.drawable.bg_lyric_row_active)
                } else {
                    b.tvLyricText.setTextColor(UiKit.color(b.root.context, R.color.ink_500))
                    b.tvLyricText.textSize = 15f
                    b.tvLyricText.alpha = 0.75f
                    b.tvLyricText.background = null
                }
                b.root.setOnClickListener { onClick(line) }
            }
        }

        private companion object {
            val DIFF = object : DiffUtil.ItemCallback<LyricLine>() {
                override fun areItemsTheSame(a: LyricLine, b: LyricLine): Boolean = a.timeMs == b.timeMs
                override fun areContentsTheSame(a: LyricLine, b: LyricLine): Boolean = a == b
            }
        }
    }
}