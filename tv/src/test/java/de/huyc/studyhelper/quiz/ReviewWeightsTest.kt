package de.huyc.studyhelper.quiz

import de.huyc.studyhelper.data.AnswerLog
import de.huyc.studyhelper.data.Question
import de.huyc.studyhelper.data.Subject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReviewWeightsTest {
    private val now = 100L * DAY
    private val q = Question("a", Subject.ENGLISH, "?", "apple", listOf("apple"))

    private fun log(daysAgo: Int, correct: Boolean, key: String = q.key) =
        AnswerLog(now - daysAgo * DAY, "a", "apple", "", correct, key)

    /** 从新到旧：`true` 答对，`false` 答错。 */
    private fun weight(vararg results: Boolean, key: String = q.key) =
        ReviewWeights(results.mapIndexed { i, ok -> log(i + 1, ok, key) }, now).weight(q)

    @Test fun orderingWrongThenUnseenThenNormalThenMastered() {
        val lastWrong = weight(false)
        val recovering = weight(true, false)
        val unseen = ReviewWeights(emptyList(), now).weight(q)
        val normal = weight(true)
        val mastered = weight(true, true, true)
        assertTrue(lastWrong > recovering && recovering > unseen && unseen > normal && normal > mastered)
    }

    @Test fun twoCorrectAfterWrongIsAlmostBack() {
        assertEquals(ReviewWeights.ALMOST, weight(true, true, false), 0.0)
    }

    @Test fun masteredNeedsThreeInARow() {
        assertEquals(ReviewWeights.MASTERED, weight(true, true, true, false, false), 0.0)
        assertTrue(weight(true, true, false) > ReviewWeights.NORMAL)
    }

    @Test fun oldRecordsAreIgnored() {
        val w = ReviewWeights(listOf(log(31, false)), now).weight(q)
        assertEquals(ReviewWeights.UNSEEN, w, 0.0)
    }

    @Test fun onlyTheMostRecentRecordsCount() {
        // 最近 3 次答对，更早的 10 次都错：算掌握
        val results = BooleanArray(13) { it < 3 }
        assertEquals(ReviewWeights.MASTERED, weight(*results), 0.0)
    }

    @Test fun otherQuestionsAndKeylessLogsDoNotAffect() {
        val logs = listOf(log(1, false, key = "OTHER"), log(1, false, key = ""))
        assertEquals(ReviewWeights.UNSEEN, ReviewWeights(logs, now).weight(q), 0.0)
    }

    private companion object { const val DAY = 24 * 3600 * 1000L }
}
