package com.quantaedge.android.ui

import android.graphics.BitmapFactory
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.quantaedge.android.R
import com.quantaedge.android.data.ApiClient
import com.quantaedge.android.data.ApiFailure
import com.quantaedge.android.data.ProtectedResourceStore
import com.quantaedge.android.data.OfflineAssessmentStore
import com.quantaedge.android.data.OfflineAssessmentSyncWorker
import androidx.work.WorkManager
import com.quantaedge.android.data.optLongAny
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

private data class ResourceDownloadUiState(
    val label: String,
    val progress: Float? = null,
    val active: Boolean = false
)

private data class AppNavItem(val key: String, val label: String, val glyph: String)

private enum class AppRoute {
    LOGIN, PARENT_SIGNUP, PARENT_RESET, STUDENT_HOME, PARENT_HOME, PARENT_REPORT, PARENT_PROFILE,
    PARENT_CHILD_CREATE, PARENT_CHILD_MANAGE, SUBJECT_LESSONS, LESSON, PRACTICE,
    DIAGNOSTIC, ASSESSMENTS, ASSESSMENT_ATTEMPT, ASSESSMENT_RESULTS, ASSESSMENT_REVIEW, RESOURCES,
    DOCUMENT_READER, PROFILE, PROGRESS, TUTOR, CLASSES, RECORDING_PLAYER, CERTIFICATES, LEADERBOARD
}

