package vip.wangxiaoming.music.data.remote.client

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.add
import java.net.URLEncoder
import vip.wangxiaoming.music.data.remote.http.JsonUtil
import vip.wangxiaoming.music.data.remote.http.LrcParser
import vip.wangxiaoming.music.data.remote.http.PlatformHttp
import vip.wangxiaoming.music.data.remote.model.Artist
import vip.wangxiaoming.music.data.remote.model.ClientResult
import vip.wangxiaoming.music.data.remote.model.LyricLine
import vip.wangxiaoming.music.data.remote.model.Platform
import vip.wangxiaoming.music.data.remote.model.Playlist
import vip.wangxiaoming.music.data.remote.model.RankInfo
import vip.wangxiaoming.music.data.remote.model.Song

/**
 * 网易云音乐元数据客户端（四家里的元数据主力）。
 *
 * 全部走游客接口，不需要登录 Cookie。唯一需要 POST 的是搜索：
 * 用 GET 也能返回，但服务端对 GET 的 s= 编码更敏感，实测 POST 表单更稳。
 *
 * 字段实测（2026-10 复核，与 jadx 里原版实现一致）：
 *  - 搜索：result.songs[].id / name / artists[].name / album.name / duration(ms)
 *  - 详情：songs[].id / name / ar[].name / al.name / al.picUrl / dt(ms)
 *  - 榜单：list[].id / name / coverImgUrl / trackCount / updateFrequency / description
 *  - 歌单：playlist.trackIds[]（最多 200+）/ tracks[]（只给前 10 首，不能用作全量）
 *  - 歌手：songs[].ar[].name / al.picUrl / dt
 */
class NeteaseClient {

    private val base = "https://music.163.com"

    /** 平台代码，写入 ClientResult.platform */
    private val PF = Platform.NETEASE.code

    suspend fun search(keyword: String, offset: Int = 0, limit: Int = 30): ClientResult<List<Song>> {
        val kw = keyword.trim()
        if (kw.isEmpty()) return empty("search") { emptyList() }

        // 实测：POST 与 GET 都返回 200；POST 用表单避免 URL 长度与编码问题
        val form = "s=" + enc(kw) +
            "&type=1&offset=$offset&total=true&limit=$limit"
        val resp = PlatformHttp.postForm(
            "$base/api/search/get/web?csrf_token=",
            form,
            referer = "$base/",
        )
        if (!resp.isSuccess) return fail("search", resp)

        val root = JsonUtil.parse(resp.body) ?: return failParse("search", resp)
        val songsArr = JsonUtil.arrAt(root, "result", "songs") ?: return ok("search", resp, emptyList())

        val ids = ArrayList<Long>(songsArr.size)
        val songs = ArrayList<Song>(songsArr.size)
        for (el in songsArr) {
            val o = JsonUtil.obj(el) ?: continue
            val id = JsonUtil.str(o, "id") ?: continue
            id.toLongOrNull()?.let { ids.add(it) }
            val artists = JsonUtil.strList(o, "artists", "name").ifEmpty {
                JsonUtil.strList(o, "ar", "name")
            }
            val albumObj = JsonUtil.obj(o.get("album")) ?: JsonUtil.obj(o.get("al"))
            val durationMs = JsonUtil.long(o, "duration") ?: JsonUtil.long(o, "dt") ?: 0L
            val secs = (durationMs / 1000).toInt()
            songs.add(
                Song(
                    id = id,
                    name = cleanTitle(JsonUtil.str(o, "name").orEmpty()),
                    artist = artists.joinToString(" / "),
                    album = JsonUtil.str(albumObj, "name") ?: "热门专辑",
                    cover = JsonUtil.https(JsonUtil.str(albumObj, "picUrl")),
                    source = Platform.NETEASE.code,
                    duration = JsonUtil.mmss(secs),
                    durationSeconds = secs,
                    artistId = JsonUtil.arr(o.get("artists"))?.firstOrNull()
                        ?.let { JsonUtil.str(JsonUtil.obj(it), "id") }.orEmpty(),
                    albumId = JsonUtil.str(albumObj, "id") ?: "",
                ),
            )
        }
        return ok("search", resp, songs)
    }

