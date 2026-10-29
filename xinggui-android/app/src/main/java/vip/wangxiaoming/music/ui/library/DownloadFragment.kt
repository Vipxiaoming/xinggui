package vip.wangxiaoming.music.ui.library

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import vip.wangxiaoming.music.data.local.entity.DownloadStatus
import vip.wangxiaoming.music.data.remote.MetadataRepository
import vip.wangxiaoming.music.data.remote.model.Song
import vip.wangxiaoming.music.databinding.FragmentLibraryBinding
import vip.wangxiaoming.music.di.AppContainer
import vip.wangxiaoming.music.download.DownloadEngine
import vip.wangxiaoming.music.download.LocalScanner
import vip.wangxiaoming.music.download.StoragePermission
import vip.wangxiaoming.music.ui.common.HostActions
import vip.wangxiaoming.music.ui.common.PlayLauncher
import vip.wangxiaoming.music.ui.common.PlayerHolder
import vip.wangxiaoming.music.ui.common.UiKit

/**
 * 下载 Tab。
 *
 * 面板有两种模式，共用同一个 RecyclerView：
 *  - SEARCH（默认）：输入关键词 → 四平台并发搜索 → 点结果入下载队列；
 *  - LOCAL：点「已下载歌曲」入口卡 → 列 MediaStore 里 Download/星轨/ 的真实文件 → 点即播。
 *
 * 为什么不用新的二级页：`ui/common/HostActions` 与 `MainActivity` 都不在 P7 写作用域内，
 * 新增导航出口会和并行阶段冲突。页内切换同样能满足「能点进去、看到真实数据」。
 *
 * 计数一律以 MediaStore 实际文件为准（不是 Room 行数）：用户从系统删了文件，
 * 这里必须跟着变，否则「已下载歌曲 3 首」点进去只有 2 首，属于典型的假数据。
 */
class DownloadFragment : Fragment() {

    private enum class Mode { SEARCH, LOCAL }

    private var _b: FragmentLibraryBinding? = null
    private val b get() = _b!!

    private val host: HostActions get() = requireActivity() as HostActions
    private val container: AppContainer get() = AppContainer.get()
    private val metadata = MetadataRepository()

    private var adapter: DownloadPickAdapter? = null
    private var mode = Mode.SEARCH
    private var lastKeyword = ""
    private var cachedLocal: List<Song> = emptyList()

