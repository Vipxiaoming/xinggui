package vip.wangxiaoming.music.data.local

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import vip.wangxiaoming.music.data.local.entity.Favorite
import java.io.File

/**
 * v1 → v2 迁移验证。
 *
 * 为什么手写建库而不用 MigrationTestHelper：项目没引入 androidx.room:room-testing
 * （加依赖会动 build.gradle.kts，与其他并行阶段冲突）。这里用原生 SQLiteDatabase
 * 按 1.json 的 createSql **原样**建 v1 库，写入真实数据后再用 Room 打开，
 * 覆盖的正是同一件事：迁移后旧数据在、新表可用、Room 的 identityHash 校验通过。
 *
 * 关键点：SQL 必须逐字取自 1.json。列名少一个下划线、索引名拼错，
 * Room 都会在 open 时抛 IllegalStateException("Migration didn't properly handle: ...")，
 * 本用例就会失败 —— 这正是实机上最难靠肉眼发现的一类错误。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class FavoritesMigrationTest {

    private lateinit var context: Context
    private lateinit var dbFile: File
    private var db: AppDatabase? = null

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        dbFile = File(context.cacheDir, "migration-test.db")
        dbFile.delete()
        createV1Database()
    }

    @After
    fun tearDown() {
        db?.close()
        dbFile.delete()
    }

    /** 按 1.json 的 createSql 建 v1 的 4 张表与 8 条索引，并塞入一条历史 + 一个歌单。 */
    private fun createV1Database() {
        val sqlite = SQLiteDatabase.openOrCreateDatabase(dbFile, null)
        sqlite.execSQL("CREATE TABLE IF NOT EXISTS `playback_history` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `song_id` TEXT NOT NULL, `name` TEXT NOT NULL, `artist` TEXT NOT NULL, `album` TEXT, `cover` TEXT, `source` TEXT NOT NULL, `duration` TEXT NOT NULL, `play_url` TEXT, `local_file_path` TEXT, `played_time` INTEGER NOT NULL)")
        sqlite.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_playback_history_song_id` ON `playback_history` (`song_id`)")
        sqlite.execSQL("CREATE INDEX IF NOT EXISTS `index_playback_history_played_time` ON `playback_history` (`played_time`)")
        sqlite.execSQL("CREATE TABLE IF NOT EXISTS `custom_playlists` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, `cover` TEXT, `link_url` TEXT, `source` TEXT, `create_time` INTEGER NOT NULL, `remote_count` INTEGER NOT NULL)")
        sqlite.execSQL("CREATE INDEX IF NOT EXISTS `index_custom_playlists_create_time` ON `custom_playlists` (`create_time`)")
        sqlite.execSQL("CREATE TABLE IF NOT EXISTS `playlist_songs` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `playlist_id` INTEGER NOT NULL, `song_id` TEXT NOT NULL, `name` TEXT NOT NULL, `artist` TEXT NOT NULL, `album` TEXT, `cover` TEXT, `source` TEXT NOT NULL, `duration` TEXT NOT NULL, `play_url` TEXT, `local_file_path` TEXT, `added_time` INTEGER NOT NULL, FOREIGN KEY(`playlist_id`) REFERENCES `custom_playlists`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )")
        sqlite.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_playlist_songs_playlist_id_song_id` ON `playlist_songs` (`playlist_id`, `song_id`)")
        sqlite.execSQL("CREATE INDEX IF NOT EXISTS `index_playlist_songs_playlist_id` ON `playlist_songs` (`playlist_id`)")
        sqlite.execSQL("CREATE TABLE IF NOT EXISTS `download_tasks` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `song_id` TEXT NOT NULL, `name` TEXT NOT NULL, `artist` TEXT NOT NULL, `album` TEXT, `cover` TEXT, `local_file_path` TEXT, `status` INTEGER NOT NULL, `error_message` TEXT, `create_time` INTEGER NOT NULL)")
        sqlite.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_download_tasks_song_id` ON `download_tasks` (`song_id`)")
        sqlite.execSQL("CREATE INDEX IF NOT EXISTS `index_download_tasks_status` ON `download_tasks` (`status`)")

        sqlite.execSQL(
            "INSERT INTO playback_history (song_id, name, artist, source, duration, played_time) " +
                "VALUES ('wy:1901371647', '晴天', '周杰伦', 'wy', '04:29', 1700000000000)",
        )
        sqlite.execSQL(
            "INSERT INTO custom_playlists (name, create_time, remote_count) VALUES ('我的歌单', 1700000000000, 0)",
        )
        sqlite.version = 1
        sqlite.close()
    }

    private fun openRoom(): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, dbFile.absolutePath)
            .addMigrations(AppDatabase.MIGRATION_1_2)
            .allowMainThreadQueries()
            .build()

    @Test
    fun migrate1To2_keepsOldDataAndCreatesFavorites() = runTest {
        val room = openRoom()
        db = room

        // Room 能打开即说明 schema 校验通过（列/索引/identityHash 全对）。
        assertEquals(1, room.playbackHistoryDao().count())
        assertEquals("晴天", room.playbackHistoryDao().queryAll().first().name)
        assertEquals(1, room.customPlaylistDao().queryAll().size)

        // 新表可用且为空。
        assertEquals(0, room.favoriteDao().count())

        // 写入 + 读回。
        room.favoriteDao().upsert(
            Favorite(
                songId = "wy:1901371647",
                name = "晴天",
                artist = "周杰伦",
                album = "叶惠美",
                cover = null,
                source = "wy",
                duration = "04:29",
                addedAt = 1700000001000,
            ),
        )
        assertEquals(1, room.favoriteDao().count())
        val back = room.favoriteDao().queryBySongId("wy:1901371647")
        assertNotNull(back)
        assertEquals("周杰伦", back!!.artist)
    }

    @Test
    fun migrate1To2_idempotentOnReopen() = runTest {
        openRoom().close()
        db = null

        // 第二次打开不应重复建表或崩（IF NOT EXISTS 生效）。
        val again = openRoom()
        db = again
        assertEquals(1, again.playbackHistoryDao().count())
        assertEquals(0, again.favoriteDao().count())
    }
}