package com.quantaedge.android.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.FileProvider
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import com.quantaedge.android.data.ApiClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest

private const val MAX_PREVIEW_BYTES = 524_288_000L
private val SHA256_PATTERN = Regex("^[0-9a-fA-F]{64}$")

/**
 * Student-facing resource catalogue for published structured lessons. Every listing and stream
 * URL comes from the authorized API response; local preview files stay inside cache and are
 * removed when this panel leaves composition. Permanent/offline copies are only offered by
 * the dedicated workflow when the API explicitly grants download permission.
 */
@Composable
fun StructuredLessonResourcesPanel(
    artifactId: String,
    language: String,
    onOpenLearningDocument: (Long) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val isHindi = language == "hi" || language.startsWith("hi-")
    fun t(en: String, hi: String) = if (isHindi) hi else en

    var resources by remember(artifactId) { mutableStateOf(emptyList<JSONObject>()) }
    var loading by remember(artifactId) { mutableStateOf(true) }
    var error by remember(artifactId) { mutableStateOf("") }
    var notice by remember(artifactId) { mutableStateOf("") }
    var activeMediaId by rememberSaveable(artifactId) { mutableStateOf(0L) }
    var previewBusyId by remember { mutableStateOf(0L) }
    var previewFile by remember(artifactId) { mutableStateOf<File?>(null) }
    var previewTitle by remember(artifactId) { mutableStateOf("") }
    var previewType by remember(artifactId) { mutableStateOf("") }
    var previewPage by remember(artifactId) { mutableStateOf(0) }
    var previewPageCount by remember(artifactId) { mutableStateOf(0) }
    var previewBitmap by remember(artifactId) { mutableStateOf<Bitmap?>(null) }
    var previewText by remember(artifactId) { mutableStateOf("") }

    val previewDirectory = remember(artifactId) {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(artifactId.toByteArray(Charsets.UTF_8))
            .take(10).joinToString("") { "%02x".format(it) }
        File(context.cacheDir, "learning_resource_previews/$digest")
    }
    DisposableEffect(previewDirectory) {
        previewDirectory.mkdirs()
        onDispose {
            // These are short-lived previews, not offline downloads. Do not leave media from a
            // previous lesson in cache after leaving this screen.
            previewDirectory.deleteRecursively()
        }
    }

    LaunchedEffect(artifactId, language) {
        loading = true
        error = ""
        try {
            val encodedArtifact = ApiClient.encodeQuery(artifactId)
            val encodedLanguage = ApiClient.encodeQuery(language)
            val array = ApiClient.getArray(
                "/api/v1/learning/resources?artifactId=$encodedArtifact&language=$encodedLanguage"
            )
            resources = buildList {
                for (i in 0 until array.length()) array.optJSONObject(i)?.let { add(JSONObject(it.toString())) }
            }
        } catch (failure: Exception) {
            error = failure.message?.take(400).orEmpty().ifBlank {
                t("इस पाठ के संसाधन लोड नहीं हो सके।", "The resources for this lesson could not be loaded.")
            }
        } finally {
            loading = false
        }
    }

    fun clearPreview() {
        previewFile?.delete()
        previewFile = null
        previewBitmap = null
        previewText = ""
        previewType = ""
        previewPage = 0
        previewPageCount = 0
        previewTitle = ""
        previewBusyId = 0L
    }

    fun startPreview(item: JSONObject) {
        val resourceId = item.optLong("resourceId", 0L)
        val assignmentId = item.optLong("assignmentId", 0L)
        val type = item.optString("resourceType").uppercase()
        val source = item.optString("sourceKind").uppercase()
        val documentId = item.optLong("learningDocumentId", 0L)
        val external = item.optString("externalUrl").ifBlank { item.optString("assetUrl") }
        when {
            resourceId <= 0L || assignmentId <= 0L -> {
                error = t("इस संसाधन का सुरक्षित संदर्भ उपलब्ध नहीं है।", "The secure reference for this resource is missing.")
            }
            documentId > 0L && source == "LEARNING_DOCUMENT" -> onOpenLearningDocument(documentId)
            source == "EXTERNAL_URL" || source == "CONTENT_ASSET" -> {
                if (!openHttpsResource(context, external)) {
                    error = t("यह बाहरी संसाधन सुरक्षित HTTPS लिंक नहीं है।", "This external resource is not a safe HTTPS link.")
                }
            }
            source == "PRIVATE_UPLOAD" || source == "RECORDED_CLASS" -> {
                val streamPath = item.optString("streamUrl")
                val mediaType = item.optString("mediaType").substringBefore(';').lowercase()
                when {
                    type == "VIDEO" || type == "AUDIO" -> {
                        if (streamPath.startsWith("/api/v1/learning/resources/")) {
                            clearPreview()
                            notice = ""
                            error = ""
                            activeMediaId = resourceId
                        } else {
                            error = t("इस मीडिया के लिए सुरक्षित प्लेयर लिंक उपलब्ध नहीं है।", "A secure media stream is unavailable for this item.")
                        }
                    }
                    type == "PDF" || mediaType == "application/pdf" -> {
                        clearPreview()
                        error = ""
                        notice = ""
                        previewBusyId = resourceId
                        previewTitle = item.optString("title")
                        scope.launch {
                            try {
                                val file = downloadResourcePreview(item, previewDirectory, allowDownload = false)
                                val rendered = withContext(Dispatchers.IO) { renderPdfPreviewPage(file, 0) }
                                previewFile = file
                                previewBitmap = rendered.second
                                previewPage = 0
                                previewPageCount = rendered.first
                                previewType = "PDF"
                            } catch (failure: Exception) {
                                error = failure.message?.take(400).orEmpty().ifBlank {
                                    t("PDF प्रीव्यू नहीं खुल सका।", "The PDF preview could not be opened.")
                                }
                            } finally {
                                previewBusyId = 0L
                            }
                        }
                    }
                    type == "IMAGE" || mediaType.startsWith("image/") -> {
                        clearPreview()
                        error = ""
                        notice = ""
                        previewBusyId = resourceId
                        previewTitle = item.optString("title")
                        scope.launch {
                            try {
                                val file = downloadResourcePreview(item, previewDirectory, allowDownload = false)
                                val bitmap = withContext(Dispatchers.IO) { decodeImagePreview(file) }
                                previewFile = file
                                previewBitmap = bitmap
                                previewType = "IMAGE"
                            } catch (failure: Exception) {
                                error = failure.message?.take(400).orEmpty().ifBlank {
                                    t("चित्र नहीं खुल सका।", "The image preview could not be opened.")
                                }
                            } finally {
                                previewBusyId = 0L
                            }
                        }
                    }
                    mediaType == "text/plain" -> {
                        clearPreview()
                        error = ""
                        notice = ""
                        previewBusyId = resourceId
                        previewTitle = item.optString("title")
                        scope.launch {
                            try {
                                val file = downloadResourcePreview(item, previewDirectory, allowDownload = false)
                                if (file.length() > 2L * 1024 * 1024) throw IOException(
                                    t("यह टेक्स्ट फ़ाइल प्रीव्यू के लिए बहुत बड़ी है।", "This text file is too large to preview.")
                                )
                                previewFile = file
                                previewText = withContext(Dispatchers.IO) { file.readText(Charsets.UTF_8) }
                                previewType = "TEXT"
                            } catch (failure: Exception) {
                                error = failure.message?.take(400).orEmpty().ifBlank {
                                    t("फ़ाइल प्रीव्यू नहीं खुल सका।", "The file preview could not be opened.")
                                }
                            } finally {
                                previewBusyId = 0L
                            }
                        }
                    }
                    item.optBoolean("downloadAllowed") && item.optString("downloadUrl").startsWith("/api/v1/learning/resources/") -> {
                        clearPreview()
                        error = ""
                        notice = ""
                        previewBusyId = resourceId
                        scope.launch {
                            try {
                                val file = downloadResourcePreview(item, previewDirectory, allowDownload = true)
                                openLocalFile(context, file, mediaType.ifBlank { "application/octet-stream" })
                            } catch (failure: Exception) {
                                error = failure.message?.take(400).orEmpty().ifBlank {
                                    t("फ़ाइल खोल नहीं सके।", "The file could not be opened.")
                                }
                            } finally {
                                previewBusyId = 0L
                            }
                        }
                    }
                    else -> {
                        notice = t(
                            "यह फ़ाइल प्रकार ऐप में प्रीव्यू नहीं हो सकता और इसके लिए डाउनलोड की अनुमति नहीं है। शिक्षक से PDF या अनुमति-युक्त फ़ाइल माँगें।",
                            "This file type cannot be previewed in the app and downloads are disabled. Ask the teacher for a PDF preview or an explicitly downloadable file."
                        )
                    }
                }
            }
            else -> {
                notice = t("इस संसाधन के लिए कोई सुरक्षित व्यूअर उपलब्ध नहीं है।", "No safe viewer is available for this resource.")
            }
        }
    }

    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(t("इस पाठ के साथ", "RESOURCES FOR THIS LESSON"),
            color = MaterialTheme.colorScheme.secondary, style = MaterialTheme.typography.labelLarge)
        Text(t("सीखने के संसाधन", "Learning resources"), style = MaterialTheme.typography.titleLarge)
        Text(
            t("शिक्षक द्वारा प्रकाशित वीडियो, ऑडियो, PDF और अन्य सामग्री।", "Videos, audio, PDFs and other materials published by the teacher."),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary
        )
        if (loading) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                Text(t("संसाधन लोड हो रहे हैं…", "Loading resources…"), style = MaterialTheme.typography.bodySmall)
            }
        }
        if (error.isNotBlank()) {
            Card {
                Text(error, modifier = Modifier.padding(12.dp), color = MaterialTheme.colorScheme.error)
                TextButton(onClick = {
                    loading = true
                    error = ""
                    scope.launch {
                        try {
                            val array = ApiClient.getArray(
                                "/api/v1/learning/resources?artifactId=" + ApiClient.encodeQuery(artifactId) +
                                    "&language=" + ApiClient.encodeQuery(language)
                            )
                            resources = buildList {
                                for (i in 0 until array.length()) array.optJSONObject(i)?.let { add(JSONObject(it.toString())) }
                            }
                        } catch (failure: Exception) {
                            error = failure.message?.take(400).orEmpty().ifBlank {
                                t("इस पाठ के संसाधन लोड नहीं हो सके।", "The resources for this lesson could not be loaded.")
                            }
                        } finally { loading = false }
                    }
                }) { Text(t("फिर प्रयास करें", "Retry")) }
            }
        } else if (!loading && resources.isEmpty()) {
            Text(t("इस पाठ के लिए कोई अतिरिक्त प्रकाशित संसाधन नहीं है।", "No additional published resources are attached to this lesson."),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
        }

        resources.forEach { item ->
            val resourceId = item.optLong("resourceId", 0L)
            val type = item.optString("resourceType", "RESOURCE").uppercase().replace('_', ' ')
            Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text(type, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)
                    Text(item.optString("title").ifBlank { t("सीखने का संसाधन", "Learning resource") },
                        style = MaterialTheme.typography.titleMedium)
                    item.optString("description").takeIf { it.isNotBlank() }?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall)
                    }
                    item.optLong("fileSizeBytes", 0L).takeIf { it > 0L }?.let {
                        Text(formatResourceSize(it), style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.secondary)
                    }
                    when {
                        resourceId == previewBusyId -> Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                            Text(t("संसाधन खोला जा रहा है…", "Opening resource…"), style = MaterialTheme.typography.bodySmall)
                        }
                        item.optLong("learningDocumentId", 0L) > 0L && item.optString("sourceKind") == "LEARNING_DOCUMENT" ->
                            Button(onClick = { startPreview(item) }, modifier = Modifier.fillMaxWidth()) {
                                Text(t("सुरक्षित PDF रीडर में खोलें", "Open protected PDF reader"))
                            }
                        item.optString("resourceType").uppercase() in setOf("VIDEO", "AUDIO") &&
                            (item.optString("streamUrl").startsWith("/api/v1/learning/resources/") ||
                                item.optString("assetUrl").startsWith("https://") ||
                                item.optString("externalUrl").startsWith("https://")) ->
                            Button(onClick = { startPreview(item) }, modifier = Modifier.fillMaxWidth()) {
                                Text(if (activeMediaId == resourceId) t("मीडिया चल रहा है", "Media player selected")
                                    else t("चलाएँ", "Play"))
                            }
                        item.optString("sourceKind") == "EXTERNAL_URL" || item.optString("sourceKind") == "CONTENT_ASSET" ->
                            OutlinedButton(onClick = { startPreview(item) }, modifier = Modifier.fillMaxWidth()) {
                                Text(t("संसाधन खोलें", "Open resource"))
                            }
                        else -> OutlinedButton(
                            onClick = { startPreview(item) },
                            enabled = item.optString("streamUrl").startsWith("/api/v1/learning/resources/"),
                            modifier = Modifier.fillMaxWidth()
                        ) { Text(t("ऑनलाइन खोलें", "Open online")) }
                    }
                    if (activeMediaId == resourceId && item.optString("resourceType").uppercase() in setOf("VIDEO", "AUDIO")) {
                        StructuredResourceMediaPlayer(item = item, language = language) { position, watched, completed ->
                            scope.launch {
                                runCatching {
                                    ApiClient.putObject(
                                        "/api/v1/learning/resources/$resourceId/progress?assignmentId=" +
                                            item.optLong("assignmentId"),
                                        JSONObject().put("lastPositionSeconds", position)
                                            .put("watchedSeconds", watched).put("completed", completed)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        if (notice.isNotBlank()) {
            Card {
                Text(notice, modifier = Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary)
            }
        }

        if (previewBusyId == 0L && previewFile != null && previewType == "PDF" && previewBitmap != null) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(previewTitle, style = MaterialTheme.typography.titleMedium)
                    Text(t("पृष्ठ", "Page") + " " + (previewPage + 1) + " / " + previewPageCount,
                        style = MaterialTheme.typography.bodySmall)
                    Image(bitmap = previewBitmap!!.asImageBitmap(), contentDescription = previewTitle,
                        modifier = Modifier.fillMaxWidth(), contentScale = ContentScale.FillWidth)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        OutlinedButton(enabled = previewPage > 0, onClick = {
                            val file = previewFile ?: return@OutlinedButton
                            val next = previewPage - 1
                            scope.launch {
                                try {
                                    val bitmap = withContext(Dispatchers.IO) { renderPdfPreviewPage(file, next).second }
                                    previewPage = next
                                    previewBitmap = bitmap
                                } catch (failure: Exception) {
                                    error = failure.message?.take(300).orEmpty()
                                }
                            }
                        }, modifier = Modifier.weight(1f)) { Text(t("पिछला", "Previous")) }
                        OutlinedButton(enabled = previewPage < previewPageCount - 1, onClick = {
                            val file = previewFile ?: return@OutlinedButton
                            val next = previewPage + 1
                            scope.launch {
                                try {
                                    val bitmap = withContext(Dispatchers.IO) { renderPdfPreviewPage(file, next).second }
                                    previewPage = next
                                    previewBitmap = bitmap
                                } catch (failure: Exception) {
                                    error = failure.message?.take(300).orEmpty()
                                }
                            }
                        }, modifier = Modifier.weight(1f)) { Text(t("अगला", "Next")) }
                    }
                    TextButton(onClick = { clearPreview() }) { Text(t("प्रीव्यू बंद करें", "Close preview")) }
                }
            }
        }
        if (previewFile != null && previewType == "IMAGE" && previewBitmap != null) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(previewTitle, style = MaterialTheme.typography.titleMedium)
                    Image(bitmap = previewBitmap!!.asImageBitmap(), contentDescription = previewTitle,
                        modifier = Modifier.fillMaxWidth(), contentScale = ContentScale.Fit)
                    TextButton(onClick = { clearPreview() }) { Text(t("चित्र बंद करें", "Close image")) }
                }
            }
        }
        if (previewFile != null && previewType == "TEXT" && previewText.isNotBlank()) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(previewTitle, style = MaterialTheme.typography.titleMedium)
                    Text(previewText, style = MaterialTheme.typography.bodyMedium)
                    TextButton(onClick = { clearPreview() }) { Text(t("बंद करें", "Close")) }
                }
            }
        }
    }
}

