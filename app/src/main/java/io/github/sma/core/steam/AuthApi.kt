package io.github.sma.core.steam

import io.github.sma.core.crypto.QrApproval
import io.github.sma.core.crypto.Secrets
import io.github.sma.core.mafile.SteamAccount
import io.github.sma.core.proto.ProtoMessage
import io.github.sma.core.proto.ProtoWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.FormBody
import okhttp3.Request
import java.io.IOException
import java.math.BigInteger
import java.security.KeyFactory
import java.security.spec.RSAPublicKeySpec
import java.util.Base64
import javax.crypto.Cipher
import kotlin.math.max

object AuthApi {
    private const val ERESULT_DUPLICATE_REQUEST = 29
    private const val PLATFORM_MOBILE_APP = 3L
    private const val OS_TYPE_ANDROID = -500L
    private const val GAMING_DEVICE_TYPE = 528L
    private const val DEVICE_FRIENDLY_NAME = "SMA"

    data class LoginSession(
        val clientId: Long,
        val requestId: ByteArray,
        val steamId: Long,
        val intervalSeconds: Float,
        val allowedConfirmations: List<Int>,
        val weakToken: String? = null,
    )

    data class Tokens(val refreshToken: String, val accessToken: String, val accountName: String?)

    data class AuthSessionInfo(
        val ip: String?,
        val geoloc: String?,
        val city: String?,
        val state: String?,
        val country: String?,
        val platformType: Int?,
        val deviceName: String?,
        val version: Int,
        val locationMismatch: Boolean,
        val highUsage: Boolean,
    )

    suspend fun begin(accountName: String, password: String): LoginSession {
        val rsaRequest = ProtoWriter()
            .string(1, accountName)
            .toByteArray()
        val rsa = SteamHttp.callService(
            iface = "Authentication",
            method = "GetPasswordRSAPublicKey",
            request = rsaRequest,
            get = true,
        )
        val modHex = rsa.string(1) ?: throw IOException("Missing RSA modulus")
        val expHex = rsa.string(2) ?: throw IOException("Missing RSA exponent")
        val timestamp = rsa.varint(3) ?: throw IOException("Missing RSA timestamp")

        val encryptedPassword = encryptPassword(password, modHex, expHex)

        val deviceDetails = ProtoWriter()
            .string(1, DEVICE_FRIENDLY_NAME)
            .varint(2, PLATFORM_MOBILE_APP)
            .varint(3, OS_TYPE_ANDROID)
            .varint(4, GAMING_DEVICE_TYPE)
            .toByteArray()

        val beginRequest = ProtoWriter()
            .string(1, DEVICE_FRIENDLY_NAME)
            .string(2, accountName)
            .string(3, encryptedPassword)
            .varint(4, timestamp)
            .bool(5, true)
            .varint(6, PLATFORM_MOBILE_APP)
            .varint(7, 1)
            .string(8, "Mobile")
            .message(9, deviceDetails)
            .varint(11, 0)
            .toByteArray()

        val response = SteamHttp.callService(
            iface = "Authentication",
            method = "BeginAuthSessionViaCredentials",
            request = beginRequest,
        )

        return LoginSession(
            clientId = response.varint(1) ?: 0L,
            requestId = response.bytes(2) ?: ByteArray(0),
            steamId = response.varint(5) ?: 0L,
            intervalSeconds = response.float32(3) ?: 5f,
            allowedConfirmations = response.messages(4).mapNotNull { it.varint(1)?.toInt() },
            weakToken = response.string(6)?.takeIf { it.isNotEmpty() },
        )
    }

    suspend fun submitCode(s: LoginSession, code: String, codeType: Int) {
        val request = ProtoWriter()
            .varint(1, s.clientId)
            .fixed64(2, s.steamId)
            // Steam Guard codes are upper-case; typed codes often come in lower case or with spaces.
            .string(3, code.filterNot { it.isWhitespace() }.uppercase())
            .varint(4, codeType.toLong())
            .toByteArray()
        try {
            SteamHttp.callService(
                iface = "Authentication",
                method = "UpdateAuthSessionWithSteamGuardCode",
                request = request,
            )
        } catch (e: SteamApiException) {
            when (e.eresult) {
                ERESULT_DUPLICATE_REQUEST -> Unit
                // 88 = TwoFactorCodeMismatch, 65 = InvalidLoginAuthCode
                88, 65 -> throw SteamApiException(
                    e.eresult,
                    "Wrong or expired code. Steam Guard codes change every 30 seconds — enter the current one.",
                )
                else -> throw e
            }
        }
    }

