package com.quantaedge.android.ui

import android.graphics.BitmapFactory
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FloatingActionButton
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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.quantaedge.android.R
import com.quantaedge.android.data.ApiClient
import com.quantaedge.android.data.ApiFailure
import com.quantaedge.android.data.ProtectedResourceStore
import com.quantaedge.android.data.OfflineAssessmentStore
import com.quantaedge.android.data.OfflineAssessmentSyncWorker
import androidx.work.WorkManager
import com.quantaedge.android.data.optLongAny
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.IOException
import java.time.LocalTime

private data class ResourceDownloadUiState(
    val label: String,
    val progress: Float? = null,
    val active: Boolean = false
)

private data class AppNavItem(val key: String, val label: String, val glyph: String)

private fun resourceDownloadUiState(work: androidx.work.WorkInfo, language: String): ResourceDownloadUiState {
    val done = if (work.state == androidx.work.WorkInfo.State.SUCCEEDED) {
        work.outputData.getInt("pagesDone", work.progress.getInt("pagesDone", 0))
    } else work.progress.getInt("pagesDone", 0)
    val total = if (work.state == androidx.work.WorkInfo.State.SUCCEEDED) {
        work.outputData.getInt("pageCount", work.progress.getInt("pageCount", 0))
    } else work.progress.getInt("pageCount", 0)
    val fraction = if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else null
    val hindi = language == "hi"
    return when (work.state) {
        androidx.work.WorkInfo.State.ENQUEUED,
        androidx.work.WorkInfo.State.BLOCKED -> ResourceDownloadUiState(
            if (hindi) "डाउनलोड कतार में है" else "Download queued", fraction, true
        )
        androidx.work.WorkInfo.State.RUNNING -> ResourceDownloadUiState(
            if (hindi) {
                if (total > 0) "डाउनलोड: $done / $total पृष्ठ" else "डाउनलोड चल रहा है"
            } else {
                if (total > 0) "Downloading: $done / $total pages" else "Downloading"
            },
            fraction, true
        )
        androidx.work.WorkInfo.State.SUCCEEDED -> ResourceDownloadUiState(
            if (hindi) "डाउनलोड पूरा" else "Download complete", 1f, false
        )
        androidx.work.WorkInfo.State.FAILED -> ResourceDownloadUiState(
            if (hindi) "डाउनलोड विफल — फिर प्रयास करें" else "Download failed — retry", null, false
        )
        androidx.work.WorkInfo.State.CANCELLED -> ResourceDownloadUiState(
            if (hindi) "डाउनलोड रद्द" else "Download cancelled", null, false
        )
    }
}

