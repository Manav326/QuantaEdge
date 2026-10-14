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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.quantaedge.android.R
import org.json.JSONObject

private data class TutorModeUi(val key: String, val icon: String, val english: String, val hindi: String)

private val tutorModes = listOf(
    TutorModeUi("HINT", "💡", "Hint", "संकेत"),
    TutorModeUi("EXPLAIN", "☷", "Explain", "समझाएँ"),
    TutorModeUi("EXAMPLE", "▦", "Example", "उदाहरण"),
    TutorModeUi("STEP_BY_STEP", "1→2", "Step by step", "चरण-दर-चरण"),
    TutorModeUi("CHECK_MY_WORK", "✓", "Check my work", "मेरी जाँच करें")
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TutorScreen(
    lessonTitle: String,
    available: Boolean,
    messages: List<JSONObject>,
    busy: Boolean,
    error: String,
    onSend: (String, String) -> Unit,
    onClose: () -> Unit
) {
    val language = LocalQuantaLanguage.current
    fun t(en: String, hi: String) = if (language == "hi") hi else en
    var input by rememberSaveable { mutableStateOf("") }
    var selectedMode by rememberSaveable { mutableStateOf("HINT") }

    Column(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
        verticalArrangement = Arrangement.spacedBy(0.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().height(54.dp)
                .background(Color(0xFF151B2C)).padding(horizontal = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Image(
                painter = painterResource(R.drawable.quantaedge_icon),
                contentDescription = "QuantaEdge",
                modifier = Modifier.size(29.dp).clip(RoundedCornerShape(8.dp))
            )
            Text(
                text = t("Your tutor", "आपका ट्यूटर"),
                color = Color.White,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = Color(0xFF292C37),
                contentColor = Color(0xFFFED7AA),
                modifier = Modifier.padding(start = 1.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    Text("✦", fontSize = 12.sp)
                    Text("AI", fontSize = 8.sp, fontWeight = FontWeight.Black)
                }
            }
            Spacer(Modifier.weight(1f))
            IconButton(onClick = onClose, modifier = Modifier.size(32.dp)) {
                Text("×", color = Color.White, fontSize = 23.sp, lineHeight = 23.sp)
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().height(46.dp)
                .background(MaterialTheme.colorScheme.surface)
                .padding(horizontal = 8.dp, vertical = 4.dp),
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
                        modifier = Modifier.weight(1f).height(37.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .clickable(enabled = !busy) { selectedMode = mode.key }
                            .semantics { contentDescription = modeLabel },
                        shape = RoundedCornerShape(10.dp),
                        color = if (selectedMode == mode.key) Color(0xFF172033) else MaterialTheme.colorScheme.surfaceVariant,
                        contentColor = if (selectedMode == mode.key) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                        tonalElevation = if (selectedMode == mode.key) 2.dp else 0.dp
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(
                                mode.icon,
                                fontSize = if (mode.icon == "1→2") 13.sp else 19.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }
            }
        }

        if (!available) {
            Box(
                modifier = Modifier.weight(1f).fillMaxWidth().padding(16.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(t("Tutor unavailable", "ट्यूटर अभी उपलब्ध नहीं"), style = MaterialTheme.typography.titleMedium)
                    Text(
                        t("The tutor is not enabled right now.", "ट्यूटर अभी चालू नहीं है।"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (error.isNotBlank()) {
                        Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 9.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(7.dp)
            ) {
                if (messages.isEmpty()) {
                    item {
                        Text(
                            t("Ask me about this lesson.", "इस पाठ के बारे में पूछें।"),
                            modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
                items(messages) { item ->
                    val isAssistant = item.textAny("role").equals("ASSISTANT", true)
                    val bubbleColor = if (isAssistant) MaterialTheme.colorScheme.surface else Color(0xFFFFF0E3)
                    val bubbleText = if (isAssistant) MaterialTheme.colorScheme.onSurface else Color(0xFF4A2E21)
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = if (isAssistant) Alignment.Start else Alignment.End
                    ) {
                        Surface(
                            shape = RoundedCornerShape(13.dp),
                            color = bubbleColor,
                            contentColor = bubbleText,
                            tonalElevation = 1.dp,
                            modifier = Modifier.widthIn(max = 620.dp)
                        ) {
                            Column(
                                modifier = Modifier.padding(horizontal = 11.dp, vertical = 9.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        if (isAssistant) t("Tutor", "ट्यूटर") else t("You", "आप"),
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = if (isAssistant) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary
                                    )
                                    val modeData = tutorModes.firstOrNull { it.key == item.textAny("mode").uppercase() }
                                    if (modeData != null) Text(modeData.icon, style = MaterialTheme.typography.labelSmall)
                                }
                                Text(item.textAny("message"), style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }
                if (busy) {
                    item {
                        Row(
                            modifier = Modifier.padding(vertical = 6.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            Text(t("Thinking…", "सोच रहा हूँ…"), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
            if (error.isNotBlank()) {
                Text(
                    error,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 3.dp),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }

        if (available) {
            Row(
                modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface)
                    .padding(horizontal = 8.dp, vertical = 7.dp),
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
                    maxLines = 2
                )
                IconButton(
                    onClick = {
                        val message = input.trim()
                        if (message.isNotEmpty() && !busy) {
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
