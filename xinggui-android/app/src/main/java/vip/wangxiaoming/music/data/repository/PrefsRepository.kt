package vip.wangxiaoming.music.data.repository

import kotlinx.coroutines.flow.Flow
import vip.wangxiaoming.music.data.model.AuthCache
import vip.wangxiaoming.music.data.model.UserPrefs
import vip.wangxiaoming.music.data.prefs.PlaybackState
import vip.wangxiaoming.music.data.prefs.PrefsDataStore

/** 偏好 + 账户缓存 + 播放状态。全部 Flow 暴露，UI 直接 collect。 */
class PrefsRepository(private val store: PrefsDataStore) {

    val userPrefs: Flow<UserPrefs> = store.userPrefs

    val authCache: Flow<AuthCache> = store.authCache

    val playbackState: Flow<PlaybackState> = store.playbackState

    suspend fun update(block: (UserPrefs) -> UserPrefs) = store.updatePrefs(block)

    suspend fun setQuality(quality: String) = store.updatePrefs { it.copy(quality = quality) }

    suspend fun setMaxConcurrentDownloads(n: Int) =
        store.updatePrefs { it.copy(maxConcurrentDownloads = n) }

    suspend fun setShowBatchDownloadBtn(show: Boolean) =
        store.updatePrefs { it.copy(showBatchDownloadBtn = show) }

    suspend fun setDarkMode(dark: Boolean) = store.updatePrefs { it.copy(darkMode = dark) }

    suspend fun setFloatingPosition(x: Float, y: Float) =
        store.updatePrefs { it.copy(floatingX = x.coerceIn(0f, 1f), floatingY = y.coerceIn(0f, 1f)) }

    suspend fun setGlobalSource(source: String) = store.updatePrefs { it.copy(globalSource = source) }

    suspend fun setLastSyncAt(at: Long) = store.updatePrefs { it.copy(lastSyncAt = at) }

    suspend fun setUpdateSkippedVersion(version: Int) =
        store.updatePrefs { it.copy(updateSkippedVersion = version) }

    suspend fun saveAuth(auth: AuthCache) = store.setAuthCache(auth)

    suspend fun clearAuth() = store.clearAuthCache()

    suspend fun savePlaybackState(state: PlaybackState) = store.savePlaybackState(state)

    suspend fun clearPlaybackState() = store.clearPlaybackState()
}