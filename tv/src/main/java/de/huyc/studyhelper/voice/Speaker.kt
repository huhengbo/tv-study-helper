package de.huyc.studyhelper.voice

import android.content.Context
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.Executors

/**
 * 朗读题目。合成结果按「服务+音色+语速+文本」缓存为 mp3，同一句话只调用一次第三方接口。
 * - 缓存放在 filesDir：电视自带的清理工具会清空 cacheDir；
 * - 超过 [MAX_CACHE_BYTES] 时删除最久没播放的文件；
 * - 固定用语和题库题目由 [warmUp] 在后台预先合成，孩子第一次答题也不用等网络。
 * 未配置 TTS 或合成失败时静默跳过（回调照常触发），不影响答题。
 */
class Speaker(context: Context) {
    private val app = context.applicationContext
    private val cacheDir = File(app.filesDir, "tts").apply { mkdirs() }
    private val executor = Executors.newSingleThreadExecutor()
    /** 预合成单独排队，不阻塞正在答题时的朗读。 */
    private val warmExecutor = Executors.newSingleThreadExecutor()
    @Volatile private var warmGeneration = 0
    private val main = Handler(Looper.getMainLooper())
    private var player: MediaPlayer? = null
    private var token = 0

    /** [provider] 为空表示未启用朗读。[done] 在主线程回调，被新的朗读打断时不回调。 */
    fun speak(provider: TtsProvider?, text: String, done: () -> Unit = {}) {
        stop()
        val myToken = ++token
        if (provider == null || text.isBlank()) { done(); return }
        executor.execute {
            val file = runCatching { cached(provider, text) }
                .onFailure { Log.w(TAG, "合成失败：${it.message}") }
                .getOrNull()
            main.post {
                if (myToken != token) return@post
                if (file == null) done() else play(file, myToken, done)
            }
        }
    }

    /**
     * 后台预先合成，后续 [speak] 直接命中缓存。新一轮调用会取消上一轮尚未开始的部分。
     * 已缓存的文本不会重复请求。
     */
    fun warmUp(provider: TtsProvider?, texts: List<String>) {
        if (provider == null) return
        val gen = ++warmGeneration
        val todo = texts.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        warmExecutor.execute {
            var made = 0
            for (text in todo) {
                if (gen != warmGeneration) return@execute
                if (fileFor(provider, text).length() > 0) continue
                runCatching { cached(provider, text); made++ }.onFailure {
                    Log.w(TAG, "预合成失败，稍后再试：${it.message}")
                    return@execute
                }
            }
            if (made > 0) Log.i(TAG, "预合成 $made 条朗读")
            trimCache()
        }
    }

    /** 已缓存的朗读条数（手机端展示用）。 */
    fun cachedCount(): Int = cacheDir.listFiles { f -> f.name.endsWith(".mp3") }?.size ?: 0

    fun stop() {
        token++
        player?.let { runCatching { it.stop() }; it.release() }
        player = null
    }

    fun synthesizeOnce(provider: TtsProvider, text: String): File = cached(provider, text)

    private fun play(file: File, myToken: Int, done: () -> Unit) {
        val mp = MediaPlayer()
        player = mp
        try {
            @Suppress("DEPRECATION")
            mp.setAudioStreamType(AudioManager.STREAM_MUSIC)
            mp.setDataSource(file.absolutePath)
            mp.setOnCompletionListener { finish(it, myToken, done) }
            mp.setOnErrorListener { p, _, _ -> finish(p, myToken, done); true }
            mp.prepare()
            mp.start()
        } catch (e: Exception) {
            Log.w(TAG, "播放失败", e)
            file.delete()
            finish(mp, myToken, done)
        }
    }

    private fun finish(mp: MediaPlayer, myToken: Int, done: () -> Unit) {
        if (player === mp) { mp.release(); player = null }
        if (myToken == token) done()
    }

    private fun fileFor(provider: TtsProvider, text: String) = File(cacheDir, md5(provider.cacheKey + "|" + text.trim()) + ".mp3")

    private fun cached(provider: TtsProvider, text: String): File {
        val file = fileFor(provider, text)
        if (file.length() > 0) {
            file.setLastModified(System.currentTimeMillis()) // 记录最近使用，供清理时保留常用的
            return file
        }
        val audio = provider.synthesize(text.trim())
        // 朗读和预合成可能同时合成同一句，临时文件名带线程号避免互相覆盖
        val tmp = File(cacheDir, "${file.name}.${Thread.currentThread().id}.tmp")
        tmp.writeBytes(audio)
        tmp.renameTo(file)
        return file
    }

    private fun trimCache() {
        val files = cacheDir.listFiles()?.sortedByDescending { it.lastModified() } ?: return
        var total = 0L
        for (f in files) {
            total += f.length()
            val staleTmp = f.name.endsWith(".tmp") && System.currentTimeMillis() - f.lastModified() > 60_000
            if (total > MAX_CACHE_BYTES || staleTmp) f.delete()
        }
    }

    private fun md5(s: String) = MessageDigest.getInstance("MD5").digest(s.toByteArray())
        .joinToString("") { "%02x".format(it) }

    companion object {
        private const val TAG = "Speaker"
        private const val MAX_CACHE_BYTES = 30L * 1024 * 1024
    }
}
