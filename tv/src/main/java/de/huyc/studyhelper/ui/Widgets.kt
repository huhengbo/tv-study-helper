package de.huyc.studyhelper.ui

import android.content.Context
import android.graphics.Color
import android.util.TypedValue
import android.view.Gravity
import android.view.animation.OvershootInterpolator
import android.widget.LinearLayout
import android.widget.TextView

fun Context.themedText(s: String, sp: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
    text = s; setTextSize(TypedValue.COMPLEX_UNIT_SP, sp); setTextColor(color)
    if (bold) typeface = BOLD
}

/** 遥控器可聚焦的胶囊按钮：获得焦点时变强调色并放大。[compact] 是次要操作用的小号按钮。 */
fun Context.focusButton(theme: ThemeSpec, label: String, compact: Boolean = false, onClick: () -> Unit) = TextView(this).apply {
    typeface = BOLD
    setTextSize(TypedValue.COMPLEX_UNIT_SP, if (compact) 14f else 18f)
    maxLines = 1
    setEmojiText(label)
    gravity = Gravity.CENTER
    isFocusable = true
    if (compact) setPadding(dp(14), dp(8), dp(14), dp(8)) else setPadding(dp(22), dp(13), dp(22), dp(13))
    val normal = pill(context, if (compact) withAlpha(theme.card, 0.75f) else theme.card)
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
    layoutParams = LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(if (compact) 10 else 16) }
}
