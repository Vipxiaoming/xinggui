package vip.wangxiaoming.music.data.remote

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import vip.wangxiaoming.music.data.remote.client.KugouClient
import vip.wangxiaoming.music.data.remote.client.KuwoClient
import vip.wangxiaoming.music.data.remote.client.NeteaseClient
import vip.wangxiaoming.music.data.remote.client.QQClient
import vip.wangxiaoming.music.data.remote.model.Artist
import vip.wangxiaoming.music.data.remote.model.ClientResult
import vip.wangxiaoming.music.data.remote.model.Platform
import vip.wangxiaoming.music.data.remote.model.Playlist
import vip.wangxiaoming.music.data.remote.model.RankInfo
import vip.wangxiaoming.music.data.remote.model.Song
import vip.wangxiaoming.music.data.remote.model.normalizeForMatch

/**
 * 四平台元数据聚合。
 *
 * 设计原则（对应契约 §"只做元数据，不做解析"）：
 *  - 并发调用、各自独立超时、单家失败不影响其余（降级为"这一家没结果"）。
 *  - 合并时按 `歌名+歌手` 归一化去重：同名同歌手只保留第一条，并把四家的
 *    source 汇总成 `sources` 以保留溯源信息（source 字段仍保留首个命中来源，
 *    以便解析层按 source 选不同的取流策略）。
 *  - 结果里带每家的 ClientResult 摘要（status / count / error），供诊断页与测试直接用，
 *    不吞掉失败原因。
 */
