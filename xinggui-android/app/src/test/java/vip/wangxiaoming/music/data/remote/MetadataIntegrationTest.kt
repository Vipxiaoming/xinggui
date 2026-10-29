package vip.wangxiaoming.music.data.remote

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import vip.wangxiaoming.music.data.remote.client.KugouClient
import vip.wangxiaoming.music.data.remote.client.KuwoClient
import vip.wangxiaoming.music.data.remote.client.NeteaseClient
import vip.wangxiaoming.music.data.remote.client.QQClient
import vip.wangxiaoming.music.data.remote.model.Platform
import java.io.File

/**
 * 真实联网集成测试（不是 mock）。
 *
 * 每个用例都发起真实 HTTP 请求，并把「平台 / HTTP 状态 / 返回条数 / 首条歌名」
 * 追加写入 build/metadata-integration.log，便于报告直接引用实测数据。
 *
 * 断言口径：只断言"能拿到结果且字段非空"，不断言具体歌名（曲库会变）。
 * 失败时把 status + 响应片段写进断言消息，避免"只看到 false"。
 */
class MetadataIntegrationTest {

    private val logFile: File by lazy {
        // 路径相对工程根的 app 模块目录；父目录不存在时先建
        File("build").mkdirs()
        File("build/metadata-integration.log")
    }

    /** JUnit 每个用例都会新建实例，所以这里必须 append，不能 writeText("") */
    @Synchronized
    private fun record(line: String) {
        println("METADATA_TEST $line")
        logFile.appendText(line + "\n")
    }

    @Test
    fun netease_search_returns_songs() = runBlocking {
        val r = NeteaseClient().search("周杰伦", 0, 5)
        record("[netease.search] ok=${r.ok} status=${r.status} count=${r.count} first=${r.data.firstOrNull()?.name} raw=${r.rawLength}")
        assertTrue("netease.search 无结果: status=${r.status} err=${r.error} len=${r.rawLength}", r.ok && r.data.isNotEmpty())
        val s = r.data.first()
        assertTrue("song.id 为空", s.id.isNotBlank())
        assertTrue("song.name 为空", s.name.isNotBlank())
        assertTrue("source 应为 wy: ${s.source}", s.source == Platform.NETEASE.code)
    }

    @Test
    fun kuwo_search_returns_songs() = runBlocking {
        val r = KuwoClient().search("周杰伦", 0, 10)
        record("[kuwo.search] ok=${r.ok} status=${r.status} count=${r.count} first=${r.data.firstOrNull()?.name} raw=${r.rawLength}")
        assertTrue("kuwo.search 无结果: status=${r.status} err=${r.error} len=${r.rawLength}", r.ok && r.data.isNotEmpty())
        val s = r.data.first()
        assertTrue("kuwo id 应为纯数字: ${s.id}", s.id.isNotBlank() && s.id.all { it.isDigit() })
        assertTrue("kuwo cover 应可访问: ${s.cover}", s.cover.startsWith("http"))
    }

    @Test
    fun kugou_search_returns_songs() = runBlocking {
        val r = KugouClient().search("周杰伦", 1, 10)
        record("[kugou.search] ok=${r.ok} status=${r.status} count=${r.count} first=${r.data.firstOrNull()?.name} raw=${r.rawLength}")
        assertTrue("kugou.search 无结果: status=${r.status} err=${r.error} len=${r.rawLength}", r.ok && r.data.isNotEmpty())
        val s = r.data.first()
        assertTrue("kugou id 应为 hash: ${s.id}", s.id.length >= 16)
        assertTrue("kugou duration 不应是兜底值: ${s.duration}", s.duration != "03:30" || s.durationSeconds > 0)
    }

    @Test
    fun qq_search_returns_songs() = runBlocking {
        val r = QQClient().search("周杰伦", 1, 10)
        record("[qq.search] ok=${r.ok} status=${r.status} count=${r.count} first=${r.data.firstOrNull()?.name} raw=${r.rawLength}")
        assertTrue("qq.search 无结果: status=${r.status} err=${r.error} len=${r.rawLength}", r.ok && r.data.isNotEmpty())
        val s = r.data.first()
        assertTrue("qq id 应为 songmid（字母数字）: ${s.id}", s.id.any { it.isLetter() } && s.id.length >= 10)
    }

    @Test
    fun kugou_special_songs_returns_page() = runBlocking {
        val r = KugouClient().specialSongs("1044932", 1, 30)
        record("[kugou.specialSongs] ok=${r.ok} status=${r.status} count=${r.count} first=${r.data.firstOrNull()?.name} raw=${r.rawLength}")
        assertTrue("kugou.specialSongs 无结果: status=${r.status} err=${r.error}", r.ok)
    }

    @Test
    fun netease_toplist_returns_ranks() = runBlocking {
        val r = NeteaseClient().toplist()
        record("[netease.toplist] ok=${r.ok} status=${r.status} count=${r.count} first=${r.data.firstOrNull()?.title} raw=${r.rawLength} err=${r.error}")
        assertTrue("toplist 无榜单", r.ok && r.data.isNotEmpty())
        assertTrue("榜单封面不可空", r.data.first().coverUrl.startsWith("http"))
    }

    @Test
    fun netease_playlist_two_phase() = runBlocking {
        val c = NeteaseClient()
        val info = c.playlistDetail("3778678")
        record("[netease.playlistDetail] ok=${info.ok} status=${info.status} name=${info.data?.name} trackCount=${info.data?.trackCount}")
        val songs = c.rankSongs("3778678", fallbackToHotSearch = false)
        record("[netease.rankSongs] ok=${songs.ok} status=${songs.status} count=${songs.count} first=${songs.data.firstOrNull()?.name}")
        assertTrue("playlistDetail 失败: status=${info.status}", info.ok && info.data != null)
        assertTrue("rankSongs 应拿到多首（>10，证明用了 trackIds 而非 tracks 前10）: ${songs.count}", songs.data.size > 10)
    }

    @Test
    fun kuwo_lyric_over_https_returns_lines() = runBlocking {
        val r = KuwoClient().lyric("228908")
        record("[kuwo.lyric] ok=${r.ok} status=${r.status} lines=${r.data.second.size} raw=${r.rawLength}")
        assertTrue("kuwo lyric 空（可能又变成 http 空壳）: status=${r.status} raw=${r.rawLength}", r.ok && r.data.second.size > 10)
        assertTrue("LRC 文本应有时间标签", r.data.first.contains("[00:"))
    }

    @Test
    fun search_all_merges_and_dedupes() = runBlocking {
        val repo = MetadataRepository()
        val r = repo.searchAll("晴天 周杰伦", limitPerPlatform = 10)
        val briefs = r.details.joinToString(" | ") { "${it.platform}:ok=${it.ok},n=${it.count},st=${it.status}" }
        record("[repo.searchAll] total=${r.data.size} details=$briefs")
        assertTrue("四家全失败: $briefs", r.anyOk)
        assertTrue("合并后为空", r.data.isNotEmpty())
        // 去重有效性：合并去重不能出现重复 dedupeKey
        val keys = r.data.map { it.dedupeKey }
        assertTrue("合并后仍有重复项: ${keys.size - keys.toSet().size}", keys.size == keys.toSet().size)
    }
}