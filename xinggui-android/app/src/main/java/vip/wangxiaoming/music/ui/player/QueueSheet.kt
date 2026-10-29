package vip.wangxiaoming.music.ui.player

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import vip.wangxiaoming.music.R
import vip.wangxiaoming.music.databinding.DialogBottomPlaylistBinding
import vip.wangxiaoming.music.databinding.ItemQueueSongBinding
import vip.wangxiaoming.music.playback.PlayMode
import vip.wangxiaoming.music.playback.PlayerConnection
import vip.wangxiaoming.music.ui.common.CoverLoader
import vip.wangxiaoming.music.ui.common.PlayerHolder
import vip.wangxiaoming.music.ui.common.UiKit

/**
 * 播放队列面板（P6 遗留功能）。
 *
 * 复用原版 [R.layout.dialog_bottom_playlist]：它的头部本来就带「模式图标 + 模式名」和
 * 清空按钮，正好对应原版队列弹窗的两个入口。列表项换成 [R.layout.item_queue_song]，
 * 因为原 item 没有序号与时长，而队列面板最常被问的两个问题就是「第几首」和「还剩多久」。
 *
 * 交互：
 *  - 点击行 → seekTo(index)，并保持当前播放状态（不自动 play）；
 *  - 左滑删除 → 调 MediaController.removeMediaItem；正在播的那首被删时
 *    ExoPlayer 会自动切到下一条，这里只需要刷新列表；
 *  - 清空 → clearMediaItems()，随后关闭面板并提示。
 *
 * 数据不落本地：队列的唯一真源是 MediaController，面板只是它的一个视图。
 * 这样从通知、锁屏、车机改动队列后，面板打开即是当前状态，不需要额外同步。
 */
object QueueSheet {