@Composable
private fun StructuredResourceMediaPlayer(
    item: JSONObject,
    language: String,
    onSaveProgress: (Long, Long, Boolean) -> Unit
) {
    val isHindi = language == "hi" || language.startsWith("hi-")
    fun t(en: String, hi: String) = if (isHindi) hi else en
    val resourceId = item.optLong("resourceId", 0L)
    val assignmentId = item.optLong("assignmentId", 0L)
    val initialPosition = item.optLong("lastPositionSeconds", 0L).coerceAtLeast(0L)
    val durationSeconds = item.optLong("durationSeconds", 0L).coerceAtLeast(0L)
    var playbackEnded by remember(resourceId) { mutableStateOf(false) }
    var positionSeconds by remember(resourceId) { mutableStateOf(initialPosition) }
    var watchedSeconds by remember(resourceId) { mutableStateOf(item.optLong("watchedSeconds", 0L).coerceAtLeast(0L)) }

    val player = remember(resourceId, assignmentId) {
        val path = item.optString("streamUrl").ifBlank {
            item.optString("assetUrl").ifBlank { item.optString("externalUrl") }
        }
        val mediaUrl = ApiClient.mediaUrl(path)
        val factory = OkHttpDataSource.Factory(ApiClient.authenticatedHttpClient())
        ExoPlayer.Builder(LocalContext.current)
            .setMediaSourceFactory(DefaultMediaSourceFactory(factory))
            .build().apply {
                setMediaItem(MediaItem.fromUri(mediaUrl))
                prepare()
                if (initialPosition > 0L) seekTo(initialPosition * 1000L)
            }
    }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                playbackEnded = playbackState == Player.STATE_ENDED
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            player.release()
        }
    }
    LaunchedEffect(player) {
        while (isActive) {
            delay(15_000)
            if (player.isPlaying) {
                positionSeconds = (player.currentPosition / 1000L).coerceAtLeast(0L)
                watchedSeconds = (watchedSeconds + 15L).coerceAtMost(durationSeconds.takeIf { it > 0L } ?: Long.MAX_VALUE)
                onSaveProgress(positionSeconds, watchedSeconds, false)
            }
            if (playbackEnded) {
                positionSeconds = (player.duration / 1000L).coerceAtLeast(0L)
                onSaveProgress(positionSeconds, watchedSeconds, true)
                break
            }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        AndroidView(
            factory = { viewContext ->
                PlayerView(viewContext).apply {
                    this.player = player
                    useController = true
                    setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
                }
            },
            update = { it.player = player },
            modifier = Modifier.fillMaxWidth().height(210.dp)
        )
        Text(t("स्थिति: ", "Position: ") + positionSeconds + t(" सेकंड", " seconds"),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
    }
}

private suspend fun downloadResourcePreview(item: JSONObject, directory: File, allowDownload: Boolean): File =
    withContext(Dispatchers.IO) {
        val key = if (allowDownload) "downloadUrl" else "streamUrl"
        val path = item.optString(key)
        if (!path.startsWith("/api/v1/learning/resources/") || path.contains("..") || path.contains("://")) {
            throw IOException("The resource did not provide a valid authorized URL.")
        }
        val resourceId = item.optLong("resourceId", 0L)
        val assignmentId = item.optLong("assignmentId", 0L)
        if (resourceId <= 0L || assignmentId <= 0L) throw IOException("The resource assignment is invalid.")
        if (!directory.exists() && !directory.mkdirs()) throw IOException("Could not prepare the private preview directory.")
        val mediaType = item.optString("mediaType").substringBefore(';').lowercase()
        val extension = resourceExtension(mediaType)
        val target = File(directory, "resource_" + resourceId + "_" + assignmentId + "." + extension)
        val temporary = File(directory, target.name + ".part")
        temporary.delete()
        val request = Request.Builder().url(ApiClient.absoluteUrl(path)).get()
            .header("Accept", mediaType.ifBlank { "application/octet-stream" })
            .header("X-QE-Workspace", "STUDENT").header("X-QE-Client", "ANDROID").build()
        try {
            ApiClient.authenticatedHttpClient().newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IOException("Resource request failed (" + response.code + ").")
                val body = response.body ?: throw IOException("The resource response is empty.")
                val declaredLength = body.contentLength()
                if (declaredLength > MAX_PREVIEW_BYTES) throw IOException("This resource exceeds the 500 MB viewing limit.")
                val expectedSize = item.optLong("fileSizeBytes", -1L)
                if (expectedSize > MAX_PREVIEW_BYTES) throw IOException("This resource exceeds the 500 MB viewing limit.")
                val digest = MessageDigest.getInstance("SHA-256")
                var total = 0L
                body.byteStream().use { input ->
                    FileOutputStream(temporary).use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            total += count
                            if (total > MAX_PREVIEW_BYTES) throw IOException("This resource exceeds the 500 MB viewing limit.")
                            digest.update(buffer, 0, count)
                            output.write(buffer, 0, count)
                        }
                    }
                }
                if (expectedSize > 0L && total != expectedSize) throw IOException("Resource size did not match published metadata.")
                val expectedSha = item.optString("sha256")
                val actualSha = digest.digest().joinToString("") { "%02x".format(it) }
                if (SHA256_PATTERN.matches(expectedSha) && !actualSha.equals(expectedSha, ignoreCase = true)) {
                    throw IOException("Resource integrity check failed.")
                }
            }
            if (target.exists() && !target.delete()) throw IOException("Could not replace the resource preview.")
            if (!temporary.renameTo(target)) throw IOException("Could not safely finish the resource preview.")
            target
        } catch (failure: Exception) {
            temporary.delete()
            throw failure
        }
    }

