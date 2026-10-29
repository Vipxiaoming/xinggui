package vip.wangxiaoming.music.ui.search

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.TextView
import androidx.core.content.getSystemService
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import kotlinx.coroutines.launch
import vip.wangxiaoming.music.R
import vip.wangxiaoming.music.data.remote.MetadataRepository
import vip.wangxiaoming.music.data.remote.model.Platform
import vip.wangxiaoming.music.data.remote.model.Playlist
import vip.wangxiaoming.music.data.remote.model.Song
import vip.wangxiaoming.music.databinding.FragmentSearchBinding
import vip.wangxiaoming.music.ui.common.HostActions
import vip.wangxiaoming.music.ui.common.PlayLauncher
import vip.wangxiaoming.music.ui.common.PlayerHolder
import vip.wangxiaoming.music.ui.common.SearchPlaylistAdapter
import vip.wangxiaoming.music.ui.common.SongAdapter
import vip.wangxiaoming.music.ui.common.UiKit

/**
 * 搜索页。
 *
 * 四平台并发由 MetadataRepository.searchAll 内部完成（每路独立超时、单家失败不影响其余），
 * 这里只负责 UI。平台 chip 是「过滤开关」而不是「单选平台」：
 * 点一下关掉一家，其余仍在跑，结果里 source 标签能看出每条来自哪家。
 * 默认四家全开，与原版行为一致。
 */
class SearchFragment : Fragment() {

    private var _b: FragmentSearchBinding? = null
    private val b get() = _b!!

    private val repo = MetadataRepository()
    private var songAdapter: SongAdapter? = null
    private var playlistAdapter: SearchPlaylistAdapter? = null

    private val enabled = Platform.entries.toMutableSet()
    private val chips = mutableMapOf<Platform, TextView>()

    private var lastKeyword = ""
    private var searching = false

