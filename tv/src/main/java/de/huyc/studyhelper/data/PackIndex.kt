package de.huyc.studyhelper.data

import de.huyc.studyhelper.voice.Http
import org.json.JSONObject
import java.io.IOException
import java.net.URL

/**
 * 分享题库目录。仓库里的 index.json：
 * ```
 * { "format": "studyhelper-index", "packs": [ { "id": "…", "name": "…", "icon": "📘", "version": 3,
 *   "description": "…", "count": 120, "tags": ["一年级"], "url": "packs/xxx.json" } ] }
 * ```
 * url 可以是相对 index.json 的路径。
 */
object PackIndex {
    /** 默认目录地址：分享题库仓库建好后填入（国内建议用 jsDelivr 镜像）。为空时手机端提示「即将开放」。 */
    const val DEFAULT_URL = ""
    private const val MAX_BYTES = 2 * 1024 * 1024

    class Entry(val id: String, val name: String, val icon: String, val description: String,
                val version: Int, val count: Int, val tags: List<String>, val url: String)

    fun urlFor(settings: Settings) = settings.packIndexUrl.ifBlank { DEFAULT_URL }

    fun fetch(indexUrl: String): List<Entry> {
        val o = JSONObject(download(indexUrl))
        val a = o.optJSONArray("packs") ?: throw IOException("题库目录格式不对")
        return (0 until a.length()).mapNotNull { i ->
            val e = a.optJSONObject(i) ?: return@mapNotNull null
            val url = e.optString("url").ifBlank { return@mapNotNull null }
            Entry(
                id = e.optString("id"),
                name = e.optString("name"),
                icon = e.optString("icon").ifBlank { "📚" },
                description = e.optString("description"),
                version = e.optInt("version", 1),
                count = e.optInt("count"),
                tags = e.optJSONArray("tags").strings(),
                url = URL(URL(indexUrl), url).toString(),
            )
        }
    }

    fun download(url: String): String {
        val r = try { Http.get(url, MAX_BYTES) } catch (e: IOException) { throw IOException("电视连不上分享题库：${e.message}", e) }
        if (r.code !in 200..299) throw IOException("分享题库返回错误 ${r.code}")
        return r.text
    }
}
