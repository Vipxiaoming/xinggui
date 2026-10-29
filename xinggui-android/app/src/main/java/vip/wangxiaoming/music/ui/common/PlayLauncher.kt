package vip.wangxiaoming.music.ui.common

import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.session.MediaController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import vip.wangxiaoming.music.data.remote.ResolveApi
import vip.wangxiaoming.music.data.remote.ResolveOutcome
import vip.wangxiaoming.music.data.remote.client.KuwoClient
import vip.wangxiaoming.music.data.remote.model.Platform
import vip.wangxiaoming.music.data.remote.model.Song
import vip.wangxiaoming.music.di.AppContainer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * 点歌 → 解析播放地址 → 交给 Media3。
 *
 * 解析顺序（P27 改动）：
 *  0. 内存缓存命中（source:id:quality）；
 *  1. 服务端 /v1/resolve（四路竞速 + 服务端缓存）。401 时刷新 token 重试一次；
 *  2. 服务端不可用/无源/超时 → 沿用 P6 的本地三路解析（kw 直出、wy outer、kw 搜索兜底）。
 *
 * 为什么保留本地解析而不是删掉：服务端跑在家里物理机 + DDNS 上，离线、重启、
 * 运营商抽风都是常态。任务书要求「停掉服务端仍能播放」，所以降级路径必须是真实可用的，
 * 不能是空壳。两条路径共用同一份 OkHttp/UA 常量，行为和 P6 实测结果一致。
 *
 * 为什么解析放在 UI 层而不是 PlaybackService：P5 的服务里绑的是
 * [vip.wangxiaoming.music.playback.StaticPlayUrlProvider]（固定样例音频，只用于验证
 * MediaSession 位置上报），而 playback 包不在 P6 的写作用域内。
 */
object PlayLauncher {

    private const val TAG = "PlayLauncher"

    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .followRedirects(false)
        .build()

    private val kuwo = KuwoClient()

    /** 队列最多预热 15 首，避免一次点播打出几十个并发请求。 */
    private const val MAX_PRELOAD = 15

    /** 同批最多并发解析几首。压低是为了避开上游限频，不是性能取舍。 */
    private const val PARALLEL_RESOLVE = 3

    /** 服务端直链统一按 320k 请求，服务端会按实际可用音质回落并回报 actualBitrate。 */
    private const val QUALITY = "320k"

    /** 缓存上限，避免长时间使用后无限增长。 */
    private const val MAX_CACHE = 64

    /** TTL 硬上限：即使服务端给了更远的 expiresAt，也不缓存超过 6 小时。 */
    private const val MAX_TTL_MS = 6 * 60 * 60 * 1000L

    private data class CacheEntry(val url: String, val expiresAtMs: Long)

    /** (source:id:quality) -> 直链。ConcurrentHashMap：多首并发预解析会同时读写。 */
    private val cache = ConcurrentHashMap<String, CacheEntry>()

    /**
     * 播放 [songs]，从 [startIndex] 起。解析失败的条目会被剔除。
     * @return 实际入队的曲目数；0 表示全部解析失败。
     */
    suspend fun play(controller: MediaController, songs: List<Song>, startIndex: Int): Int =
        coroutineScope {
            if (songs.isEmpty()) return@coroutineScope 0
            val start = startIndex.coerceIn(0, songs.lastIndex)

            // 被点的那首排第一，其余按原顺序跟进
            val ordered = buildList {
                add(songs[start])
                songs.forEachIndexed { i, s -> if (i != start) add(s) }
            }.take(MAX_PRELOAD)

            // 只并发解析前 3 首：15 首同时打会触发上游限频（实测一次点播 15 条全 null，
            // 单独请求同一 id 却 302 正常）。被点的那首排第一，用户感知不到差别。
            val head = ordered.take(PARALLEL_RESOLVE)
            val resolved = head.map { song -> async { song to resolve(song) } }.awaitAll()
            val playable = resolved.mapNotNull { (song, url) -> if (url != null) song to url else null }
            if (playable.isEmpty()) return@coroutineScope 0

            val items = playable.map { (song, url) ->
                MediaItem.Builder()
                    .setMediaId(mediaIdOf(song))
                    .setUri(url)
                    .setMediaMetadata(
                        MediaMetadata.Builder()
                            .setTitle(song.name)
                            .setArtist(song.artist)
                            .setArtworkUri(
                                song.cover.takeIf { it.isNotBlank() }?.let { android.net.Uri.parse(it) },
                            )
                            .setIsPlayable(true)
                            .setIsBrowsable(false)
                            .build(),
                    )
                    .build()
            }
            controller.setMediaItems(items, 0, 0L)
            controller.prepare()
            controller.play()
            playable.size
        }

