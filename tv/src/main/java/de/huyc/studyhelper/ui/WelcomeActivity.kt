package de.huyc.studyhelper.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import de.huyc.studyhelper.App
import de.huyc.studyhelper.core.Permissions
import de.huyc.studyhelper.web.AdminServer
import de.huyc.studyhelper.web.Net

/**
 * 首次使用引导，给家长看（孩子的首页在这之后）：欢迎 → 手机扫码 → 限时守护（可选，要开权限）。只用来学习不需要任何权限，缺的权限以后在家长页随时开启。
 * 每一步都能跳过；走完或跳过都记为已引导，之后缺权限只在家长页提示，不再自动弹出。
 */
class WelcomeActivity : Activity() {
    private enum class Step { WELCOME, PHONE, GUARD }

    private val app get() = App.instance
    private val theme get() = Themes.get(app.store.settings.theme)
    private lateinit var card: LinearLayout
    private var step = Step.WELCOME

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = FrameLayout(this)
        root.addView(FloatingBackground(this, theme), FrameLayout.LayoutParams(-1, -1))
        card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = cardBackground(context, theme.card, 32)
            elevation = dp(8f)
            clipChildren = false; clipToPadding = false
            setPadding(dp(44), dp(30), dp(44), dp(24))
        }
        root.addView(FrameLayout(this).apply {
            setPadding(dp(100), dp(30), dp(100), dp(30))
            addView(card, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER))
        }, FrameLayout.LayoutParams(-1, -1))
        setContentView(root)
        step = savedInstanceState?.getInt("step")?.let { Step.values()[it] } ?: Step.WELCOME
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt("step", step.ordinal)
    }

    /** 从授权页回来后重新判断权限是否齐了。 */
    override fun onResume() {
        super.onResume()
        render()
    }

    private fun render() {
        card.removeAllViews()
        when (step) {
            Step.WELCOME -> welcome()
            Step.PHONE -> phone()
            Step.GUARD -> guard()
        }
        card.post { (card.findViewWithTag<View>("primary") ?: card).requestFocus() }
    }

    private fun welcome() {
        header("🎉 欢迎使用学习小助手", "给家长的几分钟设置，孩子的首页在这之后")
        body(
            "孩子可以直接在首页玩「学习」：答对攒星星、抽贴纸，不需要开任何权限。\n" +
                "想让电视看够时间自动暂停、弹出答题，再多开一步「限时守护」，也可以以后在家长页里开。\n\n" +
                "接下来：  ①手机扫码    ②限时守护（可选）",
        )
        actions(button("开始设置", primary = true) { step = Step.PHONE; render() }, button("以后再说") { finishOnboarding() })
    }

    private fun phone() {
        header("① 手机扫码", "题库、规则和家长密码都在手机上设置")
        val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        val address = Net.lanAddress()
        val link = address?.let { "http://$it:${AdminServer.PORT}" }
        val qrBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
        qrBox.addView(ImageView(this).apply { link?.let { setImageBitmap(qrBitmap(it, dp(190), theme.ink)) } }, LinearLayout.LayoutParams(dp(190), dp(190)))
        qrBox.addView(themedText(link ?: "未连接网络", 14f, theme.accentDark, bold = true))
        row.addView(qrBox)
        row.addView(
            themedText("1. 手机和电视连同一个 WiFi，用浏览器扫左边的码\n2. 默认家长密码是 1234，进去先改掉\n3. 在「题库」页打开要练的题库（已内置 Yakka Dee 看图说英语，默认关着）\n4. 要限时看电视的话，在「应用」页勾选要管控的 App", 18f, theme.ink)
                .apply { setLineSpacing(dp(6f), 1f) },
            LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(32) },
        )
        card.addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16) })
        body("以后随时可以在首页右下角的小锁里再看到这个二维码。")
        actions(button("下一步", primary = true) { step = Step.GUARD; render() })
    }

    private fun guard() {
        val p = Permissions.status(this)
        header("② 限时守护（可选）", "只用来学习的话不用开，以后随时可以在家长页里开")
        listOf(
            "悬浮窗（答题界面盖住视频）" to p.overlay,
            "录音（语音作答）" to p.record,
            "无障碍（识别 App、拦截主页键）" to p.accessibility,
            "使用情况（无障碍的备用）" to p.usage,
        ).forEach { (name, ok) ->
            card.addView(themedText("", 19f, theme.ink).apply { setEmojiText("${if (ok) "✅" else "⚠️"}  $name"); setPadding(0, dp(3), 0, dp(3)) })
        }
        if (p.allGranted) {
            body("权限都已开启，限时守护可以用了。")
            actions(button("完成", primary = true) { finishOnboarding() })
        } else {
            body("电视的设置里没有这些页面，需要先打开电视的「ADB 调试」，再由本应用自己授权，不需要电脑。")
            actions(
                button("先只用来学习", primary = true) { finishOnboarding() },
                button("开启限时守护") { startActivity(Intent(this, SetupActivity::class.java)) },
            )
        }
    }

    override fun onBackPressed() = finishOnboarding()

    private fun finishOnboarding() {
        app.store.onboarded = true
        app.store.setupPrompted = true
        finish()
    }

    private fun header(title: String, sub: String) {
        card.addView(themedText("", 32f, theme.ink, bold = true).apply { setEmojiText(title) })
        card.addView(themedText(sub, 16f, theme.inkSoft))
    }

    private fun body(s: String) {
        card.addView(themedText(s, 18f, theme.ink).apply { setLineSpacing(dp(6f), 1f) },
            LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(16) })
    }

    private fun actions(vararg buttons: TextView) {
        val row = LinearLayout(this).apply { clipChildren = false; clipToPadding = false; setPadding(dp(8), dp(8), dp(8), dp(8)) }
        buttons.forEach { row.addView(it) }
        card.addView(row, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(14); leftMargin = -dp(8) })
    }

    private fun button(label: String, primary: Boolean = false, onClick: () -> Unit) =
        focusButton(theme, label, onClick = onClick).apply { if (primary) tag = "primary" }

    private fun dp(v: Int) = (this as Context).dp(v)
    private fun dp(v: Float) = (this as Context).dp(v)
}
