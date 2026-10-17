package com.quantaedge.android.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.json.JSONObject

/**
 * Dedicated enrolled-course destination. The dashboard stays focused on the active
 * course; this route is the authoritative place to switch between enrolled courses.
 * Discoverable offerings belong in Marketplace and are not presented as owned here.
 */
@Composable
internal fun StudentCoursesScreen(
    programs: List<JSONObject>,
    programsLoadFailed: Boolean,
    courseProgress: Map<Long, JSONObject>,
    activeCourseId: Long,
    language: String,
    busy: Boolean,
    onRefresh: () -> Unit,
    onSelectActiveCourse: (Long) -> Unit,
    onOpenSubject: (String, Long) -> Unit,
    onExploreMarketplace: () -> Unit
) {
    val hi = language == "hi"
    val enrolled = programs.filter { it.optBoolean("is_enrolled", it.optBoolean("enrolled")) }
    val active = enrolled.firstOrNull { it.optLong("id", it.optLong("course_id")) == activeCourseId }

    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(if (hi) "आपके courses" else "All courses",
                style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onSurface)
            Text(
                if (hi) "अपना सीखने का course चुनें। हर course की subjects और progress अलग रहती हैं।"
                else "Choose your learning course. Subjects and progress stay separate for each course.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onExploreMarketplace, enabled = !busy, modifier = Modifier.weight(1f)) {
                Text(if (hi) "Marketplace देखें" else "Explore marketplace")
            }
            TextButton(onClick = onRefresh, enabled = !busy) {
                Text("↻", style = MaterialTheme.typography.titleMedium)
            }
        }

        if (programsLoadFailed && programs.isEmpty()) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(if (hi) "Courses लोड नहीं हो पाए" else "Courses could not be loaded",
                        style = MaterialTheme.typography.titleMedium)
                    Text(if (hi) "कनेक्शन जाँचकर फिर प्रयास करें।" else "Check your connection and try again.",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    OutlinedButton(onClick = onRefresh, enabled = !busy) {
                        Text(if (hi) "फिर प्रयास करें" else "Try again")
                    }
                }
            }
        } else if (enrolled.isEmpty()) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(if (hi) "अभी कोई course जुड़ा नहीं है" else "No courses yet",
                        style = MaterialTheme.typography.titleMedium)
                    Text(
                        if (hi) "Marketplace से उपलब्ध courses देखें।"
                        else "Explore available courses in Marketplace.",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Button(onClick = onExploreMarketplace, enabled = !busy) {
                        Text(if (hi) "Marketplace खोलें" else "Open Marketplace")
                    }
                }
            }
        } else {
            enrolled.forEach { program ->
                val id = program.optLong("id", program.optLong("course_id"))
                if (id > 0L) {
                    val isActive = id == active?.optLong("id", active.optLong("course_id"))
                    val progress = courseProgress[id] ?: JSONObject()
                    val stats = progress.optJSONObject("lessonStats") ?: JSONObject()
                    val completed = stats.optInt("completed_lessons", stats.optInt("completedLessons", 0))
                    val total = stats.optInt("total_lessons", stats.optInt("totalLessons", 0))
                    val percent = stats.optDouble(
                        "completion_percent",
                        if (total > 0) completed * 100.0 / total else 0.0
                    ).toFloat().coerceIn(0f, 100f)
                    val subjects = program.optJSONArray("subjects")?.let { array ->
                        (0 until array.length()).mapNotNull { array.optJSONObject(it) }
                    }.orEmpty()

                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(program.optString("display_name").ifBlank {
                                        if (hi) "आपका course" else "Your course"
                                    }, style = MaterialTheme.typography.titleMedium)
                                    Text(
                                        listOf(program.optString("curriculum_version"), program.optString("code"))
                                            .filter { it.isNotBlank() }.distinct().joinToString(" · "),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                if (isActive) {
                                    Surface(
                                        shape = MaterialTheme.shapes.small,
                                        color = MaterialTheme.colorScheme.primaryContainer,
                                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                                    ) {
                                        Text(
                                            if (hi) "वर्तमान course" else "Active course",
                                            modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
                                            style = MaterialTheme.typography.labelMedium
                                        )
                                    }
                                }
                            }

                            if (total > 0) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        if (hi) "$completed / $total पाठ पूरे" else "$completed of $total lessons completed",
                                        modifier = Modifier.weight(1f),
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                    Text("${percent.toInt()}%", style = MaterialTheme.typography.labelLarge,
                                        color = MaterialTheme.colorScheme.primary)
                                }
                                LinearProgressIndicator(progress = { percent / 100f }, modifier = Modifier.fillMaxWidth())
                            } else {
                                Text(
                                    if (progress.length() == 0) {
                                        if (hi) "Progress उपलब्ध होते ही यहाँ दिखेगी।" else "Your progress will appear here when available."
                                    } else {
                                        if (hi) "अभी इस course की progress उपलब्ध नहीं है।" else "Progress is not available for this course yet."
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }

                            if (!isActive) {
                                Button(onClick = { onSelectActiveCourse(id) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                                    Text(if (hi) "इस course में सीखें" else "Study this course")
                                }
                            }

                            if (subjects.isNotEmpty()) {
                                Text(if (hi) "इस course के subjects" else "Subjects in this course",
                                    style = MaterialTheme.typography.labelLarge)
                                Row(
                                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    subjects.forEach { subject ->
                                        val code = subject.optString("subject_code", subject.optString("code"))
                                        FilterChip(
                                            selected = false,
                                            onClick = { if (code.isNotBlank()) onOpenSubject(code, id) },
                                            enabled = !busy && code.isNotBlank(),
                                            label = {
                                                Text(subject.optString("subject_name", code).ifBlank {
                                                    if (hi) "विषय" else "Subject"
                                                })
                                            }
                                        )
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
