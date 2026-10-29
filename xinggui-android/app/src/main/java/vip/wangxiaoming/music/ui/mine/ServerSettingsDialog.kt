package vip.wangxiaoming.music.ui.mine

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.LayoutInflater
import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import vip.wangxiaoming.music.R
import vip.wangxiaoming.music.auth.AuthApi
import vip.wangxiaoming.music.databinding.DialogServerSettingsBinding
import vip.wangxiaoming.music.ui.common.UiKit

/**
 * 「服务器设置」对话框。
 *
 * 为什么单独成文件：MineFragment 已经很长，这里只负责
 * 「展示当前基址 → 校验 → 保存 / 恢复默认 / 测试连接」，
 * 不碰登录态、不碰 DataStore，读写的唯一入口是 AuthApi。
 *
 * 三个动作分别挂在 AlertDialog 的 positive / neutral / negative 上：
 *   保存 → positive
 *   测试连接 → neutral
 *   恢复默认 → negative（这是破坏性较小的「回滚」动作，放右侧固定位）
 */
object ServerSettingsDialog {

    fun show(context: Context) {
        val ctx = context
        val inflater = LayoutInflater.from(ctx)
        val vb = DialogServerSettingsBinding.inflate(inflater)
        val current = AuthApi.resolveBaseUrl(ctx)

        vb.tvServerCurrent.text = "当前生效：" + current
        vb.etServerUrl.setText(current)
        vb.etServerUrl.setSelection(vb.etServerUrl.text?.length ?: 0)

        val dialog = AlertDialog.Builder(ctx)
            .setTitle("服务器设置")
            .setView(vb.root)
            .setPositiveButton("保存", null)
            .setNeutralButton("测试连接", null)
            .setNegativeButton("恢复默认", null)
            .create()

        dialog.setOnShowListener {
            // 「保存」要能拦住非法输入，所以不用默认的自动关闭。
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val raw = vb.etServerUrl.text?.toString().orEmpty()
                val normalized = AuthApi.normalizeBaseUrl(raw)
                if (normalized == null) {
                    showResult(vb, "地址不合法：必须以 http:// 或 https:// 开头，且不能带空格、路径或查询串。", true)
                    return@setOnClickListener
                }
                if (!AuthApi.saveBaseUrl(ctx, normalized)) {
                    showResult(vb, "保存失败：本地存储不可写，请重试。", true)
                    return@setOnClickListener
                }
                vb.tvServerCurrent.text = "当前生效：" + normalized
                vb.etServerUrl.setText(normalized)
                showResult(vb, "已保存，重启 App 后仍然生效。", false)
                // 不自动关闭：用户通常紧接着点「测试连接」验证一次。
            }

            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                val raw = vb.etServerUrl.text?.toString().orEmpty()
                val normalized = AuthApi.normalizeBaseUrl(raw)
                if (normalized == null) {
                    showResult(vb, "地址不合法：必须以 http:// 或 https:// 开头，且不能带空格、路径或查询串。", true)
                    return@setOnClickListener
                }
                showResult(vb, "正在连接 " + normalized + "/v1/health …", false)
                val button = dialog.getButton(AlertDialog.BUTTON_NEUTRAL)
                button.isEnabled = false
                // Activity 作用域：对话框可能先于页面销毁，这里只做一次请求，不持有 View 引用。
                val scope = (findActivity(ctx) as? androidx.lifecycle.LifecycleOwner)?.lifecycleScope
                if (scope == null) {
                    button.isEnabled = true
                    showResult(vb, "无法启动测试：当前上下文不是 Activity。", true)
                    return@setOnClickListener
                }
                scope.launch {
                    val r = AuthApi.checkHealth(normalized)
                    button.isEnabled = true
                    val text = buildString {
                        append(if (r.ok) "✓ " else "✗ ")
                        append(r.message)
                        if (r.elapsedMs > 0) append("（").append(r.elapsedMs).append(" ms）")
                        if (!r.ok && normalized.startsWith("http://") && !r.blockedByCleartext) {
                            append("\n提示：http 地址若被系统拦截会显示 CLEARTEXT_NOT_PERMITTED，可改用 https。")
                        }
                    }
                    showResult(vb, text, !r.ok)
                }
            }

            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener {
                AuthApi.clearSavedBaseUrl(ctx)
                val fallback = AuthApi.resolveBaseUrl(ctx)
                vb.etServerUrl.setText(fallback)
                vb.tvServerCurrent.text = "当前生效：" + fallback
                showResult(vb, "已恢复默认：" + fallback, false)
            }
        }

        dialog.show()
        // 让按钮宽度不被挤压，三个动作在窄屏也能点。
        dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.let { it.minWidth = 0 }
    }

    private fun showResult(vb: DialogServerSettingsBinding, text: String, isError: Boolean) {
        vb.tvServerResult.visibility = View.VISIBLE
        vb.tvServerResult.text = text
        val color = if (isError) {
            UiKit.color(vb.root.context, R.color.ink_700)
        } else {
            UiKit.color(vb.root.context, R.color.brand)
        }
        vb.tvServerResult.setTextColor(color)
    }
    /** Fragment.requireContext() 常常是 ContextThemeWrapper，需要逐层解包到 Activity。 */
    private fun findActivity(context: Context): Activity? {
        var c: Context? = context
        while (c is ContextWrapper) {
            if (c is Activity) return c
            c = c.baseContext
        }
        return c as? Activity
    }
}