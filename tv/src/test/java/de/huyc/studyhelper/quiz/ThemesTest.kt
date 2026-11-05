package de.huyc.studyhelper.quiz

import de.huyc.studyhelper.ui.Themes
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemesTest {

    @Test fun idsAreUniqueAndResolvable() {
        assertEquals(Themes.all.size, Themes.all.map { it.id }.toSet().size)
        Themes.all.forEach { assertEquals(it, Themes.get(it.id)) }
    }

    @Test fun unknownIdFallsBackToDefault() {
        assertEquals(Themes.KIDS, Themes.get("no-such-theme"))
    }

    @Test fun everyMascotHasAnEmojiImage() {
        val dir = File("src/main/assets/emoji")
        Themes.all.forEach { t ->
            assertTrue("${t.id} 没有吉祥物", t.mascots.isNotEmpty())
            (t.mascots + t.decor).forEach { assertTrue("${t.id} 缺少表情图片 $it", File(dir, "$it.webp").exists()) }
        }
    }

    @Test fun phoneSelectListsEveryTheme() {
        val html = File("src/main/assets/web/index.html").readText()
        Themes.all.forEach { assertTrue("手机端主题下拉缺少 ${it.id}", html.contains("<option value=\"${it.id}\"")) }
    }
}