@Composable
fun QuantaEdgeApp() {
    val context = LocalContext.current
    val preferences = remember { context.getSharedPreferences("qe_preferences", 0) }
    val scope = rememberCoroutineScope()
    var auth by remember { mutableStateOf(JSONObject()) }
    val resourceOwner = remember(auth) { com.quantaedge.android.data.ResourceOwnerKey.fromAuth(auth) }
    val resourceStore = remember(resourceOwner) { ProtectedResourceStore(context, resourceOwner) }
    val offlineAssessmentStore = remember(resourceOwner) { OfflineAssessmentStore(context, resourceOwner) }

    var route by remember { mutableStateOf(AppRoute.LOGIN) }
    var language by remember { mutableStateOf(preferences.getString("language", "hi") ?: "hi") }
    var studentProfile by remember { mutableStateOf(JSONObject()) }
    var recommendation by remember { mutableStateOf(JSONObject()) }
    var parentAccount by remember { mutableStateOf(JSONObject()) }
    var parentChildren by remember { mutableStateOf(emptyList<JSONObject>()) }
    var parentReport by remember { mutableStateOf(JSONObject()) }
    var parentProfile by remember { mutableStateOf(JSONObject()) }
    var selectedChild by remember { mutableStateOf(JSONObject()) }

    var currentSubject by remember { mutableStateOf("maths") }
    var lessons by remember { mutableStateOf(emptyList<JSONObject>()) }
    var lesson by remember { mutableStateOf(JSONObject()) }
    var practice by remember { mutableStateOf(JSONObject()) }
    var diagnostic by remember { mutableStateOf(JSONObject()) }
    var assessments by remember { mutableStateOf(emptyList<JSONObject>()) }
    var assessmentAttempt by remember { mutableStateOf(JSONObject()) }
    var assessmentResults by remember { mutableStateOf(emptyList<JSONObject>()) }
    var assessmentCertificateEligibility by remember { mutableStateOf<Map<Long, JSONObject>>(emptyMap()) }
    var pendingCertificateBytes by remember { mutableStateOf<ByteArray?>(null) }
    var pendingCertificateFilename by remember { mutableStateOf("quantaedge-certificate.pdf") }
    var progressData by remember { mutableStateOf(JSONObject()) }
    var documents by remember { mutableStateOf(emptyList<JSONObject>()) }
    var selectedDocument by remember { mutableStateOf(JSONObject()) }
    var documentPage by remember { mutableStateOf(1) }
    var documentImage by remember { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
    var tutorSession by remember { mutableStateOf(JSONObject()) }
    var tutorMessages by remember { mutableStateOf(emptyList<JSONObject>()) }
    var tutorAvailable by remember { mutableStateOf(false) }
    var liveClasses by remember { mutableStateOf(emptyList<JSONObject>()) }
    var recordedClasses by remember { mutableStateOf(emptyList<JSONObject>()) }
    var selectedRecording by remember { mutableStateOf(JSONObject()) }

    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("") }
    val answers = remember { mutableStateMapOf<String, String>() }
    val feedbacks = remember { mutableStateMapOf<String, String>() }
    val lessonAnswerLocks = remember { mutableStateMapOf<String, Boolean>() }
    val resourceDownloadStates = remember { mutableStateMapOf<Long, ResourceDownloadUiState>() }
    // Keep the app interface locale separate from the learner preferred content language.
    val learningContentLanguage = studentProfile.textAny("language").trim().lowercase()
        .takeIf { it == "hi" || it == "en" }
        ?: (preferences.getString("contentLanguage", "hi") ?: "hi")

    fun persistPendingCertificate(uri: android.net.Uri?) {
        val bytes = pendingCertificateBytes
        if (uri == null || bytes == null) {
            pendingCertificateBytes = null
            return
        }
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
                        ?: throw IOException("Certificate could not be saved to the selected location.")
                }
                status = "Certificate saved successfully."
            } catch (exception: Exception) {
                error = exception.message ?: "Certificate could not be saved."
            } finally {
                pendingCertificateBytes = null
            }
        }
    }

    val resultCertificatePdfLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/pdf")
    ) { uri -> persistPendingCertificate(uri) }
    val resultCertificatePngLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("image/png")
    ) { uri -> persistPendingCertificate(uri) }

    LaunchedEffect(resourceOwner, auth.toString()) {
        if (resourceOwner.isNotBlank() && resourceOwner != "guest") {
            val queued = withContext(Dispatchers.IO) { offlineAssessmentStore.pendingRecords().isNotEmpty() }
            if (queued) OfflineAssessmentSyncWorker.enqueue(context.applicationContext, resourceOwner)
        }
    }

    LaunchedEffect(route, assessmentAttempt.toString(), answers.toMap()) {
        if (route == AppRoute.ASSESSMENT_ATTEMPT &&
            assessmentAttempt.optBoolean("offlineMode") &&
            !assessmentAttempt.optBoolean("offlinePendingSync") &&
            assessmentAttempt.textAny("status") == "IN_PROGRESS") {
            delay(300)
            withContext(Dispatchers.IO) {
                offlineAssessmentStore.saveAttempt(
                    assessmentAttempt,
                    answers.toMap(),
                    "DRAFT"
                )
            }
        }
    }

    LaunchedEffect(route, resourceOwner, documents) {
        if (route == AppRoute.RESOURCES) {
            val documentIds = documents.map { it.optLongAny("document_id", "id") }.filter { it > 0L }.distinct()
            while (true) {
                val workByDocument = withContext(Dispatchers.IO) {
                    documentIds.mapNotNull { documentId ->
                        val uniqueName = "qe-resource-" + resourceOwner.take(16) + "-" + documentId
                        val work = runCatching {
                            WorkManager.getInstance(context.applicationContext)
                                .getWorkInfosForUniqueWork(uniqueName).get().lastOrNull()
                        }.getOrNull()
                        if (work == null) null else documentId to work
                    }.toMap()
                }
                val nextStates = linkedMapOf<Long, ResourceDownloadUiState>()
                workByDocument.forEach { (documentId, work) ->
                    val done = if (work.state == androidx.work.WorkInfo.State.SUCCEEDED) {
                        work.outputData.getInt("pagesDone", work.progress.getInt("pagesDone", 0))
                    } else work.progress.getInt("pagesDone", 0)
                    val total = if (work.state == androidx.work.WorkInfo.State.SUCCEEDED) {
                        work.outputData.getInt("pageCount", work.progress.getInt("pageCount", 0))
                    } else work.progress.getInt("pageCount", 0)
                    val fraction = if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else null
                    val value = when (work.state) {
                        androidx.work.WorkInfo.State.ENQUEUED,
                        androidx.work.WorkInfo.State.BLOCKED -> ResourceDownloadUiState("डाउनलोड कतार में है", fraction, true)
                        androidx.work.WorkInfo.State.RUNNING -> ResourceDownloadUiState(
                            if (total > 0) "डाउनलोड: " + done + " / " + total + " पृष्ठ" else "डाउनलोड चल रहा है",
                            fraction, true
                        )
                        androidx.work.WorkInfo.State.SUCCEEDED -> ResourceDownloadUiState("डाउनलोड पूरा", 1f, false)
                        androidx.work.WorkInfo.State.FAILED -> ResourceDownloadUiState("डाउनलोड विफल — फिर प्रयास करें", null, false)
                        androidx.work.WorkInfo.State.CANCELLED -> ResourceDownloadUiState("डाउनलोड रद्द", null, false)
                    }
                    nextStates[documentId] = value
                }
                resourceDownloadStates.clear()
                resourceDownloadStates.putAll(nextStates)
                delay(1200)
            }
        } else {
            resourceDownloadStates.clear()
        }
    }

    fun reportError(exception: Exception) {
        error = when (exception) {
            is ApiFailure -> exception.message
            is IOException -> "QuantaEdge server से संपर्क नहीं हो पाया। नेटवर्क और API URL जाँचें।"
            else -> exception.message?.takeIf { it.isNotBlank() } ?: "कुछ गलत हो गया। दोबारा प्रयास करें।"
        }
    }

    fun runTask(action: suspend () -> Unit) {
        scope.launch {
            busy = true
            error = ""
            try {
                action()
            } catch (exception: Exception) {
                reportError(exception)
            } finally {
                busy = false
            }
        }
    }

    suspend fun enterSignedInHome(user: JSONObject) {
        auth = user
        val role = user.textAny("role").uppercase()
        if (role.contains("PARENT") || role.contains("GUARDIAN")) {
            parentAccount = ApiClient.getObject("/api/v1/guardians/account")
            parentChildren = ApiClient.getArray("/api/v1/guardians/children").objectItems()
            route = AppRoute.PARENT_HOME
        } else if (role.contains("STUDENT")) {
            studentProfile = ApiClient.getObject("/api/v1/students/me")
            // A profile language selects learning content, not the application UI.
            recommendation = runCatching { ApiClient.getObject("/api/v1/recommendations/next") }.getOrDefault(JSONObject())
            route = AppRoute.STUDENT_HOME
        } else {
            ApiClient.postObject("/api/v1/auth/logout")
            ApiClient.clearSession()
            throw IOException("यह Android ऐप छात्र और अभिभावक खातों के लिए है।")
        }
    }

    suspend fun refreshUser() {
        val user = ApiClient.getObject("/api/v1/auth/me")
        enterSignedInHome(user)
    }

    LaunchedEffect(Unit) {
        runTask {
            try {
                refreshUser()
            } catch (exception: Exception) {
                route = AppRoute.LOGIN
                if (exception !is ApiFailure || (exception.statusCode != 401 && exception.statusCode != 403)) {
                    reportError(exception)
                }
            }
        }
    }

    fun signIn(mode: String, mobile: String, parentMobile: String, username: String, password: String) {
        runTask {
            if (mode == "PARENT") {
                ApiClient.postObject("/api/v1/auth/parent-login", JSONObject()
                    .put("mobile", mobile.trim()).put("password", password))
            } else {
                ApiClient.postObject("/api/v1/auth/student-login", JSONObject()
                    .put("parentMobile", parentMobile.trim())
                    .put("username", username.trim()).put("password", password))
            }
            refreshUser()
        }
    }

    fun requestParentOtp(purpose: String, mobile: String, onRequested: (String?) -> Unit) {
        runTask {
            val response = ApiClient.postObject("/api/v1/auth/request-otp",
                JSONObject().put("mobile", mobile.trim()).put("purpose", purpose))
            val devCode = response.optString("devCode").takeIf { it.isNotBlank() && it != "null" }
            status = if (devCode != null) "विकास वातावरण OTP: " + devCode
                else "OTP भेजने का अनुरोध स्वीकार हुआ। संदेश में आए OTP को भरें।"
            onRequested(devCode)
        }
    }

    fun verifyParentOtp(purpose: String, mobile: String, displayName: String, otp: String, password: String) {
        runTask {
            val body = JSONObject().put("mobile", mobile.trim()).put("otp", otp.trim())
                .put("purpose", purpose).put("password", password)
            if (purpose == "SIGNUP") body.put("displayName", displayName.trim())
            ApiClient.postObject("/api/v1/auth/verify-otp", body)
            status = if (purpose == "SIGNUP") "अभिभावक खाता बन गया।" else "पासवर्ड अपडेट हो गया।"
            refreshUser()
        }
    }

    fun openSubject(subjectCode: String) {
        currentSubject = subjectCode
        runTask {
            if (studentProfile.length() == 0) studentProfile = ApiClient.getObject("/api/v1/students/me")
            var classCode = studentProfile.textAny("class_code", "classCode")
            if (classCode.isBlank()) {
                val profile = ApiClient.getObject("/api/v1/students/me/profile")
                classCode = profile.textAny("class_code", "classCode")
            }
            if (classCode.isBlank()) throw IOException("आपकी कक्षा API से नहीं मिली। अपने खाते की जानकारी जाँचें।")
            val path = "/api/v1/learning/lessons?classCode=" + ApiClient.encodeQuery(classCode) +
                "&subjectCode=" + ApiClient.encodeQuery(subjectCode) + "&language=" + learningContentLanguage
            lessons = ApiClient.getArray(path).objectItems()
            route = AppRoute.SUBJECT_LESSONS
        }
    }

    fun openLesson(lessonRow: JSONObject) {
        runTask {
            val id = lessonRow.longAny("id", "lesson_id")
            if (id <= 0L) throw IOException("यह पाठ पहचान में नहीं आया।")
            ApiClient.postObject("/api/v1/learning/lessons/" + id + "/start")
            lesson = ApiClient.getObject("/api/v1/learning/lessons/" + id + "?language=" + learningContentLanguage)
            answers.clear()
            feedbacks.clear()
            lessonAnswerLocks.clear()
            route = AppRoute.LESSON
        }
    }

    fun startPractice() {
        runTask {
            val lessonId = lesson.longAny("id", "lesson_id")
            practice = ApiClient.postObject("/api/v1/learning/practice/sessions/from-lesson",
                JSONObject().put("lessonId", lessonId).put("language", learningContentLanguage))
            answers.clear()
            feedbacks.clear()
            route = AppRoute.PRACTICE
        }
    }

    fun openDiagnostic() {
        runTask {
            diagnostic = ApiClient.getObject("/api/v1/diagnostic")
            feedbacks.clear()
            route = AppRoute.DIAGNOSTIC
        }
    }

    fun openAssessments(subjectCode: String = currentSubject) {
        currentSubject = subjectCode
        runTask {
            val local = withContext(Dispatchers.IO) { offlineAssessmentStore.catalogueItems() }
                .filter { it.textAny("subject_code", "subjectCode").isBlank() ||
                    it.textAny("subject_code", "subjectCode") == subjectCode }
            val localIds = local.map { it.optLongAny("assessment_id", "assessmentId", "id") }.toSet()
            val online = runCatching {
                val path = "/api/v1/learning/assessments?subjectCode=" +
                    ApiClient.encodeQuery(subjectCode) + "&language=" + learningContentLanguage
                ApiClient.getObject(path).optJSONArray("items").objectItems()
            }.getOrElse {
                status = if (local.isEmpty()) {
                    "ऑफ़लाइन: पहले से तैयार offline tests उपलब्ध नहीं हैं। नया test डाउनलोड करने के लिए internet चाहिए।"
                } else {
                    "ऑफ़लाइन: पहले से तैयार encrypted test यहीं जारी रखें। उत्तर reconnect होने पर server पर जाँचे जाएँगे।"
                }
                emptyList()
            }
            assessments = local + online.filterNot { it.optLongAny("assessment_id", "assessmentId", "id") in localIds }
            if (local.isNotEmpty() && online.isNotEmpty()) {
                status = "Prepared offline attempts इस device पर उपलब्ध हैं; नए tests के लिए server catalogue sync है।"
            }
            route = AppRoute.ASSESSMENTS
        }
    }

    fun restoreOfflineAssessment(record: JSONObject) {
        val syncedResult = record.optJSONObject("syncResult")
        val storedAttempt = record.optJSONObject("attempt")
            ?: throw IOException("इस offline test का saved snapshot पढ़ा नहीं जा सका।")
        assessmentAttempt = JSONObject((syncedResult ?: storedAttempt).toString())
        assessmentAttempt.put("offlinePendingSync", record.optString("state") == "QUEUED" ||
            record.optString("state") == "SYNC_ERROR")
        answers.clear()
        val storedAnswers = record.optJSONObject("answers") ?: JSONObject()
        val keys = storedAnswers.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            if (!storedAnswers.isNull(key)) answers[key] = storedAnswers.optString(key)
        }
        val syncError = record.optString("lastError")
        status = when (record.optString("state")) {
            "QUEUED" -> "उत्तर इस device पर encrypted हैं और internet पर sync होने की प्रतीक्षा में हैं।"
            "SYNC_ERROR" -> "Offline answer sync में समस्या: " + syncError + " — internet आने पर ‘Sync की कोशिश करें’ दबाएँ।"
            "SYNCED" -> if (assessmentAttempt.optBoolean("resultReleased")) {
                "Server पर परिणाम जारी हो गया है। नीचे final marks और प्रश्न-समीक्षा देखें।"
            } else if (assessmentAttempt.textAny("status") == "AWAITING_REVIEW") {
                "Answers server पर submit हो चुके हैं। Teacher review के बाद final result दिखाई देगा।"
            } else {
                "Offline answers server पर sync हो गए हैं।"
            }
            else -> "यह test इस device पर encrypted है। उत्तर server पर sync होने से पहले result नहीं दिया जाएगा।"
        }
    }


    fun openAssessment(item: JSONObject) {
        runTask {
            val assessmentId = item.longAny("assessment_id", "assessmentId", "id")
            if (assessmentId <= 0L) throw IOException("इस टेस्ट की पहचान नहीं मिली।")
            val localRecord = withContext(Dispatchers.IO) {
                val localId = item.optLongAny("in_progress_attempt_id", "attempt_id")
                if (item.optBoolean("offline_local") && localId > 0L) offlineAssessmentStore.loadRecord(localId)
                else offlineAssessmentStore.recordForAssessment(assessmentId)
            }
            if (localRecord != null) {
                val localAttempt = localRecord.optJSONObject("attempt") ?: JSONObject()
                val localAttemptId = localAttempt.longAny("attempt_id", "attemptId", "id")
                val localState = localRecord.textAny("state")
                val serverAttempt = if (localAttemptId > 0L &&
                    localState in setOf("QUEUED", "SYNC_ERROR", "SYNCED")) {
                    try {
                        ApiClient.getObject("/api/v1/learning/assessment-attempts/" + localAttemptId)
                    } catch (_: Exception) {
                        null
                    }
                } else null
                if (serverAttempt != null && serverAttempt.textAny("status") != "IN_PROGRESS") {
                    withContext(Dispatchers.IO) {
                        offlineAssessmentStore.markSynced(localAttemptId, serverAttempt)
                    }
                    assessmentAttempt = serverAttempt
                    answers.clear()
                    feedbacks.clear()
                    status = if (serverAttempt.optBoolean("resultReleased")) {
                        "Server पर offline test का result जारी हो गया है।"
                    } else {
                        "Answers server पर submit हो चुके हैं। Teacher review के बाद final result दिखाई देगा।"
                    }
                } else {
                    restoreOfflineAssessment(localRecord)
                }
                route = AppRoute.ASSESSMENT_ATTEMPT
                return@runTask
            }
            assessmentAttempt = ApiClient.postObject(
                "/api/v1/learning/assessments/" + assessmentId + "/attempts?language=" + learningContentLanguage
            )
            answers.clear()
            val attemptId = assessmentAttempt.longAny("attempt_id", "attemptId", "id")
            if (assessmentAttempt.optBoolean("offlineMode")) {
                if (assessmentAttempt.textAny("offlinePackageToken").isBlank()) {
                    val refreshed = ApiClient.postObject(
                        "/api/v1/learning/assessment-attempts/" + attemptId + "/offline-package-token"
                    )
                    val token = refreshed.textAny("offlinePackageToken")
                    if (token.isBlank()) throw IOException("सुरक्षित offline package token server से नहीं मिला।")
                    assessmentAttempt.put("offlinePackageToken", token)
                }
                withContext(Dispatchers.IO) {
                    offlineAssessmentStore.saveAttempt(assessmentAttempt, emptyMap(), "DRAFT", item)
                }
                OfflineAssessmentSyncWorker.enqueueIfPending(context.applicationContext, resourceOwner, offlineAssessmentStore)
            }
            route = AppRoute.ASSESSMENT_ATTEMPT
        }
    }

    fun prepareOfflineAssessment(item: JSONObject) {
        runTask {
            val assessmentId = item.longAny("assessment_id", "assessmentId", "id")
            if (assessmentId <= 0L) throw IOException("इस टेस्ट की पहचान नहीं मिली।")
            if (!item.optBoolean("offline_enabled")) throw IOException("यह test offline उपयोग के लिए अनुमत नहीं है।")
            val attempt = ApiClient.postObject(
                "/api/v1/learning/assessments/" + assessmentId +
                    "/attempts?language=" + learningContentLanguage + "&offlineMode=true"
            )
            if (!attempt.optBoolean("offlineMode") ||
                attempt.textAny("offlinePackageToken").isBlank()) {
                throw IOException("सुरक्षित offline package server से नहीं मिला।")
            }
            assessmentAttempt = attempt
            answers.clear()
            val restored = withContext(Dispatchers.IO) {
                offlineAssessmentStore.saveAttempt(attempt, emptyMap(), "DRAFT", item)
            }
            route = AppRoute.ASSESSMENT_ATTEMPT
            status = "टेस्ट encrypted storage में तैयार है। Timer server पर शुरू हो चुका है; timer के बाद 72 घंटे के भीतर internet मिलने पर answers sync करें।"
        }
    }


    suspend fun loadAssessmentResultsData() {
        val loaded = ApiClient.getObject("/api/v1/learning/assessment-results?language=" + learningContentLanguage)
            .optJSONArray("items").objectItems()
        assessmentResults = loaded
        val attemptIds = loaded.flatMap { result ->
            val attempts = result.optJSONArray("attempts").objectItems()
            if (attempts.isNotEmpty()) attempts else listOf(result)
        }.map { it.optLongAny("attempt_id", "attemptId") }
            .filter { it > 0L }.distinct().take(100)
        assessmentCertificateEligibility = if (attemptIds.isEmpty()) emptyMap() else try {
            ApiClient.getObject(
                "/api/v1/students/me/certificates/assessment-eligibility?attemptIds=" + attemptIds.joinToString(",")
            ).optJSONArray("items").objectItems().mapNotNull { item ->
                val id = item.optLongAny("attempt_id", "attemptId")
                if (id > 0L) id to item else null
            }.toMap()
        } catch (_: Exception) {
            // Keep released result history usable even if the certificate policy endpoint is down.
            emptyMap()
        }
    }

    fun loadAssessmentResults() {
        runTask {
            loadAssessmentResultsData()
            route = AppRoute.ASSESSMENT_RESULTS
        }
    }

    fun issueCertificateForAttempt(attemptId: Long) {
        runTask {
            if (attemptId <= 0L) throw IOException("Assessment attempt could not be identified.")
            ApiClient.postObject(
                "/api/v1/students/me/certificates/issue",
                JSONObject()
                    .put("achievementType", "ASSESSMENT_PASS")
                    .put("assessmentAttemptId", attemptId)
                    .put("templateId", "CLASSIC")
                    .put("language", learningContentLanguage)
            )
            loadAssessmentResultsData()
            status = "Certificate issued. You can now save its PDF or PNG from this result."
        }
    }

    fun downloadResultCertificate(certificateId: Long, format: String) {
        runTask {
            if (certificateId <= 0L) throw IOException("Certificate record could not be identified.")
            if (format != "pdf" && format != "png") throw IOException("Choose PDF or PNG.")
            val bytes = withContext(Dispatchers.IO) {
                ApiClient.getBytes("/api/v1/students/me/certificates/" + certificateId + "/download?format=" + format)
            }
            if (bytes.isEmpty()) throw IOException("The server returned an empty certificate file.")
            val certificateNumber = assessmentCertificateEligibility.values
                .firstOrNull { it.optLongAny("certificate_id", "certificateId") == certificateId }
                ?.optString("certificate_number").orEmpty()
                .replace(Regex("[^A-Za-z0-9_-]"), "_")
                .ifBlank { certificateId.toString() }
            pendingCertificateBytes = bytes
            pendingCertificateFilename = "quantaedge-$certificateNumber.$format"
            if (format == "pdf") resultCertificatePdfLauncher.launch(pendingCertificateFilename)
            else resultCertificatePngLauncher.launch(pendingCertificateFilename)
        }
    }

    fun openAssessmentReview(attemptId: Long) {
        runTask {
            if (attemptId <= 0L) throw IOException("Result attempt की पहचान नहीं मिली।")
            assessmentAttempt = ApiClient.getObject("/api/v1/learning/assessment-attempts/" + attemptId)
            answers.clear()
            feedbacks.clear()
            route = AppRoute.ASSESSMENT_REVIEW
        }
    }

    fun openClasses(subjectCode: String = currentSubject) {
        currentSubject = subjectCode
        runTask {
            liveClasses = ApiClient.getObject(
                "/api/v1/learning/live-classes?subjectCode=" + ApiClient.encodeQuery(subjectCode)
            ).optJSONArray("items").objectItems()
            recordedClasses = ApiClient.getObject(
                "/api/v1/learning/recorded-classes?subjectCode=" + ApiClient.encodeQuery(subjectCode)
            ).optJSONArray("items").objectItems()
            route = AppRoute.CLASSES
        }
    }

    fun joinLiveClass(item: JSONObject) {
        runTask {
            val sessionId = item.longAny("session_id", "sessionId")
            val joined = ApiClient.postObject("/api/v1/learning/live-classes/" + sessionId + "/join")
            val joinUrl = joined.textAny("joinUrl", "join_url")
            if (joinUrl.isNotBlank()) {
                val uri = android.net.Uri.parse(joinUrl)
                if (uri.scheme != "https" && uri.scheme != "http") {
                    throw IOException("इस live class का सुरक्षित meeting link उपलब्ध नहीं है।")
                }
                context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, uri)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            liveClasses = liveClasses.map { row ->
                if (row.longAny("session_id", "sessionId") == sessionId)
                    JSONObject(row.toString()).put("currently_joined", true)
                else row
            }
            status = if (joinUrl.isBlank())
                "Attendance दर्ज हो गई है, लेकिन इस session के लिए meeting link उपलब्ध नहीं है।"
                else "Live class में शामिल होने की attendance दर्ज हो गई है।"
        }
    }

    fun leaveLiveClass(item: JSONObject) {
        runTask {
            val sessionId = item.longAny("session_id", "sessionId")
            ApiClient.postObject("/api/v1/learning/live-classes/" + sessionId + "/leave")
            liveClasses = liveClasses.map { row ->
                if (row.longAny("session_id", "sessionId") == sessionId)
                    JSONObject(row.toString()).put("currently_joined", false)
                else row
            }
            status = "आपकी live class leave और attendance update हो गई।"
        }
    }

    fun openTutor() {
        runTask {
            val lessonId = lesson.longAny("id", "lesson_id")
            if (lessonId <= 0L) throw IOException("Tutor शुरू करने के लिए पहले पाठ खोलें।")
            tutorSession = ApiClient.postObject("/api/v1/tutor/sessions", JSONObject().put("lessonId", lessonId))
            tutorAvailable = tutorSession.optBoolean("available")
            tutorMessages = if (tutorAvailable) {
                ApiClient.getObject("/api/v1/tutor/sessions/" + tutorSession.longAny("sessionId", "session_id") + "/messages?limit=30")
                    .optJSONArray("items").objectItems()
            } else emptyList()
            route = AppRoute.TUTOR
            if (!tutorAvailable) status = "AI Tutor अभी configured नहीं है। यह setting backend पर enable होने पर उपलब्ध होगा।"
        }
    }

    fun sendTutorMessage(message: String, mode: String) {
        runTask {
            val sessionId = tutorSession.longAny("sessionId", "session_id")
            ApiClient.postObject("/api/v1/tutor/sessions/" + sessionId + "/messages",
                JSONObject().put("message", message).put("mode", mode).put("questionId", 0))
            tutorMessages = ApiClient.getObject("/api/v1/tutor/sessions/" + sessionId + "/messages?limit=30")
                .optJSONArray("items").objectItems()
        }
    }

    fun openRecording(item: JSONObject) {
        runTask {
            val id = item.longAny("recorded_class_id", "recordingId", "id")
            if (id <= 0L) throw IOException("Recording की पहचान नहीं मिली।")
            selectedRecording = ApiClient.getObject("/api/v1/learning/recorded-classes/" + id)
            route = AppRoute.RECORDING_PLAYER
        }
    }

    fun saveRecordingProgress(positionSeconds: Long, watchedSeconds: Long, completed: Boolean) {
        runTask {
            val id = selectedRecording.longAny("recorded_class_id", "recordingId", "id")
            ApiClient.putObject("/api/v1/learning/recorded-classes/" + id + "/progress",
                JSONObject().put("positionSeconds", positionSeconds.coerceAtLeast(0))
                    .put("watchedSeconds", watchedSeconds.coerceAtLeast(0)).put("completed", completed))
            recordedClasses = recordedClasses.map { row ->
                if (row.longAny("recorded_class_id", "recordingId", "id") == id) {
                    JSONObject(row.toString())
                        .put("last_position_seconds", positionSeconds)
                        .put("watched_seconds", watchedSeconds)
                        .put("completed", completed)
                } else row
            }
        }
    }

    fun saveRecordingProgressAndBack(positionSeconds: Long, watchedSeconds: Long, completed: Boolean) {
        runTask {
            val id = selectedRecording.longAny("recorded_class_id", "recordingId", "id")
            ApiClient.putObject("/api/v1/learning/recorded-classes/" + id + "/progress",
                JSONObject().put("positionSeconds", positionSeconds.coerceAtLeast(0))
                    .put("watchedSeconds", watchedSeconds.coerceAtLeast(0)).put("completed", completed))
            recordedClasses = recordedClasses.map { row ->
                if (row.longAny("recorded_class_id", "recordingId", "id") == id) {
                    JSONObject(row.toString())
                        .put("last_position_seconds", positionSeconds)
                        .put("watched_seconds", watchedSeconds)
                        .put("completed", completed)
                } else row
            }
            route = AppRoute.CLASSES
        }
    }

    fun openResources(subjectCode: String = currentSubject) {
        currentSubject = subjectCode
        runTask {
            val local = resourceStore.downloadedDocuments()
            val online = runCatching {
                val path = "/api/v1/learning/documents?subjectCode=" + ApiClient.encodeQuery(subjectCode) + "&language=" + learningContentLanguage
                ApiClient.getArray(path).objectItems()
            }.getOrElse {
                status = "ऑफ़लाइन: केवल इस डिवाइस पर पहले डाउनलोड की गई सामग्री दिखाई जा रही है।"
                emptyList()
            }
            val combined = (online + local).distinctBy { it.optLongAny("document_id", "id") }
                .filter { it.textAny("subject_code", "subjectCode").isBlank() || it.textAny("subject_code", "subjectCode") == subjectCode }
                .filter { it.textAny("language").isBlank() || it.textAny("language") == learningContentLanguage }
            documents = combined
            route = AppRoute.RESOURCES
        }
    }

    suspend fun loadPage(page: Int) {
        val documentId = selectedDocument.optLongAny("document_id", "id")
        val pageCount = selectedDocument.optInt("page_count",
            (selectedDocument.optInt("page_end", 1) - selectedDocument.optInt("page_start", 1) + 1).coerceAtLeast(1))
        if (documentId <= 0L || page < 1 || page > pageCount) throw IOException("यह पृष्ठ उपलब्ध नहीं है।")
        documentPage = page
        val cachedPage = resourceStore.readPage(documentId, page)
        val bytes = cachedPage ?: ApiClient.getBytes(
            "/api/v1/learning/documents/" + documentId + "/pages/" + page
        ).also { resourceStore.savePage(documentId, page, it) }
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            ?: throw IOException("पृष्ठ की छवि पढ़ी नहीं जा सकी।")
        documentImage = bitmap.asImageBitmap()
        runCatching {
            ApiClient.putObject("/api/v1/learning/documents/" + documentId + "/progress",
                JSONObject().put("lastPage", page))
        }
    }

    fun openDocument(document: JSONObject) {
        runTask {
            selectedDocument = JSONObject(document.toString())
            documentPage = document.optInt("last_page", 1).coerceAtLeast(1)
            loadPage(documentPage)
            route = AppRoute.DOCUMENT_READER
        }
    }

    fun downloadDocument(document: JSONObject) {
        runTask {
            val doc = JSONObject(document.toString())
            val id = doc.optLongAny("document_id", "id")
            val count = doc.optInt("page_count",
                (doc.optInt("page_end", 1) - doc.optInt("page_start", 1) + 1).coerceAtLeast(1))
            if (id <= 0L || count <= 0) throw IOException("डाउनलोड की जानकारी उपलब्ध नहीं है।")
            resourceStore.saveDocument(doc)
            com.quantaedge.android.data.ProtectedResourceDownloadWorker.enqueue(context, resourceOwner, id)
            status = "सुरक्षित डाउनलोड बैकग्राउंड में शुरू हुआ। प्रगति देखने के लिए लाइब्रेरी रिफ्रेश करें।"
            documents = documents.map { if (it.optLongAny("document_id", "id") == id) doc else it }
        }
    }

    fun removeOfflineDownload(document: JSONObject) {
        runTask {
            val id = document.optLongAny("document_id", "id")
            if (id <= 0L) throw IOException("पुस्तक की पहचान नहीं मिली।")
            val uniqueWorkName = "qe-resource-" + resourceOwner.take(16) + "-" + id
            WorkManager.getInstance(context.applicationContext).cancelUniqueWork(uniqueWorkName)
            withContext(Dispatchers.IO) { resourceStore.removeOfflineCopy(id) }
            val online = runCatching {
                ApiClient.getArray(
                    "/api/v1/learning/documents?subjectCode=" + ApiClient.encodeQuery(currentSubject) +
                        "&language=" + learningContentLanguage
                ).objectItems()
            }.getOrDefault(emptyList())
            documents = (online + resourceStore.downloadedDocuments())
                .distinctBy { it.optLongAny("document_id", "id") }
                .filter { it.textAny("subject_code", "subjectCode").isBlank() ||
                    it.textAny("subject_code", "subjectCode") == currentSubject }
                .filter { it.textAny("language").isBlank() || it.textAny("language") == learningContentLanguage }
            resourceDownloadStates.remove(id)
            status = "इस डिवाइस से offline copy हटाई गई। Server पर मूल पुस्तक नहीं बदली गई।"
        }
    }

    fun saveLessonAnswer(question: JSONObject, answer: String) {
        runTask {
            val id = question.longAny("id", "questionId", "question_id")
            if (id <= 0L) throw IOException("प्रश्न की पहचान नहीं मिली।")
            val result = ApiClient.postObject(
                "/api/v1/learning/questions/" + id + "/answer?language=" + learningContentLanguage,
                JSONObject().put("answer", answer)
            )
            val feedback = result.textAny("feedback").ifBlank {
                if (result.isNull("correct")) "उत्तर सुरक्षित हुआ; मूल्यांकन बाकी है।"
                else if (result.optBoolean("correct")) "सही उत्तर।"
                else "उत्तर सुरक्षित हुआ। दोबारा समझकर प्रयास करें।"
            }
            val answerKey = id.toString()
            val hasGrade = result.has("correct") && !result.isNull("correct")
            lessonAnswerLocks[answerKey] = com.quantaedge.android.domain.LessonAnswerPolicy.shouldLock(
                if (hasGrade) result.optBoolean("correct") else null
            )
            feedbacks[answerKey] = feedback
            lesson = ApiClient.getObject("/api/v1/learning/lessons/" + lesson.longAny("id") + "?language=" + learningContentLanguage)
        }
    }

    fun savePracticeAnswer(question: JSONObject, answer: String) {
        runTask {
            val questionId = question.longAny("id", "questionId", "question_id")
            val sessionId = practice.longAny("sessionId", "session_id")
            if (questionId <= 0L || sessionId <= 0L) throw IOException("Practice session की जानकारी अधूरी है।")
            val response = ApiClient.postObject(
                "/api/v1/learning/questions/" + questionId + "/answer?language=" + learningContentLanguage,
                JSONObject().put("answer", answer).put("practiceSessionId", sessionId)
            )
            feedbacks[questionId.toString()] = response.textAny("feedback").ifBlank {
                if (response.isNull("correct")) "उत्तर सुरक्षित हुआ; मूल्यांकन बाकी है।"
                else if (response.optBoolean("correct")) "सही उत्तर।"
                else "उत्तर सही नहीं था। फिर प्रयास करें।"
            }
            practice = ApiClient.getObject("/api/v1/learning/practice/sessions/" + sessionId + "?language=" + learningContentLanguage)
        }
    }

    fun saveDiagnosticAnswer(question: JSONObject, answer: String) {
        runTask {
            val attemptId = diagnostic.longAny("attemptId", "attempt_id")
            val questionId = question.longAny("id", "questionId", "question_id")
            val response = ApiClient.postObject(
                "/api/v1/diagnostic/attempts/" + attemptId + "/answers/" + questionId,
                JSONObject().put("answer", answer)
            )
            feedbacks[questionId.toString()] = response.textAny("feedback").ifBlank { "उत्तर सुरक्षित हुआ।" }
            diagnostic = ApiClient.getObject("/api/v1/diagnostic")
        }
    }

    fun submitDiagnostic() {
        runTask {
            val id = diagnostic.longAny("attemptId", "attempt_id")
            ApiClient.postObject("/api/v1/diagnostic/attempts/" + id + "/submit")
            diagnostic = ApiClient.getObject("/api/v1/diagnostic")
            recommendation = runCatching { ApiClient.getObject("/api/v1/recommendations/next") }.getOrDefault(JSONObject())
            status = "Diagnostic पूरा हुआ। आपकी अगली सीख सर्वर के डेटा के आधार पर दिखाई जाएगी।"
        }
    }

    fun saveAssessmentAnswer(question: JSONObject, answer: String) {
        runTask {
            val attemptId = assessmentAttempt.longAny("attempt_id", "attemptId", "id")
            val questionId = question.longAny("attemptQuestionId", "attempt_question_id")
            if (assessmentAttempt.optBoolean("offlineMode")) {
                val copy = answers.toMutableMap().apply { put(questionId.toString(), answer) }
                withContext(Dispatchers.IO) {
                    offlineAssessmentStore.saveAttempt(assessmentAttempt, copy, "DRAFT")
                }
                feedbacks[questionId.toString()] = "उत्तर इस device पर encrypted सुरक्षित है।"
                return@runTask
            }
            ApiClient.putObject(
                "/api/v1/learning/assessment-attempts/" + attemptId + "/answers/" + questionId,
                JSONObject().put("answer", answer)
            )
            feedbacks[questionId.toString()] = "उत्तर सर्वर पर सुरक्षित है।"
        }
    }

    fun submitAssessment() {
        runTask {
            val id = assessmentAttempt.longAny("attempt_id", "attemptId", "id")
            if (assessmentAttempt.optBoolean("offlinePendingSync")) {
                val localRecord = withContext(Dispatchers.IO) { offlineAssessmentStore.recordForAttempt(id) }
                val state = localRecord?.optString("state")
                val catalog = localRecord?.optJSONObject("catalogueItem") ?: JSONObject()
                if (state == "SYNC_ERROR") {
                    val refreshed = ApiClient.postObject(
                        "/api/v1/learning/assessment-attempts/" + id + "/offline-package-token"
                    )
                    val token = refreshed.textAny("offlinePackageToken")
                    if (token.isBlank()) throw IOException("Server ने protected offline token refresh नहीं किया।")
                    assessmentAttempt.put("offlinePackageToken", token)
                }
                withContext(Dispatchers.IO) {
                    offlineAssessmentStore.saveAttempt(
                        assessmentAttempt,
                        answers.toMap(),
                        "QUEUED",
                        catalog
                    )
                }
                OfflineAssessmentSyncWorker.enqueue(context.applicationContext, resourceOwner)
                status = "Offline answers sync queue में हैं। Result server grading के बाद मिलेगा।"
                return@runTask
            }
            if (assessmentAttempt.optBoolean("offlineMode")) {
                val queued = JSONObject(assessmentAttempt.toString()).put("offlinePendingSync", true)
                withContext(Dispatchers.IO) {
                    offlineAssessmentStore.saveAttempt(queued, answers.toMap(), "QUEUED")
                }
                assessmentAttempt = queued
                OfflineAssessmentSyncWorker.enqueue(context.applicationContext, resourceOwner)
                status = "उत्तर इस device पर encrypted सुरक्षित हैं। Internet उपलब्ध होने पर server पर sync होंगे; अभी कोई स्थानीय score नहीं दिखाया जाएगा।"
                return@runTask
            }
            assessmentAttempt = ApiClient.postObject(
                "/api/v1/learning/assessment-attempts/" + id + "/submit"
            )
            route = AppRoute.ASSESSMENT_ATTEMPT
            if (assessmentAttempt.optBoolean("resultReleased")) {
                runCatching { loadAssessmentResultsData() }
            }
            status = if (assessmentAttempt.optBoolean("resultReleased")) {
                "टेस्ट जमा हुआ और परिणाम जारी है।"
            } else {
                "टेस्ट जमा हुआ। लिखित उत्तरों के मूल्यांकन के बाद परिणाम उपलब्ध होगा।"
            }
        }
    }

    fun refreshParentData() {
        runTask {
            parentAccount = ApiClient.getObject("/api/v1/guardians/account")
            parentChildren = ApiClient.getArray("/api/v1/guardians/children").objectItems()
        }
    }

    fun openStudentProfile() {
        runTask {
            studentProfile = ApiClient.getObject("/api/v1/students/me/profile")
            route = AppRoute.PROFILE
        }
    }

    fun saveStudentProfile(body: JSONObject) {
        runTask {
            val profile = ApiClient.putObject("/api/v1/students/me/profile", body)
            studentProfile = ApiClient.getObject("/api/v1/students/me/profile")
            auth = JSONObject(auth.toString()).put("displayName", profile.textAny("display_name", "displayName"))
            status = "छात्र प्रोफ़ाइल सुरक्षित हो गई।"
            route = AppRoute.PROFILE
        }
    }

    fun saveStudentPhoto(dataUrl: String) {
        runTask {
            ApiClient.putObject("/api/v1/students/me/profile-photo",
                JSONObject().put("profileImageDataUrl", dataUrl))
            studentProfile = ApiClient.getObject("/api/v1/students/me/profile")
            status = "प्रोफ़ाइल फोटो सुरक्षित हो गई।"
        }
    }

    fun openProgress() {
        runTask {
            progressData = ApiClient.getObject("/api/v1/students/me")
            route = AppRoute.PROGRESS
        }
    }

        fun openParentProfile() {
        runTask {
            parentProfile = ApiClient.getObject("/api/v1/guardians/profile")
            route = AppRoute.PARENT_PROFILE
        }
    }

    fun saveParentProfile(body: JSONObject) {
        runTask {
            parentProfile = ApiClient.putObject("/api/v1/guardians/profile", body)
            parentAccount = ApiClient.getObject("/api/v1/guardians/account")
            status = "अभिभावक प्रोफ़ाइल सुरक्षित हो गई।"
            route = AppRoute.PARENT_HOME
        }
    }

    fun saveParentPhoto(dataUrl: String) {
        runTask {
            ApiClient.putObject("/api/v1/guardians/profile-photo",
                JSONObject().put("profileImageDataUrl", dataUrl))
            parentProfile = ApiClient.getObject("/api/v1/guardians/profile")
            status = "अभिभावक की प्रोफ़ाइल फोटो सुरक्षित हो गई।"
        }
    }

    fun openCreateChild() {
        route = AppRoute.PARENT_CHILD_CREATE
        error = ""
    }

    fun createChild(name: String, classCode: String, username: String, password: String, tracks: List<String>, consent: Boolean) {
        runTask {
            if (tracks.isEmpty()) throw IOException("कम-से-कम एक विषय चुनें।")
            ApiClient.postObject("/api/v1/guardians/children",
                JSONObject()
                    .put("displayName", name.trim())
                    .put("classCode", classCode)
                    .put("language", "hi")
                    .put("username", username.trim())
                    .put("password", password)
                    .put("trackCodes", JSONArray(tracks))
                    .put("consentAccepted", consent))
            parentAccount = ApiClient.getObject("/api/v1/guardians/account")
            parentChildren = ApiClient.getArray("/api/v1/guardians/children").objectItems()
            status = "छात्र प्रोफ़ाइल बन गई। उसके login credentials सुरक्षित रखें।"
            route = AppRoute.PARENT_HOME
        }
    }

    fun openManageChild(child: JSONObject) {
        selectedChild = JSONObject(child.toString())
        route = AppRoute.PARENT_CHILD_MANAGE
        error = ""
    }

    fun saveChildCredentials(username: String, password: String) {
        runTask {
            val id = selectedChild.longAny("id")
            if (username.trim().isBlank() || password.length < 8) throw IOException("यूज़रनेम और कम-से-कम 8 अक्षरों का नया पासवर्ड भरें।")
            ApiClient.putObject("/api/v1/guardians/children/" + id + "/credentials",
                JSONObject().put("username", username.trim()).put("password", password))
            parentChildren = ApiClient.getArray("/api/v1/guardians/children").objectItems()
            selectedChild = parentChildren.firstOrNull { it.longAny("id") == id } ?: selectedChild
            status = "छात्र के login credentials अपडेट हो गए।"
        }
    }

    fun saveChildTracks(tracks: List<String>) {
        runTask {
            val id = selectedChild.longAny("id")
            if (tracks.isEmpty()) throw IOException("कम-से-कम एक विषय चुनें।")
            ApiClient.putObject("/api/v1/guardians/children/" + id + "/tracks",
                JSONObject().put("trackCodes", JSONArray(tracks)))
            parentChildren = ApiClient.getArray("/api/v1/guardians/children").objectItems()
            selectedChild = parentChildren.firstOrNull { it.longAny("id") == id } ?: selectedChild
            status = "छात्र के विषय access अपडेट हो गए।"
        }
    }

    fun archiveSelectedChild() {
        runTask {
            val id = selectedChild.longAny("id")
            if (id <= 0L) throw IOException("छात्र प्रोफ़ाइल की पहचान नहीं मिली।")
            ApiClient.deleteObject("/api/v1/guardians/children/" + id)
            parentAccount = ApiClient.getObject("/api/v1/guardians/account")
            parentChildren = ApiClient.getArray("/api/v1/guardians/children").objectItems()
            selectedChild = JSONObject()
            status = "छात्र प्रोफ़ाइल archive हो गई।"
            route = AppRoute.PARENT_HOME
        }
    }

    fun openParentReport(child: JSONObject) {
        runTask {
            val id = child.longAny("id")
            parentReport = ApiClient.getObject("/api/v1/guardians/children/" + id + "/report")
            parentReport.put("_childName", child.textAny("display_name", "displayName"))
            route = AppRoute.PARENT_REPORT
        }
    }

    fun logout() {
        runTask {
            runCatching { ApiClient.postObject("/api/v1/auth/logout") }
            WorkManager.getInstance(context.applicationContext).cancelAllWorkByTag("quantaedge-resource-download")
            ApiClient.clearSession()
            auth = JSONObject()
            studentProfile = JSONObject()
            parentChildren = emptyList()
            route = AppRoute.LOGIN
            status = "आप सुरक्षित रूप से साइन आउट हो गए।"
        }
    }

    fun toggleLanguage() {
        // Interface locale switches immediately; learning content preference is saved separately.
        language = if (language == "hi") "en" else "hi"
        preferences.edit().putString("language", language).apply()
        error = ""
        status = ""
    }

    fun goBack() {
        route = when (route) {
            AppRoute.STUDENT_HOME, AppRoute.PARENT_HOME -> route
            AppRoute.LOGIN, AppRoute.PARENT_SIGNUP, AppRoute.PARENT_RESET -> AppRoute.LOGIN
            AppRoute.CERTIFICATES, AppRoute.LEADERBOARD -> AppRoute.STUDENT_HOME
            AppRoute.SUBJECT_LESSONS, AppRoute.DIAGNOSTIC, AppRoute.ASSESSMENTS,
            AppRoute.ASSESSMENT_RESULTS, AppRoute.RESOURCES, AppRoute.PROFILE, AppRoute.PROGRESS -> if (auth.textAny("role").contains("PARENT", true)) AppRoute.PARENT_HOME else AppRoute.STUDENT_HOME
            AppRoute.LESSON -> AppRoute.SUBJECT_LESSONS
            AppRoute.PRACTICE -> AppRoute.LESSON
            AppRoute.TUTOR -> AppRoute.LESSON
            AppRoute.CLASSES -> if (auth.textAny("role").contains("PARENT", true)) AppRoute.PARENT_HOME else AppRoute.STUDENT_HOME
            AppRoute.RECORDING_PLAYER -> AppRoute.CLASSES
            AppRoute.ASSESSMENT_ATTEMPT -> AppRoute.ASSESSMENTS
            AppRoute.ASSESSMENT_REVIEW -> AppRoute.ASSESSMENT_RESULTS
            AppRoute.PARENT_REPORT, AppRoute.PARENT_PROFILE, AppRoute.PARENT_CHILD_CREATE,
            AppRoute.PARENT_CHILD_MANAGE -> AppRoute.PARENT_HOME
            AppRoute.DOCUMENT_READER -> AppRoute.RESOURCES
        }
        error = ""
    }

    val wideLayout = LocalConfiguration.current.screenWidthDp >= 600
    val role = auth.textAny("role")
    val parentRole = role.contains("PARENT", true) || role.contains("GUARDIAN", true)
    val signedIn = role.isNotBlank() && route !in setOf(AppRoute.LOGIN, AppRoute.PARENT_SIGNUP, AppRoute.PARENT_RESET)
    val navItems = if (parentRole) listOf(
        AppNavItem("home", if (language == "hi") "होम" else "Home", "⌂"),
        AppNavItem("profile", if (language == "hi") "प्रोफ़ाइल" else "Profile", "◉")
    ) else listOf(
        AppNavItem("home", if (language == "hi") "होम" else "Home", "⌂"),
        AppNavItem("learn", if (language == "hi") "सीखें" else "Learn", "▤"),
        AppNavItem("tests", if (language == "hi") "टेस्ट" else "Tests", "✓"),
        AppNavItem("results", if (language == "hi") "परिणाम" else "Results", "▥"),
        AppNavItem("profile", if (language == "hi") "प्रोफ़ाइल" else "Profile", "◉")
    )
    val selectedNavKey = when {
        parentRole -> if (route == AppRoute.PARENT_PROFILE) "profile" else "home"
        route == AppRoute.PROFILE -> "profile"
        route == AppRoute.ASSESSMENT_RESULTS -> "results"
        route in setOf(AppRoute.ASSESSMENTS, AppRoute.ASSESSMENT_ATTEMPT, AppRoute.ASSESSMENT_REVIEW) -> "tests"
        route in setOf(AppRoute.SUBJECT_LESSONS, AppRoute.LESSON, AppRoute.PRACTICE, AppRoute.DIAGNOSTIC,
            AppRoute.RESOURCES, AppRoute.DOCUMENT_READER, AppRoute.PROGRESS, AppRoute.TUTOR,
            AppRoute.CLASSES, AppRoute.RECORDING_PLAYER) -> "learn"
        else -> "home"
    }
    fun selectPrimaryDestination(key: String) {
        error = ""; status = ""
        when (key) {
            "home" -> route = if (parentRole) AppRoute.PARENT_HOME else AppRoute.STUDENT_HOME
            "learn" -> openSubject(currentSubject)
            "tests" -> openAssessments(currentSubject)
            "results" -> loadAssessmentResults()
            "profile" -> if (parentRole) openParentProfile() else openStudentProfile()
        }
    }
    BackHandler(enabled = signedIn) {
        if (route !in setOf(AppRoute.STUDENT_HOME, AppRoute.PARENT_HOME)) goBack()
    }
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Row(modifier = Modifier.fillMaxSize()) {
            if (signedIn && wideLayout) NavigationRail {
                navItems.forEach { item ->
                    NavigationRailItem(selected = item.key == selectedNavKey, onClick = { selectPrimaryDestination(item.key) },
                        icon = { Text(item.glyph) }, label = { Text(item.label) }, alwaysShowLabel = true)
                }
            }
            Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
            if (route != AppRoute.LOGIN) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (route !in setOf(AppRoute.STUDENT_HOME, AppRoute.PARENT_HOME, AppRoute.RECORDING_PLAYER)) {
                        TextButton(onClick = { goBack() }) { Text("←") }
                    }
                    Image(
                        painter = painterResource(R.drawable.quantaedge_wordmark),
                        contentDescription = "QuantaEdge",
                        modifier = Modifier.width(148.dp).heightIn(max = 42.dp),
                        contentScale = ContentScale.Fit
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    TextButton(onClick = { toggleLanguage() }, enabled = !busy) {
                        Text(if (language == "hi") "EN" else "हिंदी")
                    }
                }
                HorizontalDivider()
                if (route == AppRoute.STUDENT_HOME && auth.textAny("role").contains("STUDENT", true)) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        TextButton(onClick = { route = AppRoute.CERTIFICATES }) {
                            Text(if (language == "hi") "🏅 प्रमाणपत्र" else "🏅 Certificates")
                        }
                        TextButton(onClick = { route = AppRoute.LEADERBOARD }) {
                            Text(if (language == "hi") "🏆 टॉप 20 टेस्ट" else "🏆 Top 20 tests")
                        }
                    }
                }
            }

            if (busy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            if (error.isNotBlank()) {
                Text(
                    text = error,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }
            if (status.isNotBlank()) {
                Text(
                    text = status,
                    color = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)
                )
            }

            Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                Box(modifier = Modifier.widthIn(max = 1120.dp).fillMaxWidth().fillMaxHeight()) {
                when (route) {
                    AppRoute.LOGIN -> LoginScreen(
                        busy = busy,
                        error = error,
                        onSignIn = ::signIn,
                        onSignup = { route = AppRoute.PARENT_SIGNUP },
                        onResetPassword = { route = AppRoute.PARENT_RESET }
                    )
                    AppRoute.PARENT_SIGNUP -> ParentOtpFlowScreen(
                        isSignup = true,
                        busy = busy,
                        status = status,
                        onRequestOtp = { mobile, callback -> requestParentOtp("SIGNUP", mobile, callback) },
                        onVerify = { mobile, name, otp, password -> verifyParentOtp("SIGNUP", mobile, name, otp, password) }
                    )
                    AppRoute.PARENT_RESET -> ParentOtpFlowScreen(
                        isSignup = false,
                        busy = busy,
                        status = status,
                        onRequestOtp = { mobile, callback -> requestParentOtp("PASSWORD_RESET", mobile, callback) },
                        onVerify = { mobile, name, otp, password -> verifyParentOtp("PASSWORD_RESET", mobile, name, otp, password) }
                    )
                    AppRoute.STUDENT_HOME -> StudentHomeScreen(
                        userName = auth.textAny("displayName", "display_name"),
                        profile = studentProfile,
                        recommendation = recommendation,
                        language = language,
                        busy = busy,
                        onSubject = ::openSubject,
                        onDiagnostic = ::openDiagnostic,
                        onTests = ::openAssessments,
                        onResources = ::openResources,
                        onResults = ::loadAssessmentResults,
                        onProfile = ::openStudentProfile,
                        onProgress = ::openProgress,
                        onRefresh = { runTask {
                            studentProfile = ApiClient.getObject("/api/v1/students/me")
                            recommendation = ApiClient.getObject("/api/v1/recommendations/next")
                        } },
                        onOpenRecommendation = { recommended -> openLesson(recommended) },
                        onClasses = ::openClasses
                    )
                    AppRoute.PARENT_HOME -> ParentHomeScreen(
                        parent = parentAccount,
                        children = parentChildren,
                        busy = busy,
                        onRefresh = { runTask {
                            parentAccount = ApiClient.getObject("/api/v1/guardians/account")
                            parentChildren = ApiClient.getArray("/api/v1/guardians/children").objectItems()
                        } },
                        onReport = ::openParentReport,
                        onManageChild = ::openManageChild,
                        onAddChild = ::openCreateChild,
                        onProfile = ::openParentProfile,
                        onLogout = ::logout,
                        maxChildren = parentAccount.optInt("maxChildren", 3)
                    )
                    AppRoute.PARENT_REPORT -> ParentReportScreen(parentReport)
                    AppRoute.PARENT_PROFILE -> ParentProfileScreen(
                        profile = parentProfile,
                        busy = busy,
                        onSave = ::saveParentProfile,
                        onUploadPhoto = ::saveParentPhoto
                    )
                    AppRoute.PARENT_CHILD_CREATE -> ParentChildCreateScreen(
                        busy = busy,
                        remainingSlots = parentAccount.optInt("remainingSlots", 1),
                        onCreate = ::createChild
                    )
                    AppRoute.PARENT_CHILD_MANAGE -> ParentChildManageScreen(
                        child = selectedChild,
                        busy = busy,
                        onSaveCredentials = ::saveChildCredentials,
                        onSaveTracks = ::saveChildTracks,
                        onArchive = ::archiveSelectedChild
                    )
                    AppRoute.SUBJECT_LESSONS -> LessonListScreen(
                        subject = currentSubject,
                        lessons = lessons,
                        language = language,
                        busy = busy,
                        onRefresh = { openSubject(currentSubject) },
                        onOpenLesson = ::openLesson
                    )
                    AppRoute.LESSON -> LessonScreen(
                        lesson = lesson,
                        language = language,
                        answers = answers,
                        feedbacks = feedbacks,
                        answerLocks = lessonAnswerLocks,
                        busy = busy,
                        onAnswerChange = { id, value ->
                            answers[id] = value
                            feedbacks.remove(id)
                        },
                        onAnswer = ::saveLessonAnswer,
                        onPractice = ::startPractice,
                        onTutor = ::openTutor
                    )
                    AppRoute.PRACTICE -> PracticeScreen(
                        practice = practice,
                        answers = answers,
                        feedbacks = feedbacks,
                        busy = busy,
                        onAnswerChange = { id, value -> answers[id] = value },
                        onAnswer = ::savePracticeAnswer
                    )
                    AppRoute.DIAGNOSTIC -> DiagnosticScreen(
                        diagnostic = diagnostic,
                        answers = answers,
                        feedbacks = feedbacks,
                        busy = busy,
                        onAnswerChange = { id, value -> answers[id] = value },
                        onAnswer = ::saveDiagnosticAnswer,
                        onSubmit = ::submitDiagnostic,
                        onRefresh = ::openDiagnostic
                    )
                    AppRoute.ASSESSMENTS -> AssessmentListScreen(
                        subject = currentSubject,
                        assessmentItems = assessments,
                        busy = busy,
                        onSubject = ::openAssessments,
                        onOpen = ::openAssessment,
                        onPrepareOffline = ::prepareOfflineAssessment,
                        onResults = ::loadAssessmentResults,
                        onRefresh = { openAssessments(currentSubject) }
                    )
                    AppRoute.ASSESSMENT_ATTEMPT -> AssessmentAttemptScreen(
                        attempt = assessmentAttempt,
                        answers = answers,
                        feedbacks = feedbacks,
                        certificateEligibility = assessmentCertificateEligibility,
                        language = language,
                        busy = busy,
                        onIssueCertificate = ::issueCertificateForAttempt,
                        onDownloadCertificate = ::downloadResultCertificate,
                        onAnswerChange = { id, value -> answers[id] = value },
                        onSave = ::saveAssessmentAnswer,
                        onSubmit = ::submitAssessment,
                        onRefresh = {
                            runTask {
                                val id = assessmentAttempt.longAny("attempt_id", "attemptId", "id")
                                assessmentAttempt = ApiClient.getObject("/api/v1/learning/assessment-attempts/" + id)
                            }
                        }
                    )
                    AppRoute.ASSESSMENT_RESULTS -> ResultsScreen(
                        resultItems = assessmentResults,
                        certificateEligibility = assessmentCertificateEligibility,
                        language = language,
                        busy = busy,
                        onReview = ::openAssessmentReview,
                        onIssueCertificate = ::issueCertificateForAttempt,
                        onDownloadCertificate = ::downloadResultCertificate
                    )
                    AppRoute.ASSESSMENT_REVIEW -> AssessmentAttemptScreen(
                        attempt = assessmentAttempt,
                        answers = answers,
                        feedbacks = feedbacks,
                        certificateEligibility = assessmentCertificateEligibility,
                        language = language,
                        busy = busy,
                        onIssueCertificate = ::issueCertificateForAttempt,
                        onDownloadCertificate = ::downloadResultCertificate,
                        onAnswerChange = { id, value -> answers[id] = value },
                        onSave = ::saveAssessmentAnswer,
                        onSubmit = ::submitAssessment,
                        onRefresh = {
                            runTask {
                                val id = assessmentAttempt.longAny("attempt_id", "attemptId", "id")
                                assessmentAttempt = ApiClient.getObject("/api/v1/learning/assessment-attempts/" + id)
                            }
                        }
                    )
                    AppRoute.RESOURCES -> ResourcesScreen(
                        documents = documents,
                        subject = currentSubject,
                        store = resourceStore,
                        busy = busy,
                        downloadStatus = status,
                        downloadStates = resourceDownloadStates.toMap(),
                        onSubject = ::openResources,
                        onOpen = ::openDocument,
                        onDownload = ::downloadDocument,
                        onRemoveOffline = ::removeOfflineDownload,
                        onRefresh = { openResources(currentSubject) }
                    )
                    AppRoute.DOCUMENT_READER -> DocumentReaderScreen(
                        document = selectedDocument,
                        page = documentPage,
                        image = documentImage,
                        pageCount = selectedDocument.optInt("page_count",
                            (selectedDocument.optInt("page_end", 1) - selectedDocument.optInt("page_start", 1) + 1).coerceAtLeast(1)),
                        busy = busy,
                        cachedCount = resourceStore.cachedPageCount(
                            selectedDocument.optLongAny("document_id", "id"),
                            selectedDocument.optInt("page_count", 1)
                        ),
                        onPage = { page -> runTask { loadPage(page) } },
                        onDownload = { downloadDocument(selectedDocument) }
                    )
                    AppRoute.PROFILE -> StudentProfileScreen(
                        profile = studentProfile,
                        language = language,
                        busy = busy,
                        onSave = ::saveStudentProfile,
                        onUploadPhoto = ::saveStudentPhoto,
                        onToggleLanguage = ::toggleLanguage,
                        onLogout = ::logout
                    )
                    AppRoute.PROGRESS -> StudentProgressScreen(progressData, busy, ::openProgress)
                    AppRoute.CERTIFICATES -> StudentCertificatesScreen(
                        language = language,
                        onBack = { route = AppRoute.STUDENT_HOME }
                    )
                    AppRoute.LEADERBOARD -> AssessmentLeaderboardScreen(
                        language = language,
                        onBack = { route = AppRoute.STUDENT_HOME }
                    )
                    AppRoute.TUTOR -> TutorScreen(
                        lessonTitle = lesson.textAny("title"),
                        available = tutorAvailable,
                        messages = tutorMessages,
                        busy = busy,
                        error = error,
                        onSend = ::sendTutorMessage
                    )
                    AppRoute.CLASSES -> LearningClassesScreen(
                        subject = currentSubject,
                        liveItems = liveClasses,
                        recordedItems = recordedClasses,
                        busy = busy,
                        onSubject = ::openClasses,
                        onRefresh = { openClasses(currentSubject) },
                        onJoin = ::joinLiveClass,
                        onLeave = ::leaveLiveClass,
                        onRecording = ::openRecording
                    )
                    AppRoute.RECORDING_PLAYER -> RecordedPlayerScreen(
                        recording = selectedRecording,
                        startPositionSeconds = selectedRecording.optLongAny("last_position_seconds", "lastPositionSeconds"),
                        onSaveProgress = ::saveRecordingProgress,
                        onSaveAndBack = ::saveRecordingProgressAndBack
                    )
                }
                }
            }
            if (signedIn && !wideLayout) NavigationBar {
                navItems.forEach { item ->
                    NavigationBarItem(selected = item.key == selectedNavKey, onClick = { selectPrimaryDestination(item.key) },
                        icon = { Text(item.glyph) }, label = { Text(item.label) })
                }
            }
        }
    }
}
}

