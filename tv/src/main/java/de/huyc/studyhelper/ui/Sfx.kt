package de.huyc.studyhelper.ui

import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/** 答题用的短音效。没有音频资源文件，音色由 [SfxSynth] 合成，免版权也不占 APK 体积。 */
enum class Sound { CORRECT, WRONG, DONE }

/**
 * 播放 [Sound]。每个音效一个静态 [AudioTrack]，用到时才合成；走媒体音量，和朗读同一个流。
 * 音效初始化或播放失败不影响答题，静默跳过。
 */
class Sfx(private val enabled: Boolean) {
    private val tracks = HashMap<Sound, AudioTrack?>()

    fun play(sound: Sound) {
        if (!enabled) return
        val track = tracks.getOrPut(sound) { create(sound) } ?: return
        runCatching {
            track.stop()
            track.setPlaybackHeadPosition(0)
            track.play()
        }
    }

    fun release() {
        tracks.values.forEach { t -> runCatching { t?.release() } }
        tracks.clear()
    }

    private fun create(sound: Sound): AudioTrack? = runCatching {
        val pcm = SfxSynth.render(sound)
        @Suppress("DEPRECATION")
        AudioTrack(AudioManager.STREAM_MUSIC, SfxSynth.SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT, pcm.size * 2, AudioTrack.MODE_STATIC).also {
            it.write(pcm, 0, pcm.size)
        }
    }.getOrNull()
}

/** 用正弦波加泛音和指数衰减合成类似木琴、铃铛的音色。 */
object SfxSynth {
    const val SAMPLE_RATE = 22050

    /** (频率 Hz, 开始毫秒, 持续毫秒, 音量 0..1) */
    private data class Note(val hz: Double, val startMs: Int, val lengthMs: Int, val volume: Double = 0.5)

    private const val C5 = 523.25
    private const val E5 = 659.25
    private const val G5 = 783.99
    private const val C6 = 1046.5
    private const val A3 = 220.0
    private const val F3 = 174.61

    private fun notes(sound: Sound): List<Note> = when (sound) {
        Sound.CORRECT -> listOf(Note(C5, 0, 220), Note(E5, 90, 220), Note(G5, 180, 320))
        Sound.WRONG -> listOf(Note(A3, 0, 200, 0.4), Note(F3, 140, 280, 0.4))
        Sound.DONE -> listOf(Note(C5, 0, 260), Note(E5, 140, 260), Note(G5, 280, 260), Note(C6, 440, 520, 0.55))
    }

    fun render(sound: Sound): ShortArray {
        val list = notes(sound)
        val total = list.maxOf { it.startMs + it.lengthMs } * SAMPLE_RATE / 1000
        val mix = DoubleArray(total)
        for (n in list) {
            val start = n.startMs * SAMPLE_RATE / 1000
            val len = n.lengthMs * SAMPLE_RATE / 1000
            for (i in 0 until len) {
                val t = i.toDouble() / SAMPLE_RATE
                val attack = (i / (0.005 * SAMPLE_RATE)).coerceAtMost(1.0)
                val decay = exp(-4.5 * t / (n.lengthMs / 1000.0))
                val fade = ((len - i) / (0.02 * SAMPLE_RATE)).coerceAtMost(1.0) // 结尾淡出，避免爆音
                val wave = sin(2 * PI * n.hz * t) + 0.3 * sin(2 * PI * n.hz * 2 * t) + 0.1 * sin(2 * PI * n.hz * 3 * t)
                if (start + i < total) mix[start + i] += wave * attack * decay * fade * n.volume
            }
        }
        val peak = mix.maxOf { kotlin.math.abs(it) }.coerceAtLeast(1.0)
        return ShortArray(total) { (mix[it] / peak * 0.8 * Short.MAX_VALUE).toInt().toShort() }
    }
}
