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

    fun initialize(context: Context) {
        if (client != null) return
        synchronized(this) {
            if (client != null) return
            val jar = SecureCookieJar(context.applicationContext)
            cookies = jar
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
    suspend fun putObject(path: String, body: JSONObject): JSONObject =
        JSONObject(request(path, "PUT", body.toString()).toString(StandardCharsets.UTF_8))
    suspend fun patchObject(path: String, body: JSONObject): JSONObject =
        JSONObject(request(path, "PATCH", body.toString()).toString(StandardCharsets.UTF_8))
    suspend fun getBytes(path: String): ByteArray = request(path, "GET", null)
    fun encodeQuery(value: String): String = java.net.URLEncoder.encode(value, StandardCharsets.UTF_8.name())

    fun clearSession() { cookies?.clear() }

    private suspend fun request(path: String, method: String, body: String?): ByteArray =
        withContext(Dispatchers.IO) {
            val currentClient = client ?: throw IOException("API client is not initialized.")
            val url = BuildConfig.API_BASE_URL.trimEnd('/') + "/" + path.trimStart('/')
            val builder = Request.Builder().url(url).header("Accept", "application/json, image/png")
            val requestBody = body?.toRequestBody(jsonType)
            when (method) {
                "GET" -> builder.get()
                "POST" -> builder.post(requestBody ?: "{}".toRequestBody(jsonType))
                "PUT" -> builder.put(requestBody ?: "{}".toRequestBody(jsonType))
                "PATCH" -> builder.patch(requestBody ?: "{}".toRequestBody(jsonType))
                else -> throw IllegalArgumentException("Unsupported HTTP method.")
            }
            currentClient.newCall(builder.build()).execute().use { response ->
                val bytes = response.body?.bytes() ?: ByteArray(0)
                if (!response.isSuccessful) {
                    val message = try {
                        val parsed = JSONObject(bytes.toString(StandardCharsets.UTF_8))
                        parsed.optString("message").ifBlank {
                            parsed.optString("error").ifBlank { "Request failed (" + response.code + ")." }
                        }
                    } catch (_: JSONException) {
                        bytes.toString(StandardCharsets.UTF_8).take(500)
                            .ifBlank { "Request failed (" + response.code + ")." }
                    }
                    throw ApiFailure(response.code, message)
                }
                bytes
            }
        }
}
