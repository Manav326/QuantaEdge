package com.quantaedge.android.ui

import android.annotation.SuppressLint
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import java.util.Locale

internal fun isValidYouTubeVideoId(videoId: String): Boolean =
    videoId.matches(Regex("^[A-Za-z0-9_-]{11}$"))

private fun isAllowedYouTubeEmbedHost(host: String): Boolean {
    val normalized = host.lowercase(Locale.ROOT)
    val allowedSuffixes = listOf(
        "youtube-nocookie.com", "youtube.com", "ytimg.com",
        "googlevideo.com", "googleapis.com", "gstatic.com", "google.com"
    )
    return allowedSuffixes.any { normalized == it || normalized.endsWith(".$it") }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun LiveBroadcastPlayerScreen(title: String, videoId: String, onBack: () -> Unit, onLeave: () -> Unit) {
    val context = LocalContext.current
    val language = LocalQuantaLanguage.current
    fun t(en: String, hi: String) = if (language == "hi") hi else en
    val valid = remember(videoId) { isValidYouTubeVideoId(videoId) }
    val webView = remember(videoId, valid) {
        if (!valid) null else WebView(context).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.allowFileAccessFromFileURLs = false
            settings.allowUniversalAccessFromFileURLs = false
            settings.javaScriptCanOpenWindowsAutomatically = false
            settings.setSupportMultipleWindows(false)
            settings.mediaPlaybackRequiresUserGesture = true
            settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
            settings.safeBrowsingEnabled = true
            webChromeClient = WebChromeClient()
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                    val host = request?.url?.host ?: return true
                    return !isAllowedYouTubeEmbedHost(host)
                }
            }
            val html = """
                <!doctype html>
                <html><head>
                  <meta name="viewport" content="width=device-width, initial-scale=1, maximum-scale=1" />
                  <style>html,body{margin:0;padding:0;width:100%;height:100%;overflow:hidden;background:#000}iframe{display:block;width:100%;height:100%;border:0}</style>
                </head><body>
                  <iframe title="Live broadcast"
                    src="https://www.youtube-nocookie.com/embed/$videoId?playsinline=1"
                    allow="accelerometer; autoplay; encrypted-media; gyroscope; picture-in-picture; web-share"
                    referrerpolicy="strict-origin-when-cross-origin" allowfullscreen></iframe>
                </body></html>
            """.trimIndent()
            setBackgroundColor(android.graphics.Color.BLACK)
            loadDataWithBaseURL("https://www.youtube-nocookie.com/", html, "text/html", "UTF-8", null)
        }
    }
    DisposableEffect(webView) {
        onDispose {
            webView?.stopLoading()
            webView?.loadUrl("about:blank")
            webView?.destroy()
        }
    }
    Column(Modifier.fillMaxSize().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(title, style = MaterialTheme.typography.titleLarge)
                Text(t("YouTube Live broadcast", "YouTube लाइव प्रसारण"), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary)
            }
            TextButton(onClick = onBack) { Text(t("Back to schedule", "समय-सारणी पर वापस")) }
        }
        if (webView != null) {
            AndroidView(factory = { webView }, modifier = Modifier.fillMaxWidth().weight(1f))
        } else {
            Text(t("This broadcast reference is invalid. Return to the schedule and contact your teacher.",
                "इस प्रसारण की पहचान मान्य नहीं है। समय-सारणी पर लौटें और शिक्षक से संपर्क करें。"),
                color = MaterialTheme.colorScheme.error)
        }
        Button(onClick = onLeave, modifier = Modifier.fillMaxWidth()) {
            Text(t("Leave class and save attendance", "कक्षा छोड़ें और उपस्थिति सहेजें"))
        }
    }
}
