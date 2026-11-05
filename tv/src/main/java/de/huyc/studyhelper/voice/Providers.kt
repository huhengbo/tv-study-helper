package de.huyc.studyhelper.voice

import android.content.Context
import android.net.ConnectivityManager
import android.util.Base64
import android.util.Log
import de.huyc.studyhelper.App
import de.huyc.studyhelper.data.SpeechLanguage
import de.huyc.studyhelper.data.VoiceConfig
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder
import java.util.UUID

/** 语音识别：输入 16kHz/16bit/单声道 PCM，返回识别文本。在后台线程调用。 */
interface AsrProvider {
    /** 答题开始时调用，提前做耗时的准备（如加载离线模型）。 */
    fun prepare() {}

    /** [hints] 是可能的答案，支持热词的服务用来提高识别率。 */
    fun recognize(pcm: ByteArray, language: SpeechLanguage, hints: List<String> = emptyList()): String

    /** 支持边录边识别的服务返回会话，录音时就开始解码，松开按键后只剩结尾一小段要算；不支持的返回 null。 */
    fun startSession(language: SpeechLanguage, hints: List<String>): AsrSession? = null
}

/** 边录边识别的一次会话。[accept] 在录音线程调用，不能阻塞；[finish] 在后台线程调用。 */
interface AsrSession {
    fun accept(pcm: ByteArray, length: Int)
    fun finish(): String
    fun cancel()
}

/** 语音合成：返回 mp3 音频。在后台线程调用。 */
interface TtsProvider {
    /** 区分缓存用：同一文本在不同音色/语速下结果不同。 */
    val cacheKey: String
    fun synthesize(text: String): ByteArray
}

object VoiceProviders {

    /**
     * 选了在线服务又开了 [VoiceConfig.asrOfflineFallback] 时，在线识别出错或电视没联网就改用离线识别；
     * 在线服务没填密钥也用离线识别。离线语音包没下载时离线识别不可用。
     */
    fun asr(context: Context, c: VoiceConfig): AsrProvider? {
        val pack = App.instance.asrPack
        val offline = if (pack.installed) LocalAsrProvider(pack) else null
        val online = when (c.asrProvider) {
            VoiceConfig.PROVIDER_OFFLINE -> return offline
            VoiceConfig.PROVIDER_BAIDU -> if (c.baiduApiKey.isNotBlank() && c.baiduSecretKey.isNotBlank()) BaiduAsr(BaiduAuth.get(context, c), deviceId(context)) else null
            VoiceConfig.PROVIDER_VOLC -> if (c.volcApiKey.isNotBlank() || (c.volcAppId.isNotBlank() && c.volcAccessKey.isNotBlank())) VolcAsr(c) else null
            else -> return null
        }
        if (offline == null || !c.asrOfflineFallback) return online
        if (online == null) return offline
        val connectivity = context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        @Suppress("DEPRECATION")
        return FallbackAsr(online, offline, isOnline = { connectivity.activeNetworkInfo?.isConnected == true })
    }

    fun tts(context: Context, c: VoiceConfig): TtsProvider? = when (c.ttsProvider) {
        VoiceConfig.PROVIDER_BAIDU -> if (c.baiduApiKey.isNotBlank() && c.baiduSecretKey.isNotBlank()) BaiduTts(BaiduAuth.get(context, c), deviceId(context), c) else null
        VoiceConfig.PROVIDER_VOLC -> if (c.volcApiKey.isNotBlank() || (c.volcAppId.isNotBlank() && c.volcAccessKey.isNotBlank())) VolcTts(c) else null
        else -> null
    }

    fun deviceId(context: Context): String {
        val prefs = context.getSharedPreferences("device", Context.MODE_PRIVATE)
        return prefs.getString("id", null) ?: UUID.randomUUID().toString().replace("-", "").also {
            prefs.edit().putString("id", it).apply()
        }
    }
}

/** 在线识别失败（网络或服务出错）时改用离线识别；没联网直接用离线，不等超时。 */
class FallbackAsr(
    private val online: AsrProvider,
    private val offline: AsrProvider,
    private val isOnline: () -> Boolean,
    private val log: (String) -> Unit = { Log.w("FallbackAsr", it) },
) : AsrProvider {

    override fun recognize(pcm: ByteArray, language: SpeechLanguage, hints: List<String>): String {
        if (isOnline()) {
            try {
                return online.recognize(pcm, language, hints)
            } catch (e: IOException) {
                log("在线识别失败，改用离线识别：${e.message}")
            }
        } else {
            log("电视没联网，用离线识别")
        }
        return offline.recognize(pcm, language, hints)
    }
}

