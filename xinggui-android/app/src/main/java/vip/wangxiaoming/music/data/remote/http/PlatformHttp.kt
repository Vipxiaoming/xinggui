package vip.wangxiaoming.music.data.remote.http

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * 四平台共用的 HTTP 层。
 *
 * 超时策略按任务要求：6s connect / 8s read（原版 detailClient 的 read 是 20s，
 * 这里统一收紧到 8s，配合 Repository 的 12s 总预算；确实需要更久的两阶段歌单
 * 在 NeteaseClient 内部单独用 longClient）。
 *
 * 不使用 Gson/反射：响应一律取 String，交给各 Client 用 kotlinx.serialization 的
 * JsonElement 手动取值，字段缺失/类型漂移不会抛异常。
 */
internal object PlatformHttp {

    /** 普通请求：搜索 / 榜单 / 歌词 */
    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .callTimeout(15, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .followRedirects(true)
        .build()

    /** 两阶段歌单：网易 trackIds + song/detail 批量拉 200 首，允许更长的读超时 */
    val longClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(35, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    const val UA_DESKTOP = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
    const val UA_MOBILE = "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
    const val UA_OKHTTP = "okhttp/3.12.1"

    data class RawResponse(
        val status: Int,
        val body: String,
        val contentType: String?,
        val error: String? = null,
    ) {
        val isSuccess: Boolean get() = status in 200..299 && error == null
        val length: Int get() = body.length
    }

    suspend fun get(
        url: String,
        userAgent: String = UA_DESKTOP,
        referer: String? = null,
        useLongClient: Boolean = false,
    ): RawResponse = execute(
        Request.Builder().url(url).get()
            .header("User-Agent", userAgent)
            .apply { referer?.let { header("Referer", it) } }
            .build(),
        useLongClient,
    )

    suspend fun postForm(
        url: String,
        form: String,
        userAgent: String = UA_DESKTOP,
        referer: String? = null,
        useLongClient: Boolean = false,
    ): RawResponse = execute(
        Request.Builder().url(url)
            .post(form.toRequestBody(MediaTypeForm))
            .header("User-Agent", userAgent)
            .apply { referer?.let { header("Referer", it) } }
            .build(),
        useLongClient,
    )

    private val MediaTypeForm =
        "application/x-www-form-urlencoded; charset=UTF-8".toMediaType()

    private suspend fun execute(request: Request, useLongClient: Boolean): RawResponse =
        withContext(Dispatchers.IO) {
            val http = if (useLongClient) longClient else client
            try {
                http.newCall(request).execute().use { resp ->
                    val body = resp.body?.string().orEmpty()
                    RawResponse(
                        status = resp.code,
                        body = body,
                        contentType = resp.header("Content-Type"),
                    )
                }
            } catch (io: IOException) {
                RawResponse(0, "", null, (io.message ?: io.javaClass.simpleName))
            } catch (t: Throwable) {
                RawResponse(0, "", null, (t.message ?: t.javaClass.simpleName))
            }
        }
}