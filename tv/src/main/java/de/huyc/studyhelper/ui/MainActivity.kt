package de.huyc.studyhelper.ui

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import de.huyc.studyhelper.App
import de.huyc.studyhelper.core.GuardService
import de.huyc.studyhelper.data.DailyStats
import de.huyc.studyhelper.data.RewardRules
import de.huyc.studyhelper.data.StickerCatalog
import de.huyc.studyhelper.quiz.QuizMode
import de.huyc.studyhelper.voice.VoiceProviders

/**
 * 电视端首页，面向孩子，手账贴纸风格（规则见 docs/design/DESIGN.md）：
 * 纸面上贴着几张入口贴纸（学习、贴纸册，以后的学习游戏也贴在这一排），选中的那张摆正、抬起，并念出介绍。
 * 大人的东西在角落：右上角便利贴是剩余观看时间，右下角小锁是家长入口（规则、扫码、暂停守护等在 [ParentActivity]）。
 */
class MainActivity : Activity() {
    private val app get() = App.instance
    private val theme get() = Themes.get(app.store.settings.theme)
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var root: FrameLayout
    private lateinit var peek: ImageView
    private lateinit var title: TextView
    private lateinit var band: Highlighter
    private lateinit var note: FrameLayout
    private lateinit var noteTop: TextView
    private lateinit var noteNum: TextView
    private lateinit var noteUnit: TextView
    private lateinit var studyTile: StickerTile
    private lateinit var stickerTile: StickerTile
    private lateinit var stampRow: LinearLayout
    private lateinit var stamps: LinearLayout
    private lateinit var lock: FrameLayout
    private lateinit var hint: TextView
    private var ticks = 0
    private var entered = false
    private var lastFocused: View? = null
    private var shownStars = -1
    /** 选中某个入口时念的话；「学习」「贴纸册」只看字不念，免得孩子挪一下就说一句。 */
    private val lines = mutableMapOf<View, String>()
    /** 刚进入页面时的自动聚焦不朗读，孩子按方向键挪动后才念。 */
    private var userMoved = false
    private val speakLater = Runnable { speakFocused() }

    private val refresher = object : Runnable {
        override fun run() { refresh(); handler.postDelayed(this, 1000) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        GuardService.start(this)
        root = FrameLayout(this)
        root.addView(PaperBackground(this, theme), FrameLayout.LayoutParams(-1, -1))
        root.addView(buildContent(), FrameLayout.LayoutParams(-1, -1))
        setContentView(root)
        // 升级前已经弹过授权页的老用户不再引导
        if (app.store.setupPrompted) app.store.onboarded = true
        if (savedInstanceState == null && !app.store.onboarded) startActivity(Intent(this, WelcomeActivity::class.java))
    }

    private fun buildContent(): View {
        val page = FrameLayout(this).apply { clipChildren = false; clipToPadding = false }

        // 左上：探头的吉祥物和手写标题，标题下一道荧光笔
        peek = ImageView(this).apply { setImageBitmap(Emoji.bitmap(context, theme.mascots.first())); rotation = -8f }
        page.addView(peek, FrameLayout.LayoutParams(dp(112), dp(112), Gravity.TOP or Gravity.START).apply { leftMargin = dp(44); topMargin = dp(24) })
        band = Highlighter(this, withAlpha(theme.star, 0.6f))
        title = handText("今天玩点什么？", 46f, theme.ink).apply { background = band; setPadding(dp(8), 0, dp(12), 0) }
        page.addView(title, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START).apply { leftMargin = dp(168); topMargin = dp(48) })

        // 右上：便利贴写着还能看几分钟
        note = FrameLayout(this).apply {
            clipChildren = false; clipToPadding = false
            setBackgroundColor(NOTE_YELLOW); elevation = dp(6f); rotation = 4f
        }
        val noteText = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER }
        noteTop = handText("还能看", 21f, NOTE_INK)
        noteNum = handText("", 40f, theme.accentDark)
        noteUnit = handText("分钟", 21f, NOTE_INK)
        for (v in listOf(noteTop, noteNum, noteUnit)) noteText.addView(v, LinearLayout.LayoutParams(-2, -2))
        note.addView(noteText, FrameLayout.LayoutParams(-1, -1))
        note.addView(View(this).apply { setBackgroundColor(withAlpha(Color.WHITE, 0.55f)); rotation = -6f; elevation = dp(1f) },
            FrameLayout.LayoutParams(dp(60), dp(22), Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = -dp(10) })
        page.addView(note, FrameLayout.LayoutParams(dp(132), dp(116), Gravity.TOP or Gravity.END).apply { rightMargin = dp(46); topMargin = dp(36) })

