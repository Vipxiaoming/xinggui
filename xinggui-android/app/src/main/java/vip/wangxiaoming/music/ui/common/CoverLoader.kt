package vip.wangxiaoming.music.ui.common

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import android.widget.ImageView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import vip.wangxiaoming.music.R

/**
 * 极简封面加载器。
 *
 * 不引 Glide/Coil：P6 只需要「列表里能看到图」，而这两个库会带注解处理器 /
 * 协程扩展，编译时间与体积都不划算。这里用 OkHttp + BitmapFactory + LruCache，
 * 约 70 行，超时与失败回退都是显式的。
 *
 * 关键点：用 ImageView.tag 存当前 URL，回收复用时旧请求回来不会覆盖新图。
 */
object CoverLoader {

    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    /** 以 KB 计，上限约 24 MB，够 200 张 240px 封面。 */
    private val cache = object : LruCache<String, Bitmap>(24 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun load(view: ImageView, url: String?) {
        view.tag = url
        if (url.isNullOrBlank()) {
            view.setImageResource(R.drawable.bg_album_default)
            return
        }
        cache.get(url)?.let {
            view.setImageBitmap(it)
            return
        }
        view.setImageResource(R.drawable.bg_album_default)
        scope.launch {
            val bitmap = runCatching {
                client.newCall(Request.Builder().url(url).header("User-Agent", UA).build())
                    .execute()
                    .use { resp ->
                        if (!resp.isSuccessful) null
                        else resp.body?.byteStream()?.let { BitmapFactory.decodeStream(it) }
                    }
            }.getOrNull() ?: return@launch
            cache.put(url, bitmap)
            withContext(Dispatchers.Main) {
                if (view.tag == url) view.setImageBitmap(bitmap)
            }
        }
    }

    private const val UA = "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/124 Mobile"
}