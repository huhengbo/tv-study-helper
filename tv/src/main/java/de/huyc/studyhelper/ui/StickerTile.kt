package de.huyc.studyhelper.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.RectF
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.LinearLayout
import android.widget.TextView

/** 手写体（ZCOOL 快乐体子集，只含首页用到的字，见 docs/design/DESIGN.md）。缺的字由系统字体补上。 */
object HandFont {
    @Volatile private var cached: Typeface? = null

    fun get(context: Context): Typeface = cached ?: runCatching {
        Typeface.createFromAsset(context.assets, "fonts/zcool-kuaile-subset.ttf")
    }.getOrDefault(BOLD).also { cached = it }
}

fun Context.handText(s: String, sp: Float, color: Int) = themedText(s, sp, color).apply { typeface = HandFont.get(context) }

/** 往白色混一点。 */
fun lighten(color: Int, f: Float): Int = Color.rgb(
    (Color.red(color) + (255 - Color.red(color)) * f).toInt(),
    (Color.green(color) + (255 - Color.green(color)) * f).toInt(),
    (Color.blue(color) + (255 - Color.blue(color)) * f).toInt(),
)

/** 往黑色混一点。 */
fun darken(color: Int, f: Float): Int = Color.rgb(
    (Color.red(color) * (1 - f)).toInt(), (Color.green(color) * (1 - f)).toInt(), (Color.blue(color) * (1 - f)).toInt(),
)

/** 手账纸：主题底色向奶油色混合，加一层浅色点阵。静态，不重绘。 */
class PaperBackground(context: Context, theme: ThemeSpec) : View(context) {
    private val paper = blend(theme.bgTop, CREAM, 0.85f)
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = darken(paper, 0.09f) }
    private val gap = context.dp(26f)
    private val r = context.dp(1.6f)

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(paper)
        var y = gap / 2
        while (y < height) {
            var x = gap / 2
            while (x < width) { canvas.drawCircle(x, y, r, dot); x += gap }
            y += gap
        }
    }

    private companion object {
        val CREAM = Color.rgb(0xFF, 0xF8, 0xEA)
        fun blend(a: Int, b: Int, f: Float) = Color.rgb(
            (Color.red(a) * (1 - f) + Color.red(b) * f).toInt(),
            (Color.green(a) * (1 - f) + Color.green(b) * f).toInt(),
            (Color.blue(a) * (1 - f) + Color.blue(b) * f).toInt(),
        )
    }
}

/**
 * 一张贴纸：白色异形底，顶上一条斜纹胶带，中间是图标。
 * [lift] 0~1 表示抬起的程度（阴影加深、下移），[iconScale] 用于选中时的"呼吸"。
 */
class StickerDie(context: Context, tape: Int, iconDp: Int) : View(context) {
    private val pad = context.dp(26f)
    private val iconSize = context.dp(iconDp.toFloat())
    private val tapeW = context.dp(96f)
    private val tapeH = context.dp(30f)
    private val tapeLight = lighten(tape, 0.45f)
    private val tapeDark = Color.argb(0xC0, Color.red(tape), Color.green(tape), Color.blue(tape))
    private val white = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val shadow = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stripe = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val blob = Path()
    private val box = RectF()
    private val iconBox = RectF()
    private var icon: Bitmap? = null
    private var iconAlpha = 255

    /** 白底四周留给阴影和胶带的空间。 */
    private val side = context.dp(14f)
    private val top = context.dp(18f)
    private val bottom = context.dp(26f)

    var lift = 0f
        set(v) { field = v; invalidate() }
    var iconScale = 1f
        set(v) { field = v; invalidate() }

    val blobSize: Int get() = (iconSize + 2 * pad).toInt()

    fun setIcon(bitmap: Bitmap?, dim: Boolean) {
        icon = bitmap
        iconAlpha = if (dim) 90 else 255
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension((blobSize + 2 * side).toInt(), (blobSize + top + bottom).toInt())
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        val s = blobSize.toFloat()
        box.set(side, top, side + s, top + s)
        // 四个角半径不一样，看起来像手剪的贴纸，而不是正圆
        blob.reset()
        blob.addRoundRect(box, floatArrayOf(s * .46f, s * .55f, s * .54f, s * .45f, s * .50f, s * .55f, s * .50f, s * .45f), Path.Direction.CW)
    }