@Composable
fun QuantaEdgeApp() {
    val context = LocalContext.current
    val preferences = remember { context.getSharedPreferences("qe_preferences", 0) }
    val scope = rememberCoroutineScope()
    var auth by remember { mutableStateOf(JSONObject()) }
    val saveableStateHolder = rememberSaveableStateHolder()
    val stateOwnerKey = auth.textAny("user_id", "userId", "id", "account_id", "mobile_e164")
        .ifBlank { auth.textAny("role", "displayName", "display_name").ifBlank { "anonymous" } }
    val resourceOwner = remember(auth) { com.quantaedge.android.data.ResourceOwnerKey.fromAuth(auth) }
    val resourceStore = remember(resourceOwner) { ProtectedResourceStore(context, resourceOwner) }
    val offlineAssessmentStore = remember(resourceOwner) { OfflineAssessmentStore(context, resourceOwner) }

    var route by rememberSaveable { mutableStateOf(AppRoute.WELCOME) }
    var loginMode by rememberSaveable { mutableStateOf("STUDENT") }
    var lessonReturnRoute by rememberSaveable { mutableStateOf(AppRoute.STUDENT_HOME) }
    var resultsReturnRoute by rememberSaveable { mutableStateOf(AppRoute.STUDENT_HOME) }
    var language by rememberSaveable { mutableStateOf(preferences.getString("language", "hi") ?: "hi") }
    var learningLanguage by rememberSaveable {
        mutableStateOf(preferences.getString("contentLanguage", "hi")?.takeIf { it == "hi" || it == "en" } ?: "hi")
    }
    var accountMenuExpanded by rememberSaveable { mutableStateOf(false) }
    var headerPhotoBytes by remember { mutableStateOf<ByteArray?>(null) }
    var certificateLanguage by rememberSaveable {
        mutableStateOf(preferences.getString("certificateLanguage", "hi")?.takeIf { it in setOf("hi", "en") } ?: "hi")
    }
    var currentSubject by rememberSaveable { mutableStateOf("maths") }
    var currentCourseId by rememberSaveable { mutableStateOf(0L) }
    var lastStudentNavGroup by rememberSaveable { mutableStateOf("today") }
    var parentSection by rememberSaveable { mutableStateOf("family") }
    var studentProfile by remember { mutableStateOf(JSONObject()) }
    var studentPrograms by remember { mutableStateOf(emptyList<JSONObject>()) }
    var studentProgramsLoadFailed by remember { mutableStateOf(false) }
    var courseProgress by remember { mutableStateOf<Map<Long, JSONObject>>(emptyMap()) }
    var studentTextbookDocuments by remember { mutableStateOf(emptyList<JSONObject>()) }
    var studentTextbookDocumentsLoadFailed by remember { mutableStateOf(false) }
    var accountLifecycle by remember { mutableStateOf(JSONObject()) }
    var recommendation by remember { mutableStateOf(JSONObject()) }
    var parentAccount by remember { mutableStateOf(JSONObject()) }
    var parentChildren by remember { mutableStateOf(emptyList<JSONObject>()) }
    var archivedChildren by remember { mutableStateOf(emptyList<JSONObject>()) }
    var parentReport by remember { mutableStateOf(JSONObject()) }
    var parentCertificates by remember { mutableStateOf(emptyList<JSONObject>()) }
    var parentProfile by remember { mutableStateOf(JSONObject()) }
    var selectedChild by remember { mutableStateOf(JSONObject()) }

    var lessons by remember { mutableStateOf(emptyList<JSONObject>()) }
    var structuredLessons by remember { mutableStateOf(emptyList<JSONObject>()) }
    var structuredLessonMode by rememberSaveable { mutableStateOf(false) }
    var structuredLessonArtifactId by rememberSaveable { mutableStateOf("") }
    var structuredLessonSavedResponses by remember { mutableStateOf(JSONObject()) }
    var structuredLessonPendingQuestionIds by remember { mutableStateOf(emptySet<String>()) }
    var structuredLessonPendingActivityIds by remember { mutableStateOf(emptySet<String>()) }
    var lesson by remember { mutableStateOf(JSONObject()) }
    var practice by remember { mutableStateOf(JSONObject()) }
    var diagnostic by remember { mutableStateOf(JSONObject()) }
    var assessments by remember { mutableStateOf(emptyList<JSONObject>()) }
    var assessmentAttempt by remember { mutableStateOf(JSONObject()) }
    var offlineAssessments by remember { mutableStateOf(emptyList<JSONObject>()) }
    var assessmentsPageSize by rememberSaveable { mutableStateOf(10) }
    var assessmentsPageOffset by rememberSaveable { mutableStateOf(0) }
    var assessmentSubjectFilter by rememberSaveable { mutableStateOf("ALL") }
    var assessmentsTotal by remember { mutableStateOf(0) }
    var assessmentResults by remember { mutableStateOf(emptyList<JSONObject>()) }
    var resultsPageSize by rememberSaveable { mutableStateOf(10) }
    var resultsPageOffset by rememberSaveable { mutableStateOf(0) }
    var resultsTotal by remember { mutableStateOf(0) }
    var assessmentCertificateEligibility by remember { mutableStateOf<Map<Long, JSONObject>>(emptyMap()) }
    var pendingCertificateBytes by remember { mutableStateOf<ByteArray?>(null) }
    var pendingCertificateFilename by remember { mutableStateOf("quantaedge-certificate.pdf") }
    var progressData by remember { mutableStateOf(JSONObject()) }
    var documents by remember { mutableStateOf(emptyList<JSONObject>()) }
    var selectedDocument by remember { mutableStateOf(JSONObject()) }
    var documentPage by remember { mutableStateOf(1) }
    var documentImage by remember { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
    var documentFullscreen by rememberSaveable { mutableStateOf(false) }
    var documentThumbnailImages by remember { mutableStateOf(emptyMap<Int, androidx.compose.ui.graphics.ImageBitmap>()) }
    var documentThumbnailRequests by remember { mutableStateOf(emptySet<String>()) }
    var tutorSession by remember { mutableStateOf(JSONObject()) }
    var tutorMessages by remember { mutableStateOf(emptyList<JSONObject>()) }
    var tutorQuestionId by remember { mutableStateOf(0L) }
    var tutorAvailable by remember { mutableStateOf(false) }
    var tutorSendInFlight by remember { mutableStateOf(false) }
    var tutorConnecting by remember { mutableStateOf(false) }
    var tutorMinimized by remember { mutableStateOf(false) }
    var tutorHasOlderMessages by remember { mutableStateOf(false) }
    var tutorOlderBeforeId by remember { mutableStateOf<Long?>(null) }
    var tutorLoadingOlderMessages by remember { mutableStateOf(false) }
    var liveClasses by remember { mutableStateOf(emptyList<JSONObject>()) }
    var recordedClasses by remember { mutableStateOf(emptyList<JSONObject>()) }
    var selectedRecording by remember { mutableStateOf(JSONObject()) }

    // Each route owns its own snapshot counter. Updating a background route's counter
    // won't invalidate the currently visible route, unlike one shared SnapshotStateMap.
    val pendingTaskCounts = remember {
        mutableMapOf<AppRoute, androidx.compose.runtime.MutableState<Int>>()
    }
    fun pendingTasksFor(targetRoute: AppRoute): androidx.compose.runtime.MutableState<Int> =
        pendingTaskCounts.getOrPut(targetRoute) { mutableStateOf(0) }
    val busy = pendingTasksFor(route).value > 0
    var error by remember { mutableStateOf("") }
    var errorStatusCode by remember { mutableStateOf(0) }
    var status by remember { mutableStateOf("") }
    val answers = remember { mutableStateMapOf<String, String>() }
    val feedbacks = remember { mutableStateMapOf<String, String>() }
    val lessonAnswerLocks = remember { mutableStateMapOf<String, Boolean>() }
    val resourceDownloadStates = remember { mutableStateMapOf<Long, ResourceDownloadUiState>() }
    // Keep the app interface locale separate from the learner's actual content language.
    val learningContentLanguage = learningLanguage

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

    // SnapshotFlow observes answer changes without rebuilding the effect for every keystroke.
    // collectLatest cancels the pending debounce when another answer arrives.
    LaunchedEffect(route, assessmentAttempt.toString()) {
        if (route == AppRoute.ASSESSMENT_ATTEMPT &&
            assessmentAttempt.optBoolean("offlineMode") &&
            !assessmentAttempt.optBoolean("offlinePendingSync") &&
            assessmentAttempt.textAny("status") == "IN_PROGRESS") {
            snapshotFlow { answers.toMap() }
                .distinctUntilChanged()
                .collectLatest { draft ->
                    delay(300)
                    withContext(Dispatchers.IO) {
                        offlineAssessmentStore.saveAttempt(assessmentAttempt, draft, "DRAFT")
                    }
                }
        }
    }

    // Observe WorkManager events instead of polling every 1.2 seconds. Each update writes only
    // the changed document; the library screen owns reads from this state map.
    LaunchedEffect(route, resourceOwner, documents, language) {
        if (route == AppRoute.RESOURCES) {
            val documentIds = documents.map { it.optLongAny("document_id", "id") }
                .filter { it > 0L }.distinct()
            coroutineScope {
                documentIds.forEach { documentId ->
                    launch {
                        val uniqueName = "qe-resource-" + resourceOwner.take(16) + "-" + documentId
                        WorkManager.getInstance(context.applicationContext)
                            .getWorkInfosForUniqueWorkFlow(uniqueName)
                            .collectLatest { workInfos ->
                                val work = workInfos.lastOrNull()
                                if (work == null) {
                                    resourceDownloadStates.remove(documentId)
                                } else {
                                    val next = resourceDownloadUiState(work, language)
                                    if (resourceDownloadStates[documentId] != next) {
                                        resourceDownloadStates[documentId] = next
                                    }
                                }
                            }
                    }
                }
                awaitCancellation()
            }
        } else {
            // Don't retain document-specific progress after leaving the library or switching accounts.
            if (resourceDownloadStates.isNotEmpty()) resourceDownloadStates.clear()
        }
    }

    fun reportError(exception: Exception) {
        errorStatusCode = (exception as? ApiFailure)?.statusCode ?: 0
        error = when (exception) {
            is ApiFailure -> when (exception.statusCode) {
                401 -> if (language == "hi") "आपका सत्र समाप्त हो गया है। जारी रखने के लिए फिर से साइन इन करें।"
                    else "Your session has expired. Sign in again to continue."
                403 -> if (language == "hi") "इस खाते को इस काम की अनुमति नहीं है।"
                    else "This account does not have permission to access this item."
                else -> exception.message
            }
            is IOException -> if (language == "hi") "QuantaEdge server से संपर्क नहीं हो पाया। नेटवर्क और API URL जाँचें।"
                else "Couldn't reach the QuantaEdge server. Check your network and API URL."
            else -> exception.message?.takeIf { it.isNotBlank() }
                ?: if (language == "hi") "कुछ गलत हो गया। दोबारा प्रयास करें।"
                else "Something went wrong. Please try again."
        }
    }

    fun runTask(action: suspend () -> Unit) {
        val ownerRoute = route
        val routeTaskCount = pendingTasksFor(ownerRoute)
        routeTaskCount.value += 1
        scope.launch {
            error = ""
            errorStatusCode = 0
            status = ""
            try {
                action()
            } catch (exception: Exception) {
                reportError(exception)
            } finally {
                routeTaskCount.value = (routeTaskCount.value - 1).coerceAtLeast(0)
            }
        }
    }

    suspend fun refreshStudentPrograms() {
        val response = runCatching {
            ApiClient.getArray("/api/v1/students/me/programs").objectItems()
        }
        studentProgramsLoadFailed = response.isFailure
        val programs = response.getOrDefault(emptyList())
        studentPrograms = programs
        val progress = linkedMapOf<Long, JSONObject>()
        programs.filter { it.optBoolean("is_enrolled", it.optBoolean("enrolled")) }.forEach { program ->
            val id = program.longAny("id", "course_id")
            if (id > 0L) runCatching {
                ApiClient.getObject("/api/v1/students/me?courseId=" + id)
            }.getOrNull()?.let { progress[id] = it }
        }
        courseProgress = progress
    }

    suspend fun refreshStudentTextbookDocuments() {
        val loaded = mutableListOf<JSONObject>()
        var requestFailed = false
        listOf("maths", "science").forEach { subjectCode ->
            val response = runCatching {
                ApiClient.getArray("/api/v1/learning/documents?subjectCode=" +
                    ApiClient.encodeQuery(subjectCode) + "&language=" + learningContentLanguage).objectItems()
            }
            if (response.isFailure) requestFailed = true
            loaded.addAll(response.getOrDefault(emptyList()))
        }
        studentTextbookDocuments = loaded.distinctBy { it.optLongAny("document_id", "id") }
        studentTextbookDocumentsLoadFailed = requestFailed && studentTextbookDocuments.isEmpty()
    }

    suspend fun enterSignedInHome(user: JSONObject) {
        auth = user
        val role = user.textAny("role").uppercase()
        if (role.contains("PARENT") || role.contains("GUARDIAN")) {
            parentAccount = ApiClient.getObject("/api/v1/guardians/account")
            parentChildren = ApiClient.getArray("/api/v1/guardians/children").objectItems()
            route = AppRoute.PARENT_HOME
        } else if (role.contains("STUDENT") || role.contains("LEARNER")) {
            studentProfile = ApiClient.getObject("/api/v1/students/me")
            // Keep content language independent from the app interface language.
            val savedLearningLanguage = studentProfile.textAny("language", "learningLanguage", "learning_language")
                .trim().lowercase().takeIf { it == "hi" || it == "en" }
            if (savedLearningLanguage != null) {
                learningLanguage = savedLearningLanguage
                preferences.edit().putString("contentLanguage", savedLearningLanguage).apply()
            }
            refreshStudentPrograms()
            refreshStudentTextbookDocuments()
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
                route = AppRoute.WELCOME
                if (exception !is ApiFailure || (exception.statusCode != 401 && exception.statusCode != 403)) {
                    reportError(exception)
                }
            }
        }
    }

    fun signIn(mode: String, mobile: String, parentMobile: String, username: String, password: String) {
        runTask {
            ApiClient.setWorkspace(if (mode == "PARENT") "PARENT" else "STUDENT")
            if (mode == "PARENT") {
                ApiClient.postObject("/api/v1/auth/parent-login", JSONObject()
                    .put("mobile", mobile.trim()).put("password", password))
            } else if (mode == "LEARNER") {
                ApiClient.postObject("/api/v1/auth/learner-login", JSONObject()
                    .put("mobile", mobile.trim()).put("password", password))
            } else {
                ApiClient.postObject("/api/v1/auth/student-login", JSONObject()
                    .put("parentMobile", parentMobile.trim())
                    .put("username", username.trim()).put("password", password))
            }
            refreshUser()
            // Remove unauthenticated form state (including saved password/OTP drafts) once login succeeds.
            AppRoute.values().forEach { savedRoute ->
                saveableStateHolder.removeState("anonymous:${savedRoute.name}")
            }
        }
    }

    fun requestParentOtp(purpose: String, mobile: String, onRequested: (String?) -> Unit) {
        runTask {
            ApiClient.setWorkspace("PARENT")
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
            ApiClient.setWorkspace("PARENT")
            val body = JSONObject().put("mobile", mobile.trim()).put("otp", otp.trim())
                .put("purpose", purpose).put("password", password)
            if (purpose == "SIGNUP") body.put("displayName", displayName.trim())
            ApiClient.postObject("/api/v1/auth/verify-otp", body)
            status = when (purpose) {
                "SIGNUP" -> if (language == "hi") "अभिभावक खाता बन गया।" else "Parent account created."
                "PASSWORD_RESET" -> if (language == "hi") "पासवर्ड अपडेट हो गया।" else "Password updated."
                else -> if (language == "hi") "OTP से साइन इन सफल रहा।" else "Signed in with OTP."
            }
            refreshUser()
            AppRoute.values().forEach { savedRoute ->
                saveableStateHolder.removeState("anonymous:${savedRoute.name}")
            }
        }
    }

    fun enrollCourse(courseId: Long) {
        runTask {
            if (courseId <= 0L) throw IOException("Choose a valid course.")
            ApiClient.postObject("/api/v1/students/me/programs/id/" + courseId + "/enroll", JSONObject())
            refreshStudentPrograms()
            studentProfile = ApiClient.getObject("/api/v1/students/me")
            status = if (language == "hi") "Course join हो गया।" else "Course joined."
        }
    }

    fun openSubject(subjectCode: String, courseId: Long = 0L) {
        structuredLessonMode = false
        currentSubject = subjectCode
        currentCourseId = courseId.coerceAtLeast(0L)
        lessons = emptyList()
        route = AppRoute.SUBJECT_LESSONS
        runTask {
            if (studentProfile.length() == 0) studentProfile = ApiClient.getObject("/api/v1/students/me")
            var classCode = studentProfile.textAny("class_code", "classCode")
            if (classCode.isBlank()) {
                val profile = ApiClient.getObject("/api/v1/students/me/profile")
                classCode = profile.textAny("class_code", "classCode")
            }
            if (classCode.isBlank()) throw IOException("आपकी कक्षा API से नहीं मिली। अपने खाते की जानकारी जाँचें।")
            val path = if (courseId > 0L) {
                "/api/v1/learning/lessons?subjectCode=" + ApiClient.encodeQuery(subjectCode) +
                    "&language=" + learningContentLanguage + "&courseId=" + courseId
            } else {
                "/api/v1/learning/lessons?classCode=" + ApiClient.encodeQuery(classCode) +
                    "&subjectCode=" + ApiClient.encodeQuery(subjectCode) + "&language=" + learningContentLanguage
            }
            lessons = ApiClient.getArray(path).objectItems()
            route = AppRoute.SUBJECT_LESSONS
        }
    }

    fun openStructuredLessons() {
        structuredLessonMode = true
        currentSubject = "science"
        structuredLessons = emptyList()
        route = AppRoute.SUBJECT_LESSONS
        runTask {
            val array = ApiClient.getArray("/api/v1/learning/structured-lessons?language=" + learningLanguage)
            structuredLessons = (0 until array.length()).mapNotNull { array.optJSONObject(it) }
        }
    }

    fun openStructuredLesson(item: JSONObject) {
        val artifactId = item.optString("artifactId")
        if (artifactId.isBlank()) {
            error = if (language == "hi") "यह structured lesson पहचान में नहीं आया।" else "This structured lesson could not be identified."
            return
        }
        structuredLessonMode = true
        lessonReturnRoute = AppRoute.SUBJECT_LESSONS
        structuredLessonArtifactId = artifactId
        runTask {
            val encodedId = ApiClient.encodeQuery(artifactId)
            lesson = ApiClient.getObject("/api/v1/learning/structured-lessons/" + encodedId + "?language=" + learningLanguage)
            structuredLessonSavedResponses = ApiClient.getObject(
                "/api/v1/learning/structured-lessons/" + encodedId + "/responses?language=" + learningLanguage
            )
            structuredLessonPendingQuestionIds = emptySet()
            structuredLessonPendingActivityIds = emptySet()
            route = AppRoute.LESSON
        }
    }

    fun openLesson(lessonRow: JSONObject) {
        structuredLessonMode = false
        lessonReturnRoute = route.takeIf {
            it !in setOf(AppRoute.LESSON, AppRoute.PRACTICE, AppRoute.TUTOR, AppRoute.RECORDING_PLAYER)
        } ?: AppRoute.SUBJECT_LESSONS
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
        diagnostic = JSONObject()
        route = AppRoute.DIAGNOSTIC
        runTask {
            diagnostic = ApiClient.getObject("/api/v1/diagnostic")
            feedbacks.clear()
            route = AppRoute.DIAGNOSTIC
        }
    }

    fun openAssessments(subjectFilter: String = "ALL") {
        val normalizedFilter = subjectFilter.uppercase().takeIf { it == "ALL" }
            ?: subjectFilter.lowercase().takeIf { it in setOf("maths", "science") }
            ?: "ALL"
        if (assessmentSubjectFilter != normalizedFilter) assessmentsPageOffset = 0
        assessmentSubjectFilter = normalizedFilter
        if (normalizedFilter in setOf("maths", "science")) currentSubject = normalizedFilter
        val subjectCode = if (normalizedFilter == "ALL") "" else normalizedFilter
        route = AppRoute.ASSESSMENTS
        runTask {
            val local = withContext(Dispatchers.IO) { offlineAssessmentStore.catalogueItems() }
                .filter {
                    subjectCode.isBlank() || it.textAny("subject_code", "subjectCode").isBlank() ||
                        it.textAny("subject_code", "subjectCode") == subjectCode
                }
            offlineAssessments = local
            val localIds = local.map { it.optLongAny("assessment_id", "assessmentId", "id") }.toSet()
            val onlineResponse = runCatching {
                val filterQuery = if (subjectCode.isBlank()) "" else
                    "&subjectCode=" + ApiClient.encodeQuery(subjectCode)
                val path = "/api/v1/learning/assessments?language=" + learningContentLanguage +
                    filterQuery + "&limit=" + assessmentsPageSize + "&offset=" + assessmentsPageOffset
                ApiClient.getObject(path)
            }.getOrNull()
            val onlinePage = onlineResponse?.optJSONArray("items")?.objectItems().orEmpty()
            assessmentsTotal = if (onlineResponse != null) onlineResponse.optInt("total", onlinePage.size) else 0
            assessments = onlinePage.filterNot {
                it.optLongAny("assessment_id", "assessmentId", "id") in localIds
            }
            if (onlineResponse == null) {
                status = if (local.isEmpty()) {
                    "ऑफ़लाइन: पहले से तैयार offline tests उपलब्ध नहीं हैं। नया test डाउनलोड करने के लिए internet चाहिए।"
                } else {
                    "ऑफ़लाइन: पहले से तैयार encrypted test यहीं जारी रखें। उत्तर reconnect होने पर server पर जाँचे जाएँगे।"
                }
            } else if (local.isNotEmpty()) {
                status = "Prepared offline attempts इस device पर उपलब्ध हैं; नए tests के लिए server catalogue sync है।"
            }
            route = AppRoute.ASSESSMENTS
        }
    }

    fun changeAssessmentsPage(pageSize: Int, pageOffset: Int) {
        assessmentsPageSize = pageSize.coerceIn(10, 50)
        assessmentsPageOffset = pageOffset.coerceAtLeast(0)
        openAssessments(assessmentSubjectFilter)
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


    suspend fun loadProgressCertificateData(data: JSONObject) {
        val recent = data.optJSONArray("recentAssessments")?.objectItems() ?: emptyList()
        val attemptIds = recent.map { it.longAny("attempt_id", "attemptId") }
            .filter { it > 0L }.distinct().take(100)
        val mapped = linkedMapOf<Long, JSONObject>()
        if (attemptIds.isNotEmpty()) {
            runCatching {
                ApiClient.getObject(
                    "/api/v1/students/me/certificates/assessment-eligibility?attemptIds=" +
                        attemptIds.joinToString(",")
                ).optJSONArray("items").objectItems()
            }.getOrDefault(emptyList()).forEach { item ->
                val id = item.longAny("attempt_id", "attemptId")
                if (id > 0L) mapped[id] = JSONObject(item.toString())
            }
        }
        // The issued-certificate catalogue is authoritative for the existing View/PDF/PNG actions.
        runCatching {
            ApiClient.getObject("/api/v1/students/me/certificates")
                .optJSONArray("items").objectItems()
        }.getOrDefault(emptyList()).forEach { certificate ->
            val attemptId = certificate.longAny("assessment_attempt_id", "assessmentAttemptId", "attempt_id", "attemptId")
            val certificateId = certificate.longAny("id", "certificate_id", "certificateId")
            if (attemptId > 0L && (attemptIds.isEmpty() || attemptId in attemptIds)) {
                val entry = JSONObject((mapped[attemptId] ?: JSONObject()).toString())
                entry.put("attempt_id", attemptId)
                if (certificateId > 0L) entry.put("certificate_id", certificateId)
                entry.put("certificate_status", certificate.textAny("status", "certificate_status").uppercase())
                if (certificate.has("certificate_number")) entry.put("certificate_number", certificate.optString("certificate_number"))
                mapped[attemptId] = entry
            }
        }
        assessmentCertificateEligibility = mapped
    }

    suspend fun loadAssessmentResultsData() {
        val resultResponse = ApiClient.getObject(
            "/api/v1/learning/assessment-results?language=" + learningContentLanguage +
                "&limit=" + resultsPageSize + "&offset=" + resultsPageOffset
        )
        val loaded = resultResponse.optJSONArray("items").objectItems()
        assessmentResults = loaded
        resultsTotal = resultResponse.optInt("total", loaded.size)
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
        if (route != AppRoute.ASSESSMENT_RESULTS) {
            resultsReturnRoute = route
            resultsPageOffset = 0
        }
        route = AppRoute.ASSESSMENT_RESULTS
        runTask {
            loadAssessmentResultsData()
            route = AppRoute.ASSESSMENT_RESULTS
        }
    }

    fun changeResultsPage(pageSize: Int, pageOffset: Int) {
        resultsPageSize = pageSize.coerceIn(10, 50)
        resultsPageOffset = pageOffset.coerceAtLeast(0)
        runTask {
            loadAssessmentResultsData()
            route = AppRoute.ASSESSMENT_RESULTS
        }
    }

    suspend fun refreshCertificateEligibilityForAttempt(attemptId: Long) {
        if (attemptId <= 0L) return
        runCatching {
            ApiClient.getObject(
                "/api/v1/students/me/certificates/assessment-eligibility?attemptIds=" + attemptId
            ).optJSONArray("items").objectItems()
                .firstOrNull { it.longAny("attempt_id", "attemptId") == attemptId }
        }.getOrNull()?.let { refreshed ->
            assessmentCertificateEligibility = assessmentCertificateEligibility + (attemptId to refreshed)
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
                    .put("language", certificateLanguage)
            )
            when (route) {
                AppRoute.PROGRESS -> loadProgressCertificateData(progressData)
                else -> loadAssessmentResultsData()
            }
            // Preserve the exact attempt even when it falls outside the visible results page.
            refreshCertificateEligibilityForAttempt(attemptId)
            status = if (language == "hi") "प्रमाणपत्र जारी हो गया। अब इस परिणाम से PDF या PNG सेव करें।"
                else "Certificate issued. You can now save its PDF or PNG from this result."
        }
    }

    fun viewResultCertificate(certificateId: Long) {
        runTask {
            if (certificateId <= 0L) throw IOException("Certificate record could not be identified.")
            val bytes = withContext(Dispatchers.IO) {
                ApiClient.getBytes("/api/v1/students/me/certificates/" + certificateId + "/view?format=pdf")
            }
            if (bytes.isEmpty()) throw IOException("The server returned an empty certificate file.")
            val directory = java.io.File(context.cacheDir, "certificates")
            if (!directory.exists() && !directory.mkdirs()) throw IOException("Could not prepare certificate preview.")
            val file = java.io.File(directory, "quantaedge-certificate-" + certificateId + ".pdf")
            withContext(Dispatchers.IO) { file.writeBytes(bytes) }
            val uri = androidx.core.content.FileProvider.getUriForFile(
                context, context.packageName + ".fileprovider", file
            )
            val intent = android.content.Intent(android.content.Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/pdf")
                .addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            context.startActivity(android.content.Intent.createChooser(
                intent, if (language == "hi") "प्रमाणपत्र देखें" else "View certificate"
            ))
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
        liveClasses = emptyList()
        recordedClasses = emptyList()
        route = AppRoute.CLASSES
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

    fun tutorErrorMessage(exception: Exception): String {
        val english = language != "hi"
        val detail = exception.message.orEmpty().lowercase()
        return when {
            exception is ApiFailure && exception.statusCode == 401 ->
                if (english) "Your session may have expired. Please sign in again."
                else "आपका सत्र समाप्त हो गया हो सकता है। कृपया फिर से लॉग इन करें।"
            exception is ApiFailure && exception.statusCode == 403 ->
                if (english) "You do not have access to this lesson or tutor session."
                else "आपको इस पाठ या ट्यूटर सत्र की अनुमति नहीं है।"
            exception is ApiFailure && exception.statusCode == 429 || "usage limit" in detail || "daily tutor" in detail ->
                if (english) "You have reached the tutor usage limit. Please try again later."
                else "ट्यूटर उपयोग की सीमा पूरी हो गई है। कृपया बाद में फिर प्रयास करें।"
            "too long" in detail ->
                if (english) "Please shorten your message and try again."
                else "कृपया अपना संदेश छोटा करके फिर भेजें।"
            exception is ApiFailure && exception.statusCode == 503 ->
                if (english) "The AI tutor is not enabled right now. Please try again later."
                else "AI ट्यूटर अभी चालू नहीं है। कृपया बाद में फिर प्रयास करें।"
            exception is ApiFailure && exception.statusCode >= 500 ->
                if (english) "The tutor service had a temporary error. Your message is ready to retry."
                else "AI ट्यूटर सेवा में अस्थायी समस्या आई। आपका संदेश फिर भेजने के लिए तैयार है।"
            detail.contains("tutor शुरू करने") ->
                if (english) "Open a lesson before starting the tutor."
                else "ट्यूटर शुरू करने से पहले पाठ खोलें।"
            exception is IOException ->
                if (english) "Could not reach the tutor. Check your connection and retry."
                else "ट्यूटर से संपर्क नहीं हो पाया। कनेक्शन जाँचकर फिर प्रयास करें।"
            else ->
                if (english) "The tutor could not complete this request. Please try again."
                else "ट्यूटर यह अनुरोध पूरा नहीं कर सका। कृपया फिर प्रयास करें।"
        }
    }

    fun openTutorWithQuestion(questionId: Long) {
        tutorQuestionId = questionId
        tutorMinimized = false
        route = AppRoute.TUTOR
        tutorConnecting = true
        tutorAvailable = false
        tutorSession = JSONObject()
        tutorMessages = emptyList()
        tutorHasOlderMessages = false
        tutorOlderBeforeId = null
        error = ""
        runTask {
            try {
                val lessonId = lesson.longAny("id", "lesson_id")
                if (lessonId <= 0L) throw IOException("Tutor शुरू करने के लिए पहले पाठ खोलें।")
                tutorSession = ApiClient.postObject("/api/v1/tutor/sessions",
                    JSONObject().put("lessonId", lessonId).put("language", learningContentLanguage))
                tutorAvailable = tutorSession.optBoolean("available")
                if (tutorAvailable) {
                    val initial = ApiClient.getObject("/api/v1/tutor/sessions/" +
                        tutorSession.longAny("sessionId", "session_id") + "/messages?limit=30")
                    tutorMessages = initial.optJSONArray("items").objectItems()
                    tutorHasOlderMessages = initial.optBoolean("hasMore")
                    tutorOlderBeforeId = initial.opt("nextBeforeId")?.toString()?.toLongOrNull()
                    if (tutorOlderBeforeId == null) tutorHasOlderMessages = false
                } else {
                    tutorMessages = emptyList()
                    error = if (language == "hi") "इस पाठ के लिए AI Tutor अभी उपलब्ध नहीं है।"
                        else "AI tutor is not enabled for this lesson yet."
                }
            } catch (exception: Exception) {
                tutorAvailable = false
                error = tutorErrorMessage(exception)
            } finally {
                tutorConnecting = false
            }
        }
    }

    fun openTutor() = openTutorWithQuestion(0L)

    fun minimizeTutor() {
        tutorMinimized = true
        route = AppRoute.LESSON
    }

    fun restoreTutor() {
        tutorMinimized = false
        route = AppRoute.TUTOR
    }

    fun retryTutorConnection() = openTutorWithQuestion(tutorQuestionId)

    fun startNewTutorChat() {
        if (busy || tutorConnecting || tutorSendInFlight || !tutorAvailable) return
        tutorConnecting = true
        error = ""
        runTask {
            try {
                val lessonId = lesson.longAny("id", "lesson_id")
                if (lessonId <= 0L) throw IOException("Tutor शुरू करने के लिए पहले पाठ खोलें।")
                tutorSession = ApiClient.postObject("/api/v1/tutor/sessions",
                    JSONObject().put("lessonId", lessonId)
                        .put("language", learningContentLanguage)
                        .put("newChat", true))
                tutorAvailable = tutorSession.optBoolean("available")
                tutorMessages = emptyList()
                tutorHasOlderMessages = false
                tutorOlderBeforeId = null
                if (!tutorAvailable) {
                    error = if (language == "hi") "नई चैट बन गई, लेकिन AI Tutor अभी उपलब्ध नहीं है।"
                        else "A new chat was created, but the AI tutor is currently unavailable."
                }
            } catch (exception: Exception) {
                tutorAvailable = false
                error = tutorErrorMessage(exception)
            } finally {
                tutorConnecting = false
            }
        }
    }

    fun loadOlderTutorMessages() {
        val sessionId = tutorSession.longAny("sessionId", "session_id")
        val beforeId = tutorOlderBeforeId
        if (sessionId <= 0L || beforeId == null || !tutorHasOlderMessages || tutorLoadingOlderMessages) return
        error = ""
        tutorLoadingOlderMessages = true
        scope.launch {
            try {
                val olderPage = ApiClient.getObject(
                    "/api/v1/tutor/sessions/" + sessionId + "/messages?limit=30&beforeId=" + beforeId
                )
                val older = olderPage.optJSONArray("items").objectItems()
                tutorMessages = (older + tutorMessages).distinctBy { item ->
                    item.opt("id")?.toString()?.takeIf { it.isNotBlank() && it != "null" }
                        ?: (item.textAny("role") + "|" + item.textAny("message") + "|" + item.optString("created_at"))
                }
                tutorHasOlderMessages = olderPage.optBoolean("hasMore")
                tutorOlderBeforeId = olderPage.opt("nextBeforeId")?.toString()?.toLongOrNull()
                if (tutorOlderBeforeId == null) tutorHasOlderMessages = false
            } catch (exception: Exception) {
                error = tutorErrorMessage(exception)
            } finally {
                tutorLoadingOlderMessages = false
            }
        }
    }

    suspend fun recoverTutorTurn(sessionId: Long, userMessage: String): JSONObject? {
        // Keep parity with the web client's in-house model recovery: a gateway timeout can
        // arrive before a slow model finishes and saves its reply to the tutor history.
        for (attempt in 0 until 46) {
            if (attempt > 0) delay(2000)
            val page = runCatching {
                ApiClient.getObject("/api/v1/tutor/sessions/" + sessionId + "/messages?limit=30")
            }.getOrNull() ?: continue
            val items = page.optJSONArray("items").objectItems()
            var userIndex = -1
            for (index in items.indices.reversed()) {
                if (items[index].textAny("role").equals("USER", true) &&
                    items[index].textAny("message") == userMessage) {
                    userIndex = index
                    break
                }
            }
            if (userIndex >= 0 && items.drop(userIndex + 1).any {
                    it.textAny("role").equals("ASSISTANT", true)
                }) return page
        }
        return null
    }

    fun applyTutorMessagePage(page: JSONObject) {
        tutorMessages = page.optJSONArray("items").objectItems()
        tutorHasOlderMessages = page.optBoolean("hasMore")
        tutorOlderBeforeId = page.opt("nextBeforeId")?.toString()?.toLongOrNull()
        if (tutorOlderBeforeId == null) tutorHasOlderMessages = false
    }

    fun sendTutorMessage(message: String, mode: String) {
        if (busy || tutorSendInFlight || message.isBlank()) return
        val sessionId = tutorSession.longAny("sessionId", "session_id")
        if (sessionId <= 0L) {
            error = if (language == "hi") "ट्यूटर सत्र तैयार नहीं है। फिर से जोड़ें।"
                else "The tutor session is not ready. Reconnect and try again."
            return
        }
        // Render the user's turn immediately, as the web tutor does, then reconcile with
        // persisted server history after the model responds.
        val previousMessages = tutorMessages
        val optimisticMessage = JSONObject()
            .put("clientId", "pending-" + System.currentTimeMillis())
            .put("role", "USER")
            .put("mode", mode)
            .put("message", message)
            .put("created_at", System.currentTimeMillis())
        tutorMessages = previousMessages + optimisticMessage
        tutorSendInFlight = true
        error = ""
        scope.launch {
            try {
                try {
                    ApiClient.postObject("/api/v1/tutor/sessions/" + sessionId + "/messages",
                        JSONObject().put("message", message).put("mode", mode).put("questionId", tutorQuestionId)
                            .put("language", learningContentLanguage))
                    val latestPage = ApiClient.getObject("/api/v1/tutor/sessions/" + sessionId + "/messages?limit=30")
                    applyTutorMessagePage(latestPage)
                } catch (exception: Exception) {
                    // Do not wait for a recoverable turn on a definite authorization or
                    // validation error. Network/server failures are reconciled against history.
                    if (exception is ApiFailure &&
                        exception.statusCode in 400..499 &&
                        exception.statusCode != 408 &&
                        exception.statusCode != 429) {
                        tutorMessages = previousMessages
                        throw exception
                    }
                    val recoveredPage = recoverTutorTurn(sessionId, message)
                    if (recoveredPage != null) {
                        applyTutorMessagePage(recoveredPage)
                    } else {
                        tutorMessages = previousMessages
                        throw exception
                    }
                }
            } catch (exception: Exception) {
                tutorMessages = previousMessages
                error = tutorErrorMessage(exception)
            } finally {
                tutorSendInFlight = false
            }
        }
    }

    fun openRecording(item: JSONObject) {
        selectedRecording = JSONObject(item.toString())
        route = AppRoute.RECORDING_PLAYER
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
        documents = emptyList()
        route = AppRoute.RESOURCES
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

    suspend fun loadPage(page: Int, forceRefresh: Boolean = false) {
        val documentId = selectedDocument.optLongAny("document_id", "id")
        val pageCount = selectedDocument.optInt("page_count",
            (selectedDocument.optInt("page_end", 1) - selectedDocument.optInt("page_start", 1) + 1).coerceAtLeast(1))
        if (documentId <= 0L || page < 1 || page > pageCount) throw IOException("यह पृष्ठ उपलब्ध नहीं है।")
        documentPage = page
        documentImage = null
        if (forceRefresh) resourceStore.removePage(documentId, page)
        val cachedPage = if (forceRefresh) null else resourceStore.readPage(documentId, page)
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
        documentFullscreen = false
        documentThumbnailImages = emptyMap()
        documentThumbnailRequests = emptySet()
        runTask {
            selectedDocument = JSONObject(document.toString())
            documentPage = document.optInt("last_page", 1).coerceAtLeast(1)
            loadPage(documentPage)
            route = AppRoute.DOCUMENT_READER
        }
    }

    fun retryDocumentPage() {
        val page = documentPage
        runTask { loadPage(page, forceRefresh = true) }
    }

    fun loadDocumentThumbnails(pages: List<Int>) {
        val documentId = selectedDocument.optLongAny("document_id", "id")
        val pageCount = selectedDocument.optInt("page_count",
            (selectedDocument.optInt("page_end", 1) - selectedDocument.optInt("page_start", 1) + 1).coerceAtLeast(1))
        if (documentId <= 0L || pageCount <= 0) return
        val requested = pages.distinct().filter { it in 1..pageCount }
        documentThumbnailImages = documentThumbnailImages.filterKeys { it in requested }
        requested.forEach { pageNumber ->
            val requestKey = "$documentId:$pageNumber"
            if (documentThumbnailImages.containsKey(pageNumber) || requestKey in documentThumbnailRequests) return@forEach
            documentThumbnailRequests = documentThumbnailRequests + requestKey
            scope.launch {
                try {
                    val bytes = resourceStore.readPage(documentId, pageNumber)
                        ?: ApiClient.getBytes("/api/v1/learning/documents/" + documentId + "/pages/" + pageNumber)
                    val options = BitmapFactory.Options().apply {
                        inSampleSize = 4
                        inPreferredConfig = android.graphics.Bitmap.Config.RGB_565
                    }
                    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
                    if (bitmap != null && selectedDocument.optLongAny("document_id", "id") == documentId &&
                        pageNumber in ((documentPage - 4).coerceAtLeast(1)..(documentPage + 4).coerceAtMost(pageCount))) {
                        documentThumbnailImages = documentThumbnailImages + (pageNumber to bitmap.asImageBitmap())
                    }
                } catch (_: Exception) {
                    // Keep the page-number tile available; the main reader remains the retry path.
                } finally {
                    documentThumbnailRequests = documentThumbnailRequests - requestKey
                }
            }
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
            val explanation = result.textAny("explanation")
            feedbacks[answerKey] = if (explanation.isNotBlank()) {
                feedback + "\n\n" + (if (language == "hi") "व्याख्या: " else "Explanation: ") + explanation
            } else feedback
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
            val feedback = response.textAny("feedback").ifBlank {
                if (response.isNull("correct")) "उत्तर सुरक्षित हुआ; मूल्यांकन बाकी है।"
                else if (response.optBoolean("correct")) "सही उत्तर।"
                else "उत्तर सही नहीं था। फिर प्रयास करें।"
            }
            val explanation = response.textAny("explanation")
            feedbacks[questionId.toString()] = if (explanation.isNotBlank()) {
                feedback + "\n\n" + (if (language == "hi") "व्याख्या: " else "Explanation: ") + explanation
            } else feedback
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
            val feedback = response.textAny("feedback").ifBlank { "उत्तर सुरक्षित हुआ।" }
            val explanation = response.textAny("explanation")
            feedbacks[questionId.toString()] = if (explanation.isNotBlank()) {
                feedback + "\n\n" + (if (language == "hi") "व्याख्या: " else "Explanation: ") + explanation
            } else feedback
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
        route = AppRoute.PROFILE
        runTask {
            studentProfile = ApiClient.getObject("/api/v1/students/me/profile")
            studentProfile.textAny("language", "learningLanguage", "learning_language")
                .trim().lowercase().takeIf { it == "hi" || it == "en" }?.let { savedLanguage ->
                    learningLanguage = savedLanguage
                    preferences.edit().putString("contentLanguage", savedLanguage).apply()
                }
            route = AppRoute.PROFILE
        }
    }

    fun openAccountLifecycle() {
        route = AppRoute.ACCOUNT_LIFECYCLE
        runTask {
            ApiClient.setWorkspace("STUDENT")
            accountLifecycle = ApiClient.getObject("/api/v1/account/me/lifecycle")
            route = AppRoute.ACCOUNT_LIFECYCLE
        }
    }

    fun refreshAccountLifecycle() {
        runTask {
            accountLifecycle = ApiClient.getObject("/api/v1/account/me/lifecycle")
        }
    }

    fun requestAdultVerificationReview() {
        runTask {
            ApiClient.postObject("/api/v1/account/me/adult-verification-request",
                JSONObject().put("consentAccepted", true))
            accountLifecycle = ApiClient.getObject("/api/v1/account/me/lifecycle")
            status = if (language == "hi") "सत्यापन अनुरोध भेज दिया गया।" else "Verification request sent."
        }
    }

    fun requestLearnerConversionOtp(mobile: String, onRequested: (String?) -> Unit) {
        runTask {
            val response = ApiClient.postObject("/api/v1/auth/learner-transition/request-otp",
                JSONObject().put("mobile", mobile.trim()).put("consentAccepted", true))
            val devCode = response.optString("devCode").takeIf { it.isNotBlank() && it != "null" }
            onRequested(devCode)
            status = if (language == "hi") "OTP भेज दिया गया।" else "OTP requested."
        }
    }

    fun completeLearnerConversion(mobile: String, otp: String, password: String) {
        runTask {
            ApiClient.postObject("/api/v1/auth/verify-otp",
                JSONObject().put("mobile", mobile.trim()).put("otp", otp.trim())
                    .put("purpose", "LEARNER_TRANSITION").put("password", password))
            refreshUser()
            status = if (language == "hi") "Independent learner account तैयार है।" else "Independent learner account is ready."
        }
    }

    fun requestParentEligibilityReview() {
        runTask {
            ApiClient.postObject("/api/v1/account/me/parent-eligibility-request",
                JSONObject().put("consentAccepted", true))
            accountLifecycle = ApiClient.getObject("/api/v1/account/me/lifecycle")
            status = if (language == "hi") "Parent eligibility अनुरोध भेज दिया गया।" else "Parent eligibility request sent."
        }
    }

    fun transitionToParentAccount() {
        runTask {
            ApiClient.postObject("/api/v1/account/me/transition-to-parent",
                JSONObject().put("confirmParentTransition", true))
            ApiClient.clearSession()
            AppRoute.values().forEach { savedRoute ->
                saveableStateHolder.removeState(stateOwnerKey + ":" + savedRoute.name)
                saveableStateHolder.removeState("anonymous:" + savedRoute.name)
            }
            auth = JSONObject()
            studentProfile = JSONObject()
            studentPrograms = emptyList()
            studentProgramsLoadFailed = false
            courseProgress = emptyMap()
            studentTextbookDocuments = emptyList()
            studentTextbookDocumentsLoadFailed = false
            accountLifecycle = JSONObject()
            route = AppRoute.WELCOME
            loginMode = "PARENT"
            status = if (language == "hi") "अब अपने parent account से sign in करें।" else "Sign in to your parent account."
        }
    }

    fun saveStudentProfile(body: JSONObject) {
        runTask {
            val profile = ApiClient.putObject("/api/v1/students/me/profile", body)
            studentProfile = ApiClient.getObject("/api/v1/students/me/profile")
            studentProfile.textAny("language", "learningLanguage", "learning_language")
                .trim().lowercase().takeIf { it == "hi" || it == "en" }?.let { savedLanguage ->
                    learningLanguage = savedLanguage
                    preferences.edit().putString("contentLanguage", savedLanguage).apply()
                }
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

    fun removeStudentPhoto() {
        runTask {
            ApiClient.deleteObject("/api/v1/students/me/profile-photo")
            studentProfile = ApiClient.getObject("/api/v1/students/me/profile")
            status = if (language == "hi") "प्रोफ़ाइल फ़ोटो हटा दी गई।" else "Profile photo removed."
        }
    }

    fun openProgress() {
        route = AppRoute.PROGRESS
        runTask {
            progressData = ApiClient.getObject("/api/v1/students/me")
            loadProgressCertificateData(progressData)
            route = AppRoute.PROGRESS
        }
    }

        fun openParentProfile() {
        route = AppRoute.PARENT_PROFILE
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

    fun removeParentPhoto() {
        runTask {
            ApiClient.deleteObject("/api/v1/guardians/profile-photo")
            parentProfile = ApiClient.getObject("/api/v1/guardians/profile")
            status = if (language == "hi") "अभिभावक की प्रोफ़ाइल फ़ोटो हटा दी गई।" else "Parent profile photo removed."
        }
    }

    fun viewParentCertificate(childId: Long, certificateId: Long) {
        runTask {
            if (childId <= 0L || certificateId <= 0L) throw IOException("Certificate record could not be identified.")
            val bytes = withContext(Dispatchers.IO) {
                ApiClient.getBytes("/api/v1/guardians/children/" + childId + "/certificates/" +
                    certificateId + "/view?format=pdf")
            }
            if (bytes.isEmpty()) throw IOException("The server returned an empty certificate file.")
            val directory = java.io.File(context.cacheDir, "certificates")
            if (!directory.exists() && !directory.mkdirs()) throw IOException("Could not prepare certificate preview.")
            val file = java.io.File(directory, "quantaedge-child-" + childId + "-certificate-" + certificateId + ".pdf")
            withContext(Dispatchers.IO) { file.writeBytes(bytes) }
            val uri = androidx.core.content.FileProvider.getUriForFile(
                context, context.packageName + ".fileprovider", file
            )
            val intent = android.content.Intent(android.content.Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/pdf")
                .addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            context.startActivity(android.content.Intent.createChooser(
                intent, if (language == "hi") "प्रमाणपत्र देखें" else "View certificate"
            ))
        }
    }

    fun downloadParentCertificate(childId: Long, certificateId: Long, format: String) {
        runTask {
            if (childId <= 0L || certificateId <= 0L) throw IOException("Certificate record could not be identified.")
            if (format != "pdf" && format != "png") throw IOException("Choose PDF or PNG.")
            val bytes = withContext(Dispatchers.IO) {
                ApiClient.getBytes("/api/v1/guardians/children/" + childId + "/certificates/" +
                    certificateId + "/download?format=" + format)
            }
            if (bytes.isEmpty()) throw IOException("The server returned an empty certificate file.")
            pendingCertificateBytes = bytes
            pendingCertificateFilename = "quantaedge-child-$childId-certificate-$certificateId.$format"
            if (format == "pdf") resultCertificatePdfLauncher.launch(pendingCertificateFilename)
            else resultCertificatePngLauncher.launch(pendingCertificateFilename)
        }
    }

    fun openCreateChild() {
        route = AppRoute.PARENT_CHILD_CREATE
        error = ""
    }

    fun createChild(name: String, classCode: String, childLanguage: String, username: String, password: String, tracks: List<String>, consent: Boolean) {
        runTask {
            if (tracks.isEmpty()) throw IOException("कम-से-कम एक विषय चुनें।")
            ApiClient.postObject("/api/v1/guardians/children",
                JSONObject()
                    .put("displayName", name.trim())
                    .put("classCode", classCode)
                    .put("language", childLanguage)
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

    fun openArchivedChildren() {
        parentSection = "children"
        archivedChildren = emptyList()
        route = AppRoute.PARENT_CHILD_ARCHIVED
        runTask {
            archivedChildren = ApiClient.getArray("/api/v1/guardians/children/archived").objectItems()
            parentSection = "children"
            route = AppRoute.PARENT_CHILD_ARCHIVED
        }
    }

    fun restoreArchivedChild(child: JSONObject) {
        runTask {
            val id = child.longAny("id")
            if (id <= 0L) throw IOException("Archived profile ID is missing.")
            ApiClient.postObject("/api/v1/guardians/children/" + id + "/restore",
                JSONObject().put("consentAccepted", true))
            parentAccount = ApiClient.getObject("/api/v1/guardians/account")
            parentChildren = ApiClient.getArray("/api/v1/guardians/children").objectItems()
            archivedChildren = ApiClient.getArray("/api/v1/guardians/children/archived").objectItems()
            parentSection = "children"
            status = if (language == "hi") "प्रोफ़ाइल restore हो गई। नया student login सेट करें।"
                else "Profile restored. Set new student login credentials."
            route = AppRoute.PARENT_HOME
        }
    }

    fun openManageChild(child: JSONObject) {
        selectedChild = JSONObject(child.toString())
        route = AppRoute.PARENT_CHILD_MANAGE
        error = ""
    }

    fun saveChildProfile(displayName: String, classCode: String, childLanguage: String) {
        runTask {
            val id = selectedChild.longAny("id")
            if (id <= 0L) throw IOException("Student profile ID is missing.")
            if (displayName.trim().length !in 2..120) throw IOException("Enter a valid student name.")
            if (childLanguage !in setOf("hi", "en")) throw IOException("Choose Hindi or English.")
            ApiClient.putObject("/api/v1/guardians/children/" + id + "/profile",
                JSONObject().put("displayName", displayName.trim())
                    .put("classCode", classCode).put("language", childLanguage))
            parentChildren = ApiClient.getArray("/api/v1/guardians/children").objectItems()
            selectedChild = parentChildren.firstOrNull { it.longAny("id") == id } ?: selectedChild
            status = if (language == "hi") "छात्र प्रोफ़ाइल सुरक्षित हो गई।" else "Student profile saved."
        }
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
        val childId = child.longAny("id")
        parentReport = JSONObject().put("_childId", childId)
            .put("_childName", child.textAny("display_name", "displayName"))
        parentCertificates = emptyList()
        route = AppRoute.PARENT_REPORT
        runTask {
            val id = child.longAny("id")
            parentReport = ApiClient.getObject("/api/v1/guardians/children/" + id + "/report")
            parentReport.put("_childName", child.textAny("display_name", "displayName"))
            parentReport.put("_childId", id)
            parentCertificates = runCatching {
                ApiClient.getObject("/api/v1/guardians/children/" + id + "/certificates")
                    .optJSONArray("items").objectItems()
            }.getOrDefault(emptyList())
            route = AppRoute.PARENT_REPORT
        }
    }

    fun logout() {
        runTask {
            runCatching { ApiClient.postObject("/api/v1/auth/logout") }
            WorkManager.getInstance(context.applicationContext).cancelAllWorkByTag("quantaedge-resource-download")
            ApiClient.clearSession()
            AppRoute.values().forEach { savedRoute ->
                saveableStateHolder.removeState("$stateOwnerKey:${savedRoute.name}")
                saveableStateHolder.removeState("anonymous:${savedRoute.name}")
            }
            auth = JSONObject()
            studentProfile = JSONObject()
            studentPrograms = emptyList()
            courseProgress = emptyMap()
            studentTextbookDocuments = emptyList()
            accountLifecycle = JSONObject()
            recommendation = JSONObject()
            parentAccount = JSONObject()
            parentChildren = emptyList()
            archivedChildren = emptyList()
            parentReport = JSONObject()
            parentCertificates = emptyList()
            parentProfile = JSONObject()
            selectedChild = JSONObject()
            lessons = emptyList()
            lesson = JSONObject()
            practice = JSONObject()
            diagnostic = JSONObject()
            assessments = emptyList()
            offlineAssessments = emptyList()
            assessmentsPageOffset = 0
            assessmentsTotal = 0
            assessmentAttempt = JSONObject()
            assessmentResults = emptyList()
            resultsPageOffset = 0
            resultsTotal = 0
            assessmentCertificateEligibility = emptyMap()
            pendingCertificateBytes = null
            progressData = JSONObject()
            documents = emptyList()
            selectedDocument = JSONObject()
            documentPage = 1
            documentImage = null
            tutorSession = JSONObject()
            tutorMessages = emptyList()
            tutorAvailable = false
            liveClasses = emptyList()
            recordedClasses = emptyList()
            selectedRecording = JSONObject()
            answers.clear()
            feedbacks.clear()
            lessonAnswerLocks.clear()
            resourceDownloadStates.clear()
            currentSubject = "maths"
            currentCourseId = 0L
            route = AppRoute.WELCOME
            loginMode = "STUDENT"
            error = ""
            status = "आप सुरक्षित रूप से साइन आउट हो गए।"
        }
    }

    fun openPublicPage(path: String) {
        val safePath = if (path == "/privacy" || path == "/terms") path else "/"
        runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://qe.thinkwithsujeet.in" + safePath)))
        }.onFailure {
            error = if (language == "hi") "पेज नहीं खुल सका।" else "Page could not be opened."
        }
    }

    fun setLanguage(nextLanguage: String) {
        val next = if (nextLanguage == "en") "en" else "hi"
        language = next
        preferences.edit().putString("language", next).apply()
        error = ""
        status = ""
    }

    fun toggleLanguage() {
        // Interface locale switches immediately; learning content preference is saved separately.
        setLanguage(if (language == "hi") "en" else "hi")
    }

    fun setLearningContentLanguage(nextLanguage: String) {
        val next = if (nextLanguage == "en") "en" else "hi"
        if (learningLanguage == next) {
            accountMenuExpanded = false
            return
        }
        val previous = learningLanguage
        learningLanguage = next
        preferences.edit().putString("contentLanguage", next).apply()
        studentProfile = JSONObject(studentProfile.toString()).put("language", next)
        accountMenuExpanded = false
        runTask {
            try {
                ApiClient.patchObject("/api/v1/students/me/language", JSONObject().put("language", next))
                refreshStudentTextbookDocuments()
                status = if (language == "hi") "पढ़ाई की भाषा अपडेट हो गई।" else "Learning content language updated."
            } catch (exception: Exception) {
                learningLanguage = previous
                preferences.edit().putString("contentLanguage", previous).apply()
                studentProfile = JSONObject(studentProfile.toString()).put("language", previous)
                throw exception
            }
        }
    }

    fun retryCurrentRead() {
        error = ""
        errorStatusCode = 0
        when (route) {
            AppRoute.STUDENT_HOME -> runTask {
                studentProfile = ApiClient.getObject("/api/v1/students/me")
                recommendation = ApiClient.getObject("/api/v1/recommendations/next")
                refreshStudentPrograms()
                refreshStudentTextbookDocuments()
            }
            AppRoute.PARENT_HOME -> refreshParentData()
            AppRoute.PARENT_REPORT -> {
                val childId = parentReport.optLong("_childId")
                val child = parentChildren.firstOrNull { it.longAny("id") == childId }
                    ?: selectedChild.takeIf { it.longAny("id") == childId }
                if (child != null) openParentReport(child) else refreshParentData()
            }
            AppRoute.PARENT_PROFILE -> openParentProfile()
            AppRoute.PARENT_CHILD_ARCHIVED -> openArchivedChildren()
            AppRoute.PROFILE -> openStudentProfile()
            AppRoute.ACCOUNT_LIFECYCLE -> refreshAccountLifecycle()
            AppRoute.PROGRESS -> openProgress()
            AppRoute.SUBJECT_LESSONS -> if (structuredLessonMode) openStructuredLessons() else openSubject(currentSubject, currentCourseId)
            AppRoute.DIAGNOSTIC -> openDiagnostic()
            AppRoute.ASSESSMENTS -> openAssessments(assessmentSubjectFilter)
            AppRoute.ASSESSMENT_RESULTS -> loadAssessmentResults()
            AppRoute.RESOURCES -> openResources(currentSubject)
            AppRoute.DOCUMENT_READER -> retryDocumentPage()
            AppRoute.CLASSES -> openClasses(currentSubject)
            AppRoute.RECORDING_PLAYER -> openRecording(selectedRecording)
            else -> Unit
        }
    }

    fun currentRouteHasSafeReadRetry(): Boolean = route in setOf(
        AppRoute.STUDENT_HOME, AppRoute.PARENT_HOME, AppRoute.PARENT_REPORT,
        AppRoute.PARENT_PROFILE, AppRoute.PARENT_CHILD_ARCHIVED, AppRoute.PROFILE,
        AppRoute.ACCOUNT_LIFECYCLE, AppRoute.PROGRESS, AppRoute.SUBJECT_LESSONS,
        AppRoute.DIAGNOSTIC, AppRoute.ASSESSMENTS, AppRoute.ASSESSMENT_RESULTS,
        AppRoute.RESOURCES, AppRoute.DOCUMENT_READER, AppRoute.CLASSES,
        AppRoute.RECORDING_PLAYER
    )

    fun goBack() {
        route = when (route) {
            AppRoute.STUDENT_HOME, AppRoute.PARENT_HOME -> route
            AppRoute.WELCOME -> AppRoute.WELCOME
            AppRoute.LOGIN -> AppRoute.WELCOME
            AppRoute.PARENT_SIGNUP, AppRoute.PARENT_RESET -> AppRoute.LOGIN
            AppRoute.CERTIFICATES, AppRoute.LEADERBOARD -> AppRoute.STUDENT_HOME
            AppRoute.ACCOUNT_LIFECYCLE -> AppRoute.PROFILE
            AppRoute.SUBJECT_LESSONS, AppRoute.DIAGNOSTIC, AppRoute.ASSESSMENTS,
            AppRoute.RESOURCES, AppRoute.PROFILE, AppRoute.PROGRESS -> if (auth.textAny("role").contains("PARENT", true)) AppRoute.PARENT_HOME else AppRoute.STUDENT_HOME
            AppRoute.ASSESSMENT_RESULTS -> when (resultsReturnRoute) {
                AppRoute.ASSESSMENTS, AppRoute.ASSESSMENT_ATTEMPT, AppRoute.ASSESSMENT_REVIEW,
                AppRoute.PROGRESS, AppRoute.PROFILE, AppRoute.SUBJECT_LESSONS, AppRoute.DIAGNOSTIC -> resultsReturnRoute
                else -> AppRoute.STUDENT_HOME
            }
            AppRoute.LESSON -> if (lessonReturnRoute in setOf(AppRoute.SUBJECT_LESSONS, AppRoute.STUDENT_HOME)) lessonReturnRoute else AppRoute.SUBJECT_LESSONS
            AppRoute.PRACTICE -> AppRoute.LESSON
            AppRoute.TUTOR -> AppRoute.LESSON
            AppRoute.CLASSES -> if (auth.textAny("role").contains("PARENT", true)) AppRoute.PARENT_HOME else AppRoute.STUDENT_HOME
            AppRoute.RECORDING_PLAYER -> AppRoute.CLASSES
            AppRoute.ASSESSMENT_ATTEMPT -> AppRoute.ASSESSMENTS
            AppRoute.ASSESSMENT_REVIEW -> AppRoute.ASSESSMENT_RESULTS
            AppRoute.PARENT_REPORT, AppRoute.PARENT_PROFILE, AppRoute.PARENT_CHILD_CREATE,
            AppRoute.PARENT_CHILD_MANAGE, AppRoute.PARENT_CHILD_ARCHIVED -> AppRoute.PARENT_HOME
            AppRoute.DOCUMENT_READER -> AppRoute.RESOURCES
        }
        documentFullscreen = false
        error = ""
    }

    // Orientation controls navigation placement: portrait uses the bottom bar, landscape the left rail.
    val landscapeLayout = StudentNavigationContract.usesLeftRail(
        LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    )
    val role = auth.textAny("role")
    val parentRole = role.contains("PARENT", true) || role.contains("GUARDIAN", true)
    val signedIn = role.isNotBlank() && route !in setOf(AppRoute.WELCOME, AppRoute.LOGIN, AppRoute.PARENT_SIGNUP, AppRoute.PARENT_RESET)
    val headerPhotoUrl = if (parentRole) {
        parentProfile.textAny("profile_image_url", "profileImageUrl")
            .ifBlank { parentAccount.textAny("profile_image_url", "profileImageUrl") }
    } else {
        studentProfile.textAny("profile_image_url", "profileImageUrl")
            .ifBlank { auth.textAny("profile_image_url", "profileImageUrl") }
    }
    val headerDisplayName = if (parentRole) {
        parentProfile.textAny("display_name", "displayName", "name")
            .ifBlank { parentAccount.textAny("parentName", "display_name", "displayName", "name") }
    } else {
        studentProfile.textAny("display_name", "displayName", "name")
            .ifBlank { auth.textAny("display_name", "displayName", "name") }
    }
    LaunchedEffect(headerPhotoUrl) {
        headerPhotoBytes = if (headerPhotoUrl.isBlank()) null
            else runCatching { ApiClient.getBytes(headerPhotoUrl) }.getOrNull()
    }
    val headerPhotoBitmap = remember(headerPhotoBytes) {
        headerPhotoBytes?.let { bytes -> runCatching {
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
        }.getOrNull() }
    }

    fun studentGroupForRoute(destination: AppRoute): String =
        StudentNavigationContract.studentGroupForRoute(destination, lastStudentNavGroup)

    // Keep the same five student groups and order as the web workspace.
    // Parent accounts retain their own three web destinations.
    val navItems = if (parentRole) listOf(
        AppNavItem("family", if (language == "hi") "परिवार" else "Family report", "⌂"),
        AppNavItem("children", if (language == "hi") "बच्चे" else "Children", "♧"),
        AppNavItem("profile", if (language == "hi") "प्रोफ़ाइल" else "Profile", "◉")
    ) else listOf(
        AppNavItem("today", if (language == "hi") "आज" else "Today", "⌂"),
        AppNavItem("learn", if (language == "hi") "पढ़ाई" else "Learn", "▤"),
        AppNavItem("practice", if (language == "hi") "अभ्यास" else "Practice", "✦"),
        AppNavItem("progress", if (language == "hi") "प्रगति" else "Progress", "↗"),
        AppNavItem("library", if (language == "hi") "किताबें" else "Books", "▣")
    )
    val selectedNavKey = if (parentRole) {
        StudentNavigationContract.parentGroupForRoute(route, parentSection)
    } else studentGroupForRoute(route)

    fun selectPrimaryDestination(key: String) {
        error = ""; status = ""
        when (key) {
            "family" -> { parentSection = "family"; route = AppRoute.PARENT_HOME }
            "children" -> { parentSection = "children"; route = AppRoute.PARENT_HOME }
            "profile" -> if (parentRole) openParentProfile() else {
                lastStudentNavGroup = studentGroupForRoute(route)
                openStudentProfile()
            }
            "today" -> { lastStudentNavGroup = key; route = AppRoute.STUDENT_HOME }
            "learn" -> { lastStudentNavGroup = key; openSubject(currentSubject) }
            "practice" -> { lastStudentNavGroup = key; openAssessments("ALL") }
            "progress" -> { lastStudentNavGroup = key; openProgress() }
            "library" -> { lastStudentNavGroup = key; openResources(currentSubject) }
        }
    }
    BackHandler(enabled = signedIn || route == AppRoute.LOGIN) {
        if (route == AppRoute.DOCUMENT_READER && documentFullscreen) {
            documentFullscreen = false
        } else if (route !in setOf(AppRoute.STUDENT_HOME, AppRoute.PARENT_HOME)) {
            goBack()
        }
    }
    Surface(
        modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing),
        color = MaterialTheme.colorScheme.background
    ) {
        CompositionLocalProvider(LocalQuantaLanguage provides language) {
        Row(modifier = Modifier.fillMaxSize()) {
            if (signedIn && landscapeLayout && route != AppRoute.TUTOR && !(route == AppRoute.DOCUMENT_READER && documentFullscreen)) NavigationRail {
                navItems.forEach { item ->
                    NavigationRailItem(selected = item.key == selectedNavKey, onClick = { selectPrimaryDestination(item.key) },
                        icon = { Text(item.glyph) }, label = { Text(item.label) }, alwaysShowLabel = true)
                }
            }
            Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
            if (route != AppRoute.LOGIN && route != AppRoute.TUTOR && !(route == AppRoute.DOCUMENT_READER && documentFullscreen)) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (route !in setOf(AppRoute.STUDENT_HOME, AppRoute.PARENT_HOME, AppRoute.RECORDING_PLAYER)) {
                        TextButton(onClick = { goBack() }) { Text(localizedUiText("←")) }
                    }
                    QuantaEdgeBrand(modifier = Modifier, compact = true)
                    Spacer(modifier = Modifier.weight(1f))
                    if (signedIn) {
                        Box {
                            Surface(
                                modifier = Modifier.size(38.dp).clickable(enabled = !busy) {
                                    accountMenuExpanded = true
                                },
                                shape = CircleShape,
                                color = MaterialTheme.colorScheme.surfaceVariant,
                                tonalElevation = 1.dp
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    if (headerPhotoBitmap != null) {
                                        Image(
                                            bitmap = headerPhotoBitmap,
                                            contentDescription = if (language == "hi") "प्रोफ़ाइल खोलें" else "Open profile",
                                            modifier = Modifier.fillMaxSize().clip(CircleShape),
                                            contentScale = ContentScale.Crop
                                        )
                                    } else {
                                        Text(
                                            headerDisplayName.firstOrNull()?.uppercase() ?: "Q",
                                            color = MaterialTheme.colorScheme.primary,
                                            style = MaterialTheme.typography.titleMedium,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                    }
                                }
                            }
                            DropdownMenu(
                                expanded = accountMenuExpanded,
                                onDismissRequest = { accountMenuExpanded = false },
                                modifier = Modifier.widthIn(min = 260.dp, max = 320.dp)
                            ) {
                                Text(
                                    if (language == "hi") "ऐप इंटरफ़ेस की भाषा" else "App interface language",
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 7.dp),
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    if (language == "hi") "केवल मेन्यू, बटन और नेविगेशन बदलता है — पाठों की भाषा नहीं।"
                                    else "Changes menus, buttons and navigation only — not lesson language.",
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 0.dp),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                DropdownMenuItem(
                                    text = { Text((if (language == "hi") "✓ " else "") + "हिन्दी (Hindi)") },
                                    onClick = { setLanguage("hi") },
                                    enabled = !busy
                                )
                                DropdownMenuItem(
                                    text = { Text((if (language == "en") "✓ " else "") + "English") },
                                    onClick = { setLanguage("en") },
                                    enabled = !busy
                                )
                                if (!parentRole) {
                                    HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp))
                                    Text(
                                        if (language == "hi") "पढ़ाई की सामग्री की भाषा" else "Learning content language",
                                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 7.dp),
                                        style = MaterialTheme.typography.labelLarge,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Text(
                                        if (language == "hi") "पाठ, प्रश्न, किताबें और AI Tutor के जवाब बदलता है।"
                                        else "Changes lessons, questions, books and AI Tutor replies.",
                                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 0.dp),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    DropdownMenuItem(
                                        text = { Text((if (learningLanguage == "hi") "✓ " else "") + "हिन्दी (Hindi)") },
                                        onClick = { setLearningContentLanguage("hi") },
                                        enabled = !busy
                                    )
                                    DropdownMenuItem(
                                        text = { Text((if (learningLanguage == "en") "✓ " else "") + "English") },
                                        onClick = { setLearningContentLanguage("en") },
                                        enabled = !busy
                                    )
                                }
                                HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp))
                                DropdownMenuItem(
                                    text = { Text(if (language == "hi") "प्रोफ़ाइल और सेटिंग" else "Profile & settings") },
                                    onClick = {
                                        accountMenuExpanded = false
                                        if (parentRole) openParentProfile() else {
                                            lastStudentNavGroup = studentGroupForRoute(route)
                                            openStudentProfile()
                                        }
                                    },
                                    enabled = !busy
                                )
                                DropdownMenuItem(
                                    text = { Text(if (language == "hi") "साइन आउट" else "Log out") },
                                    onClick = { accountMenuExpanded = false; logout() },
                                    enabled = !busy
                                )
                            }
                        }
                    }
                }
                HorizontalDivider()
                // Secondary links stay text-first within their group; no feature route becomes a new primary icon.
                if (signedIn && !parentRole) {
                    when (selectedNavKey) {
                        "learn" -> Row(
                            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 2.dp),
                            horizontalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            TextButton(onClick = { openSubject(currentSubject) }, enabled = !busy) {
                                Text(if (language == "hi") "पाठ और विषय" else "Lessons & topics")
                            }
                            TextButton(onClick = { openClasses(currentSubject) }, enabled = !busy) {
                                Text(if (language == "hi") "कक्षाएँ" else "Live classes")
                            }
                            TextButton(onClick = { openTutor() }, enabled = !busy && lesson.longAny("id", "lesson_id") > 0L) {
                                Text(if (language == "hi") "ट्यूटर" else "Tutor")
                            }
                        }
                        "practice" -> Row(
                            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 2.dp),
                            horizontalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            TextButton(
                                onClick = {
                                    if (lesson.longAny("id", "lesson_id") > 0L) startPractice()
                                    else openSubject(currentSubject)
                                },
                                enabled = !busy
                            ) {
                                Text(if (lesson.longAny("id", "lesson_id") > 0L) {
                                    if (language == "hi") "विषय अभ्यास" else "Topic practice"
                                } else {
                                    if (language == "hi") "विषय चुनें" else "Choose a topic"
                                })
                            }
                            TextButton(onClick = { openAssessments("ALL") }, enabled = !busy) {
                                Text(if (language == "hi") "टेस्ट" else "Tests")
                            }
                            TextButton(onClick = { loadAssessmentResults() }, enabled = !busy) {
                                Text(if (language == "hi") "परिणाम" else "Results")
                            }
                            if (recommendation.textAny("kind", "type").contains("DIAGNOSTIC", true)) {
                                TextButton(onClick = { openDiagnostic() }, enabled = !busy) {
                                    Text(if (language == "hi") "डायग्नोस्टिक" else "Diagnostic")
                                }
                            }
                            TextButton(onClick = { route = AppRoute.LEADERBOARD }, enabled = !busy) {
                                Text(if (language == "hi") "टॉप 20" else "Top 20")
                            }
                        }
                        "progress" -> Row(
                            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 2.dp),
                            horizontalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            TextButton(onClick = { openProgress() }, enabled = !busy) {
                                Text(if (language == "hi") "प्रगति" else "My progress")
                            }
                            TextButton(onClick = { route = AppRoute.CERTIFICATES }, enabled = !busy) {
                                Text(if (language == "hi") "प्रमाणपत्र" else "Certificates")
                            }
                        }
                    }
                }
            }

            if (busy) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 5.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Text(
                        if (language == "hi") "लोड हो रहा है…" else "Loading…",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.secondary
                    )
                }
            }
            if (error.isNotBlank()) {
                Surface(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(text = localizedUiText(error), style = MaterialTheme.typography.bodyMedium)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (errorStatusCode == 401) {
                                TextButton(onClick = ::logout, enabled = !busy) {
                                    Text(if (language == "hi") "फिर से साइन इन करें" else "Sign in again")
                                }
                            } else if (errorStatusCode != 403 && currentRouteHasSafeReadRetry()) {
                                TextButton(onClick = ::retryCurrentRead, enabled = !busy) {
                                    Text(if (language == "hi") "फिर प्रयास करें" else "Retry")
                                }
                            }
                            TextButton(onClick = { error = ""; errorStatusCode = 0 }, enabled = !busy) {
                                Text(if (language == "hi") "बंद करें" else "Dismiss")
                            }
                        }
                    }
                }
            }
            if (status.isNotBlank()) {
                Text(
                    text = localizedUiText(status),
                    color = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)
                )
            }

            Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                Box(modifier = Modifier.widthIn(max = 1120.dp).fillMaxWidth().fillMaxHeight()) {
                saveableStateHolder.SaveableStateProvider("$stateOwnerKey:${route.name}") {
                when (route) {
                    AppRoute.WELCOME -> WelcomeScreen(
                        language = language,
                        onToggleLanguage = ::toggleLanguage,
                        onStudentLogin = { loginMode = "STUDENT"; error = ""; status = ""; route = AppRoute.LOGIN },
                        onParentLogin = { loginMode = "PARENT"; error = ""; status = ""; route = AppRoute.LOGIN },
                        onPrivacy = { openPublicPage("/privacy") },
                        onTerms = { openPublicPage("/terms") }
                    )
                    AppRoute.LOGIN -> LoginScreen(
                        language = language,
                        initialMode = loginMode,
                        onToggleLanguage = ::toggleLanguage,
                        busy = busy,
                        error = error,
                        onSignIn = ::signIn,
                        onRequestOtp = { number, callback -> requestParentOtp("LOGIN", number, callback) },
                        onVerifyOtp = { number, otp -> verifyParentOtp("LOGIN", number, "", otp, "") },
                        onSignup = { route = AppRoute.PARENT_SIGNUP },
                        onResetPassword = { route = AppRoute.PARENT_RESET }
                    )
                    AppRoute.PARENT_SIGNUP -> ParentOtpFlowScreen(
                        language = language,
                        onToggleLanguage = ::toggleLanguage,
                        isSignup = true,
                        busy = busy,
                        status = status,
                        onRequestOtp = { mobile, callback -> requestParentOtp("SIGNUP", mobile, callback) },
                        onVerify = { mobile, name, otp, password -> verifyParentOtp("SIGNUP", mobile, name, otp, password) }
                    )
                    AppRoute.PARENT_RESET -> ParentOtpFlowScreen(
                        language = language,
                        onToggleLanguage = ::toggleLanguage,
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
                        programs = studentPrograms,
                        programsLoadFailed = studentProgramsLoadFailed,
                        courseProgress = courseProgress,
                        onEnrollCourse = ::enrollCourse,
                        onOpenCourseSubject = { code, courseId -> openSubject(code, courseId) },
                        textbookDocuments = studentTextbookDocuments,
                        textbookDocumentsLoadFailed = studentTextbookDocumentsLoadFailed,
                        onOpenDocument = ::openDocument,
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
                            refreshStudentPrograms()
                            refreshStudentTextbookDocuments()
                        } },
                        onOpenRecommendation = { recommended -> openLesson(recommended) },
                        onClasses = ::openClasses
                    )
                    AppRoute.PARENT_HOME -> ParentHomeScreen(
                        parent = parentAccount,
                        children = parentChildren,
                        section = parentSection,
                        language = language,
                        busy = busy,
                        onRefresh = { runTask {
                            parentAccount = ApiClient.getObject("/api/v1/guardians/account")
                            parentChildren = ApiClient.getArray("/api/v1/guardians/children").objectItems()
                        } },
                        onReport = ::openParentReport,
                        onManageChild = ::openManageChild,
                        onAddChild = ::openCreateChild,
                        onArchivedProfiles = ::openArchivedChildren,
                        onProfile = ::openParentProfile,
                        onLogout = ::logout,
                        maxChildren = parentAccount.optInt("maxChildren", 3)
                    )
                    AppRoute.PARENT_REPORT -> ParentReportScreen(
                        report = parentReport,
                        children = parentChildren,
                        language = language,
                        busy = busy,
                        certificates = parentCertificates,
                        onSelectChild = ::openParentReport,
                        onViewCertificate = ::viewParentCertificate,
                        onDownloadCertificate = ::downloadParentCertificate
                    )
                    AppRoute.PARENT_PROFILE -> ParentProfileScreen(
                        profile = parentProfile,
                        language = language,
                        busy = busy,
                        onSave = ::saveParentProfile,
                        onUploadPhoto = ::saveParentPhoto,
                        onRemovePhoto = ::removeParentPhoto,
                        onLogout = ::logout
                    )
                    AppRoute.PARENT_CHILD_CREATE -> ParentChildCreateScreen(
                        busy = busy,
                        remainingSlots = parentAccount.optInt("remainingSlots", 1),
                        onCreate = ::createChild
                    )
                    AppRoute.PARENT_CHILD_MANAGE -> ParentChildManageScreen(
                        child = selectedChild,
                        language = language,
                        busy = busy,
                        onSaveProfile = ::saveChildProfile,
                        onSaveCredentials = ::saveChildCredentials,
                        onSaveTracks = ::saveChildTracks,
                        onArchive = ::archiveSelectedChild
                    )
                    AppRoute.PARENT_CHILD_ARCHIVED -> ParentArchivedChildrenScreen(
                        children = archivedChildren,
                        busy = busy,
                        error = error,
                        onRefresh = ::openArchivedChildren,
                        onRestore = ::restoreArchivedChild
                    )
                    AppRoute.SUBJECT_LESSONS -> if (structuredLessonMode) StructuredLessonCatalogueScreen(
                        items = structuredLessons,
                        language = language,
                        busy = busy,
                        onRefresh = ::openStructuredLessons,
                        onOpenLesson = ::openStructuredLesson,
                        onOpenLegacyLessons = { structuredLessonMode = false; openSubject(currentSubject, currentCourseId) }
                    ) else LessonListScreen(
                        subject = currentSubject,
                        lessons = lessons,
                        language = language,
                        busy = busy,
                        onRefresh = { openSubject(currentSubject, currentCourseId) },
                        onOpenLesson = ::openLesson,
                        onOpenStructuredLessons = ::openStructuredLessons
                    )
                    AppRoute.LESSON -> if (structuredLessonMode) StructuredLessonRenderer(
                        lesson = lesson,
                        language = learningLanguage,
                        studentMode = true,
                        savedResponses = structuredLessonSavedResponses,
                        submittingQuestionIds = structuredLessonPendingQuestionIds,
                        savingActivityIds = structuredLessonPendingActivityIds,
                        onSubmitResponse = { questionId, responseJson ->
                            if (questionId !in structuredLessonPendingQuestionIds) {
                                structuredLessonPendingQuestionIds = structuredLessonPendingQuestionIds + questionId
                                runTask {
                                    try {
                                        val responseValue = JSONTokener(responseJson).nextValue()
                                        val result = ApiClient.postObject(
                                            "/api/v1/learning/structured-lessons/" + ApiClient.encodeQuery(structuredLessonArtifactId) +
                                                "/questions/" + ApiClient.encodeQuery(questionId) + "/responses?language=" + learningLanguage,
                                            JSONObject().put("response", responseValue)
                                        )
                                        val state = JSONObject().put("response", responseValue)
                                            .put("status", result.optString("status"))
                                            .put("feedback", result.optString("feedback"))
                                        result.optJSONObject("feedbackByLocale")?.let { state.put("feedbackByLocale", it) }
                                        if (result.has("score")) state.put("score", result.optInt("score"))
                                        if (result.has("maxScore")) state.put("maxScore", result.optInt("maxScore"))
                                        val all = JSONObject(structuredLessonSavedResponses.toString())
                                        val questions = JSONObject(all.optJSONObject("questions")?.toString() ?: "{}")
                                        questions.put(questionId, state)
                                        structuredLessonSavedResponses = all.put("questions", questions)
                                    } finally {
                                        structuredLessonPendingQuestionIds = structuredLessonPendingQuestionIds - questionId
                                    }
                                }
                            }
                        },
                        onSaveActivity = { blockId, responseJson ->
                            if (blockId !in structuredLessonPendingActivityIds) {
                                structuredLessonPendingActivityIds = structuredLessonPendingActivityIds + blockId
                                runTask {
                                    try {
                                        ApiClient.postObject(
                                            "/api/v1/learning/structured-lessons/" + ApiClient.encodeQuery(structuredLessonArtifactId) +
                                                "/activities/" + ApiClient.encodeQuery(blockId) + "/responses",
                                            JSONObject().put("response", responseJson)
                                        )
                                        val all = JSONObject(structuredLessonSavedResponses.toString())
                                        val activities = JSONObject(all.optJSONObject("activities")?.toString() ?: "{}")
                                        activities.put(blockId, JSONObject(responseJson))
                                        structuredLessonSavedResponses = all.put("activities", activities)
                                    } finally {
                                        structuredLessonPendingActivityIds = structuredLessonPendingActivityIds - blockId
                                    }
                                }
                            }
                        }
                    ) else LessonScreen(
                        lesson = lesson,
                        language = language,
                        tutorMinimized = tutorMinimized,
                        onRestoreTutor = ::restoreTutor,
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
                        onTutor = ::openTutor,
                        onTutorQuestion = { questionId -> openTutorWithQuestion(questionId) }
                    )
                    AppRoute.PRACTICE -> PracticeScreen(
                        practice = practice,
                        answers = answers,
                        feedbacks = feedbacks,
                        busy = busy,
                        onAnswerChange = { id, value ->
                            answers[id] = value
                            if (value.isNotBlank()) feedbacks.remove(id)
                        },
                        onAnswer = ::savePracticeAnswer,
                        language = language,
                        onComplete = { route = AppRoute.LESSON },
                        onProgress = ::openProgress
                    )
                    AppRoute.DIAGNOSTIC -> DiagnosticScreen(
                        diagnostic = diagnostic,
                        answers = answers,
                        feedbacks = feedbacks,
                        busy = busy,
                        language = language,
                        loadFailed = error.isNotBlank(),
                        onAnswerChange = { id, value -> answers[id] = value },
                        onAnswer = ::saveDiagnosticAnswer,
                        onSubmit = ::submitDiagnostic,
                        onRefresh = ::openDiagnostic
                    )
                    AppRoute.ASSESSMENTS -> AssessmentListScreen(
                        subject = assessmentSubjectFilter,
                        assessmentItems = assessments,
                        offlineItems = offlineAssessments,
                        total = assessmentsTotal,
                        pageSize = assessmentsPageSize,
                        pageOffset = assessmentsPageOffset,
                        language = language,
                        busy = busy,
                        onPageChange = ::changeAssessmentsPage,
                        onSubject = ::openAssessments,
                        onOpen = ::openAssessment,
                        onPrepareOffline = ::prepareOfflineAssessment,
                        onResults = ::loadAssessmentResults,
                        onRefresh = { openAssessments(assessmentSubjectFilter) }
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
                        resultTotal = resultsTotal,
                        pageSize = resultsPageSize,
                        pageOffset = resultsPageOffset,
                        certificateEligibility = assessmentCertificateEligibility,
                        language = language,
                        busy = busy,
                        loadFailed = error.isNotBlank(),
                        onPageChange = ::changeResultsPage,
                        onReview = ::openAssessmentReview,
                        onIssueCertificate = ::issueCertificateForAttempt,
                        onDownloadCertificate = ::downloadResultCertificate,
                        onViewCertificate = ::viewResultCertificate
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
                        language = language,
                        store = resourceStore,
                        busy = busy,
                        error = error,
                        downloadStatus = status,
                        downloadStates = resourceDownloadStates,
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
                        thumbnailImages = documentThumbnailImages,
                        loadingThumbnailPages = documentThumbnailRequests,
                        pageCount = selectedDocument.optInt("page_count",
                            (selectedDocument.optInt("page_end", 1) - selectedDocument.optInt("page_start", 1) + 1).coerceAtLeast(1)),
                        language = language,
                        fullscreen = documentFullscreen,
                        busy = busy,
                        error = error,
                        cachedCount = resourceStore.cachedPageCount(
                            selectedDocument.optLongAny("document_id", "id"),
                            selectedDocument.optInt("page_count", 1)
                        ),
                        onPage = { page -> runTask { loadPage(page) } },
                        onRequestThumbnails = ::loadDocumentThumbnails,
                        onToggleFullscreen = { documentFullscreen = !documentFullscreen },
                        onRetry = ::retryDocumentPage,
                        onDownload = { downloadDocument(selectedDocument) }
                    )
                    AppRoute.PROFILE -> StudentProfileScreen(
                        profile = studentProfile,
                        language = language,
                        busy = busy,
                        onSave = ::saveStudentProfile,
                        onUploadPhoto = ::saveStudentPhoto,
                        onRemovePhoto = ::removeStudentPhoto,
                        onAccountLifecycle = ::openAccountLifecycle,
                        onLogout = ::logout
                    )
                    AppRoute.ACCOUNT_LIFECYCLE -> AccountLifecycleScreen(
                        data = accountLifecycle,
                        language = language,
                        busy = busy,
                        error = error,
                        status = status,
                        onRefresh = ::refreshAccountLifecycle,
                        onRequestAdultReview = ::requestAdultVerificationReview,
                        onRequestConversionOtp = ::requestLearnerConversionOtp,
                        onCompleteConversion = ::completeLearnerConversion,
                        onRequestParentReview = ::requestParentEligibilityReview,
                        onTransitionToParent = ::transitionToParentAccount
                    )
                    AppRoute.PROGRESS -> StudentProgressScreen(
                        data = progressData,
                        language = language,
                        busy = busy,
                        loadFailed = error.isNotBlank(),
                        certificateEligibility = assessmentCertificateEligibility,
                        onRefresh = ::openProgress,
                        onIssueCertificate = ::issueCertificateForAttempt,
                        onViewCertificate = ::viewResultCertificate,
                        onDownloadCertificate = ::downloadResultCertificate
                    )
                    AppRoute.CERTIFICATES -> StudentCertificatesScreen(
                        language = language,
                        certificateLanguage = certificateLanguage,
                        onCertificateLanguageChange = { next ->
                            if (next == "hi" || next == "en") {
                                certificateLanguage = next
                                preferences.edit().putString("certificateLanguage", next).apply()
                            }
                        },
                        onBack = ::openProgress
                    )
                    AppRoute.LEADERBOARD -> AssessmentLeaderboardScreen(
                        language = language,
                        onBack = { route = AppRoute.STUDENT_HOME }
                    )
                    AppRoute.TUTOR -> TutorScreen(
                        lessonTitle = lesson.textAny("title"),
                        learningLanguage = learningContentLanguage,
                        sessionId = tutorSession.longAny("sessionId", "session_id"),
                        connecting = tutorConnecting,
                        available = tutorAvailable,
                        messages = tutorMessages,
                        hasOlderMessages = tutorHasOlderMessages,
                        loadingOlderMessages = tutorLoadingOlderMessages,
                        busy = tutorSendInFlight,
                        error = error,
                        onSend = ::sendTutorMessage,
                        onLoadOlder = ::loadOlderTutorMessages,
                        onNewChat = ::startNewTutorChat,
                        onMinimize = ::minimizeTutor,
                        onRetry = ::retryTutorConnection,
                        onClose = { tutorMinimized = false; route = AppRoute.LESSON }
                    )
                    AppRoute.CLASSES -> LearningClassesScreen(
                        subject = currentSubject,
                        liveItems = liveClasses,
                        recordedItems = recordedClasses,
                        busy = busy,
                        error = error,
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
            }
            if (signedIn && !landscapeLayout && route != AppRoute.TUTOR && !(route == AppRoute.DOCUMENT_READER && documentFullscreen)) NavigationBar {
                navItems.forEach { item ->
                    NavigationBarItem(selected = item.key == selectedNavKey, onClick = { selectPrimaryDestination(item.key) },
                        icon = { Text(item.glyph) }, label = { Text(item.label) })
                }
            }
        }
    }
        }
    }
}

