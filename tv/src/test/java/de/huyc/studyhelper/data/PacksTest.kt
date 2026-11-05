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

    // ---- 精灵图 ----

    private val sheetPack = """
        {"format":"studyhelper-pack","formatVersion":1,"id":"a.b","name":"x",
         "sheets":{"s1":{"url":"../sheets/a.webp","cols":5,"rows":4,"bytes":180000}},
         "questions":[
          {"id":"banana","subject":"ENGLISH","display":"🍌","answers":["banana"],"sprite":{"sheet":"s1","cell":0}},
          {"id":"dog","subject":"ENGLISH","display":"🐶","answers":["dog"],"sprite":{"sheet":"s1","cell":1,"frames":4,"fps":8}},
          {"id":"cat","subject":"ENGLISH","display":"🐱","answers":["cat"]}]}
    """.trimIndent()

    @Test fun spritesParseResolveAndRoundTrip() {
        val parsed = PackFormat.parse(JSONObject(sheetPack), baseUrl = "https://cdn.example.com/packs/a.b.json")
        assertTrue(parsed.warnings.toString(), parsed.warnings.isEmpty())
        val p = parsed.pack
        assertEquals("https://cdn.example.com/sheets/a.webp", p.sheets["s1"]!!.url)
        assertEquals(20, p.sheets["s1"]!!.cells)
        assertEquals(SpriteRef("s1", 0), p.questions[0].sprite)
        assertEquals(SpriteRef("s1", 1, 4, 8), p.questions[1].sprite)
        assertEquals(null, p.questions[2].sprite)
        // 导出再解析、本地保存再读取都不丢
        val exported = p.toPackJson()
        assertFalse(exported.getJSONArray("questions").getJSONObject(0).getJSONObject("sprite").has("frames"))
        assertFalse(exported.getJSONArray("questions").getJSONObject(2).has("sprite"))
        assertEquals(p.questions.map { it.sprite }, PackFormat.parse(JSONObject(exported.toString())).pack.questions.map { it.sprite })
        val local = QuestionPack.fromLocalJson(JSONObject(p.toLocalJson().toString()))
        assertEquals(p.sheets, local.sheets)
        assertEquals(p.questions.map { it.sprite }, local.questions.map { it.sprite })
    }

    @Test fun badSpritesKeepTheQuestionAndWarn() {
        val text = sheetPack
            .replace("\"cell\":0}", "\"cell\":20}")           // 越界
            .replace("\"cell\":1,\"frames\":4", "\"cell\":18,\"frames\":4") // 帧超出
            .replace("\"answers\":[\"cat\"]", "\"answers\":[\"cat\"],\"sprite\":{\"sheet\":\"nope\",\"cell\":0}") // 没有这张图
        val parsed = PackFormat.parse(JSONObject(text), baseUrl = "https://cdn.example.com/packs/a.b.json")
        assertEquals(3, parsed.pack.questions.size)
        assertTrue(parsed.pack.questions.all { it.sprite == null })
        assertEquals(3, parsed.warnings.size)
        val zero = PackFormat.parse(JSONObject(sheetPack.replace("\"frames\":4", "\"frames\":0")), baseUrl = "https://cdn.example.com/packs/a.b.json")
        assertEquals(null, zero.pack.questions[1].sprite)
        assertEquals(1, zero.warnings.size)
    }

    @Test fun sheetUrlsNeedHttpsAndRelativePathsNeedABase() {
        // 文件导入没有 base：相对路径和 http 都不能用，题仍保留
        val noBase = PackFormat.parse(JSONObject(sheetPack))
        assertTrue(noBase.pack.sheets.isEmpty())
        assertTrue(noBase.pack.questions.all { it.sprite == null })
        assertEquals(3, noBase.pack.questions.size)
        val abs = PackFormat.parse(JSONObject(sheetPack.replace("../sheets/a.webp", "https://x.test/a.webp")))
        assertEquals("https://x.test/a.webp", abs.pack.sheets["s1"]!!.url)
        assertEquals(null, PackFormat.resolveSheetUrl("http://x.test/a.webp", "https://cdn.example.com/index.json"))
        assertEquals("http://lan/a.webp", PackFormat.resolveSheetUrl("a.webp", "http://lan/index.json"))
        assertEquals(null, PackFormat.resolveSheetUrl("file:///sdcard/a.webp", null))
    }

    @Test fun invalidSheetsAreDroppedWithWarnings() {
        for (bad in listOf("\"cols\":0", "\"cols\":17", "\"bytes\":99999999", "\"url\":\"\"")) {
            val key = bad.substringBefore(':')
            val text = sheetPack.replace(Regex("$key:[^,}]*"), bad)
            val parsed = PackFormat.parse(JSONObject(text), baseUrl = "https://cdn.example.com/packs/a.b.json")
            assertTrue(bad, parsed.pack.sheets.isEmpty())
            assertTrue(bad, parsed.warnings.isNotEmpty())
        }
    }

    @Test fun packsWithoutSpritesAreUnchanged() {
        val parsed = PackFormat.parse(JSONObject("""{"format":"studyhelper-pack","id":"a.b","name":"x","questions":[
            {"id":"1","subject":"HANZI","display":"山","answers":["山"]}]}"""))
        assertTrue(parsed.pack.sheets.isEmpty())
        assertTrue(parsed.warnings.isEmpty())
        assertFalse(parsed.pack.toPackJson().has("sheets"))
    }

    @Test fun activeQuestionsPreferTheSpriteVersionAndCarryItsSheet() {
        val sheet = SpriteSheet("https://x.test/a.webp", 2, 2)
        val plain = pack("a", q("1", "🐶", Subject.ENGLISH))
        val withSprite = pack("b", q("1", "🐶", Subject.ENGLISH).copy(sprite = SpriteRef("s", 3))).copy(sheets = mapOf("s" to sheet))
        for (order in listOf(listOf(plain, withSprite), listOf(withSprite, plain))) {
            val active = PackRules.activeQuestions(order)
            assertEquals(1, active.size)
            assertEquals(sheet, active[0].sprite!!.spec)
        }
    }

    @Test fun spriteSheetCacheKeyIgnoresPackButFollowsUrlAndHash() {
        val a = SpriteSheet("https://x.test/a.webp", 2, 2)
        assertEquals(a.cacheKey, a.copy(cols = 4).cacheKey)
        assertTrue(a.cacheKey != a.copy(url = "https://x.test/b.webp").cacheKey)
        assertTrue(a.cacheKey != a.copy(sha256 = "a".repeat(64)).cacheKey)
    }
}
