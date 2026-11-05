package de.huyc.studyhelper.voice

import android.annotation.SuppressLint
import android.os.SystemClock
import android.util.Log
import com.k2fsa.sherpa.onnx.EndpointConfig
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import de.huyc.studyhelper.data.SpeechLanguage
import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * 离线识别引擎（sherpa-onnx）。模型常驻要占上百 MB 内存，闲置 [IDLE_MS] 后释放，下次用到再加载。
 * 所有原生调用都在本对象的锁内串行：识别中途释放会让原生代码访问已删除的对象。
 * 小米电视 4A 上实测：加载模型约 6.5 秒；2 线程解码约 0.8 倍实时（4 线程反而更慢）。
 */
object LocalAsr {
    const val LIB_NAME = "libsherpa-onnx-jni.so"
    private const val TAG = "LocalAsr"
    private const val IDLE_MS = 3 * 60_000L
    private const val THREADS = 2

    private var libLoaded = false
    private var recognizer: OnlineRecognizer? = null
    private var tokens: Set<String> = emptySet()
    /** 边录边识别的解码和闲置释放都在这个线程上排队执行。 */
    private val worker = Executors.newSingleThreadScheduledExecutor()
    private var releaseTask: ScheduledFuture<*>? = null

    /** [hints] 是可能的答案，作为热词提高识别率。 */
    @Synchronized
    fun recognize(dir: File, pcm: ByteArray, hints: List<String>): String {
        val r = load(dir)
        val started = SystemClock.elapsedRealtime()
        val stream = r.createStream(hotwords(hints))
        try {
            stream.acceptWaveform(floats(pcm, pcm.size), VoiceRecorder.SAMPLE_RATE)
            val text = finishStream(r, stream)
            Log.i(TAG, "识别 ${VoiceRecorder.durationMs(pcm)} ms 音频用了 ${SystemClock.elapsedRealtime() - started} ms：$text")
            return text
        } finally {
            stream.release()
            scheduleRelease()
        }
    }

    /** 边录边识别：录音时音频交给 [worker] 线程解码，不阻塞录音线程。 */
    fun startSession(dir: File, hints: List<String>): AsrSession = Session(dir, hints)

    private class Session(dir: File, hints: List<String>) : AsrSession {
        private var recognizer: OnlineRecognizer? = null
        private var stream: OnlineStream? = null
        private var error: Exception? = null
        private var samples = 0

        init {
            onWorker {
                val r = load(dir)
                recognizer = r
                stream = r.createStream(hotwords(hints))
            }
        }

        override fun accept(pcm: ByteArray, length: Int) {
            val chunk = floats(pcm, length) // 录音缓冲区会被复用，先复制
            onWorker {
                val s = stream ?: return@onWorker
                s.acceptWaveform(chunk, VoiceRecorder.SAMPLE_RATE)
                samples += chunk.size
                val r = current()
                while (r.isReady(s)) r.decode(s)
            }
        }

        override fun finish(): String = worker.submit(Callable {
            synchronized(LocalAsr) {
                try {
                    error?.let { throw it }
                    val s = stream ?: throw VoiceServiceException("离线识别没有启动")
                    val started = SystemClock.elapsedRealtime()
                    val text = finishStream(current(), s)
                    Log.i(TAG, "边录边识别 ${samples * 1000L / VoiceRecorder.SAMPLE_RATE} ms 音频，松开后用了 ${SystemClock.elapsedRealtime() - started} ms：$text")
                    text
                } finally {
                    close()
                }
            }
        }).get()

        override fun cancel() {
            worker.execute { synchronized(LocalAsr) { close() } }
        }

        /** 在 [worker] 线程上执行；出错后记下来，后面的音频不再解码，[finish] 时抛出。 */
        private fun onWorker(block: () -> Unit) {
            worker.execute {
                synchronized(LocalAsr) {
                    if (error != null) return@synchronized
                    try {
                        block()
                    } catch (e: Exception) {
                        error = e
                    }
                }
            }
        }

        /** 识别器可能已被删除离线语音包释放掉，不能再用。 */
        private fun current(): OnlineRecognizer =
            recognizer?.takeIf { it === LocalAsr.recognizer } ?: throw VoiceServiceException("离线模型已释放")

        private fun close() {
            stream?.release()
            stream = null
            scheduleRelease()
        }
    }

