package de.huyc.studyhelper.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class PacksTest {

    private fun q(id: String, display: String, subject: Subject = Subject.HANZI, enabled: Boolean = true) =
        Question(id, subject, subject.defaultPrompt, display, listOf(display), enabled = enabled)

    private fun pack(id: String, vararg qs: Question, enabled: Boolean = true) =
        QuestionPack(id, id, questions = qs.toList(), enabled = enabled, source = PackSource.SHARED)

    @Test fun builtinPacksAreValid() {
        assertEquals(3, TestBank.packs.size)
        TestBank.packs.forEach { p ->
            assertTrue(p.id, p.questions.isNotEmpty())
            assertEquals(p.questions.size, p.questions.map { it.id }.toSet().size)
        }
        // 与旧版内置题库的 id 保持一致，迁移时据此沿用启用状态
        val ids = TestBank.questions().map { it.id }.toSet()
        assertTrue(ids.containsAll(listOf("en-0", "hz-0", "ch-0", "ch-1", "ch-2")))
    }

    @Test fun activeQuestionsDedupeAcrossPacksAndSkipDisabled() {
        val a = pack("a", q("1", "山"), q("2", "水"))
        val b = pack("b", q("x", "山"), q("y", "火", enabled = false))
        val c = pack("c", q("z", "田"), enabled = false)
        assertEquals(listOf("山", "水"), PackRules.activeQuestions(listOf(a, b, c)).map { it.display })
    }

    @Test fun exportRoundTripsAndOmitsDefaults() {
        val p = pack("demo.pack", q("1", "山"),
            Question("2", Subject.ENGLISH, "苹果？", "🍎", listOf("apple"), AnswerMode.CHOICE, listOf("apple", "cat")))
        val json = p.toPackJson()
        val first = json.getJSONArray("questions").getJSONObject(0)
        assertFalse(first.has("mode")); assertFalse(first.has("prompt")); assertFalse(first.has("enabled"))
        assertFalse(json.has("local"))
        val back = PackFormat.parse(JSONObject(json.toString())).pack
        assertEquals(p.questions.map { it.key }, back.questions.map { it.key })
        assertEquals(Subject.HANZI.defaultPrompt, back.questions[0].prompt)
    }

    @Test fun localJsonKeepsStateAndDisabledQuestions() {
        val p = pack("demo", q("1", "山", enabled = false), enabled = false).copy(installedAt = 42)
        val back = QuestionPack.fromLocalJson(JSONObject(p.toLocalJson().toString()))
        assertEquals(p, back)
    }

    @Test fun strictParseRejectsBadFilesAndSkipsUnknownQuestionTypes() {
        fun bad(text: String, msg: String) {
            try { PackFormat.parse(JSONObject(text)); fail(text) } catch (e: PackFormat.InvalidPack) { assertTrue(e.message!!, e.message!!.contains(msg)) }
        }
        bad("""{"id":"a.b","name":"x","questions":[]}""", "不是学习小助手")
        bad("""{"format":"studyhelper-pack","formatVersion":9,"id":"a.b","name":"x"}""", "更新版本")
        bad("""{"format":"studyhelper-pack","id":"A B","name":"x"}""", "编号")
        val parsed = PackFormat.parse(JSONObject("""
            {"format":"studyhelper-pack","formatVersion":1,"id":"a.b","name":"x","future":1,"questions":[
              {"id":"1","subject":"HANZI","display":"山","answers":["山"]},
              {"id":"2","subject":"SCIENCE","display":"?","answers":["a"]},
              {"id":"3","subject":"MATH","mode":"DRAG","display":"?","answers":["1"]},
              {"id":"4","subject":"MATH","mode":"CHOICE","display":"1+1","answers":["2"],"choices":["3","4"]},
              {"subject":"HANZI","display":"水","answers":["水"]}
            ]}""".trimIndent()))
        assertEquals(listOf("山", "水"), parsed.pack.questions.map { it.display })
        assertEquals(3, parsed.skipped)
        assertEquals(2, parsed.pack.questions.map { it.id }.toSet().size)
    }

    @Test fun upgradeKeepsSwitchesAndDisabledQuestions() {
        val old = pack("p", q("1", "山", enabled = false), q("2", "水"), enabled = false).copy(installedAt = 7)
        val new = pack("p", q("1", "山"), q("2", "水"), q("3", "火")).copy(version = 2)
        val up = new.upgradedFrom(old)
        assertFalse(up.enabled)
        assertEquals(7, up.installedAt)
        assertEquals(listOf(false, true, true), up.questions.map { it.enabled })
        assertEquals(2, up.version)
    }

    @Test fun legacyBankIsSplitIntoBuiltinAndMine() {
        val builtin = pack("builtin.x", q("hz-0", "天"), q("hz-1", "地"), q("hz-2", "人"), q("new-1", "口")).copy(source = PackSource.BUILTIN)
        val legacy = listOf(
            q("hz-0", "天", enabled = false), // 停用过的内置题
            q("hz-1", "坐"),                  // 改过内容的内置题
            q("abc", "猫"),                   // 家长新增；hz-2 被删除
        )
        val packs = PackRules.migrateLegacy(legacy, listOf(builtin), now = 1)
        val b = packs.first { it.id == "builtin.x" }
        // 停用的、改过的、删掉的都不出；新版本才有的内置题照常出
        assertEquals(listOf(false, false, false, true), b.questions.map { it.enabled })
        val mine = packs.first { it.id == PackRules.MY_PACK_ID }
        assertEquals(listOf("坐", "猫"), mine.questions.map { it.display })
        assertTrue(mine.editable)
        assertEquals(listOf("口", "坐", "猫"), PackRules.activeQuestions(packs).map { it.display })
    }

    @Test fun statsUseLatestAnswerPerQuestionAndCountSharedQuestionsInEachPack() {
        val shan = q("1", "山")
        val a = pack("a", shan, q("2", "水"))
        val b = pack("b", shan.copy(id = "other"))
        val logs = listOf( // 倒序：最新在前
            AnswerLog(300, "1", "山", "山", true, shan.key),
            AnswerLog(200, "2", "水", "谁", false, q("2", "水").key),
            AnswerLog(100, "1", "山", "三", false, shan.key),
        )
        val sa = PackRules.stats(a, logs, since = 150)
        assertEquals(2, sa.total); assertEquals(1, sa.mastered); assertEquals(2, sa.recentAnswers); assertEquals(1, sa.recentCorrect)
        val sb = PackRules.stats(b, logs, since = 0)
        assertEquals(1, sb.mastered); assertEquals(2, sb.recentAnswers)
    }
}