// ---------------- 百度智能云 ----------------

/** access_token 有效期 30 天，按凭据缓存到本地，提前 1 天刷新。 */
class BaiduAuth private constructor(private val context: Context, private val apiKey: String, private val secretKey: String) {

    @Synchronized
    fun token(): String {
        val prefs = context.getSharedPreferences("baidu_token", Context.MODE_PRIVATE)
        val cachedFor = prefs.getString("key", null)
        val expiresAt = prefs.getLong("expires", 0)
        if (cachedFor == apiKey && System.currentTimeMillis() < expiresAt) prefs.getString("token", null)?.let { return it }

        val url = "https://aip.baidubce.com/oauth/2.0/token?grant_type=client_credentials" +
            "&client_id=${enc(apiKey)}&client_secret=${enc(secretKey)}"
        val resp = Http.post(url, ByteArray(0), "application/json")
        val json = runCatching { JSONObject(resp.text) }.getOrNull()
            ?: throw VoiceServiceException("百度鉴权失败：HTTP ${resp.code}")
        val token = json.optString("access_token")
        if (token.isEmpty()) throw VoiceServiceException("百度鉴权失败：${json.optString("error_description", json.toString())}")
        val ttl = json.optLong("expires_in", 2592000L) * 1000 - 86_400_000L
        prefs.edit().putString("key", apiKey).putString("token", token)
            .putLong("expires", System.currentTimeMillis() + ttl.coerceAtLeast(3_600_000L)).apply()
        return token
    }

    companion object {
        fun get(context: Context, c: VoiceConfig) = BaiduAuth(context.applicationContext, c.baiduApiKey, c.baiduSecretKey)
    }
}

/** 短语音识别标准版：https://cloud.baidu.com/doc/SPEECH/s/Jlbxdezuf */
class BaiduAsr(private val auth: BaiduAuth, private val cuid: String) : AsrProvider {
    override fun recognize(pcm: ByteArray, language: SpeechLanguage, hints: List<String>): String {
        val body = JSONObject()
            .put("format", "pcm")
            .put("rate", VoiceRecorder.SAMPLE_RATE)
            .put("channel", 1)
            .put("cuid", cuid)
            .put("token", auth.token())
            // 1737 英语；1537 普通话（数字会识别成阿拉伯数字或中文数字，AnswerMatcher 都能处理）
            .put("dev_pid", if (language == SpeechLanguage.ENGLISH) 1737 else 1537)
            .put("speech", Base64.encodeToString(pcm, Base64.NO_WRAP))
            .put("len", pcm.size)
        val resp = Http.post("https://vop.baidu.com/server_api", body.toString().toByteArray(), "application/json")
        val json = runCatching { JSONObject(resp.text) }.getOrNull()
            ?: throw VoiceServiceException("百度识别失败：HTTP ${resp.code}")
        val err = json.optInt("err_no", -1)
        return when (err) {
            0 -> json.optJSONArray("result")?.optString(0).orEmpty()
            3301 -> "" // 音频质量过差/没说话
            else -> throw VoiceServiceException("百度识别失败：$err ${json.optString("err_msg")}")
        }
    }
}

/** 短文本在线合成（text2audio）：https://cloud.baidu.com/doc/SPEECH/s/mlbxh7xie */
class BaiduTts(private val auth: BaiduAuth, private val cuid: String, c: VoiceConfig) : TtsProvider {
    private val per = c.ttsVoice.ifBlank { DEFAULT_VOICE }
    private val spd = c.ttsSpeed

    override val cacheKey = "baidu-$per-$spd"

    override fun synthesize(text: String): ByteArray {
        // 官方建议 tex 做两次 urlencode
        val form = "tex=${enc(enc(text))}&tok=${enc(auth.token())}&cuid=${enc(cuid)}&ctp=1&lan=zh" +
            "&spd=$spd&per=${enc(per)}&aue=3"
        val resp = Http.post("https://tsn.baidu.com/text2audio", form.toByteArray(), "application/x-www-form-urlencoded")
        if (resp.contentType.startsWith("audio")) return resp.body
        val msg = runCatching { JSONObject(resp.text).let { "${it.optInt("err_no")} ${it.optString("err_msg")}" } }.getOrDefault("HTTP ${resp.code}")
        throw VoiceServiceException("百度合成失败：$msg")
    }

