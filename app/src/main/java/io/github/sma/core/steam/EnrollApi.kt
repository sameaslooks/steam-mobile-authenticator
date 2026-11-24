package io.github.sma.core.steam

import io.github.sma.core.crypto.ConfirmationKey
import io.github.sma.core.crypto.Secrets
import io.github.sma.core.crypto.SteamTotp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import java.io.IOException

/** Failure in the Steam Guard enrollment flow. [retryable] = the user may correct the input and send it again. */
class EnrollException(message: String, val retryable: Boolean = false) : Exception(message)

/**
 * Steam Guard enrollment through the ITwoFactorService JSON form API.
 * Used by the "remove with revocation code and link again" flow.
 */
object EnrollApi {
    /** Draft returned by AddAuthenticator, kept until the code is confirmed. */
    data class Pending(val draft: SteamAccountDraft)

    /** AddAuthenticator result: the draft and where Steam sent the code ("SMS to ...1234", "SMS", "email", "SMS or email"). */
    data class LinkStart(val draft: SteamAccountDraft, val sentTo: String)

    private const val ERESULT_OK = 1
    private const val STATUS_OK = 1
    private const val STATUS_NO_PHONE = 2
    private const val STATUS_ALREADY_HAS_AUTHENTICATOR = 29
    private const val STATUS_CANNOT_VERIFY = 88
    private const val STATUS_WRONG_SMS = 89
    private const val CONFIRM_TYPE_SMS = 1
    private const val CONFIRM_TYPE_EMAIL = 3
    private const val AUTHENTICATOR_TYPE_MOBILE = 1
    private const val SMS_PHONE_ID = 1
    private const val REVOCATION_REASON_USER = 1
    private const val STEAMGUARD_SCHEME_MOBILE = 1
    private const val FINALIZE_MAX_TRIES = 30
    private const val FINALIZE_STEP_SECONDS = 30L

    /** POSTs a form to ITwoFactorService/<method>/v1 and returns the inner "response" object. */
    private suspend fun post(method: String, accessToken: String, fields: List<Pair<String, String>>): JsonObject =
        withContext(Dispatchers.IO) {
            val url = "https://api.steampowered.com/ITwoFactorService/$method/v1/".toHttpUrl()
                .newBuilder()
                .addQueryParameter("access_token", accessToken)
                .build()
            val body = FormBody.Builder().apply { fields.forEach { (k, v) -> add(k, v) } }.build()
            val request = Request.Builder().url(url).post(body).build()
            SteamHttp.client.newCall(request).execute().use { response ->
                if (response.code == 401 || response.code == 403) throw NeedsSignInException()
                if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                val eresult = response.header("x-eresult")?.trim()?.toIntOrNull() ?: ERESULT_OK
                if (eresult != ERESULT_OK) {
                    throw SteamApiException(eresult, response.header("x-error_message") ?: "EResult $eresult")
                }
                val text = response.body?.string().orEmpty()
                val root = runCatching { Json.parseToJsonElement(text) as? JsonObject }.getOrNull()
                (root?.get("response") as? JsonObject) ?: throw IOException("Unexpected response")
            }
        }

    private fun JsonObject.primitive(key: String): JsonPrimitive? =
        (this[key] as? JsonPrimitive)?.takeUnless { it is JsonNull }

    /** Exact content of a string or number field; empty and missing count as null. */
    private fun JsonObject.text(key: String): String? = primitive(key)?.content?.ifEmpty { null }

    private fun JsonObject.intField(key: String): Int? = primitive(key)?.intOrNull

    private fun JsonObject.boolField(key: String): Boolean? = primitive(key)?.booleanOrNull

    /**
     * RemoveAuthenticator with the revocation code. Returns the attempts Steam reports as remaining.
     * @throws EnrollException when Steam rejects the code; retryable while attempts remain.
     */
    suspend fun removeAuthenticator(accessToken: String, steamId: String, revocationCode: String): Int? {
        val response = post(
            method = "RemoveAuthenticator",
            accessToken = accessToken,
            fields = listOf(
                "steamid" to steamId,
                "revocation_code" to revocationCode.trim().uppercase(),
                "revocation_reason" to REVOCATION_REASON_USER.toString(),
                "steamguard_scheme" to STEAMGUARD_SCHEME_MOBILE.toString(),
            ),
        )
        val remaining = response.intField("revocation_attempts_remaining")
        if (response.boolField("success") != true) {
            val suffix = if (remaining != null) " (attempts left: $remaining)" else ""
            throw EnrollException(
                "Steam rejected the revocation code$suffix",
                retryable = (remaining ?: 0) > 0,
            )
        }
        return remaining
    }