    private var pendingSong: Song? = null

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        if (StoragePermission.granted(requireContext())) {
            pendingSong?.let { doDownload(it) }
            if (mode == Mode.LOCAL) loadLocal()
        } else {
            UiKit.toast(requireContext(), StoragePermission.denialHint(requireContext()))
        }
        pendingSong = null
    }

    override fun onAttach(context: android.content.Context) {
        super.onAttach(context)
        // 引擎懒初始化挂在这里：XingGuiApp 不在本阶段写作用域，无法在其 onCreate 里 init。
        // 副作用：冷启动后若从未进入下载 Tab，未完成的队列不会自动续跑（行仍是 QUEUED，
        // 进来后立即被调度）。已在报告「未完成项」中标注。
        runCatching { DownloadEngine.init(requireContext().applicationContext) }
        runCatching { DownloadEngine.get().resumeOnStart() }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _b = FragmentLibraryBinding.inflate(inflater, container, false)
        return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val page = b.libraryTabContent

        page.cardLibraryLocal.setOnClickListener { switchTo(Mode.LOCAL) }
        page.cardDownloadTasksManage.setOnClickListener { host.openDownloadHistory() }
        page.btnLibraryRefresh.setOnClickListener { refreshAll() }

        bindPickList()
        bindSearchRow()
        bindCounts()
        switchTo(Mode.SEARCH)
    }

    override fun onDestroyView() {
        adapter = null
        _b = null
        super.onDestroyView()
    }

    // ------------------------------------------------------------ 面板

    private fun bindPickList() {
        val a = DownloadPickAdapter { song, _ ->
            when (mode) {
                Mode.SEARCH -> requestDownload(song)
                Mode.LOCAL -> playLocal(song)
            }
        }
        adapter = a
        b.rvDownloadPick.layoutManager = LinearLayoutManager(requireContext())
        b.rvDownloadPick.adapter = a
    }

    private fun bindSearchRow() {
        b.btnDownloadSearch.setOnClickListener { search(b.etDownloadQuery.text.toString()) }
        b.etDownloadQuery.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                search(b.etDownloadQuery.text.toString())
                true
            } else {
                false
            }
        }
    }

    private fun switchTo(target: Mode) {
        mode = target
        val searchVisible = target == Mode.SEARCH
        b.etDownloadQuery.visibility = if (searchVisible) View.VISIBLE else View.GONE
        b.btnDownloadSearch.visibility = if (searchVisible) View.VISIBLE else View.GONE

        when (target) {
            Mode.SEARCH -> {
                b.tvDownloadPanelHint.text = "下载到 Download/星轨/"
                adapter?.submitList(if (lastKeyword.isBlank()) emptyList() else lastResults)
                b.tvDownloadPickEmpty.text = "输入关键词搜索，点结果直接下载"
                b.tvDownloadPickEmpty.visibility =
                    if (lastResults.isEmpty()) View.VISIBLE else View.GONE
            }
            Mode.LOCAL -> loadLocal()
        }
    }

    private var lastResults: List<Song> = emptyList()

    private fun search(keyword: String) {
        val kw = keyword.trim()
        if (kw.isEmpty()) {
            UiKit.toast(requireContext(), "请输入歌名或歌手")
            return
        }
        lastKeyword = kw
        b.tvDownloadPickEmpty.text = "搜索中…"
        b.tvDownloadPickEmpty.visibility = View.VISIBLE
        b.btnDownloadSearch.isEnabled = false
        viewLifecycleOwner.lifecycleScope.launch {
            val r = metadata.searchAll(kw, limitPerPlatform = 12)
            if (_b == null) return@launch
            lastResults = r.data
            adapter?.submitList(r.data)
            b.btnDownloadSearch.isEnabled = true
            b.tvDownloadPickEmpty.text = "没有结果，换个关键词试试"
            b.tvDownloadPickEmpty.visibility =
                if (r.data.isEmpty()) View.VISIBLE else View.GONE
            b.tvDownloadPanelHint.text =
                "四家 " + r.details.count { it.ok } + "/4 有结果 · Download/星轨/"
        }
    }

    /** 本地列表：真实读 MediaStore，不用缓存糊弄。 */
    private fun loadLocal() {
        val ctx = context ?: return
        if (!StoragePermission.granted(ctx)) {
            b.etDownloadQuery.visibility = View.GONE
            b.btnDownloadSearch.visibility = View.GONE
            b.tvDownloadPanelHint.text = "未授权"
            b.tvDownloadPickEmpty.text = StoragePermission.denialHint(ctx)
            b.tvDownloadPickEmpty.visibility = View.VISIBLE
            adapter?.submitList(emptyList())
            pendingSong = null
            permissionLauncher.launch(StoragePermission.required(ctx))
            return
        }
        b.tvDownloadPanelHint.text = "Download/星轨/"
        b.tvDownloadPickEmpty.text = "加载中…"
        b.tvDownloadPickEmpty.visibility = View.VISIBLE
        viewLifecycleOwner.lifecycleScope.launch {
            val scanned = withContext(Dispatchers.IO) { LocalScanner(ctx.applicationContext).scan() }
            if (_b == null) return@launch
            cachedLocal = scanned.songs
            adapter?.submitList(scanned.songs)
            val err = scanned.error
            b.tvDownloadPickEmpty.text = when {
                err != null -> "读取失败：" + err
                scanned.songs.isEmpty() -> "还没有下载的歌曲"
                else -> ""
            }
            b.tvDownloadPickEmpty.visibility =
                if (scanned.songs.isEmpty()) View.VISIBLE else View.GONE
            b.tvDownloadPanelHint.text = "本地 " + scanned.songs.size + " 首"
        }
    }

    private fun playLocal(song: Song) {
        val c = PlayerHolder.controller
        if (c == null) {
            UiKit.toast(requireContext(), "播放器未就绪")
            return
        }
        viewLifecycleOwner.lifecycleScope.launch {
            val n = PlayLauncher.play(c, listOf(song), 0)
            if (n == 0) UiKit.toast(requireContext(), "无法播放该文件")
            else host.openPlayer()
        }
    }

    // ------------------------------------------------------------ 下载

    private fun requestDownload(song: Song) {
        val ctx = context ?: return
        if (!StoragePermission.granted(ctx)) {
            // 写入本身在 API 29+ 不需要权限；但为了让「已下载」能立刻被看到，仍先要读权限
            pendingSong = song
            permissionLauncher.launch(StoragePermission.required(ctx))
            return
        }
        doDownload(song)
    }

    private fun doDownload(song: Song) {
        viewLifecycleOwner.lifecycleScope.launch {
            val task = DownloadEngine.get().enqueue(song)
            UiKit.toast(
                requireContext(),
                if (task == null) "已在列表或已下载：" + song.name else "已加入下载：" + song.name,
            )
        }
    }

    // ------------------------------------------------------------ 计数

    private fun bindCounts() {
        val page = b.libraryTabContent
        viewLifecycleOwner.lifecycleScope.launch {
            container.downloadRepository.observeAll().collectLatest { tasks ->
                val running = tasks.count {
                    it.status == DownloadStatus.RUNNING || it.status == DownloadStatus.QUEUED
                }
                val failed = tasks.count { it.status == DownloadStatus.FAILED }
                page.tvDownloadTasksStatus.text = when {
                    tasks.isEmpty() -> "暂无任务"
                    else -> "共 " + tasks.size + " 个 · 进行中 " + running + " · 失败 " + failed
                }
                refreshLocalCount()
            }
        }
    }

    private suspend fun refreshLocalCount() {
        val ctx = context?.applicationContext ?: return
        val count = if (StoragePermission.granted(ctx)) {
            withContext(Dispatchers.IO) { runCatching { LocalScanner(ctx).scan().songs.size }.getOrNull() }
        } else {
            null
        }
        val page = _b?.libraryTabContent ?: return
        page.tvLibraryLocalCount.text = when (count) {
            null -> "未授权读取"
            else -> count.toString() + " 首歌曲"
        }
    }

    private fun refreshAll() {
        viewLifecycleOwner.lifecycleScope.launch {
            val tasks = container.downloadRepository.all()
            val ctx = context?.applicationContext
            val local = if (ctx != null && StoragePermission.granted(ctx)) {
                withContext(Dispatchers.IO) { LocalScanner(ctx).scan() }
            } else {
                null
            }
            val msg = if (local == null) {
                "任务 " + tasks.size + " 条 · 未授权，无法扫描本地"
            } else {
                "任务 " + tasks.size + " 条 · 本地 " + local.songs.size + " 首"
            }
            UiKit.toast(requireContext(), msg)
            if (mode == Mode.LOCAL) loadLocal()
        }
    }
}