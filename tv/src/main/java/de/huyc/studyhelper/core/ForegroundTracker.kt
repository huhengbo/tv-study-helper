package de.huyc.studyhelper.core

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context

/**
 * 当前前台 App 的包名。优先用无障碍服务上报的窗口切换事件（实时），
 * 无障碍未开启时退回「使用情况访问」查询最近的前台切换事件。
 */
object ForegroundTracker {
    @Volatile var fromAccessibility: String? = null
    @Volatile var accessibilityActive = false
    @Volatile private var lastFromUsage: String? = null

    /** 输入法、语音助手、系统浮层不算「切换了 App」。 */
    val transientPackages = setOf(
        "de.huyc.studyhelper",
        "com.xiaomi.voicecontrol",
        "com.android.systemui",
        "com.xiaomi.mitv.systemui",
        "com.sohu.inputmethod.sogou.tv",
    )

    fun current(context: Context): String? =
        if (accessibilityActive) fromAccessibility else fromUsageStats(context)

    private fun fromUsageStats(context: Context): String? {
        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager ?: return lastFromUsage
        val now = System.currentTimeMillis()
        val events = runCatching { usm.queryEvents(now - 10 * 60_000, now) }.getOrNull() ?: return lastFromUsage
        val e = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(e)
            @Suppress("DEPRECATION")
            if (e.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND && e.packageName !in transientPackages) {
                lastFromUsage = e.packageName
            }
        }
        return lastFromUsage
    }
}
