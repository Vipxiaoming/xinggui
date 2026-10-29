package vip.wangxiaoming.music.data.remote.client

import kotlinx.serialization.json.JsonObject
import java.net.URLEncoder
import vip.wangxiaoming.music.data.remote.http.JsonUtil
import vip.wangxiaoming.music.data.remote.http.LrcParser
import vip.wangxiaoming.music.data.remote.http.PlatformHttp
import vip.wangxiaoming.music.data.remote.model.Artist
import vip.wangxiaoming.music.data.remote.model.ClientResult
import vip.wangxiaoming.music.data.remote.model.LyricLine
import vip.wangxiaoming.music.data.remote.model.Platform
import vip.wangxiaoming.music.data.remote.model.Playlist
import vip.wangxiaoming.music.data.remote.model.Song

/**
 * 酷我音乐元数据客户端。
 *
 * 重要实测结论（与原分析报告不同，以本次实测为准）：
 *  1) search.kuwo.cn/r.s 现在返回**严格 JSON**，Content-Type 是 text/plain。
 *     原版用 `Function('return '+rawText)()` 是因为老版本返回 JS 字面量（key 无引号）。
 *     这里先尝试正常 JSON，失败再走「无引号 key 修补」的容错通道 —— 两条路都留着，
 *     因为不同 CDN/边缘节点仍可能给老格式。
 *  2) 歌词必须走 HTTPS：http://m.kuwo.cn/newh5/singles/songinfoandlrc 只返回 127 字节空壳，
 *     https 同路径返回完整 lrclist。
 *  3) 歌单接口需要**有效** pid，无效 pid 返回 {"code":-1,"msg":"未获取到歌单详情"}。
 *     有效 pid 的响应里 code/status 都可能出现，判定以 data.musicList 是否存在为准。
 *  4) 封面 = "http://img1.kuwo.cn/star/albumcover/" + web_albumpic_short
 *     （形如 "120/s3s94/93/211513640.jpg"），可把 "120/" 换成 "500/" 取大图。
 */
class KuwoClient {

    /** 平台代码，写入 ClientResult.platform */
    private val PF = Platform.KUWO.code

    /** 搜索音源。默认 rn=100，与原版一致（不是 20/30） */
    suspend fun search(keyword: String, page: Int = 0, rn: Int = 100): ClientResult<List<Song>> {
        val kw = keyword.trim()
        if (kw.isEmpty()) return ClientResult(PF, "search", true, 200, emptyList<Song>())

        val url = "$SEARCH_BASE?client=kt&all=" + enc(kw) +
            "&pn=$page&rn=$rn&uid=794764845&ver=kwplayer_ar_9.2.2.1&vipver=1" +
            "&show_copyright_off=1&newsearch=1&ft=music&cluster=0&strategy=2012" +
            "&encoding=utf8&rformat=json&vermerge=1&mobi=1"
        val resp = PlatformHttp.get(url, userAgent = PlatformHttp.UA_OKHTTP)
        if (!resp.isSuccess) return fail("search", resp)

        val root = parseLoose(resp.body) ?: return failParse("search", resp)
        val arr = JsonUtil.arrAt(root, "abslist") ?: return ok("search", resp, emptyList())

        val seen = HashSet<String>()
        val songs = ArrayList<Song>(arr.size)
        for (el in arr) {
            val o = JsonUtil.obj(el) ?: continue
            val musicRid = JsonUtil.str(o, "MUSICRID")
                ?: JsonUtil.str(o, "DC_TARGETID")?.let { "MUSIC_$it" }
                ?: continue
            val id = musicRid.removePrefix("MUSIC_")
            if (id.isEmpty()) continue
            if (!seen.add(id.lowercase())) continue

            val secs = JsonUtil.int(o, "DURATION") ?: 0
            songs.add(
                Song(
                    id = id,
                    name = NeteaseClient.cleanTitle(JsonUtil.str(o, "SONGNAME") ?: JsonUtil.str(o, "NAME") ?: "未知歌曲"),
                    artist = (JsonUtil.str(o, "ARTIST") ?: JsonUtil.str(o, "FARTIST") ?: "未知歌手")
                        .replace("&nbsp;", " ").trim(),
                    album = JsonUtil.str(o, "ALBUM") ?: "精选专辑",
                    cover = coverFrom(o),
                    source = Platform.KUWO.code,
                    duration = JsonUtil.mmss(secs),
                    durationSeconds = secs,
                    albumId = JsonUtil.str(o, "ALBUMID").orEmpty(),
                    artistId = JsonUtil.str(o, "ARTISTID").orEmpty(),
                ),
            )
        }
        return ok("search", resp, songs)
    }

