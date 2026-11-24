package io.github.sma.core.steam

import io.github.sma.core.proto.ProtoMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.Base64
import java.util.concurrent.TimeUnit

class SteamApiException(val eresult: Int, message: String) : Exception(message)

class NeedsSignInException(message: String = "Sign in required") : Exception(message)

object SteamHttp {
    private const val USER_AGENT = "okhttp/4.9.2"
    private const val COOKIE = "mobileClient=android; mobileClientVersion=777777 3.10.3"
    private val ALLOWED_HOSTS = setOf(
        "api.steampowered.com",
        "steamcommunity.com",
        "store.steampowered.com",
        "login.steampowered.com",
    )

    // Network interceptor so that redirect targets are checked as well.
    private val hostGuard = Interceptor { chain ->
        val request = chain.request()
        if (!request.url.isHttps || request.url.host !in ALLOWED_HOSTS) {
            throw IOException("Blocked host")
        }
        val builder = request.newBuilder()
        if (request.header("User-Agent") == null) {
            builder.header("User-Agent", USER_AGENT)
        }
        chain.proceed(builder.build())
    }

    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .addNetworkInterceptor(hostGuard)
        .build()

    suspend fun callService(
        iface: String,
        method: String,
        request: ByteArray,
        accessToken: String? = null,
        get: Boolean = false,
    ): ProtoMessage = withContext(Dispatchers.IO) {
        val encoded = Base64.getEncoder().encodeToString(request)
        val url = "https://api.steampowered.com/I${iface}Service/$method/v1/".toHttpUrl()
            .newBuilder()
            .apply {
                if (accessToken != null) addQueryParameter("access_token", accessToken)
                if (get) {
                    addQueryParameter("input_protobuf_encoded", encoded)
                    addQueryParameter("origin", "SteamMobile")
                }
            }
            .build()

        val builder = Request.Builder().url(url).header("Cookie", COOKIE)
        if (get) {
            builder.get()
        } else {
            builder.post(
                MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart("input_protobuf_encoded", encoded)
                    .build()
            )
        }

        client.newCall(builder.build()).execute().use { response ->
            val code = response.code
            if (code == 401 || code == 403) throw NeedsSignInException()
            if (!response.isSuccessful) throw IOException("HTTP $code")

            val eresult = response.header("x-eresult")?.trim()?.toIntOrNull() ?: 1
            if (eresult != 1) {
                throw SteamApiException(eresult, response.header("x-error_message") ?: "EResult $eresult")
            }
            ProtoMessage.parse(response.body?.bytes() ?: ByteArray(0))
        }
    }
}
