package de.huyc.studyhelper.ui

import android.app.Activity
import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import de.huyc.studyhelper.App
import de.huyc.studyhelper.BuildConfig
import de.huyc.studyhelper.core.ForegroundTracker
import de.huyc.studyhelper.core.GuardService
import de.huyc.studyhelper.diag.VoiceDiagActivity
import de.huyc.studyhelper.web.AdminServer
import de.huyc.studyhelper.web.Net
import kotlin.math.min

/** 电视端首页：剩余时间、手机管理二维码、试一试答题、暂停守护、语音诊断、权限状态。 */
class MainActivity : Activity() {
    private val app get() = App.instance
    private val theme get() = Themes.get(app.store.settings.theme)
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var root: FrameLayout
    private lateinit var ring: RingView
    private lateinit var statusChip: TextView
    private lateinit var summary: TextView
    private lateinit var pauseButton: TextView
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
        titles.addView(text("看电视也能学知识，答对题目继续看", 17f, theme.inkSoft))
        titleRow.addView(titles)
        left.addView(titleRow)

        // 状态卡片
        val statusCard = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            background = cardBackground(context, theme.card, 32)
            elevation = dp(8f)
            setPadding(dp(28), dp(18), dp(28), dp(18))
        }
        ring = RingView(this, theme)
        statusCard.addView(ring, LinearLayout.LayoutParams(dp(150), dp(150)))
        val info = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(28), 0, 0, 0) }
        statusChip = text("", 16f, Color.WHITE, bold = true).apply { setPadding(dp(16), dp(6), dp(16), dp(6)) }
        info.addView(statusChip, LinearLayout.LayoutParams(-2, -2))
        summary = text("", 18f, theme.ink).apply { setLineSpacing(dp(6f), 1f) }
        info.addView(summary, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(12) })
        statusCard.addView(info)
        left.addView(statusCard, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(20) })

        // 操作按钮
        val buttons = LinearLayout(this).apply {
            // 按钮获得焦点时会放大并带投影，不能被父布局裁掉
            clipChildren = false; clipToPadding = false; setPadding(dp(8), dp(10), dp(8), dp(14))
        }
        buttons.addView(button("✏️  试一试答题") { app.lock.showQuiz(preview = true) })
        pauseButton = button("") { askParent() }
        buttons.addView(pauseButton)
        buttons.addView(button("🎤  语音诊断") { startActivity(Intent(this, VoiceDiagActivity::class.java)) })
        left.addView(buttons, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(16); leftMargin = -dp(8) })
        perms = text("", 14f, theme.inkSoft)
        left.addView(perms, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(4); leftMargin = dp(6) })
        content.addView(left, LinearLayout.LayoutParams(0, -2, 1f))

        // 手机扫码
        val qrCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            background = cardBackground(context, theme.card, 32)
            elevation = dp(8f)
            setPadding(dp(26), dp(26), dp(26), dp(22))
        }
        qrCard.addView(text("", 20f, theme.ink, bold = true).apply { setEmojiText("📱 手机扫码管理") })
        qr = ImageView(this)
        qrCard.addView(qr, LinearLayout.LayoutParams(dp(210), dp(210)).apply { topMargin = dp(14); bottomMargin = dp(10) })
        url = text("", 15f, theme.accentDark, bold = true)
        qrCard.addView(url)
        qrCard.addView(text("手机和电视需连同一个 WiFi", 13f, theme.inkSoft).apply { setPadding(0, dp(6), 0, 0) })
        content.addView(qrCard, LinearLayout.LayoutParams(dp(290), -2).apply { leftMargin = dp(40) })
        return content
    }

    override fun onResume() {
        super.onResume()
        val address = Net.lanAddress()
        val link = if (address != null) "http://$address:${AdminServer.PORT}" else null
        url.text = link ?: "未连接网络"
        qr.setImageBitmap(link?.let { qrBitmap(it, dp(210), theme.ink) })
        handler.post(refresher)
        (root.getChildAt(1) as? LinearLayout)?.let { entrance(it) }
        root.post { pauseButton.parent?.let { (it as LinearLayout).getChildAt(0).requestFocus() } }
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
        ring.set(left, limit)
        val (label, color) = when {
            store.paused -> "已暂停守护" to theme.inkSoft
            store.locked -> "答题中" to theme.accent
            else -> "守护中" to theme.success
        }
        statusChip.text = label
        statusChip.background = pill(this, color)
        val s = store.settings
        summary.text = "每次可看 ${s.watchMinutes} 分钟\n到时间后答对 ${s.questionCount} 道题继续看\n" +
            if (s.countMode.name == "ALL") "所有画面都计时" else "只在 ${s.apps.size} 个指定 App 里计时"
        pauseButton.setEmojiText(if (store.paused) "▶️  恢复守护" else "⏸️  暂停守护")
        perms.setEmojiText("权限：悬浮窗 ${mark(Settings.canDrawOverlays(this))}   录音 ${mark(checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)}   " +
            "无障碍 ${mark(ForegroundTracker.accessibilityActive)}   使用情况 ${mark(hasUsageAccess())}   v${BuildConfig.VERSION_NAME}")
    }

    private fun mark(ok: Boolean) = if (ok) "✅" else "⚠️"

    private fun hasUsageAccess(): Boolean {
        val ops = getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        @Suppress("DEPRECATION")
        return ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), packageName) == AppOpsManager.MODE_ALLOWED
    }

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

    private fun text(s: String, sp: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = s; setTextSize(TypedValue.COMPLEX_UNIT_SP, sp); setTextColor(color)
        if (bold) typeface = BOLD
    }

    private fun button(label: String, onClick: () -> Unit) = TextView(this).apply {
        typeface = BOLD
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
        maxLines = 1
        setEmojiText(label)
        gravity = Gravity.CENTER
        isFocusable = true
        setPadding(dp(22), dp(13), dp(22), dp(13))
        val normal = pill(context, theme.card)
        val focused = pill(context, theme.accent)
        background = normal
        elevation = dp(4f)
        setTextColor(theme.ink)
        setOnFocusChangeListener { v, has ->
            v.background = if (has) focused else normal
            (v as TextView).setTextColor(if (has) Color.WHITE else theme.ink)
            v.animate().scaleX(if (has) 1.08f else 1f).scaleY(if (has) 1.08f else 1f)
                .setInterpolator(OvershootInterpolator(2.5f)).setDuration(220).start()
        }
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(16) }
    }

    private fun dp(v: Int) = (this as Context).dp(v)
    private fun dp(v: Float) = (this as Context).dp(v)
}

