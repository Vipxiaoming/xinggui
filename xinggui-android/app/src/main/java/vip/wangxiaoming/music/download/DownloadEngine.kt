package vip.wangxiaoming.music.download

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import okhttp3.Request
import vip.wangxiaoming.music.data.local.entity.DownloadStatus
import vip.wangxiaoming.music.data.local.entity.DownloadTask
import vip.wangxiaoming.music.data.model.UserPrefs
import vip.wangxiaoming.music.data.remote.client.KuwoClient
import vip.wangxiaoming.music.data.remote.model.Platform
import vip.wangxiaoming.music.data.remote.model.Song
import vip.wangxiaoming.music.di.AppContainer
import vip.wangxiaoming.music.ui.common.PlayLauncher
import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * 下载引擎（进程级单例）。
 *
 * 职责边界：Room 只存「状态」。`download_tasks` 没有百分比列，且 `data` 目录 属于 P3 写作用域，
 * 本阶段不改表结构；百分比放内存 [progress]，UI 直接订阅。
 *
 * 调度模型：`running` 是「当前真正在跑的 songId → Job」映射。每次 [kick] 都做同一件事——
 * 读当前并发上限，只要 `running.size < max` 就从 Room 取一条 QUEUED 起一个协程。
 * 并发上限来自 UserPrefs.maxConcurrentDownloads（1..10），运行时改设置下次 kick 即生效。
 *
 * 为什么不用 WorkManager：需求要求 1..10 进程内并发、暂停/取消要亚秒级生效。
 * WorkManager 的粒度是「持久任务」，取消还要 cancelWorkById 走数据库，没有进程内并发池语义。
 *
 * 暂停语义：Room 没有 PAUSED 常量（不能改 data 目录），所以暂停 = 取消 Job + 行退回 QUEUED +
 * 记进 [paused] 集合，调度器跳过它们。恢复 = 移出集合 + kick。
 */
class DownloadEngine private constructor(private val appContext: Context) {

    private val container: AppContainer get() = AppContainer.get()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    private val kuwo = KuwoClient()

    private val _progress = MutableStateFlow<Map<String, Progress>>(emptyMap())
    val progress: StateFlow<Map<String, Progress>> = _progress.asStateFlow()

    private val _active = MutableStateFlow<Set<String>>(emptySet())
    val active: StateFlow<Set<String>> = _active.asStateFlow()

    private val attempts = ConcurrentHashMap<String, Int>()
    private val paused = ConcurrentHashMap.newKeySet<String>()

    /** songId -> 正在跑的 Job */
    private val running = ConcurrentHashMap<String, Job>()

    private val scheduleLock = Mutex()

    private val kickCounter = AtomicInteger(0)

    // ---------------------------------------------------------------- 对外 API

    /** 入队。返回 null 表示已有成功记录（不重复下载），否则返回任务行。 */
    suspend fun enqueue(song: Song): DownloadTask? {
        val songId = songKeyOf(song)
        val existing = container.downloadRepository.find(songId)
        if (existing != null) {
            if (existing.status == DownloadStatus.SUCCESS) return null
            paused.remove(songId)
            attempts.remove(songId)
            container.downloadRepository.requeue(songId)
            kick()
            return existing
        }
        val task = DownloadTask(
            songId = songId,
            name = song.name,
            artist = song.artist,
            album = song.album,
            cover = song.cover,
            status = DownloadStatus.QUEUED,
            createTime = System.currentTimeMillis(),
        )
        val saved = container.downloadRepository.enqueue(task)
        kick()
        return saved
    }

    suspend fun enqueueAll(songs: List<Song>): Int {
        var added = 0
        songs.forEach { if (enqueue(it) != null) added++ }
        return added
    }

    /** 取消并删除记录（保留已落盘文件）。 */
    suspend fun cancel(songId: String) {
        paused.remove(songId)
        attempts.remove(songId)
        running.remove(songId)?.cancelAndJoin()
        _progress.update { it - songId }
        _active.update { it - songId }
        container.downloadRepository.remove(songId)
        kick()
    }

    /** 暂停：中断当前流并把行退回排队态。 */
    suspend fun pause(songId: String) {
        paused.add(songId)
        running.remove(songId)?.cancelAndJoin()
        _progress.update { it - songId }
        _active.update { it - songId }
        val row = container.downloadRepository.find(songId)
        if (row != null && row.status != DownloadStatus.SUCCESS) {
            container.downloadRepository.requeue(songId)
        }
    }

    suspend fun resume(songId: String) {
        paused.remove(songId)
        attempts.remove(songId)
        container.downloadRepository.requeue(songId)
        kick()
    }

    fun isPaused(songId: String): Boolean = songId in paused

    suspend fun retry(songId: String) {
        attempts.remove(songId)
        paused.remove(songId)
        container.downloadRepository.requeue(songId)
        kick()
    }

