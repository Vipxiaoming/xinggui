package vip.wangxiaoming.music.ui.theme

/**
 * P2 设计 token：间距与尺寸（dp）。
 *
 * 4dp 基准栅格 6 档。原版布局的 padding/margin 绝大多数落在这 6 档内；
 * 例外是 statusBarPadding（由 WindowInsets 计算，不占栅格）与若干 2/5/10/14dp 微调，
 * 迁移时按最近档位归一。
 */
object AuroraSpacing {
    const val XS = 4f
    const val SM = 8f
    const val MD = 12f
    const val LG = 16f
    const val XL = 20f
    const val XXL = 24f

    /** 屏幕左右安全边距，原版 paddingStart/End 的众数 */
    const val SCREEN_H = 16f

    /** 列表项之间的垂直间距 */
    const val LIST_GAP = 8f
}

object AuroraSize {
    /** 底部导航高度，原版 activity_main 实测 56dp */
    const val NAV_BAR = 56f

    /** mini 播放器高度，原版实测 60dp */
    const val MINI_PLAYER = 60f

    /** 最小可点击区域，Material 规范 48dp */
    const val TOUCH_TARGET = 48f

    /** 列表封面，原版 item_song 实测 52dp */
    const val COVER_LIST = 52f

    /** 二级页 / 歌单头封面，原版 76dp */
    const val COVER_DETAIL = 76f

    /** 全屏播放器黑胶基准直径，原版实测 260dp（小屏按屏宽比例自适应） */
    const val VINYL_DIAMETER = 260f

    /** 底部弹层最大高度占比 */
    const val SHEET_MAX_HEIGHT_RATIO = 0.86f
}