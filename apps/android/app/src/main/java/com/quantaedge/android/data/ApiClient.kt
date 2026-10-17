package com.quantaedge.android.data

import android.content.Context
import com.quantaedge.android.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit

class ApiFailure(val statusCode: Int, override val message: String) : IOException(message)

object ApiClient {
    private val jsonType = "application/json; charset=utf-8".toMediaType()
    @Volatile private var client: OkHttpClient? = null
    @Volatile private var cookies: SecureCookieJar? = null
    @Volatile private var activeWorkspace: String = "STUDENT"
    private val refreshLock = Any()

    fun initialize(context: Context) {
        if (client != null) return
        synchronized(this) {
            if (client != null) return
            val jar = SecureCookieJar(context.applicationContext)
            cookies = jar
            activeWorkspace = jar.workspace()
            jar.synchronizeRecoverySnapshot()
            client = OkHttpClient.Builder().cookieJar(jar)
                .connectTimeout(20, TimeUnit.SECONDS).readTimeout(45, TimeUnit.SECONDS)
                .writeTimeout(45, TimeUnit.SECONDS).callTimeout(2, TimeUnit.MINUTES).build()
        }
    }

    suspend fun getObject(path: String): JSONObject =
        JSONObject(request(path, "GET", null).toString(StandardCharsets.UTF_8))
    suspend fun getArray(path: String): JSONArray =
        JSONArray(request(path, "GET", null).toString(StandardCharsets.UTF_8))
    suspend fun postObject(path: String, body: JSONObject = JSONObject()): JSONObject =
        JSONObject(request(path, "POST", body.toString()).toString(StandardCharsets.UTF_8))
    suspend fun postObject(path: String, body: JSONObject, additionalHeaders: Map<String, String>): JSONObject =
        JSONObject(request(path, "POST", body.toString(), additionalHeaders).toString(StandardCharsets.UTF_8))
    suspend fun putObject(path: String, body: JSONObject): JSONObject =
        JSONObject(request(path, "PUT", body.toString()).toString(StandardCharsets.UTF_8))
    suspend fun patchObject(path: String, body: JSONObject): JSONObject =
        JSONObject(request(path, "PATCH", body.toString()).toString(StandardCharsets.UTF_8))
    suspend fun deleteObject(path: String): JSONObject =
        JSONObject(request(path, "DELETE", null).toString(StandardCharsets.UTF_8))
    suspend fun getBytes(path: String): ByteArray = request(path, "GET", null)
    fun encodeQuery(value: String): String = java.net.URLEncoder.encode(value, StandardCharsets.UTF_8.name())
    fun absoluteUrl(path: String): String = BuildConfig.API_BASE_URL.trimEnd('/') + "/" + path.trimStart('/')
    fun setWorkspace(workspace: String) {
        val normalized = workspace.trim().uppercase()
        if (normalized !in setOf("PARENT", "STUDENT", "LEARNING")) return
        activeWorkspace = normalized
        cookies?.setWorkspace(normalized)
    }
    fun workspaceName(): String = currentWorkspace()
    private fun currentWorkspace(): String = activeWorkspace
    fun mediaUrl(url: String): String =
        if (url.startsWith("https://", true) || url.startsWith("http://", true)) url else absoluteUrl(url)

    suspend fun getMediaBytes(url: String): ByteArray = request(url, "GET", null)

    fun authenticatedHttpClient(): OkHttpClient =
        (client ?: throw IOException("API client is not initialized.")).newBuilder()
            .authenticator(okhttp3.Authenticator { _, response ->
                val apiHost = runCatching { java.net.URI(BuildConfig.API_BASE_URL).host }.getOrNull()
                if (apiHost.isNullOrBlank() || response.request.url.host != apiHost) return@Authenticator null
                if (responseCount(response) >= 2) return@Authenticator null
                synchronized(refreshLock) {
                    val currentClient = client ?: return@synchronized null
                    val refreshRequest = Request.Builder()
                        .url(absoluteUrl("/api/v1/auth/refresh"))
                        .post("{}".toRequestBody(jsonType))
                        .header("Accept", "application/json")
                        .header("X-QE-Workspace", currentWorkspace())
                        .header("X-QE-Client", "ANDROID")
                        .build()
                    val refreshed = currentClient.newCall(refreshRequest).execute().use { it.isSuccessful }
                    if (refreshed) response.request.newBuilder().removeHeader("Cookie").build() else null
                }
            }).build()

