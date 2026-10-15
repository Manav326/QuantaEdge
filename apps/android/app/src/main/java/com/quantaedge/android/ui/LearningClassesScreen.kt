package com.quantaedge.android.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.json.JSONObject
import com.quantaedge.android.data.optLongAny

@Composable
fun LearningClassesScreen(
    subject: String,
    liveItems: List<JSONObject>,
    recordedItems: List<JSONObject>,
    busy: Boolean,
    error: String,
    onSubject: (String) -> Unit,
    onRefresh: () -> Unit,
    onJoin: (JSONObject) -> Unit,
    onLeave: (JSONObject) -> Unit,
    onRecording: (JSONObject) -> Unit
) {
    val language = LocalQuantaLanguage.current
    fun t(en: String, hi: String) = if (language == "hi") hi else en
    var tab by rememberSaveable { mutableStateOf("LIVE") }
    Column(modifier = Modifier.fillMaxSize().padding(14.dp)) {
        Text(localizedUiText("Live classes और recordings"), style = MaterialTheme.typography.headlineSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
            if (subject == "maths") Button(onClick = { onSubject("maths") }, enabled = !busy) { Text(localizedUiText("गणित")) }
            else TextButton(onClick = { onSubject("maths") }, enabled = !busy) { Text(localizedUiText("गणित")) }
            if (subject == "science") Button(onClick = { onSubject("science") }, enabled = !busy) { Text(localizedUiText("विज्ञान")) }
            else TextButton(onClick = { onSubject("science") }, enabled = !busy) { Text(localizedUiText("विज्ञान")) }
            TextButton(onClick = onRefresh, enabled = !busy) { Text(localizedUiText("रिफ्रेश")) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            if (tab == "LIVE") Button(onClick = { tab = "LIVE" }, enabled = !busy, modifier = Modifier.weight(1f)) { Text(localizedUiText("Live schedule")) }
            else TextButton(onClick = { tab = "LIVE" }, enabled = !busy, modifier = Modifier.weight(1f)) { Text(localizedUiText("Live schedule")) }
            if (tab == "RECORDED") Button(onClick = { tab = "RECORDED" }, enabled = !busy, modifier = Modifier.weight(1f)) { Text(localizedUiText("Recordings")) }
            else TextButton(onClick = { tab = "RECORDED" }, enabled = !busy, modifier = Modifier.weight(1f)) { Text(localizedUiText("Recordings")) }
        }

        if (busy && ((tab == "LIVE" && liveItems.isEmpty()) || (tab == "RECORDED" && recordedItems.isEmpty()))) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(9.dp),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
            ) {
                androidx.compose.material3.CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                Text(t("Loading classes and recordings…", "Classes और recordings लोड हो रही हैं…"),
                    style = MaterialTheme.typography.bodySmall)
            }
        }
        if (error.isNotBlank()) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(10.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                ) {
                    Text(error, modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = onRefresh, enabled = !busy) { Text(t("Retry", "फिर प्रयास करें")) }
                }
            }
        }

        if (tab == "LIVE") {
            if (liveItems.isEmpty() && !busy) EmptyState("कोई live class नहीं", "जब आपकी कक्षा/विषय के लिए session schedule होगा, वह यहाँ दिखेगा।")
            LazyColumn(modifier = Modifier.weight(1f).padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(liveItems) { item ->
                    val joined = item.optBoolean("currently_joined")
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            Text(item.textAny("title").ifBlank { "Live class" }, style = MaterialTheme.typography.titleMedium)
                            Text(item.textAny("chapter_name", "subject_name", "description"),
                                style = MaterialTheme.typography.bodyMedium)
                            Text(localizedUiText("शिक्षक: ") + item.textAny("host_staff_name").ifBlank { "QuantaEdge शिक्षक" },
                                style = MaterialTheme.typography.bodySmall)
                            Text(item.textAny("starts_at") + if (item.textAny("ends_at").isNotBlank()) " – " + item.textAny("ends_at") else "",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
                            Text(localizedUiText("Attendance: ") + (item.optLongAny("attended_seconds") / 60) + " मिनट • जुड़ने की संख्या: " + item.optInt("join_count"),
                                style = MaterialTheme.typography.bodySmall)
                            if (joined) {
                                Button(onClick = { onLeave(item) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text(localizedUiText("Class leave करें")) }
                            } else {
                                Button(onClick = { onJoin(item) }, enabled = !busy && item.textAny("status") in setOf("SCHEDULED", "LIVE"),
                                    modifier = Modifier.fillMaxWidth()) { Text(localizedUiText("Live class join करें")) }
                            }
                            if (item.optBoolean("has_recording")) {
                                Text(localizedUiText("इस class की published recording भी उपलब्ध है।"), style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
        } else {
            if (recordedItems.isEmpty() && !busy) EmptyState("कोई published recording नहीं", "आपके विषय की recordings उपलब्ध होने पर वे यहाँ दिखेंगी।")
            LazyColumn(modifier = Modifier.weight(1f).padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(recordedItems) { item ->
                    val done = item.optBoolean("completed")
                    val position = item.optLongAny("last_position_seconds")
                    val duration = item.optLongAny("duration_seconds")
                    Card(modifier = Modifier.fillMaxWidth().clickable { onRecording(item) }) {
                        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            Text(item.textAny("title").ifBlank { "Recorded class" }, style = MaterialTheme.typography.titleMedium)
                            Text(item.textAny("chapter_name", "subject_name", "description"), style = MaterialTheme.typography.bodyMedium)
                            Text(if (duration > 0) t("Duration: ", "अवधि: ") + (duration / 60) + t(" minutes", " मिनट") else t("Recorded learning session", "रिकॉर्ड किया गया अध्ययन सत्र"),
                                style = MaterialTheme.typography.bodySmall)
                            Text(if (done) t("Watched", "पूरी देखी गई") else if (position > 0) t("Continue from where you left off: ", "आगे जारी रखें: ") + (position / 60) + t(" minutes", " मिनट") else t("Not watched yet", "अभी तक नहीं देखी"),
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
                            Button(onClick = { onRecording(item) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                                Text(if (position > 0 && !done) t("Continue from where you left off", "जहाँ छोड़ा था वहीं से चलाएँ") else t("Play recording", "Recording चलाएँ"))
                            }
                        }
                    }
                }
            }
        }
    }
}