    /**
     * 触发调度。幂等、可高频调用：并发上限内把排队任务拉起来，没有空槽就什么都不做。
     * 队列表变化、偏好变化、App 启动都要调用。
     */
    fun kick() {
        val seq = kickCounter.incrementAndGet()
        scope.launch {
            scheduleLock.withLock {
                // 合并高频 kick：等待期间有更新的 kick 就让它来跑（避免重复扫库）
                if (seq != kickCounter.get()) return@withLock
                val max = runCatching {
                    container.prefsRepository.userPrefs.first().maxConcurrentDownloads
                }.getOrDefault(UserPrefs.DEFAULT_MAX_CONCURRENT).let { UserPrefs.maxConcurrent(it) }
                pump(max)
            }
        }
    }

    /** 启动时恢复：把上次进程被杀的 RUNNING 行退回 QUEUED，再开始调度。 */
    fun resumeOnStart() {
        scope.launch {
            runCatching {
                val stuck = container.downloadRepository.byStatus(DownloadStatus.RUNNING)
                stuck.forEach { container.downloadRepository.requeue(it.songId) }
            }
            kick()
        }
    }

    // ---------------------------------------------------------------- 调度内部

    private suspend fun pump(max: Int) {
        while (running.size < max) {
            val next = nextQueued() ?: return
            if (running.containsKey(next.songId)) continue
            val job = scope.launch { runTask(next) }
            running[next.songId] = job
            // 不 join：直接继续填下一个槽。Job 结束时自己从 running 移除。
        }
    }

    private suspend fun nextQueued(): DownloadTask? {
        val queued = container.downloadRepository.byStatus(DownloadStatus.QUEUED)
        return queued.firstOrNull {
            it.songId !in paused && !running.containsKey(it.songId)
        }
    }

    private suspend fun runTask(task: DownloadTask) {
        val songId = task.songId
        _active.update { it + songId }
        try {
            downloadWithRetry(task)
        } catch (ce: kotlinx.coroutines.CancellationException) {
            Log.d(TAG, "cancelled " + songId)
            throw ce
        } catch (t: Throwable) {
            Log.w(TAG, "failed " + songId + ": " + t.javaClass.simpleName + " " + t.message)
            container.downloadRepository.markFailed(songId, humanError(t))
            _progress.update { it - songId }
        } finally {
            running.remove(songId)
            _active.update { it - songId }
            // 腾出槽位：立即补下一个
            kick()
        }
    }

    private suspend fun downloadWithRetry(task: DownloadTask) {
        val songId = task.songId
        var lastError: Throwable? = null
        for (attempt in 1..MAX_ATTEMPTS) {
            if (songId in paused) return
            attempts[songId] = attempt
            try {
                container.downloadRepository.markRunning(songId)
                downloadOnce(task, attempt)
                return
            } catch (ce: kotlinx.coroutines.CancellationException) {
                throw ce
            } catch (t: Throwable) {
                lastError = t
                Log.d(TAG, "attempt " + attempt + " failed " + songId + ": " + t.message)
                if (attempt < MAX_ATTEMPTS) delay(1000L shl (attempt - 1))
            }
        }
        container.downloadRepository.markFailed(songId, humanError(lastError))
        _progress.update { it - songId }
    }

