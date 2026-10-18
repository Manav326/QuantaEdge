package com.quantaedge.android.data

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import com.quantaedge.android.BuildConfig

private val institutionLauncherCodePattern = Regex("^[A-Z0-9][A-Z0-9_-]{2,31}$")

/**
 * Parses the build-time allow-list of approved, pre-packaged launcher icons.
 * Any malformed or ambiguous mapping fails closed to the generic launcher.
 */
internal fun launcherSlotForInstitution(institutionCode: String?, configuredSlots: String): Int? {
    val code = institutionCode?.trim()?.uppercase() ?: return null
    if (!institutionLauncherCodePattern.matches(code) || configuredSlots.isBlank()) return null

    val mappings = configuredSlots.split(';').mapNotNull { raw ->
        val separator = raw.indexOf('=')
        if (separator <= 0 || separator == raw.lastIndex) return@mapNotNull null
        val key = raw.substring(0, separator).trim().uppercase()
        val slot = raw.substring(separator + 1).trim().toIntOrNull()
        if (!institutionLauncherCodePattern.matches(key) || slot == null || slot !in 1..50) return@mapNotNull null
        key to slot
    }
    if (mappings.size != configuredSlots.split(';').size ||
        mappings.map { it.first }.distinct().size != mappings.size ||
        mappings.map { it.second }.distinct().size != mappings.size) return null
    return mappings.firstOrNull { it.first == code }?.second
}

internal object InstitutionLauncherAliasController {
    private const val PLATFORM_ALIAS = ".launcher.Platform"
    private const val SLOT_PREFIX = ".launcher.TenantSlot"

    /**
     * Enable only a locally packaged alias selected by a validated tenant code.
     * Dedicated-package builds already have their reviewed app icon baked in and do
     * not alter launcher components at runtime.
     */
    fun apply(context: Context, institutionCode: String?) {
        if (BuildConfig.IS_TENANT_BRANDED) return

        val slot = launcherSlotForInstitution(institutionCode, BuildConfig.INSTITUTION_LAUNCHER_SLOTS)
        val targetSuffix = slot?.let { SLOT_PREFIX + it.toString().padStart(2, '0') } ?: PLATFORM_ALIAS
        val manager = context.packageManager
        val target = component(context, targetSuffix)

        // Enable the new target before disabling the prior one so Android never ends
        // up with no launcher entry during a school/account switch.
        val targetEnabled = runCatching {
            manager.setComponentEnabledSetting(
                target,
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                PackageManager.DONT_KILL_APP
            )
        }.isSuccess
        val safeTargetSuffix = if (targetEnabled) targetSuffix else PLATFORM_ALIAS
        if (!targetEnabled) {
            runCatching {
                manager.setComponentEnabledSetting(
                    component(context, PLATFORM_ALIAS),
                    PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                    PackageManager.DONT_KILL_APP
                )
            }
        }

        for (index in 1..50) {
            val suffix = SLOT_PREFIX + index.toString().padStart(2, '0')
            if (suffix == safeTargetSuffix) continue
            runCatching {
                manager.setComponentEnabledSetting(
                    component(context, suffix),
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    PackageManager.DONT_KILL_APP
                )
            }
        }
        if (safeTargetSuffix != PLATFORM_ALIAS) {
            runCatching {
                manager.setComponentEnabledSetting(
                    component(context, PLATFORM_ALIAS),
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    PackageManager.DONT_KILL_APP
                )
            }
        }
    }

    private fun component(context: Context, suffix: String): ComponentName =
        ComponentName(context.packageName, context.packageName + suffix)
}
