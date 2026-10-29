package vip.wangxiaoming.music.ui.mine

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatDelegate
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import vip.wangxiaoming.music.R
import vip.wangxiaoming.music.data.model.AuthCache
import vip.wangxiaoming.music.databinding.FragmentMineBinding
import vip.wangxiaoming.music.di.AppContainer
import vip.wangxiaoming.music.ui.common.HostActions
import vip.wangxiaoming.music.auth.ApiResult
import vip.wangxiaoming.music.auth.AuthFailure
import vip.wangxiaoming.music.auth.AuthOutcome
import vip.wangxiaoming.music.ui.auth.AuthActivity
import vip.wangxiaoming.music.ui.common.UiKit

/**
 * 我的 Tab。
 *
 * 账号卡的数据全部来自 AuthRepository（DataStore 缓存 + 加密 TokenStore），
 * 没有「先显示假会员」的占位；未登录就显示未登录。
 *
 * 深色模式：写 DataStore（darkMode）+ 调 AppCompatDelegate.setDefaultNightMode。
 * 为什么两处都要：DataStore 是持久化的真源，AppCompatDelegate 是本次立刻生效的开关；
 * 冷启动时由 MainActivity 读 DataStore 再 apply（见 MainActivity.applyDarkMode）。
 */
class MineFragment : Fragment() {

    private var _b: FragmentMineBinding? = null
    private val b get() = _b!!

    private val host: HostActions get() = requireActivity() as HostActions
    private val container: AppContainer get() = AppContainer.get()
    private val favorites = vip.wangxiaoming.music.ui.common.FavoritesRepository()

    /** 防止 collect 回写时触发 switch 监听造成自激。 */
    private var bindingSwitch = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _b = FragmentMineBinding.inflate(inflater, container, false)
        return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val page = b.mineTabContent

        page.btnMineRefresh.setOnClickListener { refreshProfile() }
        page.cardMineSettings.setOnClickListener { host.openSettings() }
        // 真机联调：不重打包、不 adb，直接在 App 内改服务端基址。
        page.cardMineServerSettings.setOnClickListener { ServerSettingsDialog.show(requireContext()) }
        page.cardMineAbout.setOnClickListener {
            AlertDialog.Builder(requireContext())
                .setTitle("星轨音乐")
                .setMessage("版本 0.1.0-p6\n\n四平台聚合检索客户端。\n音频由上游平台提供，本应用不自建音源。")
                .setPositiveButton("知道了", null)
                .show()
        }
        page.cardMineClearCache.setOnClickListener { confirmClearCache() }
        page.cardMineFavorites.setOnClickListener { host.openFavorites() }
        page.btnAccountAction.setOnClickListener { onAccountAction() }
        page.btnAccountUnbind.setOnClickListener {
            onUnbind()
        }

        page.switchMineDarkMode.setOnCheckedChangeListener { _, checked ->
            if (bindingSwitch) return@setOnCheckedChangeListener
            applyDark(checked)
        }

