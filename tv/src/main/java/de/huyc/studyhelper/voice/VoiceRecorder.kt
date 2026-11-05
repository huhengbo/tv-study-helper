package de.huyc.studyhelper.voice

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.SystemClock
import android.util.Log
import java.io.ByteArrayOutputStream
import kotlin.concurrent.thread
import kotlin.math.sqrt

/**
 * 按住语音键期间录音：16kHz / 16bit / 单声道 PCM，与小米语音服务录遥控器麦克风的参数一致。
 *
 * 遥控器音频由蓝牙服务经一个 socket 送进音频驱动，实测：按键后约 250ms 内只要有一路录音被关闭
 * （包括小爱同学被拒绝的那次录音），这次按键剩下的音频就送不进来了。所以按下后先等小爱那一下过去
 * （[OPEN_DELAY_MS]）再打开录音，中途不关；长时间收不到音频才重开一次。
 * 小爱同学自己也会录音并抢音频，需要在家长页停用它，见 [XiaoaiGuard]。
 */
class VoiceRecorder(private val context: Context) {

    interface Listener {
        /** 实时音量 0..1，用于驱动麦克风动效；在录音线程回调。 */
        fun onLevel(level: Float)
        /** 每读到一段音频（约 50ms）回调一次，用于边录边识别；在录音线程回调，[buf] 会被复用。 */
        fun onAudio(buf: ByteArray, length: Int) {}
        /** 按下后第一次收到音频，此后说的话能录到；只回调一次，在录音线程。 */
        fun onSteady() {}
        /** 录音结束；在录音线程回调。 */
        fun onFinished(pcm: ByteArray)
        fun onError(message: String)
    }

    /** 一次按键期间的录音。 */
    private class Capture(val listener: Listener) {
        val startedAt = SystemClock.elapsedRealtime()
        val out = ByteArrayOutputStream()
        val timeline = StringBuilder()
        var firstAt = -1L
        @Volatile var stopping = false
    }

    @Volatile private var capture: Capture? = null

    val isRecording: Boolean get() = capture != null

    fun start(listener: Listener) {
        if (capture != null) return
        val c = Capture(listener)
        capture = c
        thread(name = "voice-record") { record(c) }
        XiaoaiGuard.releaseMicSoon(context)
    }

    fun stop() {
        capture?.stopping = true
    }

    private fun record(c: Capture) {
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            fail(c, "没有录音权限")
            return
        }
        while (!c.stopping && elapsed(c) < OPEN_DELAY_MS) SystemClock.sleep(10)
        if (c.stopping) { finish(c); return }
        var r = open(c) ?: return
        Log.i(TAG, "按下后 ${elapsed(c)} ms 开始录音")
        val buf = ByteArray(CHUNK)
        var lastSound = elapsed(c)
        var reopens = 0
        try {
            while (!c.stopping && elapsed(c) < MAX_MILLIS) {
                // 不按键时遥控器不传音频，阻塞读会一直等，松开按键也停不下来
                val n = r.read(buf, 0, buf.size, AudioRecord.READ_NON_BLOCKING)
                val now = elapsed(c)
                if (n > 0 && buf.take(n).any { it != 0.toByte() }) lastSound = now
                if (now - lastSound > STALL_MS && reopens < MAX_REOPENS) {
                    reopens++
                    runCatching { r.stop() }
                    r.release()
                    // 打开失败时已通知出错，不再回调 onFinished
                    r = open(c) ?: return
                    lastSound = elapsed(c)
                    Log.i(TAG, "${STALL_MS} ms 没有音频，按下后 $now ms 重开录音")
                    continue
                }
                if (n <= 0) {
                    SystemClock.sleep(10)
                    continue
                }
                c.out.write(buf, 0, n)
                if (c.timeline.length < 600) c.timeline.append(now).append(':').append(c.out.size() / BYTES_PER_MS).append(' ')
                c.listener.onAudio(buf, n)
                c.listener.onLevel(level(buf, n))
                // 遥控器还没传音频时驱动会补全零的静音，真实录音不会整段为零
                if (c.firstAt < 0 && lastSound == now) {
                    c.firstAt = now
                    c.listener.onSteady()
                }
            }
            finish(c)
        } catch (e: Exception) {
            Log.w(TAG, "录音异常", e)
            finish(c)
        } finally {
            runCatching { r.stop() }
            r.release()
        }
    }

    private fun elapsed(c: Capture) = SystemClock.elapsedRealtime() - c.startedAt

    /** 打开并开始录音；失败时通知出错并返回 null。录音权限已在 [record] 开头检查。 */
    @SuppressLint("MissingPermission")
    private fun open(c: Capture): AudioRecord? {
        val min = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val r = try {
            AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, maxOf(min, CHUNK * 4))
        } catch (e: Exception) {
            fail(c, "无法打开麦克风：${e.message}")
            return null
        }
        if (r.state != AudioRecord.STATE_INITIALIZED) {
            r.release()
            fail(c, "麦克风初始化失败，请检查录音权限")
            return null
        }
        return try {
            r.startRecording()
            r
        } catch (e: Exception) {
            r.release()
            fail(c, "无法开始录音：${e.message}")
            null
        }
    }

    private fun finish(c: Capture) {
        if (capture === c) capture = null
        // 「按下后毫秒:累计录到毫秒」，排查开头丢音
        Log.i(TAG, "首段音频于 ${c.firstAt} ms；到达时间线 ${c.timeline}")
        c.listener.onFinished(c.out.toByteArray())
    }

    private fun fail(c: Capture, message: String) {
        if (capture === c) capture = null
        c.listener.onError(message)
    }

    private fun level(buf: ByteArray, n: Int): Float {
        var sum = 0.0
        var i = 0
        while (i + 1 < n) {
            val s = ((buf[i].toInt() and 0xff) or (buf[i + 1].toInt() shl 8)).toShort().toDouble()
            sum += s * s
            i += 2
        }
        val rms = sqrt(sum / (n / 2).coerceAtLeast(1))
        // 遥控器麦克风音量偏低，正常说话 RMS 约 200~2000
        return (rms / 2500.0).coerceIn(0.0, 1.0).toFloat()
    }

    companion object {
        const val SAMPLE_RATE = 16000
        private const val CHUNK = 1600 // 50ms
        private const val MAX_MILLIS = 15_000L
        /** 按下后多久打开录音：小爱被拒绝的那次录音约在按下后 130~250ms 关闭，见类说明。 */
        private const val OPEN_DELAY_MS = 300L
        /** 打开后这么久都没有声音就重开录音。 */
        private const val STALL_MS = 1000L
        private const val MAX_REOPENS = 2
        private const val BYTES_PER_MS = SAMPLE_RATE * 2 / 1000
        private const val TAG = "VoiceRecorder"

        /** PCM 时长（毫秒）。 */
        fun durationMs(pcm: ByteArray) = pcm.size / (SAMPLE_RATE * 2 / 1000)
    }
}
