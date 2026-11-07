package de.huyc.studyhelper.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.View
import de.huyc.studyhelper.App

/**
 * 锁定期间垫在答题浮层下面的黑色全屏页。浮层不会让下面的 App 进入后台，
 * 不响应媒体键和音频焦点的播放器会一直播；盖上这一页，视频 App 退到后台（onStop），
 * 播放器基本都会自己暂停。单独的任务栈，关掉后回到原来的视频 App，而不是首页。
 */
class LockCoverActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 启动途中已经解锁
        if (!App.instance.store.locked) { finish(); return }
        current = this
        setContentView(View(this).apply { setBackgroundColor(Color.BLACK) })
    }

    /** 被切走时拉回来，避免视频 App 回到前台接着播（安卓 6 允许后台启动 Activity）。 */
    override fun onStop() {
        super.onStop()
        if (!isFinishing && App.instance.store.locked) startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (!App.instance.store.locked) super.onBackPressed()
    }

    override fun onDestroy() {
        if (current === this) current = null
        super.onDestroy()
    }

    companion object {
        private var current: LockCoverActivity? = null

        fun show(context: Context) {
            if (current != null) return
            context.startActivity(Intent(context, LockCoverActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }

        fun hide() {
            current?.finish()
            current = null
        }
    }
}