    suspend fun poll(s: LoginSession, timeoutSeconds: Int = 90): Tokens {
        val deadline = System.currentTimeMillis() + timeoutSeconds * 1000L
        val delayMillis = (max(s.intervalSeconds, 1f) * 1000).toLong()
        var clientId = s.clientId
        while (true) {
            if (System.currentTimeMillis() >= deadline) throw IOException("Sign-in timed out")

            val request = ProtoWriter()
                .varint(1, clientId)
                .bytes(2, s.requestId)
                .toByteArray()
            val response = SteamHttp.callService(
                iface = "Authentication",
                method = "PollAuthSessionStatus",
                request = request,
            )

            response.varint(1)?.takeIf { it != 0L }?.let { clientId = it }

            val refreshToken = response.string(3).orEmpty()
            if (refreshToken.isNotEmpty()) {
                return Tokens(
                    refreshToken = refreshToken,
                    accessToken = response.string(4).orEmpty(),
                    accountName = response.string(6),
                )
            }
            delay(delayMillis)
        }
    }

    suspend fun generateAccessToken(refreshToken: String, steamId: String): String =
        withContext(Dispatchers.IO) {
            val body = FormBody.Builder()
                .add("refresh_token", refreshToken)
                .add("steamid", steamId)
                .build()
            val request = Request.Builder()
                .url("https://api.steampowered.com/IAuthenticationService/GenerateAccessTokenForApp/v1/")
                .post(body)
                .build()
            SteamHttp.client.newCall(request).execute().use { response ->
                if (response.code == 401 || response.code == 403) throw NeedsSignInException()
                if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                val text = response.body?.string().orEmpty()
                val root = runCatching { Json.parseToJsonElement(text) as? JsonObject }.getOrNull()
                val inner = root?.get("response") as? JsonObject
                val token = (inner?.get("access_token") as? JsonPrimitive)?.content
                if (token.isNullOrEmpty()) throw NeedsSignInException()
                token
            }
        }

    suspend fun sessionInfo(accessToken: String, clientId: Long): AuthSessionInfo {
        val request = ProtoWriter()
            .varint(1, clientId)
            .toByteArray()
        val r: ProtoMessage = SteamHttp.callService(
            iface = "Authentication",
            method = "GetAuthSessionInfo",
            request = request,
            accessToken = accessToken,
        )
        return AuthSessionInfo(
            ip = r.string(1),
            geoloc = r.string(2),
            city = r.string(3),
            state = r.string(4),
            country = r.string(5),
            platformType = r.varint(6)?.toInt(),
            deviceName = r.string(7),
            version = r.varint(8)?.toInt() ?: 0,
            locationMismatch = r.bool(10) ?: false,
            highUsage = r.bool(11) ?: false,
        )
    }

    suspend fun pendingSessions(accessToken: String): List<Long> {
        val response = SteamHttp.callService(
            iface = "Authentication",
            method = "GetAuthSessionsForAccount",
            request = ProtoWriter().toByteArray(),
            accessToken = accessToken,
            get = true, // Steam answers 405 to POST for this method
        )
        return response.varints(1)
    }

    suspend fun respondToSession(
        accessToken: String,
        account: SteamAccount,
        version: Int,
        clientId: Long,
        approve: Boolean,
    ) {
        val steamId = account.steamId.toLong()
        val signature = QrApproval.signature(Secrets.decode(account.sharedSecret), version, clientId, steamId)
        val request = ProtoWriter()
            .varint(1, version.toLong())
            .varint(2, clientId)
            .fixed64(3, steamId)
            .bytes(4, signature)
            .bool(5, approve)
            .varint(6, 1)
            .toByteArray()
        SteamHttp.callService(
            iface = "Authentication",
            method = "UpdateAuthSessionWithMobileConfirmation",
            request = request,
            accessToken = accessToken,
        )
    }

    private fun encryptPassword(password: String, modHex: String, expHex: String): String {
        val spec = RSAPublicKeySpec(BigInteger(modHex, 16), BigInteger(expHex, 16))
        val publicKey = KeyFactory.getInstance("RSA").generatePublic(spec)
        val cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding")
        cipher.init(Cipher.ENCRYPT_MODE, publicKey)
        val encrypted = cipher.doFinal(password.toByteArray(Charsets.UTF_8))
        return Base64.getEncoder().encodeToString(encrypted)
    }
}
