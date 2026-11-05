package de.huyc.studyhelper.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import de.huyc.studyhelper.App
import de.huyc.studyhelper.BuildConfig
import de.huyc.studyhelper.core.Permissions
import de.huyc.studyhelper.data.DailyStats
import de.huyc.studyhelper.data.StickerCatalog
import de.huyc.studyhelper.quiz.QuizMode
import de.huyc.studyhelper.core.GuardService
import de.huyc.studyhelper.diag.VoiceDiagActivity
import de.huyc.studyhelper.web.AdminServer
import de.huyc.studyhelper.web.Net

/**
 * 电视端首页，学习为主、限时为辅：
 * 最大的是「开始学习」卡片和贴纸册，剩余观看时间是一条细的时间条，
 * 家长功能（试一试、暂停守护、语音诊断、开启权限）收在底部一行小按钮里，右侧是手机扫码入口。
 */
class MainActivity : Activity() {
    private val app get() = App.instance
    private val theme get() = Themes.get(app.store.settings.theme)
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var root: FrameLayout
    private lateinit var timeBar: TimeBar
    private lateinit var timeText: TextView
    private lateinit var statusChip: TextView
    private lateinit var summary: TextView
    private lateinit var pauseButton: TextView
    private lateinit var studyCard: View
    private lateinit var studyHint: TextView
    private lateinit var studyToday: TextView
    private lateinit var stickerCount: TextView
    private var ticks = 0
    private lateinit var setupButton: TextView
    private lateinit var perms: TextView
    private lateinit var qr: ImageView
    private lateinit var url: TextView
    private var gate: ParentGate? = null
    private var pausedWhenGateOpened = false

