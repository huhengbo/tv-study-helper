package de.huyc.studyhelper.diag

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.PixelFormat
import android.provider.Settings
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Bundle
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ScrollView
import android.widget.TextView
import de.huyc.studyhelper.voice.VoiceKey
import de.huyc.studyhelper.voice.Wav
import de.huyc.studyhelper.voice.XiaoaiGuard
import java.io.File
import kotlin.concurrent.thread
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * 语音诊断页：按住遥控器语音键录音，松开后显示录到的音量统计，并保存 wav 便于回放核对。
 * 方向键「上」切换录音源，用于排查不同机型的麦克风路由。
 *
 * 按语音键时系统蓝牙服务会拉起小爱同学浮窗（TYPE_PHONE）抢走焦点，
 * 所以有悬浮窗权限时界面放在更高层级的系统窗口里，保证按键松开事件仍然发给本页。
 */
class VoiceDiagActivity : Activity() {

    private lateinit var output: TextView
    private val sources = intArrayOf(
        MediaRecorder.AudioSource.VOICE_RECOGNITION,
        MediaRecorder.AudioSource.MIC,
        MediaRecorder.AudioSource.DEFAULT,
    )
    private var sourceIndex = 0
    @Volatile private var recording = false
    private var worker: Thread? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        output = TextView(this).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            setPadding(64, 48, 64, 48)
            gravity = Gravity.TOP
        }
        val root = KeyRoot(this).apply {
            setBackgroundColor(Color.rgb(30, 34, 48))
            addView(ScrollView(context).apply { addView(output) })
        }
        if (Settings.canDrawOverlays(this)) {
            overlay = root
            (getSystemService(WINDOW_SERVICE) as WindowManager).addView(root, WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_SYSTEM_ERROR,
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON, PixelFormat.OPAQUE,
            ))
            root.requestFocus()
        } else {
            setContentView(root)
        }
        log("语音诊断：按住语音键说话，松开查看结果；确认键 回放；方向键上 切换录音源；方向键下 开关「让小爱让出麦克风」")
        log("悬浮窗权限：" + Settings.canDrawOverlays(this))
        log("录音权限：" + (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED))
        val am = getSystemService(AUDIO_SERVICE) as AudioManager
        am.getDevices(AudioManager.GET_DEVICES_INPUTS).forEach {
            log("输入设备 id=${it.id} type=${it.type} name=${it.productName}")
        }
        log("当前录音源：${sources[sourceIndex]}，让小爱让出麦克风：$releaseXiaoai")
    }

    private var releaseXiaoai = true
    @Volatile private var lastPcm: ByteArray? = null

    private var overlay: KeyRoot? = null

    private inner class KeyRoot(context: Context) : FrameLayout(context) {
        init { isFocusable = true; isFocusableInTouchMode = true }
        override fun dispatchKeyEvent(event: KeyEvent): Boolean =
            handleKey(event) || super.dispatchKeyEvent(event)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean = handleKey(event) || super.dispatchKeyEvent(event)

    private fun handleKey(event: KeyEvent): Boolean {
        Log.d(TAG, "key action=${event.action} code=${event.keyCode} scan=${event.scanCode} repeat=${event.repeatCount} canceled=${event.isCanceled}")
        if (VoiceKey.matches(event)) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) startRecord()
            if (event.action == KeyEvent.ACTION_UP) {
                if (event.isCanceled) log("（松开事件被系统取消：焦点被抢走）") else stopRecord()
            }
            return true
        }
        if (event.action == KeyEvent.ACTION_DOWN && event.keyCode == KeyEvent.KEYCODE_BACK) {
            finish(); return true
        }
        if (event.action == KeyEvent.ACTION_DOWN && event.keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
            releaseXiaoai = !releaseXiaoai
            log("让小爱让出麦克风：$releaseXiaoai")
            return true
        }
        if (event.action == KeyEvent.ACTION_DOWN && event.keyCode == KeyEvent.KEYCODE_DPAD_CENTER && event.repeatCount == 0) {
            replay(); return true
        }
        if (event.action == KeyEvent.ACTION_DOWN && event.keyCode == KeyEvent.KEYCODE_DPAD_UP) {
            sourceIndex = (sourceIndex + 1) % sources.size
            log("切换录音源：${sources[sourceIndex]}")
            return true
        }
        return false
    }

    // 诊断页本来就是排查权限和麦克风用的：没有权限时 AudioRecord 抛出的异常会打印在页面上
    @SuppressLint("MissingPermission")
    private fun startRecord() {
        if (recording) return
        recording = true
        val source = sources[sourceIndex]
        log("▶ 开始录音 source=$source")
        worker = thread(name = "voice-diag") {
            val rate = 16000
            val min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val recorder = try {
                AudioRecord(source, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, 3200))
            } catch (e: Exception) {
                post("创建 AudioRecord 失败：$e"); recording = false; return@thread
            }
            if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                post("AudioRecord 未初始化 state=${recorder.state}"); recorder.release(); recording = false; return@thread
            }
            val pcm = java.io.ByteArrayOutputStream()
            val buf = ByteArray(3200)
            var reads = 0; var failed = 0
            val startedAt = System.currentTimeMillis()
            try {
                recorder.startRecording()
                if (releaseXiaoai) XiaoaiGuard.releaseMicSoon(this@VoiceDiagActivity)
                val routed: AudioDeviceInfo? = if (android.os.Build.VERSION.SDK_INT >= 24) recorder.routedDevice else null
                post("录音状态=${recorder.recordingState} 路由设备=${routed?.type}/${routed?.productName}")
                while (recording && System.currentTimeMillis() - startedAt < 15_000) {
                    val n = recorder.read(buf, 0, buf.size)
                    if (n > 0) { pcm.write(buf, 0, n); reads++ } else failed++
                }
            } finally {
                runCatching { recorder.stop() }
                recorder.release()
            }
            val bytes = pcm.toByteArray()
            lastPcm = bytes
            val file = File(filesDir, "diag_${System.currentTimeMillis()}.wav")
            runCatching { Wav.write(file, bytes, rate) }.onFailure { post("保存失败：$it") }
            post("■ 结束：${bytes.size / 32} ms，读取成功 $reads 次/失败 $failed 次，${stats(bytes)}\n  已保存 ${file.absolutePath}")
        }
    }

    private fun replay() {
        val pcm = lastPcm ?: return log("还没有录音")
        log("▶ 回放 ${pcm.size / 32} ms")
        thread(name = "voice-replay") {
            val track = AudioTrack(AudioManager.STREAM_MUSIC, 16000, AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT, pcm.size, AudioTrack.MODE_STATIC)
            track.write(pcm, 0, pcm.size)
            track.play()
            Thread.sleep(pcm.size / 32L + 300)
            track.release()
        }
    }

    private fun stopRecord() {
        recording = false
    }

    private fun stats(pcm: ByteArray): String {
        if (pcm.size < 2) return "无数据"
        var peak = 0; var sum = 0.0; var nonZero = 0
        var i = 0
        while (i + 1 < pcm.size) {
            val s = (pcm[i].toInt() and 0xff) or (pcm[i + 1].toInt() shl 8)
            val a = abs(s.toShort().toInt())
            if (a > peak) peak = a
            if (a != 0) nonZero++
            sum += a.toDouble() * a
            i += 2
        }
        val rms = sqrt(sum / (pcm.size / 2))
        return "峰值=$peak 均方根=${rms.toInt()} 非零样本=${nonZero * 100 / (pcm.size / 2)}%"
    }

    private fun post(msg: String) = runOnUiThread { log(msg) }

    private fun log(msg: String) {
        Log.i(TAG, msg)
        output.append(msg + "\n")
    }

    override fun onDestroy() {
        recording = false
        overlay?.let { (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(it) }
        super.onDestroy()
    }

    companion object { private const val TAG = "SHDiag" }
}
