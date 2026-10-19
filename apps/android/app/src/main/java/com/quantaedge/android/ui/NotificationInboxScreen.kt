package com.quantaedge.android.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.quantaedge.android.data.ApiClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun NotificationBellButton(language: String, onNavigate: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    var unreadCount by remember { mutableStateOf(0) }
    var loaded by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    suspend fun refreshCount() {
        runCatching {
            val response = ApiClient.getObject("/api/v1/notifications?limit=100&unreadOnly=true")
            response.optJSONArray("items")?.length() ?: 0
        }.onSuccess {
            unreadCount = it
            loaded = true
        }
    }

    LaunchedEffect(Unit) {
        refreshCount()
        while (true) {
            delay(60_000L)
            refreshCount()
        }
    }

    Box {
        IconButton(onClick = { open = true }) {
            Text("♧", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface)
        }
        if (unreadCount > 0) {
            Box(
                modifier = Modifier.align(Alignment.TopEnd).padding(top = 2.dp, end = 1.dp)
                    .size(if (unreadCount > 99) 23.dp else 19.dp)
                    .clip(CircleShape).background(MaterialTheme.colorScheme.primary),
                contentAlignment = Alignment.Center
            ) {
                Text(if (unreadCount > 99) "99+" else unreadCount.toString(),
                    color = MaterialTheme.colorScheme.onPrimary,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold)
            }
        }
    }

    if (open) {
        NotificationInboxDialog(
            language = language,
            onDismiss = { open = false },
            onChanged = { scope.launch { refreshCount() } },
            onNavigate = onNavigate
        )
    }
}

private data class NotificationRow(
    val id: Long,
    val title: String,
    val summary: String,
    val senderLabel: String,
    val scope: String,
    val state: String,
    val deliveredAt: String,
    val expiresAt: String,
    val content: JSONObject
)

