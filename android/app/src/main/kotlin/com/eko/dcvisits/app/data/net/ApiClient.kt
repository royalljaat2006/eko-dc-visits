package com.eko.dcvisits.app.data.net

import com.eko.dcvisits.app.BuildConfig
import com.eko.dcvisits.app.di.ServiceLocator
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.Authenticator
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response as OkResponse
import okhttp3.Route
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Response
import retrofit2.Retrofit
import java.util.concurrent.TimeUnit

/**
 * The single network entry point (mirrors web/src/api/client.ts — "the only
 * module allowed to perform network I/O"). Everything else calls repositories,
 * never Retrofit or OkHttp directly.
 */
object ApiClient {

    val json: Json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = true
    }

    val api: C2Api by lazy { build(BuildConfig.API_BASE_URL) }

    fun build(baseUrl: String): C2Api {
        val logging = HttpLoggingInterceptor().apply {
            level = if (BuildConfig.DEBUG) HttpLoggingInterceptor.Level.BASIC
            else HttpLoggingInterceptor.Level.NONE
        }
        val client = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .addInterceptor(logging)
            .authenticator(SessionAuthenticator)
            .build()
        return Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(C2Api::class.java)
    }
}

/** Bearer header value from a raw access token. */
fun bearer(accessToken: String): String = "Bearer $accessToken"

/**
 * On a 401, silently exchange the refresh token for a fresh access token and
 * replay the request once (mirrors web/src/api/client.ts's re-login, but
 * without kicking the DC out). Recursion is impossible: the refresh call
 * itself is skipped, and a second 401 on the same request gives up.
 */
private object SessionAuthenticator : Authenticator {
    override fun authenticate(route: Route?, response: OkResponse): Request? {
        if (response.request.url.encodedPath.endsWith("/auth/token/refresh")) return null
        if (priorResponseCount(response) >= 2) return null
        val fresh = runBlocking { ServiceLocator.authRepository.tryRefresh() } ?: return null
        return response.request.newBuilder()
            .header("Authorization", bearer(fresh))
            .build()
    }

    private fun priorResponseCount(response: OkResponse): Int {
        var count = 1
        var prior = response.priorResponse
        while (prior != null) {
            count++
            prior = prior.priorResponse
        }
        return count
    }
}

/**
 * Uniform failure type. [networkFailure] = the request never reached the server
 * (offline / backend down) — the field flows treat that as "queued, will sync",
 * never as an error the DC must act on.
 */
class ApiException(
    val status: Int,
    override val message: String,
    val networkFailure: Boolean = false,
) : Exception(message)

/** Unwraps a Retrofit [Response], turning problem+json bodies into [ApiException]. */
fun <T> Response<T>.bodyOrThrow(): T {
    if (isSuccessful) {
        return body() ?: throw ApiException(status = code(), message = "Empty response body")
    }
    val problem = try {
        errorBody()?.string()?.let { ApiClient.json.decodeFromString<ProblemDto>(it) }
    } catch (_: Exception) {
        null
    }
    throw ApiException(
        status = code(),
        message = problem?.let { "${it.title}${it.detail?.let { d -> ": $d" } ?: ""}" }
            ?: "HTTP ${code()} ${message()}",
    )
}
