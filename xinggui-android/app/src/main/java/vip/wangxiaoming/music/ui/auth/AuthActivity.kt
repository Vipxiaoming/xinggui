package vip.wangxiaoming.music.ui.auth

import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import vip.wangxiaoming.music.MainActivity
import vip.wangxiaoming.music.R
import vip.wangxiaoming.music.auth.AuthFailure
import vip.wangxiaoming.music.auth.AuthOutcome
import vip.wangxiaoming.music.auth.AuthSession
import vip.wangxiaoming.music.databinding.ActivityAuthBinding
import vip.wangxiaoming.music.di.AppContainer
import vip.wangxiaoming.music.ui.common.UiKit

/**
 * 登录页 + 全局登录闸门。
 *
 * 它是 launcher Activity：冷启动先进这里做一次会话检查
 * （有 refresh token → 静默续期 → 拉 me 确认 active），通过才进 MainActivity。
 * 这样「未登录 / 待审核 / 被封禁」在结构上就不可能看到任何功能页 ——
 * 不依赖每个页面各自防守，也就不存在漏判。
 */
class AuthActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAuthBinding
    private val container: AppContainer get() = AppContainer.get()
    private val session: AuthSession get() = container.authSession

    /** 冷启动检查只跑一次，避免 onResume 反复打网络 */
    private var gateChecked = false
    private var busy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAuthBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.tvAuthServerHint.text = "服务器：" + session.baseUrl()

        binding.btnAuthLogin.setOnClickListener { submitLogin() }
        binding.btnAuthGoRegister.setOnClickListener {
            startActivity(Intent(this, RegisterActivity::class.java))
            overridePendingTransition(0, 0)
        }
        binding.btnAuthUnbindOthers.setOnClickListener { showUnbindGuide() }
        binding.etAuthPassword.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) { submitLogin(); true } else false
        }
    }

    override fun onStart() {
        super.onStart()
        if (gateChecked) return
        gateChecked = true
        lifecycleScope.launch { runStartupGate() }
    }

    override fun onBackPressed() {
        // 登录页是栈底，返回键直接退到桌面，不留在空白页
        moveTaskToBack(true)
    }

    // ------------------------------------------------------------ 启动闸门

    private suspend fun runStartupGate() {
        if (!session.hasSession()) return          // 未登录：停留登录页
        setBusy(true, quietly = true)
        try {
            // 1) 续期：本地 access 过期就换新，refresh 也失效会清会话
            val tokens = if (container.tokenStore.isAccessExpired()) session.refreshTokens()
            else container.tokenStore.read()
            if (tokens == null) {
                // 续期失败有两种：① 服务端明确吊销（refresh 已被 TokenStore 清掉）→ 必须重新登录；
                // ② 网络不可达（令牌还在）→ 走离线模式，不能把用户挡在登录页。
                if (container.tokenStore.refreshTokenOrNull() != null) {
                    goMainOffline()
                    return
                }
                showError("登录状态已失效，请重新登录")
                return
            }
            // 2) 校验账号状态：pending / banned / expired 一律停在登录页
            when (val me = session.syncProfile()) {
                is AuthOutcome.Success -> {
                    if (me.user.status != "active") {
                        session.logout()
                        showError(statusText(me.user.status))
                        return
                    }
                    goMain()
                }
                is AuthOutcome.Failure -> {
                    // 仅「网络不可达」允许进入离线模式；
                    // pending / banned / 401 等认证类失败一律停在登录页。
                    if (me.reason is AuthFailure.Network && container.tokenStore.refreshTokenOrNull() != null) goMainOffline()
                    else showFailure(me.reason, quiet = true)
                }
                is AuthOutcome.Registered -> Unit
            }
        } finally {
            setBusy(false, quietly = true)
        }
    }

    private fun statusText(status: String): String = when (status) {
        "pending" -> "账号仍在等待管理员审核"
        "banned" -> "账号已被封禁"
        "rejected" -> "注册申请已被拒绝"
        "expired" -> "会员已到期"
        else -> "账号状态异常（$status）"
    }

    // ------------------------------------------------------------ 登录

    private fun submitLogin() {
        if (busy) return
        val username = binding.etAuthUsername.text.toString().trim()
        val password = binding.etAuthPassword.text.toString()

        AuthSession.validateUsername(username)?.let { showError(it); return }
        AuthSession.validatePassword(password)?.let { showError(it); return }

        hideError()
        binding.cardAuthDeviceLimit.visibility = View.GONE
        setBusy(true)
        lifecycleScope.launch {
            try {
                when (val r = session.login(username, password)) {
                    is AuthOutcome.Success -> goMain()
                    is AuthOutcome.Failure -> showFailure(r.reason, quiet = false)
                    is AuthOutcome.Registered -> showError("该账号状态为待审核，请联系管理员")
                }
            } finally {
                setBusy(false)
            }
        }
    }

    /**
     * 每种失败给不同文案与不同可操作项。这是任务书里「封禁/过期/设备超限分别提示」的落点。
     */
    private fun showFailure(reason: AuthFailure, quiet: Boolean) {
        when (reason) {
            AuthFailure.PendingApproval -> {
                showError("账号正在等待管理员审核，通过后即可登录")
                showPendingDialog()
            }
            AuthFailure.Banned -> showError("账号已被封禁，请联系管理员")
            AuthFailure.Expired -> showError("会员已到期，请联系管理员续期")
            AuthFailure.DeviceLimit -> {
                showError("设备数已达上限，请先解绑其他设备")
                binding.cardAuthDeviceLimit.visibility = View.VISIBLE
                binding.tvAuthDeviceLimitDesc.text =
                    "当前账号已在其它设备登录。下方可查看并解绑（需要该账号在本机有可用登录态）。"
            }
            AuthFailure.AccountLocked -> showError("连续登录失败次数过多，请 15 分钟后再试")
            AuthFailure.BadCredentials -> showError("用户名或密码错误")
            is AuthFailure.Server -> showError(reason.message)
            is AuthFailure.Network -> showError(reason.message)
        }
        if (!quiet) UiKit.toast(this, errorTextFor(reason))
    }

    private fun errorTextFor(reason: AuthFailure): String = when (reason) {
        AuthFailure.AccountLocked -> "账号已临时锁定"
        AuthFailure.DeviceLimit -> "设备数已达上限"
        AuthFailure.PendingApproval -> "等待管理员审核"
        AuthFailure.Banned -> "账号已被封禁"
        AuthFailure.Expired -> "会员已到期"
        AuthFailure.BadCredentials -> "用户名或密码错误"
        is AuthFailure.Server -> reason.message
        is AuthFailure.Network -> reason.message
    }

    private fun showPendingDialog() {
        AlertDialog.Builder(this)
            .setTitle("等待管理员审核")
            .setMessage("你的注册申请已提交。审核通过后即可登录，无需重复注册。")
            .setPositiveButton("知道了", null)
            .show()
    }

    // ------------------------------------------------------------ 解绑

    /**
     * 契约里 /v1/auth/unbind 需要 access token，而设备超限恰恰发生在「还没有 token」的时刻。
     * 因此这里分两条路：
     *  1) 本机还留着该账号的可用 refresh token（例如同步被服务端吊销过设备）→ 拉 me 列出设备，
     *     逐台解绑；
     *  2) 没有可用登录态 → 明确告知必须在已登录设备或管理后台解绑，不假装成功。
     */
    private fun showUnbindGuide() {
        lifecycleScope.launch {
            val token = session.validAccessToken()
            if (token == null) {
                AlertDialog.Builder(this@AuthActivity)
                    .setTitle("无法在本机解绑")
                    .setMessage(
                        "设备数已达上限时，本机还没有登录凭证，服务端不允许未认证的解绑请求。\n\n" +
                            "请在已登录的设备上进入「我的 → 解绑设备」，或由管理员在后台解绑一台后重试。",
                    )
                    .setPositiveButton("知道了", null)
                    .show()
                return@launch
            }
            when (val me = session.syncProfile()) {
                is AuthOutcome.Success -> showDevicePicker(me.user.devices)
                is AuthOutcome.Failure -> UiKit.toast(this@AuthActivity, errorTextFor(me.reason))
                is AuthOutcome.Registered -> Unit
            }
        }
    }

    private fun showDevicePicker(devices: List<vip.wangxiaoming.music.auth.AuthDevice>) {
        val others = devices.filter { !it.current }
        if (others.isEmpty()) {
            UiKit.toast(this, "没有可解绑的其他设备")
            return
        }
        val labels = others.map { d ->
            val name = d.deviceName.ifBlank { d.deviceId }
            name + if (d.lastSeenAt > 0) "（最近 " + formatDay(d.lastSeenAt) + "）" else ""
        }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("选择要解绑的设备")
            .setItems(labels) { _, which ->
                val target = others[which]
                lifecycleScope.launch {
                    when (val r = session.unbind(target.deviceId)) {
                        is vip.wangxiaoming.music.auth.ApiResult.Ok ->
                            UiKit.toast(this@AuthActivity, "已解绑，当前设备数 " + r.value.deviceCount + "/" + r.value.maxDevices)
                        is vip.wangxiaoming.music.auth.ApiResult.Err ->
                            UiKit.toast(this@AuthActivity, r.message)
                        is vip.wangxiaoming.music.auth.ApiResult.Net ->
                            UiKit.toast(this@AuthActivity, r.message)
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun formatDay(epochSeconds: Long): String = runCatching {
        java.time.Instant.ofEpochSecond(epochSeconds)
            .atZone(java.time.ZoneId.systemDefault())
            .format(java.time.format.DateTimeFormatter.ofPattern("MM-dd"))
    }.getOrDefault("")

    // ------------------------------------------------------------ 小工具

    /** 离线模式进入首页：本地有可用会话，只是此刻连不上服务端。 */
    private fun goMainOffline() {
        UiKit.toast(this, "离线模式，部分功能不可用")
        goMain()
    }

    private fun goMain() {
        startActivity(
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        )
        finish()
        overridePendingTransition(0, 0)
    }

    private fun setBusy(value: Boolean, quietly: Boolean = false) {
        busy = value
        if (!quietly) {
            binding.pbAuthLogin.visibility = if (value) View.VISIBLE else View.GONE
            binding.btnAuthLogin.text = if (value) "" else getString(R.string.auth_login)
        }
        binding.btnAuthLogin.isEnabled = !value
        binding.btnAuthGoRegister.isEnabled = !value
        binding.etAuthUsername.isEnabled = !value
        binding.etAuthPassword.isEnabled = !value
    }

    private fun showError(text: String) {
        binding.tvAuthError.text = text
        binding.tvAuthError.visibility = View.VISIBLE
    }

    private fun hideError() {
        binding.tvAuthError.visibility = View.GONE
    }
}
