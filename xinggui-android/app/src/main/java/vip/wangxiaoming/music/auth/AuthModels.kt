package vip.wangxiaoming.music.auth

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** 认证层统一结果。UI 只按 Ok / Err / Net 三分支渲染。 */
sealed interface ApiResult<out T> {
    data class Ok<T>(val value: T) : ApiResult<T>

    /** 服务端明确返回了业务错误（HTTP 4xx/5xx + {"error":code}）。 */
    data class Err(
        val http: Int,
        val code: String,
        val message: String,
    ) : ApiResult<Nothing>

    /** 没拿到 HTTP 响应：超时、DNS、连接拒绝、证书、JSON 解析失败。 */
    data class Net(val message: String) : ApiResult<Nothing>
}

/** 服务端 /v1/auth/… 里的 user 对象。me 的 devices 与 login 的 user 共用。 */
data class AuthUser(
    val username: String = "",
    val status: String = "",
    val vipType: String = "free",
    /** 已格式化为 yyyy-MM-dd；服务端原值是 Unix 秒或空串 */
    val expireAt: String = "",
    val deviceCount: Int = 0,
    val maxDevices: Int = 2,
    val devices: List<AuthDevice> = emptyList(),
)

data class AuthDevice(
    val deviceId: String = "",
    val deviceName: String = "",
    val current: Boolean = false,
    val lastSeenAt: Long = 0L,
)

data class LoginResult(
    val accessToken: String,
    val refreshToken: String,
    val expiresInSeconds: Long,
    val user: AuthUser,
)

data class RefreshResult(
    val accessToken: String,
    val refreshToken: String,
    val expiresInSeconds: Long,
)

data class RegisterInfo(val status: String, val message: String) {
    val pending: Boolean get() = status == "pending"
}

data class UnbindInfo(val deviceCount: Int, val maxDevices: Int)

/**
 * 宽松 JSON 取值。服务端字段类型有漂移风险（expireAt 可能是 int 也可能是 string），
 * 所有取值都返回默认值，绝不因为一个字段把整次登录打挂。
 */
internal object JsonField {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
        explicitNulls = false
    }

    fun parseObject(raw: String): JsonObject? {
        val text = raw.trimStart('\uFEFF').trim()
        if (text.isEmpty()) return null
        return runCatching { json.parseToJsonElement(text) }.getOrNull() as? JsonObject
    }

    fun asObj(el: JsonElement?): JsonObject? = el as? JsonObject

    fun obj(o: JsonObject?, key: String): JsonObject? = asObj(o?.get(key))

    fun array(o: JsonObject?, key: String): List<JsonElement> {
        val arr = o?.get(key) as? JsonArray ?: return emptyList()
        return arr.toList()
    }

    fun str(o: JsonObject?, key: String): String? {
        val el = o?.get(key) ?: return null
        if (el is JsonNull) return null
        return (el as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotEmpty() }
    }

    fun long(o: JsonObject?, key: String): Long? {
        val el = o?.get(key) ?: return null
        if (el is JsonNull) return null
        val prim = el as? JsonPrimitive ?: return null
        return prim.longOrNull ?: prim.contentOrNull?.trim()?.toLongOrNull()
    }

    fun int(o: JsonObject?, key: String): Int? = long(o, key)?.let {
        it.coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt()
    }

    fun bool(o: JsonObject?, key: String): Boolean? {
        val el = o?.get(key) ?: return null
        if (el is JsonNull) return null
        return (el as? JsonPrimitive)?.booleanOrNull
    }

    /**
     * expireAt 兼容两种形态：Unix 秒（服务端 DB 原值）或已格式化字符串。
     * 0 / 空串统一输出空串，UI 显示「长期有效」。
     */
    fun dateText(o: JsonObject?, key: String): String {
        val el = o?.get(key) ?: return ""
        if (el is JsonNull) return ""
        val prim = el as? JsonPrimitive ?: return ""
        val raw = prim.contentOrNull?.trim().orEmpty()
        if (raw.isEmpty()) return ""
        val sec = prim.longOrNull ?: raw.toLongOrNull()
        if (sec == null || sec <= 0L) {
            // 已是 "2099-12-31" 这类字符串就原样透出
            return if (sec == null && raw.length in 8..12) raw else ""
        }
        return runCatching {
            Instant.ofEpochSecond(sec)
                .atZone(ZoneId.systemDefault())
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd"))
        }.getOrDefault("")
    }
}
