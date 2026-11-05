package de.huyc.studyhelper.data

import android.content.Context
import android.graphics.BitmapFactory
import android.util.Log
import de.huyc.studyhelper.voice.Http
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * 精灵图的本地缓存（filesDir/sheets/<缓存键>.img，缓存键见 [SpriteSheet.cacheKey]），下载失败不影响装包和答题。
 * 下载都在一个后台线程里排队；写临时文件再改名，不会留下半截文件。
 * 旧图只在新图都下载成功后才清理，升级时断网也不会两头落空。
 */
object SpriteCache {
    private const val TAG = "SpriteCache"
    private const val DIR = "sheets"
    private const val EXT = ".img"
    private const val STALE_TMP_MS = 10 * 60_000L

    /** 单张图宽高上限，防止体积很小但像素极大的图撑爆内存。 */
    const val MAX_SIDE = 2048
    const val MAX_PIXELS = 4_200_000

    private val io = Executors.newSingleThreadExecutor { Thread(it, "sprite-cache").apply { isDaemon = true } }
    private val inFlight = ConcurrentHashMap.newKeySet<String>()

    fun file(context: Context, sheet: SpriteSheet) = File(dir(context), sheet.cacheKey + EXT)

    fun has(context: Context, sheet: SpriteSheet) = file(context, sheet).isFile

    /**
     * 后台下载 [packIds]（null 为所有开着的包）里还没缓存的图。全部就绪后清理不再被任何包使用的旧图。
     * 重复调用安全：正在下载的图不会重复下载。
     */
    fun prefetch(context: Context, store: Store, packIds: Set<String>? = null) {
        val app = context.applicationContext
        io.execute {
            runCatching {
                val targets = store.packs.filter { if (packIds == null) it.enabled else it.id in packIds }
                    .flatMap { it.sheets.values }.distinctBy { it.cacheKey }
                var allReady = true
                for (sheet in targets) if (!has(app, sheet) && !fetch(app, sheet)) allReady = false
                if (allReady) prune(app, store.packs)
            }.onFailure { Log.w(TAG, "精灵图下载出错", it) }
        }
    }

    /** 删除包后清理它的图（只删文件，不联网）。 */
    fun prune(context: Context, store: Store) {
        val app = context.applicationContext
        io.execute { runCatching { prune(app, store.packs) } }
    }

    private fun fetch(context: Context, sheet: SpriteSheet): Boolean {
        val key = sheet.cacheKey
        if (!inFlight.add(key)) return false
        val tmp = File(dir(context), "$key.tmp")
        return try {
            val r = Http.get(sheet.url, sheet.bytes.takeIf { it > 0 } ?: SpriteSheet.MAX_BYTES, timeoutMs = 30_000)
            check(r.code in 200..299) { "HTTP ${r.code}" }
            if (sheet.sha256.isNotEmpty()) check(sha256(r.body).equals(sheet.sha256, ignoreCase = true)) { "校验和不符" }
            tmp.writeBytes(r.body)
            check(sizeOk(tmp)) { "图片损坏或尺寸过大" }
            check(tmp.renameTo(file(context, sheet))) { "无法保存" }
            true
        } catch (e: Exception) {
            Log.w(TAG, "精灵图下载失败 ${sheet.url}：${e.message}")
            tmp.delete()
            false
        } finally {
            inFlight.remove(key)
        }
    }

    /** 图片能解出尺寸，且没超过 [MAX_SIDE]、[MAX_PIXELS]。 */
    fun sizeOk(file: File): Boolean {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, o)
        return o.outWidth > 0 && o.outHeight > 0 && o.outWidth <= MAX_SIDE && o.outHeight <= MAX_SIDE &&
            o.outWidth.toLong() * o.outHeight <= MAX_PIXELS
    }

    private fun prune(context: Context, packs: List<QuestionPack>) {
        val used = packs.flatMap { it.sheets.values }.map { it.cacheKey + EXT }.toSet()
        val now = System.currentTimeMillis()
        dir(context).listFiles()?.forEach { f ->
            val stale = if (f.name.endsWith(".tmp")) now - f.lastModified() > STALE_TMP_MS else f.name !in used
            if (stale) f.delete()
        }
    }

    private fun dir(context: Context) = File(context.filesDir, DIR).also { it.mkdirs() }

    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
