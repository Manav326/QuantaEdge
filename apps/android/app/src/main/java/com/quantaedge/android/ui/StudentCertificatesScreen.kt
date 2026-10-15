package com.quantaedge.android.ui

import com.quantaedge.android.BuildConfig

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.quantaedge.android.data.ApiClient
import com.quantaedge.android.data.ApiFailure
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

private fun localizedCertificateSubject(code: String, displayName: String, language: String): String {
    return when (code.trim().lowercase()) {
        "maths", "math", "mathematics" -> if (language == "hi") "गणित" else "Mathematics"
        "science" -> if (language == "hi") "विज्ञान" else "Science"
        else -> displayName.ifBlank { code }
    }
}

@Composable
fun StudentCertificatesScreen(
    language: String,
    certificateLanguage: String,
    onCertificateLanguageChange: (String) -> Unit,
    initialAssessmentAttemptId: Long = 0L,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val compact = LocalConfiguration.current.screenWidthDp < 390
    var certificates by remember { mutableStateOf(emptyList<JSONObject>()) }
    var eligibleAssessments by remember { mutableStateOf(emptyList<JSONObject>()) }
    var subjectTracks by remember { mutableStateOf(emptyList<JSONObject>()) }
    var selectedTrackKey by remember { mutableStateOf("") }
    var selectedAchievement by remember { mutableStateOf("SUBJECT_COMPLETION") }
    var selectedAttemptId by remember { mutableStateOf("") }
    var achievementMenuExpanded by remember { mutableStateOf(false) }
    var subjectMenuExpanded by remember { mutableStateOf(false) }
    var assessmentMenuExpanded by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf("") }
    var notice by remember { mutableStateOf("") }
    var pendingBytes by remember { mutableStateOf<ByteArray?>(null) }
    var pendingName by remember { mutableStateOf("quantaedge-certificate") }
    var verification by remember { mutableStateOf<JSONObject?>(null) }
    var verificationCode by remember { mutableStateOf("") }
    var verifyingQr by remember { mutableStateOf(false) }
    var showScanner by rememberSaveable { mutableStateOf(false) }

    fun writeDownloadedFile(uri: android.net.Uri?) {
        val bytes = pendingBytes
        if (uri == null || bytes == null) {
            pendingBytes = null
            return
        }
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
                        ?: throw IOException("चुनी गई जगह पर certificate save नहीं हो सका।")
                }
                notice = "Certificate आपके चुने हुए स्थान पर सुरक्षित कर दिया गया।"
            } catch (e: Exception) {
                error = e.message ?: "Certificate save नहीं हो सका।"
            } finally {
                pendingBytes = null
            }
        }
    }

    val pdfLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { writeDownloadedFile(it) }
    val pngLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/png")) { writeDownloadedFile(it) }

    suspend fun reload() {
        loading = true
        error = ""
        try {
            val trackResponse = ApiClient.getObject("/api/v1/students/me/certificates/subject-tracks")
            subjectTracks = trackResponse.optJSONArray("items").toObjectList()
            if (subjectTracks.none { it.optString("track_key") == selectedTrackKey }) {
                selectedTrackKey = subjectTracks.firstOrNull()?.optString("track_key").orEmpty()
            }
            val issued = ApiClient.getObject("/api/v1/students/me/certificates")
            certificates = issued.optJSONArray("items").toObjectList()
            val eligible = ApiClient.getObject("/api/v1/students/me/certificates/eligible-assessments")
            eligibleAssessments = eligible.optJSONArray("items").toObjectList()
            val requestedAttempt = eligibleAssessments.firstOrNull { it.optLongValue("attempt_id", "attemptId", "id") == initialAssessmentAttemptId }
            if (initialAssessmentAttemptId > 0L && requestedAttempt != null) {
                selectedAchievement = "ASSESSMENT_PASS"
                selectedAttemptId = initialAssessmentAttemptId.toString()
            } else if (selectedAttemptId.isBlank() && eligibleAssessments.isNotEmpty()) {
                selectedAttemptId = eligibleAssessments.first().optLongValue("attempt_id", "attemptId", "id").takeIf { it > 0 }?.toString().orEmpty()
            }
        } catch (e: Exception) {
            error = e.message ?: "प्रमाणपत्रों की जानकारी लोड नहीं हो सकी।"
        } finally {
            loading = false
        }
    }

    LaunchedEffect(language, initialAssessmentAttemptId) { reload() }

    fun verifyScannedCertificate(code: String) {
        scope.launch {
            verifyingQr = true
            error = ""
            notice = ""
            verification = null
            try {
                verification = withContext(Dispatchers.IO) {
                    ApiClient.getObject("/api/v1/certificates/verify/" + ApiClient.encodeQuery(code))
                }
            } catch (e: Exception) {
                verification = null
                error = if (e is ApiFailure && e.statusCode == 404) {
                    if (language == "hi") "यह प्रमाणपत्र नहीं मिला। QR या प्रमाणपत्र नंबर जाँचें।"
                    else "Certificate not found. Check the QR code or certificate number."
                } else {
                    e.message ?: if (language == "hi") "प्रमाणपत्र सत्यापित नहीं हो सका।" else "Certificate verification failed."
                }
            } finally {
                verifyingQr = false
            }
        }
    }

    fun scanCertificateQr() {
        error = ""
        notice = ""
        verification = null
        showScanner = true
    }

    fun downloadCertificate(certificate: JSONObject, format: String) {
        scope.launch {
            busy = true
            error = ""
            notice = ""
            try {
                val id = certificate.optLongValue("id", "certificate_id", "certificateId")
                if (id <= 0) throw IOException("Certificate record की पहचान नहीं मिली।")
                val bytes = withContext(Dispatchers.IO) {
                    ApiClient.getBytes("/api/v1/students/me/certificates/" + id + "/download?format=" + format)
                }
                if (bytes.isEmpty()) throw IOException("Server ने खाली certificate file भेजी।")
                pendingBytes = bytes
                val code = certificate.optString("certificate_number", certificate.optString("certificateNumber", id.toString()))
                    .replace(Regex("[^A-Za-z0-9_-]"), "_")
                pendingName = "quantaedge-$code.$format"
                if (format == "pdf") pdfLauncher.launch(pendingName) else pngLauncher.launch(pendingName)
            } catch (e: Exception) {
                error = e.message ?: "Certificate download नहीं हो सका।"
            } finally {
                busy = false
            }
        }
    }


    fun viewCertificate(certificate: JSONObject) {
        scope.launch {
            busy = true
            error = ""
            notice = ""
            try {
                val id = certificate.optLongValue("id", "certificate_id", "certificateId")
                if (id <= 0L) throw IOException("Certificate record की पहचान नहीं मिली।")
                val bytes = withContext(Dispatchers.IO) {
                    ApiClient.getBytes("/api/v1/students/me/certificates/" + id + "/view?format=pdf")
                }
                if (bytes.isEmpty()) throw IOException("Server ने खाली certificate file भेजी।")
                val directory = java.io.File(context.cacheDir, "certificates")
                if (!directory.exists() && !directory.mkdirs()) throw IOException("प्रमाणपत्र preview तैयार नहीं हो सका।")
                val file = java.io.File(directory, "quantaedge-certificate-" + id + ".pdf")
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
            } catch (e: Exception) {
                error = e.message ?: if (language == "hi") "Certificate खोला नहीं जा सका।" else "Could not open this certificate."
            } finally {
                busy = false
            }
        }
    }

    fun issueCertificate() {
        val selectedAttempt = eligibleAssessments.firstOrNull {
            it.optLongValue("attempt_id", "attemptId", "id").toString() == selectedAttemptId
        }
        if (selectedAchievement == "ASSESSMENT_PASS" && selectedAttempt?.optString("certificate_status") == "ISSUED") {
            downloadCertificate(selectedAttempt, "pdf")
            return
        }
        if (selectedAchievement == "ASSESSMENT_PASS" && selectedAttempt?.optString("certificate_status") == "REVOKED") {
            error = if (language == "hi") "इस result का प्रमाणपत्र निरस्त है; इसे दोबारा जारी नहीं किया जा सकता।"
                else "The certificate for this result is revoked and cannot be reissued. Contact support if this is unexpected."
            notice = ""
            return
        }
        scope.launch {
            busy = true
            error = ""
            notice = ""
            try {
                val body = JSONObject()
                    .put("achievementType", selectedAchievement)
                    .put("templateId", "CLASSIC")
                    .put("language", certificateLanguage)
                if (selectedAchievement == "SUBJECT_COMPLETION") {
                    val selectedTrack = subjectTracks.firstOrNull {
                        it.optString("track_key") == selectedTrackKey
                    } ?: throw IOException(
                        if (language == "hi") "पहले विषय ट्रैक चुनें।" else "Choose a subject track first."
                    )
                    body.put("subjectCode", selectedTrack.optString("subject_code"))
                    val courseId = selectedTrack.optLongValue("course_id", "courseId", "program_id", "programId")
                    if (courseId > 0L) body.put("courseId", courseId)
                } else {
                    val attemptId = selectedAttemptId.toLongOrNull()
                        ?: throw IOException(
                            if (language == "hi") "पहले पात्र जारी टेस्ट चुनें।"
                            else "Choose an eligible released test first."
                        )
                    body.put("assessmentAttemptId", attemptId)
                    val selectedAttempt = eligibleAssessments.firstOrNull {
                        it.optLongValue("attempt_id", "attemptId", "id") == attemptId
                    }
                    val courseId = selectedAttempt?.optLongValue("course_id", "courseId", "program_id", "programId") ?: 0L
                    if (courseId > 0L) body.put("courseId", courseId)
                }
                val issued = withContext(Dispatchers.IO) {
                    ApiClient.postObject("/api/v1/students/me/certificates/issue", body)
                }
                notice = if (language == "hi") {
                    "प्रमाणपत्र जारी हो गया: " + issued.optString("certificate_number", issued.optString("certificateNumber", "जारी"))
                } else {
                    "Certificate issued: " + issued.optString("certificate_number", issued.optString("certificateNumber", "Issued"))
                }
                reload()
            } catch (e: Exception) {
                error = e.message ?: "Certificate जारी नहीं हो सका।"
            } finally {
                busy = false
            }
        }
    }


    if (showScanner) {
        PremiumCodeScannerScreen(
            language = language,
            onBack = { showScanner = false },
            onCodeScanned = { rawValue, _ ->
                showScanner = false
                val code = parseQuantaEdgeCertificateQr(rawValue, BuildConfig.DEBUG)
                if (code == null) {
                    verifyingQr = false
                    error = if (language == "hi") "यह मान्य QuantaEdge प्रमाणपत्र QR या बारकोड नहीं है।"
                    else "This is not a valid QuantaEdge certificate QR or barcode."
                    notice = ""
                } else {
                    verificationCode = code
                    verifyScannedCertificate(code)
                }
            }
        )
        return
    }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(if (language == "hi") "मेरे प्रमाणपत्र" else "My certificates", style = MaterialTheme.typography.headlineSmall)
            TextButton(onClick = onBack) { Text(if (language == "hi") "वापस" else "Back") }
        }
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(if (language == "hi") "प्रमाणपत्र स्कैन करें" else "Scan a certificate",
                    style = MaterialTheme.typography.titleMedium)
                Text(if (language == "hi")
                    "किसी भी QuantaEdge प्रमाणपत्र का QR स्कैन करें। स्थिति सर्वर से जाँची जाएगी।"
                    else "Scan the QR or linear barcode on a QuantaEdge certificate. Its current status is checked with the server.",
                    style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(
                    value = verificationCode,
                    onValueChange = { verificationCode = it },
                    label = { Text(if (language == "hi") "प्रमाणपत्र ID / नंबर" else "Certificate ID / number") },
                    placeholder = { Text("QE-2026-ABCDEF1234567890") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { verifyScannedCertificate(verificationCode.trim()) },
                        enabled = !verifyingQr && !busy && verificationCode.isNotBlank()
                    ) {
                        Text(if (language == "hi") "नंबर सत्यापित करें" else "Verify ID")
                    }
                    OutlinedButton(onClick = { scanCertificateQr() }, enabled = !verifyingQr && !busy) {
                        Text(if (verifyingQr) {
                            if (language == "hi") "जाँच हो रही है…" else "Checking…"
                        } else {
                            if (language == "hi") "QR / बारकोड स्कैन करें" else "Scan QR / barcode"
                        })
                    }
                }
            }
        }
        if (verifyingQr) CircularProgressIndicator()
        verification?.let { result ->
            val valid = result.optBoolean("verified", false) && result.optString("status") == "VALID"
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    Text(
                        if (valid) {
                            if (language == "hi") "✓ वैध प्रमाणपत्र" else "✓ Valid certificate"
                        } else {
                            if (language == "hi") "⚠ प्रमाणपत्र निरस्त" else "⚠ Certificate revoked"
                        },
                        style = MaterialTheme.typography.titleLarge,
                        color = if (valid) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                    )
                    Text((if (language == "hi") "प्रमाणपत्र नंबर: " else "Certificate ID: ") +
                        result.optString("certificateNumber"))
                    Text((if (language == "hi") "प्राप्तकर्ता: " else "Recipient: ") +
                        result.optString("recipientName"))
                    Text((if (language == "hi") "उपलब्धि: " else "Achievement: ") +
                        result.optString("achievementTitle"))
                    val classCode = result.optString("classCode")
                    Text((if (language == "hi") "कक्षा: " else "Class: ") +
                        if (classCode.isNotBlank()) "Class $classCode" else "—")
                    Text((if (language == "hi") "विषय ट्रैक: " else "Subject track: ") +
                        result.optString("subjectTrack", "—"))
                    val issuedAt = result.optString("issuedAt").substringBefore("T")
                    Text((if (language == "hi") "जारी करने की तारीख: " else "Issue date: ") +
                        if (issuedAt.isNotBlank()) issuedAt else "—")
                    Text(if (language == "hi") "यह स्थिति सर्वर के वर्तमान रिकॉर्ड से सत्यापित है।"
                        else "This status was checked against the server's current record.",
                        style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        Text(
            if (language == "hi") "केवल सत्यापित उपलब्धि पर प्रमाणपत्र जारी होगा। नाम, अंक और पात्रता server से सत्यापित होते हैं।"
            else "Certificates are issued only for verified achievements. The server verifies identity, scores and eligibility.",
            style = MaterialTheme.typography.bodyMedium
        )
        if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
        if (notice.isNotBlank()) Text(notice, color = MaterialTheme.colorScheme.primary)
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    if (language == "hi") "उपलब्धि प्रमाणपत्र जारी करें" else "Issue an achievement certificate",
                    style = MaterialTheme.typography.titleLarge
                )
                Text(
                    if (language == "hi") "प्रमाणपत्र सेटिंग्स" else "Certificate settings",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.secondary
                )
                Text(if (language == "hi") "प्रमाणपत्र की भाषा" else "Certificate language")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (certificateLanguage == "hi") Button(onClick = { onCertificateLanguageChange("hi") }) { Text("हिन्दी") }
                    else OutlinedButton(onClick = { onCertificateLanguageChange("hi") }) { Text("हिन्दी") }
                    if (certificateLanguage == "en") Button(onClick = { onCertificateLanguageChange("en") }) { Text("English") }
                    else OutlinedButton(onClick = { onCertificateLanguageChange("en") }) { Text("English") }
                }
                Box(Modifier.fillMaxWidth()) {
                    OutlinedButton(onClick = { achievementMenuExpanded = true }, modifier = Modifier.fillMaxWidth()) {
                        Text(
                            if (selectedAchievement == "SUBJECT_COMPLETION") {
                                if (language == "hi") "विषय की पढ़ाई पूरी" else "Subject completion"
                            } else if (language == "hi") "टेस्ट में पास" else "Assessment pass",
                            modifier = Modifier.weight(1f)
                        )
                        Text("▾")
                    }
                    DropdownMenu(expanded = achievementMenuExpanded, onDismissRequest = { achievementMenuExpanded = false }) {
                        DropdownMenuItem(text = { Text(if (language == "hi") "विषय की पढ़ाई पूरी" else "Subject completion") }, onClick = {
                            selectedAchievement = "SUBJECT_COMPLETION"; achievementMenuExpanded = false
                        })
                        DropdownMenuItem(text = { Text(if (language == "hi") "टेस्ट में पास" else "Assessment pass") }, onClick = {
                            selectedAchievement = "ASSESSMENT_PASS"; assessmentMenuExpanded = false; achievementMenuExpanded = false
                        })
                    }
                }

                Text(
                    if (language == "hi") "उपलब्धि का विवरण" else "Achievement details",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.secondary
                )
                if (selectedAchievement == "SUBJECT_COMPLETION") {
                    val selectedTrack = subjectTracks.firstOrNull { it.optString("track_key") == selectedTrackKey }
                    fun trackLabel(track: JSONObject?): String {
                        if (track == null) return if (language == "hi") "विषय ट्रैक चुनें" else "Choose a subject track"
                        val courseId = track.optLongValue("course_id", "courseId", "program_id", "programId")
                        val scopeLabel = if (courseId > 0L) track.optString("course_name").ifBlank {
                            if (language == "hi") "कोर्स" else "Course"
                        } else (if (language == "hi") "कक्षा " else "Class ") + track.optString("class_code")
                        val subject = localizedCertificateSubject(track.optString("subject_code"), track.optString("subject_name"), language)
                        val completed = track.optInt("completed")
                        val lessons = track.optInt("lessons")
                        val progressLabel = if (lessons > 0) "$completed / $lessons" + if (language == "hi") " पाठ पूरे" else " lessons complete" else ""
                        return listOf(scopeLabel, subject, progressLabel).filter { it.isNotBlank() }.joinToString(" · ")
                    }
                    if (subjectTracks.isEmpty()) Text(
                        if (language == "hi") "कोई सक्रिय विषय ट्रैक उपलब्ध नहीं है।" else "No active subject track available.",
                        color = MaterialTheme.colorScheme.secondary
                    )
                    Box(Modifier.fillMaxWidth()) {
                        OutlinedButton(onClick = { subjectMenuExpanded = true }, enabled = !loading && subjectTracks.isNotEmpty(), modifier = Modifier.fillMaxWidth()) {
                            Text(trackLabel(selectedTrack), modifier = Modifier.weight(1f), maxLines = 2)
                            Text("▾")
                        }
                        DropdownMenu(expanded = subjectMenuExpanded, onDismissRequest = { subjectMenuExpanded = false }) {
                            subjectTracks.forEach { track ->
                                DropdownMenuItem(text = { Text(trackLabel(track), maxLines = 2) }, onClick = {
                                    selectedTrackKey = track.optString("track_key"); subjectMenuExpanded = false
                                })
                            }
                        }
                    }
                    Text(
                        if (language == "hi") "इस सक्रिय ट्रैक के सभी प्रकाशित पाठ पूरे करें।" else "Complete all published lessons in this active track.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary
                    )
                } else {
                    val selectedAssessment = eligibleAssessments.firstOrNull {
                        it.optLongValue("attempt_id", "attemptId", "id").toString() == selectedAttemptId
                    }
                    fun assessmentLabel(item: JSONObject?): String {
                        if (item == null) return if (language == "hi") "पात्र जारी टेस्ट चुनें" else "Choose an eligible released test"
                        val trackName = localizedCertificateSubject(item.optString("subject_code"), item.optString("subject_name"), language)
                        val courseId = item.optLongValue("course_id", "courseId", "program_id", "programId")
                        val scopeLabel = if (courseId > 0L) item.optString("course_name") else
                            (if (language == "hi") "कक्षा " else "Class ") + item.optString("class_code")
                        val score = item.optString("final_score", item.optString("score", "—")) + "/" + item.optString("max_score", "—")
                        val status = when (item.optString("certificate_status")) {
                            "ISSUED" -> if (language == "hi") "प्रमाणपत्र जारी" else "Certificate issued"
                            "REVOKED" -> if (language == "hi") "प्रमाणपत्र निरस्त" else "Certificate revoked"
                            else -> ""
                        }
                        return listOf(scopeLabel, trackName, item.optString("assessment_title", item.optString("title", "Assessment")), score, status)
                            .filter { it.isNotBlank() }.joinToString(" · ")
                    }
                    if (eligibleAssessments.isEmpty()) Text(
                        if (language == "hi") "कोई पात्र जारी Admin-created टेस्ट परिणाम नहीं मिला।" else "No eligible released admin-created test results.",
                        color = MaterialTheme.colorScheme.secondary
                    )
                    Box(Modifier.fillMaxWidth()) {
                        OutlinedButton(onClick = { assessmentMenuExpanded = true }, enabled = !loading && eligibleAssessments.isNotEmpty(), modifier = Modifier.fillMaxWidth()) {
                            Text(assessmentLabel(selectedAssessment), modifier = Modifier.weight(1f), maxLines = 2)
                            Text("▾")
                        }
                        DropdownMenu(expanded = assessmentMenuExpanded, onDismissRequest = { assessmentMenuExpanded = false }) {
                            eligibleAssessments.forEach { item ->
                                DropdownMenuItem(text = { Text(assessmentLabel(item), maxLines = 3) }, onClick = {
                                    selectedAttemptId = item.optLongValue("attempt_id", "attemptId", "id").toString()
                                    assessmentMenuExpanded = false
                                })
                            }
                        }
                    }
                    Text(
                        if (language == "hi") "पास अंक वाले केवल जारी Admin-created टेस्ट पात्र हैं।"
                        else "Only released admin-created tests with passing scores qualify.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary
                    )
                }

                val selectedCertificateStatus = eligibleAssessments.firstOrNull {
                    it.optLongValue("attempt_id", "attemptId", "id").toString() == selectedAttemptId
                }?.optString("certificate_status").orEmpty()
                Button(
                    onClick = { issueCertificate() },
                    enabled = !busy && !loading && (
                        if (selectedAchievement == "SUBJECT_COMPLETION") subjectTracks.any { it.optString("track_key") == selectedTrackKey }
                        else selectedAttemptId.isNotBlank() && selectedCertificateStatus != "REVOKED"
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        if (busy) { if (language == "hi") "जाँच हो रही है…" else "Checking eligibility…" }
                        else if (selectedCertificateStatus == "ISSUED" && selectedAchievement == "ASSESSMENT_PASS") {
                            if (language == "hi") "मौजूदा प्रमाणपत्र खोलें" else "Open existing certificate"
                        } else if (selectedCertificateStatus == "REVOKED" && selectedAchievement == "ASSESSMENT_PASS") {
                            if (language == "hi") "प्रमाणपत्र निरस्त" else "Certificate revoked"
                        } else if (language == "hi") "पात्रता जाँचें और जारी करें" else "Verify eligibility & issue"
                    )
                }
            }
        }
        Text(if (language == "hi") "जारी किए गए प्रमाणपत्र" else "Issued certificates", style = MaterialTheme.typography.titleLarge)
        if (loading) CircularProgressIndicator()
        if (!loading && certificates.isEmpty()) {
            Text(if (language == "hi") "अभी कोई प्रमाणपत्र जारी नहीं हुआ है। पात्र उपलब्धि पूरी होने पर यहाँ दिखेगा।" else "No certificates issued yet. Earned certificates will appear here.")
        }
        certificates.forEach { certificate ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    Text(certificate.optString("achievement_title", certificate.optString("achievementTitle", "QuantaEdge achievement")), style = MaterialTheme.typography.titleMedium)
                    val certificateClass = certificate.optString("class_code")
                    val subject = localizedCertificateSubject(
                        certificate.optString("subject_code"), certificate.optString("subject_name"), language)
                    if (certificateClass.isNotBlank() && subject.isNotBlank()) {
                        Text((if (language == "hi") "कक्षा " else "Class ") + certificateClass + " · " + subject,
                            style = MaterialTheme.typography.bodyMedium)
                    }
                    Text(certificate.optString("certificate_number", certificate.optString("certificateNumber", "")), style = MaterialTheme.typography.bodySmall)
                    val rawStatus = certificate.optString("status", "ISSUED").uppercase()
                    val statusLabel = when (rawStatus) {
                        "ISSUED" -> if (language == "hi") "जारी" else "Issued"
                        "REVOKED" -> if (language == "hi") "निरस्त" else "Revoked"
                        else -> rawStatus.replace('_', ' ')
                    }
                    val rawLanguage = certificate.optString("language", language).lowercase()
                    val languageLabel = when (rawLanguage) {
                        "hi" -> if (language == "hi") "हिंदी" else "Hindi"
                        "en" -> if (language == "hi") "अंग्रेज़ी" else "English"
                        else -> rawLanguage.uppercase()
                    }
                    Text(
                        statusLabel + " · " +
                            certificate.optString("template_id", certificate.optString("templateId", "CLASSIC")) + " · " +
                            languageLabel,
                        color = if (rawStatus == "REVOKED") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.secondary
                    )
                    if (certificate.optString("status", "ISSUED") == "ISSUED") {
                        if (compact) {
                            Column(verticalArrangement = Arrangement.spacedBy(5.dp), modifier = Modifier.fillMaxWidth()) {
                                OutlinedButton(onClick = { viewCertificate(certificate) }, enabled = !busy,
                                    modifier = Modifier.fillMaxWidth()) {
                                    Text(if (language == "hi") "प्रमाणपत्र देखें" else "View certificate")
                                }
                                OutlinedButton(onClick = { downloadCertificate(certificate, "pdf") }, enabled = !busy,
                                    modifier = Modifier.fillMaxWidth()) { Text(localizedUiText("PDF")) }
                                OutlinedButton(onClick = { downloadCertificate(certificate, "png") }, enabled = !busy,
                                    modifier = Modifier.fillMaxWidth()) { Text(localizedUiText("PNG")) }
                            }
                        } else {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                                OutlinedButton(onClick = { viewCertificate(certificate) }, enabled = !busy,
                                    modifier = Modifier.weight(1f)) {
                                    Text(if (language == "hi") "देखें" else "View")
                                }
                                OutlinedButton(onClick = { downloadCertificate(certificate, "pdf") }, enabled = !busy,
                                    modifier = Modifier.weight(1f)) { Text(localizedUiText("PDF")) }
                                OutlinedButton(onClick = { downloadCertificate(certificate, "png") }, enabled = !busy,
                                    modifier = Modifier.weight(1f)) { Text(localizedUiText("PNG")) }
                            }
                        }
                    } else {
                        Text(if (language == "hi") "यह प्रमाणपत्र निरस्त है; मान्य प्रमाण के रूप में डाउनलोड नहीं किया जा सकता।" else "This certificate is revoked and cannot be downloaded as valid proof.", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    }
}

