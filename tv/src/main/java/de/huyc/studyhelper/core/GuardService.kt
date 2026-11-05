package de.huyc.studyhelper.core

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import de.huyc.studyhelper.App
import de.huyc.studyhelper.R
import de.huyc.studyhelper.data.CountMode
import de.huyc.studyhelper.voice.Phrases
import de.huyc.studyhelper.voice.VoiceProviders
import de.huyc.studyhelper.web.AdminServer

/**
 * 常驻前台服务：每秒计一次观看时间，到点锁定答题；同时承载手机管理网页服务。
 * 计时只在亮屏且（按设置）指定 App 在前台时累加，状态每 10 秒落盘，断电重启后继续。
 */
class GuardService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private val app get() = App.instance
    private var server: AdminServer? = null
    private var ticks = 0
    private var warned = false

    private val tick = object : Runnable {
        override fun run() {
            onTick()
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIFICATION_ID, notification())
        server = AdminServer(this, app.store).also { runCatching { it.start() } }
        handler.post(tick)
        // 设置或题库变化后预合成朗读（防抖），另外定时补一次以便网络恢复后重试
        app.store.onChange { handler.removeCallbacks(warmUp); handler.postDelayed(warmUp, 5_000) }
        handler.postDelayed(warmUp, 20_000)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        handler.removeCallbacks(tick)
        server?.stop()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private val warmUp = object : Runnable {
        override fun run() {
            handler.removeCallbacks(this)
            val s = app.store.settings
            if (s.voice.ttsEnabled) {
                val texts = Phrases.all(s) + app.store.activeQuestions.filter { it.subject in s.subjects }.map { it.speakText }
                app.speaker.warmUp(VoiceProviders.tts(this@GuardService, s.voice), texts)
            }
            handler.postDelayed(this, 10 * 60_000L)
        }
    }

    private fun onTick() {
        val store = app.store
        val lock = app.lock
        if (store.paused) { warned = false; return }
        if (store.locked) {
            // 开机或浮层意外消失后重新弹出
            if (!lock.isShowingQuiz) lock.showQuiz()
            return
        }
        if (!isCounting()) return

        val used = store.usedSeconds + 1
        val remaining = store.limitSeconds - used
        store.setUsed(used, persist = ++ticks % 10 == 0 || remaining <= 0)

        val warnAt = store.settings.warnSeconds
        if (warnAt > 0 && remaining in 1..warnAt && !warned) {
            warned = true
            lock.showWarning(remaining)
        }
        if (remaining <= 0) {
            warned = false
            lock.lock()
        }
    }

    private fun isCounting(): Boolean {
        val power = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (!power.isInteractive) return false
        // 孩子在答题（含主动学习）时不计时，否则答题中途可能被锁屏打断
        if (app.lock.isShowingQuiz) return false
        val settings = app.store.settings
        if (settings.countMode == CountMode.ALL) return true
        val pkg = ForegroundTracker.current(this) ?: return false
        return pkg in settings.apps
    }

    private fun notification(): Notification {
        val builder = if (Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "观看时间守护", NotificationManager.IMPORTANCE_MIN))
            Notification.Builder(this, CHANNEL)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this).setPriority(Notification.PRIORITY_MIN)
        }
        return builder
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.app_name))
            .setContentText("正在守护观看时间")
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL = "guard"

        fun start(context: Context) {
            val intent = Intent(context, GuardService::class.java)
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent) else context.startService(intent)
        }
    }
}
