package com.quantaedge.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.json.JSONObject

@Composable
fun StudentProgressScreen(data: JSONObject, busy: Boolean, onRefresh: () -> Unit) {
    val lessons = data.optJSONObject("lessonStats") ?: JSONObject()
    val questions = data.optJSONObject("questionStats") ?: JSONObject()
    val mastery = data.optJSONObject("mastery") ?: JSONObject()
    val assessments = data.optJSONObject("assessmentStats") ?: JSONObject()
    val sessions = data.optJSONObject("sessionStats") ?: JSONObject()
    val curriculum = data.optJSONArray("curriculum")?.objectItems() ?: emptyList()
    val concepts = data.optJSONArray("masteryDetails")?.objectItems() ?: emptyList()
    val recentTests = data.optJSONArray("recentAssessments")?.objectItems() ?: emptyList()

    Column(modifier = Modifier.fillMaxSize().padding(14.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("मेरी सीखने की प्रगति", style = MaterialTheme.typography.headlineSmall)
            TextButton(onClick = onRefresh, enabled = !busy) { Text("रिफ्रेश") }
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 6.dp)) {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    MetricCard("पाठ पूरे", lessons.optString("completed_lessons", "0") + " / " + lessons.optString("total_lessons", "0"),
                        lessons.optString("completion_percent", "0") + "% completion", Modifier.weight(1f))
                    MetricCard("Accuracy", questions.optString("accuracy_percent", "0") + "%",
                        questions.optString("attempts", "0") + " attempts", Modifier.weight(1f))
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    MetricCard("Concept mastery", mastery.optString("average_mastery", "0") + "%",
                        mastery.optString("mastered", "0") + " concepts mastered", Modifier.weight(1f))
                    MetricCard("Tests", assessments.optString("released", "0") + " released",
                        assessments.optString("average_released_percent", "0") + "% average score", Modifier.weight(1f))
                }
            }
            item {
                MetricCard("पिछले 30 दिन", sessions.optString("sessions_30d", "0") + " learning sessions",
                    sessions.optString("minutes_30d", "0") + " मिनट tracked", Modifier.fillMaxWidth())
            }
            item { Text("विषयवार प्रगति", style = MaterialTheme.typography.titleLarge) }
            if (curriculum.isEmpty()) item {
                EmptyState("अभी विषयवार प्रगति नहीं", "आपकी कक्षा के प्रकाशित पाठ और विषय enrollment उपलब्ध होने पर आँकड़े यहाँ दिखेंगे।")
            }
            items(curriculum) { row ->
                val percent = row.optDouble("completion_percent", 0.0).toFloat().coerceIn(0f, 100f)
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(row.textAny("subject_name", "subject_code"), style = MaterialTheme.typography.titleMedium)
                        Text(row.optString("completed", "0") + " / " + row.optString("lessons", "0") + " पाठ • " +
                            row.optString("chapters", "0") + " अध्याय", style = MaterialTheme.typography.bodySmall)
                        LinearProgressIndicator(progress = { percent / 100f }, modifier = Modifier.fillMaxWidth())
                        Text(percent.toInt().toString() + "% complete", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
            item { Text("जिन concepts पर फिर काम कर सकते हैं", style = MaterialTheme.typography.titleLarge) }
            if (concepts.isEmpty()) item {
                Text("अभी concept mastery उपलब्ध नहीं है। प्रकाशित पाठ के प्रश्नों का अभ्यास करने पर यहाँ दिखाई देगी।",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.secondary)
            }
            items(concepts) { item ->
                val value = item.optDouble("mastery_percent", 0.0).toFloat().coerceIn(0f, 100f)
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text(item.textAny("concept_title"), style = MaterialTheme.typography.titleSmall)
                        Text(item.textAny("subject_name", "chapter_name"), style = MaterialTheme.typography.bodySmall)
                        LinearProgressIndicator(progress = { value / 100f }, modifier = Modifier.fillMaxWidth())
                        Text(value.toInt().toString() + "% mastery • " + item.optString("attempts", "0") + " attempts",
                            style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
            item { Text("हाल के टेस्ट", style = MaterialTheme.typography.titleLarge) }
            if (recentTests.isEmpty()) item {
                Text("अभी कोई टेस्ट attempt नहीं है।", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.secondary)
            }
            items(recentTests) { item ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(item.textAny("assessment_title"), style = MaterialTheme.typography.titleSmall)
                        Text(item.textAny("subject_name", "chapter_name"), style = MaterialTheme.typography.bodySmall)
                        Text("स्थिति: " + item.textAny("status") + " • अंक: " + item.textAny("final_score", "auto_score") +
                            " / " + item.textAny("max_score"), style = MaterialTheme.typography.bodyMedium)
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
