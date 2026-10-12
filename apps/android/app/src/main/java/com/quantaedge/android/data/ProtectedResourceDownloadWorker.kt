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
import java.io.IOException
import java.time.Duration

/** Resumable background download for authorized, server-rendered page images. */
class ProtectedResourceDownloadWorker(appContext: Context, parameters: WorkerParameters) : CoroutineWorker(appContext, parameters) {
    override suspend fun doWork(): Result {
        val ownerKey = inputData.getString(KEY_OWNER) ?: return Result.failure()
        val documentId = inputData.getLong(KEY_DOCUMENT_ID, 0L)
        if (ownerKey == "guest" || documentId <= 0L) return Result.failure()
        return try {
            ApiClient.initialize(applicationContext)
            val activeAccount = ApiClient.getObject("/api/v1/auth/me")
            if (ResourceOwnerKey.fromAuth(activeAccount) != ownerKey) return Result.failure()
            val store = ProtectedResourceStore(applicationContext, ownerKey)
            val document = store.downloadedDocuments().firstOrNull { it.optLongAny("document_id", "id") == documentId }
                ?: return Result.failure()
            val count = document.optInt("page_count",
                (document.optInt("page_end", 1) - document.optInt("page_start", 1) + 1).coerceAtLeast(1))
            if (count !in 1..1500) return Result.failure()
            var completed = store.cachedPageCount(documentId, count)
            setProgress(Data.Builder().putInt("pagesDone", completed).putInt("pageCount", count).build())
            for (page in 1..count) {
                if (isStopped) return Result.retry()
                if (store.hasPage(documentId, page)) continue
                val bytes = ApiClient.getBytes("/api/v1/learning/documents/" + documentId + "/pages/" + page)
                if (isStopped) return Result.retry()
                if (bytes.isEmpty() || bytes.size > MAX_PAGE_BYTES) return Result.failure()
                store.savePage(documentId, page, bytes)
                completed++
                setProgress(Data.Builder().putInt("pagesDone", completed).putInt("pageCount", count).build())
            }
            Result.success(Data.Builder().putInt("pagesDone", count).putInt("pageCount", count).build())
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: ApiFailure) {
            if (failure.statusCode == 429 || failure.statusCode >= 500) Result.retry() else Result.failure()
        } catch (_: IOException) {
            Result.retry()
        } catch (_: Exception) {
            Result.failure()
        }
    }

    companion object {
        private const val KEY_OWNER = "owner_namespace"
        private const val KEY_DOCUMENT_ID = "document_id"
        private const val MAX_PAGE_BYTES = 20 * 1024 * 1024

        fun enqueue(context: Context, ownerKey: String, documentId: Long) {
            require(ownerKey.isNotBlank() && ownerKey != "guest" && documentId > 0L)
            val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
            val request = OneTimeWorkRequestBuilder<ProtectedResourceDownloadWorker>()
                .setInputData(Data.Builder().putString(KEY_OWNER, ownerKey).putLong(KEY_DOCUMENT_ID, documentId).build())
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, Duration.ofSeconds(30))
                .addTag("quantaedge-resource-download")
                .build()
            val uniqueName = "qe-resource-" + ownerKey.take(16) + "-" + documentId
            WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(uniqueName, ExistingWorkPolicy.KEEP, request)
        }
    }
}
