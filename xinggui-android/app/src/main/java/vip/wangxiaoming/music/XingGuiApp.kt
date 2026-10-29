package vip.wangxiaoming.music

import android.app.Application
import vip.wangxiaoming.music.di.AppContainer

/**
 * 只做一件事：把依赖容器建起来。
 * 刻意不在这里做数据库预热、主题注入等耗时操作，避免冷启动变慢。
 */
class XingGuiApp : Application() {

    override fun onCreate() {
        super.onCreate()
        AppContainer.init(this)
    }
}