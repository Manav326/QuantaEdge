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
import org.json.JSONObject
import java.io.IOException
import java.time.Duration

/** Uploads queued offline answers; the API remains the only grader and result publisher. */
class OfflineAssessmentSyncWorker(appContext: Context, parameters: WorkerParameters) :
    CoroutineWorker(appContext, parameters) {

    override suspend fun doWork(): Result {
        val ownerKey = inputData.getString(KEY_OWNER) ?: return Result.failure()
        if (ownerKey.isBlank() || ownerKey == "guest") return Result.failure()
        return try {
            ApiClient.initialize(applicationContext)
            val account = ApiClient.getObject("/api/v1/auth/me")
            if (ResourceOwnerKey.fromAuth(account) != ownerKey) return Result.failure()

            val store = OfflineAssessmentStore(applicationContext, ownerKey)
            for (record in store.pendingRecords()) {
                if (isStopped) return Result.retry()
                val attempt = record.optJSONObject("attempt") ?: continue
                val attemptId = attempt.optLongAny("attempt_id", "attemptId", "id")
                val token = attempt.optString("offlinePackageToken")
                if (attemptId <= 0L || token.isBlank()) {
                    store.markState(attemptId, "SYNC_ERROR", "Offline package metadata is incomplete.")
                    continue
                }
                try {
                    val serverAttempt = ApiClient.getObject("/api/v1/learning/assessment-attempts/$attemptId")
                    if (serverAttempt.optString("status") != "IN_PROGRESS") {
                        // Handles worker interruption after server accepted submission; preserve its real result state locally.
                        store.markSynced(attemptId, serverAttempt)
                        continue
                    }
                    val localAnswers = record.optJSONObject("answers") ?: JSONObject()
                    val questions = serverAttempt.optJSONArray("questions") ?: attempt.optJSONArray("questions")
                    if (questions == null) {
                        store.markState(attemptId, "SYNC_ERROR", "Question snapshot is missing.")
                        continue
                    }
                    for (index in 0 until questions.length()) {
                        if (isStopped) return Result.retry()
                        val question = questions.optJSONObject(index) ?: continue
                        val questionId = question.optLongAny("attemptQuestionId", "attempt_question_id")
                        if (questionId <= 0L) continue
                        val key = questionId.toString()
                        val answer: Any = if (localAnswers.has(key) && !localAnswers.isNull(key)) {
                            localAnswers.opt(key) ?: ""
                        } else {
                            // Never infer a learner response from a server question snapshot.
                            // Missing local answers must be submitted as blank, not a potentially
                            // grading-sensitive or stale "answer" field supplied with the question.
                            ""
                        }
                        ApiClient.putObject(
                            "/api/v1/learning/assessment-attempts/$attemptId/answers/$questionId",
                            JSONObject().put("answer", answer).put("offlinePackageToken", token)
                        )
                    }
                    val submitted = ApiClient.postObject(
                        "/api/v1/learning/assessment-attempts/$attemptId/submit",
                        JSONObject().put("offlinePackageToken", token)
                    )
                    store.markSynced(attemptId, submitted)
                } catch (failure: ApiFailure) {
                    if (failure.statusCode == 429 || failure.statusCode >= 500 || failure.statusCode == 401) {
                        return Result.retry()
                    }
                    store.markState(attemptId, "SYNC_ERROR", failure.message)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: IOException) {
                    return Result.retry()
                } catch (failure: Exception) {
                    store.markState(attemptId, "SYNC_ERROR", failure.message ?: "Unable to sync offline answers.")
                }
            }
            Result.success(Data.Builder().putString("owner_namespace", ownerKey.take(16)).build())
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: ApiFailure) {
            if (failure.statusCode == 401 || failure.statusCode == 429 || failure.statusCode >= 500) Result.retry()
            else Result.failure()
        } catch (_: IOException) {
            Result.retry()
        } catch (_: Exception) {
            Result.retry()
        }
    }

    companion object {
        private const val KEY_OWNER = "owner_namespace"

        fun enqueue(context: Context, ownerKey: String) {
            require(ownerKey.isNotBlank() && ownerKey != "guest")
            val request = OneTimeWorkRequestBuilder<OfflineAssessmentSyncWorker>()
                .setInputData(Data.Builder().putString(KEY_OWNER, ownerKey).build())
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, Duration.ofSeconds(30))
                .addTag(TAG)
                .build()
            WorkManager.getInstance(context.applicationContext)
                .enqueueUniqueWork("qe-offline-assessment-sync-" + ownerKey.take(16),
                    ExistingWorkPolicy.APPEND_OR_REPLACE, request)
        }

        fun enqueueIfPending(context: Context, ownerKey: String, store: OfflineAssessmentStore) {
            if (ownerKey.isNotBlank() && ownerKey != "guest" && store.pendingRecords().isNotEmpty()) {
                enqueue(context, ownerKey)
            }
        }

        private const val TAG = "quantaedge-offline-assessment-sync"
    }
}