    fun mediaIdOf(song: Song): String = song.source + ":" + song.id

    /** 单曲解析。失败返回 null，绝不把异常抛到 UI。 */
    suspend fun resolve(song: Song): String? = withTimeoutOrNull(15_000) {
        // 必须显式切 IO：调用方（Fragment 的 viewLifecycleOwner.lifecycleScope）跑在
        // Main.immediate 上，async 会继承它，OkHttp 的同步 execute 就会抛
        // NetworkOnMainThreadException（实测：15 条解析全部 null，日志里全是这条）。
        withContext(Dispatchers.IO) {
            val key = cacheKey(song)
            cached(key)?.let {
                Log.d(TAG, "resolve cache hit ${song.source}:${song.id}")
                return@withContext it
            }

            val viaServer = resolveViaServer(song)
            if (viaServer != null) {
                Log.d(TAG, "resolve via server ${song.source}:${song.id} ${song.name} url=${viaServer.take(60)}")
                return@withContext viaServer
            }

            Log.d(TAG, "resolve via local fallback ${song.source}:${song.id} ${song.name}")
            val local = resolveLocal(song)
            Log.d(TAG, "local fallback done ${song.source}:${song.id} url=${local?.take(60)}")
            local
        }
    }

    // ------------------------------------------------------------ 服务端路径

    /**
     * 走聚合层。任何失败都返回 null（由调用方降级），唯一例外是拿到直链。
     *
     * 401 处理：服务端 access token 2h 过期，而播放可能发生在后台很久之后。
     * 这里只重试一次：refresh 成功 → 重发；refresh 也失败说明会话真没了，
     * AuthSession 内部会广播 SignedOut，播放降级到本地仍可继续。
     */
    private suspend fun resolveViaServer(song: Song): String? {
        val session = AppContainer.peek()?.authSession ?: run {
            Log.d(TAG, "server skipped: no AppContainer")
            return null
        }
        val creds = runCatching { session.resolveCredentials() }.getOrNull() ?: run {
            Log.d(TAG, "server skipped: not logged in")
            return null
        }
        val base = creds.first
        var token = creds.second

        val duration = durationSecondsOf(song)
        var outcome = callResolve(base, token, song, duration)
        if (outcome is ResolveOutcome.Unauthorized) {
            Log.d(TAG, "server 401, refreshing token once")
            val refreshed = runCatching { session.refreshTokens() }.getOrNull()
            if (refreshed == null) {
                Log.d(TAG, "refresh failed, fallback local")
                return null
            }
            token = refreshed.accessToken
            outcome = callResolve(base, token, song, duration)
        }

        return when (outcome) {
            is ResolveOutcome.Ok -> {
                putCache(cacheKey(song), outcome.result.url, outcome.result.expiresAt)
                outcome.result.url
            }
            ResolveOutcome.Unauthorized -> {
                Log.d(TAG, "server still 401 after refresh")
                null
            }
            is ResolveOutcome.NoSource -> {
                Log.d(TAG, "server no_source ${outcome.code}: ${outcome.message}")
                null
            }
            is ResolveOutcome.Failed -> {
                Log.d(TAG, "server failed: ${outcome.message}")
                null
            }
        }
    }

    private suspend fun callResolve(
        base: String,
        token: String,
        song: Song,
        duration: Int,
    ): ResolveOutcome = ResolveApi.resolve(
        baseUrl = base,
        token = token,
        source = song.source,
        id = song.id,
        name = song.name,
        artist = song.artist,
        durationSeconds = duration,
        quality = QUALITY,
    )