    private val refresher = object : Runnable {
        override fun run() { refresh(); handler.postDelayed(this, 1000) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        GuardService.start(this)
        root = FrameLayout(this)
        root.addView(FloatingBackground(this, theme), FrameLayout.LayoutParams(-1, -1))
        root.addView(buildContent(), FrameLayout.LayoutParams(-1, -1))
        setContentView(root)
        if (savedInstanceState == null && !app.store.setupPrompted && !Permissions.status(this).allGranted) {
            app.store.setupPrompted = true
            startActivity(Intent(this, SetupActivity::class.java))
        }
    }

    private fun buildContent(): View {
        val content = LinearLayout(this).apply {
            clipChildren = false; clipToPadding = false
            setPadding(dp(56), dp(30), dp(56), dp(24))
            gravity = Gravity.CENTER_VERTICAL
        }
        val left = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; clipChildren = false; clipToPadding = false }

        // 标题
        val titleRow = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        titleRow.addView(ImageView(this).apply {
            setImageBitmap(Emoji.bitmap(context, theme.mascots.first()))
            setPadding(dp(14), dp(14), dp(14), dp(14))
            background = pill(context, theme.card); elevation = dp(4f)
        }, LinearLayout.LayoutParams(dp(84), dp(84)))
        val titles = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(18), 0, 0, 0) }
        titles.addView(text("学习小助手", 38f, theme.ink, bold = true))
        titles.addView(text("每天学一点，看电视的时间也能攒出来", 17f, theme.inkSoft))
        titleRow.addView(titles)
        left.addView(titleRow)

        // 主区域：开始学习（大）+ 我的贴纸
        val main = LinearLayout(this).apply { clipChildren = false; clipToPadding = false; setPadding(dp(8), dp(8), dp(8), dp(8)) }
        studyCard = studyCard()
        main.addView(studyCard, LinearLayout.LayoutParams(0, dp(150), 2f))
        main.addView(stickerCard(), LinearLayout.LayoutParams(0, dp(150), 1f).apply { leftMargin = dp(18) })
        left.addView(main, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(14); leftMargin = -dp(8); rightMargin = -dp(8) })

        // 观看时间：一条细的时间条
        val timeCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = cardBackground(context, withAlpha(theme.card, 0.8f), 24)
            setPadding(dp(22), dp(14), dp(22), dp(14))
        }
        val timeRow = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        statusChip = text("", 13f, Color.WHITE, bold = true).apply { setPadding(dp(12), dp(3), dp(12), dp(3)) }
        timeRow.addView(statusChip)
        timeText = text("", 20f, theme.ink, bold = true).apply { setPadding(dp(14), 0, 0, 0) }
        timeRow.addView(timeText)
        timeCard.addView(timeRow)
        timeBar = TimeBar(this, theme)
        timeCard.addView(timeBar, LinearLayout.LayoutParams(-1, dp(8)).apply { topMargin = dp(10) })
        summary = text("", 13f, theme.inkSoft)
        timeCard.addView(summary, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(8) })
        left.addView(timeCard, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })

        // 家长功能：底部一行小按钮
        val parent = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            // 按钮获得焦点时会放大并带投影，不能被父布局裁掉
            clipChildren = false; clipToPadding = false; setPadding(dp(8), dp(14), dp(8), dp(4))
        }
        parent.addView(text("家长", 13f, theme.inkSoft).apply { setPadding(0, 0, dp(12), 0) })
        parent.addView(button("✏️  试一试答题") { app.lock.showQuiz(QuizMode.PREVIEW) })
        pauseButton = button("") { askParent() }
        parent.addView(pauseButton)
        parent.addView(button("🎤  语音诊断") { startActivity(Intent(this, VoiceDiagActivity::class.java)) })
        // 权限齐全时隐藏
        setupButton = button("🔧  开启权限") { startActivity(Intent(this, SetupActivity::class.java)) }
        parent.addView(setupButton)
        left.addView(parent, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = -dp(8) })
        perms = text("", 12f, theme.inkSoft)
        left.addView(perms, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(2) })
        content.addView(left, LinearLayout.LayoutParams(0, -2, 1f))

        // 手机扫码：给家长用，比学习入口小
        val qrCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            background = cardBackground(context, withAlpha(theme.card, 0.85f), 28)
            elevation = dp(4f)
            setPadding(dp(20), dp(20), dp(20), dp(16))
        }
        qrCard.addView(text("", 17f, theme.ink, bold = true).apply { setEmojiText("📱 家长扫码管理") })
        qr = ImageView(this)
        qrCard.addView(qr, LinearLayout.LayoutParams(dp(170), dp(170)).apply { topMargin = dp(10); bottomMargin = dp(8) })
        url = text("", 13f, theme.accentDark, bold = true)
        qrCard.addView(url)
        qrCard.addView(text("手机和电视需连同一个 WiFi", 12f, theme.inkSoft).apply { setPadding(0, dp(4), 0, 0) })
        content.addView(qrCard, LinearLayout.LayoutParams(dp(230), -2).apply { leftMargin = dp(36) })
        return content
    }

    /** 最醒目的入口：强调色大卡片，写明这一轮能得到什么。 */
    private fun studyCard(): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(26), dp(14), dp(26), dp(14))
            isFocusable = true
            elevation = dp(8f)
        }
        card.addView(text("", 34f, Color.WHITE, bold = true).apply { setEmojiText("📚  开始学习") })
        studyHint = text("", 16f, Color.WHITE)
        card.addView(studyHint, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(6) })
        studyToday = text("", 14f, withAlpha(Color.WHITE, 0.8f))
        card.addView(studyToday, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(4) })
        styleCard(card, theme.accent, theme.accentDark, Color.WHITE)
        card.setOnClickListener { app.lock.showQuiz(QuizMode.STUDY) }
        return card
    }

    private fun stickerCard(): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(14), 0, dp(14), 0)
            isFocusable = true
            elevation = dp(6f)
        }
        card.addView(text("", 17f, theme.ink, bold = true).apply { setEmojiText("🌟 我的贴纸") })
        stickerCount = text("", 30f, theme.accentDark, bold = true)
        card.addView(stickerCount, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(4) })
        styleCard(card, theme.card, theme.card, theme.accent)
        card.setOnClickListener { startActivity(Intent(this, StickerBookActivity::class.java)) }
        return card
    }

    /** 大卡片的聚焦效果：放大，描边，换深一点的底色。 */
    private fun styleCard(card: View, normal: Int, focused: Int, stroke: Int) {
        val n = cardBackground(this, normal, 28)
        val f = cardBackground(this, focused, 28).apply { setStroke(dp(4), stroke) }
        card.background = n
        card.setOnFocusChangeListener { v, has ->
            v.background = if (has) f else n
            v.animate().scaleX(if (has) 1.05f else 1f).scaleY(if (has) 1.05f else 1f)
                .setInterpolator(OvershootInterpolator(2.5f)).setDuration(220).start()
        }
    }

    override fun onResume() {
        super.onResume()
        val address = Net.lanAddress()
        val link = if (address != null) "http://$address:${AdminServer.PORT}" else null
        url.text = link ?: "未连接网络"
        qr.setImageBitmap(link?.let { qrBitmap(it, dp(170), theme.ink) })
        ticks = 0
        handler.post(refresher)
        (root.getChildAt(1) as? LinearLayout)?.let { entrance(it) }
        root.post { studyCard.requestFocus() }
    }

    override fun onPause() {
        handler.removeCallbacks(refresher)
        super.onPause()
    }

    private fun refresh() {
        val store = app.store
        // 家长已在手机上切换了暂停状态，关闭扫码面板
        if (gate != null && store.paused != pausedWhenGateOpened) closeGate()
        val limit = store.limitSeconds
        val left = (limit - store.usedSeconds).coerceAtLeast(0)
        timeBar.set(left, limit)
        timeText.text = "还可以看 %d:%02d".format(left / 60, left % 60)
        val (label, color) = when {
            store.paused -> "已暂停守护" to theme.inkSoft
            store.locked -> "答题中" to theme.accent
            else -> "守护中" to theme.success
        }
        statusChip.text = label
        statusChip.background = pill(this, color)
        val s = store.settings
        summary.text = "每次可看 ${s.watchMinutes} 分钟，到时间后答对 ${s.questionCount} 道题继续看 · " +
            if (s.countMode.name == "ALL") "所有画面都计时" else "只在 ${s.apps.size} 个指定 App 里计时"
        pauseButton.setEmojiText(if (store.paused) "▶️  恢复守护" else "⏸️  暂停守护")
        // 统计和贴纸要读文件，不必每秒都读
        if (ticks++ % 5 == 0) {
            val now = System.currentTimeMillis()
            val rewards = store.rewards()
            val answered = store.daily()[DailyStats.dayKey(now)]?.answered ?: 0
            stickerCount.text = "${rewards.stickers.size}/${StickerCatalog.total}"
            studyToday.text = if (answered > 0) "今天已答 $answered 道题，真棒" else "今天还没有学习哦"
            val earned = rewards.earnedOn(DailyStats.dayKey(now))
            studyHint.text = "答对 ${s.studyQuestionCount} 道题，抽一张贴纸" + when {
                store.paused || s.rewardMinutesPerRound == 0 -> ""
                earned >= s.rewardDailyCapMinutes -> "（今天的加时已攒满）"
                else -> "，还能多看 ${s.rewardMinutesPerRound} 分钟"
            }
        }
        val p = Permissions.status(this)
        setupButton.visibility = if (p.allGranted) View.GONE else View.VISIBLE
        perms.setEmojiText(if (p.allGranted) "v${BuildConfig.VERSION_NAME}" else
            "权限：悬浮窗 ${mark(p.overlay)}   录音 ${mark(p.record)}   " +
            "无障碍 ${mark(p.accessibility)}   使用情况 ${mark(p.usage)}   v${BuildConfig.VERSION_NAME}")
    }

    private fun mark(ok: Boolean) = if (ok) "✅" else "⚠️"

    /** 暂停/恢复守护需要家长验证：扫码由手机端切换，或（设置允许时）电视上输密码。 */
    private fun askParent() {
        val store = app.store
        pausedWhenGateOpened = store.paused
        val title = if (store.paused) "恢复守护" else "暂停守护"
        val g = ParentGate(this, theme, title, if (store.paused) "resume" else "pause") { pinVerified ->
            closeGate()
            if (pinVerified) {
                store.paused = !store.paused
                if (store.paused && store.locked) app.lock.unlock()
                refresh()
            }
        }
        gate = g
        root.addView(g, FrameLayout.LayoutParams(-1, -1))
    }

    private fun closeGate() {
        gate?.let { root.removeView(it) }
        gate = null
        pauseButton.requestFocus()
    }

    private fun entrance(content: LinearLayout) {
        for (i in 0 until content.childCount) {
            val v = content.getChildAt(i)
            v.alpha = 0f; v.translationY = dp(40f)
            v.animate().alpha(1f).translationY(0f).setStartDelay(i * 120L).setDuration(500)
                .setInterpolator(OvershootInterpolator(1.1f)).start()
        }
    }

    private fun text(s: String, sp: Float, color: Int, bold: Boolean = false) = themedText(s, sp, color, bold)

    private fun button(label: String, onClick: () -> Unit) = focusButton(theme, label, compact = true, onClick = onClick)

    private fun dp(v: Int) = (this as Context).dp(v)
    private fun dp(v: Float) = (this as Context).dp(v)
}

/** 剩余时间条：随剩余比例缩短，不足 20% 变为强调色。 */
class TimeBar(context: Context, private val theme: ThemeSpec) : View(context) {
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = theme.starEmpty }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private var shown = -1f

    fun set(leftSeconds: Int, totalSeconds: Int) {
        val target = leftSeconds.toFloat() / totalSeconds.coerceAtLeast(1)
        if (shown < 0) shown = target
        android.animation.ValueAnimator.ofFloat(shown, target).apply {
            duration = 600
            addUpdateListener { shown = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        val r = height / 2f
        canvas.drawRoundRect(RectF(0f, 0f, width.toFloat(), height.toFloat()), r, r, track)
        fill.color = if (shown < 0.2f) theme.accent else theme.success
        val w = width * shown.coerceIn(0f, 1f)
        if (w > 0f) canvas.drawRoundRect(RectF(0f, 0f, maxOf(w, height.toFloat()), height.toFloat()), r, r, fill)
    }
}
