package vip.wangxiaoming.music.data.local

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import vip.wangxiaoming.music.data.local.entity.DownloadStatus
import vip.wangxiaoming.music.data.local.entity.DownloadTask

class DownloadTaskDaoTest : DbTestBase() {

    private fun task(songId: String, time: Long = 1000L, status: Int = DownloadStatus.QUEUED) =
        DownloadTask(
            songId = songId, name = "n-$songId", artist = "a",
            status = status, createTime = time,
        )

    @Test
    fun insert_and_queryBySongId() = runTest {
        val dao = db.downloadTaskDao()
        dao.insertIgnore(task("s1"))
        assertEquals(1, dao.count())
        assertEquals("n-s1", dao.queryBySongId("s1")!!.name)
    }

    @Test
    fun songId_unique_duplicate_is_ignored() = runTest {
        val dao = db.downloadTaskDao()
        dao.insertIgnore(task("s1", 100))
        dao.insertIgnore(task("s1", 999))
        assertEquals(1, dao.count())
        assertEquals(100L, dao.queryBySongId("s1")!!.createTime)
    }

    @Test
    fun updateStatus_sets_status_and_error() = runTest {
        val dao = db.downloadTaskDao()
        dao.insertIgnore(task("s1"))
        assertEquals(1, dao.updateStatus("s1", DownloadStatus.RUNNING, null, null))
        val running = dao.queryBySongId("s1")!!
        assertEquals(DownloadStatus.RUNNING, running.status)

        dao.updateStatus("s1", DownloadStatus.FAILED, "timeout", null)
        val failed = dao.queryBySongId("s1")!!
        assertEquals(DownloadStatus.FAILED, failed.status)
        assertEquals("timeout", failed.errorMessage)
    }

    @Test
    fun updateStatus_with_path_keeps_existing_when_null() = runTest {
        val dao = db.downloadTaskDao()
        dao.insertIgnore(task("s1"))
        dao.updateStatus("s1", DownloadStatus.SUCCESS, null, "/sdcard/a.mp3")
        dao.updateStatus("s1", DownloadStatus.SUCCESS, null, null)
        assertEquals("/sdcard/a.mp3", dao.queryBySongId("s1")!!.localFilePath)
    }

    @Test
    fun queryByStatus_filters_and_orders_by_create_time_asc() = runTest {
        val dao = db.downloadTaskDao()
        dao.insertIgnore(task("q1", 300, DownloadStatus.QUEUED))
        dao.insertIgnore(task("q2", 100, DownloadStatus.QUEUED))
        dao.insertIgnore(task("r1", 200, DownloadStatus.RUNNING))
        assertEquals(listOf("q2", "q1"), dao.queryByStatus(DownloadStatus.QUEUED).map { it.songId })
        assertEquals(listOf("r1"), dao.queryByStatus(DownloadStatus.RUNNING).map { it.songId })
    }

    @Test
    fun observeAll_orders_by_create_time_desc() = runTest {
        val dao = db.downloadTaskDao()
        dao.insertIgnore(task("old", 100))
        dao.insertIgnore(task("new", 300))
        assertEquals(listOf("new", "old"), dao.observeAll().first().map { it.songId })
    }

    @Test
    fun clearFinished_removes_only_success_and_failed() = runTest {
        val dao = db.downloadTaskDao()
        dao.insertIgnore(task("q", 100, DownloadStatus.QUEUED))
        dao.insertIgnore(task("r", 200, DownloadStatus.RUNNING))
        dao.insertIgnore(task("ok", 300, DownloadStatus.SUCCESS))
        dao.insertIgnore(task("bad", 400, DownloadStatus.FAILED))
        assertEquals(2, dao.clearFinished())
        assertEquals(listOf("q", "r"), dao.queryAll().map { it.songId }.sorted())
    }

    @Test
    fun deleteBySongId_removes_row() = runTest {
        val dao = db.downloadTaskDao()
        dao.insertIgnore(task("s1"))
        assertEquals(1, dao.deleteBySongId("s1"))
        assertNull(dao.queryBySongId("s1"))
        assertEquals(0, dao.count())
    }

    @Test
    fun upsert_replaces_row_by_unique_song_id() = runTest {
        val dao = db.downloadTaskDao()
        dao.insertIgnore(task("s1", 100))
        dao.upsert(task("s1", 500, DownloadStatus.SUCCESS))
        assertEquals(1, dao.count())
        assertEquals(500L, dao.queryBySongId("s1")!!.createTime)
        assertEquals(DownloadStatus.SUCCESS, dao.queryBySongId("s1")!!.status)
    }
}