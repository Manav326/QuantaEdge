package com.quantaedge.android.ui

import androidx.compose.foundation.clickable
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import com.quantaedge.android.data.ApiClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.util.Locale

@Composable
fun AssessmentLeaderboardScreen(language: String, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val compact = LocalConfiguration.current.screenWidthDp < 390
    fun t(en: String, hi: String) = if (language == "hi") hi else en
    var tests by remember { mutableStateOf(emptyList<JSONObject>()) }
    var selectedTest by remember { mutableStateOf<JSONObject?>(null) }
    var leaderboard by remember { mutableStateOf(JSONObject()) }
    var loadingTests by remember { mutableStateOf(true) }
    var loadingBoard by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var refreshing by remember { mutableStateOf(0) }

    LaunchedEffect(language, refreshing) {
        loadingTests = true
        error = ""
        try {
            val loaded = withContext(Dispatchers.IO) {
                listOf("maths", "science").flatMap { subject ->
                    ApiClient.getObject("/api/v1/learning/assessments?subjectCode=" +
                        ApiClient.encodeQuery(subject) + "&language=" + ApiClient.encodeQuery(language))
                        .optJSONArray("items").asObjectList()
                }
            }
            tests = loaded.distinctBy { it.optLongAny("assessment_id", "assessmentId", "id") }
                .sortedBy { it.optString("title", it.optString("assessment_title")).lowercase(Locale.ROOT) }
        } catch (e: Exception) {
            error = e.message ?: t("Tests could not be loaded.", "Tests load नहीं हो सके।")
        } finally {
            loadingTests = false
        }
    }

    fun loadLeaderboard(test: JSONObject) {
        scope.launch {
            selectedTest = test
            leaderboard = JSONObject()
            loadingBoard = true
            error = ""
            try {
                val id = test.optLongAny("assessment_id", "assessmentId", "id")
                if (id <= 0L) throw IOException("इस test की पहचान नहीं मिली।")
                leaderboard = withContext(Dispatchers.IO) {
                    ApiClient.getObject("/api/v1/learning/assessments/" + id + "/leaderboard?language=" + ApiClient.encodeQuery(language))
                }
            } catch (e: Exception) {
                error = e.message ?: t("Leaderboard could not be loaded.", "Leaderboard लोड नहीं हो सकी।")
            } finally {
                loadingBoard = false
            }
        }
    }

    val title = selectedTest?.optString("title")
        ?.takeIf { it.isNotBlank() }
        ?: selectedTest?.optString("assessment_title")?.takeIf { it.isNotBlank() }
        ?: leaderboard.optString("assessment_title", t("Test leaderboard", "टेस्ट लीडरबोर्ड"))
    val entries = leaderboard.optJSONArray("items").asObjectList()
    val current = leaderboard.optJSONObject("current_student")
    val maxScore = leaderboard.optString("max_score", selectedTest?.optString("max_score", "—") ?: "—")

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (compact) {
            Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(if (language == "hi") "टॉप 20 टेस्ट लीडरबोर्ड" else "Top 20 test leaderboard",
                    style = MaterialTheme.typography.headlineSmall)
                TextButton(onClick = onBack) { Text(if (language == "hi") "वापस" else "Back") }
            }
        } else {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(if (language == "hi") "टॉप 20 टेस्ट लीडरबोर्ड" else "Top 20 test leaderboard",
                    style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                TextButton(onClick = onBack) { Text(if (language == "hi") "वापस" else "Back") }
            }
        }
        Text(
            if (language == "hi") "हर test की ranking server से आती है। अंक समान होने पर server के ranking rules लागू होते हैं।"
            else "Each test's ranking is provided by the server, using its authoritative score and tie-break rules.",
            style = MaterialTheme.typography.bodyMedium
        )
        if (error.isNotBlank()) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(10.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                ) {
                    Text(localizedUiText(error), modifier = Modifier.weight(1f),
                        color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    TextButton(
                        onClick = { selectedTest?.let(::loadLeaderboard) ?: run { refreshing += 1 } },
                        enabled = !loadingTests && !loadingBoard
                    ) { Text(t("Retry", "फिर प्रयास करें")) }
                }
            }
        }
        if (loadingTests) CircularProgressIndicator()
        if (!loadingTests && tests.isEmpty()) Text(if (language == "hi") "अभी कोई प्रकाशित test उपलब्ध नहीं है।" else "No published tests are available.")
        tests.forEach { test ->
            val id = test.optLongAny("assessment_id", "assessmentId", "id")
            val testTitle = test.optString("title", test.optString("assessment_title", "Assessment"))
            val subject = test.optString("subject_code", test.optString("subjectCode", ""))
            Card(modifier = Modifier.fillMaxWidth().clickable { loadLeaderboard(test) }) {
                Column(Modifier.padding(13.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text(testTitle, style = MaterialTheme.typography.titleMedium)
                    Text(subject.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString() } +
                        " · " + test.optString("question_count", "—") + " " + t("questions", "प्रश्न"),
                        color = MaterialTheme.colorScheme.secondary)
                    if (selectedTest?.optLongAny("assessment_id", "assessmentId", "id") == id) {
                        Text(if (language == "hi") "चुना गया" else "Selected", color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
        if (selectedTest != null) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                OutlinedButton(onClick = { selectedTest?.let(::loadLeaderboard) }, enabled = !loadingBoard) {
                    Text(if (language == "hi") "रिफ्रेश" else "Refresh")
                }
            }
            if (loadingBoard) CircularProgressIndicator()
            else if (leaderboard.length() > 0) {
                if (compact) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        SummaryCard(if (language == "hi") "प्रतिभागी" else "Participants",
                            leaderboard.optString("participant_count", "0"), Modifier.fillMaxWidth())
                        SummaryCard(if (language == "hi") "अधिकतम अंक" else "Max score", maxScore, Modifier.fillMaxWidth())
                        SummaryCard(if (language == "hi") "मेरी रैंक" else "My rank",
                            current?.optString("rank") ?: "—", Modifier.fillMaxWidth())
                    }
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        SummaryCard(if (language == "hi") "प्रतिभागी" else "Participants",
                            leaderboard.optString("participant_count", "0"), Modifier.weight(1f))
                        SummaryCard(if (language == "hi") "अधिकतम अंक" else "Max score", maxScore, Modifier.weight(1f))
                        SummaryCard(if (language == "hi") "मेरी रैंक" else "My rank",
                            current?.optString("rank") ?: "—", Modifier.weight(1f))
                    }
                }
                if (current != null) {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(13.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(if (language == "hi") "आपकी स्थिति" else "Your standing", style = MaterialTheme.typography.titleMedium)
                            Text("#" + current.optString("rank", "—") + " · " +
                                current.optString("student_name", if (language == "hi") "आप" else "You"))
                            Text(current.optString("score", "—") + " / " + current.optString("max_score", maxScore))
                        }
                    }
                }
                Text(if (language == "hi") "शीर्ष विद्यार्थी" else "Top students", style = MaterialTheme.typography.titleMedium)
                if (entries.isEmpty()) {
                    Text(if (language == "hi") "अभी ranking के लिए पर्याप्त released results नहीं हैं।" else "There are no released results to rank yet.")
                } else {
                    entries.forEach { entry ->
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                    Text("#" + entry.optString("rank", "—") + "  " +
                                        entry.optString("student_name", if (language == "hi") "विद्यार्थी" else "Student"),
                                        style = MaterialTheme.typography.titleSmall)
                                    Text(entry.optString("score", "—") + " / " + entry.optString("max_score", maxScore),
                                        color = MaterialTheme.colorScheme.secondary)
                                }
                                Text(formatDuration(entry.optLong("time_taken_seconds", 0L)), style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
                val criteria = leaderboard.optJSONArray("ranking_criteria").asStringList()
                if (criteria.isNotEmpty()) Text(
                    (if (language == "hi") "रैंकिंग नियम: " else "Ranking criteria: ") + criteria.joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary
                )
            }
        }
    }
}

@Composable
private fun SummaryCard(label: String, value: String, modifier: Modifier = Modifier) {
    Card(modifier = modifier) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(label, style = MaterialTheme.typography.labelSmall)
            Text(value, style = MaterialTheme.typography.titleMedium)
        }
    }
}

private fun formatDuration(seconds: Long): String {
    val safe = seconds.coerceAtLeast(0)
    return String.format(Locale.ROOT, "%d:%02d", safe / 60, safe % 60)
}

private fun org.json.JSONArray?.asObjectList(): List<JSONObject> =
    if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }

private fun org.json.JSONArray?.asStringList(): List<String> =
    if (this == null) emptyList() else (0 until length()).mapNotNull { optString(it).takeIf(String::isNotBlank) }

private fun JSONObject.optLongAny(vararg keys: String): Long {
    keys.forEach { key ->
        val value = optLong(key, 0L)
        if (value > 0L) return value
    }
    return 0L
}
