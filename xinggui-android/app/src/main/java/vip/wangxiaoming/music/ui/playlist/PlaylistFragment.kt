package vip.wangxiaoming.music.ui.playlist

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import androidx.appcompat.app.AlertDialog
import androidx.core.content.getSystemService
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import vip.wangxiaoming.music.R
import vip.wangxiaoming.music.data.local.entity.CustomPlaylist
import vip.wangxiaoming.music.data.local.entity.PlaylistSong
import vip.wangxiaoming.music.data.remote.MetadataRepository
import vip.wangxiaoming.music.data.remote.model.Playlist
import vip.wangxiaoming.music.data.remote.model.Song
import vip.wangxiaoming.music.databinding.FragmentPlaylistBinding
import vip.wangxiaoming.music.di.AppContainer
import vip.wangxiaoming.music.ui.common.CoverLoader
import vip.wangxiaoming.music.ui.common.HostActions
import vip.wangxiaoming.music.ui.common.PlayLauncher
import vip.wangxiaoming.music.ui.common.PlayerHolder
import vip.wangxiaoming.music.ui.common.PlaylistLink
import vip.wangxiaoming.music.ui.common.SongAdapter
import vip.wangxiaoming.music.ui.common.UiKit
import android.widget.EditText

/**
 * 歌单 Tab。
 *
 * 两条数据来源严格区分：
 *  - 上半部分「解析导入」：把网易/酷我/酷狗/QQ 的分享链接换成远端歌单，读的是网络；
 *  - 下半部分「我的歌单」：只读 Room，纯本地。
 *
 * 导入流程：解析链接 → 拉歌曲列表 → 建本地歌单 + 批量写 playlist_songs。
 * 去重由 (playlist_id, song_id) 唯一索引保证，重复导入只会新增没见过的曲目。
 */
class PlaylistFragment : Fragment() {

    private var _b: FragmentPlaylistBinding? = null
    private val b get() = _b!!

    private val repo = MetadataRepository()
    private var adapter: CustomPlaylistAdapter? = null

    private var pendingParse: PlaylistLink.Target? = null
    private var pendingSongs: List<Song> = emptyList()
    private var busy = false

