package de.huyc.studyhelper.quiz

import de.huyc.studyhelper.data.AnswerLog
import de.huyc.studyhelper.data.Question

/**
 * 按作答记录给题目算出题权重：答错的题更常出现，连续答对几次后淡出。
 * 只看每道题（[Question.key]）最近 [WINDOW] 条、[DAYS] 天内的记录；[logs] 按时间从新到旧。
 */
class ReviewWeights(logs: List<AnswerLog>, now: Long) {
    private val byKey: Map<String, List<Boolean>> = logs.asSequence()
        .filter { it.key.isNotEmpty() && now - it.time <= DAYS * DAY_MS }
        .groupBy({ it.key }, { it.correct })
        .mapValues { it.value.take(WINDOW) }

    fun weight(q: Question): Double {
        val recent = byKey[q.key] ?: return UNSEEN
        if (!recent.first()) return LAST_WRONG
        val streak = recent.takeWhile { it }.size
        return when {
            streak >= MASTERED_STREAK -> MASTERED
            streak == recent.size -> NORMAL // 没答错过，但次数还不够算掌握
            streak == 1 -> RECOVERING
            else -> ALMOST
        }
    }

    companion object {
        const val WINDOW = 6
        const val DAYS = 30
        const val MASTERED_STREAK = 3
        const val UNSEEN = 1.5
        const val LAST_WRONG = 4.0
        const val RECOVERING = 2.5
        const val ALMOST = 1.2
        const val NORMAL = 1.0
        const val MASTERED = 0.4
        private const val DAY_MS = 24 * 3600 * 1000L

        /** 不区分题目，权重恒为 1（关闭错题优先时使用）。 */
        val NONE: (Question) -> Double = { NORMAL }
    }
}
