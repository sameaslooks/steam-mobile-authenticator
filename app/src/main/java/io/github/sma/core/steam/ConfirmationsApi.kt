package io.github.sma.core.steam

import io.github.sma.core.crypto.ConfirmationKey
import io.github.sma.core.crypto.Secrets
import io.github.sma.core.mafile.SteamAccount
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import java.io.IOException
import java.security.SecureRandom

object ConfirmationsApi {

    data class Confirmation(
        val id: String,
        val nonce: String,
        val type: Int,
        val typeName: String,
        val creatorId: String,
        val headline: String,
        val summary: List<String>,
        val icon: String?,
        val multi: Boolean,
        val creationTime: Long,
    )

    private const val BASE_URL = "https://steamcommunity.com/mobileconf/"
    private const val MOBILE_COOKIE = "mobileClient=android; mobileClientVersion=777777 3.10.3"
    private const val APP_TAG = "react"
    private const val TAG_LIST = "list"
    private const val TAG_ACCEPT = "accept"
    private const val TAG_REJECT = "reject"
    private const val TAG_DETAILS = "details"
    private const val OP_ALLOW = "allow"
    private const val OP_CANCEL = "cancel"

    /** Thrown inside an attempt when Steam says the session is stale; triggers one retry. */
    private class StaleSessionException : Exception("Stale session")

    suspend fun list(acc: SteamAccount): List<Confirmation> = withContext(Dispatchers.IO) {
        withSession(acc) { accessToken ->
            val deviceId = deviceIdOf(acc)
            val t = SteamTime.now()
            val key = ConfirmationKey.generate(Secrets.decode(acc.identitySecret), t, TAG_LIST)
            val url = (BASE_URL + "getlist").toHttpUrl().newBuilder()
                .addQueryParameter("p", deviceId)
                .addQueryParameter("a", acc.steamId)
                .addQueryParameter("k", key)
                .addQueryParameter("t", t.toString())
                .addQueryParameter("m", APP_TAG)
                .addQueryParameter("tag", TAG_LIST)
                .build()
            val request = Request.Builder()
                .url(url)
                .header("Cookie", cookieHeader(acc.steamId, accessToken))
                .get()
                .build()
            val root = parseStatus(execute(request))
            val conf = root["conf"] as? JsonArray ?: return@withSession emptyList()
            conf.mapNotNull { parseConfirmation(it) }
        }
    }

    /** Returns the Steam-rendered HTML describing one confirmation (mobileconf/details). */
    suspend fun details(acc: SteamAccount, confId: String): String = withContext(Dispatchers.IO) {
        withSession(acc) { accessToken ->
            val deviceId = deviceIdOf(acc)
            val t = SteamTime.now()
            val key = ConfirmationKey.generate(Secrets.decode(acc.identitySecret), t, TAG_DETAILS)
            val url = (BASE_URL + "details/" + confId).toHttpUrl().newBuilder()
                .addQueryParameter("p", deviceId)
                .addQueryParameter("a", acc.steamId)
                .addQueryParameter("k", key)
                .addQueryParameter("t", t.toString())
                .addQueryParameter("m", APP_TAG)
                .addQueryParameter("tag", TAG_DETAILS)
                .build()
            val request = Request.Builder()
                .url(url)
                .header("Cookie", cookieHeader(acc.steamId, accessToken))
                .get()
                .build()
            val root = parseStatus(execute(request), refusal = "Steam returned no details")
            root.text("html").orEmpty()
        }
    }

    suspend fun respond(acc: SteamAccount, items: List<Confirmation>, accept: Boolean) {
        if (items.isEmpty()) return
        withContext(Dispatchers.IO) {
            val tag = if (accept) TAG_ACCEPT else TAG_REJECT
            val op = if (accept) OP_ALLOW else OP_CANCEL
            withSession(acc) { accessToken ->
                val deviceId = deviceIdOf(acc)
                val t = SteamTime.now()
                val key = ConfirmationKey.generate(Secrets.decode(acc.identitySecret), t, tag)
                val form = FormBody.Builder()
                    .add("p", deviceId)
                    .add("a", acc.steamId)
                    .add("k", key)
                    .add("t", t.toString())
                    .add("m", APP_TAG)
                    .add("tag", tag)
                    .add("op", op)
                for (item in items) {
                    form.add("cid[]", item.id)
                    form.add("ck[]", item.nonce)
                }
                val request = Request.Builder()
                    .url(BASE_URL + "multiajaxop")
                    .header("Cookie", cookieHeader(acc.steamId, accessToken))
                    .post(form.build())
                    .build()
                parseStatus(execute(request))
                Unit
            }
        }
    }

    /**
     * Runs [block] with a fresh access token. If Steam reports a stale session, the cached
     * token is dropped and the block is retried once; a second failure requires sign-in.
     */
    private suspend fun <T> withSession(acc: SteamAccount, block: suspend (String) -> T): T {
        repeat(2) {
            val accessToken = SessionManager.accessToken(acc)
            try {
                return block(accessToken)
            } catch (e: StaleSessionException) {
                SessionManager.invalidate(acc.steamId)
            }
        }
        throw NeedsSignInException()
    }

    private fun deviceIdOf(acc: SteamAccount): String =
        acc.deviceId ?: ConfirmationKey.deviceId(acc.steamId)

    private fun cookieHeader(steamId: String, accessToken: String): String =
        "steamLoginSecure=${steamId}%7C%7C${accessToken}; sessionid=${randomSessionId()}; $MOBILE_COOKIE"

    private fun randomSessionId(): String {
        val bytes = ByteArray(12)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    private fun execute(request: Request): String =
        SteamHttp.client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            response.body?.string().orEmpty()
        }

    /** Validates a mobileconf response body and returns the parsed JSON object. */
    private fun parseStatus(text: String, refusal: String? = null): JsonObject {
        if (text.trimStart().startsWith("<")) throw StaleSessionException()
        val root = runCatching { Json.parseToJsonElement(text) as? JsonObject }.getOrNull()
            ?: throw IOException("Unexpected response")
        if (root.text("needauth")?.toBooleanStrictOrNull() == true) throw StaleSessionException()
        val success = root.text("success")?.toBooleanStrictOrNull() ?: false
        if (!success) throw IOException(refusal ?: root.text("message") ?: "Steam refused")
        return root
    }

    private fun parseConfirmation(element: JsonElement): Confirmation? {
        val obj = element as? JsonObject ?: return null
        val id = obj.text("id") ?: return null
        val nonce = obj.text("nonce") ?: return null
        val type = obj.text("type")?.toIntOrNull() ?: return null
        val summary = (obj["summary"] as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            ?: emptyList()
        return Confirmation(
            id = id,
            nonce = nonce,
            type = type,
            typeName = obj.text("type_name").orEmpty(),
            creatorId = obj.text("creator_id").orEmpty(),
            headline = obj.text("headline").orEmpty(),
            summary = summary,
            icon = obj.text("icon")?.takeIf { it.isNotEmpty() },
            multi = obj.text("multi")?.toBooleanStrictOrNull() ?: false,
            creationTime = obj.text("creation_time")?.toLongOrNull() ?: 0L,
        )
    }

    private fun JsonObject.text(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull
}
