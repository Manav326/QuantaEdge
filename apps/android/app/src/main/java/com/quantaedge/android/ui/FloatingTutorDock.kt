package com.quantaedge.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import org.json.JSONObject
import kotlin.math.max

/**
 * Lesson-only floating shell for the native tutor.
 *
 * Conversation/session state stays in QuantaEdgeApp; this component only owns
 * geometry and presentation state so minimizing or resizing never restarts chat.
 */
@Composable
fun FloatingTutorDock(
    minimized: Boolean,
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
    onMinimize: () -> Unit,
    onRestore: () -> Unit,
    onRetry: () -> Unit,
    onClose: () -> Unit
) {
    var panelWidth by rememberSaveable { mutableStateOf(390f) }
    var panelHeight by rememberSaveable { mutableStateOf(540f) }
    var left by rememberSaveable { mutableStateOf<Float?>(null) }
    var top by rememberSaveable { mutableStateOf<Float?>(null) }
    var bubbleLeft by rememberSaveable { mutableStateOf<Float?>(null) }
    var bubbleTop by rememberSaveable { mutableStateOf<Float?>(null) }
    var maximized by rememberSaveable { mutableStateOf(false) }
    var userResized by rememberSaveable { mutableStateOf(false) }
    val density = LocalDensity.current.density

    BoxWithConstraints(
        modifier = Modifier.fillMaxSize().zIndex(20f)
    ) {
        val viewportWidth = maxWidth.value
        val viewportHeight = maxHeight.value
        val compact = maxWidth < 700.dp
        // Real viewport bounds take precedence over minimum ergonomic sizes. This matters
        // in split-screen, landscape and when IME/window insets reduce available space.
        val availableWidth = (maxWidth - 16.dp).coerceAtLeast(1.dp)
        val availableHeight = (maxHeight - 16.dp).coerceAtLeast(1.dp)
        val compactMinimumWidth = minOf(260.dp, availableWidth)
        val compactMinimumHeight = minOf(260.dp, availableHeight)
        val wideMinimumWidth = minOf(320.dp, availableWidth)
        val wideMinimumHeight = minOf(360.dp, availableHeight)
        val compactHeight = if (availableHeight < 280.dp) availableHeight
            else (maxHeight * 0.78f).coerceIn(minOf(280.dp, availableHeight), availableHeight)
        // Keep the mobile default fitted to the viewport, then honour any user resize.
        val effectiveWidth = if (compact && !userResized) availableWidth
            else if (compact) panelWidth.dp.coerceIn(compactMinimumWidth, availableWidth)
            else panelWidth.dp.coerceIn(wideMinimumWidth, availableWidth)
        val effectiveHeight = if (compact && !userResized) compactHeight
            else if (compact) panelHeight.dp.coerceIn(compactMinimumHeight, availableHeight)
            else panelHeight.dp.coerceIn(wideMinimumHeight, availableHeight)
        val defaultLeft = if (compact) 8f else (viewportWidth - effectiveWidth.value - 16f).coerceAtLeast(8f)
        val defaultTop = (viewportHeight - effectiveHeight.value - if (compact) 12f else 18f).coerceAtLeast(8f)
        val panelLeft = (left ?: defaultLeft).coerceIn(8f, (viewportWidth - effectiveWidth.value - 8f).coerceAtLeast(8f))
        val panelTop = (top ?: defaultTop).coerceIn(8f, (viewportHeight - effectiveHeight.value - 8f).coerceAtLeast(8f))

        if (minimized) {
            val bubbleSize = 58.dp
            val bubbleDefaultLeft = (viewportWidth - bubbleSize.value - 18f).coerceAtLeast(8f)
            val bubbleDefaultTop = (viewportHeight - bubbleSize.value - 22f).coerceAtLeast(8f)
            val bubbleX = (bubbleLeft ?: bubbleDefaultLeft).coerceIn(8f, (viewportWidth - bubbleSize.value - 8f).coerceAtLeast(8f))
            val bubbleY = (bubbleTop ?: bubbleDefaultTop).coerceIn(8f, (viewportHeight - bubbleSize.value - 8f).coerceAtLeast(8f))
            Surface(
                modifier = Modifier
                    .offset(x = bubbleX.dp, y = bubbleY.dp)
                    .size(bubbleSize)
                    .clip(CircleShape)
                    .clickable(onClick = onRestore)
                    .pointerInput(viewportWidth, viewportHeight) {
                        detectDragGestures { change, dragAmount ->
                            change.consume()
                            bubbleLeft = ((bubbleLeft ?: bubbleDefaultLeft) + dragAmount.x / density)
                                .coerceIn(8f, (viewportWidth - bubbleSize.value - 8f).coerceAtLeast(8f))
                            bubbleTop = ((bubbleTop ?: bubbleDefaultTop) + dragAmount.y / density)
                                .coerceIn(8f, (viewportHeight - bubbleSize.value - 8f).coerceAtLeast(8f))
                        }
                    },
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                shadowElevation = 12.dp
            ) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                    Text("✦", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                }
            }
        } else {
            val frameModifier = if (maximized) {
                Modifier.fillMaxSize().padding(8.dp)
            } else {
                Modifier
                    .offset(x = panelLeft.dp, y = panelTop.dp)
                    .width(effectiveWidth)
                    .height(effectiveHeight)
            }

            Surface(
                modifier = frameModifier.zIndex(21f),
                shape = RoundedCornerShape(if (maximized) 14.dp else 20.dp),
                color = MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.onSurface,
                shadowElevation = 18.dp,
                tonalElevation = 3.dp
            ) {
                Box(Modifier.fillMaxSize()) {
                    val dragHeader = if (maximized) {
                        Modifier
                    } else {
                        Modifier.pointerInput(viewportWidth, viewportHeight, effectiveWidth, effectiveHeight) {
                            detectDragGestures { change, dragAmount ->
                                change.consume()
                                left = ((left ?: defaultLeft) + dragAmount.x / density)
                                    .coerceIn(8f, (viewportWidth - effectiveWidth.value - 8f).coerceAtLeast(8f))
                                top = ((top ?: defaultTop) + dragAmount.y / density)
                                    .coerceIn(8f, (viewportHeight - effectiveHeight.value - 8f).coerceAtLeast(8f))
                            }
                        }
                    }
                    TutorScreen(
                        lessonTitle = lessonTitle,
                        learningLanguage = learningLanguage,
                        sessionId = sessionId,
                        connecting = connecting,
                        available = available,
                        messages = messages,
                        hasOlderMessages = hasOlderMessages,
                        loadingOlderMessages = loadingOlderMessages,
                        busy = busy,
                        error = error,
                        onSend = onSend,
                        onLoadOlder = onLoadOlder,
                        onNewChat = onNewChat,
                        onMinimize = onMinimize,
                        onMaximize = { maximized = !maximized },
                        onRetry = onRetry,
                        onClose = onClose,
                        modifier = Modifier.fillMaxSize(),
                        headerDragModifier = dragHeader
                    )
                    if (!maximized) {
                        Surface(
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .padding(2.dp)
                                .size(30.dp)
                                .clip(RoundedCornerShape(bottomEnd = 18.dp, topStart = 8.dp))
                                .pointerInput(viewportWidth, viewportHeight, effectiveWidth, effectiveHeight) {
                                    detectDragGestures { change, dragAmount ->
                                        change.consume()
                                        userResized = true
                                        val maxResizeWidth = (viewportWidth - panelLeft - 8f).coerceAtLeast(1f)
                                        val maxResizeHeight = (viewportHeight - panelTop - 8f).coerceAtLeast(1f)
                                        panelWidth = (effectiveWidth.value + dragAmount.x / density)
                                            .coerceIn(minOf(260f, maxResizeWidth), maxResizeWidth)
                                        panelHeight = (effectiveHeight.value + dragAmount.y / density)
                                            .coerceIn(minOf(260f, maxResizeHeight), maxResizeHeight)
                                    }
                                },
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            contentColor = MaterialTheme.colorScheme.primary
                        ) {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Text("◢", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        }
    }
}