private fun renderPdfPreviewPage(file: File, pageIndex: Int): Pair<Int, Bitmap> {
    if (!file.isFile || file.length() <= 0L) throw IOException("The PDF preview file is missing.")
    ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
        PdfRenderer(descriptor).use { renderer ->
            if (pageIndex !in 0 until renderer.pageCount) throw IOException("The requested PDF page is unavailable.")
            renderer.openPage(pageIndex).use { page ->
                val scale = minOf(1f, 1600f / page.width.coerceAtLeast(1), 2000f / page.height.coerceAtLeast(1))
                val width = (page.width * scale).toInt().coerceAtLeast(1)
                val height = (page.height * scale).toInt().coerceAtLeast(1)
                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565)
                bitmap.eraseColor(Color.WHITE)
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                return renderer.pageCount to bitmap
            }
        }
    }
}

private fun decodeImagePreview(file: File): Bitmap {
    if (!file.isFile || file.length() <= 0L) throw IOException("The image preview file is missing.")
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.absolutePath, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw IOException("The image file is invalid.")
    var sample = 1
    while (bounds.outWidth / sample > 1800 || bounds.outHeight / sample > 1800) sample *= 2
    val options = BitmapFactory.Options().apply {
        inSampleSize = sample
        inPreferredConfig = Bitmap.Config.RGB_565
    }
    return BitmapFactory.decodeFile(file.absolutePath, options) ?: throw IOException("The image could not be decoded.")
}

