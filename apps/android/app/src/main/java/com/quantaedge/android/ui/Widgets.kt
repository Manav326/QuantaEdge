package com.quantaedge.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
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
    if (raw is String) {
        val trimmed = raw.trim()
        if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
            runCatching { JSONObject(trimmed) }.getOrNull()?.let { return contentValueText(it) }
        }
        if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
            runCatching { JSONArray(trimmed) }.getOrNull()?.let { return contentValueText(it) }
        }
        return raw
    }
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
    submitLabel: String = "उत्तर सुरक्षित करें",
    onTutorHelp: (() -> Unit)? = null,
    showSubmitButton: Boolean = true
) {
    val options = jsonArrayValue(question.opt("options")).objectItems()
    val prompt = question.textAny("prompt", "question", "title").ifBlank { "प्रश्न" }
    val type = question.textAny("question_type", "questionType").uppercase()
    val responseMode = question.textAny("response_mode", "responseMode").uppercase()
    val isOrder = type == "ORDER" || responseMode == "ORDER"
    val isMatch = type == "MATCH" || responseMode == "MATCH"
    val optionKeys = options.map { option ->
        option.textAny("key", "option_key", "value").ifBlank { option.textAny("label", "text") }
    }.filter { it.isNotBlank() }
    val selectedMulti = parseAnswerKeyArray(answer).toSet()
    val orderedKeys = displayedOrder(answer, optionKeys)
    val isMultiSelect = type in setOf("MULTI_OPTION", "MULTI_SELECT", "MULTIPLE_SELECT", "MULTI_CHOICE") ||
        responseMode in setOf("MULTI_OPTION", "MULTI_SELECT", "MULTIPLE_SELECT", "MULTI_CHOICE")
    val isNumericInput = type in setOf("NUMERICAL", "NUMERIC", "INPUT") || responseMode == "NUMERIC"

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(localizedUiText("प्रश्न ") + question.textAny("sequence_no", "sort_order").ifBlank { "" },
                    style = MaterialTheme.typography.labelMedium)
                Text(type.replace('_', ' '), style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.secondary)
            }
            LearningMathText(prompt, style = MaterialTheme.typography.titleMedium)

            when {
                isOrder && options.isNotEmpty() -> {
                    Text(localizedUiText("नीचे दिए गए items को सही क्रम में रखें। ↑ / ↓ से क्रम बदलें।"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.secondary)
                    orderedKeys.forEachIndexed { index, key ->
                        val option = options.firstOrNull {
                            it.textAny("key", "option_key", "value").ifBlank { it.textAny("label", "text") } == key
                        }
                        val label = option?.textAny("label", "text").orEmpty().ifBlank { key }
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text((index + 1).toString() + ".", style = MaterialTheme.typography.titleSmall)
                                LearningMathText(label, modifier = Modifier.weight(1f))
                                TextButton(
                                    onClick = {
                                        val next = orderedKeys.toMutableList()
                                        val value = next.removeAt(index)
                                        next.add(index - 1, value)
                                        onAnswerChange(JSONArray(next).toString())
                                    },
                                    enabled = !locked && !saving && index > 0
                                ) { Text(localizedUiText("↑")) }
                                TextButton(
                                    onClick = {
                                        val next = orderedKeys.toMutableList()
                                        val value = next.removeAt(index)
                                        next.add(index + 1, value)
                                        onAnswerChange(JSONArray(next).toString())
                                    },
                                    enabled = !locked && !saving && index < orderedKeys.lastIndex
                                ) { Text(localizedUiText("↓")) }
                            }
                        }
                    }
                }
                isMultiSelect && options.isNotEmpty() -> {
                    Text(localizedUiText("सभी लागू विकल्प चुनें।"), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.secondary)
                    options.forEach { option ->
                        val key = option.textAny("key", "option_key", "value")
                        val label = option.textAny("label", "text").ifBlank { key }
                        Row(verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Checkbox(
                                checked = key in selectedMulti,
                                onCheckedChange = { checked ->
                                    val next = selectedMulti.toMutableSet()
                                    if (checked) next.add(key) else next.remove(key)
                                    val selected = optionKeys.filter { it in next }
                                    onAnswerChange(if (selected.isEmpty()) "" else JSONArray(selected).toString())
                                },
                                enabled = !locked && !saving
                            )
                            LearningMathText(label, modifier = Modifier.weight(1f))
                        }
                    }
                }
                isMatch -> {
                    if (options.isNotEmpty()) {
                        options.forEach { option ->
                            LearningMathText(
                                option.textAny("key", "option_key") + " — " + option.textAny("label", "text"),
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                    Text(localizedUiText("Enter matching pairs as a JSON map (for example, 1 maps to A and 2 maps to B)."),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
                    OutlinedTextField(
                        value = answer,
                        onValueChange = onAnswerChange,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(localizedUiText("मिलान के उत्तर")) },
                        enabled = !locked && !saving,
                        minLines = 3,
                        maxLines = 6
                    )
                }
                isNumericInput -> {
                    OutlinedTextField(
                        value = answer,
                        onValueChange = onAnswerChange,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(localizedUiText("अपना संख्यात्मक उत्तर लिखें")) },
                        enabled = !locked && !saving,
                        minLines = 1,
                        maxLines = 2,
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                            keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal
                        )
                    )
                }
                options.isNotEmpty() -> {
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
                            RadioButton(
                                selected = answer == key,
                                onClick = { onAnswerChange(key) },
                                enabled = !locked && !saving
                            )
                            LearningMathText(label, modifier = Modifier.weight(1f).padding(start = 8.dp))
                        }
                    }
                }
                else -> {
                    if (isOrder) {
                        Text(localizedUiText("Enter the order as a JSON array (for example, B, C, A)."),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
                    }
                    val inputLabel = when {
                        isOrder -> "क्रम का उत्तर"
                        isMatch -> "मिलान के उत्तर"
                        else -> "अपना उत्तर या reasoning लिखें"
                    }
                    val longAnswer = type in setOf(
                        "LONG_ANSWER", "SHORT_ANSWER", "CASE_BASED", "SOURCE_BASED",
                        "ASSERTION_REASON", "DIAGRAM", "MAP", "MATCH", "ORDER"
                    )
                    OutlinedTextField(
                        value = answer,
                        onValueChange = onAnswerChange,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(localizedUiText(inputLabel)) },
                        enabled = !locked && !saving,
                        minLines = if (longAnswer) 3 else 1,
                        maxLines = 6
                    )
                }
            }

            if (!status.isNullOrBlank()) LearningMathText(localizedUiText(status), style = MaterialTheme.typography.bodyMedium)
            if (!feedback.isNullOrBlank()) {
                LearningMathText(feedback, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.secondary)
            }
            if (onTutorHelp != null) {
                TextButton(onClick = onTutorHelp, enabled = !saving, modifier = Modifier.fillMaxWidth()) {
                    Text(localizedUiText("Tutor से मदद लें"))
                }
            }
            if (!locked && showSubmitButton) {
                Button(
                    onClick = onSubmit,
                    enabled = !saving && answer.isNotBlank(),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(localizedUiText(if (saving) "सहेज रहे हैं…" else submitLabel))
                }
            }
        }
    }
}

private fun parseAnswerKeyArray(answer: String): List<String> {
    if (answer.isBlank()) return emptyList()
    val array = runCatching { JSONArray(answer) }.getOrNull() ?: return emptyList()
    return buildList {
        for (index in 0 until array.length()) {
            val value = array.opt(index)?.toString().orEmpty()
            if (value.isNotBlank()) add(value)
        }
    }
}

private fun displayedOrder(answer: String, optionKeys: List<String>): List<String> {
    val parsed = parseAnswerKeyArray(answer).filter { it in optionKeys }
    return (parsed + optionKeys.filterNot { it in parsed }).distinct()
}

@Composable
fun EmptyState(title: String, detail: String) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceVariant
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 26.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                localizedUiText(title),
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center
            )
            Text(
                localizedUiText(detail),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
fun ActionRow(title: String, detail: String, actionLabel: String, onClick: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(localizedUiText(title), style = MaterialTheme.typography.titleMedium)
            if (detail.isNotBlank()) Text(localizedUiText(detail), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.secondary)
            TextButton(onClick = onClick) { Text(localizedUiText(actionLabel)) }
        }
    }
}
