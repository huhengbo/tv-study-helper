package de.huyc.studyhelper.update

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.content.FileProvider
import de.huyc.studyhelper.BuildConfig
import de.huyc.studyhelper.voice.Http
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.ExecutorCompletionService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * 检查和安装新版本。只在家长打开手机管理页时检查（结果缓存 [CACHE_MS]），不在电视上打扰孩子。
 * 安装流程：按镜像速度依次下载 → 核对大小和校验码 → 核对包名、版本号和签名与已安装的一致 → 拉起系统安装界面，
 * 家长用遥控器点「安装」。没有 root 权限，静默安装做不到。
 */
class Updater(context: Context) {
    private val app = context.applicationContext

    enum class Phase { IDLE, DOWNLOADING, VERIFYING, INSTALLING, FAILED }

    class Status(val phase: Phase, val progress: Int = 0, val message: String = "") {
        fun toJson(): JSONObject = JSONObject().put("phase", phase.name).put("progress", progress).put("message", message)
    }

    /** [mirrors] 按响应快慢排序，下载时依次尝试。 */
    class Checked(val manifest: UpdateManifest?, val mirrors: List<String>, val error: String, val time: Long) {
        val available: Boolean get() = (manifest?.versionCode ?: 0) > BuildConfig.VERSION_CODE
    }

    @Volatile var status = Status(Phase.IDLE)
        private set
    @Volatile private var checked: Checked? = null

    @Synchronized
    fun check(force: Boolean = false): Checked {
        checked?.takeIf { !force && System.currentTimeMillis() - it.time < CACHE_MS && it.error.isEmpty() }?.let { return it }
        return fetchManifest().also { checked = it }
    }

    /** 开始下载安装，立即返回；进度看 [status]。 */
    @Synchronized
    fun start(locked: Boolean) {
        val phase = status.phase
        require(phase != Phase.DOWNLOADING && phase != Phase.VERIFYING) { "正在下载，请稍等" }
        // 答题浮层盖在所有窗口之上，系统安装界面会被挡住
        require(!locked) { "电视正在答题锁定中，请先解锁再更新" }
        val c = check()
        val manifest = c.manifest ?: throw IllegalArgumentException(c.error.ifEmpty { "检查更新失败" })
        require(c.available) { "已经是最新版本" }
        status = Status(Phase.DOWNLOADING, 0, "正在下载 v${manifest.version}")
        thread(name = "updater") {
            status = try {
                val apk = download(manifest, c.mirrors)
                status = Status(Phase.VERIFYING, 100, "正在核对安装包")
                verify(apk, manifest)
                install(apk)
            } catch (e: Exception) {
                Log.w(TAG, "更新失败", e)
                Status(Phase.FAILED, 0, e.message ?: e.toString())
            }
        }
    }

    // ---- 检查 ----

    private fun fetchManifest(): Checked {
        val pool = Executors.newFixedThreadPool(UpdateSource.MIRRORS.size)
        try {
            val done = ExecutorCompletionService<Pair<String, UpdateManifest>>(pool)
            UpdateSource.MIRRORS.forEach { mirror ->
                done.submit {
                    val resp = Http.get(mirror + UpdateSource.MANIFEST_URL, 64 * 1024, TIMEOUT_MS)
                    if (resp.code != 200) throw IOException("HTTP ${resp.code}")
                    mirror to UpdateManifest.parse(JSONObject(resp.text))
                }
            }
            val ranked = ArrayList<String>()
            var best: UpdateManifest? = null
            var lastError = ""
            // 第一个镜像返回后再稍等其他镜像，排出下载顺序；镜像可能缓存了旧的清单，取版本最新的
            var deadline = System.currentTimeMillis() + TIMEOUT_MS + 2_000
            repeat(UpdateSource.MIRRORS.size) {
                val f = done.poll((deadline - System.currentTimeMillis()).coerceAtLeast(0), TimeUnit.MILLISECONDS) ?: return@repeat
                runCatching { f.get() }
                    .onSuccess { (mirror, m) ->
                        ranked += mirror
                        if (best == null) deadline = minOf(deadline, System.currentTimeMillis() + EXTRA_WAIT_MS)
                        if (m.versionCode > (best?.versionCode ?: 0)) best = m
                    }
                    .onFailure { lastError = (it.cause ?: it).message.orEmpty() }
            }
            // 没来得及返回的镜像排在最后，下载时仍可作为后备
            val mirrors = ranked + UpdateSource.MIRRORS.filter { it !in ranked }
            val error = if (best == null) "连不上更新服务器，请检查电视网络（$lastError）" else ""
            Log.i(TAG, "最新版本 ${best?.version ?: "未知"}，镜像顺序 ${ranked.map { it.ifEmpty { "GitHub" } }}")
            return Checked(best, mirrors, error, System.currentTimeMillis())
        } finally {
            pool.shutdownNow()
        }
    }

    // ---- 下载 ----

    private fun download(m: UpdateManifest, mirrors: List<String>): File {
        // Android 7 以前系统安装器只接受 file:// 地址，文件要放在它能读到的外部存储
        val dir = app.getExternalFilesDir("update") ?: throw IOException("电视存储不可用")
        dir.listFiles()?.forEach { it.delete() }
        val target = File(dir, m.apk.name)
        MirrorDownload.download(mirrors, m.apk.url, target, m.apk.size, m.apk.sha256) { total ->
            status = Status(Phase.DOWNLOADING, (total * 100 / m.apk.size).toInt(), "正在下载 v${m.version}")
        }
        return target
    }

    // ---- 核对与安装 ----

    @Suppress("DEPRECATION")
    private fun verify(apk: File, m: UpdateManifest) {
        val pm = app.packageManager
        val info = pm.getPackageArchiveInfo(apk.path, PackageManager.GET_SIGNATURES)
            ?: throw IOException("下载的文件不是有效的安装包")
        if (info.packageName != app.packageName) throw IOException("安装包不是学习小助手")
        if (info.versionCode != m.versionCode || info.versionCode <= BuildConfig.VERSION_CODE) throw IOException("安装包版本不对")
        val mine = pm.getPackageInfo(app.packageName, PackageManager.GET_SIGNATURES).signatures.orEmpty().map { it.toCharsString() }.toSet()
        val theirs = info.signatures.orEmpty().map { it.toCharsString() }.toSet()
        // 签名不同系统也装不上；提前拦下来给出明确提示（如电视上装的是自己编译的调试版）
        if (theirs.isEmpty() || theirs != mine) {
            throw IOException("安装包的签名和电视上的版本不一致，不能直接升级。请卸载后重新安装")
        }
    }

    private fun install(apk: File): Status {
        if (Build.VERSION.SDK_INT >= 26 && !app.packageManager.canRequestPackageInstalls()) {
            app.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${app.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return Status(Phase.FAILED, 0, "请在电视上允许学习小助手安装应用，然后再点一次更新")
        }
        val uri = if (Build.VERSION.SDK_INT >= 24) FileProvider.getUriForFile(app, "${app.packageName}.update", apk)
        else Uri.fromFile(apk)
        app.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION))
        return Status(Phase.INSTALLING, 100, "请用遥控器在电视上点「安装」，装好后守护会自动恢复")
    }

    companion object {
        private const val TAG = "Updater"
        private const val CACHE_MS = 10 * 60_000L
        private const val TIMEOUT_MS = 8_000
        private const val EXTRA_WAIT_MS = 1_500L
    }
}
