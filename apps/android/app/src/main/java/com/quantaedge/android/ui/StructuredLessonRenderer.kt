package com.quantaedge.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.json.JSONArray
import org.json.JSONObject

/**
 * Additive Compose renderer for structured bilingual lesson artifacts.
 * Persistence and grading remain owned by the host; this UI never exposes answer keys.
 */
@Composable
fun StructuredLessonRenderer(
    lesson: JSONObject,
    language: String,
    studentMode: Boolean = true,
    onSubmitResponse: (questionId: String, responseJson: String) -> Unit,
    onSaveActivity: ((blockId: String, responseJson: String) -> Unit)? = null,
    savedResponses: JSONObject = JSONObject(),
    submittingQuestionIds: Set<String> = emptySet(),
    savingActivityIds: Set<String> = emptySet()
) {
    val locale = if (language == "hi") "hi" else "en"
    val workflow = lesson.optJSONObject("workflow") ?: JSONObject()
    val chapter = lesson.optJSONObject("chapter") ?: JSONObject()
    val topic = lesson.optJSONObject("topic") ?: JSONObject()
    val explain = lesson.optJSONObject("explain") ?: JSONObject()
    val practice = lesson.optJSONObject("practice") ?: JSONObject()

    val blocks = explain.optJSONArray("blocks").jsonObjects().sortedBy { it.optInt("order", Int.MAX_VALUE) }
    val questions = practice.optJSONArray("questions").jsonObjects()
    val questionStates = savedResponses.optJSONObject("questions") ?: JSONObject()
    val activityStates = savedResponses.optJSONObject("activities") ?: JSONObject()
    val hasAnswerKeys = questions.any { question ->
        listOf("correct_answer_ids", "correct_answer", "correct_pairs", "accepted_answers", "answer_guide_by_locale", "grading")
            .any(question::has)
    }
    val workflowStatus = workflow.optString("status").uppercase()
    if (studentMode && (workflowStatus != "PUBLISHED" ||
            !workflow.optBoolean("publication_allowed", false) || hasAnswerKeys)) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(if (locale == "hi") "यह पाठ अभी उपलब्ध नहीं है" else "This lesson is not available yet",
                    style = MaterialTheme.typography.titleMedium)
                Text(when {
                    hasAnswerKeys -> if (locale == "hi")
                        "सर्वर ने अभी लेखक की उत्तर-कुंजियाँ हटाई नहीं हैं।" else "Author-only answer keys have not been removed by the server."
                    workflowStatus != "PUBLISHED" || !workflow.optBoolean("publication_allowed", false) ->
                        if (locale == "hi") "इस पाठ की समीक्षा और प्रकाशन की अनुमति अभी पूरी नहीं हुई है।"
                        else "This lesson has not completed review and publication approval."
                    else -> if (locale == "hi") "यह पाठ अभी उपलब्ध नहीं है।" else "This lesson is not available yet."
                })
            }
        }
        return
    }

    Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(localized(chapter.optJSONObject("title_by_locale"), locale),
            style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.secondary)
        Text(localized(topic.optJSONObject("title_by_locale"), locale), style = MaterialTheme.typography.headlineSmall)
        Text(localized(explain.optJSONObject("title_by_locale"), locale), style = MaterialTheme.typography.titleLarge)
        blocks.forEach { block ->
            StructuredExplainBlockView(block, locale, studentMode, onSaveActivity,
                activityStates.optJSONObject(block.optString("id")) ?: JSONObject(),
                block.optString("id") in savingActivityIds)
        }
        Text(localized(practice.optJSONObject("title_by_locale"), locale), style = MaterialTheme.typography.titleLarge)
        if (questions.isEmpty()) {
            Text(if (locale == "hi") "इस पाठ में अभी अभ्यास प्रश्न उपलब्ध नहीं हैं।" else "No practice questions are available in this lesson yet.")
        } else {
            var questionIndex by remember(lesson.optString("artifact_id")) { mutableStateOf(0) }
            val safeIndex = questionIndex.coerceIn(0, questions.lastIndex)
            val currentQuestion = questions[safeIndex]
            Text((if (locale == "hi") "प्रश्न " else "Question ") + (safeIndex + 1) +
                (if (locale == "hi") " / " else " of ") + questions.size,
                style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.secondary)
            StructuredQuestionView(currentQuestion, locale, studentMode, blocks,
                questionStates.optJSONObject(currentQuestion.optString("id")) ?: JSONObject(),
                currentQuestion.optString("id") in submittingQuestionIds, onSubmitResponse)
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                OutlinedButton(enabled = safeIndex > 0, onClick = { questionIndex = (safeIndex - 1).coerceAtLeast(0) }) {
                    Text(if (locale == "hi") "पिछला प्रश्न" else "Previous")
                }
                Button(enabled = safeIndex < questions.lastIndex, onClick = { questionIndex = (safeIndex + 1).coerceAtMost(questions.lastIndex) }) {
                    Text(if (locale == "hi") "अगला प्रश्न" else "Next")
                }
            }
        }
    }
}

