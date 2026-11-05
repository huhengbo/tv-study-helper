package de.huyc.studyhelper.core

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import de.huyc.studyhelper.App

/**
 * 开机后总是启动守护服务（手机管理网页也在其中）。
 * 关闭「开机自动开始守护」时只把守护置为暂停；关机前处于锁定状态则照常重新弹出答题。
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val store = App.instance.store
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED && !store.settings.autoStart && !store.locked) store.paused = true
        GuardService.start(context)
    }
}
