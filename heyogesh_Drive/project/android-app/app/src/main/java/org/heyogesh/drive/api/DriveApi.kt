package org.heyogesh.drive.api

import android.content.Context
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.heyogesh.drive.BuildConfig
import org.heyogesh.drive.data.SessionStore
import java.io.IOException
import java.time.Instant
import java.util.concurrent.TimeUnit

/** Typed API boundary. Activities never construct URLs or parse HTTP errors. */
class DriveApi(context: Context) {
    private val sessionStore = SessionStore(context.applicationContext)
    private val gson = Gson()
    private val baseUrl: HttpUrl = BuildConfig.STORAGE_BASE_URL.toHttpUrl().also {
        check(it.isHttps) { "Heyogesh Drive requires an HTTPS storage endpoint." }
    }
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS) // media downloads may be long-running
        .callTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    suspend fun login(password: String): LoginResponse = withContext(Dispatchers.IO) {
        val body = JsonObject().apply { addProperty("password", password) }
        val request = Request.Builder()
            .url(endpoint("api", "v1", "auth", "login"))
            .post(gson.toJson(body).toRequestBody(JSON))
            .build()
        executeJson(request)
    }

    suspend fun folder(path: String, offset: Int = 0): FolderResponse = withContext(Dispatchers.IO) {
        val url = endpoint("api", "v1", "folders").newBuilder()
            .addQueryParameter("path", path)
            .addQueryParameter("offset", offset.toString())
            .addQueryParameter("limit", "200")
            .build()
        executeJson(authenticated(url).get().build())
    }

    suspend fun open(path: String): OpenResponse = withContext(Dispatchers.IO) {
        val url = endpoint("api", "v1", "open").newBuilder().addQueryParameter("path", path).build()
        executeJson(authenticated(url).get().build())
    }

    suspend fun createArchive(paths: List<String>): ArchiveStatus = withContext(Dispatchers.IO) {
        val body = JsonObject().apply {
            add("paths", gson.toJsonTree(paths))
        }
        executeJson(authenticated(endpoint("api", "v1", "archives"))
            .post(gson.toJson(body).toRequestBody(JSON)).build())
    }

    suspend fun archiveStatus(id: String): ArchiveStatus = withContext(Dispatchers.IO) {
        executeJson(authenticated(endpoint("api", "v1", "archives", id)).get().build())
    }

    fun downloadUrl(path: String): String = endpoint("api", "v1", "download").newBuilder()
        .addQueryParameter("path", path).build().toString()

    fun absoluteApiPath(relativePath: String): String {
        require(relativePath.startsWith("/api/")) { "Unexpected API path from server." }
        return baseUrl.newBuilder().encodedPath(relativePath).build().toString()
    }

    fun currentToken(): String = sessionStore.getValid()?.token
        ?: throw ApiException(401, "UNAUTHENTICATED", "Sign in again to continue.")

    fun sessionStore(): SessionStore = sessionStore

    private fun endpoint(vararg segments: String): HttpUrl {
        val builder = baseUrl.newBuilder()
        segments.forEach(builder::addPathSegment)
        return builder.build()
    }

    private fun authenticated(url: HttpUrl): Request.Builder = Request.Builder()
        .url(url)
        .header("Authorization", "Bearer ${currentToken()}")

    /**
     * Mobile DNS and a just-reconnected tunnel can fail briefly even while the
     * storage endpoint is healthy. Retry only transport failures: HTTP errors
     * (including a wrong password) are returned to the user immediately.
     */
    private inline fun <reified T> executeJson(request: Request): T {
        var lastError: IOException? = null
        repeat(CONNECTION_ATTEMPTS) { attempt ->
            try {
                return executeJsonOnce(request)
            } catch (error: IOException) {
                lastError = error
                if (attempt < CONNECTION_ATTEMPTS - 1) {
                    Thread.sleep(CONNECTION_RETRY_DELAY_MS * (attempt + 1))
                }
            }
        }
        throw lastError ?: IOException("Unable to reach the storage server.")
    }

    private inline fun <reified T> executeJsonOnce(request: Request): T = client.newCall(request).execute().use { response ->
        val text = response.body?.string().orEmpty()
        if (!response.isSuccessful) throw asApiException(response, text)
        try {
            gson.fromJson<T>(text, object : TypeToken<T>() {}.type)
        } catch (error: Exception) {
            throw ApiException(502, "INVALID_RESPONSE", "The storage server returned an invalid response.")
        }
    }

    private fun asApiException(response: Response, text: String): ApiException {
        val envelope = runCatching { gson.fromJson(text, ErrorEnvelope::class.java) }.getOrNull()
        return ApiException(
            response.code,
            envelope?.error?.code ?: "HTTP_${response.code}",
            envelope?.error?.message ?: "The storage server returned HTTP ${response.code}.",
        )
    }

    companion object {
        private const val CONNECTION_ATTEMPTS = 3
        private const val CONNECTION_RETRY_DELAY_MS = 750L
        val JSON = "application/json; charset=utf-8".toMediaType()
        fun expiresAtMillis(value: String): Long = Instant.parse(value).toEpochMilli()
        fun isConnectivityError(error: Throwable): Boolean = error is IOException
    }
}
