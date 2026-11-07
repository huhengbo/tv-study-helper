package de.huyc.studyhelper.ui

import android.animation.ObjectAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.Animatable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.TextUtils
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.animation.AccelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.dynamicanimation.animation.DynamicAnimation
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
import de.huyc.studyhelper.data.AnswerLog
import de.huyc.studyhelper.data.VoiceConfig
import de.huyc.studyhelper.data.AnswerMode
import de.huyc.studyhelper.data.Question
import de.huyc.studyhelper.data.SpriteCache
import de.huyc.studyhelper.data.Subject
import de.huyc.studyhelper.data.Store
import de.huyc.studyhelper.quiz.AnswerMatcher
import de.huyc.studyhelper.quiz.QuizItem
import de.huyc.studyhelper.quiz.QuizSession
import de.huyc.studyhelper.voice.AsrProvider
import de.huyc.studyhelper.voice.AsrSession
import de.huyc.studyhelper.data.Draw
import de.huyc.studyhelper.data.Rarity
import de.huyc.studyhelper.data.RoundReward
import de.huyc.studyhelper.quiz.QuizMode
import de.huyc.studyhelper.voice.Phrases
import de.huyc.studyhelper.voice.Speaker
import de.huyc.studyhelper.voice.VoiceKey
import de.huyc.studyhelper.voice.VoiceProviders
import de.huyc.studyhelper.voice.VoiceRecorder
import java.util.concurrent.Executors

/**
 * 答题界面：顶部是小动物和星星进度，中间是题卡，下方是语音按钮或拼音选项。
 * 按住语音键录音，松开后识别并判分；菜单键打开家长 PIN 解锁。
 * [onFinish] 参数为 true 表示答题通过或家长解锁。
 */
