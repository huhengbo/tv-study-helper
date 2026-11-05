package de.huyc.studyhelper.ui

import android.app.Activity
import android.os.Bundle
import android.view.KeyEvent
import de.huyc.studyhelper.App
import de.huyc.studyhelper.quiz.QuizMode

/** 没有悬浮窗权限时的退路：用全屏 Activity 承载答题界面。 */
class QuizActivity : Activity() {
    private var quiz: QuizView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val mode = runCatching { QuizMode.valueOf(intent.getStringExtra(EXTRA_MODE).orEmpty()) }.getOrDefault(QuizMode.GATE)
        val view = App.instance.lock.newQuizView(this, mode) { finish() }
        quiz = view
        setContentView(view)
        view.requestFocus()
        view.start()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean = quiz?.dispatchKeyEvent(event) ?: super.dispatchKeyEvent(event)

    /** 锁定状态下被主页键切走时拉回来（安卓 6 允许后台启动 Activity）。 */
    override fun onStop() {
        super.onStop()
        if (!isFinishing && App.instance.store.locked) startActivity(intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    override fun onDestroy() {
        quiz?.release()
        App.instance.lock.onQuizActivityClosed()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_MODE = "mode"
    }
}
