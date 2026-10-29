package vip.wangxiaoming.music.data.secure

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/** 一次登录会话里需要长期保存的两枚 token。 */
data class AuthTokens(
    val accessToken: String,
    val refreshToken: String,
    /** 本地推算的 access 过期时刻（epoch ms），0 表示未知 */
    val accessExpiresAt: Long = 0L,
)

/**
 * Token 专用存储：Android Keystore 里生成 AES256-GCM 主密钥，
 * 由 EncryptedSharedPreferences 加密键与值。
 *
 * 使用独立的 prefs 文件（xinggui_secure），与普通偏好彻底分开：
 * 备份开关、导出、以及后续可能的「清缓存」都不会碰到 token。
 *
 * 失败的兜底：部分机型 Keystore 不可用（老旧 ROM、被 root 工具破坏的 keystore）。
 * 此时 EncryptedSharedPreferences 构造会抛异常。这里降级为普通 prefs 并打 warn 日志，
 * 保证登录流程不因存储层崩掉 —— 安全性降级是显式的、可观测的，
 * 通过 [isEncryptedBacked] 暴露给上层（可用于上报/提示）。
 */
class TokenStore(context: Context) {

    private val prefs: SharedPreferences
    private val encrypted: Boolean

    init {
        val app = context.applicationContext
        var p: SharedPreferences? = null
        var ok = false
        try {
            val masterKey = MasterKey.Builder(app)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            p = EncryptedSharedPreferences.create(
                app,
                FILE_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
            ok = true
        } catch (e: Exception) {
            Log.w(TAG, "EncryptedSharedPreferences unavailable, falling back to plain prefs", e)
        }
        if (p == null) {
            p = app.getSharedPreferences(FALLBACK_FILE_NAME, Context.MODE_PRIVATE)
        }
        prefs = p
        encrypted = ok
    }

    /** false 表示当前运行在明文降级模式（Keystore 不可用）。 */
    val isEncryptedBacked: Boolean get() = encrypted

    fun read(): AuthTokens? {
        val access = prefs.getString(KEY_ACCESS, null) ?: return null
        val refresh = prefs.getString(KEY_REFRESH, null) ?: return null
        if (access.isEmpty() || refresh.isEmpty()) return null
        return AuthTokens(
            accessToken = access,
            refreshToken = refresh,
            accessExpiresAt = prefs.getLong(KEY_ACCESS_EXPIRES_AT, 0L),
        )
    }

    fun save(tokens: AuthTokens) {
        prefs.edit()
            .putString(KEY_ACCESS, tokens.accessToken)
            .putString(KEY_REFRESH, tokens.refreshToken)
            .putLong(KEY_ACCESS_EXPIRES_AT, tokens.accessExpiresAt)
            .apply()
    }

    /** 只换 access，refresh 保持不变（静默续期用）。 */
    fun updateAccess(accessToken: String, accessExpiresAt: Long) {
        prefs.edit()
            .putString(KEY_ACCESS, accessToken)
            .putLong(KEY_ACCESS_EXPIRES_AT, accessExpiresAt)
            .apply()
    }

    fun clear() {
        prefs.edit()
            .remove(KEY_ACCESS)
            .remove(KEY_REFRESH)
            .remove(KEY_ACCESS_EXPIRES_AT)
            .apply()
    }

    fun accessTokenOrNull(): String? = read()?.accessToken

    fun refreshTokenOrNull(): String? = read()?.refreshToken

    /** 本地判断 access 是否过期，留 60s 余量避免边界上打穿。 */
    fun isAccessExpired(now: Long = System.currentTimeMillis(), skewMs: Long = 60_000L): Boolean {
        val exp = read()?.accessExpiresAt ?: return true
        if (exp <= 0L) return false // 未知过期时间就交给服务端 401 判定
        return now + skewMs >= exp
    }

    private companion object {
        const val TAG = "TokenStore"
        const val FILE_NAME = "xinggui_secure"
        const val FALLBACK_FILE_NAME = "xinggui_secure_plain"
        const val KEY_ACCESS = "token_access"
        const val KEY_REFRESH = "token_refresh"
        const val KEY_ACCESS_EXPIRES_AT = "token_access_expires_at"
    }
}