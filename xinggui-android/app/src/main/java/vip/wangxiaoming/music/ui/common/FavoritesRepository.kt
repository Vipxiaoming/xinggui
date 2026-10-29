package vip.wangxiaoming.music.ui.common

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import vip.wangxiaoming.music.data.local.entity.Favorite
import vip.wangxiaoming.music.data.remote.model.Song
import vip.wangxiaoming.music.di.AppContainer

/**
 * 收藏仓库（P6 遗留功能补齐）。
 *
 * 为什么放在 ui/common：它只有 UI 侧消费者（播放器心形按钮、我的页计数、后续收藏列表），
 * 不属于需要跨模块复用的 domain 层；放在这里也避免动 data/repository 的既有结构。
 *
 * 唯一 id 口径与播放层一致：`source:id`（见 PlayLauncher.mediaIdOf），
 * 这样从 MediaItem.mediaId 反查收藏不需要再拼一次。
 *
 * 所有写操作都是 suspend，调用方必须在 lifecycleScope / IO 上调用；
 * Room 的 Flow 会随表变更自动重发，UI 不需要手动刷新。
 */
class FavoritesRepository(
    private val container: AppContainer = AppContainer.get(),
) {

    private val dao get() = container.database.favoriteDao()

    /** 全部收藏，按 added_at 倒序（最近收藏在前）。 */
    fun observeAll(): Flow<List<Favorite>> = dao.observeAll()

    /** 收藏总数，供「我的」页计数使用。 */
    fun observeCount(): Flow<Int> = dao.observeCount()

    fun observeCountText(): Flow<String> = observeCount().map { "$it 首歌曲" }

    /** 某首歌是否已收藏，按 mediaId（source:id）查询。 */
    fun observeIsFavorite(mediaId: String): Flow<Boolean> = dao.observeExists(mediaId)

    suspend fun isFavorite(mediaId: String): Boolean = dao.exists(mediaId)

    /**
     * 切换收藏状态，返回切换后的结果（true = 已收藏）。
     *
     * 用「先查后写」而不是 REPLACE 盲写：盲写无法区分「取消收藏」，
     * 且 REPLACE 会重置主键，破坏 added_at 的稳定性。
     */
    suspend fun toggle(song: Song): Boolean {
        val mediaId = mediaIdOf(song)
        if (dao.exists(mediaId)) {
            dao.deleteBySongId(mediaId)
            return false
        }
        dao.upsert(toFavorite(song, mediaId))
        return true
    }

    /** 显式设置收藏状态（例如列表批量操作或恢复同步）。 */
    suspend fun setFavorite(song: Song, favorite: Boolean) {
        val mediaId = mediaIdOf(song)
        if (favorite) {
            if (!dao.exists(mediaId)) dao.upsert(toFavorite(song, mediaId))
        } else {
            dao.deleteBySongId(mediaId)
        }
    }

    suspend fun remove(mediaId: String) {
        dao.deleteBySongId(mediaId)
    }

    private fun toFavorite(song: Song, mediaId: String) = Favorite(
        songId = mediaId,
        name = song.name.ifBlank { "未知曲目" },
        artist = song.artist.ifBlank { "未知歌手" },
        album = song.album.takeIf { it.isNotBlank() },
        cover = song.cover.takeIf { it.isNotBlank() },
        source = song.source,
        duration = song.duration,
        addedAt = System.currentTimeMillis(),
    )

    companion object {
        /** 与 PlayLauncher / MediaItem.mediaId 完全同源，避免两处拼接口径漂移。 */
        fun mediaIdOf(song: Song): String = song.source + ":" + song.id

        /** 从 MediaController 的 mediaId 直接取（它已经是 source:id）。 */
        fun mediaIdOf(rawMediaId: String): String = rawMediaId
    }
}