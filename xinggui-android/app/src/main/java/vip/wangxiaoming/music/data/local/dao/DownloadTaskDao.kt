package vip.wangxiaoming.music.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import vip.wangxiaoming.music.data.local.entity.DownloadTask

@Dao
abstract class DownloadTaskDao {

    @Query("SELECT * FROM download_tasks ORDER BY create_time DESC")
    abstract fun observeAll(): Flow<List<DownloadTask>>

    @Query("SELECT * FROM download_tasks WHERE status = :status ORDER BY create_time ASC")
    abstract fun observeByStatus(status: Int): Flow<List<DownloadTask>>

    @Query("SELECT * FROM download_tasks ORDER BY create_time DESC")
    abstract suspend fun queryAll(): List<DownloadTask>

    @Query("SELECT * FROM download_tasks WHERE status = :status ORDER BY create_time ASC")
    abstract suspend fun queryByStatus(status: Int): List<DownloadTask>

    @Query("SELECT * FROM download_tasks WHERE song_id = :songId LIMIT 1")
    abstract suspend fun queryBySongId(songId: String): DownloadTask?

    @Query("SELECT COUNT(*) FROM download_tasks")
    abstract suspend fun count(): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insertIgnore(item: DownloadTask): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun upsert(item: DownloadTask): Long

    @Query("UPDATE download_tasks SET status = :status, error_message = :errorMessage, local_file_path = COALESCE(:localFilePath, local_file_path) WHERE song_id = :songId")
    abstract suspend fun updateStatus(songId: String, status: Int, errorMessage: String?, localFilePath: String?): Int

    @Query("UPDATE download_tasks SET status = :status, error_message = :errorMessage, local_file_path = :localFilePath, create_time = :createTime WHERE id = :id")
    abstract suspend fun updateById(id: Long, status: Int, errorMessage: String?, localFilePath: String?, createTime: Long): Int

    @Query("DELETE FROM download_tasks WHERE song_id = :songId")
    abstract suspend fun deleteBySongId(songId: String): Int

    @Query("DELETE FROM download_tasks WHERE id = :id")
    abstract suspend fun deleteById(id: Long): Int

    @Query("DELETE FROM download_tasks WHERE status IN (2, 3)")
    abstract suspend fun clearFinished(): Int

    @Query("DELETE FROM download_tasks")
    abstract suspend fun clear(): Int
}