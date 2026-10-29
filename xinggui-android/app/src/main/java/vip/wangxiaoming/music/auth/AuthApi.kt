package vip.wangxiaoming.music.auth

import android.content.Context
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 服务端认证接口客户端（/v1/auth/…）。
 *
 * 为什么不用 Retrofit：
 *  - 全工程只有 6 个端点，Retrofit + Gson + 适配器带来的方法数、编译时间和
 *    「字段名写错要到运行时才发现」的成本都不划算；
 *  - 服务端错误统一是 {"error":code,"message":文案}，手写一次就能精确映射到
 *    UI 的每种提示，不需要额外转换层。
 *
 * 所有方法都返回 [ApiResult]，不抛异常：网络异常/超时/JSON 抖动统一落到 ApiResult.Net，
 * 调用方只需处理四种分支（成功 / 业务错误 / 网络）。
 */
object AuthApi {

    /** 生产基址。debug 可用外部文件覆盖，见 [resolveBaseUrl]。 */
    const val PROD_BASE = "https://music.wangxiaoming.vip:8443"

    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .callTimeout(25, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    // ---------------------------------------------------------------- 基址

    /**
     * 基址优先级（release / debug 统一）：
     *   1. 用户在「我的 → 服务器设置」里保存的地址（SharedPreferences，持久化）
     *   2. `<外部files目录>/auth_base_url.txt` 覆盖文件（保留原有 adb/run-as 联调能力）
     *   3. release → [PROD_BASE]；debug → [DEBUG_BASE]
     *
     * 明文说明（已核对 OkHttp 4.12.0 字节码，不是猜的）：
     * `RealConnection` 里有 `isCleartextTrafficPermitted` 与
     * "CLEARTEXT communication to ... not permitted by network security policy"，
     * 所以 OkHttp **同样受** network-security-config 约束；http 基址必须被
     * network_security_config 放行，否则会抛 CLEARTEXT_NOT_PERMITTED。
     */
    private fun isDebuggable(context: Context): Boolean =
        (context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0

    /** 设置项存储：普通 SharedPreferences（非机密，只是内网/公网地址）。 */
    private const val PREFS_NAME = "server_settings"

    /** SharedPreferences key：用户手动设置的基址。 */
    const val KEY_SERVER_BASE_URL = "server_base_url"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * 规范化并校验用户输入。
     * 规则：必须以 http:// 或 https:// 开头、有主机名、不含空白/查询串/片段；去掉尾部斜杠。
     * 返回 null 表示非法输入（调用方必须给出明确提示，不能静默忽略）。
     */
    fun normalizeBaseUrl(raw: String?): String? {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return null
        if (text.any { it.isWhitespace() }) return null
        val lower = text.lowercase()
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) return null
        val parsed = runCatching { text.toHttpUrl() }.getOrNull() ?: return null
        if (parsed.scheme != "http" && parsed.scheme != "https") return null
        if (parsed.host.isBlank()) return null
        if (parsed.query != null || parsed.fragment != null) return null
        return text.trimEnd('/')
    }

    /** 用户手动保存的基址；未设置或已损坏时返回 null。 */
    fun savedBaseUrl(context: Context): String? =
        normalizeBaseUrl(prefs(context).getString(KEY_SERVER_BASE_URL, null))

    /** 保存用户基址。非法输入返回 false，且不覆盖已有值。 */
    fun saveBaseUrl(context: Context, raw: String): Boolean {
        val normalized = normalizeBaseUrl(raw) ?: return false
        return prefs(context).edit().putString(KEY_SERVER_BASE_URL, normalized).commit()
    }

    /** 恢复默认：删掉用户设置，回落到覆盖文件 / 构建默认值。 */
    fun clearSavedBaseUrl(context: Context): Boolean =
        prefs(context).edit().remove(KEY_SERVER_BASE_URL).commit()

    /** 覆盖文件读取（只在 debuggable 构建里生效，保持原有防劫持语义）。 */
    private fun overrideFileBaseUrl(context: Context): String? {
        if (!isDebuggable(context)) return null
        val candidates = listOfNotNull(
            runCatching { context.getExternalFilesDir(null) }.getOrNull(),
            context.filesDir,
        )
        for (dir in candidates) {
            val f = File(dir, OVERRIDE_FILE)
            if (!f.exists()) continue
            val text = runCatching { f.readText().trim() }.getOrNull().orEmpty()
            normalizeBaseUrl(text)?.let { return it }
        }
        return null
    }

    /** 不含用户设置时的「构建默认值」：覆盖文件优先，其次 debug/prod 常量。 */
    fun defaultBaseUrl(context: Context): String =
        overrideFileBaseUrl(context) ?: if (isDebuggable(context)) DEBUG_BASE else PROD_BASE

    fun resolveBaseUrl(context: Context): String =
        savedBaseUrl(context) ?: defaultBaseUrl(context)

    /** 联调辅助：把基址写进内部 files（跨 pm clear 由调用方自行重建）。 */
    fun writeOverride(context: Context, url: String): Boolean = runCatching {
        File(context.filesDir, OVERRIDE_FILE).writeText(url.trim().trimEnd('/'))
    }.isSuccess

    private const val OVERRIDE_FILE = "auth_base_url.txt"

    private const val DEBUG_BASE = "http://10.0.2.2:9443"

    // ---------------------------------------------------------------- 连接自检

    /** 「测试连接」结果。[blockedByCleartext] 为 true 表示系统策略拦了明文，不是服务端没开。 */
    data class HealthCheck(
        val ok: Boolean,
        val httpCode: Int,
        val elapsedMs: Long,
        val message: String,
        val blockedByCleartext: Boolean = false,
    )

    /**
     * 明文是否被系统放行。用 NetworkSecurityPolicy 直接问系统，
     * 比在 UI 里猜 network_security_config 的结果可靠。
     */
    fun isCleartextAllowed(host: String): Boolean = runCatching {
        android.security.NetworkSecurityPolicy.getInstance()
            .isCleartextTrafficPermitted(host)
    }.getOrDefault(false)

    /**
     * GET {baseUrl}/v1/health，返回状态码与耗时。
     * 不抛异常：所有失败都折成 [HealthCheck.ok] = false + 可读原因。
     */
    suspend fun checkHealth(baseUrl: String): HealthCheck = withContext(Dispatchers.IO) {
        val normalized = normalizeBaseUrl(baseUrl)
            ?: return@withContext HealthCheck(false, 0, 0, "地址格式不对：必须以 http:// 或 https:// 开头")
        val host = runCatching { normalized.toHttpUrl().host }.getOrDefault("")
        if (normalized.startsWith("http://") && host.isNotBlank() && !isCleartextAllowed(host)) {
            return@withContext HealthCheck(
                ok = false, httpCode = 0, elapsedMs = 0,
                message = "系统禁止明文 HTTP：$host 未在 network_security_config 放行。改用 https，或把该地址加入放行列表。",
                blockedByCleartext = true,
            )
        }
        val started = System.nanoTime()
        try {
            val req = Request.Builder()
                .url(normalized + "/v1/health")
                .header("Accept", "application/json")
                .get()
                .build()
            client.newCall(req).execute().use { resp ->
                val ms = (System.nanoTime() - started) / 1_000_000
                if (resp.isSuccessful) {
                    HealthCheck(true, resp.code, ms, "连接成功（HTTP ${resp.code}）")
                } else {
                    HealthCheck(false, resp.code, ms, "服务端可达，但返回 HTTP ${resp.code}")
                }
            }
        } catch (io: java.io.IOException) {
            val ms = (System.nanoTime() - started) / 1_000_000
            val msg = io.message.orEmpty()
            val blocked = msg.contains("CLEARTEXT", ignoreCase = true)
            HealthCheck(
                ok = false, httpCode = 0, elapsedMs = ms,
                message = if (blocked) {
                    "被系统明文策略拦截（CLEARTEXT_NOT_PERMITTED）。请改用 https。"
                } else {
                    "连接失败：" + networkMessage(io)
                },
                blockedByCleartext = blocked,
            )
        } catch (t: Throwable) {
            val ms = (System.nanoTime() - started) / 1_000_000
            HealthCheck(false, 0, ms, "连接失败：" + (t.message ?: t.javaClass.simpleName))
        }
    }


    // ---------------------------------------------------------------- 端点

    suspend fun register(
        baseUrl: String, username: String, password: String,
        deviceId: String, deviceName: String,
    ): ApiResult<RegisterInfo> = call(
        baseUrl = baseUrl, path = "/v1/auth/register", method = "POST",
        accessToken = null,
        body = buildJsonObject {
            put("username", username)
            put("password", password)
            put("deviceId", deviceId)
            put("deviceName", deviceName)
        },
    ) { obj ->
        RegisterInfo(
            status = JsonField.str(obj, "status") ?: "",
            message = JsonField.str(obj, "message") ?: "",
        )
    }

    suspend fun login(
        baseUrl: String, username: String, password: String,
        deviceId: String, deviceName: String,
    ): ApiResult<LoginResult> = call(
        baseUrl = baseUrl, path = "/v1/auth/login", method = "POST",
        accessToken = null,
        body = buildJsonObject {
            put("username", username)
            put("password", password)
            put("deviceId", deviceId)
            put("deviceName", deviceName)
        },
    ) { obj ->
        LoginResult(
            accessToken = JsonField.str(obj, "accessToken") ?: error("missing accessToken"),
            refreshToken = JsonField.str(obj, "refreshToken") ?: error("missing refreshToken"),
            // 契约写 expiresIn（秒）；缺失时按 2 小时兜底（服务端 JWT 默认值）
            expiresInSeconds = JsonField.long(obj, "expiresIn") ?: 7200L,
            user = parseUser(JsonField.obj(obj, "user")),
        )
    }

    suspend fun refresh(baseUrl: String, refreshToken: String): ApiResult<RefreshResult> = call(
        baseUrl = baseUrl, path = "/v1/auth/refresh", method = "POST",
        accessToken = null,
        body = buildJsonObject { put("refreshToken", refreshToken) },
    ) { obj ->
        RefreshResult(
            accessToken = JsonField.str(obj, "accessToken") ?: error("missing accessToken"),
            refreshToken = JsonField.str(obj, "refreshToken") ?: refreshToken,
            expiresInSeconds = JsonField.long(obj, "expiresIn") ?: 7200L,
        )
    }

    suspend fun me(baseUrl: String, accessToken: String): ApiResult<AuthUser> = call(
        baseUrl = baseUrl, path = "/v1/auth/me", method = "GET",
        accessToken = accessToken, body = null,
    ) { obj -> parseUser(obj) }

    suspend fun unbind(
        baseUrl: String, accessToken: String, deviceId: String,
    ): ApiResult<UnbindInfo> = call(
        baseUrl = baseUrl, path = "/v1/auth/unbind", method = "POST",
        accessToken = accessToken,
        body = buildJsonObject { put("deviceId", deviceId) },
    ) { obj ->
        UnbindInfo(
            deviceCount = JsonField.int(obj, "deviceCount") ?: 0,
            maxDevices = JsonField.int(obj, "maxDevices") ?: 2,
        )
    }

    /** 退出登录：服务端吊销该 refresh token。失败不影响本地清理。 */
    suspend fun logout(baseUrl: String, accessToken: String): ApiResult<Boolean> = call(
        baseUrl = baseUrl, path = "/v1/auth/logout", method = "POST",
        accessToken = accessToken, body = buildJsonObject { },
    ) { true }

    // ---------------------------------------------------------------- 内部

    private suspend fun <T> call(
        baseUrl: String,
        path: String,
        method: String,
        accessToken: String?,
        body: JsonObject?,
        parse: (JsonObject) -> T,
    ): ApiResult<T> = withContext(Dispatchers.IO) {
        val request = try {
            Request.Builder()
                .url(baseUrl.trimEnd('/') + path)
                .header("Accept", "application/json")
                .header("User-Agent", "xinggui-android/" + android.os.Build.VERSION.SDK_INT)
                .apply { accessToken?.let { header("Authorization", "Bearer $it") } }
                .apply {
                    if (method == "GET") get()
                    else post((body?.toString() ?: "{}").toRequestBody(jsonMedia))
                }
                .build()
        } catch (t: Throwable) {
            return@withContext ApiResult.Net("请求地址无效：${t.message}")
        }

        try {
            client.newCall(request).execute().use { resp ->
                val raw = resp.body?.string().orEmpty()
                val obj = JsonField.parseObject(raw)
                if (resp.isSuccessful) {
                    if (obj == null) {
                        if (raw.isBlank()) ApiResult.Ok(parse(buildJsonObject { }))
                        else ApiResult.Net("服务端返回了无法解析的内容")
                    } else {
                        runCatching { parse(obj) }.fold(
                            { ApiResult.Ok(it) },
                            { ApiResult.Net("响应字段异常：${it.message}") },
                        )
                    }
                } else {
                    ApiResult.Err(
                        http = resp.code,
                        code = JsonField.str(obj, "error") ?: "http_${resp.code}",
                        message = JsonField.str(obj, "message") ?: defaultMessage(resp.code),
                    )
                }
            }
        } catch (io: java.io.IOException) {
            ApiResult.Net(networkMessage(io))
        } catch (t: Throwable) {
            ApiResult.Net(t.message ?: t.javaClass.simpleName)
        }
    }

    private fun networkMessage(io: java.io.IOException): String = when {
        io is java.net.SocketTimeoutException -> "连接超时，请检查网络后重试"
        io is java.net.UnknownHostException -> "无法解析服务器地址"
        io is java.net.ConnectException -> "无法连接服务器"
        else -> io.message ?: "网络异常"
    }

    private fun defaultMessage(http: Int): String = when (http) {
        401 -> "登录状态已失效"
        403 -> "没有访问权限"
        429 -> "请求过于频繁，请稍后再试"
        in 500..599 -> "服务器繁忙，请稍后重试"
        else -> "请求失败（$http）"
    }

    private fun parseUser(obj: JsonObject?): AuthUser {
        val devices = JsonField.array(obj, "devices").mapNotNull { el ->
            val d = JsonField.asObj(el) ?: return@mapNotNull null
            AuthDevice(
                deviceId = JsonField.str(d, "deviceId") ?: "",
                deviceName = JsonField.str(d, "deviceName") ?: "",
                current = JsonField.bool(d, "current") ?: false,
                lastSeenAt = JsonField.long(d, "lastSeenAt") ?: 0L,
            )
        }
        return AuthUser(
            username = JsonField.str(obj, "username") ?: "",
            status = JsonField.str(obj, "status") ?: "",
            vipType = JsonField.str(obj, "vipType") ?: "free",
            expireAt = JsonField.dateText(obj, "expireAt"),
            deviceCount = JsonField.int(obj, "deviceCount") ?: 0,
            maxDevices = JsonField.int(obj, "maxDevices") ?: 2,
            devices = devices,
        )
    }

    fun deviceName(): String {
        val model = Build.MODEL?.trim().orEmpty()
        return if (model.isEmpty()) "Android 设备" else model
    }
}



