package com.quantaedge.android.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import org.json.JSONArray
import com.quantaedge.android.data.ApiClient
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive
import kotlinx.coroutines.CancellationException
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.remember
import androidx.compose.material3.OutlinedTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.heightIn
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
import okhttp3.WebSocketListener
import okhttp3.WebSocket
import okhttp3.Response
import okhttp3.Request
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.json.JSONObject
import com.quantaedge.android.data.optLongAny
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.util.Locale
import kotlinx.coroutines.delay

internal fun parseLiveClassTimestampMillis(value: String): Long? {
    val text = value.trim()
    if (text.isEmpty()) return null
    return runCatching { OffsetDateTime.parse(text).toInstant().toEpochMilli() }.getOrElse {
        runCatching { Instant.parse(text).toEpochMilli() }.getOrElse {
            runCatching { LocalDateTime.parse(text).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli() }.getOrNull()
        }
    }
}

internal fun formatLiveClassTimestamp(value: String, language: String): String {
    val millis = parseLiveClassTimestampMillis(value) ?: return ""
    val locale = if (language == "hi") Locale.forLanguageTag("hi-IN") else Locale.ENGLISH
    val formatter = java.time.format.DateTimeFormatter.ofPattern("EEE dd MMM yyyy, h:mm a", locale)
        .withZone(ZoneId.systemDefault())
    return formatter.format(Instant.ofEpochMilli(millis))
}

internal fun isLiveClassJoinWindowOpen(
    status: String, startsAt: String, endsAt: String, joinWindowMinutes: Int, nowMillis: Long
): Boolean {
    if (status !in setOf("SCHEDULED", "LIVE")) return false
    val startMillis = parseLiveClassTimestampMillis(startsAt) ?: return false
    val endMillis = parseLiveClassTimestampMillis(endsAt) ?: return false
    if (endMillis <= nowMillis) return false
    val minutes = joinWindowMinutes.coerceIn(0, 1440)
    return minutes == 0 || startMillis <= nowMillis + minutes.toLong() * 60_000L
}

