package de.huyc.studyhelper.core

import android.app.AppOpsManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Process
import android.provider.Settings

/** 四项权限的当前状态。电视主页、手机管理页和授权引导页共用同一份判断。 */
data class PermissionStatus(
    val overlay: Boolean,
    val record: Boolean,
    val accessibility: Boolean,
    val usage: Boolean,
) {
    val allGranted: Boolean get() = overlay && record && accessibility && usage
}

object Permissions {
    fun status(context: Context) = PermissionStatus(
        overlay = Settings.canDrawOverlays(context),
        record = context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED,
        accessibility = ForegroundTracker.accessibilityActive,
        usage = hasUsageAccess(context),
    )

    private fun hasUsageAccess(context: Context): Boolean {
        val ops = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        @Suppress("DEPRECATION")
        return ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName) == AppOpsManager.MODE_ALLOWED
    }
}
