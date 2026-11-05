package de.huyc.studyhelper.voice

import android.view.KeyEvent

/** 小米蓝牙语音遥控器的语音键：内核上报 KEY_F5（scanCode 63），keylayout 未映射，到应用层是 KEYCODE_UNKNOWN。 */
object VoiceKey {
    const val SCAN_CODE = 63

    fun matches(event: KeyEvent): Boolean =
        event.scanCode == SCAN_CODE ||
            event.keyCode == KeyEvent.KEYCODE_VOICE_ASSIST ||
            event.keyCode == KeyEvent.KEYCODE_SEARCH
}
