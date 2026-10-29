package vip.wangxiaoming.music.data.prefs

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import vip.wangxiaoming.music.data.model.AuthCache
import vip.wangxiaoming.music.data.model.UserPrefs

/** 播放进度等设备本地状态，对应原版 playback_state 单行表。 */
data class PlaybackState(
    val queueJson: String = "",
    val currentIndex: Int = 0,
    val progressMs: Long = 0L,
    /** 0 顺序 / 1 单曲 / 2 随机 */
    val playMode: Int = 0,
)

/**
 * 单一 Preferences DataStore（一个文件、一次打开、全进程共享）。
 *
 * 为什么用 Preferences 而不是 Proto：P3 只有 8 + 6 + 4 个标量键，
 * Proto 要额外引入 protobuf 插件与 .proto 编译，收益不抵复杂度。
 * 需要 proto 时再换，调用方只依赖 PrefsRepository 的接口。
 */
private val Context.prefsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "xinggui_prefs",
)

class PrefsDataStore(context: Context) {

    private val store = context.applicationContext.prefsDataStore

    private object Keys {
        val quality = stringPreferencesKey("key_quality")
        val maxConcurrent = intPreferencesKey("key_max_concurrent_downloads")
        val showBatch = booleanPreferencesKey("key_show_batch_download_btn")
        val darkMode = booleanPreferencesKey("key_dark_mode")
        val floatingX = floatPreferencesKey("key_floating_x")
        val floatingY = floatPreferencesKey("key_floating_y")
        val globalSource = stringPreferencesKey("key_global_source")
        val lastSyncAt = longPreferencesKey("key_last_sync_at")
        val updateSkippedVersion = intPreferencesKey("key_update_skipped_version")

        val authUsername = stringPreferencesKey("key_auth_username")
        val authVipType = stringPreferencesKey("key_auth_vip_type")
        val authExpireAt = stringPreferencesKey("key_auth_expire_at")
        val authDeviceCount = intPreferencesKey("key_auth_device_count")
        val authMaxDevices = intPreferencesKey("key_auth_max_devices")
        val authDeviceId = stringPreferencesKey("key_saved_device_id")

        val stateQueue = stringPreferencesKey("key_state_queue_json")
        val stateIndex = intPreferencesKey("key_state_current_index")
        val stateProgress = longPreferencesKey("key_state_progress_ms")
        val stateMode = intPreferencesKey("key_state_play_mode")
    }

    /** 坏文件（损坏/权限）不让 App 崩，回退成默认值并记一条日志。 */
    private fun DataStore<Preferences>.safeData(): Flow<Preferences> =
        data.catch { e ->
            if (e is IOException) {
                Log.w(TAG, "prefs read failed, using defaults", e)
                emit(emptyPreferences())
            } else {
                throw e
            }
        }

    val userPrefs: Flow<UserPrefs> = store.safeData()
        .map { p ->
            UserPrefs(
                quality = UserPrefs.qualityOrDefault(p[Keys.quality]),
                maxConcurrentDownloads = UserPrefs.maxConcurrent(
                    p[Keys.maxConcurrent] ?: UserPrefs.DEFAULT_MAX_CONCURRENT
                ),
                showBatchDownloadBtn = p[Keys.showBatch] ?: true,
                darkMode = p[Keys.darkMode] ?: false,
                floatingX = p[Keys.floatingX] ?: 0f,
                floatingY = p[Keys.floatingY] ?: 0f,
                globalSource = UserPrefs.sourceOrDefault(p[Keys.globalSource]),
                lastSyncAt = p[Keys.lastSyncAt] ?: 0L,
                updateSkippedVersion = p[Keys.updateSkippedVersion] ?: 0,
            )
        }
        .distinctUntilChanged()

