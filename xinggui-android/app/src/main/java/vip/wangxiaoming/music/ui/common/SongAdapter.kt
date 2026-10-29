package vip.wangxiaoming.music.ui.common

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import vip.wangxiaoming.music.R
import vip.wangxiaoming.music.data.remote.model.Song
import vip.wangxiaoming.music.databinding.ItemSongBinding

/**
 * 通用单曲列表（复用原版 item_song 布局）。
 *
 * 原版 item_song 有「选择框 / 序号 / 品质 / 大小 / 收藏 / 下载」六个可选部件；
 * 默认只启用封面、歌名+来源标签、歌手+时长，其余保持 gone，不做「显示但点了没反应」的假按钮。
 *
 * 收藏（P6 遗留功能接线）：传入 [onToggleLike] 与 [scope] 后心形按钮才显示，
 * 状态来自 Room 的 Flow（不是本地布尔），所以在播放器里取消收藏、列表里也会立刻变灰。
 * 未传时按钮继续 gone —— 下载选择页等场景不需要收藏入口。
 */
class SongAdapter(
    private val onClick: (Song, Int) -> Unit,
    private val onToggleLike: ((Song) -> Unit)? = null,
    private val scope: CoroutineScope? = null,
) : ListAdapter<Song, SongAdapter.VH>(DIFF) {

    private val favorites = FavoritesRepository()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(ItemSongBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(getItem(position))

    override fun onViewRecycled(holder: VH) {
        holder.stopObserving()
        super.onViewRecycled(holder)
    }

    inner class VH(private val b: ItemSongBinding) : RecyclerView.ViewHolder(b.root) {

        private var likeJob: Job? = null

        fun stopObserving() {
            likeJob?.cancel()
            likeJob = null
        }

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
            b.btnDownload.visibility = View.GONE
            b.root.setOnClickListener { onClick(song, bindingAdapterPosition) }

            stopObserving()
            val toggle = onToggleLike
            val s = scope
            if (toggle == null || s == null || song.id.isBlank()) {
                b.btnLike.visibility = View.GONE
                b.btnLike.setOnClickListener(null)
                return
            }

            b.btnLike.visibility = View.VISIBLE
            b.btnLike.setOnClickListener { toggle(song) }
            b.btnLike.contentDescription = "收藏"

            val mediaId = FavoritesRepository.mediaIdOf(song)
            likeJob = s.launch {
                favorites.observeIsFavorite(mediaId).collect { liked -> renderLike(liked) }
            }
        }

        private fun renderLike(liked: Boolean) {
            val ctx = b.root.context
            b.btnLike.setImageResource(if (liked) R.drawable.ic_vinyl_liked else R.drawable.ic_heart_line)
            b.btnLike.setColorFilter(
                ContextCompat.getColor(ctx, if (liked) R.color.danger else R.color.ink_400),
            )
        }
    }

    private companion object {
        val DIFF = object : DiffUtil.ItemCallback<Song>() {
            override fun areItemsTheSame(a: Song, b: Song): Boolean =
                a.source == b.source && a.id == b.id

            override fun areContentsTheSame(a: Song, b: Song): Boolean = a == b
        }
    }
}
