package de.huyc.studyhelper.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import de.huyc.studyhelper.App
import de.huyc.studyhelper.web.AdminServer
import de.huyc.studyhelper.web.Net

/**
 * 家长验证面板。电视屏幕大，在上面用方向键输密码孩子一眼就能看到，
 * 所以默认只显示二维码：家长用手机扫码、在手机上输密码后由手机端执行 [action]。
 * 设置里打开「允许在电视上输密码」时才提供电视端密码面板，供手机不在身边时应急。
 *
 * [action] 是管理网页的动作锚点（如 "unlock"），手机打开 http://电视IP:端口/#unlock 后登录即执行。
 */
@SuppressLint("ViewConstructor")
class ParentGate(
    context: Context,
    private val theme: ThemeSpec,
    title: String,
    action: String,
    private val onClose: (pinVerified: Boolean) -> Unit,
) : FrameLayout(context) {

    private val store = App.instance.store
    private val panel = LinearLayout(context)
    private var pinPad: PinPad? = null

    init {
        setBackgroundColor(withAlpha(theme.ink, 0.45f))
        isClickable = true
        panel.orientation = LinearLayout.VERTICAL
        panel.gravity = Gravity.CENTER_HORIZONTAL
        panel.background = cardBackground(context, theme.card, 32)
        panel.elevation = context.dp(12f)
        val pad = context.dp(30)
        panel.setPadding(pad, pad, pad, context.dp(24))
        panel.minimumWidth = context.dp(400)

        panel.addView(text(title, 22f, theme.ink, bold = true))
        panel.addView(text("手机扫码，在手机上输入家长密码", 15f, theme.inkSoft).apply { setPadding(0, context.dp(6), 0, context.dp(14)) })
        val link = Net.lanAddress()?.let { "http://$it:${AdminServer.PORT}/#$action" }
        panel.addView(ImageView(context).apply { link?.let { setImageBitmap(qrBitmap(it, context.dp(220), theme.ink)) } },
            LinearLayout.LayoutParams(context.dp(220), context.dp(220)))
        if (link == null) panel.addView(text("电视没有联网，无法扫码", 15f, theme.error))

        val hint = if (store.settings.tvPinUnlock) "按确认键改用密码　·　按返回键关闭" else "按返回键关闭"
        panel.addView(text(hint, 14f, theme.inkSoft).apply { setPadding(0, context.dp(14), 0, 0) })
        addView(panel, LayoutParams(-2, -2, Gravity.CENTER))
        isFocusable = true
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        panel.scaleX = 0.85f; panel.scaleY = 0.85f; panel.alpha = 0f
        panel.animate().scaleX(1f).scaleY(1f).alpha(1f).setInterpolator(OvershootInterpolator(1.6f)).setDuration(320).start()
        requestFocus()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (pinPad != null) return super.dispatchKeyEvent(event)
        if (event.action == KeyEvent.ACTION_DOWN) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_BACK -> onClose(false)
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> if (store.settings.tvPinUnlock) openPinPad()
            }
        }
        return true
    }

    private fun openPinPad() {
        panel.animate().alpha(0f).setDuration(150).start()
        val pad = PinPad(context, theme, "输入家长密码", store.settings.pin.length, { it == store.settings.pin }) { ok ->
            removeView(pinPad)
            pinPad = null
            if (ok) onClose(true) else { panel.animate().alpha(1f).setDuration(150).start(); requestFocus() }
        }
        pinPad = pad
        addView(pad, LayoutParams(-1, -1))
    }

    private fun text(s: String, sp: Float, color: Int, bold: Boolean = false) = TextView(context).apply {
        text = s; setTextSize(TypedValue.COMPLEX_UNIT_SP, sp); setTextColor(color); gravity = Gravity.CENTER
        if (bold) typeface = BOLD
    }
}

fun qrBitmap(content: String, size: Int, ink: Int): Bitmap {
    val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, size, size, mapOf(EncodeHintType.MARGIN to 1))
    val pixels = IntArray(size * size) { i -> if (matrix[i % size, i / size]) ink else Color.WHITE }
    return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.RGB_565)
}
