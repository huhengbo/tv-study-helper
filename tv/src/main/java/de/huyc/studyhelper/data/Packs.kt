package de.huyc.studyhelper.data

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** 题库包从哪来。只有 [LOCAL]（家长自建）可以改题目，其余只读，但可以停用其中的题或复制一份再改。 */
enum class PackSource(val label: String) {
    BUILTIN("内置"),
    SHARED("分享题库"),
    FILE("文件导入"),
    LOCAL("我的"),
}

/**
 * 题库包：一本书、一个单元或一个绘本的题目，是分享和开关的单位。
 * 文件格式见 [PackFormat]，与分享题库仓库里的文件相同。
 * 题目归包所有，同一道题可以出现在多个包里，出题时按 [Question.key] 去重。
 */
data class QuestionPack(
    val id: String,
    val name: String,
    val icon: String = "📚",
    val description: String = "",
    val author: String = "",
    val license: String = "",
    val tags: List<String> = emptyList(),
    val version: Int = 1,
    val questions: List<Question> = emptyList(),
    // 以下为电视本地状态，导出时不写入
    val source: PackSource = PackSource.LOCAL,
    val sourceUrl: String = "",
    val enabled: Boolean = true,
    val installedAt: Long = 0,
) {
    val editable: Boolean get() = source == PackSource.LOCAL

    /** 导出 / 分享用的文件内容。 */
    fun toPackJson(): JSONObject = header().put("questions", JSONArray(questions.map { it.toPackJson() }))

    /** 电视本地保存：带上开关状态和来源。 */
    fun toLocalJson(): JSONObject = header()
        .put("questions", JSONArray(questions.map { it.toJson() }))
        .put("local", JSONObject()
            .put("source", source.name).put("sourceUrl", sourceUrl)
            .put("enabled", enabled).put("installedAt", installedAt))

    private fun header() = JSONObject()
        .put("format", PackFormat.FORMAT)
        .put("formatVersion", PackFormat.VERSION)
        .put("id", id)
        .put("version", version)
        .put("name", name)
        .put("icon", icon)
        .put("description", description)
        .put("author", author)
        .put("license", license)
        .put("tags", JSONArray(tags))

    /**
     * 用新版本内容替换旧包：保留开关、来源和家长停用的题（按题目 id 对应）。
     * 本包是新版本，[old] 是电视上已有的。
     */
    fun upgradedFrom(old: QuestionPack): QuestionPack {
        val disabled = old.questions.filterNot { it.enabled }.map { it.id }.toSet()
        return copy(
            enabled = old.enabled,
            installedAt = old.installedAt,
            questions = questions.map { if (it.id in disabled) it.copy(enabled = false) else it },
        )
    }

    /** 复制成家长自己的包（可以改题）。 */
    fun copyAsLocal(now: Long): QuestionPack = copy(
        id = PackFormat.newLocalId(),
        name = "$name（我的）",
        version = 1,
        source = PackSource.LOCAL,
        sourceUrl = "",
        installedAt = now,
    )

    companion object {
        /** 读取电视本地保存的包。 */
        fun fromLocalJson(o: JSONObject): QuestionPack {
            val local = o.optJSONObject("local") ?: JSONObject()
            val parsed = PackFormat.parse(o, strict = false).pack
            return parsed.copy(
                questions = o.optJSONArray("questions")?.let { a ->
                    (0 until a.length()).mapNotNull { Question.fromJsonOrNull(a.getJSONObject(it)) }
                } ?: emptyList(),
                source = enumOr(local.optString("source"), PackSource.LOCAL),
                sourceUrl = local.optString("sourceUrl"),
                enabled = local.optBoolean("enabled", true),
                installedAt = local.optLong("installedAt"),
            )
        }
    }
}

/**
 * 题库包文件格式（v1）：
 * ```
 * { "format": "studyhelper-pack", "formatVersion": 1, "id": "renjiao.yuwen-1a", "version": 3,
 *   "name": "…", "icon": "📘", "description": "…", "author": "…", "license": "CC-BY-SA-4.0", "tags": ["一年级"],
 *   "questions": [ { "id": "u1-1", "subject": "HANZI", "display": "天", "answers": ["天"] }, … ] }
 * ```
 * 题目字段同 [Question.toPackJson]：mode 默认 VOICE，prompt 默认用科目的默认题目文字。
 * 不认识的字段忽略；不认识的科目或作答方式跳过该题（新版本才支持的题型）。
 */
object PackFormat {
    const val FORMAT = "studyhelper-pack"
    const val VERSION = 1
    private val ID_RE = Regex("[a-z0-9][a-z0-9._-]{1,63}")

    class Parsed(val pack: QuestionPack, val skipped: Int)

    class InvalidPack(message: String) : IllegalArgumentException(message)

