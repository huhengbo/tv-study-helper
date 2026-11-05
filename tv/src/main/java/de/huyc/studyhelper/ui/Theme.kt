package de.huyc.studyhelper.ui

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.util.TypedValue

/**
 * 界面主题。手机端可切换主题 id；有「童趣」「探险蓝（男孩）」「梦幻粉（女孩）」「宝宝乐园（学龄前）」四套，新增主题只需在 [Themes] 里注册，
 * 并在手机端 index.html 的主题下拉里加一项。
 * 颜色都偏暖、低饱和，避免在电视大屏上刺眼。
 */
data class ThemeSpec(
    val id: String,
    val name: String,
    val bgTop: Int,
    val bgBottom: Int,
    val blobs: List<Int>,
    val card: Int,
    val ink: Int,
    val inkSoft: Int,
    val accent: Int,
    val accentDark: Int,
    val success: Int,
    val error: Int,
    val star: Int,
    val starEmpty: Int,
    val mascots: List<String>,
    /** 背景里缓慢漂浮的表情图片码位，让主题一眼能认出来；空表示只有色块。 */
    val decor: List<String> = emptyList(),
)

object Themes {
    // 用 ARGB 字面量而不是 Color.parseColor，这样主题表可以直接在 JVM 单测里检查
    private fun argb(v: Long) = v.toInt()

    val KIDS = ThemeSpec(
        id = "kids",
        name = "童趣",
        bgTop = argb(0xFFFFF3D9),
        bgBottom = argb(0xFFFFD8C9),
        blobs = listOf(argb(0x88FFB4A2), argb(0x88B5E3D8), argb(0x88FFE08A), argb(0x88C9B6FF)),
        card = argb(0xFFFFFFFF),
        ink = argb(0xFF3D2C5E),
        inkSoft = argb(0xFF8A7BA8),
        accent = argb(0xFFFF7A59),
        accentDark = argb(0xFFF2552F),
        success = argb(0xFF2FBF7F),
        error = argb(0xFFFF5C6C),
        star = argb(0xFFFFC531),
        starEmpty = argb(0xFFE9DCCB),
        // 表情图片的码位（assets/emoji）：熊猫、兔子、熊、猫、狗
        mascots = listOf("1f43c", "1f430", "1f43b", "1f431", "1f436"),
    )

    /** 男孩：天空蓝 + 探险、太空、车辆。 */
    val EXPLORER = ThemeSpec(
        id = "boy",
        name = "探险蓝（男孩）",
        bgTop = argb(0xFFA9D4FF),
        bgBottom = argb(0xFF6FADFF),
        blobs = listOf(argb(0xAA3F86F0), argb(0xAA4CCBE0), argb(0xAA7FE0A0), argb(0xAAFFD24D)),
        card = argb(0xFFFFFFFF),
        ink = argb(0xFF10294A),
        inkSoft = argb(0xFF3F5F8A),
        accent = argb(0xFF2F7DE1),
        accentDark = argb(0xFF1B5FC4),
        success = argb(0xFF1FAF6B),
        error = argb(0xFFF2555A),
        star = argb(0xFFFFB800),
        starEmpty = argb(0xFFD5E0EC),
        // 火箭、霸王龙、消防车、小汽车、狮子
        mascots = listOf("1f680", "1f996", "1f692", "1f697", "1f981"),
        // 火箭、星星、土星、恐龙、赛车、月亮
        decor = listOf("1f680", "2b50", "1fa90", "1f995", "1f3ce", "1f31f", "1f319", "1f6f8"),
    )

    /** 女孩：粉紫 + 独角兽、蝴蝶、花朵。 */
    val DREAMY = ThemeSpec(
        id = "girl",
        name = "梦幻粉（女孩）",
        bgTop = argb(0xFFFFC9E3),
        bgBottom = argb(0xFFFF9FCC),
        blobs = listOf(argb(0xAAFF7FBC), argb(0xAAC29CFF), argb(0xAAFFB88A), argb(0xAA9FE3C8)),
        card = argb(0xFFFFFFFF),
        ink = argb(0xFF4A1D52),
        inkSoft = argb(0xFF8A4F8F),
        accent = argb(0xFFE0488F),
        accentDark = argb(0xFFC22D75),
        success = argb(0xFF2FB585),
        error = argb(0xFFF0506E),
        star = argb(0xFFFFC531),
        starEmpty = argb(0xFFEBD8E4),
        // 独角兽、蝴蝶、兔子、猫、樱花
        mascots = listOf("1f984", "1f98b", "1f430", "1f431", "1f338"),
        // 花束、花朵、蝴蝶、彩虹、蝴蝶结、蛋糕
        decor = listOf("1f490", "1f338", "1f98b", "1f308", "1f380", "1f337", "1f9c1", "2728"),
    )

    /** 学龄前：明快的三原色 + 圆滚滚的小动物，色块更大更鲜艳。 */
    val PRESCHOOL = ThemeSpec(
        id = "preschool",
        name = "宝宝乐园（学龄前）",
        bgTop = argb(0xFFFFEE7A),
        bgBottom = argb(0xFF8FD8FF),
        blobs = listOf(argb(0xBBFF8A93), argb(0xBB5BB8FF), argb(0xBBFFD93D), argb(0xBB6FD68F)),
        card = argb(0xFFFFFFFF),
        ink = argb(0xFF1F2E4A),
        inkSoft = argb(0xFF55658A),
        accent = argb(0xFF8A5CF0),
        accentDark = argb(0xFF6F3FD6),
        success = argb(0xFF35B45B),
        error = argb(0xFFF2545B),
        star = argb(0xFFFFCE1F),
        starEmpty = argb(0xFFE3E8EF),
        // 小鸡、小狗、小猪、大象、考拉、老虎
        mascots = listOf("1f425", "1f436", "1f437", "1f418", "1f428", "1f42f"),
        // 气球、彩虹、太阳、小花、积木、冰淇淋
        decor = listOf("1f388", "1f308", "2600", "1f33c", "1f9e9", "1f366", "1f98b", "1f353"),
    )

    val all = listOf(KIDS, EXPLORER, DREAMY, PRESCHOOL)

    fun get(id: String): ThemeSpec = all.firstOrNull { it.id == id } ?: KIDS
}

fun Context.dp(v: Float): Float = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)
fun Context.dp(v: Int): Int = dp(v.toFloat()).toInt()

val BOLD: Typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)

fun withAlpha(color: Int, alpha: Float): Int =
    Color.argb((Color.alpha(color) * alpha).toInt(), Color.red(color), Color.green(color), Color.blue(color))
