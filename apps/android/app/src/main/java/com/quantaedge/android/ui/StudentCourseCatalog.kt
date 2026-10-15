package com.quantaedge.android.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.json.JSONObject

@Composable
fun StudentCourseCatalog(
    programs: List<JSONObject>,
    courseProgress: Map<Long, JSONObject>,
    language: String,
    busy: Boolean,
    onEnroll: (Long) -> Unit,
    onOpenSubject: (String, Long) -> Unit,
    onRefresh: () -> Unit
) {
    val hi = language == "hi"
    if (programs.isEmpty()) {
        Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(if (hi) "आपके courses" else "Your courses",
                style = MaterialTheme.typography.titleMedium)
            if (busy) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Text(if (hi) "Courses लोड हो रहे हैं…" else "Loading courses…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary)
            } else {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.fillMaxWidth().padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(if (hi) "अभी कोई course उपलब्ध नहीं है" else "No courses available yet",
                            style = MaterialTheme.typography.titleSmall)
                        Text(
                            if (hi) "प्रकाशित courses यहाँ दिखेंगे। फिर से जाँचने के लिए रिफ्रेश करें।"
                            else "Published courses will appear here. Refresh to check again.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        OutlinedButton(onClick = onRefresh, enabled = !busy,
                            modifier = Modifier.fillMaxWidth()) {
                            Text(if (hi) "Courses फिर जाँचें" else "Retry courses")
                        }
                    }
                }
            }
        }
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(if (hi) "आपके courses" else "Your courses",
            style = MaterialTheme.typography.titleMedium)
        programs.forEach { program ->
            val id = program.optLong("id", program.optLong("course_id"))
            val enrolled = program.optBoolean("is_enrolled", program.optBoolean("enrolled"))
            val subjects = program.optJSONArray("subjects")?.let { array ->
                (0 until array.length()).mapNotNull { index -> array.optJSONObject(index) }
            }.orEmpty()
            val progress = courseProgress[id]
            val stats = progress?.optJSONObject("lessonStats") ?: JSONObject()
            val completed = stats.optInt("completed_lessons", stats.optInt("completedLessons", 0))
            val total = stats.optInt("total_lessons", stats.optInt("totalLessons", 0))
            val percent = stats.optDouble("completion_percent",
                if (total > 0) completed * 100.0 / total else 0.0).toFloat().coerceIn(0f, 100f)
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.fillMaxWidth().padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(program.optString("display_name").ifBlank { "Course" },
                                style = MaterialTheme.typography.titleSmall)
                            Text(if (enrolled) { if (hi) "नामांकन सक्रिय" else "Enrolled" }
                                else { if (hi) "Join करने के लिए उपलब्ध" else "Available to join" },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (!enrolled) {
                            Button(onClick = { onEnroll(id) }, enabled = !busy && id > 0) {
                                Text(if (hi) "Join" else "Join")
                            }
                        }
                    }
                    if (enrolled) {
                        if (total > 0) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(completed.toString() + " / " + total +
                                    if (hi) " पाठ पूरे" else " lessons completed",
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.weight(1f))
                                Text(percent.toInt().toString() + "%",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary)
                            }
                            LinearProgressIndicator(progress = { percent / 100f }, modifier = Modifier.fillMaxWidth())
                        } else {
                            Text(if (progress == null) {
                                if (hi) "Progress load हो रही है…" else "Loading progress…"
                            } else {
                                if (hi) "अभी published lessons नहीं हैं।" else "No published lessons yet."
                            }, style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.secondary)
                        }
                        if (subjects.isNotEmpty()) {
                            Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                subjects.forEach { subject ->
                                    OutlinedButton(
                                        onClick = { onOpenSubject(
                                            subject.optString("subject_code", subject.optString("code")), id
                                        ) },
                                        enabled = !busy && id > 0
                                    ) {
                                        Text(subject.optString("subject_name", subject.optString("subject_code", "Subject")))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
