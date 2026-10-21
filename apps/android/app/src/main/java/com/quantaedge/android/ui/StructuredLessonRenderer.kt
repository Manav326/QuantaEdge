package com.quantaedge.android.ui

import android.content.Intent
import android.net.Uri
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
import androidx.compose.ui.platform.LocalContext
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
    showPractice: Boolean = true,
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
            style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(localized(topic.optJSONObject("title_by_locale"), locale), style = MaterialTheme.typography.headlineSmall)
        Text(localized(explain.optJSONObject("title_by_locale"), locale), style = MaterialTheme.typography.titleLarge)
        blocks.forEach { block ->
            StructuredExplainBlockView(block, locale, studentMode, onSaveActivity,
                activityStates.optJSONObject(block.optString("id")) ?: JSONObject(),
                block.optString("id") in savingActivityIds)
        }
        if (showPractice) {
            Text(localized(practice.optJSONObject("title_by_locale"), locale), style = MaterialTheme.typography.titleLarge)
        if (questions.isEmpty()) {
            Text(if (locale == "hi") "इस पाठ में अभी अभ्यास प्रश्न उपलब्ध नहीं हैं।" else "No practice questions are available in this lesson yet.")
        } else {
            var questionIndex by remember(lesson.optString("artifact_id")) { mutableStateOf(0) }
            val safeIndex = questionIndex.coerceIn(0, questions.lastIndex)
            val currentQuestion = questions[safeIndex]
            Text((if (locale == "hi") "प्रश्न " else "Question ") + (safeIndex + 1) +
                (if (locale == "hi") " / " else " of ") + questions.size,
                style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
}

/**
 * Reads all recap payload shapes currently accepted by the Web contract:
 * a localized string, an array of points, or an object with key_takeaways/points.
 * Historical rich_text paragraphs remain rendered by their own block branch.
 */
internal fun recapTakeaways(block: JSONObject, locale: String): List<String> {
    val content = block.optJSONObject("content_by_locale")
    val selected = content?.opt(locale)
    return when (selected) {
        is JSONObject -> {
            val points = selected.optJSONArray("key_takeaways") ?: selected.optJSONArray("points")
            points.stringItems().filter { it.isNotBlank() }
        }
        is JSONArray -> selected.stringItems().filter { it.isNotBlank() }
        is String -> selected.takeIf { it.isNotBlank() }?.let(::listOf) ?: emptyList()
        else -> emptyList()
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
    val context = LocalContext.current
    val type = block.optString("type")
    val text = block.optJSONObject("text_by_locale")?.optJSONObject(locale) ?: JSONObject()
    val content = block.optJSONObject("content_by_locale")
    val title = text.optString("heading").ifBlank {
        if (type == "recap") {
            if (locale == "hi") "दोहराएँ" else "Recap"
        } else {
            localized(content, locale).ifBlank { if (locale == "hi") "सीखें" else "Learn" }
        }
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            when (type) {
                "rich_text" -> {
                    text.optJSONArray("paragraphs").stringItems().forEach {
                        LearningMathText(it, style = MaterialTheme.typography.bodyLarge)
                    }
                    text.optString("key_point").takeIf { it.isNotBlank() }?.let {
                        Text(it, style = MaterialTheme.typography.bodyLarge, color = LocalQuantaEdgeSemanticColors.current.primaryText)
                    }
                    text.optString("callout").takeIf { it.isNotBlank() }?.let { Text(it) }
                }
                "recap" -> {
                    val takeaways = recapTakeaways(block, locale)
                    if (takeaways.isEmpty()) {
                        Text(if (locale == "hi") "दोहराने के मुख्य बिंदु उपलब्ध नहीं हैं।" else "No recap points are available.")
                    } else {
                        takeaways.forEach { point ->
                            Text("✓ $point", style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                    val recapLocale = content?.optJSONObject(locale)
                    val vocabulary = recapLocale?.optJSONArray("vocabulary").jsonObjects()
                    if (vocabulary.isNotEmpty()) {
                        Text(if (locale == "hi") "मुख्य शब्द" else "Key vocabulary",
                            style = MaterialTheme.typography.titleSmall)
                        vocabulary.forEach { item ->
                            val term = item.optString("term")
                            val meaning = item.optString("meaning")
                            if (term.isNotBlank() || meaning.isNotBlank()) {
                                Text(listOf(term, meaning).filter { it.isNotBlank() }.joinToString(" — "))
                            }
                        }
                    }
                }
                "diagram" -> if (studentMode && !isApprovedDiagram(block)) {
                    Text(if (locale == "hi") "यह आरेख समीक्षा तक छिपा है।" else "Diagram is hidden until approved.")
                } else StructuredDiagramContent(block, locale)
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
                else -> {
                    // Unknown published/backend block types remain visible; only private answer/grading keys are omitted.
                    val fallbackFields = structuredBlockVisibleText(block, locale)
                    val fallbackAsset = block.optJSONObject("asset") ?: JSONObject()
                    val fallbackConfig = block.optJSONObject("configuration") ?: JSONObject()
                    val rawUrl = fallbackAsset.optString("url").ifBlank {
                        fallbackConfig.optString("url").ifBlank { block.optString("url") }
                    }
                    val safeUrl = rawUrl.takeIf { it.startsWith("https://") }.orEmpty()
                    val mediaPath = safeUrl.substringBefore("?").lowercase()
                    val fallbackMediaType = when {
                        mediaPath.endsWith(".mp4") || mediaPath.endsWith(".webm") ||
                            mediaPath.endsWith(".mov") || mediaPath.endsWith(".ogv") ||
                            type.contains("video", ignoreCase = true) -> "VIDEO"
                        mediaPath.endsWith(".mp3") || mediaPath.endsWith(".m4a") ||
                            mediaPath.endsWith(".wav") || mediaPath.endsWith(".aac") ||
                            mediaPath.endsWith(".flac") || mediaPath.endsWith(".oga") ||
                            type.contains("audio", ignoreCase = true) -> "AUDIO"
                        listOf(".png", ".jpg", ".jpeg", ".gif", ".webp", ".avif", ".svg")
                            .any(mediaPath::endsWith) || type.contains("image", ignoreCase = true) -> "IMAGE"
                        else -> ""
                    }
                    if (safeUrl.isNotBlank() && fallbackMediaType.isNotBlank()) {
                        val alt = fallbackAsset.optJSONObject("alt_by_locale")?.optString(locale)
                            .orEmpty().ifBlank { fallbackFields.firstOrNull().orEmpty().ifBlank { title } }
                        val caption = block.optJSONObject("caption_by_locale")?.optString(locale).orEmpty()
                        val mediaContent = JSONObject().put("title", title)
                            .put("description", alt).put("alt", alt).put("caption", caption)
                        LessonContentBlock(
                            block = JSONObject().put("block_type", fallbackMediaType)
                                .put("asset_url", safeUrl).put("content", mediaContent.toString()),
                            language = if (locale == "hi") "hi" else "en",
                            onTutorOpen = {}
                        )
                    }
                    fallbackFields.forEach { field ->
                        Text(field, style = MaterialTheme.typography.bodyLarge)
                    }
                    if (safeUrl.isNotBlank() && fallbackMediaType.isBlank()) {
                        TextButton(onClick = {
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(safeUrl))
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        }) {
                            Text(if (locale == "hi") "सामग्री खोलें ↗" else "Open resource ↗")
                        }
                    }
                    if (fallbackFields.isEmpty() && safeUrl.isBlank()) {
                        Text(if (locale == "hi") "इस सामग्री के लिए कोई पढ़ने योग्य फ़ील्ड नहीं है।"
                            else "No displayable fields are available for this content type.")
                    }
                }
            }
            val caption = localized(block.optJSONObject("caption_by_locale"), locale)
            if (caption.isNotBlank() && type != "image") {
                Text(caption, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
            Text("${index + 1}.", color = LocalQuantaEdgeSemanticColors.current.primaryText)
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
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
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
    val legacyInteraction = question.optString("legacy_interaction_type")
    val defaultOrder = options.map { it.optString("id") }
    val restoredOrder = (savedResponse as? JSONArray)?.let { saved ->
        (0 until saved.length()).map { saved.optString(it) }
    }.orEmpty()
    var orderedIds by remember(id) {
        mutableStateOf(if (restoredOrder.size == defaultOrder.size &&
            restoredOrder.toSet() == defaultOrder.toSet()) restoredOrder else defaultOrder)
    }
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
                question.optJSONObject("legacy_question_type_by_locale")?.let { localized(it, locale) }
                    ?.takeIf { it.isNotBlank() }?.let { sourceType ->
                        Text(sourceType, style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
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
                    "fill_blank" -> OutlinedTextField(
                        value = textResponse, onValueChange = { textResponse = it }, enabled = !locked && !submitting,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(if (locale == "hi") "उत्तर" else "Answer") },
                        minLines = 1
                    )
                    "short_answer" -> if (legacyInteraction == "ORDER") {
                        Text(if (locale == "hi") "सही क्रम बनाने के लिए items को ऊपर या नीचे करें।"
                            else "Reorder the items using the up and down controls.")
                        orderedIds.forEachIndexed { index, itemId ->
                            val item = options.firstOrNull { it.optString("id") == itemId } ?: return@forEachIndexed
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text((index + 1).toString() + ". " +
                                    localized(item.optJSONObject("text_by_locale"), locale),
                                    modifier = Modifier.weight(1f))
                                TextButton(enabled = !locked && !submitting && index > 0, onClick = {
                                    val next = orderedIds.toMutableList()
                                    val itemAt = next.removeAt(index)
                                    next.add(index - 1, itemAt)
                                    orderedIds = next
                                }) { Text("↑") }
                                TextButton(enabled = !locked && !submitting && index < orderedIds.lastIndex, onClick = {
                                    val next = orderedIds.toMutableList()
                                    val itemAt = next.removeAt(index)
                                    next.add(index + 1, itemAt)
                                    orderedIds = next
                                }) { Text("↓") }
                            }
                        }
                    } else OutlinedTextField(
                        value = textResponse, onValueChange = { textResponse = it }, enabled = !locked && !submitting,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(if (locale == "hi") "उत्तर" else "Answer") },
                        minLines = 3
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
                    "fill_blank" -> textResponse.isNotBlank()
                    "short_answer" -> if (legacyInteraction == "ORDER") {
                        defaultOrder.size >= 2 && orderedIds.size == defaultOrder.size &&
                            orderedIds.toSet() == defaultOrder.toSet()
                    } else textResponse.isNotBlank()
                    else -> false
                }
                Button(enabled = isAnswerComplete && !locked && !submitting, onClick = {
                    val payloadJson = if (legacyInteraction == "ORDER") {
                        JSONArray().also { array -> orderedIds.forEach { array.put(it) } }.toString()
                    } else when (type) {
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
                    Text(statusLabel, style = MaterialTheme.typography.titleSmall, color = when (savedStatus) {
                        "correct" -> LocalQuantaEdgeSemanticColors.current.success
                        "incorrect" -> LocalQuantaEdgeSemanticColors.current.error
                        "partially_correct" -> LocalQuantaEdgeSemanticColors.current.warning
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    })
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

private val PRIVATE_STRUCTURED_BLOCK_KEYS = setOf(
    "answerpayload", "answerkey", "correctanswer", "correctanswerids", "correctpairs",
    "acceptedanswers", "answerguidebylocale", "grading", "gradingnotes", "iscorrect",
    "correctoption", "solutionkey", "modelanswer", "expectedanswer", "explanationkey"
)

private fun structuredBlockVisibleText(block: JSONObject, locale: String): List<String> {
    val output = mutableListOf<String>()
    block.optJSONObject("text_by_locale")?.optJSONObject(locale)?.let {
        output += collectStructuredTextValue(it)
    }
    block.optJSONObject("content_by_locale")?.let { content ->
        val selected = content.opt(locale)
        output += collectStructuredTextValue(selected ?: content)
    }
    block.optJSONObject("instructions_by_locale")?.opt(locale)?.let {
        output += collectStructuredTextValue(it)
    }
    listOf("configuration", "diagram_data", "asset").forEach { key ->
        block.optJSONObject(key)?.let { output += collectStructuredTextValue(it) }
    }
    block.optJSONObject("caption_by_locale")?.opt(locale)?.let {
        output += collectStructuredTextValue(it)
    }
    return output.distinct().filter(String::isNotBlank)
}

private fun collectStructuredTextValue(value: Any?, prefix: String = "", depth: Int = 0): List<String> {
    if (depth > 10 || value == null || value == JSONObject.NULL) return emptyList()
    return when (value) {
        is JSONObject -> {
            val output = mutableListOf<String>()
            val keys = value.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val normalized = key.replace(Regex("[^A-Za-z0-9]"), "").lowercase()
                val privateKey = normalized in PRIVATE_STRUCTURED_BLOCK_KEYS ||
                    normalized.startsWith("correct") || normalized.startsWith("grading") ||
                    normalized.startsWith("answerpayload") || normalized.startsWith("solutionkey") ||
                    normalized.startsWith("acceptedanswer") || normalized.startsWith("modelanswer") ||
                    normalized.startsWith("expectedanswer")
                if (privateKey || normalized in setOf(
                        "id", "type", "order", "objectiveids", "sourcerefs", "url", "asseturl",
                        "renderingcontractid", "reviewstatus", "studentrenderable", "publicationallowed",
                        "assetid", "assetrefs"
                    )) continue
                if (depth == 0 && normalized in setOf("heading", "title", "name")) continue
                val label = key.replace(Regex("([A-Z])"), " $1").replace('_', ' ').replace('-', ' ').trim()
                val childPrefix = if (prefix.isBlank()) label else "$prefix · $label"
                output += collectStructuredTextValue(value.opt(key), childPrefix, depth + 1)
            }
            output
        }
        is JSONArray -> buildList {
            for (index in 0 until value.length()) {
                val childPrefix = if (prefix.isBlank()) "" else "$prefix ${index + 1}"
                addAll(collectStructuredTextValue(value.opt(index), childPrefix, depth + 1))
            }
        }
        is String -> value.trim().takeIf(String::isNotBlank)?.let {
            listOf(if (prefix.isBlank()) it else "$prefix: $it")
        } ?: emptyList()
        is Number, is Boolean -> listOf(if (prefix.isBlank()) value.toString() else "$prefix: $value")
        else -> emptyList()
    }
}

internal fun isApprovedDiagram(block: JSONObject): Boolean {
    val data = block.optJSONObject("diagram_data") ?: return false
    val review = block.optString("review_status").ifBlank { data.optString("review_status") }.uppercase()
    val renderable = if (block.has("student_renderable")) {
        block.optBoolean("student_renderable")
    } else {
        data.optBoolean("student_renderable")
    }
    val alt = block.optJSONObject("alt_by_locale")
    val nodes = data.optJSONArray("nodes")
    return renderable &&
        review in setOf("APPROVED", "APPROVED_FOR_STUDENT_RENDERING") &&
        !localized(alt, "en").isBlank() && !localized(alt, "hi").isBlank() &&
        nodes != null && nodes.length() > 0
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
