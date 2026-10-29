package vip.wangxiaoming.music.data.remote.http

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

/**
 * 不抛异常的 JSON 取值层。
 *
 * 四家平台都没有公开契约，字段名与类型随时可能变（比如酷狗 timelen 从有值变成空串、
 * 酷我 time 从数字变成字符串）。这里所有取值路径都返回可空值，缺字段/类型不符一律
 * 落到默认值，绝不让一次字段漂移把整批结果打挂。
 */
internal object JsonUtil {

    val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
        explicitNulls = false
    }

    fun parse(raw: String): JsonElement? {
        val text = raw.trimStart('\uFEFF').trim()
        if (text.isEmpty()) return null
        return try {
            json.parseToJsonElement(text)
        } catch (_: Throwable) {
            null
        }
    }

    fun obj(el: JsonElement?): JsonObject? = el as? JsonObject

    fun arr(el: JsonElement?): JsonArray? = el as? JsonArray

    fun objAt(root: JsonElement?, vararg path: String): JsonObject? {
        var cur: JsonElement? = root
        for (key in path) {
            cur = obj(cur)?.get(key) ?: return null
        }
        return obj(cur)
    }

    fun arrAt(root: JsonElement?, vararg path: String): JsonArray? {
        var cur: JsonElement? = root
        for (key in path) {
            cur = obj(cur)?.get(key) ?: return null
        }
        return arr(cur)
    }

    fun str(o: JsonObject?, key: String): String? {
        val el = o?.get(key) ?: return null
        if (el is JsonNull) return null
        return when (el) {
            is JsonPrimitive -> el.contentOrNull?.takeIf { it.isNotEmpty() }
            else -> null
        }
    }

    fun strOr(o: JsonObject?, key: String, def: String): String = str(o, key) ?: def

    fun long(o: JsonObject?, key: String): Long? {
        val el = o?.get(key) ?: return null
        if (el is JsonNull) return null
        val prim = el as? JsonPrimitive ?: return null
        return prim.longOrNull ?: prim.contentOrNull?.trim()?.toLongOrNull()
    }

    fun int(o: JsonObject?, key: String): Int? = long(o, key)?.let {
        if (it > Int.MAX_VALUE) Int.MAX_VALUE else if (it < Int.MIN_VALUE) Int.MIN_VALUE else it.toInt()
    }

    fun dbl(o: JsonObject?, key: String): Double? {
        val el = o?.get(key) ?: return null
        if (el is JsonNull) return null
        val prim = el as? JsonPrimitive ?: return null
        return prim.contentOrNull?.trim()?.toDoubleOrNull()
    }

    fun bool(o: JsonObject?, key: String): Boolean? {
        val el = o?.get(key) ?: return null
        if (el is JsonNull) return null
        return (el as? JsonPrimitive)?.booleanOrNull
    }

    fun strList(o: JsonObject?, key: String, field: String): List<String> =
        arr(o?.get(key))?.mapNotNull { str(obj(it), field) } ?: emptyList()

    /** 秒 → "mm:ss"，与原版 String.format("%02d:%02d") 完全一致 */
    fun mmss(totalSeconds: Int): String {
        if (totalSeconds <= 0) return "03:30"
        val s = totalSeconds.coerceAtMost(99 * 60 + 59)
        return "%02d:%02d".format(s / 60, s % 60)
    }

    /** "mm:ss" / "hh:mm:ss" → 秒；失败返回 0（与原版 parseDurationSeconds 同义） */
    fun parseMmss(text: String?): Int {
        val t = text?.trim().orEmpty()
        if (t.isEmpty()) return 0
        return try {
            val parts = t.split(":")
            when (parts.size) {
                2 -> parts[0].toInt() * 60 + parts[1].toInt()
                3 -> parts[0].toInt() * 3600 + parts[1].toInt() * 60 + parts[2].toInt()
                else -> 0
            }
        } catch (_: Throwable) {
            0
        }
    }

    /** http → https。原版多处直接返回 http 图床，Android 9+ 默认禁止明文，必须升级 */
    fun https(url: String?): String {
        val u = url?.trim().orEmpty()
        return when {
            u.startsWith("https://") -> u
            u.startsWith("http://") -> "https://" + u.removePrefix("http://")
            u.startsWith("//") -> "https:$u"
            else -> u
        }
    }

    /** 替换酷狗图床的 {size} 占位（240/400/800 等） */
    fun withSize(template: String?, size: Int = 240): String {
        val t = template?.trim().orEmpty()
        if (t.isEmpty()) return ""
        return https(t.replace("{size}", size.toString()))
    }
}