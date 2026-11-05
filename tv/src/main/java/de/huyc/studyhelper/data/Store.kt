package de.huyc.studyhelper.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import de.huyc.studyhelper.quiz.AdaptiveMath
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.random.Random

/**
 * 设置、题库、作答记录的本地持久化。设置是 JSON 文件，题库每个包一个文件（packs/<id>.json），
 * 计时状态放 SharedPreferences（频繁写）。电视端和内置网页服务在同一进程内共享这一个实例。
 */
class Store(context: Context) {
    private val app = context.applicationContext
    private val dir = app.filesDir
    private val settingsFile = File(dir, "settings.json")
    private val legacyQuestionsFile = File(dir, "questions.json")
    private val packDir = File(dir, "packs")
    private val logFile = File(dir, "answers.json")
    private val dailyFile = File(dir, "daily.json")
    private val rewardsFile = File(dir, "rewards.json")
    private val prefs: SharedPreferences = app.getSharedPreferences("state", Context.MODE_PRIVATE)
    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    @Volatile var settings: Settings = load(settingsFile)?.let { Settings.fromJson(JSONObject(it)) } ?: Settings()
        private set

    @Volatile var packs: List<QuestionPack> = sorted(loadPacks())
        private set

    /** 出题用的题：开着的包里启用的题，内容相同的只保留一道。 */
    @Volatile var activeQuestions: List<Question> = PackRules.activeQuestions(packs)
        private set

    fun onChange(listener: () -> Unit) { listeners += listener }

    @Synchronized
    fun updateSettings(transform: (Settings) -> Settings) {
        settings = transform(settings)
        save(settingsFile, settings.toJson().toString())
        notifyChanged()
    }

    // ---- 题库包 ----

    fun pack(id: String): QuestionPack? = packs.firstOrNull { it.id == id }

    private fun loadPacks(): List<QuestionPack> {
        val now = System.currentTimeMillis()
        val builtins = runCatching { BuiltinPacks.load(app) }.onFailure { Log.e(TAG, "内置题库读取失败", it) }.getOrDefault(emptyList())
        val list = ArrayList<QuestionPack>()
        if (!packDir.exists()) {
            packDir.mkdirs()
            val legacy = load(legacyQuestionsFile)?.let { text ->
                runCatching { JSONArray(text).let { a -> (0 until a.length()).mapNotNull { Question.fromJsonOrNull(a.getJSONObject(it)) } } }.getOrNull()
            }
            list += if (legacy != null) PackRules.migrateLegacy(legacy, builtins, now)
            else builtins.map { it.copy(installedAt = now) } + PackRules.myPack(now = now)
            list.forEach(::writePack)
            // 旧文件改名留作备份，不删除
            if (legacy != null) legacyQuestionsFile.renameTo(File(dir, "questions.legacy.json"))
        } else {
            packDir.listFiles { f -> f.name.endsWith(".json") }.orEmpty().forEach { f ->
                runCatching { QuestionPack.fromLocalJson(JSONObject(f.readText())) }
                    .onSuccess { list += it }.onFailure { Log.e(TAG, "题库文件损坏：${f.name}", it) }
            }
        }
        // 内置包随程序升级；家长删掉的内置包不再自动装回
        val removed = prefs.getStringSet(KEY_REMOVED_BUILTINS, emptySet()).orEmpty()
        for (b in builtins) {
            val i = list.indexOfFirst { it.id == b.id }
            when {
                i < 0 && b.id !in removed -> list += b.copy(installedAt = now).also(::writePack)
                i >= 0 && list[i].source == PackSource.BUILTIN && list[i].version < b.version -> list[i] = b.upgradedFrom(list[i]).also(::writePack)
            }
        }
        // 随程序预置的公共题库：第一次装上（默认不启用），之后只在预置的版本更高时升级；家长删掉的不再装回
        val removedShared = prefs.getStringSet(KEY_REMOVED_BUNDLED, emptySet()).orEmpty()
        for (b in runCatching { BundledShared.load(app) }.onFailure { Log.e(TAG, "预置公共题库读取失败", it) }.getOrDefault(emptyList())) {
            val i = list.indexOfFirst { it.id == b.id }
            when {
                i < 0 && b.id !in removedShared -> list += b.copy(enabled = false, installedAt = now).also(::writePack)
                i >= 0 && list[i].source == PackSource.SHARED && list[i].version < b.version -> list[i] = b.upgradedFrom(list[i]).also(::writePack)
            }
        }
        BundledShared.seedSprites(app, list)
        return list
    }

    @Synchronized
    fun savePack(pack: QuestionPack): QuestionPack {
        writePack(pack)
        val list = packs.filterNot { it.id == pack.id } + pack
        setPacks(list)
        return pack
    }

    @Synchronized
    fun updatePack(id: String, transform: (QuestionPack) -> QuestionPack): QuestionPack {
        val old = pack(id) ?: throw IllegalArgumentException("题库不存在")
        return savePack(transform(old))
    }

