package com.quantaedge.android.ui

import android.content.Context
import org.json.JSONException
import org.json.JSONObject
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

enum class QeAppearanceMode {
    INSTITUTION, SYSTEM, LIGHT, DARK
}

/**
 * Runtime state for tenant colours and user appearance preferences.
 *
 * Only JSON returned by the authenticated, server-resolved institution branding API should be
 * passed to applyInstitutionTheme. The controller validates the contract and scopes persisted
 * palettes by institution code so switching accounts cannot reuse the previous tenant's colours.
 */
class QuantaEdgeThemeController(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    var appearanceMode by mutableStateOf(readAppearanceMode())
        private set

    // Do not render a palette left active by a previous process/session before this process
    // re-resolves the institution. QuantaEdge defaults render until a trusted tenant code is resolved.
    var activeInstitutionCode by mutableStateOf<String?>(null)
        private set

    var themeTokensJson by mutableStateOf<String?>(null)
        private set

    /** A validated, theme-aware accent for supported platform-owned checkout UI. */
    fun checkoutAccentColor(): String {
        val raw = runCatching {
            themeTokensJson?.let(::JSONObject)?.optJSONObject("light")?.optString("primary")
        }.getOrNull()
        return raw?.takeIf { HEX_COLOUR.matches(it) } ?: "#F97316"
    }

    fun updateAppearanceMode(mode: QeAppearanceMode) {
        appearanceMode = mode
        val editor = preferences.edit()
        if (mode == QeAppearanceMode.INSTITUTION) editor.remove(KEY_USER_APPEARANCE)
        else editor.putString(KEY_USER_APPEARANCE, mode.name)
        editor.apply()
    }

    /**
     * Validates and caches the server-normalized v2 palette, then activates it for this tenant.
     * Invalid or incomplete colours are rejected instead of silently rendering a partial theme.
     */
    fun applyInstitutionTheme(institutionCode: String, normalizedThemeJson: String) {
        val code = institutionCode.trim().uppercase()
        require(INSTITUTION_CODE.matches(code)) { "Institution code is invalid." }
        validateThemeTokens(normalizedThemeJson)
        preferences.edit()
            .putString(KEY_TENANT_THEME_PREFIX + code, normalizedThemeJson)
            .putString(KEY_ACTIVE_INSTITUTION, code)
            .apply()
        activeInstitutionCode = code
        themeTokensJson = normalizedThemeJson
    }

    /**
     * Activates a cached palette only after the caller has independently resolved this tenant.
     * A missing palette explicitly clears the previous tenant's colours.
     */
    fun activateCachedInstitutionTheme(institutionCode: String) {
        val code = institutionCode.trim().uppercase()
        require(INSTITUTION_CODE.matches(code)) { "Institution code is invalid." }
        val cached = preferences.getString(KEY_TENANT_THEME_PREFIX + code, null)
        if (cached != null) {
            try {
                validateThemeTokens(cached)
            } catch (_: IllegalArgumentException) {
                preferences.edit().remove(KEY_TENANT_THEME_PREFIX + code).apply()
            }
        }
        val validCached = preferences.getString(KEY_TENANT_THEME_PREFIX + code, null)
        preferences.edit().putString(KEY_ACTIVE_INSTITUTION, code).apply()
        activeInstitutionCode = code
        themeTokensJson = validCached
    }

    /** Clear the active tenant scope on logout or when the account is not institution-bound. */
    fun clearInstitutionTheme() {
        preferences.edit().remove(KEY_ACTIVE_INSTITUTION).apply()
        activeInstitutionCode = null
        themeTokensJson = null
    }

    /** Remove a revoked/stale tenant palette from the local device cache. */
    fun forgetInstitutionTheme(institutionCode: String) {
        val code = institutionCode.trim().uppercase()
        if (!INSTITUTION_CODE.matches(code)) return
        preferences.edit().remove(KEY_TENANT_THEME_PREFIX + code).apply()
        if (activeInstitutionCode == code) clearInstitutionTheme()
    }

    private fun readAppearanceMode(): QeAppearanceMode {
        val saved = preferences.getString(KEY_USER_APPEARANCE, null)
        return runCatching { saved?.let(QeAppearanceMode::valueOf) }.getOrNull() ?: QeAppearanceMode.INSTITUTION
    }

    private fun validateThemeTokens(raw: String) {
        val root = try { JSONObject(raw) } catch (_: JSONException) {
            throw IllegalArgumentException("Institution theme is not valid JSON.")
        }
        require(root.optInt("schemaVersion", -1) == 2) { "Institution theme schema version 2 is required." }
        val requiredRoles = listOf(
            "primary", "onPrimary", "primaryText", "primaryContainer", "onPrimaryContainer",
            "accent", "onAccent", "accentText", "background", "onBackground", "surface", "onSurface",
            "surfaceElevated", "onSurfaceElevated", "text", "textMuted", "border", "borderSubtle", "focus",
            "success", "onSuccess", "successContainer", "onSuccessContainer",
            "warning", "onWarning", "warningContainer", "onWarningContainer",
            "error", "onError", "errorContainer", "onErrorContainer",
            "selectedBackground", "selectedText", "navBackground", "navText",
            "navActiveBackground", "navActiveText", "disabledBackground", "disabledText"
        )
        for (mode in listOf("light", "dark")) {
            val palette = root.optJSONObject(mode)
                ?: throw IllegalArgumentException("Institution theme is missing the $mode palette.")
            for (role in requiredRoles) {
                val color = palette.optString(role, "")
                require(HEX_COLOUR.matches(color)) {
                    "Institution theme has a missing or invalid $mode/$role colour."
                }
            }
        }
        val appearance = root.optString("appearance", "system")
        require(appearance in setOf("system", "light", "dark")) {
            "Institution theme appearance must be system, light, or dark."
        }
    }

    companion object {
        private const val PREFERENCES_NAME = "qe_preferences"
        private const val KEY_USER_APPEARANCE = "qe_appearance_mode_v2"
        private const val KEY_ACTIVE_INSTITUTION = "qe_active_theme_institution_code_v2"
        private const val KEY_TENANT_THEME_PREFIX = "qe_tenant_theme_v2."
        private val INSTITUTION_CODE = Regex("^[A-Z0-9][A-Z0-9_-]{2,31}$")
        private val HEX_COLOUR = Regex("^#[0-9A-Fa-f]{6}$")
    }
}