@Composable
private fun StructuredExplainBlockView(
    block: JSONObject,
    locale: String,
    studentMode: Boolean,
    onSaveActivity: ((String, String) -> Unit)?,
    savedActivity: JSONObject = JSONObject(),
    savingActivity: Boolean = false
) {
    val type = block.optString("type")
    val text = block.optJSONObject("text_by_locale")?.optJSONObject(locale) ?: JSONObject()
    val content = block.optJSONObject("content_by_locale")
    val title = text.optString("heading").ifBlank {
        localized(content, locale).ifBlank { if (locale == "hi") "सीखें" else "Learn" }
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            when (type) {
                "rich_text", "recap" -> {
                    text.optJSONArray("paragraphs").stringItems().forEach {
                        LearningMathText(it, style = MaterialTheme.typography.bodyLarge)
                    }
                    text.optString("key_point").takeIf { it.isNotBlank() }?.let {
                        Text(it, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.primary)
                    }
                    text.optString("callout").takeIf { it.isNotBlank() }?.let { Text(it) }
                }
                "diagram" -> StructuredDiagramContent(block, locale)
                "image" -> {
                    val asset = block.optJSONObject("asset") ?: JSONObject()
                    if (studentMode && !isApprovedVisual(asset)) {
                        Text(if (locale == "hi") "यह चित्र समीक्षा तक छिपा है।" else "This visual is hidden until approved.")
                    } else {
                        val url = asset.optString("url")
                        val alt = localized(asset.optJSONObject("alt_by_locale"), locale)
                        if (url.startsWith("https://") && alt.isNotBlank()) {
                            LessonContentBlock(
                                block = JSONObject().put("block_type", "IMAGE").put("asset_url", url)
                                    .put("content", JSONObject().put("title", title).put("description", alt)
                                        .put("alt", alt).put("caption",
                                            localized(block.optJSONObject("caption_by_locale"), locale)).toString()),
                                language = locale,
                                onTutorOpen = {}
                            )
                        } else {
                            Text(if (locale == "hi") "मान्य चित्र उपलब्ध नहीं है।" else "No valid visual asset is available.")
                        }
                    }
                }
                "interactive", "activity" -> StructuredActivityContent(block, locale, onSaveActivity, savedActivity, savingActivity)
                else -> Text(if (locale == "hi") "यह सामग्री प्रकार समर्थित नहीं है।" else "Unsupported content type.")
            }
            val caption = localized(block.optJSONObject("caption_by_locale"), locale)
            if (caption.isNotBlank() && type != "image") {
                Text(caption, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
            }
        }
    }
}

