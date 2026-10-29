package vip.wangxiaoming.music.ui.theme

/**
 * P2 设计 token：颜色。
 *
 * 来源：apktool-out/res/values/aurora_colors.xml（日间）与 values-night/aurora_colors.xml（暗色），
 * 只保留 26 个业务布局实际引用到的 29 个键（闭包计算结果，见 reports/16-p2-skeleton-report.md §3）。
 *
 * 本工程 UI 路线是「全 XML + ViewBinding」（决策 B5-a），因此这里的常量用于：
 *  1. 代码内动态着色（如根据状态改 tint）；
 *  2. 自定义 View 的 onDraw；
 *  3. 后续若引入 Compose 时直接复用，不需要再抄一遍。
 * XML 侧同名颜色在 values/aurora_colors.xml 与 values-night/aurora_colors.xml 中已定义。
 */
object AuroraColors {

    // ---------- 品牌 ----------
    const val BRAND = 0xFF2F6BFF.toInt()
    const val BRAND_DEEP = 0xFF1F51DB.toInt()
    const val BRAND_LIGHT = 0xFF7BA4FF.toInt()
    const val BRAND_SOFT = 0xFFEEF3FF.toInt()
    const val BRAND_TINT = 0xFFD6E2FF.toInt()

    const val ON_BRAND = 0xFFFFFFFF.toInt()
    const val ON_BRAND_MUTED = 0xFFC9D8FF.toInt()

    // ---------- 文字层级 ----------
    const val INK_900 = 0xFF121316.toInt()
    const val INK_700 = 0xFF35383E.toInt()
    const val INK_600 = 0xFF5A5E66.toInt()
    const val INK_500 = 0xFF83878F.toInt()
    const val INK_400 = 0xFFA8ADB5.toInt()
    const val INK_300 = 0xFFC9CDD4.toInt()
    const val INK_200 = 0xFFE6E8EC.toInt()

    // ---------- 表面 / 描边 ----------
    const val SURFACE = 0xFFFFFFFF.toInt()
    const val SURFACE_MUTED = 0xFFF2F3F6.toInt()
    const val SURFACE_SUNKEN = 0xFFF7F8FA.toInt()
    const val LINE = 0xFFEFF0F3.toInt()
    const val LINE_SOFT = 0xFFF6F7F9.toInt()

    // ---------- 语义 ----------
    const val SUCCESS = 0xFF12B76A.toInt()
    const val WARNING = 0xFFFFB020.toInt()
    const val DANGER = 0xFFFF4D4F.toInt()
    const val INFO = 0xFF2F6BFF.toInt()
    const val RANK_RED = 0xFFFF4D4F.toInt()
    const val RANK_RED_DEEP = 0xFFE0343A.toInt()

    // ---------- 黑胶 ----------
    const val VINYL_BASE = 0xFF121316.toInt()
    const val VINYL_GROOVE = 0xFF1E2024.toInt()

    // ---------- 玻璃 / 弹层 ----------
    const val GLASS_STROKE = 0x33FFFFFF
    const val DIALOG_EDGE = 0x0F000000
    const val DIALOG_SHADOW = 0x260F1626
}

/** 暗色覆盖（只覆盖 values-night 里出现的 17 个键，其余继承日间）。 */
object AuroraColorsDark {
    const val BRAND_SOFT = 0xFF1A2340.toInt()
    const val BRAND_TINT = 0xFF26355C.toInt()
    const val INK_900 = 0xFFEDEFF3.toInt()
    const val INK_700 = 0xFFB4B9C1.toInt()
    const val INK_600 = 0xFF959BA4.toInt()
    const val INK_500 = 0xFF8A9099.toInt()
    const val INK_400 = 0xFF6B7078.toInt()
    const val INK_300 = 0xFF4E535B.toInt()
    const val INK_200 = 0xFF34383E.toInt()
    const val SURFACE = 0xFF16181D.toInt()
    const val SURFACE_MUTED = 0xFF1E2127.toInt()
    const val SURFACE_SUNKEN = 0xFF101216.toInt()
    const val LINE = 0xFF24272C.toInt()
    const val LINE_SOFT = 0xFF1B1E23.toInt()
    const val GLASS_STROKE = 0x1FFFFFFF
    const val DIALOG_EDGE = 0x24FFFFFF
    const val DIALOG_SHADOW = 0x66000000
}

/** 按 isNight 取值的统一入口，避免各处写 if (night) A else B。 */
object AuroraPalette {
    fun brandSoft(night: Boolean) = if (night) AuroraColorsDark.BRAND_SOFT else AuroraColors.BRAND_SOFT
    fun ink900(night: Boolean) = if (night) AuroraColorsDark.INK_900 else AuroraColors.INK_900
    fun ink700(night: Boolean) = if (night) AuroraColorsDark.INK_700 else AuroraColors.INK_700
    fun ink500(night: Boolean) = if (night) AuroraColorsDark.INK_500 else AuroraColors.INK_500
    fun surface(night: Boolean) = if (night) AuroraColorsDark.SURFACE else AuroraColors.SURFACE
    fun line(night: Boolean) = if (night) AuroraColorsDark.LINE else AuroraColors.LINE

    /** 带透明度的品牌色，用于 ripple / scrim（替代原版 8 处手写 #xx2F6BFF）。 */
    fun brandAlpha(alpha: Float) =
        (AuroraColors.BRAND and 0x00FFFFFF) or ((alpha.coerceIn(0f, 1f) * 255).toInt() shl 24)
}