package vip.wangxiaoming.music.ui.common

import androidx.media3.session.MediaController

/**
 * 播放控制器的页面级共享引用。
 *
 * 为什么放在 ui 层而不是 playback 层：MediaController 的生命周期由 Activity 持有
 * （Media3 要求 onStart 连接 / onStop 释放），Fragment 只借用。这里不持有强引用之外的
 * 任何状态，Activity 释放时置空，避免 Fragment 拿到已失效的 controller。
 */
object PlayerHolder {

    @Volatile
    var controller: MediaController? = null
        private set

    fun attach(c: MediaController?) {
        controller = c
    }

    fun detach() {
        controller = null
    }
}