package com.quantaedge.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.quantaedge.android.data.ApiClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * One reusable result-side certificate action. Eligibility is resolved by the
 * server, and the certificates screen owns template selection, issue and download.
 */
@Composable
fun CertificateResultActions(
    attemptId: Long,
    assessmentType: String,
    isGenerated: Boolean,
    status: String,
    language: String,
    onOpenCertificates: (Long) -> Unit
) {
    if (attemptId <= 0L || assessmentType != "ADMIN_CREATED" || isGenerated || status != "RELEASED") return

    var eligibility by remember(attemptId) { mutableStateOf<JSONObject?>(null) }
    var loading by remember(attemptId) { mutableStateOf(false) }

    LaunchedEffect(attemptId) {
        loading = true
        eligibility = try {
            withContext(Dispatchers.IO) {
                ApiClient.getObject("/api/v1/students/me/certificates/assessment-attempts/" + attemptId)
            }
        } catch (_: Exception) {
            null
        } finally {
            loading = false
        }
    }

    val response = eligibility ?: return
    val certificate = response.optJSONObject("certificate")
    val hasCertificate = certificate != null
    val canIssue = response.optBoolean("canIssue")
    if (!canIssue && !hasCertificate) return

    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    if (language == "hi") "प्रमाणपत्र" else "Certificate",
                    style = MaterialTheme.typography.titleSmall
                )
                Text(
                    if (hasCertificate) {
                        val number = certificate?.optString("certificate_number").orEmpty()
                        if (number.isNotBlank()) number else if (language == "hi") "प्रमाणपत्र जारी है" else "Certificate issued"
                    } else {
                        val score = response.optString("scorePercent", "—")
                        val threshold = response.optString("passThresholdPercent", "80")
                        if (language == "hi") "अंक: $score% · आवश्यक: $threshold%" else "Score: $score% · Required: $threshold%"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary
                )
            }
            Button(onClick = { onOpenCertificates(attemptId) }, enabled = !loading) {
                Text(
                    when {
                        language == "hi" && hasCertificate -> "देखें / डाउनलोड"
                        language == "hi" -> "प्रमाणपत्र जारी करें"
                        hasCertificate -> "View / download"
                        else -> "Issue certificate"
                    }
                )
            }
        }
    }
}
