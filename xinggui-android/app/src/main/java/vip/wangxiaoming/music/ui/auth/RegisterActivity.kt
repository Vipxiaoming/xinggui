package vip.wangxiaoming.music.ui.auth

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import vip.wangxiaoming.music.R
import vip.wangxiaoming.music.auth.AuthFailure
import vip.wangxiaoming.music.auth.AuthOutcome
import vip.wangxiaoming.music.auth.AuthSession
import vip.wangxiaoming.music.databinding.ActivityRegisterBinding
import vip.wangxiaoming.music.di.AppContainer
import vip.wangxiaoming.music.ui.common.UiKit

/**
 * 注册页。
 *
 * 关键设计：注册成功后**不留任何进入主界面的路径**。
 *  - 表单换成等待审核面板（不销毁 Activity，避免用户按返回键回到表单重复注册）；
 *  - 不写入任何 token（服务端 201 也不返回 token），因此即便有人绕过 UI 也进不了功能页；
 *  - 返回键在等待态被接管，只允许「返回登录页」这一个出口。
 */
class RegisterActivity : AppCompatActivity() {

    private lateinit var binding: ActivityRegisterBinding
    private val container: AppContainer get() = AppContainer.get()
    private val session: AuthSession get() = container.authSession

    private var busy = false
    private var pending = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityRegisterBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnRegisterSubmit.setOnClickListener { submit() }
        binding.btnRegisterGoLogin.setOnClickListener { finish() }
        binding.btnPendingBackToLogin.setOnClickListener { finish() }
        binding.etRegisterConfirm.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) { submit(); true } else false
        }
    }

    override fun onBackPressed() {
        if (pending) {
            // 等待审核态唯一合法出口是回登录页，别让用户退到桌面后误以为注册失败
            finish()
            return
        }
        super.onBackPressed()
    }

    private fun submit() {
        if (busy) return
        val username = binding.etRegisterUsername.text.toString().trim()
        val password = binding.etRegisterPassword.text.toString()
        val confirm = binding.etRegisterConfirm.text.toString()

        AuthSession.validateUsername(username)?.let { showError(it); return }
        AuthSession.validatePassword(password)?.let { showError(it); return }
        if (password != confirm) { showError("两次输入的密码不一致"); return }

        hideError()
        setBusy(true)
        lifecycleScope.launch {
            try {
                when (val r = session.register(username, password)) {
                    is AuthOutcome.Registered -> showPending(username, r.pending)
                    is AuthOutcome.Failure -> showError(describe(r.reason))
                    is AuthOutcome.Success -> showError("注册返回状态异常，请联系管理员")
                }
            } finally {
                setBusy(false)
            }
        }
    }

    private fun showPending(username: String, isPending: Boolean) {
        pending = true
        binding.registerFormScroll.visibility = View.GONE
        binding.registerPendingPanel.visibility = View.VISIBLE
        binding.tvPendingUsername.text = "@" + username
        if (!isPending) {
            // 服务端当前配置 requireApproval=false：已直接激活，但客户端仍不发放会话，
            // 统一让用户回登录页登录一次，保证「登录才能拿 token」这条链路唯一。
            binding.tvPendingTitle.text = "注册成功"
            binding.tvPendingDesc.text = "账号已激活，请返回登录页登录。"
        }
    }

    private fun describe(reason: AuthFailure): String = when (reason) {
        AuthFailure.PendingApproval -> "该用户名已在等待审核，请勿重复注册"
        AuthFailure.Banned -> "该用户名已被禁用，请联系管理员"
        is AuthFailure.Server -> when (reason.code) {
            "username_taken" -> "用户名已被占用，请换一个"
            "invalid_username" -> "用户名需 3-32 位字母、数字、下划线或连字符"
            "weak_password" -> "密码至少 8 位"
            "registration_closed" -> "当前未开放注册"
            "rate_limited" -> "操作过于频繁，请稍后再试"
            else -> reason.message
        }
        is AuthFailure.Network -> reason.message
        else -> "注册失败，请稍后重试"
    }

    private fun setBusy(value: Boolean) {
        busy = value
        binding.pbRegisterSubmit.visibility = if (value) View.VISIBLE else View.GONE
        binding.btnRegisterSubmit.text = if (value) "" else getString(R.string.register_submit)
        binding.btnRegisterSubmit.isEnabled = !value
        binding.btnRegisterGoLogin.isEnabled = !value
        binding.etRegisterUsername.isEnabled = !value
        binding.etRegisterPassword.isEnabled = !value
        binding.etRegisterConfirm.isEnabled = !value
    }

    private fun showError(text: String) {
        binding.tvRegisterError.text = text
        binding.tvRegisterError.visibility = View.VISIBLE
        UiKit.toast(this, text)
    }

    private fun hideError() {
        binding.tvRegisterError.visibility = View.GONE
    }
}