    /** /api/v3/song/detail?c=[{"id":1},{"id":2}] */
    suspend fun songDetail(ids: List<Long>): ClientResult<List<Song>> {
        if (ids.isEmpty()) return empty("songDetail") { emptyList() }
        val c = buildJsonArray { ids.take(MAX_DETAIL_BATCH).forEach { add(buildJsonObjectId(it)) } }.toString()
        val url = "$base/api/v3/song/detail?c=" + enc(c)
        val resp = PlatformHttp.get(url, referer = "$base/", useLongClient = ids.size > 50)
        if (!resp.isSuccess) return fail("songDetail", resp)

        val root = JsonUtil.parse(resp.body) ?: return failParse("songDetail", resp)
        val arr = JsonUtil.arrAt(root, "songs") ?: return ok("songDetail", resp, emptyList())
        val songs = ArrayList<Song>(arr.size)
        for (el in arr) {
            val o = JsonUtil.obj(el) ?: continue
            songs.add(songFromDetail(o))
        }
        return ok("songDetail", resp, songs)
    }

    /** /api/toplist —— 官方榜单目录（65 个）。失败时给 4 个内置榜兜底，与原版一致 */
    suspend fun toplist(): ClientResult<List<RankInfo>> {
        val resp = PlatformHttp.get("$base/api/toplist", referer = "$base/")
        if (!resp.isSuccess) return failWithFallback<List<RankInfo>>("toplist", resp, FALLBACK_RANKS)

        val root = JsonUtil.parse(resp.body) ?: return failWithFallback<List<RankInfo>>("toplist", resp, FALLBACK_RANKS)
        val arr = JsonUtil.arrAt(root, "list") ?: return failWithFallback<List<RankInfo>>("toplist", resp, FALLBACK_RANKS)

        val ranks = ArrayList<RankInfo>(arr.size)
        for (el in arr) {
            val o = JsonUtil.obj(el) ?: continue
            val id = JsonUtil.str(o, "id") ?: continue
            val trackCount = JsonUtil.int(o, "trackCount") ?: 0
            // 原版过滤 <30 首的小榜，保持同样口径
            if (trackCount in 1..29) continue
            ranks.add(
                RankInfo(
                    id = id,
                    source = Platform.NETEASE.code,
                    title = cleanTitle(JsonUtil.str(o, "name").orEmpty()),
                    updateFrequency = JsonUtil.str(o, "updateFrequency") ?: "每日更新",
                    coverUrl = stripQuery(JsonUtil.https(JsonUtil.str(o, "coverImgUrl"))),
                    description = JsonUtil.str(o, "description") ?: "全网权威音乐实时风向标",
                ),
            )
        }
        return ok("toplist", resp, ranks.ifEmpty { FALLBACK_RANKS })
    }

    /** /api/v6/playlist/detail —— 歌单元信息 */
    suspend fun playlistDetail(id: String): ClientResult<Playlist?> {
        val resp = PlatformHttp.get("$base/api/v6/playlist/detail?id=" + enc(id), referer = "$base/")
        if (!resp.isSuccess) return failNullable("playlistDetail", resp)

        val root = JsonUtil.parse(resp.body) ?: return failParseNullable("playlistDetail", resp)
        val pl = JsonUtil.objAt(root, "playlist") ?: return failParseNullable("playlistDetail", resp)
        return ClientResult(
            platform = PF,
            operation = "playlistDetail",
            ok = true,
            status = resp.status,
            rawLength = resp.length,
            data = Playlist(
                id = JsonUtil.str(pl, "id") ?: id,
                name = cleanTitle(JsonUtil.str(pl, "name").orEmpty()),
                coverUrl = JsonUtil.https(JsonUtil.str(pl, "coverImgUrl")),
                description = JsonUtil.str(pl, "description") ?: "",
                playCount = JsonUtil.long(pl, "playCount") ?: 0L,
                trackCount = JsonUtil.int(pl, "trackCount") ?: 0,
                source = Platform.NETEASE.code,
            ),
        )
    }