    /** 歌单详情 + 首屏曲目 */
    suspend fun playlistInfo(pid: String, page: Int = 1, rn: Int = 100): ClientResult<Playlist?> {
        val id = pid.trim().removePrefix("MUSIC_")
        if (id.isEmpty()) return ClientResult(PF, "playlistInfo", false, 400, null, 0, "empty pid")

        val resp = PlatformHttp.get(
            "$H5_BASE/playlist/playListInfo?pid=$id&pn=$page&rn=$rn",
            userAgent = PlatformHttp.UA_DESKTOP,
        )
        if (!resp.isSuccess) return failNullable("playlistInfo", resp)

        val root = parseLoose(resp.body) ?: return failParseNullable("playlistInfo", resp)
        val data = JsonUtil.objAt(root, "data") ?: return failParseNullable("playlistInfo", resp)

        // 无效 pid：{"code":-1,"msg":"未获取到歌单详情"}，data 为空
        val list = JsonUtil.arr(data["musicList"]) ?: return failParseNullable("playlistInfo", resp)
        if (list.isEmpty() && JsonUtil.int(root, "code") == -1) {
            return ClientResult(PF, "playlistInfo", false, resp.status, null, resp.length,
                "code=-1 " + (JsonUtil.str(root, "msg") ?: ""))
        }

        val cover = JsonUtil.https(
            JsonUtil.str(data, "img700") ?: JsonUtil.str(data, "img500")
            ?: JsonUtil.str(data, "img300") ?: JsonUtil.str(data, "img"),
        )
        return ClientResult(
            platform = PF,
            operation = "playlistInfo",
            ok = true,
            status = resp.status,
            rawLength = resp.length,
            data = Playlist(
                id = JsonUtil.str(data, "id") ?: id,
                name = JsonUtil.str(data, "name") ?: "酷我歌单",
                coverUrl = cover,
                description = JsonUtil.str(data, "desc") ?: JsonUtil.str(data, "info") ?: "",
                playCount = JsonUtil.long(data, "listencnt") ?: 0L,
                trackCount = JsonUtil.int(data, "total") ?: list.size,
                source = Platform.KUWO.code,
            ),
        )
    }

    /** 歌单曲目（分页）。第 1 页也能当"歌单歌曲"直接用 */
    suspend fun playlistSongs(pid: String, page: Int = 1, rn: Int = 100): ClientResult<List<Song>> {
        val id = pid.trim()
        if (id.isEmpty()) return ClientResult(PF, "playlistSongs", true, 200, emptyList<Song>())

        val resp = PlatformHttp.get(
            "$H5_BASE/playlist/playListInfo?pid=$id&pn=$page&rn=$rn",
            userAgent = PlatformHttp.UA_DESKTOP,
        )
        if (!resp.isSuccess) return fail("playlistSongs", resp)

        val root = parseLoose(resp.body) ?: return failParse("playlistSongs", resp)
        val list = JsonUtil.arrAt(root, "data", "musicList") ?: return ok("playlistSongs", resp, emptyList())

        val songs = list.mapNotNull { el ->
            val o = JsonUtil.obj(el) ?: return@mapNotNull null
            songFromMusicList(o)
        }
        return ok("playlistSongs", resp, songs)
    }

