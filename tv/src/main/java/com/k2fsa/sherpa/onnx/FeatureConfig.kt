// 摘自 sherpa-onnx v2.0.1 sherpa-onnx/kotlin-api（Apache-2.0，https://github.com/k2-fsa/sherpa-onnx）。
// JNI 按包名、类名和字段名查找，不要改名。原文件里的 System.loadLibrary 已去掉：库文件随离线语音包下载，
// 由 de.huyc.studyhelper.voice.LocalAsr 用 System.load 加载。
package com.k2fsa.sherpa.onnx

data class FeatureConfig(
    var sampleRate: Int = 16000,
    var featureDim: Int = 80,
    var dither: Float = 0.0f
)

fun getFeatureConfig(sampleRate: Int, featureDim: Int): FeatureConfig {
    return FeatureConfig(sampleRate = sampleRate, featureDim = featureDim)
}
