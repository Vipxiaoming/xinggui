package vip.wangxiaoming.music.ui.library

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import vip.wangxiaoming.music.R
import vip.wangxiaoming.music.data.local.entity.DownloadStatus
import vip.wangxiaoming.music.data.local.entity.DownloadTask
import vip.wangxiaoming.music.databinding.FragmentDownloadHistoryBinding
import vip.wangxiaoming.music.di.AppContainer
import vip.wangxiaoming.music.download.DownloadEngine
import vip.wangxiaoming.music.ui.common.HostActions
import vip.wangxiaoming.music.ui.common.UiKit

/**
 * 下载历史 / 任务管理。
 *
 * 4 个筛选 tab 与原版一致（全部 / 下载中 / 成功 / 失败）。计数直接从 Room 的 Flow 算，
 * 不在 UI 里另存一份 —— 原版就是因为 UI 自管计数，删任务后角标不刷新。
 *
 * 进度条订阅 [DownloadEngine.progress]（内存），任务行本身仍来自 Room。
 * 两种数据源分开：状态需要持久化（杀进程后还在），百分比不需要（重启即从 0 重下）。
 */
class DownloadHistoryFragment : Fragment() {

    private var _b: FragmentDownloadHistoryBinding? = null
    private val b get() = _b!!

    private var adapter: DownloadTaskAdapter? = null
    private var allTasks: List<DownloadTask> = emptyList()
    private var filter = FILTER_ALL

    private val host: HostActions get() = requireActivity() as HostActions
    private val container: AppContainer get() = AppContainer.get()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _b = FragmentDownloadHistoryBinding.inflate(inflater, container, false)
        return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val page = b.downloadHistoryContent
        val a = DownloadTaskAdapter(
            onDelete = { task -> confirmDelete(task) },
            onToggle = { task -> toggle(task) },
        )
        adapter = a
        page.rvDownloadTasks.layoutManager = LinearLayoutManager(requireContext())
        page.rvDownloadTasks.adapter = a

        page.btnBackFromDownload.visibility = View.VISIBLE
        page.btnBackFromDownload.setOnClickListener { host.closeSecondary() }

        page.tabFilterAll.setOnClickListener { setFilter(FILTER_ALL) }
        page.tabFilterDownloading.setOnClickListener { setFilter(FILTER_RUNNING) }
        page.tabFilterSuccess.setOnClickListener { setFilter(FILTER_SUCCESS) }
        page.tabFilterFailed.setOnClickListener { setFilter(FILTER_FAILED) }

        page.btnClearDownloadHistory.setOnClickListener { confirmClearFinished() }

        viewLifecycleOwner.lifecycleScope.launch {
            container.downloadRepository.observeAll().collectLatest { list ->
                allTasks = list
                render()
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            DownloadEngine.peek()?.progress?.collectLatest { map ->
                adapter?.setProgress(map)
                render()
            }
        }
    }

    override fun onDestroyView() {
        adapter = null
        allTasks = emptyList()
        _b = null
        super.onDestroyView()
    }

    private fun setFilter(which: Int) {
        filter = which
        render()
    }

    private fun render() {
        val page = b.downloadHistoryContent
        val running = allTasks.count {
            it.status == DownloadStatus.RUNNING || it.status == DownloadStatus.QUEUED
        }
        val success = allTasks.count { it.status == DownloadStatus.SUCCESS }
        val failed = allTasks.count { it.status == DownloadStatus.FAILED }

        page.tabFilterAll.text = "全部(" + allTasks.size + ")"
        page.tabFilterDownloading.text = "下载中(" + running + ")"
        page.tabFilterSuccess.text = "成功(" + success + ")"
        page.tabFilterFailed.text = "失败(" + failed + ")"

        val brand = UiKit.color(requireContext(), R.color.brand)
        val muted = UiKit.color(requireContext(), R.color.ink_500)
        page.tabFilterAll.setTextColor(if (filter == FILTER_ALL) brand else muted)
        page.tabFilterDownloading.setTextColor(if (filter == FILTER_RUNNING) brand else muted)
        page.tabFilterSuccess.setTextColor(if (filter == FILTER_SUCCESS) brand else muted)
        page.tabFilterFailed.setTextColor(if (filter == FILTER_FAILED) brand else muted)

        val shown = when (filter) {
            FILTER_RUNNING -> allTasks.filter {
                it.status == DownloadStatus.RUNNING || it.status == DownloadStatus.QUEUED
            }
            FILTER_SUCCESS -> allTasks.filter { it.status == DownloadStatus.SUCCESS }
            FILTER_FAILED -> allTasks.filter { it.status == DownloadStatus.FAILED }
            else -> allTasks
        }
        adapter?.submitList(shown)
        page.tvDownloadEmpty.visibility = if (shown.isEmpty()) View.VISIBLE else View.GONE
    }

    /** 点行：失败→重试；进行中→暂停/继续；成功→提示落盘路径。 */
    private fun toggle(task: DownloadTask) {
        val engine = DownloadEngine.peek() ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            when {
                task.status == DownloadStatus.FAILED -> {
                    engine.retry(task.songId)
                    UiKit.toast(requireContext(), "已重新排队")
                }
                task.status == DownloadStatus.SUCCESS -> {
                    UiKit.toast(requireContext(), task.localFilePath ?: "已完成")
                }
                engine.isPaused(task.songId) -> {
                    engine.resume(task.songId)
                    UiKit.toast(requireContext(), "已继续")
                }
                else -> {
                    engine.pause(task.songId)
                    UiKit.toast(requireContext(), "已暂停")
                }
            }
            render()
        }
    }

    private fun confirmDelete(task: DownloadTask) {
        val hint = if (task.status == DownloadStatus.SUCCESS) {
            "只删除这条记录，已落盘的文件不会被删除。"
        } else {
            "将中断该任务并删除记录。"
        }
        AlertDialog.Builder(requireContext())
            .setTitle("移除任务")
            .setMessage(hint)
            .setPositiveButton("移除") { _, _ ->
                viewLifecycleOwner.lifecycleScope.launch {
                    DownloadEngine.peek()?.cancel(task.songId)
                        ?: container.downloadRepository.remove(task.songId)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun confirmClearFinished() {
        val finished = allTasks.count {
            it.status == DownloadStatus.SUCCESS || it.status == DownloadStatus.FAILED
        }
        if (finished == 0) {
            UiKit.toast(requireContext(), "没有可清理的已完成任务")
            return
        }
        AlertDialog.Builder(requireContext())
            .setTitle("清空已完成记录")
            .setMessage("将删除 " + finished + " 条成功/失败记录，已落盘文件保留。")
            .setPositiveButton("清空") { _, _ ->
                viewLifecycleOwner.lifecycleScope.launch {
                    container.downloadRepository.clearFinished()
                    UiKit.toast(requireContext(), "已清理")
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private companion object {
        const val FILTER_ALL = 0
        const val FILTER_RUNNING = 1
        const val FILTER_SUCCESS = 2
        const val FILTER_FAILED = 3
    }
}