    companion object {
        /** 度小童（精品音库）。 */
        const val DEFAULT_VOICE = "110"
    }
}

// ---------------- 火山引擎（豆包语音） ----------------

private fun volcAuthHeaders(c: VoiceConfig, appIdHeader: String): Map<String, String> =
    if (c.volcApiKey.isNotBlank()) mapOf("X-Api-Key" to c.volcApiKey)
    else mapOf(appIdHeader to c.volcAppId, "X-Api-Access-Key" to c.volcAccessKey)

/** 录音文件识别极速版（同步返回）：https://docs.volcengine.com/docs/doubao-voice/large-model-recording-file-lite-recognition-api */
class VolcAsr(private val c: VoiceConfig) : AsrProvider {
    override fun recognize(pcm: ByteArray, language: SpeechLanguage, hints: List<String>): String {
        // 豆包大模型识别自动判断中英文，无需指定语言
        val wav = Wav.wrap(pcm, VoiceRecorder.SAMPLE_RATE)
        val body = JSONObject()
            .put("user", JSONObject().put("uid", "study-helper-tv"))
            .put("audio", JSONObject().put("data", Base64.encodeToString(wav, Base64.NO_WRAP)))
            .put("request", JSONObject().put("model_name", "bigmodel"))
        val headers = volcAuthHeaders(c, "X-Api-App-Key") + mapOf(
            "X-Api-Resource-Id" to "volc.bigasr.auc_turbo",
            "X-Api-Request-Id" to UUID.randomUUID().toString(),
            "X-Api-Sequence" to "-1",
        )
        val resp = Http.post("https://openspeech.bytedance.com/api/v3/auc/bigmodel/recognize/flash",
            body.toString().toByteArray(), "application/json", headers)
        val status = resp.headers["x-api-status-code"].orEmpty()
        if (status == "20000003") return "" // 静音音频
        if (status != "20000000") {
            throw VoiceServiceException("火山识别失败：${status.ifEmpty { "HTTP ${resp.code}" }} ${resp.headers["x-api-message"].orEmpty()}")
        }
        return runCatching { JSONObject(resp.text).getJSONObject("result").optString("text") }.getOrDefault("")
    }
}

/** 语音合成 V3 HTTP 单向流式：https://docs.volcengine.com/docs/6561/1598757 */
class VolcTts(private val c: VoiceConfig) : TtsProvider {
    private val speaker = c.ttsVoice.ifBlank { DEFAULT_VOICE }

    override val cacheKey = "volc-$speaker"

    override fun synthesize(text: String): ByteArray {
        val body = JSONObject()
            .put("user", JSONObject().put("uid", "study-helper-tv"))
            .put("req_params", JSONObject()
                .put("text", text)
                .put("speaker", speaker)
                .put("audio_params", JSONObject().put("format", "mp3").put("sample_rate", 24000)))
        // 2.0 音色（*_uranus_bigtts）必须配 seed-tts-2.0 资源
        val resource = if (speaker.contains("uranus")) "seed-tts-2.0" else "seed-tts-1.0"
        val headers = volcAuthHeaders(c, "X-Api-App-Id") + mapOf("X-Api-Resource-Id" to resource)
        val resp = Http.post("https://openspeech.bytedance.com/api/v3/tts/unidirectional",
            body.toString().toByteArray(), "application/json", headers)
        if (resp.code !in 200..299) throw VoiceServiceException("火山合成失败：HTTP ${resp.code} ${resp.text.take(200)}")
        // 响应是逐行的 JSON：{"code":0,"data":"<base64>"}，结束包 code=20000000
        val out = java.io.ByteArrayOutputStream()
        resp.text.lineSequence().map { it.trim() }.filter { it.startsWith("{") }.forEach { line ->
            val o = runCatching { JSONObject(line) }.getOrNull() ?: return@forEach
            val code = o.optInt("code")
            if (code != 0 && code != 20000000) throw VoiceServiceException("火山合成失败：$code ${o.optString("message")}")
            o.optString("data").takeIf { it.isNotEmpty() }?.let { out.write(Base64.decode(it, Base64.DEFAULT)) }
        }
        if (out.size() == 0) throw VoiceServiceException("火山合成失败：没有返回音频")
        return out.toByteArray()
    }

    companion object {
        /** 儿童绘本 2.0。 */
        const val DEFAULT_VOICE = "zh_female_xiaoxue_uranus_bigtts"
    }
}

private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")