@Composable
private fun LoginScreen(
    busy: Boolean,
    error: String,
    onSignIn: (String, String, String, String, String) -> Unit,
    onSignup: () -> Unit,
    onResetPassword: () -> Unit
) {
    var mode by remember { mutableStateOf("STUDENT") }
    var mobile by remember { mutableStateOf("") }
    var parentMobile by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 22.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Image(
            painter = painterResource(R.drawable.quantaedge_wordmark),
            contentDescription = "QuantaEdge",
            modifier = Modifier.fillMaxWidth().heightIn(max = 90.dp),
            contentScale = ContentScale.Fit
        )
        Spacer(modifier = Modifier.height(12.dp))
        Text("Smarter Decisions. Greater Growth.", style = MaterialTheme.typography.bodyMedium)
        Text("सीखना शुरू करें", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(top = 22.dp, bottom = 16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (mode == "STUDENT") Button(onClick = { mode = "STUDENT" }, enabled = !busy) { Text("छात्र") }
            else TextButton(onClick = { mode = "STUDENT" }, enabled = !busy) { Text("छात्र") }
            if (mode == "PARENT") Button(onClick = { mode = "PARENT" }, enabled = !busy) { Text("अभिभावक") }
            else TextButton(onClick = { mode = "PARENT" }, enabled = !busy) { Text("अभिभावक") }
        }
        if (mode == "PARENT") {
            OutlinedTextField(value = mobile, onValueChange = { mobile = it }, label = { Text("अभिभावक का मोबाइल") },
                singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 10.dp))
        } else {
            OutlinedTextField(value = parentMobile, onValueChange = { parentMobile = it }, label = { Text("अभिभावक का पंजीकृत मोबाइल") },
                singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 10.dp))
            OutlinedTextField(value = username, onValueChange = { username = it }, label = { Text("छात्र यूज़रनेम") },
                singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 10.dp))
        }
        OutlinedTextField(value = password, onValueChange = { password = it }, label = { Text("पासवर्ड") },
            singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 10.dp))
        Button(
            onClick = { onSignIn(mode, mobile, parentMobile, username, password) },
            enabled = !busy && password.isNotBlank() &&
                (if (mode == "PARENT") mobile.isNotBlank() else parentMobile.isNotBlank() && username.isNotBlank()),
            modifier = Modifier.fillMaxWidth().padding(top = 18.dp)
        ) {
            Text(if (busy) "साइन इन हो रहा है…" else "साइन इन करें")
        }
        if (mode == "PARENT") {
            TextButton(onClick = onSignup, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                Text("नया अभिभावक खाता बनाएँ")
            }
            TextButton(onClick = onResetPassword, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                Text("पासवर्ड भूल गए?")
            }
        }
        Text(
            "अपने अभिभावक द्वारा बनाए गए छात्र खाते या मौजूदा अभिभावक खाते से साइन इन करें।",
            modifier = Modifier.padding(top = 14.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.secondary
        )
    }
}

