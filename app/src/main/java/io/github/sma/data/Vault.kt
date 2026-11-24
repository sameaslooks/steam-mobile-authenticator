package io.github.sma.data

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import io.github.sma.core.mafile.SteamAccount
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.GeneralSecurityException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Legacy key. Requires an unlocked device, so background workers cannot read it. */
private const val KEY_ALIAS_V1 = "sma_vault_v1"

/** Current key. Usable while the device is locked, so background workers can read the vault. */
private const val KEY_ALIAS_V2 = "sma_vault_v2"
private const val ANDROID_KEYSTORE = "AndroidKeyStore"
private const val TRANSFORMATION = "AES/GCM/NoPadding"
private const val FORMAT_VERSION_V1: Byte = 1
private const val FORMAT_VERSION_V2: Byte = 2
private const val IV_LENGTH = 12
private const val TAG_BITS = 128
private const val TAG_BYTES = 16
private const val FILE_NAME = "vault.bin"
private val AAD_V1 = "sma-vault-v1".toByteArray(Charsets.UTF_8)
private val AAD_V2 = "sma-vault-v2".toByteArray(Charsets.UTF_8)

class VaultCorruptedException(message: String, cause: Throwable? = null) : Exception(message, cause)

@Serializable
data class NotificationSettings(
    val mode: String = MODE_PERIODIC,
    val periodicMinutes: Int = 15,
    val serviceSeconds: Int = 60,
    val slowWhenScreenOff: Boolean = true,
    val disabledAccounts: List<String> = emptyList(),
) {
    companion object {
        const val MODE_OFF = "off"
        const val MODE_PERIODIC = "periodic"
        const val MODE_SERVICE = "service"
    }
}

@Serializable
data class Settings(
    val requireUnlock: Boolean = true,
    val notifications: NotificationSettings = NotificationSettings(),
    val showSignInMap: Boolean = true,
)

@Serializable
data class VaultData(
    val version: Int = 1,
    val accounts: List<SteamAccount> = emptyList(),
    val settings: Settings = Settings(),
)

/**
 * Encrypted on-disk storage for [VaultData].
 *
 * File layout: 1 byte format version | 12 byte IV | AES-GCM ciphertext (with 128-bit tag).
 * Format version 1 uses key [KEY_ALIAS_V1] and AAD "sma-vault-v1".
 * Format version 2 uses key [KEY_ALIAS_V2] and AAD "sma-vault-v2". [save] always writes version 2.
 * Blocking I/O: call from a background dispatcher.
 */
class Vault(context: Context) {
    private val vaultFile = File(context.filesDir, FILE_NAME)
    private val tmpFile = File(context.filesDir, "$FILE_NAME.tmp")
    private val bakFile = File(context.filesDir, "$FILE_NAME.bak")

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /**
     * Returns empty data when no vault file exists.
     * @throws VaultCorruptedException when the file cannot be decrypted or parsed, or has an unknown format version.
     * The file is never modified here.
     * @throws IOException on read failure.
     */
    fun load(): VaultData {
        if (!vaultFile.exists()) return VaultData()
        val plain = decrypt(vaultFile.readBytes())
        return try {
            json.decodeFromString(VaultData.serializer(), plain.toString(Charsets.UTF_8))
        } catch (e: SerializationException) {
            throw VaultCorruptedException("Vault payload is invalid", e)
        }
    }

    /** True when the vault file exists and is still in the legacy format version 1. */
    fun needsMigration(): Boolean = formatVersionOf(vaultFile) == FORMAT_VERSION_V1

    /**
     * Writes to a temp file, keeps the previous vault as backup, then atomically replaces the vault file.
     * Always writes format version 2. After a successful write, the legacy key is deleted
     * unless the vault or its backup is still in format version 1.
     * @throws IOException on write failure; the existing vault file stays intact.
     */
    fun save(data: VaultData) {
        val plain = json.encodeToString(VaultData.serializer(), data).toByteArray(Charsets.UTF_8)
        val blob = encrypt(plain)
        FileOutputStream(tmpFile).use { out ->
            out.write(blob)
            out.fd.sync()
        }
        if (vaultFile.exists()) {
            vaultFile.copyTo(bakFile, overwrite = true)
        }
        replaceFile(tmpFile, vaultFile)
        dropLegacyKeyIfUnused()
    }

    private fun dropLegacyKeyIfUnused() {
        if (formatVersionOf(vaultFile) == FORMAT_VERSION_V1 || formatVersionOf(bakFile) == FORMAT_VERSION_V1) return
        try {
            keyStore().deleteEntry(KEY_ALIAS_V1)
        } catch (e: Exception) {
            // Best effort: a leftover unused key is harmless.
        }
    }

    private fun formatVersionOf(file: File): Byte? {
        if (!file.exists()) return null
        return try {
            file.inputStream().use { it.read() }.takeIf { it >= 0 }?.toByte()
        } catch (e: IOException) {
            null
        }
    }

    private fun replaceFile(src: File, dst: File) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                Files.move(
                    src.toPath(),
                    dst.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
                return
            } catch (e: IOException) {
                // fall through to renameTo
            } catch (e: UnsupportedOperationException) {
                // filesystem does not support atomic move; fall through
            }
        }
        if (!src.renameTo(dst)) {
            throw IOException("Failed to replace ${dst.name}")
        }
    }

    private fun keyStore(): KeyStore =
        KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    private fun existingKey(alias: String): SecretKey? =
        keyStore().getKey(alias, null) as? SecretKey

    private fun getOrCreateV2Key(): SecretKey {
        existingKey(KEY_ALIAS_V2)?.let { return it }
        // Deliberately no setUnlockedDeviceRequired: background workers must read the vault while locked.
        val spec = KeyGenParameterSpec.Builder(
            KEY_ALIAS_V2,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setRandomizedEncryptionRequired(true)
            .setUserAuthenticationRequired(false)
            .build()
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(spec)
        return generator.generateKey()
    }

    private fun encrypt(plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateV2Key())
        cipher.updateAAD(AAD_V2)
        val iv = cipher.iv
        val body = cipher.doFinal(plain)
        return byteArrayOf(FORMAT_VERSION_V2) + iv + body
    }

    private fun decrypt(blob: ByteArray): ByteArray {
        if (blob.isEmpty()) throw VaultCorruptedException("Unsupported or truncated vault file")
        return when (blob[0]) {
            FORMAT_VERSION_V1 -> decryptWith(blob, KEY_ALIAS_V1, AAD_V1)
            FORMAT_VERSION_V2 -> decryptWith(blob, KEY_ALIAS_V2, AAD_V2)
            else -> throw VaultCorruptedException("Unsupported vault format version ${blob[0]}")
        }
    }

    private fun decryptWith(blob: ByteArray, alias: String, aad: ByteArray): ByteArray {
        if (blob.size < 1 + IV_LENGTH + TAG_BYTES) {
            throw VaultCorruptedException("Unsupported or truncated vault file")
        }
        val iv = blob.copyOfRange(1, 1 + IV_LENGTH)
        val body = blob.copyOfRange(1 + IV_LENGTH, blob.size)
        return try {
            val key = existingKey(alias) ?: throw VaultCorruptedException("Vault key is missing")
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
            cipher.updateAAD(aad)
            cipher.doFinal(body)
        } catch (e: GeneralSecurityException) {
            throw VaultCorruptedException("Vault cannot be decrypted", e)
        } catch (e: IllegalArgumentException) {
            throw VaultCorruptedException("Vault cannot be decrypted", e)
        }
    }
}
