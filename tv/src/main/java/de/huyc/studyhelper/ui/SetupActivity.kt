package de.huyc.studyhelper.ui

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import de.huyc.studyhelper.App
import de.huyc.studyhelper.core.PermissionStatus
import de.huyc.studyhelper.core.Permissions
import de.huyc.studyhelper.core.SelfGrant
import kotlin.concurrent.thread

/**
 * 权限引导页。小米电视的设置里没有悬浮窗、无障碍、使用情况的页面，所以不跳转系统设置，
 * 而是让家长打开电视的「ADB 调试」，由应用通过本机 adb 自己授权（见 [SelfGrant]），全程不需要电脑。
 */
class SetupActivity : Activity() {
    private enum class Phase { WAIT_ADB, READY, WORKING, DONE, FAILED }

    private val theme get() = Themes.get(App.instance.store.settings.theme)
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var rows: LinearLayout
    private lateinit var heading: TextView
    private lateinit var body: TextView
    private lateinit var actions: LinearLayout

    private var phase = Phase.WAIT_ADB
    private var failure = ""
    private var status: PermissionStatus? = null

    /** 命令执行完后无障碍服务需要几秒才会连上，在这个时间点之前不判定失败。 */
    private var verifyUntil = 0L

    private val poller = object : Runnable {
        override fun run() { poll(); handler.postDelayed(this, 1500) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = FrameLayout(this)
        root.addView(FloatingBackground(this, theme), FrameLayout.LayoutParams(-1, -1))
        root.addView(buildContent(), FrameLayout.LayoutParams(-1, -1))
        setContentView(root)
        render()
    }

    private fun buildContent(): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = cardBackground(context, theme.card, 32)
            elevation = dp(8f)
            clipChildren = false; clipToPadding = false
            setPadding(dp(40), dp(28), dp(40), dp(24))
        }
        card.addView(themedText("开启必要权限", 32f, theme.ink, bold = true))
        card.addView(themedText("学习小助手需要下面这些权限才能计时、锁屏和语音作答", 16f, theme.inkSoft))

