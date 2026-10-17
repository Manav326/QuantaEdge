package com.quantaedge.android.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/** Account-scoped app-private storage and durable manifest for permitted offline video files. */
class OfflineLearningVideoStore(context: Context, ownerKey: String) {
    private val ownerNamespace = MessageDigest.getInstance("SHA-256")
        .digest(ownerKey.ifBlank { "guest" }.toByteArray(Charsets.UTF_8))
        .take(12).joinToString("") { "%02x".format(it) }
    private val root = File(context.applicationContext.filesDir, "offline_learning_videos/" + ownerNamespace).apply {
        if (!exists() && !mkdirs()) throw IOException("Could not prepare private offline video storage.")
    }
    private val preferences = context.applicationContext.getSharedPreferences(
        "qe_offline_video_manifest_" + ownerNamespace, Context.MODE_PRIVATE
    )
    private val lock = locks.getOrPut(ownerNamespace) { Any() }

    fun prepareDownload(item: JSONObject) {
        val id = item.optLongAny("recorded_class_id", "recordingId", "id")
        val resourceId = item.optLongAny("download_resource_id", "downloadResourceId")
        val assignmentId = item.optLongAny("download_assignment_id", "downloadAssignmentId")
        val mediaType = item.optString("media_type", item.optString("mediaType"))
        val extension = OfflineLearningVideoContract.extensionFor(mediaType)
        val size = item.optLongAny("file_size_bytes", "fileSizeBytes")
        val sha = item.optString("download_sha256", item.optString("downloadSha256")).lowercase()
        if (id <= 0L || resourceId <= 0L || assignmentId <= 0L) {
            throw IllegalArgumentException("This recording has no approved offline-download placement.")
        }
        if (extension == null || !OfflineLearningVideoContract.isValidSize(size) ||
            !OfflineLearningVideoContract.isValidSha256(sha) || !item.optBoolean("download_allowed")) {
            throw IllegalArgumentException("The recording's type, size, checksum or download permission is invalid.")
        }
        synchronized(lock) {
            val items = readItems()
            val existing = items.firstOrNull { it.optLongAny("recorded_class_id") == id }
            val row = existing?.let { JSONObject(it.toString()) } ?: JSONObject()
            row.put("recorded_class_id", id)
                .put("download_resource_id", resourceId)
                .put("download_assignment_id", assignmentId)
                .put("download_sha256", sha)
                .put("file_size_bytes", size)
                .put("media_type", mediaType)
                .put("download_allowed", true)
                .put("local_filename", "recording_" + id + "." + extension)
                .put("title", item.optString("title", row.optString("title", "Recorded class")))
                .put("description", item.optString("description", row.optString("description", "")))
                .put("subject_name", item.optString("subject_name", row.optString("subject_name", "")))
                .put("chapter_name", item.optString("chapter_name", row.optString("chapter_name", "")))
                .put("duration_seconds", item.optLongAny("duration_seconds", "durationSeconds"))
                .put("download_state", "QUEUED")
                .put("downloaded", false)
            replaceRow(items, id, row)
            writeItems(items)
        }
    }

    fun recording(recordingId: Long): JSONObject? = synchronized(lock) {
        readItems().firstOrNull { it.optLongAny("recorded_class_id") == recordingId }
            ?.let { JSONObject(it.toString()) }
    }

    fun downloadedRecordings(): List<JSONObject> = synchronized(lock) {
        readItems().filter { row ->
            row.optBoolean("downloaded") && row.optString("download_state") == "COMPLETE" &&
                destinationFor(row)?.let { it.isFile && it.length() == row.optLong("file_size_bytes") } == true
        }.map { row -> JSONObject(row.toString()).put("offline_downloaded", true) }
    }

    fun downloadedRecordingIds(): Set<Long> =
        downloadedRecordings().map { it.optLongAny("recorded_class_id") }.filter { it > 0L }.toSet()

    fun completedFileForRecording(recordingId: Long): File? = synchronized(lock) {
        val row = readItems().firstOrNull { it.optLongAny("recorded_class_id") == recordingId }
            ?: return@synchronized null
        if (!row.optBoolean("downloaded") || row.optString("download_state") != "COMPLETE") return@synchronized null
        val file = destinationFor(row) ?: return@synchronized null
        file.takeIf { it.isFile && it.length() == row.optLong("file_size_bytes") }
    }

    fun partFileForRecording(recordingId: Long, mediaType: String? = null): File {
        require(recordingId > 0L)
        val type = mediaType ?: recording(recordingId)?.optString("media_type").orEmpty()
        val extension = OfflineLearningVideoContract.extensionFor(type)
            ?: throw IllegalArgumentException("Unsupported recording media type.")
        return File(root, "recording_" + recordingId + "." + extension + ".part")
    }

