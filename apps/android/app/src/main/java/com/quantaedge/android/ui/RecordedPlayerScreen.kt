package com.quantaedge.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import com.quantaedge.android.data.ApiClient
import kotlinx.coroutines.delay
import org.json.JSONObject
import com.quantaedge.android.data.optLongAny
import android.net.Uri
import java.io.File

@Composable
fun RecordedPlayerScreen(
    recording: JSONObject,
    startPositionSeconds: Long,
    onSaveProgress: (Long, Long, Boolean) -> Unit,
    onSaveAndBack: (Long, Long, Boolean) -> Unit,
    offlineFile: File? = null
) {
    val context = LocalContext.current
    val language = LocalQuantaLanguage.current
    fun t(en: String, hi: String) = if (language == "hi") hi else en
    val recordingId = recording.optLongAny("recorded_class_id", "recordingId", "id")
    val durationSeconds = recording.optLongAny("duration_seconds", "durationSeconds")
    var watchedSeconds by rememberSaveable(recordingId) { mutableStateOf(recording.optLongAny("watched_seconds", "watchedSeconds")) }
    var currentPosition by rememberSaveable(recordingId) { mutableStateOf(startPositionSeconds.coerceAtLeast(0)) }
    var isEnded by rememberSaveable(recordingId) { mutableStateOf(false) }

    val player = remember(recordingId, offlineFile?.absolutePath) {
        val dataSource = DefaultDataSource.Factory(context,
            OkHttpDataSource.Factory(ApiClient.authenticatedHttpClient()))
        val mediaUri = offlineFile?.let { Uri.fromFile(it) }
            ?: Uri.parse(ApiClient.absoluteUrl("/api/v1/learning/recorded-classes/" + recordingId + "/stream"))
        ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSource))
            .build().apply {
                setMediaItem(MediaItem.fromUri(mediaUri))
                prepare()
                seekTo(currentPosition.coerceAtLeast(0) * 1000)
            }
    }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                isEnded = playbackState == Player.STATE_ENDED
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            player.release()
        }
    }

    LaunchedEffect(player) {
        while (true) {
            delay(15_000)
            if (player.isPlaying) {
                currentPosition = (player.currentPosition / 1000).coerceAtLeast(0)
                watchedSeconds = (watchedSeconds + 15).coerceAtMost(durationSeconds.takeIf { it > 0 } ?: Long.MAX_VALUE)
                val completed = durationSeconds > 0 && currentPosition >= durationSeconds - 10
                onSaveProgress(currentPosition, watchedSeconds, completed)
            }
            if (isEnded) {
                currentPosition = (player.duration / 1000).coerceAtLeast(0)
                onSaveProgress(currentPosition, watchedSeconds, true)
                break
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(recording.textAny("title").ifBlank { t("Recorded class", "रिकॉर्ड किया गया सत्र") }, style = MaterialTheme.typography.headlineSmall)
        Text(recording.textAny("chapter_name", "subject_name", "description"), style = MaterialTheme.typography.bodyMedium)
        AndroidView(
            factory = { viewContext ->
                PlayerView(viewContext).apply {
                    this.player = player
                    useController = true
                    setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
                }
            },
            update = { it.player = player },
            modifier = Modifier.fillMaxWidth().height(250.dp)
        )
        Text(t("Playback position: ", "वीडियो की स्थिति: ") + currentPosition + " " + t("seconds", "सेकंड"),
            style = MaterialTheme.typography.bodySmall)
        Text(if (offlineFile != null) t("Offline copy is playing. Playback progress is saved locally and synced when connected.", "Offline वीडियो चल रहा है। प्रगति डिवाइस पर सहेजी जाएगी और कनेक्शन मिलने पर sync होगी।")
            else localizedUiText("Progress is saved to your QuantaEdge account periodically."),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
        Button(onClick = {
            currentPosition = (player.currentPosition / 1000).coerceAtLeast(0)
            val complete = isEnded || (durationSeconds > 0 && currentPosition >= durationSeconds - 10)
            onSaveAndBack(currentPosition, watchedSeconds, complete)
        }, modifier = Modifier.fillMaxWidth()) { Text(localizedUiText("प्रगति सुरक्षित कर वापस जाएँ")) }
    }
}