    override fun onDraw(canvas: Canvas) {
        // 阴影：未选中时贴在纸上，选中时抬起来，阴影更远更淡开
        val dy = context.dp(4f) + lift * context.dp(10f)
        shadow.color = Color.argb((22 + lift * 14).toInt(), 80, 60, 20)
        canvas.save(); canvas.translate(0f, dy); canvas.drawPath(blob, shadow); canvas.restore()
        canvas.drawPath(blob, white)

        icon?.let {
            val half = iconSize * iconScale / 2
            iconBox.set(box.centerX() - half, box.centerY() - half, box.centerX() + half, box.centerY() + half)
            bitmapPaint.alpha = iconAlpha
            canvas.drawBitmap(it, null, iconBox, bitmapPaint)
        }

        // 胶带：斜着贴在顶上，深浅两色的 45° 斜纹
        canvas.save()
        canvas.translate(box.centerX(), box.top + context.dp(2f))
        canvas.rotate(-7f)
        val l = -tapeW / 2; val t = -tapeH / 2
        canvas.clipRect(l, t, l + tapeW, t + tapeH)
        stripe.color = tapeLight
        canvas.drawRect(l, t, l + tapeW, t + tapeH, stripe)
        stripe.color = tapeDark
        stripe.strokeWidth = context.dp(9f)
        val step = context.dp(18f)
        var x = l - tapeH
        while (x < l + tapeW + tapeH) { canvas.drawLine(x, t + tapeH, x + tapeH, t, stripe); x += step }
        canvas.restore()
    }
}

/** 荧光笔：在文字下半部画一道斜斜的色带，[progress] 0~1 从左往右划出来。 */
class Highlighter(private val context: Context, color: Int) : android.graphics.drawable.Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
    private val rect = RectF()

    var progress = 1f
        set(v) { field = v; invalidateSelf() }

    override fun draw(canvas: Canvas) {
        val b = bounds
        val h = context.dp(18f)
        val bottom = b.bottom - context.dp(10f)
        rect.set(b.left.toFloat(), bottom - h, b.left + b.width() * progress, bottom)
        canvas.save()
        canvas.rotate(-1.2f, b.exactCenterX(), b.exactCenterY())
        canvas.drawRoundRect(rect, context.dp(5f), context.dp(5f), paint)
        canvas.restore()
    }

    override fun setAlpha(alpha: Int) { paint.alpha = alpha }
    override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) { paint.colorFilter = colorFilter }
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = android.graphics.PixelFormat.TRANSLUCENT
}

/** 手绘波浪下划线，[progress] 0~1 从左往右画出来。 */
class Squiggle(context: Context, color: Int) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color; style = Paint.Style.STROKE; strokeWidth = context.dp(5f); strokeCap = Paint.Cap.ROUND
    }
    private val full = Path()
    private val part = Path()
    private val measure = PathMeasure()

    var progress = 0f
        set(v) { field = v; invalidate() }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        val p = paint.strokeWidth
        val mid = h / 2f
        full.reset()
        full.moveTo(p, mid + h * .15f)
        val waves = 4
        val seg = (w - 2 * p) / waves
        for (i in 0 until waves) {
            val x = p + seg * i
            full.quadTo(x + seg / 2, if (i % 2 == 0) mid - h * .35f else mid + h * .35f, x + seg, mid)
        }
        measure.setPath(full, false)
    }

    override fun onDraw(canvas: Canvas) {
        if (progress <= 0f) return
        part.reset()
        measure.getSegment(0f, measure.length * progress, part, true)
        part.rLineTo(0f, 0f)
        canvas.drawPath(part, paint)
    }
}

/**
 * 首页上的一个入口：贴纸 + 手写名字 + 选中时出现的说明。整体倾斜 [tilt] 度。
 * 选中时摆正、抬起、放大，图标跳一下然后轻轻呼吸，名字下画出波浪线；按确认先压一下再进入。
 */
