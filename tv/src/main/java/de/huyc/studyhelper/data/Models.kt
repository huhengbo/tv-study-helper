package de.huyc.studyhelper.data

import org.json.JSONArray
import org.json.JSONObject

/**
 * 科目：决定题目内容怎么理解、语音答案怎么判分（见 quiz/Judges）。
 * 新增科目：在这里加一项，并在 Judges 里注册判分规则即可；作答界面按 [AnswerMode] 复用。
 */
enum class Subject(val label: String, val defaultPrompt: String, val speechLanguage: SpeechLanguage) {
    MATH("数学", "算一算，等于几？", SpeechLanguage.CHINESE),
    ENGLISH("英语", "用英语怎么说？", SpeechLanguage.ENGLISH),
    HANZI("认字", "这个字怎么读？", SpeechLanguage.CHINESE),
    PINYIN("拼音", "选出这个字的拼音", SpeechLanguage.CHINESE),
}

/** 语音题交给识别服务时使用的语言模型。 */
enum class SpeechLanguage { CHINESE, ENGLISH }

/**
 * 作答方式：与科目正交，任何科目的题都可以是语音题或选择题。
 * 新增作答方式（如判断题）：在这里加一项，并在答题界面 QuizView 里加对应的作答区。
 */
enum class AnswerMode(val label: String) {
    /** 按住语音键说出答案，按科目规则判分。 */
    VOICE("语音回答"),
    /** 方向键在 [Question.choices] 里选择，选中 [Question.answers] 第一项即答对。 */
    CHOICE("选择题"),
}

/**
 * 一道题。
 * - [prompt]：题目文字，如「香蕉的英语是什么？」「这个字怎么读？」
 * - [display]：卡片中央的大字/表情，如 🍌、山、7 + 5 = ?
 * - [answers]：语音题为可接受的答案（认字按读音、数学按数值、英语忽略大小写）；选择题第一项为正确选项
 * - [choices]：选择题的全部选项（含正确项），出题时打乱顺序
 * - [speak]：朗读文本，空时朗读 [prompt]
 */
data class Question(
    val id: String,
    val subject: Subject,
    val prompt: String,
    val display: String,
    val answers: List<String>,
    val mode: AnswerMode = AnswerMode.VOICE,
    val choices: List<String> = emptyList(),
    val speak: String = "",
    val enabled: Boolean = true,
) {
    val speakText: String get() = speak.ifBlank { prompt }

    /** 去重键：多个题库里内容相同的题，出题时只算一道，作答统计也按它归到每个题库。 */
    val key: String get() = "${subject.name}|${mode.name}|${display.trim()}|${answers.firstOrNull()?.trim()?.lowercase().orEmpty()}"

    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("subject", subject.name)
        .put("mode", mode.name)
        .put("prompt", prompt)
        .put("display", display)
        .put("answers", JSONArray(answers))
        .put("choices", JSONArray(choices))
        .put("speak", speak)
        .put("enabled", enabled)

    /** 题库包文件里的写法：省略默认值，便于手写和审阅。 */
    fun toPackJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("subject", subject.name)
        if (mode != AnswerMode.VOICE) put("mode", mode.name)
        if (prompt != subject.defaultPrompt) put("prompt", prompt)
        put("display", display)
        put("answers", JSONArray(answers))
        if (choices.isNotEmpty()) put("choices", JSONArray(choices))
        if (speak.isNotBlank()) put("speak", speak)
    }

    companion object {
        /** 科目或作答方式是新版本才有的，返回 null（老版本跳过这道题）。 */
        fun fromJsonOrNull(o: JSONObject): Question? {
            val subject = Subject.entries.firstOrNull { it.name == o.optString("subject") } ?: return null
            val mode = if (o.has("mode")) AnswerMode.entries.firstOrNull { it.name == o.optString("mode") } ?: return null else AnswerMode.VOICE
            val q = fromJson(o)
            return q.copy(subject = subject, mode = mode, prompt = q.prompt.ifBlank { subject.defaultPrompt })
        }

        fun fromJson(o: JSONObject) = Question(
            id = o.optString("id"),
            // 旧版本字段名为 type
            subject = Subject.valueOf(o.optString("subject").ifEmpty { o.getString("type") }),
            mode = enumOr(o.optString("mode"), AnswerMode.VOICE),
            prompt = o.optString("prompt"),
            display = o.optString("display"),
            answers = o.optJSONArray("answers").strings(),
            choices = o.optJSONArray("choices").strings(),
            speak = o.optString("speak"),
            enabled = o.optBoolean("enabled", true),
        )
    }
}