    fun show(context: Context) {
        val controller = PlayerHolder.controller ?: run {
            UiKit.toast(context, "播放器未连接")
            return
        }

        val b = DialogBottomPlaylistBinding.inflate(LayoutInflater.from(context))
        val dialog = BottomSheetDialog(context, R.style.AuroraBottomSheet)
        dialog.setContentView(b.root)

        val adapter = QueueAdapter(controller) { position ->
            controller.seekTo(position, 0L)
        }
        b.rvPlaylistDialog.layoutManager = LinearLayoutManager(context)
        b.rvPlaylistDialog.adapter = adapter

        // 左滑删除：SWIPE_START 在 RTL 下是右侧，用 LEFT 明确表达「左滑」。
        val swipe = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.LEFT) {
            override fun onMove(rv: RecyclerView, vh: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder) = false

            override fun onSwiped(vh: RecyclerView.ViewHolder, direction: Int) {
                val pos = vh.bindingAdapterPosition
                if (pos == RecyclerView.NO_POSITION) return
                if (controller.mediaItemCount <= 1) {
                    // 删掉最后一首会让播放器进入空态，用户通常会以为是 bug，直接拒绝。
                    adapter.notifyItemChanged(pos)
                    UiKit.toast(context, "队列至少保留一首")
                    return
                }
                controller.removeMediaItem(pos)
                adapter.refresh()
                renderHeader(b, controller)
            }
        })
        swipe.attachToRecyclerView(b.rvPlaylistDialog)

        b.btnClearAll.setOnClickListener {
            controller.clearMediaItems()
            dialog.dismiss()
            UiKit.toast(context, "已清空播放队列")
        }

        b.btnPlayModeHeader.setOnClickListener {
            val order = PlayMode.ordered
            val next = order[(order.indexOf(PlayMode.fromPlayer(controller)) + 1) % order.size]
            PlayMode.applyTo(controller, next)
            renderHeader(b, controller)
            b.ivPlayModeIcon.setImageResource(PlayerConnection.modeIconRes(next))
        }

        renderHeader(b, controller)
        adapter.refresh()

        // 面板打开期间监听队列变化：切歌、通知栏上一首/下一首都要实时反映。
        val listener = object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) {
                if (events.containsAny(
                        Player.EVENT_MEDIA_ITEM_TRANSITION,
                        Player.EVENT_TIMELINE_CHANGED,
                        Player.EVENT_MEDIA_METADATA_CHANGED,
                        Player.EVENT_PLAYBACK_STATE_CHANGED,
                    )
                ) {
                    adapter.refresh()
                    renderHeader(b, controller)
                }
            }
        }
        controller.addListener(listener)
        dialog.setOnDismissListener { controller.removeListener(listener) }

        dialog.show()
        // 展开到半屏：队列通常 10-30 首，半屏比 wrap_content 更好用。
        dialog.behavior.peekHeight = (context.resources.displayMetrics.heightPixels * 0.6f).toInt()
        dialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED
    }

    private fun renderHeader(b: DialogBottomPlaylistBinding, c: MediaController) {
        val mode = PlayMode.fromPlayer(c)
        b.tvPlayModeTitle.text = when (mode) {
            PlayMode.REPEAT_ONE -> "单曲循环 (" + c.mediaItemCount + ")"
            PlayMode.SHUFFLE -> "随机播放 (" + c.mediaItemCount + ")"
            else -> "顺序播放 (" + c.mediaItemCount + ")"
        }
        b.ivPlayModeIcon.setImageResource(PlayerConnection.modeIconRes(mode))
    }

    /** 队列行模型：MediaItem 本身不可比对，抽出 UI 需要的四个字段。 */
    private data class Row(
        val index: Int,
        val mediaId: String,
        val title: String,
        val artist: String,
        val cover: String?,
        val durationMs: Long,
        val active: Boolean,
    )

    private class QueueAdapter(
        private val controller: MediaController,
        private val onClick: (Int) -> Unit,
    ) : ListAdapter<Row, QueueAdapter.VH>(DIFF) {

        fun refresh() {
            val current = controller.currentMediaItemIndex
            val rows = (0 until controller.mediaItemCount).map { i ->
                val item = controller.getMediaItemAt(i)
                val meta = item.mediaMetadata
                Row(
                    index = i,
                    mediaId = item.mediaId,
                    title = meta.title?.toString().orEmpty().ifBlank { "未知曲目" },
                    artist = meta.artist?.toString().orEmpty().ifBlank { "未知歌手" },
                    cover = meta.artworkUri?.toString(),
                    durationMs = meta.durationMs ?: 0L,
                    active = i == current,
                )
            }
            submitList(rows)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
            VH(ItemQueueSongBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(getItem(position))

        inner class VH(private val b: ItemQueueSongBinding) : RecyclerView.ViewHolder(b.root) {
            fun bind(row: Row) {
                b.tvQueueIndex.text = (row.index + 1).toString()
                b.tvQueueIndex.visibility = if (row.active) View.GONE else View.VISIBLE
                b.ivQueuePlaying.visibility = if (row.active) View.VISIBLE else View.GONE
                b.tvQueueTitle.text = row.title
                b.tvQueueArtist.text = row.artist
                // 时长优先用 MediaMetadata；解析不到的条目留空而不是显示 00:00 假数据。
                b.tvQueueDuration.text =
                    if (row.durationMs > 0) UiKit.mmss(row.durationMs) else ""
                CoverLoader.load(b.ivQueueCover, row.cover)

                val ctx = b.root.context
                b.tvQueueTitle.setTextColor(
                    ContextCompat.getColor(
                        ctx,
                        if (row.active) R.color.brand else R.color.ink_900,
                    ),
                )
                b.root.setOnClickListener { onClick(bindingAdapterPosition) }
                b.btnQueueRemove.setOnClickListener {
                    val pos = bindingAdapterPosition
                    if (pos == RecyclerView.NO_POSITION) return@setOnClickListener
                    if (controller.mediaItemCount <= 1) {
                        UiKit.toast(ctx, "队列至少保留一首")
                    } else {
                        controller.removeMediaItem(pos)
                        refresh()
                    }
                }
            }
        }

        private companion object {
            val DIFF = object : DiffUtil.ItemCallback<Row>() {
                override fun areItemsTheSame(a: Row, b: Row): Boolean = a.mediaId == b.mediaId && a.index == b.index
                override fun areContentsTheSame(a: Row, b: Row): Boolean = a == b
            }
        }
    }
}