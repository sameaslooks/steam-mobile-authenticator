package io.github.sma.core.steam

import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/** Friendly texts for Steam EResult codes. */
object EResults {
    private val TEXTS: Map<Int, String> = mapOf(
        2 to "Steam reported a generic failure. Try again.",
        3 to "No connection to Steam.",
        5 to "Wrong password.",
        6 to "Logged in elsewhere.",
        8 to "Steam rejected the request (invalid parameter).",
        9 to "Not found or already handled.",
        10 to "Steam is busy. Try again shortly.",
        11 to "Steam says the request is in an invalid state.",
        15 to "Access denied.",
        16 to "Steam timed out. Try again.",
        20 to "Steam service unavailable. Try again later.",
        25 to "Limit exceeded. Try again later.",
        27 to "Expired. Start again.",
        29 to "Already in progress.",
        42 to "No match: the request expired or was already handled.",
        63 to "Sign-in denied by Steam.",
        65 to "Wrong or expired code.",
        66 to "Sign-in denied; Steam could not send an email.",
        84 to "Too many attempts. Wait a while and try again.",
        85 to "Steam Guard code required.",
        87 to "Too many sign-in attempts. Wait and try again.",
        88 to "Wrong or expired Steam Guard code. Codes change every 30 seconds.",
        89 to "Wrong verification code from SMS or email.",
        92 to "No mobile authenticator on this account.",
        93 to "Phone time is not in sync with Steam. Sync time and retry.",
        94 to "Steam could not send the SMS.",
        95 to "Account limit exceeded.",
        96 to "Too many account changes recently. Try again later.",
        97 to "Too many phone changes recently. Try again later.",
        99 to "Steam could not send the email.",
        101 to "Steam requires a captcha; sign in on the Steam website first.",
        105 to "Your IP address is blocked by Steam.",
        108 to "Too many pending requests. Try again later.",
    )

    fun describe(code: Int): String? = TEXTS[code]
}

private val HTTP_CODE_REGEX = Regex("^HTTP (\\d+)")

/** Text suitable for showing to the user for any error raised by the Steam layer. */
fun Throwable.userMessage(): String = when (this) {
    is SteamApiException ->
        (EResults.describe(eresult) ?: message?.takeIf { it.isNotBlank() } ?: "Steam error") + " (EResult $eresult)"
    is NeedsSignInException -> "Sign-in expired. Sign in again."
    is UnknownHostException, is ConnectException, is NoRouteToHostException -> "No internet connection."
    is SocketTimeoutException -> "Steam did not respond in time. Try again."
    is SSLException -> "Secure connection to Steam failed. Check the phone's date and time."
    is IOException -> {
        val code = message?.let { HTTP_CODE_REGEX.find(it)?.groupValues?.get(1)?.toIntOrNull() }
        when {
            code == null -> message ?: "Unexpected error"
            code == 429 -> "Too many requests to Steam. Wait a minute."
            code in 500..599 -> "Steam servers are having problems. Try again later."
            code == 401 || code == 403 -> "Sign-in expired. Sign in again."
            else -> "Steam returned HTTP $code."
        }
    }
    is TransferException -> message ?: "Unexpected error"
    is EnrollException -> message ?: "Unexpected error"
    else -> message ?: "Unexpected error"
}
