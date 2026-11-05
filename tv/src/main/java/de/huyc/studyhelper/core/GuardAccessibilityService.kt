package de.huyc.studyhelper.core

import android.accessibilityservice.AccessibilityService
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import de.huyc.studyhelper.App

/**
 * 1. 跟踪前台 App，用于「只统计指定 App 的观看时间」；
 * 2. 答题锁定期间吞掉主页键和最近任务键，孩子无法切走（答题浮层本身也始终在最上层）。
 */
class GuardAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        ForegroundTracker.accessibilityActive = true
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        if (pkg !in ForegroundTracker.transientPackages) ForegroundTracker.fromAccessibility = pkg
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (!App.instance.lock.isShowingQuiz) return false
        return event.keyCode == KeyEvent.KEYCODE_HOME || event.keyCode == KeyEvent.KEYCODE_APP_SWITCH
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        ForegroundTracker.accessibilityActive = false
        super.onDestroy()
    }
}
