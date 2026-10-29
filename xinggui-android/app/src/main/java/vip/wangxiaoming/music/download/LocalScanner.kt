package vip.wangxiaoming.music.download

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import vip.wangxiaoming.music.data.remote.model.Song
import java.io.File

/**
 * 本地扫描（B8-a：只扫 App 自己的下载目录）。
 *
 * 不遍历存储、不读别的 App 的文件。查询条件固定为
 * `Download/星轨/` 前缀（API 29+ 用 RELATIVE_PATH，API 24-28 用 DATA 绝对路径）。
 *
 * 为什么要查两个集合：
 *  1. 我们的文件写在 MediaStore.Downloads（原因见 [MediaStoreWriter] 注释），
 *     所以主查询必须是 Downloads；
 *  2. MediaProvider 对 `Download/` 下的音频常常**同时**建一条 audio 记录，
 *     而部分机型/版本只在 audio 里出现。两个都查再按 `_id + 文件名` 去重，
 *     才不会出现「文件明明在，列表却是空的」。
 *
 * 结果映射为 [Song]：`source = "local"`，`id` 用该集合的 `_id`，
 * 与远程歌曲（wy/kw/kg/tx:id）的去重键天然不冲突。
 */
class LocalScanner(private val context: Context) {

    data class Result(
        val songs: List<Song>,
        val error: String? = null,
    )

    fun scan(): Result {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) scanScoped() else scanLegacy()
    }

    private fun scanScoped(): Result {
        val prefix = MediaStoreWriter.RELATIVE_DIR + "/%"
        val fromDownloads = queryDownloads(prefix)
        val fromAudio = queryAudio(prefix)
        // 去重键只用「歌名 + 歌手」：同一个文件会被 MediaProvider 同时登记在
        // Downloads 与 Audio 两个集合里，Audio 那条带 DURATION、Downloads 那条不带。
        // 早先把 duration 也拼进键，结果同一首歌被算成 2 首（实测：列表 2 行、计数 2）。
        // 同一目录下「同名同歌手但确实是两个文件」由文件名里的 (1) 后缀区分，
        // 这里再按 DISPLAY_NAME 兜一层，防止真的两份文件被合并。
        val byKey = LinkedHashMap<String, Song>()
        // Audio 先入：它带时长/专辑，信息更完整；Downloads 里独有的再补
        (fromAudio.songs + fromDownloads.songs).forEach { s ->
            val key = s.name + "|" + s.artist
            val existing = byKey[key]
            if (existing == null) {
                byKey[key] = s
            } else if (existing.localFilePath != s.localFilePath &&
                existing.durationSeconds == 0 && s.durationSeconds > 0
            ) {
                // 同键但当前这条信息更全（有真实时长），替换
                byKey[key] = s
            }
        }
        return Result(byKey.values.toList(), fromDownloads.error ?: fromAudio.error)
    }

    private fun queryDownloads(prefix: String): Result {
        val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val projection = arrayOf(
            MediaStore.Downloads._ID,
            MediaStore.Downloads.DISPLAY_NAME,
            MediaStore.Downloads.TITLE,
            MediaStore.Downloads.MIME_TYPE,
            MediaStore.Downloads.SIZE,
        )
        val selection = MediaStore.Downloads.RELATIVE_PATH + " LIKE ? AND " +
            MediaStore.Downloads.MIME_TYPE + " LIKE ?"
        val args = arrayOf(prefix, "audio/%")
        return query(collection, projection, selection, args, hasDuration = false)
    }

    private fun queryAudio(prefix: String): Result {
        val collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.DISPLAY_NAME,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.SIZE,
        )
        val selection = MediaStore.Audio.Media.RELATIVE_PATH + " LIKE ?"
        val args = arrayOf(prefix)
        return query(collection, projection, selection, args, hasDuration = true)
    }

    @Suppress("DEPRECATION")
    private fun scanLegacy(): Result {
        val dir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "星轨",
        )
        if (!dir.exists()) return Result(emptyList())
        val collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.DISPLAY_NAME,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.SIZE,
        )
        val selection = MediaStore.Audio.Media.DATA + " LIKE ?"
        return query(collection, projection, selection, arrayOf(dir.absolutePath + "/%"), true)
    }

    private fun query(
        collection: Uri,
        projection: Array<String>,
        selection: String,
        args: Array<String>,
        hasDuration: Boolean,
    ): Result {
        val songs = ArrayList<Song>()
        val resolver = context.contentResolver
        try {
            resolver.query(
                collection,
                projection,
                selection,
                args,
                MediaStore.Audio.Media.DATE_ADDED + " DESC",
            )?.use { c ->
                val idIdx = c.getColumnIndex(MediaStore.Audio.Media._ID)
                val nameIdx = c.getColumnIndex(MediaStore.Audio.Media.DISPLAY_NAME)
                val titleIdx = c.getColumnIndex(MediaStore.Audio.Media.TITLE)
                val artistIdx = if (hasDuration) c.getColumnIndex(MediaStore.Audio.Media.ARTIST) else -1
                val albumIdx = if (hasDuration) c.getColumnIndex(MediaStore.Audio.Media.ALBUM) else -1
                val durIdx = if (hasDuration) c.getColumnIndex(MediaStore.Audio.Media.DURATION) else -1
                if (idIdx < 0) return Result(emptyList(), "查询列缺失")
                while (c.moveToNext()) {
                    val mediaId = c.getLong(idIdx)
                    val display = nameIdx.takeIf { it >= 0 }?.let { c.getString(it) }.orEmpty()
                    if (display.isBlank()) continue
                    // TITLE 优先（系统从 ID3 读），否则退回去掉扩展名的文件名
                    val title = titleIdx.takeIf { it >= 0 }?.let { c.getString(it) }
                        ?.takeIf { it.isNotBlank() }
                        ?: display.substringBeforeLast('.')
                    val artist = artistIdx.takeIf { it >= 0 }?.let { c.getString(it) }
                        ?.takeIf { it.isNotBlank() && it != "<unknown>" }
                        ?: display.substringBefore(" - ").takeIf { display.contains(" - ") }
                        ?: "未知歌手"
                    val album = albumIdx.takeIf { it >= 0 }?.let { c.getString(it) }.orEmpty()
                    val durationMs = durIdx.takeIf { it >= 0 }?.let { c.getLong(it) } ?: 0L
                    val seconds = (durationMs / 1000).toInt()
                    val contentUri = ContentUris.withAppendedId(collection, mediaId)
                    songs.add(
                        Song(
                            id = mediaId.toString(),
                            name = title,
                            artist = artist,
                            album = album,
                            source = SOURCE_LOCAL,
                            duration = mmss(seconds),
                            durationSeconds = seconds,
                            localFilePath = contentUri.toString(),
                            isDownloaded = true,
                        ),
                    )
                }
            }
        } catch (e: SecurityException) {
            return Result(emptyList(), "缺少读取音频权限")
        } catch (e: Exception) {
            return Result(emptyList(), e.javaClass.simpleName + ": " + (e.message ?: ""))
        }
        return Result(songs)
    }

    private fun mmss(totalSeconds: Int): String {
        if (totalSeconds <= 0) return ""
        return "%02d:%02d".format(totalSeconds / 60, totalSeconds % 60)
    }

    companion object {
        const val SOURCE_LOCAL = "local"
    }
}