    /** 歌词。必须 HTTPS（HTTP 只给 127 字节空壳） */
    suspend fun lyric(musicId: String): ClientResult<Pair<String, List<LyricLine>>> {
        val id = musicId.trim().removePrefix("MUSIC_")
        if (id.isEmpty()) return ClientResult(PF, "lyric", true, 200, "" to emptyList())

        val resp = PlatformHttp.get(
            "$SINGLES_BASE/songinfoandlrc?musicId=$id",
            userAgent = PlatformHttp.UA_DESKTOP,
        )
        if (!resp.isSuccess) return fail("lyric", resp)

        val root = parseLoose(resp.body) ?: return failParse("lyric", resp)
        val list = JsonUtil.arrAt(root, "data", "lrclist") ?: return ok("lyric", resp, "" to emptyList())

        val lines = list.mapNotNull { el ->
            val o = JsonUtil.obj(el) ?: return@mapNotNull null
            val text = JsonUtil.str(o, "lineLyric").orEmpty()
            val sec = JsonUtil.dbl(o, "time") ?: return@mapNotNull null
            LyricLine((sec * 1000).toLong(), text)
        }.sortedBy { it.timeMs }

        val lrc = if (lines.size > 3) LrcParser.toLrc(lines) else ""
        return ok("lyric", resp, lrc to lines)
    }

    /** 歌手搜索（原版 searchArtists 主路，ft=artist） */
    suspend fun searchArtists(keyword: String, rn: Int = 12): ClientResult<List<Artist>> {
        val kw = keyword.trim()
        if (kw.isEmpty()) return ClientResult(PF, "searchArtists", true, 200, emptyList<Artist>())

        val url = "$SEARCH_BASE?client=kt&all=" + enc(kw) +
            "&pn=0&rn=$rn&ft=artist&encoding=utf8&rformat=json&newsearch=1&mobi=1"
        val resp = PlatformHttp.get(url, userAgent = PlatformHttp.UA_OKHTTP)
        if (!resp.isSuccess) return fail("searchArtists", resp)

        val root = parseLoose(resp.body) ?: return failParse("searchArtists", resp)
        val arr = JsonUtil.arrAt(root, "abslist") ?: return ok("searchArtists", resp, emptyList())
        val basePic = JsonUtil.str(root, "BASEPICPATH") ?: "http://img1.kuwo.cn/star/starheads/"

        val artists = arr.mapNotNull { el ->
            val o = JsonUtil.obj(el) ?: return@mapNotNull null
            val id = JsonUtil.str(o, "ARTISTID") ?: return@mapNotNull null
            // 优先用 CDN 直给的 hts_PICPATH（已是 https），否则按 base + PICPATH 拼
            val pic = JsonUtil.str(o, "hts_PICPATH")
                ?: JsonUtil.str(o, "PICPATH")?.let { basePic + it }
            Artist(
                id = id,
                name = (JsonUtil.str(o, "ARTIST") ?: "").replace("&nbsp;", " ").trim(),
                picUrl = JsonUtil.https(pic),
                musicSize = JsonUtil.int(o, "SONGNUM") ?: 0,
                albumSize = JsonUtil.int(o, "ALBUMNUM") ?: 0,
            )
        }
        return ok("searchArtists", resp, artists)
    }

    /** 歌单搜索（原版无此能力，聚合层"搜索歌单"要用） */
    suspend fun searchPlaylists(keyword: String, rn: Int = 20): ClientResult<List<Playlist>> {
        val kw = keyword.trim()
        if (kw.isEmpty()) return ClientResult(PF, "searchPlaylists", true, 200, emptyList<Playlist>())

        val url = "$SEARCH_BASE?client=kt&all=" + enc(kw) +
            "&pn=0&rn=$rn&ft=playlist&encoding=utf8&rformat=json&newsearch=1&mobi=1"
        val resp = PlatformHttp.get(url, userAgent = PlatformHttp.UA_OKHTTP)
        if (!resp.isSuccess) return fail("searchPlaylists", resp)

        val root = parseLoose(resp.body) ?: return failParse("searchPlaylists", resp)
        val arr = JsonUtil.arrAt(root, "abslist") ?: return ok("searchPlaylists", resp, emptyList())

        val playlists = arr.mapNotNull { el ->
            val o = JsonUtil.obj(el) ?: return@mapNotNull null
            val id = JsonUtil.str(o, "playlistid") ?: JsonUtil.str(o, "DC_TARGETID") ?: return@mapNotNull null
            Playlist(
                id = id,
                name = JsonUtil.str(o, "name") ?: "酷我歌单",
                coverUrl = JsonUtil.https(JsonUtil.str(o, "hts_pic") ?: JsonUtil.str(o, "pic")),
                description = JsonUtil.str(o, "intro") ?: "",
                playCount = JsonUtil.long(o, "playcnt") ?: 0L,
                trackCount = JsonUtil.int(o, "songnum") ?: 0,
                source = Platform.KUWO.code,
            )
        }
        return ok("searchPlaylists", resp, playlists)
    }