    /** Same as [addAuthenticatorWithChannel], without the delivery channel. */
    suspend fun addAuthenticator(accessToken: String, steamId: String): SteamAccountDraft =
        addAuthenticatorWithChannel(accessToken, steamId).draft

    /**
     * AddAuthenticator: Steam sends a verification code by SMS (or by email when the account has no phone)
     * and returns the new secrets. Nothing is active on Steam until [finalize] succeeds.
     */
    suspend fun addAuthenticatorWithChannel(accessToken: String, steamId: String): LinkStart {
        val response = post(
            method = "AddAuthenticator",
            accessToken = accessToken,
            fields = listOf(
                "steamid" to steamId,
                "authenticator_time" to SteamTime.now().toString(),
                "authenticator_type" to AUTHENTICATOR_TYPE_MOBILE.toString(),
                "device_identifier" to ConfirmationKey.deviceId(steamId),
                "sms_phone_id" to SMS_PHONE_ID.toString(),
            ),
        )
        val status = response.intField("status")
        when (status) {
            STATUS_OK -> Unit
            STATUS_NO_PHONE -> throw EnrollException(
                "Steam could not send a verification code to this account (status 2). " +
                    "Check the account's email and phone in Steam settings.",
            )
            STATUS_ALREADY_HAS_AUTHENTICATOR -> throw EnrollException("This account still has an authenticator")
            else -> throw EnrollException("Steam did not add an authenticator (status $status)")
        }

        val sharedSecret = response.text("shared_secret")
        val identitySecret = response.text("identity_secret")
        val revocationCode = response.text("revocation_code")
        if (sharedSecret == null || identitySecret == null || revocationCode == null) {
            throw EnrollException("Steam did not return the new authenticator secrets")
        }
        val confirmType = response.intField("confirm_type")
        val hint = response.text("phone_number_hint")
        val sentTo = when (confirmType) {
            CONFIRM_TYPE_SMS -> if (hint != null) "SMS to …$hint" else "SMS"
            CONFIRM_TYPE_EMAIL -> "email"
            else -> "SMS or email"
        }
        val draft = SteamAccountDraft(
            sharedSecret = sharedSecret,
            identitySecret = identitySecret,
            secret1 = response.text("secret_1"),
            revocationCode = revocationCode,
            serialNumber = response.text("serial_number").orEmpty(),
            uri = response.text("uri").orEmpty(),
            serverTime = response.text("server_time").orEmpty(),
            accountName = response.text("account_name").orEmpty(),
            tokenGid = response.text("token_gid").orEmpty(),
            status = STATUS_OK.toString(),
            steamId = steamId,
        )
        return LinkStart(draft, sentTo)
    }

    /**
     * FinalizeAddAuthenticator with the code. Steam may ask for later codes (want_more);
     * each try moves the time 30 s forward, up to [FINALIZE_MAX_TRIES] tries.
     * @throws EnrollException on a wrong code or Steam refusal; retryable where the user can re-enter the code.
     */
    suspend fun finalize(accessToken: String, steamId: String, sharedSecret: String, smsCode: String) {
        val secret = Secrets.decode(sharedSecret)
        var time = SteamTime.now()
        repeat(FINALIZE_MAX_TRIES) {
            val response = post(
                method = "FinalizeAddAuthenticator",
                accessToken = accessToken,
                fields = listOf(
                    "steamid" to steamId,
                    "authenticator_code" to SteamTotp.code(secret, time),
                    "authenticator_time" to time.toString(),
                    "activation_code" to smsCode.trim(),
                    "validate_sms_code" to "1",
                ),
            )
            when (response.intField("status")) {
                STATUS_WRONG_SMS -> throw EnrollException("Wrong code from SMS or email", retryable = true)
                STATUS_CANNOT_VERIFY -> throw EnrollException(
                    "Steam could not verify the codes. Check phone time and retry",
                    retryable = true,
                )
            }
            val wantMore = response.boolField("want_more") == true
            if (response.boolField("success") == true && !wantMore) return
            if (!wantMore) throw EnrollException("Finalization did not complete")
            time += FINALIZE_STEP_SECONDS
        }
        throw EnrollException("Finalization did not complete")
    }
}
