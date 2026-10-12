package com.quantaedge.android.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Persists backend HttpOnly cookies encrypted with an Android Keystore-held AES key. */
class SecureCookieJar(context: Context) : CookieJar {
    private val preferences = context.getSharedPreferences("qe_secure_cookie_state", Context.MODE_PRIVATE)
    private val lock = Any()
    private val alias = "quantaedge_session_cookie_key"

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        synchronized(lock) {
            val all = readCookies().toMutableList()
            cookies.forEach { incoming ->
                all.removeAll { it.name == incoming.name && it.domain == incoming.domain && it.path == incoming.path }
                if (incoming.expiresAt > System.currentTimeMillis()) all.add(incoming)
            }
            persistCookies(all)
        }
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> = synchronized(lock) {
        readCookies().filter { it.expiresAt > System.currentTimeMillis() && it.matches(url) }
    }

    fun clear() { synchronized(lock) { preferences.edit().remove("encrypted").apply() } }

    private fun persistCookies(cookies: List<Cookie>) {
        val json = JSONArray()
        cookies.forEach { cookie ->
            json.put(JSONObject().put("name", cookie.name).put("value", cookie.value)
                .put("domain", cookie.domain).put("path", cookie.path).put("expiresAt", cookie.expiresAt)
                .put("secure", cookie.secure).put("httpOnly", cookie.httpOnly).put("hostOnly", cookie.hostOnly))
        }
        val encrypted = encrypt(json.toString().toByteArray(Charsets.UTF_8))
        preferences.edit().putString("encrypted", Base64.encodeToString(encrypted, Base64.NO_WRAP)).apply()
    }

    private fun readCookies(): List<Cookie> {
        val stored = preferences.getString("encrypted", null) ?: return emptyList()
        return try {
            val array = JSONArray(decrypt(Base64.decode(stored, Base64.NO_WRAP)).toString(Charsets.UTF_8))
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    val name = item.optString("name")
                    val value = item.optString("value")
                    val domain = item.optString("domain")
                    if (name.isBlank() || domain.isBlank()) continue
                    val builder = Cookie.Builder().name(name).value(value)
                        .path(item.optString("path", "/"))
                        .expiresAt(item.optLong("expiresAt", System.currentTimeMillis() + 60_000L))
                    if (item.optBoolean("hostOnly")) builder.hostOnlyDomain(domain) else builder.domain(domain)
                    if (item.optBoolean("secure")) builder.secure()
                    if (item.optBoolean("httpOnly")) builder.httpOnly()
                    runCatching { builder.build() }.getOrNull()?.let(::add)
                }
            }
        } catch (_: Exception) { emptyList() }
    }

    private fun encrypt(clear: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        return cipher.iv + cipher.doFinal(clear)
    }

    private fun decrypt(value: ByteArray): ByteArray {
        require(value.size > 12) { "Encrypted cookie state is incomplete." }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, value.copyOfRange(0, 12)))
        return cipher.doFinal(value.copyOfRange(12, value.size))
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true).build())
        return generator.generateKey()
    }
}