/** 剩余时间圆环：圆环随剩余比例收缩，不足 20% 变为强调色。 */
class RingView(context: Context, private val theme: ThemeSpec) : View(context) {
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; color = theme.starEmpty }
    private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val big = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; typeface = BOLD; color = theme.ink }
    private val small = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; color = theme.inkSoft }
    private var left = 0
    private var total = 1
    private var shown = -1f

    fun set(leftSeconds: Int, totalSeconds: Int) {
        left = leftSeconds; total = totalSeconds.coerceAtLeast(1)
        val target = left.toFloat() / total
        if (shown < 0) shown = target
        android.animation.ValueAnimator.ofFloat(shown, target).apply {
            duration = 600
            addUpdateListener { shown = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        val stroke = width * 0.08f
        track.strokeWidth = stroke
        arc.strokeWidth = stroke
        val r = min(width, height) / 2f - stroke
        val rect = RectF(width / 2f - r, height / 2f - r, width / 2f + r, height / 2f + r)
        canvas.drawArc(rect, 0f, 360f, false, track)
        arc.color = if (shown < 0.2f) theme.accent else theme.success
        canvas.drawArc(rect, -90f, 360f * shown.coerceIn(0f, 1f), false, arc)
        big.textSize = width * 0.2f
        small.textSize = width * 0.085f
        canvas.drawText("%d:%02d".format(left / 60, left % 60), width / 2f, height / 2f + big.textSize * 0.25f, big)
        canvas.drawText("还可以看", width / 2f, height / 2f + big.textSize * 0.25f + small.textSize * 1.6f, small)
    }
}
