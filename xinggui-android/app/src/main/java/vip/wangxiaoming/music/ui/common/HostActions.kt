package vip.wangxiaoming.music.ui.common

/**
 * Fragment 到 Activity 的导航出口。
 *
 * 为什么不用 Navigation Component：当前只有 4 个 tab + 5 个二级页，全部是
 * 「替换容器内容」的线性导航，Navigation 会引入 navigation-fragment 依赖、
 * 一套 XML 图和 Safe Args。用显式回调更直观，也让 P6 不碰 build.gradle.kts。
 */
interface HostActions {

    /** 打开搜索页并带上关键词（空串则只聚焦输入框）。 */
    fun openSearch(keyword: String)

    /** 从底部弹出全屏播放器。 */
    fun openPlayer()

    fun closePlayer()

    fun openSettings()

    fun openDownloadHistory()

    /** 打开「我的喜欢」收藏列表（P6 遗留功能接线）。 */
    fun openFavorites()

    /** 二级页返回。 */
    fun closeSecondary()

    /** 切到某个 tab（0 首页 / 1 歌单 / 2 下载 / 3 我的）。 */
    fun selectTab(index: Int)

    /** 当前是否处于二级页（搜索/设置/历史）。 */
    fun isSecondaryOpen(): Boolean
}