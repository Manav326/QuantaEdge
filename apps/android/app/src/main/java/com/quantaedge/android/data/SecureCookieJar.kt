package com.quantaedge.android.data

import android.app.backup.BackupManager
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

/**
 * Normal cookie state is encrypted with Android Keystore. A dedicated recovery preference file
 * contains only the current student OR parent refresh cookie and any still-valid access cookie.
 * It is the only shared-preference file allowed into Android Auto Backup. Protected resources, offline attempts, and profile data remain
 * excluded. The backend revokes these credentials on explicit logout and enforces absolute expiry.
 */
class SecureCookieJar(context: Context) : CookieJar {
    private val preferences = context.getSharedPreferences("qe_secure_cookie_state", Context.MODE_PRIVATE)
    private val recoveryPreferences = context.getSharedPreferences("qe_session_restore", Context.MODE_PRIVATE)
    private val backupManager = BackupManager(context.applicationContext)
    private val lock = Any()

    @Volatile
    private var activeWorkspace =
        normalizeWorkspace(recoveryPreferences.getString(KEY_WORKSPACE, null)) ?: "STUDENT"

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        synchronized(lock) {
            val all = currentCookies().toMutableList()
            cookies.forEach { incoming ->
                all.removeAll { it.name == incoming.name && it.domain == incoming.domain && it.path == incoming.path }
                if (incoming.expiresAt > System.currentTimeMillis()) all.add(incoming)
            }
            workspaceForCookies(cookies)?.let { activeWorkspace = it }
            persistCookies(all)
            persistRecoverySnapshot(all, activeWorkspace)
        }
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> = synchronized(lock) {
        val encrypted = readEncryptedCookies()
        val all = if (encrypted.isNotEmpty()) {
            encrypted
        } else {
            readRecoveryCookies().also { restored ->
                if (restored.isNotEmpty()) persistCookies(restored)
            }
        }
        all.filter { it.expiresAt > System.currentTimeMillis() && it.matches(url) }
    }

    fun workspace(): String = synchronized(lock) {
        normalizeWorkspace(recoveryPreferences.getString(KEY_WORKSPACE, null))?.let {
            activeWorkspace = it
        } ?: workspaceForCookies(currentCookies())?.let {
            activeWorkspace = it
        }
        activeWorkspace
    }

    fun setWorkspace(workspace: String) {
        synchronized(lock) {
            normalizeWorkspace(workspace)?.let { activeWorkspace = it }
        }
    }

    /** Migrate existing installs into the narrowly allow-listed backup snapshot. */
    fun synchronizeRecoverySnapshot() {
        synchronized(lock) {
            val all = currentCookies()
            val workspace = normalizeWorkspace(recoveryPreferences.getString(KEY_WORKSPACE, null))
                ?: workspaceForCookies(all)
                ?: activeWorkspace
            if (all.any { it.expiresAt > System.currentTimeMillis() && it.name in recoveryCookieNames(workspace) }) {
                activeWorkspace = workspace
                persistRecoverySnapshot(all, workspace)
            } else if (all.isNotEmpty() && all.none { it.expiresAt > System.currentTimeMillis() }) {
                recoveryPreferences.edit().clear().commit()
                backupManager.dataChanged()
            }
        }
    }

    fun clear() = synchronized(lock) {
        preferences.edit().clear().commit()
        recoveryPreferences.edit().clear().commit()
        backupManager.dataChanged()
        activeWorkspace = "STUDENT"
    }

    private fun currentCookies(): List<Cookie> {
        val encrypted = readEncryptedCookies()
        return if (encrypted.isNotEmpty()) encrypted else readRecoveryCookies()
    }

    private fun readEncryptedCookies(): List<Cookie> {
        val stored = preferences.getString(KEY_ENCRYPTED, null) ?: return emptyList()
        return try {
            parseCookieArray(JSONArray(decrypt(Base64.decode(stored, Base64.NO_WRAP)).toString(Charsets.UTF_8)))
        } catch (_: Exception) {
            // Android Keystore keys are not restored after uninstall; use the scoped OS-restored snapshot.
            emptyList()
        }
    }

