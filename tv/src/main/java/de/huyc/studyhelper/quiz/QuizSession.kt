package de.huyc.studyhelper.quiz

/**
 * 一轮答题的进度规则：需要答对 [required] 题。[items] 可多于 [required]，答错后公布答案并换新题。
 * [wrongResets] 为 true 时答错还会清零已答对数（「连续答对」）。
 * [retryWrong] 为 true 时答错的题隔 [RETRY_GAP] 题后再考一次，每道题每轮只回炉一次。
 */
class QuizSession(
    private val items: List<QuizItem>,
    val required: Int,
    private val wrongResets: Boolean,
    private val retryWrong: Boolean = false,
) {
    var correctCount = 0
        private set
    var current: QuizItem = items.first()
        private set
    val done: Boolean get() = correctCount >= required

    /** 下一道从 [items] 里取的题的下标。 */
    private var next = 1
    /** 已经换过几道题；回炉的题到这个数就该出现。 */
    private var served = 0
    private val retries = ArrayList<Pair<Int, QuizItem>>()
    private val retriedIds = HashSet<String>()

    enum class Result { NEXT, WRONG, RESET, DONE }

    fun answer(correct: Boolean): Result {
        val answered = current
        if (correct) {
            correctCount++
            advance()
            return if (done) Result.DONE else Result.NEXT
        }
        if (retryWrong && retriedIds.add(answered.question.id)) retries += served + RETRY_GAP to answered
        advance()
        if (wrongResets && correctCount > 0) {
            correctCount = 0
            return Result.RESET
        }
        return Result.WRONG
    }

    /** 跳过当前题（如识别服务不可用时换一道），不计对错。 */
    fun skip() = advance()

    private fun advance() {
        served++
        val due = retries.firstOrNull { it.first <= served }
        if (due != null) {
            retries.remove(due)
            current = due.second
        } else {
            current = items[next++ % items.size]
        }
    }

    companion object {
        /** 答错的题之后隔两道题再出现。 */
        const val RETRY_GAP = 3
    }
}
