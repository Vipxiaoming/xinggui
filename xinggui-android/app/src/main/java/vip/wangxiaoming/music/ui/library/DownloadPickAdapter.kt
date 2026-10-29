package vip.wangxiaoming.music.ui.library

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import vip.wangxiaoming.music.R
import vip.wangxiaoming.music.data.remote.model.Song
import vip.wangxiaoming.music.databinding.ItemSongBinding
import vip.wangxiaoming.music.ui.common.CoverLoader
import vip.wangxiaoming.music.ui.common.UiKit

/**
 * 「搜索并下载」结果行。复用原版 item_song 布局，但把 btnDownload 显出来——
 * [vip.wangxiaoming.music.ui.common.SongAdapter] 把它固定为 GONE（那个类不在本阶段写作用域），
 * 所以这里单独做一个只服务下载场景的适配器，行为差异只在于点击语义。
 *
 * 点击行 = 下载；点击右侧下载图标 = 下载（两个热区，避免误触播放）。
 */
class DownloadPickAdapter(
    private val onPick: (Song, Int) -> Unit,
) : ListAdapter<Song, DownloadPickAdapter.VH>(DIFF) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(ItemSongBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(getItem(position))

    inner class VH(private val b: ItemSongBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind(song: Song) {
            b.tvTitle.text = song.name.ifBlank { "未知曲目" }
            b.tvArtist.text = buildString {
                append(song.artist.ifBlank { "未知歌手" })
                if (song.duration.isNotBlank()) append(" · ").append(song.duration)
            }
            b.tvSource.text = UiKit.sourceLabel(song.source)
            b.tvSource.visibility = if (song.source.isBlank()) View.GONE else View.VISIBLE
            CoverLoader.load(b.ivCover, song.cover)

            b.tvIndex.visibility = View.GONE
            b.ivSelectCheck.visibility = View.GONE
            b.tvSongQualityTag.visibility = View.GONE
            b.tvSongSize.visibility = View.GONE
            b.btnLike.visibility = View.GONE

            b.btnDownload.visibility = View.VISIBLE
            b.btnDownload.setOnClickListener { onPick(song, bindingAdapterPosition) }
            b.root.setOnClickListener { onPick(song, bindingAdapterPosition) }
        }
    }

    private companion object {
        val DIFF = object : DiffUtil.ItemCallback<Song>() {
            override fun areItemsTheSame(a: Song, b: Song) = a.source == b.source && a.id == b.id
            override fun areContentsTheSame(a: Song, b: Song) = a == b
        }
    }
}