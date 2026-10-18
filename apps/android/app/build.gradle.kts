import groovy.json.JsonSlurper
import org.gradle.api.tasks.Copy
import java.io.File
import java.security.MessageDigest

data class TenantLauncherAliasEntry(
    val slot: Int,
    val institutionCode: String,
    val displayName: String,
    val iconFile: File
)

fun xmlEscapeForAndroidString(value: String): String = value
    .replace("&", "&amp;")
    .replace("<", "&lt;")
    .replace(">", "&gt;")
    .replace("\"", "&quot;")
    .replace("'", "&apos;")

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val configuredApiBaseUrl = providers.gradleProperty("quantaedgeApiBaseUrl")
    .orElse(providers.environmentVariable("QUANTAEDGE_API_BASE_URL"))
    .orElse("http://192.168.31.47:8080/")
    .get()
    .let { if (it.endsWith("/")) it else "$it/" }

// Google Identity Services / Credential Manager returns a real Google ID token.
// Use the OAuth Web application client ID here as the server audience.
val configuredGoogleWebClientId = providers.gradleProperty("googleWebClientId")
    .orElse(providers.environmentVariable("GOOGLE_WEB_CLIENT_ID"))
    .orElse("")
    .get()
    .trim()

val generatedBrandingRes = layout.buildDirectory.dir("generated/quantaedge-brand/res")

val configuredInstitutionCode = providers.gradleProperty("quantaedgeInstitutionCode")
    .orElse(providers.environmentVariable("QUANTAEDGE_INSTITUTION_CODE"))
    .orElse("").get().trim().uppercase()
val suppliedInstitutionName = providers.gradleProperty("quantaedgeInstitutionName")
    .orElse(providers.environmentVariable("QUANTAEDGE_INSTITUTION_NAME"))
    .orElse("").get().trim()
val configuredInstitutionIconPath = providers.gradleProperty("quantaedgeInstitutionIconPath")
    .orElse(providers.environmentVariable("QUANTAEDGE_INSTITUTION_ICON_PATH"))
    .orNull?.trim()?.takeIf { it.isNotBlank() }

// Default distribution is one shared Play package. A different package is an explicit
// alternate distribution mode, never an automatic side effect of setting a tenant code.
val configuredInstitutionApplicationIdOverride = providers.gradleProperty("quantaedgeInstitutionApplicationId")
    .orElse(providers.environmentVariable("QUANTAEDGE_INSTITUTION_APPLICATION_ID"))
    .orNull?.trim()?.takeIf { it.isNotBlank() }
val isDedicatedInstitutionPackage = configuredInstitutionApplicationIdOverride != null &&
    configuredInstitutionApplicationIdOverride != "com.quantaedge.android"
val configuredInstitutionApplicationId = configuredInstitutionApplicationIdOverride ?: "com.quantaedge.android"
val configuredInstitutionDisplayName = if (isDedicatedInstitutionPackage) suppliedInstitutionName else "QuantaEdge"

require(configuredInstitutionCode.isBlank() || isDedicatedInstitutionPackage) {
    "The shared QuantaEdge package must resolve the school from its trusted /school/{institutionCode}/ link. Set QUANTAEDGE_INSTITUTION_APPLICATION_ID only for the separate dedicated-package distribution."
}
require(!isDedicatedInstitutionPackage || configuredInstitutionCode.isNotBlank()) {
    "A dedicated institution package requires QUANTAEDGE_INSTITUTION_CODE."
}
require(!isDedicatedInstitutionPackage || configuredInstitutionDisplayName.length in 2..180) {
    "A dedicated institution package requires QUANTAEDGE_INSTITUTION_NAME (2–180 characters)."
}
require(configuredInstitutionDisplayName.none { it.code < 32 || it.code == 127 }) {
    "Institution display name contains control characters."
}
require(!isDedicatedInstitutionPackage || !configuredInstitutionIconPath.isNullOrBlank()) {
    "A dedicated institution package requires QUANTAEDGE_INSTITUTION_ICON_PATH. The shared Play-listing package instead uses its packaged launcher aliases."
}
require(isDedicatedInstitutionPackage || configuredInstitutionIconPath == null) {
    "QUANTAEDGE_INSTITUTION_ICON_PATH belongs to the optional dedicated-package mode. Shared package launcher aliases use QUANTAEDGE_LAUNCHER_ALIAS_MANIFEST."
}

val configuredLauncherAliasManifestPath = providers.gradleProperty("quantaedgeLauncherAliasManifest")
    .orElse(providers.environmentVariable("QUANTAEDGE_LAUNCHER_ALIAS_MANIFEST"))
    .orNull?.trim()?.takeIf { it.isNotBlank() }
