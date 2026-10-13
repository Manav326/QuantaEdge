package com.quantaedge.android.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * QuantaEdge's learning-oriented design tokens.
 * The orange/navy brand remains distinct from mPay; mPay is only the
 * reference for responsive behavior and reusable-component discipline.
 */
private val QuantaColors = lightColorScheme(
    primary = Color(0xFFF97316),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFFFEDD5),
    onPrimaryContainer = Color(0xFF7C2D12),
    secondary = Color(0xFF152038),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFE8EEF8),
    onSecondaryContainer = Color(0xFF172554),
    tertiary = Color(0xFF0F766E),
    onTertiary = Color(0xFFFFFFFF),
    background = Color(0xFFF8FAFC),
    surface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFFF1F5F9),
    onSurface = Color(0xFF152038),
    onSurfaceVariant = Color(0xFF475569),
    onBackground = Color(0xFF152038),
    outline = Color(0xFFCBD5E1),
    outlineVariant = Color(0xFFE2E8F0),
    error = Color(0xFFB91C1C),
    onError = Color(0xFFFFFFFF)
)

private val QuantaShapes = Shapes(
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(24.dp)
)

@Composable
fun QuantaEdgeTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = QuantaColors,
        shapes = QuantaShapes,
        content = content
    )
}
