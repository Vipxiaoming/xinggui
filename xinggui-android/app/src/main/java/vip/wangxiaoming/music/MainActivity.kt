package vip.wangxiaoming.music

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import vip.wangxiaoming.music.databinding.ActivityMainBinding
import vip.wangxiaoming.music.di.AppContainer
import vip.wangxiaoming.music.playback.PlayMode
import vip.wangxiaoming.music.playback.PlayerConnection
import vip.wangxiaoming.music.ui.common.HostActions
import vip.wangxiaoming.music.ui.common.PlayerHolder
import vip.wangxiaoming.music.ui.home.HomeFragment
import vip.wangxiaoming.music.ui.library.DownloadFragment
import vip.wangxiaoming.music.ui.library.DownloadHistoryFragment
import vip.wangxiaoming.music.ui.mine.FavoritesFragment
import vip.wangxiaoming.music.ui.mine.MineFragment
import vip.wangxiaoming.music.ui.player.PlayerFragment
import vip.wangxiaoming.music.ui.playlist.PlaylistFragment
import vip.wangxiaoming.music.ui.search.SearchFragment
import vip.wangxiaoming.music.ui.settings.SettingsFragment

/**
 * 主界面 + 导航宿主。
 *
 * P6 的结构：
 *  - contentContainer 由 4 个 tab Fragment 共享（show/hide，不重建，保持滚动位置与请求状态）；
 *  - 二级页（搜索 / 设置 / 下载历史）压在同一容器里，返回时恢复原 tab；
 *  - 全屏播放器是独立的覆盖层，走 FragmentTransaction 的 add + 自定义动画，
 *    不进 back stack（否则返回会连 tab 一起回退）。
 *
 * 不用 Navigation Component：只有 4 + 3 + 1 个目标、全是线性导航，
 * 引入 navigation-fragment 会多一套依赖与 XML 图，收益不抵复杂度。
 * 也不用 BottomNavigationView：activity_main 里已经是手写的 4 个 tab 容器，
 * 换成 BottomNavigationView 会重写整段视觉，与 P2 的「1:1 还原」约定冲突。
 */
class MainActivity : AppCompatActivity(), HostActions {

    private lateinit var binding: ActivityMainBinding

    private class Tab(
        val container: View,
        val icon: ImageView,
        val label: TextView,
        val title: String,
        val tag: String,
    )

    private lateinit var tabs: List<Tab>
    private var selectedTab = TAB_HOME
    private var secondaryTag: String? = null

    private var controller: MediaController? = null

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private val playerListener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            renderNowPlaying(player)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        applyWindowInsets()
        bindTabs()
        bindMiniPlayer()
        requestNotificationPermissionIfNeeded()