    private val host: HostActions get() = requireActivity() as HostActions

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _b = FragmentSearchBinding.inflate(inflater, container, false)
        return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        buildChips()
        bindList()
        bindInput()
        presetKeyword()
    }

    override fun onDestroyView() {
        songAdapter = null
        playlistAdapter = null
        chips.clear()
        _b = null
        super.onDestroyView()
    }

    /** Home 传进来的预填关键词：命中就立即搜一次。 */
    private fun presetKeyword() {
        val kw = arguments?.getString(ARG_KEYWORD).orEmpty()
        if (kw.isBlank()) return
        b.etSearchInput.setText(kw)
        b.etSearchInput.setSelection(kw.length)
        doSearch(kw)
    }

    private fun bindInput() {
        b.btnSearchBack.setOnClickListener { host.closeSecondary() }
        b.btnSearchGo.setOnClickListener { doSearch(b.etSearchInput.text.toString()) }
        b.etSearchInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                doSearch(b.etSearchInput.text.toString())
                true
            } else {
                false
            }
        }
        b.btnSearchClear.setOnClickListener {
            b.etSearchInput.setText("")
            b.btnSearchClear.visibility = View.GONE
        }
        b.etSearchInput.setOnFocusChangeListener { _, hasFocus ->
            b.btnSearchClear.visibility =
                if (hasFocus && b.etSearchInput.text.isNotEmpty()) View.VISIBLE else View.GONE
        }
    }

    private fun buildChips() {
        val bar = b.layoutPlatformChips
        bar.removeAllViews()
        val density = resources.displayMetrics.density
        val padH = (10 * density).toInt()
        val padV = (5 * density).toInt()
        val gap = (6 * density).toInt()
        Platform.entries.forEach { platform ->
            val tv = TextView(requireContext()).apply {
                text = platform.display
                textSize = 13f
                gravity = android.view.Gravity.CENTER
                setPadding(padH, padV, padH, padV)
                setBackgroundResource(R.drawable.bg_category_chip_unselected)
                val lp = ViewGroup.MarginLayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { marginEnd = gap }
                layoutParams = lp
                setOnClickListener { toggle(platform) }
            }
            chips[platform] = tv
            bar.addView(tv)
        }
        renderChips()
    }

    private fun toggle(platform: Platform) {
        // 至少留一家：全关会让搜索永远返回空，属于「看起来像 bug」的状态
        if (enabled.size == 1 && platform in enabled) {
            UiKit.toast(requireContext(), "至少保留一个平台")
            return
        }
        if (!enabled.remove(platform)) enabled.add(platform)
        renderChips()
        if (lastKeyword.isNotBlank()) doSearch(lastKeyword)
    }

    private fun renderChips() {
        chips.forEach { (platform, tv) ->
            val on = platform in enabled
            tv.setTextColor(UiKit.color(requireContext(), if (on) R.color.brand else R.color.ink_500))
            tv.background = androidx.core.content.ContextCompat.getDrawable(
                requireContext(),
                if (on) R.drawable.bg_tag_quality else R.drawable.bg_category_chip_unselected,
            )
        }
    }

    private val favorites = vip.wangxiaoming.music.ui.common.FavoritesRepository()

    private fun toggleFavorite(song: Song) {
        viewLifecycleOwner.lifecycleScope.launch {
            val now = runCatching { favorites.toggle(song) }.getOrDefault(false)
            UiKit.toast(requireContext(), if (now) "已加入我的喜欢" else "已取消收藏")
        }
    }

    private fun bindList() {
        // 心形按钮接真实收藏：状态由 Room Flow 订阅，点一下即落库/删库。
        val songs = SongAdapter(
            onClick = { song, position -> play(song, position) },
            onToggleLike = { song -> toggleFavorite(song) },
            scope = viewLifecycleOwner.lifecycleScope,
        )
        songAdapter = songs
        b.searchResultPage.rvSearchSongs.layoutManager = LinearLayoutManager(requireContext())
        b.searchResultPage.rvSearchSongs.adapter = songs

        val playlists = SearchPlaylistAdapter { pl -> host.openSearch(pl.name) }
        playlistAdapter = playlists
        b.searchResultPage.rvSearchPlaylists.layoutManager =
            LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false)
        b.searchResultPage.rvSearchPlaylists.adapter = playlists

        // 歌手横条本阶段不接（Artist 点击需要歌手页，属 P7）
        b.searchResultPage.layoutSearchArtists.visibility = View.GONE
    }

    private fun doSearch(raw: String) {
        val kw = raw.trim()
        if (kw.isEmpty()) {
            UiKit.toast(requireContext(), "请输入关键词")
            return
        }
        if (searching) return
        searching = true
        lastKeyword = kw
        b.etSearchInput.clearFocus()
        requireContext().getSystemService<InputMethodManager>()?.hideSoftInputFromWindow(b.etSearchInput.windowToken, 0)

        b.pbSearchLoading.visibility = View.VISIBLE
        b.tvSearchEmpty.visibility = View.GONE
        b.searchResultPage.searchResultContainer.visibility = View.VISIBLE
        b.searchResultPage.tvSearchSongsCount.text = "单曲列表 · 搜索中…"
        songAdapter?.submitList(emptyList())
        playlistAdapter?.submit(emptyList())

        viewLifecycleOwner.lifecycleScope.launch {
            val songsRes = runCatching { repo.searchAll(kw, enabled = enabled.toSet()) }.getOrNull()
            val playlistsRes = runCatching { repo.searchPlaylists(kw) }.getOrNull()

            val songs: List<Song> = songsRes?.data.orEmpty()
            val playlists: List<Playlist> = playlistsRes?.data.orEmpty()

            songAdapter?.submitList(songs)
            playlistAdapter?.submit(playlists.take(10))
            b.searchResultPage.layoutSearchPlaylists.visibility =
                if (playlists.isEmpty()) View.GONE else View.VISIBLE

            b.searchResultPage.tvSearchSongsCount.text = buildString {
                append("单曲列表")
                if (songs.isNotEmpty()) append(" (").append(songs.size).append(")")
            }

            b.pbSearchLoading.visibility = View.GONE
            val empty = songs.isEmpty() && playlists.isEmpty()
            // 区分「真的没有这首歌」与「四家全部连不上」：后者说成无结果会误导用户去换关键词。
            // 判据用 anyOk（至少一家拿到 HTTP 响应），而不是「结果为空」。
            val networkDown = songsRes == null || !songsRes.anyOk
            b.tvSearchEmpty.visibility = if (empty) View.VISIBLE else View.GONE
            b.tvSearchEmpty.text = when {
                !empty -> ""
                networkDown -> "网络异常，请检查网络后重试"
                else -> "没有找到「" + kw + "」\n换个关键词，或在顶部打开更多平台"
            }
            searching = false
        }
    }

    private fun play(song: Song, position: Int) {
        val controller = PlayerHolder.controller
        if (controller == null) {
            UiKit.toast(requireContext(), "播放服务未就绪")
            return
        }
        val list = songAdapter?.currentList.orEmpty()
        if (position !in list.indices) return
        UiKit.toast(requireContext(), "正在解析：" + song.name)
        viewLifecycleOwner.lifecycleScope.launch {
            val n = PlayLauncher.play(controller, list, position)
            if (n == 0) UiKit.toast(requireContext(), "解析失败，换一首试试")
            else host.openPlayer()
        }
    }

    companion object {
        private const val ARG_KEYWORD = "keyword"

        fun newInstance(keyword: String): SearchFragment = SearchFragment().apply {
            arguments = Bundle().apply { putString(ARG_KEYWORD, keyword) }
        }
    }
}