package de.huyc.studyhelper.voice

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * 按下语音键时，系统蓝牙服务会同时拉起小爱同学录音。遥控器麦克风的 HAL 只有一条共享数据通道，
 * 两路录音会轮流分走数据，导致双方都只拿到约一半音频。
 * 小爱的 VoiceControlService 导出了「语音键松开」指令，发送后它会结束录音、释放通道。
 */
object XiaoaiGuard {
    private const val PKG = "com.xiaomi.voicecontrol"
    private const val SERVICE = "com.xiaomi.voicecontrol.VoiceControlService"
    private const val ACTION_KEYUP = "com.xiaomi.mitv.voice.KEYUP"

    private val handler = Handler(Looper.getMainLooper())

    /** 小爱在按键后约 10~50ms 才开始录音，分两次发送，确保它已进入录音状态后再让它退出。 */
    fun releaseMicSoon(context: Context) {
        val app = context.applicationContext
        handler.postDelayed({ releaseMic(app) }, 250)
        handler.postDelayed({ releaseMic(app) }, 700)
    }

    fun releaseMic(context: Context) {
        runCatching {
            context.startService(Intent(ACTION_KEYUP).setClassName(PKG, SERVICE))
        }.onFailure { Log.w("XiaoaiGuard", "发送 KEYUP 失败", it) }
    }
}
