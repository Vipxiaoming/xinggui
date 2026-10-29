package vip.wangxiaoming.music.download

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * 存储权限收敛点。
 *
 * 三档策略（对应任务书「API 33+ READ_MEDIA_AUDIO / API 29-32 READ_EXTERNAL_STORAGE」）：
 *  - API 33+：读别人的音频要 READ_MEDIA_AUDIO；但我方只用 MediaStore 写自己的下载，
 *    写入不需要任何权限。读自己的下载目录仍需该权限（MediaStore 查询是全库过滤，
 *    无法按 owner 过滤），所以扫描前必须检查。
 *  - API 29-32：READ_EXTERNAL_STORAGE。
 *  - API 24-28：写公共目录要 WRITE_EXTERNAL_STORAGE + READ_EXTERNAL_STORAGE。
 *
 * 不申请 MANAGE_EXTERNAL_STORAGE（B8-a 决策），也不申请 READ_MEDIA_VISUAL_USER_SELECTED
 * 那种部分媒体授权——本 App 只处理音频，多要一个权限就是多一个被拒点。
 */
object StoragePermission {

    /** 需要动态申请的全部权限（给 ActivityCompat.requestPermissions 用）。 */
    fun required(context: Context): Array<String> = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU ->
            arrayOf(Manifest.permission.READ_MEDIA_AUDIO)
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ->
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        else -> arrayOf(
            Manifest.permission.READ_EXTERNAL_STORAGE,
            Manifest.permission.WRITE_EXTERNAL_STORAGE,
        )
    }

    /** 是否已全部授予。API 29+ 的写入不需要权限，但读取（本地扫描）需要。 */
    fun granted(context: Context): Boolean =
        required(context).all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }

    /** 给用户看的原因文案。区分「没申请过」与「拒绝且不再询问」。 */
    fun denialHint(context: Context): String {
        val audio = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            "「音乐和音频」"
        } else {
            "「存储」"
        }
        return "需要授予 " + audio + " 权限才能读取已下载的歌曲。" +
            "下载功能本身不受影响，可从系统设置里重新开启。"
    }
}