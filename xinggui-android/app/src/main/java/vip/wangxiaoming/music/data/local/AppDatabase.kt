package vip.wangxiaoming.music.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import vip.wangxiaoming.music.data.local.dao.CustomPlaylistDao
import vip.wangxiaoming.music.data.local.dao.DownloadTaskDao
import vip.wangxiaoming.music.data.local.dao.FavoriteDao
import vip.wangxiaoming.music.data.local.dao.PlaybackHistoryDao
import vip.wangxiaoming.music.data.local.dao.PlaylistSongDao
import vip.wangxiaoming.music.data.local.entity.CustomPlaylist
import vip.wangxiaoming.music.data.local.entity.DownloadTask
import vip.wangxiaoming.music.data.local.entity.Favorite
import vip.wangxiaoming.music.data.local.entity.PlaybackHistory
import vip.wangxiaoming.music.data.local.entity.PlaylistSong

/**
 * 应用本地库。v2 起 5 张表（原版 5 张，playback_state 已移到 DataStore）。
 *
 * schema 导出到 app/schemas/（见 build.gradle.kts 的 room.schemaLocation），
 * 后续加表/改列时用导出的 json + 明文 SQL Migration 做对照。
 *
 * v1 → v2 只新增 favorites 表，不改任何既有列，因此迁移是纯增量：
 * 老的歌单 / 历史 / 下载任务在升级后原样保留。
 */
@Database(
    entities = [
        PlaybackHistory::class,
        CustomPlaylist::class,
        PlaylistSong::class,
        DownloadTask::class,
        Favorite::class,
    ],
    version = 2,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun playbackHistoryDao(): PlaybackHistoryDao
    abstract fun customPlaylistDao(): CustomPlaylistDao
    abstract fun playlistSongDao(): PlaylistSongDao
    abstract fun downloadTaskDao(): DownloadTaskDao
    abstract fun favoriteDao(): FavoriteDao

    companion object {
        const val NAME = "xinggui.db"

        /**
         * v1 → v2：新增 favorites 表。
         *
         * SQL 与 Room 依据 [Favorite] 生成的建表语句逐列对齐（列名、非空、默认值都必须一致），
         * 否则 Room 在启动校验 schema 时抛 IllegalStateException:
         * "Migration didn't properly handle: favorites"。
         * 两条索引也必须在迁移里建，否则 identityHash 不匹配。
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `favorites` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`song_id` TEXT NOT NULL, " +
                        "`name` TEXT NOT NULL, " +
                        "`artist` TEXT NOT NULL, " +
                        "`album` TEXT, " +
                        "`cover` TEXT, " +
                        "`source` TEXT NOT NULL, " +
                        "`duration` TEXT NOT NULL, " +
                        "`added_at` INTEGER NOT NULL)",
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_favorites_song_id` " +
                        "ON `favorites` (`song_id`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_favorites_added_at` " +
                        "ON `favorites` (`added_at`)",
                )
            }
        }

        @Volatile
        private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: build(context.applicationContext).also { instance = it }
            }

        private fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, NAME)
                .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
                .addMigrations(MIGRATION_1_2)
                // 不再 fallbackToDestructiveMigration：v1 用户的歌单/历史/下载记录
                // 必须靠 Migration 保住，静默清库是不可接受的。
                .build()
    }
}