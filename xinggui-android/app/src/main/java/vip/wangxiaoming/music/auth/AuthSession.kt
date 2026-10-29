package vip.wangxiaoming.music.auth

import android.content.Context
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import vip.wangxiaoming.music.data.model.AuthCache
import vip.wangxiaoming.music.data.repository.PrefsRepository
import vip.wangxiaoming.music.data.secure.AuthTokens
import vip.wangxiaoming.music.data.secure.TokenStore
import java.util.UUID

/** 登录/刷新失败的原因，UI 按它给不同文案与动作。 */
sealed interface AuthFailure {
    data object PendingApproval : AuthFailure
    data object Banned : AuthFailure
    data object Expired : AuthFailure
    data object DeviceLimit : AuthFailure
    data object AccountLocked : AuthFailure
    data object BadCredentials : AuthFailure
    data class Server(val code: String, val message: String) : AuthFailure
    data class Network(val message: String) : AuthFailure
}

sealed interface AuthOutcome {
    data class Success(val user: AuthUser) : AuthOutcome
    data class Failure(val reason: AuthFailure) : AuthOutcome
    /** 注册成功但待审核（服务端 201 pending）。 */
    data class Registered(val pending: Boolean, val message: String) : AuthOutcome
}

/** 全局登录态变化：token 被服务端判定失效时，UI 必须回登录页。 */
sealed interface AuthEvent {
    data class SignedOut(val reason: String) : AuthEvent
}

/**
 * 认证会话门面 —— 把「TokenStore（加密）」「AuthCache（明文 DataStore）」
 * 「AuthApi（网络）」三者合成一个对 UI 稳定的接口。
 *
 * 并发约定：refresh 用 Mutex 串行化。多个页面同时撞到 401 时只会打一次 refresh，
 * 其余等结果，避免并发刷新导致服务端把旧 refresh 用一次就作废、后到的全部失败。
 */
