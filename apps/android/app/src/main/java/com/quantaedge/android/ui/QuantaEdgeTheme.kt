package com.quantaedge.android.ui

import org.json.JSONObject
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.dp

val LocalQuantaEdgeThemeController = staticCompositionLocalOf<QuantaEdgeThemeController> {
    error("QuantaEdgeThemeController is missing from the composition.")
}

/**
 * Contrast-safe foreground/status roles. MaterialTheme.colorScheme.primary remains the actual brand
 * colour for filled controls; labels must use primaryText/accentText instead.
 */
data class QuantaEdgeSemanticColors(
    val primaryText: Color,
    val accentText: Color,
    val success: Color,
    val warning: Color,
    val error: Color
)

val LocalQuantaEdgeSemanticColors = staticCompositionLocalOf {
    QuantaEdgeSemanticColors(
        primaryText = Color(0xFF9A3412),
        accentText = Color(0xFF9A3412),
        success = Color(0xFF166534),
        warning = Color(0xFF92400E),
        error = Color(0xFFB42318)
    )
}

/**
 * Central Android colour system. The active institution palette comes from the server-normalized
 * v2 theme contract; QuantaEdge orange/navy remain the safe default when no tenant is resolved.
 */
@Composable
fun QuantaEdgeTheme(
    controller: QuantaEdgeThemeController,
    content: @Composable () -> Unit
) {
    val systemDark = isSystemInDarkTheme()
    val themeJson = remember(controller.themeTokensJson) {
        runCatching { controller.themeTokensJson?.let(::JSONObject) }.getOrNull()
    }
    val configuredAppearance = themeJson?.optString("appearance", "system") ?: "system"
    val useDark = when (controller.appearanceMode) {
        QeAppearanceMode.LIGHT -> false
        QeAppearanceMode.DARK -> true
        QeAppearanceMode.SYSTEM -> systemDark
        QeAppearanceMode.INSTITUTION -> when (configuredAppearance) {
            "light" -> false
            "dark" -> true
            else -> systemDark
        }
    }
    val palette = themeJson?.optJSONObject(if (useDark) "dark" else "light")
    val colors = if (useDark) {
        darkColorScheme(
            primary = tokenColor(palette, "primary", Color(0xFFF97316)),
            onPrimary = tokenColor(palette, "onPrimary", Color(0xFF291400)),
            primaryContainer = tokenColor(palette, "primaryContainer", Color(0xFF48220D)),
            onPrimaryContainer = tokenColor(palette, "onPrimaryContainer", Color(0xFFFFF0E5)),
            secondary = tokenColor(palette, "accent", Color(0xFFC2410C)),
            onSecondary = tokenColor(palette, "onAccent", Color(0xFFFFFFFF)),
            secondaryContainer = tokenColor(palette, "primaryContainer", Color(0xFF48220D)),
            onSecondaryContainer = tokenColor(palette, "onPrimaryContainer", Color(0xFFFFF0E5)),
            tertiary = tokenColor(palette, "success", Color(0xFF4ADE80)),
            onTertiary = tokenColor(palette, "onSuccess", Color(0xFF052E16)),
            background = tokenColor(palette, "background", Color(0xFF0B1220)),
            onBackground = tokenColor(palette, "onBackground", Color(0xFFF3F4F6)),
            surface = tokenColor(palette, "surface", Color(0xFF111827)),
            onSurface = tokenColor(palette, "onSurface", Color(0xFFF3F4F6)),
            surfaceVariant = tokenColor(palette, "surfaceElevated", Color(0xFF182235)),
            onSurfaceVariant = tokenColor(palette, "textMuted", Color(0xFFD1D5DB)),
            outline = tokenColor(palette, "border", Color(0xFF6B7280)),
            outlineVariant = tokenColor(palette, "borderSubtle", Color(0xFF4B5563)),
            error = tokenColor(palette, "error", Color(0xFFFDA4AF)),
            onError = tokenColor(palette, "onError", Color(0xFF450A0A)),
            errorContainer = tokenColor(palette, "errorContainer", Color(0xFF7F1D1D)),
            onErrorContainer = tokenColor(palette, "onErrorContainer", Color(0xFFFEE2E2)),
        )
    } else {
        lightColorScheme(
            primary = tokenColor(palette, "primary", Color(0xFFF97316)),
            onPrimary = tokenColor(palette, "onPrimary", Color(0xFF291400)),
            primaryContainer = tokenColor(palette, "primaryContainer", Color(0xFFFFF0E5)),
            onPrimaryContainer = tokenColor(palette, "onPrimaryContainer", Color(0xFF7C2D12)),
            secondary = tokenColor(palette, "accent", Color(0xFFC2410C)),
            onSecondary = tokenColor(palette, "onAccent", Color(0xFFFFFFFF)),
            secondaryContainer = tokenColor(palette, "primaryContainer", Color(0xFFFFF0E5)),
            onSecondaryContainer = tokenColor(palette, "onPrimaryContainer", Color(0xFF7C2D12)),
            tertiary = tokenColor(palette, "success", Color(0xFF166534)),
            onTertiary = tokenColor(palette, "onSuccess", Color(0xFFFFFFFF)),
            background = tokenColor(palette, "background", Color(0xFFF8FAFC)),
            onBackground = tokenColor(palette, "onBackground", Color(0xFF172033)),
            surface = tokenColor(palette, "surface", Color(0xFFFFFFFF)),
            onSurface = tokenColor(palette, "onSurface", Color(0xFF172033)),
            surfaceVariant = tokenColor(palette, "surfaceElevated", Color(0xFFFFFFFF)),
            onSurfaceVariant = tokenColor(palette, "textMuted", Color(0xFF475467)),
            outline = tokenColor(palette, "border", Color(0xFF737D91)),
            outlineVariant = tokenColor(palette, "borderSubtle", Color(0xFFD1D5DB)),
            error = tokenColor(palette, "error", Color(0xFFB42318)),
            onError = tokenColor(palette, "onError", Color(0xFFFFFFFF)),
            errorContainer = tokenColor(palette, "errorContainer", Color(0xFFFEE2E2)),
            onErrorContainer = tokenColor(palette, "onErrorContainer", Color(0xFF7F1D1D)),
        )
    }

    val semanticColors = QuantaEdgeSemanticColors(
        primaryText = tokenColor(
            palette, "primaryText",
            if (useDark) Color(0xFFFDBA74) else Color(0xFF9A3412)
        ),
        accentText = tokenColor(
            palette, "accentText",
            if (useDark) Color(0xFFFDBA74) else Color(0xFF9A3412)
        ),
        success = tokenColor(
            palette, "success",
            if (useDark) Color(0xFF4ADE80) else Color(0xFF166534)
        ),
        warning = tokenColor(
            palette, "warning",
            if (useDark) Color(0xFFFCD34D) else Color(0xFF92400E)
        ),
        error = tokenColor(
            palette, "error",
            if (useDark) Color(0xFFFDA4AF) else Color(0xFFB42318)
        )
    )

    MaterialTheme(
        colorScheme = colors,
        shapes = QuantaShapes,
        content = {
            CompositionLocalProvider(
                LocalQuantaEdgeThemeController provides controller,
                LocalQuantaEdgeSemanticColors provides semanticColors,
                content = content
            )
        }
    )
}

private fun tokenColor(palette: JSONObject?, role: String, fallback: Color): Color {
    val raw = palette?.optString(role, "") ?: ""
    if (!raw.matches(Regex("^#[0-9A-Fa-f]{6}$"))) return fallback
    return runCatching { Color(android.graphics.Color.parseColor(raw)) }.getOrDefault(fallback)
}

private val QuantaShapes = Shapes(
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(24.dp)
)
