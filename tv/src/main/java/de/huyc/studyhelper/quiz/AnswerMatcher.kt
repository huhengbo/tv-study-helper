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
 * - 英语题只保留字母比对，并允许识别结果里包含答案（如「是 banana」）；
 *   离线模型英语偏弱，常错一两个字母（apple → appel），较长的词允许少量拼写差异。
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
        return (extractNumbers(heard) + extractNumbers(fixHomophoneDigits(heard))).contains(expected)
    }

    /** 识别器常把数字听成同音字（二→而/耳，四→死，九→酒）。 */
    private val digitHomophones = mapOf(
        '幺' to '一', '而' to '二', '儿' to '二', '耳' to '二', '尔' to '二',
        '山' to '三', '伞' to '三', '死' to '四', '思' to '四', '斯' to '四',
        '无' to '五', '吴' to '五', '流' to '六', '刘' to '六', '其' to '七', '起' to '七',
        '酒' to '九', '就' to '九',
    )
    private val numberFiller = Regex("^(答案|应该|我觉得|就|是|等于|得|等)+|[的啊呀呢吗啦哦]+$")

    /**
     * 整句话只有数字和同音字时才替换，避免「我就是山」这类句子被误当成数字；
     * 其他情况原样返回。
     */
    private fun fixHomophoneDigits(heard: String): String {
        val core = heard.filter { isHan(it) || it.isDigit() }.replace(numberFiller, "")
        if (core.isEmpty() || core.none { it in digitHomophones }) return heard
        if (!core.all { it in digitHomophones || it in digits || it in units || it.isDigit() }) return heard
        return core.map { digitHomophones[it] ?: it }.joinToString("")
    }

    /** 0~9999 的整数读法，用来给本地识别器生成数学题的热词。 */
    fun toChineseNumber(n: Int): String? {
        if (n !in 0..9999) return null
        val d = "零一二三四五六七八九"
        if (n < 10) return d[n].toString()
        if (n < 20) return "十" + if (n % 10 == 0) "" else d[n % 10].toString()
        val sb = StringBuilder()
        var zero = false
        for (u in listOf(1000 to "千", 100 to "百", 10 to "十", 1 to "")) {
            val v = n / u.first % 10
            if (v == 0) { if (sb.isNotEmpty()) zero = true; continue }
            if (zero) sb.append('零').also { zero = false }
            sb.append(d[v]).append(u.second)
        }
        return sb.toString()
    }

    private fun englishMatches(heard: String, answer: String): Boolean {
        val h = heard.lowercase().filter { it in 'a'..'z' }
        val a = answer.lowercase().filter { it in 'a'..'z' }
        if (a.isEmpty()) return false
        if (h.contains(a)) return true
        // 短词差一个字母就是另一个词（cat/hat），不放宽；按识别出的每个词和整句分别比较
        val allowed = when {
            a.length >= 7 -> 2
            a.length >= 4 -> 1
            else -> return false
        }
        val words = heard.lowercase().split(Regex("[^a-z]+")).filter { it.isNotEmpty() } + h
        return words.any { editDistance(it, a) <= allowed }
    }

    /** 编辑距离，相邻两个字母互换（appel/apple）也算一步。 */
    private fun editDistance(x: String, y: String): Int {
        val d = Array(x.length + 1) { i -> IntArray(y.length + 1) { j -> if (i == 0) j else if (j == 0) i else 0 } }
        for (i in 1..x.length) for (j in 1..y.length) {
            d[i][j] = minOf(d[i - 1][j] + 1, d[i][j - 1] + 1, d[i - 1][j - 1] + if (x[i - 1] == y[j - 1]) 0 else 1)
            if (i > 1 && j > 1 && x[i - 1] == y[j - 2] && x[i - 2] == y[j - 1]) d[i][j] = minOf(d[i][j], d[i - 2][j - 2] + 1)
        }
        return d[x.length][y.length]
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
            // 逐位报数（「一二」「零五」）按位拼起来，不当成数量级写法
            if (m.value.length >= 2 && m.value.all { it in digits }) {
                m.value.map { digits.getValue(it) }.joinToString("").toIntOrNull()?.let(result::add)
                return@forEach
            }
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