class AuthSession(
    private val context: Context,
    private val prefs: PrefsRepository,
    private val tokens: TokenStore,
) {

    private val refreshMutex = Mutex()

    private val _events = MutableSharedFlow<AuthEvent>(extraBufferCapacity = 4)
    val events: SharedFlow<AuthEvent> = _events.asSharedFlow()

    fun baseUrl(): String = AuthApi.resolveBaseUrl(context)

    // ------------------------------------------------------------ 设备标识

    /**
     * 设备 ID 必须跨「退出登录」保持稳定：服务端按 deviceId 判断是不是同一台机器，
     * 换了 ID 就等于新设备，2 台上限会被自己占满。因此它存在 AuthCache 里，
     * clearAuthCache() 刻意不删这个键（见 PrefsDataStore）。
     */
    suspend fun deviceId(): String {
        val cached = prefs.authCache.first().deviceId
        if (cached.isNotBlank()) return cached
        val fresh = UUID.randomUUID().toString().replace("-", "").take(16)
        prefs.updateAuthCacheCompat { it.copy(deviceId = fresh) }
        return fresh
    }

    private suspend fun PrefsRepository.updateAuthCacheCompat(block: (AuthCache) -> AuthCache) {
        val current = authCache.first()
        saveAuth(block(current))
    }

    // ------------------------------------------------------------ 注册 / 登录

    suspend fun register(username: String, password: String): AuthOutcome {
        val dev = deviceId()
        return when (val r = AuthApi.register(
            baseUrl(), username, password, dev, AuthApi.deviceName(),
        )) {
            is ApiResult.Ok -> AuthOutcome.Registered(r.value.pending, r.value.message)
            is ApiResult.Err -> AuthOutcome.Failure(mapError(r))
            is ApiResult.Net -> AuthOutcome.Failure(AuthFailure.Network(r.message))
        }
    }

    suspend fun login(username: String, password: String): AuthOutcome {
        val dev = deviceId()
        return when (val r = AuthApi.login(
            baseUrl(), username, password, dev, AuthApi.deviceName(),
        )) {
            is ApiResult.Ok -> {
                persist(r.value)
                AuthOutcome.Success(r.value.user)
            }
            is ApiResult.Err -> AuthOutcome.Failure(mapError(r))
            is ApiResult.Net -> AuthOutcome.Failure(AuthFailure.Network(r.message))
        }
    }

    private suspend fun persist(result: LoginResult) {
        val dev = deviceId()
        tokens.save(
            AuthTokens(
                accessToken = result.accessToken,
                refreshToken = result.refreshToken,
                accessExpiresAt = System.currentTimeMillis() + result.expiresInSeconds * 1000L,
            ),
        )
        prefs.saveAuth(toCache(result.user, dev))
    }

    private fun toCache(user: AuthUser, deviceId: String) = AuthCache(
        username = user.username,
        vipType = user.vipType,
        expireAt = user.expireAt,
        deviceCount = user.deviceCount,
        maxDevices = user.maxDevices,
        deviceId = deviceId,
    )

    private fun mapError(e: ApiResult.Err): AuthFailure = when (e.code) {
        "pending_approval" -> AuthFailure.PendingApproval
        "banned" -> AuthFailure.Banned
        "expired" -> AuthFailure.Expired
        "device_limit" -> AuthFailure.DeviceLimit
        "account_locked", "rate_limited" -> AuthFailure.AccountLocked
        "bad_credentials" -> AuthFailure.BadCredentials
        else -> AuthFailure.Server(e.code, e.message)
    }

    // ------------------------------------------------------------ 会话维护

    /** 本地是否还有可用会话（有 refresh token 且有用户名）。 */
    suspend fun hasSession(): Boolean =
        tokens.refreshTokenOrNull() != null && prefs.authCache.first().isLoggedIn

    /**
     * 取一个可用 access token：本地没过期就直接用，过期则静默 refresh。
     * 返回 null 表示会话彻底失效（已清本地并广播 SignedOut）。
     */
    suspend fun validAccessToken(): String? {
        val existing = tokens.read() ?: return null.also { forceSignOut("登录状态已失效") }
        if (!tokens.isAccessExpired()) return existing.accessToken
        return refreshTokens()?.accessToken
    }

    /**
     * 供播放/下载层取一次「基址 + access token」，用于调用 /v1/resolve。
     * 两个都拿不到（未登录）时返回 null，调用方直接走本地解析，不当错误处理。
     */
    suspend fun resolveCredentials(): Pair<String, String>? {
        val token = validAccessToken() ?: return null
        return baseUrl() to token
    }

    /** 主动刷新（启动检查也走这里）。失败即清会话。 */
    suspend fun refreshTokens(): AuthTokens? = refreshMutex.withLock {
        val current = tokens.read() ?: return@withLock null
        when (val r = AuthApi.refresh(baseUrl(), current.refreshToken)) {
            is ApiResult.Ok -> {
                val expiresAt = System.currentTimeMillis() + r.value.expiresInSeconds * 1000L
                val next = AuthTokens(r.value.accessToken, r.value.refreshToken, expiresAt)
                tokens.save(next)
                next
            }
            is ApiResult.Err -> {
                // 401/403 才是「会话没了」；5xx 属于服务端临时故障，不该把用户踢下线
                if (r.http == 401 || r.http == 403) {
                    forceSignOut(r.message)
                    null
                } else null
            }
            is ApiResult.Net -> null
        }
    }

    /** 拉一次 /v1/auth/me，刷新本地缓存（用户名、设备数、到期日）。 */
    suspend fun syncProfile(): AuthOutcome {
        val access = validAccessToken()
            ?: return AuthOutcome.Failure(AuthFailure.Network("未登录"))
        return when (val r = AuthApi.me(baseUrl(), access)) {
            is ApiResult.Ok -> {
                val dev = deviceId()
                prefs.saveAuth(toCache(r.value, dev))
                AuthOutcome.Success(r.value)
            }
            is ApiResult.Err -> {
                if (r.http == 401) {
                    // access 可能刚好过期：再刷一次；仍失败才清
                    val again = refreshTokens()
                    if (again != null) {
                        when (val retry = AuthApi.me(baseUrl(), again.accessToken)) {
                            is ApiResult.Ok -> {
                                prefs.saveAuth(toCache(retry.value, deviceId()))
                                AuthOutcome.Success(retry.value)
                            }
                            is ApiResult.Err -> AuthOutcome.Failure(mapError(retry))
                            is ApiResult.Net -> AuthOutcome.Failure(AuthFailure.Network(retry.message))
                        }
                    } else {
                        AuthOutcome.Failure(AuthFailure.Server(r.code, r.message))
                    }
                } else {
                    AuthOutcome.Failure(mapError(r))
                }
            }
            is ApiResult.Net -> AuthOutcome.Failure(AuthFailure.Network(r.message))
        }
    }

    /** 解绑一台其他设备。当前设备服务端会拒绝（400 forbidden）。 */
    suspend fun unbind(deviceId: String): ApiResult<UnbindInfo> {
        val access = validAccessToken()
            ?: return ApiResult.Net("登录状态已失效")
        val r = AuthApi.unbind(baseUrl(), access, deviceId)
        if (r is ApiResult.Ok) {
            prefs.updateAuthCacheCompat {
                it.copy(deviceCount = r.value.deviceCount, maxDevices = r.value.maxDevices)
            }
        }
        return r
    }

    /** 退出登录：先告诉服务端吊销 refresh，再清本地。网络失败也照清。 */
    suspend fun logout() {
        val access = tokens.accessTokenOrNull()
        if (access != null) runCatching { AuthApi.logout(baseUrl(), access) }
        tokens.clear()
        prefs.clearAuth()
    }

    private suspend fun forceSignOut(reason: String) {
        tokens.clear()
        prefs.clearAuth()
        _events.tryEmit(AuthEvent.SignedOut(reason))
    }

    // ------------------------------------------------------------ 输入校验

    companion object {
        private val USERNAME = Regex("^[A-Za-z0-9_-]{3,32}$")

        fun validateUsername(raw: String): String? {
            val v = raw.trim()
            if (v.isEmpty()) return "请输入用户名"
            if (v.length !in 3..32) return "用户名需 3-32 位"
            if (!USERNAME.matches(v)) return "用户名只能包含字母、数字、下划线或连字符"
            return null
        }

        fun validatePassword(raw: String): String? = when {
            raw.isEmpty() -> "请输入密码"
            raw.length < 8 -> "密码至少 8 位"
            else -> null
        }
    }
}
