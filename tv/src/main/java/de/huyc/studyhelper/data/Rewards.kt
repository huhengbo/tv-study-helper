package de.huyc.studyhelper.data

import org.json.JSONObject
import java.util.TimeZone
import kotlin.random.Random

/** 贴纸稀有度，[weight] 是抽中的相对概率。 */
enum class Rarity(val label: String, val weight: Int) {
    COMMON("普通", 70), RARE("稀有", 25), LEGENDARY("传说", 5)
}

/**
 * 贴纸图鉴：用 assets/emoji 里已有的表情图片，值是码位（文件名）。
 * 新增贴纸前确认图片存在，单测会检查。
 */
object StickerCatalog {
    val byRarity: Map<Rarity, List<String>> = mapOf(
        Rarity.COMMON to """
            1f401 1f402 1f404 1f407 1f408 1f40e 1f410 1f411 1f412 1f413 1f414 1f415 1f416 1f418 1f41b 1f41c 1f41d 1f41e
            1f422 1f423 1f424 1f425 1f426 1f427 1f428 1f429 1f42b 1f42d 1f42e 1f42f 1f430 1f431 1f434 1f435 1f436 1f437
            1f438 1f439 1f43a 1f43b 1f43c 1f34e 1f34c 1f347 1f349 1f34a 1f353 1f351 1f352 1f34d 1f34b 1f350 1f95d 1f955
            1f33d 1f966 1f370 1f366 1f369 1f36a 1f355 1f354
        """.codes(),
        Rarity.RARE to """
            1f419 1f420 1f42c 1f433 1f988 1f980 1f990 1f991 1f99e 1f981 1f992 1f993 1f98f 1f99b 1f998 1f994 1f989 1f99c
            1f9a9 1f9a5 1f9a6 1f98b 1f680 1f681 1f692 1f691 1f6f8 1f3ce 1f682 1f3b8 1f3ba 1f3bb 1f941
        """.codes(),
        Rarity.LEGENDARY to "1f984 1f409 1f995 1f996 1f308 1f320 1fa90 1f52e".codes(),
    )

    val all: List<String> = Rarity.entries.flatMap { byRarity.getValue(it) }
    val total: Int get() = all.size

    fun rarityOf(code: String): Rarity? = Rarity.entries.firstOrNull { code in byRarity.getValue(it) }

    private fun String.codes() = trim().split(Regex("\\s+"))
}

data class StickerEntry(val count: Int, val first: Long)

/** 贴纸收集和每天已得的加时。[day] 是 [earnedMinutes] 对应的本地日期。 */
data class RewardState(
    val stickers: Map<String, StickerEntry> = emptyMap(),
    val day: String = "",
    val earnedMinutes: Int = 0,
) {
    fun earnedOn(today: String) = if (day == today) earnedMinutes else 0

    fun toJson(): JSONObject = JSONObject()
        .put("day", day).put("earnedMinutes", earnedMinutes)
        .put("stickers", JSONObject().also { o -> stickers.forEach { (k, v) -> o.put(k, JSONObject().put("n", v.count).put("first", v.first)) } })

    companion object {
        fun fromJson(o: JSONObject): RewardState {
            val s = o.optJSONObject("stickers")
            val stickers = s?.keys()?.asSequence()?.associateWith { k ->
                s.getJSONObject(k).let { StickerEntry(it.optInt("n", 1).coerceAtLeast(1), it.optLong("first")) }
            }.orEmpty()
            return RewardState(stickers, o.optString("day"), o.optInt("earnedMinutes"))
        }
    }
}

data class Draw(val code: String, val rarity: Rarity, val isNew: Boolean)

/** 一轮答题通过的奖励：新的收集状态、加时分钟数、抽到的贴纸。[capReached] 表示今天的加时已经攒满。 */
data class RoundReward(val state: RewardState, val minutes: Int, val draw: Draw, val capReached: Boolean)

object RewardRules {
    /** 今天已得 [earned] 分钟时，下一轮能得的加时：每轮 [perRound] 分钟，补到 [cap] 为止。首页提示也用它，和实际发放一致。 */
    fun nextMinutes(earned: Int, perRound: Int, cap: Int): Int =
        perRound.coerceAtLeast(0).coerceAtMost((cap - earned).coerceAtLeast(0))

    /**
     * 通过一轮答题的奖励。每轮抽一张贴纸；[canEarnTime] 时再给加时：
     * 每轮 [perRound] 分钟，当天累计不超过 [cap] 分钟（0 表示不给）。
     */
    fun complete(
        state: RewardState,
        canEarnTime: Boolean,
        perRound: Int,
        cap: Int,
        time: Long,
        random: Random = Random.Default,
        zone: TimeZone = TimeZone.getDefault(),
    ): RoundReward {
        val today = DailyStats.dayKey(time, zone)
        val earned = state.earnedOn(today)
        val minutes = if (canEarnTime) nextMinutes(earned, perRound, cap) else 0
        val draw = draw(state, time, random)
        val next = state.copy(
            stickers = state.stickers + (draw.code to StickerEntry((state.stickers[draw.code]?.count ?: 0) + 1, state.stickers[draw.code]?.first ?: time)),
            day = today,
            earnedMinutes = earned + minutes,
        )
        return RoundReward(next, minutes, draw, capReached = canEarnTime && perRound > 0 && minutes < perRound)
    }

    fun draw(state: RewardState, time: Long, random: Random): Draw {
        var roll = random.nextInt(Rarity.entries.sumOf { it.weight })
        val rarity = Rarity.entries.first { roll -= it.weight; roll < 0 }
        val code = StickerCatalog.byRarity.getValue(rarity).random(random)
        return Draw(code, rarity, isNew = code !in state.stickers)
    }
}