    val authCache: Flow<AuthCache> = store.safeData()
        .map { p ->
            AuthCache(
                username = p[Keys.authUsername] ?: "",
                vipType = p[Keys.authVipType] ?: "free",
                expireAt = p[Keys.authExpireAt] ?: "",
                deviceCount = p[Keys.authDeviceCount] ?: 0,
                maxDevices = p[Keys.authMaxDevices] ?: 2,
                deviceId = p[Keys.authDeviceId] ?: "",
            )
        }
        .distinctUntilChanged()

    val playbackState: Flow<PlaybackState> = store.safeData()
        .map { p ->
            PlaybackState(
                queueJson = p[Keys.stateQueue] ?: "",
                currentIndex = p[Keys.stateIndex] ?: 0,
                progressMs = p[Keys.stateProgress] ?: 0L,
                playMode = p[Keys.stateMode] ?: 0,
            )
        }
        .distinctUntilChanged()

    suspend fun updatePrefs(block: (UserPrefs) -> UserPrefs) {
        store.edit { p ->
            val current = UserPrefs(
                quality = UserPrefs.qualityOrDefault(p[Keys.quality]),
                maxConcurrentDownloads = UserPrefs.maxConcurrent(
                    p[Keys.maxConcurrent] ?: UserPrefs.DEFAULT_MAX_CONCURRENT
                ),
                showBatchDownloadBtn = p[Keys.showBatch] ?: true,
                darkMode = p[Keys.darkMode] ?: false,
                floatingX = p[Keys.floatingX] ?: 0f,
                floatingY = p[Keys.floatingY] ?: 0f,
                globalSource = UserPrefs.sourceOrDefault(p[Keys.globalSource]),
                lastSyncAt = p[Keys.lastSyncAt] ?: 0L,
                updateSkippedVersion = p[Keys.updateSkippedVersion] ?: 0,
            )
            val next = block(current)
            p[Keys.quality] = UserPrefs.qualityOrDefault(next.quality)
            p[Keys.maxConcurrent] = UserPrefs.maxConcurrent(next.maxConcurrentDownloads)
            p[Keys.showBatch] = next.showBatchDownloadBtn
            p[Keys.darkMode] = next.darkMode
            p[Keys.floatingX] = next.floatingX.coerceIn(0f, 1f)
            p[Keys.floatingY] = next.floatingY.coerceIn(0f, 1f)
            p[Keys.globalSource] = UserPrefs.sourceOrDefault(next.globalSource)
            p[Keys.lastSyncAt] = next.lastSyncAt
            p[Keys.updateSkippedVersion] = next.updateSkippedVersion
        }
    }

    suspend fun setAuthCache(auth: AuthCache) {
        store.edit { p ->
            p[Keys.authUsername] = auth.username
            p[Keys.authVipType] = auth.vipType
            p[Keys.authExpireAt] = auth.expireAt
            p[Keys.authDeviceCount] = auth.deviceCount
            p[Keys.authMaxDevices] = auth.maxDevices
            p[Keys.authDeviceId] = auth.deviceId
        }
    }

    suspend fun clearAuthCache() {
        store.edit { p ->
            p.remove(Keys.authUsername)
            p.remove(Keys.authVipType)
            p.remove(Keys.authExpireAt)
            p.remove(Keys.authDeviceCount)
            p.remove(Keys.authMaxDevices)
            // deviceId 是设备标识，退出登录不清，避免重登被算成新设备
        }
    }

    suspend fun savePlaybackState(state: PlaybackState) {
        store.edit { p ->
            p[Keys.stateQueue] = state.queueJson
            p[Keys.stateIndex] = state.currentIndex
            p[Keys.stateProgress] = state.progressMs
            p[Keys.stateMode] = state.playMode
        }
    }

    suspend fun clearPlaybackState() {
        store.edit { p ->
            p.remove(Keys.stateQueue)
            p.remove(Keys.stateIndex)
            p.remove(Keys.stateProgress)
            p.remove(Keys.stateMode)
        }
    }

    private companion object {
        const val TAG = "PrefsDataStore"
    }
}