val configuredLauncherAliases: List<TenantLauncherAliasEntry> =
    if (configuredLauncherAliasManifestPath == null) {
        emptyList()
    } else {
        require(!isDedicatedInstitutionPackage) {
            "QUANTAEDGE_LAUNCHER_ALIAS_MANIFEST is only supported by the shared application-ID distribution."
        }
        val aliasManifest = file(configuredLauncherAliasManifestPath)
        require(aliasManifest.isFile) {
            "QUANTAEDGE_LAUNCHER_ALIAS_MANIFEST does not point to a readable JSON file: " + aliasManifest.absolutePath
        }
        val parsed = JsonSlurper().parse(aliasManifest) as? List<*>
            ?: error("The launcher alias manifest must be a JSON array of tenant entries.")
        require(parsed.size <= 50) { "At most 50 reviewed tenant launcher aliases are supported per shared-app release." }
        parsed.mapIndexed { index, raw ->
            val row = raw as? Map<*, *> ?: error("Launcher alias entry ${index + 1} must be an object.")
            val code = (row["institutionCode"] ?: row["code"])?.toString()?.trim()?.uppercase().orEmpty()
            val displayName = (row["displayName"] ?: row["name"])?.toString()?.trim().orEmpty()
            val iconPath = (row["iconPath"] ?: row["icon_path"])?.toString()?.trim().orEmpty()
            val slot = (row["slot"] as? Number)?.toInt() ?: index + 1
            require(code.matches(Regex("^[A-Z0-9][A-Z0-9_-]{2,31}$"))) {
                "Launcher alias entry ${index + 1} has an invalid institutionCode."
            }
            require(displayName.length in 2..180 && displayName.none { it.code < 32 || it.code == 127 }) {
                "Launcher alias entry ${index + 1} must have a valid 2–180 character displayName."
            }
            require(slot in 1..50) { "Launcher alias entry ${index + 1} slot must be between 1 and 50." }
            require(iconPath.isNotBlank()) { "Launcher alias entry ${index + 1} must specify iconPath." }
            TenantLauncherAliasEntry(slot, code, displayName, file(iconPath))
        }
    }
require(configuredLauncherAliases.map { it.institutionCode }.distinct().size == configuredLauncherAliases.size) {
    "Launcher alias manifest contains duplicate institution codes."
}
require(configuredLauncherAliases.map { it.slot }.distinct().size == configuredLauncherAliases.size) {
    "Launcher alias manifest contains duplicate slots."
}
val configuredLauncherSlotMap = configuredLauncherAliases
    .sortedBy { it.slot }
    .joinToString(";") { it.institutionCode + "=" + it.slot.toString().padStart(2, '0') }

// Five-hex-digit suffixes are valid alphanumeric ID material. These values are used
// only by explicit dedicated packages; the normal shared app resolves branding at runtime.
val tenantPackageSlug = "school" + configuredInstitutionCode.lowercase()
    .filter { it in 'a'..'z' || it in '0'..'9' }
    .take(20).ifBlank { "tenant" }
val tenantPackageHash = if (configuredInstitutionCode.isBlank()) "" else
    MessageDigest.getInstance("SHA-256")
        .digest(configuredInstitutionCode.toByteArray(Charsets.UTF_8))
        .take(4).joinToString("") { byte ->
            (byte.toInt() and 0xff).toString(16).padStart(2, '0')
        }
