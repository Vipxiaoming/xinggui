package vip.wangxiaoming.music.data.local

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import vip.wangxiaoming.music.data.local.entity.PlaybackHistory

class PlaybackHistoryDaoTest : DbTestBase() {

    private fun song(id: String, time: Long, name: String = "song-$id") = PlaybackHistory(
        songId = id, name = name, artist = "artist", source = "kw", playedTime = time,
    )

    @Test
    fun insert_then_queryAll_returns_one_row() = runTest {
        val dao = db.playbackHistoryDao()
        dao.insertIgnore(song("a", 100))
        assertEquals(1, dao.count())
        assertEquals("a", dao.queryAll().single().songId)
    }

    @Test
    fun queryRecent_orders_by_played_time_desc() = runTest {
        val dao = db.playbackHistoryDao()
        dao.insertIgnore(song("old", 100))
        dao.insertIgnore(song("new", 300))
        dao.insertIgnore(song("mid", 200))
        assertEquals(listOf("new", "mid", "old"), dao.queryRecent(10).map { it.songId })
    }

    @Test
    fun queryRecent_respects_limit() = runTest {
        val dao = db.playbackHistoryDao()
        (1..5).forEach { dao.insertIgnore(song("s$it", it * 100L)) }
        assertEquals(listOf("s5", "s4"), dao.queryRecent(2).map { it.songId })
    }

    @Test
    fun songId_unique_duplicate_insert_is_ignored() = runTest {
        val dao = db.playbackHistoryDao()
        dao.insertIgnore(song("a", 100, "first"))
        dao.insertIgnore(song("a", 200, "second"))
        assertEquals(1, dao.count())
        assertEquals("first", dao.queryBySongId("a")!!.name)
    }

    @Test
    fun record_refreshes_played_time_but_keeps_id() = runTest {
        val dao = db.playbackHistoryDao()
        dao.record(song("a", 100))
        val firstId = dao.queryBySongId("a")!!.id
        dao.record(song("a", 999, "renamed"))
        val after = dao.queryBySongId("a")!!
        assertEquals(1, dao.count())
        assertEquals(firstId, after.id)
        assertEquals(999L, after.playedTime)
        assertEquals("renamed", after.name)
    }

    @Test
    fun record_keeps_existing_local_file_path() = runTest {
        val dao = db.playbackHistoryDao()
        dao.record(song("a", 100).copy(localFilePath = "/sdcard/x.mp3"))
        dao.record(song("a", 200))
        assertEquals("/sdcard/x.mp3", dao.queryBySongId("a")!!.localFilePath)
    }

    @Test
    fun deleteBySongId_removes_only_that_row() = runTest {
        val dao = db.playbackHistoryDao()
        dao.insertIgnore(song("a", 100))
        dao.insertIgnore(song("b", 200))
        assertEquals(1, dao.deleteBySongId("a"))
        assertEquals(listOf("b"), dao.queryAll().map { it.songId })
        assertNull(dao.queryBySongId("a"))
    }

    @Test
    fun clear_empties_table() = runTest {
        val dao = db.playbackHistoryDao()
        dao.insertIgnore(song("a", 100))
        dao.insertIgnore(song("b", 200))
        dao.clear()
        assertEquals(0, dao.count())
    }

    @Test
    fun observeAll_emits_after_insert() = runTest {
        val dao = db.playbackHistoryDao()
        dao.insertIgnore(song("a", 100))
        assertEquals(1, dao.observeAll().first().size)
    }
}