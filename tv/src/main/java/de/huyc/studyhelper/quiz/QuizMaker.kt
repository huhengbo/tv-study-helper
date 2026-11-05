package de.huyc.studyhelper.quiz

import de.huyc.studyhelper.data.Question
import de.huyc.studyhelper.data.Settings
import kotlin.random.Random

/**
 * 组卷：按设置里启用的科目轮流出题，每个科目的题来自题库和该科目的自动出题器。
 * 一个科目既没有题库题也没有可用的出题器时跳过；全部为空时退回数学加减法。
 */
class QuizMaker(private val random: Random = Random.Default) {

    fun make(settings: Settings, bank: List<Question>, count: Int = settings.questionCount): List<QuizItem> {
        val sources = settings.subjects.sortedBy { it.ordinal }.mapNotNull { subject ->
            val pool = bank.filter { it.enabled && it.subject == subject }
            val generators = Generators.enabledFor(subject, settings)
            if (pool.isEmpty() && generators.isEmpty()) null else Source(pool, generators)
        }
        if (sources.isEmpty()) return List(count) { QuizItem.of(MathGenerator.generate(settings.math, random), random) }
        val offset = random.nextInt(sources.size)
        return List(count) { i ->
            // 科目轮流出现，避免连续都是同一类
            val q = sources[(offset + i) % sources.size].next(settings, bank)
                ?: MathGenerator.generate(settings.math, random)
            QuizItem.of(q, random)
        }
    }

    /** 一个科目的题源：题库按洗牌顺序不重复地取，取完再洗；有出题器时与题库约各占一半。 */
    private inner class Source(private val pool: List<Question>, private val generators: List<QuestionGenerator>) {
        private val queue = ArrayDeque<Question>()

        fun next(settings: Settings, bank: List<Question>): Question? {
            val useGenerator = generators.isNotEmpty() && (pool.isEmpty() || random.nextBoolean())
            if (useGenerator) generators.random(random).generate(settings, bank, random)?.let { return it }
            if (pool.isEmpty()) return null
            if (queue.isEmpty()) queue.addAll(pool.shuffled(random))
            return queue.removeFirst()
        }
    }
}
