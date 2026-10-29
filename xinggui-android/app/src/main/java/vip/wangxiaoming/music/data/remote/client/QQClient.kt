package vip.wangxiaoming.music.data.remote.client

import java.net.URLEncoder
import vip.wangxiaoming.music.data.remote.http.JsonUtil
import vip.wangxiaoming.music.data.remote.http.PlatformHttp
import vip.wangxiaoming.music.data.remote.model.ClientResult
import vip.wangxiaoming.music.data.remote.model.Platform
import vip.wangxiaoming.music.data.remote.model.Song

/**
 * QQ 音乐元数据客户端。
 *
 * 实测要点：
 *  - `client_search_cp` 用 format=json 返回标准 JSON；不传 format 会返回 JSONP/JS 回调包裹，
 *    所以这里强制带 format=json。
 *  - 歌曲主键用 `songmid`（字符串，如 003Qui1q2u1Zho），**不是**数字 songid；
 *    后续取播放地址也依赖 songmid。
 *  - 封面来自 albummid：https://y.gtimg.cn/music/photo_new/T002R300x300M000{albummid}.jpg
 *  - 时长字段是 `interval`（秒），不是毫秒。
 *  - `smartbox_new` 是联想/聚合接口（singer/album/song/mv 四类 itemlist），
 *    给搜索建议用；它返回的条目没有时长，时长留空让上层补。
 */
class QQClient {

    /** 平台代码，写入 ClientResult.platform */
    private val PF = Platform.QQ.code

    suspend fun search(keyword: String, page: Int = 1, pageSize: Int = 30): ClientResult<List<Song>> {
        val kw = keyword.trim()
        if (kw.isEmpty()) return ClientResult(PF, "search", true, 200, emptyList<Song>())

        val url = "$SEARCH?p=$page&n=$pageSize&w=" + enc(kw) + "&format=json"
        val resp = PlatformHttp.get(url, userAgent = PlatformHttp.UA_DESKTOP, referer = "https://y.qq.com/")
        if (!resp.isSuccess) return fail("search", resp)

        val root = JsonUtil.parse(resp.body) ?: return failParse("search", resp)
        val arr = JsonUtil.arrAt(root, "data", "song", "list") ?: return ok("search", resp, emptyList())

        val songs = ArrayList<Song>(arr.size)
        for (el in arr) {
            val o = JsonUtil.obj(el) ?: continue
            val mid = JsonUtil.str(o, "songmid") ?: continue
            val secs = JsonUtil.int(o, "interval") ?: 0
            val albumMid = JsonUtil.str(o, "albummid").orEmpty()
            songs.add(
                Song(
                    id = mid,
                    name = NeteaseClient.cleanTitle(JsonUtil.str(o, "songname") ?: "未知歌曲"),
                    artist = JsonUtil.strList(o, "singer", "name").joinToString(" / ")
                        .ifEmpty { "未知歌手" },
                    album = JsonUtil.str(o, "albumname") ?: "QQ专辑",
                    cover = if (albumMid.isEmpty()) "" else
                        "https://y.gtimg.cn/music/photo_new/T002R300x300M000$albumMid.jpg",
                    source = Platform.QQ.code,
                    duration = JsonUtil.mmss(secs),
                    durationSeconds = secs,
                    albumId = albumMid,
                ),
            )
        }
        return ok("search", resp, songs)
    }

    /**
     * 联想搜索。返回四类：singer / album / song / mv。
     * 这里只把 song 类转成 Song，并额外给出命中的歌手名，便于上层做"直达歌手页"。
     */
    suspend fun smartbox(keyword: String): ClientResult<SmartboxResult> {
        val kw = keyword.trim()
        if (kw.isEmpty()) return ClientResult(PF, "smartbox", true, 200, SmartboxResult.EMPTY)

        val resp = PlatformHttp.get("$SMARTBOX?key=" + enc(kw), userAgent = PlatformHttp.UA_DESKTOP,
            referer = "https://y.qq.com/")
        if (!resp.isSuccess) return ClientResult(PF, "smartbox", false, resp.status, SmartboxResult.EMPTY,
            resp.length, resp.error ?: "http ${resp.status}")

        val root = JsonUtil.parse(resp.body) ?: return ClientResult(PF, "smartbox", false, resp.status,
            SmartboxResult.EMPTY, resp.length, "non-json: " + resp.body.take(120))

        fun items(section: String): List<kotlinx.serialization.json.JsonObject> =
            JsonUtil.arrAt(root, "data", section, "itemlist")
                ?.mapNotNull { JsonUtil.obj(it) } ?: emptyList()

        val songs = items("song").mapNotNull { o ->
            val mid = JsonUtil.str(o, "mid") ?: return@mapNotNull null
            Song(
                id = mid,
                name = NeteaseClient.cleanTitle(JsonUtil.str(o, "name") ?: "未知歌曲"),
                artist = JsonUtil.str(o, "singer") ?: "未知歌手",
                album = "",
                cover = "",
                source = Platform.QQ.code,
                duration = "03:30",
                durationSeconds = 0,
            )
        }
        val singers = items("singer").mapNotNull { o ->
            val mid = JsonUtil.str(o, "mid") ?: return@mapNotNull null
            mid to (JsonUtil.str(o, "name") ?: "")
        }
        val albums = items("album").mapNotNull { o ->
            val mid = JsonUtil.str(o, "mid") ?: return@mapNotNull null
            mid to (JsonUtil.str(o, "name") ?: "")
        }

        val data = SmartboxResult(
            songs = songs,
            singers = singers,
            albums = albums,
            mvCount = JsonUtil.arrAt(root, "data", "mv", "itemlist")?.size ?: 0,
        )
        return ClientResult(PF, "smartbox", true, resp.status, data, resp.length)
    }

    data class SmartboxResult(
        val songs: List<Song> = emptyList(),
        val singers: List<Pair<String, String>> = emptyList(),
        val albums: List<Pair<String, String>> = emptyList(),
        val mvCount: Int = 0,
    ) {
        companion object { val EMPTY = SmartboxResult() }
    }

    private fun <T> ok(tag: String, resp: PlatformHttp.RawResponse, data: T) =
        ClientResult(platform = PF, operation = tag, ok = true, status = resp.status, data = data, rawLength = resp.length)

    private fun <T> fail(tag: String, resp: PlatformHttp.RawResponse) =
        ClientResult(platform = PF, operation = tag, ok = false, status = resp.status, data = (emptyList<Song>() as T), rawLength = resp.length,
            error = resp.error ?: "http ${resp.status}")

    private fun <T> failParse(tag: String, resp: PlatformHttp.RawResponse) =
        ClientResult(platform = PF, operation = tag, ok = false, status = resp.status, data = (emptyList<Song>() as T), rawLength = resp.length,
            error = "non-json: " + resp.body.take(120))

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    companion object {
        private const val SEARCH = "https://c.y.qq.com/soso/fcgi-bin/client_search_cp"
        private const val SMARTBOX = "https://c.y.qq.com/splcloud/fcgi-bin/smartbox_new.fcg"
    }
}