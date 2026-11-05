package de.huyc.studyhelper.ui

import android.app.Activity
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import de.huyc.studyhelper.App
import de.huyc.studyhelper.data.Rarity
import de.huyc.studyhelper.data.RewardState
import de.huyc.studyhelper.data.StickerCatalog

/** 贴纸册：按稀有度分组展示图鉴，已收集的显示数量，没收集的是灰色剪影。方向键浏览，返回键退出。 */
class StickerBookActivity : Activity() {
    private val theme get() = Themes.get(App.instance.store.settings.theme)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = FrameLayout(this)
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
            layoutParams = GridLayout.LayoutParams().apply { width = dp(CELL_DP); height = dp(CELL_DP); setMargins(dp(6), dp(6), dp(6), dp(6)) }
        }
        box.addView(ImageView(this).apply {
            setImageBitmap(Emoji.bitmap(context, code))
            if (!owned) {
                // 灰色剪影：去色、压暗
                colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0f) })
                alpha = 0.25f
            }
        }, FrameLayout.LayoutParams(dp(CELL_DP - 36), dp(CELL_DP - 36), Gravity.CENTER))
        if (count > 1) {
            box.addView(themedText("×$count", 14f, Color.WHITE, bold = true).apply {
                background = pill(context, theme.accent)
                setPadding(dp(8), dp(1), dp(8), dp(1))
            }, FrameLayout.LayoutParams(-2, -2, Gravity.END or Gravity.BOTTOM).apply { setMargins(0, 0, dp(4), dp(4)) })
        }
        return box
    }

    private fun accent(rarity: Rarity) = when (rarity) {
        Rarity.COMMON -> theme.inkSoft
        Rarity.RARE -> theme.accentDark
        Rarity.LEGENDARY -> theme.star
    }

    private companion object {
        const val COLUMNS = 9
        const val CELL_DP = 88
    }
}