    fun destinationFileForRecording(recordingId: Long, mediaType: String): File {
        require(recordingId > 0L)
        val extension = OfflineLearningVideoContract.extensionFor(mediaType)
            ?: throw IllegalArgumentException("Unsupported recording media type.")
        return File(root, "recording_" + recordingId + "." + extension)
    }

    fun markDownloadState(recordingId: Long, state: String) {
        require(state in setOf("QUEUED", "FAILED", "CANCELLED", "NONE"))
        updateRow(recordingId) { it.put("download_state", state).put("downloaded", false) }
    }

    fun markComplete(recordingId: Long, verifiedSha256: String) {
        require(OfflineLearningVideoContract.isValidSha256(verifiedSha256))
        updateRow(recordingId) {
            if (!it.optString("download_sha256").equals(verifiedSha256, ignoreCase = true)) {
                throw IOException("Verified video checksum does not match the approved recording.")
            }
            it.put("download_state", "COMPLETE").put("downloaded", true)
        }
    }

    fun savePlaybackProgress(recordingId: Long, positionSeconds: Long, watchedSeconds: Long, completed: Boolean) {
        updateRow(recordingId) { row ->
            val duration = row.optLong("duration_seconds").takeIf { it > 0L } ?: Long.MAX_VALUE
            row.put("last_position_seconds", positionSeconds.coerceIn(0L, duration))
                .put("watched_seconds", watchedSeconds.coerceIn(0L, duration))
                .put("completed", completed)
                .put("progress_sync_pending", true)
        }
    }

    fun pendingProgressUploads(): List<JSONObject> = synchronized(lock) {
        readItems().filter { it.optBoolean("progress_sync_pending") }.map { JSONObject(it.toString()) }
    }

    fun markProgressSynced(recordingId: Long) {
        synchronized(lock) {
            val items = readItems()
            val row = items.firstOrNull { it.optLongAny("recorded_class_id") == recordingId } ?: return
            row.put("progress_sync_pending", false)
            if (!row.optBoolean("downloaded") && row.optString("download_state") == "NONE") items.remove(row)
            writeItems(items)
        }
    }

    fun removeOfflineCopy(recordingId: Long) {
        require(recordingId > 0L)
        synchronized(lock) {
            val row = readItems().firstOrNull { it.optLongAny("recorded_class_id") == recordingId } ?: return
            val target = destinationFileForRecording(recordingId, row.optString("media_type"))
            val partial = partFileForRecording(recordingId, row.optString("media_type"))
            listOf(target, partial).forEach { file ->
                if (file.exists() && !file.delete()) throw IOException("Could not remove the local video file.")
            }
            val items = readItems()
            val item = items.firstOrNull { it.optLongAny("recorded_class_id") == recordingId } ?: return
            if (item.optBoolean("progress_sync_pending")) item.put("downloaded", false).put("download_state", "NONE")
            else items.remove(item)
            writeItems(items)
        }
    }

    private fun destinationFor(row: JSONObject): File? {
        val id = row.optLongAny("recorded_class_id")
        val extension = OfflineLearningVideoContract.extensionFor(row.optString("media_type")) ?: return null
        val expectedName = "recording_" + id + "." + extension
        if (id <= 0L || row.optString("local_filename") != expectedName) return null
        return File(root, expectedName)
    }

    private fun updateRow(recordingId: Long, mutate: (JSONObject) -> Unit) {
        synchronized(lock) {
            val items = readItems()
            val row = items.firstOrNull { it.optLongAny("recorded_class_id") == recordingId }
                ?: throw IOException("The offline recording manifest is missing.")
            mutate(row)
            writeItems(items)
        }
    }

    private fun replaceRow(items: MutableList<JSONObject>, id: Long, row: JSONObject) {
        items.removeAll { it.optLongAny("recorded_class_id") == id }
        items.add(row)
    }

    private fun readItems(): MutableList<JSONObject> {
        val raw = preferences.getString("recordings", null) ?: return mutableListOf()
        return try {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) array.optJSONObject(index)?.let { add(JSONObject(it.toString())) }
            }.toMutableList()
        } catch (_: Exception) {
            mutableListOf()
        }
    }

    private fun writeItems(items: List<JSONObject>) {
        val array = JSONArray()
        items.forEach { array.put(it) }
        if (!preferences.edit().putString("recordings", array.toString()).commit()) {
            throw IOException("The offline video manifest could not be saved.")
        }
    }

    companion object {
        private val locks = ConcurrentHashMap<String, Any>()
    }
}
