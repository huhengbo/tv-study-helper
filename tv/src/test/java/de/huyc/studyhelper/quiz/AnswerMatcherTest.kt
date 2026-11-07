package de.huyc.studyhelper.quiz

import de.huyc.studyhelper.data.Subject.ENGLISH
import de.huyc.studyhelper.data.Subject.HANZI
import de.huyc.studyhelper.data.Subject.MATH
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AnswerMatcherTest {

    @Test fun hanziAcceptsSameCharWithPunctuationAndFiller() {
        assertTrue(AnswerMatcher.isCorrect(HANZI, "山。", listOf("山")))
        assertTrue(AnswerMatcher.isCorrect(HANZI, "是山", listOf("山")))
    }

    @Test fun hanziAcceptsHomophoneButNotOtherSound() {
        assertTrue(AnswerMatcher.isCorrect(HANZI, "删", listOf("山")))
        assertTrue(AnswerMatcher.isCorrect(HANZI, "衫", listOf("山")))
        assertFalse(AnswerMatcher.isCorrect(HANZI, "水", listOf("山")))
    }

    @Test fun hanziMultiCharWord() {
        assertTrue(AnswerMatcher.isCorrect(HANZI, "妈妈", listOf("妈妈")))
        assertFalse(AnswerMatcher.isCorrect(HANZI, "爸爸", listOf("妈妈")))
    }

    @Test fun mathAcceptsDigitsAndChineseNumerals() {
        assertTrue(AnswerMatcher.isCorrect(MATH, "12", listOf("12")))
        assertTrue(AnswerMatcher.isCorrect(MATH, "十二。", listOf("12")))
        assertTrue(AnswerMatcher.isCorrect(MATH, "等于二十", listOf("20")))
        assertTrue(AnswerMatcher.isCorrect(MATH, "一百零五", listOf("105")))
        assertFalse(AnswerMatcher.isCorrect(MATH, "十三", listOf("12")))
        assertFalse(AnswerMatcher.isCorrect(MATH, "", listOf("12")))
    }

    @Test fun englishIgnoresCaseSpacesAndExtras() {
        assertTrue(AnswerMatcher.isCorrect(ENGLISH, "Banana.", listOf("banana")))
        assertTrue(AnswerMatcher.isCorrect(ENGLISH, "it is a banana", listOf("banana")))
        assertTrue(AnswerMatcher.isCorrect(ENGLISH, "ice cream", listOf("ice cream")))
        assertFalse(AnswerMatcher.isCorrect(ENGLISH, "apple", listOf("banana")))
    }

    @Test fun englishToleratesSmallMisspellingInLongerWords() {
        assertTrue(AnswerMatcher.isCorrect(ENGLISH, "appel", listOf("apple")))
        assertTrue(AnswerMatcher.isCorrect(ENGLISH, "bikes", listOf("bike")))
        assertTrue(AnswerMatcher.isCorrect(ENGLISH, "it's a elefant", listOf("elephant")))
        assertTrue(AnswerMatcher.isCorrect(ENGLISH, "ice crem", listOf("ice cream")))
        // 短词不放宽，明显不同的词也不算
        assertFalse(AnswerMatcher.isCorrect(ENGLISH, "hat", listOf("cat")))
        assertFalse(AnswerMatcher.isCorrect(ENGLISH, "fuck.", listOf("bike")))
        assertFalse(AnswerMatcher.isCorrect(ENGLISH, "飒飒", listOf("sun")))
    }

    @Test fun mathToleratesHomophoneAndDigitByDigit() {
        assertTrue(AnswerMatcher.isCorrect(MATH, "而", listOf("2")))
        assertTrue(AnswerMatcher.isCorrect(MATH, "答案是死。", listOf("4")))
        assertTrue(AnswerMatcher.isCorrect(MATH, "幺二", listOf("12")))
        assertTrue(AnswerMatcher.isCorrect(MATH, "一二", listOf("12")))
        assertTrue(AnswerMatcher.isCorrect(MATH, "十酒", listOf("19")))
        // 带有其他字的句子不做同音替换
        assertFalse(AnswerMatcher.isCorrect(MATH, "我不知道山在哪", listOf("3")))
        assertFalse(AnswerMatcher.isCorrect(MATH, "而", listOf("3")))
    }

    @Test fun toChineseNumber() {
        assertEquals("七", AnswerMatcher.toChineseNumber(7))
        assertEquals("十", AnswerMatcher.toChineseNumber(10))
        assertEquals("十二", AnswerMatcher.toChineseNumber(12))
        assertEquals("二十五", AnswerMatcher.toChineseNumber(25))
        assertEquals("一百零五", AnswerMatcher.toChineseNumber(105))
        assertEquals("一百二十", AnswerMatcher.toChineseNumber(120))
        assertEquals(null, AnswerMatcher.toChineseNumber(-1))
    }

    @Test fun extractNumbers() {
        assertEquals(listOf(7, 15), AnswerMatcher.extractNumbers("7和十五"))
        assertEquals(listOf(2), AnswerMatcher.extractNumbers("两"))
    }

    @Test fun tonedPinyin() {
        assertEquals("shān", AnswerMatcher.tonedPinyin('山'))
        // pinyin4j 第三声输出的是短音符（ŭ），必须纠正为抑扬符（ǔ）
        assertEquals("tǔ", AnswerMatcher.tonedPinyin('土'))
        assertEquals("mǎ", AnswerMatcher.tonedPinyin('马'))
        assertEquals("nǐ", AnswerMatcher.tonedPinyin('你'))
        assertEquals("wǒ", AnswerMatcher.tonedPinyin('我'))
        assertEquals("zǐ", AnswerMatcher.tonedPinyin('子'))
    }
}

