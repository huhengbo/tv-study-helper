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
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import de.huyc.studyhelper.App
import de.huyc.studyhelper.BuildConfig
import de.huyc.studyhelper.core.Permissions
import de.huyc.studyhelper.data.CountMode
import de.huyc.studyhelper.diag.VoiceDiagActivity
import de.huyc.studyhelper.quiz.QuizMode
import de.huyc.studyhelper.web.AdminServer
import de.huyc.studyhelper.web.Net

/**
 * 家长面板：从首页右上角的「家长」进入。守护状态和规则、手机扫码、试一试答题、
 * 暂停守护（需家长验证）、语音诊断、开启限时守护都在这里，孩子的首页只留学习入口。
 */
class ParentActivity : Activity() {
    private val app get() = App.instance
    private val theme get() = Themes.get(app.store.settings.theme)
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var root: FrameLayout
    private lateinit var timeBar: TimeBar
    private lateinit var timeText: TextView
    private lateinit var statusChip: TextView
    private lateinit var summary: TextView
    private lateinit var pauseButton: TextView
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
        root = FrameLayout(this)
        root.addView(FloatingBackground(this, theme), FrameLayout.LayoutParams(-1, -1))
        root.addView(buildContent(), FrameLayout.LayoutParams(-1, -1))
        setContentView(root)
    }

    private fun buildContent(): View {
        val content = LinearLayout(this).apply {
            clipChildren = false; clipToPadding = false
            setPadding(dp(56), dp(30), dp(56), dp(24))
            gravity = Gravity.CENTER_VERTICAL
        }
        val left = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; clipChildren = false; clipToPadding = false }
        left.addView(text("", 34f, theme.ink, bold = true).apply { setEmojiText("🔒 家长") })
        left.addView(text("看电视的规则和管理功能，按返回键回到孩子的首页", 16f, theme.inkSoft).apply { setPadding(0, dp(4), 0, 0) })

        // 守护状态和剩余时间
        val timeCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = cardBackground(context, withAlpha(theme.card, 0.85f), 24)
            elevation = dp(4f)
            setPadding(dp(24), dp(18), dp(24), dp(18))
        }
        val timeRow = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        statusChip = text("", 14f, Color.WHITE, bold = true).apply { setPadding(dp(12), dp(3), dp(12), dp(3)) }
        timeRow.addView(statusChip)
        timeText = text("", 22f, theme.ink, bold = true).apply { setPadding(dp(14), 0, 0, 0) }
        timeRow.addView(timeText)
        timeCard.addView(timeRow)
        timeBar = TimeBar(this, theme)
        timeCard.addView(timeBar, LinearLayout.LayoutParams(-1, dp(10)).apply { topMargin = dp(12) })
        summary = text("", 15f, theme.inkSoft)
        timeCard.addView(summary, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(10) })
        left.addView(timeCard, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(22) })

        val buttons = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            // 按钮获得焦点时会放大并带投影，不能被父布局裁掉
            clipChildren = false; clipToPadding = false; setPadding(dp(8), dp(20), dp(8), dp(8))
        }
        pauseButton = button("") { askParent() }
        buttons.addView(pauseButton)
        buttons.addView(button("✏️  试一试答题") { app.lock.showQuiz(QuizMode.PREVIEW) })
        buttons.addView(button("🎤  语音诊断") { startActivity(Intent(this, VoiceDiagActivity::class.java)) })
        buttons.addView(button("📖  使用引导") { startActivity(Intent(this, WelcomeActivity::class.java)) })
        // 权限齐全时隐藏
        setupButton = button("🔧  开启限时守护") { startActivity(Intent(this, SetupActivity::class.java)) }
        buttons.addView(setupButton)
        left.addView(buttons, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = -dp(8) })
        perms = text("", 13f, theme.inkSoft)
        left.addView(perms, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(6) })
        content.addView(left, LinearLayout.LayoutParams(0, -2, 1f))

        val qrCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            background = cardBackground(context, theme.card, 28)
            elevation = dp(4f)
            setPadding(dp(24), dp(22), dp(24), dp(18))
        }
        qrCard.addView(text("", 18f, theme.ink, bold = true).apply { setEmojiText("📱 手机扫码管理") })
        qr = ImageView(this)
        qrCard.addView(qr, LinearLayout.LayoutParams(dp(220), dp(220)).apply { topMargin = dp(12); bottomMargin = dp(10) })
        url = text("", 14f, theme.accentDark, bold = true)
        qrCard.addView(url)
        qrCard.addView(text("手机和电视需连同一个 WiFi", 13f, theme.inkSoft).apply { setPadding(0, dp(4), 0, 0) })
        content.addView(qrCard, LinearLayout.LayoutParams(dp(290), -2).apply { leftMargin = dp(40) })
        return content
    }

    override fun onResume() {
        super.onResume()
        val address = Net.lanAddress()
        val link = if (address != null) "http://$address:${AdminServer.PORT}" else null
        url.text = link ?: "未连接网络"
        qr.setImageBitmap(link?.let { qrBitmap(it, dp(220), theme.ink) })
        handler.post(refresher)
        if (gate == null && currentFocus == null) root.post { pauseButton.requestFocus() }
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
        timeBar.visibility = if (store.paused) View.INVISIBLE else View.VISIBLE
        timeBar.set(left, limit)
        timeText.text = if (store.paused) "暂停期间不限时" else "还可以看 %d:%02d".format(left / 60, left % 60)
        val (label, color) = when {
            store.paused -> "已暂停守护" to theme.inkSoft
            store.locked -> "答题中" to theme.accent
            else -> "守护中" to theme.success
        }
        statusChip.text = label
        statusChip.background = pill(this, color)
        val s = store.settings
        summary.text = "每次可看 ${s.watchMinutes} 分钟，到时间后答对 ${s.questionCount} 道题继续看\n" +
            if (s.countMode == CountMode.ALL) "所有画面都计时" else "只在 ${s.apps.size} 个指定 App 里计时"
        pauseButton.setEmojiText(if (store.paused) "▶️  恢复守护" else "⏸️  暂停守护")
        val p = Permissions.status(this)
        setupButton.visibility = if (p.allGranted) View.GONE else View.VISIBLE
        perms.setEmojiText(if (p.allGranted) "v${BuildConfig.VERSION_NAME}" else
            "限时守护需要的权限（只学习可以不开）：悬浮窗 ${mark(p.overlay)}   录音 ${mark(p.record)}   " +
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

    private fun text(s: String, sp: Float, color: Int, bold: Boolean = false) = themedText(s, sp, color, bold)

    private fun button(label: String, onClick: () -> Unit) = focusButton(theme, label, onClick = onClick)

    private fun dp(v: Int) = (this as Context).dp(v)
    private fun dp(v: Float) = (this as Context).dp(v)
}

/** 剩余时间条：随剩余比例缩短，不足 20% 变为警示色。 */
class TimeBar(context: Context, private val theme: ThemeSpec) : View(context) {
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = theme.starEmpty }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private var shown = -1f
    private var target = -1f

    fun set(leftSeconds: Int, totalSeconds: Int) {
        val next = leftSeconds.toFloat() / totalSeconds.coerceAtLeast(1)
        // 每秒都会调用，数值没变时不重绘
        if (next == target) return
        target = next
        if (shown < 0) shown = next
        android.animation.ValueAnimator.ofFloat(shown, next).apply {
            duration = 600
            addUpdateListener { shown = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        val r = height / 2f
        canvas.drawRoundRect(RectF(0f, 0f, width.toFloat(), height.toFloat()), r, r, track)
        fill.color = if (shown < 0.2f) theme.error else theme.success
        val w = width * shown.coerceIn(0f, 1f)
        if (w > 0f) canvas.drawRoundRect(RectF(0f, 0f, maxOf(w, height.toFloat()), height.toFloat()), r, r, fill)
    }
}
