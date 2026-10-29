package vip.wangxiaoming.music.data.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import vip.wangxiaoming.music.data.model.AuthCache
import vip.wangxiaoming.music.data.secure.AuthTokens
import vip.wangxiaoming.music.data.secure.TokenStore

/**
 * 认证数据门面：把「非机密账户信息（DataStore）」与「token（加密存储）」
 * 合成一个对上层稳定的接口。UI/网络层都只依赖这里，不直接碰 TokenStore。
 */
class AuthRepository(
    private val prefs: PrefsRepository,
    private val tokens: TokenStore,
) {

    val authCache: Flow<AuthCache> = prefs.authCache

    /** 已登录 = 有 refresh token 且有用户名。缺一即视为未登录。 */
    val isLoggedIn: Flow<Boolean> =
        prefs.authCache.map { it.isLoggedIn && tokens.refreshTokenOrNull() != null }

    val currentUsername: Flow<String> = prefs.authCache.map { it.username }

    fun accessToken(): String? = tokens.accessTokenOrNull()

    fun refreshToken(): String? = tokens.refreshTokenOrNull()

    fun isAccessExpired(now: Long = System.currentTimeMillis()): Boolean =
        tokens.isAccessExpired(now)

    val isEncryptedStorage: Boolean get() = tokens.isEncryptedBacked

    suspend fun login(tokens: AuthTokens, cache: AuthCache) {
        this.tokens.save(tokens)
        prefs.saveAuth(cache)
    }

    suspend fun updateAccessToken(accessToken: String, expiresAt: Long) =
        tokens.updateAccess(accessToken, expiresAt)

    suspend fun updateAuthCache(block: (AuthCache) -> AuthCache) {
        val next = block(prefs.authCache.first())
        prefs.saveAuth(next)
    }

    /** 退出登录：token 与账户信息都清，deviceId 保留。 */
    suspend fun logout() {
        tokens.clear()
        prefs.clearAuth()
    }

    /** 服务端判定凭证失效（401 且 refresh 也失败）：本地直接清干净。 */
    suspend fun invalidate() = logout()
}