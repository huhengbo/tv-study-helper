package de.huyc.studyhelper.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.TimeZone
import kotlin.random.Random

class RewardsTest {
    private val utc = TimeZone.getTimeZone("UTC")
    private val t0 = 1_730_800_000_000L // 2024-11-05 09:46 UTC
    private val day = 24 * 3600 * 1000L

    private fun complete(state: RewardState, canEarn: Boolean = true, perRound: Int = 5, cap: Int = 15, time: Long = t0, seed: Int = 1) =
        RewardRules.complete(state, canEarn, perRound, cap, time, Random(seed), utc)

    @Test fun eachRoundGivesFiveMinutesUntilDailyCap() {
        var state = RewardState()
        val minutes = List(5) { complete(state).also { r -> state = r.state }.minutes }
        assertEquals(listOf(5, 5, 5, 0, 0), minutes)
        assertEquals(15, state.earnedMinutes)
    }

    @Test fun nextMinutesMatchesWhatTheRoundPays() {
        assertEquals(5, RewardRules.nextMinutes(0, 5, 15))
        assertEquals(5, RewardRules.nextMinutes(10, 10, 15))
        assertEquals(0, RewardRules.nextMinutes(15, 5, 15))
        assertEquals(0, RewardRules.nextMinutes(0, 5, 0))
        assertEquals(0, RewardRules.nextMinutes(0, 0, 15))
    }

    @Test fun lastRoundBeforeCapGetsOnlyTheRemainder() {
        val r = complete(RewardState(day = "2024-11-05", earnedMinutes = 12))
        assertEquals(3, r.minutes)
        assertTrue(r.capReached)
    }

    @Test fun capReachedOnlyWhenSomethingWasWithheld() {
        assertFalse(complete(RewardState()).capReached)
        assertTrue(complete(RewardState(day = "2024-11-05", earnedMinutes = 15)).capReached)
    }

    @Test fun nextDayStartsFromZero() {
        val r = complete(RewardState(day = "2024-11-05", earnedMinutes = 15), time = t0 + day)
        assertEquals(5, r.minutes)
        assertEquals("2024-11-06", r.state.day)
        assertEquals(5, r.state.earnedMinutes)
    }

    @Test fun noTimeWhenNotAllowedOrDisabled() {
        assertEquals(0, complete(RewardState(), canEarn = false).minutes)
        assertEquals(0, complete(RewardState(), perRound = 0).minutes)
        assertFalse(complete(RewardState(), perRound = 0).capReached)
        assertEquals(0, complete(RewardState(), cap = 0).minutes)
    }

    @Test fun notAllowedRoundDoesNotUseUpTheDailyCap() {
        assertEquals(0, complete(RewardState(), canEarn = false).state.earnedMinutes)
    }

    @Test fun everyRoundDrawsOneStickerAndCountsDuplicates() {
        var state = RewardState()
        repeat(200) { i -> state = complete(state, time = t0 + i, seed = i).state }
        assertEquals(200, state.stickers.values.sumOf { it.count })
        assertTrue(state.stickers.keys.all { it in StickerCatalog.all })
    }

    @Test fun newStickerIsMarkedNewOnlyTheFirstTime() {
        val first = complete(RewardState(), seed = 7)
        assertTrue(first.draw.isNew)
        val again = RewardRules.draw(first.state, t0, Random(7))
        assertFalse(again.isNew)
        assertEquals(first.draw.code, again.code)
    }

    @Test fun firstTimeIsKeptWhenDuplicateDrawn() {
        val first = complete(RewardState(), time = t0, seed = 7)
        val again = complete(first.state, time = t0 + 5000, seed = 7)
        assertEquals(t0, again.state.stickers.getValue(first.draw.code).first)
        assertEquals(2, again.state.stickers.getValue(first.draw.code).count)
    }

    @Test fun allRaritiesCanBeDrawnWithRoughlyTheConfiguredOdds() {
        val random = Random(42)
        val counts = List(10_000) { RewardRules.draw(RewardState(), t0, random).rarity }.groupingBy { it }.eachCount()
        assertTrue(counts.keys.containsAll(Rarity.entries))
        assertTrue("普通 ${counts[Rarity.COMMON]}", counts.getValue(Rarity.COMMON) in 6500..7500)
        assertTrue("稀有 ${counts[Rarity.RARE]}", counts.getValue(Rarity.RARE) in 2100..2900)
        assertTrue("传说 ${counts[Rarity.LEGENDARY]}", counts.getValue(Rarity.LEGENDARY) in 300..700)
    }

    @Test fun stateRoundTripsThroughJson() {
        val state = complete(complete(RewardState(), seed = 1).state, seed = 2).state
        assertEquals(state, RewardState.fromJson(JSONObject(state.toJson().toString())))
    }

    @Test fun emptyOrDamagedJsonGivesEmptyState() {
        assertEquals(RewardState(), RewardState.fromJson(JSONObject()))
    }

    @Test fun catalogHasNoDuplicatesAndEveryImageExists() {
        assertEquals(StickerCatalog.all.size, StickerCatalog.all.toSet().size)
        val missing = StickerCatalog.all.filterNot { File("src/main/assets/emoji/$it.webp").exists() }
        assertTrue("图鉴里的图片不存在：$missing", missing.isEmpty())
    }

    @Test fun catalogSizesAreAsDesigned() {
        assertTrue(StickerCatalog.total in 90..110)
        assertTrue(StickerCatalog.byRarity.getValue(Rarity.COMMON).size > StickerCatalog.byRarity.getValue(Rarity.RARE).size)
        assertTrue(StickerCatalog.byRarity.getValue(Rarity.RARE).size > StickerCatalog.byRarity.getValue(Rarity.LEGENDARY).size)
        StickerCatalog.all.forEach { assertEquals(it, StickerCatalog.rarityOf(it)?.let { _ -> it }) }
    }
}
