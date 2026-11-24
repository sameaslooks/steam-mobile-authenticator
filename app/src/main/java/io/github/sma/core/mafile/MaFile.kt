package io.github.sma.core.mafile

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonUnquotedLiteral
import java.net.URLEncoder
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.Base64
import javax.crypto.BadPaddingException
import javax.crypto.Cipher
import javax.crypto.IllegalBlockSizeException
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

@Serializable
data class SteamAccount(
    val accountName: String,
    val steamId: String,
    val sharedSecret: String,
    val identitySecret: String,
    val revocationCode: String? = null,
    val serialNumber: String? = null,
    val tokenGid: String? = null,
    val uri: String? = null,
    val secret1: String? = null,
    val deviceId: String? = null,
    val serverTime: String? = null,
    val status: Int? = null,
    val fullyEnrolled: Boolean? = null,
    val refreshToken: String? = null,
)

data class ManifestEntry(
    val filename: String,
    val steamId: String,
    val salt: String?,
    val iv: String?,
)

data class Manifest(
    val encrypted: Boolean,
    val entries: List<ManifestEntry>,
)

object MaFile {

    private val STEAM_ID_REGEX = Regex("^7656119\\d{10}$")
    private val DIGITS_REGEX = Regex("^\\d+$")
    private const val PBKDF2_ALGORITHM = "PBKDF2WithHmacSHA1"
    private const val PBKDF2_ITERATIONS = 50000
    private const val KEY_BITS = 256
    private const val AES_TRANSFORMATION = "AES/CBC/PKCS5Padding"
    private const val WRONG_PASSWORD_MESSAGE = "Wrong password or corrupt file"

    fun parse(text: String, fileName: String? = null): SteamAccount {
        val root = parseObject(text)

        val session = root["Session"] as? JsonObject
        val rawSteamId = session?.str("SteamID")
            ?: root.str("steamid")
            ?: fileName?.removeSuffix(".maFile")
        require(rawSteamId != null) { "Missing field: steamId" }
        require(STEAM_ID_REGEX.matches(rawSteamId)) { "Invalid steamId: $rawSteamId" }

        return SteamAccount(
            accountName = required(root, "account_name"),
            steamId = rawSteamId,
            sharedSecret = required(root, "shared_secret"),
            identitySecret = required(root, "identity_secret"),
            revocationCode = root.str("revocation_code"),
            serialNumber = root.str("serial_number"),
            tokenGid = root.str("token_gid"),
            uri = root.str("uri"),
            secret1 = root.str("secret_1"),
            deviceId = root.str("device_id"),
            serverTime = root.str("server_time"),
            status = root.str("status")?.toIntOrNull(),
            fullyEnrolled = root.str("fully_enrolled")?.toBooleanStrictOrNull(),
            refreshToken = session?.str("RefreshToken"),
        )
    }

    fun parseManifest(text: String): Manifest {
        val root = parseObject(text)
        val encrypted = root.str("encrypted")?.toBooleanStrictOrNull() ?: false
        val entriesJson = root["entries"]
        val entries = when (entriesJson) {
            null -> emptyList()
            is JsonArray -> entriesJson.map { element ->
                val obj = element as? JsonObject
                    ?: throw IllegalArgumentException("Invalid manifest entry")
                val steamId = obj.str("steamid")
                    ?: throw IllegalArgumentException("Missing manifest field: steamid")
                require(STEAM_ID_REGEX.matches(steamId)) { "Invalid manifest steamid: $steamId" }
                ManifestEntry(
                    filename = required(obj, "filename"),
                    steamId = steamId,
                    salt = obj.str("encryption_salt"),
                    iv = obj.str("encryption_iv"),
                )
            }
            else -> throw IllegalArgumentException("Invalid manifest entries")
        }
        return Manifest(encrypted = encrypted, entries = entries)
    }

    /** @throws IllegalArgumentException on wrong password, corrupt data or invalid Base64. */
    fun decryptSda(cipherBase64: String, password: String, saltBase64: String, ivBase64: String): String {
        val decoder = Base64.getDecoder()
        val salt = decoder.decode(saltBase64)
        val iv = decoder.decode(ivBase64)
        val cipherBytes = decoder.decode(cipherBase64)

        val key = deriveKey(password, salt)
        val cipher = Cipher.getInstance(AES_TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))

        val plain = try {
            cipher.doFinal(cipherBytes)
        } catch (e: BadPaddingException) {
            throw IllegalArgumentException(WRONG_PASSWORD_MESSAGE, e)
        } catch (e: IllegalBlockSizeException) {
            throw IllegalArgumentException(WRONG_PASSWORD_MESSAGE, e)
        }

