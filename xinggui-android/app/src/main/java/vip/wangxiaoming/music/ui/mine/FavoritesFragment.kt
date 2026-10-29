package vip.wangxiaoming.music.ui.mine

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import vip.wangxiaoming.music.data.remote.model.Song
import vip.wangxiaoming.music.databinding.FragmentFavoritesBinding
import vip.wangxiaoming.music.ui.common.FavoritesRepository
import vip.wangxiaoming.music.ui.common.HostActions
import vip.wangxiaoming.music.ui.common.PlayLauncher
import vip.wangxiaoming.music.ui.common.PlayerHolder
import vip.wangxiaoming.music.ui.common.SongAdapter
import vip.wangxiaoming.music.ui.common.UiKit

/**
 * 我的喜欢（收藏列表）。
 *
 * 数据源只有一个：Room 的 favorites 表（[FavoritesRepository.observeAll]）。
 * 顶部计数同样来自 Flow，所以在播放器或列表里取消收藏后，进来自动是新的数量，
 * 不需要「返回时手动刷新」。
 *
 * 点行播放：整张收藏列表作为队列投给 [PlayLauncher]，与歌单详情行为一致。
 * 心形按钮直接 toggle；取消后该行会因 Flow 更新而消失（ListAdapter 差分移除）。
 */
class FavoritesFragment : Fragment() {

    private var _b: FragmentFavoritesBinding? = null
    private val b get() = _b!!

    private val repo = FavoritesRepository()
    private var adapter: SongAdapter? = null
    private var songs: List<Song> = emptyList()

    private val host: HostActions get() = requireActivity() as HostActions

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _b = FragmentFavoritesBinding.inflate(inflater, container, false)
        return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        b.btnBackFavorites.setOnClickListener { host.closeSecondary() }

        val a = SongAdapter(
            onClick = { _, position -> playAt(position) },
            onToggleLike = { song -> toggle(song) },
            scope = viewLifecycleOwner.lifecycleScope,
        )
        adapter = a
        b.rvFavorites.layoutManager = LinearLayoutManager(requireContext())
        b.rvFavorites.adapter = a

        viewLifecycleOwner.lifecycleScope.launch {
            repo.observeAll().collectLatest { rows ->
                songs = rows.map { r ->
                    Song(
                        id = r.songId.substringAfter(':', r.songId),
                        name = r.name,
                        artist = r.artist,
                        album = r.album.orEmpty(),
                        cover = r.cover.orEmpty(),
                        source = r.source,
                        duration = r.duration.ifBlank { "0" },
                    )
                }
                adapter?.submitList(songs)
                if (_b == null) return@collectLatest
                b.tvFavoritesCount.text = songs.size.toString() + " 首"
                b.layoutFavoritesEmpty.visibility =
                    if (songs.isEmpty()) View.VISIBLE else View.GONE
            }
        }
    }

    override fun onDestroyView() {
        adapter = null
        _b = null
        super.onDestroyView()
    }

    private fun toggle(song: Song) {
        viewLifecycleOwner.lifecycleScope.launch {
            val now = runCatching { repo.toggle(song) }.getOrDefault(false)
            UiKit.toast(requireContext(), if (now) "已加入我的喜欢" else "已取消收藏")
        }
    }

    private fun playAt(position: Int) {
        val controller = PlayerHolder.controller ?: run {
            UiKit.toast(requireContext(), "播放器未连接")
            return
        }
        if (songs.isEmpty()) return
        viewLifecycleOwner.lifecycleScope.launch {
            val n = PlayLauncher.play(controller, songs, position)
            if (n > 0) host.openPlayer() else UiKit.toast(requireContext(), "解析失败，请稍后重试")
        }
    }
}
