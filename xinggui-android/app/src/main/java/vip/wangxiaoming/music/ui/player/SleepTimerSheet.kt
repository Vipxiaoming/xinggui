package vip.wangxiaoming.music.ui.player

import android.content.Context
import android.view.LayoutInflater
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import vip.wangxiaoming.music.R
import vip.wangxiaoming.music.databinding.DialogBottomSleepTimerBinding
import vip.wangxiaoming.music.playback.SleepTimer
import vip.wangxiaoming.music.ui.common.UiKit

/**
 * 睡眠定时面板（P6 遗留功能）。
 *
 * 复用原版 [R.layout.dialog_bottom_sleep_timer]：5 个预设 chip + 自定义分钟输入 + 取消按钮。
 * 计时逻辑全部在 [SleepTimer]（进程级，不绑本面板），面板只做「投递分钟数 + 订阅倒计时」。
 *
 * 关闭面板不会取消定时 —— 这是与原版一致的行为：用户点「定时关闭」后通常就退出界面了，
 * 如果面板一关就取消，功能等于没用。取消必须显式点「取消当前定时」。
 *
 * @param owner 用于收集 Flow 的生命周期；不传时用 dialog 自身不收集（只做一次性设置）。
 */
object SleepTimerSheet {

    fun show(context: Context, owner: LifecycleOwner? = null) {
        val b = DialogBottomSleepTimerBinding.inflate(LayoutInflater.from(context))
        val dialog = BottomSheetDialog(context, R.style.AuroraBottomSheet)
        dialog.setContentView(b.root)

        val chips = listOf(
            b.btnTimer15 to 15,
            b.btnTimer30 to 30,
            b.btnTimer45 to 45,
            b.btnTimer60 to 60,
            b.btnTimer90 to 90,
        )

        fun renderState() {
            val active = SleepTimer.activeMinutes.value
            val left = SleepTimer.formatRemaining(SleepTimer.remainingMs.value)
            b.tvSleepTimerStatus.text = when {
                active <= 0 -> "未开启"
                left.isNotBlank() -> "剩余 " + left
                else -> active.toString() + " 分钟后关闭"
            }
            b.btnCancelTimer.visibility = if (active > 0) android.view.View.VISIBLE else android.view.View.GONE
            chips.forEach { (v, min) ->
                val selected = active == min
                v.background = ContextCompat.getDrawable(
                    context,
                    if (selected) R.drawable.bg_chip_selected else R.drawable.bg_category_chip_unselected,
                )
                (v as TextView).setTextColor(
                    ContextCompat.getColor(context, if (selected) R.color.on_brand else R.color.ink_700),
                )
            }
        }

        chips.forEach { (v, minutes) ->
            v.setOnClickListener {
                SleepTimer.start(context, minutes)
                renderState()
                UiKit.toast(context, minutes.toString() + " 分钟后自动暂停")
                dialog.dismiss()
            }
        }

        b.btnStartTimer.setOnClickListener {
            val raw = b.etCustomTimerMinutes.text?.toString().orEmpty().trim()
            val minutes = raw.toIntOrNull()
            if (minutes == null || minutes <= 0) {
                UiKit.toast(context, "请输入大于 0 的分钟数")
                return@setOnClickListener
            }
            if (minutes > 720) {
                UiKit.toast(context, "最长支持 12 小时")
                return@setOnClickListener
            }
            SleepTimer.start(context, minutes)
            renderState()
            UiKit.toast(context, minutes.toString() + " 分钟后自动暂停")
            dialog.dismiss()
        }

        b.btnCancelTimer.setOnClickListener {
            SleepTimer.cancel()
            renderState()
            UiKit.toast(context, "已取消定时关闭")
            dialog.dismiss()
        }

        renderState()

        // 面板停留期间持续刷新倒计时（用户可能只是想改档位，会盯着这个数字）。
        if (owner != null) {
            owner.lifecycleScope.launch {
                SleepTimer.remainingMs.collectLatest { renderState() }
            }
        }

        dialog.show()
        dialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED
    }
}