    /**
     * 榜单/歌单曲目 —— 两阶段：
     *  1) /api/v6/playlist/detail 只取 trackIds（tracks 字段只给前 10 首，不能当全量）
     *  2) /api/v3/song/detail 批量补全，每批 200
     * 详情失败回落 /api/playlist/detail（原版同款兜底）。
     */
    suspend fun rankSongs(id: String, fallbackToHotSearch: Boolean = false): ClientResult<List<Song>> {
        val trackIds = fetchTrackIds(id)
        if (trackIds.isEmpty()) {
            return if (fallbackToHotSearch) search("热门推荐") else
                ClientResult(PF, "rankSongs", true, 200, emptyList<Song>())
        }

        val all = ArrayList<Song>(trackIds.size)
        for (chunk in trackIds.chunked(MAX_DETAIL_BATCH)) {
            val detail = songDetail(chunk)
            if (detail.ok) all.addAll(detail.data)
        }
        return ClientResult(
            platform = PF,
            operation = "rankSongs",
            ok = all.isNotEmpty(),
            status = 200,
            rawLength = trackIds.size,
            data = all,
        )
    }

    /** 与 rankSongs 同实现，语义上用于"歌单歌曲" */
    suspend fun playlistSongs(id: String): ClientResult<List<Song>> = rankSongs(id, false)

    private suspend fun fetchTrackIds(id: String): List<Long> {
        val url = "$base/api/v6/playlist/detail?id=" + enc(id)
        val resp = PlatformHttp.get(url, referer = "$base/", useLongClient = true)
        val ids = ArrayList<Long>()
        if (resp.isSuccess) {
            JsonUtil.parse(resp.body)?.let { root ->
                JsonUtil.arrAt(root, "playlist", "trackIds")?.forEach { el ->
                    JsonUtil.long(JsonUtil.obj(el), "id")?.let { ids.add(it) }
                }
            }
        }
        if (ids.isEmpty()) {
            // 回落老接口
            val old = PlatformHttp.get("$base/api/playlist/detail?id=" + enc(id), referer = "$base/", useLongClient = true)
            if (old.isSuccess) {
                JsonUtil.parse(old.body)?.let { root ->
                    JsonUtil.arrAt(root, "playlist", "trackIds")?.forEach { el ->
                        JsonUtil.long(JsonUtil.obj(el), "id")?.let { ids.add(it) }
                    }
                    if (ids.isEmpty()) {
                        JsonUtil.arrAt(root, "playlist", "tracks")?.forEach { el ->
                            JsonUtil.long(JsonUtil.obj(el), "id")?.let { ids.add(it) }
                        }
                    }
                }
            }
        }
        return ids.distinct()
    }

    /** /api/artist/top/song?id= —— 歌手热门 50 首 */
    suspend fun artistTopSongs(artistId: String): ClientResult<List<Song>> {
        val resp = PlatformHttp.get(
            "$base/api/artist/top/song?id=" + enc(artistId),
            referer = "$base/",
        )
        if (!resp.isSuccess) return fail("artistTopSongs", resp)

        val root = JsonUtil.parse(resp.body) ?: return failParse("artistTopSongs", resp)
        val arr = JsonUtil.arrAt(root, "songs") ?: return ok("artistTopSongs", resp, emptyList())
        val songs = arr.mapNotNull { el ->
            JsonUtil.obj(el)?.let { songFromDetail(it) }
        }
        return ok("artistTopSongs", resp, songs)
    }

    /** /api/artist/list?categoryCode=1001 —— 热门歌手（原版同参数） */
    suspend fun popularArtists(limit: Int = 30): ClientResult<List<Artist>> {
        val url = "$base/api/artist/list?categoryCode=1001&offset=0&limit=$limit"
        val resp = PlatformHttp.get(url, referer = "$base/")
        if (!resp.isSuccess) return fail("popularArtists", resp)

        val root = JsonUtil.parse(resp.body) ?: return failParse("popularArtists", resp)
        val arr = JsonUtil.arrAt(root, "artists") ?: return ok("popularArtists", resp, emptyList())
        val artists = arr.mapNotNull { el ->
            val o = JsonUtil.obj(el) ?: return@mapNotNull null
            val id = JsonUtil.str(o, "id") ?: return@mapNotNull null
            Artist(
                id = id,
                name = JsonUtil.str(o, "name").orEmpty(),
                // 实测 picUrl 返回 http://p2.music.126.net/...，必须升级否则 Android 9+ 加载失败
                picUrl = JsonUtil.https(JsonUtil.str(o, "picUrl")),
                musicSize = JsonUtil.int(o, "musicSize") ?: 0,
                albumSize = JsonUtil.int(o, "albumSize") ?: 0,
            )
        }
        return ok("popularArtists", resp, artists)
    }

