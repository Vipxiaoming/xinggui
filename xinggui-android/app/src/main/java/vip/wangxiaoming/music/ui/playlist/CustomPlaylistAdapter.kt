package vip.wangxiaoming.music.ui.playlist

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import vip.wangxiaoming.music.data.local.entity.CustomPlaylist
import vip.wangxiaoming.music.databinding.ItemCustomPlaylistBinding
import vip.wangxiaoming.music.ui.common.CoverLoader

/**
 * 「我的歌单」列表（复用原版 item_custom_playlist）。
 *
 * 原版把「重命名 / 删除」两个按钮常驻在行尾，占掉 108dp 但默认看不见（内容层盖住）。
 * 这里改成明确的交互：单击进详情、长按滑出操作、点封面角标直接播放整单。
 * 操作展开用 translationX 而不是 visibility，保证滑出/收起有过渡且点击区域不重叠。
 */
class CustomPlaylistAdapter(
    private val onOpen: (CustomPlaylist) -> Unit,
    private val onPlay: (CustomPlaylist) -> Unit,
    private val onRename: (CustomPlaylist) -> Unit,
    private val onDelete: (CustomPlaylist) -> Unit,
) : RecyclerView.Adapter<CustomPlaylistAdapter.VH>() {

    data class Row(val playlist: CustomPlaylist, val songCount: Int)

    private val rows = mutableListOf<Row>()

    fun submit(list: List<Row>) {
        rows.clear()
        rows.addAll(list)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(ItemCustomPlaylistBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun getItemCount(): Int = rows.size

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(rows[position])

    inner class VH(private val b: ItemCustomPlaylistBinding) : RecyclerView.ViewHolder(b.root) {

        private val actionWidth: Float
            get() = b.llItemActions.width.toFloat().takeIf { it > 0f }
                ?: (108 * b.root.resources.displayMetrics.density)

        private var opened = false

        fun bind(row: Row) {
            val pl = row.playlist
            b.tvCustomPlaylistTitle.text = pl.name
            b.tvCustomPlaylistCount.text = row.songCount.toString() + " 首歌曲"
            CoverLoader.load(b.ivCustomPlaylistCover, pl.cover)
            closeActions(animate = false)

            b.llItemContent.setOnClickListener { onOpen(pl) }
            b.btnCustomPlaylistPlay.setOnClickListener { onPlay(pl) }
            b.llItemContent.setOnLongClickListener {
                if (opened) closeActions(true) else openActions()
                true
            }
            b.btnItemRename.setOnClickListener {
                closeActions(true)
                onRename(pl)
            }
            b.btnItemDelete.setOnClickListener {
                closeActions(true)
                onDelete(pl)
            }
        }

        private fun openActions() {
            opened = true
            val w = actionWidth
            b.llItemActions.animate().translationX(0f).setDuration(160).start()
            b.llItemContent.animate().translationX(-w).setDuration(160).start()
        }

        private fun closeActions(animate: Boolean) {
            opened = false
            val w = actionWidth
            b.llItemActions.translationX = w
            if (animate) {
                b.llItemContent.animate().translationX(0f).setDuration(160).start()
            } else {
                b.llItemContent.translationX = 0f
            }
        }
    }
}