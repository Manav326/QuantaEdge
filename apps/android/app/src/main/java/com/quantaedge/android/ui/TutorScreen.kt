package com.quantaedge.android.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.quantaedge.android.R
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

private data class TutorModeUi(val key: String, val icon: String, val english: String, val hindi: String)

private val tutorModes = listOf(
    TutorModeUi("HINT", "💡", "Hint", "संकेत"),
    TutorModeUi("EXPLAIN", "☷", "Explain", "समझाएँ"),
    TutorModeUi("EXAMPLE", "▦", "Example", "उदाहरण"),
    TutorModeUi("STEP_BY_STEP", "1→2", "Step by step", "चरण-दर-चरण"),
    TutorModeUi("CHECK_MY_WORK", "✓", "Check my work", "मेरी जाँच करें")
)



internal data class TutorMessageBlock(
    val kind: String,
    val text: String = "",
    val level: Int = 0,
    val items: List<String> = emptyList(),
    val headers: List<String> = emptyList(),
    val rows: List<List<String>> = emptyList()
)

/** Keeps headings, lists, tables, code and equations structured like the web tutor renderer. */
internal fun parseTutorMessageBlocks(message: String): List<TutorMessageBlock> {
    val lines = message.replace("\r\n", "\n").replace('\r', '\n').split('\n')
    val blocks = mutableListOf<TutorMessageBlock>()
    var paragraph = mutableListOf<String>()
    var listKind: String? = null
    var listItems = mutableListOf<String>()

    fun flushParagraph() {
        if (paragraph.isNotEmpty()) blocks += TutorMessageBlock("paragraph", paragraph.joinToString("\n"))
        paragraph = mutableListOf()
    }
    fun flushList() {
        if (listKind != null && listItems.isNotEmpty()) {
            blocks += TutorMessageBlock(listKind!!, items = listItems.toList())
        }
        listKind = null
        listItems = mutableListOf()
    }
    fun cells(line: String): List<String> =
        line.trim().removePrefix("|").removeSuffix("|").split('|').map { it.trim() }

    var index = 0
    while (index < lines.size) {
        val trimmed = lines[index].trim()
        if (trimmed.isEmpty()) {
            flushParagraph()
            flushList()
            index++
            continue
        }
        if (trimmed.startsWith("```")) {
            flushParagraph()
            flushList()
            val code = mutableListOf<String>()
            index++
            while (index < lines.size && !lines[index].trim().startsWith("```")) {
                code += lines[index]
                index++
            }
            blocks += TutorMessageBlock("code", code.joinToString("\n"))
            index++
            continue
        }
        if (trimmed == "\u0024\u0024") {
            flushParagraph()
            flushList()
            val equation = mutableListOf<String>()
            index++
            while (index < lines.size && lines[index].trim() != "\u0024\u0024") {
                equation += lines[index]
                index++
            }
            blocks += TutorMessageBlock("equation", equation.joinToString("\n"))
            index++
            continue
        }
        if (trimmed.startsWith("\u0024\u0024") && trimmed.endsWith("\u0024\u0024") && trimmed.length >= 4) {
            flushParagraph()
            flushList()
            blocks += TutorMessageBlock("equation", trimmed.substring(2, trimmed.length - 2).trim())
            index++
            continue
        }
        val nextLine = lines.getOrNull(index + 1)?.trim().orEmpty()
        val isTableSeparator = nextLine.matches(Regex("^\\|?\\s*:?-{3,}:?\\s*(?:\\|\\s*:?-{3,}:?\\s*)+\\|?$"))
        if ('|' in trimmed && isTableSeparator) {
            flushParagraph()
            flushList()
            val headers = cells(trimmed)
            val rows = mutableListOf<List<String>>()
            index += 2
            while (index < lines.size && lines[index].trim().isNotEmpty() && '|' in lines[index]) {
                rows += cells(lines[index])
                index++
            }
            blocks += TutorMessageBlock("table", headers = headers, rows = rows)
            continue
        }
        val heading = Regex("^(#{1,3})\\s+(.+)$").matchEntire(trimmed)
        if (heading != null) {
            flushParagraph()
            flushList()
            blocks += TutorMessageBlock("heading", heading.groupValues[2], heading.groupValues[1].length)
            index++
            continue
        }
        val listItem = Regex("^(?:([-*+•])|(\\d+)[.)])\\s+(.+)$").matchEntire(trimmed)
        if (listItem != null) {
            flushParagraph()
            val nextKind = if (listItem.groupValues[2].isNotEmpty()) "ordered" else "unordered"
            if (listKind != null && listKind != nextKind) flushList()
            listKind = nextKind
            listItems += listItem.groupValues[3]
            index++
            continue
        }
        flushList()
        paragraph += trimmed
        index++
    }
    flushParagraph()
    flushList()
    return blocks
}