@Composable
private fun NotificationInboxDialog(
    language: String,
    onDismiss: () -> Unit,
    onChanged: () -> Unit,
    onNavigate: (String) -> Unit
) {
    val hindi = language == "hi"
    val scope = rememberCoroutineScope()
    var items by remember { mutableStateOf<List<NotificationRow>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var expandedId by remember { mutableStateOf(0L) }
    var beforeId by remember { mutableStateOf<Long?>(null) }
    var hasMore by remember { mutableStateOf(false) }
    var filterUnread by remember { mutableStateOf(false) }
    var filterArchived by remember { mutableStateOf(false) }

    fun text(en: String, hi: String) = if (hindi) hi else en

    suspend fun load(append: Boolean = false, unreadOnly: Boolean = filterUnread) {
        loading = true
        error = ""
        try {
            val query = buildString {
                append("/api/v1/notifications?limit=50&unreadOnly=")
                append(unreadOnly)
                if (append && beforeId != null) append("&beforeId=").append(beforeId)
            }
            val response = ApiClient.getObject(query)
            val array = response.optJSONArray("items") ?: JSONArray()
            val next = (0 until array.length()).mapNotNull { index ->
                val row = array.optJSONObject(index) ?: return@mapNotNull null
                val content = row.optJSONObject("contentDocument") ?: JSONObject()
                NotificationRow(
                    id = row.optLong("recipient_id"),
                    title = row.optString("title", text("Notification", "सूचना")),
                    summary = row.optString("summary"),
                    senderLabel = row.optString("sender_label"),
                    scope = row.optString("scope", "USER"),
                    state = row.optString("state", "CREATED"),
                    deliveredAt = row.optString("delivered_at"),
                    expiresAt = row.optString("expires_at"),
                    content = content
                )
            }
            items = if (append) items + next.filterNot { candidate -> items.any { it.id == candidate.id } } else next
            beforeId = response.optLong("nextBeforeId").takeIf { it > 0L }
            hasMore = next.size >= 50 && beforeId != null
            if (expandedId == 0L) expandedId = items.firstOrNull { it.state != "READ" }?.id ?: 0L
        } catch (failure: Exception) {
            error = failure.message ?: text("Notifications could not be loaded.", "सूचनाएँ लोड नहीं हो सकीं।")
        } finally {
            loading = false
        }
    }

    LaunchedEffect(filterUnread, filterArchived) { load(append = false, unreadOnly = filterUnread && !filterArchived) }

    suspend fun markRead(id: Long) {
        val row = items.firstOrNull { it.id == id } ?: return
        if (row.state == "READ") return
        runCatching { ApiClient.postObject("/api/v1/notifications/$id/read") }
            .onSuccess {
                items = items.map { if (it.id == id) it.copy(state = "READ") else it }
                onChanged()
            }
            .onFailure { error = it.message ?: text("Read state could not be saved.", "पढ़ी हुई स्थिति सेव नहीं हो सकी।") }
    }

    suspend fun markUnread(id: Long) {
        runCatching { ApiClient.postObject("/api/v1/notifications/$id/unread") }
            .onSuccess {
                items = items.map { if (it.id == id) it.copy(state = "CREATED") else it }
                onChanged()
            }
            .onFailure { error = it.message ?: text("Could not restore this notification.", "सूचना वापस नहीं ला सके।") }
    }

    suspend fun dismissNotification(id: Long) {
        runCatching { ApiClient.postObject("/api/v1/notifications/$id/dismiss") }
            .onSuccess {
                items = items.map { if (it.id == id) it.copy(state = "DISMISSED") else it }
                onChanged()
            }
            .onFailure { error = it.message ?: text("Could not archive this notification.", "सूचना archive नहीं हो सकी।") }
    }

    val visibleRows = items.filter { row ->
        when {
            filterArchived -> row.state == "DISMISSED"
            filterUnread -> row.state != "READ" && row.state != "DISMISSED"
            else -> row.state != "DISMISSED"
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier.fillMaxWidth().widthIn(max = 720.dp).heightIn(max = 780.dp),
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 8.dp
        ) {
            Column(modifier = Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(text("YOUR INBOX", "आपकी सूचनाएँ"),
                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold)
                        Text(text("Notifications", "सूचनाएँ"), style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold)
                        Text(text("Course, institution and platform updates.", "कोर्स, संस्थान और प्लेटफ़ॉर्म के अपडेट।"),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    TextButton(onClick = onDismiss) { Text(text("Close", "बंद करें")) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (!filterUnread && !filterArchived) Button(onClick = { filterUnread = false; filterArchived = false }) { Text(text("All", "सभी")) }
                    else OutlinedButton(onClick = { filterUnread = false; filterArchived = false }) { Text(text("All", "सभी")) }
                    if (filterUnread) Button(onClick = { filterUnread = true; filterArchived = false }) { Text(text("Unread", "अपठित")) }
                    else OutlinedButton(onClick = { filterUnread = true; filterArchived = false }) { Text(text("Unread", "अपठित")) }
                    if (filterArchived) Button(onClick = { filterArchived = true; filterUnread = false }) { Text(text("Archived", "संग्रहित")) }
                    else OutlinedButton(onClick = { filterArchived = true; filterUnread = false }) { Text(text("Archived", "संग्रहित")) }
                    Spacer(Modifier.weight(1f))
                    TextButton(
                        enabled = !saving && items.any { it.state != "READ" && it.state != "DISMISSED" },
                        onClick = {
                            scope.launch {
                                saving = true
                                runCatching { ApiClient.postObject("/api/v1/notifications/read-all") }
                                    .onSuccess { items = items.map { if (it.state == "DISMISSED") it else it.copy(state = "READ") }; onChanged() }
                                    .onFailure { error = it.message ?: text("Could not mark notifications as read.", "सूचनाएँ read नहीं हो सकीं।") }
                                saving = false
                            }
                        }
                    ) { Text(text("Mark all read", "सबको पढ़ा हुआ करें")) }
                }
                if (error.isNotBlank()) {
                    Card { Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(error, color = MaterialTheme.colorScheme.error)
                        OutlinedButton(onClick = { scope.launch { load(false, filterUnread && !filterArchived) } }) { Text(text("Retry", "फिर प्रयास करें")) }
                    } }
                }
                if (loading && items.isEmpty()) {
                    Box(Modifier.fillMaxWidth().height(150.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                } else if (items.isEmpty()) {
                    Box(Modifier.fillMaxWidth().height(160.dp), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("✦", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.headlineLarge)
                            Text(text("You're all caught up", "अभी कोई नई सूचना नहीं है"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            Text(text("New updates will appear here.", "नए अपडेट यहाँ दिखेंगे।"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth().weight(1f, fill = false).heightIn(max = 515.dp),
                        verticalArrangement = Arrangement.spacedBy(9.dp)
                    ) {
                        items(visibleRows, key = { it.id }) { row ->
                            val expanded = expandedId == row.id
                            val unread = row.state != "READ" && row.state != "DISMISSED"
                            Card(
                                modifier = Modifier.fillMaxWidth().clickable {
                                    expandedId = if (expanded) 0L else row.id
                                    if (!expanded && row.state != "DISMISSED") scope.launch { markRead(row.id) }
                                },
                                shape = RoundedCornerShape(15.dp)
                            ) {
                                Column(Modifier.fillMaxWidth().padding(13.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                        Surface(shape = RoundedCornerShape(11.dp), color = MaterialTheme.colorScheme.secondaryContainer) {
                                            Box(Modifier.size(36.dp), contentAlignment = Alignment.Center) {
                                                Text(when(row.scope) { "HRMS_PLATFORM" -> "✦"; "INSTITUTION" -> "▦"; "COURSE" -> "▤"; else -> "◉" },
                                                    color = MaterialTheme.colorScheme.onSecondaryContainer, fontWeight = FontWeight.Bold)
                                            }
                                        }
                                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                            Text(row.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                                            Text(row.senderLabel.ifBlank { when(row.scope) {
                                                "HRMS_PLATFORM" -> text("HRMS Platform", "HRMS प्लेटफ़ॉर्म")
                                                "INSTITUTION" -> text("Institution", "संस्थान")
                                                "COURSE" -> text("Course", "कोर्स")
                                                else -> text("Personal", "व्यक्तिगत")
                                            } }, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                                            Text(row.summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                            val readableDate = readableNotificationDate(row.deliveredAt)
                                            if (readableDate.isNotBlank()) Text(readableDate, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                        if (unread) Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primary, modifier = Modifier.size(8.dp)) {} 
                                    }
                                    if (expanded) {
                                        HorizontalDivider()
                                        NotificationRichContent(row.content, onNavigate, ::text)
                                        if (row.expiresAt.isNotBlank()) Text(text("Available until ", "उपलब्धता की अंतिम तिथि: ") + readableNotificationDate(row.expiresAt),
                                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                            if (row.state == "DISMISSED") {
                                                OutlinedButton(enabled = !saving, onClick = {
                                                    scope.launch {
                                                        saving = true
                                                        markUnread(row.id)
                                                        saving = false
                                                    }
                                                }) { Text(text("Restore to inbox", "Inbox में वापस")) }
                                            } else {
                                                if (row.state == "READ") OutlinedButton(enabled = !saving, onClick = {
                                                    scope.launch {
                                                        saving = true
                                                        markUnread(row.id)
                                                        saving = false
                                                    }
                                                }) { Text(text("Mark unread", "Unread करें")) }
                                                OutlinedButton(enabled = !saving, onClick = {
                                                    scope.launch {
                                                        saving = true
                                                        dismissNotification(row.id)
                                                        saving = false
                                                    }
                                                }) { Text(text("Archive", "संग्रहित करें")) }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        if (hasMore) item {
                            OutlinedButton(
                                modifier = Modifier.fillMaxWidth(),
                                enabled = !loading,
                                onClick = { scope.launch { load(append = true, unreadOnly = filterUnread && !filterArchived) } }
                            ) { Text(if (loading) text("Loading…", "लोड हो रहा है…") else text("Load older", "पुरानी सूचनाएँ")) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun NotificationRichContent(
    document: JSONObject,
    onNavigate: (String) -> Unit,
    text: (String, String) -> String
) {
    val blocks = document.optJSONArray("blocks") ?: JSONArray()
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        for (i in 0 until blocks.length()) {
            val block = blocks.optJSONObject(i) ?: continue
            when (block.optString("type")) {
                "hero" -> {
                    val assetId = block.optString("assetId")
                    if (assetId.isNotBlank() && assetId != "null") NotificationImage(assetId, block.optString("alt", block.optString("title")))
                    val eyebrow = block.optString("eyebrow")
                    if (eyebrow.isNotBlank()) Text(eyebrow, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                    if (block.optString("title").isNotBlank()) Text(block.optString("title"), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    if (block.optString("subtitle").isNotBlank()) Text(block.optString("subtitle"), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                "richText" -> block.optString("text").split(Regex("\\n{2,}")).filter { it.isNotBlank() }.forEach {
                    Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                }
                "image", "animation" -> {
                    val assetId = block.optString("assetId")
                    if (assetId.isNotBlank() && assetId != "null") NotificationImage(assetId, if(block.optBoolean("decorative")) "" else block.optString("alt"))
                    if (block.optString("caption").isNotBlank()) Text(block.optString("caption"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                "video" -> Card(shape = RoundedCornerShape(12.dp)) {
                    Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text("▷  " + block.optString("title", text("Video", "वीडियो")), fontWeight = FontWeight.SemiBold)
                        Text(text("Video content is available in the web notification inbox.", "वीडियो सामग्री वेब सूचना इनबॉक्स में उपलब्ध है।"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                "courseCard", "eventCard" -> Card(shape = RoundedCornerShape(12.dp)) {
                    Column(Modifier.fillMaxWidth().padding(13.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(block.optString("courseName", block.optString("title", text("Update", "अपडेट"))), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        if (block.optString("dateLabel").isNotBlank()) Text(block.optString("dateLabel"), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium)
                        if (block.optString("description").isNotBlank()) Text(block.optString("description"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        val target = safeNotificationTarget(block.optString("target"))
                        if (target != null) Button(onClick = { onNavigate(target) }) { Text(block.optString("buttonLabel", text("Open", "खोलें"))) }
                    }
                }
                "highlight" -> Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.secondaryContainer) {
                    Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(block.optString("label"), color = MaterialTheme.colorScheme.onSecondaryContainer, style = MaterialTheme.typography.labelMedium)
                        Text(block.optString("value"), color = MaterialTheme.colorScheme.onSecondaryContainer, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                        if (block.optString("description").isNotBlank()) Text(block.optString("description"), color = MaterialTheme.colorScheme.onSecondaryContainer, style = MaterialTheme.typography.bodySmall)
                    }
                }
                "badge" -> Surface(shape = CircleShape, color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.widthIn(max = 260.dp)) {
                    Text(block.optString("text", block.optString("label")), modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSecondaryContainer)
                }
                "icon" -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(block.optString("symbol", "✦"), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.titleLarge)
                    Text(block.optString("text"), style = MaterialTheme.typography.bodyMedium)
                }
                "divider" -> HorizontalDivider()
                "spacer" -> Spacer(Modifier.height(12.dp))
                "cta" -> {
                    val target = safeNotificationTarget(block.optString("target"))
                    if (target != null) Button(onClick = { onNavigate(target) }) { Text(block.optString("label", text("Open", "खोलें"))) }
                }
                "footer" -> if (block.optString("text").isNotBlank()) Text(block.optString("text"), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                "container", "carousel" -> NotificationRichContent(JSONObject().put("blocks", block.optJSONArray("children") ?: block.optJSONArray("items") ?: JSONArray()), onNavigate, text)
            }
        }
    }
}

@Composable
private fun NotificationImage(assetId: String, alt: String) {
    var bitmap by remember(assetId) { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
    var failed by remember(assetId) { mutableStateOf(false) }
    LaunchedEffect(assetId) {
        runCatching {
            val bytes = ApiClient.getBytes("/api/v1/notifications/media/$assetId")
            withContext(Dispatchers.IO) { BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap() }
                ?: throw IllegalStateException("Image could not be decoded.")
        }.onSuccess { bitmap = it }.onFailure { failed = true }
    }
    when {
        bitmap != null -> Image(bitmap!!, contentDescription = alt, modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).heightIn(max = 420.dp), contentScale = ContentScale.Fit)
        failed -> Text("▧  " + alt.ifBlank { "Image unavailable" }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        else -> Box(Modifier.fillMaxWidth().height(150.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
    }
}

private fun safeNotificationTarget(raw: String): String? {
    val target = raw.trim()
    if (target.isEmpty()) return null
    if (target.startsWith("/") && !target.startsWith("//") && !target.contains('\\') && !target.contains('\r') && !target.contains('\n')) return target
    val uri = runCatching { java.net.URI(target) }.getOrNull() ?: return null
    return target.takeIf { uri.scheme.equals("https", true) && !uri.host.isNullOrBlank() && uri.userInfo == null }
}

private fun readableNotificationDate(raw: String): String = runCatching {
    val instant = Instant.parse(raw)
    DateTimeFormatter.ofPattern("dd MMM yyyy, h:mm a", Locale.getDefault()).withZone(ZoneId.systemDefault()).format(instant)
}.getOrElse { raw }
