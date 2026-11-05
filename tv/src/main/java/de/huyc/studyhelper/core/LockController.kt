package de.huyc.studyhelper.core

import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import de.huyc.studyhelper.data.Store
import de.huyc.studyhelper.quiz.QuizMaker
import de.huyc.studyhelper.ui.QuizActivity
import de.huyc.studyhelper.ui.QuizView
import de.huyc.studyhelper.ui.WarningToast
import de.huyc.studyhelper.voice.Speaker

/**
 * 锁定与解锁。答题界面放在系统级浮层里：层级高于小爱同学的语音浮窗（TYPE_PHONE），
 * 按语音键时焦点不会被抢走；按主页键也只是切换浮层下面的 App。
 * 没有悬浮窗权限时退回全屏 Activity（按语音键时小爱浮窗可能抢焦点）。
 */
class LockController(private val context: Context, private val store: Store, private val speaker: Speaker) {
    private val main = Handler(Looper.getMainLooper())
    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val media = MediaControl(context)
    private var quizView: QuizView? = null
    private var activityShowing = false
    private var warning: View? = null

    val isShowingQuiz: Boolean get() = quizView != null || activityShowing

    fun lock() = main.post {
        store.locked = true
        dismissWarning()
        media.pauseOthers()
        showQuiz()
    }

    /** 显示答题界面。[preview] 为家长「试一试」：答完只关闭，不改动计时。 */
    fun showQuiz(preview: Boolean = false) = main.post {
        if (isShowingQuiz) return@post
        if (!Settings.canDrawOverlays(context)) {
            activityShowing = true
            context.startActivity(Intent(context, QuizActivity::class.java)
                .putExtra(QuizActivity.EXTRA_PREVIEW, preview)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
            return@post
        }
        val view = newQuizView(context, preview) { removeQuizView() }
        quizView = view
        wm.addView(view, overlayParams(focusable = true))
        view.requestFocus()
        view.start()
    }

    fun newQuizView(ctx: Context, preview: Boolean, close: () -> Unit): QuizView {
        val settings = store.settings
        // 多备几道，答错清零或换题时不重复
        val items = QuizMaker().make(settings, store.activeQuestions, settings.questionCount * 3)
        return QuizView(ctx, store, speaker, items, preview) { passed ->
            close()
            if (!preview && passed) unlock()
        }
    }

    fun onQuizActivityClosed() { activityShowing = false }

    /** 答题通过、家长 PIN 或手机端解锁。 */
    fun unlock() = main.post {
        removeQuizView()
        store.locked = false
        store.setUsed(0)
        store.bonusSeconds = 0
        media.resumeOthers(store.settings.resumePlayback)
    }

    /** 手机端加时：锁定中则解锁并只给 [minutes] 分钟；未锁定则追加到本轮时长。 */
    fun addTime(minutes: Int) = main.post {
        if (store.locked) {
            removeQuizView()
            store.locked = false
            store.bonusSeconds = 0
            store.setUsed((store.limitSeconds - minutes * 60).coerceAtLeast(0))
            media.resumeOthers(store.settings.resumePlayback)
        } else {
            store.bonusSeconds = store.bonusSeconds + minutes * 60
        }
    }

    fun showWarning(remainingSeconds: Int) = main.post {
        if (!Settings.canDrawOverlays(context) || warning != null) return@post
        val toast = WarningToast(context, remainingSeconds)
        warning = toast
        wm.addView(toast, overlayParams(focusable = false).apply {
            width = WindowManager.LayoutParams.WRAP_CONTENT
            height = WindowManager.LayoutParams.WRAP_CONTENT
            gravity = Gravity.TOP or Gravity.END
            format = PixelFormat.TRANSLUCENT
        })
        toast.play { dismissWarning() }
    }

    private fun dismissWarning() {
        warning?.let { runCatching { wm.removeView(it) } }
        warning = null
    }

    private fun removeQuizView() {
        quizView?.let { it.release(); runCatching { wm.removeView(it) } }
        quizView = null
    }

    private fun overlayParams(focusable: Boolean): WindowManager.LayoutParams {
        @Suppress("DEPRECATION")
        val type = if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else WindowManager.LayoutParams.TYPE_SYSTEM_ERROR
        // ALT_FOCUSABLE_IM：浮层不作为输入法目标，否则遥控器按键（如菜单键）会先被搜狗输入法截走
        var flags = WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED or
            WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM
        if (!focusable) flags = flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            type, flags, PixelFormat.TRANSLUCENT,
        )
    }
}
