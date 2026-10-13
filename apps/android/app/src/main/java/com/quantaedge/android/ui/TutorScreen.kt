package com.quantaedge.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.json.JSONObject

private val tutorModes = listOf("HINT", "EXPLAIN", "EXAMPLE", "STEP_BY_STEP", "CHECK_MY_WORK")

@Composable
fun TutorScreen(
    lessonTitle: String,
    available: Boolean,
    messages: List<JSONObject>,
    busy: Boolean,
    error: String,
    onSend: (String, String) -> Unit
) {
    val language = LocalQuantaLanguage.current
    fun t(en: String, hi: String) = if (language == "hi") hi else en
    var input by remember { mutableStateOf("") }
    var selectedMode by remember { mutableStateOf("HINT") }

    Column(modifier = Modifier.fillMaxSize().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(localizedUiText("QuantaEdge Tutor"), style = MaterialTheme.typography.headlineSmall)
        Text(lessonTitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.secondary)
        if (!available) {
            EmptyState("AI Tutor अभी उपलब्ध नहीं", "Tutor सुविधा backend पर AI provider configure होने पर ही चालू होगी। यह स्क्रीन कोई नकली उत्तर नहीं दिखाती।")
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                tutorModes.forEach { key ->
                    val label = when (key) {
                        "HINT" -> t("Hint", "संकेत")
                        "EXPLAIN" -> t("Explain", "समझाएँ")
                        "EXAMPLE" -> t("Example", "उदाहरण")
                        "STEP_BY_STEP" -> t("Step by step", "चरण-दर-चरण")
                        else -> t("Check my work", "मेरे काम की जाँच")
                    }
                    if (selectedMode == key) Button(onClick = { selectedMode = key }, enabled = !busy) {
                        Text(label, style = MaterialTheme.typography.labelSmall)
                    } else TextButton(onClick = { selectedMode = key }, enabled = !busy) {
                        Text(label, style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
            if (messages.isEmpty()) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Text(localizedUiText("पहले Hint से शुरू करें"), style = MaterialTheme.typography.titleMedium)
                        Text(localizedUiText("Tutor इसी पाठ की प्रकाशित सामग्री और आपकी learning context के आधार पर मदद करेगा।"), style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(messages) { item ->
                    val isAssistant = item.textAny("role").equals("ASSISTANT", true)
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            Text(if (isAssistant) t("Tutor", "ट्यूटर") else t("You", "आप"), style = MaterialTheme.typography.labelMedium,
                                color = if (isAssistant) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary)
                            if (item.textAny("mode").isNotBlank()) Text(item.textAny("mode").replace('_', ' '), style = MaterialTheme.typography.labelSmall)
                            Text(item.textAny("message"), style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
            }
            OutlinedTextField(
                value = input,
                onValueChange = { input = it.take(1200) },
                modifier = Modifier.fillMaxWidth(),
                enabled = !busy,
                label = { Text(localizedUiText("आप कहाँ अटके हैं?")) },
                minLines = 2,
                maxLines = 4
            )
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                Button(
                    onClick = {
                        val message = input.trim()
                        if (message.isNotEmpty()) {
                            input = ""
                            onSend(message, selectedMode)
                        }
                    },
                    enabled = !busy && input.isNotBlank()
                ) { Text(t(if (busy) "Thinking…" else "Ask", if (busy) "सोच रहा है…" else "पूछें")) }
            }
        }
        if (error.isNotBlank()) Text(localizedUiText(error), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
    }
}
