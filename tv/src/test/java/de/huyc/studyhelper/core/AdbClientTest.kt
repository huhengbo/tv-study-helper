package de.huyc.studyhelper.core

import java.io.DataInputStream
import java.net.ServerSocket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyPairGenerator
import javax.crypto.Cipher
import kotlin.concurrent.thread
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 用一个按 adb 协议实现的假 adbd，核对客户端的握手、签名、公钥提交和 shell 读取。 */
class AdbClientTest {
    private val cnxn = 0x4e584e43; private val auth = 0x48545541; private val open = 0x4e45504f
    private val okay = 0x59414b4f; private val wrte = 0x45545257; private val clse = 0x45534c43

    private class Msg(val cmd: Int, val arg0: Int, val arg1: Int, val data: ByteArray)

    private fun DataInputStream.msg(): Msg {
        val raw = ByteArray(24); readFully(raw)
        val h = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
        val cmd = h.int; val a0 = h.int; val a1 = h.int; val len = h.int; val sum = h.int; val magic = h.int
        assertEquals(cmd xor -1, magic)
        val data = ByteArray(len); readFully(data)
        assertEquals("数据校验和", data.sumOf { it.toInt() and 0xff }, sum)
        return Msg(cmd, a0, a1, data)
    }

    private fun java.io.OutputStream.msg(cmd: Int, a0: Int, a1: Int, data: ByteArray = ByteArray(0)) {
        val h = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
        h.putInt(cmd).putInt(a0).putInt(a1).putInt(data.size).putInt(data.sumOf { it.toInt() and 0xff }).putInt(cmd xor -1)
        write(h.array()); write(data); flush()
    }

    @Test fun authenticatesWithNewKeyAndRunsShell() {
        val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.genKeyPair()
        val publicKeyBytes = "fake-adb-public-key".toByteArray() + 0
        val token = ByteArray(20) { (it * 7 + 1).toByte() }
        val server = ServerSocket(0)
        var serverError: Throwable? = null
        val t = thread {
            try {
                server.accept().use { s ->
                    val input = DataInputStream(s.getInputStream().buffered()); val out = s.getOutputStream()
                    assertEquals(cnxn, input.msg().cmd)
                    out.msg(auth, 1, 0, token)

                    val sig = input.msg()
                    assertEquals(auth, sig.cmd); assertEquals(2, sig.arg0)
                    // 公钥解签名，应还原出 00 01 FF.. 00 DigestInfo 令牌
                    val plain = Cipher.getInstance("RSA/ECB/NoPadding").apply { init(Cipher.DECRYPT_MODE, pair.public) }.doFinal(sig.data)
                    assertArrayEquals(token, plain.copyOfRange(plain.size - 20, plain.size))
                    assertEquals(0x01, plain[1].toInt()); assertEquals(0xff, plain[2].toInt() and 0xff)
                    out.msg(auth, 1, 0, token) // 不认识这把密钥，要求提交公钥

                    val pub = input.msg()
                    assertEquals(3, pub.arg0); assertArrayEquals(publicKeyBytes, pub.data)
                    out.msg(cnxn, 0x01000000, 4096, "device::ro.product.model=Test;".toByteArray()) // 没有 features=

                    val o = input.msg()
                    assertEquals(open, o.cmd); assertEquals("shell:echo hi\u0000", String(o.data))
                    out.msg(okay, 7, o.arg0)
                    out.msg(wrte, 7, o.arg0, "h".toByteArray())
                    assertEquals(okay, input.msg().cmd)
                    out.msg(wrte, 7, o.arg0, "i\n".toByteArray())
                    assertEquals(okay, input.msg().cmd)
                    out.msg(clse, 7, o.arg0)
                }
            } catch (e: Throwable) { serverError = e }
        }
        AdbClient("127.0.0.1", server.localPort, pair.private, publicKeyBytes).use { c ->
            c.connect(5000)
            assertEquals("hi\n", c.shell("echo hi"))
        }
        t.join(5000); server.close()
        serverError?.let { throw it }
        assertTrue(!t.isAlive)
    }
}
