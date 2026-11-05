package de.huyc.studyhelper.quiz

import de.huyc.studyhelper.data.AnswerLog
import de.huyc.studyhelper.data.MathConfig
import org.junit.Assert.assertEquals
import org.junit.Test

class AdaptiveMathTest {
    private val on = MathConfig(max = 30, adaptive = true)

    /** 从新到旧：[correct] 个答对放前面，其余答错。 */
    private fun logs(total: Int, correct: Int, time: Long = 1000) =
        List(total) { AnswerLog(time, "m", "1 + 1 = ?", "", it < correct, "MATH|VOICE|1 + 1 = ?|2") }

    @Test fun startsAtTheCapAndIgnoresStoredWhenOff() {
        assertEquals(30, AdaptiveMath.level(on, 0))
        assertEquals(30, AdaptiveMath.level(MathConfig(max = 30, adaptive = false), 15))
    }

    @Test fun storedLevelIsClampedToCap() {
        assertEquals(30, AdaptiveMath.level(on, 80))
        assertEquals(10, AdaptiveMath.level(on, 3))
    }

    @Test fun tooFewSamplesDoNotChangeLevel() {
        assertEquals(30, AdaptiveMath.next(on, 0, logs(9, 0), 0))
    }

    @Test fun strugglingLowersByOneStep() {
        assertEquals(25, AdaptiveMath.next(on, 0, logs(10, 6), 0))
    }

    @Test fun doingWellRaisesUpToCap() {
        assertEquals(30, AdaptiveMath.next(on, 25, logs(10, 10), 0))
        assertEquals(20, AdaptiveMath.next(on, 15, logs(10, 9), 0))
    }

    @Test fun middleAccuracyKeepsLevel() {
        assertEquals(20, AdaptiveMath.next(on, 20, logs(10, 7), 0))
    }

    @Test fun neverBelowMinimum() {
        assertEquals(10, AdaptiveMath.next(on, 10, logs(10, 0), 0))
    }

    @Test fun onlyAnswersSinceLastChangeCount() {
        assertEquals(20, AdaptiveMath.next(on, 20, logs(10, 10, time = 1000), since = 2000))
    }

    @Test fun nonMathAnswersAreIgnored() {
        val english = List(10) { AnswerLog(1000, "e", "apple", "", false, "ENGLISH|VOICE|apple|apple") }
        assertEquals(30, AdaptiveMath.next(on, 0, english, 0))
    }

    @Test fun capBelowMinimumDisablesAdapting() {
        val small = MathConfig(max = 5, adaptive = true)
        assertEquals(5, AdaptiveMath.level(small, 0))
        assertEquals(5, AdaptiveMath.next(small, 0, logs(10, 0), 0))
    }

    @Test fun effectiveConfigUsesLevelAsMax() {
        assertEquals(15, AdaptiveMath.effective(on, 15).max)
    }
}
