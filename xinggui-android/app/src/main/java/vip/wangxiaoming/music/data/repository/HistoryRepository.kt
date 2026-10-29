package vip.wangxiaoming.music.data.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import vip.wangxiaoming.music.data.local.dao.PlaybackHistoryDao
import vip.wangxiaoming.music.data.local.entity.PlaybackHistory

/** 播放历史。读走 Flow（列表自动刷新），写走 suspend（幂等 upsert 语义）。 */
class HistoryRepository(private val dao: PlaybackHistoryDao) {

    fun observeRecent(limit: Int = DEFAULT_LIMIT): Flow<List<PlaybackHistory>> =
        dao.observeRecent(limit.coerceIn(1, MAX_LIMIT))

    fun observeAll(): Flow<List<PlaybackHistory>> = dao.observeAll()

    /** 历史条数，供「我的」页展示与上限裁剪使用。 */
    fun observeCount(): Flow<Int> = dao.observeAll().map { it.size }

    suspend fun recent(limit: Int = DEFAULT_LIMIT): List<PlaybackHistory> =
        dao.queryRecent(limit.coerceIn(1, MAX_LIMIT))

    suspend fun all(): List<PlaybackHistory> = dao.queryAll()

    suspend fun find(songId: String): PlaybackHistory? = dao.queryBySongId(songId)

    suspend fun count(): Int = dao.count()

    suspend fun record(item: PlaybackHistory) = dao.record(item)

    suspend fun delete(songId: String): Boolean = dao.deleteBySongId(songId) > 0

    suspend fun clear() = dao.clear()

    /**
     * 裁剪到 [keep] 条。原版历史无上限，长时间使用会无限膨胀；
     * 这里只保留最近 N 条，其余按 played_time 从旧到新删除。
     */
    suspend fun trimTo(keep: Int = DEFAULT_LIMIT) {
        val all = dao.queryAll()
        if (all.size <= keep) return
        all.drop(keep).forEach { dao.deleteBySongId(it.songId) }
    }

    companion object {
        const val DEFAULT_LIMIT = 200
        const val MAX_LIMIT = 1000
    }
}