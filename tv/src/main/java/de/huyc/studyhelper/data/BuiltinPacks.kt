package de.huyc.studyhelper.data

import android.content.Context
import org.json.JSONObject

/** 内置题库包：assets/packs/ 下的文件，格式与分享题库相同。程序升级时版本号更高的内置包会自动更新。 */
object BuiltinPacks {
    private const val DIR = "packs"

    fun load(context: Context): List<QuestionPack> = context.assets.list(DIR).orEmpty()
        .filter { it.endsWith(".json") }
        .sorted()
        .map { name -> parse(context.assets.open("$DIR/$name").bufferedReader().use { it.readText() }) }

    fun parse(text: String): QuestionPack = PackFormat.parse(JSONObject(text)).pack.copy(source = PackSource.BUILTIN)
}
