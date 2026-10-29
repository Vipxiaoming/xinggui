package vip.wangxiaoming.music.ui.theme

import android.util.TypedValue
import android.widget.TextView

/**
 * P2 设计 token：字号体系。
 *
 * 取值依据：原版 41 个自有布局里出现过的 textSize 统计众数（reports/ui-analysis.md 字号章节），
 * 归纳成 5 档主字号 + 1 档数字。原版散布的 10/12/14/16/17/22/26 等一次性字号统一收敛到这里，
 * 迁移时按「视觉层级不变、具体数值归一」处理。
 */
object AuroraType {

    /** 大标题 / 空态主文案 28sp */
    const val DISPLAY = 28f

    /** 二级页标题 / 歌单名 / 弹层标题 18sp */
    const val TITLE = 18f

    /** 曲名 / 列表主文案 / 正文 15sp */
    const val BODY = 15f

    /** 歌手名 / 副文案 / 说明 13sp */
    const val BODY2 = 13f

    /** 标签 / 角标 / 时间 11sp */
    const val LABEL = 11f

    /** 进度时间、数量等数字，用等宽字体对齐 */
    const val NUMERIC = 11f

    // ---- 行高（sp）----
    const val LINE_HEIGHT_DISPLAY = 36f
    const val LINE_HEIGHT_TITLE = 26f
    const val LINE_HEIGHT_BODY = 22f
    const val LINE_HEIGHT_BODY2 = 19f
    const val LINE_HEIGHT_LABEL = 15f

    /**
     * 把字号档位应用到已有 TextView。
     * P2 不重排原版 XML 的 textSize，此方法供 P6 起的动态文本使用。
     */
    fun apply(tv: TextView, sizeSp: Float, lineHeightSp: Float = 0f) {
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        if (lineHeightSp > 0f) {
            val fm = tv.paint.fontMetrics
            val textHeightSp = (fm.descent - fm.ascent) / tv.resources.displayMetrics.scaledDensity
            val extraSp = lineHeightSp - textHeightSp
            tv.setLineSpacing(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_SP, extraSp, tv.resources.displayMetrics), 1f)
        }
    }
}