    /** [strict] 用于导入外部文件：检查格式标记和每道题是否完整；本地文件只做宽松读取。 */
    fun parse(o: JSONObject, strict: Boolean = true): Parsed {
        if (strict) {
            if (o.optString("format") != FORMAT) throw InvalidPack("这不是学习小助手的题库文件")
            if (o.optInt("formatVersion", 1) > VERSION) throw InvalidPack("这个题库需要更新版本的学习小助手")
        }
        val id = o.optString("id").trim()
        if (!ID_RE.matches(id)) throw InvalidPack("题库编号不对：只能用小写字母、数字、点、横线和下划线")
        val name = o.optString("name").trim()
        if (name.isEmpty()) throw InvalidPack("题库没有名字")
        val array = o.optJSONArray("questions") ?: JSONArray()
        var skipped = 0
        val questions = (0 until array.length()).mapNotNull { i ->
            val q = array.optJSONObject(i)?.let(Question::fromJsonOrNull)
            if (q == null || (strict && !valid(q))) { skipped++; null } else q
        }.let { list ->
            // 题目 id 用于升级时保留停用状态；缺失或重复时按位置补一个
            val seen = HashSet<String>()
            list.mapIndexed { i, q -> if (q.id.isBlank() || !seen.add(q.id)) q.copy(id = "q$i").also { seen += it.id } else q }
        }
        if (strict && questions.isEmpty()) throw InvalidPack("题库里没有可用的题目")
        return Parsed(
            QuestionPack(
                id = id,
                name = name,
                icon = o.optString("icon").trim().ifEmpty { "📚" },
                description = o.optString("description").trim(),
                author = o.optString("author").trim(),
                license = o.optString("license").trim(),
                tags = o.optJSONArray("tags").strings(),
                version = o.optInt("version", 1).coerceAtLeast(1),
                questions = questions,
            ),
            skipped,
        )
    }

    private fun valid(q: Question): Boolean {
        if (q.display.isBlank() || q.answers.none { it.isNotBlank() }) return false
        if (q.mode == AnswerMode.CHOICE) return q.choices.distinct().size >= 2 && q.answers.first() in q.choices
        return true
    }

    fun newLocalId() = "my." + UUID.randomUUID().toString().take(8)
}

object PackRules {
    /** 默认的自建包：旧版本自己加的题、批量导入都放这里。 */
    const val MY_PACK_ID = "my.default"

    /** 0.1 版内置题库的题目 id，迁移时用来判断「家长删掉的内置题」。 */
    private val LEGACY_BUILTIN_ID = Regex("(en|hz)-\\d+|ch-[012]")

    fun myPack(questions: List<Question> = emptyList(), now: Long = 0) =
        QuestionPack(MY_PACK_ID, "我的题目", "✏️", "自己添加的题目", questions = questions, installedAt = now)

    /** 开着的包里启用的题，按内容去重。 */
    fun activeQuestions(packs: List<QuestionPack>): List<Question> =
        packs.filter { it.enabled }.flatMap { p -> p.questions.filter { it.enabled } }.distinctBy { it.key }

    /**
     * 旧版本只有一个题库（questions.json）。拆分为内置包 + 「我的题目」：
     * - 内置题（id 和内容都没变）沿用原来的启用状态；
     * - 被删掉的旧版内置题（[LEGACY_BUILTIN_ID]）在内置包里停用，新加的内置题照常启用；
     * - 家长新增的题、改过内容的内置题放进「我的题目」。
     */
    fun migrateLegacy(legacy: List<Question>, builtins: List<QuestionPack>, now: Long): List<QuestionPack> {
        val byId = legacy.associateBy { it.id }
        val kept = HashSet<String>()
        val packs = builtins.map { pack ->
            pack.copy(installedAt = now, questions = pack.questions.map { q ->
                val old = byId[q.id]
                when {
                    old != null && old.key == q.key -> { kept += old.id; q.copy(enabled = old.enabled) }
                    old != null || LEGACY_BUILTIN_ID.matches(q.id) -> q.copy(enabled = false)
                    else -> q
                }
            })
        }
        val mine = legacy.filter { it.id !in kept }
        return packs + myPack(mine, now)
    }

    class Stats(val total: Int, val mastered: Int, val recentAnswers: Int, val recentCorrect: Int)

    /**
     * 按题库统计：[Stats.mastered] 为最近一次回答正确的题数；recent 为 [since] 之后的作答次数。
     * 多个包里相同的题，答一次在每个包里都算。
     */
    fun stats(pack: QuestionPack, logs: List<AnswerLog>, since: Long): Stats {
        val keys = pack.questions.filter { it.enabled }.map { it.key }.toSet()
        val mine = logs.filter { it.key in keys }
        // logs 按时间倒序，第一次出现的是最近一次
        val latest = mine.distinctBy { it.key }
        val recent = mine.filter { it.time >= since }
        return Stats(keys.size, latest.count { it.correct }, recent.size, recent.count { it.correct })
    }
}
