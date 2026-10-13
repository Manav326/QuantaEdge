package com.quantaedge.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import org.json.JSONObject

@Composable
fun StudentProgressScreen(
    data: JSONObject,
    language: String,
    busy: Boolean,
    certificateEligibility: Map<Long, JSONObject>,
    onRefresh: () -> Unit,
    onIssueCertificate: (Long) -> Unit,
    onViewCertificate: (Long) -> Unit,
    onDownloadCertificate: (Long, String) -> Unit
) {
    fun t(en: String, hi: String) = if (language == "hi") hi else en
    val compact = LocalConfiguration.current.screenWidthDp < 390
    val lessons = data.optJSONObject("lessonStats") ?: JSONObject()
    val questions = data.optJSONObject("questionStats") ?: JSONObject()
    val mastery = data.optJSONObject("mastery") ?: JSONObject()
    val assessments = data.optJSONObject("assessmentStats") ?: JSONObject()
    val sessions = data.optJSONObject("sessionStats") ?: JSONObject()
    val curriculum = data.optJSONArray("curriculum")?.objectItems() ?: emptyList()
    val concepts = data.optJSONArray("masteryDetails")?.objectItems() ?: emptyList()
    val recentTests = data.optJSONArray("recentAssessments")?.objectItems() ?: emptyList()

    Column(modifier = Modifier.fillMaxSize().widthIn(max = 1000.dp).padding(14.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(t("My learning progress", "मेरी सीखने की प्रगति"),
                style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
            TextButton(onClick = onRefresh, enabled = !busy) { Text(t("Refresh", "रिफ्रेश")) }
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 6.dp)) {
            item {
                if (compact) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        MetricCard(t("Lessons completed", "पाठ पूरे"),
                            lessons.optString("completed_lessons", "0") + " / " + lessons.optString("total_lessons", "0"),
                            lessons.optString("completion_percent", "0") + "% " + t("complete", "पूरे"), Modifier.fillMaxWidth())
                        MetricCard(t("Practice accuracy", "अभ्यास की सटीकता"),
                            questions.optString("accuracy_percent", "0") + "%",
                            questions.optString("attempts", "0") + " " + t("attempts", "प्रयास"), Modifier.fillMaxWidth())
                    }
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        MetricCard(t("Lessons completed", "पाठ पूरे"),
                            lessons.optString("completed_lessons", "0") + " / " + lessons.optString("total_lessons", "0"),
                            lessons.optString("completion_percent", "0") + "% " + t("complete", "पूरे"), Modifier.weight(1f))
                        MetricCard(t("Practice accuracy", "अभ्यास की सटीकता"),
                            questions.optString("accuracy_percent", "0") + "%",
                            questions.optString("attempts", "0") + " " + t("attempts", "प्रयास"), Modifier.weight(1f))
                    }
                }
            }
            item {
                if (compact) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        MetricCard(t("Concept mastery", "अवधारणाओं पर पकड़"),
                            mastery.optString("average_mastery", "0") + "%",
                            mastery.optString("mastered", "0") + " " + t("concepts mastered", "अवधारणाएँ पूरी"), Modifier.fillMaxWidth())
                        MetricCard(t("Tests", "टेस्ट"),
                            assessments.optString("released", "0") + " " + t("released", "जारी"),
                            assessments.optString("average_released_percent", "0") + "% " + t("average score", "औसत अंक"), Modifier.fillMaxWidth())
                    }
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        MetricCard(t("Concept mastery", "अवधारणाओं पर पकड़"),
                            mastery.optString("average_mastery", "0") + "%",
                            mastery.optString("mastered", "0") + " " + t("concepts mastered", "अवधारणाएँ पूरी"), Modifier.weight(1f))
                        MetricCard(t("Tests", "टेस्ट"),
                            assessments.optString("released", "0") + " " + t("released", "जारी"),
                            assessments.optString("average_released_percent", "0") + "% " + t("average score", "औसत अंक"), Modifier.weight(1f))
                    }
                }
            }
            item {
                MetricCard(t("Last 30 days", "पिछले 30 दिन"),
                    sessions.optString("sessions_30d", "0") + " " + t("learning sessions", "सीखने के सत्र"),
                    sessions.optString("minutes_30d", "0") + " " + t("minutes tracked", "ट्रैक किए गए मिनट"), Modifier.fillMaxWidth())
            }
            item { Text(t("Subject progress", "विषयवार प्रगति"), style = MaterialTheme.typography.titleLarge) }
            if (curriculum.isEmpty()) item {
                EmptyState(t("No subject progress yet", "अभी विषयवार प्रगति नहीं"),
                    t("Statistics will appear when published lessons and subject enrolments for your class are available.",
                        "आपकी कक्षा के प्रकाशित पाठ और विषय enrollment उपलब्ध होने पर आँकड़े यहाँ दिखेंगे।"))
            }
            items(curriculum) { row ->
                val percent = row.optDouble("completion_percent", 0.0).toFloat().coerceIn(0f, 100f)
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(row.textAny("subject_name", "subject_code"), style = MaterialTheme.typography.titleMedium)
                        Text(row.optString("completed", "0") + " / " + row.optString("lessons", "0") + " " +
                            t("lessons", "पाठ") + " • " + row.optString("chapters", "0") + " " + t("chapters", "अध्याय"),
                            style = MaterialTheme.typography.bodySmall)
                        LinearProgressIndicator(progress = { percent / 100f }, modifier = Modifier.fillMaxWidth())
                        Text(percent.toInt().toString() + "% " + t("complete", "पूरे"), style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
            item { Text(t("Concepts to revisit", "जिन अवधारणाओं पर फिर काम कर सकते हैं"), style = MaterialTheme.typography.titleLarge) }
            if (concepts.isEmpty()) item {
                Text(t("No concept mastery is available yet. It will appear after practicing questions from published lessons.",
                    "अभी अवधारणाओं पर पकड़ की जानकारी उपलब्ध नहीं है। प्रकाशित पाठ के प्रश्नों का अभ्यास करने पर यह यहाँ दिखाई देगी।"),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.secondary)
            }
            items(concepts) { concept ->
                val value = concept.optDouble("mastery_percent", 0.0).toFloat().coerceIn(0f, 100f)
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text(concept.textAny("concept_title"), style = MaterialTheme.typography.titleSmall)
                        Text(concept.textAny("subject_name", "chapter_name"), style = MaterialTheme.typography.bodySmall)
                        LinearProgressIndicator(progress = { value / 100f }, modifier = Modifier.fillMaxWidth())
                        Text(value.toInt().toString() + "% " + t("mastery", "महारत") + " • " +
                            concept.optString("attempts", "0") + " " + t("attempts", "प्रयास"), style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
            item { Text(t("Recent tests", "हाल के टेस्ट"), style = MaterialTheme.typography.titleLarge) }
            if (recentTests.isEmpty()) item {
                Text(t("No test attempts yet.", "अभी कोई टेस्ट प्रयास नहीं है。"),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.secondary)
            }
            items(recentTests) { attempt ->
                val attemptId = attempt.longAny("attempt_id", "attemptId")
                val eligibility = certificateEligibility[attemptId]
                val certificateStatus = eligibility?.textAny("certificate_status", "certificateStatus", "status")?.uppercase().orEmpty()
                val certificateId = eligibility?.longAny("certificate_id", "certificateId", "id") ?: 0L
                val status = attempt.textAny("status").uppercase()
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(attempt.textAny("assessment_title"), style = MaterialTheme.typography.titleSmall)
                        Text(attempt.textAny("subject_name", "chapter_name"), style = MaterialTheme.typography.bodySmall)
                        val displayStatus = when (status) {
                            "RELEASED" -> t("Result released", "परिणाम जारी")
                            "SUBMITTED" -> t("Submitted", "जमा किया गया")
                            "PENDING_REVIEW", "AWAITING_REVIEW" -> t("Awaiting review", "समीक्षा की प्रतीक्षा")
                            "GRADED" -> t("Graded · awaiting release", "जाँचा गया · जारी होना बाकी")
                            "IN_PROGRESS" -> t("In progress", "जारी है")
                            else -> attempt.textAny("status").replace('_', ' ')
                        }
                        Text(t("Status: ", "स्थिति: ") + displayStatus + " • " +
                            t("Score: ", "अंक: ") + attempt.textAny("final_score", "auto_score") + " / " +
                            attempt.textAny("max_score"), style = MaterialTheme.typography.bodyMedium)
                        when {
                            certificateStatus == "ISSUED" && certificateId > 0L -> {
                                Text(t("Certificate issued", "प्रमाणपत्र जारी"), color = MaterialTheme.colorScheme.primary,
                                    style = MaterialTheme.typography.labelMedium)
                                if (compact) {
                                    Column(verticalArrangement = Arrangement.spacedBy(5.dp), modifier = Modifier.fillMaxWidth()) {
                                        OutlinedButton(onClick = { onViewCertificate(certificateId) }, enabled = !busy,
                                            modifier = Modifier.fillMaxWidth()) { Text(t("View certificate", "प्रमाणपत्र देखें")) }
                                        OutlinedButton(onClick = { onDownloadCertificate(certificateId, "pdf") }, enabled = !busy,
                                            modifier = Modifier.fillMaxWidth()) { Text(t("Save PDF", "PDF सेव करें")) }
                                        OutlinedButton(onClick = { onDownloadCertificate(certificateId, "png") }, enabled = !busy,
                                            modifier = Modifier.fillMaxWidth()) { Text(t("Save PNG", "PNG सेव करें")) }
                                    }
                                } else {
                                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                                        OutlinedButton(onClick = { onViewCertificate(certificateId) }, enabled = !busy,
                                            modifier = Modifier.weight(1f)) { Text(t("View", "देखें")) }
                                        OutlinedButton(onClick = { onDownloadCertificate(certificateId, "pdf") }, enabled = !busy,
                                            modifier = Modifier.weight(1f)) { Text(t("Save PDF", "PDF सेव करें")) }
                                        OutlinedButton(onClick = { onDownloadCertificate(certificateId, "png") }, enabled = !busy,
                                            modifier = Modifier.weight(1f)) { Text(t("Save PNG", "PNG सेव करें")) }
                                    }
                                }
                            }
                            certificateStatus == "REVOKED" || eligibility?.textAny("eligibility_reason") == "CERTIFICATE_REVOKED" -> {
                                Text(t("This certificate has been revoked.", "यह प्रमाणपत्र निरस्त है。"),
                                    color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                            }
                            status == "RELEASED" && eligibility?.optBoolean("eligible", false) == true && attemptId > 0L -> {
                                Button(onClick = { onIssueCertificate(attemptId) }, enabled = !busy,
                                    modifier = Modifier.fillMaxWidth()) { Text(t("Issue certificate", "प्रमाणपत्र जारी करें")) }
                            }
                            status == "RELEASED" -> {
                                val reason = eligibility?.textAny("eligibility_reason").orEmpty()
                                val hint = when (reason) {
                                    "ADMIN_CREATED_TEST_ONLY" -> t("Only admin-created tests qualify; generated tests and practice are excluded.",
                                        "केवल admin-created tests पात्र हैं; generated tests और practice शामिल नहीं हैं।")
                                    "SUBJECT_COMPLETION_REQUIRED" -> t("Complete all published lessons in this subject to unlock the test certificate.",
                                        "टेस्ट प्रमाणपत्र पाने के लिए इस विषय के सभी प्रकाशित पाठ पूरे करें।")
                                    "PASS_THRESHOLD_NOT_MET" -> t("The released score is below the " +
                                        (eligibility?.textAny("pass_threshold_percent").ifBlank { "80" }) + "% certificate threshold.",
                                        "जारी अंक " + (eligibility?.textAny("pass_threshold_percent").ifBlank { "80" }) + "% प्रमाणपत्र सीमा से कम हैं।")
                                    "PUBLISHED_LESSONS_NOT_AVAILABLE" -> t("Published lessons are not available for this subject yet.",
                                        "इस विषय के प्रकाशित पाठ अभी उपलब्ध नहीं हैं।")
                                    "SUBJECT_TRACK_NOT_ACTIVE" -> t("An active subject enrolment is required.",
                                        "इस विषय में सक्रिय नामांकन आवश्यक है।")
                                    else -> t("Certificate eligibility could not be checked; refresh to retry.",
                                        "प्रमाणपत्र पात्रता जाँची नहीं जा सकी; दोबारा रिफ्रेश करें।")
                                }
                                Text(hint, color = MaterialTheme.colorScheme.secondary, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MetricCard(title: String, value: String, detail: String, modifier: Modifier) {
    Card(modifier = modifier) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(title, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.secondary)
            Text(value, style = MaterialTheme.typography.titleLarge)
            Text(detail, style = MaterialTheme.typography.bodySmall)
        }
    }
}
