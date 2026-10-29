package vip.wangxiaoming.music.ui.common

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import vip.wangxiaoming.music.data.remote.model.Playlist
import vip.wangxiaoming.music.databinding.ItemHotPlaylistBinding
import vip.wangxiaoming.music.databinding.ItemSearchPlaylistCardBinding

/**
 * 首页「热门歌单」横滑条（复用原版 item_hot_playlist：120dp 封面 + 播放量角标 + 两行标题）。
 * 不做 DiffUtil：条目最多 10 条，一次 submit 整体替换即可。
 */
class PlaylistStripAdapter(
    private val onClick: (Playlist) -> Unit,
) : RecyclerView.Adapter<PlaylistStripAdapter.VH>() {

    private val items = mutableListOf<Playlist>()

    fun submit(list: List<Playlist>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(ItemHotPlaylistBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(items[position])

    inner class VH(private val b: ItemHotPlaylistBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind(pl: Playlist) {
            b.tvPlaylistTitle.text = pl.name.ifBlank { "未命名歌单" }
            b.tvPlayCount.text = if (pl.playCount > 0) UiKit.playCount(pl.playCount) else
                (if (pl.trackCount > 0) pl.trackCount.toString() + "首" else "")
            CoverLoader.load(b.ivPlaylistCover, pl.coverUrl)
            b.root.setOnClickListener { onClick(pl) }
        }
    }
}

/**
 * 搜索页「精选歌单」横滑条（复用原版 item_search_playlist_card）。
 * 与上者的差别只在布局：这张卡是 100dp 封面 + 单行标题 + 曲目数。
 */
class SearchPlaylistAdapter(
    private val onClick: (Playlist) -> Unit,
) : RecyclerView.Adapter<SearchPlaylistAdapter.VH>() {

    private val items = mutableListOf<Playlist>()

    fun submit(list: List<Playlist>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(ItemSearchPlaylistCardBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(items[position])

    inner class VH(private val b: ItemSearchPlaylistCardBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind(pl: Playlist) {
            b.tvSearchPlaylistTitle.text = pl.name.ifBlank { "未命名歌单" }
            b.tvSearchPlaylistCount.text = if (pl.trackCount > 0) pl.trackCount.toString() + "首" else ""
            CoverLoader.load(b.ivSearchPlaylistCover, pl.coverUrl)
            b.root.setOnClickListener { onClick(pl) }
        }
    }
}