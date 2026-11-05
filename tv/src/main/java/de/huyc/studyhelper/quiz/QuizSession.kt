package de.huyc.studyhelper.quiz

/**
 * 一轮答题的进度规则：需要答对 [required] 题。[items] 可多于 [required]，答错后公布答案并换新题。
 * [wrongResets] 为 true 时答错还会清零已答对数（「连续答对」）。
 */
class QuizSession(private val items: List<QuizItem>, val required: Int, private val wrongResets: Boolean) {
    var correctCount = 0
        private set
    private var index = 0

    val current: QuizItem get() = items[index % items.size]
    val done: Boolean get() = correctCount >= required

    enum class Result { NEXT, WRONG, RESET, DONE }

    fun answer(correct: Boolean): Result {
        if (correct) {
            correctCount++
            index++
            return if (done) Result.DONE else Result.NEXT
        }
        index++
        if (wrongResets && correctCount > 0) {
            correctCount = 0
            return Result.RESET
        }
        return Result.WRONG
    }

    /** 跳过当前题（如识别服务不可用时换一道），不计对错。 */
    fun skip() { index++ }
}
