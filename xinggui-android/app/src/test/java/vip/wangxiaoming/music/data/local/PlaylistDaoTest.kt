package vip.wangxiaoming.music.data.local

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import vip.wangxiaoming.music.data.local.entity.CustomPlaylist
import vip.wangxiaoming.music.data.local.entity.PlaylistSong

class PlaylistDaoTest : DbTestBase() {

    private fun playlist(name: String, time: Long = 1000L) =
        CustomPlaylist(name = name, createTime = time)

    private fun song(playlistId: Long, songId: String, added: Long = 1000L) = PlaylistSong(
        playlistId = playlistId, songId = songId, name = "n-$songId",
        artist = "a", source = "kw", addedTime = added,
    )

    @Test
    fun insert_and_queryById() = runTest {
        val dao = db.customPlaylistDao()
        val id = dao.insert(playlist("我的歌单"))
        val found = dao.queryById(id)
        assertNotNull(found)
        assertEquals("我的歌单", found!!.name)
        assertEquals(1, dao.count())
    }

    @Test
    fun observeAll_orders_by_create_time_desc() = runTest {
        val dao = db.customPlaylistDao()
        dao.insert(playlist("old", 100))
        dao.insert(playlist("new", 300))
        dao.insert(playlist("mid", 200))
        assertEquals(listOf("new", "mid", "old"), dao.observeAll().first().map { it.name })
    }

    @Test
    fun rename_and_updateRemoteCount() = runTest {
        val dao = db.customPlaylistDao()
        val id = dao.insert(playlist("before"))
        assertEquals(1, dao.rename(id, "after"))
        assertEquals(1, dao.updateRemoteCount(id, 42))
        val found = dao.queryById(id)!!
        assertEquals("after", found.name)
        assertEquals(42, found.remoteCount)
    }

    @Test
    fun addSongs_and_queryByPlaylistId() = runTest {
        val plDao = db.customPlaylistDao()
        val songDao = db.playlistSongDao()
        val id = plDao.insert(playlist("p"))
        songDao.insertAll(listOf(song(id, "s1", 10), song(id, "s2", 20), song(id, "s3", 30)))
        assertEquals(3, songDao.countByPlaylistId(id))
        assertEquals(listOf("s1", "s2", "s3"), songDao.queryByPlaylistId(id).map { it.songId })
    }

    @Test
    fun duplicate_song_in_same_playlist_is_ignored() = runTest {
        val plDao = db.customPlaylistDao()
        val songDao = db.playlistSongDao()
        val id = plDao.insert(playlist("p"))
        songDao.insert(song(id, "s1"))
        val second = songDao.insert(song(id, "s1"))
        assertEquals(-1L, second)
        assertEquals(1, songDao.countByPlaylistId(id))
    }

    @Test
    fun same_song_in_different_playlists_is_allowed() = runTest {
        val plDao = db.customPlaylistDao()
        val songDao = db.playlistSongDao()
        val a = plDao.insert(playlist("a"))
        val b = plDao.insert(playlist("b"))
        songDao.insert(song(a, "s1"))
        songDao.insert(song(b, "s1"))
        assertEquals(1, songDao.countByPlaylistId(a))
        assertEquals(1, songDao.countByPlaylistId(b))
    }

    @Test
    fun deleting_playlist_cascades_to_songs() = runTest {
        val plDao = db.customPlaylistDao()
        val songDao = db.playlistSongDao()
        val keep = plDao.insert(playlist("keep"))
        val drop = plDao.insert(playlist("drop"))
        songDao.insert(song(keep, "k1"))
        songDao.insert(song(drop, "d1"))
        songDao.insert(song(drop, "d2"))
        assertEquals(2, songDao.countByPlaylistId(drop))

        assertEquals(1, plDao.deleteById(drop))
        assertEquals(0, songDao.countByPlaylistId(drop))
        assertEquals(1, songDao.countByPlaylistId(keep))
    }

    @Test
    fun remove_single_song() = runTest {
        val plDao = db.customPlaylistDao()
        val songDao = db.playlistSongDao()
        val id = plDao.insert(playlist("p"))
        songDao.insert(song(id, "s1"))
        songDao.insert(song(id, "s2"))
        assertEquals(1, songDao.delete(id, "s1"))
        assertEquals(listOf("s2"), songDao.queryByPlaylistId(id).map { it.songId })
    }

    @Test
    fun clearSongs_keeps_playlist() = runTest {
        val plDao = db.customPlaylistDao()
        val songDao = db.playlistSongDao()
        val id = plDao.insert(playlist("p"))
        songDao.insert(song(id, "s1"))
        assertEquals(1, songDao.deleteByPlaylistId(id))
        assertEquals(0, songDao.countByPlaylistId(id))
        assertNotNull(plDao.queryById(id))
    }

    @Test
    fun observeCountByPlaylistId_emits() = runTest {
        val plDao = db.customPlaylistDao()
        val songDao = db.playlistSongDao()
        val id = plDao.insert(playlist("p"))
        songDao.insert(song(id, "s1"))
        assertEquals(1, songDao.observeCountByPlaylistId(id).first())
    }
}