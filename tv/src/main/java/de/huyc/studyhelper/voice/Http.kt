package de.huyc.studyhelper.voice

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** 极简 HTTP 工具，避免为几个 REST 调用引入 OkHttp（电视内存只有 1.6GB）。 */
object Http {

    class Response(val code: Int, val headers: Map<String, String>, val body: ByteArray, val contentType: String) {
        val text: String get() = String(body, Charsets.UTF_8)
    }

    /** 下载小文件（如分享题库），超过 [maxBytes] 报错。 */
    fun get(url: String, maxBytes: Int, timeoutMs: Int = 15_000): Response {
        val conn = open(url)
        try {
            conn.connectTimeout = timeoutMs
            conn.readTimeout = timeoutMs
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val bytes = stream?.use { input ->
                val out = java.io.ByteArrayOutputStream()
                val buf = ByteArray(8192)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    if (out.size() > maxBytes) throw IOException("文件太大")
                }
                out.toByteArray()
            } ?: ByteArray(0)
            return Response(code, emptyMap(), bytes, conn.contentType.orEmpty())
        } finally {
            conn.disconnect()
        }
    }

    fun open(url: String): HttpURLConnection = (URL(url).openConnection() as HttpURLConnection).also(TlsCompat::apply)

    fun post(
        url: String,
        body: ByteArray,
        contentType: String,
        headers: Map<String, String> = emptyMap(),
        timeoutMs: Int = 15_000,
    ): Response {
        val conn = open(url)
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = timeoutMs
            conn.readTimeout = timeoutMs
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", contentType)
            headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
            conn.setFixedLengthStreamingMode(body.size)
            conn.outputStream.use { it.write(body) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val bytes = stream?.use { it.readBytes() } ?: ByteArray(0)
            val respHeaders = conn.headerFields.filterKeys { it != null }.mapValues { it.value.firstOrNull().orEmpty() }
                .mapKeys { it.key.lowercase() }
            return Response(code, respHeaders, bytes, conn.contentType.orEmpty())
        } finally {
            conn.disconnect()
        }
    }
}

class VoiceServiceException(message: String, cause: Throwable? = null) : IOException(message, cause)
