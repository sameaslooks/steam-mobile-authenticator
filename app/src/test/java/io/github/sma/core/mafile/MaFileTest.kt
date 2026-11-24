package io.github.sma.core.mafile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

class MaFileTest {

    private val sample = """
        {
          "account_name": "test_user",
          "shared_secret": "AQIDBAUGBwgJCgsMDQ4PEBESExQ=",
          "identity_secret": "FVVWV1hZWltcXV5fYGBhYg==",
          "serial_number": 12345678901234567890,
          "Session": {"SteamID": 76561198000000123, "RefreshToken": "tok"}
        }
    """.trimIndent()

    @Test
    fun parseKeepsExactDigits() {
        val acc = MaFile.parse(sample)
        assertEquals("76561198000000123", acc.steamId)
        assertEquals("12345678901234567890", acc.serialNumber)
        assertEquals("test_user", acc.accountName)
        assertEquals("tok", acc.refreshToken)
    }

    @Test(expected = IllegalArgumentException::class)
    fun missingIdentitySecretThrows() {
        MaFile.parse("""{"account_name":"a","shared_secret":"AQ==","Session":{"SteamID":76561198000000123}}""")
    }

    @Test
    fun bomIsHandled() {
        val acc = MaFile.parse("﻿$sample")
        assertEquals("76561198000000123", acc.steamId)
    }

    @Test
    fun steamIdFallsBackToFileName() {
        val text = """{"account_name":"a","shared_secret":"AQ==","identity_secret":"Ag=="}"""
        val acc = MaFile.parse(text, "76561198000000456.maFile")
        assertEquals("76561198000000456", acc.steamId)
        assertNull(acc.serialNumber)
    }

    @Test
    fun exportParseRoundtrip() {
        val acc = SteamAccount(
            accountName = "test user",
            steamId = "76561198000000123",
            sharedSecret = "AQIDBAUGBwgJCgsMDQ4PEBESExQ=",
            identitySecret = "FVVWV1hZWltcXV5fYGBhYg==",
            revocationCode = "R12345",
            serialNumber = "12345678901234567890",
            tokenGid = "abc123",
            uri = "https://example.invalid/uri",
            secret1 = "s1",
            deviceId = "android:dev-1",
            serverTime = "1700000000",
            status = 2,
            fullyEnrolled = true,
            refreshToken = "rt",
        )
        val exported = MaFile.export(acc)
        assertEquals(acc, MaFile.parse(exported))
    }

    @Test
    fun otpauthUri() {
        val acc = SteamAccount(
            accountName = "test user",
            steamId = "76561198000000123",
            sharedSecret = "AQIDBAUGBwgJCgsMDQ4PEBESExQ=",
            identitySecret = "FVVWV1hZWltcXV5fYGBhYg==",
        )
        assertEquals(
            "otpauth://totp/Steam:test+user?secret=AEBAGBAFAYDQQCIKBMGA2DQPCAIREEYU&issuer=Steam&encoder=steam&digits=5&period=30",
            MaFile.otpauth(acc),
        )
    }

    @Test
    fun decryptSdaRoundtrip() {
        val plain = """{"account_name":"x","shared_secret":"AQ=="}"""
        val salt = ByteArray(16) { (it + 3).toByte() }
        val iv = ByteArray(16) { (it + 1).toByte() }
        val cipher = encryptForTest(plain, "correct horse", salt, iv)

        val decrypted = MaFile.decryptSda(
            cipher,
            "correct horse",
            Base64.getEncoder().encodeToString(salt),
            Base64.getEncoder().encodeToString(iv),
        )
        assertEquals(plain, decrypted)
    }

    @Test
    fun decryptSdaWrongPasswordThrows() {
        val salt = ByteArray(16) { (it + 3).toByte() }
        val iv = ByteArray(16) { (it + 1).toByte() }
        val cipher = encryptForTest("""{"account_name":"x"}""", "correct horse", salt, iv)
        try {
            MaFile.decryptSda(
                cipher,
                "wrong password",
                Base64.getEncoder().encodeToString(salt),
                Base64.getEncoder().encodeToString(iv),
            )
            fail("Expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertEquals("Wrong password or corrupt file", e.message)
        }
    }

    private fun encryptForTest(plain: String, password: String, salt: ByteArray, iv: ByteArray): String {
        val spec = PBEKeySpec(password.toCharArray(), salt, 50000, 256)
        val key = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1").generateSecret(spec).encoded
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        return Base64.getEncoder().encodeToString(cipher.doFinal(plain.toByteArray(Charsets.UTF_8)))
    }
}
