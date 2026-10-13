package com.quantaedge.android.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.quantaedge.android.data.ApiClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException

@Composable
fun ProfilePhotoPickerInput(
    profilePhotoUrl: String?,
    busy: Boolean,
    onPhotoSelected: (String) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var displayedBytes by remember { mutableStateOf<ByteArray?>(null) }
    var selectedDataUrl by remember { mutableStateOf<String?>(null) }
    var selecting by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }

    LaunchedEffect(profilePhotoUrl) {
        if (selectedDataUrl == null && !profilePhotoUrl.isNullOrBlank()) {
            displayedBytes = runCatching { ApiClient.getBytes(profilePhotoUrl) }.getOrNull()
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri: Uri? ->
        if (uri != null) {
            scope.launch {
                selecting = true
                error = ""
                try {
                    val dataUrl = withContext(Dispatchers.IO) { encodeJpegDataUrl(context, uri) }
                    selectedDataUrl = dataUrl
                    displayedBytes = Base64.decode(dataUrl.substringAfter(','), Base64.NO_WRAP)
                    onPhotoSelected(dataUrl)
                } catch (failure: Exception) {
                    error = failure.message ?: "फोटो पढ़ी नहीं जा सकी।"
                } finally {
                    selecting = false
                }
            }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(7.dp), horizontalAlignment = Alignment.Start) {
        displayedBytes?.let { bytes ->
            val bitmap = remember(bytes) { BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap() }
            if (bitmap != null) {
                Image(bitmap = bitmap, contentDescription = "Profile photo",
                    modifier = Modifier.size(88.dp), contentScale = ContentScale.Crop)
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            }, enabled = !busy && !selecting) {
                Text(if (selecting) "फोटो तैयार हो रही है…" else "प्रोफ़ाइल फोटो चुनें")
            }
            if (selectedDataUrl != null) {
                Text(localizedUiText("नई फोटो चुनी गई"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
            } else if (displayedBytes != null) {
                Text(localizedUiText("वर्तमान फोटो"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
            }
        }
        if (error.isNotBlank()) Text(error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        Text(localizedUiText("केवल फोटो चुनी जाती है। कोई broad storage permission नहीं माँगी जाती; सुरक्षित JPEG को आपके QuantaEdge खाते में भेजा जाता है।"),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
    }
}

private fun encodeJpegDataUrl(context: Context, uri: Uri): String {
    val source = context.contentResolver.openInputStream(uri) ?: throw IOException("फोटो फ़ाइल नहीं खुली।")
    val original = source.use { input ->
        ByteArrayOutputStream().use { output ->
            val buffer = ByteArray(16 * 1024)
            var total = 0
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                total += count
                if (total > MAX_SOURCE_BYTES) throw IOException("फोटो बहुत बड़ी है। छोटी फोटो चुनें।")
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
    }

    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(original, 0, original.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw IOException("यह image format समर्थित नहीं है।")
    var sampleSize = 1
    while (maxOf(bounds.outWidth / sampleSize, bounds.outHeight / sampleSize) > MAX_DIMENSION) sampleSize *= 2
    var bitmap = BitmapFactory.decodeByteArray(original, 0, original.size,
        BitmapFactory.Options().apply { inSampleSize = sampleSize })
        ?: throw IOException("फोटो decode नहीं हो सकी।")

    try {
        repeat(6) {
            var quality = 88
            while (quality >= 48) {
                val output = ByteArrayOutputStream()
                if (!bitmap.compress(Bitmap.CompressFormat.JPEG, quality, output)) {
                    throw IOException("फोटो को JPEG में बदला नहीं जा सका।")
                }
                val bytes = output.toByteArray()
                if (bytes.size <= MAX_COMPRESSED_BYTES) {
                    return "data:image/jpeg;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP)
                }
                quality -= 10
            }
            val width = (bitmap.width * SCALE_FACTOR).toInt().coerceAtLeast(1)
            val height = (bitmap.height * SCALE_FACTOR).toInt().coerceAtLeast(1)
            val smaller = Bitmap.createScaledBitmap(bitmap, width, height, true)
            if (smaller !== bitmap) bitmap.recycle()
            bitmap = smaller
        }
    } finally {
        if (!bitmap.isRecycled) bitmap.recycle()
    }
    throw IOException("फोटो 350 KB से छोटी नहीं हो सकी। दूसरी फोटो चुनें।")
}

private const val MAX_SOURCE_BYTES = 15 * 1024 * 1024
private const val MAX_COMPRESSED_BYTES = 340 * 1024
private const val MAX_DIMENSION = 1600
private const val SCALE_FACTOR = 0.82
