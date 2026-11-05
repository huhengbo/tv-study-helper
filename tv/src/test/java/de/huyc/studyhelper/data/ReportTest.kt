package de.huyc.studyhelper.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone

class ReportTest {
    private val utc = TimeZone.getTimeZone("UTC")
    private val day = 24 * 3600 * 1000L
    private val now = 1_730_800_000_000L // 2024-11-05 09:46 UTC，周二

    private fun log(daysAgo: Int, correct: Boolean, key: String, display: String = key.split('|').getOrElse(2) { key }) =
        AnswerLog(now - daysAgo * day, "q", display, "", correct, key)

    private fun build(daily: Map<String, DayStats> = emptyMap(), logs: List<AnswerLog> = emptyList(), days: Int = 7, rewards: RewardState = RewardState()) =
        ReportBuilder.build(daily, logs, rewards, now, days, utc)

    @Test fun daysAreOldestFirstAndIncludeEmptyOnes() {
        val daily = mapOf("2024-11-05" to DayStats(answered = 4, correct = 3, rounds = 1, studyRounds = 2))
        val r = build(daily)
        assertEquals(7, r.days.size)
        assertEquals("2024-10-30", r.days.first().date)
        assertEquals("2024-11-05", r.days.last().date)
        assertEquals(ReportDay("2024-11-05", 4, 3, 3), r.days.last())
        assertEquals(ReportDay("2024-11-04", 0, 0, 0), r.days[5])
        assertEquals(1, r.activeDays)
    }

    @Test fun daysOutsideTheWindowAreIgnored() {
        val daily = mapOf("2024-10-29" to DayStats(answered = 9, correct = 9), "2024-11-05" to DayStats(answered = 1, correct = 1))
        val r = build(daily)
        assertEquals(1, r.days.sumOf { it.answered })
        assertEquals(1, r.activeDays)
    }

    @Test fun subjectsAreSummedAndSortedByVolume() {
        val daily = mapOf(
            "2024-11-04" to DayStats(bySubject = mapOf("MATH" to (3 to 2), "HANZI" to (1 to 1))),
            "2024-11-05" to DayStats(bySubject = mapOf("MATH" to (2 to 2))),
        )
        assertEquals(listOf(ReportSubject("MATH", 5, 4), ReportSubject("HANZI", 1, 1)), build(daily).bySubject)
    }

    @Test fun weakItemsNeedTwoWrongAndNoRecentStreak() {
        val logs = listOf(
            log(0, false, "HANZI|VOICE|山|shan"), log(1, false, "HANZI|VOICE|山|shan"), // 弱：错 2 次
            log(0, false, "HANZI|VOICE|水|shui"), // 只错 1 次：不算
            log(0, true, "ENGLISH|VOICE|cat|cat"), log(1, true, "ENGLISH|VOICE|cat|cat"), log(2, true, "ENGLISH|VOICE|cat|cat"),
            log(3, false, "ENGLISH|VOICE|cat|cat"), log(4, false, "ENGLISH|VOICE|cat|cat"), // 错过但已连对 3 次：已掌握
        )
        assertEquals(listOf(WeakItem("山", "HANZI", 2, 2)), build(logs = logs).weak)
    }

    @Test fun weakItemsOnlyLookAtTheWindow() {
        val logs = listOf(log(8, false, "HANZI|VOICE|山|shan"), log(9, false, "HANZI|VOICE|山|shan"))
        assertTrue(build(logs = logs).weak.isEmpty())
        assertEquals(1, build(logs = logs, days = 14).weak.size)
    }

    @Test fun weakItemsAreOrderedByMistakesAndCapped() {
        val logs = (1..15).flatMap { i -> List(2 + i % 3) { log(it, false, "MATH|VOICE|$i + 1 = ?|${i + 1}") } }
        val weak = build(logs = logs).weak
        assertEquals(ReportBuilder.MAX_WEAK, weak.size)
        assertEquals(weak.sortedByDescending { it.wrong }.map { it.wrong }, weak.map { it.wrong })
    }

    @Test fun logsWithoutKeyAreSkipped() {
        assertTrue(build(logs = listOf(log(0, false, ""), log(1, false, ""))).weak.isEmpty())
    }

    @Test fun stickerProgressComesFromRewards() {
        val r = build(rewards = RewardState(stickers = mapOf("1f984" to StickerEntry(1, 0), "1f409" to StickerEntry(2, 0))))
        assertEquals(2, r.stickers)
        assertEquals(StickerCatalog.total, r.stickerTotal)
    }

    @Test fun dayKeysAreContiguousAcrossMonthEnd() {
        val keys = ReportBuilder.dayKeys(1_730_332_800_000L + day, 4, utc) // 2024-11-01 往前
        assertEquals(listOf("2024-10-29", "2024-10-30", "2024-10-31", "2024-11-01"), keys)
    }

    @Test fun jsonHasTheFieldsThePageReads() {
        val j = build(mapOf("2024-11-05" to DayStats(answered = 1, correct = 1))).toJson()
        assertEquals(7, j.getJSONArray("days").length())
        listOf("bySubject", "weak", "activeDays", "stickers", "stickerTotal").forEach { assertTrue(it, j.has(it)) }
    }
}
