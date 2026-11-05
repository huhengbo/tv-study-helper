package de.huyc.studyhelper.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class SfxSynthTest {
    @Test fun everySoundRendersAudibleBoundedAudio() {
        for (sound in Sound.entries) {
            val pcm = SfxSynth.render(sound)
            val peak = pcm.maxOf { abs(it.toInt()) }
            assertTrue("$sound 时长 ${pcm.size}", pcm.size in SfxSynth.SAMPLE_RATE / 4..SfxSynth.SAMPLE_RATE * 2)
            assertTrue("$sound 峰值 $peak", peak in 8000..Short.MAX_VALUE.toInt())
        }
    }

    @Test fun soundsEndSilentlyToAvoidClicks() {
        for (sound in Sound.entries) assertEquals(0, SfxSynth.render(sound).last().toInt())
    }

    @Test fun doneIsLongerThanFeedbackSounds() {
        assertTrue(SfxSynth.render(Sound.DONE).size > SfxSynth.render(Sound.CORRECT).size)
    }
}
