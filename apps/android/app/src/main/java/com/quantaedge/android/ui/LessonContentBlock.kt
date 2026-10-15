package com.quantaedge.android.ui

import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.drawable.AnimatedImageDrawable
import android.os.Build
import android.widget.ImageView
import java.nio.ByteBuffer
import android.net.Uri
import android.text.Html
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import com.quantaedge.android.data.ApiClient
import com.quantaedge.android.data.optLongAny
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

@Composable
fun LessonContentBlock(
    block: JSONObject,
    language: String,
    onTutorOpen: () -> Unit
) {
    val context = LocalContext.current
    val type = block.textAny("block_type").uppercase()
    val raw = block.textAny("content")
    val data = remember(raw) {
        runCatching { JSONObject(raw) }.getOrElse { JSONObject().put("body", raw) }
    }
    val title = (if (language == "hi") data.textAny("title_hi", "heading") else data.textAny("title", "heading")).ifBlank {
        when (type) {
            "PREREQUISITE" -> if (language == "hi") "पहले की जानकारी" else "Prior knowledge"
            "EXPLANATION" -> if (language == "hi") "समझें" else "Understand"
            "WORKED_EXAMPLE" -> if (language == "hi") "उदाहरण" else "Worked example"
            "GUIDED_PRACTICE" -> if (language == "hi") "साथ में करें" else "Try together"
            "INDEPENDENT_PRACTICE", "CHALLENGE" -> if (language == "hi") "अब खुद करें" else "Try it yourself"
            "HINT" -> if (language == "hi") "संकेत" else "Hint"
            "IMAGE", "DIAGRAM", "ANIMATION" -> if (language == "hi") "चित्र से समझें" else "Learn with a visual"
            "VIDEO" -> if (language == "hi") "देखकर समझें" else "Watch and learn"
            "AUDIO" -> if (language == "hi") "सुनकर समझें" else "Listen and learn"
            "AI_HELP" -> if (language == "hi") "AI Tutor" else "AI tutor"
            "SUMMARY", "RECAP" -> "Recap"
            else -> if (language == "hi") "सीखें" else "Learn"
        }
    }
    val url = data.textAny("url").ifBlank { block.textAny("asset_url") }
    val description = (if (language == "hi") data.textAny("description_hi") else "").ifBlank {
        data.textAny("description", "caption")
    }
    val caption = (if (language == "hi") data.textAny("caption_hi") else "").ifBlank {
        data.textAny("caption")
    }
    val visualKind = data.textAny("visual_kind").uppercase()
    val sourceCandidateId = data.textAny("source_candidate_id")
    val sourceBookTitle = data.textAny("source_book_title").ifBlank { data.textAny("source_book_id") }
    val sourcePdfPage = data.textAny("source_pdf_page_number")
    val sourcePageUrl = data.textAny("source_page_url")
    val body = data.textAny("body", "description", "problem", "prompt")
    val html = data.textAny("html")
    val points = data.optJSONArray("keyPoints")?.stringItems()
        ?: data.optJSONArray("points")?.stringItems()
        ?: JSONArray().stringItems()
    val steps = data.optJSONArray("steps")?.stringItems() ?: emptyList()

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            when (type) {
                "PREREQUISITE", "EXPLANATION" -> {
                    val content = if (html.isNotBlank()) htmlToPlainText(html) else body
                    if (content.isNotBlank()) LearningMathText(content, style = MaterialTheme.typography.bodyLarge)
                    points.forEach { point ->
                        LearningMathText("•  " + point, style = MaterialTheme.typography.bodyMedium)
                    }
                }
                "WORKED_EXAMPLE" -> {
                    if (body.isNotBlank()) LearningMathText(body, style = MaterialTheme.typography.bodyLarge)
                    steps.forEachIndexed { index, step ->
                        LearningMathText((index + 1).toString() + ".  " + step, style = MaterialTheme.typography.bodyMedium)
                    }
                    val answer = data.textAny("answer")
                    if (answer.isNotBlank()) {
                        LearningMathText((if (language == "hi") "हल: " else "Solution: ") + answer,
                            style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.primary)
                    }
                }
                "GUIDED_PRACTICE", "INDEPENDENT_PRACTICE", "CHALLENGE", "HINT" -> {
                    if (body.isNotBlank()) LearningMathText(body, style = MaterialTheme.typography.bodyLarge)
                    val hint = data.textAny("hint")
                    if (hint.isNotBlank()) {
                        LearningMathText((if (language == "hi") "संकेत: " else "Hint: ") + hint,
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.secondary)
                    }
                }
                "IMAGE", "DIAGRAM", "ANIMATION" -> {
                    if (visualKind.isNotBlank()) Text(visualKindLabel(visualKind, language),
                        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.secondary)
                    if (description.isNotBlank()) LearningMathText(description, style = MaterialTheme.typography.bodyMedium)
                    val mediaPath = url.substringBefore("?").lowercase()
                    val animationVideo = type == "ANIMATION" &&
                        (mediaPath.endsWith(".mp4") || mediaPath.endsWith(".webm") || mediaPath.endsWith(".ogg"))
                    val animatedImage = mediaPath.endsWith(".gif") || mediaPath.endsWith(".webp")
                    if (url.isNotBlank() && animationVideo) {
                        LessonPlayableMedia(url = url, audioOnly = false, language = language)
                    } else if (url.isNotBlank()) {
                        val alt = (if (language == "hi") data.textAny("alt_hi") else "").ifBlank {
                            data.textAny("alt").ifBlank { title }
                        }
                        if (animatedImage && Build.VERSION.SDK_INT >= 28) {
                            LessonAnimatedImage(url = url, alt = alt)
                        } else {
                            LessonImage(url = url, alt = alt)
                        }
                    } else {
                        Text((if (language == "hi") data.textAny("alt_hi") else "").ifBlank {
                            data.textAny("alt").ifBlank {
                                if (language == "hi") "इस visual के लिए अभी asset उपलब्ध नहीं है।"
                                else "No visual asset is available for this block yet."
                            }
                        }, style = MaterialTheme.typography.bodyMedium)
                    }
                    if (caption.isNotBlank()) LearningMathText(caption,
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
                    if (sourceCandidateId.isNotBlank()) {
                        LearningMathText(
                            (if (language == "hi") "स्रोत: " else "Source: ") +
                                sourceBookTitle + " · " +
                                (if (language == "hi") "PDF पृष्ठ " else "PDF page ") + sourcePdfPage,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.secondary
                        )
                        if (sourcePageUrl.startsWith("https://")) {
                            TextButton(onClick = {
                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(sourcePageUrl))
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                            }) {
                                Text(if (language == "hi") "स्रोत पृष्ठ देखें ↗" else "View source page ↗")
                            }
                        }
                    }
                }
                "VIDEO", "AUDIO" -> {
                    if (description.isNotBlank()) LearningMathText(description, style = MaterialTheme.typography.bodyMedium)
                    if (url.isNotBlank()) LessonPlayableMedia(
                        url = url,
                        audioOnly = type == "AUDIO",
                        language = language
                    ) else Text(
                        data.textAny("alt").ifBlank {
                            if (language == "hi") "मीडिया लिंक अभी उपलब्ध नहीं है।" else "The media link is not available yet."
                        }, style = MaterialTheme.typography.bodyMedium
                    )
                    if (caption.isNotBlank()) LearningMathText(caption, style = MaterialTheme.typography.bodySmall)
                    if (sourceCandidateId.isNotBlank()) {
                        LearningMathText(
                            (if (language == "hi") "स्रोत: " else "Source: ") +
                                sourceBookTitle + " · " +
                                (if (language == "hi") "PDF पृष्ठ " else "PDF page ") + sourcePdfPage,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.secondary
                        )
                        if (sourcePageUrl.startsWith("https://")) {
                            TextButton(onClick = {
                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(sourcePageUrl))
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                            }) { Text(if (language == "hi") "स्रोत पृष्ठ देखें ↗" else "View source page ↗") }
                        }
                    }
                }
                "AI_HELP" -> {
                    Text(if (language == "hi")
                        "इस पाठ के context में hint, explanation, example या step-by-step मदद पाएँ।"
                        else "Get a hint, explanation, example, or step-by-step help in this lesson's context.",
                        style = MaterialTheme.typography.bodyMedium)
                    Button(onClick = onTutorOpen, modifier = Modifier.fillMaxWidth()) {
                        Text(if (language == "hi") "Tutor खोलें" else "Open tutor")
                    }
                }
                "SUMMARY", "RECAP" -> {
                    points.forEach { point ->
                        LearningMathText("✓  " + point, style = MaterialTheme.typography.bodyLarge)
                    }
                    if (points.isEmpty() && body.isNotBlank()) LearningMathText(body, style = MaterialTheme.typography.bodyLarge)
                }
                else -> {
                    val fallback = block.safeContentText()
                    if (fallback.isNotBlank()) LearningMathText(fallback, style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
    }
}

@Composable
private fun LessonAnimatedImage(url: String, alt: String) {
    var animated by remember(url) { mutableStateOf<AnimatedImageDrawable?>(null) }
    var loadFailed by remember(url) { mutableStateOf(false) }
    var expanded by remember(url) { mutableStateOf(false) }

    LaunchedEffect(url) {
        loadFailed = false
        animated = null
        val result = withContext(Dispatchers.IO) {
            runCatching {
                if (Build.VERSION.SDK_INT < 28) throw IllegalStateException("Animated images need Android 9 or newer")
                val bytes = ApiClient.getMediaBytes(url)
                if (bytes.isEmpty() || bytes.size > MAX_MEDIA_BYTES) throw IllegalStateException("Image size is invalid")
                val drawable = ImageDecoder.decodeDrawable(ImageDecoder.createSource(ByteBuffer.wrap(bytes))) { decoder, info, _ ->
                    val maxSide = maxOf(info.size.width, info.size.height)
                    if (maxSide > MAX_IMAGE_DIMENSION) {
                        val scale = MAX_IMAGE_DIMENSION.toFloat() / maxSide.toFloat()
                        decoder.setTargetSize(
                            maxOf(1, (info.size.width * scale).toInt()),
                            maxOf(1, (info.size.height * scale).toInt())
                        )
                    }
                }
                drawable as? AnimatedImageDrawable ?: throw IllegalStateException("Image is not animated")
            }
        }
        animated = result.getOrNull()
        loadFailed = result.isFailure
    }

    val drawable = animated
    if (drawable != null) {
        DisposableEffect(drawable) {
            drawable.repeatCount = AnimatedImageDrawable.REPEAT_INFINITE
            drawable.start()
            onDispose { drawable.stop() }
        }
        AndroidView(
            factory = { viewContext ->
                ImageView(viewContext).apply {
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    adjustViewBounds = true
                    contentDescription = alt
                }
            },
            update = { view ->
                view.contentDescription = alt
                if (view.drawable !== drawable) view.setImageDrawable(drawable)
            },
            modifier = Modifier.fillMaxWidth().height(220.dp).clickable { expanded = true }
        )
        if (expanded) {
            Dialog(onDismissRequest = { expanded = false }) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(alt, style = MaterialTheme.typography.titleSmall)
                        AndroidView(
                            factory = { viewContext ->
                                ImageView(viewContext).apply {
                                    scaleType = ImageView.ScaleType.FIT_CENTER
                                    adjustViewBounds = true
                                    contentDescription = alt
                                }
                            },
                            update = { view ->
                                view.contentDescription = alt
                                if (view.drawable !== drawable) view.setImageDrawable(drawable)
                            },
                            modifier = Modifier.fillMaxWidth().height(480.dp)
                        )
                        TextButton(onClick = { expanded = false }, modifier = Modifier.align(Alignment.End)) {
                            Text(localizedUiText("Close"))
                        }
                    }
                }
            }
        }
    } else if (loadFailed) {
        LessonImage(url = url, alt = alt)
    } else {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            Text(localizedUiText("Visual लोड हो रहा है…"), style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun LessonImage(url: String, alt: String) {
    var bitmap by remember(url) { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
    var loadFailed by remember(url) { mutableStateOf(false) }
    var expanded by remember(url) { mutableStateOf(false) }
    LaunchedEffect(url) {
        loadFailed = false
        val result = withContext(Dispatchers.IO) {
            runCatching {
                val bytes = ApiClient.getMediaBytes(url)
                if (bytes.isEmpty() || bytes.size > MAX_MEDIA_BYTES) throw IllegalStateException("Image size is invalid")
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                var sample = 1
                while (maxOf(bounds.outWidth / sample, bounds.outHeight / sample) > MAX_IMAGE_DIMENSION) sample *= 2
                val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size,
                    BitmapFactory.Options().apply { inSampleSize = sample })
                    ?: throw IllegalStateException("Image could not be decoded")
                decoded.asImageBitmap()
            }
        }
        bitmap = result.getOrNull()
        loadFailed = result.isFailure
    }
    if (bitmap != null) {
        Image(bitmap = bitmap!!, contentDescription = alt,
            modifier = Modifier.fillMaxWidth().height(220.dp).clickable { expanded = true },
            contentScale = ContentScale.Fit)
        if (expanded) {
            Dialog(onDismissRequest = { expanded = false }) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(alt, style = MaterialTheme.typography.titleSmall)
                        Image(bitmap = bitmap!!, contentDescription = alt,
                            modifier = Modifier.fillMaxWidth().height(480.dp), contentScale = ContentScale.Fit)
                        TextButton(onClick = { expanded = false }, modifier = Modifier.align(Alignment.End)) {
                            Text(localizedUiText("Close"))
                        }
                    }
                }
            }
        }
    } else if (loadFailed) {
        Text(if (alt.isNotBlank()) alt else localizedUiText("Image unavailable"), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.secondary)
    } else {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            Text(localizedUiText("Visual लोड हो रहा है…"), style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun LessonPlayableMedia(url: String, audioOnly: Boolean, language: String) {
    val context = LocalContext.current
    var opened by remember(url) { mutableStateOf(false) }
    val isYoutube = url.contains("youtube.com/watch", true) || url.contains("youtu.be/", true)
    if (isYoutube) {
        Button(onClick = {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }, modifier = Modifier.fillMaxWidth()) {
            Text(if (language == "hi") "वीडियो खोलें" else "Open video")
        }
    } else if (!opened) {
        Button(onClick = { opened = true }, modifier = Modifier.fillMaxWidth()) {
            Text(if (audioOnly) {
                if (language == "hi") "ऑडियो चलाएँ" else "Play audio"
            } else {
                if (language == "hi") "वीडियो चलाएँ" else "Play video"
            })
        }
    } else {
        val mediaUrl = ApiClient.mediaUrl(url)
        val player = remember(mediaUrl) {
            ExoPlayer.Builder(context)
                .setMediaSourceFactory(
                    DefaultMediaSourceFactory(OkHttpDataSource.Factory(ApiClient.authenticatedHttpClient()))
                )
                .build().apply {
                    setMediaItem(MediaItem.fromUri(mediaUrl))
                    prepare()
                }
        }
        DisposableEffect(player) {
            onDispose { player.release() }
        }
        AndroidView(
            factory = { viewContext ->
                PlayerView(viewContext).apply {
                    this.player = player
                    useController = true
                    setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
                }
            },
            update = { it.player = player },
            modifier = Modifier.fillMaxWidth().height(if (audioOnly) 82.dp else 220.dp)
        )
    }
}

private fun visualKindLabel(kind: String, language: String): String {
    val english = language != "hi"
    return when (kind.uppercase()) {
        "PHOTO" -> if (english) "Photo" else "फ़ोटो"
        "ILLUSTRATION" -> if (english) "Illustration" else "चित्रण"
        "DIAGRAM" -> if (english) "Diagram" else "आरेख"
        "CHART" -> if (english) "Chart / graph" else "चार्ट / ग्राफ़"
        "TABLE" -> if (english) "Table / comparison" else "तालिका / तुलना"
        "INFOGRAPHIC" -> if (english) "Infographic" else "सूचनात्मक चित्र"
        "SEQUENCE" -> if (english) "Sequence / process" else "क्रम / प्रक्रिया"
        else -> if (english) "Learning visual" else "सीखने का चित्र"
    }
}

private fun htmlToPlainText(html: String): String =
    Html.fromHtml(html, Html.FROM_HTML_MODE_COMPACT).toString().trim()

private fun JSONArray.stringItems(): List<String> = buildList {
    for (index in 0 until length()) {
        val value = opt(index)
        if (value is String && value.isNotBlank()) add(value)
    }
}

private const val MAX_MEDIA_BYTES = 20 * 1024 * 1024
private const val MAX_IMAGE_DIMENSION = 1800
