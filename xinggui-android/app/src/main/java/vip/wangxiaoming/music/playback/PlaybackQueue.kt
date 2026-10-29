package vip.wangxiaoming.music.playback

/**
 * 一条队列条目。mediaId 必须全局唯一且稳定，它会原样写进 MediaItem.mediaId。
 * 格式约定「平台:id」，例如 "kw:474680496"、"wy:1901371647"。
 * 原版把 id 写成了 -1 常量，导致 MediaSession 的 active item id 永远是 -1。
 */
data class TrackRef(
    val mediaId: String,
    val title: String,
    val artist: String,
)

/**
 * 进程内队列（P5 阶段）。
 * P6 接 Room/服务端后，这里换成 Flow<List<TrackRef>> + 持久化即可，
 * PlaybackService 的消费方式不变。
 */
object PlaybackQueue {

    /** P5 冒烟用队列：只用一条可公网播放的样例音频，用于验证位置上报。 */
    val demo: List<TrackRef> = listOf(
        TrackRef("demo:soundhelix-1", "SoundHelix Song 1", "T. Schürger"),
        TrackRef("demo:soundhelix-2", "SoundHelix Song 2", "T. Schürger"),
    )
}