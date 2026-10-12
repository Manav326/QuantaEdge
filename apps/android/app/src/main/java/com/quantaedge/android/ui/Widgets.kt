package com.quantaedge.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import org.json.JSONArray
import org.json.JSONObject

fun JSONObject.textAny(vararg keys: String): String {
    for (key in keys) {
        if (!has(key) || isNull(key)) continue
        val value = opt(key) ?: continue
        if (value == JSONObject.NULL) continue
        val text = if (value is JSONObject || value is JSONArray) value.toString() else value.toString()
        if (text.isNotBlank() && text != "null") return text
    }
    return ""
}

fun JSONObject.longAny(vararg keys: String): Long {
    for (key in keys) {
        if (!has(key) || isNull(key)) continue
        val value = opt(key)
        if (value is Number) return value.toLong()
        value?.toString()?.toLongOrNull()?.let { return it }
    }
    return 0L
}

fun JSONArray.objectItems(): List<JSONObject> = buildList {
    for (i in 0 until length()) optJSONObject(i)?.let(::add)
}

fun jsonArrayValue(value: Any?): JSONArray = when (value) {
    is JSONArray -> value
    is String -> runCatching { JSONArray(value) }.getOrDefault(JSONArray())
    else -> JSONArray()
}

fun JSONObject.safeContentText(): String {
    val raw = opt("content") ?: return ""
    return contentValueText(raw)
}

private fun contentValueText(value: Any?, depth: Int = 0): String {
    if (depth > 8 || value == null || value == JSONObject.NULL) return ""
    if (value is JSONArray) {
        return (0 until value.length()).mapNotNull { index ->
            contentValueText(value.opt(index), depth + 1).takeIf { it.isNotBlank() }
        }.joinToString("\n\n")
    }
    if (value is JSONObject) {
        val hidden = setOf("answer_payload", "answer_key", "correct_answer", "is_correct", "grading_notes")
        val preferred = listOf("heading", "title", "text", "body", "explanation", "statement", "content", "steps", "items", "rows", "example", "caption", "description")
        val parts = mutableListOf<String>()
        preferred.forEach { key ->
            if (value.has(key) && key !in hidden) {
                val part = contentValueText(value.opt(key), depth + 1)
                if (part.isNotBlank() && part !in parts) parts.add(part)
            }
        }
        if (parts.isEmpty()) {
            val keys = value.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                if (key in hidden || key in setOf("type", "block_type", "layout", "style", "asset_id")) continue
                val part = contentValueText(value.opt(key), depth + 1)
                if (part.isNotBlank()) parts.add(part)
            }
        }
        return parts.joinToString("\n\n")
    }
    return value.toString()
}

@Composable
fun QuestionCard(
    question: JSONObject,
    answer: String,
    onAnswerChange: (String) -> Unit,
    onSubmit: () -> Unit,
    locked: Boolean,
    saving: Boolean,
    feedback: String? = null,
    status: String? = null,
    submitLabel: String = "उत्तर सुरक्षित करें"
) {
    val options = jsonArrayValue(question.opt("options")).objectItems()
    val prompt = question.textAny("prompt", "question", "title").ifBlank { "प्रश्न" }
    val type = question.textAny("question_type", "questionType").uppercase()
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("प्रश्न " + question.textAny("sequence_no", "sort_order").ifBlank { "" }, style = MaterialTheme.typography.labelMedium)
                Text(type.replace('_', ' '), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)
            }
            Text(prompt, style = MaterialTheme.typography.titleMedium)
            if (options.isNotEmpty()) {
                options.forEach { option ->
                    val key = option.textAny("key", "option_key", "value")
                    val label = option.textAny("label", "text").ifBlank { key }
                    Row(
                        modifier = Modifier.fillMaxWidth().selectable(
                            selected = answer == key,
                            enabled = !locked && !saving,
                            role = Role.RadioButton,
                            onClick = { onAnswerChange(key) }
                        ),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = answer == key, onClick = { onAnswerChange(key) }, enabled = !locked && !saving)
                        Text(label, modifier = Modifier.weight(1f).padding(start = 8.dp))
                    }
                }
            } else {
                OutlinedTextField(
                    value = answer,
                    onValueChange = onAnswerChange,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(if (type.contains("NUMERICAL") || type == "INPUT") "अपना उत्तर लिखें" else "अपना उत्तर लिखें") },
                    enabled = !locked && !saving,
                    minLines = if (type.contains("LONG")) 3 else 1,
                    maxLines = 6
                )
            }
            if (!status.isNullOrBlank()) Text(status, style = MaterialTheme.typography.bodyMedium)
            if (!feedback.isNullOrBlank()) Text(feedback, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.secondary)
            if (!locked) {
                Button(
                    onClick = onSubmit,
                    enabled = !saving && answer.isNotBlank(),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(if (saving) "सहेज रहे हैं…" else submitLabel)
                }
            }
        }
    }
}

@Composable
fun EmptyState(title: String, detail: String) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(title, style = MaterialTheme.typography.titleLarge)
        Text(detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.secondary)
    }
}

@Composable
fun ActionRow(title: String, detail: String, actionLabel: String, onClick: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            if (detail.isNotBlank()) Text(detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.secondary)
            TextButton(onClick = onClick) { Text(actionLabel) }
        }
    }
}