@Composable
private fun ParentOtpFlowScreen(
    isSignup: Boolean,
    busy: Boolean,
    status: String,
    onRequestOtp: (String, (String?) -> Unit) -> Unit,
    onVerify: (String, String, String, String) -> Unit
) {
    var mobile by remember { mutableStateOf("") }
    var displayName by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var otp by remember { mutableStateOf("") }
    var otpRequested by remember { mutableStateOf(false) }
    var devCode by remember { mutableStateOf("") }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(if (isSignup) "अभिभावक खाता बनाएँ" else "पासवर्ड रीसेट करें",
            style = MaterialTheme.typography.headlineSmall)
        Text(
            if (isSignup) "मोबाइल नंबर सत्यापित करके अपना अभिभावक खाता बनाएँ।"
            else "अपने पंजीकृत मोबाइल पर OTP प्राप्त करके नया पासवर्ड सेट करें।",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 8.dp, bottom = 14.dp)
        )
        OutlinedTextField(
            value = mobile,
            onValueChange = { mobile = it.filter { ch -> ch.isDigit() }.take(13) },
            label = { Text("मोबाइल नंबर") },
            singleLine = true,
            enabled = !otpRequested && !busy,
            modifier = Modifier.fillMaxWidth()
        )
        if (isSignup) {
            OutlinedTextField(
                value = displayName,
                onValueChange = { displayName = it },
                label = { Text("पूरा नाम") },
                singleLine = true,
                enabled = !otpRequested && !busy,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
            )
        }
        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text("नया पासवर्ड (कम से कम 8 अक्षर)") },
            singleLine = true,
            enabled = !busy,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
        )
        if (otpRequested) {
            OutlinedTextField(
                value = otp,
                onValueChange = { otp = it.filter { ch -> ch.isDigit() }.take(6) },
                label = { Text("OTP") },
                singleLine = true,
                enabled = !busy,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
            )
            if (devCode.isNotBlank()) {
                Text("स्थानीय विकास OTP: " + devCode, color = MaterialTheme.colorScheme.secondary,
                    style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
            }
            Button(
                onClick = { onVerify(mobile, displayName, otp, password) },
                enabled = !busy && otp.length == 6 && password.length >= 8 &&
                    (!isSignup || displayName.trim().length >= 2),
                modifier = Modifier.fillMaxWidth().padding(top = 14.dp)
            ) {
                Text(if (isSignup) "खाता सत्यापित कर बनाएँ" else "पासवर्ड अपडेट करें")
            }
            TextButton(onClick = { otpRequested = false; otp = ""; devCode = "" }, enabled = !busy) {
                Text("मोबाइल नंबर बदलें")
            }
        } else {
            Button(
                onClick = { onRequestOtp(mobile) { code -> otpRequested = true; devCode = code.orEmpty() } },
                enabled = !busy && mobile.filter { it.isDigit() }.length >= 10 &&
                    password.length >= 8 && (!isSignup || displayName.trim().length >= 2),
                modifier = Modifier.fillMaxWidth().padding(top = 14.dp)
            ) { Text("OTP भेजें") }
        }
        if (status.isNotBlank()) {
            Text(status, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.secondary, modifier = Modifier.padding(top = 10.dp))
        }
    }
}