internal fun isLiveClassWaitingForJoinWindow(
    status: String, startsAt: String, endsAt: String, joinWindowMinutes: Int, nowMillis: Long
): Boolean {
    if (status !in setOf("SCHEDULED", "LIVE")) return false
    val minutes = joinWindowMinutes.coerceIn(0, 1440)
    if (minutes == 0) return false
    val startMillis = parseLiveClassTimestampMillis(startsAt) ?: return false
    val endMillis = parseLiveClassTimestampMillis(endsAt) ?: return false
    return endMillis > nowMillis && startMillis > nowMillis + minutes.toLong() * 60_000L
}

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
    onRecording: (JSONObject) -> Unit,
    offlineDownloadedIds: Set<Long> = emptySet(),
    videoDownloadStates: Map<Long, String> = emptyMap(),
    onDownloadRecording: (JSONObject) -> Unit = {},
    onRemoveOfflineRecording: (JSONObject) -> Unit = {}
) {
    val language = LocalQuantaLanguage.current
    fun t(en: String, hi: String) = if (language == "hi") hi else en
    var clockNowMillis by rememberSaveable { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            clockNowMillis = System.currentTimeMillis()
            delay(30_000)
        }
    }
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
                    val status = item.textAny("status")
                    val joinWindowMinutes = item.optInt("join_window_minutes", item.optInt("joinWindowMinutes", 15)).coerceIn(0, 1440)
                    val startsAt = item.textAny("starts_at", "startsAt")
                    val endsAt = item.textAny("ends_at", "endsAt")
                    val joinWindowOpen = isLiveClassJoinWindowOpen(status, startsAt, endsAt, joinWindowMinutes, clockNowMillis)
                    val waitingForJoinWindow = isLiveClassWaitingForJoinWindow(status, startsAt, endsAt, joinWindowMinutes, clockNowMillis)
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            Text(item.textAny("title").ifBlank { "Live class" }, style = MaterialTheme.typography.titleMedium)
                            Text(item.textAny("chapter_name", "subject_name", "description"),
                                style = MaterialTheme.typography.bodyMedium)
                            val schoolName = LocalInstitutionBrandIdentity.current?.displayName?.takeIf { it.isNotBlank() }
                            val teacherFallback = when {
                                schoolName != null -> if (language == "hi") "$schoolName के शिक्षक" else "$schoolName teacher"
                                LocalInstitutionBrandResolutionPending.current -> if (language == "hi") "शिक्षक" else "Teacher"
                                language == "hi" -> "QuantaEdge शिक्षक"
                                else -> "QuantaEdge teacher"
                            }
                            Text(localizedUiText("शिक्षक: ") + item.textAny("host_staff_name").ifBlank { teacherFallback },
                                style = MaterialTheme.typography.bodySmall)
                            val startLabel = formatLiveClassTimestamp(item.textAny("starts_at", "startsAt"), language)
                            val endLabel = formatLiveClassTimestamp(item.textAny("ends_at", "endsAt"), language)
                            Text(listOf(startLabel, endLabel).filter { it.isNotBlank() }.joinToString(" – "),
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
                            Text(localizedUiText("Attendance: ") + (item.optLongAny("attended_seconds") / 60) + " मिनट • जुड़ने की संख्या: " + item.optInt("join_count"),
                                style = MaterialTheme.typography.bodySmall)
                            val youtubeLive = item.textAny("provider") == "YOUTUBE_LIVE"
                            if (joined) {
                                if (youtubeLive) {
                                    Button(onClick = { onJoin(item) }, enabled = !busy && joinWindowOpen, modifier = Modifier.fillMaxWidth()) {
                                        Text(t("Watch live broadcast", "लाइव प्रसारण देखें"))
                                    }
                                }
                                Button(onClick = { onLeave(item) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                                    Text(t("Leave class and save attendance", "कक्षा छोड़ें और उपस्थिति सहेजें"))
                                }
                            } else {
                                Button(onClick = { onJoin(item) }, enabled = !busy && joinWindowOpen,
                                    modifier = Modifier.fillMaxWidth()) {
                                    Text(if (youtubeLive) t("Watch live broadcast", "लाइव प्रसारण देखें")
                                        else t("Join live class", "लाइव कक्षा में शामिल हों"))
                                }
                                if (waitingForJoinWindow) {
                                    Text(t("Joining opens ", "कक्षा में जुड़ना शुरू होगा: ") + joinWindowMinutes +
                                        t(" minutes before start.", " मिनट पहले।"),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.secondary)
                                }
                            }
                            if (item.optBoolean("has_recording")) {
                                Text(localizedUiText("इस class की published recording भी उपलब्ध है।"), style = MaterialTheme.typography.bodySmall)
                            }
                            val chatSessionId = item.longAny("session_id", "sessionId")
                            if (chatSessionId > 0L) {
                                LearningChatPanel(sessionId = chatSessionId, locale = language, busy = busy)
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
                    val recordingId = item.longAny("recorded_class_id", "recordingId", "id")
                    val isDownloaded = recordingId in offlineDownloadedIds
                    val downloadState = videoDownloadStates[recordingId].orEmpty()
                    val transferActive = downloadState.startsWith("Downloading", ignoreCase = true) ||
                        downloadState.startsWith("Queued", ignoreCase = true) ||
                        downloadState.startsWith("डाउनलोड चल", ignoreCase = true) ||
                        downloadState.startsWith("डाउनलोड कतार", ignoreCase = true)
                    val downloadAllowed = item.optBoolean("download_allowed") && item.optLongAny("download_resource_id") > 0L
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
                            if (isDownloaded) {
                                Text(t("Available offline on this device", "इस डिवाइस पर offline उपलब्ध"),
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
                                Button(onClick = { onRemoveOfflineRecording(item) }, enabled = !busy && !transferActive,
                                    modifier = Modifier.fillMaxWidth()) {
                                    Text(t("Remove offline copy", "Offline copy हटाएँ"))
                                }
                            } else if (downloadAllowed) {
                                Button(onClick = { onDownloadRecording(item) }, enabled = !busy && !transferActive,
                                    modifier = Modifier.fillMaxWidth()) {
                                    Text(when {
                                        transferActive -> downloadState
                                        downloadState.contains("failed", ignoreCase = true) ||
                                            downloadState.contains("विफल", ignoreCase = true) -> t("Retry offline download", "Offline डाउनलोड फिर करें")
                                        else -> t("Download for offline playback", "Offline देखने के लिए डाउनलोड करें")
                                    })
                                }
                                if (downloadState.isNotBlank()) {
                                    Text(downloadState, style = MaterialTheme.typography.bodySmall,
                                        color = if (downloadState.contains("failed", ignoreCase = true) ||
                                            downloadState.contains("विफल", ignoreCase = true)) MaterialTheme.colorScheme.error
                                        else MaterialTheme.colorScheme.secondary)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}


@Composable
private fun LearningChatPanel(sessionId: Long, locale: String, busy: Boolean) {
    fun t(en: String, hi: String) = if (locale == "hi") hi else en
    var expanded by rememberSaveable(sessionId) { mutableStateOf(false) }
    var conversations by remember(sessionId) { mutableStateOf<List<JSONObject>>(emptyList()) }
    var activeConversationId by rememberSaveable(sessionId) { mutableStateOf(0L) }
    var messages by remember(sessionId) { mutableStateOf<List<JSONObject>>(emptyList()) }
    var draft by rememberSaveable(sessionId) { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var sending by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    var realtimeReconnectToken by remember { mutableStateOf(0) }
    var realtimeRetryDelay by remember { mutableStateOf(2_000L) }

    fun scheduleRealtimeReconnect(stillActive: () -> Boolean) {
        scope.launch {
            delay(realtimeRetryDelay)
            if (stillActive()) {
                realtimeRetryDelay = (realtimeRetryDelay * 2).coerceAtMost(30_000L)
                realtimeReconnectToken += 1
            }
        }
    }

    suspend fun loadConversations(): List<JSONObject> {
        val array = ApiClient.getArray("/api/v1/learning/live-classes/$sessionId/conversations")
        val result = ArrayList<JSONObject>()
        for (index in 0 until array.length()) array.optJSONObject(index)?.let(result::add)
        conversations = result
        return result
    }

    suspend fun openConversation(type: String) {
        error = ""
        val row = ApiClient.postObject(
            "/api/v1/learning/live-classes/$sessionId/conversations",
            JSONObject().put("conversationType", type)
        )
        activeConversationId = row.optLong("conversationId", 0L)
        loadConversations()
    }

    LaunchedEffect(expanded, sessionId) {
        if (!expanded) return@LaunchedEffect
        loading = true
        error = ""
        try {
            val list = loadConversations()
            val classroom = list.firstOrNull { it.optString("conversationType") == "CLASSROOM" }
            if (classroom != null) {
                activeConversationId = classroom.optLong("conversationId", 0L)
            } else {
                openConversation("CLASSROOM")
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            error = failure.message ?: t("Chat could not be loaded.", "चैट लोड नहीं हो सकी।")
        } finally {
            loading = false
        }
    }

    LaunchedEffect(expanded, activeConversationId, sessionId) {
        if (!expanded || activeConversationId <= 0L) return@LaunchedEffect
        while (isActive) {
            try {
                val response = ApiClient.getObject(
                    "/api/v1/learning/conversations/$activeConversationId/messages?limit=60"
                )
                val array = response.optJSONArray("messages") ?: JSONArray()
                val latest = ArrayList<JSONObject>()
                for (index in 0 until array.length()) array.optJSONObject(index)?.let(latest::add)
                messages = latest
                error = ""
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                error = failure.message ?: t("Messages could not be refreshed.", "संदेश रीफ़्रेश नहीं हो सके।")
            }
            delay(4_000)
        }
    }

    // Native WebSocket delivery is low-latency; API polling above recovers messages after reconnect.
    DisposableEffect(expanded, activeConversationId, sessionId, realtimeReconnectToken) {
        var socket: WebSocket? = null
        var disposed = false
        if (expanded && activeConversationId > 0L) {
            val workspace = ApiClient.workspaceName()
            val httpUrl = ApiClient.absoluteUrl("/api/v1/learning-chat/ws?workspace=" + ApiClient.encodeQuery(workspace))
            val wsUrl = httpUrl.replaceFirst("https://", "wss://").replaceFirst("http://", "ws://")
            val request = Request.Builder().url(wsUrl)
                .header("X-QE-Workspace", workspace)
                .header("X-QE-Client", "ANDROID")
                .build()
            socket = ApiClient.authenticatedHttpClient().newWebSocket(request, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    scope.launch { realtimeRetryDelay = 2_000L }
                    webSocket.send("CONNECT\naccept-version:1.2\nheart-beat:0,0\n\n\u0000")
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    for (raw in text.split('\u0000')) {
                        val frame = raw.trimStart('\n', '\r')
                        if (frame.startsWith("CONNECTED")) {
                            webSocket.send(
                                "SUBSCRIBE\nid:qe-android-$sessionId-$activeConversationId\n" +
                                    "destination:/user/queue/learning-conversations/$activeConversationId\n" +
                                    "ack:auto\n\n\u0000"
                            )
                        } else if (frame.startsWith("MESSAGE")) {
                            val split = frame.indexOf("\n\n")
                            if (split >= 0) {
                                runCatching { JSONObject(frame.substring(split + 2)) }.getOrNull()?.let { message ->
                                    scope.launch {
                                        if (message.optLong("messageId") > 0L &&
                                            messages.none { it.optLong("messageId") == message.optLong("messageId") }) {
                                            messages = (messages + message).takeLast(60)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    scheduleRealtimeReconnect { !disposed }
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    scheduleRealtimeReconnect { !disposed }
                }
            })
        }
        onDispose {
            disposed = true
            runCatching { socket?.send("DISCONNECT\n\n\u0000") }
            runCatching { socket?.close(1000, "chat panel changed") }
        }
    }

    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(7.dp)) {
        TextButton(onClick = { expanded = !expanded }, enabled = !busy) {
            Text(if (expanded) t("Hide class chat −", "कक्षा चैट छिपाएँ −") else t("Class chat & message teacher +", "कक्षा चैट और शिक्षक को संदेश +"))
        }
        if (expanded) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                        Button(
                            onClick = { scope.launch { runCatching { openConversation("CLASSROOM") }.onFailure { error = it.message ?: t("Could not open class chat.", "कक्षा चैट नहीं खुली।") } } },
                            enabled = !busy && !loading,
                            modifier = Modifier.weight(1f)
                        ) { Text(t("Class chat", "कक्षा चैट")) }
                        Button(
                            onClick = { scope.launch { runCatching { openConversation("STUDENT_TEACHER") }.onFailure { error = it.message ?: t("Could not open private thread.", "निजी बातचीत नहीं खुली।") } } },
                            enabled = !busy && !loading,
                            modifier = Modifier.weight(1f)
                        ) { Text(t("Private teacher", "शिक्षक को निजी संदेश")) }
                    }
                    if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    if (loading) Text(t("Loading messages…", "संदेश लोड हो रहे हैं…"), style = MaterialTheme.typography.bodySmall)
                    Column(
                        modifier = Modifier.fillMaxWidth().heightIn(max = 230.dp).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(7.dp)
                    ) {
                        val visible = messages.takeLast(30)
                        if (visible.isEmpty()) {
                            Text(t("No messages yet. Start the conversation.", "अभी संदेश नहीं हैं। बातचीत शुरू करें।"),
                                style = MaterialTheme.typography.bodySmall)
                        } else visible.forEach { message ->
                            Column(modifier = Modifier.fillMaxWidth().padding(7.dp)) {
                                Text(
                                    message.optString("senderName").ifBlank { t("Member", "सदस्य") } +
                                        if (message.optBoolean("pinned")) " · 📌" else "",
                                    style = MaterialTheme.typography.labelMedium
                                )
                                Text(message.optString("body"), style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = androidx.compose.ui.Alignment.Bottom) {
                        OutlinedTextField(
                            value = draft, onValueChange = { draft = it.take(4000) },
                            modifier = Modifier.weight(1f), maxLines = 3,
                            label = { Text(t("Message", "संदेश")) }
                        )
                        Button(onClick = {
                            val body = draft.trim()
                            if (body.isNotEmpty() && activeConversationId > 0L && !sending) {
                                scope.launch {
                                    sending = true
                                    error = ""
                                    try {
                                        val saved = ApiClient.postObject(
                                            "/api/v1/learning/conversations/$activeConversationId/messages",
                                            JSONObject()
                                                .put("clientMessageId", java.util.UUID.randomUUID().toString())
                                                .put("body", body)
                                                .put("messageType", "TEXT")
                                        )
                                        draft = ""
                                        messages = (messages.filterNot { it.optLong("messageId") == saved.optLong("messageId") } + saved)
                                            .takeLast(60)
                                    } catch (failure: Exception) {
                                        error = failure.message ?: t("Message could not be sent.", "संदेश नहीं भेजा जा सका।")
                                    } finally { sending = false }
                                }
                            }
                        }, enabled = !busy && !loading && !sending && draft.isNotBlank() && activeConversationId > 0L) {
                            Text(if (sending) t("Sending…", "भेज रहे हैं…") else t("Send", "भेजें"))
                        }
                    }
                    Text(t("Messages refresh automatically. Do not share passwords or personal contact details.",
                        "संदेश अपने आप रीफ़्रेश होते हैं। पासवर्ड या व्यक्तिगत संपर्क विवरण साझा न करें।"),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
                }
            }
        }
    }
}
