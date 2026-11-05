package de.huyc.studyhelper.quiz

import de.huyc.studyhelper.data.AnswerMode
import de.huyc.studyhelper.data.MathConfig
import de.huyc.studyhelper.data.Question
import de.huyc.studyhelper.data.Settings
import de.huyc.studyhelper.data.Subject
import kotlin.random.Random

/**
 * 自动出题器：按设置即时生成某个科目的题，与题库里的题混合出现。
 * 新增出题器（如「比大小」「看图选单词」）：实现此接口并加入 [Generators.all]。
 */
interface QuestionGenerator {
    val subject: Subject
    fun isEnabled(settings: Settings): Boolean
    /** 返回 null 表示当前条件下出不了题（如题库里没有可用的字）。 */
    fun generate(settings: Settings, bank: List<Question>, random: Random): Question?
}

object Generators {
    val all: List<QuestionGenerator> = listOf(MathGenerator, PinyinGenerator)

    fun enabledFor(subject: Subject, settings: Settings) = all.filter { it.subject == subject && it.isEnabled(settings) }
}

/** 加减法：结果不为负、不超过范围；可出语音题或四选一。 */
object MathGenerator : QuestionGenerator {
    override val subject = Subject.MATH

    override fun isEnabled(settings: Settings) = settings.math.enabled

    override fun generate(settings: Settings, bank: List<Question>, random: Random) = generate(settings.math, random)

    fun generate(config: MathConfig, random: Random): Question {
        val max = config.max.coerceAtLeast(5)
        val add = when {
            config.add && config.sub -> random.nextBoolean()
            else -> config.add || !config.sub
        }
        val (a, b, answer) = if (add) {
            val sum = random.nextInt(2, max + 1)
            val a = random.nextInt(1, sum)
            Triple(a, sum - a, sum)
        } else {
            val a = random.nextInt(2, max + 1)
            val b = random.nextInt(1, a + 1)
            Triple(a, b, a - b)
        }
        val op = if (add) "+" else "−"
        val choices = if (config.mode == AnswerMode.CHOICE) nearbyNumbers(answer, max, random) else emptyList()
        return Question(
            id = "math-$a$op$b",
            subject = Subject.MATH,
            prompt = if (config.mode == AnswerMode.CHOICE) "算一算，选出得数" else Subject.MATH.defaultPrompt,
            display = "$a $op $b = ?",
            answers = listOf(answer.toString()),
            mode = config.mode,
            choices = choices,
            speak = "$a ${if (add) "加" else "减"} $b 等于几？",
        )
    }

    /** 正确答案加 3 个相近的干扰项（不为负）。 */
    private fun nearbyNumbers(answer: Int, max: Int, random: Random): List<String> {
        val pool = (maxOf(0, answer - 4)..minOf(max, answer + 4)).filter { it != answer }.shuffled(random)
        val wrong = (pool + (0..max).filter { it != answer && it !in pool }.shuffled(random)).take(3)
        return (wrong + answer).map { it.toString() }
    }
}

/** 拼音四选一：从认字题库里取单字，干扰项为其他字的拼音（参考 kidslock 的认字解锁）。 */
object PinyinGenerator : QuestionGenerator {
    override val subject = Subject.PINYIN

    override fun isEnabled(settings: Settings) = settings.pinyinAuto

    override fun generate(settings: Settings, bank: List<Question>, random: Random): Question? {
        val chars = bank.filter { it.subject == Subject.HANZI && it.enabled && it.display.length == 1 }
            .map { it.display[0] }.distinct()
        if (chars.size < 4) return null
        val c = chars.random(random)
        val right = AnswerMatcher.tonedPinyin(c) ?: return null
        val wrong = chars.asSequence().filter { it != c }.mapNotNull(AnswerMatcher::tonedPinyin)
            .filter { it != right }.distinct().shuffled(random).take(3).toList()
        if (wrong.size < 3) return null
        return Question(
            id = "pinyin-$c",
            subject = Subject.PINYIN,
            prompt = Subject.PINYIN.defaultPrompt,
            display = c.toString(),
            answers = listOf(right),
            mode = AnswerMode.CHOICE,
            choices = wrong + right,
            speak = "这个字的拼音是哪一个？",
        )
    }
}
