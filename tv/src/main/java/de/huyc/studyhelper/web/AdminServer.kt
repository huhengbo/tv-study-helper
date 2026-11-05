package de.huyc.studyhelper.web

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.SystemClock
import android.util.Log
import de.huyc.studyhelper.App
import de.huyc.studyhelper.BuildConfig
import de.huyc.studyhelper.core.ForegroundTracker
import de.huyc.studyhelper.core.Permissions
import de.huyc.studyhelper.data.AnswerLog
import de.huyc.studyhelper.data.PackFormat
import de.huyc.studyhelper.data.PackIndex
import de.huyc.studyhelper.data.PackRules
import de.huyc.studyhelper.data.PackSource
import de.huyc.studyhelper.data.Question
import de.huyc.studyhelper.data.QuestionPack
import de.huyc.studyhelper.data.AnswerMode
import de.huyc.studyhelper.data.SpeechLanguage
import de.huyc.studyhelper.data.Subject
import de.huyc.studyhelper.data.enumOr
import de.huyc.studyhelper.data.Store
import de.huyc.studyhelper.data.VoiceConfig
import de.huyc.studyhelper.data.strings
import de.huyc.studyhelper.quiz.AnswerMatcher
import de.huyc.studyhelper.quiz.QuizMode
import de.huyc.studyhelper.ui.Emoji
import de.huyc.studyhelper.ui.Themes
import de.huyc.studyhelper.voice.LocalAsr
import de.huyc.studyhelper.voice.LocalAsrProvider
import de.huyc.studyhelper.voice.VoiceProviders
import de.huyc.studyhelper.voice.VoiceRecorder
import fi.iki.elonen.NanoHTTPD
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.security.SecureRandom
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

/**
 * 手机管理网页的服务端：静态页面来自 assets/web，接口均为 JSON，除登录外都需要家长 PIN 换来的令牌。
 * 只监听局域网，不对公网开放。
 */
class AdminServer(private val context: Context, private val store: Store) : NanoHTTPD(PORT) {

    // newKeySet 需要 API 24，目标电视是 API 23
    private val tokens: MutableSet<String> = Collections.newSetFromMap(ConcurrentHashMap())
    private val loginFailures = ArrayDeque<Long>()
    private val iconCache = ConcurrentHashMap<String, ByteArray>()
    private val app get() = App.instance

    override fun serve(session: IHTTPSession): Response = try {
        val uri = session.uri
        when {
            uri.startsWith("/api/") -> api(session, uri.removePrefix("/api/"))
            else -> asset(uri)
        }
    } catch (e: HttpError) {
        json(e.status, JSONObject().put("error", e.message.orEmpty()))
    } catch (e: IllegalArgumentException) {
        // 业务校验失败（如修改只读题库），消息直接给家长看
        json(Response.Status.BAD_REQUEST, JSONObject().put("error", e.message.orEmpty()))
    } catch (e: java.io.IOException) {
        json(Response.Status.BAD_REQUEST, JSONObject().put("error", e.message.orEmpty()))
    } catch (e: Exception) {
        Log.w(TAG, "请求失败 ${session.uri}", e)
        json(Response.Status.INTERNAL_ERROR, JSONObject().put("error", e.message ?: e.toString()))
    }

