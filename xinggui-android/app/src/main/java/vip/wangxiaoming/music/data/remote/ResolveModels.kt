package vip.wangxiaoming.music.data.remote

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import vip.wangxiaoming.music.auth.JsonField

/**
 * 聚合层 /v1/resolve 的请求/响应模型。
 *
 * 为什么手写取值而不是 @Serializable 自动映射：服务端还会返回 trace / warnings /
 * size / cached 等字段，且未来可能继续加。手写取值保证「服务端加字段不会把客户端打挂」，
 * 与 AuthModels.JsonField 的宽松策略保持一致。
 */
data class ResolveRequest(
    val source: String,
    val id: String,
    val name: String,
    val artist: String,
    val durationSeconds: Int,
    val quality: String = "320k",
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("source", source)
        put("id", id)
        put("name", name)
        put("artist", artist)
        put("duration", durationSeconds)
        put("quality", quality)
    }
}

/** 成功响应。url 已由 [ResolveApi] 做过 http→https 升级，可直接交给 Media3。 */
data class ResolveResult(
    val url: String,
    /** 实际命中的平台（wy / kw / kg / tx），可能不等于请求里的 source */
    val resolvedBy: String,
    val level: String,
    val actualBitrate: Int,
    /** Unix 秒；0 表示服务端没给 */
    val expiresAt: Long,
    val cached: Boolean,
    val durationSeconds: Int,
    val trace: List<String>,
) {
    companion object {
        fun parse(obj: JsonObject): ResolveResult? {
            val url = JsonField.str(obj, "url") ?: return null
            val trace = JsonField.array(obj, "trace").mapNotNull {
                (it as? JsonPrimitive)?.content
            }
            return ResolveResult(
                url = url,
                resolvedBy = JsonField.str(obj, "resolvedBy") ?: "",
                level = JsonField.str(obj, "level") ?: "",
                actualBitrate = JsonField.int(obj, "actualBitrate") ?: 0,
                expiresAt = JsonField.long(obj, "expiresAt") ?: 0L,
                cached = JsonField.bool(obj, "cached") ?: false,
                durationSeconds = (JsonField.long(obj, "duration") ?: 0L).toInt(),
                trace = trace,
            )
        }
    }
}

/**
 * 解析结果三态。
 *
 * 与 AuthApi 的 ApiResult 分开：这里必须能把「401 需刷新后重试一次」和
 * 「404 无源（立刻降级本地，重试没意义）」区分开，用同一个枚举会让调用点
 * 写出两层 when 嵌套。
 */
sealed interface ResolveOutcome {
    data class Ok(val result: ResolveResult) : ResolveOutcome

    /** token 过期/无效：调用方 refresh 后可重试一次。 */
    data object Unauthorized : ResolveOutcome

    /** 服务端明确说「没有可用音源」（404 no_source）或参数错误：不要重试，直接降级本地。 */
    data class NoSource(val code: String, val message: String) : ResolveOutcome

    /** 网络层失败（超时/拒绝/5xx/JSON 炸）：降级本地。 */
    data class Failed(val message: String) : ResolveOutcome
}