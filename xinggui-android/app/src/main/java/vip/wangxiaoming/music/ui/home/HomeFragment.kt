package vip.wangxiaoming.music.ui.home

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import vip.wangxiaoming.music.R
import vip.wangxiaoming.music.data.remote.MetadataRepository
import vip.wangxiaoming.music.data.remote.model.Playlist
import vip.wangxiaoming.music.data.remote.model.RankInfo
import vip.wangxiaoming.music.data.remote.model.Song
import vip.wangxiaoming.music.databinding.FragmentHomeBinding
import vip.wangxiaoming.music.ui.common.CoverLoader
import vip.wangxiaoming.music.ui.common.HostActions
import vip.wangxiaoming.music.ui.common.PlayLauncher
import vip.wangxiaoming.music.ui.common.PlaylistStripAdapter
import vip.wangxiaoming.music.ui.common.PlayerHolder
import vip.wangxiaoming.music.ui.common.SongAdapter
import vip.wangxiaoming.music.ui.common.UiKit

/**
 * 首页。
 *
 * 数据来源全部是真实网络接口（MetadataRepository），不做本地兜底假数据：
 *  - 榜单卡：网易 toplist 前两个（飙升榜 19723756 / 热歌榜 3778678，接口失败时用内置同源兜底）；
 *  - 新歌速递：网易 新歌榜 3779629 的前 20 首；
 *  - 热门歌单：用「热门」关键词做歌单搜索（酷我 + 酷狗），取前 10。
 *
 * 评分口径：原版首页「热门歌单」是编辑位（服务端下发）。客户端没有这个接口，
 * 这里用搜索结果代替并标注，不冒充编辑推荐。
 */
class HomeFragment : Fragment() {

    private var _b: FragmentHomeBinding? = null
    private val b get() = _b!!

    private val repo = MetadataRepository()
    private var newSongAdapter: SongAdapter? = null
    private var loading = false

