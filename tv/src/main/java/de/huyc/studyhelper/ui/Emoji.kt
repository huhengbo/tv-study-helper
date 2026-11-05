package de.huyc.studyhelper.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ImageSpan
import android.util.LruCache
import android.widget.TextView

/**
 * 小米电视 4A 的系统字体没有彩色表情，表情会显示成空白或方框。
 * 这里改用打包在 assets/emoji 下的 Noto 表情图片（文件名为码位，如 1f34c.webp）。
 */
object Emoji {
    private const val DIR = "emoji"
    private const val VS16 = 0xFE0F
    @Volatile private var available: Set<String>? = null
    private val cache = object : LruCache<String, Bitmap>(8 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    fun available(context: Context): Set<String> = available ?: synchronized(this) {
        available ?: (context.assets.list(DIR)?.map { it.substringBefore('.') }?.toSet() ?: emptySet()).also { available = it }
    }

    /** 文本是单个可显示的表情时返回其码位（如 "1f34c"），否则 null。 */
    fun single(context: Context, text: String): String? {
        val cps = codePoints(text.trim()).filter { it != VS16 }
        if (cps.size != 1) return null
        return key(cps[0]).takeIf { it in available(context) }
    }

    /** 文本里含有电视无法显示的表情。 */
    fun unsupported(context: Context, text: String): Boolean =
        codePoints(text).any { it != VS16 && isEmojiLike(it) && key(it) !in available(context) }

    fun bitmap(context: Context, key: String): Bitmap? = cache.get(key) ?: runCatching {
        context.assets.open("$DIR/$key.webp").use { BitmapFactory.decodeStream(it) }
    }.getOrNull()?.also { cache.put(key, it) }

    fun drawable(context: Context, key: String, sizePx: Int): Drawable? =
        bitmap(context, key)?.let { BitmapDrawable(context.resources, it).apply { setBounds(0, 0, sizePx, sizePx) } }

    /** 把文本里能显示的表情替换成图片，不能显示的表情去掉（避免出现方框）。 */
    fun spannable(context: Context, text: CharSequence, sizePx: Int): CharSequence {
        val out = SpannableStringBuilder()
        val s = text.toString()
        var i = 0
        while (i < s.length) {
            val cp = s.codePointAt(i)
            val len = Character.charCount(cp)
            when {
                cp == VS16 -> {}
                isEmojiLike(cp) -> {
                    val d = drawable(context, key(cp), sizePx)
                    if (d != null) {
                        val start = out.length
                        out.append("￼")
                        out.setSpan(ImageSpan(d, ImageSpan.ALIGN_BASELINE), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    }
                }
                else -> out.append(s, i, i + len)
            }
            i += len
        }
        return out
    }

    private fun key(cp: Int) = Integer.toHexString(cp)

    private fun codePoints(s: String): List<Int> {
        val list = mutableListOf<Int>()
        var i = 0
        while (i < s.length) { val cp = s.codePointAt(i); list += cp; i += Character.charCount(cp) }
        return list
    }

    /** 粗略判断：辅助平面符号、杂项符号区、箭头/播放控制等常见表情码位。 */
    private fun isEmojiLike(cp: Int) = cp >= 0x1F000 || cp in 0x2600..0x27BF || cp in 0x2300..0x23FF ||
        cp in 0x2B00..0x2BFF || cp == 0x25B6 || cp == 0x25C0
}

/** 设置可能含表情的文字，表情按当前字号显示为图片。 */
fun TextView.setEmojiText(text: CharSequence) {
    setText(Emoji.spannable(context, text, (textSize * 1.1f).toInt()))
}