val institutionAutoVerify = "true"
val institutionLinkPath = if (isDedicatedInstitutionPackage) {
    "/school/" + configuredInstitutionCode + "/"
} else {
    "/school/"
}
val institutionLinkScheme = if (isDedicatedInstitutionPackage) {
    "qe-institution-" + tenantPackageSlug + tenantPackageHash
} else {
    "qe-institution"
}
require(configuredInstitutionApplicationId.matches(Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+$"))) {
    "QUANTAEDGE_INSTITUTION_APPLICATION_ID must be a valid Android package ID."
}
require(configuredInstitutionApplicationId == "com.quantaedge.android" || isDedicatedInstitutionPackage) {
    "A non-default Android application ID is only supported by the explicit dedicated-package distribution."
}
require(configuredLauncherAliases.isEmpty() || !isDedicatedInstitutionPackage) {
    "Use either the shared package's launcher alias manifest or the separate dedicated-package mode, not both."
}

fun buildConfigString(value: String): String = "\"" + value
    .replace("\\", "\\\\").replace("\"", "\\\"")
    .replace("\n", "\\n").replace("\r", "\\r") + "\""
val configuredVersionCode = providers.gradleProperty("quantaedgeVersionCode")
    .orElse(providers.environmentVariable("QUANTAEDGE_VERSION_CODE"))
    .orElse("1").get().toInt().also { require(it > 0) { "versionCode must be a positive integer." } }
val configuredVersionName = providers.gradleProperty("quantaedgeVersionName")
    .orElse(providers.environmentVariable("QUANTAEDGE_VERSION_NAME"))
    .orElse("1.0.0").get().also { require(it.isNotBlank()) { "versionName must not be blank." } }

val releaseKeystorePath = providers.environmentVariable("ANDROID_KEYSTORE_PATH").orNull
val releaseStorePassword = providers.environmentVariable("ANDROID_KEYSTORE_PASSWORD").orNull
val releaseKeyAlias = providers.environmentVariable("ANDROID_KEY_ALIAS").orNull
val releaseKeyPassword = providers.environmentVariable("ANDROID_KEY_PASSWORD").orNull
val releaseSigningConfigured = listOf(
    releaseKeystorePath, releaseStorePassword, releaseKeyAlias, releaseKeyPassword
).all { !it.isNullOrBlank() }

android {
    sourceSets.getByName("main").res.srcDir(generatedBrandingRes)
    namespace = "com.quantaedge.android"
    compileSdk = 36
    signingConfigs {
        if (releaseSigningConfigured) {
            create("release") {
                storeFile = file(requireNotNull(releaseKeystorePath))
                storePassword = requireNotNull(releaseStorePassword)
                keyAlias = requireNotNull(releaseKeyAlias)
                keyPassword = requireNotNull(releaseKeyPassword)
            }
        }
    }
    defaultConfig {
        applicationId = configuredInstitutionApplicationId
        manifestPlaceholders["applicationLabel"] = configuredInstitutionDisplayName
        manifestPlaceholders["applicationIcon"] = "@drawable/qe_launcher_icon"
        manifestPlaceholders["institutionLinkPath"] = institutionLinkPath
        manifestPlaceholders["institutionLinkScheme"] = institutionLinkScheme
        manifestPlaceholders["institutionAutoVerify"] = institutionAutoVerify
        minSdk = 26
        targetSdk = 36
        versionCode = configuredVersionCode
        versionName = configuredVersionName
        buildConfigField("String", "API_BASE_URL", "\"$configuredApiBaseUrl\"")
        buildConfigField("String", "GOOGLE_WEB_CLIENT_ID", "\"$configuredGoogleWebClientId\"")
        buildConfigField("String", "INSTITUTION_CODE", buildConfigString(configuredInstitutionCode))
        buildConfigField("String", "INSTITUTION_DISPLAY_NAME", buildConfigString(configuredInstitutionDisplayName))
        buildConfigField("String", "INSTITUTION_LAUNCHER_SLOTS", buildConfigString(configuredLauncherSlotMap))
        buildConfigField("boolean", "IS_TENANT_BRANDED", isDedicatedInstitutionPackage.toString())
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildTypes {
        debug { manifestPlaceholders["allowCleartextTraffic"] = "true" }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            if (releaseSigningConfigured) signingConfig = signingConfigs.getByName("release")
            manifestPlaceholders["allowCleartextTraffic"] = "false"
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    buildFeatures { compose = true; buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    packaging { resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" } }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.02.00"))
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.credentials:credentials:1.6.0")
    implementation("androidx.credentials:credentials-play-services-auth:1.6.0")
    implementation("com.google.android.libraries.identity.googleid:googleid:1.1.1")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.camera:camera-camera2:1.4.2")
    implementation("androidx.camera:camera-lifecycle:1.4.2")
    implementation("androidx.camera:camera-view:1.4.2")
    implementation("com.google.mlkit:barcode-scanning:17.3.0")
    implementation("com.razorpay:checkout:1.6.41")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("androidx.work:work-runtime-ktx:2.10.0")
    implementation("androidx.media3:media3-exoplayer:1.11.1")
    implementation("androidx.media3:media3-ui:1.11.1")
    implementation("androidx.media3:media3-datasource-okhttp:1.11.1")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    debugImplementation("androidx.compose.ui:ui-tooling")
}

val brandIcon = rootProject.layout.projectDirectory.file("../web/public/branding/quantaedge-icon.png")
val tenantLauncherIcon = configuredInstitutionIconPath?.let { file(it) }
tasks.register<Copy>("syncQuantaEdgeBranding") {
    from(brandIcon) { rename { "quantaedge_icon.png" } }
    into(generatedBrandingRes.map { it.dir("drawable-nodpi") })
    doFirst {
        check(brandIcon.asFile.isFile) {
            "QuantaEdge canonical Android branding icon is missing at " + brandIcon.asFile.absolutePath
        }
        check(javax.imageio.ImageIO.read(brandIcon.asFile) != null) {
            "QuantaEdge canonical branding icon is not a decodable image: " + brandIcon.asFile.absolutePath
        }
        val iconFile = tenantLauncherIcon ?: brandIcon.asFile
        require(iconFile.isFile && iconFile.length() in 1..2_097_152L) {
            "The launcher icon must be a readable PNG/JPEG image no larger than 2 MiB."
        }
        val image = javax.imageio.ImageIO.read(iconFile)
        require(image != null && image.width in 1..4096 && image.height in 1..4096 &&
            image.width.toLong() * image.height.toLong() <= 16_000_000L) {
            "The launcher icon must be a valid PNG/JPEG no larger than 4096 by 4096 pixels."
        }
        if (isDedicatedInstitutionPackage) {
            require(!configuredInstitutionIconPath.isNullOrBlank()) {
                "A dedicated institution package requires QUANTAEDGE_INSTITUTION_ICON_PATH."
            }
        }
        configuredLauncherAliases.forEach { entry ->
            require(entry.iconFile.isFile && entry.iconFile.length() in 1..2_097_152L) {
                "Launcher alias icon for " + entry.institutionCode + " must be a readable image no larger than 2 MiB."
            }
            val image = javax.imageio.ImageIO.read(entry.iconFile)
            require(image != null && image.width in 1..4096 && image.height in 1..4096 &&
                image.width.toLong() * image.height.toLong() <= 16_000_000L) {
                "Launcher alias icon for " + entry.institutionCode + " is corrupt or exceeds the dimension limit."
            }
        }
    }
    doLast {
        // Convert both PNG and JPEG uploads to real PNG resources. Never just rename JPEG bytes.
        val fallbackIcon = tenantLauncherIcon ?: brandIcon.asFile
        val decoded = requireNotNull(javax.imageio.ImageIO.read(fallbackIcon)) {
            "The launcher icon could not be decoded: " + fallbackIcon.absolutePath
        }
        val drawableDir = generatedBrandingRes.get().dir("drawable-nodpi").asFile
        drawableDir.mkdirs()
        val destination = File(drawableDir, "qe_launcher_icon.png")
        check(javax.imageio.ImageIO.write(decoded, "png", destination)) {
            "The launcher image could not be encoded as PNG."
        }

        val aliasBySlot = configuredLauncherAliases.associateBy { it.slot }
        val valuesDir = generatedBrandingRes.get().dir("values").asFile
        valuesDir.mkdirs()
        val stringsFile = File(valuesDir, "tenant_launcher_aliases.xml")
        stringsFile.writeText(buildString {
            appendLine("<resources>")
            for (slot in 1..50) {
                val entry = aliasBySlot[slot]
                val label = entry?.displayName ?: "School learning space"
                appendLine("    <string name=\"tenant_launcher_alias_${slot.toString().padStart(2, '0')}_label\">${xmlEscapeForAndroidString(label)}</string>")
            }
            appendLine("</resources>")
        }, Charsets.UTF_8)

        for (slot in 1..50) {
            val entry = aliasBySlot[slot]
            val source = entry?.iconFile ?: fallbackIcon
            val aliasImage = requireNotNull(javax.imageio.ImageIO.read(source)) {
                "Launcher alias icon source could not be decoded for slot " + slot
            }
            val aliasDestination = File(drawableDir, "tenant_launcher_alias_${slot.toString().padStart(2, '0')}.png")
            check(javax.imageio.ImageIO.write(aliasImage, "png", aliasDestination)) {
                "Launcher alias image could not be encoded for slot " + slot
            }
        }
    }
}
tasks.named("preBuild").configure { dependsOn("syncQuantaEdgeBranding") }

tasks.register("verifyReleaseApiConfiguration") {
    group = "verification"
    description = "Prevents release builds from using the emulator or insecure HTTP API URL."
    doLast {
        check(configuredApiBaseUrl.startsWith("https://")) {
            "Release builds require -PquantaedgeApiBaseUrl=https://your-real-api-host/."
        }
        check(!configuredApiBaseUrl.contains("10.0.2.2") && !configuredApiBaseUrl.contains("example.invalid")) {
            "Configure the real QuantaEdge API URL before building a release."
        }
    }
}
tasks.register("verifyPlayStoreSigning") {
    group = "verification"
    description = "Requires the private upload key before producing a Play Store release."
    doLast {
        check(releaseSigningConfigured) {
            "Release signing is not configured. Set ANDROID_KEYSTORE_PATH, ANDROID_KEYSTORE_PASSWORD, ANDROID_KEY_ALIAS and ANDROID_KEY_PASSWORD."
        }
        check(file(requireNotNull(releaseKeystorePath)).isFile) {
            "ANDROID_KEYSTORE_PATH does not point to an existing keystore file."
        }
    }
}
tasks.configureEach {
    if (name == "assembleRelease" || name == "bundleRelease") {
        dependsOn("verifyReleaseApiConfiguration", "verifyPlayStoreSigning")
    }
}