private fun resourceExtension(mediaType: String): String = when (mediaType) {
    "application/pdf" -> "pdf"
    "application/msword" -> "doc"
    "application/vnd.openxmlformats-officedocument.wordprocessingml.document" -> "docx"
    "application/vnd.ms-powerpoint" -> "ppt"
    "application/vnd.openxmlformats-officedocument.presentationml.presentation" -> "pptx"
    "application/vnd.ms-excel" -> "xls"
    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" -> "xlsx"
    "text/plain" -> "txt"
    "audio/mpeg" -> "mp3"
    "audio/mp4" -> "m4a"
    "audio/wav" -> "wav"
    "audio/ogg" -> "ogg"
    "video/mp4" -> "mp4"
    "video/webm" -> "webm"
    "video/quicktime" -> "mov"
    "image/png" -> "png"
    "image/jpeg" -> "jpg"
    "image/webp" -> "webp"
    "image/gif" -> "gif"
    else -> "bin"
}

private fun formatResourceSize(bytes: Long): String =
    if (bytes >= 1024L * 1024L) String.format("%.1f MB", bytes.toDouble() / (1024.0 * 1024.0))
    else maxOf(1L, bytes / 1024L).toString() + " KB"

private fun openHttpsResource(context: android.content.Context, value: String): Boolean {
    val uri = runCatching { Uri.parse(value.trim()) }.getOrNull() ?: return false
    if (!uri.scheme.equals("https", true) || uri.host.isNullOrBlank() || uri.userInfo != null) return false
    return try {
        context.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (_: ActivityNotFoundException) {
        false
    }
}

private fun openLocalFile(context: android.content.Context, file: File, mediaType: String) {
    if (!file.isFile || !file.canonicalPath.startsWith(File(context.cacheDir, "learning_resource_previews").canonicalPath + File.separator)) {
        throw IOException("The permitted resource file is not available in the private preview area.")
    }
    val uri = FileProvider.getUriForFile(context, context.packageName + ".fileprovider", file)
    val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, mediaType)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        context.startActivity(Intent.createChooser(intent, "Open with").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: ActivityNotFoundException) {
        throw IOException("No app on this device can open this file type.")
    }
}
