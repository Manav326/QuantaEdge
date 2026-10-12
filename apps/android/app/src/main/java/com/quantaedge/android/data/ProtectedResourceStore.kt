package com.quantaedge.android.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** App-private encrypted cache for server-rendered protected textbook pages. */
class ProtectedResourceStore(context: Context, ownerKey: String = "guest") {
    private val ownerNamespace = MessageDigest.getInstance("SHA-256")
        .digest(ownerKey.ifBlank { "guest" }.toByteArray(Charsets.UTF_8))
        .take(12).joinToString("") { "%02x".format(it) }
    private val root = File(context.applicationContext.filesDir, "protected_learning_resources/" + ownerNamespace).apply { mkdirs() }
    private val preferences = context.applicationContext.getSharedPreferences("qe_downloaded_resource_manifest_" + ownerNamespace, Context.MODE_PRIVATE)
    private val alias = "qe_resource_key_" + ownerNamespace

    fun saveDocument(document: JSONObject) {
        val id = document.optLongAny("document_id", "id")
        if (id <= 0L) return
        val current = downloadedDocuments().toMutableList()
        current.removeAll { it.optLongAny("document_id", "id") == id }
        current.add(JSONObject(document.toString()))
        val array = JSONArray()
        current.forEach(array::put)
        preferences.edit().putString("documents", array.toString()).apply()
    }

    fun downloadedDocuments(): List<JSONObject> {
        val stored = preferences.getString("documents", null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(stored)
            buildList {
                for (i in 0 until array.length()) array.optJSONObject(i)?.let { add(JSONObject(it.toString())) }
            }
        }.getOrDefault(emptyList())
    }

    /**
     * Encrypt to a temporary file and rename only after the authenticated ciphertext is complete.
     * A process kill or full disk during a download must not leave a partial file that looks cached.
     */
    fun savePage(documentId: Long, page: Int, bytes: ByteArray) {
        require(documentId > 0L && page > 0)
        val target = File(root, pageName(documentId, page))
        val temporary = File(root, target.name + ".tmp")
        try {
            temporary.writeBytes(encrypt(bytes))
            if (!temporary.renameTo(target)) {
                // Some filesystems cannot rename over an existing target; replace only after the
                // complete temporary ciphertext has been written successfully.
                if (target.exists() && !target.delete()) {
                    throw IOException("Unable to replace the cached textbook page.")
                }
                if (!temporary.renameTo(target)) {
                    throw IOException("Unable to safely persist the encrypted textbook page.")
                }
            }
        } catch (failure: Exception) {
            temporary.delete()
            throw failure
        }
    }

    /** Fast cache-presence check for UI rendering; does not decrypt large page images. */
    fun hasPage(documentId: Long, page: Int): Boolean {
        val file = File(root, pageName(documentId, page))
        return file.isFile && file.length() > 28L
    }

    /** Verifies AES-GCM integrity before a worker skips a page during an offline download. */
    fun hasValidPage(documentId: Long, page: Int): Boolean {
        val file = File(root, pageName(documentId, page))
        if (!file.isFile) return false
        return try {
            decrypt(file.readBytes())
            true
        } catch (_: Exception) {
            // Remove a damaged or legacy partial cache entry so WorkManager can download it again.
            file.delete()
            false
        }
    }

    fun readPage(documentId: Long, page: Int): ByteArray? {
        val file = File(root, pageName(documentId, page))
        if (!file.isFile) return null
        return try {
            decrypt(file.readBytes())
        } catch (_: Exception) {
            file.delete()
            null
        }
    }

    fun cachedPageCount(documentId: Long, pageCount: Int): Int =
        (1..pageCount.coerceAtLeast(0)).count { hasPage(documentId, it) }

    /** Deletes only this account's encrypted local copy; it never touches server content. */
    fun removeOfflineCopy(documentId: Long): Int {
        require(documentId > 0L)
        val prefix = "doc_" + documentId + "_page_"
        val cachedFiles = root.listFiles()
            ?.filter { it.isFile && it.name.startsWith(prefix) && it.name.endsWith(".enc") }
            .orEmpty()
        var deleted = 0
        cachedFiles.forEach { if (it.delete()) deleted++ }

        val remainingDocs = downloadedDocuments().filter { it.optLongAny("document_id", "id") != documentId }
        val manifest = JSONArray()
        remainingDocs.forEach(manifest::put)
        preferences.edit().putString("documents", manifest.toString()).apply()

        val listedFiles = root.listFiles()
        val noEncryptedFilesRemain = listedFiles != null &&
            listedFiles.none { it.isFile && it.name.endsWith(".enc") }
        if (noEncryptedFilesRemain) {
            runCatching {
                val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
                if (keyStore.containsAlias(alias)) keyStore.deleteEntry(alias)
            }
        }
        return deleted
    }

    private fun pageName(id: Long, page: Int): String = "doc_" + id + "_page_" + page + ".enc"
    private fun encrypt(clear: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        return cipher.iv + cipher.doFinal(clear)
    }
    private fun decrypt(value: ByteArray): ByteArray {
        require(value.size > 12) { "Encrypted resource page is incomplete." }
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

fun JSONObject.optLongAny(vararg keys: String): Long {
    for (key in keys) {
        if (!has(key) || isNull(key)) continue
        val value = opt(key)
        if (value is Number) return value.toLong()
        value?.toString()?.toLongOrNull()?.let { return it }
    }
    return 0L
}
