package de.huyc.studyhelper.update

import android.util.Log
import de.huyc.studyhelper.voice.Http
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/** 从 GitHub Release 下载文件：按 [mirrors] 顺序逐个尝试，边下边算校验码，大小或校验码不对就换下一个镜像。 */
object MirrorDownload {

    /** [onProgress] 参数是已下载的字节数。失败时删掉 [target]。 */
    fun download(mirrors: List<String>, url: String, target: File, size: Long, sha256: String, onProgress: (Long) -> Unit) {
        var lastError: Exception? = null
        for (mirror in mirrors) {
            try {
                downloadFrom(mirror + url, target, size, sha256, onProgress)
                return
            } catch (e: Exception) {
                Log.w(TAG, "从 ${mirror.ifEmpty { "GitHub" }} 下载 ${target.name} 失败：${e.message}")
                lastError = e
                target.delete()
            }
        }
        throw IOException("下载失败，请稍后再试（${lastError?.message}）")
    }

    private fun downloadFrom(url: String, target: File, size: Long, sha256: String, onProgress: (Long) -> Unit) {
        val conn = Http.open(url)
        try {
            conn.connectTimeout = 8_000
            conn.readTimeout = 30_000
            if (conn.responseCode != 200) throw IOException("HTTP ${conn.responseCode}")
            val digest = MessageDigest.getInstance("SHA-256")
            var total = 0L
            conn.inputStream.use { input ->
                target.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        total += n
                        if (total > size) throw IOException("文件大小不对")
                        out.write(buf, 0, n)
                        digest.update(buf, 0, n)
                        onProgress(total)
                    }
                }
            }
            if (total != size) throw IOException("下载不完整")
            val sha = digest.digest().joinToString("") { "%02x".format(it) }
            if (sha != sha256) throw IOException("校验码不对")
        } finally {
            conn.disconnect()
        }
    }

    private const val TAG = "MirrorDownload"
}