inline fun <reified E : Enum<E>> enumOr(name: String?, default: E): E =
    enumValues<E>().firstOrNull { it.name == name } ?: default

enum class CountMode { ALL, SELECTED_APPS }

/** 数学自动出题：加减法范围与作答方式（说出得数，或四选一）。 */
data class MathConfig(
    val enabled: Boolean = true,
    val add: Boolean = true,
    val sub: Boolean = true,
    val max: Int = 20,
    val mode: AnswerMode = AnswerMode.VOICE,
    /** 按近期正确率在 [AdaptiveMath.MIN_LEVEL, max] 之间自动调整范围，[max] 是上限。 */
    val adaptive: Boolean = false,
) {
    fun toJson(): JSONObject = JSONObject().put("enabled", enabled).put("add", add).put("sub", sub)
        .put("max", max).put("mode", mode.name).put("adaptive", adaptive)

    companion object {
        fun fromJson(o: JSONObject?) = if (o == null) MathConfig() else MathConfig(
            enabled = o.optBoolean("enabled", true),
            add = o.optBoolean("add", true),
            sub = o.optBoolean("sub", true),
            max = o.optInt("max", 20).coerceIn(5, 1000),
            mode = enumOr(o.optString("mode"), AnswerMode.VOICE),
            adaptive = o.optBoolean("adaptive", false),
        )
    }
}

/**
 * 语音服务设置。识别可选离线（电视本机，需先下载离线语音包）或在线服务。
 * 第三方语音服务凭据：百度用 API Key/Secret Key；火山新版控制台用 apiKey，旧版用 appId + accessKey。
 */
data class VoiceConfig(
    val asrProvider: String = PROVIDER_NONE,
    /** 在线识别出错或没联网时改用离线识别（已下载离线语音包时生效）。 */
    val asrOfflineFallback: Boolean = true,
    val ttsEnabled: Boolean = false,
    val ttsProvider: String = PROVIDER_NONE,
    val ttsVoice: String = "",
    val ttsSpeed: Int = 5,
    val baiduApiKey: String = "",
    val baiduSecretKey: String = "",
    val volcAppId: String = "",
    val volcAccessKey: String = "",
    val volcApiKey: String = "",
) {
    fun toJson(maskSecrets: Boolean = false): JSONObject {
        fun s(v: String) = if (maskSecrets && v.isNotEmpty()) MASK else v
        return JSONObject()
            .put("asrProvider", asrProvider)
            .put("asrOfflineFallback", asrOfflineFallback)
            .put("ttsEnabled", ttsEnabled)
            .put("ttsProvider", ttsProvider)
            .put("ttsVoice", ttsVoice)
            .put("ttsSpeed", ttsSpeed)
            .put("baiduApiKey", baiduApiKey)
            .put("baiduSecretKey", s(baiduSecretKey))
            .put("volcAppId", volcAppId)
            .put("volcAccessKey", s(volcAccessKey))
            .put("volcApiKey", s(volcApiKey))
    }

    /** 手机端回传的掩码值表示「未修改」，保留原密钥。 */
    fun merge(o: JSONObject): VoiceConfig {
        fun keep(key: String, old: String) = o.optString(key, old).let { if (it == MASK) old else it.trim() }
        return VoiceConfig(
            asrProvider = o.optString("asrProvider", asrProvider),
            asrOfflineFallback = o.optBoolean("asrOfflineFallback", asrOfflineFallback),
            ttsEnabled = o.optBoolean("ttsEnabled", ttsEnabled),
            ttsProvider = o.optString("ttsProvider", ttsProvider),
            ttsVoice = o.optString("ttsVoice", ttsVoice).trim(),
            ttsSpeed = o.optInt("ttsSpeed", ttsSpeed).coerceIn(0, 15),
            baiduApiKey = keep("baiduApiKey", baiduApiKey),
            baiduSecretKey = keep("baiduSecretKey", baiduSecretKey),
            volcAppId = keep("volcAppId", volcAppId),
            volcAccessKey = keep("volcAccessKey", volcAccessKey),
            volcApiKey = keep("volcApiKey", volcApiKey),
        )
    }

    companion object {
        const val PROVIDER_NONE = "none"
        const val PROVIDER_BAIDU = "baidu"
        const val PROVIDER_VOLC = "volc"
        const val PROVIDER_OFFLINE = "offline"
        private const val MASK = "******"

        fun fromJson(o: JSONObject?) = if (o == null) VoiceConfig() else VoiceConfig().merge(o)
    }
}

