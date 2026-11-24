package io.github.sma.core.steam

import io.github.sma.core.crypto.ConfirmationKey
import io.github.sma.core.mafile.SteamAccount
import io.github.sma.core.proto.ProtoMessage
import io.github.sma.core.proto.ProtoWriter
import kotlinx.coroutines.CancellationException
import java.io.IOException
import java.net.ConnectException
import java.net.UnknownHostException
import java.util.Base64

/**
 * Moves the Steam Guard authenticator from the official Steam app to SMA via a verification code challenge (SMS or email).
 * IRREVERSIBLE: once Steam accepts the code it issues new secrets and the old app stops working.
 */
object TransferApi {
    private const val ERESULT_FAIL = 2
    private const val ERESULT_INVALID_PARAM = 8
    private const val SCHEME_VERSION = 2L

    suspend fun startChallenge(accessToken: String) {
        // Success is the absence of an exception; the body is empty.
        SteamHttp.callService(
            iface = "TwoFactor",
            method = "RemoveAuthenticatorViaChallengeStart",
            request = ByteArray(0),
            accessToken = accessToken,
        )
    }

    suspend fun continueChallenge(accessToken: String, smsCode: String): SteamAccountDraft {
        val request = ProtoWriter()
            .string(1, smsCode)
            .bool(2, true)
            .varint(3, SCHEME_VERSION)
            .toByteArray()

        val response = try {
            SteamHttp.callService(
                iface = "TwoFactor",
                method = "RemoveAuthenticatorViaChallengeContinue",
                request = request,
                accessToken = accessToken,
            )
        } catch (e: SteamApiException) {
            // 88/89 = code mismatch: also a wrong or expired verification code.
            if (e.eresult == ERESULT_FAIL || e.eresult == ERESULT_INVALID_PARAM || e.eresult == 88 || e.eresult == 89) {
                throw TransferException("Wrong or expired code from SMS or email (EResult ${e.eresult})", sent = false, retryable = true)
            }
            throw TransferException(e.userMessage(), sent = false)
        } catch (e: NeedsSignInException) {
            throw TransferException("Sign-in expired, start again", sent = false)
        } catch (e: IOException) {
            // Only a failure to connect guarantees the request never left the device.
            val connectFailure = e is UnknownHostException || e is ConnectException
            throw TransferException(
                "Network error: ${e.userMessage()}",
                sent = !connectFailure,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Malformed response after Steam processed the request.
            throw TransferException("Unexpected response: ${e.message ?: "unknown"}", sent = true)
        }

        return parseReplacement(response)
    }

    /** Parses the Continue response: {1 success, 2 replacement_token}. Throws TransferException if no usable secrets. */
    internal fun parseReplacement(msg: ProtoMessage): SteamAccountDraft {
        val token = msg.message(2)
        if (msg.bool(1) != true || token == null) {
            throw TransferException("Steam did not return new secrets", sent = true)
        }
        val sharedSecret = token.bytes(1)
        val identitySecret = token.bytes(8)
        if (sharedSecret == null || sharedSecret.isEmpty() || identitySecret == null || identitySecret.isEmpty()) {
            throw TransferException("Steam did not return new secrets", sent = true)
        }
        val steamId = token.fixed64(12) ?: 0L
        if (steamId == 0L) {
            throw TransferException("Steam did not return new secrets", sent = true)
        }
        val enc = Base64.getEncoder()
        val serial = token.fixed64(2)
        val serverTime = token.varint(5)
        val status = token.varint(10)
        return SteamAccountDraft(
            sharedSecret = enc.encodeToString(sharedSecret),
            identitySecret = enc.encodeToString(identitySecret),
            secret1 = token.bytes(9)?.takeIf { it.isNotEmpty() }?.let { enc.encodeToString(it) },
            revocationCode = token.string(3).orEmpty(),
            serialNumber = if (serial == null) "" else java.lang.Long.toUnsignedString(serial),
            uri = token.string(4).orEmpty(),
            serverTime = if (serverTime == null) "" else java.lang.Long.toUnsignedString(serverTime),
            accountName = token.string(6).orEmpty(),
            tokenGid = token.string(7).orEmpty(),
            status = if (status == null) "" else status.toInt().toString(),
            steamId = java.lang.Long.toUnsignedString(steamId),
        )
    }
}

/** New secrets returned by Steam. All values are strings; empty means absent. */
data class SteamAccountDraft(
    val sharedSecret: String,
    val identitySecret: String,
    val secret1: String?,
    val revocationCode: String,
    val serialNumber: String,
    val uri: String,
    val serverTime: String,
    val accountName: String,
    val tokenGid: String,
    val status: String,
    val steamId: String,
) {
    fun toAccount(refreshToken: String?): SteamAccount = SteamAccount(
        accountName = accountName,
        steamId = steamId,
        sharedSecret = sharedSecret,
        identitySecret = identitySecret,
        revocationCode = revocationCode.ifEmpty { null },
        serialNumber = serialNumber.ifEmpty { null },
        tokenGid = tokenGid.ifEmpty { null },
        uri = uri.ifEmpty { null },
        secret1 = secret1?.ifEmpty { null },
        deviceId = ConfirmationKey.deviceId(steamId),
        serverTime = serverTime.ifEmpty { null },
        status = status.toIntOrNull(),
        fullyEnrolled = true,
        refreshToken = refreshToken,
    )
}

/**
 * @property sent true when the request may have reached Steam; such a failure must not be retried blindly.
 * @property retryable true when the user may re-enter the code and send again.
 */
class TransferException(
    message: String,
    val sent: Boolean,
    val retryable: Boolean = false,
) : Exception(message)
