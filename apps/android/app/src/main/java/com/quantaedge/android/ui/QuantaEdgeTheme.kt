package com.quantaedge.android.ui

import org.json.JSONObject
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
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
import androidx.compose.ui.unit.sp

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
        primaryText = Color(0xFFB0561C),
        accentText = Color(0xFF0F766E),
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
            onPrimary = tokenColor(palette, "onPrimary", Color(0xFF111827)),
            primaryContainer = tokenColor(palette, "primaryContainer", Color(0xFF362F33)),
            onPrimaryContainer = tokenColor(palette, "onPrimaryContainer", Color(0xFFFFFFFF)),
            secondary = tokenColor(palette, "accent", Color(0xFF0F766E)),
            onSecondary = tokenColor(palette, "onAccent", Color(0xFFFFFFFF)),
            secondaryContainer = tokenColor(palette, "accentContainer", Color(0xFF1A303E)),
            onSecondaryContainer = tokenColor(palette, "onAccentContainer", Color(0xFFFFFFFF)),
            tertiary = tokenColor(palette, "success", Color(0xFF4ADE80)),
            onTertiary = tokenColor(palette, "onSuccess", Color(0xFF052E16)),
            background = tokenColor(palette, "background", Color(0xFF121A28)),
            onBackground = tokenColor(palette, "onBackground", Color(0xFFF4F7FB)),
            surface = tokenColor(palette, "surface", Color(0xFF1B2637)),
            onSurface = tokenColor(palette, "onSurface", Color(0xFFF4F7FB)),
            surfaceVariant = tokenColor(palette, "surfaceElevated", Color(0xFF243247)),
            onSurfaceVariant = tokenColor(palette, "textMuted", Color(0xFFB6C2D2)),
            outline = tokenColor(palette, "border", Color(0xFF66758B)),
            outlineVariant = tokenColor(palette, "borderSubtle", Color(0xFF354156)),
            error = tokenColor(palette, "error", Color(0xFFFDA4AF)),
            onError = tokenColor(palette, "onError", Color(0xFF450A0A)),
            errorContainer = tokenColor(palette, "errorContainer", Color(0xFF451C28)),
            onErrorContainer = tokenColor(palette, "onErrorContainer", Color(0xFFFFE1E8)),
        )
    } else {
        lightColorScheme(
            primary = tokenColor(palette, "primary", Color(0xFFF97316)),
            onPrimary = tokenColor(palette, "onPrimary", Color(0xFF291400)),
            primaryContainer = tokenColor(palette, "primaryContainer", Color(0xFFFEF1E8)),
            onPrimaryContainer = tokenColor(palette, "onPrimaryContainer", Color(0xFF111827)),
            secondary = tokenColor(palette, "accent", Color(0xFF0F766E)),
            onSecondary = tokenColor(palette, "onAccent", Color(0xFFFFFFFF)),
            secondaryContainer = tokenColor(palette, "accentContainer", Color(0xFFE7F1F0)),
            onSecondaryContainer = tokenColor(palette, "onAccentContainer", Color(0xFF111827)),
            tertiary = tokenColor(palette, "success", Color(0xFF166534)),
            onTertiary = tokenColor(palette, "onSuccess", Color(0xFFFFFFFF)),
            background = tokenColor(palette, "background", Color(0xFFF5F7FB)),
            onBackground = tokenColor(palette, "onBackground", Color(0xFF172033)),
            surface = tokenColor(palette, "surface", Color(0xFFFFFFFF)),
            onSurface = tokenColor(palette, "onSurface", Color(0xFF172033)),
            surfaceVariant = tokenColor(palette, "surfaceElevated", Color(0xFFFFFFFF)),
            onSurfaceVariant = tokenColor(palette, "textMuted", Color(0xFF536176)),
            outline = tokenColor(palette, "border", Color(0xFF7B8798)),
            outlineVariant = tokenColor(palette, "borderSubtle", Color(0xFFD7DEE8)),
            error = tokenColor(palette, "error", Color(0xFFB42318)),
            onError = tokenColor(palette, "onError", Color(0xFFFFFFFF)),
            errorContainer = tokenColor(palette, "errorContainer", Color(0xFFFEE2E2)),
            onErrorContainer = tokenColor(palette, "onErrorContainer", Color(0xFF7F1D1D)),
        )
    }

    // Tighten vertical leading globally without shrinking text: this frees space while
    // keeping lesson content, question options and Hindi glyphs readable.
    val compactTypography = remember {
        val base = Typography()
        base.copy(
            displayLarge = base.displayLarge.copy(lineHeight = 60.sp),
            displayMedium = base.displayMedium.copy(lineHeight = 48.sp),
            displaySmall = base.displaySmall.copy(lineHeight = 40.sp),
            headlineLarge = base.headlineLarge.copy(lineHeight = 36.sp),
            headlineMedium = base.headlineMedium.copy(lineHeight = 32.sp),
            headlineSmall = base.headlineSmall.copy(lineHeight = 28.sp),
            titleLarge = base.titleLarge.copy(lineHeight = 26.sp),
            titleMedium = base.titleMedium.copy(lineHeight = 21.sp),
            titleSmall = base.titleSmall.copy(lineHeight = 19.sp),
            bodyLarge = base.bodyLarge.copy(lineHeight = 22.sp),
            bodyMedium = base.bodyMedium.copy(lineHeight = 19.sp),
            bodySmall = base.bodySmall.copy(lineHeight = 16.sp),
            labelLarge = base.labelLarge.copy(lineHeight = 18.sp),
            labelMedium = base.labelMedium.copy(lineHeight = 15.sp),
            labelSmall = base.labelSmall.copy(lineHeight = 14.sp),
        )
    }

    val semanticColors = QuantaEdgeSemanticColors(
        primaryText = tokenColor(
            palette, "primaryText",
            if (useDark) Color(0xFFF97316) else Color(0xFFB0561C)
        ),
        accentText = tokenColor(
            palette, "accentText",
            if (useDark) Color(0xFF5AA09B) else Color(0xFF0F766E)
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
        typography = compactTypography,
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