private fun org.json.JSONArray?.toObjectList(): List<JSONObject> =
    if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }

private fun JSONObject.optLongValue(vararg keys: String): Long {
    keys.forEach { key ->
        val value = optLong(key, 0L)
        if (value > 0L) return value
    }
    return 0L
}


/**
 * Accept only the public QuantaEdge verification URL, plus local HTTP URLs in debug builds.
 * Never trust a certificate number from an arbitrary QR payload.
 */
internal fun parseQuantaEdgeCertificateQr(raw: String, debugBuild: Boolean): String? {
    val input = raw.trim()
    val directCode = input.uppercase()
    if (directCode.matches(Regex("QE-[0-9]{4}-[A-F0-9]{16}"))) return directCode
    val uri = runCatching { URI(input) }.getOrNull() ?: return null
    val host = uri.host?.lowercase() ?: return null
    val production = uri.scheme.equals("https", ignoreCase = true) &&
        host == "qe.thinkwithsujeet.in" && (uri.port == -1 || uri.port == 443)
    val localDevelopment = debugBuild &&
        uri.scheme.equals("http", ignoreCase = true) &&
        host in setOf("localhost", "127.0.0.1") &&
        (uri.port == -1 || uri.port in 1..65535)
    if ((!production && !localDevelopment) || uri.rawUserInfo != null ||
        !uri.rawFragment.isNullOrEmpty()) return null

    // Accept both public QR links and the explicit verification API URL that can be
    // encoded in a linear barcode. Host/scheme validation happens before either route.
    val path = uri.path ?: return null
    val apiPrefix = "/api/v1/certificates/verify/"
    if (path.startsWith(apiPrefix)) {
        if (!uri.rawQuery.isNullOrEmpty()) return null
        val pathCode = path.removePrefix(apiPrefix).trim().uppercase()
        return pathCode.takeIf { it.matches(Regex("QE-[0-9]{4}-[A-F0-9]{16}")) }
    }
    if (path != "/certificate/verify") return null

    val query = uri.rawQuery ?: return null
    val codes = query.split("&").mapNotNull { pair ->
        val parts = pair.split("=", limit = 2)
        val key = runCatching { URLDecoder.decode(parts[0], StandardCharsets.UTF_8.name()) }.getOrNull()
        if (key != "code" || parts.size != 2) null
        else runCatching { URLDecoder.decode(parts[1], StandardCharsets.UTF_8.name()) }.getOrNull()
    }
    if (codes.size != 1) return null
    val code = codes.single().trim().uppercase()
    return code.takeIf { it.matches(Regex("QE-[0-9]{4}-[A-F0-9]{16}")) }
}
