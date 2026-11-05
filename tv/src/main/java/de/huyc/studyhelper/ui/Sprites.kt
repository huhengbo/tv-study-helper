package de.huyc.studyhelper.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.Animatable
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.LruCache
import de.huyc.studyhelper.data.SpriteCache
import de.huyc.studyhelper.data.SpriteRef
import de.huyc.studyhelper.data.SpriteSheet
import java.util.concurrent.Executors

/**
 * 题目的精灵图：整张图只解码一次，按格裁切显示。图没下载、损坏或解码失败时返回 null，由调用方显示备用的 display。
 * 内存缓存与 [Emoji] 的各自独立，整张图（不是单格）按字节数计。
 */
object Sprites {
    private const val CACHE_BYTES = 24 * 1024 * 1024
    private val cache = object : LruCache<String, Bitmap>(CACHE_BYTES) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private val decoder = Executors.newSingleThreadExecutor { Thread(it, "sprite-decode").apply { isDaemon = true } }
    private val main = Handler(Looper.getMainLooper())

    /** 整张图已在内存里时立刻给出绘制对象（主线程调用），否则 null。 */
    fun cached(ref: SpriteRef): SpriteDrawable? {
        val sheet = ref.spec ?: return null
        return cache.get(sheet.cacheKey)?.let { SpriteDrawable(it, sheet, ref) }
    }

    /** 后台解码，完成后在主线程回调；失败不回调（调用方一直显示 display）。 */
    fun load(context: Context, ref: SpriteRef, targetPx: Int, onReady: (SpriteDrawable) -> Unit) {
        val sheet = ref.spec ?: return
        val app = context.applicationContext
        decoder.execute {
            val bitmap = cache.get(sheet.cacheKey) ?: decode(app, sheet, targetPx) ?: return@execute
            main.post { onReady(SpriteDrawable(bitmap, sheet, ref)) }
        }
    }

    private fun decode(context: Context, sheet: SpriteSheet, targetPx: Int): Bitmap? {
        val file = SpriteCache.file(context, sheet)
        if (!file.isFile) return null
        val bitmap = runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.path, bounds)
            if (!SpriteCache.sizeOk(file)) return@runCatching null
            // 缩小到每格仍不小于显示尺寸；inSampleSize 只能取 2 的幂
            var sample = 1
            while (bounds.outWidth / sheet.cols / (sample * 2) >= targetPx && bounds.outHeight / sheet.rows / (sample * 2) >= targetPx) sample *= 2
            BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
        }.getOrNull()
        // 解不出来的文件删掉，下次答题前会重新下载
        if (bitmap == null) file.delete() else cache.put(sheet.cacheKey, bitmap)
        return bitmap
    }
}

/** 画精灵图里的一格；[SpriteRef.frames] 大于 1 时循环播放。停止或不可见时不再调度，不会泄漏。 */
class SpriteDrawable(private val bitmap: Bitmap, sheet: SpriteSheet, ref: SpriteRef) : Drawable(), Animatable {
    private val cols = sheet.cols
    private val cellW = bitmap.width / sheet.cols
    private val cellH = bitmap.height / sheet.rows
    private val first = ref.cell
    private val frames = ref.frames
    private val periodMs = 1000L / ref.fps
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val src = Rect()
    private var frame = 0
    private var running = false

    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            frame = (frame + 1) % frames
            invalidateSelf()
            scheduleSelf(this, SystemClock.uptimeMillis() + periodMs)
        }
    }

    override fun draw(canvas: Canvas) {
        val index = first + frame
        val left = index % cols * cellW
        val top = index / cols * cellH
        src.set(left, top, left + cellW, top + cellH)
        canvas.drawBitmap(bitmap, src, bounds, paint)
    }

    override fun start() {
        if (frames <= 1 || running) return
        running = true
        scheduleSelf(tick, SystemClock.uptimeMillis() + periodMs)
    }

    override fun stop() {
        running = false
        unscheduleSelf(tick)
    }

    override fun isRunning() = running

    override fun setVisible(visible: Boolean, restart: Boolean): Boolean {
        if (!visible) stop()
        return super.setVisible(visible, restart)
    }

    override fun getIntrinsicWidth() = cellW
    override fun getIntrinsicHeight() = cellH
    override fun setAlpha(alpha: Int) { paint.alpha = alpha }
    override fun setColorFilter(filter: ColorFilter?) { paint.colorFilter = filter }
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}
