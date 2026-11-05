package de.huyc.studyhelper.data

import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar
import java.util.TimeZone

/** [rounds] 是当天通过的答题轮数（锁屏答题加主动学习）。 */
data class ReportDay(val date: String, val answered: Int, val correct: Int, val rounds: Int)

data class ReportSubject(val subject: String, val answered: Int, val correct: Int)

/** 近期反复答错的题。 */
data class WeakItem(val display: String, val subject: String, val wrong: Int, val total: Int)

data class Report(
    /** 从早到晚，没学习的日子也在，数字全是 0。 */
    val days: List<ReportDay>,
    val bySubject: List<ReportSubject>,
    val weak: List<WeakItem>,
    val activeDays: Int,
    val stickers: Int,
    val stickerTotal: Int,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("days", JSONArray(days.map { JSONObject().put("date", it.date).put("answered", it.answered).put("correct", it.correct).put("rounds", it.rounds) }))
        .put("bySubject", JSONArray(bySubject.map { JSONObject().put("subject", it.subject).put("answered", it.answered).put("correct", it.correct) }))
        .put("weak", JSONArray(weak.map { JSONObject().put("display", it.display).put("subject", it.subject).put("wrong", it.wrong).put("total", it.total) }))
        .put("activeDays", activeDays).put("stickers", stickers).put("stickerTotal", stickerTotal)
}

object ReportBuilder {
    const val MAX_WEAK = 10
    /** 最近连续答对这么多次就不再算薄弱题（和出题的"已掌握"一致）。 */
    private const val MASTERED_STREAK = 3
    private const val MIN_WRONG = 2

    /**
     * 最近 [days] 天（含今天）的周报。[logs] 按时间从新到旧；薄弱题只看这段时间内答错至少 2 次、
     * 且最近没有连续答对 3 次的题。
     */
    fun build(
        daily: Map<String, DayStats>,
        logs: List<AnswerLog>,
        rewards: RewardState,
        now: Long,
        days: Int,
        zone: TimeZone = TimeZone.getDefault(),
    ): Report {
        val keys = dayKeys(now, days, zone)
        val rows = keys.map { k -> daily[k].let { ReportDay(k, it?.answered ?: 0, it?.correct ?: 0, (it?.rounds ?: 0) + (it?.studyRounds ?: 0)) } }
        val subjects = keys.mapNotNull { daily[it] }.flatMap { it.bySubject.entries }
            .groupBy({ it.key }, { it.value })
            .map { (s, v) -> ReportSubject(s, v.sumOf { it.first }, v.sumOf { it.second }) }
            .sortedByDescending { it.answered }
        val since = keys.first()
        val recent = logs.filter { DailyStats.dayKey(it.time, zone) >= since && it.key.isNotEmpty() }
        val weak = recent.groupBy { it.key }.mapNotNull { (key, list) ->
            val wrong = list.count { !it.correct }
            val streak = list.takeWhile { it.correct }.size
            if (wrong >= MIN_WRONG && streak < MASTERED_STREAK) WeakItem(list.first().display, DailyStats.subjectOf(key), wrong, list.size) else null
        }.sortedWith(compareByDescending<WeakItem> { it.wrong }.thenByDescending { it.total }.thenBy { it.display }).take(MAX_WEAK)
        return Report(rows, subjects, weak, rows.count { it.answered > 0 }, rewards.stickers.size, StickerCatalog.total)
    }

    /** 从早到晚的本地日期，最后一个是 [now] 当天；按日历推算，不受夏令时影响。 */
    fun dayKeys(now: Long, days: Int, zone: TimeZone): List<String> {
        val cal = Calendar.getInstance(zone).apply { timeInMillis = now }
        val list = ArrayList<String>(days)
        repeat(days) {
            list += DailyStats.dayKey(cal.timeInMillis, zone)
            cal.add(Calendar.DAY_OF_MONTH, -1)
        }
        return list.reversed()
    }
}
