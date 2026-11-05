package de.huyc.studyhelper.core

import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.util.Base64
import java.io.File
import java.math.BigInteger
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.interfaces.RSAPublicKey
import java.security.spec.PKCS8EncodedKeySpec

/**
 * 不需要电脑的授权：电视打开「ADB 调试」后，应用以 adb 客户端身份连接本机 127.0.0.1:5555，
 * 执行与 scripts/setup_tv.sh 相同的几条授权命令（录音、悬浮窗、使用情况、无障碍）。
 * 电视会弹出一次「允许 USB 调试」，家长用遥控器确认即可。
 *
 * 另外可以收回或恢复小爱同学的录音权限（[setXiaoaiMic]）。只执行下面写死的命令，不接受任何外部输入。
 */
object SelfGrant {
    private const val HOST = "127.0.0.1"
    private const val PORT = 5555

    /** 家长要在电视上点确认，所以连接和握手的等待时间放宽。 */
    private const val CONFIRM_TIMEOUT_MS = 120_000
    private const val COMMAND_TIMEOUT_MS = 15_000
    private const val XIAOAI_PKG = "com.xiaomi.voicecontrol"

    sealed class Outcome {
        /** 命令已全部执行；各项是否真的生效要再看 [Permissions.status]。 */
        object Done : Outcome()
        /** 没有等到家长在电视上确认「允许调试」。 */
        object NotConfirmed : Outcome()
        /** 本机 5555 端口连不上：ADB 调试没开，或这台电视不支持网络 ADB。 */
        object Unreachable : Outcome()
        class Failed(val reason: String) : Outcome()
    }

    /**
     * 电视上的「ADB 调试」是否已打开。读系统设置而不去探测 5555 端口：adbd 的本机连接槽只有 16 个，
     * 裸连接探测后不会及时释放，反复探测会把槽占满，之后真正的授权连接就卡住。
     */
    fun adbEnabled(context: Context): Boolean =
        Settings.Global.getInt(context.contentResolver, Settings.Global.ADB_ENABLED, 0) == 1

    /** 阻塞执行，需在后台线程调用。 */
    fun grant(context: Context): Outcome = withAdb(context) { runGrantCommands(it, context) }

    /**
     * 收回或恢复小爱同学的录音权限（appops，重启后仍有效）。小爱是系统 uid 的应用，`pm disable` 停不掉，
     * 但收回录音后它打不开麦克风，遥控器的音频就只给本应用，见 [de.huyc.studyhelper.voice.XiaoaiGuard]。
     * 阻塞执行，需在后台线程调用；执行后读回状态，没生效时返回 [Outcome.Failed]。
     */
    fun setXiaoaiMic(context: Context, allow: Boolean): Outcome = withAdb(context) { adb ->
        val mode = if (allow) "allow" else "ignore"
        shell(adb, "appops set $XIAOAI_PKG RECORD_AUDIO $mode")
        val now = shell(adb, "appops get $XIAOAI_PKG RECORD_AUDIO").trim()
        if (!now.startsWith("RECORD_AUDIO: $mode")) throw IllegalStateException("设置没有生效：$now")
    }

    private fun withAdb(context: Context, commands: (AdbClient) -> Unit): Outcome {
        var connected = false
        return try {
            val (privateKey, publicKey) = loadKeys(context)
            AdbClient(HOST, PORT, privateKey, publicKey).use { adb ->
                adb.connect(CONFIRM_TIMEOUT_MS)
                connected = true
                adb.setReadTimeout(COMMAND_TIMEOUT_MS)
                commands(adb)
            }
            Outcome.Done
        } catch (e: SocketTimeoutException) {
            // 认证阶段超时 = 家长没确认；命令执行阶段超时是别的问题
            if (connected) Outcome.Failed("命令执行超时") else Outcome.NotConfirmed
        } catch (e: ConnectException) {
            Outcome.Unreachable
        } catch (e: Exception) {
            Outcome.Failed(e.message ?: e.javaClass.simpleName)
        }
    }

    private fun runGrantCommands(adb: AdbClient, context: Context) {
        val pkg = context.packageName
        val service = ComponentName(context, GuardAccessibilityService::class.java).flattenToString()

        shell(adb, "pm grant $pkg android.permission.RECORD_AUDIO")
        shell(adb, "appops set $pkg SYSTEM_ALERT_WINDOW allow")
        shell(adb, "appops set $pkg GET_USAGE_STATS allow")

        // 保留系统已有的无障碍服务（如小爱同学），只追加自己
        val current = shell(adb, "settings get secure enabled_accessibility_services").trim()
        val enabled = if (current.isEmpty() || current == "null") emptyList() else current.split(":")
        if (service !in enabled) shell(adb, "settings put secure enabled_accessibility_services ${(enabled + service).joinToString(":")}")
        shell(adb, "settings put secure accessibility_enabled 1")
    }

    /** 旧版 adbd 的 shell 没有退出码，所以是否成功以授权后重新检查权限状态为准。 */
    private fun shell(adb: AdbClient, command: String): String = adb.shell(command)

    // ---- 密钥 ----

    /** 密钥只在本应用私有目录里，生成一次后复用，家长勾选「一律允许」后下次不再弹窗。 */
    private fun loadKeys(context: Context): Pair<PrivateKey, ByteArray> {
        val privateFile = File(context.filesDir, "adbkey")
        val publicFile = File(context.filesDir, "adbkey.pub")
        if (!privateFile.exists() || !publicFile.exists()) {
            val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.genKeyPair()
            privateFile.writeBytes(pair.private.encoded)
            // adb 要求公钥以 NUL 结尾：「base64 备注\0」
            val text = Base64.encodeToString(adbPublicKey(pair.public as RSAPublicKey), Base64.NO_WRAP) + " studyhelper@tv"
            publicFile.writeBytes(text.toByteArray() + 0)
        }
        val private = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(privateFile.readBytes()))
        return private to publicFile.readBytes()
    }
}

/**
 * adb 的公钥格式（system/core/adb 里的 RSAPublicKey 结构，小端）：
 * len（模数字数）、n0inv（-1/n[0] mod 2^32）、模数 n、R² mod n、指数。
 */
internal fun adbPublicKey(key: RSAPublicKey): ByteArray {
    val words = 64 // 2048 位
    val n = key.modulus
    val r32 = BigInteger.ZERO.setBit(32)
    val rr = BigInteger.ZERO.setBit(words * 32).pow(2).mod(n)
    val n0inv = n.mod(r32).modInverse(r32).negate()
    val buf = ByteBuffer.allocate(4 + 4 + words * 4 * 2 + 4).order(ByteOrder.LITTLE_ENDIAN)
    buf.putInt(words)
    buf.putInt(n0inv.toInt())
    for (i in 0 until words) buf.putInt(n.shiftRight(32 * i).toInt())
    for (i in 0 until words) buf.putInt(rr.shiftRight(32 * i).toInt())
    buf.putInt(key.publicExponent.toInt())
    return buf.array()
}
