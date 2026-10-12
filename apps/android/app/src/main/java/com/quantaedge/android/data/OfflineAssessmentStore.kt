package com.quantaedge.android.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.security.KeyGenerator
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.KeyGenerator as JcaKeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Account-scoped, app-private AES-GCM storage for explicitly approved offline test attempts.
 * It stores only the server-filtered question snapshots and learner responses, never grading keys.
 */
class OfflineAssessmentStore(context: Context, private val ownerKey: String) {
    private val appContext = context.applicationContext
    private val namespace = MessageDigest.getInstance("SHA-256")
        .digest(ownerKey.toByteArray(Charsets.UTF_8))
        .take(12).joinToString("") { "%02x".format(it) }
    private val root = File(appContext.filesDir, "offline_assessment_attempts/$namespace").apply { mkdirs() }
    private val keyAlias = "qe_offline_assessment_key_$namespace"
    private val lock = Any()

    fun saveAttempt(
        attempt: JSONObject,
        answers: Map<String, String>,
        state: String,
        catalogueItem: JSONObject? = null,
        error: String? = null
    ) {
        require(ownerKey.isNotBlank() && ownerKey != "guest") { "Offline attempts require an authenticated account." }
        require(state in setOf("DRAFT", "QUEUED", "SYNC_ERROR")) { "Invalid offline attempt state." }
        val attemptId = attempt.optLongAny("attempt_id", "attemptId", "id")
        require(attemptId > 0L) { "Offline attempt identifier is invalid." }
        synchronized(lock) {
            val previous = readRecordInternal(attemptId)
            val clean = sanitize(attempt)
            val record = JSONObject()
                .put("attemptId", attemptId)
                .put("assessmentId", attempt.optLongAny("assessment_id", "assessmentId"))
                .put("attempt", clean)
                .put("answers", JSONObject().apply { answers.forEach { (key, value) -> put(key, value) } })
                .put("state", state)
                .put("updatedAtMillis", System.currentTimeMillis())
            val retainedCatalogue = catalogueItem ?: previous?.optJSONObject("catalogueItem")
            if (retainedCatalogue != null) record.put("catalogueItem", sanitize(retainedCatalogue))
            if (!error.isNullOrBlank()) record.put("lastError", error.take(500)) else record.remove("lastError")
            writeRecord(attemptId, record)
        }
    }

    fun loadRecord(attemptId: Long): JSONObject? = synchronized(lock) {
        readRecordInternal(attemptId)?.let { JSONObject(it.toString()) }
    }

    fun recordForAttempt(attemptId: Long): JSONObject? = loadRecord(attemptId)

    fun recordForAssessment(assessmentId: Long): JSONObject? = synchronized(lock) {
        allRecordsInternal().firstOrNull {
            it.optLong("assessmentId", it.optJSONObject("attempt")?.optLongAny("assessment_id", "assessmentId") ?: 0L) == assessmentId
        }?.let { JSONObject(it.toString()) }
    }

    fun allRecords(): List<JSONObject> = synchronized(lock) {
        allRecordsInternal().map { JSONObject(it.toString()) }
    }

    fun pendingRecords(): List<JSONObject> = synchronized(lock) {
        allRecordsInternal().filter { it.optString("state") == "QUEUED" }.map { JSONObject(it.toString()) }
    }

    fun catalogueItems(): List<JSONObject> = synchronized(lock) {
        allRecordsInternal().mapNotNull { record ->
            val attempt = record.optJSONObject("attempt") ?: return@mapNotNull null
            val item = record.optJSONObject("catalogueItem")?.let { JSONObject(it.toString()) } ?: JSONObject()
            val assessmentId = record.optLong("assessmentId", attempt.optLongAny("assessment_id", "assessmentId"))
            if (assessmentId <= 0L) return@mapNotNull null
            val state = record.optString("state", "DRAFT")
            item.put("assessment_id", assessmentId)
                .put("title", item.optString("title").ifBlank { attempt.optString("assessment_title", "Offline test") })
                .put("subject_name", item.optString("subject_name").ifBlank { attempt.optString("subject_name") })
                .put("chapter_name", item.optString("chapter_name").ifBlank { attempt.optString("chapter_name") })
                .put("offline_local", true)
                .put("offline_enabled", true)
                .put("offline_state", state)
                .put("in_progress_attempt_id", attempt.optLongAny("attempt_id", "attemptId", "id"))
                .put("in_progress_offline_mode", true)
                .put("attempts_used", maxOf(item.optInt("attempts_used", 0), attempt.optInt("attempt_number", 1)))
                .put("max_attempts", maxOf(item.optInt("max_attempts", 1), item.optInt("attempts_used", 0), 1))
                .put("question_count", attempt.optJSONArray("questions")?.length() ?: item.optInt("question_count", 0))
            item
        }
    }

