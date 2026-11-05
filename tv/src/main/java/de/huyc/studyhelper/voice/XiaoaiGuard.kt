package de.huyc.studyhelper.voice

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import de.huyc.studyhelper.core.SelfGrant

/**
 * 按下语音键时，系统蓝牙服务会同时拉起小爱同学录音，遥控器音频只送给先开始录音的一方（见 [VoiceRecorder]）。
 * 小爱的 VoiceControlService 导出了「语音键松开」指令，发送后它会结束这次提问。
 *
 * 遥控器的音频驱动只有一路数据，两边同时录音时每个音频包只归其中一方，双方都录不全，代码上绕不过去。
 * 所以家长可以在家长页停用小爱同学（收回它的录音权限，见 [SelfGrant.setXiaoaiMic]），语音键就只给本应用用。
 */
object XiaoaiGuard {
    private const val PKG = "com.xiaomi.voicecontrol"
    private const val SERVICE = "com.xiaomi.voicecontrol.VoiceControlService"
    private const val ACTION_KEYUP = "com.xiaomi.mitv.voice.KEYUP"

    private const val PREFS = "xiaoai"
    private const val KEY_MIC_BLOCKED = "mic_blocked"

    private val handler = Handler(Looper.getMainLooper())

    /** 是否已收回小爱的录音权限。应用读不到别的应用的 appops，所以记的是上次在家长页设置成功的结果。 */
    fun micBlocked(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_MIC_BLOCKED, false)

    /** 通过本机 adb 收回或恢复小爱的录音权限，成功后记下状态；阻塞执行，需在后台线程调用。 */
    fun setMicBlocked(context: Context, blocked: Boolean): SelfGrant.Outcome {
        val outcome = SelfGrant.setXiaoaiMic(context, allow = !blocked)
        if (outcome is SelfGrant.Outcome.Done) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_MIC_BLOCKED, blocked).apply()
        }
        return outcome
    }

    /**
     * 让小爱早点结束这次提问，不在答题界面下面空等（停用后它录音失败，也会弹提示等着）。
     * KEYUP 对小爱是「结束提问」，它有时会再开一轮，所以发两次。
     */
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
