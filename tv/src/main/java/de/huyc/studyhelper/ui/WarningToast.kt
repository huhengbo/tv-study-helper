package de.huyc.studyhelper.ui

import android.annotation.SuppressLint
import android.content.Context
import android.util.TypedValue
import android.view.Gravity
import android.view.animation.AccelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.TextView
import de.huyc.studyhelper.App

/** 到点前的提醒：右上角滑入一个小气泡，停留几秒后滑出。不抢焦点，不打断播放。 */
@SuppressLint("ViewConstructor")
class WarningToast(context: Context, remainingSeconds: Int) : FrameLayout(context) {
    private val theme = Themes.get(App.instance.store.settings.theme)
    private val label = TextView(context)

    init {
        val minutes = (remainingSeconds + 59) / 60
        label.setTextColor(theme.ink)
        label.typeface = BOLD
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
        label.setEmojiText("⏰  还有 $minutes 分钟就要答题啦")
        label.background = pill(context, theme.card, theme.accent, 3)
        label.elevation = context.dp(8f)
        label.setPadding(context.dp(26), context.dp(14), context.dp(26), context.dp(14))
        label.gravity = Gravity.CENTER
        val m = context.dp(28)
        addView(label, LayoutParams(-2, -2).apply { setMargins(m, m, m, m) })
    }

    fun play(onDone: () -> Unit) {
        label.translationX = context.dp(420f)
        label.animate().translationX(0f).setInterpolator(OvershootInterpolator(1.2f)).setDuration(520)
            .withEndAction {
                label.animate().rotation(-3f).setDuration(120).withEndAction {
                    label.animate().rotation(0f).setDuration(220).start()
                }.start()
            }.start()
        postDelayed({
            label.animate().translationX(context.dp(420f)).setInterpolator(AccelerateInterpolator())
                .setDuration(380).withEndAction(onDone).start()
        }, 6000)
    }
}