class MetadataRepository(
    private val netease: NeteaseClient = NeteaseClient(),
    private val kuwo: KuwoClient = KuwoClient(),
    private val kugou: KugouClient = KugouClient(),
    private val qq: QQClient = QQClient(),
) {

    /** 聚合搜索。默认四家全开，可用 enabled 只测其中几家 */
    suspend fun searchAll(
        keyword: String,
        limitPerPlatform: Int = 30,
        enabled: Set<Platform> = Platform.entries.toSet(),
        totalBudgetMs: Long = 12_000,
    ): AggregateResult<List<Song>> = coroutineScope {
        val kw = keyword.trim()
        if (kw.isEmpty()) {
            return@coroutineScope AggregateResult(emptyList(), emptyList())
        }

        val jobs = buildList {
            if (Platform.NETEASE in enabled) {
                add(async { netease.search(kw, 0, limitPerPlatform) })
            }
            if (Platform.KUWO in enabled) {
                add(async { kuwo.search(kw, 0, limitPerPlatform.coerceAtLeast(30)) })
            }
            if (Platform.KUGOU in enabled) {
                add(async { kugou.search(kw, 1, limitPerPlatform) })
            }
            if (Platform.QQ in enabled) {
                add(async { qq.search(kw, 1, limitPerPlatform) })
            }
        }

        val results = withTimeoutOrNull(totalBudgetMs) {
            jobs.awaitAll()
        } ?: jobs.mapNotNull { job ->
            // 超时后已完成的结果仍要保留，不能整批丢弃
            if (job.isCompleted) runCatching { job.await() }.getOrNull() else null
        }

        AggregateResult(mergeSongs(results), results.map { it.brief() })
    }

    /** 榜单目录（只有网易有；其余三家无公开榜单接口，保持空列表而不是造假数据） */
    suspend fun ranks(): AggregateResult<List<RankInfo>> = coroutineScope {
        val r = async { netease.toplist() }
        val res = withTimeoutOrNull(10_000) { r.await() }
            ?: ClientResult("wy", "toplist", false, 0, NeteaseClient.FALLBACK_RANKS, 0, "timeout")
        AggregateResult(res.data, listOf(res.brief()))
    }

    /** 榜单/歌单曲目：source 决定走哪家 */
    suspend fun rankSongs(rankId: String, source: String = Platform.NETEASE.code): AggregateResult<List<Song>> {
        val res = when (Platform.of(source)) {
            Platform.KUWO -> kuwo.playlistSongs(rankId)
            Platform.KUGOU -> kugou.specialSongs(rankId)
            Platform.NETEASE, Platform.QQ, null -> netease.rankSongs(rankId, fallbackToHotSearch = false)
        }
        return AggregateResult(res.data, listOf(res.brief()))
    }

    suspend fun playlistDetail(playlistId: String, source: String): AggregateResult<Playlist?> {
        val res = when (Platform.of(source)) {
            Platform.KUWO -> kuwo.playlistInfo(playlistId)
            Platform.KUGOU -> kugou.specialInfo(playlistId)
            else -> netease.playlistDetail(playlistId)
        }
        return AggregateResult(res.data, listOf(res.brief()))
    }

    suspend fun playlistSongs(playlistId: String, source: String): AggregateResult<List<Song>> {
        val res = when (Platform.of(source)) {
            Platform.KUWO -> kuwo.playlistSongs(playlistId)
            Platform.KUGOU -> kugou.specialSongs(playlistId)
            else -> netease.playlistSongs(playlistId)
        }
        return AggregateResult(res.data, listOf(res.brief()))
    }

    /** 歌词：按 source 路由。酷狗/QQ 的歌词链路属于解析层，本阶段不接 */
    suspend fun lyric(song: Song): AggregateResult<String> {
        return when (Platform.of(song.source)) {
            Platform.KUWO -> {
                val r = kuwo.lyric(song.id)
                AggregateResult(r.data.first, listOf(r.brief()))
            }
            Platform.NETEASE -> {
                val r = netease.lyric(song.id)
                AggregateResult(r.data.first, listOf(r.brief()))
            }
            else -> AggregateResult(
                "",
                listOf(PlatformBrief(song.source, "lyric", true, 200, 0, 0, "no-lyric-endpoint")),
            )
        }
    }

    /** 平台无关的歌手检索：酷我为主（原版主路），网易补足 */
    suspend fun searchArtists(keyword: String): AggregateResult<List<Artist>> = coroutineScope {
        val kw = keyword.trim()
        if (kw.isEmpty()) return@coroutineScope AggregateResult(emptyList(), emptyList())

        val jobs = listOf(
            async { kuwo.searchArtists(kw) },
            async { netease.searchArtists(kw) },
        )
        val results = withTimeoutOrNull(10_000) { jobs.awaitAll() }
            ?: listOf(ClientResult("kw", "searchArtists", false, 0, emptyList(), 0, "timeout"))

        val seen = HashSet<String>()
        val merged = ArrayList<Artist>()
        for (r in results) for (a in r.data) {
            if (a.name.isBlank()) continue
            if (seen.add(normalizeForMatch(a.name))) merged.add(a)
        }
        AggregateResult(merged, results.map { it.brief() })
    }

    /** 热门歌手：网易官方接口（原版同源） */
    suspend fun popularArtists(): AggregateResult<List<Artist>> = coroutineScope {
        val r = async { netease.popularArtists() }
        val res = withTimeoutOrNull(10_000) { r.await() }
            ?: ClientResult("wy", "popularArtists", false, 0, emptyList(), 0, "timeout")
        AggregateResult(res.data, listOf(res.brief()))
    }

    suspend fun artistTopSongs(artistId: String): AggregateResult<List<Song>> {
        val res = netease.artistTopSongs(artistId)
        return AggregateResult(res.data, listOf(res.brief()))
    }

    /** 歌单搜索聚合（酷我 + 酷狗） */
    suspend fun searchPlaylists(keyword: String): AggregateResult<List<Playlist>> = coroutineScope {
        val kw = keyword.trim()
        if (kw.isEmpty()) return@coroutineScope AggregateResult(emptyList(), emptyList())

        val jobs = listOf(
            async { kuwo.searchPlaylists(kw) },
            async { kugou.searchPlaylists(kw) },
        )
        val results = withTimeoutOrNull(10_000) { jobs.awaitAll() }
            ?: emptyList()

        val seen = HashSet<String>()
        val merged = ArrayList<Playlist>()
        for (r in results) for (p in r.data) {
            if (p.id.isBlank()) continue
            if (seen.add(p.source + ":" + p.id)) merged.add(p)
        }
        AggregateResult(merged, results.map { it.brief() })
    }

    // ---------- 合并 ----------

    /**
     * 跨平台合并去重。
     * 保留顺序：网易 → 酷我 → 酷狗 → QQ（调用方传进来的顺序）。
     * 网易优先，因为它是唯一能稳定给出封面 + 精确时长的源。
     */
    private fun mergeSongs(results: List<ClientResult<List<Song>>>): List<Song> {
        val seen = HashSet<String>()
        val out = ArrayList<Song>()
        for (r in results) {
            if (!r.ok) continue
            for (s in r.data) {
                if (s.name.isBlank() || s.id.isBlank()) continue
                val key = s.dedupeKey
                if (key.isBlank() || !seen.add(key)) continue
                out.add(s)
            }
        }
        return out
    }

    data class AggregateResult<T>(
        val data: T,
        val details: List<PlatformBrief>,
    ) {
        val allOk: Boolean get() = details.isNotEmpty() && details.all { it.ok }
        val anyOk: Boolean get() = details.any { it.ok }
        fun countOf(platform: String, operation: String? = null): Int =
            details.firstOrNull { it.platform == platform && (operation == null || it.operation == operation) }?.count ?: 0
    }

    data class PlatformBrief(
        /** 平台代码：wy / kw / kg / tx */
        val platform: String,
        /** 操作名：search / lyric / toplist ... */
        val operation: String,
        val ok: Boolean,
        val status: Int,
        val count: Int,
        val rawLength: Int,
        val error: String?,
    )

    private fun ClientResult<*>.brief() = PlatformBrief(
        platform = platform,
        operation = operation,
        ok = ok,
        status = status,
        count = count,
        rawLength = rawLength,
        error = error,
    )



}