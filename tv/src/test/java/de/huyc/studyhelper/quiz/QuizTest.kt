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

    private fun english(id: String) = Question(id, Subject.ENGLISH, "?", id, listOf(id))

    @Test fun heavierQuestionsComeFirstMoreOften() {
        val bank = List(10) { english("w$it") }
        val weight: (Question) -> Double = { if (it.id == "w0") 20.0 else 1.0 }
        val settings = Settings(subjects = setOf(Subject.ENGLISH), math = MathConfig(enabled = false))
        val first = (0 until 200).count { seed ->
            QuizMaker(Random(seed), weight).make(settings, bank, 1).single().question.id == "w0"
        }
        // 权重 20 对 9 个权重 1：期望约 69%；普通洗牌只有 10%
        assertTrue("w0 排第一 $first/200 次", first > 100)
    }

    @Test fun everyQuestionIsAskedOncePerPass() {
        val bank = List(6) { english("w$it") }
        val settings = Settings(subjects = setOf(Subject.ENGLISH))
        val ids = QuizMaker(Random(1), { if (it.id == "w3") 9.0 else 1.0 }).make(settings, bank, 6).map { it.question.id }
        assertEquals(bank.map { it.id }.toSet(), ids.toSet())
    }

    @Test fun pinyinCandidatesFollowWeights() {
        val bank = TestBank.questions()
        val settings = Settings(subjects = setOf(Subject.PINYIN))
        val target = QuizMaker(Random(0)).make(settings, bank, 1).single().question
        val favored = (0 until 100).count { seed ->
            QuizMaker(Random(seed), { if (it.key == target.key) 50.0 else 1.0 }).make(settings, bank, 1).single().question.key == target.key
        }
        val plain = (0 until 100).count { seed -> QuizMaker(Random(seed)).make(settings, bank, 1).single().question.key == target.key }
        assertTrue("加权 $favored 次 > 不加权 $plain 次", favored > plain)
    }

    @Test fun wrongQuestionIsRetriedAfterTwoOthers() {
        val list = items(8)
        val s = QuizSession(list, required = 5, wrongResets = false, retryWrong = true)
        val missed = s.current
        s.answer(false)
        s.answer(true) // 第 2 题
        s.answer(true) // 第 3 题
        assertEquals(missed, s.current) // 第 4 题回炉
    }

    @Test fun wrongQuestionIsRetriedOnlyOnce() {
        val s = QuizSession(items(8), required = 6, wrongResets = false, retryWrong = true)
        val missed = s.current
        s.answer(false); s.answer(true); s.answer(true)
        assertEquals(missed, s.current)
        s.answer(false) // 回炉又答错
        val rest = List(4) { s.current.also { s.answer(true) } }
        assertTrue(rest.none { it == missed })
    }

    @Test fun noRetryWhenDisabled() {
        val s = QuizSession(items(8), required = 5, wrongResets = false, retryWrong = false)
        val missed = s.current
        s.answer(false)
        repeat(4) { assertTrue(s.current != missed); s.answer(true) }
    }
}