@Composable
private fun tutorInlineText(value: String): androidx.compose.ui.text.AnnotatedString {
    val inlineCodeBackground = MaterialTheme.colorScheme.surfaceVariant
    val inlineMathColor = MaterialTheme.colorScheme.primary
    return buildAnnotatedString {
    val pattern = Regex("(\\*\\*[^*]+\\*\\*|`[^`]+`|\\u0024[^\\u0024\\n]+\\u0024|\\*[^*\\n]+\\*)")
    var cursor = 0
    for (match in pattern.findAll(value)) {
        append(value.substring(cursor, match.range.first))
        val token = match.value
        when {
            token.startsWith("**") && token.endsWith("**") ->
                withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(token.substring(2, token.length - 2)) }
            token.startsWith("`") && token.endsWith("`") ->
                withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = inlineCodeBackground)) {
                    append(token.substring(1, token.length - 1))
                }
            token.startsWith("\u0024") && token.endsWith("\u0024") ->
                withStyle(SpanStyle(fontFamily = FontFamily.Monospace, color = inlineMathColor)) {
                    append(token.substring(1, token.length - 1))
                }
            token.startsWith("*") && token.endsWith("*") ->
                withStyle(SpanStyle(fontStyle = androidx.compose.ui.text.font.FontStyle.Italic)) {
                    append(token.substring(1, token.length - 1))
                }
            else -> append(token)
        }
        cursor = match.range.last + 1
    }
    append(value.substring(cursor))
    }
}
@Composable
private fun TutorMessageBody(message: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(7.dp)) {
        parseTutorMessageBlocks(message).forEach { block ->
            when (block.kind) {
                "heading" -> Text(
                    text = tutorInlineText(block.text),
                    style = if (block.level <= 1) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                "ordered", "unordered" -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    block.items.forEachIndexed { index, item ->
                        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                            Text(
                                if (block.kind == "ordered") (index + 1).toString() + "." else "•",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text(text = tutorInlineText(item), style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
                "table" -> Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant
                ) {
                    Column(modifier = Modifier.horizontalScroll(rememberScrollState()).padding(1.dp)) {
                        (listOf(block.headers) + block.rows).forEachIndexed { rowIndex, row ->
                            Row {
                                row.forEach { cell ->
                                    Box(modifier = Modifier.widthIn(min = 96.dp, max = 190.dp).padding(8.dp)) {
                                        Text(
                                            text = tutorInlineText(cell),
                                            style = MaterialTheme.typography.bodySmall,
                                            fontWeight = if (rowIndex == 0) FontWeight.Bold else FontWeight.Normal
                                        )
                                    }
                                }
                            }
                            if (rowIndex < block.rows.size) {
                                androidx.compose.material3.HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                            }
                        }
                    }
                }
                "equation" -> Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant
                ) {
                    Text(
                        block.text,
                        modifier = Modifier.horizontalScroll(rememberScrollState()).padding(10.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        fontFamily = FontFamily.Monospace
                    )
                }
                "code" -> Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant
                ) {
                    Text(
                        block.text,
                        modifier = Modifier.horizontalScroll(rememberScrollState()).padding(10.dp),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace
                    )
                }
                else -> Text(text = tutorInlineText(block.text), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

private fun tutorTimestampLabel(message: JSONObject): String {
    val raw = listOf("created_at", "createdAt", "created-at")
        .firstNotNullOfOrNull { key ->
            message.opt(key)?.takeUnless { it == JSONObject.NULL || it.toString() == "null" }
        } ?: return ""
    val value = raw.toString().trim()
    val millis = when (raw) {
        is Number -> raw.toLong()
        else -> value.toLongOrNull() ?: listOf(
            "yyyy-MM-dd'T'HH:mm:ss.SSSXXX",
            "yyyy-MM-dd'T'HH:mm:ssXXX",
            "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
            "yyyy-MM-dd'T'HH:mm:ss'Z'",
            "yyyy-MM-dd HH:mm:ss.SSS",
            "yyyy-MM-dd HH:mm:ss"
        ).firstNotNullOfOrNull { pattern ->
            runCatching {
                SimpleDateFormat(pattern, Locale.US).apply {
                    isLenient = false
                    if (pattern.endsWith("'Z'")) timeZone = TimeZone.getTimeZone("UTC")
                }.parse(value)?.time
            }.getOrNull()
        } ?: return ""
    }
    val messageDay = Calendar.getInstance().apply { timeInMillis = millis }
    val today = Calendar.getInstance()
    val sameDay = messageDay.get(Calendar.YEAR) == today.get(Calendar.YEAR)
        && messageDay.get(Calendar.DAY_OF_YEAR) == today.get(Calendar.DAY_OF_YEAR)
    val pattern = if (sameDay) "h:mm a" else "MMM d, h:mm a"
    return SimpleDateFormat(pattern, Locale.getDefault()).format(Date(millis))
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TutorScreen(
    lessonTitle: String,
    learningLanguage: String,
    sessionId: Long,
    connecting: Boolean,
    available: Boolean,
    messages: List<JSONObject>,
    hasOlderMessages: Boolean,
    loadingOlderMessages: Boolean,
    busy: Boolean,
    error: String,
    onSend: (String, String) -> Unit,
    onLoadOlder: () -> Unit,
    onNewChat: () -> Unit,
    onRetry: () -> Unit,
    onClose: () -> Unit
) {
    val language = LocalQuantaLanguage.current
    fun t(en: String, hi: String) = if (language == "hi") hi else en
    var input by rememberSaveable(sessionId) { mutableStateOf("") }
    var pendingInput by rememberSaveable(sessionId) { mutableStateOf("") }
    var selectedMode by rememberSaveable(sessionId) { mutableStateOf("EXPLAIN") }

    androidx.compose.runtime.LaunchedEffect(busy, error, sessionId) {
        if (!busy && pendingInput.isNotBlank()) {
            if (error.isNotBlank()) input = pendingInput
            pendingInput = ""
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
        verticalArrangement = Arrangement.spacedBy(0.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().height(58.dp)
                .background(Color(0xFF151B2C)).padding(horizontal = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Image(
                painter = painterResource(R.drawable.quantaedge_icon),
                contentDescription = "QuantaEdge",
                modifier = Modifier.size(29.dp).clip(RoundedCornerShape(8.dp))
            )
            Column(verticalArrangement = Arrangement.spacedBy(1.dp), modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(t("Your tutor", "आपका ट्यूटर"), color = Color.White, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Surface(shape = RoundedCornerShape(8.dp), color = Color(0xFF292C37), contentColor = Color(0xFFFED7AA)) {
                        Row(modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp), horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("✦", fontSize = 11.sp)
                            Text("AI", fontSize = 8.sp, fontWeight = FontWeight.Black)
                        }
                    }
                }
                Text(
                    t("Learning: ", "पढ़ाई: ") + if (learningLanguage == "hi") t("Hindi", "हिन्दी") else "English",
                    color = Color(0xFFC6D0E0),
                    fontSize = 10.sp,
                    maxLines = 1
                )
            }
            TooltipBox(
                positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
                tooltip = { PlainTooltip { Text(t("Start a new chat", "नई चैट शुरू करें")) } },
                state = rememberTooltipState()
            ) {
                IconButton(onClick = onNewChat, enabled = !busy && !connecting && available, modifier = Modifier.size(35.dp)) {
                    Text("＋", color = Color.White, fontSize = 23.sp)
                }
            }
            TooltipBox(
                positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
                tooltip = { PlainTooltip { Text(t("Close tutor", "ट्यूटर बंद करें")) } },
                state = rememberTooltipState()
            ) {
                IconButton(onClick = onClose, modifier = Modifier.size(35.dp)) {
                    Text("×", color = Color.White, fontSize = 23.sp, lineHeight = 23.sp)
                }
            }
        }

        if (connecting) {
            Box(modifier = Modifier.weight(1f).fillMaxWidth().padding(18.dp), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    CircularProgressIndicator()
                    Text(t("Connecting to your lesson…", "आपके पाठ से जुड़ रहे हैं…"), style = MaterialTheme.typography.bodyMedium)
                    if (lessonTitle.isNotBlank()) {
                        Text(lessonTitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        } else if (!available) {
            Box(modifier = Modifier.weight(1f).fillMaxWidth().padding(18.dp), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
                        Box(modifier = Modifier.size(46.dp), contentAlignment = Alignment.Center) {
                            Text("✦", fontSize = 23.sp, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                    Text(t("Tutor is temporarily unavailable", "ट्यूटर अभी उपलब्ध नहीं है"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        error.ifBlank { t("Please try again in a moment.", "कृपया थोड़ी देर बाद फिर प्रयास करें।") },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Button(onClick = onRetry, enabled = !busy) { Text(t("Retry connection", "फिर से जोड़ें")) }
                }
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface)
                    .horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 5.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                tutorModes.forEach { mode ->
                    val modeLabel = t(mode.english, mode.hindi)
                    TooltipBox(
                        positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
                        tooltip = { PlainTooltip { Text(modeLabel) } },
                        state = rememberTooltipState()
                    ) {
                        Surface(
                            modifier = Modifier.size(width = 57.dp, height = 38.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .clickable(enabled = !connecting) { selectedMode = mode.key }
                                .semantics { contentDescription = modeLabel },
                            shape = RoundedCornerShape(10.dp),
                            color = if (selectedMode == mode.key) Color(0xFF172033) else MaterialTheme.colorScheme.surfaceVariant,
                            contentColor = if (selectedMode == mode.key) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                            tonalElevation = if (selectedMode == mode.key) 2.dp else 0.dp
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text(mode.icon, fontSize = if (mode.icon == "1→2") 13.sp else 19.sp, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                }
            }

            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 9.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (hasOlderMessages) {
                    item(key = "load-older-messages") {
                        OutlinedButton(onClick = onLoadOlder, enabled = !loadingOlderMessages, modifier = Modifier.fillMaxWidth()) {
                            if (loadingOlderMessages) {
                                CircularProgressIndicator(modifier = Modifier.size(15.dp), strokeWidth = 2.dp)
                                Spacer(Modifier.size(8.dp))
                                Text(t("Loading older messages…", "पुराने संदेश लोड हो रहे हैं…"))
                            } else Text(t("↑ Load older messages", "↑ पुराने संदेश लोड करें"))
                        }
                    }
                }
                if (messages.isEmpty()) {
                    item(key = "tutor-welcome") {
                        Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth()) {
                            Text(
                                t("Ask me about this lesson.", "इस पाठ के बारे में पूछें।"),
                                modifier = Modifier.padding(14.dp),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                }
                items(messages, key = { item ->
                    item.opt("id")?.toString()?.takeIf { it.isNotBlank() && it != "null" }
                        ?: (item.textAny("role") + "-" + item.hashCode())
                }) { item ->
                    val isAssistant = item.textAny("role").equals("ASSISTANT", true)
                    val bubbleColor = if (isAssistant) MaterialTheme.colorScheme.surface else Color(0xFFFFF0E3)
                    val bubbleText = if (isAssistant) MaterialTheme.colorScheme.onSurface else Color(0xFF4A2E21)
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = if (isAssistant) Alignment.Start else Alignment.End
                    ) {
                        Surface(shape = RoundedCornerShape(14.dp), color = bubbleColor, contentColor = bubbleText, tonalElevation = 1.dp, modifier = Modifier.widthIn(max = 620.dp)) {
                            Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                Row(horizontalArrangement = Arrangement.spacedBy(7.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        if (isAssistant) t("Tutor", "ट्यूटर") else t("You", "आप"),
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = if (isAssistant) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary
                                    )
                                    tutorModes.firstOrNull { it.key == item.textAny("mode").uppercase() }?.let { Text(it.icon, style = MaterialTheme.typography.labelSmall) }
                                }
                                TutorMessageBody(item.textAny("message"))
                                val timestamp = tutorTimestampLabel(item)
                                if (timestamp.isNotBlank()) {
                                    Text(
                                        timestamp,
                                        modifier = Modifier.align(Alignment.End),
                                        color = if (isAssistant) MaterialTheme.colorScheme.onSurfaceVariant else Color(0xFF98694F),
                                        style = MaterialTheme.typography.labelSmall,
                                        fontSize = 9.sp
                                    )
                                }
                            }
                        }
                    }
                }
                if (busy) {
                    item(key = "tutor-thinking") {
                        Row(modifier = Modifier.padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            Text(t("Thinking…", "सोच रहा हूँ…"), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }

            if (error.isNotBlank()) {
                Surface(modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 3.dp), shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.errorContainer) {
                    Text(error, modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp), color = MaterialTheme.colorScheme.onErrorContainer, style = MaterialTheme.typography.bodySmall)
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(horizontal = 8.dp, vertical = 7.dp),
                horizontalArrangement = Arrangement.spacedBy(7.dp),
                verticalAlignment = Alignment.Bottom
            ) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it.take(1200) },
                    modifier = Modifier.weight(1f),
                    enabled = !busy,
                    placeholder = { Text(t("Ask about this lesson…", "इस पाठ के बारे में पूछें…")) },
                    minLines = 2,
                    maxLines = 4
                )
                IconButton(
                    onClick = {
                        val message = input.trim()
                        if (message.isNotEmpty() && !busy) {
                            pendingInput = message
                            input = ""
                            onSend(message, selectedMode)
                        }
                    },
                    enabled = !busy && input.isNotBlank(),
                    modifier = Modifier.size(44.dp)
                ) {
                    Text(if (busy) "…" else "↑", fontSize = 24.sp, lineHeight = 24.sp)
                }
            }
        }
    }
}