    private fun api(session: IHTTPSession, path: String): Response {
        val method = session.method
        if (path == "login" && method == Method.POST) return login(body(session))
        val token = session.headers["x-token"]
        if (token == null || token !in tokens) throw HttpError(Response.Status.UNAUTHORIZED, "请先输入家长密码")

        return when ("${method.name} $path") {
            "GET status" -> ok(status())
            "GET settings" -> ok(store.settings.toJson(maskSecrets = true).put("pinLength", store.settings.pin.length))
            "PUT settings" -> {
                val patch = body(session)
                store.updateSettings { it.merge(patch) }
                ok(store.settings.toJson(maskSecrets = true))
            }
            "GET apps" -> ok(JSONObject().put("apps", apps()))
            "GET icon" -> icon(session.parameters["pkg"]?.firstOrNull().orEmpty())
            "GET packs" -> ok(packs())
            "GET pack" -> ok(packDetail(requirePack(param(session, "id"))))
            "POST pack" -> ok(packMeta(savePackMeta(body(session)), emptyList(), 0))
            "DELETE pack" -> { store.deletePack(param(session, "id")); ok(JSONObject()) }
            "POST pack/enable" -> body(session).let { b ->
                store.updatePack(requirePack(b.optString("id")).id) { it.copy(enabled = b.optBoolean("enabled")) }
                ok(JSONObject())
            }
            "POST pack/only" -> { store.enableOnly(requirePack(body(session).optString("id")).id); ok(JSONObject()) }
            "POST pack/copy" -> {
                val copy = store.savePack(requirePack(body(session).optString("id")).copyAsLocal(System.currentTimeMillis()))
                ok(JSONObject().put("id", copy.id).put("name", copy.name))
            }
            "POST pack/upload" -> ok(installPack(body(session), PackSource.FILE))
            "GET pack/export" -> exportPack(requirePack(param(session, "id")))
            "POST packs/restore" -> { store.restoreBuiltins(); ok(JSONObject()) }
            "GET shared" -> ok(shared())
            "POST shared/install" -> ok(installShared(body(session).optString("id")))
            "POST questions" -> body(session).let { b -> ok(store.upsertQuestion(requirePack(b.optString("packId")).id, question(b)).toJson()) }
            "DELETE questions" -> { store.deleteQuestion(param(session, "pack"), param(session, "id")); ok(JSONObject()) }
            "POST questions/enable" -> body(session).let { b ->
                store.setQuestionEnabled(requirePack(b.optString("packId")).id, b.optString("id"), b.optBoolean("enabled"))
                ok(JSONObject())
            }
            "POST questions/import" -> ok(JSONObject().put("added", import(body(session))))
            "GET logs" -> ok(JSONObject().put("logs", JSONArray(store.logs().map { it.toJson() })))
            "POST lock" -> { app.lock.lock(); ok(JSONObject()) }
            "POST unlock" -> { app.lock.unlock(); ok(JSONObject()) }
            "POST addtime" -> { app.lock.addTime(body(session).optInt("minutes", 10).coerceIn(1, 240)); ok(JSONObject()) }
            "POST pause" -> {
                val paused = body(session).optBoolean("paused")
                store.paused = paused
                if (paused && store.locked) app.lock.unlock()
                ok(JSONObject())
            }
            "POST preview" -> { app.lock.showQuiz(QuizMode.PREVIEW); ok(JSONObject()) }
            "GET update" -> ok(update(session.parameters["refresh"]?.firstOrNull() == "1"))
            "POST update/install" -> { app.updater.start(store.locked); ok(app.updater.status.toJson()) }
            "GET update/status" -> ok(app.updater.status.toJson())
            "POST test/tts" -> testTts(body(session).optString("text", "小朋友你好，我们来答题吧"))
            "POST test/asr" -> testAsr()
            "GET voice/offline" -> ok(app.asrPack.toJson())
            "POST voice/offline/install" -> { app.asrPack.start(); ok(app.asrPack.toJson()) }
            "POST voice/offline/remove" -> { app.asrPack.remove(); ok(app.asrPack.toJson()) }
            else -> throw HttpError(Response.Status.NOT_FOUND, "未知接口")
        }
    }

    // ---- 版本更新 ----

    private fun update(refresh: Boolean): JSONObject {
        val c = app.updater.check(force = refresh)
        return JSONObject()
            .put("current", BuildConfig.VERSION_NAME)
            .put("available", c.available)
            .put("error", c.error)
            .put("latest", c.manifest?.let { m ->
                JSONObject().put("version", m.version).put("date", m.date).put("notes", m.notes).put("size", m.apk.size)
            } ?: JSONObject.NULL)
            .put("status", app.updater.status.toJson())
    }

    // ---- 登录 ----

    @Synchronized
    private fun login(body: JSONObject): Response {
        val now = System.currentTimeMillis()
        while (loginFailures.isNotEmpty() && now - loginFailures.first() > 60_000) loginFailures.removeFirst()
        if (loginFailures.size >= 5) throw HttpError(Response.Status.FORBIDDEN, "密码错误次数太多，请 1 分钟后再试")
        if (body.optString("pin") != store.settings.pin) {
            loginFailures.addLast(now)
            throw HttpError(Response.Status.UNAUTHORIZED, "密码不对")
        }
        val token = ByteArray(16).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
        tokens += token
        return ok(JSONObject().put("token", token))
    }

    // ---- 状态 ----