    /** 单次尝试：解析 URL → 流式写入 MediaStore。 */
    private suspend fun downloadOnce(task: DownloadTask, attempt: Int) {
        val songId = task.songId
        val url = resolveUrl(task) ?: throw IOException("解析不到播放地址")
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", UA)
            .header("Accept", "audio/*,*/*")
            .build()

        http.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("HTTP " + resp.code)
            val body = resp.body ?: throw IOException("响应为空")
            val declared = body.contentLength()
            val pending = MediaStoreWriter.create(
                appContext,
                FileNames.buildFileName(task.artist, task.name),
            )
            var written = 0L
            var committed = false
            try {
                val input = BufferedInputStream(body.byteStream(), 64 * 1024)
                verifyIsAudio(input)
                val buf = ByteArray(64 * 1024)
                var read: Int
                _progress.update {
                    it + (songId to Progress(0, declared.coerceAtLeast(0), declared <= 0, attempt))
                }
                while (true) {
                    if (songId in paused) throw kotlinx.coroutines.CancellationException("paused")
                    read = input.read(buf)
                    if (read <= 0) break
                    pending.write(buf, 0, read)
                    written += read
                    _progress.update {
                        it + (songId to Progress(written, declared.coerceAtLeast(0), declared <= 0, attempt))
                    }
                }
                if (written < MIN_VALID_BYTES) throw IOException("文件过小（" + written + " B）")
                val saved = pending.commit(written)
                committed = true
                container.downloadRepository.markSuccess(songId, saved.path)
                _progress.update { it + (songId to Progress(written, written, false, attempt)) }
                Log.d(TAG, "saved " + saved.path + " " + written + " B")
            } finally {
                if (!committed) pending.abort()
            }
        }
    }

    /**
     * 魔数校验：MP3（ID3 / 0xFFEx 帧同步）、FLAC（fLaC）、M4A（ftyp）、OGG（OggS）。
     * 上游无版权时常回 200 + HTML，不校验会存出 2 KB 的「歌曲」。
     * 用 mark/reset 读前 12 字节后复位，调用方继续从同一流读取。
     */
    private fun verifyIsAudio(input: InputStream) {
        val head = ByteArray(12)
        input.mark(head.size)
        var n = 0
        while (n < head.size) {
            val r = input.read(head, n, head.size - n)
            if (r <= 0) break
            n += r
        }
        input.reset()
        if (n < 4) throw IOException("响应过短")
        val b0 = head[0].toInt() and 0xFF
        val b1 = head[1].toInt() and 0xFF
        val b2 = head[2].toInt() and 0xFF
        val b3 = head[3].toInt() and 0xFF
        val isId3 = b0 == 0x49 && b1 == 0x44 && b2 == 0x33
        val isFrameSync = b0 == 0xFF && (b1 and 0xE0) == 0xE0
        val isFlac = b0 == 0x66 && b1 == 0x4C && b2 == 0x61 && b3 == 0x43
        val isOgg = b0 == 0x4F && b1 == 0x67 && b2 == 0x67 && b3 == 0x53
        val isMp4 = b1 == 0x66 && b2 == 0x74 && b3 == 0x79
        if (!(isId3 || isFrameSync || isFlac || isOgg || isMp4)) {
            throw IOException(
                "非音频响应（首字节 " + String.format("%02X %02X %02X %02X", b0, b1, b2, b3) + "）",
            )
        }
    }

    /**
     * 解析播放地址。直接复用 [PlayLauncher.resolve]，保证「能听的必然能下」。
     * 只读调用，不修改 ui/common（该目录不在本阶段写作用域）。
     */
    private suspend fun resolveUrl(task: DownloadTask): String? {
        val songId = task.songId
        val sep = songId.indexOf(':')
        if (sep <= 0) return null
        val source = songId.substring(0, sep)
        val id = songId.substring(sep + 1)
        val song = Song(
            id = id,
            name = task.name,
            artist = task.artist,
            album = task.album.orEmpty(),
            cover = task.cover.orEmpty(),
            source = source,
        )
        val viaPlay = runCatching { PlayLauncher.resolve(song) }.getOrNull()
        if (!viaPlay.isNullOrBlank()) return viaPlay
        // kg / tx 直连缺失时用「歌名 + 歌手」在酷我兜底一次
        if (Platform.of(source) == Platform.KUGOU || Platform.of(source) == Platform.QQ) {
            val kw = runCatching {
                kuwo.search((task.name + " " + task.artist).trim(), rn = 5)
            }.getOrNull()
            val hit = kw?.takeIf { it.ok }?.data?.firstOrNull() ?: return null
            return runCatching { PlayLauncher.resolve(hit) }.getOrNull()
        }
        return null
    }

    private fun humanError(t: Throwable?): String {
        if (t == null) return "未知错误"
        val msg = t.message.orEmpty()
        return when {
            t is java.net.UnknownHostException -> "网络不可达"
            t is java.net.SocketTimeoutException -> "连接超时"
            msg.startsWith("HTTP") -> "服务器返回 " + msg.removePrefix("HTTP ")
            msg.isNotBlank() -> msg.take(60)
            else -> t.javaClass.simpleName
        }
    }

    /** 与 PlayLauncher.mediaIdOf 一致：`source:id`。 */
    fun songKeyOf(song: Song): String = song.source + ":" + song.id

    companion object {
        private const val TAG = "DownloadEngine"
        const val MAX_ATTEMPTS = 3
        private const val MIN_VALID_BYTES = 16 * 1024L

        private const val UA =
            "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124 Mobile Safari/537.36"

        @Volatile
        private var instance: DownloadEngine? = null

        fun init(context: Context) {
            if (instance == null) {
                synchronized(this) {
                    if (instance == null) instance = DownloadEngine(context.applicationContext)
                }
            }
        }

        fun get(): DownloadEngine = instance ?: error("DownloadEngine 未初始化")

        fun peek(): DownloadEngine? = instance
    }

    data class Progress(
        val bytes: Long = 0,
        val total: Long = 0,
        val indeterminate: Boolean = false,
        val attempt: Int = 1,
    ) {
        val fraction: Float get() = if (total > 0) (bytes.toFloat() / total).coerceIn(0f, 1f) else 0f
        val percent: Int get() = (fraction * 100).toInt()
    }
}