package de.huyc.studyhelper.core

import java.io.Closeable
import java.io.DataInputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.PrivateKey
import javax.crypto.Cipher

/**
 * 最小的 adb 客户端，只支持「连接 + 认证 + 执行 shell 命令」。
 *
 * 不用现成的 adb 库：它们要求 adbd 的连接串带 `features=`，Android 6 的 adbd 没有这个字段，会直接报错；
 * 也不用 `shell,v2`（Android 7 才有），只用最老的 `shell:` 服务，没有退出码，stdout 和 stderr 合在一起。
 * 协议见 AOSP system/core/adb/protocol.txt。
 */
internal class AdbClient(
    private val host: String,
    private val port: Int,
    private val key: PrivateKey,
    private val publicKey: ByteArray,
) : Closeable {
    private val socket = Socket()
    private lateinit var input: DataInputStream
    private var nextLocalId = 1

    /** 连接并认证。首次使用时电视会弹出「允许 USB 调试」，需要等家长确认，所以读超时由 [timeoutMs] 控制。 */
    fun connect(timeoutMs: Int) {
        socket.connect(InetSocketAddress(host, port), 3000)
        socket.soTimeout = timeoutMs
        input = DataInputStream(socket.getInputStream().buffered())
        send(A_CNXN, VERSION, MAX_DATA, "host::\u0000".toByteArray())
        var signed = false
        while (true) {
            val m = read()
            when {
                m.command == A_CNXN -> return
                m.command == A_AUTH && m.arg0 == AUTH_TOKEN ->
                    // 先用已有密钥签名；电视不认识这把密钥时会再发一次令牌，这时提交公钥，由电视弹窗让家长确认
                    if (!signed) { signed = true; send(A_AUTH, AUTH_SIGNATURE, 0, sign(m.data)) }
                    else send(A_AUTH, AUTH_RSAPUBLICKEY, 0, publicKey)
                else -> throw IOException("adb 返回了意外的消息：${m.command.toString(16)}")
            }
        }
    }

    fun setReadTimeout(timeoutMs: Int) { socket.soTimeout = timeoutMs }

    /** 执行一条 shell 命令，返回输出（stdout 与 stderr 合并）。 */
    fun shell(command: String): String {
        val localId = nextLocalId++
        send(A_OPEN, localId, 0, "shell:$command\u0000".toByteArray())
        var remoteId = -1
        val out = java.io.ByteArrayOutputStream()
        while (true) {
            val m = read()
            when (m.command) {
                A_OKAY -> if (remoteId < 0) remoteId = m.arg0
                A_WRTE -> {
                    out.write(m.data)
                    send(A_OKAY, localId, m.arg0, ByteArray(0))
                }
                A_CLSE -> {
                    if (remoteId < 0) throw IOException("adb 拒绝执行：$command")
                    return out.toString("UTF-8")
                }
            }
        }
    }

    override fun close() {
        runCatching { socket.close() }
    }

    // ---- 报文 ----

    private class Message(val command: Int, val arg0: Int, val arg1: Int, val data: ByteArray)

    private fun send(command: Int, arg0: Int, arg1: Int, data: ByteArray) {
        val header = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
        header.putInt(command).putInt(arg0).putInt(arg1).putInt(data.size)
        header.putInt(data.sumOf { it.toInt() and 0xff }) // 旧版 adbd 会校验：所有字节之和
        header.putInt(command xor -1)
        val out = socket.getOutputStream()
        out.write(header.array())
        out.write(data)
        out.flush()
    }

    private fun read(): Message {
        val raw = ByteArray(24)
        input.readFully(raw)
        val h = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
        val command = h.int; val arg0 = h.int; val arg1 = h.int; val length = h.int
        h.int // 数据校验和：本机回环连接，不再校验
        if (h.int != command xor -1) throw IOException("adb 报文格式错误")
        if (length < 0 || length > MAX_PAYLOAD) throw IOException("adb 报文过长：$length")
        val data = ByteArray(length)
        input.readFully(data)
        return Message(command, arg0, arg1, data)
    }

    /** adb 对 20 字节令牌的签名：PKCS#1 v1.5 + SHA-1 的 DigestInfo 前缀，令牌本身已是摘要，不再哈希。 */
    private fun sign(token: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("RSA/ECB/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        cipher.update(SIGNATURE_PADDING)
        return cipher.doFinal(token)
    }

    private companion object {
        const val A_CNXN = 0x4e584e43
        const val A_AUTH = 0x48545541
        const val A_OPEN = 0x4e45504f
        const val A_OKAY = 0x59414b4f
        const val A_WRTE = 0x45545257
        const val A_CLSE = 0x45534c43
        const val VERSION = 0x01000000
        const val MAX_DATA = 4096
        const val MAX_PAYLOAD = 1 shl 20
        const val AUTH_TOKEN = 1
        const val AUTH_SIGNATURE = 2
        const val AUTH_RSAPUBLICKEY = 3

        /** 00 01 FF…FF 00 + SHA-1 DigestInfo，凑满 2048 位密钥的 256 字节（后面再接 20 字节令牌）。 */
        val SIGNATURE_PADDING: ByteArray = run {
            val digestInfo = byteArrayOf(0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e, 0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14)
            val ff = 256 - 3 - digestInfo.size - 20
            byteArrayOf(0x00, 0x01) + ByteArray(ff) { 0xff.toByte() } + byteArrayOf(0x00) + digestInfo
        }
    }
}