    private val host: HostActions get() = requireActivity() as HostActions
    private val container: AppContainer get() = AppContainer.get()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _b = FragmentPlaylistBinding.inflate(inflater, container, false)
        return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        bindList()
        bindSearchBar()
        observePlaylists()
    }

    override fun onDestroyView() {
        adapter = null
        _b = null
        super.onDestroyView()
    }

    // ------------------------------------------------------------ 本地歌单

    private fun bindList() {
        val a = CustomPlaylistAdapter(
            onOpen = { pl -> showPlaylistSongs(pl) },
            onPlay = { pl -> playPlaylist(pl) },
            onRename = { pl -> renamePlaylist(pl) },
            onDelete = { pl -> confirmDelete(pl) },
        )
        adapter = a
        b.playlistTabContent.rvMinePlaylists.layoutManager = LinearLayoutManager(requireContext())
        b.playlistTabContent.rvMinePlaylists.adapter = a
    }

    private fun observePlaylists() {
        viewLifecycleOwner.lifecycleScope.launch {
            container.playlistRepository.observeWithCounts().collectLatest { list ->
                val mapped = list.map {
                    CustomPlaylistAdapter.Row(it.playlist, it.songCount)
                }
                adapter?.submit(mapped)
                b.playlistTabContent.tvEmptyMinePlaylist.visibility =
                    if (mapped.isEmpty()) View.VISIBLE else View.GONE
            }
        }
    }

    private fun playPlaylist(pl: CustomPlaylist) {
        val controller = PlayerHolder.controller
        if (controller == null) {
            UiKit.toast(requireContext(), "播放服务未就绪")
            return
        }
        viewLifecycleOwner.lifecycleScope.launch {
            val rows = container.playlistRepository.songs(pl.id)
            if (rows.isEmpty()) {
                UiKit.toast(requireContext(), "歌单里还没有歌曲")
                return@launch
            }
            val songs = rows.map { r ->
                Song(
                    id = r.songId,
                    name = r.name,
                    artist = r.artist,
                    album = r.album.orEmpty(),
                    cover = r.cover.orEmpty(),
                    source = r.source,
                    duration = r.duration,
                )
            }
            val n = PlayLauncher.play(controller, songs, 0)
            if (n == 0) UiKit.toast(requireContext(), "全部曲目解析失败")
            else host.openPlayer()
        }
    }

    private fun showPlaylistSongs(pl: CustomPlaylist) {
        viewLifecycleOwner.lifecycleScope.launch {
            val rows = container.playlistRepository.songs(pl.id)
            if (rows.isEmpty()) {
                UiKit.toast(requireContext(), "歌单「" + pl.name + "」还没有歌曲")
                return@launch
            }
            val songs = rows.map { r ->
                Song(
                    id = r.songId,
                    name = r.name,
                    artist = r.artist,
                    album = r.album.orEmpty(),
                    cover = r.cover.orEmpty(),
                    source = r.source,
                    duration = r.duration,
                )
            }
            val sheet = com.google.android.material.bottomsheet.BottomSheetDialog(requireContext())
            val view = layoutInflater.inflate(R.layout.dialog_bottom_playlist, null)
            sheet.setContentView(view)
            (view.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.rvPlaylistDialog)).apply {
                layoutManager = LinearLayoutManager(requireContext())
                adapter = SongAdapter(onClick = { song, pos ->
                    val controller = PlayerHolder.controller ?: return@SongAdapter
                    lifecycleScope.launch {
                        val n = PlayLauncher.play(controller, songs, pos)
                        sheet.dismiss()
                        if (n > 0) host.openPlayer() else UiKit.toast(requireContext(), "解析失败")
                    }
                })
                (adapter as SongAdapter).submitList(songs)
            }
            view.findViewById<android.widget.TextView>(R.id.tvPlayModeTitle).text =
                pl.name + " (" + rows.size + ")"
            sheet.show()
        }
    }

    private fun renamePlaylist(pl: CustomPlaylist) {
        val input = EditText(requireContext()).apply {
            setText(pl.name)
            setSelection(pl.name.length)
            setPadding(48, 32, 48, 32)
        }
        AlertDialog.Builder(requireContext())
            .setTitle("重命名歌单")
            .setView(input)
            .setPositiveButton("确定") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isEmpty()) {
                    UiKit.toast(requireContext(), "名称不能为空")
                } else {
                    viewLifecycleOwner.lifecycleScope.launch {
                        container.playlistRepository.rename(pl.id, name)
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun confirmDelete(pl: CustomPlaylist) {
        AlertDialog.Builder(requireContext())
            .setTitle("删除歌单")
            .setMessage("将删除「" + pl.name + "」及其中全部歌曲记录，不影响已下载文件。")
            .setPositiveButton("删除") { _, _ ->
                viewLifecycleOwner.lifecycleScope.launch {
                    container.playlistRepository.delete(pl.id)
                    UiKit.toast(requireContext(), "已删除")
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // ------------------------------------------------------------ 链接解析

    private fun bindSearchBar() {
        val bar = b.playlistTabContent
        bar.btnDoPlaylistSearch.setOnClickListener { parseAndImport() }
        bar.etPlaylistSearchInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                parseAndImport()
                true
            } else {
                false
            }
        }
        bar.etPlaylistSearchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                bar.btnClearPlaylistSearch.visibility =
                    if (s.isNullOrEmpty()) View.GONE else View.VISIBLE
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })
        bar.btnClearPlaylistSearch.setOnClickListener {
            bar.etPlaylistSearchInput.setText("")
            bar.cardParsedResultContainer.visibility = View.GONE
        }
        bar.btnViewParsedDetail.setOnClickListener { showParsedSheet() }
    }

    private fun parseAndImport() {
        if (busy) return
        val bar = b.playlistTabContent
        val raw = bar.etPlaylistSearchInput.text.toString()
        val target = PlaylistLink.parse(raw)
        if (target == null) {
            bar.cardParsedResultContainer.visibility = View.GONE
            UiKit.toast(requireContext(), "无法识别该链接（支持网易/酷我/酷狗/QQ 歌单）")
            return
        }
        busy = true
        requireContext().getSystemService<InputMethodManager>()
            ?.hideSoftInputFromWindow(bar.etPlaylistSearchInput.windowToken, 0)
        bar.btnDoPlaylistSearch.text = "解析中…"

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val detail = runCatching { repo.playlistDetail(target.id, target.source) }.getOrNull()
                val songs = runCatching { repo.playlistSongs(target.id, target.source) }.getOrNull()
                val list: List<Song> = songs?.data.orEmpty()

                if (list.isEmpty()) {
                    bar.cardParsedResultContainer.visibility = View.GONE
                    val why = when {
                        songs == null -> "网络异常"
                        target.platform == vip.wangxiaoming.music.data.remote.model.Platform.KUGOU &&
                            target.id.startsWith("gcid") -> "酷狗 gcid 链接需先反查 specialid，本阶段未支持"
                        else -> "上游未返回曲目（歌单可能已失效或需要登录）"
                    }
                    UiKit.toast(requireContext(), "解析失败：" + why)
                    return@launch
                }

                val info: Playlist? = detail?.data
                pendingParse = target
                pendingSongs = list

                CoverLoader.load(bar.ivParsedResultCover, info?.coverUrl)
                bar.tvParsedResultTitle.text = info?.name?.takeIf { it.isNotBlank() }
                    ?: (target.platform.display + "歌单 " + target.id)
                bar.tvParsedResultCount.text = "共 " + list.size + " 首歌曲 · 点击查看详情"
                bar.cardParsedResultContainer.visibility = View.VISIBLE

                importToLocal(info, target, list)
            } finally {
                busy = false
                bar.btnDoPlaylistSearch.text = "解析搜索"
            }
        }
    }

    /** 导入本地：同名歌单已存在则复用，避免用户重复点两下多出两个一模一样的歌单。 */
    private suspend fun importToLocal(info: Playlist?, target: PlaylistLink.Target, songs: List<Song>) {
        val name = info?.name?.takeIf { it.isNotBlank() }
            ?: (target.platform.display + "歌单 " + target.id)
        val playlistRepo = container.playlistRepository
        val existing = playlistRepo.playlists().firstOrNull {
            it.source == target.source && it.name == name
        }
        val id = existing?.id ?: playlistRepo.create(name, info?.coverUrl, target.source)

        val now = System.currentTimeMillis()
        val rows = songs.mapIndexed { index, s ->
            PlaylistSong(
                playlistId = id,
                songId = s.id,
                name = s.name,
                artist = s.artist,
                album = s.album,
                cover = s.cover.takeIf { it.isNotBlank() },
                source = s.source,
                duration = s.duration,
                addedTime = now + index,
            )
        }
        val added = playlistRepo.addSongs(rows)
        UiKit.toast(requireContext(), "已导入 " + added + " 首到「" + name + "」")
    }

    private fun showParsedSheet() {
        val songs = pendingSongs
        if (songs.isEmpty()) return
        val sheet = com.google.android.material.bottomsheet.BottomSheetDialog(requireContext())
        val view = layoutInflater.inflate(R.layout.dialog_bottom_playlist, null)
        sheet.setContentView(view)
        val rv = view.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.rvPlaylistDialog)
        val a = SongAdapter(onClick = { _, pos ->
            val controller = PlayerHolder.controller ?: return@SongAdapter
            viewLifecycleOwner.lifecycleScope.launch {
                val n = PlayLauncher.play(controller, songs, pos)
                sheet.dismiss()
                if (n > 0) host.openPlayer() else UiKit.toast(requireContext(), "解析失败")
            }
        })
        rv.layoutManager = LinearLayoutManager(requireContext())
        rv.adapter = a
        a.submitList(songs)
        view.findViewById<android.widget.TextView>(R.id.tvPlayModeTitle).text = "解析结果 (" + songs.size + ")"
        sheet.show()
    }
}