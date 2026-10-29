package vip.wangxiaoming.music.data.repository

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import vip.wangxiaoming.music.data.local.DbTestBase
import vip.wangxiaoming.music.data.local.entity.PlaybackHistory
import vip.wangxiaoming.music.data.local.entity.PlaylistSong

class RepositoryTest : DbTestBase() {

    private fun history(id: String, time: Long) = PlaybackHistory(
        songId = id, name = "n-$id", artist = "a", source = "kw", playedTime = time,
    )

    @Test
    fun historyRepository_record_and_trim() = runTest {
        val repo = HistoryRepository(db.playbackHistoryDao())
        (1..5).forEach { repo.record(history("s$it", it * 100L)) }
        assertEquals(5, repo.count())

        repo.trimTo(2)
        assertEquals(2, repo.count())
        assertEquals(listOf("s5", "s4"), repo.recent(10).map { it.songId })
    }

    @Test
    fun historyRepository_delete_returns_false_when_missing() = runTest {
        val repo = HistoryRepository(db.playbackHistoryDao())
        assertFalse(repo.delete("nope"))
        repo.record(history("a", 100))
        assertTrue(repo.delete("a"))
        assertEquals(0, repo.count())
    }

    @Test
    fun historyRepository_observeCount_tracks_rows() = runTest {
        val repo = HistoryRepository(db.playbackHistoryDao())
        repo.record(history("a", 100))
        assertEquals(1, repo.observeCount().first())
    }

    @Test
    fun playlistRepository_create_add_delete_with_counts() = runTest {
        val repo = PlaylistRepository(db, db.customPlaylistDao(), db.playlistSongDao())
        val id = repo.create("  开车  ")
        assertEquals("开车", repo.playlist(id)!!.name)

        val added = repo.addSongs(
            listOf(
                PlaylistSong(playlistId = id, songId = "s1", name = "n1", artist = "a", source = "kw", addedTime = 1),
                PlaylistSong(playlistId = id, songId = "s2", name = "n2", artist = "a", source = "kw", addedTime = 2),
            )
        )
        assertEquals(2, added)
        assertEquals(2, repo.songCount(id))

        // 重复加入同一首不算新增
        val dup = repo.addSong(
            PlaylistSong(playlistId = id, songId = "s1", name = "n1", artist = "a", source = "kw", addedTime = 3)
        )
        assertFalse(dup)
        assertEquals(2, repo.songCount(id))

        val withCounts = repo.observeWithCounts().first()
        assertEquals(1, withCounts.size)
        assertEquals(2, withCounts.single().songCount)

        repo.delete(id)
        assertEquals(0, repo.playlists().size)
        assertEquals(0, repo.songCount(id))
    }

    @Test
    fun playlistRepository_blank_name_falls_back() = runTest {
        val repo = PlaylistRepository(db, db.customPlaylistDao(), db.playlistSongDao())
        val id = repo.create("   ")
        assertEquals("未命名歌单", repo.playlist(id)!!.name)
    }

    @Test
    fun downloadRepository_lifecycle() = runTest {
        val repo = DownloadRepository(db.downloadTaskDao())
        val t = vip.wangxiaoming.music.data.local.entity.DownloadTask(
            songId = "s1", name = "n", artist = "a", status = 0, createTime = 100,
        )
        val queued = repo.enqueue(t)
        assertEquals("s1", queued.songId)
        assertEquals(1, repo.count())

        repo.markRunning("s1")
        assertEquals(1, repo.observeRunning().first().size)

        repo.markFailed("s1", "io")
        assertEquals("io", repo.find("s1")!!.errorMessage)

        repo.markSuccess("s1", "/sdcard/m.mp3")
        assertEquals("/sdcard/m.mp3", repo.find("s1")!!.localFilePath)
        assertEquals(1, repo.observeFinished().first().size)

        assertEquals(1, repo.clearFinished())
        assertEquals(0, repo.count())
    }
}