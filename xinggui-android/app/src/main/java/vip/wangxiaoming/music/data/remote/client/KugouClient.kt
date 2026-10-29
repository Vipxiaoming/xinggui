package vip.wangxiaoming.music.data.remote.client

import kotlinx.serialization.json.JsonObject
import java.net.URLEncoder
import vip.wangxiaoming.music.data.remote.http.JsonUtil
import vip.wangxiaoming.music.data.remote.http.PlatformHttp
import vip.wangxiaoming.music.data.remote.model.ClientResult
import vip.wangxiaoming.music.data.remote.model.Platform
import vip.wangxiaoming.music.data.remote.model.Playlist
import vip.wangxiaoming.music.data.remote.model.Song

/**
 * 酷狗音乐元数据客户端。
 *
 * 关键实测结论：
 *  1) mobilecdn.kugou.com / mobilecdnbj.kugou.com 的 **HTTPS 证书 CN 与域名不匹配**
 *     （RemoteCertificateNameMismatch），实际握手失败，因此必须走 http；
 *     已在 res/xml/network_security_config.xml 里对这两个域名单独放行明文。
 *  2) `/api/v5/special/song` 是**免签**端点（specialid 直接取），实测 34~117 首正常返回。
 *     酷狗带签名的是 global_collection（gcid_*）形态，元数据阶段不需要，交给解析层。
 *  3) 歌单项 **没有 timelen 字段**（实测该字段为空/不存在），时长要用 `duration`（秒）。
 *  4) 封面在 trans_param.union_cover 里，形如
 *     "http://imge.kugou.com/stdmusic/{size}/2023...jpg"，需替换 {size}。
 *     该图床 HTTPS 可用，替换后升级成 https。
 *  5) 响应 Content-Type 是 text/html 但内容其实是 JSON。
 */
class KugouClient {

    /** 平台代码，写入 ClientResult.platform */
    private val PF = Platform.KUGOU.code

    suspend fun search(keyword: String, page: Int = 1, pageSize: Int = 30): ClientResult<List<Song>> {
        val kw = keyword.trim()
        if (kw.isEmpty()) return ClientResult(PF, "search", true, 200, emptyList<Song>())

        val url = "$CDN/api/v3/search/song?keyword=" + enc(kw) + "&page=$page&pagesize=$pageSize"
        val resp = PlatformHttp.get(url, userAgent = PlatformHttp.UA_DESKTOP)
        if (!resp.isSuccess) return fail("search", resp)

        val root = JsonUtil.parse(resp.body) ?: return failParse("search", resp)
        val arr = JsonUtil.arrAt(root, "data", "info") ?: return ok("search", resp, emptyList())

        val songs = ArrayList<Song>(arr.size)
        for (el in arr) {
            val o = JsonUtil.obj(el) ?: continue
            val hash = JsonUtil.str(o, "hash") ?: continue
            songs.add(songFromInfo(o, hash))
        }
        return ok("search", resp, songs)
    }

    /** 免签歌单曲目。pagesize 上限实测可到 400（原版用 400） */
    suspend fun specialSongs(specialId: String, page: Int = 1, pageSize: Int = 300): ClientResult<List<Song>> {
        val id = specialId.trim().removePrefix("special_")
        if (id.isEmpty()) return ClientResult(PF, "specialSongs", true, 200, emptyList<Song>())

        val url = "$CDN_BJ/api/v5/special/song?specialid=$id&page=$page&pagesize=$pageSize" +
            "&version=9108&plat=0"
        val resp = PlatformHttp.get(url, userAgent = PlatformHttp.UA_MOBILE)
        if (!resp.isSuccess) return fail("specialSongs", resp)

        val root = JsonUtil.parse(resp.body) ?: return failParse("specialSongs", resp)
        val arr = JsonUtil.arrAt(root, "data", "info") ?: return ok("specialSongs", resp, emptyList())

        val songs = ArrayList<Song>(arr.size)
        for (el in arr) {
            val o = JsonUtil.obj(el) ?: continue
            val hash = JsonUtil.str(o, "hash") ?: continue
            songs.add(songFromInfo(o, hash, coverFrom(o)))
        }
        return ok("specialSongs", resp, songs)
    }