    /** 流式模型要在结尾补一段静音，最后几个字才会解码出来。 */
    private fun finishStream(r: OnlineRecognizer, stream: OnlineStream): String {
        stream.acceptWaveform(FloatArray(VoiceRecorder.SAMPLE_RATE * 6 / 10), VoiceRecorder.SAMPLE_RATE)
        stream.inputFinished()
        while (r.isReady(stream)) r.decode(stream)
        return r.getResult(stream).text.trim()
    }

    /** 提前加载模型，孩子开口时不用再等。 */
    @Synchronized
    fun warmUp(dir: File) {
        load(dir)
        scheduleRelease()
    }

    @Synchronized
    fun release() {
        releaseTask?.cancel(false)
        recognizer?.let {
            it.release()
            Log.i(TAG, "已释放离线模型")
        }
        recognizer = null
    }

    // 库文件随离线语音包下载，下载时已核对校验码，放在 App 私有目录
    @SuppressLint("UnsafeDynamicallyLoadedCode")
    private fun load(dir: File): OnlineRecognizer {
        releaseTask?.cancel(false)
        recognizer?.let { return it }
        if (!libLoaded) {
            try {
                System.load(File(dir, LIB_NAME).path)
            } catch (e: Throwable) {
                throw VoiceServiceException("离线识别引擎加载失败：${e.message}", e)
            }
            libLoaded = true
        }
        val started = SystemClock.elapsedRealtime()
        val config = OnlineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = VoiceRecorder.SAMPLE_RATE, featureDim = 80),
            modelConfig = OnlineModelConfig(
                transducer = OnlineTransducerModelConfig(
                    encoder = File(dir, "encoder.int8.onnx").path,
                    decoder = File(dir, "decoder.int8.onnx").path,
                    joiner = File(dir, "joiner.int8.onnx").path,
                ),
                tokens = File(dir, "tokens.txt").path,
                numThreads = THREADS,
                modelType = "zipformer",
                // 中文按字、英文按 BPE 切分，热词编码要用
                modelingUnit = "cjkchar+bpe",
                bpeVocab = File(dir, "bpe.vocab").path,
            ),
            endpointConfig = EndpointConfig(),
            enableEndpoint = false,
            // 热词只在 modified_beam_search 下生效
            decodingMethod = "modified_beam_search",
            maxActivePaths = 4,
        )
        val r = try {
            OnlineRecognizer(null, config)
        } catch (e: Exception) {
            throw VoiceServiceException("离线模型加载失败，请在手机上删除离线语音包后重新下载", e)
        }
        tokens = File(dir, "tokens.txt").readLines().mapTo(HashSet()) { it.substringBefore(' ') }
        Log.i(TAG, "离线模型加载用了 ${SystemClock.elapsedRealtime() - started} ms")
        recognizer = r
        return r
    }

    private fun scheduleRelease() {
        releaseTask?.cancel(false)
        releaseTask = worker.schedule({ release() }, IDLE_MS, TimeUnit.MILLISECONDS)
    }

    /** 每行一个热词。模型的英文词表是大写的；词表里没有的字会让热词编码失败，先过滤掉。 */
    private fun hotwords(hints: List<String>): String = hints.asSequence()
        .map { it.trim().uppercase() }
        .filter { h ->
            h.isNotEmpty() && h.all { c -> c == ' ' || c in 'A'..'Z' || c.toString() in tokens }
        }
        .distinct()
        .joinToString("\n")

    private fun floats(pcm: ByteArray, length: Int): FloatArray {
        val out = FloatArray(length / 2)
        for (i in out.indices) {
            val s = (pcm[2 * i].toInt() and 0xff) or (pcm[2 * i + 1].toInt() shl 8)
            out[i] = s.toShort() / 32768f
        }
        return out
    }
}

/** 离线识别：模型自动区分中英文，不需要指定语言。 */
class LocalAsrProvider(private val pack: LocalAsrPack) : AsrProvider {
    override fun prepare() = LocalAsr.warmUp(pack.dir)

    override fun recognize(pcm: ByteArray, language: SpeechLanguage, hints: List<String>): String =
        LocalAsr.recognize(pack.dir, pcm, hints)

    override fun startSession(language: SpeechLanguage, hints: List<String>): AsrSession =
        LocalAsr.startSession(pack.dir, hints)
}