    /** 服务端 duration 单位是秒；Song 里既有 "mm:ss" 文本也有 durationSeconds。 */
    private fun durationSecondsOf(song: Song): Int {
        if (song.durationSeconds > 0) return song.durationSeconds
        val m = Regex("""(\d+):(\d+)""").find(song.duration)
        return m?.let { it.groupValues[1].toInt() * 60 + it.groupValues[2].toInt() } ?: 0
    }

    // ------------------------------------------------------------ 缓存

    private fun cacheKey(song: Song): String = "${song.source}:${song.id}:$QUALITY"

    private fun cached(key: String): String? {
        val e = cache[key] ?: return null
        if (e.expiresAtMs <= System.currentTimeMillis()) {
            cache.remove(key)
            return null
        }
        return e.url
    }

    /**
     * expiresAt 是 Unix 秒。服务端可能返回 0（未给），此时用 6h 上限。
     * TTL = min(expiresAt-now, 6h)，并至少保留 60s，避免「拿到就过期」导致
     * 一次点播内重复打服务端。
     */
    private fun putCache(key: String, url: String, expiresAtSec: Long) {
        val now = System.currentTimeMillis()
        val fromServer = if (expiresAtSec > 0) expiresAtSec * 1000L - now else MAX_TTL_MS
        val ttl = fromServer.coerceIn(60_000L, MAX_TTL_MS)
        if (cache.size >= MAX_CACHE) cache.clear()
        cache[key] = CacheEntry(url, now + ttl)
    }

    // ------------------------------------------------------------ 本地路径（P6 原逻辑，保留）

    private suspend fun resolveLocal(song: Song): String? {
        val primary = when (Platform.of(song.source)) {
            Platform.KUWO -> kuwoUrl(song.id)
            Platform.NETEASE -> neteaseUrl(song.id)
            else -> null
        }
        return primary ?: searchFallback(song)
    }

    private suspend fun kuwoUrl(id: String): String? {
        if (id.isBlank()) return null
        val u = "https://antiserver.kuwo.cn/anti.s?type=convert_url&format=mp3&response=url&rid=MUSIC_$id&br=128kmp3"
        val body = runCatching {
            client.newCall(Request.Builder().url(u).header("User-Agent", UA).build())
                .execute().use { it.body?.string().orEmpty() }
        }.onFailure { Log.d(TAG, "kw fail ${it.javaClass.simpleName}: ${it.message}") }
            .getOrNull().orEmpty().trim()
        // 失败时返回空串或 "noCopyright"，只有 http(s) 才算命中
        return body.takeIf { it.startsWith("http") }
    }

    private suspend fun neteaseUrl(id: String): String? {
        if (id.isBlank()) return null
        val u = "https://music.163.com/song/media/outer/url?id=$id.mp3"
        return runCatching {
            client.newCall(Request.Builder().url(u).header("User-Agent", UA).build())
                .execute().use { resp ->
                    val loc = resp.header("Location").orEmpty()
                    Log.d(TAG, "wy code=${resp.code} loc=${loc.take(90)}")
                    // 无版权时 302 → http://music.163.com/404
                    if (resp.code in 300..399 && loc.isNotBlank() && !loc.contains("/404")) {
                        if (loc.startsWith("http://")) "https://" + loc.removePrefix("http://") else loc
                    } else if (resp.isSuccessful) {
                        u
                    } else {
                        null
                    }
                }
        }.onFailure { Log.d(TAG, "wy fail ${it.javaClass.simpleName}: ${it.message}") }.getOrNull()
    }

    private suspend fun searchFallback(song: Song): String? {
        val keyword = (song.name + " " + song.artist).trim()
        if (keyword.isBlank()) return null
        val hit = runCatching { kuwo.search(keyword, rn = 10) }
            .onFailure { Log.d(TAG, "kw search fail ${it.javaClass.simpleName}") }
            .getOrNull()
            ?.takeIf { it.ok }?.data?.firstOrNull() ?: return null
        return kuwoUrl(hit.id)
    }

    private const val UA =
        "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124 Mobile Safari/537.36"
}