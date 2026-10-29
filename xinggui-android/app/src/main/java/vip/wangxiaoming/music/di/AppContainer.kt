package vip.wangxiaoming.music.di

import android.content.Context
import vip.wangxiaoming.music.auth.AuthSession
import vip.wangxiaoming.music.data.local.AppDatabase
import vip.wangxiaoming.music.data.prefs.PrefsDataStore
import vip.wangxiaoming.music.data.repository.AuthRepository
import vip.wangxiaoming.music.data.repository.DownloadRepository
import vip.wangxiaoming.music.data.repository.HistoryRepository
import vip.wangxiaoming.music.data.repository.PlaylistRepository
import vip.wangxiaoming.music.data.repository.PrefsRepository
import vip.wangxiaoming.music.data.secure.TokenStore

/**
 * 手写依赖容器（不用 Hilt）。
 *
 * 理由：当前只有 1 个 Activity、5 个 Repository。Hilt 会引入 kapt/ksp processor +
 * 生成代码 + 2 个插件，编译时间涨 40%+，而收益只有「少写几行构造」。
 * 等到 feature 模块拆出去、ViewModel 数量上到 10+ 再考虑迁移，
 * 届时只需把这里的 getter 换成 @Provides 即可，调用点不动。
 *
 * 生命周期：进程级单例，由 Application 初始化一次（见 XingGuiApp）。
 */
class AppContainer(context: Context) {

    private val appContext = context.applicationContext

    val database: AppDatabase by lazy { AppDatabase.get(appContext) }

    val prefsDataStore: PrefsDataStore by lazy { PrefsDataStore(appContext) }

    val tokenStore: TokenStore by lazy { TokenStore(appContext) }

    val prefsRepository: PrefsRepository by lazy { PrefsRepository(prefsDataStore) }

    val authRepository: AuthRepository by lazy { AuthRepository(prefsRepository, tokenStore) }

    /** P8 认证会话：网络 + 加密 token + 明文账户缓存三者合一，UI 只依赖它。 */
    val authSession: AuthSession by lazy { AuthSession(appContext, prefsRepository, tokenStore) }

    val historyRepository: HistoryRepository by lazy {
        HistoryRepository(database.playbackHistoryDao())
    }

    val playlistRepository: PlaylistRepository by lazy {
        PlaylistRepository(database, database.customPlaylistDao(), database.playlistSongDao())
    }

    val downloadRepository: DownloadRepository by lazy {
        DownloadRepository(database.downloadTaskDao())
    }

    companion object {
        @Volatile
        private var instance: AppContainer? = null

        fun init(context: Context) {
            if (instance == null) {
                synchronized(this) {
                    if (instance == null) instance = AppContainer(context)
                }
            }
        }

        fun get(): AppContainer = instance
            ?: error("AppContainer 未初始化：请确认 XingGuiApp 已注册到 AndroidManifest")

        fun peek(): AppContainer? = instance
    }
}
