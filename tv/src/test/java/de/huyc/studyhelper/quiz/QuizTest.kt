package de.huyc.studyhelper.quiz

import de.huyc.studyhelper.data.AnswerMode
import de.huyc.studyhelper.data.TestBank
import de.huyc.studyhelper.data.MathConfig
import de.huyc.studyhelper.data.Question
import de.huyc.studyhelper.data.Settings
import de.huyc.studyhelper.data.Subject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class QuizTest {

    private fun items(n: Int) = List(n) { QuizItem.of(MathGenerator.generate(MathConfig(), Random(it))) }

    @Test fun sessionNeedsRequiredCorrectAnswers() {
        val s = QuizSession(items(6), required = 2, wrongResets = false)
        assertEquals(QuizSession.Result.WRONG, s.answer(false))
        assertEquals(QuizSession.Result.NEXT, s.answer(true))
        assertEquals(QuizSession.Result.WRONG, s.answer(false))
        assertEquals(1, s.correctCount)
        assertEquals(QuizSession.Result.DONE, s.answer(true))
    }

    @Test fun wrongAnswerMovesToNextQuestion() {
        val list = items(6)
        val s = QuizSession(list, required = 2, wrongResets = false)
        val missed = s.current
        s.answer(false)
        assertTrue(s.current !== missed)
    }

    @Test fun sessionWrongResetsProgress() {
        val s = QuizSession(items(6), required = 2, wrongResets = true)
        s.answer(true)
        assertEquals(QuizSession.Result.RESET, s.answer(false))
        assertEquals(0, s.correctCount)
        assertEquals(QuizSession.Result.NEXT, s.answer(true))
        assertEquals(QuizSession.Result.DONE, s.answer(true))
    }

    @Test fun mathStaysWithinRangeAndNonNegative() {
        val r = Random(1)
        repeat(500) {
            val q = MathGenerator.generate(MathConfig(max = 10), r)
            val answer = q.answers.single().toInt()
            assertTrue(q.display, answer in 0..10)
            val (a, b) = Regex("(\\d+) . (\\d+)").find(q.display)!!.destructured
            assertTrue(a.toInt() <= 10 && b.toInt() <= 10)
        }
    }

    @Test fun mathChoiceHasFourDistinctOptionsIncludingAnswer() {
        val r = Random(2)
        repeat(200) {
            val item = QuizItem.of(MathGenerator.generate(MathConfig(max = 10, mode = AnswerMode.CHOICE), r), r)
            assertEquals(AnswerMode.CHOICE, item.mode)
            assertEquals(4, item.choices.toSet().size)
            assertEquals(item.question.answers.single(), item.choices[item.correctChoice])
            assertTrue(item.choices.all { it.toInt() >= 0 })
        }
    }

    @Test fun onlyEnabledSubjectsAreUsed() {
        val settings = Settings(subjects = setOf(Subject.ENGLISH))
        val items = QuizMaker(Random(3)).make(settings, TestBank.questions(), 9)
        assertEquals(9, items.size)
        assertTrue(items.all { it.question.subject == Subject.ENGLISH })
    }

    @Test fun hanziIsReadAloudNotPinyin() {
        val settings = Settings(subjects = setOf(Subject.HANZI))
        val items = QuizMaker(Random(4)).make(settings, TestBank.questions(), 10)
        assertTrue(items.all { it.question.subject == Subject.HANZI && it.mode == AnswerMode.VOICE })
    }

    @Test fun pinyinGeneratedFromHanziBank() {
        val settings = Settings(subjects = setOf(Subject.PINYIN))
        val items = QuizMaker(Random(5)).make(settings, TestBank.questions(), 5)
        items.forEach { item ->
            assertEquals(Subject.PINYIN, item.question.subject)
            assertEquals(4, item.choices.toSet().size)
            assertEquals(AnswerMatcher.tonedPinyin(item.question.display[0]), item.choices[item.correctChoice])
        }
    }

    @Test fun customChoiceQuestionIsShuffledWithCorrectIndex() {
        val q = Question("x", Subject.ENGLISH, "?", "🍎", listOf("apple"), AnswerMode.CHOICE, listOf("apple", "pear", "kiwi"))
        repeat(20) {
            val item = QuizItem.of(q, Random(it))
            assertEquals("apple", item.choices[item.correctChoice])
            assertEquals(3, item.choices.size)
        }
    }

    @Test fun choiceWithoutWrongOptionsFallsBackToVoice() {
        val q = Question("x", Subject.ENGLISH, "?", "🍎", listOf("apple"), AnswerMode.CHOICE, listOf("apple"))
        assertEquals(AnswerMode.VOICE, QuizItem.of(q).mode)
    }

    @Test fun fallsBackToMathWhenNothingElse() {
        val settings = Settings(subjects = setOf(Subject.ENGLISH))
        val items = QuizMaker(Random(9)).make(settings, emptyList(), 3)
        assertTrue(items.all { it.question.subject == Subject.MATH })
    }
}
