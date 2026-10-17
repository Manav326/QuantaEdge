package com.quantaedge.android.data

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.time.Duration

/** Resumable, checksum-verified transfer from the authenticated resource download endpoint. */
class OfflineLearningVideoDownloadWorker(appContext: Context, parameters: WorkerParameters) :
    CoroutineWorker(appContext, parameters) {

    override suspend fun doWork(): Result {
        val ownerKey = inputData.getString(KEY_OWNER) ?: return Result.failure()
        val recordingId = inputData.getLong(KEY_RECORDING_ID, 0L)
        val resourceId = inputData.getLong(KEY_RESOURCE_ID, 0L)
        val assignmentId = inputData.getLong(KEY_ASSIGNMENT_ID, 0L)
        val expectedSize = inputData.getLong(KEY_EXPECTED_SIZE, 0L)
        val expectedSha = inputData.getString(KEY_SHA256).orEmpty().lowercase()
        val mediaType = inputData.getString(KEY_MEDIA_TYPE).orEmpty()
        if (ownerKey == "guest" || recordingId <= 0L || resourceId <= 0L || assignmentId <= 0L ||
            !OfflineLearningVideoContract.isValidSize(expectedSize) ||
            !OfflineLearningVideoContract.isValidSha256(expectedSha) ||
            OfflineLearningVideoContract.extensionFor(mediaType) == null) return Result.failure()
        val store = try { OfflineLearningVideoStore(applicationContext, ownerKey) }
            catch (_: Exception) { return Result.failure() }

        return try {
            withContext(Dispatchers.IO) {
                ApiClient.initialize(applicationContext)
                val auth = ApiClient.getObject("/api/v1/auth/me")
                if (ResourceOwnerKey.fromAuth(auth) != ownerKey) {
                    store.markDownloadState(recordingId, "FAILED")
                    return@withContext Result.failure()
                }
                val target = store.destinationFileForRecording(recordingId, mediaType)
                val partial = store.partFileForRecording(recordingId, mediaType)
                target.parentFile?.let {
                    if (!it.isDirectory && !it.mkdirs()) throw IOException("Could not prepare private offline video storage.")
                }

                if (target.isFile && target.length() == expectedSize &&
                    sha256(target).equals(expectedSha, ignoreCase = true)) {
                    finalizeVerifiedFile(store, recordingId, target, partial, expectedSha)
                    setProgress(Data.Builder().putLong(KEY_BYTES_DOWNLOADED, expectedSize)
                        .putLong(KEY_TOTAL_BYTES, expectedSize).build())
                    return@withContext Result.success()
                }
                if (target.exists() && !target.delete()) throw IOException("Could not replace an invalid local video copy.")

                var existing = partial.length()
                if (existing > expectedSize) {
                    if (!partial.delete()) throw IOException("Could not clear an oversized partial video.")
                    existing = 0L
                }
                if (existing == expectedSize) {
                    if (sha256(partial).equals(expectedSha, ignoreCase = true)) {
                        finalizeVerifiedFile(store, recordingId, target, partial, expectedSha)
                        return@withContext Result.success()
                    }
                    if (!partial.delete()) throw IOException("Could not reset an incomplete local video.")
                    existing = 0L
                }

                val path = "/api/v1/learning/resources/" + resourceId + "/download?assignmentId=" + assignmentId
                val requestBuilder = Request.Builder()
                    .url(ApiClient.absoluteUrl(path))
                    .get()
                    .header("Accept", "video/*, application/octet-stream")
                    .header("X-QE-Workspace", "STUDENT")
                    .header("X-QE-Client", "ANDROID")
                if (existing > 0L) requestBuilder.header("Range", "bytes=" + existing + "-")

                ApiClient.authenticatedHttpClient().newCall(requestBuilder.build()).execute().use { response ->
                    if (response.code == 416) {
                        if (partial.isFile && partial.length() == expectedSize &&
                            sha256(partial).equals(expectedSha, ignoreCase = true)) {
                            finalizeVerifiedFile(store, recordingId, target, partial, expectedSha)
                            return@withContext Result.success()
                        }
                        if (partial.exists() && !partial.delete()) throw IOException("Could not reset invalid range state.")
                        store.markDownloadState(recordingId, "QUEUED")
                        return@withContext Result.retry()
                    }
                    if (response.code == 408 || response.code == 429 || response.code >= 500) {
                        store.markDownloadState(recordingId, "QUEUED")
                        return@withContext Result.retry()
                    }
                    if (response.code != 200 && response.code != 206) {
                        if (partial.exists()) partial.delete()
                        store.markDownloadState(recordingId, "FAILED")
                        return@withContext Result.failure()
                    }

                    val range = OfflineLearningVideoContract.parseContentRange(response.header("Content-Range"))
                    val disposition = OfflineLearningVideoContract.resumeDisposition(existing, response.code, range, expectedSize)
                    if (disposition == OfflineLearningVideoContract.ResumeDisposition.RETRY_INVALID ||
                        disposition == OfflineLearningVideoContract.ResumeDisposition.VERIFY_COMPLETE) {
                        if (partial.exists()) partial.delete()
                        store.markDownloadState(recordingId, "QUEUED")
                        return@withContext Result.retry()
                    }
                    val append = response.code == 206 &&
                        disposition == OfflineLearningVideoContract.ResumeDisposition.APPEND && existing > 0L
                    val body = response.body ?: run {
                        store.markDownloadState(recordingId, "QUEUED")
                        return@withContext Result.retry()
                    }
                    if (!append && partial.exists() && !partial.delete()) throw IOException("Could not restart the offline video transfer.")
                    var received = if (append) existing else 0L
                    body.byteStream().use { input ->
                        FileOutputStream(partial, append).use { output ->
                            val buffer = ByteArray(DEFAULT_BUFFER_SIZE * 4)
                            var lastReported = received
                            while (true) {
                                if (isStopped) {
                                    store.markDownloadState(recordingId, "QUEUED")
                                    return@withContext Result.retry()
                                }
                                val count = input.read(buffer)
                                if (count < 0) break
                                if (count == 0) continue
                                received += count
                                if (received > expectedSize) {
                                    partial.delete()
                                    store.markDownloadState(recordingId, "FAILED")
                                    return@withContext Result.failure()
                                }
                                output.write(buffer, 0, count)
                                if (received - lastReported >= PROGRESS_CHUNK_BYTES) {
                                    setProgress(Data.Builder().putLong(KEY_BYTES_DOWNLOADED, received)
                                        .putLong(KEY_TOTAL_BYTES, expectedSize).build())
                                    lastReported = received
                                }
                            }
                            output.fd.sync()
                        }
                    }
                }

                if (!partial.isFile || partial.length() != expectedSize) {
                    store.markDownloadState(recordingId, "QUEUED")
                    return@withContext Result.retry()
                }
                val actualSha = sha256(partial)
                if (!actualSha.equals(expectedSha, ignoreCase = true)) {
                    if (!partial.delete()) throw IOException("Video checksum failed and partial copy could not be removed.")
                    store.markDownloadState(recordingId, "FAILED")
                    return@withContext Result.failure()
                }
                finalizeVerifiedFile(store, recordingId, target, partial, actualSha)
                setProgress(Data.Builder().putLong(KEY_BYTES_DOWNLOADED, expectedSize)
                    .putLong(KEY_TOTAL_BYTES, expectedSize).build())
                Result.success()
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: ApiFailure) {
            if (failure.statusCode == 408 || failure.statusCode == 429 || failure.statusCode >= 500) {
                runCatching { store.markDownloadState(recordingId, "QUEUED") }
                Result.retry()
            } else {
                runCatching { store.markDownloadState(recordingId, "FAILED") }
                Result.failure()
            }
        } catch (_: IOException) {
            runCatching { store.markDownloadState(recordingId, "QUEUED") }
            Result.retry()
        } catch (_: Exception) {
            runCatching { store.markDownloadState(recordingId, "FAILED") }
            Result.failure()
        }
    }

    private fun finalizeVerifiedFile(store: OfflineLearningVideoStore, recordingId: Long,
        target: File, partial: File, expectedSha: String) {
        if (partial.exists()) {
            try {
                Files.move(partial.toPath(), target.toPath(),
                    StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(partial.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        }
        try {
            store.markComplete(recordingId, expectedSha)
        } catch (failure: Exception) {
            target.delete()
            throw failure
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE * 4)
            while (true) {
                if (isStopped) throw IOException("Offline video transfer was stopped.")
                val count = input.read(buffer)
                if (count < 0) break
                if (count > 0) digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    companion object {
        private const val KEY_OWNER = "owner_namespace"
        private const val KEY_RECORDING_ID = "recorded_class_id"
        private const val KEY_RESOURCE_ID = "resource_id"
        private const val KEY_ASSIGNMENT_ID = "assignment_id"
        private const val KEY_SHA256 = "sha256"
        private const val KEY_EXPECTED_SIZE = "expected_size_bytes"
        private const val KEY_MEDIA_TYPE = "media_type"
        private const val KEY_BYTES_DOWNLOADED = "bytesDownloaded"
        private const val KEY_TOTAL_BYTES = "totalBytes"
        private const val PROGRESS_CHUNK_BYTES = 2L * 1024L * 1024L

        fun uniqueWorkName(ownerKey: String, recordingId: Long): String =
            "qe-offline-video-" + ownerKey.take(16) + "-" + recordingId

        fun enqueue(context: Context, ownerKey: String, recording: JSONObject) {
            if (ownerKey.isBlank() || ownerKey == "guest") {
                throw IllegalArgumentException("Sign in before downloading videos.")
            }
            val recordingId = recording.optLongAny("recorded_class_id", "recordingId", "id")
            val resourceId = recording.optLongAny("download_resource_id", "downloadResourceId")
            val assignmentId = recording.optLongAny("download_assignment_id", "downloadAssignmentId")
            val expectedSize = recording.optLongAny("file_size_bytes", "fileSizeBytes")
            val sha = recording.optString("download_sha256", recording.optString("downloadSha256"))
            val mediaType = recording.optString("media_type", recording.optString("mediaType"))
            if (recordingId <= 0L || resourceId <= 0L || assignmentId <= 0L ||
                !recording.optBoolean("download_allowed") ||
                !OfflineLearningVideoContract.isValidSize(expectedSize) ||
                !OfflineLearningVideoContract.isValidSha256(sha) ||
                OfflineLearningVideoContract.extensionFor(mediaType) == null) {
                throw IllegalArgumentException("This recording is not approved for offline download.")
            }
            val store = OfflineLearningVideoStore(context.applicationContext, ownerKey)
            if (store.completedFileForRecording(recordingId) != null) return
            store.prepareDownload(recording)
            val request = OneTimeWorkRequestBuilder<OfflineLearningVideoDownloadWorker>()
                .setInputData(Data.Builder()
                    .putString(KEY_OWNER, ownerKey)
                    .putLong(KEY_RECORDING_ID, recordingId)
                    .putLong(KEY_RESOURCE_ID, resourceId)
                    .putLong(KEY_ASSIGNMENT_ID, assignmentId)
                    .putString(KEY_SHA256, sha.lowercase())
                    .putLong(KEY_EXPECTED_SIZE, expectedSize)
                    .putString(KEY_MEDIA_TYPE, mediaType)
                    .build())
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, Duration.ofSeconds(30))
                .addTag("quantaedge-offline-video-download")
                .build()
            WorkManager.getInstance(context.applicationContext)
                .enqueueUniqueWork(uniqueWorkName(ownerKey, recordingId), ExistingWorkPolicy.KEEP, request)
        }
    }
}
