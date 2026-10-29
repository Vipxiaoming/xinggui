package vip.wangxiaoming.music.data.remote

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import vip.wangxiaoming.music.auth.JsonField
import java.util.concurrent.TimeUnit

/**
 * 聚合层 /v1/resolve 客户端。
 *
 * 设计取舍：
 *  - 超时 10s（任务书要求）。服务端 P95 目标 1200ms，10s 已经覆盖「上游四路各 7s
 *    但并行」的最坏情况；再长会让用户点了不播却没有任何反馈。
 *  - 只做一次网络调用，不做重试：重试由调用方（PlayLauncher）在 401 时触发一次，
 *    其余失败直接降级本地解析。这样「服务端挂了」的恢复路径是确定的，不会叠加重试延迟。
 *  - 返回 [ResolveOutcome] 而不是抛异常：解析失败是常态（无版权、上游风控），
 *    用 sealed 三态让调用点能明确区分「该刷新 token」和「该降级」。
 */
object ResolveApi {

    private const val TAG = "ResolveApi"

    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .callTimeout(10, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .build()

    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    suspend fun resolve(
        baseUrl: String,
        token: String,
        source: String,
        id: String,
        name: String,
        artist: String,
        durationSeconds: Int,
        quality: String = "320k",
    ): ResolveOutcome = withContext(Dispatchers.IO) {
        val payload = ResolveRequest(
            source = source,
            id = id,
            name = name,
            artist = artist,
            durationSeconds = durationSeconds,
            quality = quality,
        ).toJson().toString()

        val request = try {
            Request.Builder()
                .url(baseUrl.trimEnd('/') + "/v1/resolve")
                .header("Accept", "application/json")
                .header("Authorization", "Bearer $token")
                .header("User-Agent", "xinggui-android")
                .post(payload.toRequestBody(jsonMedia))
                .build()
        } catch (t: Throwable) {
            Log.w(TAG, "build request failed: ${t.message}")
            return@withContext ResolveOutcome.Failed("请求地址无效：${t.message}")
        }

        try {
            client.newCall(request).execute().use { resp ->
                val raw = resp.body?.string().orEmpty()
                when {
                    resp.isSuccessful -> {
                        val obj = JsonField.parseObject(raw)
                        val parsed = obj?.let { ResolveResult.parse(it) }
                        if (parsed == null) {
                            Log.w(TAG, "200 but unparseable body (${raw.length}B)")
                            ResolveOutcome.Failed("服务端返回了无法解析的内容")
                        } else {
                            val upgraded = upgradeToHttps(parsed.url)
                            if (upgraded != parsed.url) {
                                Log.d(TAG, "upgraded url http→https")
                            }
                            ResolveOutcome.Ok(parsed.copy(url = upgraded))
                        }
                    }
                    resp.code == 401 -> ResolveOutcome.Unauthorized
                    resp.code == 404 -> {
                        val obj = JsonField.parseObject(raw)
                        val code = obj?.let { JsonField.str(it, "error") } ?: "no_source"
                        val msg = obj?.let { JsonField.str(it, "message") } ?: "没有可用音源"
                        Log.d(TAG, "404 $code")
                        ResolveOutcome.NoSource(code, msg)
                    }
                    else -> {
                        val obj = JsonField.parseObject(raw)
                        val code = obj?.let { JsonField.str(it, "error") } ?: "http_${resp.code}"
                        val msg = obj?.let { JsonField.str(it, "message") } ?: "HTTP ${resp.code}"
                        if (resp.code in 500..599) ResolveOutcome.Failed("$code/$msg")
                        else ResolveOutcome.NoSource(code, msg)
                    }
                }
            }
        } catch (io: java.io.IOException) {
            val msg = when (io) {
                is java.net.SocketTimeoutException -> "timeout"
                is java.net.ConnectException -> "connect refused"
                is java.net.UnknownHostException -> "dns fail"
                else -> io.message ?: io.javaClass.simpleName
            }
            Log.d(TAG, "io error: $msg")
            ResolveOutcome.Failed(msg)
        } catch (t: Throwable) {
            Log.d(TAG, "unexpected: ${t.javaClass.simpleName} ${t.message}")
            ResolveOutcome.Failed(t.message ?: t.javaClass.simpleName)
        }
    }

    /**
     * 网易云直链仍是 http://m*.music.126.net/...，而 network_security_config
     * 默认禁止明文（实测表现为 Media3 报 Source error、点了完全不播）。
     * 同路径 https 实测 HEAD 200 + audio/mpeg，因此在这里统一升级。
     *
     * 只改 music.126.net，不动其他域名：酷狗 CDN 的 https 证书 CN 不匹配，
     * 强行升级会把原本能播的链接改坏。
     */
    private fun upgradeToHttps(url: String): String {
        if (!url.startsWith("http://")) return url
        val host = url.removePrefix("http://").substringBefore('/').substringBefore(':')
        return if (host.endsWith("music.126.net")) "https://" + url.removePrefix("http://") else url
    }
}