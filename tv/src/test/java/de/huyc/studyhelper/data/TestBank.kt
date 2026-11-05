package de.huyc.studyhelper.data

import java.io.File

/** 单元测试直接读 assets 里的内置题库包（测试工作目录为 tv/）。 */
object TestBank {
    val packs: List<QuestionPack> by lazy {
        File("src/main/assets/packs").listFiles { f -> f.name.endsWith(".json") }!!.sorted().map { BuiltinPacks.parse(it.readText()) }
    }

    fun questions(): List<Question> = PackRules.activeQuestions(packs)
}
