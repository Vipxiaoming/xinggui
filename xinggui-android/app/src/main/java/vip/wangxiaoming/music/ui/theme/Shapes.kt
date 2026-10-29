package vip.wangxiaoming.music.ui.theme

/**
 * P2 设计 token：圆角。
 *
 * 与 res/values/aurora_dimens.xml 的 radius_* 一一对应，单位 dp：
 *   xs=6  sm=12  md=16  lg=20  dialog=24  pill=100
 * 原版另有 radius_bar=2（进度条），不属于圆角档位，单独列出。
 */
object AuroraRadius {
    const val XS = 6f
    const val SM = 12f
    const val MD = 16f
    const val LG = 20f
    const val DIALOG = 24f
    const val PILL = 100f

    /** 进度条圆角，原版 radius_bar */
    const val BAR = 2f
}

object AuroraStroke {
    /** 0.5dp 发丝线，原版 stroke_hairline */
    const val HAIRLINE = 0.5f

    /** 1dp 常规描边，原版 stroke_normal */
    const val NORMAL = 1f
}