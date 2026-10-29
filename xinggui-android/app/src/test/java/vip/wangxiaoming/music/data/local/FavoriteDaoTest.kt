package vip.wangxiaoming.music.data.local

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import vip.wangxiaoming.music.data.local.entity.Favorite

/**
 * 收藏表行为验证（v2 新表）。
 *
 * 重点覆盖「重复收藏不产生第二行」这条约束：songId 上的 UNIQUE 索引
 * 配合 OnConflictStrategy.REPLACE 是 toggle 逻辑正确的前提，
 * 一旦索引丢失，列表会出现同一首歌两条，用户表现为「收藏了两个」。
 */
class FavoriteDaoTest : DbTestBase() {

    private fun fav(mediaId: String, time: Long = 1L) = Favorite(
        songId = mediaId,
        name = "晴天",
        artist = "周杰伦",
        album = "叶惠美",
        cover = null,
        source = mediaId.substringBefore(':'),
        duration = "04:29",
        addedAt = time,
    )

    @Test
    fun upsert_same_song_keeps_single_row() = runTest {
        val dao = db.favoriteDao()
        dao.upsert(fav("wy:1901371647", 100))
        dao.upsert(fav("wy:1901371647", 200))
        assertEquals(1, dao.count())
        assertEquals(200L, dao.queryBySongId("wy:1901371647")!!.addedAt)
    }

    @Test
    fun exists_and_delete_reflect_state() = runTest {
        val dao = db.favoriteDao()
        assertFalse(dao.exists("kw:474680496"))
        dao.upsert(fav("kw:474680496"))
        assertTrue(dao.exists("kw:474680496"))
        assertEquals(1, dao.deleteBySongId("kw:474680496"))
        assertFalse(dao.exists("kw:474680496"))
    }

    @Test
    fun observeAll_orders_by_added_at_desc_and_count_flow_updates() = runTest {
        val dao = db.favoriteDao()
        dao.upsert(fav("wy:a", 100))
        dao.upsert(fav("wy:b", 300))
        dao.upsert(fav("wy:c", 200))
        assertEquals(listOf("wy:b", "wy:c", "wy:a"), dao.observeAll().first().map { it.songId })
        assertEquals(3, dao.observeCount().first())
    }
}