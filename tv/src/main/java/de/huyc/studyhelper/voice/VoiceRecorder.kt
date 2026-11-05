package de.huyc.studyhelper.voice

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import java.io.ByteArrayOutputStream
import kotlin.concurrent.thread
import kotlin.math.sqrt

/**
 * 按住语音键期间录音：16kHz / 16bit / 单声道 PCM，与小米语音服务录遥控器麦克风的参数一致。
 * 录音开始后通知小爱让出麦克风通道（见 [XiaoaiGuard]）。
 */
class VoiceRecorder(private val context: Context) {

    interface Listener {
        /** 实时音量 0..1，用于驱动麦克风动效；在录音线程回调。 */
        fun onLevel(level: Float)
        /** 录音结束；在录音线程回调。 */
        fun onFinished(pcm: ByteArray)
        fun onError(message: String)
    }

    @Volatile private var recording = false
    @Volatile private var recorder: AudioRecord? = null

    val isRecording: Boolean get() = recording

    fun start(listener: Listener) {
        if (recording) return
        recording = true
        thread(name = "voice-record") { record(listener) }
        XiaoaiGuard.releaseMicSoon(context)
    }

    fun stop() {
        recording = false
    }

    private fun record(listener: Listener) {
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            recording = false
            listener.onError("没有录音权限")
            return
        }
        val min = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val r = try {
            AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, maxOf(min, CHUNK * 4))
        } catch (e: Exception) {
            recording = false
            listener.onError("无法打开麦克风：${e.message}")
            return
        }
        if (r.state != AudioRecord.STATE_INITIALIZED) {
            r.release()
            recording = false
            listener.onError("麦克风初始化失败，请检查录音权限")
            return
        }
        recorder = r
        val out = ByteArrayOutputStream()
        val buf = ByteArray(CHUNK)
        val startedAt = System.currentTimeMillis()
        try {
            r.startRecording()
            while (recording && System.currentTimeMillis() - startedAt < MAX_MILLIS) {
                val n = r.read(buf, 0, buf.size)
                if (n > 0) {
                    out.write(buf, 0, n)
                    listener.onLevel(level(buf, n))
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "录音异常", e)
        } finally {
            recording = false
            runCatching { r.stop() }
            r.release()
            recorder = null
        }
        listener.onFinished(out.toByteArray())
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
        private const val TAG = "VoiceRecorder"

        /** PCM 时长（毫秒）。 */
        fun durationMs(pcm: ByteArray) = pcm.size / (SAMPLE_RATE * 2 / 1000)
    }
}
