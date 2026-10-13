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
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import org.json.JSONObject

@Composable
fun StudentProgressScreen(data: JSONObject, language: String, busy: Boolean, onRefresh: () -> Unit) {
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

    Column(
        modifier = Modifier.fillMaxSize().widthIn(max = 1000.dp).padding(14.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(t("My learning progress", "मेरी सीखने की प्रगति"),
                style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
            TextButton(onClick = onRefresh, enabled = !busy) { Text(t("Refresh", "रिफ्रेश")) }
        }
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.padding(top = 6.dp)
        ) {
            item {
                if (compact) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        MetricCard(t("Lessons completed", "पाठ पूरे"),
                            lessons.optString("completed_lessons", "0") + " / " + lessons.optString("total_lessons", "0"),
                            lessons.optString("completion_percent", "0") + "% " + t("complete", "पूरे"),
                            Modifier.fillMaxWidth())
                        MetricCard(t("Practice accuracy", "अभ्यास की सटीकता"),
                            questions.optString("accuracy_percent", "0") + "%",
                            questions.optString("attempts", "0") + " " + t("attempts", "प्रयास"),
                            Modifier.fillMaxWidth())
                    }
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        MetricCard(t("Lessons completed", "पाठ पूरे"),
                            lessons.optString("completed_lessons", "0") + " / " + lessons.optString("total_lessons", "0"),
                            lessons.optString("completion_percent", "0") + "% " + t("complete", "पूरे"),
                            Modifier.weight(1f))
                        MetricCard(t("Practice accuracy", "अभ्यास की सटीकता"),
                            questions.optString("accuracy_percent", "0") + "%",
                            questions.optString("attempts", "0") + " " + t("attempts", "प्रयास"),
                            Modifier.weight(1f))
                    }
                }
            }
            item {
                if (compact) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        MetricCard(t("Concept mastery", "अवधारणाओं पर पकड़"),
                            mastery.optString("average_mastery", "0") + "%",
                            mastery.optString("mastered", "0") + " " + t("concepts mastered", "अवधारणाएँ पूरी"),
                            Modifier.fillMaxWidth())
                        MetricCard(t("Tests", "टेस्ट"),
                            assessments.optString("released", "0") + " " + t("released", "जारी"),
                            assessments.optString("average_released_percent", "0") + "% " + t("average score", "औसत अंक"),
                            Modifier.fillMaxWidth())
                    }
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        MetricCard(t("Concept mastery", "अवधारणाओं पर पकड़"),
                            mastery.optString("average_mastery", "0") + "%",
                            mastery.optString("mastered", "0") + " " + t("concepts mastered", "अवधारणाएँ पूरी"),
                            Modifier.weight(1f))
                        MetricCard(t("Tests", "टेस्ट"),
                            assessments.optString("released", "0") + " " + t("released", "जारी"),
                            assessments.optString("average_released_percent", "0") + "% " + t("average score", "औसत अंक"),
                            Modifier.weight(1f))
                    }
                }
            }
            item {
                MetricCard(t("Last 30 days", "पिछले 30 दिन"),
                    sessions.optString("sessions_30d", "0") + " " + t("learning sessions", "सीखने के सत्र"),
                    sessions.optString("minutes_30d", "0") + " " + t("minutes tracked", "ट्रैक किए गए मिनट"),
                    Modifier.fillMaxWidth())
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
                        Text(
                            row.optString("completed", "0") + " / " + row.optString("lessons", "0") + " " +
                                t("lessons", "पाठ") + " • " + row.optString("chapters", "0") + " " + t("chapters", "अध्याय"),
                            style = MaterialTheme.typography.bodySmall
                        )
                        LinearProgressIndicator(progress = { percent / 100f }, modifier = Modifier.fillMaxWidth())
                        Text(percent.toInt().toString() + "% " + t("complete", "पूरे"), style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
            item { Text(t("Concepts to revisit", "जिन अवधारणाओं पर फिर काम कर सकते हैं"), style = MaterialTheme.typography.titleLarge) }
            if (concepts.isEmpty()) item {
                Text(
                    t("No concept mastery is available yet. It will appear after practicing questions from published lessons.",
                        "अभी अवधारणाओं पर पकड़ की जानकारी उपलब्ध नहीं है। प्रकाशित पाठ के प्रश्नों का अभ्यास करने पर यह यहाँ दिखाई देगी।"),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.secondary
                )
            }
            items(concepts) { item ->
                val value = item.optDouble("mastery_percent", 0.0).toFloat().coerceIn(0f, 100f)
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text(item.textAny("concept_title"), style = MaterialTheme.typography.titleSmall)
                        Text(item.textAny("subject_name", "chapter_name"), style = MaterialTheme.typography.bodySmall)
                        LinearProgressIndicator(progress = { value / 100f }, modifier = Modifier.fillMaxWidth())
                        Text(value.toInt().toString() + "% " + t("mastery", "महारत") + " • " +
                            item.optString("attempts", "0") + " " + t("attempts", "प्रयास"),
                            style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
            item { Text(t("Recent tests", "हाल के टेस्ट"), style = MaterialTheme.typography.titleLarge) }
            if (recentTests.isEmpty()) item {
                Text(t("No test attempts yet.", "अभी कोई टेस्ट प्रयास नहीं है।"),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.secondary)
            }
            items(recentTests) { item ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(item.textAny("assessment_title"), style = MaterialTheme.typography.titleSmall)
                        Text(item.textAny("subject_name", "chapter_name"), style = MaterialTheme.typography.bodySmall)
                        val rawStatus = item.textAny("status")
                        val displayStatus = when (rawStatus.uppercase()) {
                            "RELEASED" -> t("Result released", "परिणाम जारी")
                            "SUBMITTED" -> t("Submitted", "जमा किया गया")
                            "PENDING_REVIEW", "AWAITING_REVIEW" -> t("Awaiting review", "समीक्षा की प्रतीक्षा")
                            "IN_PROGRESS" -> t("In progress", "जारी है")
                            else -> rawStatus.replace('_', ' ').lowercase().replaceFirstChar { it.uppercase() }
                        }
                        Text(
                            t("Status: ", "स्थिति: ") + displayStatus + " • " +
                                t("Score: ", "अंक: ") + item.textAny("final_score", "auto_score") + " / " +
                                item.textAny("max_score"),
                            style = MaterialTheme.typography.bodyMedium
                        )
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