    private fun status(): JSONObject {
        val pm = context.packageManager
        val fg = ForegroundTracker.current(context)
        val fgLabel = fg?.let { runCatching { pm.getApplicationLabel(pm.getApplicationInfo(it, 0)).toString() }.getOrNull() }
        return JSONObject()
            .put("locked", store.locked)
            .put("paused", store.paused)
            .put("usedSeconds", store.usedSeconds)
            .put("limitSeconds", store.limitSeconds)
            .put("foreground", fg)
            .put("foregroundLabel", fgLabel)
            .put("version", BuildConfig.VERSION_NAME)
            .put("ttsCached", app.speaker.cachedCount())
            .put("themes", JSONArray(Themes.all.map { JSONObject().put("id", it.id).put("name", it.name) }))
            .put("permissions", Permissions.status(context).let { p ->
                JSONObject().put("overlay", p.overlay).put("record", p.record)
                    .put("accessibility", p.accessibility).put("usage", p.usage)
            })
    }

    // ---- 应用列表 ----

    private fun apps(): JSONArray {
        val pm = context.packageManager
        val launchers = listOf(Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER).flatMap { cat ->
            pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(cat), 0)
        }
        val selected = store.settings.apps.toSet()
        return JSONArray(launchers
            .map { it.activityInfo.packageName to it.loadLabel(pm).toString() }
            .filter { it.first != context.packageName }
            .distinctBy { it.first }
            .sortedWith(compareBy({ it.first !in selected }, { it.second }))
            .map { (pkg, label) -> JSONObject().put("pkg", pkg).put("label", label).put("selected", pkg in selected) })
    }

    private fun icon(pkg: String): Response {
        val bytes = iconCache.getOrPut(pkg) {
            val d = context.packageManager.getApplicationIcon(pkg)
            val bmp = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888)
            d.setBounds(0, 0, 96, 96)
            d.draw(Canvas(bmp))
            ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        }
        return newFixedLengthResponse(Response.Status.OK, "image/png", bytes.inputStream(), bytes.size.toLong()).apply {
            addHeader("Cache-Control", "max-age=86400")
        }
    }

    // ---- 题库 ----

    private fun question(o: JSONObject): Question {
        val subject = subjectOf(o)
        val mode = enumOr(o.optString("mode"), AnswerMode.VOICE)
        val answers = o.optJSONArray("answers").strings().map { it.trim() }.filter { it.isNotEmpty() }
        if (answers.isEmpty()) throw HttpError(Response.Status.BAD_REQUEST, if (mode == AnswerMode.CHOICE) "请填写正确选项" else "至少填写一个答案")
        val choices = o.optJSONArray("choices").strings().map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        if (mode == AnswerMode.CHOICE && (choices + answers.first()).distinct().size < 2) {
            throw HttpError(Response.Status.BAD_REQUEST, "选择题至少需要一个错误选项")
        }
        val display = o.optString("display").trim()
        if (display.isEmpty()) throw HttpError(Response.Status.BAD_REQUEST, "请填写展示内容")
        if (Emoji.unsupported(context, display)) throw HttpError(Response.Status.BAD_REQUEST, "电视上显示不了这个表情，请点「选表情」从列表里选")
        return Question(
            id = o.optString("id"),
            subject = subject,
            prompt = o.optString("prompt").trim().ifEmpty { subject.defaultPrompt },
            display = display,
            answers = if (mode == AnswerMode.CHOICE) answers.take(1) else answers,
            mode = mode,
            // choices 存全部选项（含正确项），出题时打乱
            choices = if (mode == AnswerMode.CHOICE) (listOf(answers.first()) + choices).distinct() else emptyList(),
            speak = o.optString("speak").trim(),
            enabled = o.optBoolean("enabled", true),
        )
    }

    private fun subjectOf(o: JSONObject): Subject =
        Subject.entries.firstOrNull { it.name == o.optString("subject") }
            ?: throw HttpError(Response.Status.BAD_REQUEST, "科目不对")

    /**
     * 批量导入。选择题（任何科目）每行：「题目文字|大字展示|正确答案|错误选项1|错误选项2…」。
     * 语音题：
     * - 英语：每行「中文 英文 [表情]」，如「香蕉 banana 🍌」
     * - 认字：一段汉字，每个字一道题
     * - 数学：每行「题目=答案」，如「3+4=7」
     * - 拼音：一段汉字，每个字生成一道「选出拼音」四选一
     */
    private fun import(o: JSONObject): Int {
        val pack = requirePack(o.optString("packId"))
        val subject = subjectOf(o)
        val mode = enumOr(o.optString("mode"), AnswerMode.VOICE)
        val text = o.optString("text")
        val parsed = if (mode == AnswerMode.CHOICE) importChoices(subject, text) else when (subject) {
            Subject.HANZI -> text.filter(AnswerMatcher::isHan).toSet().map { c ->
                Question("", subject, subject.defaultPrompt, c.toString(), listOf(c.toString()))
            }
            Subject.ENGLISH -> text.lines().mapNotNull { line ->
                val parts = line.trim().split(Regex("[\\s,，]+")).filter { it.isNotEmpty() }
                val zh = parts.firstOrNull { p -> p.any(AnswerMatcher::isHan) } ?: return@mapNotNull null
                val en = parts.filter { p -> p.all { it.isLetter() && it.code < 128 } }.joinToString(" ").ifEmpty { return@mapNotNull null }
                val emoji = parts.firstOrNull { p -> p != zh && p.none { it.isLetterOrDigit() } }
                Question("", subject, "${zh}的英语是什么？", emoji ?: zh, listOf(en))
            }
            Subject.MATH -> text.lines().mapNotNull { line ->
                val m = Regex("^(.+?)=\\s*(-?\\d+)\\s*$").find(line.trim()) ?: return@mapNotNull null
                Question("", subject, subject.defaultPrompt, "${m.groupValues[1].trim()} = ?", listOf(m.groupValues[2]))
            }
            Subject.PINYIN -> importPinyin(text)
        }
        val existing = pack.questions.map { it.key }.toSet()
        val fresh = parsed.filter { it.key !in existing }.distinctBy { it.key }
        fresh.forEach { store.upsertQuestion(pack.id, it) }
        return fresh.size
    }

    private fun importChoices(subject: Subject, text: String): List<Question> = text.lines().mapNotNull { line ->
        val parts = line.split('|', '｜').map { it.trim() }
        if (parts.size < 4 || parts[1].isEmpty() || parts[2].isEmpty()) return@mapNotNull null
        val choices = (parts.drop(2)).filter { it.isNotEmpty() }.distinct()
        if (choices.size < 2) return@mapNotNull null
        Question("", subject, parts[0].ifEmpty { subject.defaultPrompt }, parts[1], listOf(parts[2]), AnswerMode.CHOICE, choices)
    }

    /** 拼音四选一：干扰项取自同批字和认字题库里其他字的拼音。 */
    private fun importPinyin(text: String): List<Question> {
        val chars = text.filter(AnswerMatcher::isHan).toSet()
        val pool = (chars + store.packs.flatMap { it.questions }.filter { it.subject == Subject.HANZI && it.display.length == 1 }.map { it.display[0] })
            .mapNotNull(AnswerMatcher::tonedPinyin).distinct()
        return chars.mapNotNull { c ->
            val right = AnswerMatcher.tonedPinyin(c) ?: return@mapNotNull null
            val wrong = pool.filter { it != right }.shuffled().take(3)
            if (wrong.size < 3) return@mapNotNull null
            Question("", Subject.PINYIN, Subject.PINYIN.defaultPrompt, c.toString(), listOf(right), AnswerMode.CHOICE, listOf(right) + wrong)
        }
    }

    // ---- 题库包 ----

    private fun param(session: IHTTPSession, name: String) = session.parameters[name]?.firstOrNull().orEmpty()

    private fun requirePack(id: String): QuestionPack = store.pack(id) ?: throw HttpError(Response.Status.NOT_FOUND, "题库不存在，请刷新页面")

    private fun packs(): JSONObject {
        val logs = store.logs()
        val since = System.currentTimeMillis() - 7 * 24 * 3600_000L
        return JSONObject()
            .put("packs", JSONArray(store.packs.map { packMeta(it, logs, since) }))
            .put("activeCount", store.activeQuestions.size)
            .put("sharedReady", PackIndex.urlFor(store.settings).isNotBlank())
    }

    private fun packMeta(p: QuestionPack, logs: List<AnswerLog>, since: Long): JSONObject {
        val stats = PackRules.stats(p, logs, since)
        return JSONObject()
            .put("id", p.id).put("name", p.name).put("icon", p.icon).put("description", p.description)
            .put("author", p.author).put("version", p.version).put("tags", JSONArray(p.tags))
            .put("source", p.source.name).put("sourceLabel", p.source.label)
            .put("enabled", p.enabled).put("editable", p.editable)
            .put("deletable", p.id != PackRules.MY_PACK_ID)
            .put("count", p.questions.size).put("enabledCount", stats.total)
            .put("mastered", stats.mastered).put("recentAnswers", stats.recentAnswers).put("recentCorrect", stats.recentCorrect)
    }

    private fun packDetail(p: QuestionPack): JSONObject {
        val logs = store.logs()
        return packMeta(p, logs, System.currentTimeMillis() - 7 * 24 * 3600_000L)
            .put("questions", JSONArray(p.questions.map { it.toJson() }))
    }

    /** 新建或修改自建题库的名字、图标、简介。 */
    private fun savePackMeta(o: JSONObject): QuestionPack {
        val name = o.optString("name").trim().take(30)
        if (name.isEmpty()) throw HttpError(Response.Status.BAD_REQUEST, "请填写题库名字")
        val icon = o.optString("icon").trim().ifEmpty { "📚" }
        if (Emoji.unsupported(context, icon)) throw HttpError(Response.Status.BAD_REQUEST, "电视上显示不了这个表情，请从列表里选")
        val description = o.optString("description").trim().take(200)
        val id = o.optString("id")
        if (id.isEmpty()) {
            return store.savePack(QuestionPack(PackFormat.newLocalId(), name, icon, description, installedAt = System.currentTimeMillis()))
        }
        return store.updatePack(requirePack(id).id) {
            require(it.editable) { "这个题库不能修改，可以先「复制一份」再改" }
            it.copy(name = name, icon = icon, description = description)
        }
    }

    /** 安装题库文件；电视上显示不了的表情题跳过，并告诉家长跳过了几道。 */
    private fun installPack(o: JSONObject, source: PackSource, url: String = ""): JSONObject {
        val parsed = PackFormat.parse(o)
        val usable = parsed.pack.questions.filterNot { Emoji.unsupported(context, it.display) }
        if (usable.isEmpty()) throw HttpError(Response.Status.BAD_REQUEST, "题库里没有电视能显示的题目")
        val result = store.installPack(parsed.pack.copy(questions = usable), source, url)
        return JSONObject()
            .put("id", parsed.pack.id).put("name", parsed.pack.name)
            .put("updated", result == Store.InstallResult.UPDATED)
            .put("count", usable.size)
            .put("skipped", parsed.skipped + parsed.pack.questions.size - usable.size)
    }

    private fun exportPack(p: QuestionPack): Response {
        val text = p.toPackJson().toString(1)
        return newFixedLengthResponse(Response.Status.OK, "application/json; charset=utf-8", text).apply {
            addHeader("Content-Disposition", "attachment; filename=\"${p.id}.json\"")
        }
    }

    private fun shared(): JSONObject {
        val url = PackIndex.urlFor(store.settings)
        if (url.isBlank()) return JSONObject().put("ready", false).put("packs", JSONArray())
        val installed = store.packs.associateBy { it.id }
        return JSONObject().put("ready", true).put("packs", JSONArray(PackIndex.fetch(url).map { e ->
            val mine = installed[e.id]
            JSONObject().put("id", e.id).put("name", e.name).put("icon", e.icon).put("description", e.description)
                .put("version", e.version).put("count", e.count).put("tags", JSONArray(e.tags))
                .put("state", when {
                    mine == null -> "new"
                    mine.editable -> "local"
                    mine.version < e.version -> "update"
                    else -> "installed"
                })
        }))
    }

    private fun installShared(id: String): JSONObject {
        val url = PackIndex.urlFor(store.settings).ifBlank { throw HttpError(Response.Status.BAD_REQUEST, "分享题库还没开放") }
        val entry = PackIndex.fetch(url).firstOrNull { it.id == id } ?: throw HttpError(Response.Status.NOT_FOUND, "分享题库里没有这个题库")
        return installPack(JSONObject(PackIndex.download(entry.url)), PackSource.SHARED, entry.url)
    }

    // ---- 语音服务测试 ----

    private fun testTts(text: String): Response {
        val provider = VoiceProviders.tts(context, store.settings.voice)
            ?: throw HttpError(Response.Status.BAD_REQUEST, "请先选择朗读服务并填写密钥")
        app.speaker.synthesizeOnce(provider, text) // 同步合成，便于把错误返回给手机
        app.speaker.speak(provider, text)
        return ok(JSONObject().put("message", "已在电视上播放"))
    }

    private fun testAsr(): Response {
        val voice = store.settings.voice
        if (voice.asrProvider == VoiceConfig.PROVIDER_OFFLINE) return testOfflineAsr()
        // 只测在线服务本身，不要让离线识别兜底掩盖密钥错误
        val provider = VoiceProviders.asr(context, voice.copy(asrOfflineFallback = false))
            ?: throw HttpError(Response.Status.BAD_REQUEST, "请先选择识别服务并填写密钥")
        // 1 秒静音：密钥正确时返回空结果，错误时抛出鉴权失败
        val text = provider.recognize(ByteArray(32000), SpeechLanguage.CHINESE)
        return ok(JSONObject().put("message", "连接成功" + if (text.isNotEmpty()) "（识别到：$text）" else ""))
    }

    private fun testOfflineAsr(): Response {
        val pack = app.asrPack
        if (!pack.installed) throw HttpError(Response.Status.BAD_REQUEST, "请先下载离线语音包")
        val started = SystemClock.elapsedRealtime()
        LocalAsr.warmUp(pack.dir)
        val loaded = SystemClock.elapsedRealtime()
        // 和答题时一样边录边识别：按录音的节奏送 2 秒静音，再看「松开按键」后多久出结果
        val live = LocalAsrProvider(pack).startSession(SpeechLanguage.CHINESE, emptyList())
        val chunk = ByteArray(VoiceRecorder.SAMPLE_RATE / 10) // 50ms
        repeat(40) { live.accept(chunk, chunk.size); Thread.sleep(50) }
        val released = SystemClock.elapsedRealtime()
        live.finish()
        val done = SystemClock.elapsedRealtime()
        fun sec(ms: Long) = "%.1f".format(ms / 1000.0)
        return ok(JSONObject().put("message", "离线识别可用（加载模型 ${sec(loaded - started)} 秒，说完后 ${sec(done - released)} 秒出结果）"))
    }

    // ---- 工具 ----

    private fun asset(uri: String): Response {
        val path = if (uri == "/" || uri.isEmpty()) "index.html" else uri.trimStart('/')
        if (path.contains("..")) throw HttpError(Response.Status.FORBIDDEN, "禁止访问")
        if (path == "emoji/list.json") return ok(JSONObject().put("emoji", JSONArray(Emoji.available(context).sorted())))
        // 表情图片与电视上显示的是同一套，不需要登录
        val assetPath = if (path.startsWith("emoji/")) path else "web/$path"
        val bytes = runCatching { context.assets.open(assetPath).use { it.readBytes() } }.getOrNull()
            ?: throw HttpError(Response.Status.NOT_FOUND, "页面不存在")
        val mime = when (path.substringAfterLast('.')) {
            "html" -> "text/html; charset=utf-8"
            "js" -> "application/javascript; charset=utf-8"
            "css" -> "text/css; charset=utf-8"
            "svg" -> "image/svg+xml"
            "png" -> "image/png"
            "webp" -> "image/webp"
            else -> "application/octet-stream"
        }
        return newFixedLengthResponse(Response.Status.OK, mime, bytes.inputStream(), bytes.size.toLong()).apply {
            addHeader("Cache-Control", if (path.startsWith("emoji/")) "max-age=604800" else "no-cache")
        }
    }

    private fun body(session: IHTTPSession): JSONObject {
        val length = session.headers["content-length"]?.toIntOrNull() ?: 0
        if (length <= 0) return JSONObject()
        if (length > MAX_BODY) throw HttpError(Response.Status.BAD_REQUEST, "内容太大")
        val buf = ByteArray(length)
        var read = 0
        while (read < length) {
            val n = session.inputStream.read(buf, read, length - read)
            if (n < 0) break
            read += n
        }
        return runCatching { JSONObject(String(buf, 0, read, Charsets.UTF_8)) }
            .getOrElse { throw HttpError(Response.Status.BAD_REQUEST, "请求格式不对") }
    }

    private fun ok(o: JSONObject) = json(Response.Status.OK, o)

    private fun json(status: Response.Status, o: JSONObject): Response =
        newFixedLengthResponse(status, "application/json; charset=utf-8", o.toString())

    private class HttpError(val status: Response.Status, message: String) : Exception(message)

    companion object {
        const val PORT = 8848
        private const val MAX_BODY = 2 * 1024 * 1024
        private const val TAG = "AdminServer"
    }
}