        observeAuth()
        observePrefs()
        observeSessionEvents()
        observeFavorites()
        renderServerSubtitle()
    }

    override fun onDestroyView() {
        _b = null
        super.onDestroyView()
    }

    /**
     * 收藏计数订阅。放在「我的」页而不是收藏页里，是因为用户最常看的就是这个数字：
     * 在播放器点收藏，退回「我的」应立即 +1，而不是等下次进收藏页才刷新。
     */
    /** 服务器设置卡副标题：始终显示当前生效的基址，用户不用点进去才能确认。 */
    private fun renderServerSubtitle() {
        val ctx = context ?: return
        val page = _b?.mineTabContent ?: return
        val url = vip.wangxiaoming.music.auth.AuthApi.resolveBaseUrl(ctx)
        page.tvMineServerSub.text = if (url.startsWith("https://")) {
            "HTTPS · " + url.removePrefix("https://")
        } else {
            "HTTP · " + url.removePrefix("http://")
        }
    }

    private fun observeFavorites() {
        viewLifecycleOwner.lifecycleScope.launch {
            favorites.observeCountText().collectLatest { text ->
                _b?.mineTabContent?.tvMineFavoritesCount?.text = text
            }
        }
    }

    private fun observeAuth() {
        viewLifecycleOwner.lifecycleScope.launch {
            container.authRepository.authCache.collectLatest { cache -> renderAccount(cache) }
        }
    }

    /**
     * 服务端把会话判死（refresh 失效 / 账号被停用）时，AuthSession 会广播 SignedOut。
     * 这里立即回登录页——否则用户会停在一个所有请求都 401 的界面上。
     */
    private fun observeSessionEvents() {
        viewLifecycleOwner.lifecycleScope.launch {
            container.authSession.events.collectLatest { ev ->
                when (ev) {
                    is vip.wangxiaoming.music.auth.AuthEvent.SignedOut -> {
                        UiKit.toast(requireContext(), ev.reason)
                        openAuthPage()
                    }
                }
            }
        }
    }

    private fun observePrefs() {
        viewLifecycleOwner.lifecycleScope.launch {
            container.prefsRepository.userPrefs.collectLatest { prefs ->
                val page = _b?.mineTabContent ?: return@collectLatest
                bindingSwitch = true
                page.switchMineDarkMode.isChecked = prefs.darkMode
                bindingSwitch = false
                page.tvMineDarkModeSub.text = if (prefs.darkMode) {
                    "已开启，全局使用深色配色"
                } else {
                    "已关闭，全局使用亮色配色"
                }
            }
        }
    }

    private fun renderAccount(cache: AuthCache) {
        val page = _b?.mineTabContent ?: return
        if (cache.isLoggedIn) {
            page.tvAccountUsername.text = cache.username
            page.tvAccountStatusDesc.text = buildString {
                append(vipLabel(cache.vipType))
                if (cache.expireAt.isNotBlank()) append(" · 有效期至 ").append(cache.expireAt)
            }
            page.tvAccountDeviceLimit.visibility = View.VISIBLE
            page.tvAccountDeviceLimit.text =
                "设备数: " + cache.deviceCount + "/" + cache.maxDevices
            page.btnAccountAction.text = "退出登录"
            page.btnAccountUnbind.visibility = View.VISIBLE
        } else {
            page.tvAccountUsername.text = "未登录"
            page.tvAccountStatusDesc.text = "登录后可同步歌单与收藏"
            page.tvAccountDeviceLimit.visibility = View.GONE
            page.btnAccountAction.text = "登录"
            page.btnAccountUnbind.visibility = View.GONE
        }
    }

    /**
     * 已登录时这里是「退出登录」；未登录时是「切换账号」。
     * 两者最终都回到 AuthActivity：退出前先吊销服务端 refresh token，
     * 切换账号则直接清空任务栈，保证返回键不会退回已失效的主界面。
     */
    /** 服务端下发小写 free / VIP 等原文，UI 统一成中文；未知值原样透出便于排查。 */
    private fun vipLabel(vipType: String): String = when (vipType.lowercase()) {
        "free", "" -> "普通用户"
        "vip" -> "VIP 会员"
        "svip" -> "SVIP 会员"
        else -> vipType
    }

    private fun onAccountAction() {
        viewLifecycleOwner.lifecycleScope.launch {
            val cache = container.authRepository.authCache.first()
            if (!cache.isLoggedIn) {
                openAuthPage()
                return@launch
            }
            AlertDialog.Builder(requireContext())
                .setTitle("退出登录")
                .setMessage("退出后本地歌单与下载记录保留，云端同步将暂停。")
                .setPositiveButton("退出") { _, _ ->
                    viewLifecycleOwner.lifecycleScope.launch {
                        container.authSession.logout()
                        UiKit.toast(requireContext(), "已退出登录")
                        openAuthPage()
                    }
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }

    /** 切换账号 / 退出后回登录页：清空任务栈，返回键不会退回失效页面。 */
    private fun openAuthPage() {
        val ctx = activity ?: return
        startActivity(
            Intent(ctx, AuthActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
        )
        ctx.finish()
        ctx.overridePendingTransition(0, 0)
    }

    // ------------------------------------------------------------ P8：真实账户信息

    /** 刷新账号卡：拉 /v1/auth/me，把用户名、到期日、设备数刷成服务端真值。 */
    private fun refreshProfile() {
        val ctx = context ?: return
        UiKit.toast(ctx, "正在刷新账号信息…")
        viewLifecycleOwner.lifecycleScope.launch {
            when (val r = container.authSession.syncProfile()) {
                is AuthOutcome.Success -> {
                    renderAccount(
                        AuthCache(
                            username = r.user.username,
                            vipType = r.user.vipType,
                            expireAt = r.user.expireAt,
                            deviceCount = r.user.deviceCount,
                            maxDevices = r.user.maxDevices,
                            deviceId = container.authSession.deviceId(),
                        ),
                    )
                    UiKit.toast(ctx, "账号信息已更新")
                }
                is AuthOutcome.Failure -> {
                    UiKit.toast(ctx, "刷新失败：" + describe(r.reason))
                    if (r.reason is AuthFailure.PendingApproval || r.reason is AuthFailure.Banned) {
                        openAuthPage()
                    }
                }
                is AuthOutcome.Registered -> Unit
            }
        }
    }

    /**
     * 解绑设备。只列「非当前」的那台：服务端对当前设备直接回 400 forbidden，
     * 客户端先过滤能少一次无意义的往返。
     */
    private fun onUnbind() {
        val ctx = context ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            when (val r = container.authSession.syncProfile()) {
                is AuthOutcome.Success -> {
                    val others = r.user.devices.filter { !it.current }
                    if (others.isEmpty()) {
                        AlertDialog.Builder(requireContext())
                            .setTitle("解绑设备")
                            .setMessage(
                                "当前账号只登记了本机一台设备，没有可解绑的对象。\n" +
                                    "（服务端不允许解绑正在使用的设备）",
                            )
                            .setPositiveButton("知道了", null)
                            .show()
                        return@launch
                    }
                    val labels = others.map { d -> d.deviceName.ifBlank { d.deviceId } }.toTypedArray()
                    AlertDialog.Builder(requireContext())
                        .setTitle("选择要解绑的设备")
                        .setItems(labels) { _, which ->
                            val target = others[which]
                            viewLifecycleOwner.lifecycleScope.launch {
                                when (val u = container.authSession.unbind(target.deviceId)) {
                                    is ApiResult.Ok -> {
                                        val cur = container.authRepository.authCache.first()
                                        renderAccount(
                                            cur.copy(
                                                deviceCount = u.value.deviceCount,
                                                maxDevices = u.value.maxDevices,
                                            ),
                                        )
                                        UiKit.toast(
                                            ctx,
                                            "已解绑 " + labels[which] + "，当前 " +
                                                u.value.deviceCount + "/" + u.value.maxDevices,
                                        )
                                    }
                                    is ApiResult.Err -> UiKit.toast(ctx, u.message)
                                    is ApiResult.Net -> UiKit.toast(ctx, u.message)
                                }
                            }
                        }
                        .setNegativeButton(android.R.string.cancel, null)
                        .show()
                }
                is AuthOutcome.Failure -> UiKit.toast(ctx, "解绑失败：" + describe(r.reason))
                is AuthOutcome.Registered -> Unit
            }
        }
    }

    private fun describe(reason: AuthFailure): String = when (reason) {
        AuthFailure.PendingApproval -> "账号待审核"
        AuthFailure.Banned -> "账号已被封禁"
        AuthFailure.Expired -> "会员已到期"
        AuthFailure.DeviceLimit -> "设备数已达上限"
        AuthFailure.AccountLocked -> "登录已临时锁定"
        AuthFailure.BadCredentials -> "登录凭证错误"
        is AuthFailure.Server -> reason.message
        is AuthFailure.Network -> reason.message
    }
    private fun applyDark(enabled: Boolean) {
        AppCompatDelegate.setDefaultNightMode(
            if (enabled) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO,
        )
        viewLifecycleOwner.lifecycleScope.launch {
            container.prefsRepository.setDarkMode(enabled)
        }
    }

    private fun confirmClearCache() {
        AlertDialog.Builder(requireContext())
            .setTitle("清除缓存")
            .setMessage("将清除图片缓存与临时文件。歌单、下载记录、已下载音频不受影响。")
            .setPositiveButton("清除") { _, _ ->
                viewLifecycleOwner.lifecycleScope.launch {
                    val cleared = withContextIo {
                        val imageCache = requireContext().cacheDir
                        var bytes = 0L
                        imageCache.listFiles()?.forEach { f ->
                            bytes += f.length()
                            runCatching { f.delete() }
                        }
                        bytes
                    }
                    UiKit.toast(requireContext(), "已清除缓存 " + (cleared / 1024) + " KB")
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private suspend fun <T> withContextIo(block: () -> T): T =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { block() }
}





