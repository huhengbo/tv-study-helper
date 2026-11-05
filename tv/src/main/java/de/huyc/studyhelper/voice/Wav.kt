package de.huyc.studyhelper.voice

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** 16bit 单声道 PCM 加 44 字节 WAV 头。 */
object Wav {
    fun wrap(pcm: ByteArray, sampleRate: Int): ByteArray {
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + pcm.size); put("WAVE".toByteArray())
            put("fmt ".toByteArray()); putInt(16); putShort(1); putShort(1)
            putInt(sampleRate); putInt(sampleRate * 2); putShort(2); putShort(16)
            put("data".toByteArray()); putInt(pcm.size)
        }.array()
        return header + pcm
    }

    fun write(file: File, pcm: ByteArray, sampleRate: Int) = file.writeBytes(wrap(pcm, sampleRate))
}
