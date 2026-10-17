package com.quantaedge.android.data

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import org.json.JSONObject
import java.io.IOException

data class InstitutionBrandIdentity(
    val institutionCode: String,
    val displayName: String,
    val logo: Bitmap? = null,
    val appIcon: Bitmap? = null,
    val brandingVersion: Long = 1L,
    val themeTokensJson: String
)

/**
 * Resolves public institution identity from a validated tenant code.
 * Theme/palette is intentionally not handled here; ColorThemeProvider remains the sole theme owner.
 */
object InstitutionBrandingRepository {
    private val codePattern = Regex("^[A-Z0-9][A-Z0-9_-]{2,31}$")
    private val safeAssetUrlPattern = Regex("^/api/v1/institution/public/branding/[A-Z0-9][A-Z0-9_-]{2,31}/assets/(logo|app-icon)\\?v=\\d+$")
    private val publicBrandHeaders = mapOf("X-QE-Workspace" to "INSTITUTION")

    fun normalizeCode(value: String?): String? {
        val normalized = value?.trim()?.uppercase() ?: return null
        return normalized.takeIf { codePattern.matches(it) }
    }

    fun tenantCodeFromIntent(intent: Intent?): String? {
        val data = intent?.data
        val supplied = intent?.getStringExtra("institutionCode")
            ?: intent?.getStringExtra("institution_code")
            ?: intent?.getStringExtra("tenantCode")
            ?: intent?.getStringExtra("tenant")
            ?: data?.getQueryParameter("institution")
            ?: data?.getQueryParameter("institutionCode")
            ?: data?.getQueryParameter("institution_code")
            ?: data?.getQueryParameter("tenant")
        return normalizeCode(supplied ?: codeFromPath(data))
    }

    fun joinCodeFromIntent(intent: Intent?): String? {
        val value = intent?.getStringExtra("joinCode")
            ?: intent?.getStringExtra("join_code")
            ?: intent?.data?.getQueryParameter("joinCode")
            ?: intent?.data?.getQueryParameter("join_code")
            ?: intent?.data?.getQueryParameter("join")
        return value?.trim()?.takeIf { it.length in 12..256 && it.none(Char::isISOControl) }
    }

    private fun codeFromPath(uri: Uri?): String? {
        val parts = uri?.pathSegments ?: return null
        val index = parts.indexOfFirst { it.equals("institution", true) || it.equals("school", true) }
        return if (index >= 0 && index + 1 < parts.size) parts[index + 1] else null
    }

    suspend fun fetch(codeInput: String): InstitutionBrandIdentity {
        val code = normalizeCode(codeInput) ?: throw IOException("Institution code is invalid.")
        val data = ApiClient.getObject(
            "/api/v1/institution/public/branding/$code",
            publicBrandHeaders
        )
        if (normalizeCode(data.optString("institutionCode")) != code) {
            throw IOException("The institution identity did not match the requested school.")
        }
        val displayName = data.optString("displayName").trim()
        if (displayName.length !in 2..180) throw IOException("The institution display name is unavailable.")
        val version = data.optLong("brandingVersion", 1L).coerceAtLeast(1L)
        val logo = loadApprovedImage(data.optString("logoUrl"), code, "logo")
        val appIcon = loadApprovedImage(data.optString("appIconUrl"), code, "app-icon")
        val themeTokens = data.optJSONObject("themeTokens")?.toString()
            ?: throw IOException("The institution colour theme is unavailable.")
        return InstitutionBrandIdentity(code, displayName, logo, appIcon, version, themeTokens)
    }

    suspend fun soleActiveMembershipCode(): String? {
        val body = ApiClient.getObject(
            "/api/v1/institution/memberships/me",
            mapOf("X-QE-Workspace" to "STUDENT")
        )
        val institutions = body.optJSONArray("institutions") ?: return null
        val activeCodes = buildList {
            for (index in 0 until institutions.length()) {
                val item = institutions.optJSONObject(index) ?: continue
                if (item.optString("institution_status") != "ACTIVE" ||
                    item.optString("membership_status") != "ACTIVE") continue
                normalizeCode(item.optString("tenant_code"))?.let { add(it) }
            }
        }.distinct()
        return activeCodes.singleOrNull()
    }

    suspend fun join(code: String): JSONObject {
        val cleaned = code.trim()
        if (cleaned.length !in 12..256 || cleaned.any(Char::isISOControl)) {
            throw IOException("The institution join code is invalid.")
        }
        return ApiClient.postObject(
            "/api/v1/institution/join",
            JSONObject().put("code", cleaned),
            mapOf("X-QE-Workspace" to "STUDENT")
        )
    }

    private suspend fun loadApprovedImage(rawUrl: String?, code: String, type: String): Bitmap? {
        val value = rawUrl?.trim().orEmpty()
        if (value.isEmpty()) return null
        if (!safeAssetUrlPattern.matches(value) ||
            !value.startsWith("/api/v1/institution/public/branding/$code/assets/$type?v=")) {
            throw IOException("The institution image URL is not an approved tenant asset.")
        }
        val bytes = ApiClient.getMediaBytes(value, publicBrandHeaders)
        if (bytes.isEmpty() || bytes.size > 2_097_152) throw IOException("The institution image is invalid.")
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth !in 1..4096 || bounds.outHeight !in 1..4096 ||
            bounds.outWidth.toLong() * bounds.outHeight.toLong() > 16_000_000L) {
            throw IOException("The institution image dimensions are invalid.")
        }
        val options = BitmapFactory.Options().apply {
            inSampleSize = maxOf(1, maxOf(bounds.outWidth / 512, bounds.outHeight / 512))
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
    }
}