    fun markState(attemptId: Long, state: String, error: String? = null) {
        require(state in setOf("DRAFT", "QUEUED", "SYNC_ERROR"))
        synchronized(lock) {
            val record = readRecordInternal(attemptId) ?: return
            record.put("state", state).put("updatedAtMillis", System.currentTimeMillis())
            if (!error.isNullOrBlank()) record.put("lastError", error.take(500)) else record.remove("lastError")
            writeRecord(attemptId, record)
        }
    }

    fun remove(attemptId: Long) {
        require(attemptId > 0L)
        synchronized(lock) {
            File(root, fileName(attemptId)).delete()
            val remaining = root.listFiles()?.any { it.isFile && it.name.endsWith(".enc") } == true
            if (!remaining) runCatching {
                val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
                if (ks.containsAlias(keyAlias)) ks.deleteEntry(keyAlias)
            }
        }
    }

    private fun allRecordsInternal(): List<JSONObject> = root.listFiles()
        ?.filter { it.isFile && it.name.endsWith(".enc") }
        ?.mapNotNull { file -> runCatching { JSONObject(decrypt(file.readBytes()).toString(Charsets.UTF_8)) }.getOrNull() }
        .orEmpty()
        .sortedByDescending { it.optLong("updatedAtMillis", 0L) }

    private fun readRecordInternal(attemptId: Long): JSONObject? {
        val file = File(root, fileName(attemptId))
        if (!file.isFile) return null
        return runCatching { JSONObject(decrypt(file.readBytes()).toString(Charsets.UTF_8)) }.getOrNull()
    }

    private fun writeRecord(attemptId: Long, value: JSONObject) {
        val target = File(root, fileName(attemptId))
        val temp = File(root, fileName(attemptId) + ".tmp")
        temp.writeBytes(encrypt(value.toString().toByteArray(Charsets.UTF_8)))
        if (!temp.renameTo(target)) {
            runCatching { target.delete() }
            if (!temp.renameTo(target)) {
                temp.delete()
                throw IOException("Unable to safely persist the encrypted offline test.")
            }
        }
    }

    private fun fileName(attemptId: Long) = "attempt_$attemptId.enc"

    private fun sanitize(source: JSONObject): JSONObject {
        val result = JSONObject()
        val keys = source.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            if (key.lowercase() in forbiddenFields) continue
            val value = source.opt(key)
            when (value) {
                is JSONObject -> result.put(key, sanitize(value))
                is JSONArray -> result.put(key, sanitize(value))
                else -> result.put(key, value ?: JSONObject.NULL)
            }
        }
        return result
    }

    private fun sanitize(source: JSONArray): JSONArray {
        val result = JSONArray()
        for (i in 0 until source.length()) {
            val value = source.opt(i)
            result.put(when (value) {
                is JSONObject -> sanitize(value)
                is JSONArray -> sanitize(value)
                else -> value ?: JSONObject.NULL
            })
        }
        return result
    }

    private fun encrypt(clear: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        return cipher.iv + cipher.doFinal(clear)
    }

    private fun decrypt(value: ByteArray): ByteArray {
        require(value.size > 12) { "Encrypted offline assessment is incomplete." }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, value.copyOfRange(0, 12)))
        return cipher.doFinal(value.copyOfRange(12, value.size))
    }

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(keyAlias, null) as? SecretKey)?.let { return it }
        val generator: JcaKeyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(KeyGenParameterSpec.Builder(
            keyAlias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true)
            .build())
        return generator.generateKey()
    }

    companion object {
        private val forbiddenFields = setOf(
            "answerkey", "answer_key", "answer_payload", "answerpayload",
            "correctanswer", "correct_answer", "correctoption", "correct_option",
            "iscorrect", "is_correct", "correct", "solution", "markingscheme", "marking_scheme"
        )
    }
}
