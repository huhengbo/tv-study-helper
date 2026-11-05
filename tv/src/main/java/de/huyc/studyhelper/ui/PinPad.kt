package de.huyc.studyhelper.ui

import android.animation.ObjectAnimator
import android.content.Context
import android.os.SystemClock
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 家长 PIN 输入面板，遥控器方向键选数字、确认键输入。连续输错 3 次锁定 60 秒，防止孩子乱试。
 * [check] 返回 true 表示 PIN 正确。
 */
class PinPad(
    context: Context,
    private val theme: ThemeSpec,
    title: String,
    /** PIN 位数（4~8），输满即校验。 */
    private val pinLength: Int,
    private val check: (String) -> Boolean,
    private val onClose: (success: Boolean) -> Unit,
) : FrameLayout(context) {

    private val dots = TextView(context)
    private val hint = TextView(context)
    private val panel = LinearLayout(context)
    private var input = ""

    init {
        setBackgroundColor(withAlpha(theme.ink, 0.45f))
        isClickable = true
        panel.orientation = LinearLayout.VERTICAL
        panel.gravity = Gravity.CENTER_HORIZONTAL
        panel.background = cardBackground(context, theme.card, 32)
        panel.elevation = context.dp(12f)
        val pad = context.dp(28)
        panel.setPadding(pad, pad, pad, pad)

        panel.addView(TextView(context).apply {
            text = title; setTextColor(theme.ink); typeface = BOLD
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f); gravity = Gravity.CENTER
        })
        dots.setTextColor(theme.accent)
        dots.setTextSize(TypedValue.COMPLEX_UNIT_SP, 30f)
        dots.gravity = Gravity.CENTER
        dots.letterSpacing = 0.3f
        panel.addView(dots, LinearLayout.LayoutParams(-1, context.dp(56)))
        hint.setTextColor(theme.inkSoft)
        hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        hint.gravity = Gravity.CENTER
        panel.addView(hint, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = context.dp(12) })

        val grid = GridLayout(context).apply { columnCount = 3 }
        listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", DELETE, "0", CANCEL).forEach { label ->
            grid.addView(key(label), GridLayout.LayoutParams().apply {
                width = context.dp(84); height = context.dp(60)
                setMargins(context.dp(6), context.dp(6), context.dp(6), context.dp(6))
            })
        }
        panel.addView(grid)
        addView(panel, LayoutParams(-2, -2, Gravity.CENTER))
        render()
    }

    private fun key(label: String) = TextView(context).apply {
        text = label
        gravity = Gravity.CENTER
        typeface = BOLD
        setTextSize(TypedValue.COMPLEX_UNIT_SP, if (label.length > 1) 18f else 24f)
        isFocusable = true
        val normal = pill(context, theme.bgTop)
        val focused = pill(context, theme.accent)
        background = normal
        setTextColor(theme.ink)
        setOnFocusChangeListener { v, has ->
            v.background = if (has) focused else normal
            (v as TextView).setTextColor(if (has) android.graphics.Color.WHITE else theme.ink)
            v.animate().scaleX(if (has) 1.12f else 1f).scaleY(if (has) 1.12f else 1f)
                .setInterpolator(OvershootInterpolator(3f)).setDuration(180).start()
        }
        setOnClickListener { press(label) }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        panel.scaleX = 0.85f; panel.scaleY = 0.85f; panel.alpha = 0f
        panel.animate().scaleX(1f).scaleY(1f).alpha(1f).setInterpolator(OvershootInterpolator(1.6f)).setDuration(320).start()
        post { findViewWithText("5")?.requestFocus() }
    }

    private fun findViewWithText(t: String): View? {
        val grid = panel.getChildAt(panel.childCount - 1) as GridLayout
        return (0 until grid.childCount).map { grid.getChildAt(it) }.firstOrNull { (it as TextView).text == t }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_MENU -> { onClose(false); return true }
                in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> { press((event.keyCode - KeyEvent.KEYCODE_0).toString()); return true }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun press(label: String) {
        val now = SystemClock.elapsedRealtime()
        if (now < lockedUntil) { render(); shake(); return }
        when (label) {
            CANCEL -> { onClose(false); return }
            DELETE -> input = input.dropLast(1)
            else -> if (input.length < pinLength) input += label
        }
        render()
        if (input.length == pinLength) {
            if (check(input)) { failures = 0; onClose(true) } else fail()
        }
    }

    private fun fail() {
        failures++
        input = ""
        if (failures >= 3) {
            lockedUntil = SystemClock.elapsedRealtime() + 60_000
            failures = 0
        }
        render(error = true)
        shake()
    }

    private fun shake() {
        ObjectAnimator.ofFloat(panel, "translationX", 0f, -24f, 24f, -16f, 16f, -8f, 8f, 0f)
            .setDuration(420).start()
    }

    private fun render(error: Boolean = false) {
        dots.text = "●".repeat(input.length) + "○".repeat(pinLength - input.length)
        val wait = (lockedUntil - SystemClock.elapsedRealtime()) / 1000
        hint.text = when {
            wait > 0 -> "输错太多次，请 $wait 秒后再试"
            error -> "密码不对，再试一次"
            else -> "用方向键选数字，按确认键输入"
        }
        hint.setTextColor(if (error || wait > 0) theme.error else theme.inkSoft)
    }

    companion object {
        private const val DELETE = "退格"
        private const val CANCEL = "取消"
        // 跨面板实例共享，关掉重开也不能绕过冷却
        private var failures = 0
        private var lockedUntil = 0L
    }
}
