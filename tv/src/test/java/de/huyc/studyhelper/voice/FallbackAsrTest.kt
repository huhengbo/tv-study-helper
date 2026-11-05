package de.huyc.studyhelper.voice

import de.huyc.studyhelper.data.SpeechLanguage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class FallbackAsrTest {

    private class Fake(private val result: () -> String) : AsrProvider {
        var calls = 0
        var lastHints: List<String> = emptyList()
        override fun recognize(pcm: ByteArray, language: SpeechLanguage, hints: List<String>): String {
            calls++
            lastHints = hints
            return result()
        }
    }

    private fun fallback(online: AsrProvider, offline: AsrProvider, connected: Boolean) =
        FallbackAsr(online, offline, isOnline = { connected }, log = {})

    @Test
    fun usesOnlineWhenItWorks() {
        val online = Fake { "苹果" }
        val offline = Fake { "离线" }
        assertEquals("苹果", fallback(online, offline, true).recognize(ByteArray(0), SpeechLanguage.CHINESE, listOf("苹果")))
        assertEquals(0, offline.calls)
        assertEquals(listOf("苹果"), online.lastHints)
    }

    @Test
    fun fallsBackWhenOnlineServiceFails() {
        val online = Fake { throw VoiceServiceException("火山识别失败：鉴权失败") }
        val offline = Fake { "apple" }
        assertEquals("apple", fallback(online, offline, true).recognize(ByteArray(0), SpeechLanguage.ENGLISH, listOf("apple")))
        assertEquals(1, online.calls)
        assertEquals(listOf("apple"), offline.lastHints)
    }

    @Test
    fun skipsOnlineWithoutNetwork() {
        val online = Fake { "不该调用" }
        val offline = Fake { "三" }
        assertEquals("三", fallback(online, offline, false).recognize(ByteArray(0), SpeechLanguage.CHINESE))
        assertEquals(0, online.calls)
    }

    @Test
    fun programmingErrorsAreNotHidden() {
        val online = Fake { throw IllegalStateException("bug") }
        val offline = Fake { "离线" }
        val error = runCatching { fallback(online, offline, true).recognize(ByteArray(0), SpeechLanguage.CHINESE) }.exceptionOrNull()
        assertTrue(error is IllegalStateException)
        assertEquals(0, offline.calls)
    }

    @Test
    fun timeoutIsANetworkFailure() {
        val online = Fake { throw IOException("timeout") }
        val offline = Fake { "离线" }
        assertEquals("离线", fallback(online, offline, true).recognize(ByteArray(0), SpeechLanguage.CHINESE))
    }
}