@Composable
private fun StudentHomeScreen(
    userName: String,
    profile: JSONObject,
    recommendation: JSONObject,
    language: String,
    busy: Boolean,
    onSubject: (String) -> Unit,
    onDiagnostic: () -> Unit,
    onTests: (String) -> Unit,
    onResources: (String) -> Unit,
    onResults: () -> Unit,
    onProfile: () -> Unit,
    onProgress: () -> Unit,
    onRefresh: () -> Unit,
    onOpenRecommendation: (JSONObject) -> Unit,
    onClasses: (String) -> Unit
) {
    val name = userName.ifBlank { profile.textAny("display_name", "displayName", "name").ifBlank { "विद्यार्थी" } }
    val className = profile.textAny("class_name", "className", "class_code", "classCode")
    val lessonCount = profile.optInt("completedLessons", profile.optInt("completed_lessons", -1))
    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(if (language == "hi") "नमस्ते, $name" else "Welcome, $name", style = MaterialTheme.typography.headlineSmall)
        Text(if (className.isNotBlank()) "कक्षा $className" else "आपकी सीखने की जगह", style = MaterialTheme.typography.bodyMedium)
        if (lessonCount >= 0) Text("पूरे किए गए पाठ: $lessonCount", style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            SubjectCard("गणित", "Maths", Modifier.weight(1f)) { onSubject("maths") }
            SubjectCard("विज्ञान", "Science", Modifier.weight(1f)) { onSubject("science") }
        }
        ActionRow("सीखना जारी रखें", "अपनी कक्षा और विषय के प्रकाशित पाठ खोलें।", "पाठ खोलें") { onSubject("maths") }
        ActionRow("प्रारंभिक जाँच", "आपके वर्तमान ज्ञान को समझने के लिए सर्वर पर उपलब्ध डायग्नोस्टिक।", "जाँच शुरू करें", onDiagnostic)
        ActionRow("टेस्ट", "प्रकाशित अध्याय और विषय मूल्यांकन।", "टेस्ट देखें") { onTests("maths") }
        ActionRow("Textbook Library", "पुस्तक और अध्याय संसाधन ऐप में पढ़ें; अनुमति होने पर ऑफ़लाइन डाउनलोड करें।", "लाइब्रेरी खोलें") { onResources("maths") }
        ActionRow("Live classes और recordings", "Schedule की गई classes, recorded lessons और आपकी playback progress।", "Classes खोलें") { onClasses("maths") }
        ActionRow("मेरे परिणाम", "जारी किए गए टेस्ट परिणाम और उत्तर समीक्षा।", "परिणाम देखें", onResults)
        ActionRow("प्रगति और mastery", "पाठ completion, accuracy, concept mastery और assessment performance।", "मेरी प्रगति देखें", onProgress)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = onProfile) { Text("प्रोफ़ाइल और सेटिंग") }
            TextButton(onClick = onRefresh, enabled = !busy) { Text("रिफ्रेश") }
        }
        if (recommendation.optBoolean("available") && recommendation.has("lesson")) {
            val next = recommendation.optJSONObject("lesson") ?: JSONObject()
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("आपके लिए अगला पाठ", style = MaterialTheme.typography.titleMedium)
                    Text(next.textAny("title", "lesson_title"))
                    TextButton(onClick = { onOpenRecommendation(next) }) {
                        Text("अगला पाठ खोलें")
                    }
                }
            }
        } else if (recommendation.textAny("kind") == "DIAGNOSTIC") {
            Text("अपनी अगली सीख तय करने के लिए प्रारंभिक जाँच पूरी करें।", style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun SubjectCard(title: String, subtitle: String, modifier: Modifier, onClick: () -> Unit) {
    Card(modifier = modifier.clickable(onClick = onClick)) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
            Text("पाठ और अभ्यास देखें →", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun LessonListScreen(
    subject: String,
    lessons: List<JSONObject>,
    language: String,
    busy: Boolean,
    onRefresh: () -> Unit,
    onOpenLesson: (JSONObject) -> Unit
) {
    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.weight(1f)) {
                Text(if (subject == "maths") "गणित" else "विज्ञान", style = MaterialTheme.typography.headlineSmall)
                Text(if (language == "hi") "प्रकाशित पाठ और प्रगति" else "Published lessons and progress",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
            }
            TextButton(onClick = onRefresh, enabled = !busy) { Text("रिफ्रेश") }
        }
        if (lessons.isEmpty() && !busy) {
            EmptyState("अभी कोई पाठ उपलब्ध नहीं", "इस कक्षा और विषय के प्रकाशित पाठ सर्वर पर उपलब्ध होने पर वे यहाँ दिखेंगे।")
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 10.dp)) {
                items(lessons) { item ->
                    val progress = item.optInt("progress_percent", 0)
                    Card(modifier = Modifier.fillMaxWidth().clickable { onOpenLesson(item) }) {
                        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(item.textAny("chapter_name", "chapterName"), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.secondary)
                            Text(item.textAny("title").ifBlank { "पाठ" }, style = MaterialTheme.typography.titleMedium)
                            Text(item.textAny("summary"), style = MaterialTheme.typography.bodyMedium)
                            Text("समय: " + item.optInt("estimated_minutes", 0) + " मिनट  •  प्रगति: " + progress + "%",
                                style = MaterialTheme.typography.bodySmall)
                            LinearProgressIndicator(progress = { progress.coerceIn(0, 100) / 100f }, modifier = Modifier.fillMaxWidth())
                            Text("पाठ खोलें →", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LessonScreen(
    lesson: JSONObject,
    language: String,
    answers: MutableMap<String, String>,
    feedbacks: MutableMap<String, String>,
    answerLocks: Map<String, Boolean>,
    busy: Boolean,
    onAnswerChange: (String, String) -> Unit,
    onAnswer: (JSONObject, String) -> Unit,
    onPractice: () -> Unit,
    onTutor: () -> Unit
) {
    val lessonId = lesson.longAny("id", "lesson_id")
    val blocks = lesson.optJSONArray("blocks")?.objectItems() ?: emptyList()
    val questions = lesson.optJSONArray("questions")?.objectItems() ?: emptyList()
    Column(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(lesson.textAny("chapter_name", "chapterName"), style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.secondary)
            Text(lesson.textAny("title").ifBlank { "पाठ" }, style = MaterialTheme.typography.headlineSmall)
            Text(lesson.textAny("summary"), style = MaterialTheme.typography.bodyLarge)
            if (blocks.isEmpty()) {
                EmptyState("पाठ की सामग्री उपलब्ध नहीं", "इस प्रकाशित पाठ में अभी कोई पढ़ने योग्य ब्लॉक नहीं मिला।")
            } else {
                blocks.forEach { block ->
                    LessonContentBlock(block = block, language = language, onTutorOpen = onTutor)
                }
            }
            if (questions.isNotEmpty()) {
                Text(if (language == "hi") "अब खुद करें" else "Try it yourself",
                    style = MaterialTheme.typography.headlineSmall)
                questions.forEach { question ->
                    val id = question.longAny("id", "questionId", "question_id").toString()
                    val answer = answers[id] ?: ""
                    QuestionCard(
                        question = question,
                        answer = answer,
                        onAnswerChange = { onAnswerChange(id, it) },
                        onSubmit = { onAnswer(question, answer) },
                        locked = question.optBoolean("answered") || answerLocks[id] == true,
                        saving = busy,
                        feedback = feedbacks[id],
                        status = if (question.optBoolean("answered") || answerLocks[id] == true)
                            "उत्तर सुरक्षित है।" else null
                    )
                }
            }
            Text(if (language == "hi") "Recap" else "Recap", style = MaterialTheme.typography.headlineSmall)
            Text(lesson.textAny("summary").ifBlank {
                if (language == "hi") "मुख्य विचारों को दोहराएँ और अभ्यास से अपनी समझ जाँचें।"
                else "Review the key ideas and check your understanding with practice."
            }, style = MaterialTheme.typography.bodyLarge)
        }
        Row(modifier = Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = onPractice, enabled = !busy && lessonId > 0, modifier = Modifier.weight(1f)) {
                Text(if (language == "hi") "अभ्यास शुरू करें" else "Start practice")
            }
            TextButton(onClick = onTutor, enabled = !busy && lessonId > 0) {
                Text(if (language == "hi") "Tutor से पूछें" else "Ask tutor")
            }
        }
    }
}

@Composable
private fun PracticeScreen(
    practice: JSONObject,
    answers: MutableMap<String, String>,
    feedbacks: MutableMap<String, String>,
    busy: Boolean,
    onAnswerChange: (String, String) -> Unit,
    onAnswer: (JSONObject, String) -> Unit
) {
    val questions = practice.optJSONArray("questions")?.objectItems() ?: emptyList()
    val sessionId = practice.longAny("sessionId", "session_id")
    val statusText = practice.textAny("status")
    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("अभ्यास सत्र", style = MaterialTheme.typography.headlineSmall)
        Text(practice.textAny("message").ifBlank { "आपकी सीख से जुड़े अभ्यास प्रश्न।" }, style = MaterialTheme.typography.bodyMedium)
        Text("सत्र: " + sessionId + "  •  स्थिति: " + statusText, style = MaterialTheme.typography.labelSmall)
        if (questions.isEmpty()) EmptyState("अभ्यास प्रश्न उपलब्ध नहीं", "इस पाठ के लिए स्वीकृत प्रश्न उपलब्ध होने पर अभ्यास शुरू हो सकेगा।")
        else LazyColumn(modifier = Modifier.weight(1f).padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(questions) { question ->
                val id = question.longAny("id", "questionId", "question_id").toString()
                val answered = question.optBoolean("answered")
                val hasLast = question.has("lastCorrect") && !question.isNull("lastCorrect")
                val lastCorrect = if (hasLast) question.optBoolean("lastCorrect") else null
                val attemptCount = question.optInt("attemptCount", 0)
                val existing = answers[id] ?: ""
                val statusMessage = when {
                    answered && lastCorrect == true -> "उत्तर सही है और सुरक्षित है।"
                    answered && lastCorrect == null -> "उत्तर सुरक्षित है; मूल्यांकन बाकी है।"
                    !answered && lastCorrect == false && attemptCount > 0 -> "पिछला उत्तर सही नहीं था। फिर प्रयास करें।"
                    else -> null
                }
                QuestionCard(
                    question = question,
                    answer = existing,
                    onAnswerChange = { onAnswerChange(id, it) },
                    onSubmit = { onAnswer(question, existing) },
                    locked = answered,
                    saving = busy,
                    feedback = feedbacks[id],
                    status = statusMessage,
                    submitLabel = "उत्तर जमा करें"
                )
            }
        }
        Text("गलत auto-graded उत्तर पर फिर प्रयास कर सकते हैं। जमा हुआ अनिर्णीत उत्तर दोबारा नहीं भेजा जा सकता।",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary, modifier = Modifier.padding(top = 8.dp))
    }
}

@Composable
private fun DiagnosticScreen(
    diagnostic: JSONObject,
    answers: MutableMap<String, String>,
    feedbacks: MutableMap<String, String>,
    busy: Boolean,
    onAnswerChange: (String, String) -> Unit,
    onAnswer: (JSONObject, String) -> Unit,
    onSubmit: () -> Unit,
    onRefresh: () -> Unit
) {
    val questions = diagnostic.optJSONArray("questions")?.objectItems() ?: emptyList()
    val attemptId = diagnostic.longAny("attemptId", "attempt_id")
    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("प्रारंभिक जाँच", style = MaterialTheme.typography.headlineSmall)
        if (diagnostic.optBoolean("completed")) {
            EmptyState("जाँच पूरी हो चुकी है", "आपकी आगे की पढ़ाई और अनुशंसाएँ सर्वर पर उपलब्ध प्रगति के अनुसार हैं।")
            Button(onClick = onRefresh, enabled = !busy) { Text("स्थिति रिफ्रेश करें") }
        } else if (!diagnostic.optBoolean("available")) {
            EmptyState("जाँच अभी उपलब्ध नहीं", "आपकी कक्षा और विषयों के लिए स्वीकृत प्रश्न उपलब्ध होने पर यहाँ जाँच शुरू हो सकेगी।")
            Button(onClick = onRefresh, enabled = !busy) { Text("दोबारा जाँचें") }
        } else {
            Text("प्रश्न: " + questions.size + "  •  जवाब सर्वर पर सुरक्षित होंगे।")
            LazyColumn(modifier = Modifier.weight(1f).padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(questions) { question ->
                    val id = question.longAny("id", "questionId", "question_id").toString()
                    val value = answers[id] ?: question.textAny("selectedAnswer")
                    QuestionCard(
                        question = question,
                        answer = value,
                        onAnswerChange = { onAnswerChange(id, it) },
                        onSubmit = { onAnswer(question, value) },
                        locked = question.optBoolean("answered"),
                        saving = busy,
                        feedback = feedbacks[id],
                        status = if (question.optBoolean("answered")) "उत्तर सुरक्षित है।" else null
                    )
                }
            }
            Button(onClick = onSubmit, enabled = !busy && attemptId > 0, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                Text("जाँच जमा करें")
            }
        }
    }
}

@Composable
private fun AssessmentListScreen(
    subject: String,
    assessmentItems: List<JSONObject>,
    busy: Boolean,
    onSubject: (String) -> Unit,
    onOpen: (JSONObject) -> Unit,
    onPrepareOffline: (JSONObject) -> Unit,
    onResults: () -> Unit,
    onRefresh: () -> Unit
) {
    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("टेस्ट और मूल्यांकन", style = MaterialTheme.typography.headlineSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (subject == "maths") Button(onClick = { onSubject("maths") }, enabled = !busy) { Text("गणित") }
            else TextButton(onClick = { onSubject("maths") }, enabled = !busy) { Text("गणित") }
            if (subject == "science") Button(onClick = { onSubject("science") }, enabled = !busy) { Text("विज्ञान") }
            else TextButton(onClick = { onSubject("science") }, enabled = !busy) { Text("विज्ञान") }
            TextButton(onClick = onResults, enabled = !busy) { Text("मेरे परिणाम") }
            TextButton(onClick = onRefresh, enabled = !busy) { Text("रिफ्रेश") }
        }
        if (assessmentItems.any { it.optBoolean("offline_enabled") }) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("संरक्षित offline test", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "Offline mode शुरू करते ही test का timer शुरू हो जाता है। Answers इस device पर encrypted रहते हैं; कोई local score नहीं दिखाया जाता। Internet मिलने पर server answers sync करके जाँचता है। यह सुविधा केवल staff-approved low-stakes tests के लिए है।",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
        if (assessmentItems.isEmpty() && !busy) EmptyState("कोई प्रकाशित टेस्ट नहीं", "सिर्फ उपलब्ध और आपके खाते के लिए अनुमत मूल्यांकन यहाँ दिखेंगे।")
        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 8.dp)) {
            items(assessmentItems) { item ->
                val used = item.optInt("attempts_used", 0)
                val max = item.optInt("max_attempts", 1)
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(item.textAny("title").ifBlank { "मूल्यांकन" }, style = MaterialTheme.typography.titleMedium)
                        Text(item.textAny("description"), style = MaterialTheme.typography.bodyMedium)
                        Text(
                            item.textAny("chapter_name", "subject_name") + " • " +
                                item.optInt("question_count", 0) + " प्रश्न • " +
                                item.optInt("duration_minutes", 0) + " मिनट",
                            style = MaterialTheme.typography.bodySmall
                        )
                        Text("प्रयास: " + used + " / " + max, style = MaterialTheme.typography.bodySmall)
                        val offlineLocal = item.optBoolean("offline_local")
                        val pendingSync = item.textAny("offline_state") == "QUEUED" ||
                            item.textAny("offline_state") == "SYNC_ERROR"
                        if (offlineLocal) {
                            Text(
                                when (item.textAny("offline_state")) {
                                    "QUEUED" -> "Offline answers saved · sync pending"
                                    "SYNC_ERROR" -> "Offline answers saved · sync needs attention"
                                    "SYNCED" -> when (item.textAny("offline_result_status")) {
                                        "RELEASED" -> "Synced · result released"
                                        "AWAITING_REVIEW" -> "Synced · awaiting teacher review"
                                        else -> "Synced to server"
                                    }
                                    else -> "Encrypted offline attempt ready"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        Button(onClick = { onOpen(item) }, enabled = !busy &&
                            (offlineLocal || item.optLong("in_progress_attempt_id", 0L) > 0L || used < max),
                            modifier = Modifier.fillMaxWidth()) {
                            Text(when {
                                pendingSync -> "Offline attempt / sync status"
                                offlineLocal -> "Offline test जारी रखें"
                                item.optLong("in_progress_attempt_id", 0L) > 0L -> "टेस्ट जारी रखें"
                                else -> "टेस्ट शुरू करें"
                            })
                        }
                        if (!offlineLocal && item.optBoolean("offline_enabled") &&
                            item.optLong("in_progress_attempt_id", 0L) <= 0L && used < max) {
                            TextButton(onClick = { onPrepareOffline(item) }, enabled = !busy,
                                modifier = Modifier.fillMaxWidth()) {
                                Text("Offline test शुरू करें (timer अभी शुरू होगा)")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AssessmentAttemptScreen(
    attempt: JSONObject,
    answers: MutableMap<String, String>,
    feedbacks: MutableMap<String, String>,
    certificateEligibility: Map<Long, JSONObject>,
    language: String,
    busy: Boolean,
    onIssueCertificate: (Long) -> Unit,
    onDownloadCertificate: (Long, String) -> Unit,
    onAnswerChange: (String, String) -> Unit,
    onSave: (JSONObject, String) -> Unit,
    onSubmit: () -> Unit,
    onRefresh: () -> Unit
) {
    val questions = attempt.optJSONArray("questions")?.objectItems() ?: emptyList()
    val attemptId = attempt.longAny("attempt_id", "attemptId", "id")
    val phase = attempt.textAny("status")
    val released = attempt.optBoolean("resultReleased")
    val offlinePendingSync = attempt.optBoolean("offlinePendingSync")
    val inProgress = phase == "IN_PROGRESS" && !offlinePendingSync
    val deadlineText = attempt.textAny("deadline_at", "deadlineAt")
    val deadlineEpochMillis = remember(deadlineText) {
        AssessmentTimer.deadlineEpochMillis(deadlineText)
    }
    var nowEpochMillis by remember(attemptId, deadlineEpochMillis) { mutableStateOf(System.currentTimeMillis()) }
    var autoSubmittedAttemptId by remember { mutableStateOf(0L) }
    val remainingSeconds = deadlineEpochMillis?.let { ((it - nowEpochMillis) / 1000L).coerceAtLeast(0L) }
    val deadlineReached = inProgress && remainingSeconds != null && remainingSeconds <= 0L

    LaunchedEffect(attemptId, deadlineEpochMillis, inProgress) {
        if (inProgress && deadlineEpochMillis != null) {
            while (true) {
                nowEpochMillis = System.currentTimeMillis()
                if (nowEpochMillis >= deadlineEpochMillis) break
                delay(1000)
            }
        }
    }

    LaunchedEffect(attemptId, deadlineReached, inProgress, offlinePendingSync, busy) {
        if (deadlineReached && inProgress && !offlinePendingSync && !busy &&
            attemptId > 0L && autoSubmittedAttemptId != attemptId) {
            autoSubmittedAttemptId = attemptId
            onSubmit()
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text(attempt.textAny("assessment_title", "title").ifBlank { "टेस्ट" }, style = MaterialTheme.typography.headlineSmall)
        Text("प्रयास " + attempt.optInt("attempt_number", 1) + " • स्थिति: " +
            if (offlinePendingSync) "OFFLINE_PENDING_SYNC" else phase, style = MaterialTheme.typography.bodySmall)
        if (inProgress && remainingSeconds != null) {
            val minutes = (remainingSeconds / 60L).toString().padStart(2, '0')
            val seconds = (remainingSeconds % 60L).toString().padStart(2, '0')
            Card(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(if (deadlineReached) "समय पूरा" else "समय शेष", style = MaterialTheme.typography.titleSmall)
                    Text("$minutes:$seconds", style = MaterialTheme.typography.titleLarge,
                        color = if (remainingSeconds <= 60L) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
                }
            }
        }
        if (deadlineReached) {
            Text(
                if (attempt.optBoolean("offlineMode")) "समय पूरा हो गया है। आपके सुरक्षित उत्तर स्वतः sync queue में भेजे जा रहे हैं।"
                else "समय पूरा हो गया है। आपका टेस्ट स्वतः जमा किया जा रहा है।",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }
        if (offlinePendingSync) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("उत्तर इस device पर encrypted सुरक्षित हैं", style = MaterialTheme.typography.titleSmall)
                    Text("इन answers को यहाँ grade नहीं किया जा रहा। Internet मिलने पर authenticated server उन्हें sync करके जाँचेगा। Offline package की sync सीमा timer खत्म होने के 72 घंटे बाद तक है.",
                        style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        if (released) {
            Text("अंक: " + attempt.textAny("final_score") + " / " + attempt.textAny("max_score"),
                style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(vertical = 8.dp))
            val certificate = certificateEligibility[attemptId]
            val certificateStatus = certificate?.optString("certificate_status").orEmpty()
            val certificateId = certificate?.optLongAny("certificate_id", "certificateId") ?: 0L
            Card(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    Text(if (language == "hi") "इस result का certificate" else "Certificate for this result",
                        style = MaterialTheme.typography.titleSmall)
                    Text(
                        if (language == "hi") "Admin-created test, qualifying released score और सभी published lessons पूरे होना ज़रूरी है।"
                        else "Requires an admin-created test, a qualifying released score and completion of all published lessons.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.secondary
                    )
                    when {
                        certificateStatus == "ISSUED" && certificateId > 0L -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { onDownloadCertificate(certificateId, "pdf") }, enabled = !busy) {
                                Text(if (language == "hi") "PDF सेव करें" else "Save PDF")
                            }
                            OutlinedButton(onClick = { onDownloadCertificate(certificateId, "png") }, enabled = !busy) {
                                Text(if (language == "hi") "PNG सेव करें" else "Save PNG")
                            }
                        }
                        certificateStatus == "REVOKED" -> Text(
                            if (language == "hi") "यह certificate निरस्त है।"
                            else "This certificate has been revoked.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error
                        )
                        certificate?.optBoolean("eligible", false) == true -> Button(
                            onClick = { onIssueCertificate(attemptId) }, enabled = !busy && attemptId > 0L,
                            modifier = Modifier.fillMaxWidth()
                        ) { Text(if (language == "hi") "प्रमाणपत्र जारी करें" else "Issue certificate") }
                        else -> {
                            val reason = certificate?.optString("eligibility_reason").orEmpty()
                            val message = when (reason) {
                                "ADMIN_CREATED_TEST_ONLY" -> if (language == "hi") "केवल admin-created tests पात्र हैं; practice और generated tests नहीं।" else "Only admin-created tests qualify; practice and generated tests do not."
                                "SUBJECT_COMPLETION_REQUIRED" -> if (language == "hi") "Certificate unlock करने के लिए subject के सभी published lessons पूरे करें।" else "Complete all published lessons in this subject to unlock the certificate."
                                "PASS_THRESHOLD_NOT_MET" -> if (language == "hi") "Score certificate threshold से कम है।" else "The score is below the certificate threshold."
                                "SUBJECT_TRACK_NOT_ACTIVE" -> if (language == "hi") "इस subject में active enrollment ज़रूरी है।" else "An active enrollment in this subject is required."
                                "PUBLISHED_LESSONS_NOT_AVAILABLE" -> if (language == "hi") "इस subject के published lessons उपलब्ध नहीं हैं।" else "Published lessons are not available for this subject yet."
                                else -> if (language == "hi") "Certificate eligibility अभी जाँची नहीं जा सकी। Results स्क्रीन खोलकर refresh करें।" else "Certificate eligibility could not be checked. Open the results screen to refresh."
                            }
                            Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
                        }
                    }
                }
            }
        } else if (phase == "AWAITING_REVIEW") {
            Text("टेस्ट जमा है। लिखित उत्तरों का मूल्यांकन होने पर परिणाम जारी होगा।", modifier = Modifier.padding(vertical = 8.dp))
        }
        LazyColumn(modifier = Modifier.weight(1f).padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(questions) { question ->
                val id = question.longAny("attemptQuestionId", "attempt_question_id").toString()
                val serverAnswer = question.textAny("answer")
                val value = answers[id] ?: serverAnswer
                QuestionCard(
                    question = question,
                    answer = value,
                    onAnswerChange = { onAnswerChange(id, it) },
                    onSubmit = { onSave(question, value) },
                    locked = !inProgress || deadlineReached,
                    saving = busy,
                    feedback = feedbacks[id],
                    status = if (released) {
                        val correctness = when {
                            question.has("isCorrect") && !question.isNull("isCorrect") ->
                                if (question.optBoolean("isCorrect")) "सही" else "गलत"
                            else -> "जाँच पूरी"
                        }
                        "परिणाम: " + correctness + " • अंक: " + question.textAny("awardedMarks", "maxMarks")
                    } else null,
                    submitLabel = "उत्तर सहेजें"
                )
                if (released) {
                    val correctAnswer = question.optJSONObject("correctAnswer")
                    val options = question.optJSONArray("options")?.objectItems() ?: emptyList()
                    val key = correctAnswer?.textAny("correctOption", "correct_option", "option_key", "key", "answer", "value").orEmpty()
                    val optionLabel = options.firstOrNull { it.textAny("key", "option_key") == key }?.textAny("label", "text").orEmpty()
                    val displayAnswer = optionLabel.ifBlank { key.ifBlank { correctAnswer?.toString().orEmpty() } }
                    if (displayAnswer.isNotBlank() && displayAnswer != "{}") {
                        Text("सही उत्तर: " + displayAnswer, style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary)
                    }
                    if (question.textAny("teacherFeedback").isNotBlank()) {
                        Text("शिक्षक की टिप्पणी: " + question.textAny("teacherFeedback"), style = MaterialTheme.typography.bodyMedium)
                    }
                    if (question.textAny("explanation").isNotBlank()) {
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text("व्याख्या", style = MaterialTheme.typography.titleSmall)
                                Text(question.textAny("explanation"), style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }
            }
        }
        if (inProgress) {
            Button(onClick = onSubmit, enabled = !busy && attemptId > 0, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                Text(
                    when {
                        deadlineReached && attempt.optBoolean("offlineMode") -> "समय पूरा — answers sync करें"
                        deadlineReached -> "समय पूरा — टेस्ट जमा करें"
                        else -> "टेस्ट जमा करें"
                    }
                )
            }
        } else if (offlinePendingSync) {
            Button(onClick = onSubmit, enabled = !busy && attemptId > 0,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                Text(if (busy) "Sync शुरू हो रहा है…" else "Sync की कोशिश करें")
            }
        } else if (!released) {
            Button(onClick = onRefresh, enabled = !busy, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                Text("परिणाम की स्थिति रिफ्रेश करें")
            }
        }
    }
}

@Composable
private fun ResultsScreen(
    resultItems: List<JSONObject>,
    certificateEligibility: Map<Long, JSONObject>,
    language: String,
    busy: Boolean,
    onReview: (Long) -> Unit,
    onIssueCertificate: (Long) -> Unit,
    onDownloadCertificate: (Long, String) -> Unit
) {
    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("मेरे परिणाम", style = MaterialTheme.typography.headlineSmall)
        Text(
            if (language == "hi") "हर result के साथ certificate की पात्रता और उपलब्ध download देखें।"
            else "Check certificate eligibility and access available downloads from each released result.",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.secondary
        )
        if (resultItems.isEmpty()) EmptyState("अभी कोई जारी परिणाम नहीं", "जारी किए गए मूल्यांकन परिणाम उपलब्ध होने पर वे यहाँ दिखेंगे।")
        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 10.dp)) {
            items(resultItems) { item ->
                val attempts = item.optJSONArray("attempts")?.objectItems() ?: emptyList()
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                        Text(item.textAny("assessment_title", "title").ifBlank { "मूल्यांकन" },
                            style = MaterialTheme.typography.titleMedium)
                        Text(item.textAny("subject_name", "chapter_name"), style = MaterialTheme.typography.bodySmall)
                        Text("नवीनतम अंक: " + item.textAny("final_score", "score") + " / " + item.textAny("max_score"),
                            style = MaterialTheme.typography.bodyLarge)
                        Text("पिछले तीन में सर्वश्रेष्ठ: " + item.textAny("best_score") + " / " + item.textAny("max_score"),
                            style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                        Text("कुल जारी attempts: " + item.optInt("attempt_count", attempts.size) +
                            " • प्रश्न: " + item.optInt("question_count", 0),
                            style = MaterialTheme.typography.bodySmall)
                        val history = if (attempts.isNotEmpty()) attempts else listOf(item)
                        Text(if (language == "hi") "Attempt history और certificate" else "Attempt history & certificate",
                            style = MaterialTheme.typography.titleSmall)
                        history.forEachIndexed { index, attempt ->
                            val attemptId = attempt.optLongAny("attempt_id", "attemptId")
                            val certificate = certificateEligibility[attemptId]
                            val certificateStatus = certificate?.optString("certificate_status").orEmpty()
                            val certificateId = certificate?.optLongAny("certificate_id", "certificateId") ?: 0L
                            Column(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp),
                                verticalArrangement = Arrangement.spacedBy(5.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                        Text(
                                            (if (language == "hi") "Attempt " else "Attempt ") +
                                                attempt.optInt("attempt_number", index + 1),
                                            style = MaterialTheme.typography.bodyMedium
                                        )
                                        Text(
                                            attempt.textAny("released_at", "submitted_at") + " • " +
                                                attempt.textAny("final_score") + " / " + attempt.textAny("max_score"),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.secondary
                                        )
                                    }
                                    TextButton(onClick = { onReview(attemptId) }, enabled = !busy) {
                                        Text(if (language == "hi") "उत्तर समीक्षा" else "Review")
                                    }
                                }
                                when {
                                    certificateStatus == "ISSUED" && certificateId > 0L -> {
                                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            OutlinedButton(
                                                onClick = { onDownloadCertificate(certificateId, "pdf") },
                                                enabled = !busy
                                            ) { Text(if (language == "hi") "PDF सेव करें" else "Save PDF") }
                                            OutlinedButton(
                                                onClick = { onDownloadCertificate(certificateId, "png") },
                                                enabled = !busy
                                            ) { Text(if (language == "hi") "PNG सेव करें" else "Save PNG") }
                                        }
                                    }
                                    certificateStatus == "REVOKED" -> Text(
                                        if (language == "hi") "यह certificate निरस्त है।"
                                        else "This certificate has been revoked.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.error
                                    )
                                    certificate?.optBoolean("eligible", false) == true -> Button(
                                        onClick = { onIssueCertificate(attemptId) },
                                        enabled = !busy && attemptId > 0L
                                    ) {
                                        Text(if (language == "hi") "प्रमाणपत्र जारी करें" else "Issue certificate")
                                    }
                                    else -> {
                                        val reason = certificate?.optString("eligibility_reason").orEmpty()
                                        val passThreshold = certificate?.optString("pass_threshold_percent", "80") ?: "80"
                                        val hint = when (reason) {
                                            "ADMIN_CREATED_TEST_ONLY" -> if (language == "hi") "केवल admin-created tests पात्र हैं; practice और generated tests नहीं।" else "Only admin-created tests qualify; practice and generated tests do not."
                                            "RESULT_NOT_RELEASED" -> if (language == "hi") "Final result जारी होने के बाद पात्रता जाँची जाएगी।" else "Eligibility is checked after the final result is released."
                                            "SUBJECT_TRACK_NOT_ACTIVE" -> if (language == "hi") "इस subject में active enrollment ज़रूरी है।" else "An active enrollment in this subject is required."
                                            "PUBLISHED_LESSONS_NOT_AVAILABLE" -> if (language == "hi") "इस subject में published lessons अभी उपलब्ध नहीं हैं।" else "Published lessons are not available for this subject yet."
                                            "SUBJECT_COMPLETION_REQUIRED" -> if (language == "hi") "Certificate unlock करने के लिए subject के सभी published lessons पूरे करें।" else "Complete all published lessons in this subject to unlock the certificate."
                                            "PASS_THRESHOLD_NOT_MET" -> if (language == "hi") "Score certificate threshold " + passThreshold + "% से कम है।" else "The score is below the " + passThreshold + "% certificate threshold."
                                            "INVALID_SCORE" -> if (language == "hi") "इस result का score मान्य नहीं है।" else "This result has an invalid score."
                                            else -> if (language == "hi") "Certificate eligibility अभी जाँची नहीं जा सकी। Results refresh करें।" else "Certificate eligibility could not be checked. Refresh results."
                                        }
                                        Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
                                    }
                                }
                            }
                            if (index < history.lastIndex) HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ResourcesScreen(
    documents: List<JSONObject>,
    subject: String,
    store: ProtectedResourceStore,
    busy: Boolean,
    downloadStatus: String,
    downloadStates: Map<Long, ResourceDownloadUiState>,
    onSubject: (String) -> Unit,
    onOpen: (JSONObject) -> Unit,
    onDownload: (JSONObject) -> Unit,
    onRemoveOffline: (JSONObject) -> Unit,
    onRefresh: () -> Unit
) {
    var pendingRemoval by remember { mutableStateOf<JSONObject?>(null) }
    if (pendingRemoval != null) {
        AlertDialog(
            onDismissRequest = { pendingRemoval = null },
            title = { Text("Offline copy हटाएँ?") },
            text = { Text("यह केवल इस device पर रखे encrypted page copies हटाएगा। Published resource server पर बना रहेगा और बाद में दोबारा डाउनलोड किया जा सकता है।") },
            confirmButton = {
                Button(onClick = {
                    val selected = pendingRemoval
                    pendingRemoval = null
                    if (selected != null) onRemoveOffline(selected)
                }, enabled = !busy) { Text("Offline copy हटाएँ") }
            },
            dismissButton = {
                TextButton(onClick = { pendingRemoval = null }, enabled = !busy) { Text("रद्द करें") }
            }
        )
    }
    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("Textbook Library", style = MaterialTheme.typography.headlineSmall)
        Text("सामग्री ऐप के अंदर पढ़ें। ऑफ़लाइन डाउनलोड पब्लिक Downloads या दूसरी ऐप में उपलब्ध नहीं होगा।",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.secondary)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (subject == "maths") Button(onClick = { onSubject("maths") }, enabled = !busy) { Text("गणित") }
            else TextButton(onClick = { onSubject("maths") }, enabled = !busy) { Text("गणित") }
            if (subject == "science") Button(onClick = { onSubject("science") }, enabled = !busy) { Text("विज्ञान") }
            else TextButton(onClick = { onSubject("science") }, enabled = !busy) { Text("विज्ञान") }
            TextButton(onClick = onRefresh, enabled = !busy) { Text("रिफ्रेश") }
        }
        if (documents.isEmpty() && !busy) EmptyState("संसाधन उपलब्ध नहीं", "इस भाषा और विषय के लिए डाउनलोड की अनुमति वाले प्रकाशित संसाधन दिखाई देंगे।")
        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 8.dp)) {
            items(documents) { doc ->
                val id = doc.optLongAny("document_id", "id")
                val count = doc.optInt("page_count",
                    (doc.optInt("page_end", 1) - doc.optInt("page_start", 1) + 1).coerceAtLeast(1))
                val cached = store.cachedPageCount(id, count)
                val downloadState = downloadStates[id]
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(doc.textAny("title").ifBlank { "पुस्तक संसाधन" }, style = MaterialTheme.typography.titleMedium)
                        Text(doc.textAny("chapter_name", "book_part", "scope"), style = MaterialTheme.typography.bodySmall)
                        Text(count.toString() + " पृष्ठ • ऑफ़लाइन: " + cached + " / " + count, style = MaterialTheme.typography.bodySmall)
                        if (downloadState != null) {
                            Text(downloadState.label, style = MaterialTheme.typography.bodySmall,
                                color = if (downloadState.active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary)
                            if (downloadState.progress != null && downloadState.active) {
                                LinearProgressIndicator(progress = { downloadState.progress }, modifier = Modifier.fillMaxWidth())
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { onOpen(doc) }, enabled = !busy, modifier = Modifier.weight(1f)) { Text("पढ़ें") }
                            TextButton(onClick = { onDownload(doc) }, enabled = !busy && downloadState?.active != true,
                                modifier = Modifier.weight(1f)) {
                                Text(if (cached == count) "फिर डाउनलोड" else "ऑफ़लाइन डाउनलोड")
                            }
                        }
                        if (cached > 0) {
                            TextButton(onClick = { pendingRemoval = doc }, enabled = !busy,
                                modifier = Modifier.fillMaxWidth()) {
                                Text("Downloaded copy हटाएँ", color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
            }
        }
        if (downloadStatus.isNotBlank()) Text(downloadStatus, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
    }
}

@Composable
private fun DocumentReaderScreen(
    document: JSONObject,
    page: Int,
    image: androidx.compose.ui.graphics.ImageBitmap?,
    pageCount: Int,
    busy: Boolean,
    cachedCount: Int,
    onPage: (Int) -> Unit,
    onDownload: () -> Unit
) {
    Column(modifier = Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(document.textAny("title").ifBlank { "पुस्तक" }, style = MaterialTheme.typography.titleLarge)
        Text("पृष्ठ " + page + " / " + pageCount + " • सुरक्षित ऑफ़लाइन प्रतियाँ: " + cachedCount,
            style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            TextButton(onClick = { onPage(page - 1) }, enabled = !busy && page > 1) { Text("← पिछला") }
            Spacer(modifier = Modifier.weight(1f))
            TextButton(onClick = onDownload, enabled = !busy && cachedCount < pageCount) { Text("ऑफ़लाइन डाउनलोड") }
            Spacer(modifier = Modifier.weight(1f))
            TextButton(onClick = { onPage(page + 1) }, enabled = !busy && page < pageCount) { Text("अगला →") }
        }
        if (image != null) {
            Image(bitmap = image, contentDescription = "पुस्तक का पृष्ठ " + page,
                modifier = Modifier.fillMaxWidth().weight(1f), contentScale = ContentScale.Fit)
        } else {
            Box(modifier = Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                if (busy) CircularProgressIndicator()
                else EmptyState("पृष्ठ अभी लोड नहीं हुआ", "नेटवर्क उपलब्ध होने पर या पृष्ठ डाउनलोड होने पर फिर प्रयास करें।")
            }
        }
        Text("यह सामग्री केवल QuantaEdge ऐप में दिखाई जाती है; export और share विकल्प उपलब्ध नहीं हैं।",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
    }
}

@Composable
private fun ParentHomeScreen(
    parent: JSONObject,
    children: List<JSONObject>,
    busy: Boolean,
    onRefresh: () -> Unit,
    onReport: (JSONObject) -> Unit,
    onManageChild: (JSONObject) -> Unit,
    onAddChild: () -> Unit,
    onProfile: () -> Unit,
    onLogout: () -> Unit,
    maxChildren: Int
) {
    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("अभिभावक डैशबोर्ड", style = MaterialTheme.typography.headlineSmall)
        Text(parent.textAny("parentName").ifBlank { "अभिभावक" }, style = MaterialTheme.typography.titleMedium)
        Text("सक्रिय छात्र: " + parent.optInt("activeChildren", children.size) + " / " + maxChildren,
            style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = onRefresh, enabled = !busy) { Text("रिफ्रेश") }
            TextButton(onClick = onProfile, enabled = !busy) { Text("प्रोफ़ाइल") }
            TextButton(onClick = onLogout, enabled = !busy) { Text("साइन आउट") }
        }
        Button(onClick = onAddChild, enabled = !busy && children.size < maxChildren,
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) { Text("नया छात्र प्रोफ़ाइल बनाएँ") }
        if (children.isEmpty()) EmptyState("कोई सक्रिय छात्र प्रोफ़ाइल नहीं", "अभिभावक खाते से जुड़े सक्रिय छात्र यहाँ दिखेंगे।")
        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 8.dp)) {
            items(children) { child ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(child.textAny("display_name", "displayName").ifBlank { "छात्र" }, style = MaterialTheme.typography.titleMedium)
                        Text("कक्षा " + child.textAny("class_code", "class_name") + " • " + child.textAny("track_codes"),
                            style = MaterialTheme.typography.bodySmall)
                        Text("यूज़रनेम: " + child.textAny("login_username").ifBlank { "सेट नहीं" },
                            style = MaterialTheme.typography.bodySmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { onReport(child) }, enabled = !busy, modifier = Modifier.weight(1f)) {
                                Text("सीखने की रिपोर्ट")
                            }
                            TextButton(onClick = { onManageChild(child) }, enabled = !busy, modifier = Modifier.weight(1f)) {
                                Text("Login / विषय")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ParentReportScreen(report: JSONObject) {
    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(report.textAny("_childName", "display_name").ifBlank { "छात्र की रिपोर्ट" }, style = MaterialTheme.typography.headlineSmall)
        Text("कक्षा " + report.textAny("class_code", "classCode"), style = MaterialTheme.typography.bodyMedium)
        val excluded = setOf("_childName", "display_name", "public_id", "class_code", "board", "language")
        val keys = report.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            if (key in excluded || report.isNull(key)) continue
            val value = report.opt(key)
            if (value is JSONObject || value is JSONArray) continue
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(key.replace('_', ' ').replaceFirstChar { it.uppercase() }, style = MaterialTheme.typography.labelMedium)
                    Text(value.toString(), style = MaterialTheme.typography.titleMedium)
                }
            }
        }
    }
}

@Composable
private fun ProfileScreen(
    auth: JSONObject,
    profile: JSONObject,
    language: String,
    onToggleLanguage: () -> Unit,
    onLogout: () -> Unit
) {
    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("प्रोफ़ाइल और सेटिंग", style = MaterialTheme.typography.headlineSmall)
        Text(auth.textAny("displayName", "display_name", "role"), style = MaterialTheme.typography.titleMedium)
        Text("खाता: " + auth.textAny("role"), style = MaterialTheme.typography.bodyMedium)
        Text("कक्षा: " + profile.textAny("class_code", "classCode"), style = MaterialTheme.typography.bodyMedium)
        Text("ऐप भाषा: " + if (language == "hi") "हिंदी" else "English", style = MaterialTheme.typography.bodyMedium)
        Button(onClick = onToggleLanguage, modifier = Modifier.fillMaxWidth()) {
            Text(if (language == "hi") "Switch to English" else "हिंदी में बदलें")
        }
        Button(onClick = onLogout, modifier = Modifier.fillMaxWidth()) { Text("साइन आउट") }
        Text("डाउनलोड की गई अध्ययन सामग्री ऐप के निजी स्टोरेज में encrypted रहती है और Android backup में शामिल नहीं होती।",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
    }
}
