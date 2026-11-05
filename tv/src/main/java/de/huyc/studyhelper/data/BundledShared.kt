package de.huyc.studyhelper.data

import android.content.Context
import android.util.Log
import de.huyc.studyhelper.ui.Emoji
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * 随 APK 预置的公共题库（构建时从 packs-repo 拷进 assets/shared）：不联网也能在手机管理页启用。
 * 装成「分享题库」来源，地址指向线上仓库，以后仍可在线升级；精灵图直接从 assets 放进本地缓存，不用再下载。
 */
object BundledShared {
    private const val TAG = "BundledShared"
    private const val PACKS = "shared/packs"
    private const val SHEETS = "shared/sheets"

    /** 线上目录所在的位置，预置包的来源地址和精灵图地址据此还原。 */
    private val base = PackIndex.DEFAULT_URL.substringBeforeLast('/') + "/"

    fun load(context: Context): List<QuestionPack> = context.assets.list(PACKS).orEmpty()
        .filter { it.endsWith(".json") }
        .sorted()
        .mapNotNull { name ->
            runCatching {
                val url = "${base}packs/$name"
                val text = context.assets.open("$PACKS/$name").bufferedReader().use { it.readText() }
                val pack = PackFormat.parse(JSONObject(text), baseUrl = url).pack
                // 电视显示不了的表情题不进题库，与手机端安装时一致
                pack.copy(
                    questions = pack.questions.filterNot { Emoji.unsupported(context, it.display) },
                    source = PackSource.SHARED,
                    sourceUrl = url,
                )
            }.onFailure { Log.w(TAG, "预置题库 $name 无法读取", it) }.getOrNull()
        }
        .filter { it.questions.isNotEmpty() }

    /** 把包用到的精灵图从 assets 放进缓存（已有的跳过，校验和不符的不放）。 */
    fun seedSprites(context: Context, packs: List<QuestionPack>) {
        val bundled = context.assets.list(SHEETS).orEmpty().toSet()
        for (sheet in packs.filter { it.source == PackSource.SHARED }.flatMap { it.sheets.values }.distinctBy { it.cacheKey }) {
            val name = sheet.url.substringAfterLast('/')
            if (name !in bundled || SpriteCache.has(context, sheet)) continue
            runCatching {
                val bytes = context.assets.open("$SHEETS/$name").use { it.readBytes() }
                if (sheet.sha256.isNotEmpty() && !sha256(bytes).equals(sheet.sha256, ignoreCase = true)) return@runCatching
                val target = SpriteCache.file(context, sheet)
                val tmp = File(target.path + ".tmp")
                tmp.writeBytes(bytes)
                if (!tmp.renameTo(target)) tmp.delete()
            }.onFailure { Log.w(TAG, "预置精灵图 $name 放入缓存失败", it) }
        }
    }

    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