    /** 歌单元数据 */
    suspend fun specialInfo(specialId: String): ClientResult<Playlist?> {
        val id = specialId.trim().removePrefix("special_")
        if (id.isEmpty()) return ClientResult(PF, "specialInfo", false, 400, null, 0, "empty specialid")

        val resp = PlatformHttp.get("$CDN/api/v3/special/info?specialid=$id", userAgent = PlatformHttp.UA_DESKTOP)
        if (!resp.isSuccess) return failNullable("specialInfo", resp)

        val root = JsonUtil.parse(resp.body) ?: return failParseNullable("specialInfo", resp)
        val d = JsonUtil.objAt(root, "data") ?: return failParseNullable("specialInfo", resp)

        return ClientResult(
            platform = PF,
            operation = "specialInfo",
            ok = true,
            status = resp.status,
            rawLength = resp.length,
            data = Playlist(
                id = JsonUtil.str(d, "specialid") ?: id,
                name = JsonUtil.str(d, "specialname") ?: "酷狗歌单",
                coverUrl = JsonUtil.withSize(JsonUtil.str(d, "imgurl")),
                description = JsonUtil.str(d, "intro") ?: "",
                playCount = JsonUtil.long(d, "playcount") ?: 0L,
                trackCount = JsonUtil.int(d, "songcount") ?: 0,
                source = Platform.KUGOU.code,
            ),
        )
    }

    /** 歌单搜索（v3/search/special），聚合层"搜歌单"用 */
    suspend fun searchPlaylists(keyword: String, page: Int = 1, pageSize: Int = 20): ClientResult<List<Playlist>> {
        val kw = keyword.trim()
        if (kw.isEmpty()) return ClientResult(PF, "searchPlaylists", true, 200, emptyList<Playlist>())

        val resp = PlatformHttp.get(
            "$CDN/api/v3/search/special?keyword=" + enc(kw) + "&page=$page&pagesize=$pageSize",
            userAgent = PlatformHttp.UA_DESKTOP,
        )
        if (!resp.isSuccess) return fail("searchPlaylists", resp)

        val root = JsonUtil.parse(resp.body) ?: return failParse("searchPlaylists", resp)
        val arr = JsonUtil.arrAt(root, "data", "info") ?: return ok("searchPlaylists", resp, emptyList())

        val lists = arr.mapNotNull { el ->
            val o = JsonUtil.obj(el) ?: return@mapNotNull null
            val id = JsonUtil.str(o, "specialid") ?: return@mapNotNull null
            Playlist(
                id = id,
                name = JsonUtil.str(o, "specialname") ?: "酷狗歌单",
                coverUrl = JsonUtil.withSize(JsonUtil.str(o, "imgurl")),
                description = JsonUtil.str(o, "intro") ?: "",
                playCount = JsonUtil.long(o, "playcount") ?: 0L,
                trackCount = JsonUtil.int(o, "songcount") ?: 0,
                source = Platform.KUGOU.code,
            )
        }
        return ok("searchPlaylists", resp, lists)
    }

    // ---------- 内部 ----------

    /**
     * info 项 → Song。
     * 歌名优先 filename 拆 "歌手 - 歌名"（filename 比 songname 更完整，原版同款做法），
     * 拆不出来再回落到 songname / singername。
     */
    private fun songFromInfo(o: JsonObject, hash: String, coverOverride: String? = null): Song {
        val filename = JsonUtil.str(o, "filename")
        var artist = JsonUtil.str(o, "singername")
        var title = JsonUtil.str(o, "songname")

        if (filename != null && filename.contains(" - ")) {
            val parts = filename.split(" - ", limit = 2)
            if (artist.isNullOrBlank()) artist = parts[0]
            if (title.isNullOrBlank()) title = parts[1]
        }
        if (title.isNullOrBlank()) title = filename ?: "未知歌曲"

        val secs = JsonUtil.int(o, "duration")
            ?: JsonUtil.int(o, "timelen")?.let { if (it > 1000) it / 1000 else it }
            ?: 0

        return Song(
            id = hash,
            name = NeteaseClient.cleanTitle(title),
            artist = (artist ?: "未知歌手").replace("&nbsp;", " ").trim(),
            album = JsonUtil.str(o, "album_name") ?: "酷狗专辑",
            cover = coverOverride ?: coverFrom(o),
            source = Platform.KUGOU.code,
            duration = JsonUtil.mmss(secs),
            durationSeconds = secs,
            albumId = JsonUtil.str(o, "album_id").orEmpty(),
        )
    }

    private fun coverFrom(o: JsonObject): String {
        val tp = JsonUtil.obj(o.get("trans_param"))
        return JsonUtil.withSize(JsonUtil.str(tp, "union_cover"))
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
        /** 必须 http：HTTPS 证书 CN 不匹配，见类注释 */
        private const val CDN = "http://mobilecdn.kugou.com"
        private const val CDN_BJ = "http://mobilecdnbj.kugou.com"
    }
}