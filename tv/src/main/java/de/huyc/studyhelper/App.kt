package de.huyc.studyhelper

import android.animation.ValueAnimator
import android.app.Application
import android.util.Log
import de.huyc.studyhelper.core.LockController
import de.huyc.studyhelper.data.Store
import de.huyc.studyhelper.update.Updater
import de.huyc.studyhelper.voice.LocalAsrPack
import de.huyc.studyhelper.voice.Speaker

class App : Application() {
    lateinit var store: Store
        private set
    lateinit var speaker: Speaker
        private set
    lateinit var lock: LockController
        private set
    val updater by lazy { Updater(this) }
    val asrPack by lazy { LocalAsrPack(this) }

    override fun onCreate() {
        super.onCreate()
        instance = this
        forceAnimations()
        store = Store(this)
        speaker = Speaker(this)
        lock = LockController(this, store, speaker)
    }

    /**
     * 小米电视出厂把「动画程序时长缩放」设为 0，所有属性动画会直接跳到结束。
     * 这里只在本进程内恢复为 1，不改动系统全局设置。setDurationScale 是隐藏接口，失败时保持系统行为。
     */
    private fun forceAnimations() {
        runCatching {
            ValueAnimator::class.java.getMethod("setDurationScale", Float::class.javaPrimitiveType).invoke(null, 1f)
        }.onFailure { Log.w("App", "无法恢复动画时长缩放", it) }
    }

    companion object {
        lateinit var instance: App
            private set
    }
}