@Composable
private fun LoginScreen(
    language: String,
    initialMode: String,
    onToggleLanguage: () -> Unit,
    busy: Boolean,
    error: String,
    onSignIn: (String, String, String, String, String) -> Unit,
    onRequestOtp: (String, (String?) -> Unit) -> Unit,
    onVerifyOtp: (String, String) -> Unit,
    onSignup: () -> Unit,
    onResetPassword: () -> Unit
) {
    var mode by rememberSaveable(initialMode) { mutableStateOf(initialMode) }
    var mobile by rememberSaveable { mutableStateOf("") }
    var parentMobile by rememberSaveable { mutableStateOf("") }
    var username by rememberSaveable { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var parentLoginMethod by rememberSaveable(initialMode) { mutableStateOf("PASSWORD") }
    var otp by rememberSaveable { mutableStateOf("") }
    var otpRequested by rememberSaveable { mutableStateOf(false) }
    var devCode by rememberSaveable { mutableStateOf("") }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        QuantaEdgeBrand(modifier = Modifier.fillMaxWidth(), compact = false, centered = true)
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = onToggleLanguage, enabled = !busy) { Text(if (language == "hi") "EN" else "हिंदी") }
        }
        Text(if (language == "hi") "साइन इन करें" else "Sign in",
            style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = 6.dp)) {
            if (mode == "STUDENT") Button(onClick = { mode = "STUDENT" }, enabled = !busy) { Text(if (language=="hi") "छात्र" else "Student") }
            else TextButton(onClick = { mode = "STUDENT" }, enabled = !busy) { Text(if (language=="hi") "छात्र" else "Student") }
            if (mode == "LEARNER") Button(onClick = { mode = "LEARNER" }, enabled = !busy) { Text(localizedUiText("Learner")) }
            else TextButton(onClick = { mode = "LEARNER"; otpRequested = false; otp = ""; devCode = "" }, enabled = !busy) { Text(localizedUiText("Learner")) }
            if (mode == "PARENT") Button(onClick = { mode = "PARENT" }, enabled = !busy) { Text(localizedUiText("Parent")) }
            else TextButton(onClick = { mode = "PARENT"; parentLoginMethod = "PASSWORD"; otpRequested = false; otp = ""; devCode = "" }, enabled = !busy) { Text(localizedUiText("Parent")) }
        }
        if (mode == "PARENT") {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
                if (parentLoginMethod == "PASSWORD") Button(onClick = { parentLoginMethod = "PASSWORD"; otpRequested = false; otp = ""; devCode = "" }, enabled = !busy, modifier = Modifier.weight(1f)) { Text(if (language == "hi") "पासवर्ड" else "Password") }
                else OutlinedButton(onClick = { parentLoginMethod = "PASSWORD"; otpRequested = false; otp = ""; devCode = "" }, enabled = !busy, modifier = Modifier.weight(1f)) { Text(if (language == "hi") "पासवर्ड" else "Password") }
                if (parentLoginMethod == "OTP") Button(onClick = { parentLoginMethod = "OTP" }, enabled = !busy, modifier = Modifier.weight(1f)) { Text("OTP") }
                else OutlinedButton(onClick = { parentLoginMethod = "OTP"; otpRequested = false; otp = ""; devCode = "" }, enabled = !busy, modifier = Modifier.weight(1f)) { Text("OTP") }
            }
            OutlinedTextField(value = mobile, onValueChange = { mobile = it.filter(Char::isDigit).take(10); otpRequested = false; otp = ""; devCode = "" },
                label = { Text(if(language=="hi") "Parent mobile (10 अंक)" else "Parent mobile (10 digits)") },
                singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 5.dp))
            if (parentLoginMethod == "PASSWORD") {
                OutlinedTextField(value = password, onValueChange = { password = it }, label = { Text(if(language=="hi") "पासवर्ड" else "Password") },
                    singleLine = true, visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth().padding(top = 5.dp))
                Button(onClick = { onSignIn(mode, mobile, parentMobile, username, password) },
                    enabled = !busy && mobile.length == 10 && password.isNotBlank(),
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                    Text(if (busy) if (language=="hi") "साइन इन हो रहा है…" else "Signing in…" else if (language=="hi") "साइन इन करें" else "Sign in")
                }
            } else if (otpRequested) {
                OutlinedTextField(value = otp, onValueChange = { otp = it.filter(Char::isDigit).take(6) },
                    label = { Text(if (language == "hi") "OTP (6 अंक)" else "OTP (6 digits)") },
                    singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 5.dp))
                if (devCode.isNotBlank()) Text(localizedUiText("Development OTP: ") + devCode,
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
                Button(onClick = { onVerifyOtp(mobile, otp) }, enabled = !busy && otp.length == 6,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                    Text(if (language == "hi") "OTP से साइन इन करें" else "Sign in with OTP")
                }
                TextButton(onClick = { otpRequested = false; otp = ""; devCode = "" }, enabled = !busy) {
                    Text(if (language == "hi") "दूसरा OTP माँगें" else "Request another OTP")
                }
            } else {
                Button(onClick = { onRequestOtp(mobile) { code -> otpRequested = true; devCode = code.orEmpty() } },
                    enabled = !busy && mobile.length == 10, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                    Text(if (language == "hi") "OTP भेजें" else "Send OTP")
                }
            }
        } else if (mode == "LEARNER") {
            OutlinedTextField(value = mobile, onValueChange = { mobile = it.filter(Char::isDigit).take(10) },
                label = { Text(if (language == "hi") "Mobile (10 अंक)" else "Mobile (10 digits)") },
                singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 5.dp))
            OutlinedTextField(value = password, onValueChange = { password = it },
                label = { Text(if (language == "hi") "Password" else "Password") }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth().padding(top = 5.dp))
            Button(onClick = { onSignIn(mode, mobile, "", "", password) },
                enabled = !busy && mobile.length == 10 && password.isNotBlank(),
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                Text(if (busy) "Signing in…" else if (language == "hi") "साइन इन करें" else "Sign in")
            }
        } else {
            OutlinedTextField(value = parentMobile, onValueChange = { parentMobile = it.filter(Char::isDigit).take(10) },
                label = { Text(if(language=="hi") "Parent mobile (10 digits)" else "Registered parent mobile (10 digits)") },
                singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 5.dp))
            OutlinedTextField(value = username, onValueChange = { username = it.trim().take(32) },
                label = { Text(localizedUiText("Student username")) }, singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(top = 5.dp))
            OutlinedTextField(value = password, onValueChange = { password = it },
                label = { Text(if(language=="hi") "पासवर्ड" else "Password") },
                singleLine = true, visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth().padding(top = 5.dp))
            Button(onClick = { onSignIn(mode, mobile, parentMobile, username, password) },
                enabled = !busy && parentMobile.length == 10 && username.isNotBlank() && password.isNotBlank(),
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                Text(if (busy) if (language=="hi") "साइन इन हो रहा है…" else "Signing in…" else if (language=="hi") "साइन इन करें" else "Sign in")
            }
        }
        if (mode == "PARENT") {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                TextButton(onClick = onSignup, enabled = !busy) { Text(if(language=="hi") "Create account" else "Create account") }
                TextButton(onClick = onResetPassword, enabled = !busy) { Text(if(language=="hi") "Forgot password?" else "Forgot password?") }
            }
        }
        if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 5.dp))
        Text(if(language=="hi") "Parent और student accounts अलग हैं।" else "Parent and student accounts are separate.",
            modifier = Modifier.padding(top = 6.dp), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.secondary)
    }
}
@Composable
private fun ParentOtpFlowScreen(
    language: String,
    onToggleLanguage: () -> Unit,
    isSignup: Boolean,
    busy: Boolean,
    status: String,
    onRequestOtp: (String, (String?) -> Unit) -> Unit,
    onVerify: (String, String, String, String) -> Unit
) {
    var mobile by rememberSaveable { mutableStateOf("") }
    var displayName by rememberSaveable { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var otp by remember { mutableStateOf("") }
    var otpRequested by rememberSaveable { mutableStateOf(false) }
    var devCode by remember { mutableStateOf("") }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = onToggleLanguage, enabled = !busy) { Text(if (language=="hi") "EN" else "हिंदी") }
        }
        Text(if(language=="hi") { if(isSignup) "अभिभावक खाता बनाएँ" else "पासवर्ड रीसेट करें" }
            else { if(isSignup) "Create parent account" else "Reset password" },
            style = MaterialTheme.typography.headlineSmall)
        Text(
            if(language=="hi") { if(isSignup) "मोबाइल नंबर सत्यापित करके अपना अभिभावक खाता बनाएँ।" else "अपने पंजीकृत मोबाइल पर OTP प्राप्त करके नया पासवर्ड सेट करें।" }
            else { if(isSignup) "Verify your mobile number to create your parent account." else "Request an OTP on your registered mobile and set a new password." },
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 8.dp, bottom = 14.dp)
        )
        OutlinedTextField(
            value = mobile,
            onValueChange = { mobile = it.filter { ch -> ch.isDigit() }.take(13) },
            label = { Text(if(language=="hi") "मोबाइल नंबर" else "Mobile number") },
            singleLine = true,
            enabled = !otpRequested && !busy,
            modifier = Modifier.fillMaxWidth()
        )
        if (isSignup) {
            OutlinedTextField(
                value = displayName,
                onValueChange = { displayName = it },
                label = { Text(if(language=="hi") "पूरा नाम" else "Full name") },
                singleLine = true,
                enabled = !otpRequested && !busy,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
            )
        }
        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text(if(language=="hi") "नया पासवर्ड (कम से कम 8 अक्षर)" else "New password (at least 8 characters)") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            enabled = !busy,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
        )
        if (otpRequested) {
            OutlinedTextField(
                value = otp,
                onValueChange = { otp = it.filter { ch -> ch.isDigit() }.take(6) },
                label = { Text(localizedUiText("OTP")) },
                singleLine = true,
                enabled = !busy,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
            )
            if (devCode.isNotBlank()) {
                Text((if(language=="hi") "स्थानीय विकास OTP: " else "Development OTP: ") + devCode, color = MaterialTheme.colorScheme.secondary,
                    style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
            }
            Button(
                onClick = { onVerify(mobile, displayName, otp, password) },
                enabled = !busy && otp.length == 6 && password.length >= 8 &&
                    (!isSignup || displayName.trim().length >= 2),
                modifier = Modifier.fillMaxWidth().padding(top = 14.dp)
            ) {
                Text(if(language=="hi") { if(isSignup) "खाता सत्यापित कर बनाएँ" else "पासवर्ड अपडेट करें" } else { if(isSignup) "Verify and create account" else "Update password" })
            }
            TextButton(onClick = { otpRequested = false; otp = ""; devCode = "" }, enabled = !busy) {
                Text(if(language=="hi") "मोबाइल नंबर बदलें" else "Change mobile number")
            }
        } else {
            Button(
                onClick = { onRequestOtp(mobile) { code -> otpRequested = true; devCode = code.orEmpty() } },
                enabled = !busy && mobile.filter { it.isDigit() }.length >= 10 &&
                    password.length >= 8 && (!isSignup || displayName.trim().length >= 2),
                modifier = Modifier.fillMaxWidth().padding(top = 14.dp)
            ) { Text(if(language=="hi") "OTP भेजें" else "Send OTP") }
        }
        if (status.isNotBlank()) {
            Text(localizedUiText(status), style = MaterialTheme.typography.bodySmall,
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
    onClasses: (String) -> Unit,
    programs: List<JSONObject>,
    programsLoadFailed: Boolean,
    courseProgress: Map<Long, JSONObject>,
    onEnrollCourse: (Long) -> Unit,
    onOpenCourseSubject: (String, Long) -> Unit,
    textbookDocuments: List<JSONObject>,
    textbookDocumentsLoadFailed: Boolean,
    onOpenDocument: (JSONObject) -> Unit
) {
    val name = userName.ifBlank { profile.textAny("display_name", "displayName", "name").ifBlank { if (language == "hi") "विद्यार्थी" else "Learner" } }
    val className = profile.textAny("class_name", "className", "class_code", "classCode")
    val lessonStats = profile.optJSONObject("lessonStats") ?: JSONObject()
    val completedLessons = profile.optInt("completedLessons",
        profile.optInt("completed_lessons", lessonStats.optInt("completed_lessons", 0)))
    val totalLessons = profile.optInt("totalLessons",
        profile.optInt("total_lessons", lessonStats.optInt("total_lessons", 0)))
    val questionStats = profile.optJSONObject("questionStats")
    val hasLessonSummary = profile.has("completedLessons") || profile.has("completed_lessons") || profile.has("lessonStats")
    val percent = if (totalLessons > 0) (completedLessons * 100 / totalLessons).coerceIn(0, 100) else 0
    val curriculum = profile.optJSONArray("curriculum")?.objectItems()?.take(2) ?: emptyList()
    val greeting = HomeGreeting.text(LocalTime.now().hour, language)

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(greeting, style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
            Text("$name 👋", style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.secondary, fontWeight = FontWeight.SemiBold)
            Text(
                if (className.isNotBlank()) {
                    if (language == "hi") "आपकी सीखने की जगह · कक्षा $className" else "Your learning space · Class $className"
                } else if (language == "hi") "आपकी सीखने की जगह" else "Your learning space",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        if (hasLessonSummary) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 1.dp
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(if (language == "hi") "सीखने की प्रगति" else "Learning progress",
                                style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                            Text(
                                if (language == "hi") "$completedLessons / $totalLessons पाठ पूरे" else "$completedLessons of $totalLessons lessons completed",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Text("$percent%", style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                    }
                    LinearProgressIndicator(
                        progress = { percent / 100f },
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (questionStats != null) {
                        Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                            Text(
                                (if (language == "hi") "अभ्यास सटीकता " else "Practice accuracy ") +
                                    questionStats.optString("accuracy_percent", "0") + "%",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                questionStats.optString("attempts", "0") +
                                    if (language == "hi") " प्रयास" else " attempts",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }

        Text(if (language == "hi") "आपके विषय" else "Your subjects",
            style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            if (curriculum.isNotEmpty()) {
                curriculum.forEach { subject ->
                    val code = subject.textAny("subject_code", "subjectCode").ifBlank { "maths" }.lowercase()
                    Surface(
                        modifier = Modifier.weight(1f).clickable { onSubject(code) },
                        shape = MaterialTheme.shapes.medium,
                        color = MaterialTheme.colorScheme.surface,
                        tonalElevation = 1.dp
                    ) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            Text(
                                subject.textAny("subject_name", "subject_code").ifBlank {
                                    if (code == "science") "Science" else "Mathematics"
                                },
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(subject.optInt("completion_percent", 0).toString() + "%",
                                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                            LinearProgressIndicator(
                                progress = { subject.optDouble("completion_percent", 0.0).toFloat().coerceIn(0f, 100f) / 100f },
                                modifier = Modifier.fillMaxWidth()
                            )
                            Text(if (language == "hi") "पाठ खोलें →" else "Open lessons →",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
                if (curriculum.size == 1) {
                    Surface(
                        modifier = Modifier.weight(1f).clickable {
                            onSubject(if (curriculum.first().textAny("subject_code").equals("maths", true)) "science" else "maths")
                        },
                        shape = MaterialTheme.shapes.medium,
                        color = MaterialTheme.colorScheme.surfaceVariant
                    ) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            Text(if (language == "hi") "दूसरा विषय" else "Other subject",
                                style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                            Text(if (language == "hi") "अध्याय देखें →" else "Explore chapters →",
                                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            } else {
                Surface(
                    modifier = Modifier.weight(1f).clickable { onSubject("maths") },
                    shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surface, tonalElevation = 1.dp
                ) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text(if (language == "hi") "गणित" else "Mathematics",
                            style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        Text(if (language == "hi") "पाठ खोलें →" else "Open lessons →",
                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                    }
                }
                Surface(
                    modifier = Modifier.weight(1f).clickable { onSubject("science") },
                    shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surface, tonalElevation = 1.dp
                ) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text(if (language == "hi") "विज्ञान" else "Science",
                            style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        Text(if (language == "hi") "पाठ खोलें →" else "Open lessons →",
                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }

        StudentCourseCatalog(
            programs = programs,
            programsLoadFailed = programsLoadFailed,
            courseProgress = courseProgress,
            language = language,
            busy = busy,
            onEnroll = onEnrollCourse,
            onOpenSubject = onOpenCourseSubject,
            onRefresh = onRefresh
        )
        StudentTextbookQuickLinks(
            documents = textbookDocuments,
            loadFailed = textbookDocumentsLoadFailed,
            language = language,
            busy = busy,
            onOpenDocument = onOpenDocument,
            onBrowseSubject = onResources,
            onRefresh = onRefresh
        )

        Text(if (language == "hi") "त्वरित पहुँच" else "Quick access",
            style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        DashboardActionRow(
            title = if (language == "hi") "पढ़ाई जारी रखें" else "Continue learning",
            subtitle = if (language == "hi") "प्रकाशित पाठ और विषय खोलें" else "Open published lessons and topics",
            action = if (language == "hi") "खोलें" else "Open",
            glyph = "↗", enabled = !busy, onClick = { onSubject("maths") }
        )
        DashboardActionRow(
            title = if (language == "hi") "अभ्यास और टेस्ट" else "Practice & tests",
            subtitle = if (language == "hi") "विषय अभ्यास, टेस्ट और परिणाम" else "Topic practice, assessments and results",
            action = if (language == "hi") "देखें" else "View",
            glyph = "✓", enabled = !busy, onClick = { onTests("ALL") }
        )
        DashboardActionRow(
            title = if (language == "hi") "किताबें और अध्याय" else "Books & chapters",
            subtitle = if (language == "hi") "किताबें पढ़ें और उपलब्ध पेज डाउनलोड करें" else "Read books and download available pages",
            action = if (language == "hi") "लाइब्रेरी" else "Library",
            glyph = "▤", enabled = !busy, onClick = { onResources("maths") }
        )
        DashboardActionRow(
            title = if (language == "hi") "Live classes और recordings" else "Live classes & recordings",
            subtitle = if (language == "hi") "कक्षाएँ और आपकी playback progress" else "Classes and your playback progress",
            action = if (language == "hi") "खोलें" else "Open",
            glyph = "▷", enabled = !busy, onClick = { onClasses("maths") }
        )
        DashboardActionRow(
            title = if (language == "hi") "मेरे परिणाम और समीक्षा" else "My results & review",
            subtitle = if (language == "hi") "जारी टेस्ट परिणाम और उत्तर समीक्षा" else "Released test results and answer review",
            action = if (language == "hi") "परिणाम" else "Results",
            glyph = "✓", enabled = !busy, onClick = onResults
        )
        DashboardActionRow(
            title = if (language == "hi") "प्रारंभिक जाँच" else "Initial diagnostic",
            subtitle = if (language == "hi") "आपकी वर्तमान समझ के अनुसार सीखने की योजना" else "Personalise your learning plan to your current understanding",
            action = if (language == "hi") "शुरू करें" else "Start",
            glyph = "◎", enabled = !busy, onClick = onDiagnostic
        )
        DashboardActionRow(
            title = if (language == "hi") "प्रगति और प्रमाणपत्र" else "Progress & certificates",
            subtitle = if (language == "hi") "सीखे गए पाठ और आपकी उपलब्धियाँ" else "Completed learning and earned achievements",
            action = if (language == "hi") "देखें" else "View",
            glyph = "↗", enabled = !busy, onClick = onProgress
        )

        if (recommendation.optBoolean("available") && recommendation.has("lesson")) {
            val next = recommendation.optJSONObject("lesson") ?: JSONObject()
            DashboardActionRow(
                title = if (language == "hi") "आपके लिए अगला पाठ" else "Next lesson for you",
                subtitle = next.textAny("title", "lesson_title"),
                action = if (language == "hi") "खोलें" else "Open",
                glyph = "→", enabled = !busy, onClick = { onOpenRecommendation(next) }
            )
        } else if (recommendation.textAny("kind") == "DIAGNOSTIC") {
            Text(
                if (language == "hi") "अपनी अगली सीख तय करने के लिए प्रारंभिक जाँच पूरी करें।"
                else "Complete your initial diagnostic to tailor your next learning steps.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = onProfile, enabled = !busy) {
                Text(if (language == "hi") "प्रोफ़ाइल और सेटिंग" else "Profile & settings")
            }
            TextButton(onClick = onRefresh, enabled = !busy) {
                Text(if (language == "hi") "रिफ्रेश" else "Refresh")
            }
        }
    }
}

@Composable
private fun DashboardActionRow(
    title: String,
    subtitle: String,
    action: String,
    glyph: String,
    enabled: Boolean,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick),
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Surface(
                modifier = Modifier.size(34.dp),
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primaryContainer
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(glyph, color = MaterialTheme.colorScheme.onPrimaryContainer,
                        style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                }
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(subtitle, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(action, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun SubjectCard(title: String, subtitle: String, modifier: Modifier, onClick: () -> Unit) {
    Card(modifier = modifier.clickable(onClick = onClick)) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(localizedUiText(title), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Text(localizedUiText(subtitle), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
            Text(localizedUiText("पाठ और अभ्यास देखें →"), style = MaterialTheme.typography.bodySmall)
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
    onOpenLesson: (JSONObject) -> Unit,
    onOpenStructuredLessons: () -> Unit
) {
    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.weight(1f)) {
                Text(localizedUiText(if (subject == "maths") "गणित" else "विज्ञान"), style = MaterialTheme.typography.headlineSmall)
                Text(if (language == "hi") "प्रकाशित पाठ और प्रगति" else "Published lessons and progress",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
            }
            TextButton(onClick = onRefresh, enabled = !busy) { Text(localizedUiText("रिफ्रेश")) }
        }
        OutlinedButton(onClick = onOpenStructuredLessons, enabled = !busy, modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) {
            Text(if (language == "hi") "Structured lessons खोलें" else "Open structured lessons")
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
                            Text(localizedUiText("समय: ") + item.optInt("estimated_minutes", 0) + " मिनट  •  प्रगति: " + progress + "%",
                                style = MaterialTheme.typography.bodySmall)
                            LinearProgressIndicator(progress = { progress.coerceIn(0, 100) / 100f }, modifier = Modifier.fillMaxWidth())
                            Text(localizedUiText("पाठ खोलें →"), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
                        }
                    }
                }
            }
        }
    }
}


@Composable
private fun StructuredLessonCatalogueScreen(
    items: List<JSONObject>,
    language: String,
    busy: Boolean,
    onRefresh: () -> Unit,
    onOpenLesson: (JSONObject) -> Unit,
    onOpenLegacyLessons: () -> Unit
) {
    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.weight(1f)) {
                Text(if (language == "hi") "संरचित पाठ" else "Structured lessons", style = MaterialTheme.typography.headlineSmall)
                Text(if (language == "hi") "समीक्षा और प्रकाशन स्वीकृत पाठ" else "Reviewed and published lessons",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
            }
            TextButton(onClick = onRefresh, enabled = !busy) { Text(if (language == "hi") "रिफ्रेश" else "Refresh") }
        }
        OutlinedButton(onClick = onOpenLegacyLessons, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
            Text(if (language == "hi") "Existing lessons पर लौटें" else "Return to existing lessons")
        }
        if (busy) {
            Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else if (items.isEmpty()) {
            EmptyState(
                if (language == "hi") "अभी कोई approved structured lesson उपलब्ध नहीं है" else "No approved structured lessons yet",
                if (language == "hi") "Content, source, visual और bilingual review पूरा होने के बाद published lessons यहाँ दिखेंगे।"
                else "Published lessons appear here only after content, source, visual and bilingual reviews are approved."
            )
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxSize()) {
                items(items) { item ->
                    Card(modifier = Modifier.fillMaxWidth().clickable { onOpenLesson(item) }) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(item.optString("subjectCode").uppercase() + " · " +
                                (if (language == "hi") "कक्षा " else "Class ") + item.optInt("classNumber"),
                                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.secondary)
                            Text(item.optString("title").ifBlank { if (language == "hi") "पाठ" else "Lesson" },
                                style = MaterialTheme.typography.titleMedium)
                            Text((if (language == "hi") "अध्याय " else "Chapter ") + item.optInt("chapterNumber") +
                                ": " + item.optString("chapterTitle") + " · " +
                                (if (language == "hi") "विषय " else "Topic ") + item.optInt("topicNumber"),
                                style = MaterialTheme.typography.bodySmall)
                            if (item.optInt("estimatedMinutes") > 0) {
                                Text(item.optInt("estimatedMinutes").toString() + (if (language == "hi") " मिनट" else " min"),
                                    style = MaterialTheme.typography.bodySmall)
                            }
                            Text(if (language == "hi") "Lesson खोलें →" else "Open lesson →",
                                color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
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
    tutorMinimized: Boolean,
    onRestoreTutor: () -> Unit,
    answers: MutableMap<String, String>,
    feedbacks: MutableMap<String, String>,
    answerLocks: Map<String, Boolean>,
    busy: Boolean,
    onAnswerChange: (String, String) -> Unit,
    onAnswer: (JSONObject, String) -> Unit,
    onPractice: () -> Unit,
    onTutor: () -> Unit,
    onTutorQuestion: (Long) -> Unit
) {
    val lessonId = lesson.longAny("id", "lesson_id")
    val blocks = lesson.optJSONArray("blocks")?.objectItems() ?: emptyList()
    val questions = lesson.optJSONArray("questions")?.objectItems() ?: emptyList()
    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(lesson.textAny("chapter_name", "chapterName"), style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.secondary)
            Text(lesson.textAny("title").ifBlank { localizedUiText("Lesson") }, style = MaterialTheme.typography.headlineSmall)
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
                            "उत्तर सुरक्षित है।" else null,
                        onTutorHelp = { onTutorQuestion(question.longAny("id", "questionId", "question_id")) }
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
        if (tutorMinimized) {
            FloatingActionButton(
                onClick = onRestoreTutor,
                modifier = Modifier.align(Alignment.BottomEnd).padding(end = 18.dp, bottom = 84.dp)
                    .semantics {
                        contentDescription = if (language == "hi") "क्वांटाएज ट्यूटर फिर खोलें" else "Restore QuantaEdge Tutor"
                    },
                containerColor = Color(0xFF172033),
                contentColor = Color.White
            ) {
                Text("✦", fontSize = 23.sp)
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
    onAnswer: (JSONObject, String) -> Unit,
    language: String,
    onComplete: () -> Unit,
    onProgress: () -> Unit
) {
    val questions = practice.optJSONArray("questions")?.objectItems() ?: emptyList()
    val sessionId = practice.longAny("sessionId", "session_id")
    val statusText = practice.textAny("status")
    var currentQuestionIndex by rememberSaveable(sessionId) {
        mutableStateOf(questions.indexOfFirst { !it.optBoolean("answered") }
            .let { firstUnanswered -> if (firstUnanswered >= 0) firstUnanswered else questions.size })
    }
    LaunchedEffect(sessionId, questions.size) {
        currentQuestionIndex = currentQuestionIndex.coerceIn(0, questions.size)
    }
    if (questions.isNotEmpty() && currentQuestionIndex >= questions.size) {
        val correctCount = questions.count { it.has("lastCorrect") && !it.isNull("lastCorrect") && it.optBoolean("lastCorrect") }
        val gradedCount = questions.count { it.has("lastCorrect") && !it.isNull("lastCorrect") }
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text(if (language == "hi") "बहुत अच्छा! अभ्यास पूरा हुआ।" else "Well done! Practice complete.",
                style = MaterialTheme.typography.headlineSmall)
            Text(practice.textAny("message").ifBlank {
                if (language == "hi") "इस अभ्यास सत्र के प्रश्न पूरे हो गए हैं।" else "You have reached the end of this practice session."
            }, style = MaterialTheme.typography.bodyMedium)
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 1.dp
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(if (language == "hi") "इस सत्र का परिणाम" else "Session result",
                        style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        if (gradedCount > 0) {
                            if (language == "hi") "$correctCount / $gradedCount प्रश्नों का नवीनतम जाँचा गया उत्तर सही है"
                            else "$correctCount of $gradedCount questions have a correct latest graded answer"
                        } else if (language == "hi") {
                            "आपके उत्तर सुरक्षित हैं।"
                        } else {
                            "Your submitted answers have been saved."
                        },
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        if (language == "hi") "प्रगति जाँचे गए उत्तरों के आधार पर सुरक्षित होती है।"
                        else "Learning progress is updated from graded answers.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Button(onClick = onComplete, modifier = Modifier.fillMaxWidth()) {
                Text(if (language == "hi") "पाठ पर लौटें" else "Return to lesson")
            }
            TextButton(onClick = onProgress, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                Text(if (language == "hi") "मेरी प्रगति देखें" else "View my progress")
            }
        }
    } else {
        Column(
            modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(if (language == "hi") "अब खुद करके देखें" else "Now, put it into practice",
                style = MaterialTheme.typography.headlineSmall)
            Text(practice.textAny("message").ifBlank {
                if (language == "hi") "पहले खुद सोचें, फिर उत्तर जमा करें और feedback से सीखें।"
                else "Think it through, submit your answer, and learn from the feedback."
            }, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                (if (language == "hi") "सत्र " else "Session ") + sessionId +
                    " · " + (if (language == "hi") "स्थिति " else "Status ") + statusText,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (questions.isEmpty()) {
                EmptyState(
                    if (language == "hi") "अभ्यास प्रश्न उपलब्ध नहीं" else "No practice questions available",
                    if (language == "hi") "इस पाठ के लिए स्वीकृत प्रश्न उपलब्ध होने पर अभ्यास शुरू हो सकेगा।"
                    else "Practice will be available when approved questions are published for this lesson."
                )
            } else {
                val safeIndex = currentQuestionIndex.coerceIn(0, questions.lastIndex)
                val question = questions[safeIndex]
                val id = question.longAny("id", "questionId", "question_id").toString()
                val hasLast = question.has("lastCorrect") && !question.isNull("lastCorrect")
                val lastCorrect = if (hasLast) question.optBoolean("lastCorrect") else null
                val attemptCount = question.optInt("attemptCount", 0)
                val existing = answers[id] ?: ""
                val answered = question.optBoolean("answered")
                val canAdvance = answered || (hasLast && lastCorrect == null && attemptCount > 0)
                val statusMessage = when {
                    answered && lastCorrect == true -> if (language == "hi") "उत्तर सही है और सुरक्षित है।" else "Correct answer saved."
                    answered && lastCorrect == null -> if (language == "hi") "उत्तर सुरक्षित है; मूल्यांकन बाकी है।" else "Answer saved; review may still be pending."
                    !answered && lastCorrect == false && attemptCount > 0 -> if (language == "hi") "पिछला उत्तर सही नहीं था। फिर प्रयास करें।" else "The previous answer was incorrect. Try again."
                    else -> null
                }

                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()) {
                    Text(
                        if (language == "hi") "प्रश्न ${safeIndex + 1} / ${questions.size}" else "Question ${safeIndex + 1} of ${questions.size}",
                        style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        if (language == "hi") "${(safeIndex * 100 / questions.size)}% पूरा" else "${(safeIndex * 100 / questions.size)}% complete",
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                LinearProgressIndicator(
                    progress = { (safeIndex + 1).toFloat() / questions.size.toFloat() },
                    modifier = Modifier.fillMaxWidth()
                )
                Column(
                    modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    QuestionCard(
                        question = question,
                        answer = existing,
                        onAnswerChange = { onAnswerChange(id, it) },
                        onSubmit = { onAnswer(question, existing) },
                        locked = answered,
                        saving = busy,
                        feedback = feedbacks[id],
                        status = statusMessage,
                        submitLabel = if (language == "hi") "उत्तर जमा करें" else "Submit answer"
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (!canAdvance) {
                        Text(
                            if (lastCorrect == false) {
                                if (language == "hi") "उत्तर सुधारकर फिर जमा करें।" else "Revise your answer and submit again."
                            } else if (language == "hi") {
                                "आगे बढ़ने से पहले उत्तर जमा करें।"
                            } else {
                                "Submit your answer before continuing."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f)
                        )
                    }
                    Button(
                        onClick = { currentQuestionIndex = (safeIndex + 1).coerceAtMost(questions.size) },
                        enabled = canAdvance && !busy,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(if (safeIndex == questions.lastIndex) {
                            if (language == "hi") "नतीजे देखें →" else "See results →"
                        } else {
                            if (language == "hi") "अगला प्रश्न →" else "Next question →"
                        })
                    }
                }
            }
            Text(
                if (language == "hi") "सिर्फ स्वीकृत प्रश्न · आपके उत्तर सुरक्षित रूप से सहेजे जाते हैं।"
                else "Approved questions only · Your answers are saved securely.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun DiagnosticScreen(
    diagnostic: JSONObject,
    answers: MutableMap<String, String>,
    feedbacks: MutableMap<String, String>,
    busy: Boolean,
    language: String,
    loadFailed: Boolean,
    onAnswerChange: (String, String) -> Unit,
    onAnswer: (JSONObject, String) -> Unit,
    onSubmit: () -> Unit,
    onRefresh: () -> Unit
) {
    val questions = diagnostic.optJSONArray("questions")?.objectItems() ?: emptyList()
    val attemptId = diagnostic.longAny("attemptId", "attempt_id")
    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text(localizedUiText("प्रारंभिक जाँच"), style = MaterialTheme.typography.headlineSmall)
        if (busy && diagnostic.length() == 0) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 18.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                CircularProgressIndicator()
                Text(if (language == "hi") "जाँच लोड हो रही है…" else "Loading diagnostic…")
            }
        } else if (loadFailed && diagnostic.length() == 0) {
            EmptyState(
                if (language == "hi") "जाँच लोड नहीं हो पाई" else "Diagnostic couldn't be loaded",
                if (language == "hi") "ऊपर दिया गया error जाँचें और फिर प्रयास करें।"
                else "Check the error above and retry."
            )
            Button(onClick = onRefresh, enabled = !busy) {
                Text(if (language == "hi") "फिर प्रयास करें" else "Retry")
            }
        } else if (diagnostic.optBoolean("completed")) {
            EmptyState("जाँच पूरी हो चुकी है", "आपकी आगे की पढ़ाई और अनुशंसाएँ सर्वर पर उपलब्ध प्रगति के अनुसार हैं।")
            Button(onClick = onRefresh, enabled = !busy) { Text(localizedUiText("स्थिति रिफ्रेश करें")) }
        } else if (!diagnostic.optBoolean("available")) {
            EmptyState("जाँच अभी उपलब्ध नहीं", "आपकी कक्षा और विषयों के लिए स्वीकृत प्रश्न उपलब्ध होने पर यहाँ जाँच शुरू हो सकेगी।")
            Button(onClick = onRefresh, enabled = !busy) { Text(localizedUiText("दोबारा जाँचें")) }
        } else {
            Text(localizedUiText("प्रश्न: ") + questions.size + "  •  जवाब सर्वर पर सुरक्षित होंगे।")
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
                Text(localizedUiText("जाँच जमा करें"))
            }
        }
    }
}

@Composable
private fun AssessmentListScreen(
    subject: String,
    assessmentItems: List<JSONObject>,
    offlineItems: List<JSONObject>,
    total: Int,
    pageSize: Int,
    pageOffset: Int,
    language: String,
    busy: Boolean,
    onPageChange: (Int, Int) -> Unit,
    onSubject: (String) -> Unit,
    onOpen: (JSONObject) -> Unit,
    onPrepareOffline: (JSONObject) -> Unit,
    onResults: () -> Unit,
    onRefresh: () -> Unit
) {
    val compact = LocalConfiguration.current.screenWidthDp < 390
    val pageCount = ((total + pageSize - 1) / pageSize).coerceAtLeast(1)
    val pageIndex = (pageOffset / pageSize).coerceIn(0, pageCount - 1)
    var pageSizeMenuExpanded by remember { mutableStateOf(false) }
    fun t(en: String, hi: String) = if (language == "hi") hi else en

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text(localizedUiText("टेस्ट और मूल्यांकन"), style = MaterialTheme.typography.headlineSmall)
        if (compact) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
                if (subject == "ALL") Button(onClick = { onSubject("ALL") }, enabled = !busy, modifier = Modifier.weight(1f)) { Text(t("All", "सभी")) }
                else TextButton(onClick = { onSubject("ALL") }, enabled = !busy, modifier = Modifier.weight(1f)) { Text(t("All", "सभी")) }
                if (subject == "maths") Button(onClick = { onSubject("maths") }, enabled = !busy, modifier = Modifier.weight(1f)) { Text(localizedUiText("गणित")) }
                else TextButton(onClick = { onSubject("maths") }, enabled = !busy, modifier = Modifier.weight(1f)) { Text(localizedUiText("गणित")) }
                if (subject == "science") Button(onClick = { onSubject("science") }, enabled = !busy, modifier = Modifier.weight(1f)) { Text(localizedUiText("विज्ञान")) }
                else TextButton(onClick = { onSubject("science") }, enabled = !busy, modifier = Modifier.weight(1f)) { Text(localizedUiText("विज्ञान")) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                TextButton(onClick = onResults, enabled = !busy, modifier = Modifier.weight(1f)) { Text(localizedUiText("मेरे परिणाम")) }
                TextButton(onClick = onRefresh, enabled = !busy, modifier = Modifier.weight(1f)) { Text(localizedUiText("रिफ्रेश")) }
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                if (subject == "ALL") Button(onClick = { onSubject("ALL") }, enabled = !busy) { Text(t("All subjects", "सभी विषय")) }
                else TextButton(onClick = { onSubject("ALL") }, enabled = !busy) { Text(t("All subjects", "सभी विषय")) }
                if (subject == "maths") Button(onClick = { onSubject("maths") }, enabled = !busy) { Text(localizedUiText("गणित")) }
                else TextButton(onClick = { onSubject("maths") }, enabled = !busy) { Text(localizedUiText("गणित")) }
                if (subject == "science") Button(onClick = { onSubject("science") }, enabled = !busy) { Text(localizedUiText("विज्ञान")) }
                else TextButton(onClick = { onSubject("science") }, enabled = !busy) { Text(localizedUiText("विज्ञान")) }
                TextButton(onClick = onResults, enabled = !busy) { Text(localizedUiText("मेरे परिणाम")) }
                TextButton(onClick = onRefresh, enabled = !busy) { Text(localizedUiText("रिफ्रेश")) }
            }
        }
        if (offlineItems.isNotEmpty() || assessmentItems.any { it.optBoolean("offline_enabled") }) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(localizedUiText("संरक्षित offline test"), style = MaterialTheme.typography.titleSmall)
                    Text(localizedUiText("The offline timer starts immediately. Answers remain encrypted on this device; no local score is shown. When online, the server syncs and grades answers. This is only for staff-approved low-stakes tests."),
                        style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 8.dp)) {
            if (offlineItems.isNotEmpty()) {
                item { Text(t("Offline attempts to resume", "जारी रखने के लिए ऑफ़लाइन प्रयास"), style = MaterialTheme.typography.titleMedium) }
                items(offlineItems) { item ->
                    AssessmentRowCard(item, language, busy, onOpen, onPrepareOffline)
                }
            }
            item { Text(t("Available tests", "उपलब्ध टेस्ट"), style = MaterialTheme.typography.titleMedium) }
            if (assessmentItems.isEmpty() && !busy && total <= 0) {
                item { EmptyState("कोई प्रकाशित टेस्ट नहीं", "सिर्फ उपलब्ध और आपके खाते के लिए अनुमत मूल्यांकन यहाँ दिखेंगे।") }
            } else if (total > 0) {
                item {
                    if (compact) {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
                            Text(
                                if (language == "hi") "पृष्ठ " + (pageIndex + 1) + " / " + pageCount + " • कुल " + total
                                else "Page " + (pageIndex + 1) + " of " + pageCount + " • " + total + " tests",
                                style = MaterialTheme.typography.bodySmall
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                                Box(modifier = Modifier.weight(1f)) {
                                    TextButton(onClick = { pageSizeMenuExpanded = true }) {
                                        Text(t("Rows: ", "पंक्तियाँ: ") + pageSize)
                                    }
                                    DropdownMenu(expanded = pageSizeMenuExpanded, onDismissRequest = { pageSizeMenuExpanded = false }) {
                                        listOf(10, 20, 50).forEach { size ->
                                            DropdownMenuItem(text = { Text(size.toString()) },
                                                onClick = { pageSizeMenuExpanded = false; onPageChange(size, 0) })
                                        }
                                    }
                                }
                                TextButton(onClick = { onPageChange(pageSize, ((pageIndex - 1).coerceAtLeast(0)) * pageSize) },
                                    enabled = pageIndex > 0 && !busy) { Text(t("Previous", "पिछला")) }
                                TextButton(onClick = { onPageChange(pageSize, (pageIndex + 1) * pageSize) },
                                    enabled = pageIndex < pageCount - 1 && !busy) { Text(t("Next", "अगला")) }
                            }
                        }
                    } else {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth()) {
                            Text(if (language == "hi") "पृष्ठ " + (pageIndex + 1) + " / " + pageCount + " • कुल " + total
                                else "Page " + (pageIndex + 1) + " of " + pageCount + " • " + total + " tests",
                                modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                            Box {
                                TextButton(onClick = { pageSizeMenuExpanded = true }) { Text(t("Rows: ", "पंक्तियाँ: ") + pageSize) }
                                DropdownMenu(expanded = pageSizeMenuExpanded, onDismissRequest = { pageSizeMenuExpanded = false }) {
                                    listOf(10, 20, 50).forEach { size ->
                                        DropdownMenuItem(text = { Text(size.toString()) },
                                            onClick = { pageSizeMenuExpanded = false; onPageChange(size, 0) })
                                    }
                                }
                            }
                            TextButton(onClick = { onPageChange(pageSize, ((pageIndex - 1).coerceAtLeast(0)) * pageSize) },
                                enabled = pageIndex > 0 && !busy) { Text(t("Previous", "← पिछला")) }
                            TextButton(onClick = { onPageChange(pageSize, (pageIndex + 1) * pageSize) },
                                enabled = pageIndex < pageCount - 1 && !busy) { Text(t("Next", "अगला →")) }
                        }
                    }
                }
            }
            items(assessmentItems) { item ->
                AssessmentRowCard(item, language, busy, onOpen, onPrepareOffline)
            }
        }
    }
}

@Composable
private fun AssessmentRowCard(
    item: JSONObject,
    language: String,
    busy: Boolean,
    onOpen: (JSONObject) -> Unit,
    onPrepareOffline: (JSONObject) -> Unit
) {
    fun t(en: String, hi: String) = if (language == "hi") hi else en
    val used = item.optInt("attempts_used", 0)
    val max = item.optInt("max_attempts", 1)
    val offlineLocal = item.optBoolean("offline_local")
    val pendingSync = item.textAny("offline_state") == "QUEUED" || item.textAny("offline_state") == "SYNC_ERROR"
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(item.textAny("title").ifBlank { t("Assessment", "मूल्यांकन") }, style = MaterialTheme.typography.titleMedium)
            Text(item.textAny("description"), style = MaterialTheme.typography.bodyMedium)
            Text(item.textAny("chapter_name", "subject_name") + " • " +
                item.optInt("question_count", 0) + " " + t("questions", "प्रश्न") + " • " +
                item.optInt("duration_minutes", 0) + " " + t("minutes", "मिनट"),
                style = MaterialTheme.typography.bodySmall)
            Text(t("Attempts: ", "प्रयास: ") + used + " / " + max, style = MaterialTheme.typography.bodySmall)
            if (offlineLocal) {
                val stateLabel = when (item.textAny("offline_state")) {
                    "QUEUED" -> t("Offline answers saved · sync pending", "ऑफ़लाइन उत्तर सुरक्षित · sync बाकी")
                    "SYNC_ERROR" -> t("Offline answers saved · sync needs attention", "ऑफ़लाइन उत्तर सुरक्षित · sync में समस्या")
                    "SYNCED" -> when (item.textAny("offline_result_status")) {
                        "RELEASED" -> t("Synced · result released", "Sync हुआ · परिणाम जारी")
                        "AWAITING_REVIEW" -> t("Synced · awaiting teacher review", "Sync हुआ · शिक्षक की समीक्षा बाकी")
                        else -> t("Synced to server", "Server पर sync हुआ")
                    }
                    else -> t("Encrypted offline attempt ready", "Encrypted offline attempt तैयार")
                }
                Text(stateLabel, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            }
            Button(onClick = { onOpen(item) }, enabled = !busy &&
                (offlineLocal || item.optLong("in_progress_attempt_id", 0L) > 0L || used < max),
                modifier = Modifier.fillMaxWidth()) {
                Text(when {
                    pendingSync -> t("Offline attempt / sync status", "ऑफ़लाइन प्रयास / sync स्थिति")
                    offlineLocal -> t("Continue offline test", "ऑफ़लाइन टेस्ट जारी रखें")
                    item.optLong("in_progress_attempt_id", 0L) > 0L -> t("Resume test", "टेस्ट जारी रखें")
                    else -> t("Start test", "टेस्ट शुरू करें")
                })
            }
            if (!offlineLocal && item.optBoolean("offline_enabled") &&
                item.optLong("in_progress_attempt_id", 0L) <= 0L && used < max) {
                TextButton(onClick = { onPrepareOffline(item) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                    Text(localizedUiText("Offline test शुरू करें (timer अभी शुरू होगा)"))
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
    fun t(en: String, hi: String) = if (language == "hi") hi else en
    val attemptId = attempt.longAny("attempt_id", "attemptId", "id")
    var currentQuestionIndex by rememberSaveable(attemptId) { mutableStateOf(0) }
    LaunchedEffect(attemptId, questions.size) {
        currentQuestionIndex = currentQuestionIndex.coerceIn(0, (questions.size - 1).coerceAtLeast(0))
    }
    val safeQuestionIndex = currentQuestionIndex.coerceIn(0, (questions.size - 1).coerceAtLeast(0))
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
        Text(localizedUiText("प्रयास ") + attempt.optInt("attempt_number", 1) + " • स्थिति: " +
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
                    Text(localizedUiText(if (deadlineReached) "समय पूरा" else "समय शेष"), style = MaterialTheme.typography.titleSmall)
                    Text(localizedUiText("$minutes:$seconds"), style = MaterialTheme.typography.titleLarge,
                        color = if (remainingSeconds <= 60L) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
                }
            }
        }
        if (deadlineReached) {
            Text(
                if (attempt.optBoolean("offlineMode"))
                    t("Time is up. Your encrypted answers are being queued for sync.",
                        "समय पूरा हो गया है। आपके सुरक्षित उत्तर sync queue में भेजे जा रहे हैं।")
                else t("Time is up. Your test is being submitted automatically.",
                    "समय पूरा हो गया है। आपका टेस्ट स्वतः जमा किया जा रहा है।"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }
        if (offlinePendingSync) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(localizedUiText("उत्तर इस device पर encrypted सुरक्षित हैं"), style = MaterialTheme.typography.titleSmall)
                    Text(localizedUiText("These answers are not graded here. When internet returns, the authenticated server syncs and grades them. The offline package can sync until 72 hours after the timer expires."),
                        style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        if (released) {
            Text(localizedUiText("अंक: ") + attempt.textAny("final_score") + " / " + attempt.textAny("max_score"),
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
                                "SUBJECT_COMPLETION_REQUIRED" -> if (language == "hi") "Test certificate के लिए सभी lessons पूरे करना ज़रूरी नहीं है; सभी published lessons केवल subject-completion certificate के लिए पूरे करें।" else "A test certificate does not require completing every lesson; all published lessons are required only for a subject-completion certificate."
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
            Text(localizedUiText("टेस्ट जमा है। लिखित उत्तरों का मूल्यांकन होने पर परिणाम जारी होगा।"), modifier = Modifier.padding(vertical = 8.dp))
        }
        if (questions.isEmpty()) {
            EmptyState(
                if (language == "hi") "प्रश्न उपलब्ध नहीं हैं" else "No questions available",
                if (language == "hi") "इस टेस्ट के प्रश्न लोड नहीं हो पाए। रिफ्रेश करके फिर प्रयास करें।" else "The test questions could not be loaded. Refresh and try again."
            )
        } else {
            val answeredCount = questions.count { item ->
                val answerId = item.longAny("attemptQuestionId", "attempt_question_id").toString()
                (answers[answerId] ?: item.textAny("answer")).isNotBlank()
            }
            Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                questions.forEachIndexed { index, item ->
                    val answerId = item.longAny("attemptQuestionId", "attempt_question_id").toString()
                    val hasAnswer = (answers[answerId] ?: item.textAny("answer")).isNotBlank()
                    val label = (if (hasAnswer) "✓ " else "") + (index + 1)
                    if (index == safeQuestionIndex) Button(onClick = { currentQuestionIndex = index }) { Text(label) }
                    else OutlinedButton(onClick = { currentQuestionIndex = index }) { Text(label) }
                }
            }
            Column(
                modifier = Modifier.weight(1f).fillMaxWidth().padding(top = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        if (language == "hi") "प्रश्न ${safeQuestionIndex + 1} / ${questions.size}" else "Question ${safeQuestionIndex + 1} of ${questions.size}",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        if (language == "hi") "$answeredCount उत्तर दिए" else "$answeredCount answered",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                LinearProgressIndicator(
                    progress = { (safeQuestionIndex + 1).toFloat() / questions.size.toFloat() },
                    modifier = Modifier.fillMaxWidth()
                )
                Column(
                    modifier = Modifier.weight(1f).fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    val question = questions[safeQuestionIndex]
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
                            LearningMathText(
                                localizedUiText("सही उत्तर: ") + displayAnswer,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        if (question.textAny("teacherFeedback").isNotBlank()) {
                            LearningMathText(localizedUiText("शिक्षक की टिप्पणी: ") + question.textAny("teacherFeedback"),
                                style = MaterialTheme.typography.bodyMedium)
                        }
                        if (question.textAny("explanation").isNotBlank()) {
                            Surface(
                                modifier = Modifier.fillMaxWidth(),
                                shape = MaterialTheme.shapes.small,
                                color = MaterialTheme.colorScheme.surfaceVariant
                            ) {
                                Column(
                                    modifier = Modifier.padding(12.dp),
                                    verticalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Text(localizedUiText("व्याख्या"), style = MaterialTheme.typography.titleSmall)
                                    LearningMathText(question.textAny("explanation"), style = MaterialTheme.typography.bodyMedium)
                                }
                            }
                        }
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            val question = questions[safeQuestionIndex]
                            val id = question.longAny("attemptQuestionId", "attempt_question_id").toString()
                            val value = answers[id] ?: question.textAny("answer")
                            if (inProgress && !deadlineReached && value.isNotBlank() && value != question.textAny("answer")) {
                                onSave(question, value)
                            }
                            currentQuestionIndex = (safeQuestionIndex - 1).coerceAtLeast(0)
                        },
                        enabled = safeQuestionIndex > 0 && !busy,
                        modifier = Modifier.weight(1f)
                    ) { Text(if (language == "hi") "← पिछला" else "← Previous") }
                    Text(
                        "${safeQuestionIndex + 1}/${questions.size}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Button(
                        onClick = {
                            val question = questions[safeQuestionIndex]
                            val id = question.longAny("attemptQuestionId", "attempt_question_id").toString()
                            val value = answers[id] ?: question.textAny("answer")
                            if (inProgress && !deadlineReached && value.isNotBlank() && value != question.textAny("answer")) {
                                onSave(question, value)
                            }
                            currentQuestionIndex = (safeQuestionIndex + 1).coerceAtMost(questions.lastIndex)
                        },
                        enabled = safeQuestionIndex < questions.lastIndex && !busy,
                        modifier = Modifier.weight(1f)
                    ) { Text(if (language == "hi") "अगला →" else "Next →") }
                }
            }
        }
        if (inProgress) {
            Button(onClick = onSubmit, enabled = !busy && attemptId > 0, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                Text(
                    when {
                        deadlineReached && attempt.optBoolean("offlineMode") -> t("Time is up — sync answers", "समय पूरा — उत्तर sync करें")
                        deadlineReached -> t("Time is up — submit test", "समय पूरा — टेस्ट जमा करें")
                        else -> t("Submit test", "टेस्ट जमा करें")
                    }
                )
            }
        } else if (offlinePendingSync) {
            Button(onClick = onSubmit, enabled = !busy && attemptId > 0,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                Text(localizedUiText(if (busy) "Sync शुरू हो रहा है…" else "Sync की कोशिश करें"))
            }
        } else if (!released) {
            Button(onClick = onRefresh, enabled = !busy, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                Text(localizedUiText("परिणाम की स्थिति रिफ्रेश करें"))
            }
        }
    }
}

@Composable
private fun ResultsScreen(
    resultItems: List<JSONObject>,
    resultTotal: Int,
    pageSize: Int,
    pageOffset: Int,
    certificateEligibility: Map<Long, JSONObject>,
    language: String,
    busy: Boolean,
    loadFailed: Boolean,
    onPageChange: (Int, Int) -> Unit,
    onReview: (Long) -> Unit,
    onIssueCertificate: (Long) -> Unit,
    onDownloadCertificate: (Long, String) -> Unit,
    onViewCertificate: (Long) -> Unit
) {
    val compact = LocalConfiguration.current.screenWidthDp < 390
    var pageSizeMenuExpanded by remember { mutableStateOf(false) }
    val pageCount = ((resultTotal + pageSize - 1) / pageSize).coerceAtLeast(1)
    val safePageIndex = (pageOffset / pageSize).coerceIn(0, pageCount - 1)
    fun t(en: String, hi: String) = if (language == "hi") hi else en

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text(localizedUiText("मेरे परिणाम"), style = MaterialTheme.typography.headlineSmall)
        Text(
            if (language == "hi") "हर result के साथ certificate की पात्रता और उपलब्ध download देखें।"
            else "Check certificate eligibility and access available downloads from each released result.",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.secondary
        )
        if (busy && resultItems.isEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 18.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                CircularProgressIndicator()
                Text(t("Loading results…", "परिणाम लोड हो रहे हैं…"))
            }
        } else if (loadFailed && resultItems.isEmpty()) {
            EmptyState(
                t("Results couldn't be loaded", "परिणाम लोड नहीं हो पाए"),
                t("Check the error above and retry.", "ऊपर दिया गया error जाँचें और फिर प्रयास करें।")
            )
        } else if (resultItems.isEmpty() && resultTotal <= 0) {
            EmptyState("अभी कोई जारी परिणाम नहीं", "जारी किए गए मूल्यांकन परिणाम उपलब्ध होने पर वे यहाँ दिखेंगे।")
        } else {
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(t("Rows per page", "प्रति पृष्ठ पंक्तियाँ"), modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodySmall)
                Box {
                    TextButton(onClick = { pageSizeMenuExpanded = true }) {
                        Text(pageSize.toString())
                    }
                    DropdownMenu(expanded = pageSizeMenuExpanded, onDismissRequest = { pageSizeMenuExpanded = false }) {
                        listOf(10, 20, 50).forEach { size ->
                            DropdownMenuItem(
                                text = { Text(size.toString()) },
                                onClick = { pageSizeMenuExpanded = false; onPageChange(size, 0) }
                            )
                        }
                    }
                }
            }
            val rangeStart = if (resultTotal == 0) 0 else pageOffset + 1
            val rangeEnd = (pageOffset + resultItems.size).coerceAtMost(resultTotal)
            if (compact) {
                Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(if (language == "hi") "$rangeStart–$rangeEnd / $resultTotal परिणाम"
                        else "Showing $rangeStart–$rangeEnd of $resultTotal results",
                        style = MaterialTheme.typography.bodySmall)
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        TextButton(onClick = { onPageChange(pageSize, ((safePageIndex - 1).coerceAtLeast(0)) * pageSize) },
                            enabled = safePageIndex > 0 && !busy) { Text(localizedUiText("Previous ←")) }
                        TextButton(onClick = { onPageChange(pageSize, (safePageIndex + 1) * pageSize) },
                            enabled = safePageIndex < pageCount - 1 && !busy) { Text(localizedUiText("Next →")) }
                    }
                }
            } else {
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(if (language == "hi") "$rangeStart–$rangeEnd / $resultTotal परिणाम"
                        else "Showing $rangeStart–$rangeEnd of $resultTotal results",
                        modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { onPageChange(pageSize, ((safePageIndex - 1).coerceAtLeast(0)) * pageSize) },
                        enabled = safePageIndex > 0 && !busy) { Text(localizedUiText("Previous ←")) }
                    TextButton(onClick = { onPageChange(pageSize, (safePageIndex + 1) * pageSize) },
                        enabled = safePageIndex < pageCount - 1 && !busy) { Text(localizedUiText("Next →")) }
                }
            }
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 10.dp)) {
            items(resultItems) { item ->
                val attempts = item.optJSONArray("attempts")?.objectItems() ?: emptyList()
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                        Text(item.textAny("assessment_title", "title").ifBlank { "मूल्यांकन" },
                            style = MaterialTheme.typography.titleMedium)
                        Text(item.textAny("subject_name", "chapter_name"), style = MaterialTheme.typography.bodySmall)
                        Text(localizedUiText("नवीनतम अंक: ") + item.textAny("final_score", "score") + " / " + item.textAny("max_score"),
                            style = MaterialTheme.typography.bodyLarge)
                        Text(localizedUiText("पिछले तीन में सर्वश्रेष्ठ: ") + item.textAny("best_score") + " / " + item.textAny("max_score"),
                            style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                        Text(localizedUiText("कुल जारी attempts: ") + item.optInt("attempt_count", attempts.size) +
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
                                        if (compact) {
                                            Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
                                                OutlinedButton(onClick = { onViewCertificate(certificateId) }, enabled = !busy,
                                                    modifier = Modifier.fillMaxWidth()) { Text(if (language == "hi") "प्रमाणपत्र देखें" else "View certificate") }
                                                OutlinedButton(onClick = { onDownloadCertificate(certificateId, "pdf") }, enabled = !busy,
                                                    modifier = Modifier.fillMaxWidth()) { Text(if (language == "hi") "PDF सेव करें" else "Save PDF") }
                                                OutlinedButton(onClick = { onDownloadCertificate(certificateId, "png") }, enabled = !busy,
                                                    modifier = Modifier.fillMaxWidth()) { Text(if (language == "hi") "PNG सेव करें" else "Save PNG") }
                                            }
                                        } else {
                                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                                                OutlinedButton(onClick = { onViewCertificate(certificateId) }, enabled = !busy,
                                                    modifier = Modifier.weight(1f)) { Text(if (language == "hi") "देखें" else "View") }
                                                OutlinedButton(onClick = { onDownloadCertificate(certificateId, "pdf") }, enabled = !busy,
                                                    modifier = Modifier.weight(1f)) { Text(if (language == "hi") "PDF सेव करें" else "Save PDF") }
                                                OutlinedButton(onClick = { onDownloadCertificate(certificateId, "png") }, enabled = !busy,
                                                    modifier = Modifier.weight(1f)) { Text(if (language == "hi") "PNG सेव करें" else "Save PNG") }
                                            }
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
                                            "SUBJECT_COMPLETION_REQUIRED" -> if (language == "hi") "Test certificate के लिए सभी lessons पूरे करना ज़रूरी नहीं है; सभी published lessons केवल subject-completion certificate के लिए पूरे करें।" else "A test certificate does not require completing every lesson; all published lessons are required only for a subject-completion certificate."
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
    language: String,
    store: ProtectedResourceStore,
    busy: Boolean,
    error: String,
    downloadStatus: String,
    downloadStates: SnapshotStateMap<Long, ResourceDownloadUiState>,
    onSubject: (String) -> Unit,
    onOpen: (JSONObject) -> Unit,
    onDownload: (JSONObject) -> Unit,
    onRemoveOffline: (JSONObject) -> Unit,
    onRefresh: () -> Unit
) {
    fun t(en: String, hi: String) = if (language == "hi") hi else en
    var pendingRemoval by remember { mutableStateOf<JSONObject?>(null) }
    var chaptersExpanded by rememberSaveable(subject) { mutableStateOf(true) }
    var chapterPage by rememberSaveable(subject) { mutableStateOf(1) }
    var chapterPageSize by rememberSaveable(subject) { mutableStateOf(10) }

    val subjectBooks = documents
        .filter { it.textAny("scope").equals("SUBJECT_BOOK", true) }
        .sortedWith(compareBy<JSONObject> { it.optInt("book_part", 0) }.thenBy { it.optLongAny("document_id", "id") })
    // Keep the API's curriculum teaching order; do not alphabetically sort chapter codes.
    val chapterBooks = documents.filter {
        it.textAny("scope").equals("CHAPTER_PDF", true) ||
            it.textAny("chapter_name", "chapterName").isNotBlank()
    }
    val otherResources = documents.filter { doc ->
        !doc.textAny("scope").equals("SUBJECT_BOOK", true) &&
            !doc.textAny("scope").equals("CHAPTER_PDF", true) &&
            doc.textAny("chapter_name", "chapterName").isBlank()
    }
    val chapterPageCount = ((chapterBooks.size + chapterPageSize - 1) / chapterPageSize).coerceAtLeast(1)
    val safeChapterPage = chapterPage.coerceIn(1, chapterPageCount)
    val visibleChapters = chapterBooks.drop((safeChapterPage - 1) * chapterPageSize).take(chapterPageSize)

    if (pendingRemoval != null) {
        AlertDialog(
            onDismissRequest = { pendingRemoval = null },
            title = { Text(t("Remove offline copy?", "Offline copy हटाएँ?")) },
            text = { Text(t(
                "This only removes encrypted page copies kept on this device. The published resource remains on the server and can be downloaded again later.",
                "इससे केवल इस डिवाइस पर रखी encrypted page copies हटेंगी। प्रकाशित सामग्री server पर बनी रहेगी और बाद में फिर डाउनलोड की जा सकती है।"
            )) },
            confirmButton = {
                Button(onClick = {
                    val selected = pendingRemoval
                    pendingRemoval = null
                    if (selected != null) onRemoveOffline(selected)
                }, enabled = !busy) { Text(t("Remove offline copy", "Offline copy हटाएँ")) }
            },
            dismissButton = {
                TextButton(onClick = { pendingRemoval = null }, enabled = !busy) { Text(t("Cancel", "रद्द करें")) }
            }
        )
    }

    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(t("Textbook library", "पाठ्यपुस्तक लाइब्रेरी"), style = MaterialTheme.typography.headlineSmall)
                Text(t("Subject → complete book/parts → chapter PDFs", "विषय → पूरी पुस्तक/भाग → अध्याय PDF"),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
            }
            TextButton(onClick = onRefresh, enabled = !busy) { Text(t("Refresh", "रिफ्रेश")) }
        }
        Text(
            t("Read protected learning content inside QuantaEdge. Offline copies are encrypted and remain private to this account on this device.",
                "संरक्षित सामग्री QuantaEdge ऐप में पढ़ें। Offline copies encrypted रहती हैं और इस डिवाइस पर इसी खाते के लिए निजी रहती हैं।"),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            if (subject == "maths") Button(onClick = { onSubject("maths") }, enabled = !busy, modifier = Modifier.weight(1f)) {
                Text(t("Mathematics", "गणित"))
            } else OutlinedButton(onClick = { onSubject("maths") }, enabled = !busy, modifier = Modifier.weight(1f)) {
                Text(t("Mathematics", "गणित"))
            }
            if (subject == "science") Button(onClick = { onSubject("science") }, enabled = !busy, modifier = Modifier.weight(1f)) {
                Text(t("Science", "विज्ञान"))
            } else OutlinedButton(onClick = { onSubject("science") }, enabled = !busy, modifier = Modifier.weight(1f)) {
                Text(t("Science", "विज्ञान"))
            }
        }

        if (busy && documents.isEmpty()) {
            Box(modifier = Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    CircularProgressIndicator()
                    Text(t("Loading your textbook library…", "आपकी पाठ्यपुस्तक लाइब्रेरी लोड हो रही है…"))
                }
            }
        } else if (documents.isEmpty() && error.isNotBlank()) {
            Box(modifier = Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                    Button(onClick = onRefresh, enabled = !busy) { Text(t("Retry", "फिर प्रयास करें")) }
                }
            }
        } else if (documents.isEmpty()) {
            Box(modifier = Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                EmptyState(
                    t("No published textbooks yet", "अभी कोई प्रकाशित पाठ्यपुस्तक नहीं"),
                    t("Published books and chapter PDFs for this subject and learning language will appear here.",
                        "इस विषय और चुनी गई पढ़ाई की भाषा के लिए प्रकाशित पुस्तकें और अध्याय PDF यहाँ दिखेंगे।")
                )
            }
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(9.dp),
                modifier = Modifier.weight(1f).fillMaxWidth().padding(top = 7.dp)
            ) {
                if (subjectBooks.isNotEmpty()) {
                    item(key = "subject-book-heading") {
                        Text(t("Complete book and book parts", "पूरी पुस्तक और पुस्तक के भाग"),
                            style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    }
                    items(subjectBooks, key = { "book-" + it.optLongAny("document_id", "id") }) { doc ->
                        ResourceDocumentCard(
                            doc = doc, store = store, busy = busy, language = language,
                            downloadState = downloadStates[doc.optLongAny("document_id", "id")],
                            onOpen = onOpen, onDownload = onDownload,
                            onRemoveOffline = { pendingRemoval = it }
                        )
                    }
                }

                if (chapterBooks.isNotEmpty()) {
                    item(key = "chapter-pdf-heading") {
                        Card(modifier = Modifier.fillMaxWidth(), onClick = { chaptersExpanded = !chaptersExpanded }) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 13.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text(t("Chapter PDFs", "अध्याय PDF"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                                    Text(
                                        chapterBooks.size.toString() + " " + t("published chapter resources", "प्रकाशित अध्याय संसाधन"),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Text(if (chaptersExpanded) "−" else "+", style = MaterialTheme.typography.titleLarge)
                            }
                        }
                    }
                    if (chaptersExpanded) {
                        item(key = "chapter-pagination-top") {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(t("Rows", "पंक्तियाँ"), style = MaterialTheme.typography.labelMedium)
                                listOf(10, 20, 30, 50).forEach { size ->
                                    if (chapterPageSize == size) Button(
                                        onClick = { chapterPageSize = size; chapterPage = 1 },
                                        enabled = !busy,
                                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                                    ) { Text(size.toString()) }
                                    else TextButton(
                                        onClick = { chapterPageSize = size; chapterPage = 1 },
                                        enabled = !busy,
                                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                                    ) { Text(size.toString()) }
                                }
                            }
                        }
                        items(visibleChapters, key = { "chapter-" + it.optLongAny("document_id", "id") }) { doc ->
                            val index = chapterBooks.indexOfFirst { it.optLongAny("document_id", "id") == doc.optLongAny("document_id", "id") } + 1
                            ResourceDocumentCard(
                                doc = doc, store = store, busy = busy, language = language,
                                downloadState = downloadStates[doc.optLongAny("document_id", "id")],
                                onOpen = onOpen, onDownload = onDownload,
                                onRemoveOffline = { pendingRemoval = it }, chapterIndex = index
                            )
                        }
                        item(key = "chapter-pagination-bottom") {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(t("Page $safeChapterPage of $chapterPageCount", "पृष्ठ $safeChapterPage / $chapterPageCount"),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Spacer(Modifier.weight(1f))
                                TextButton(onClick = { chapterPage = (safeChapterPage - 1).coerceAtLeast(1) }, enabled = !busy && safeChapterPage > 1) {
                                    Text(t("Previous", "पिछला"))
                                }
                                TextButton(onClick = { chapterPage = (safeChapterPage + 1).coerceAtMost(chapterPageCount) }, enabled = !busy && safeChapterPage < chapterPageCount) {
                                    Text(t("Next", "अगला"))
                                }
                            }
                        }
                    }
                }

                if (otherResources.isNotEmpty()) {
                    item(key = "other-resource-heading") {
                        Text(t("Other learning resources", "अन्य पढ़ाई के संसाधन"),
                            style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    }
                    items(otherResources, key = { "other-" + it.optLongAny("document_id", "id") }) { doc ->
                        ResourceDocumentCard(
                            doc = doc, store = store, busy = busy, language = language,
                            downloadState = downloadStates[doc.optLongAny("document_id", "id")],
                            onOpen = onOpen, onDownload = onDownload,
                            onRemoveOffline = { pendingRemoval = it }
                        )
                    }
                }
            }
        }

        if (error.isNotBlank() && documents.isNotEmpty()) {
            Text(error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 4.dp))
        }
        if (downloadStatus.isNotBlank()) {
            Text(downloadStatus, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp),
                color = MaterialTheme.colorScheme.secondary, maxLines = 3)
        }
    }
}

@Composable
private fun ResourceDocumentCard(
    doc: JSONObject,
    store: ProtectedResourceStore,
    busy: Boolean,
    language: String,
    downloadState: ResourceDownloadUiState?,
    onOpen: (JSONObject) -> Unit,
    onDownload: (JSONObject) -> Unit,
    onRemoveOffline: (JSONObject) -> Unit,
    chapterIndex: Int? = null
) {
    fun t(en: String, hi: String) = if (language == "hi") hi else en
    val id = doc.optLongAny("document_id", "id")
    val count = doc.optInt("page_count",
        (doc.optInt("page_end", 1) - doc.optInt("page_start", 1) + 1).coerceAtLeast(1))
    val cached = store.cachedPageCount(id, count)
    val scope = doc.textAny("scope").uppercase()
    val part = doc.optInt("book_part", 0)
    val title = when {
        scope == "SUBJECT_BOOK" && part > 0 -> t("Part $part", "भाग $part") + " · " + doc.textAny("title")
        scope == "SUBJECT_BOOK" -> doc.textAny("title").ifBlank { t("Complete subject book", "पूरी विषय-पुस्तक") }
        else -> doc.textAny("chapter_name", "chapterName").ifBlank { doc.textAny("title").ifBlank { t("Learning resource", "पढ़ाई का संसाधन") } }
    }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(13.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text((chapterIndex?.let { "$it. " } ?: "") + title,
                        style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    val meta = listOfNotNull(
                        doc.textAny("edition").takeIf { it.isNotBlank() },
                        doc.textAny("class_code", "classCode").takeIf { it.isNotBlank() }?.let { t("Class $it", "कक्षा $it") },
                        doc.textAny("language").takeIf { it.isNotBlank() }?.let {
                            if (it.equals("en", true)) t("English", "अंग्रेज़ी") else t("Hindi", "हिन्दी")
                        }
                    ).joinToString(" · ")
                    if (meta.isNotBlank()) Text(meta, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(t("$count pages", "$count पृष्ठ") + " · " + t("Offline", "Offline") + ": $cached / $count",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (downloadState?.active == true) CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            }
            if (downloadState != null) {
                Text(downloadState.label, style = MaterialTheme.typography.bodySmall,
                    color = if (downloadState.active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary)
                if (downloadState.progress != null && downloadState.active) {
                    LinearProgressIndicator(progress = { downloadState.progress }, modifier = Modifier.fillMaxWidth())
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                Button(onClick = { onOpen(doc) }, enabled = !busy, modifier = Modifier.weight(1f)) { Text(t("Read", "पढ़ें")) }
                TextButton(onClick = { onDownload(doc) }, enabled = !busy && downloadState?.active != true,
                    modifier = Modifier.weight(1f)) {
                    Text(t(if (cached == count) "Download again" else "Download offline",
                        if (cached == count) "फिर डाउनलोड करें" else "Offline डाउनलोड"))
                }
            }
            if (cached > 0) TextButton(onClick = { onRemoveOffline(doc) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                Text(t("Remove offline copy", "Offline copy हटाएँ"), color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun DocumentReaderScreen(
    document: JSONObject,
    page: Int,
    image: androidx.compose.ui.graphics.ImageBitmap?,
    thumbnailImages: Map<Int, androidx.compose.ui.graphics.ImageBitmap>,
    loadingThumbnailPages: Set<String>,
    pageCount: Int,
    language: String,
    fullscreen: Boolean,
    busy: Boolean,
    error: String,
    cachedCount: Int,
    onPage: (Int) -> Unit,
    onRequestThumbnails: (List<Int>) -> Unit,
    onToggleFullscreen: () -> Unit,
    onRetry: () -> Unit,
    onDownload: () -> Unit
) {
    fun t(en: String, hi: String) = if (language == "hi") hi else en
    var pageInput by rememberSaveable(document.optLongAny("document_id", "id")) { mutableStateOf(page.toString()) }
    var zoomScale by rememberSaveable(document.optLongAny("document_id", "id")) { mutableStateOf(1f) }
    var imageOffset by remember(document.optLongAny("document_id", "id")) { mutableStateOf(Offset.Zero) }
    var fitWidth by rememberSaveable(document.optLongAny("document_id", "id")) { mutableStateOf(false) }
    var thumbnailsOpen by rememberSaveable(document.optLongAny("document_id", "id")) { mutableStateOf(false) }
    LaunchedEffect(page) {
        pageInput = page.toString()
        zoomScale = 1f
        imageOffset = Offset.Zero
        fitWidth = false
    }
    val requestedPage = pageInput.toIntOrNull()
    val validPage = requestedPage != null && requestedPage in 1..pageCount
    val thumbnailPages = if (pageCount <= 9) {
        (1..pageCount.coerceAtLeast(1)).toList()
    } else {
        ((page - 4).coerceAtLeast(1)..(page + 4).coerceAtMost(pageCount)).toList()
    }
    LaunchedEffect(thumbnailsOpen, page, pageCount, document.optLongAny("document_id", "id")) {
        if (thumbnailsOpen) onRequestThumbnails(thumbnailPages)
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(if (fullscreen) 5.dp else 10.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Text(document.textAny("title").ifBlank { t("Textbook", "पुस्तक") },
                    style = if (fullscreen) MaterialTheme.typography.titleSmall else MaterialTheme.typography.titleMedium,
                    maxLines = 1)
                Text(
                    t("Page ", "पृष्ठ ") + page + " / " + pageCount + " • " +
                        t("Offline pages: ", "Offline पृष्ठ: ") + cachedCount,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
            }
            TextButton(onClick = onToggleFullscreen) {
                Text(if (fullscreen) t("Exit full screen", "Fullscreen बंद करें") else t("Full screen", "Fullscreen"))
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            OutlinedTextField(
                value = pageInput,
                onValueChange = { pageInput = it.filter(Char::isDigit).take(6) },
                label = { Text(t("Page number", "पृष्ठ संख्या")) },
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
            Button(onClick = { if (validPage) onPage(requestedPage!!) }, enabled = !busy && validPage) {
                Text(t("Go", "जाएँ"))
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            TextButton(onClick = { onPage(page - 1) }, enabled = !busy && page > 1) { Text(t("← Previous", "← पिछला")) }
            Spacer(modifier = Modifier.weight(1f))
            TextButton(onClick = { thumbnailsOpen = !thumbnailsOpen }, enabled = pageCount > 1) {
                Text(if (thumbnailsOpen) t("Hide pages", "पृष्ठ छिपाएँ") else t("Page thumbnails", "पृष्ठ थंबनेल"))
            }
            TextButton(onClick = onDownload, enabled = !busy && cachedCount < pageCount) { Text(t("Offline", "Offline")) }
            Spacer(modifier = Modifier.weight(1f))
            TextButton(onClick = { onPage(page + 1) }, enabled = !busy && page < pageCount) { Text(t("Next →", "अगला →")) }
        }

        if (thumbnailsOpen) {
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                thumbnailPages.forEach { pageNumber ->
                    val selected = pageNumber == page
                    Surface(
                        modifier = Modifier.width(72.dp).height(94.dp).clickable(enabled = !busy) { onPage(pageNumber) },
                        shape = RoundedCornerShape(8.dp),
                        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                        contentColor = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                        border = androidx.compose.foundation.BorderStroke(
                            if (selected) 2.dp else 1.dp,
                            if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
                        )
                    ) {
                        Column(
                            modifier = Modifier.fillMaxSize().padding(4.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(3.dp)
                        ) {
                            val thumbnail = thumbnailImages[pageNumber]
                            if (thumbnail != null) {
                                Image(
                                    bitmap = thumbnail,
                                    contentDescription = t("Preview of page ", "पृष्ठ का पूर्वावलोकन ") + pageNumber,
                                    modifier = Modifier.fillMaxWidth().weight(1f),
                                    contentScale = ContentScale.Fit
                                )
                            } else {
                                Box(modifier = Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                                    if (loadingThumbnailPages.contains(document.optLongAny("document_id", "id").toString() + ":" + pageNumber)) {
                                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                                    } else {
                                        Text(t("Preview", "पूर्वावलोकन"), style = MaterialTheme.typography.labelSmall)
                                    }
                                }
                            }
                            Text(pageNumber.toString(), style = MaterialTheme.typography.labelSmall,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal)
                        }
                    }
                }
            }
        }

        if (image != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                TextButton(
                    onClick = { zoomScale = (zoomScale - 0.25f).coerceAtLeast(1f); fitWidth = false; if (zoomScale == 1f) imageOffset = Offset.Zero },
                    enabled = zoomScale > 1f
                ) { Text("−") }
                Text(zoomScale.times(100).toInt().toString() + "%", style = MaterialTheme.typography.labelMedium)
                TextButton(
                    onClick = { zoomScale = (zoomScale + 0.25f).coerceAtMost(4f); fitWidth = false },
                    enabled = zoomScale < 4f
                ) { Text("+") }
                TextButton(onClick = { zoomScale = 1f; fitWidth = false; imageOffset = Offset.Zero }) { Text(t("Fit page", "पृष्ठ में फिट करें")) }
                TextButton(onClick = { zoomScale = 1f; fitWidth = true; imageOffset = Offset.Zero }) { Text(t("Fit width", "चौड़ाई में फिट करें")) }
                Spacer(modifier = Modifier.weight(1f))
                TextButton(onClick = onRetry, enabled = !busy) { Text(t("Retry page", "पृष्ठ फिर लोड करें")) }
            }
            Image(
                bitmap = image,
                contentDescription = t("Textbook page ", "पुस्तक का पृष्ठ ") + page,
                modifier = Modifier.fillMaxWidth().weight(1f)
                    .pointerInput(page) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            zoomScale = (zoomScale * zoom).coerceIn(1f, 4f)
                            fitWidth = false
                            imageOffset = if (zoomScale == 1f) Offset.Zero else imageOffset + pan
                        }
                    }
                    .graphicsLayer(scaleX = zoomScale, scaleY = zoomScale,
                        translationX = imageOffset.x, translationY = imageOffset.y),
                contentScale = if (fitWidth) ContentScale.FillWidth else ContentScale.Fit
            )
        } else {
            Box(modifier = Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                if (busy) {
                    CircularProgressIndicator()
                } else {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            if (error.isNotBlank()) error else t("This page could not be rendered.", "यह पृष्ठ नहीं दिखाया जा सका।"),
                            color = if (error.isNotBlank()) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Button(onClick = onRetry, enabled = !busy) { Text(t("Retry page", "पृष्ठ फिर लोड करें")) }
                    }
                }
            }
        }

        Text(t("Protected learning content stays inside QuantaEdge; download/export/share of the source PDF is not available.",
            "संरक्षित पढ़ाई की सामग्री QuantaEdge में ही रहती है; मूल PDF को डाउनलोड/export/share नहीं किया जा सकता।"),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
    }
}

@Composable
private fun ParentHomeScreen(
    parent: JSONObject,
    children: List<JSONObject>,
    section: String,
    language: String,
    busy: Boolean,
    onRefresh: () -> Unit,
    onReport: (JSONObject) -> Unit,
    onManageChild: (JSONObject) -> Unit,
    onAddChild: () -> Unit,
    onArchivedProfiles: () -> Unit,
    onProfile: () -> Unit,
    onLogout: () -> Unit,
    maxChildren: Int
) {
    val parentName = parent.textAny("parentName").ifBlank { if (language == "hi") "अभिभावक" else "Parent account" }
    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text(HomeGreeting.text(LocalTime.now().hour, language),
            style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.SemiBold)
        Text(
            (if (language == "hi") "नमस्ते, " else "Hello, ") + parentName + " 👋",
            style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.secondary
        )
        Text(
            text = if (section == "children") {
                if (language == "hi") "बच्चों के प्रोफ़ाइल" else "Children’s profiles"
            } else {
                if (language == "hi") "परिवार की रिपोर्ट" else "Family report"
            },
            style = MaterialTheme.typography.titleMedium
        )
        Text(
            (if (language == "hi") "सक्रिय छात्र: " else "Active students: ") +
                parent.optInt("activeChildren", children.size) + " / " + maxChildren,
            style = MaterialTheme.typography.bodySmall
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = onRefresh, enabled = !busy) {
                Text(if (language == "hi") "रिफ्रेश" else "Refresh")
            }
            TextButton(onClick = onLogout, enabled = !busy) {
                Text(if (language == "hi") "साइन आउट" else "Sign out")
            }
        }
        Button(onClick = onAddChild, enabled = !busy && children.size < maxChildren,
            modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
            Text(if (language == "hi") "नया छात्र प्रोफ़ाइल बनाएँ" else "Create child profile")
        }
        if (section == "children") {
            TextButton(onClick = onArchivedProfiles, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                Text(if (language == "hi") "Archived profiles / Restore" else "Archived profiles / Restore")
            }
        }
        if (children.isEmpty()) EmptyState(
            if (language == "hi") "कोई सक्रिय छात्र प्रोफ़ाइल नहीं" else "No active child profiles",
            if (language == "hi") "अभिभावक खाते से जुड़े सक्रिय छात्र यहाँ दिखेंगे।" else "Active student profiles connected to this parent account appear here."
        )
        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 8.dp)) {
            items(children) { child ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(child.textAny("display_name", "displayName").ifBlank { "छात्र" }, style = MaterialTheme.typography.titleMedium)
                        Text(localizedUiText("कक्षा ") + child.textAny("class_code", "class_name") + " • " + child.textAny("track_codes"),
                            style = MaterialTheme.typography.bodySmall)
                        if (section == "children") {
                            Text(
                                (if (language == "hi") "यूज़रनेम: " else "Username: ") +
                                    child.textAny("login_username").ifBlank { if (language == "hi") "सेट नहीं" else "Not set" },
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { onReport(child) }, enabled = !busy,
                                modifier = Modifier.weight(1f)) {
                                Text(if (language == "hi") "सीखने की रिपोर्ट" else "Learning report")
                            }
                            if (section == "children") {
                                TextButton(onClick = { onManageChild(child) }, enabled = !busy,
                                    modifier = Modifier.weight(1f)) {
                                    Text(if (language == "hi") "Login / विषय" else "Login / subjects")
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ParentReportScreen(
    report: JSONObject,
    children: List<JSONObject>,
    language: String,
    busy: Boolean,
    certificates: List<JSONObject>,
    onSelectChild: (JSONObject) -> Unit,
    onViewCertificate: (Long, Long) -> Unit,
    onDownloadCertificate: (Long, Long, String) -> Unit
) {
    val childId = report.optLong("_childId", 0L)
    val otherChildren = children.filter { it.longAny("id") > 0L && it.longAny("id") != childId }
    val compact = LocalConfiguration.current.screenWidthDp < 390
    val lessons = report.optJSONObject("lessonStats") ?: JSONObject()
    val questions = report.optJSONObject("questionStats") ?: JSONObject()
    val mastery = report.optJSONObject("mastery") ?: JSONObject()
    val sessions = report.optJSONObject("sessionStats") ?: JSONObject()
    val stats = report.optJSONObject("assessmentStats") ?: JSONObject()
    val curriculum = report.optJSONArray("curriculum")?.objectItems() ?: emptyList()
    val concepts = report.optJSONArray("masteryDetails")?.objectItems() ?: emptyList()
    val tests = report.optJSONArray("recentAssessments")?.objectItems() ?: emptyList()
    val practice = report.optJSONArray("recentAttempts")?.objectItems() ?: emptyList()
    fun t(en: String, hi: String) = if (language == "hi") hi else en

    Column(modifier = Modifier.fillMaxSize().widthIn(max = 1000.dp).verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(report.textAny("_childName", "display_name").ifBlank { t("Student report", "छात्र की रिपोर्ट") },
            style = MaterialTheme.typography.headlineSmall)
        Text(t("Class", "कक्षा") + " " + report.textAny("class_code", "classCode"),
            color = MaterialTheme.colorScheme.secondary)
        if (otherChildren.isNotEmpty()) {
            Text(t("Switch child report", "दूसरे बच्चे की रिपोर्ट देखें"), style = MaterialTheme.typography.titleMedium)
            otherChildren.forEach { child ->
                OutlinedButton(
                    onClick = { onSelectChild(child) },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(t("View report: ", "रिपोर्ट देखें: ") +
                        child.textAny("display_name", "displayName").ifBlank { t("Student", "छात्र") })
                }
            }
        }
        if (compact) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                ParentReportMetric(t("Lessons", "पाठ"), lessons.optString("completed_lessons","0")+" / "+lessons.optString("total_lessons","0"),
                    lessons.optString("completion_percent","0")+"% "+t("complete","पूरे"), Modifier.fillMaxWidth())
                ParentReportMetric(t("Practice accuracy","अभ्यास सटीकता"), questions.optString("accuracy_percent","0")+"%",
                    questions.optString("attempts","0")+" "+t("attempts","प्रयास"), Modifier.fillMaxWidth())
                ParentReportMetric(t("Average mastery","औसत महारत"), mastery.optString("average_mastery","0")+"%",
                    mastery.optString("mastered","0")+" "+t("concepts mastered","अवधारणाएँ पूरी"), Modifier.fillMaxWidth())
                ParentReportMetric(t("Tests released","जारी टेस्ट"), stats.optString("released","0"),
                    stats.optString("awaiting_review","0")+" "+t("awaiting review","समीक्षा बाकी"), Modifier.fillMaxWidth())
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                ParentReportMetric(t("Lessons", "पाठ"), lessons.optString("completed_lessons","0")+" / "+lessons.optString("total_lessons","0"),
                    lessons.optString("completion_percent","0")+"% "+t("complete","पूरे"), Modifier.weight(1f))
                ParentReportMetric(t("Practice accuracy","अभ्यास सटीकता"), questions.optString("accuracy_percent","0")+"%",
                    questions.optString("attempts","0")+" "+t("attempts","प्रयास"), Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                ParentReportMetric(t("Average mastery","औसत महारत"), mastery.optString("average_mastery","0")+"%",
                    mastery.optString("mastered","0")+" "+t("concepts mastered","अवधारणाएँ पूरी"), Modifier.weight(1f))
                ParentReportMetric(t("Tests released","जारी टेस्ट"), stats.optString("released","0"),
                    stats.optString("awaiting_review","0")+" "+t("awaiting review","समीक्षा बाकी"), Modifier.weight(1f))
            }
        }
        ParentReportSectionTitle(t("Curriculum covered","पूरा किया गया पाठ्यक्रम"))
        if (curriculum.isEmpty()) {
            EmptyState(t("No curriculum summary yet","अभी पाठ्यक्रम सारांश नहीं है"),
                t("Published lessons will appear here when available.","प्रकाशित पाठ उपलब्ध होने पर यहाँ दिखेंगे।"))
        } else curriculum.forEach { item ->
            Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(13.dp), verticalArrangement=Arrangement.spacedBy(4.dp)) {
                Text(item.textAny("subject_name","subject_code"), style=MaterialTheme.typography.titleMedium)
                Text(item.optString("completed","0")+" / "+item.optString("lessons","0")+" "+t("lessons","पाठ")+
                    " · "+item.optString("chapters","0")+" "+t("chapters","अध्याय"), style=MaterialTheme.typography.bodySmall)
            } }
        }
        ParentReportSectionTitle(t("Learning signals","सीखने के संकेत"))
        Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(13.dp), verticalArrangement=Arrangement.spacedBy(8.dp)) {
            ParentReportLine(t("Mastered concepts","पूरी की गई अवधारणाएँ"), mastery.optString("mastered","0"))
            ParentReportLine(t("Needs support","सहायता चाहिए"), mastery.optString("needs_support","0"))
            ParentReportLine(t("Learning time · 30 days","अध्ययन समय · 30 दिन"), sessions.optString("minutes_30d","0")+" min")
            ParentReportLine(t("Average released score","जारी टेस्ट का औसत अंक"), stats.optString("average_released_percent","0")+"%")
        } }
        ParentReportSectionTitle(t("Test history and certificates","टेस्ट इतिहास और प्रमाणपत्र"))
        if (tests.isEmpty()) EmptyState(t("No test attempts yet","अभी कोई टेस्ट प्रयास नहीं"),
            t("The child's test attempts will appear here.","बच्चे के टेस्ट प्रयास यहाँ दिखाई देंगे।"))
        else tests.take(20).forEach { attempt ->
            val attemptId=attempt.optLongAny("attempt_id","attemptId","assessment_attempt_id","assessmentAttemptId")
            val cert=certificates.firstOrNull { it.optLongAny("assessment_attempt_id","assessmentAttemptId")==attemptId }
            Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(13.dp), verticalArrangement=Arrangement.spacedBy(6.dp)) {
                Text(attempt.textAny("assessment_title","title").ifBlank { "Assessment" }, style=MaterialTheme.typography.titleMedium)
                Text(attempt.textAny("subject_name","chapter_name","assessment_type"), style=MaterialTheme.typography.bodySmall,
                    color=MaterialTheme.colorScheme.secondary)
                val state=attempt.textAny("status")
                Text(if(state=="RELEASED") attempt.textAny("final_score","score")+" / "+attempt.textAny("max_score")+" · "+t("Result released","परिणाम जारी")
                    else t("Status","स्थिति")+": "+state)
                when(cert?.textAny("status")) {
                    "ISSUED" -> {
                        Text(t("Certificate available","प्रमाणपत्र उपलब्ध है"), color=MaterialTheme.colorScheme.primary)
                        val certificateId = cert.optLong("id", 0L)
                        val canAccess = !busy && childId > 0L && certificateId > 0L
                        if (compact) {
                            Column(verticalArrangement = Arrangement.spacedBy(5.dp), modifier = Modifier.fillMaxWidth()) {
                                OutlinedButton(onClick = { onViewCertificate(childId, certificateId) },
                                    enabled = canAccess, modifier = Modifier.fillMaxWidth()) {
                                    Text(t("View certificate", "प्रमाणपत्र देखें"))
                                }
                                OutlinedButton(onClick = { onDownloadCertificate(childId, certificateId, "pdf") },
                                    enabled = canAccess, modifier = Modifier.fillMaxWidth()) {
                                    Text(t("Save PDF", "PDF सेव करें"))
                                }
                                OutlinedButton(onClick = { onDownloadCertificate(childId, certificateId, "png") },
                                    enabled = canAccess, modifier = Modifier.fillMaxWidth()) {
                                    Text(t("Save PNG", "PNG सेव करें"))
                                }
                            }
                        } else {
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                                OutlinedButton(onClick = { onViewCertificate(childId, certificateId) },
                                    enabled = canAccess, modifier = Modifier.weight(1f)) {
                                    Text(t("View", "देखें"))
                                }
                                OutlinedButton(onClick = { onDownloadCertificate(childId, certificateId, "pdf") },
                                    enabled = canAccess, modifier = Modifier.weight(1f)) {
                                    Text(t("Save PDF", "PDF सेव करें"))
                                }
                                OutlinedButton(onClick = { onDownloadCertificate(childId, certificateId, "png") },
                                    enabled = canAccess, modifier = Modifier.weight(1f)) {
                                    Text(t("Save PNG", "PNG सेव करें"))
                                }
                            }
                        }
                    }
                    "REVOKED" -> Text(t("Certificate revoked","प्रमाणपत्र निरस्त है"), color=MaterialTheme.colorScheme.error)
                }
            } }
        }
        ParentReportSectionTitle(t("Concepts needing support","जिन अवधारणाओं में सहायता चाहिए"))
        if (concepts.isEmpty()) Text(t("No concept mastery details are available yet.","अभी अवधारणा महारत का विवरण उपलब्ध नहीं है।"),
            color=MaterialTheme.colorScheme.secondary)
        else concepts.take(6).forEach { item ->
            Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp), verticalArrangement=Arrangement.spacedBy(4.dp)) {
                Text(item.textAny("concept_title").ifBlank { "Concept" }, style=MaterialTheme.typography.titleSmall)
                Text(item.textAny("subject_name","chapter_name"), style=MaterialTheme.typography.bodySmall)
                Text(item.optString("mastery_percent","0")+"% "+t("mastery","महारत"))
            } }
        }
        ParentReportSectionTitle(t("Recent practice","हाल का अभ्यास"))
        if(practice.isEmpty()) Text(t("No recent practice attempts.","अभ्यास के हाल के प्रयास नहीं हैं。"), color=MaterialTheme.colorScheme.secondary)
        else practice.take(6).forEach { item ->
            Card(Modifier.fillMaxWidth()) { Row(Modifier.fillMaxWidth().padding(12.dp),horizontalArrangement=Arrangement.SpaceBetween) {
                Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(3.dp)) {
                    Text(item.textAny("concept_title","chapter_name").ifBlank{"Practice"},style=MaterialTheme.typography.titleSmall)
                    Text(item.textAny("question_type").ifBlank{"Question"},style=MaterialTheme.typography.bodySmall)
                }
                Text(if(item.optBoolean("correct")) t("Correct","सही") else if(item.has("correct")&&!item.isNull("correct")) t("Incorrect","गलत") else t("Review","समीक्षा"))
            } }
        }
        Text(t("Parent view is read-only. The child must sign in with their own credentials to learn and take assessments.",
            "अभिभावक दृश्य केवल देखने के लिए है। पढ़ाई और टेस्ट के लिए बच्चे को अपने लॉगिन से प्रवेश करना होगा।"),
            style=MaterialTheme.typography.bodySmall, color=MaterialTheme.colorScheme.secondary)
    }
}
@Composable private fun ParentReportMetric(title:String,value:String,detail:String,modifier:Modifier) {
    Card(modifier=modifier) { Column(Modifier.padding(11.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
        Text(title,style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.secondary)
        Text(value,style=MaterialTheme.typography.titleLarge); Text(detail,style=MaterialTheme.typography.bodySmall)
    } }
}
@Composable private fun ParentReportSectionTitle(title:String) { Text(title,style=MaterialTheme.typography.titleLarge) }
@Composable private fun ParentReportLine(label:String,value:String) {
    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
        Text(label,Modifier.weight(1f),style=MaterialTheme.typography.bodyMedium); Text(value,style=MaterialTheme.typography.titleSmall)
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
        Text(localizedUiText("प्रोफ़ाइल और सेटिंग"), style = MaterialTheme.typography.headlineSmall)
        Text(auth.textAny("displayName", "display_name", "role"), style = MaterialTheme.typography.titleMedium)
        Text(localizedUiText("खाता: ") + auth.textAny("role"), style = MaterialTheme.typography.bodyMedium)
        Text(localizedUiText("कक्षा: ") + profile.textAny("class_code", "classCode"), style = MaterialTheme.typography.bodyMedium)
        Text(localizedUiText("ऐप भाषा: ") + if (language == "hi") "हिंदी" else "English", style = MaterialTheme.typography.bodyMedium)
        Button(onClick = onToggleLanguage, modifier = Modifier.fillMaxWidth()) {
            Text(if (language == "hi") "Switch to English" else "हिंदी में बदलें")
        }
        Button(onClick = onLogout, modifier = Modifier.fillMaxWidth()) { Text(localizedUiText("साइन आउट")) }
        Text(localizedUiText("डाउनलोड की गई अध्ययन सामग्री ऐप के निजी स्टोरेज में encrypted रहती है और Android backup में शामिल नहीं होती।"),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
    }
}
