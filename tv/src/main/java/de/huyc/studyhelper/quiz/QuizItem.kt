package de.huyc.studyhelper.quiz

import de.huyc.studyhelper.data.AnswerMode
import de.huyc.studyhelper.data.Question
import kotlin.random.Random

/**
 * 出给孩子的一道题：选择题的选项已打乱，[correctChoice] 为正确选项下标。
 * 选择题选项不足两个时按语音题处理，避免出现无法作答的题。
 */
data class QuizItem(
    val question: Question,
    val choices: List<String> = emptyList(),
    val correctChoice: Int = -1,
) {
    val mode: AnswerMode get() = if (choices.size >= 2) AnswerMode.CHOICE else AnswerMode.VOICE

    companion object {
        fun of(q: Question, random: Random = Random.Default): QuizItem {
            if (q.mode != AnswerMode.CHOICE) return QuizItem(q)
            val right = q.answers.firstOrNull() ?: return QuizItem(q)
            val options = (q.choices + right).map { it.trim() }.filter { it.isNotEmpty() }.distinct()
            if (options.size < 2) return QuizItem(q)
            val shuffled = options.shuffled(random)
            return QuizItem(q, shuffled, shuffled.indexOf(right.trim()))
        }
    }
}