data class Settings(
    val watchMinutes: Int = 30,
    val questionCount: Int = 3,
    /** 答错后已答对的题数清零（kidslock 的「连续答对」规则）。 */
    val wrongResets: Boolean = false,
    /** 近期答错的题更常出现，同一轮里答错的题隔两题再考一次。 */
    val reviewWrong: Boolean = true,
    /** 答对、答错、通关的短音效。 */
    val soundEffects: Boolean = true,
    /** 孩子主动学习一轮需要答对的题数。 */
    val studyQuestionCount: Int = 5,
    /** 主动学习通过一轮得到的加时（分钟），0 表示不加时；每天累计不超过 [rewardDailyCapMinutes]。 */
    val rewardMinutesPerRound: Int = 5,
    val rewardDailyCapMinutes: Int = 15,
    val autoStart: Boolean = true,
    val pin: String = "1234",
    /** 允许在电视上用方向键输密码（孩子可能看到），默认只能手机扫码验证。 */
    val tvPinUnlock: Boolean = false,
    val countMode: CountMode = CountMode.SELECTED_APPS,
    val apps: List<String> = emptyList(),
    val warnSeconds: Int = 60,
    val resumePlayback: Boolean = true,
    val theme: String = "kids",
    /** 参与出题的科目。 */
    val subjects: Set<Subject> = Subject.entries.toSet(),
    val math: MathConfig = MathConfig(),
    /** 拼音题除题库外，自动用认字题库里的字生成「选出拼音」四选一。 */
    val pinyinAuto: Boolean = true,
    val voice: VoiceConfig = VoiceConfig(),
    /** 分享题库目录（index.json）地址，空表示使用程序内置的默认地址。 */
    val packIndexUrl: String = "",
) {
    fun toJson(maskSecrets: Boolean = false): JSONObject = JSONObject()
        .put("watchMinutes", watchMinutes)
        .put("questionCount", questionCount)
        .put("wrongResets", wrongResets)
        .put("reviewWrong", reviewWrong)
        .put("soundEffects", soundEffects)
        .put("studyQuestionCount", studyQuestionCount)
        .put("rewardMinutesPerRound", rewardMinutesPerRound)
        .put("rewardDailyCapMinutes", rewardDailyCapMinutes)
        .put("autoStart", autoStart)
        .put("pin", if (maskSecrets) "" else pin)
        .put("tvPinUnlock", tvPinUnlock)
        .put("countMode", countMode.name)
        .put("apps", JSONArray(apps))
        .put("warnSeconds", warnSeconds)
        .put("resumePlayback", resumePlayback)
        .put("theme", theme)
        .put("subjects", JSONArray(subjects.map { it.name }))
        .put("math", math.toJson())
        .put("pinyinAuto", pinyinAuto)
        .put("voice", voice.toJson(maskSecrets))
        .put("packIndexUrl", packIndexUrl)

    /** 合并手机端提交的部分字段；空 PIN 表示不修改。 */
    fun merge(o: JSONObject): Settings = copy(
        watchMinutes = o.optInt("watchMinutes", watchMinutes).coerceIn(1, 600),
        questionCount = o.optInt("questionCount", questionCount).coerceIn(1, 20),
        wrongResets = o.optBoolean("wrongResets", wrongResets),
        reviewWrong = o.optBoolean("reviewWrong", reviewWrong),
        soundEffects = o.optBoolean("soundEffects", soundEffects),
        studyQuestionCount = o.optInt("studyQuestionCount", studyQuestionCount).coerceIn(1, 20),
        rewardMinutesPerRound = o.optInt("rewardMinutesPerRound", rewardMinutesPerRound).coerceIn(0, 60),
        rewardDailyCapMinutes = o.optInt("rewardDailyCapMinutes", rewardDailyCapMinutes).coerceIn(0, 240),
        autoStart = o.optBoolean("autoStart", autoStart),
        tvPinUnlock = o.optBoolean("tvPinUnlock", tvPinUnlock),
        pin = o.optString("pin", "").trim().takeIf { it.matches(Regex("\\d{4,8}")) } ?: pin,
        countMode = o.optString("countMode").let { v -> CountMode.entries.firstOrNull { it.name == v } } ?: countMode,
        apps = o.optJSONArray("apps")?.strings() ?: apps,
        warnSeconds = o.optInt("warnSeconds", warnSeconds).coerceIn(0, 600),
        resumePlayback = o.optBoolean("resumePlayback", resumePlayback),
        theme = o.optString("theme", theme),
        subjects = o.optJSONArray("subjects")?.strings()
            ?.mapNotNull { v -> Subject.entries.firstOrNull { it.name == v } }?.toSet()?.ifEmpty { null } ?: subjects,
        math = if (o.has("math")) MathConfig.fromJson(o.optJSONObject("math")) else math,
        pinyinAuto = o.optBoolean("pinyinAuto", pinyinAuto),
        voice = o.optJSONObject("voice")?.let { voice.merge(it) } ?: voice,
        packIndexUrl = o.optString("packIndexUrl", packIndexUrl).trim()
            .takeIf { it.isEmpty() || it.matches(Regex("https?://\\S+")) } ?: packIndexUrl,
    )

    companion object {
        fun fromJson(o: JSONObject?): Settings {
            if (o == null) return Settings()
            migrate(o)
            val base = Settings().merge(o)
            // fromJson 读取本地文件，PIN 允许原样恢复
            return base.copy(pin = o.optString("pin").ifBlank { base.pin })
        }

        /** 0.1 版用 types + hanziMode（认字题的拼音四选一）；现在拼音是独立科目。 */
        private fun migrate(o: JSONObject) {
            if (o.has("subjects") || !o.has("types")) return
            val list = o.optJSONArray("types").strings().toMutableList()
            if (o.optString("hanziMode") == "CHOICE") list += Subject.PINYIN.name
            o.put("subjects", JSONArray(list))
        }
    }
}

/** 一次作答记录，供手机端查看。[key] 为 [Question.key]，用于按题库统计。 */
data class AnswerLog(
    val time: Long,
    val questionId: String,
    val display: String,
    val heard: String,
    val correct: Boolean,
    val key: String = "",
) {
    fun toJson(): JSONObject = JSONObject()
        .put("time", time).put("questionId", questionId).put("display", display)
        .put("heard", heard).put("correct", correct).put("key", key)

    companion object {
        fun fromJson(o: JSONObject) = AnswerLog(
            o.optLong("time"), o.optString("questionId"), o.optString("display"),
            o.optString("heard"), o.optBoolean("correct"), o.optString("key"),
        )
    }
}

fun JSONArray?.strings(): List<String> =
    if (this == null) emptyList() else (0 until length()).map { getString(it) }