@SuppressLint("ViewConstructor")
class QuizView(
    context: Context,
    private val store: Store,
    private val speaker: Speaker,
    items: List<QuizItem>,
    /** 锁屏答题、家长试一试或孩子主动学习，见 [QuizMode]。 */
    private val mode: QuizMode,
    private val onFinish: (passed: Boolean) -> Unit,
) : FrameLayout(context) {

    private enum class Phase { INTRO, READY, LISTENING, THINKING, CORRECT, WRONG, DONE }

    private val settings = store.settings
    private val theme = Themes.get(settings.theme)
    private val required = if (mode == QuizMode.STUDY) settings.studyQuestionCount else settings.questionCount
    // 主动学习答错不清零，鼓励多试
    private val session = QuizSession(items, required, settings.wrongResets && mode != QuizMode.STUDY, settings.reviewWrong)
    private val greeting = if (mode == QuizMode.STUDY) Phrases.studyGreeting(required) else Phrases.greeting(required)
    private val asr: AsrProvider? = VoiceProviders.asr(context, settings.voice)
    private val tts = if (settings.voice.ttsEnabled) VoiceProviders.tts(context, settings.voice) else null
    private val recorder = VoiceRecorder(context)
    /** 正在进行的边录边识别，只在主线程读写。 */
    private var liveAsr: AsrSession? = null
    private val worker = Executors.newSingleThreadExecutor()
    private val sfx = Sfx(settings.soundEffects)
    private val main = Handler(Looper.getMainLooper())
    private var phase = Phase.INTRO
    private var released = false
    private var spriteToken = 0
    private var gate: ParentGate? = null
    private val menuPresses = mutableListOf<Long>()
    private var choiceViews: List<TextView> = emptyList()
    /** 换题计划的编号，换题后作废同一计划里的兜底计时。 */
    private var advanceToken = 0
    /** 本次录音的最大音量，写日志用于排查没识别到的情况。 */
    @Volatile private var peakLevel = 0f

    // ---- 视图 ----
    private val background = FloatingBackground(context, theme)
    private val mascot = ImageView(context)
    private val bubble = TextView(context)
    private val stars = LinearLayout(context)
    private val card = LinearLayout(context)
    private val prompt = TextView(context)
    private val display = TextView(context)
    private val displayImage = ImageView(context)
    private val rays = RaysView(context)
    private val answerZone = FrameLayout(context)
    private val voiceRow = LinearLayout(context)
    private val mic = MicView(context, theme)
    private val status = TextView(context)
    private val dots = DotsView(context, theme.accent)
    private val choiceRow = android.widget.GridLayout(context)
    private val badge = BadgeView(context)
    private val confetti = ConfettiView(context, listOf(theme.accent, theme.star, theme.success, Color.parseColor("#6FA8FF"), Color.parseColor("#C9B6FF")))
    private val footer = TextView(context)

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        descendantFocusability = FOCUS_AFTER_DESCENDANTS
        addView(background, LayoutParams(-1, -1))
        addView(buildContent(), LayoutParams(-1, -1))
        addView(confetti, LayoutParams(-1, -1))
        // 本轮的题（含随机生成的数学题）和固定用语，提前合成
        speaker.warmUp(tts, Phrases.all(settings) + items.map { it.question.speakText })
        // 之前没下载成功的精灵图在这里重试；后台进行，这一轮没下好的题先显示 display
        SpriteCache.prefetch(context, store)
        preloadSprites(items)
        asr?.let { worker.execute { runCatching { it.prepare() }.onFailure { e -> Log.w(TAG, "语音识别准备失败", e) } } }
    }

    private fun buildContent(): View {
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            clipToPadding = false
            setPadding(context.dp(48), context.dp(28), context.dp(48), context.dp(20))
        }

        // 顶部：小动物 + 对话气泡 + 星星
        val header = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL; clipChildren = false; clipToPadding = false }
        mascot.setImageBitmap(Emoji.bitmap(context, theme.mascots.random()))
        mascot.setPadding(context.dp(12), context.dp(12), context.dp(12), context.dp(12))
        mascot.background = pill(context, theme.card)
        mascot.elevation = context.dp(4f)
        header.addView(mascot, LinearLayout.LayoutParams(context.dp(76), context.dp(76)))
        bubble.text = greeting
        bubble.setTextColor(theme.ink)
        bubble.typeface = BOLD
        bubble.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
        bubble.background = pill(context, withAlpha(theme.card, 0.85f))
        bubble.setPadding(context.dp(22), context.dp(12), context.dp(22), context.dp(12))
        header.addView(bubble, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = context.dp(14) })
        header.addView(View(context), LinearLayout.LayoutParams(0, 1, 1f))
        stars.gravity = Gravity.CENTER_VERTICAL
        stars.background = pill(context, withAlpha(theme.card, 0.85f))
        stars.setPadding(context.dp(10), context.dp(8), context.dp(16), context.dp(8))
        repeat(session.required) {
            stars.addView(StarView(context, theme), LinearLayout.LayoutParams(context.dp(44), context.dp(44)).apply { leftMargin = context.dp(6) })
        }
        header.addView(stars)
        root.addView(header)

        // 中间题卡
        val cardHolder = FrameLayout(context)
        card.orientation = LinearLayout.VERTICAL
        card.gravity = Gravity.CENTER_HORIZONTAL
        card.background = cardBackground(context, theme.card)
        card.elevation = context.dp(10f)
        card.setPadding(context.dp(36), context.dp(22), context.dp(36), context.dp(22))
        prompt.setTextColor(theme.inkSoft)
        prompt.typeface = BOLD
        prompt.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f)
        prompt.gravity = Gravity.CENTER
        card.addView(prompt, LinearLayout.LayoutParams(-1, -2))
        display.setTextColor(theme.ink)
        display.typeface = BOLD
        display.gravity = Gravity.CENTER
        display.includeFontPadding = false
        display.maxLines = 1
        display.ellipsize = TextUtils.TruncateAt.END
        val displayBox = FrameLayout(context)
        displayBox.addView(display, LayoutParams(-1, -1))
        // 比显示区大的正方形，转起来四角也有光芒，超出部分被显示区裁掉
        displayBox.addView(rays, LayoutParams(context.dp(420), context.dp(420), Gravity.CENTER))
        displayImage.cameraDistance = 12000 * resources.displayMetrics.density
        displayBox.addView(displayImage, LayoutParams(context.dp(170), context.dp(170), Gravity.CENTER))
        card.addView(displayBox, LinearLayout.LayoutParams(-1, 0, 1f))

        // 作答区：语音（麦克风+提示）或拼音选项，二者互斥
        voiceRow.gravity = Gravity.CENTER
        voiceRow.addView(mic, LinearLayout.LayoutParams(context.dp(120), context.dp(120)))
        val statusBox = FrameLayout(context)
        status.setTextColor(theme.ink)
        status.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
        status.typeface = BOLD
        statusBox.addView(status, LayoutParams(-2, -2, Gravity.CENTER_VERTICAL))
        statusBox.addView(dots, LayoutParams(context.dp(54), context.dp(24), Gravity.CENTER_VERTICAL or Gravity.END))
        dots.visibility = GONE
        voiceRow.addView(statusBox, LinearLayout.LayoutParams(context.dp(420), -2).apply { leftMargin = context.dp(4) })
        answerZone.addView(voiceRow, LayoutParams(-1, -2, Gravity.CENTER))
        // 选项获得焦点会放大，不能被题卡裁掉
        for (v in listOf(choiceRow, answerZone, card)) { v.clipChildren = false; v.clipToPadding = false }
        answerZone.addView(choiceRow, LayoutParams(-2, -2, Gravity.CENTER))
        answerZone.minimumHeight = context.dp(120)
        card.addView(answerZone, LinearLayout.LayoutParams(-1, -2))

        cardHolder.addView(card, LayoutParams(context.dp(660), -1, Gravity.CENTER))
        badge.elevation = context.dp(14f)
        badge.visibility = INVISIBLE
        // 徽章挂在题卡右上角，不遮挡题目
        badge.translationX = context.dp(300f)
        cardHolder.clipChildren = false
        cardHolder.addView(badge, LayoutParams(context.dp(84), context.dp(84), Gravity.CENTER_HORIZONTAL or Gravity.TOP))
        root.addView(cardHolder, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = context.dp(18); bottomMargin = context.dp(10) })

        // 正式锁定时不提示家长入口（长按菜单键），避免孩子发现
        footer.text = when (mode) {
            QuizMode.GATE -> ""
            QuizMode.PREVIEW -> "试一试模式：按返回键退出"
            QuizMode.STUDY -> "按返回键可以退出，这一轮就不算啦"
        }
        footer.setTextColor(withAlpha(theme.inkSoft, 0.7f))
        footer.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        footer.gravity = Gravity.END
        root.addView(footer, LinearLayout.LayoutParams(-1, -2))
        return root
    }

    // ---- 流程 ----

    fun start() {
        alpha = 0f
        animate().alpha(1f).setDuration(350).start()
        card.translationY = context.dp(120f)
        card.alpha = 0f
        card.animate().alpha(1f).setDuration(250).start()
        spring(card, DynamicAnimation.TRANSLATION_Y, 0f, SpringForce.STIFFNESS_LOW, 0.62f)
        bind(session.current)
        // 先说开场白，再读第一题
        main.postDelayed({ if (!released) speaker.speak(tts, greeting) { readQuestion() } }, 450)
    }

    fun release() {
        released = true
        cancelLiveAsr()
        recorder.stop()
        speaker.stop()
        sfx.release()
        stopSprite()
        main.removeCallbacksAndMessages(null)
        worker.shutdownNow()
    }

    private fun bind(item: QuizItem) {
        val q = item.question
        prompt.text = q.prompt
        val size = when {
            (q.subject == Subject.HANZI || q.subject == Subject.PINYIN) && q.display.length <= 2 -> 150f
            q.display.codePointCount(0, q.display.length) <= 2 -> 120f
            q.display.length <= 10 -> 80f
            else -> 52f
        }
        display.setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
        showQuestion(q)
        badge.visibility = INVISIBLE
        when (item.mode) {
            AnswerMode.VOICE -> showVoice()
            AnswerMode.CHOICE -> showChoices(item)
        }
        phase = Phase.READY
    }

    private fun showVoice() {
        choiceRow.visibility = GONE
        choiceRow.removeAllViews()
        choiceViews = emptyList()
        voiceRow.visibility = VISIBLE
        mic.listening = false
        setStatus(if (asr == null) "还没有设置语音识别，请家长在手机上设置" else READY_TEXT, asr == null)
        requestFocus()
    }

    private fun showChoices(item: QuizItem) {
        voiceRow.visibility = GONE
        choiceRow.visibility = VISIBLE
        choiceRow.removeAllViews()
        // 选项短（拼音、数字）排一行；有长单词时排成两列，避免挤出题卡
        val long = item.choices.any { it.length > 6 }
        choiceRow.columnCount = if (long) 2 else item.choices.size
        val (w, h, sp) = if (long) Triple(250, 62, 24f) else Triple(130, 72, 30f)
        choiceViews = item.choices.mapIndexed { i, label ->
            TextView(context).apply {
                text = label
                gravity = Gravity.CENTER
                typeface = BOLD
                setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
                maxLines = 1
                setTextColor(theme.ink)
                isFocusable = true
                val normal = pill(context, theme.bgTop, theme.starEmpty, 3)
                val focused = pill(context, theme.accent)
                background = normal
                setOnFocusChangeListener { v, has ->
                    v.background = if (has) focused else normal
                    (v as TextView).setTextColor(if (has) Color.WHITE else theme.ink)
                    v.animate().scaleX(if (has) 1.14f else 1f).scaleY(if (has) 1.14f else 1f)
                        .setInterpolator(OvershootInterpolator(3f)).setDuration(200).start()
                }
                setOnClickListener { if (phase == Phase.READY) choose(i) }
            }.also {
                choiceRow.addView(it, android.widget.GridLayout.LayoutParams().apply {
                    width = context.dp(w); height = context.dp(h)
                    setMargins(context.dp(10), context.dp(6), context.dp(10), context.dp(6))
                })
            }
        }
        // 选项依次弹出
        choiceViews.forEachIndexed { i, v ->
            v.scaleX = 0.3f; v.scaleY = 0.3f; v.alpha = 0f
            v.animate().scaleX(1f).scaleY(1f).alpha(1f).setStartDelay(120L + i * 70L)
                .setInterpolator(OvershootInterpolator(2.2f)).setDuration(320).start()
        }
        choiceViews.firstOrNull()?.requestFocus()
    }

    private fun readQuestion() {
        if (released) return
        speaker.speak(tts, session.current.question.speakText)
    }

    // ---- 按键 ----

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (gate != null) return super.dispatchKeyEvent(event)
        if (VoiceKey.matches(event)) {
            onVoiceKey(event); return true
        }
        if (event.keyCode == KeyEvent.KEYCODE_MENU) {
            // 2 秒内连按 3 次菜单键打开家长面板。小米系统会拦下菜单键、松开后再补发一次瞬时按键，
            // 测不出按住时长，所以不用长按。面板只有扫码入口，孩子误打开也无法解锁。
            if (event.action == KeyEvent.ACTION_UP) {
                val now = event.eventTime
                menuPresses.removeAll { now - it > 2000 }
                menuPresses += now
                if (menuPresses.size >= 3) { menuPresses.clear(); openParentGate() }
            }
            return true
        }
        if (event.action == KeyEvent.ACTION_DOWN) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_BACK -> { if (mode != QuizMode.GATE) finish(false) else wiggleMascot(); return true }
            }
        }
        if (choiceViews.isEmpty()) return true // 语音题不需要方向键，吞掉以免焦点跑掉
        return super.dispatchKeyEvent(event)
    }

    private fun onVoiceKey(event: KeyEvent) {
        val item = session.current
        if (item.mode != AnswerMode.VOICE || asr == null) return
        when (event.action) {
            KeyEvent.ACTION_DOWN -> if (event.repeatCount == 0 && phase == Phase.READY) startListening()
            KeyEvent.ACTION_UP -> if (phase == Phase.LISTENING) recorder.stop()
        }
    }

    /**
     * 按下语音键后遥控器约 0.3 秒才开始传音频，这之前说的话录不到。
     * 所以先显示「准备」，收到第一段音频再「叮」一声提示开口。
     */
    private fun startListening() {
        speaker.stop()
        phase = Phase.LISTENING
        setStatus("准备…")
        val item = session.current
        // 离线识别边录边解码，松开按键后很快出结果
        val live = runCatching { asr?.startSession(item.question.subject.speechLanguage, item.question.answers) }.getOrNull()
        liveAsr = live
        peakLevel = 0f
        recorder.start(object : VoiceRecorder.Listener {
            override fun onLevel(level: Float) { peakLevel = maxOf(peakLevel, level); mic.setLevel(level) }
            override fun onAudio(buf: ByteArray, length: Int) { live?.accept(buf, length) }
            override fun onSteady() { main.post { onAudioStarted() } }
            override fun onFinished(pcm: ByteArray) { main.post { onRecorded(pcm) } }
            override fun onError(message: String) { main.post { cancelLiveAsr(); mic.listening = false; phase = Phase.READY; setStatus(message, true) } }
        })
    }

    private fun onAudioStarted() {
        if (released || phase != Phase.LISTENING) return
        mic.listening = true
        sfx.play(Sound.LISTEN)
        setStatus("请说，说完再松开按键")
    }

    private fun cancelLiveAsr() {
        liveAsr?.cancel()
        liveAsr = null
    }

    private fun onRecorded(pcm: ByteArray) {
        if (released) return
        mic.listening = false
        Log.i(TAG, "录音 ${VoiceRecorder.durationMs(pcm)} ms，最大音量 ${"%.2f".format(peakLevel)}")
        if (VoiceRecorder.durationMs(pcm) < 400) {
            cancelLiveAsr()
            phase = Phase.READY
            setStatus(RETRY_TEXT, true)
            return
        }
        phase = Phase.THINKING
        setStatus("我想想", thinking = true)
        val item = session.current
        val provider = asr ?: return
        val live = liveAsr.also { liveAsr = null }
        worker.execute {
            val result = runCatching { live?.finish() ?: provider.recognize(pcm, item.question.subject.speechLanguage, item.question.answers) }
            main.post {
                if (released) return@post
                result.fold(
                    onSuccess = { heard ->
                        Log.i(TAG, "识别结果「$heard」，答案 ${item.question.answers}")
                        // 什么都没识别出来多半是没录到声音，让孩子再说一次，不算答错
                        if (heard.isBlank()) { phase = Phase.READY; setStatus(RETRY_TEXT, true) }
                        else judge(item, heard, AnswerMatcher.isCorrect(item.question.subject, heard, item.question.answers))
                    },
                    onFailure = { e ->
                        phase = Phase.READY
                        setStatus(if (settings.voice.asrProvider == VoiceConfig.PROVIDER_OFFLINE) "没听清，再说一次吧" else "网络开小差了，再说一次吧", true)
                        Log.w(TAG, "识别失败", e)
                    },
                )
            }
        }
    }

    private fun choose(index: Int) {
        val item = session.current
        judge(item, item.choices[index], index == item.correctChoice)
    }

    // ---- 判分与动效 ----

    private fun judge(item: QuizItem, heard: String, correct: Boolean) {
        store.appendLog(AnswerLog(System.currentTimeMillis(), item.question.id, item.question.display, heard, correct, item.question.key))
        val before = session.correctCount
        when (session.answer(correct)) {
            QuizSession.Result.DONE -> { speakAfterSfx(celebrate(before)); allDone() }
            QuizSession.Result.NEXT -> advanceAfter(CORRECT_MS, celebrate(before))
            QuizSession.Result.WRONG -> advanceAfter(REVEAL_MS, wrong(item, heard, reset = false))
            QuizSession.Result.RESET -> { advanceAfter(REVEAL_MS, wrong(item, heard, reset = true)); resetStars() }
        }
    }

    /** 朗读排在音效之后，避免两个声音叠在一起。[done] 在朗读结束（或没有朗读）时回调。 */
    private fun speakAfterSfx(text: String, gapMs: Long = SFX_MS, done: () -> Unit = {}) {
        if (!settings.soundEffects) { speaker.speak(tts, text, done); return }
        main.postDelayed({ if (!released) speaker.speak(tts, text, done) }, gapMs)
    }

    /**
     * 朗读完 [speech] 再执行 [next]（默认换下一题），且停留不少于 [minMs]。[speech] 为空时只按 [minMs] 计时。
     * 合成卡住时最多等 [MAX_SPEECH_WAIT_MS]，不让答题停在这里。
     */
    private fun advanceAfter(minMs: Long, speech: String?, gapMs: Long = SFX_MS, next: () -> Unit = ::nextQuestion) {
        val token = ++advanceToken
        val earliest = SystemClock.uptimeMillis() + minMs
        val go = Runnable { if (!released && token == advanceToken) { advanceToken++; next() } }
        if (speech == null) { main.postAtTime(go, earliest); return }
        speakAfterSfx(speech, gapMs) { main.postAtTime(go, maxOf(earliest, SystemClock.uptimeMillis() + AFTER_SPEECH_MS)) }
        main.postDelayed(go, MAX_SPEECH_WAIT_MS)
    }

    /** 返回要朗读的表扬，由调用方决定朗读时机。 */
    private fun celebrate(starIndex: Int): String {
        phase = Phase.CORRECT
        val praise = Phrases.PRAISE.random()
        setStatus(praise)
        val left = session.required - session.correctCount
        say(if (left > 0) "$praise 还差 $left 道题" else praise)
        sfx.play(Sound.CORRECT)
        showBadge(true)
        confetti.burst()
        // 题卡弹一下
        card.animate().scaleX(1.05f).scaleY(1.05f).setDuration(140).withEndAction {
            spring(card, DynamicAnimation.SCALE_X, 1f, SpringForce.STIFFNESS_MEDIUM, 0.35f)
            spring(card, DynamicAnimation.SCALE_Y, 1f, SpringForce.STIFFNESS_MEDIUM, 0.35f)
        }.start()
        (stars.getChildAt(starIndex) as? StarView)?.let { star ->
            star.filled = true
            star.scaleX = 0.2f; star.scaleY = 0.2f
            star.animate().scaleX(1f).scaleY(1f).rotation(star.rotation + 144f)
                .setInterpolator(OvershootInterpolator(3.5f)).setDuration(520).start()
        }
        return praise
    }

    /** 答错：公布正确答案，返回要朗读的内容（拼音题不朗读，返回 null）。 */
    private fun wrong(item: QuizItem, heard: String, reset: Boolean): String? {
        phase = Phase.WRONG
        speaker.stop()
        val answer = if (item.mode == AnswerMode.CHOICE) item.choices[item.correctChoice] else item.question.answers.first()
        setStatus("正确答案是「$answer」" + if (reset) "，要重新开始哦" else "", true)
        say(if (item.mode == AnswerMode.VOICE && heard.isNotBlank()) "我听到的是「${heard.take(12)}」" else "不对哦，记住答案")
        // 拼音答案带声调符号，朗读会读错，这类题只显示不朗读
        sfx.play(Sound.WRONG)
        showBadge(false)
        if (item.mode == AnswerMode.CHOICE) highlightAnswer(item.correctChoice)
        ObjectAnimator.ofFloat(card, "translationX", 0f, -28f, 28f, -18f, 18f, -8f, 8f, 0f).setDuration(480).start()
        wiggleMascot()
        return if (item.question.subject != Subject.PINYIN) "不对哦，正确答案是$answer" else null
    }

    /** 选择题答错后标出正确选项，其余选项不再可选。 */
    private fun highlightAnswer(correct: Int) {
        choiceViews.forEachIndexed { i, v ->
            v.isFocusable = false
            v.clearFocus()
            if (i == correct) {
                v.background = pill(context, theme.success)
                v.setTextColor(Color.WHITE)
                v.animate().scaleX(1.14f).scaleY(1.14f).setInterpolator(OvershootInterpolator(3f)).setDuration(200).start()
            }
        }
    }

    private fun resetStars() {
        for (i in 0 until stars.childCount) {
            val star = stars.getChildAt(i) as StarView
            if (star.filled) star.animate().scaleX(0.6f).scaleY(0.6f).setDuration(160).withEndAction {
                star.filled = false
                star.animate().scaleX(1f).scaleY(1f).setDuration(200).start()
            }.start()
        }
    }

    private fun nextQuestion() {
        if (released) return
        val w = card.width.toFloat()
        card.animate().translationX(-w * 0.35f).alpha(0f).setInterpolator(AccelerateInterpolator())
            .setDuration(220).withEndAction {
                bind(session.current)
                card.translationX = w * 0.35f
                card.animate().alpha(1f).setDuration(200).setInterpolator(null).start()
                spring(card, DynamicAnimation.TRANSLATION_X, 0f, SpringForce.STIFFNESS_LOW, 0.7f)
                main.postDelayed({ readQuestion() }, 300)
            }.start()
    }

    private fun allDone() {
        phase = Phase.DONE
        // 家长试一试不计轮数、不发奖励
        val reward = if (mode == QuizMode.PREVIEW) null else store.completeRound(study = mode == QuizMode.STUDY).also { store.adaptMath() }
        val sticker = reward?.draw
        main.postDelayed({
            if (released) return@postDelayed
            if (sticker != null) revealSticker(sticker) else { prompt.text = ""; showDisplay("🎉") }
            badge.animate().scaleX(0f).scaleY(0f).setDuration(200).start()
            val (statusText, sayText, speakText) = doneTexts(reward)
            setStatus(statusText)
            say(sayText)
            voiceRow.visibility = VISIBLE
            choiceRow.visibility = GONE
            mic.visibility = INVISIBLE
            sfx.play(Sound.DONE)
            if (sticker != null) main.postDelayed({ sfx.play(Sound.STICKER) }, STICKER_SFX_DELAY_MS)
            advanceAfter(if (sticker != null) 3400 else 2400, speakText, DONE_SFX_MS) { finish(true) }
            confetti.burst(110)
            for (i in 0 until stars.childCount) {
                stars.getChildAt(i).animate().translationY(-context.dp(14f)).setStartDelay(i * 90L).setDuration(180)
                    .withEndAction { stars.getChildAt(i).animate().translationY(0f).setStartDelay(0).setDuration(260).setInterpolator(OvershootInterpolator(3f)).start() }
                    .start()
            }
        }, 900)
    }

    /**
     * 抽贴纸动画：先出现礼物盒，晃两下，翻过来变成抽到的贴纸，背后散开稀有度颜色的光芒。
     * 翻开的时刻和「抽到贴纸」音效（[STICKER_SFX_DELAY_MS]）对齐。
     */
    private fun revealSticker(sticker: Draw) {
        prompt.text = "抽贴纸…"
        showDisplay("🎁")
        main.postDelayed({
            if (released) return@postDelayed
            ObjectAnimator.ofFloat(displayImage, "rotation", 0f, -12f, 12f, -9f, 9f, 0f).setDuration(REVEAL_SHAKE_MS).start()
        }, REVEAL_SHAKE_DELAY_MS)
        main.postDelayed({
            if (released) return@postDelayed
            displayImage.animate().rotationY(90f).setDuration(REVEAL_FLIP_MS).setInterpolator(AccelerateInterpolator()).withEndAction {
                if (released) return@withEndAction
                displayImage.setImageBitmap(Emoji.bitmap(context, sticker.code))
                displayImage.rotationY = -90f
                displayImage.animate().rotationY(0f).scaleX(1f).scaleY(1f).setDuration(REVEAL_FLIP_MS * 2)
                    .setInterpolator(OvershootInterpolator(1.6f)).start()
                rays.burst(withAlpha(rarityColor(sticker.rarity), 0.45f))
                prompt.text = "抽到${sticker.rarity.label}贴纸" + if (sticker.isNew) "，新的！" else ""
            }.start()
        }, STICKER_SFX_DELAY_MS - REVEAL_FLIP_MS)
    }

    private fun rarityColor(rarity: Rarity) = when (rarity) {
        Rarity.COMMON -> theme.accent
        Rarity.RARE -> theme.accentDark
        Rarity.LEGENDARY -> theme.star
    }

    /** 通关后显示的状态文字、气泡文字和朗读内容。 */
    private fun doneTexts(reward: RoundReward?): Triple<String, String, String> {
        if (mode != QuizMode.STUDY) return Triple("全部答对，太棒啦！", "全部答对，太棒啦！可以继续看啦", Phrases.ALL_DONE)
        val extra = when {
            reward == null -> ""
            reward.minutes > 0 -> "，多了 ${reward.minutes} 分钟观看时间"
            reward.capReached -> "，今天的加时已经攒满啦"
            else -> ""
        }
        return Triple("学习完成，太棒啦！" + extra, "学习完成，太棒啦！" + extra, Phrases.STUDY_DONE)
    }

    private fun finish(passed: Boolean) {
        if (released) return
        animate().alpha(0f).scaleX(1.04f).scaleY(1.04f).setDuration(320).withEndAction { onFinish(passed) }.start()
    }

    // ---- 家长解锁 ----

    /** 家长扫码在手机上解锁（手机端会直接关闭本界面）；电视端密码通过时这里直接放行。 */
    private fun openParentGate() {
        if (phase == Phase.LISTENING) recorder.stop()
        val g = ParentGate(context, theme, "家长解锁", "unlock") { pinVerified ->
            removeView(gate)
            gate = null
            requestFocus()
            choiceViews.firstOrNull()?.requestFocus()
            if (pinVerified) { speaker.stop(); finish(true) }
        }
        gate = g
        addView(g, LayoutParams(-1, -1))
    }

    // ---- 小工具 ----

    /** 本轮题目用到的精灵图提前解码，出题时就能直接显示；只取前几张，免得互相挤出缓存。 */
    private fun preloadSprites(items: List<QuizItem>) {
        items.mapNotNull { it.question.sprite }
            .filter { ref -> ref.spec?.let { SpriteCache.has(context, it) } == true }
            .distinctBy { it.spec?.cacheKey }
            .take(PRELOAD_SHEETS)
            .forEach { Sprites.load(context, it, context.dp(170)) {} }
    }

    /**
     * 题目有精灵图且图可用就显示精灵图（动画则循环播放），否则显示 display。
     * 图还没解码好时先空着等一会儿，不先显示备用表情，否则看起来像同一道题换了一张图；等太久才退回 display。
     */
    private fun showQuestion(q: Question) {
        val ref = q.sprite
        val sheet = ref?.spec
        val ready = ref?.let(Sprites::cached)
        stopSprite()
        if (ready != null) {
            showSprite(ready)
            return
        }
        if (ref == null || sheet == null || !SpriteCache.has(context, sheet)) {
            showDisplay(q.display)
            return
        }
        display.visibility = INVISIBLE
        displayImage.visibility = INVISIBLE
        displayImage.setImageDrawable(null)
        val token = spriteToken
        val fallback = Runnable { if (!released && token == spriteToken) showDisplay(q.display) }
        main.postDelayed(fallback, SPRITE_WAIT_MS)
        Sprites.load(context, ref, context.dp(170)) {
            if (!released && token == spriteToken) {
                main.removeCallbacks(fallback)
                showSprite(it)
            }
        }
    }

    private fun showSprite(d: SpriteDrawable) {
        display.visibility = INVISIBLE
        displayImage.visibility = VISIBLE
        displayImage.setImageDrawable(d)
        popIn()
        d.start()
    }

    /** 停掉正在播放的精灵图动画，并让还没回来的解码结果作废。 */
    private fun stopSprite() {
        spriteToken++
        (displayImage.drawable as? Animatable)?.stop()
    }

    /** 单个表情用图片显示（电视没有彩色表情字体），其余按文字显示。 */
    private fun showDisplay(text: String) {
        stopSprite()
        val emoji = Emoji.single(context, text)
        if (emoji != null) {
            display.visibility = INVISIBLE
            displayImage.visibility = VISIBLE
            displayImage.setImageBitmap(Emoji.bitmap(context, emoji))
            popIn()
        } else {
            displayImage.visibility = GONE
            display.visibility = VISIBLE
            display.setEmojiText(text)
        }
    }

    /** 图片从小弹到原大小。 */
    private fun popIn() {
        displayImage.scaleX = 0.4f; displayImage.scaleY = 0.4f
        displayImage.animate().scaleX(1f).scaleY(1f).setInterpolator(OvershootInterpolator(2.4f)).setDuration(420).start()
    }

    private fun setStatus(text: String, warn: Boolean = false, thinking: Boolean = false) {
        status.text = text
        status.setTextColor(if (warn) theme.error else theme.ink)
        dots.visibility = if (thinking) VISIBLE else GONE
        status.alpha = 0f
        status.translationY = context.dp(8f)
        status.animate().alpha(1f).translationY(0f).setDuration(200).start()
    }

    /** 顶部对话气泡换一句话，带弹出效果。 */
    private fun say(text: String) {
        bubble.text = text
        bubble.scaleX = 0.85f; bubble.scaleY = 0.85f
        bubble.animate().scaleX(1f).scaleY(1f).setInterpolator(OvershootInterpolator(3f)).setDuration(320).start()
    }

    private fun showBadge(correct: Boolean) {
        badge.set(correct, if (correct) theme.success else theme.error)
        badge.visibility = VISIBLE
        badge.translationY = -context.dp(26f)
        badge.scaleX = 0f; badge.scaleY = 0f
        badge.animate().scaleX(1f).scaleY(1f).setInterpolator(OvershootInterpolator(4f)).setDuration(360).start()
    }

    private fun wiggleMascot() {
        ObjectAnimator.ofFloat(mascot, "rotation", 0f, -14f, 12f, -8f, 4f, 0f).setDuration(600).start()
    }

    private fun spring(view: View, property: DynamicAnimation.ViewProperty, target: Float, stiffness: Float, damping: Float) {
        SpringAnimation(view, property, target).apply {
            spring.stiffness = stiffness
            spring.dampingRatio = damping
            start()
        }
    }

    companion object {
        private const val TAG = "QuizView"
        private const val READY_TEXT = "按住遥控器上的语音键，听到「叮」再说答案"
        private const val RETRY_TEXT = "没听清，按住语音键，听到「叮」再说哦"
        /** 答对、答错后至少停留的时间；朗读更长时等朗读讲完。 */
        private const val CORRECT_MS = 1500L
        private const val REVEAL_MS = 4000L
        /** 朗读讲完后稍停一下再换题。 */
        private const val AFTER_SPEECH_MS = 400L
        private const val MAX_SPEECH_WAIT_MS = 15_000L
        /** 答对、答错音效的长度，朗读等音效播完再开口。 */
        private const val SFX_MS = 400L
        private const val DONE_SFX_MS = 900L
        /** 精灵图解码最多等这么久，超时显示备用画面。 */
        private const val SPRITE_WAIT_MS = 1500L
        /** 开场预先解码的精灵图张数，一张约 5MB，不超过 [Sprites] 的缓存。 */
        private const val PRELOAD_SHEETS = 4
        /** 通关号角响完后再放抽到贴纸的音效。 */
        private const val STICKER_SFX_DELAY_MS = 1100L
        /** 抽贴纸动画：礼物盒出现后多久开始晃、晃多久，翻面一半的时长。 */
        private const val REVEAL_SHAKE_DELAY_MS = 380L
        private const val REVEAL_SHAKE_MS = 480L
        private const val REVEAL_FLIP_MS = 140L
    }
}
