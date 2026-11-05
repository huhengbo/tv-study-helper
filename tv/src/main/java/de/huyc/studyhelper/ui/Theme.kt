package de.huyc.studyhelper.ui

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.util.TypedValue

/**
 * 界面主题。手机端可切换主题 id；目前只有「童趣」一套，新增主题只需在 [Themes] 里注册。
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
)

object Themes {
    val KIDS = ThemeSpec(
        id = "kids",
        name = "童趣",
        bgTop = Color.parseColor("#FFF3D9"),
        bgBottom = Color.parseColor("#FFD8C9"),
        blobs = listOf(
            Color.parseColor("#88FFB4A2"),
            Color.parseColor("#88B5E3D8"),
            Color.parseColor("#88FFE08A"),
            Color.parseColor("#88C9B6FF"),
        ),
        card = Color.WHITE,
        ink = Color.parseColor("#3D2C5E"),
        inkSoft = Color.parseColor("#8A7BA8"),
        accent = Color.parseColor("#FF7A59"),
        accentDark = Color.parseColor("#F2552F"),
        success = Color.parseColor("#2FBF7F"),
        error = Color.parseColor("#FF5C6C"),
        star = Color.parseColor("#FFC531"),
        starEmpty = Color.parseColor("#E9DCCB"),
        // 表情图片的码位（assets/emoji）：熊猫、兔子、熊、猫、狗
        mascots = listOf("1f43c", "1f430", "1f43b", "1f431", "1f436"),
    )

    val all = listOf(KIDS)

    fun get(id: String): ThemeSpec = all.firstOrNull { it.id == id } ?: KIDS
}

fun Context.dp(v: Float): Float = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)
fun Context.dp(v: Int): Int = dp(v.toFloat()).toInt()

val BOLD: Typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)

fun withAlpha(color: Int, alpha: Float): Int =
    Color.argb((Color.alpha(color) * alpha).toInt(), Color.red(color), Color.green(color), Color.blue(color))
