package de.huyc.studyhelper.quiz

import de.huyc.studyhelper.data.AnswerLog
import de.huyc.studyhelper.data.MathConfig

/**
 * 数学出题范围的自适应：家长设置的 [MathConfig.max] 是上限，实际范围 [level] 在 [MIN_LEVEL, max] 之间，
 * 一轮答题结束后按这个范围下的正确率升降一档。
 */
object AdaptiveMath {
    const val MIN_LEVEL = 10
    const val STEP = 5
    const val WINDOW = 20
    const val MIN_SAMPLES = 10
    const val RAISE_AT = 0.9
    const val LOWER_AT = 0.6
    const val MATH_KEY_PREFIX = "MATH|"

    /** 出题时用的范围：未开启或上限低于下限时就是家长设置的上限；没有存过范围时从上限开始。 */
    fun level(config: MathConfig, stored: Int): Int =
        if (!config.adaptive || config.max < MIN_LEVEL || stored <= 0) config.max else stored.coerceIn(MIN_LEVEL, config.max)

    fun effective(config: MathConfig, stored: Int): MathConfig = config.copy(max = level(config, stored))

    /**
     * 新的范围。只统计 [since]（上次调整范围的时间）之后的数学题记录，[logs] 按时间从新到旧；
     * 样本不足 [MIN_SAMPLES] 时不变。
     */
    fun next(config: MathConfig, stored: Int, logs: List<AnswerLog>, since: Long): Int {
        val current = level(config, stored)
        if (!config.adaptive || config.max < MIN_LEVEL) return current
        val recent = logs.asSequence().filter { it.key.startsWith(MATH_KEY_PREFIX) && it.time >= since }.take(WINDOW).toList()
        if (recent.size < MIN_SAMPLES) return current
        val accuracy = recent.count { it.correct }.toDouble() / recent.size
        return when {
            accuracy >= RAISE_AT -> (current + STEP).coerceAtMost(config.max)
            accuracy <= LOWER_AT -> (current - STEP).coerceAtLeast(MIN_LEVEL)
            else -> current
        }
    }
}