    @Synchronized
    fun deletePack(id: String) {
        val p = pack(id) ?: return
        require(id != PackRules.MY_PACK_ID) { "「我的题目」不能删除" }
        File(packDir, "$id.json").delete()
        if (p.source == PackSource.BUILTIN) {
            prefs.edit().putStringSet(KEY_REMOVED_BUILTINS, prefs.getStringSet(KEY_REMOVED_BUILTINS, emptySet()).orEmpty() + id).apply()
        }
        if (p.source == PackSource.SHARED) {
            prefs.edit().putStringSet(KEY_REMOVED_BUNDLED, prefs.getStringSet(KEY_REMOVED_BUNDLED, emptySet()).orEmpty() + id).apply()
        }
        setPacks(packs.filterNot { it.id == id })
        if (p.sheets.isNotEmpty()) SpriteCache.prune(app, this)
    }

    /** 只开这一个包，其余全部关掉。 */
    @Synchronized
    fun enableOnly(id: String) {
        val list = packs.map { it.copy(enabled = it.id == id) }
        list.filter { it.enabled != pack(it.id)?.enabled }.forEach(::writePack)
        setPacks(list)
    }

    /** 恢复内置题库为出厂内容（包括被删掉的），不影响其他包。 */
    @Synchronized
    fun restoreBuiltins() {
        prefs.edit().remove(KEY_REMOVED_BUILTINS).apply()
        val now = System.currentTimeMillis()
        val builtins = BuiltinPacks.load(app).map { it.copy(installedAt = now) }
        builtins.forEach(::writePack)
        setPacks(packs.filterNot { p -> builtins.any { it.id == p.id } } + builtins)
    }

    enum class InstallResult { NEW, UPDATED }

    /** 安装外部题库包；已安装同一个包时按新内容更新，保留开关和停用的题。 */
    @Synchronized
    fun installPack(incoming: QuestionPack, source: PackSource, url: String = ""): InstallResult {
        val old = pack(incoming.id)
        require(old == null || !old.editable) { "电视上已有这个题库（自己建的），不需要再导入" }
        val fresh = incoming.copy(source = source, sourceUrl = url, installedAt = System.currentTimeMillis())
        savePack(if (old != null) fresh.upgradedFrom(old) else fresh)
        // 图在后台下载，失败了下次答题前重试；没有图的新版本也要清理旧版本的图
        if (fresh.sheets.isNotEmpty() || old?.sheets?.isNotEmpty() == true) SpriteCache.prefetch(app, this, setOf(fresh.id))
        return if (old == null) InstallResult.NEW else InstallResult.UPDATED
    }

    @Synchronized
    fun upsertQuestion(packId: String, q: Question): Question {
        val saved = if (q.id.isBlank()) q.copy(id = UUID.randomUUID().toString().take(8)) else q
        updatePack(packId) { p ->
            require(p.editable) { "这个题库不能修改，可以先「复制一份」再改" }
            val list = p.questions.toMutableList()
            val i = list.indexOfFirst { it.id == saved.id }
            if (i >= 0) list[i] = saved else list += saved
            p.copy(questions = list)
        }
        return saved
    }

    @Synchronized
    fun deleteQuestion(packId: String, id: String) {
        updatePack(packId) { p ->
            require(p.editable) { "这个题库不能修改，可以停用这道题" }
            p.copy(questions = p.questions.filterNot { it.id == id })
        }
    }

    /** 停用 / 启用某道题，只读的包也可以。 */
    @Synchronized
    fun setQuestionEnabled(packId: String, id: String, enabled: Boolean) {
        updatePack(packId) { p -> p.copy(questions = p.questions.map { if (it.id == id) it.copy(enabled = enabled) else it }) }
    }

    private fun setPacks(list: List<QuestionPack>) {
        packs = sorted(list)
        activeQuestions = PackRules.activeQuestions(packs)
        notifyChanged()
    }

    private fun writePack(p: QuestionPack) = save(File(packDir, "${p.id}.json"), p.toLocalJson().toString())

    /** 内置包在前，其余按添加时间。 */
    private fun sorted(list: List<QuestionPack>) = list.sortedWith(compareBy({ it.source != PackSource.BUILTIN }, { it.installedAt }, { it.id }))

    // ---- 计时状态 ----

    @Volatile private var used: Int = prefs.getInt("used", 0)

    /** 已观看秒数。计时每秒更新内存值，[persist] 为 false 时不落盘以减少闪存写入。 */
    val usedSeconds: Int get() = used

    fun setUsed(value: Int, persist: Boolean = true) {
        used = value
        if (persist) prefs.edit().putInt("used", value).apply()
    }

    var locked: Boolean
        get() = prefs.getBoolean("locked", false)
        set(v) = prefs.edit().putBoolean("locked", v).commit().let { }