        val decoderUtf8 = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        return try {
            decoderUtf8.decode(ByteBuffer.wrap(plain)).toString()
        } catch (e: java.nio.charset.CharacterCodingException) {
            throw IllegalArgumentException(WRONG_PASSWORD_MESSAGE, e)
        }
    }

    /** SDA-compatible JSON export. SteamID and serial_number are written as raw JSON numbers. */
    @OptIn(ExperimentalSerializationApi::class)
    /**
     * SDA-compatible maFile. Session mirrors SDA 1.0.15: SteamID, AccessToken, RefreshToken, SessionID
     * (always present, null when unknown). [accessToken] is optional; SDA refreshes it from RefreshToken.
     */
    fun export(acc: SteamAccount, accessToken: String? = null): String {
        val session = LinkedHashMap<String, kotlinx.serialization.json.JsonElement>()
        session["SteamID"] = JsonUnquotedLiteral(acc.steamId)
        session["AccessToken"] = accessToken?.let { JsonPrimitive(it) } ?: kotlinx.serialization.json.JsonNull
        session["RefreshToken"] = acc.refreshToken?.let { JsonPrimitive(it) } ?: kotlinx.serialization.json.JsonNull
        session["SessionID"] = kotlinx.serialization.json.JsonNull

        val map = LinkedHashMap<String, kotlinx.serialization.json.JsonElement>()
        map["shared_secret"] = JsonPrimitive(acc.sharedSecret)
        acc.serialNumber?.let { map["serial_number"] = numberOrString(it) }
        acc.revocationCode?.let { map["revocation_code"] = JsonPrimitive(it) }
        acc.uri?.let { map["uri"] = JsonPrimitive(it) }
        acc.serverTime?.let { map["server_time"] = numberOrString(it) } // SDA stores it as a number
        map["account_name"] = JsonPrimitive(acc.accountName)
        acc.tokenGid?.let { map["token_gid"] = JsonPrimitive(it) }
        map["identity_secret"] = JsonPrimitive(acc.identitySecret)
        acc.secret1?.let { map["secret_1"] = JsonPrimitive(it) }
        acc.status?.let { map["status"] = JsonPrimitive(it) }
        acc.deviceId?.let { map["device_id"] = JsonPrimitive(it) }
        acc.fullyEnrolled?.let { map["fully_enrolled"] = JsonPrimitive(it) }
        map["Session"] = JsonObject(session)

        return JsonObject(map).toString()
    }

    /** otpauth URI for authenticator apps (Steam TOTP with 5 digits, 30 s period). */
    fun otpauth(acc: SteamAccount): String {
        val secret = base32(Base64.getDecoder().decode(acc.sharedSecret))
        val label = URLEncoder.encode(acc.accountName, "UTF-8")
        return "otpauth://totp/Steam:$label?secret=$secret&issuer=Steam&encoder=steam&digits=5&period=30"
    }

    private fun parseObject(text: String): JsonObject {
        val trimmed = text.removePrefix("﻿").trim()
        require(trimmed.startsWith("{")) { "Input is not a JSON object" }
        return Json.parseToJsonElement(trimmed) as? JsonObject
            ?: throw IllegalArgumentException("Input is not a JSON object")
    }

    private fun required(obj: JsonObject, key: String): String =
        obj.str(key) ?: throw IllegalArgumentException("Missing required field: $key")

    /** Returns the primitive's exact content string; empty strings and JSON null count as absent. */
    private fun JsonObject.str(key: String): String? {
        val primitive = this[key] as? JsonPrimitive ?: return null
        if (primitive is JsonNull) return null
        return primitive.content.ifEmpty { null }
    }

    @OptIn(ExperimentalSerializationApi::class)
    private fun numberOrString(value: String): kotlinx.serialization.json.JsonElement =
        if (DIGITS_REGEX.matches(value)) JsonUnquotedLiteral(value) else JsonPrimitive(value)

    private fun deriveKey(password: String, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(password.toCharArray(), salt, PBKDF2_ITERATIONS, KEY_BITS)
        try {
            return SecretKeyFactory.getInstance(PBKDF2_ALGORITHM).generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    private const val BASE32_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"

    /** RFC 4648 Base32, uppercase, no padding. */
    private fun base32(bytes: ByteArray): String {
        val sb = StringBuilder()
        var buffer = 0
        var bitsLeft = 0
        for (b in bytes) {
            buffer = (buffer shl 8) or (b.toInt() and 0xFF)
            bitsLeft += 8
            while (bitsLeft >= 5) {
                bitsLeft -= 5
                sb.append(BASE32_ALPHABET[(buffer shr bitsLeft) and 0x1F])
            }
            buffer = buffer and ((1 shl bitsLeft) - 1)
        }
        if (bitsLeft > 0) {
            sb.append(BASE32_ALPHABET[(buffer shl (5 - bitsLeft)) and 0x1F])
        }
        return sb.toString()
    }
}