        applyDarkModeFromPrefs()
        if (savedInstanceState == null) showTab(TAB_HOME) else restoreState(savedInstanceState)
    }

    override fun onStart() {
        super.onStart()
        lifecycleScope.launch {
            try {
                val c = PlayerConnection.connect(this@MainActivity)
                controller = c
                PlayerHolder.attach(c)
                c.addListener(playerListener)
                renderNowPlaying(c)
            } catch (t: Throwable) {
                PlayerHolder.attach(null)
                showEmptyMini()
            }
        }
    }

    override fun onStop() {
        controller?.let {
            it.removeListener(playerListener)
            it.release()
        }
        controller = null
        PlayerHolder.detach()
        super.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt(KEY_TAB, selectedTab)
        outState.putString(KEY_SECONDARY, secondaryTag)
        super.onSaveInstanceState(outState)
    }

    private fun restoreState(state: Bundle) {
        val tab = state.getInt(KEY_TAB, TAB_HOME).coerceIn(0, tabs.lastIndex)
        val sec = state.getString(KEY_SECONDARY)
        applyTabSelected(tab)
        when (sec) {
            TAG_SEARCH -> openSearchInternal("")
            TAG_SETTINGS -> openSecondary(TAG_SETTINGS) { SettingsFragment() }
            TAG_DOWNLOAD_HISTORY -> openSecondary(TAG_DOWNLOAD_HISTORY) { DownloadHistoryFragment() }
            TAG_FAVORITES -> openSecondary(TAG_FAVORITES) { FavoritesFragment() }
            else -> showTab(tab)
        }
        if (supportFragmentManager.findFragmentByTag(TAG_PLAYER) != null) {
            binding.miniPlayerLayout.visibility = View.GONE
        }
    }

    /** 状态栏高度写入占位 View；导航栏高度补到 bottomNavLayout 的 bottom padding。 */
    private fun applyWindowInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            binding.statusBarPadding.updateLayoutParams { height = bars.top }
            binding.bottomNavLayout.setPadding(
                binding.bottomNavLayout.paddingLeft,
                binding.bottomNavLayout.paddingTop,
                binding.bottomNavLayout.paddingRight,
                bars.bottom,
            )
            insets
        }
    }

    private fun applyDarkModeFromPrefs() {
        lifecycleScope.launch {
            val prefs = runCatching { AppContainer.get().prefsRepository.userPrefs.first() }.getOrNull()
                ?: return@launch
            val mode = if (prefs.darkMode) AppCompatDelegate.MODE_NIGHT_YES
            else AppCompatDelegate.MODE_NIGHT_NO
            if (AppCompatDelegate.getDefaultNightMode() != mode) {
                AppCompatDelegate.setDefaultNightMode(mode)
            }
        }
    }

    // ------------------------------------------------------------ tabs

    private fun bindTabs() {
        tabs = listOf(
            Tab(binding.navTabHome, binding.ivNavHome, binding.tvNavHome, getString(R.string.nav_home), TAG_HOME),
            Tab(binding.navTabPlaylist, binding.ivNavPlaylist, binding.tvNavPlaylist, getString(R.string.nav_playlist), TAG_PLAYLIST),
            Tab(binding.navTabLibrary, binding.ivNavLibrary, binding.tvNavLibrary, getString(R.string.nav_library), TAG_LIBRARY),
            Tab(binding.navTabMine, binding.ivNavMine, binding.tvNavMine, getString(R.string.nav_mine), TAG_MINE),
        )
        tabs.forEachIndexed { index, tab ->
            tab.container.setOnClickListener {
                // 已在二级页时点 tab：先关二级页再切，否则用户会觉得点击没反应
                closeSecondaryInternal()
                showTab(index)
            }
        }
    }

    private fun applyTabSelected(index: Int) {
        selectedTab = index
        val brand = getColor(R.color.brand)
        val inactive = getColor(R.color.ink_400)
        tabs.forEachIndexed { i, tab ->
            val color = if (i == index) brand else inactive
            tab.icon.setColorFilter(color)
            tab.label.setTextColor(color)
        }
    }

    /**
     * 切 tab：所有 tab Fragment 都 add 一次并用 show/hide 保留状态。
     *
     * 关键坑（P6 实测踩到）：同一事务里 add 的 Fragment 在 commit 之前用
     * findFragmentByTag 查不到，返回 null。于是「只 show 目标」会变成「谁都不 show」，
     * 4 个 Fragment 同时 VISIBLE，屏幕上显示的是最后 add 的那个（我的）。
     * 所以必须用本地 resolved 列表，而不是再查一遍 FragmentManager。
     */
    private fun showTab(index: Int) {
        if (index !in tabs.indices) return
        applyTabSelected(index)
        val fm = supportFragmentManager
        val tx = fm.beginTransaction()
        val resolved = arrayOfNulls<Fragment>(tabs.size)

        tabs.forEachIndexed { i, tab ->
            val existing = fm.findFragmentByTag(tab.tag)
            if (existing != null) {
                resolved[i] = existing
                tx.hide(existing)
            } else {
                val created = createTabFragment(i)
                resolved[i] = created
                tx.add(R.id.contentContainer, created, tab.tag)
                // 关键：Fragment 默认 mHidden=false，add 之后立刻就是可见的。
                // 只 show 目标而不 hide 新建的非目标项，会变成「4 个页面同时可见、
                // 屏幕上显示最后 add 的那个」——实测就是这么表现的（首页显示成「我的」）。
                if (i != index) tx.hide(created)
            }
        }
        resolved[index]?.let { tx.show(it) }

        // 二级页若还在栈里，切 tab 时一并移除
        fm.findFragmentByTag(secondaryTag ?: "")?.let { tx.remove(it) }
        secondaryTag = null
        tx.commitNowAllowingStateLoss()
    }

    private fun createTabFragment(index: Int): Fragment = when (index) {
        TAB_HOME -> HomeFragment()
        TAB_PLAYLIST -> PlaylistFragment()
        TAB_LIBRARY -> DownloadFragment()
        else -> MineFragment()
    }

    // ------------------------------------------------------------ HostActions

    override fun openSearch(keyword: String) {
        openSearchInternal(keyword)
    }

    private fun openSearchInternal(keyword: String) {
        openSecondary(TAG_SEARCH) { SearchFragment.newInstance(keyword) }
    }

    override fun openSettings() {
        openSecondary(TAG_SETTINGS) { SettingsFragment() }
    }

    override fun openDownloadHistory() {
        openSecondary(TAG_DOWNLOAD_HISTORY) { DownloadHistoryFragment() }
    }

    override fun openFavorites() {
        openSecondary(TAG_FAVORITES) { FavoritesFragment() }
    }

    /**
     * 二级页：add 到容器上层并把当前 tab 隐藏，返回时再显示回来。
     * 用 replace 会销毁 tab 的 View，回退时列表要重新请求，体验更差。
     */
    private fun openSecondary(tag: String, factory: () -> Fragment) {
        val fm = supportFragmentManager
        val tx = fm.beginTransaction()
        tabs.forEach { tab -> fm.findFragmentByTag(tab.tag)?.let { tx.hide(it) } }
        fm.findFragmentByTag(secondaryTag ?: "")?.let { tx.remove(it) }
        val existing = fm.findFragmentByTag(tag)
        if (existing == null) {
            tx.add(R.id.contentContainer, factory(), tag)
        } else {
            tx.show(existing)
        }
        tx.commitNowAllowingStateLoss()
        secondaryTag = tag
    }

    override fun closeSecondary() {
        closeSecondaryInternal()
    }

    private fun closeSecondaryInternal() {
        val tag = secondaryTag ?: return
        val fm = supportFragmentManager
        val tx = fm.beginTransaction()
        fm.findFragmentByTag(tag)?.let { tx.remove(it) }
        fm.findFragmentByTag(tabs[selectedTab].tag)?.let { tx.show(it) }
        tx.commitNowAllowingStateLoss()
        secondaryTag = null
    }

    override fun isSecondaryOpen(): Boolean = secondaryTag != null

    override fun selectTab(index: Int) {
        closeSecondaryInternal()
        showTab(index)
    }

    // ------------------------------------------------------------ 播放器覆盖层

    override fun openPlayer() {
        if (supportFragmentManager.findFragmentByTag(TAG_PLAYER) != null) return
        // playerHost 是 RelativeLayout 的兄弟节点，z-order 低于 mini 与底部导航；
        // 只设 visibility 会被这两块盖住（实测：播放器底部控制行被裁、导航栏压在页面上）。
        // 因此同时隐藏 mini / 导航，并把 playerHost 提到最前。
        binding.playerHost.visibility = View.VISIBLE
        binding.playerHost.bringToFront()
        binding.miniPlayerLayout.visibility = View.GONE
        binding.bottomNavLayout.visibility = View.GONE
        supportFragmentManager.beginTransaction()
            .add(R.id.playerHost, PlayerFragment(), TAG_PLAYER)
            .commitAllowingStateLoss()
    }

    override fun closePlayer() {
        val f = supportFragmentManager.findFragmentByTag(TAG_PLAYER) ?: return
        supportFragmentManager.beginTransaction()
            .remove(f)
            .commitAllowingStateLoss()
        binding.playerHost.visibility = View.GONE
        binding.miniPlayerLayout.visibility = View.VISIBLE
        binding.bottomNavLayout.visibility = View.VISIBLE
        controller?.let { renderNowPlaying(it) }
    }

    override fun onBackPressed() {
        when {
            supportFragmentManager.findFragmentByTag(TAG_PLAYER) != null -> closePlayer()
            secondaryTag != null -> closeSecondaryInternal()
            selectedTab != TAB_HOME -> selectTab(TAB_HOME)
            else -> @Suppress("DEPRECATION") super.onBackPressed()
        }
    }

    // ------------------------------------------------------------ mini player

    private fun bindMiniPlayer() {
        binding.btnPlayPause.setOnClickListener {
            val c = controller ?: return@setOnClickListener
            if (c.isPlaying) c.pause() else c.play()
        }

        // 点整条 mini：有队列就打开全屏播放器（原版行为），没队列给提示
        binding.miniPlayerLayout.setOnClickListener {
            val c = controller ?: return@setOnClickListener
            if (c.mediaItemCount == 0) {
                showToast(getString(R.string.mini_empty_notice))
            } else {
                openPlayer()
            }
        }

        binding.miniPlayerLayout.setOnLongClickListener {
            showPlayModeDialog()
            true
        }

        binding.btnMiniPlaylist.setOnClickListener {
            showToast("队列面板属 P7")
        }

        binding.miniProgressBar.max = 1000
        showEmptyMini()
    }

    private fun showPlayModeDialog() {
        val c = controller ?: return
        val options = arrayOf(
            getString(R.string.play_mode_sequential),
            getString(R.string.play_mode_repeat_one),
            getString(R.string.play_mode_shuffle),
        )
        val checked = PlayMode.ordered.indexOf(PlayMode.fromPlayer(c))
        AlertDialog.Builder(this)
            .setTitle(R.string.playback_channel_name)
            .setSingleChoiceItems(options, checked) { dialog, which ->
                PlayMode.applyTo(c, PlayMode.ordered[which])
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun renderNowPlaying(player: Player) {
        val metadata: MediaMetadata? = player.currentMediaItem?.mediaMetadata
        if (metadata == null) {
            showEmptyMini()
            return
        }
        binding.tvMiniEmptyNotice.visibility = View.GONE
        // layoutMiniInfo 是标题/歌手的包裹容器，XML 里默认 gone。
        // 只恢复子 View 而不恢复容器，表现为「有封面、有按钮、没有文字」的空白 mini。
        binding.layoutMiniInfo.visibility = View.VISIBLE
        binding.miniCover.visibility = View.VISIBLE
        binding.miniTitle.visibility = View.VISIBLE
        binding.miniArtist.visibility = View.VISIBLE
        binding.controlsLayout.visibility = View.VISIBLE
        binding.miniProgressBar.visibility = View.VISIBLE

        binding.miniTitle.text = metadata.title ?: getString(R.string.now_playing_unknown)
        binding.miniArtist.text = metadata.artist ?: getString(R.string.now_playing_unknown_artist)

        binding.btnPlayPause.setImageResource(
            if (player.isPlaying) R.drawable.ic_player_pause else R.drawable.ic_player_play_line,
        )
        binding.btnPlayPause.contentDescription =
            getString(if (player.isPlaying) R.string.mini_pause else R.string.mini_play)

        val duration = player.duration
        if (duration > 0) {
            val ratio = (player.currentPosition.toFloat() / duration).coerceIn(0f, 1f)
            binding.miniProgressBar.progress = (ratio * 1000).toInt()
        } else {
            binding.miniProgressBar.progress = 0
        }
        binding.miniProgressBar.visibility = View.VISIBLE
    }

    private fun showEmptyMini() {
        binding.tvMiniEmptyNotice.visibility = View.VISIBLE
        binding.layoutMiniInfo.visibility = View.GONE
        binding.miniCover.visibility = View.GONE
        binding.miniTitle.visibility = View.GONE
        binding.miniArtist.visibility = View.GONE
        binding.controlsLayout.visibility = View.GONE
        binding.miniProgressBar.visibility = View.GONE
        binding.miniTitle.text = ""
        binding.miniArtist.text = ""
    }

    private fun showToast(text: String) {
        android.widget.Toast.makeText(this, text, android.widget.Toast.LENGTH_SHORT).show()
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private companion object {
        const val TAB_HOME = 0
        const val TAB_PLAYLIST = 1
        const val TAB_LIBRARY = 2
        const val TAB_MINE = 3

        const val TAG_HOME = "tab_home"
        const val TAG_PLAYLIST = "tab_playlist"
        const val TAG_LIBRARY = "tab_library"
        const val TAG_MINE = "tab_mine"

        const val TAG_SEARCH = "page_search"
        const val TAG_SETTINGS = "page_settings"
        const val TAG_DOWNLOAD_HISTORY = "page_download_history"
        const val TAG_FAVORITES = "page_favorites"
        const val TAG_PLAYER = "page_player"

        const val KEY_TAB = "state_tab"
        const val KEY_SECONDARY = "state_secondary"
    }
}