    private fun responseCount(response: okhttp3.Response): Int {
        var count = 1
        var previous = response.priorResponse
        while (previous != null) { count++; previous = previous.priorResponse }
        return count
    }

    private fun shouldAttemptRefresh(path: String): Boolean {
        val normalized = path.substringBefore('?').trimEnd('/')
        return normalized != "/api/v1/auth/refresh" &&
            !normalized.endsWith("/auth/request-otp") &&
            !normalized.endsWith("/auth/verify-otp") &&
            !normalized.endsWith("/auth/parent-login") &&
            !normalized.endsWith("/auth/student-login") &&
            !normalized.endsWith("/auth/learner-login") &&
            !normalized.endsWith("/auth/preview-login") &&
            !normalized.endsWith("/auth/logout")
    }

    fun clearSession() {
        cookies?.clear()
        activeWorkspace = "STUDENT"
    }

    private suspend fun request(
        path: String,
        method: String,
        body: String?,
        additionalHeaders: Map<String, String> = emptyMap()
    ): ByteArray =
        withContext(Dispatchers.IO) {
            val currentClient = client ?: throw IOException("API client is not initialized.")
            val absoluteRequestUrl = path.startsWith("https://", true) || path.startsWith("http://", true)
            val url = if (absoluteRequestUrl) path else BuildConfig.API_BASE_URL.trimEnd('/') + "/" + path.trimStart('/')
            val builder = Request.Builder().url(url).header("Accept", "application/json, application/pdf, image/png, image/jpeg, image/webp")
            if (!absoluteRequestUrl) {
                builder.header("X-QE-Workspace", currentWorkspace())
                    .header("X-QE-Client", "ANDROID")
            }
            additionalHeaders.forEach { (name, value) ->
                require(name.matches(Regex("[A-Za-z0-9-]{1,64}")) && !value.contains('\r') && !value.contains('\n')) {
                    "Invalid request header."
                }
                builder.header(name, value)
            }
            val requestBody = body?.toRequestBody(jsonType)
            when (method) {
                "GET" -> builder.get()
                "POST" -> builder.post(requestBody ?: "{}".toRequestBody(jsonType))
                "PUT" -> builder.put(requestBody ?: "{}".toRequestBody(jsonType))
                "PATCH" -> builder.patch(requestBody ?: "{}".toRequestBody(jsonType))
                "DELETE" -> builder.delete()
                else -> throw IllegalArgumentException("Unsupported HTTP method.")
            }
            var response = currentClient.newCall(builder.build()).execute()
            if (response.code == 401 && !absoluteRequestUrl && shouldAttemptRefresh(path)) {
                response.close()
                synchronized(refreshLock) {
                    val refreshRequest = Request.Builder()
                        .url(BuildConfig.API_BASE_URL.trimEnd('/') + "/api/v1/auth/refresh")
                        .post("{}".toRequestBody(jsonType))
                        .header("Accept", "application/json")
                        .header("X-QE-Workspace", currentWorkspace())
                        .header("X-QE-Client", "ANDROID")
                        .build()
                    val refreshed = currentClient.newCall(refreshRequest).execute().use { it.isSuccessful }
                    if (!refreshed) {
                        throw ApiFailure(401, "आपका सत्र समाप्त हो गया है। कृपया फिर से साइन इन करें।")
                    }
                }
                response = currentClient.newCall(builder.build()).execute()
            }
            response.use { result ->
                val bytes = result.body?.bytes() ?: ByteArray(0)
                if (!result.isSuccessful) {
                    val message = try {
                        val parsed = JSONObject(bytes.toString(StandardCharsets.UTF_8))
                        parsed.optString("detail").ifBlank {
                            parsed.optString("message").ifBlank {
                                parsed.optString("error").ifBlank { "Request failed (" + result.code + ")." }
                            }
                        }
                    } catch (_: JSONException) {
                        bytes.toString(StandardCharsets.UTF_8).take(500)
                            .ifBlank { "Request failed (" + result.code + ")." }
                    }
                    throw ApiFailure(result.code, message)
                }
                bytes
            }
        }
}
