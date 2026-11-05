package de.huyc.studyhelper.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/**
 * 渐变背景 + 缓慢漂浮的柔和色块。每个色块是独立的硬件层 View，只做平移/缩放动画，
 * 由 GPU 合成，不触发整屏重绘——电视的 Mali-450 GPU 较弱，这样才能保持流畅。
 */
class FloatingBackground(context: Context, theme: ThemeSpec) : FrameLayout(context) {
    private val animators = mutableListOf<Animator>()

    init {
        background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(theme.bgTop, theme.bgBottom))
        val rnd = Random(7)
        val spots = listOf(0.08f to 0.15f, 0.82f to 0.12f, 0.15f to 0.78f, 0.88f to 0.72f, 0.5f to 0.92f, 0.62f to 0.05f)
        spots.forEachIndexed { i, (fx, fy) ->
            val size = context.dp(120 + rnd.nextInt(140))
            val blob = View(context).apply {
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(theme.blobs[i % theme.blobs.size]) }
                setLayerType(LAYER_TYPE_HARDWARE, null)
            }
            addView(blob, LayoutParams(size, size))
            blob.tag = floatArrayOf(fx, fy)
            val drift = context.dp(40f + rnd.nextInt(40))
            animators += ObjectAnimator.ofFloat(blob, "translationY", -drift, drift).apply {
                duration = 6000L + rnd.nextInt(5000); repeatCount = ValueAnimator.INFINITE
                repeatMode = ValueAnimator.REVERSE; interpolator = AccelerateDecelerateInterpolator()
            }
            animators += ObjectAnimator.ofFloat(blob, "translationX", -drift / 2, drift / 2).apply {
                duration = 8000L + rnd.nextInt(5000); repeatCount = ValueAnimator.INFINITE
                repeatMode = ValueAnimator.REVERSE; interpolator = AccelerateDecelerateInterpolator()
            }
        }
        // 主题表情：只出现在四周，漂浮、轻轻摇摆
        val edges = listOf(0.04f to 0.45f, 0.95f to 0.42f, 0.32f to 0.07f, 0.74f to 0.94f, 0.5f to 0.95f, 0.94f to 0.92f, 0.5f to 0.04f, 0.97f to 0.2f)
        theme.decor.take(edges.size).forEachIndexed { i, code ->
            val bmp = Emoji.bitmap(context, code) ?: return@forEachIndexed
            val size = context.dp(64 + rnd.nextInt(40))
            val sprite = ImageView(context).apply {
                setImageBitmap(bmp); alpha = 0.7f; setLayerType(LAYER_TYPE_HARDWARE, null)
            }
            addView(sprite, LayoutParams(size, size))
            sprite.tag = floatArrayOf(edges[i].first, edges[i].second)
            animators += ObjectAnimator.ofFloat(sprite, "translationY", -context.dp(16f), context.dp(16f)).apply {
                duration = 3500L + rnd.nextInt(2500); repeatCount = ValueAnimator.INFINITE
                repeatMode = ValueAnimator.REVERSE; interpolator = AccelerateDecelerateInterpolator()
            }
            animators += ObjectAnimator.ofFloat(sprite, "rotation", -12f, 12f).apply {
                duration = 2500L + rnd.nextInt(2000); repeatCount = ValueAnimator.INFINITE
                repeatMode = ValueAnimator.REVERSE; interpolator = AccelerateDecelerateInterpolator()
            }
        }
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            val (fx, fy) = (c.tag as? FloatArray ?: continue).let { it[0] to it[1] }
            val x = ((right - left) * fx - c.measuredWidth / 2).toInt()
            val y = ((bottom - top) * fy - c.measuredHeight / 2).toInt()
            c.layout(x, y, x + c.measuredWidth, y + c.measuredHeight)
        }
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); animators.forEach { it.start() } }
    override fun onDetachedFromWindow() { animators.forEach { it.cancel() }; super.onDetachedFromWindow() }
}

/** 白色圆角卡片 + 柔和投影。 */
fun cardBackground(context: Context, color: Int, radiusDp: Int = 36): GradientDrawable =
    GradientDrawable().apply { setColor(color); cornerRadius = context.dp(radiusDp.toFloat()) }