@Composable
private fun StructuredDiagramContent(block: JSONObject, locale: String) {
    val diagram = block.optJSONObject("diagram_data") ?: JSONObject()
    val nodes = diagram.optJSONArray("nodes").jsonObjects()
    val edges = diagram.optJSONArray("edges").jsonObjects()
    if (nodes.isEmpty()) {
        Text(if (locale == "hi") "आरेख की जानकारी उपलब्ध नहीं है।" else "Diagram data is unavailable.")
        return
    }
    fun nodeLabel(id: String): String {
        val node = nodes.firstOrNull { it.optString("id") == id } ?: return ""
        return localized(node.optJSONObject("label_by_locale"), locale)
            .ifBlank { node.optString("label") }
            .ifBlank { if (locale == "hi") "चरण" else "Step" }
    }
    nodes.forEachIndexed { index, node ->
        val label = localized(node.optJSONObject("label_by_locale"), locale).ifBlank { node.optString("label") }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("${index + 1}.", color = MaterialTheme.colorScheme.primary)
            Text(label.ifBlank { if (locale == "hi") "चरण" else "Step" })
        }
    }
    edges.forEach { edge ->
        val from = nodeLabel(edge.optString("from")).ifBlank { if (locale == "hi") "अज्ञात चरण" else "Unknown step" }
        val to = nodeLabel(edge.optString("to")).ifBlank { if (locale == "hi") "अज्ञात चरण" else "Unknown step" }
        val label = localized(edge.optJSONObject("label_by_locale"), locale)
        Text("$from → $to" + if (label.isBlank()) "" else " · $label",
            style = MaterialTheme.typography.bodySmall)
    }
    localized(block.optJSONObject("alt_by_locale"), locale).takeIf { it.isNotBlank() }?.let {
        Text(it, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun StructuredActivityContent(
    block: JSONObject,
    locale: String,
    onSave: ((String, String) -> Unit)?,
    savedActivity: JSONObject = JSONObject(),
    saving: Boolean = false
) {
    val config = block.optJSONObject("configuration") ?: JSONObject()
    val blockId = block.optString("id")
    var response by remember(blockId) { mutableStateOf(savedActivity.optString("response")) }
    var saveRequested by remember(blockId) { mutableStateOf(false) }
    val rawInstructions = block.optJSONObject("instructions_by_locale")?.opt(locale)
    when (rawInstructions) {
        is JSONArray -> rawInstructions.stringItems().forEach { Text(it) }
        is String -> if (rawInstructions.isNotBlank()) Text(rawInstructions)
    }
    val rawSteps = config.optJSONArray("steps")
    if (rawSteps != null) for (index in 0 until rawSteps.length()) {
        val rawStep = rawSteps.opt(index)
        val stepText = if (rawStep is JSONObject) {
            localized(rawStep.optJSONObject("instruction_by_locale"), locale)
                .ifBlank { localized(rawStep.optJSONObject("label_by_locale"), locale) }
                .ifBlank { rawStep.optString("instruction").ifBlank { rawStep.optString("label") } }
        } else rawStep?.toString().orEmpty()
        if (stepText.isNotBlank()) Text("${index + 1}. $stepText")
    }
    OutlinedTextField(
        value = response, onValueChange = { response = it; saveRequested = false }, modifier = Modifier.fillMaxWidth(),
        label = { Text(if (locale == "hi") "आपका उत्तर" else "Your response") }, minLines = 2
    )
    Button(enabled = onSave != null && !saving, onClick = {
        onSave?.invoke(blockId, JSONObject().put("response", response).toString())
        saveRequested = true
    }) {
        Text(if (saving) { if (locale == "hi") "सहेज रहे हैं…" else "Saving…" }
            else if (locale == "hi") "उत्तर सहेजें" else "Save response")
    }
    if (saveRequested && !saving && savedActivity.optString("response") == response) {
        Text(if (locale == "hi") "उत्तर सहेजा गया।" else "Response saved.", style = MaterialTheme.typography.bodySmall)
    }
    if (onSave == null) Text(
        if (locale == "hi") "इस स्क्रीन पर उत्तर सहेजना अभी जुड़ा नहीं है।" else "Response saving is not connected on this screen yet.",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary
    )
}

@Composable
private fun StructuredQuestionView(
    question: JSONObject,
    locale: String,
    studentMode: Boolean,
    lessonBlocks: List<JSONObject>,
    savedState: JSONObject = JSONObject(),
    submitting: Boolean = false,
    onSubmit: (String, String) -> Unit
) {
    val id = question.optString("id")
    val type = question.optString("type")
    val prompt = localized(question.optJSONObject("prompt_by_locale"), locale)
    val options = question.optJSONArray("options").jsonObjects()
    val savedResponse = savedState.opt("response")
    val response = remember(id) { mutableStateMapOf<String, String>().apply {
        when (type) {
            "multiple_choice", "scenario_based", "image_observation" -> if (savedResponse is String) put("selected", savedResponse)
            "true_false" -> if (savedResponse is Boolean) put("boolean", savedResponse.toString())
            "multiple_select" -> if (savedResponse is JSONArray) {
                for (index in 0 until savedResponse.length()) put(savedResponse.optString(index), "true")
            }
            "matching" -> if (savedResponse is JSONObject) savedResponse.keys().forEach { key -> put(key, savedResponse.optString(key)) }
        }
    } }
    var textResponse by remember(id) { mutableStateOf(
        if (type == "fill_blank" || type == "short_answer") savedResponse as? String ?: "" else ""
    ) }
    var submittedRequest by remember(id) { mutableStateOf(savedState.has("status")) }
    val locked = savedState.optString("status") in setOf("correct", "pending_review")

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (type == "image_observation" && studentMode && !isApprovedQuestionVisual(question, lessonBlocks)) {
                Text(if (locale == "hi") "चित्र स्वीकृत होने तक यह प्रश्न उपलब्ध नहीं होगा।"
                    else "This question is unavailable until its visual is approved.")
            } else {
                Text(prompt, style = MaterialTheme.typography.titleMedium)
                if (type == "image_observation") {
                    val visualBlock = findQuestionVisualBlock(question, lessonBlocks)
                    if (visualBlock != null) {
                        when (visualBlock.optString("type")) {
                            "diagram" -> StructuredDiagramContent(visualBlock, locale)
                            "image" -> StructuredExplainBlockView(visualBlock, locale, studentMode, null)
                        }
                    }
                }
                when (type) {
                    "multiple_choice", "scenario_based", "image_observation" -> {
                        if (type == "scenario_based") {
                            localized(question.optJSONObject("scenario_by_locale"), locale)
                                .takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodyLarge) }
                        }
                        options.forEach { option ->
                            val optionId = option.optString("id")
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                RadioButton(response["selected"] == optionId, enabled = !locked && !submitting, onClick = { response["selected"] = optionId })
                                Text(localized(option.optJSONObject("text_by_locale"), locale))
                            }
                        }
                    }
                    "multiple_select" -> options.forEach { option ->
                        val optionId = option.optString("id")
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(response[optionId] == "true", enabled = !locked && !submitting, onCheckedChange = { response[optionId] = it.toString() })
                            Text(localized(option.optJSONObject("text_by_locale"), locale))
                        }
                    }
                    "true_false" -> listOf("true", "false").forEach { value ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(response["boolean"] == value, enabled = !locked && !submitting, onClick = { response["boolean"] = value })
                            Text(if (value == "true") { if (locale == "hi") "सही" else "True" }
                                else { if (locale == "hi") "गलत" else "False" })
                        }
                    }
                    "matching" -> {
                        val leftItems = question.optJSONArray("left_items").jsonObjects()
                        val rightItems = question.optJSONArray("right_items").jsonObjects()
                        leftItems.forEach { left ->
                            val leftId = left.optString("id")
                            var expanded by remember(id, leftId) { mutableStateOf(false) }
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(localized(left.optJSONObject("label_by_locale"), locale))
                                TextButton(onClick = { expanded = true }, enabled = !locked && !submitting) {
                                    val selected = rightItems.firstOrNull { it.optString("id") == response[leftId] }
                                    Text(selected?.let { localized(it.optJSONObject("label_by_locale"), locale) }
                                        ?: if (locale == "hi") "मिलान चुनें" else "Choose a match")
                                }
                                DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
                                    rightItems.forEach { right ->
                                        DropdownMenuItem(
                                            text = { Text(localized(right.optJSONObject("label_by_locale"), locale)) },
                                            onClick = { response[leftId] = right.optString("id"); expanded = false }
                                        )
                                    }
                                }
                            }
                        }
                    }
                    "fill_blank", "short_answer" -> OutlinedTextField(
                        value = textResponse, onValueChange = { textResponse = it }, enabled = !locked && !submitting,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(if (locale == "hi") "उत्तर" else "Answer") },
                        minLines = if (type == "short_answer") 3 else 1
                    )
                    else -> Text(if (locale == "hi") "यह प्रश्न प्रकार समर्थित नहीं है।" else "Unsupported question type.")
                }
                val isAnswerComplete = when (type) {
                    "multiple_choice", "scenario_based", "image_observation" -> !response["selected"].isNullOrBlank()
                    "multiple_select" -> response.values.any { it == "true" }
                    "true_false" -> response["boolean"] != null
                    "matching" -> question.optJSONArray("left_items").jsonObjects().let { items ->
                        items.isNotEmpty() && items.all { !response[it.optString("id")].isNullOrBlank() }
                    }
                    "fill_blank", "short_answer" -> textResponse.isNotBlank()
                    else -> false
                }
                Button(enabled = isAnswerComplete && !locked && !submitting, onClick = {
                    val payloadJson = when (type) {
                        "multiple_choice", "scenario_based", "image_observation" -> JSONObject.quote(response["selected"] ?: "")
                        "true_false" -> response["boolean"]?.toBooleanStrictOrNull()?.toString() ?: "null"
                        "multiple_select" -> JSONArray().also { array ->
                            response.filterValues { it == "true" }.keys.sorted().forEach { selectedId -> array.put(selectedId) }
                        }.toString()
                        "matching" -> JSONObject(response.toMap()).toString()
                        else -> JSONObject.quote(textResponse)
                    }
                    onSubmit(id, payloadJson)
                    submittedRequest = true
                }) { Text(if (submitting) { if (locale == "hi") "सहेज रहे हैं…" else "Saving…" }
                    else if (locale == "hi") "उत्तर भेजें" else "Submit answer") }
                val savedStatus = savedState.optString("status")
                if (savedStatus.isNotBlank()) {
                    val statusLabel = when (savedStatus) {
                        "correct" -> if (locale == "hi") "सही उत्तर" else "Correct"
                        "incorrect" -> if (locale == "hi") "फिर प्रयास करें" else "Try again"
                        "partially_correct" -> if (locale == "hi") "आंशिक रूप से सही" else "Partially correct"
                        else -> if (locale == "hi") "जाँच के लिए सहेजा गया" else "Saved for review"
                    }
                    Text(statusLabel, style = MaterialTheme.typography.titleSmall, color =
                        if (savedStatus == "correct") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary)
                    val feedback = savedState.optJSONObject("feedbackByLocale")?.optString(locale).orEmpty()
                        .ifBlank { savedState.optString("feedback") }
                    if (feedback.isNotBlank()) Text(feedback, style = MaterialTheme.typography.bodyMedium)
                    if (savedState.has("score") && savedState.has("maxScore")) {
                        Text(savedState.optInt("score").toString() + " / " + savedState.optInt("maxScore").toString(),
                            style = MaterialTheme.typography.labelMedium)
                    }
                } else if (submitting) {
                    Text(if (locale == "hi") "आपका उत्तर सहेजा जा रहा है…" else "Saving your response…", style = MaterialTheme.typography.bodySmall)
                } else if (submittedRequest) {
                    Text(if (locale == "hi") "उत्तर का परिणाम अभी नहीं मिला। पुनः प्रयास करें。" else "No result received yet. You can retry.",
                        style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

private fun isApprovedVisual(asset: JSONObject): Boolean {
    val review = asset.optString("review_status").uppercase()
    return asset.optBoolean("publication_allowed") && asset.optBoolean("student_renderable") &&
        asset.optString("url").startsWith("https://") &&
        localized(asset.optJSONObject("alt_by_locale"), "en").isNotBlank() &&
        localized(asset.optJSONObject("alt_by_locale"), "hi").isNotBlank() &&
        review in setOf("APPROVED", "APPROVED_FOR_STUDENT_RENDERING")
}

private fun findQuestionVisualBlock(question: JSONObject, blocks: List<JSONObject>): JSONObject? {
    val ref = question.optString("visual_asset_ref")
    if (ref.isBlank()) return null
    return blocks.firstOrNull { block ->
        block.optString("id") == ref ||
            block.optJSONObject("asset")?.optString("asset_id") == ref ||
            block.optJSONArray("asset_refs").stringItems().contains(ref) ||
            block.optJSONObject("diagram_data")?.optString("visual_id") == ref ||
            block.optJSONObject("diagram_data")?.optString("diagram_id") == ref
    }
}

private fun isApprovedQuestionVisual(question: JSONObject, blocks: List<JSONObject>): Boolean {
    val status = question.optJSONObject("renderability")?.optString("status")?.uppercase().orEmpty()
    if (status !in setOf("APPROVED", "APPROVED_FOR_STUDENT_RENDERING")) return false
    val linked = findQuestionVisualBlock(question, blocks)
    if (linked != null) {
        return when (linked.optString("type")) {
            "image" -> isApprovedVisual(linked.optJSONObject("asset") ?: JSONObject())
            "diagram" -> {
                val data = linked.optJSONObject("diagram_data") ?: JSONObject()
                val review = linked.optString("review_status").ifBlank { data.optString("review_status") }.uppercase()
                val renderable = linked.optBoolean("student_renderable", data.optBoolean("student_renderable"))
                val alt = linked.optJSONObject("alt_by_locale")
                renderable && review in setOf("APPROVED", "APPROVED_FOR_STUDENT_RENDERING") &&
                    !localized(alt, "en").isBlank() && !localized(alt, "hi").isBlank()
            }
            else -> false
        }
    }
    val asset = question.optJSONObject("visual_asset") ?: question.optJSONObject("asset") ?: JSONObject()
    return isApprovedVisual(asset)
}

private fun localized(value: JSONObject?, locale: String): String =
    value?.optString(locale)?.takeIf { it.isNotBlank() } ?: ""

private fun JSONArray?.jsonObjects(): List<JSONObject> =
    if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }

private fun JSONArray?.stringItems(): List<String> =
    if (this == null) emptyList() else (0 until length()).map { optString(it) }.filter { it.isNotBlank() }
