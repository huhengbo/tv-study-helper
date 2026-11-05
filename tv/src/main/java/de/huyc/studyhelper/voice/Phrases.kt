package de.huyc.studyhelper.voice

import de.huyc.studyhelper.data.Settings

/** 答题过程中固定会说的话。集中放在这里，方便统一修改和预先合成朗读。 */
object Phrases {
    val PRAISE = listOf("答对啦！", "真棒！", "太厉害了！", "你真聪明！")
    const val ALL_DONE = "全部答对，太棒啦！可以继续看啦"

    fun greeting(questionCount: Int) = "休息一下吧！答对 $questionCount 道题就能继续看啦"

    fun all(settings: Settings): List<String> = listOf(greeting(settings.questionCount)) + PRAISE + ALL_DONE
}
