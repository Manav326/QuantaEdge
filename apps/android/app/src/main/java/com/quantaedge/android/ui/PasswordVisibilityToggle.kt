package com.quantaedge.android.ui

import androidx.compose.foundation.Canvas
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/** Small vector eye/eye-off control shared by password fields across the Android app. */
@Composable
internal fun PasswordVisibilityToggle(
    visible: Boolean,
    onToggle: () -> Unit,
    description: String,
) {
    val color = MaterialTheme.colorScheme.onSurfaceVariant
    IconButton(
        onClick = onToggle,
        modifier = Modifier.semantics { contentDescription = description },
    ) {
        Canvas(Modifier) {
            val left = 1.5.dp.toPx()
            val right = size.width - left
            val middle = size.height / 2f
            val upperLower = 2.5.dp.toPx()
            val eye = Path().apply {
                moveTo(left, middle)
                cubicTo(size.width * 0.27f, upperLower, size.width * 0.73f, upperLower, right, middle)
                cubicTo(size.width * 0.73f, size.height - upperLower, size.width * 0.27f, size.height - upperLower, left, middle)
            }
            val stroke = 1.7.dp.toPx()
            drawPath(eye, color, style = Stroke(width = stroke))
            drawCircle(color, radius = size.minDimension * 0.14f, center = Offset(size.width / 2f, middle))
            if (visible) {
                drawLine(
                    color = color,
                    start = Offset(size.width * 0.18f, size.height * 0.82f),
                    end = Offset(size.width * 0.82f, size.height * 0.18f),
                    strokeWidth = stroke,
                )
            }
        }
    }
}