    private fun readRecoveryCookies(): List<Cookie> {
        val stored = recoveryPreferences.getString(KEY_RECOVERY_COOKIES, null) ?: return emptyList()
        return try {
            val workspace = normalizeWorkspace(recoveryPreferences.getString(KEY_WORKSPACE, null)) ?: return emptyList()
            parseCookieArray(JSONArray(stored)).filter { it.name in recoveryCookieNames(workspace) }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun parseCookieArray(array: JSONArray): List<Cookie> = buildList {
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val name = item.optString("name")
            val value = item.optString("value")
            val domain = item.optString("domain")
            if (name.isBlank() || value.isBlank() || domain.isBlank()) continue
            val builder = Cookie.Builder().name(name).value(value)
                .path(item.optString("path", "/"))
                .expiresAt(item.optLong("expiresAt", System.currentTimeMillis() + 60_000L))
            if (item.optBoolean("hostOnly")) builder.hostOnlyDomain(domain) else builder.domain(domain)
            if (item.optBoolean("secure")) builder.secure()
            if (item.optBoolean("httpOnly")) builder.httpOnly()
            runCatching { builder.build() }.getOrNull()?.let(::add)
        }
    }

    private fun persistCookies(cookies: List<Cookie>) {
        val encrypted = encrypt(cookieArray(cookies).toString().toByteArray(Charsets.UTF_8))
        preferences.edit().putString(KEY_ENCRYPTED, Base64.encodeToString(encrypted, Base64.NO_WRAP)).apply()
    }

    private fun persistRecoverySnapshot(cookies: List<Cookie>, workspace: String) {
        val allowedNames = recoveryCookieNames(workspace)
        val selected = cookies.filter {
            it.name in allowedNames && it.expiresAt > System.currentTimeMillis()
        }
        // The access cookie expires quickly; the unexpired refresh cookie alone can recover a
        // session by refreshing /auth/me after reinstall. Never delete it just because access expired.
        val hasRefresh = selected.any { it.name in setOf("QE_PARENT_REFRESH", "QE_STUDENT_REFRESH") }
        if (!hasRefresh) {
            recoveryPreferences.edit().clear().commit()
            backupManager.dataChanged()
            return
        }
        val recoveryPayload = cookieArray(selected).toString()
        val changed = recoveryPreferences.getString(KEY_WORKSPACE, null) != workspace ||
            recoveryPreferences.getString(KEY_RECOVERY_COOKIES, null) != recoveryPayload
        recoveryPreferences.edit()
            .putString(KEY_WORKSPACE, workspace)
            .putString(KEY_RECOVERY_COOKIES, recoveryPayload)
            .apply()
        if (changed) backupManager.dataChanged()
    }

    private fun cookieArray(cookies: List<Cookie>): JSONArray {
        val json = JSONArray()
        cookies.forEach { cookie ->
            json.put(JSONObject().put("name", cookie.name).put("value", cookie.value)
                .put("domain", cookie.domain).put("path", cookie.path).put("expiresAt", cookie.expiresAt)
                .put("secure", cookie.secure).put("httpOnly", cookie.httpOnly).put("hostOnly", cookie.hostOnly))
        }
        return json
    }

    private fun workspaceForCookies(cookies: List<Cookie>): String? =
        cookies.asReversed().firstNotNullOfOrNull { cookie ->
            when (cookie.name) {
                "QE_PARENT_SESSION", "QE_PARENT_REFRESH" -> "PARENT"
                "QE_STUDENT_SESSION", "QE_STUDENT_REFRESH" -> "STUDENT"
                else -> null
            }
        }

    private fun recoveryCookieNames(workspace: String): Set<String> = when (workspace) {
        "PARENT" -> setOf("QE_PARENT_SESSION", "QE_PARENT_REFRESH")
        "STUDENT" -> setOf("QE_STUDENT_SESSION", "QE_STUDENT_REFRESH")
        else -> emptySet()
    }

    private fun normalizeWorkspace(value: String?): String? =
        value?.trim()?.uppercase()?.takeIf { it == "PARENT" || it == "STUDENT" || it == "LEARNING" }

    private fun encrypt(clear: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        return cipher.iv + cipher.doFinal(clear)
    }

    private fun decrypt(value: ByteArray): ByteArray {
        require(value.size > GCM_IV_LENGTH_BYTES) { "Encrypted cookie state is incomplete." }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(),
            GCMParameterSpec(GCM_TAG_LENGTH_BITS, value.copyOfRange(0, GCM_IV_LENGTH_BYTES)))
        return cipher.doFinal(value.copyOfRange(GCM_IV_LENGTH_BYTES, value.size))
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(KeyGenParameterSpec.Builder(
            KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true).setKeySize(256).build())
        return generator.generateKey()
    }

    companion object {
        private const val KEY_ENCRYPTED = "encrypted"
        private const val KEY_WORKSPACE = "workspace"
        private const val KEY_RECOVERY_COOKIES = "session_cookies"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_IV_LENGTH_BYTES = 12
        private const val GCM_TAG_LENGTH_BITS = 128
        private const val KEY_ALIAS = "quantaedge_session_cookie_key"
    }
}
