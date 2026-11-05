package de.huyc.studyhelper.quiz

import de.huyc.studyhelper.data.Subject
import net.sourceforge.pinyin4j.PinyinHelper
import net.sourceforge.pinyin4j.format.HanyuPinyinCaseType
import net.sourceforge.pinyin4j.format.HanyuPinyinOutputFormat
import net.sourceforge.pinyin4j.format.HanyuPinyinToneType
import net.sourceforge.pinyin4j.format.HanyuPinyinVCharType

/**
 * 判断语音题是否答对（选择题按选项下标判断，不经过这里）。识别结果常带标点、语气词或同音字，所以：
 * - 认字题按拼音（不带声调）比对，「山」读成识别出的「删」也算对；
 * - 数学题把中文数字转成阿拉伯数字后比对；
 * - 英语题只保留字母比对，并允许识别结果里包含答案（如「是 banana」）。
 */
object AnswerMatcher {

    /** 每个科目一条判分规则；新增科目时编译器会要求在这里补上。 */
    fun isCorrect(subject: Subject, heard: String, answers: List<String>): Boolean {
        if (heard.isBlank()) return false
        return when (subject) {
            // 拼音科目的语音题是「看字读出来」，与认字同样按读音判断
            Subject.HANZI, Subject.PINYIN -> answers.any { hanziMatches(heard, it) }
            Subject.MATH -> answers.any { numberMatches(heard, it) }
            Subject.ENGLISH -> answers.any { englishMatches(heard, it) }
        }
    }

    private fun hanziMatches(heard: String, answer: String): Boolean {
        val target = answer.filter { isHan(it) }
        if (target.isEmpty()) return false
        val chars = heard.filter { isHan(it) }
        if (chars.contains(target)) return true
        // 认单字时孩子常说「山，山」「是山」，任意位置出现同音的连续片段即算对
        val targetPy = target.map { pinyins(it) }
        if (targetPy.any { it.isEmpty() }) return false
        return chars.windowed(target.length).any { window ->
            window.indices.all { i -> pinyins(window[i]).any { it in targetPy[i] } }
        }
    }

    private fun numberMatches(heard: String, answer: String): Boolean {
        val expected = answer.trim().toIntOrNull() ?: return heard.contains(answer.trim())
        return extractNumbers(heard).contains(expected)
    }

    private fun englishMatches(heard: String, answer: String): Boolean {
        val h = heard.lowercase().filter { it in 'a'..'z' }
        val a = answer.lowercase().filter { it in 'a'..'z' }
        return a.isNotEmpty() && h.contains(a)
    }

    fun isHan(c: Char) = c in '一'..'鿿'

    private val format = HanyuPinyinOutputFormat().apply {
        caseType = HanyuPinyinCaseType.LOWERCASE
        toneType = HanyuPinyinToneType.WITHOUT_TONE
        vCharType = HanyuPinyinVCharType.WITH_V
    }

    /** 多音字返回全部读音。 */
    fun pinyins(c: Char): Set<String> =
        runCatching { PinyinHelper.toHanyuPinyinStringArray(c, format)?.toSet() }.getOrNull() ?: emptySet()

    /** 带声调的拼音（ā á ǎ à），用于拼音选择题的选项。 */
    fun tonedPinyin(c: Char): String? {
        val f = HanyuPinyinOutputFormat().apply {
            caseType = HanyuPinyinCaseType.LOWERCASE
            toneType = HanyuPinyinToneType.WITH_TONE_MARK
            vCharType = HanyuPinyinVCharType.WITH_U_UNICODE
        }
        val all = runCatching { PinyinHelper.toHanyuPinyinStringArray(c, f) }.getOrNull() ?: return null
        // 多音字里轻声读音（无声调符号）往往排在前面，如「子」zi/zǐ，优先取带声调的
        val pick = all.firstOrNull { py -> py.any { it.code > 0x7F && it != 'ü' } } ?: all.firstOrNull() ?: return null
        return pick.map { BREVE_TO_CARON[it] ?: it }.joinToString("")
    }

    /** pinyin4j 用短音符（ă ĕ ĭ ŏ ŭ）表示第三声，汉语拼音规范是抑扬符（ǎ ě ǐ ǒ ǔ）。 */
    private val BREVE_TO_CARON = mapOf('ă' to 'ǎ', 'ĕ' to 'ě', 'ĭ' to 'ǐ', 'ŏ' to 'ǒ', 'ŭ' to 'ǔ')

    /** 提取文本中的所有整数，支持「12」「十二」「一百零五」「负三」。 */
    fun extractNumbers(text: String): List<Int> {
        val result = mutableListOf<Int>()
        Regex("-?\\d+").findAll(text).forEach { m -> m.value.toIntOrNull()?.let(result::add) }
        Regex("负?[零一二两三四五六七八九十百千]+").findAll(text).forEach { m ->
            val negative = m.value.startsWith("负")
            parseChinese(m.value.removePrefix("负"))?.let { result += if (negative) -it else it }
        }
        return result
    }

    private val digits = mapOf('零' to 0, '一' to 1, '二' to 2, '两' to 2, '三' to 3, '四' to 4,
        '五' to 5, '六' to 6, '七' to 7, '八' to 8, '九' to 9)
    private val units = mapOf('十' to 10, '百' to 100, '千' to 1000)

    private fun parseChinese(s: String): Int? {
        if (s.isEmpty()) return null
        var total = 0
        var current = 0
        for (c in s) {
            val d = digits[c]
            val u = units[c]
            when {
                d != null -> current = d
                u != null -> { total += (if (current == 0) 1 else current) * u; current = 0 }
                else -> return null
            }
        }
        return total + current
    }
}
