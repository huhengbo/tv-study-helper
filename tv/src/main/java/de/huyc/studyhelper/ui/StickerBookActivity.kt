package de.huyc.studyhelper.ui

import android.app.Activity
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.view.doOnPreDraw
import de.huyc.studyhelper.App
import de.huyc.studyhelper.data.Rarity
import de.huyc.studyhelper.data.RewardState
import de.huyc.studyhelper.data.StickerCatalog

/**
 * 贴纸册：按稀有度分组展示图鉴，已收集的显示数量，没收集的是灰色剪影。
 * 方向键浏览，确认键放大看选中的贴纸，返回键退出。
 */
class StickerBookActivity : Activity() {
    private val theme get() = Themes.get(App.instance.store.settings.theme)
    private lateinit var root: FrameLayout
    /** 放大层和它是从哪个格子放大出来的，关闭时缩回去。 */
    private var zoom: Pair<View, View>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        root = FrameLayout(this)
        root.addView(FloatingBackground(this, theme), FrameLayout.LayoutParams(-1, -1))
        root.addView(buildContent(App.instance.store.rewards()), FrameLayout.LayoutParams(-1, -1))
        setContentView(root)
    }

    private fun buildContent(state: RewardState): View {
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false; clipToPadding = false
            setPadding(dp(56), dp(28), dp(56), dp(20))
        }
        page.addView(themedText("", 34f, theme.ink, bold = true).apply {
            setEmojiText("🌟 我的贴纸  ${state.stickers.size}/${StickerCatalog.total}")
        })
        page.addView(themedText("每答完一轮题就能抽一张，抽到重复的会记数量", 16f, theme.inkSoft).apply { setPadding(0, dp(4), 0, dp(10)) })

        val list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false; clipToPadding = false
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        var firstCell: View? = null
        for (rarity in Rarity.entries) {
            val codes = StickerCatalog.byRarity.getValue(rarity)
            val got = codes.count { it in state.stickers }
            list.addView(themedText("${rarity.label}  $got/${codes.size}", 20f, accent(rarity), bold = true).apply { setPadding(dp(4), dp(14), 0, dp(8)) })
            val grid = GridLayout(this).apply { columnCount = COLUMNS; clipChildren = false; clipToPadding = false }
            for (code in codes) {
                val cell = cell(code, state.stickers[code]?.count ?: 0)
                if (firstCell == null) firstCell = cell
                grid.addView(cell)
            }
            list.addView(grid)
        }
        val scroll = ScrollView(this).apply { isVerticalScrollBarEnabled = false; clipChildren = false; clipToPadding = false }
        scroll.addView(list)
        page.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        page.addView(themedText("按返回键回到首页", 14f, theme.inkSoft).apply { gravity = Gravity.END })
        firstCell?.let { c -> page.post { c.requestFocus() } }
        return page
    }

    private fun cell(code: String, count: Int): View {
        val owned = count > 0
        val box = FrameLayout(this).apply {
            isFocusable = true
            val normal = cardBackground(context, withAlpha(theme.card, if (owned) 0.9f else 0.45f), 20)
            val focused = cardBackground(context, theme.card, 20)
            background = normal
            setOnFocusChangeListener { v, has ->
                v.background = if (has) focused else normal
                v.elevation = if (has) dp(10f) else 0f
                v.animate().scaleX(if (has) 1.12f else 1f).scaleY(if (has) 1.12f else 1f)
                    .setInterpolator(OvershootInterpolator(2.5f)).setDuration(200).start()
            }
            setOnClickListener { showZoom(this, code, count) }
            layoutParams = GridLayout.LayoutParams().apply { width = dp(CELL_DP); height = dp(CELL_DP); setMargins(dp(6), dp(6), dp(6), dp(6)) }
        }
        box.addView(stickerImage(code, owned), FrameLayout.LayoutParams(dp(CELL_DP - 36), dp(CELL_DP - 36), Gravity.CENTER))
        if (count > 1) {
            box.addView(themedText("×$count", 14f, Color.WHITE, bold = true).apply {
                background = pill(context, theme.accent)
                setPadding(dp(8), dp(1), dp(8), dp(1))
            }, FrameLayout.LayoutParams(-2, -2, Gravity.END or Gravity.BOTTOM).apply { setMargins(0, 0, dp(4), dp(4)) })
        }
        return box
    }

    private fun stickerImage(code: String, owned: Boolean) = ImageView(this).apply {
        setImageBitmap(Emoji.bitmap(context, code))
        if (!owned) {
            // 灰色剪影：去色、压暗
            colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0f) })
            alpha = 0.25f
        }
    }

    /** 从格子的位置放大到屏幕中间：大图、稀有度和数量。确认或返回键缩回去。 */
    private fun showZoom(cell: View, code: String, count: Int) {
        if (zoom != null) return
        val owned = count > 0
        val rarity = StickerCatalog.rarityOf(code) ?: Rarity.COMMON
        val scrim = FrameLayout(this).apply {
            setBackgroundColor(Color.argb(140, 0, 0, 0))
            isFocusable = true
            setOnClickListener { hideZoom() }
        }
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            background = cardBackground(context, theme.card, 40)
            setPadding(dp(48), dp(36), dp(48), dp(28))
        }
        card.addView(stickerImage(code, owned), LinearLayout.LayoutParams(dp(ZOOM_DP), dp(ZOOM_DP)))
        card.addView(themedText("${rarity.label}贴纸", 26f, accent(rarity), bold = true).apply { setPadding(0, dp(16), 0, 0) })
        card.addView(themedText(if (owned) "已经有 $count 张" else "还没抽到", 20f, theme.ink).apply { setPadding(0, dp(6), 0, 0) })
        card.addView(themedText("按确认键或返回键收起", 14f, theme.inkSoft).apply { setPadding(0, dp(18), 0, 0) })
        for (i in 1 until card.childCount) (card.getChildAt(i) as TextView).gravity = Gravity.CENTER_HORIZONTAL
        scrim.addView(card, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        root.addView(scrim, FrameLayout.LayoutParams(-1, -1))
        zoom = scrim to cell
        scrim.requestFocus()

        scrim.alpha = 0f
        scrim.animate().alpha(1f).setDuration(ZOOM_MS).start()
        card.doOnPreDraw {
            moveOnto(card, cell)
            card.animate().translationX(0f).translationY(0f).scaleX(1f).scaleY(1f)
                .setInterpolator(OvershootInterpolator(1.4f)).setDuration(ZOOM_MS + 120).withLayer().start()
        }
    }

    private fun hideZoom() {
        val (scrim, cell) = zoom ?: return
        zoom = null
        val card = (scrim as ViewGroup).getChildAt(0)
        val to = FloatArray(3).also { moveOnto(card, cell, it) }
        card.animate().translationX(to[0]).translationY(to[1]).scaleX(to[2]).scaleY(to[2])
            .setInterpolator(DecelerateInterpolator()).setDuration(ZOOM_MS).withLayer().start()
        scrim.animate().alpha(0f).setDuration(ZOOM_MS).withEndAction { root.removeView(scrim) }.start()
        cell.requestFocus()
    }

    /** 让 [card] 看起来正好盖在 [cell] 上（按中心对齐、按宽度缩放）。给了 [out] 就只算出平移和缩放，不改 [card]。 */
    private fun moveOnto(card: View, cell: View, out: FloatArray? = null) {
        val from = IntArray(2).also(cell::getLocationOnScreen)
        val at = IntArray(2).also(card::getLocationOnScreen)
        // 算的是 card 不带平移和缩放时的位置
        val cardLeft = at[0] - card.translationX - card.width * (1 - card.scaleX) / 2
        val cardTop = at[1] - card.translationY - card.height * (1 - card.scaleY) / 2
        val scale = cell.width * cell.scaleX / card.width
        val dx = from[0] + cell.width * cell.scaleX / 2 - (cardLeft + card.width / 2f)
        val dy = from[1] + cell.height * cell.scaleY / 2 - (cardTop + card.height / 2f)
        if (out != null) { out[0] = dx; out[1] = dy; out[2] = scale; return }
        card.translationX = dx; card.translationY = dy; card.scaleX = scale; card.scaleY = scale
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // 放大时方向键不移动后面格子的焦点
        if (zoom != null && event.keyCode in DPAD_MOVES) return true
        return super.dispatchKeyEvent(event)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (zoom != null) hideZoom() else super.onBackPressed()
    }

    private fun accent(rarity: Rarity) = when (rarity) {
        Rarity.COMMON -> theme.inkSoft
        Rarity.RARE -> theme.accentDark
        Rarity.LEGENDARY -> theme.star
    }

    private companion object {
        const val COLUMNS = 9
        const val CELL_DP = 88
        const val ZOOM_DP = 280
        const val ZOOM_MS = 220L
        val DPAD_MOVES = setOf(KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT)
    }
}
