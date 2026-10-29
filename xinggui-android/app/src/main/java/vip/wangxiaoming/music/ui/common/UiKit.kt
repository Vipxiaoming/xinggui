package vip.wangxiaoming.music.ui.common

import android.content.Context
import android.widget.Toast
import androidx.annotation.ColorRes
import androidx.core.content.ContextCompat
import vip.wangxiaoming.music.data.remote.model.Platform

/** 页面层共用的小工具。只放无状态纯函数，避免同一映射在多处漂移。 */
object UiKit {

    fun toast(context: Context?, text: String) {
        val ctx = context?.applicationContext ?: return
        Toast.makeText(ctx, text, Toast.LENGTH_SHORT).show()
    }

    fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density + 0.5f).toInt()

    fun color(context: Context, @ColorRes id: Int): Int = ContextCompat.getColor(context, id)

    /** 平台代码 → 中文名。四家与原版 Song.source 完全一致。 */
    fun sourceLabel(code: String?): String =
        Platform.of(code)?.display ?: (code?.uppercase() ?: "未知")

    /** 毫秒 → mm:ss */
    fun mmss(ms: Long): String {
        if (ms <= 0L) return "00:00"
        val total = ms / 1000
        return "%02d:%02d".format(total / 60, total % 60)
    }

    fun mmss(seconds: Int): String = mmss(seconds * 1000L)

    /** 播放量 18250000 → "1825.0万" */
    fun playCount(n: Long): String = when {
        n >= 100_000_000L -> String.format("%.1f亿", n / 100_000_000.0)
        n >= 10_000L -> String.format("%.1f万", n / 10_000.0)
        n > 0 -> n.toString()
        else -> ""
    }
}