    /** /api/song/lyric —— 返回 LRC 原文 + 结构化行 */
    suspend fun lyric(id: String): ClientResult<Pair<String, List<LyricLine>>> {
        val url = "$base/api/song/lyric?os=pc&id=" + enc(id) + "&lv=-1&kv=-1&tv=-1"
        val resp = PlatformHttp.get(url, referer = "$base/")
        if (!resp.isSuccess) return fail("lyric", resp)

        val root = JsonUtil.parse(resp.body) ?: return failParse("lyric", resp)
        val lrc = JsonUtil.str(JsonUtil.objAt(root, "lrc"), "lyric").orEmpty()
        return ok("lyric", resp, lrc to LrcParser.parse(lrc))
    }

    /** /api/search/get/web?type=100 —— 歌手搜索（酷我主路的兜底，原版 searchArtists 同款） */
    suspend fun searchArtists(keyword: String, limit: Int = 10): ClientResult<List<Artist>> {
        val kw = keyword.trim()
        if (kw.isEmpty()) return ClientResult(PF, "searchArtists", true, 200, emptyList<Artist>())

        val form = "s=" + enc(kw) + "&type=100&offset=0&total=true&limit=$limit"
        val resp = PlatformHttp.postForm("$base/api/search/get/web?csrf_token=", form, referer = "$base/")
        if (!resp.isSuccess) return fail("searchArtists", resp)

        val root = JsonUtil.parse(resp.body) ?: return failParse("searchArtists", resp)
        val arr = JsonUtil.arrAt(root, "result", "artists") ?: return ok("searchArtists", resp, emptyList())

        val artists = arr.mapNotNull { el ->
            val o = JsonUtil.obj(el) ?: return@mapNotNull null
            val id = JsonUtil.str(o, "id") ?: return@mapNotNull null
            Artist(
                id = id,
                name = JsonUtil.str(o, "name").orEmpty(),
                picUrl = JsonUtil.https(
                    JsonUtil.str(o, "picUrl") ?: JsonUtil.str(o, "img1v1Url"),
                ),
                musicSize = JsonUtil.int(o, "musicSize") ?: 0,
                albumSize = JsonUtil.int(o, "albumSize") ?: 0,
            )
        }
        return ok("searchArtists", resp, artists)
    }

    // ---------- 内部映射 ----------

    private fun songFromDetail(o: JsonObject): Song {
        val artists = JsonUtil.strList(o, "ar", "name").ifEmpty {
            JsonUtil.strList(o, "artists", "name")
        }
        val al = JsonUtil.obj(o.get("al")) ?: JsonUtil.obj(o.get("album"))
        val ms = JsonUtil.long(o, "dt") ?: JsonUtil.long(o, "duration") ?: 0L
        val secs = (ms / 1000).toInt()
        val arFirst = JsonUtil.arr(o.get("ar"))?.firstOrNull() ?: JsonUtil.arr(o.get("artists"))?.firstOrNull()
        return Song(
            id = JsonUtil.str(o, "id").orEmpty(),
            name = cleanTitle(JsonUtil.str(o, "name").orEmpty()),
            artist = artists.joinToString(" / "),
            album = JsonUtil.str(al, "name") ?: "精选专辑",
            cover = JsonUtil.https(JsonUtil.str(al, "picUrl")),
            source = Platform.NETEASE.code,
            duration = JsonUtil.mmss(secs),
            durationSeconds = secs,
            artistId = JsonUtil.str(JsonUtil.obj(arFirst), "id").orEmpty(),
            albumId = JsonUtil.str(al, "id") ?: "",
        )
    }

    private fun buildJsonObjectId(id: Long) = kotlinx.serialization.json.buildJsonObject {
        put("id", kotlinx.serialization.json.JsonPrimitive(id))
    }