    // ---------- 内部 ----------

    private fun songFromMusicList(o: JsonObject): Song {
        val id = JsonUtil.str(o, "rid") ?: JsonUtil.str(o, "musicrid")?.removePrefix("MUSIC_") ?: ""
        val secs = JsonUtil.int(o, "duration") ?: JsonUtil.parseMmss(JsonUtil.str(o, "songTimeMinutes"))
        return Song(
            id = id,
            name = NeteaseClient.cleanTitle(JsonUtil.str(o, "name") ?: "未知歌曲"),
            artist = (JsonUtil.str(o, "artist") ?: "未知歌手").trim(),
            album = JsonUtil.str(o, "album") ?: "精选专辑",
            cover = JsonUtil.https(JsonUtil.str(o, "pic") ?: JsonUtil.str(o, "albumpic")),
            source = Platform.KUWO.code,
            duration = JsonUtil.mmss(secs),
            durationSeconds = secs,
            albumId = JsonUtil.str(o, "albumid").orEmpty(),
            artistId = JsonUtil.str(o, "artistid").orEmpty(),
        )
    }

    private fun coverFrom(o: JsonObject): String {
        JsonUtil.str(o, "hts_albumpic")?.let { return JsonUtil.https(it) }
        val short = JsonUtil.str(o, "web_albumpic_short")
            ?: JsonUtil.str(o, "web_albumpic")
            ?: return ""
        return JsonUtil.https(COVER_BASE + short)
    }

    /**
     * 容错解析：先按严格 JSON 解；失败则把无引号 key 补上引号再试一次。
     * 补丁只处理 `{key:` 与 `,key:` 两种位置，不会碰字符串内部。
     */
    private fun parseLoose(raw: String): JsonObject? = run {
        JsonUtil.obj(JsonUtil.parse(raw)) ?: runCatching {
            val patched = UNQUOTED_KEY.replace(raw) { m ->
                m.groupValues[1] + "\"" + m.groupValues[2] + "\":"
            }
            JsonUtil.obj(JsonUtil.parse(patched))
        }.getOrNull()
    }

    private fun <T> ok(tag: String, resp: PlatformHttp.RawResponse, data: T) =
        ClientResult(platform = PF, operation = tag, ok = true, status = resp.status, data = data, rawLength = resp.length)

    private fun <T> fail(tag: String, resp: PlatformHttp.RawResponse) =
        ClientResult(platform = PF, operation = tag, ok = false, status = resp.status, data = (emptyList<Song>() as T), rawLength = resp.length,
            error = resp.error ?: "http ${resp.status}")

    private fun <T> failParse(tag: String, resp: PlatformHttp.RawResponse) =
        ClientResult(platform = PF, operation = tag, ok = false, status = resp.status, data = (emptyList<Song>() as T), rawLength = resp.length,
            error = "non-json: " + resp.body.take(120))

    private fun failNullable(tag: String, resp: PlatformHttp.RawResponse) =
        ClientResult(platform = PF, operation = tag, ok = false, status = resp.status, data = (null as Playlist?), rawLength = resp.length, error = resp.error ?: "http ${resp.status}")

    private fun failParseNullable(tag: String, resp: PlatformHttp.RawResponse) =
        ClientResult(platform = PF, operation = tag, ok = false, status = resp.status, data = (null as Playlist?), rawLength = resp.length,
            error = "non-json: " + resp.body.take(120))

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    companion object {
        private const val SEARCH_BASE = "https://search.kuwo.cn/r.s"
        private const val H5_BASE = "https://m.kuwo.cn/newh5app/wapi/api/www"
        private const val SINGLES_BASE = "https://m.kuwo.cn/newh5/singles"
        /** 与 smali 常量一致：http://img1.kuwo.cn/star/albumcover/ */
        private const val COVER_BASE = "http://img1.kuwo.cn/star/albumcover/"

        private val UNQUOTED_KEY = Regex("([{,])\\s*([A-Za-z_][A-Za-z0-9_]*)\\s*:")

    }
}