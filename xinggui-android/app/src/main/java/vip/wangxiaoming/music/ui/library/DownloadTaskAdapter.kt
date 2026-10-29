package vip.wangxiaoming.music.ui.library

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import vip.wangxiaoming.music.R
import vip.wangxiaoming.music.data.local.entity.DownloadStatus
import vip.wangxiaoming.music.data.local.entity.DownloadTask
import vip.wangxiaoming.music.databinding.ItemDownloadTaskBinding
import vip.wangxiaoming.music.download.DownloadEngine
import vip.wangxiaoming.music.ui.common.CoverLoader
import vip.wangxiaoming.music.ui.common.UiKit

/**
 * 下载任务行（复用原版 item_download_task）。
 *
 * 进度：P3 的表没有百分比列（data 目录 不在写作用域），所以百分比来自
 * [DownloadEngine.progress] 的内存 StateFlow，由 Fragment 通过 [setProgress] 注入。
 * 拿不到字节总量时（服务端未给 Content-Length）退回不确定态，绝不画假百分比。
 *
 * 操作：点行 → 已完成则提示路径，进行中则暂停/继续，失败则重试。
 */
class DownloadTaskAdapter(
    private val onDelete: (DownloadTask) -> Unit,
    private val onToggle: (DownloadTask) -> Unit,
) : ListAdapter<DownloadTask, DownloadTaskAdapter.VH>(DIFF) {

    /** songId -> 进度快照。整个 Map 替换后 notifyDataSetChanged 代价可接受（列表通常 <50 行）。 */
    private var progressMap: Map<String, DownloadEngine.Progress> = emptyMap()

    fun setProgress(map: Map<String, DownloadEngine.Progress>) {
        if (map == progressMap) return
        progressMap = map
        // 只刷新进度相关视图，避免整行重绑导致封面闪烁
        notifyItemRangeChanged(0, itemCount, PAYLOAD_PROGRESS)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(ItemDownloadTaskBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(getItem(position))

    override fun onBindViewHolder(holder: VH, position: Int, payloads: MutableList<Any>) {
        if (payloads.contains(PAYLOAD_PROGRESS)) holder.bindProgress(getItem(position))
        else super.onBindViewHolder(holder, position, payloads)
    }

    inner class VH(private val b: ItemDownloadTaskBinding) : RecyclerView.ViewHolder(b.root) {

        fun bind(task: DownloadTask) {
            b.tvTitle.text = task.name.ifBlank { "未知曲目" }
            b.tvArtist.text = task.artist.ifBlank { "未知歌手" }
            CoverLoader.load(b.ivCover, task.cover)
            bindProgress(task)

            b.btnDeleteTask.setOnClickListener { onDelete(task) }
            b.root.setOnClickListener { onToggle(task) }
        }

        fun bindProgress(task: DownloadTask) {
            val (label, colorRes) = when {
                task.status == DownloadStatus.SUCCESS -> "已完成" to R.color.success
                task.status == DownloadStatus.FAILED -> {
                    val why = task.errorMessage?.takeIf { it.isNotBlank() }
                    (if (why == null) "失败 · 点此重试" else "失败：" + why) to R.color.danger
                }
                DownloadEngine.peek()?.isPaused(task.songId) == true -> "已暂停 · 点此继续" to R.color.ink_500
                task.status == DownloadStatus.RUNNING -> {
                    val p = progressMap[task.songId]
                    when {
                        p == null -> "下载中" to R.color.brand
                        p.indeterminate -> "下载中 " + fmt(p.bytes) to R.color.brand
                        else -> "下载中 " + p.percent + "%" to R.color.brand
                    }
                }
                else -> "排队中" to R.color.ink_500
            }
            b.tvTaskStatus.text = label
            b.tvTaskStatus.setTextColor(UiKit.color(b.root.context, colorRes))

            val p = progressMap[task.songId]
            val downloading = task.status == DownloadStatus.RUNNING
            when {
                downloading && p != null && !p.indeterminate -> {
                    b.pbDownloadTask.visibility = View.VISIBLE
                    b.pbDownloadTask.isIndeterminate = false
                    b.pbDownloadTask.progress = p.percent
                }
                downloading -> {
                    b.pbDownloadTask.visibility = View.VISIBLE
                    b.pbDownloadTask.isIndeterminate = true
                }
                else -> b.pbDownloadTask.visibility = View.GONE
            }
        }
    }

    private fun fmt(bytes: Long): String = when {
        bytes >= 1024 * 1024 -> String.format("%.1f MB", bytes / 1024.0 / 1024.0)
        bytes >= 1024 -> String.format("%.0f KB", bytes / 1024.0)
        else -> bytes.toString() + " B"
    }

    private companion object {
        const val PAYLOAD_PROGRESS = "progress"

        val DIFF = object : DiffUtil.ItemCallback<DownloadTask>() {
            override fun areItemsTheSame(a: DownloadTask, b: DownloadTask) = a.songId == b.songId
            override fun areContentsTheSame(a: DownloadTask, b: DownloadTask) = a == b
        }
    }
}