fun pill(context: Context, color: Int, strokeColor: Int = 0, strokeDp: Int = 0): GradientDrawable =
    GradientDrawable().apply {
        setColor(color); cornerRadius = context.dp(999f)
        if (strokeDp > 0) setStroke(context.dp(strokeDp), strokeColor)
    }

/**
 * 麦克风按钮：录音时向外扩散三圈波纹，波纹幅度跟随实时音量。
 * 只在自身区域重绘（约 220dp 见方）。
 */
class MicView(context: Context, private val theme: ThemeSpec) : View(context) {
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val icon = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.WHITE; style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val iconFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.WHITE }
    private var phase = 0f
    private var level = 0f
    private var shownLevel = 0f
    var listening = false
        set(v) {
            field = v
            if (v) pulse.start() else { pulse.cancel(); level = 0f; shownLevel = 0f }
            invalidate()
        }

    private val pulse = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 1400; repeatCount = ValueAnimator.INFINITE; interpolator = LinearInterpolator()
        addUpdateListener { phase = it.animatedValue as Float; shownLevel += (level - shownLevel) * 0.25f; invalidate() }
    }

    fun setLevel(v: Float) { level = v }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val base = min(width, height) * 0.22f
        if (listening) {
            for (k in 0 until 3) {
                val p = (phase + k / 3f) % 1f
                val r = base * (1f + p * (0.9f + shownLevel * 1.2f))
                ring.strokeWidth = context.dp(4f) * (1f - p)
                ring.color = withAlpha(theme.accent, 1f - p)
                canvas.drawCircle(cx, cy, r, ring)
            }
        }
        fill.color = if (listening) theme.accentDark else theme.accent
        canvas.drawCircle(cx, cy, base * (1f + shownLevel * 0.15f), fill)
        // 麦克风图标：胶囊 + 支架
        val w = base * 0.42f
        val h = base * 0.72f
        canvas.drawRoundRect(RectF(cx - w / 2, cy - h * 0.62f, cx + w / 2, cy + h * 0.18f), w / 2, w / 2, iconFill)
        icon.strokeWidth = base * 0.09f
        val arc = RectF(cx - w * 0.9f, cy - h * 0.45f, cx + w * 0.9f, cy + h * 0.38f)
        canvas.drawArc(arc, 20f, 140f, false, icon)
        canvas.drawLine(cx, cy + h * 0.38f, cx, cy + h * 0.6f, icon)
    }

    override fun onDetachedFromWindow() { pulse.cancel(); super.onDetachedFromWindow() }
}

/** 「我想想…」三个跳动的小圆点。 */
class DotsView(context: Context, color: Int) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
    private var t = 0f
    private val anim = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 900; repeatCount = ValueAnimator.INFINITE; interpolator = LinearInterpolator()
        addUpdateListener { t = it.animatedValue as Float; invalidate() }
    }

    override fun onDraw(canvas: Canvas) {
        val r = height / 6f
        val gap = width / 3f
        for (i in 0 until 3) {
            val p = ((t - i * 0.18f) % 1f + 1f) % 1f
            val lift = if (p < 0.5f) sin(p * 2 * PI).toFloat() else 0f
            canvas.drawCircle(gap * i + gap / 2, height / 2f + r - lift * r * 2.2f, r, paint)
        }
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (visibility == VISIBLE && isAttachedToWindow) anim.start() else anim.cancel()
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); if (visibility == VISIBLE) anim.start() }
    override fun onDetachedFromWindow() { anim.cancel(); super.onDetachedFromWindow() }
}

/** 一颗星：空心/实心，点亮时由外部做弹跳缩放。 */
class StarView(context: Context, private val theme: ThemeSpec) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    var filled = false
        set(v) { field = v; invalidate() }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val outer = min(width, height) / 2f * 0.95f
        val inner = outer * 0.48f
        path.reset()
        for (i in 0 until 10) {
            val r = if (i % 2 == 0) outer else inner
            val a = -PI / 2 + i * PI / 5
            val x = cx + (r * cos(a)).toFloat()
            val y = cy + (r * sin(a)).toFloat()
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()
        paint.color = if (filled) theme.star else theme.starEmpty
        canvas.drawPath(path, paint)
    }
}