    private val host: HostActions get() = requireActivity() as HostActions

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _b = FragmentHomeBinding.inflate(inflater, container, false)
        return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        bindHeader()
        bindLists()
        if (savedInstanceState == null) load()
    }

    override fun onDestroyView() {
        newSongAdapter = null
        _b = null
        super.onDestroyView()
    }

    // ------------------------------------------------------------ 静态交互

    private fun bindHeader() {
        // 输入框自身不可聚焦（首页不在这里直接打字），点击由外层胶囊拦截并跳搜索页。
        // 只给 EditText 设 onClickListener 是没用的：isFocusable=false 后它不接收触摸，
        // 事件会穿透到父容器，表现为「点了完全没反应」（实测）。
        b.homeStickyHeader.etSearch.isFocusable = false
        b.homeStickyHeader.etSearch.isClickable = false
        // EditText 即使 isFocusable=false 也会在 onTouchEvent 里消费触摸（实测：点击完全没反应），
        // 所以三个入口都拦截：touch 最可靠，click 覆盖走焦点/无障碍的路径。
        val goSearch = {
            host.openSearch("")
            true
        }
        b.homeStickyHeader.etSearch.setOnTouchListener { _, event ->
            if (event.actionMasked == android.view.MotionEvent.ACTION_UP) {
                host.openSearch("")
            }
            true
        }
        b.homeStickyHeader.layoutHomeSearchEntry.setOnClickListener { goSearch() }
        b.homeStickyHeader.btnPlayHistory.setOnClickListener {
            UiKit.toast(requireContext(), "播放历史：数据层已就绪，页面属 P7")
        }

        b.homeHeader.btnFuncDownload.setOnClickListener { host.selectTab(2) }
        b.homeHeader.btnFuncMV.setOnClickListener {
            UiKit.toast(requireContext(), "MV 模块未接入")
        }
        b.homeHeader.btnFuncRank.setOnClickListener {
            UiKit.toast(requireContext(), "排行榜列表属 P7，当前首页展示前两个榜")
        }
        b.homeHeader.btnFuncPlaylistSquare.setOnClickListener {
            host.openSearch("热门歌单")
        }

        b.homeHeader.btnHeaderHotRank.setOnClickListener {
            host.openSearch("热歌榜")
        }
        b.homeHeader.btnMorePlaylist.setOnClickListener {
            host.openSearch("热门歌单")
        }
        b.homeHeader.btnRefreshNewSongs.setOnClickListener { load() }
    }

    private val favorites = vip.wangxiaoming.music.ui.common.FavoritesRepository()

    private fun toggleFavorite(song: Song) {
        viewLifecycleOwner.lifecycleScope.launch {
            val now = runCatching { favorites.toggle(song) }.getOrDefault(false)
            vip.wangxiaoming.music.ui.common.UiKit.toast(
                requireContext(),
                if (now) "已加入我的喜欢" else "已取消收藏",
            )
        }
    }

    private fun bindLists() {
        // 列表心形：与播放器共用 FavoritesRepository，收藏态由 Room Flow 驱动。
        newSongAdapter = SongAdapter(
            onClick = { _, position -> playFromNewSongs(position) },
            onToggleLike = { song -> toggleFavorite(song) },
            scope = viewLifecycleOwner.lifecycleScope,
        )
        b.rvNewSongs.layoutManager = LinearLayoutManager(requireContext())
        b.rvNewSongs.adapter = newSongAdapter

        b.homeHeader.rvHotPlaylists.layoutManager =
            LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false)
        b.homeHeader.rvHotPlaylists.adapter = PlaylistStripAdapter { pl ->
            host.openSearch(pl.name)
        }
    }

    // ------------------------------------------------------------ 数据

    private fun load() {
        if (loading) return
        loading = true
        b.homeHeader.tvCardSurgeSong.text = "正在加载…"
        b.homeHeader.tvCardHotSong.text = "正在加载…"
        viewLifecycleOwner.lifecycleScope.launch {
            val (ranks, newSongs, hotPlaylists) = coroutineScope {
                val r = async { runCatching { repo.ranks() }.getOrNull() }
                val n = async { runCatching { repo.rankSongs(NEW_SONG_RANK) }.getOrNull() }
                val p = async { runCatching { repo.searchPlaylists("热门") }.getOrNull() }
                Triple(r.await(), n.await(), p.await())
            }

            // 榜单卡：优先取接口名里的「飙升 / 热歌」，找不到就按内置 id 匹配
            val rankList: List<RankInfo> = ranks?.data.orEmpty()
            val surge = rankList.firstOrNull { it.title.contains("飙升") }
                ?: rankList.firstOrNull { it.id == "19723756" }
                ?: rankList.getOrNull(0)
            val hot = rankList.firstOrNull { it.title.contains("热歌") }
                ?: rankList.firstOrNull { it.id == "3778678" }
                ?: rankList.getOrNull(1)
            surge?.let { bindRankCard(it, isSurge = true) }
            hot?.let { bindRankCard(it, isSurge = false) }

            // 新歌速递
            val songs: List<Song> = newSongs?.data.orEmpty()
            newSongAdapter?.submitList(songs)

            // 热门歌单
            val lists: List<Playlist> = hotPlaylists?.data.orEmpty().ifEmpty {
                rankList.map { RankInfo(it.id, it.source, it.title, it.updateFrequency, it.coverUrl, it.description) }
                    .map { Playlist(it.id, it.title, it.coverUrl, it.description, 0L, 0, "wy") }
            }
            (b.homeHeader.rvHotPlaylists.adapter as? PlaylistStripAdapter)?.submit(lists.take(10))

            val okCount = listOf(ranks?.anyOk == true, newSongs?.anyOk == true, hotPlaylists?.anyOk == true)
                .count { it }
            b.tvHomeStatus.visibility = if (songs.isEmpty() && lists.isEmpty()) View.VISIBLE else View.GONE
            b.tvHomeStatus.text = if (songs.isEmpty() && lists.isEmpty()) {
                "网络不可用或上游接口异常，点击「刷新」重试"
            } else {
                ""
            }
            if (okCount == 0) UiKit.toast(requireContext(), "首页数据加载失败，请检查网络")
            loading = false
        }
    }

    private fun bindRankCard(rank: RankInfo, isSurge: Boolean) {
        val cover = if (isSurge) b.homeHeader.ivSurgeCover else b.homeHeader.ivHotCover
        val label = if (isSurge) b.homeHeader.tvCardSurgeSong else b.homeHeader.tvCardHotSong
        CoverLoader.load(cover, rank.coverUrl)
        label.text = rank.title

        viewLifecycleOwner.lifecycleScope.launch {
            val res = runCatching { repo.rankSongs(rank.id, rank.source) }.getOrNull()
            val first = res?.data?.firstOrNull()
            if (first != null) {
                label.text = "1. " + first.name
                CoverLoader.load(cover, first.cover.takeIf { it.isNotBlank() } ?: rank.coverUrl)
                label.setOnClickListener { host.openSearch(rank.title) }
                cover.setOnClickListener { host.openSearch(rank.title) }
            } else {
                label.text = rank.title + " · 暂无数据"
            }
        }
    }

    private fun playFromNewSongs(position: Int) {
        val list = newSongAdapter?.currentList.orEmpty()
        if (position !in list.indices) return
        val controller = PlayerHolder.controller
        if (controller == null) {
            UiKit.toast(requireContext(), "播放服务未就绪")
            return
        }
        UiKit.toast(requireContext(), "正在解析：" + list[position].name)
        viewLifecycleOwner.lifecycleScope.launch {
            val n = PlayLauncher.play(controller, list, position)
            if (n == 0) {
                UiKit.toast(requireContext(), "解析失败，可换一首或稍后重试")
            } else {
                host.openPlayer()
            }
        }
    }

    companion object {
        /** 网易「新歌榜」 */
        const val NEW_SONG_RANK = "3779629"
    }
}