package com.quantaedge.android.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.json.JSONObject

@Composable
fun StudentAllCoursesScreen(
    programs: List<JSONObject>,
    programsLoaded: Boolean,
    programsLoadFailed: Boolean,
    courseProgress: Map<Long, JSONObject>,
    activeCourseId: Long,
    language: String,
    busy: Boolean,
    onActivateCourse: (Long) -> Unit,
    onOpenSubject: (String, Long) -> Unit,
    onOpenAssessments: (Long) -> Unit,
    onMarketplace: () -> Unit,
    onRefresh: () -> Unit
) {
    val hi = language == "hi"
    val owned = programs.filter { it.optBoolean("is_enrolled", it.optBoolean("enrolled")) || it.optBoolean("paused") }
    Column(
        modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(if (hi) "आपके courses" else "All courses", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text(
                if (hi) "यहाँ आपके लिए उपलब्ध सभी enrolled courses हैं। किसी course को चुनने पर पढ़ाई उसी के अनुसार होगी।"
                else "Your enrolled courses in one place. Choose a course to focus your learning on it.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (owned.isEmpty()) {
            if (!programsLoaded && !programsLoadFailed) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 18.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    androidx.compose.material3.CircularProgressIndicator()
                    Text(localizedUiText("Loading your courses…"),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else if (programsLoadFailed) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(if (hi) "Courses लोड नहीं हो पाए" else "Courses couldn't be loaded",
                            style = MaterialTheme.typography.titleMedium)
                        Text(if (hi) "कनेक्शन जाँचें और फिर प्रयास करें।" else "Check your connection and retry.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        OutlinedButton(onClick = onRefresh, enabled = !busy) {
                            Text(if (hi) "फिर प्रयास करें" else "Retry")
                        }
                    }
                }
            } else {
                StudentEnrollmentEmptyState(
                    icon = "▦",
                    title = localizedUiText("No courses in your account yet"),
                    message = localizedUiText("Explore the Marketplace to find a course. Once enrolment is confirmed, your lessons, practice, tests and progress will appear here."),
                    primaryLabel = localizedUiText("Explore Marketplace"),
                    onPrimary = onMarketplace
                )
            }
        } else {
            owned.forEach { course ->
                val id = course.optLong("id", course.optLong("course_id"))
                val active = id == activeCourseId
                val assessmentCourse = course.optString("course_type_code") == "ASSESSMENT" ||
                    course.optString("delivery_mode") == "ASSESSMENT_ONLY"
                val subjects = course.optJSONArray("subjects")?.let { array ->
                    (0 until array.length()).mapNotNull { index -> array.optJSONObject(index) }
                }.orEmpty()
                val progress = courseProgress[id]
                val stats = progress?.optJSONObject("lessonStats") ?: JSONObject()
                val done = stats.optInt("completed_lessons", stats.optInt("completedLessons", 0))
                val total = stats.optInt("total_lessons", stats.optInt("totalLessons", 0))
                val pct = stats.optDouble("completion_percent", if (total > 0) done * 100.0 / total else 0.0)
                    .toFloat().coerceIn(0f, 100f)
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(course.optString("display_name").ifBlank { "Course" },
                                    style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                Text(
                                    course.optString("course_tag").ifBlank {
                                        course.optString("tag").ifBlank { course.optString("curriculum_version").ifBlank { if (hi) "आपका course" else "Your course" } }
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Surface(
                                color = if (active) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                                shape = MaterialTheme.shapes.small
                            ) {
                                Text(
                                    if (active) { if (hi) "सीख रहे हैं" else "Learning now" } else "Enrolled",
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (active) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        if (assessmentCourse) {
                            Text(
                                if (hi) "इस course में test packs, attempts और results उपलब्ध हैं।"
                                else "Test packs, attempts and results are available in this course.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        } else if (total > 0) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(if (hi) "$done / $total lessons पूरे" else "$done of $total lessons completed",
                                    modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text("${pct.toInt()}%", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium)
                            }
                            LinearProgressIndicator(progress = { pct / 100f }, modifier = Modifier.fillMaxWidth())
                        }
                        if (!active) {
                            OutlinedButton(
                                onClick = { onActivateCourse(id) },
                                enabled = !busy && id > 0L,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(if (hi) "इस course पर पढ़ें" else "Focus on this course")
                            }
                        }
                        if (assessmentCourse) {
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                            Text(if (hi) "परीक्षा सामग्री" else "Assessment content", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                            Row(
                                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(enabled = !busy && id > 0L) {
                                    onOpenAssessments(id)
                                }.padding(vertical = 7.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text(if (hi) "Tests खोलें" else "Open tests", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                                    Text(if (hi) "Test catalogue, attempts और results" else "Test catalogue, attempts and results",
                                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Text("→", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.titleMedium)
                            }
                        } else if (subjects.isNotEmpty()) {
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                            Text(if (hi) "विषय" else "Subjects", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                            subjects.forEach { subject ->
                                val code = subject.optString("subject_code", subject.optString("code"))
                                Row(
                                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(enabled = !busy && id > 0L) {
                                        onOpenSubject(code, id)
                                    }.padding(vertical = 7.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                        Text(subject.optString("subject_name", subject.optString("subject_code", "Subject")), style = MaterialTheme.typography.bodyMedium)
                                        Text(subject.optInt("lessons", 0).toString() + " lessons",
                                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    Text("→", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.titleMedium)
                                }
                            }
                        } else if (!assessmentCourse) {
                            Text(if (hi) "विषयों की सूची उपलब्ध नहीं है।" else "Subjects are not available for this course yet.",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
        Button(onClick = onMarketplace, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
            Text(if (hi) "और courses खोजें · Marketplace" else "Explore more courses · Marketplace")
        }
    }
}
