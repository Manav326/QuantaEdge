package com.quantaedge.android.ui

import android.app.Activity
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import com.google.android.gms.common.api.CommonStatusCodes
import com.quantaedge.android.BuildConfig

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.ui.Modifier
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

@Composable
fun StudentCertificatesScreen(language: String, initialAssessmentAttemptId: Long = 0L, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var certificates by remember { mutableStateOf(emptyList<JSONObject>()) }
    var eligibleAssessments by remember { mutableStateOf(emptyList<JSONObject>()) }
    var template by remember { mutableStateOf("CLASSIC") }
    var selectedAchievement by remember { mutableStateOf("SUBJECT_COMPLETION") }
    var subjectCode by remember { mutableStateOf("maths") }
    var selectedAttemptId by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf("") }
    var notice by remember { mutableStateOf("") }
    var pendingBytes by remember { mutableStateOf<ByteArray?>(null) }
    var pendingName by remember { mutableStateOf("quantaedge-certificate") }
    var verification by remember { mutableStateOf<JSONObject?>(null) }
    var verifyingQr by remember { mutableStateOf(false) }

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
        val activity = context as? Activity
        if (activity == null) {
            error = if (language == "hi") "कैमरा स्कैनर शुरू नहीं हो सका।" else "Could not start the QR scanner."
            return
        }
        error = ""
        notice = ""
        verification = null
        verifyingQr = true
        GmsBarcodeScanning.getClient(activity).startScan()
            .addOnSuccessListener { barcode ->
                val code = barcode.rawValue?.let { parseQuantaEdgeCertificateQr(it, BuildConfig.DEBUG) }
                if (code == null) {
                    verifyingQr = false
                    error = if (language == "hi") "यह मान्य QuantaEdge प्रमाणपत्र QR या बारकोड नहीं है।"
                    else "This is not a valid QuantaEdge certificate QR or barcode."
                } else {
                    verifyScannedCertificate(code)
                }
            }
            .addOnCanceledListener { verifyingQr = false }
            .addOnFailureListener { failure ->
                verifyingQr = false
                error = if (failure is com.google.android.gms.common.api.ApiException &&
                    failure.statusCode == CommonStatusCodes.CANCELED) "" else {
                    if (language == "hi") "QR स्कैन नहीं हो सका। कृपया दोबारा कोशिश करें।"
                    else "QR/barcode scanning failed. Please try again."
                }
            }
    }

    fun issueCertificate() {
        scope.launch {
            busy = true
            error = ""
            notice = ""
            try {
                val body = JSONObject()
                    .put("achievementType", selectedAchievement)
                    .put("templateId", template)
                    .put("language", language)
                if (selectedAchievement == "SUBJECT_COMPLETION") {
                    body.put("subjectCode", subjectCode)
                } else {
                    val attemptId = selectedAttemptId.toLongOrNull()
                        ?: throw IOException("पहले eligible released test चुनें।")
                    body.put("assessmentAttemptId", attemptId)
                }
                val issued = withContext(Dispatchers.IO) {
                    ApiClient.postObject("/api/v1/students/me/certificates/issue", body)
                }
                notice = "Certificate issue हो गया: " + issued.optString("certificate_number", issued.optString("certificateNumber", "जारी"))
                reload()
            } catch (e: Exception) {
                error = e.message ?: "Certificate जारी नहीं हो सका।"
            } finally {
                busy = false
            }
        }
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
                Text(if (language == "hi") "प्रमाणपत्र QR स्कैन करें" else "Scan a certificate QR",
                    style = MaterialTheme.typography.titleMedium)
                Text(if (language == "hi")
                    "किसी भी QuantaEdge प्रमाणपत्र का QR स्कैन करें। स्थिति सर्वर से जाँची जाएगी।"
                    else "Scan the QR or linear barcode on a QuantaEdge certificate. Its current status is checked with the server.",
                    style = MaterialTheme.typography.bodyMedium)
                Button(onClick = { scanCertificateQr() }, enabled = !verifyingQr && !busy) {
                    Text(if (verifyingQr) {
                        if (language == "hi") "जाँच हो रही है…" else "Checking…"
                    } else {
                        if (language == "hi") "QR स्कैनर खोलें" else "Scan QR / barcode"
                    })
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
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(if (language == "hi") "नई उपलब्धि का प्रमाणपत्र" else "Issue an achievement certificate", style = MaterialTheme.typography.titleMedium)
                Text(if (language == "hi") "डिज़ाइन चुनें" else "Choose a design")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("CLASSIC", "MODERN", "ACADEMIC").forEach { id ->
                        if (template == id) Button(onClick = { template = id }) { Text(id) }
                        else OutlinedButton(onClick = { template = id }) { Text(id) }
                    }
                }
                Text(if (language == "hi") "उपलब्धि का प्रकार" else "Achievement type")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (selectedAchievement == "SUBJECT_COMPLETION") Button(onClick = { selectedAchievement = "SUBJECT_COMPLETION" }) { Text(if (language == "hi") "विषय पूर्ण" else "Subject completion") }
                    else OutlinedButton(onClick = { selectedAchievement = "SUBJECT_COMPLETION" }) { Text(if (language == "hi") "विषय पूर्ण" else "Subject completion") }
                    if (selectedAchievement == "ASSESSMENT_PASS") Button(onClick = { selectedAchievement = "ASSESSMENT_PASS" }) { Text(if (language == "hi") "टेस्ट पास" else "Assessment pass") }
                    else OutlinedButton(onClick = { selectedAchievement = "ASSESSMENT_PASS" }) { Text(if (language == "hi") "टेस्ट पास" else "Assessment pass") }
                }
                if (selectedAchievement == "SUBJECT_COMPLETION") {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (subjectCode == "maths") Button(onClick = { subjectCode = "maths" }) { Text(if (language == "hi") "गणित" else "Maths") }
                        else OutlinedButton(onClick = { subjectCode = "maths" }) { Text(if (language == "hi") "गणित" else "Maths") }
                        if (subjectCode == "science") Button(onClick = { subjectCode = "science" }) { Text(if (language == "hi") "विज्ञान" else "Science") }
                        else OutlinedButton(onClick = { subjectCode = "science" }) { Text(if (language == "hi") "विज्ञान" else "Science") }
                    }
                } else {
                    if (eligibleAssessments.isEmpty()) {
                        Text(
                            if (language == "hi") "कोई पात्र result नहीं मिला। Test certificate के लिए admin-created test का result RELEASED होना चाहिए, pass mark पूरा होना चाहिए और subject enrollment active होना चाहिए। किसी specific test की वजह जानने के लिए उसका result खोलें।"
                            else "No eligible result found. A test certificate requires a released result from an admin-created test, a passing score and an active subject enrollment. Open a specific test result to see its eligibility reason.",
                            color = MaterialTheme.colorScheme.secondary
                        )
                    } else {
                        Text(if (language == "hi") "पात्र test चुनें" else "Select an eligible test")
                        eligibleAssessments.forEach { item ->
                            val id = item.optLongValue("attempt_id", "attemptId", "id").toString()
                            val title = item.optString("assessment_title", item.optString("title", "Assessment")) +
                                " · " + item.optString("final_score", item.optString("score", "—")) +
                                "/" + item.optString("max_score", "—")
                            if (selectedAttemptId == id) Button(onClick = { selectedAttemptId = id }, modifier = Modifier.fillMaxWidth()) { Text(title) }
                            else OutlinedButton(onClick = { selectedAttemptId = id }, modifier = Modifier.fillMaxWidth()) { Text(title) }
                        }
                    }
                }
                Button(onClick = { issueCertificate() }, enabled = !busy && !loading &&
                    (selectedAchievement != "ASSESSMENT_PASS" || selectedAttemptId.isNotBlank())) {
                    Text(if (busy) "…" else if (language == "hi") "पात्रता जाँचें और जारी करें" else "Verify eligibility & issue")
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
                    Text(certificate.optString("certificate_number", certificate.optString("certificateNumber", "")), style = MaterialTheme.typography.bodySmall)
                    Text(
                        (certificate.optString("status", "ISSUED")) + " · " +
                            certificate.optString("template_id", certificate.optString("templateId", "CLASSIC")) + " · " +
                            certificate.optString("language", language),
                        color = if (certificate.optString("status", "ISSUED") == "REVOKED") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.secondary
                    )
                    if (certificate.optString("status", "ISSUED") == "ISSUED") {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { downloadCertificate(certificate, "pdf") }, enabled = !busy) { Text("PDF") }
                            OutlinedButton(onClick = { downloadCertificate(certificate, "png") }, enabled = !busy) { Text("PNG") }
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
        uri.path != "/certificate/verify" || !uri.rawFragment.isNullOrEmpty()) return null

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