        // 中间：一排入口贴纸，错落、各自歪一点
        val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; clipChildren = false; clipToPadding = false }
        studyTile = StickerTile(this, theme, "学习", tape(0), iconDp = 124, tilt = -5f) { app.lock.showQuiz(QuizMode.STUDY) }
        studyTile.setIcon("1f4da")
        row.addView(studyTile)
        stickerTile = StickerTile(this, theme, "贴纸册", tape(1), iconDp = 100, tilt = 4f) {
            startActivity(Intent(this, StickerBookActivity::class.java))
        }
        row.addView(stickerTile, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(96); topMargin = dp(34) })
        page.addView(row, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER).apply { topMargin = dp(36) })

        // 左下：今天的星星印章
        stampRow = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; clipChildren = false; clipToPadding = false }
        stampRow.addView(handText("今天", 26f, NOTE_INK).apply { setPadding(0, 0, dp(8), 0) })
        stamps = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; clipChildren = false; clipToPadding = false }
        stampRow.addView(stamps)
        page.addView(stampRow, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.START).apply { leftMargin = dp(46); bottomMargin = dp(28) })

        // 右下：家长小锁，平时很淡，选中才清楚，不提示缺权限（那是大人的事，在家长页里看）
        lock = FrameLayout(this).apply { isFocusable = true; alpha = 0.55f }
        lock.addView(ImageView(this).apply { setImageBitmap(Emoji.bitmap(context, "1f512")) },
            FrameLayout.LayoutParams(dp(28), dp(28), Gravity.CENTER))
        val lockFocused = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.WHITE) }
        lock.setOnClickListener { startActivity(Intent(this, ParentActivity::class.java)) }
        page.addView(lock, FrameLayout.LayoutParams(dp(54), dp(54), Gravity.BOTTOM or Gravity.END).apply { rightMargin = dp(42); bottomMargin = dp(24) })

        // 第一次进首页时的操作提示，孩子挪动一下或几秒后淡出
        hint = themedText("", 22f, NOTE_INK, bold = true).apply {
            setEmojiText("用左右方向键选一选，按「确定」进去")
            background = pill(context, withAlpha(Color.WHITE, 0.9f))
            setPadding(dp(22), dp(8), dp(22), dp(8)); alpha = 0f
        }
        page.addView(hint, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = dp(34) })

        // 遥控器焦点路径写死：入口之间左右切换，往下到小锁，小锁往上回「学习」
        val tiles = listOf(studyTile, stickerTile)
        for (v in tiles + lock) v.id = View.generateViewId()
        tiles.forEachIndexed { i, t ->
            t.nextFocusLeftId = tiles[maxOf(i - 1, 0)].id
            t.nextFocusRightId = tiles[minOf(i + 1, tiles.size - 1)].id
            t.nextFocusUpId = t.id
            t.nextFocusDownId = lock.id
        }
        lock.nextFocusUpId = studyTile.id
        lock.nextFocusLeftId = tiles.last().id
        lock.nextFocusRightId = lock.id
        lock.nextFocusDownId = lock.id
        for (v in tiles + lock) {
            val inner = v.onFocusChangeListener
            v.setOnFocusChangeListener { view, has ->
                inner?.onFocusChange(view, has)
                if (view === lock) {
                    lock.background = if (has) lockFocused else null
                    lock.animate().alpha(if (has) 1f else 0.55f).scaleX(if (has) 1.25f else 1f).scaleY(if (has) 1.25f else 1f)
                        .setInterpolator(OvershootInterpolator(2.5f)).setDuration(220).start()
                }
                if (has) onFocused()
            }
        }
        return page
    }

    /** 胶带颜色取主题的色块颜色，每张贴纸不同。 */
    private fun tape(i: Int) = theme.blobs[i % theme.blobs.size].let { Color.rgb(Color.red(it), Color.green(it), Color.blue(it)) }

    /** 选中后停一会儿没再挪动，就把这个入口念出来；吉祥物也歪头看过去。 */
    private fun onFocused() {
        handler.removeCallbacks(speakLater)
        if (userMoved && hint.alpha > 0f) hideHint()
        if (userMoved) handler.postDelayed(speakLater, 450)
        userMoved = true
        peek.animate().translationY(-dp(10f)).setDuration(120).withEndAction {
            peek.animate().translationY(0f).setDuration(320).setInterpolator(OvershootInterpolator(3f)).start()
        }.start()
    }

    private fun speakFocused() {
        val s = app.store.settings
        if (!s.voice.ttsEnabled) return
        val line = lines[currentFocus ?: return] ?: return
        app.speaker.speak(VoiceProviders.tts(this, s.voice), line)
    }

    override fun onResume() {
        super.onResume()
        ticks = 0
        refresh()
        handler.post(refresher)
        val firstTime = !entered
        if (firstTime) {
            entered = true
            entrance()
        }
        userMoved = false
        val target = lastFocused ?: studyTile
        // 第一次进入等贴纸贴好以后再聚焦，否则选中动画会被入场动画盖掉
        root.postDelayed({ target.requestFocus() }, if (firstTime) ENTRANCE_MS else 0)
        if (app.store.onboarded && !app.store.homeHinted) showHint(if (firstTime) ENTRANCE_MS else 0)
    }

    private fun showHint(after: Long) {
        app.store.homeHinted = true
        hint.animate().alpha(1f).setStartDelay(after + 400).setDuration(300).start()
        handler.postDelayed({ hideHint() }, after + 8000)
    }

    private fun hideHint() { hint.animate().alpha(0f).setStartDelay(0).setDuration(300).start() }

    override fun onPause() {
        lastFocused = currentFocus
        handler.removeCallbacksAndMessages(null)
        app.speaker.stop()
        super.onPause()
    }

    private fun refresh() {
        val store = app.store
        // 便利贴：守护暂停时收起来，时间快到时数字变红并不时抖一下
        val limit = store.limitSeconds
        val left = (limit - store.usedSeconds).coerceAtLeast(0)
        val low = left * 5 < limit
        note.visibility = if (store.paused) View.GONE else View.VISIBLE
        // 每秒刷新，分钟数没变时不重新排版
        val noteKey = "${(left + 59) / 60}|$low"
        if (note.tag != noteKey) {
            note.tag = noteKey
            updateNote(left, low)
        }
        if (low && !store.paused && ticks % 4 == 0) shakeNote()
        // 统计和贴纸要读文件，不必每秒都读
        if (ticks++ % 5 != 0) return
        refreshStudy()
    }

    private fun updateNote(left: Int, low: Boolean) {
        if (left == 0) {
            noteTop.text = ""; noteUnit.text = ""
            noteNum.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 28f); noteNum.text = "时间到啦"
        } else {
            noteTop.text = "还能看"; noteUnit.text = "分钟"
            noteNum.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 40f); noteNum.text = "${(left + 59) / 60}"
        }
        noteNum.setTextColor(if (low) theme.error else theme.accentDark)
    }

    private fun refreshStudy() {
        val store = app.store
        val s = store.settings
        val today = DailyStats.dayKey(System.currentTimeMillis())
        val rewards = store.rewards()
        showStars(store.daily()[today]?.studyRounds ?: 0)

        val minutes = RewardRules.nextMinutes(rewards.earnedOn(today), s.rewardMinutesPerRound, s.rewardDailyCapMinutes)
        val canEarn = !store.paused && minutes > 0
        studyTile.setSub("答对 ${s.studyQuestionCount} 题 · 抽贴纸" + if (canEarn) " · 多看 $minutes 分钟" else "")

        val latest = rewards.stickers.maxByOrNull { it.value.first }?.key
        stickerTile.setIcon(latest ?: "1f31f", dim = latest == null)
        stickerTile.setSub(if (latest == null) "还没有贴纸" else "已收集 ${rewards.stickers.size} / ${StickerCatalog.total}")
        lines[lock] = "这里是给爸爸妈妈用的哦"
    }

    /** 今天的星星印章：至少 3 个，新得的星星像盖章一样落下。 */
    private fun showStars(count: Int) {
        val slots = maxOf(3, minOf(count, 8))
        if (stamps.childCount != slots) {
            stamps.removeAllViews()
            repeat(slots) { i ->
                val ring = FrameLayout(this).apply {
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setStroke(dp(3), STAMP_RING, dp(6f), dp(5f))
                    }
                    rotation = if (i % 2 == 0) -10f else 8f
                }
                ring.addView(ImageView(this).apply { setImageBitmap(Emoji.bitmap(context, "2b50")) },
                    FrameLayout.LayoutParams(dp(30), dp(30), Gravity.CENTER))
                stamps.addView(ring, LinearLayout.LayoutParams(dp(46), dp(46)).apply { leftMargin = dp(8) })
            }
        }
        val grey = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0f) })
        for (i in 0 until slots) {
            val ring = stamps.getChildAt(i) as FrameLayout
            val star = ring.getChildAt(0) as ImageView
            val on = i < count
            star.colorFilter = if (on) null else grey
            star.alpha = if (on) 1f else 0.35f
            // 第一次显示时不盖，之后新得的那几颗才盖下来
            if (on && shownStars >= 0 && i >= shownStars) {
                val delay = 500L + (i - shownStars) * 260L
                star.scaleX = 2f; star.scaleY = 2f; star.alpha = 0f; star.rotation = -30f
                star.animate().scaleX(1f).scaleY(1f).alpha(1f).rotation(0f).setStartDelay(delay)
                    .setDuration(320).setInterpolator(DecelerateInterpolator(2f)).withEndAction {
                        ring.scaleX = 1.18f; ring.scaleY = 1.18f
                        ring.animate().scaleX(1f).scaleY(1f).setDuration(260).setInterpolator(OvershootInterpolator(3f)).start()
                    }.start()
            }
        }
        shownStars = count
    }

    private fun shakeNote() {
        ObjectAnimator.ofFloat(note, View.ROTATION, 4f, -3f, 8f, -1f, 4f).setDuration(600).start()
    }

    /** 第一次进入：吉祥物探出头，荧光笔划出标题，贴纸一张张拍到纸上，便利贴掉下来。 */
    private fun entrance() {
        peek.alpha = 0f; peek.translationY = dp(60f); peek.rotation = -30f
        peek.animate().alpha(1f).translationY(0f).rotation(-8f).setDuration(560)
            .setInterpolator(OvershootInterpolator(1.8f)).start()
        title.alpha = 0f; title.translationY = dp(10f)
        title.animate().alpha(1f).translationY(0f).setStartDelay(150).setDuration(360).start()
        band.progress = 0f
        ValueAnimator.ofFloat(0f, 1f).apply {
            startDelay = 320; duration = 460; interpolator = DecelerateInterpolator()
            addUpdateListener { band.progress = it.animatedValue as Float }
            start()
        }
        listOf(studyTile, stickerTile).forEachIndexed { i, t ->
            val tilt = t.rotation
            t.alpha = 0f; t.scaleX = 1.35f; t.scaleY = 1.35f; t.rotation = tilt + 12f
            t.animate().alpha(1f).scaleX(1f).scaleY(1f).rotation(tilt).setStartDelay(520L + i * 170L)
                .setDuration(420).setInterpolator(OvershootInterpolator(1.6f)).start()
        }
        note.alpha = 0f; note.translationY = -dp(170f); note.rotation = 22f
        note.animate().alpha(1f).translationY(0f).rotation(4f).setStartDelay(820).setDuration(520)
            .setInterpolator(OvershootInterpolator(1.4f)).start()
        for (v in listOf<View>(stampRow, lock)) {
            val target = v.alpha
            v.alpha = 0f
            v.animate().alpha(target).setStartDelay(1000).setDuration(300).start()
        }
    }

    private companion object {
        const val ENTRANCE_MS = 1300L
        val NOTE_YELLOW = Color.rgb(0xFF, 0xE9, 0x8A)
        val NOTE_INK = Color.rgb(0x6B, 0x53, 0x20)
        val STAMP_RING = Color.rgb(0xD9, 0xB9, 0x8A)
    }

    private fun dp(v: Float) = (this as Context).dp(v)
    private fun dp(v: Int) = (this as Context).dp(v)
}
