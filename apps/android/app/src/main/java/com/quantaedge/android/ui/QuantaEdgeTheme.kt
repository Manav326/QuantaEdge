package com.quantaedge.android.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val QuantaColors = lightColorScheme(
    primary = Color(0xFFF97316),
    onPrimary = Color(0xFFFFFFFF),
    secondary = Color(0xFF152038),
    onSecondary = Color(0xFFFFFFFF),
    background = Color(0xFFFAFAF9),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF152038),
    onBackground = Color(0xFF152038),
    error = Color(0xFFB91C1C)
)

@Composable
fun QuantaEdgeTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = QuantaColors, content = content)
}
