package de.huyc.studyhelper.data

import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** 一天的作答汇总，周报用。[bySubject] 的值是 (作答次数, 答对次数)。 */
data class DayStats(
    val answered: Int = 0,
    val correct: Int = 0,
    /** 通过的锁屏答题轮数。 */
    val rounds: Int = 0,
    /** 通过的主动学习轮数。 */
    val studyRounds: Int = 0,
    val bySubject: Map<String, Pair<Int, Int>> = emptyMap(),
) {
    fun withAnswer(subject: String, isCorrect: Boolean): DayStats {
        val (a, c) = bySubject[subject] ?: (0 to 0)
        val subjects = if (subject.isEmpty()) bySubject else bySubject + (subject to (a + 1 to c + if (isCorrect) 1 else 0))
        return copy(answered = answered + 1, correct = correct + if (isCorrect) 1 else 0, bySubject = subjects)
    }

    fun withRound(study: Boolean) = if (study) copy(studyRounds = studyRounds + 1) else copy(rounds = rounds + 1)

    fun toJson(): JSONObject = JSONObject().put("a", answered).put("c", correct).put("r", rounds).put("s", studyRounds)
        .put("sub", JSONObject().also { o -> bySubject.forEach { (k, v) -> o.put(k, org.json.JSONArray(listOf(v.first, v.second))) } })

    companion object {
        fun fromJson(o: JSONObject): DayStats {
            val sub = o.optJSONObject("sub")
            val subjects = sub?.keys()?.asSequence()?.mapNotNull { k ->
                sub.optJSONArray(k)?.let { k to (it.optInt(0) to it.optInt(1)) }
            }?.toMap().orEmpty()
            return DayStats(o.optInt("a"), o.optInt("c"), o.optInt("r"), o.optInt("s"), subjects)
        }
    }
}

/** 按天汇总的作答统计（键为本地日期 yyyy-MM-dd），保留最近 [KEEP_DAYS] 天。 */
object DailyStats {
    const val KEEP_DAYS = 90

    fun dayKey(time: Long, zone: TimeZone = TimeZone.getDefault()): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = zone }.format(Date(time))

    /** 题目的科目取自 [Question.key] 的第一段；没有 key 的老记录没有科目。 */
    fun subjectOf(key: String): String = key.substringBefore('|', "")

    fun record(stats: Map<String, DayStats>, log: AnswerLog, zone: TimeZone = TimeZone.getDefault()): Map<String, DayStats> {
        val day = dayKey(log.time, zone)
        return prune(stats + (day to (stats[day] ?: DayStats()).withAnswer(subjectOf(log.key), log.correct)), log.time, zone)
    }

    fun recordRound(stats: Map<String, DayStats>, study: Boolean, time: Long, zone: TimeZone = TimeZone.getDefault()): Map<String, DayStats> {
        val day = dayKey(time, zone)
        return prune(stats + (day to (stats[day] ?: DayStats()).withRound(study)), time, zone)
    }

    private fun prune(stats: Map<String, DayStats>, now: Long, zone: TimeZone): Map<String, DayStats> {
        if (stats.size <= KEEP_DAYS) return stats
        val oldest = dayKey(now - KEEP_DAYS * 24 * 3600 * 1000L, zone)
        return stats.filterKeys { it >= oldest }
    }

    fun toJson(stats: Map<String, DayStats>): JSONObject = JSONObject().also { o -> stats.forEach { (k, v) -> o.put(k, v.toJson()) } }

    fun fromJson(o: JSONObject): Map<String, DayStats> =
        o.keys().asSequence().associateWith { DayStats.fromJson(o.getJSONObject(it)) }
}
