package de.huyc.studyhelper.voice

import android.annotation.SuppressLint
import android.content.Context
import android.os.Process
import android.util.Log
import de.huyc.studyhelper.App
import de.huyc.studyhelper.update.MirrorDownload
import de.huyc.studyhelper.update.UpdateSource
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.zip.GZIPInputStream
import kotlin.concurrent.thread

/**
 * 离线语音包：sherpa-onnx 运行库 + 中英双语流式识别模型（k2-fsa，Apache-2.0），约 50MB。
 * 不打进 APK，家长在手机上点下载时从本项目 Release 下载，和版本更新走同样的镜像。
 * 文件清单和校验码写死在这里：安装包有签名，换模型要跟着发新版本。
 */
class LocalAsrPack(context: Context) {
    private val app = context.applicationContext

    class PackFile(val name: String, val target: String, val size: Long, val sha256: String)

    enum class Phase { IDLE, DOWNLOADING, FAILED }

    class Status(val phase: Phase, val progress: Int = 0, val message: String = "") {
        fun toJson(): JSONObject = JSONObject().put("phase", phase.name).put("progress", progress).put("message", message)
    }

    @Volatile var status = Status(Phase.IDLE)
        private set

    val dir: File get() = File(app.filesDir, "asr-v$VERSION")
    val installed: Boolean get() = File(dir, DONE_MARK).exists()

    /** 当前进程的指令集：APK 里没有原生库，64 位电视上 App 跑在 64 位模式。 */
    val abi: String get() = if (Process.is64Bit()) "arm64-v8a" else "armeabi-v7a"

    val files: List<PackFile> get() = MODEL + LIBS.getValue(abi)
    val downloadSize: Long get() = files.sumOf { it.size }

    fun toJson(): JSONObject = JSONObject()
        .put("installed", installed)
        .put("size", downloadSize)
        .put("status", status.toJson())

    @Synchronized
    fun start() {
        require(status.phase != Phase.DOWNLOADING) { "正在下载，请稍等" }
        require(!installed) { "离线语音包已经下载好了" }
        status = Status(Phase.DOWNLOADING, 0, "正在下载离线语音包")
        thread(name = "asr-pack") {
            status = try {
                install()
                Status(Phase.IDLE, 100, "下载完成")
            } catch (e: Exception) {
                Log.w(TAG, "离线语音包下载失败", e)
                Status(Phase.FAILED, 0, e.message ?: e.toString())
            }
        }
    }

    @Synchronized
    fun remove() {
        require(status.phase != Phase.DOWNLOADING) { "正在下载，请稍等" }
        LocalAsr.release()
        dir.deleteRecursively()
        status = Status(Phase.IDLE)
    }

    // getAllocatableBytes 要 Android 8，目标电视是 Android 6
    @SuppressLint("UsableSpace")
    private fun install() {
        val staging = File(app.filesDir, "asr-v$VERSION.tmp")
        staging.deleteRecursively()
        staging.mkdirs()
        // 解压后约 70MB，下载的压缩文件逐个删掉
        if (staging.usableSpace < downloadSize + 80L * 1024 * 1024) throw IOException("电视存储空间不够，需要约 120MB")
        // 镜像顺序沿用版本检查的测速结果
        val mirrors = runCatching { App.instance.updater.check().mirrors }.getOrDefault(UpdateSource.MIRRORS)
        val total = downloadSize
        var done = 0L
        for (f in files) {
            val gz = File(staging, f.name)
            MirrorDownload.download(mirrors, BASE_URL + f.name, gz, f.size, f.sha256) { n ->
                status = Status(Phase.DOWNLOADING, ((done + n) * 100 / total).toInt(), "正在下载离线语音包")
            }
            GZIPInputStream(gz.inputStream()).use { input -> File(staging, f.target).outputStream().use { input.copyTo(it) } }
            gz.delete()
            done += f.size
        }
        File(staging, DONE_MARK).writeText(VERSION.toString())
        // 清掉旧版本的语音包
        app.filesDir.listFiles { f -> f.name.startsWith("asr-v") && f != staging }?.forEach { it.deleteRecursively() }
        if (!staging.renameTo(dir)) throw IOException("保存离线语音包失败")
    }

    companion object {
        const val VERSION = 1
        private const val TAG = "LocalAsrPack"
        private const val DONE_MARK = ".done"
        const val BASE_URL = "${UpdateSource.RELEASE_PREFIX}offline-asr-$VERSION/"

        // sherpa-onnx-streaming-zipformer-small-bilingual-zh-en-2023-02-16 的 int8 模型；bpe.vocab 由 bpe.model 导出，热词要用
        private val MODEL = listOf(
            PackFile("encoder.int8.onnx.gz", "encoder.int8.onnx", 37533271, "3f7c8119345cfa908bc495e9020f045f2f14f130e873c07d40bf7cfd2ef72fcd"),
            PackFile("decoder.int8.onnx.gz", "decoder.int8.onnx", 2378927, "59a14ef9f2d3f951f8ac3d09a2dba4be351135396f36dd45a9b38a61be236ba0"),
            PackFile("joiner.int8.onnx.gz", "joiner.int8.onnx", 1931567, "8401e784d8fe8eb6c7795e09957fdbf6c7b7bf9e7261273e0ff82e064366d022"),
            PackFile("tokens.txt.gz", "tokens.txt", 28463, "89fd4c8ca27a6941d940c87afa848bbebaa5f20f10b03ec63cb2d5710f14c9f1"),
            PackFile("bpe.vocab.gz", "bpe.vocab", 6084, "de8831b0075ead6719befb2ed13fd21a4dd5d5fb11f2dadb4c9d1e0ede24120b"),
        )

        // sherpa-onnx v2.0.1 android-static-link-onnxruntime，onnxruntime 静态链接，只依赖系统库
        private val LIBS = mapOf(
            "armeabi-v7a" to PackFile("libsherpa-onnx-jni-armeabi-v7a.so.gz", LocalAsr.LIB_NAME, 8059623, "0c552343a89813db062d566091729943a13052252278944454438bc1689c0a6c"),
            "arm64-v8a" to PackFile("libsherpa-onnx-jni-arm64-v8a.so.gz", LocalAsr.LIB_NAME, 8941891, "8dfc5f66a51782963d016a9b6c5418f07491f0d9949f9b483eabf712a1ad23aa"),
        )
    }
}
