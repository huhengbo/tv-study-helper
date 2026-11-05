package de.huyc.studyhelper.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone

class DailyStatsTest {
    private val utc = TimeZone.getTimeZone("UTC")
    private val day = 24 * 3600 * 1000L
    private val t0 = 1_730_800_000_000L // 2024-11-05 09:46 UTC

    private fun log(time: Long, correct: Boolean, key: String = "MATH|VOICE|1 + 1 = ?|2") =
        AnswerLog(time, "m", "1 + 1 = ?", "", correct, key)

    @Test fun answersAreGroupedByLocalDayAndSubject() {
        var s = emptyMap<String, DayStats>()
        s = DailyStats.record(s, log(t0, true), utc)
        s = DailyStats.record(s, log(t0 + 1000, false), utc)
        s = DailyStats.record(s, log(t0 + 1000, true, key = "HANZI|VOICE|山|shan"), utc)
        s = DailyStats.record(s, log(t0 + day, true), utc)
        val d = s.getValue("2024-11-05")
        assertEquals(3, d.answered); assertEquals(2, d.correct)
        assertEquals(2 to 1, d.bySubject["MATH"]); assertEquals(1 to 1, d.bySubject["HANZI"])
        assertEquals(1, s.getValue("2024-11-06").answered)
    }

    @Test fun dayBoundaryFollowsTimeZone() {
        val tokyo = TimeZone.getTimeZone("Asia/Tokyo")
        val lateUtc = 1_730_851_200_000L - 1000 // 2024-11-05 23:59:59 UTC = 11-06 08:59:59 东京
        assertEquals("2024-11-05", DailyStats.dayKey(lateUtc, utc))
        assertEquals("2024-11-06", DailyStats.dayKey(lateUtc, tokyo))
    }

    @Test fun roundsCountSeparatelyForGateAndStudy() {
        var s = DailyStats.recordRound(emptyMap(), study = false, time = t0, zone = utc)
        s = DailyStats.recordRound(s, study = false, time = t0, zone = utc)
        s = DailyStats.recordRound(s, study = true, time = t0, zone = utc)
        assertEquals(2, s.getValue("2024-11-05").rounds)
        assertEquals(1, s.getValue("2024-11-05").studyRounds)
    }

    @Test fun logsWithoutKeyStillCountButHaveNoSubject() {
        val s = DailyStats.record(emptyMap(), log(t0, true, key = ""), utc)
        assertEquals(1, s.getValue("2024-11-05").answered)
        assertTrue(s.getValue("2024-11-05").bySubject.isEmpty())
    }

    @Test fun oldDaysArePruned() {
        var s = emptyMap<String, DayStats>()
        for (i in 0 until 100) s = DailyStats.record(s, log(t0 + i * day, true), utc)
        assertTrue(s.size <= DailyStats.KEEP_DAYS + 1)
        assertFalse(s.containsKey("2024-11-05"))
        assertTrue(s.containsKey(DailyStats.dayKey(t0 + 99 * day, utc)))
    }

    @Test fun jsonRoundTrip() {
        var s = DailyStats.record(emptyMap(), log(t0, true), utc)
        s = DailyStats.recordRound(s, study = true, time = t0, zone = utc)
        assertEquals(s, DailyStats.fromJson(JSONObject(DailyStats.toJson(s).toString())))
    }
}
