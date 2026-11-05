package de.huyc.studyhelper.core

import android.content.Context
import android.media.AudioManager
import android.os.SystemClock
import android.view.KeyEvent

/**
 * 暂停/恢复其他 App 的播放：
 * 1. 向当前媒体会话发送 MEDIA_PAUSE / MEDIA_PLAY（不用 PLAY_PAUSE，避免状态反转）；
 * 2. 申请临时音频焦点，不支持媒体键的播放器多数也会因失去焦点而暂停，释放焦点后自动恢复。
 */
class MediaControl(context: Context) {
    private val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val focusListener = AudioManager.OnAudioFocusChangeListener { }
    private var holdingFocus = false

    fun pauseOthers() {
        sendKey(KeyEvent.KEYCODE_MEDIA_PAUSE)
        @Suppress("DEPRECATION")
        holdingFocus = audio.requestAudioFocus(focusListener, AudioManager.STREAM_MUSIC,
            AudioManager.AUDIOFOCUS_GAIN_TRANSIENT) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    fun resumeOthers(sendPlay: Boolean) {
        if (holdingFocus) {
            @Suppress("DEPRECATION")
            audio.abandonAudioFocus(focusListener)
            holdingFocus = false
        }
        if (sendPlay) sendKey(KeyEvent.KEYCODE_MEDIA_PLAY)
    }

    private fun sendKey(code: Int) {
        val now = SystemClock.uptimeMillis()
        audio.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, code, 0))
        audio.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, code, 0))
    }
}