    private fun <T> ok(tag: String, resp: PlatformHttp.RawResponse, data: T) =
        ClientResult(platform = PF, operation = tag, ok = true, status = resp.status, data = data, rawLength = resp.length)

    private fun <T> fail(tag: String, resp: PlatformHttp.RawResponse) =
        ClientResult(platform = PF, operation = tag, ok = false, status = resp.status, data = emptyData<T>(), rawLength = resp.length, error = resp.error ?: "http ${resp.status}")

    private fun <T> failParse(tag: String, resp: PlatformHttp.RawResponse) =
        ClientResult(platform = PF, operation = tag, ok = false, status = resp.status, data = emptyData<T>(), rawLength = resp.length,
            error = "non-json: " + resp.body.take(120))

    private fun failNullable(tag: String, resp: PlatformHttp.RawResponse) =
        ClientResult(platform = PF, operation = tag, ok = false, status = resp.status, data = (null as Playlist?), rawLength = resp.length, error = resp.error ?: "http ${resp.status}")

    private fun failParseNullable(tag: String, resp: PlatformHttp.RawResponse) =
        ClientResult(platform = PF, operation = tag, ok = false, status = resp.status, data = (null as Playlist?), rawLength = resp.length,
            error = "non-json: " + resp.body.take(120))

    @Suppress("UNCHECKED_CAST")
    private fun <T> emptyData(): T = emptyList<Any>() as T

    private fun <T> empty(tag: String, block: () -> T) =
        ClientResult(platform = PF, operation = tag, ok = true, status = 200, data = block(), rawLength = 0)

    private fun <T> failWithFallback(tag: String, resp: PlatformHttp.RawResponse, fb: List<RankInfo>): ClientResult<T> =
        ClientResult(platform = PF, operation = tag, ok = true, status = resp.status, data = fb as T,
            rawLength = resp.length, error = "fallback: " + (resp.error ?: "http ${resp.status}"))

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun stripQuery(url: String): String = url.substringBefore('?')

    companion object {
        const val MAX_DETAIL_BATCH = 200

        /** 标题清洗，与原版 cleanTitleText 等价：去 【】[]（） 与首尾装饰符 */
        fun cleanTitle(raw: String?): String {
            if (raw.isNullOrBlank()) return ""
            if (isPlaceholderTitle(raw)) return ""
            var t = raw
                .replace(Regex("【[^】]*】"), "")
                .replace(Regex("\\[[^\\]]*\\]"), "")
                .replace(Regex("（[^）]*）"), "")
                .replace(Regex("\\([^\\)]*\\)"), "")
                .trim()
            if (t.startsWith("｜") || t.startsWith("|") || t.startsWith("-")) t = t.substring(1).trim()
            if (t.endsWith("｜") || t.endsWith("|") || t.endsWith("-")) t = t.dropLast(1).trim()
            // 占位值归一必须在 ifEmpty 回退之前，否则空串会被 raw.trim() 还原成 "undefined"
            if (isPlaceholderTitle(t)) return ""
            return t.ifEmpty { raw.trim() }
        }

        private val PLACEHOLDER_TITLES = setOf("undefined", "null", "none", "nil")

        private fun isPlaceholderTitle(s: String): Boolean =
            s.trim().lowercase() in PLACEHOLDER_TITLES

        val FALLBACK_RANKS = listOf(
            RankInfo("3778678", "wy", "热歌榜", "每日更新",
                "https://p1.music.126.net/GhhuF6Ep5TmanDu708Stopw==/109951163124508493.jpg",
                "全网热播综合指数最高曲目"),
            RankInfo("19723756", "wy", "飙升榜", "实时更新",
                "https://p1.music.126.net/pcYHpMkdStffQdag8snvAg==/109951166952713766.jpg",
                "近期热度飙升最快单曲"),
            RankInfo("3779629", "wy", "新歌榜", "每日更新",
                "https://p1.music.126.net/N2HO1XM2IDA8lAVUdl8ivg==/109951166962388666.jpg",
                "甄选新鲜出炉好歌"),
            RankInfo("2884035", "wy", "原创榜", "每周更新",
                "https://p1.music.126.net/sBzD11nforcuh1jdLSgX7g==/18740076185638788.jpg",
                "独立音乐人高品质力作"),
        )
    }
}
