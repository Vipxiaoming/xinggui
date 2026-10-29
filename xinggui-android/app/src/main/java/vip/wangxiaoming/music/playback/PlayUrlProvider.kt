package vip.wangxiaoming.music.playback

/**
 * 播放地址来源抽象。
 *
 * P5 只提供 [StaticPlayUrlProvider]（本地调试用固定公网 mp3）。
 * P6 换成服务端 /v1/resolve 的实现时，只需在 AppContainer 里换一行绑定，
 * PlaybackService 与 UI 都不用动。
 *
 * 之所以做成 suspend：解析要走网络竞速（4 家平台），不能阻塞主线程；
 * 但 Media3 的 MediaItem 是同步构造的，因此实际调用点在 prepareQueue() 里
 * 先并行解析完拿到 URL，再一次性 setMediaItems。
 */
interface PlayUrlProvider {
    /**
     * @param mediaId 曲目稳定标识（平台前缀 + 平台内 id），必须原样写回 MediaItem.mediaId，
     *                否则 MediaController 拿到的 active item id 就是 -1（原版缺陷）。
     * @return 可直接播放的 URL；解析失败返回 null。
     */
    suspend fun resolve(mediaId: String, title: String, artist: String): String?
}

/**
 * 纯本地实现：忽略入参，永远返回同一个可公网访问的 mp3。
 * 用途只有两个——验证播放链路与 MediaSession 位置上报，不参与任何业务判断。
 */
class StaticPlayUrlProvider(
    private val url: String = DEFAULT_URL,
) : PlayUrlProvider {

    override suspend fun resolve(mediaId: String, title: String, artist: String): String = url

    companion object {
        /** 被测过 HEAD=200 audio/mpeg 的公开样例音频，非我方服务。 */
        const val DEFAULT_URL = "https://www.soundhelix.com/examples/mp3/SoundHelix-Song-1.mp3"
    }
}