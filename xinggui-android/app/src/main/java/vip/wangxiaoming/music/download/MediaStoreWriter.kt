package vip.wangxiaoming.music.download

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream

/**
 * 把音频写进共享存储的 `Download/星轨/`。
 *
 * 关键实测结论（v1 踩过的坑，勿回退）：
 *   MediaStore.Audio 的集合**只接受** Audio 类目录
 *   （Music / Podcasts / Ringtones / Alarms / Notifications / Audiobooks / Recordings）。
 *   往它插 RELATIVE_PATH="Download/星轨" 会被 ContentProvider 直接拒绝：
 *   `Primary directory Download not allowed for content://media/external_primary/audio/media`。
 *   所以「放到 Download 且被媒体库索引」这两件事只能用 MediaStore.Downloads 完成，
 *   MediaStore.Downloads 的条目在 Android 11+ 也会进入系统「下载」与部分媒体扫描结果。
 *
 * 分档：
 *  - API 29+：MediaStore.Downloads + RELATIVE_PATH + IS_PENDING，写完置 0。
 *  - API 24-28：Downloads 集合与 RELATIVE_PATH 不可用，直接写
 *    /sdcard/Download/星轨/ 再 MediaScannerConnection.scanFile 让媒体库认领。
 *
 * 不使用 MANAGE_EXTERNAL_STORAGE（B8-a 决策）。
 */
object MediaStoreWriter {

    const val RELATIVE_DIR = "Download/星轨"
    const val MIME_MP3 = "audio/mpeg"

    private fun isScoped(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

    /** 创建待写入文件。同名不覆盖，由 [uniqueDisplayName] 追加序号。 */
    fun create(context: Context, displayName: String, mimeType: String = MIME_MP3): PendingFile {
        return if (isScoped()) createScoped(context, displayName, mimeType)
        else createLegacy(displayName)
    }

    private fun createScoped(context: Context, displayName: String, mimeType: String): PendingFile {
        val resolver = context.contentResolver
        val name = uniqueDisplayName(context, displayName)
        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Downloads.EXTERNAL_CONTENT_URI
        }
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, mimeType)
            put(MediaStore.Downloads.RELATIVE_PATH, RELATIVE_DIR)
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = resolver.insert(collection, values)
            ?: throw IllegalStateException("MediaStore 插入失败（存储不可用或目录被拒）")
        val out = resolver.openOutputStream(uri, "w")
            ?: run {
                runCatching { resolver.delete(uri, null, null) }
                throw IllegalStateException("无法打开 MediaStore 输出流")
            }
        return PendingFile(
            uri = uri,
            output = out,
            resolver = resolver,
            legacyFile = null,
            displayName = name,
        )
    }

    /** 同目录已有同名文件时补 `(1)`、`(2)`……，避免 MediaStore 悄悄改名成无意义后缀。 */
    private fun uniqueDisplayName(context: Context, displayName: String): String {
        val base = displayName.substringBeforeLast('.', displayName)
        val ext = displayName.substringAfterLast('.', "")
        val suffix = if (ext.isBlank()) "" else ".$ext"
        var candidate = displayName
        var i = 1
        while (exists(context, candidate) && i < 500) {
            candidate = base + " (" + i + ")" + suffix
            i++
        }
        return candidate
    }

    private fun exists(context: Context, displayName: String): Boolean {
        val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val args = arrayOf(RELATIVE_DIR + "/" + displayName)
        return runCatching {
            context.contentResolver.query(
                collection,
                arrayOf(MediaStore.Downloads._ID),
                MediaStore.Downloads.RELATIVE_PATH + "=?",
                args,
                null,
            )?.use { it.count > 0 } ?: false
        }.getOrDefault(false)
    }

    @Suppress("DEPRECATION")
    private fun createLegacy(displayName: String): PendingFile {
        val dir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "星轨",
        )
        if (!dir.exists() && !dir.mkdirs()) {
            throw IllegalStateException("无法创建目录 " + dir.absolutePath)
        }
        val file = uniqueLegacyFile(dir, displayName)
        return PendingFile(
            uri = Uri.fromFile(file),
            output = FileOutputStream(file),
            resolver = null,
            legacyFile = file,
            displayName = file.name,
        )
    }

    private fun uniqueLegacyFile(dir: File, displayName: String): File {
        val base = displayName.substringBeforeLast('.', displayName)
        val ext = displayName.substringAfterLast('.', "")
        var candidate = File(dir, displayName)
        var i = 1
        while (candidate.exists() && i < 500) {
            val suffix = if (ext.isBlank()) " ($i)" else " ($i).$ext"
            candidate = File(dir, base + suffix)
            i++
        }
        return candidate
    }

    /** 一次下载的目标。必须 [commit] 或 [abort]，否则会留下 pending 空记录。 */
    class PendingFile internal constructor(
        val uri: Uri,
        val output: OutputStream,
        private val resolver: android.content.ContentResolver?,
        private val legacyFile: File?,
        val displayName: String,
    ) {

        fun write(buffer: ByteArray, offset: Int, length: Int) = output.write(buffer, offset, length)

        fun commit(sizeBytes: Long): SavedFile {
            output.flush()
            output.close()
            if (legacyFile != null) {
                MediaScannerConnection.scanFile(
                    null,
                    arrayOf(legacyFile.absolutePath),
                    arrayOf(MIME_MP3),
                    null,
                )
                return SavedFile(
                    uri = Uri.fromFile(legacyFile),
                    path = legacyFile.absolutePath,
                    displayName = displayName,
                    sizeBytes = sizeBytes,
                )
            }
            val values = ContentValues().apply {
                put(MediaStore.Downloads.IS_PENDING, 0)
                put(MediaStore.Downloads.SIZE, sizeBytes)
            }
            resolver?.update(uri, values, null, null)
            return SavedFile(
                uri = uri,
                path = RELATIVE_DIR + "/" + displayName,
                displayName = displayName,
                sizeBytes = sizeBytes,
            )
        }

        /** 失败/取消时清理，绝不留下空文件或 pending 记录。 */
        fun abort() {
            runCatching { output.close() }
            if (legacyFile != null) {
                runCatching { legacyFile!!.delete() }
            } else {
                resolver?.let { runCatching { it.delete(uri, null, null) } }
            }
        }
    }

    data class SavedFile(
        val uri: Uri,
        /** 展示用：`Download/星轨/xxx.mp3`；老系统为绝对路径 */
        val path: String,
        val displayName: String,
        val sizeBytes: Long,
    )
}