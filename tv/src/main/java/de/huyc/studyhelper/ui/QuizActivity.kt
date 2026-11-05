package de.huyc.studyhelper.ui

import android.app.Activity
import android.os.Bundle
import android.view.KeyEvent
import de.huyc.studyhelper.App

/** 没有悬浮窗权限时的退路：用全屏 Activity 承载答题界面。 */
class QuizActivity : Activity() {
    private var quiz: QuizView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val preview = intent.getBooleanExtra(EXTRA_PREVIEW, false)
        val view = App.instance.lock.newQuizView(this, preview) { finish() }
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
        const val EXTRA_PREVIEW = "preview"
    }
}