/**
 * 答对时的彩纸礼花：粒子从中心向上喷出后受重力下落，约 1.6 秒结束。
 * 只在 [burst] 后运行动画，平时不占用绘制。
 */
class ConfettiView(context: Context, private val colors: List<Int>) : View(context) {
    private class Bit(var x: Float, var y: Float, var vx: Float, var vy: Float, var rot: Float, val vr: Float, val color: Int, val w: Float, val h: Float, val round: Boolean)
    private val bits = mutableListOf<Bit>()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var last = 0L
    private var anim: ValueAnimator? = null

    fun burst(count: Int = 70) {
        val rnd = Random(System.nanoTime())
        bits.clear()
        val cx = width / 2f
        val cy = height * 0.45f
        repeat(count) {
            val angle = (-PI / 2 + (rnd.nextFloat() - 0.5f) * PI * 0.9f)
            val speed = context.dp(500f + rnd.nextFloat() * 700f)
            bits += Bit(cx, cy, (cos(angle) * speed).toFloat(), (sin(angle) * speed).toFloat(),
                rnd.nextFloat() * 360f, (rnd.nextFloat() - 0.5f) * 720f, colors[rnd.nextInt(colors.size)],
                context.dp(8f + rnd.nextFloat() * 8f), context.dp(5f + rnd.nextFloat() * 6f), rnd.nextBoolean())
        }
        anim?.cancel()
        last = 0L
        anim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1600
            addUpdateListener { step() }
            addListener(object : AnimatorListenerAdapter() { override fun onAnimationEnd(a: Animator) { bits.clear(); invalidate() } })
            start()
        }
    }

    private fun step() {
        val now = System.nanoTime()
        val dt = if (last == 0L) 0.016f else ((now - last) / 1e9f).coerceAtMost(0.05f)
        last = now
        val g = context.dp(1400f)
        for (b in bits) {
            b.vy += g * dt; b.vx *= 0.985f
            b.x += b.vx * dt; b.y += b.vy * dt; b.rot += b.vr * dt
        }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        for (b in bits) {
            paint.color = b.color
            canvas.save()
            canvas.rotate(b.rot, b.x, b.y)
            if (b.round) canvas.drawCircle(b.x, b.y, b.h * 0.6f, paint)
            else canvas.drawRect(b.x - b.w / 2, b.y - b.h / 2, b.x + b.w / 2, b.y + b.h / 2, paint)
            canvas.restore()
        }
    }

    override fun onDetachedFromWindow() { anim?.cancel(); super.onDetachedFromWindow() }
}

/** 答对/答错徽章：圆形底 + 手绘对勾或叉，不依赖字体里的 ✓ ✗ 字形。 */
class BadgeView(context: Context) : View(context) {
    private val bg = Paint(Paint.ANTI_ALIAS_FLAG)
    private val mark = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.WHITE; style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val path = Path()
    private var correct = true

    fun set(correct: Boolean, color: Int) { this.correct = correct; bg.color = color; invalidate() }

    override fun onDraw(canvas: Canvas) {
        val s = min(width, height).toFloat()
        canvas.drawCircle(width / 2f, height / 2f, s / 2f, bg)
        mark.strokeWidth = s * 0.11f
        val l = (width - s) / 2f
        val t = (height - s) / 2f
        path.reset()
        if (correct) {
            path.moveTo(l + s * 0.28f, t + s * 0.52f); path.lineTo(l + s * 0.44f, t + s * 0.68f); path.lineTo(l + s * 0.73f, t + s * 0.36f)
        } else {
            path.moveTo(l + s * 0.33f, t + s * 0.33f); path.lineTo(l + s * 0.67f, t + s * 0.67f)
            path.moveTo(l + s * 0.67f, t + s * 0.33f); path.lineTo(l + s * 0.33f, t + s * 0.67f)
        }
        canvas.drawPath(path, mark)
    }
}