    /** 家长暂停管控：暂停时不计时也不锁屏。 */
    var paused: Boolean
        get() = prefs.getBoolean("paused", false)
        set(v) { prefs.edit().putBoolean("paused", v).apply(); notifyChanged() }

    /** 首次启动缺权限时已自动打开过授权页；之后只在主页提供入口，不再自动弹出。 */
    var setupPrompted: Boolean
        get() = prefs.getBoolean("setupPrompted", false)
        set(v) = prefs.edit().putBoolean("setupPrompted", v).apply()

    /** 家长已走完（或跳过）首次使用引导。 */
    var onboarded: Boolean
        get() = prefs.getBoolean("onboarded", false)
        set(v) = prefs.edit().putBoolean("onboarded", v).apply()

    /** 孩子的首页已经出过一次操作提示（方向键选、确定进入）。 */
    var homeHinted: Boolean
        get() = prefs.getBoolean("homeHinted", false)
        set(v) = prefs.edit().putBoolean("homeHinted", v).apply()

    /** 家长从手机额外加的时长（秒），解锁或下一轮开始时清零。 */
    var bonusSeconds: Int
        get() = prefs.getInt("bonus", 0)
        set(v) = prefs.edit().putInt("bonus", v).apply()

    val limitSeconds: Int get() = settings.watchMinutes * 60 + bonusSeconds

    // ---- 作答记录 ----

    @Synchronized
    fun appendLog(entry: AnswerLog) {
        val logs = (listOf(entry) + logs()).take(MAX_LOGS)
        save(logFile, JSONArray(logs.map { it.toJson() }).toString())
        saveDaily(DailyStats.record(daily(), entry))
    }

    /** 按天汇总的统计，比 [logs] 保留得久。 */
    fun daily(): Map<String, DayStats> = load(dailyFile)
        ?.let { runCatching { DailyStats.fromJson(JSONObject(it)) }.getOrNull() }
        ?: emptyMap()

    /** 一轮答题通过（[study] 为主动学习，否则是锁屏答题）。 */
    @Synchronized
    fun recordRound(study: Boolean, time: Long = System.currentTimeMillis()) =
        saveDaily(DailyStats.recordRound(daily(), study, time))

    /** 贴纸收集和今天已得的加时。 */
    fun rewards(): RewardState = load(rewardsFile)
        ?.let { runCatching { RewardState.fromJson(JSONObject(it)) }.getOrNull() }
        ?: RewardState()

    /**
     * 一轮答题通过：记入统计，抽一张贴纸；主动学习（[study]）且守护没暂停、当前没锁定时再加时。
     * 加时直接加到本轮的 [bonusSeconds]，下次解锁时清零，和家长在手机上加时是同一个机制。
     */
    @Synchronized
    fun completeRound(study: Boolean, random: Random = Random.Default, time: Long = System.currentTimeMillis()): RoundReward {
        recordRound(study, time)
        val s = settings
        val reward = RewardRules.complete(rewards(), study && !paused && !locked, s.rewardMinutesPerRound, s.rewardDailyCapMinutes, time, random)
        save(rewardsFile, reward.state.toJson().toString())
        if (reward.minutes > 0) bonusSeconds += reward.minutes * 60
        return reward
    }

    private fun saveDaily(stats: Map<String, DayStats>) = save(dailyFile, DailyStats.toJson(stats).toString())

    /** 数学自适应出题的当前范围（0 表示还没调整过）及上次调整的时间。 */
    var mathLevel: Int
        get() = prefs.getInt("mathLevel", 0)
        private set(v) = prefs.edit().putInt("mathLevel", v).apply()

    private var mathLevelSince: Long
        get() = prefs.getLong("mathLevelSince", 0)
        set(v) = prefs.edit().putLong("mathLevelSince", v).apply()

    /** 一轮答题通过后，按新的作答记录调整数学范围。 */
    @Synchronized
    fun adaptMath(time: Long = System.currentTimeMillis()) {
        val config = settings.math
        val next = AdaptiveMath.next(config, mathLevel, logs(), mathLevelSince)
        if (next != AdaptiveMath.level(config, mathLevel)) {
            mathLevel = next
            mathLevelSince = time
        }
    }

    fun logs(): List<AnswerLog> = load(logFile)
        ?.let { text -> JSONArray(text).let { a -> (0 until a.length()).map { AnswerLog.fromJson(a.getJSONObject(it)) } } }
        ?: emptyList()

    private fun notifyChanged() = listeners.forEach { it() }

    private fun load(file: File): String? = runCatching { file.takeIf { it.exists() }?.readText() }.getOrNull()

    /** 先写临时文件再改名，避免断电写出半个文件。 */
    private fun save(file: File, text: String) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(text)
        tmp.renameTo(file)
    }

    companion object {
        private const val MAX_LOGS = 1000
        private const val KEY_REMOVED_BUILTINS = "removedBuiltins"
        private const val KEY_REMOVED_BUNDLED = "removedBundled"
        private const val TAG = "Store"
    }
}
