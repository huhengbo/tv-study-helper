package de.huyc.studyhelper.core

import dadb.AdbKeyPair
import java.io.File
import java.security.KeyFactory
import java.security.interfaces.RSAPrivateCrtKey
import java.security.interfaces.RSAPublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.RSAPublicKeySpec
import java.util.Base64
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class AdbPublicKeyTest {
    /** 与 dadb 自带的 generate() 生成的公钥逐字节对照（它用 java.util.Base64，Android 6 上不能直接用）。 */
    @Test fun matchesDadbReferenceEncoding() {
        val dir = createTempDir()
        try {
            val privateFile = File(dir, "key"); val publicFile = File(dir, "key.pub")
            AdbKeyPair.generate(privateFile, publicFile)

            val pem = privateFile.readText().lines().filterNot { it.startsWith("-----") }.joinToString("")
            val crt = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(Base64.getDecoder().decode(pem))) as RSAPrivateCrtKey
            val pub = KeyFactory.getInstance("RSA").generatePublic(RSAPublicKeySpec(crt.modulus, crt.publicExponent)) as RSAPublicKey

            val expected = Base64.getDecoder().decode(publicFile.readText().substringBefore(' '))
            assertEquals(524, expected.size)
            assertArrayEquals(expected, adbPublicKey(pub))
        } finally {
            dir.deleteRecursively()
        }
    }
}