        rows = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        card.addView(rows, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16) })

        heading = themedText("", 22f, theme.accentDark, bold = true)
        card.addView(heading, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(22) })
        body = themedText("", 18f, theme.ink).apply { setLineSpacing(dp(6f), 1f) }
        card.addView(body, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(8) })

        actions = LinearLayout(this).apply {
            clipChildren = false; clipToPadding = false; setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        card.addView(actions, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(14); leftMargin = -dp(8) })

        return FrameLayout(this).apply {
            setPadding(dp(80), dp(30), dp(80), dp(30))
            addView(card, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER))
        }
    }

    override fun onResume() {
        super.onResume()
        handler.post(poller)
    }

    override fun onPause() {
        handler.removeCallbacks(poller)
        super.onPause()
    }

    private fun poll() {
        status = Permissions.status(this)
        if (phase == Phase.WAIT_ADB || phase == Phase.READY) {
            val next = if (SelfGrant.adbEnabled(this)) Phase.READY else Phase.WAIT_ADB
            if (next != phase) { phase = next; render() }
        }
        settle()
    }

    /** 全部生效则完成；执行完命令后超时仍有缺项则判定失败。 */
    private fun settle() {
        val s = status ?: return
        if (s.allGranted && phase != Phase.DONE && (phase != Phase.WORKING || verifyUntil > 0)) {
            phase = Phase.DONE; render(); return
        }
        if (phase == Phase.WORKING && verifyUntil > 0 && SystemClock.elapsedRealtime() > verifyUntil) {
            failure = "命令已执行，但有权限没有生效：" + missing(s).joinToString("、")
            phase = Phase.FAILED; render(); return
        }
        renderRows()
    }

    private fun missing(s: PermissionStatus) = buildList {
        if (!s.overlay) add("悬浮窗")
        if (!s.record) add("录音")
        if (!s.accessibility) add("无障碍")
        if (!s.usage) add("使用情况")
    }

    private fun startGrant() {
        phase = Phase.WORKING
        verifyUntil = 0
        render()
        thread(name = "self-grant") {
            val outcome = SelfGrant.grant(applicationContext)
            runOnUiThread {
                when (outcome) {
                    is SelfGrant.Outcome.Done -> verifyUntil = SystemClock.elapsedRealtime() + 8000
                    is SelfGrant.Outcome.NotConfirmed -> {
                        failure = "没有收到确认。请重试，并在电视弹出的提示里把光标移到「确定」后按确认键；\n如果电脑正连着这台电视的 adb，请先断开。"
                        phase = Phase.FAILED
                    }
                    is SelfGrant.Outcome.Unreachable -> {
                        failure = "连不上电视的 ADB。请确认「ADB 调试」已打开；个别电视不支持网络 ADB。"
                        phase = Phase.FAILED
                    }
                    is SelfGrant.Outcome.Failed -> {
                        failure = "授权失败：${outcome.reason}"
                        phase = Phase.FAILED
                    }
                }
                render()
            }
        }
    }

    private fun render() {
        renderRows()
        val buttons = mutableListOf<TextView>()
        when (phase) {
            Phase.WAIT_ADB -> {
                heading.text = "第 1 步：打开电视的「ADB 调试」"
                body.text = "用遥控器进入  设置 → 账号与安全 → ADB 调试，把开关打开。\n打开后回到这里，会自动进入下一步。"
                buttons += focusButton(theme, "返回") { finish() }
            }
            Phase.READY -> {
                heading.text = "第 2 步：开始授权"
                body.text = "点「开始授权」后，电视会弹出「允许 USB 调试吗？」的提示。\n请用遥控器的方向键移到「确定」，再按确认键（保持勾选「一律允许」）。不需要电脑。"
                buttons += focusButton(theme, "✅  开始授权") { startGrant() }
                buttons += focusButton(theme, "返回") { finish() }
            }
            Phase.WORKING -> {
                heading.text = "正在授权…"
                body.text = "请留意屏幕上的「允许 USB 调试吗？」提示，用方向键移到「确定」再按确认键。"
                buttons += focusButton(theme, "返回") { finish() }
            }
            Phase.DONE -> {
                heading.text = "✅ 权限已全部开启"
                body.text = "为了安全，建议现在回到  设置 → 账号与安全，把「ADB 调试」关掉，\n学习小助手之后不再需要它。"
                buttons += focusButton(theme, "完成") { finish() }
            }
            Phase.FAILED -> {
                heading.text = "⚠️ 没有完成"
                body.text = failure + "\n\n仍然不行，可以在电脑上运行项目里的 scripts/setup_tv.sh 开启。"
                buttons += focusButton(theme, "重试") { phase = Phase.WAIT_ADB; render(); poll() }
                buttons += focusButton(theme, "返回") { finish() }
            }
        }
        heading.setEmojiText(heading.text)
        actions.removeAllViews()
        buttons.forEach { actions.addView(it) }
        buttons.firstOrNull()?.requestFocus()
    }

    private fun renderRows() {
        val s = status ?: Permissions.status(this).also { status = it }
        val items = listOf(
            "悬浮窗（答题界面）" to s.overlay,
            "录音（语音作答）" to s.record,
            "无障碍（识别 App、拦截主页键）" to s.accessibility,
            "使用情况（无障碍的备用）" to s.usage,
        )
        rows.removeAllViews()
        items.forEach { (name, ok) ->
            rows.addView(themedText("", 19f, theme.ink).apply {
                setEmojiText("${if (ok) "✅" else "⚠️"}  $name")
                setPadding(0, dp(3), 0, dp(3))
            })
        }
    }

    private fun dp(v: Int) = (this as android.content.Context).dp(v)
    private fun dp(v: Float) = (this as android.content.Context).dp(v)
}