class StickerTile(
    context: Context,
    theme: ThemeSpec,
    label: String,
    tape: Int,
    iconDp: Int,
    private val tilt: Float,
    private val onClick: () -> Unit,
) : LinearLayout(context) {
    val die = StickerDie(context, tape, iconDp)
    private val name = context.handText(label, 36f, theme.ink)
    private val squiggle = Squiggle(context, theme.accent)
    private val sub = context.themedText("", 17f, theme.inkSoft, bold = true)
    private var breathing: ValueAnimator? = null

    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        clipChildren = false; clipToPadding = false
        isFocusable = true
        rotation = tilt
        addView(die)
        addView(name, LayoutParams(-2, -2).apply { topMargin = -context.dp(4) })
        addView(squiggle, LayoutParams(context.dp(label.length * 36 + 24), context.dp(16)).apply { topMargin = -context.dp(4) })
        sub.alpha = 0f
        addView(sub, LayoutParams(-2, -2).apply { topMargin = context.dp(6) })

        setOnFocusChangeListener { _, has -> if (has) focusIn() else focusOut() }
        setOnClickListener {
            animate().scaleX(0.95f).scaleY(0.95f).setDuration(90).setInterpolator(DecelerateInterpolator()).withEndAction {
                animate().scaleX(FOCUS_SCALE).scaleY(FOCUS_SCALE).setDuration(240).setInterpolator(OvershootInterpolator(3f)).start()
                onClick()
            }.start()
        }
    }

    fun setIcon(code: String, dim: Boolean = false) = die.setIcon(Emoji.bitmap(context, code), dim)

    fun setSub(text: String) { sub.setEmojiText(text) }

    private fun focusIn() {
        animate().rotation(0f).translationY(-context.dp(16f)).scaleX(FOCUS_SCALE).scaleY(FOCUS_SCALE)
            .setInterpolator(OvershootInterpolator(2.6f)).setDuration(380).start()
        tween(die.lift, 1f, 300) { die.lift = it }
        tween(0f, 1f, 420, delay = 120) { squiggle.progress = it }
        sub.animate().alpha(1f).setStartDelay(160).setDuration(260).start()
        hop()
    }

    private fun focusOut() {
        animate().rotation(tilt).translationY(0f).scaleX(1f).scaleY(1f)
            .setInterpolator(DecelerateInterpolator()).setDuration(220).start()
        tween(die.lift, 0f, 220) { die.lift = it }
        tween(squiggle.progress, 0f, 160) { squiggle.progress = it }
        sub.animate().alpha(0f).setStartDelay(0).setDuration(140).start()
        stopBreathing()
    }

    /** 用回调而不是按属性名反射，发布版混淆后也能动。 */
    private fun tween(from: Float, to: Float, ms: Long, delay: Long = 0, set: (Float) -> Unit) =
        ValueAnimator.ofFloat(from, to).apply {
            duration = ms; startDelay = delay; interpolator = DecelerateInterpolator()
            addUpdateListener { set(it.animatedValue as Float) }
            start()
        }

    /** 图标跳一下，再开始轻轻呼吸。只有选中的那张在动，省电视的性能。 */
    private fun hop() {
        stopBreathing()
        breathing = ValueAnimator.ofFloat(1f, 1.22f, 0.94f, 1.04f, 1f).apply {
            duration = 520
            addUpdateListener { die.iconScale = it.animatedValue as Float }
            start()
        }.also { hop ->
            hop.addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(a: android.animation.Animator) {
                    if (breathing !== hop || !hasFocus()) return
                    breathing = ValueAnimator.ofFloat(1f, 1.06f).apply {
                        duration = 1100
                        repeatMode = ValueAnimator.REVERSE
                        repeatCount = ValueAnimator.INFINITE
                        interpolator = AccelerateDecelerateInterpolator()
                        addUpdateListener { die.iconScale = it.animatedValue as Float }
                        start()
                    }
                }
            })
        }
    }

    private fun stopBreathing() {
        breathing?.let { it.removeAllListeners(); it.cancel() }
        breathing = null
        die.iconScale = 1f
    }

    override fun onDetachedFromWindow() { stopBreathing(); super.onDetachedFromWindow() }

    private companion object {
        const val FOCUS_SCALE = 1.12f
    }
}
