package vip.wangxiaoming.music.data.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import vip.wangxiaoming.music.data.local.dao.DownloadTaskDao
import vip.wangxiaoming.music.data.local.entity.DownloadStatus
import vip.wangxiaoming.music.data.local.entity.DownloadTask

/**
 * 下载任务队列。这里只管「任务状态」，真正落盘由 P5 的下载器实现，
 * 下载器通过 updateStatus / markSuccess / markFailed 回写进度。
 */
class DownloadRepository(private val dao: DownloadTaskDao) {

    fun observeAll(): Flow<List<DownloadTask>> = dao.observeAll()

    fun observeByStatus(status: Int): Flow<List<DownloadTask>> = dao.observeByStatus(status)

    fun observeRunning(): Flow<List<DownloadTask>> = dao.observeByStatus(DownloadStatus.RUNNING)

    fun observeFinished(): Flow<List<DownloadTask>> =
        dao.observeAll().map { list ->
            list.filter { it.status == DownloadStatus.SUCCESS || it.status == DownloadStatus.FAILED }
        }

    suspend fun all(): List<DownloadTask> = dao.queryAll()

    suspend fun byStatus(status: Int): List<DownloadTask> = dao.queryByStatus(status)

    suspend fun find(songId: String): DownloadTask? = dao.queryBySongId(songId)

    suspend fun count(): Int = dao.count()

    /** 入队。同一首歌已在队列里就复用原行（IGNORE），返回已有任务。 */
    suspend fun enqueue(task: DownloadTask): DownloadTask {
        dao.insertIgnore(task.copy(status = DownloadStatus.QUEUED))
        return dao.queryBySongId(task.songId) ?: task
    }

    suspend fun markRunning(songId: String) =
        dao.updateStatus(songId, DownloadStatus.RUNNING, null, null)

    suspend fun markSuccess(songId: String, localFilePath: String) =
        dao.updateStatus(songId, DownloadStatus.SUCCESS, null, localFilePath)

    suspend fun markFailed(songId: String, errorMessage: String?) =
        dao.updateStatus(songId, DownloadStatus.FAILED, errorMessage, null)

    suspend fun requeue(songId: String) =
        dao.updateStatus(songId, DownloadStatus.QUEUED, null, null)

    suspend fun remove(songId: String): Boolean = dao.deleteBySongId(songId) > 0

    suspend fun clearFinished() = dao.clearFinished()

    suspend fun clearAll() = dao.clear()
}