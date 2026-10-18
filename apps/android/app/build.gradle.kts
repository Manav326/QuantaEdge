import org.gradle.api.tasks.Copy
import java.security.MessageDigest

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
val configuredInstitutionDisplayName = if (configuredInstitutionCode.isBlank()) "QuantaEdge" else suppliedInstitutionName
require(configuredInstitutionCode.isBlank() || configuredInstitutionDisplayName.length in 2..180) {
    "A tenant-branded Android package requires QUANTAEDGE_INSTITUTION_NAME (2–180 characters)."
}
require(configuredInstitutionDisplayName.none { it.code < 32 || it.code == 127 }) {
    "Institution display name contains control characters."
}
val configuredInstitutionIconPath = providers.gradleProperty("quantaedgeInstitutionIconPath")
    .orElse(providers.environmentVariable("QUANTAEDGE_INSTITUTION_ICON_PATH"))
    .orNull?.trim()?.takeIf { it.isNotBlank() }
// Every generated segment must begin with a letter. Institution codes may start
// with digits or contain only punctuation after normalization, so always prefix the slug.
val tenantPackageSlug = "school" + configuredInstitutionCode.lowercase()
    .filter { it in 'a'..'z' || it in '0'..'9' }
    .take(20).ifBlank { "tenant" }
val tenantPackageHash = if (configuredInstitutionCode.isBlank()) "" else
    MessageDigest.getInstance("SHA-256")
        .digest(configuredInstitutionCode.toByteArray(Charsets.UTF_8))
        .take(4).joinToString("") { byte ->
            (byte.toInt() and 0xff).toString(16).padStart(2, '0')
        }
val configuredInstitutionApplicationId = providers.gradleProperty("quantaedgeInstitutionApplicationId")
    .orElse(providers.environmentVariable("QUANTAEDGE_INSTITUTION_APPLICATION_ID"))
    .orNull?.trim()?.takeIf { it.isNotBlank() }
    ?: if (configuredInstitutionCode.isBlank()) "com.quantaedge.android"
       else "com.quantaedge.android.tenant." + tenantPackageSlug + tenantPackageHash
val institutionAutoVerify = configuredInstitutionCode.isNotBlank().toString()
val institutionLinkPath = if (configuredInstitutionCode.isBlank()) {
    "/__no_tenant_app_link_for_platform_build__/"
} else {
    "/school/" + configuredInstitutionCode + "/"
}
val institutionLinkScheme = if (configuredInstitutionCode.isBlank()) {
    "qe-institution"
} else {
    "qe-institution-" + tenantPackageSlug + tenantPackageHash
}
require(configuredInstitutionApplicationId.matches(Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+$"))) {
    "QUANTAEDGE_INSTITUTION_APPLICATION_ID must be a valid, unique Android package ID."
}
require(configuredInstitutionCode.isBlank() || configuredInstitutionApplicationId != "com.quantaedge.android") {
    "A separately listed institution app needs its own application ID."
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
        buildConfigField("boolean", "IS_TENANT_BRANDED", configuredInstitutionCode.isNotBlank().toString())
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
        if (configuredInstitutionCode.isNotBlank()) {
            require(!configuredInstitutionIconPath.isNullOrBlank()) {
                "A tenant-branded Android package requires QUANTAEDGE_INSTITUTION_ICON_PATH so the installed launcher icon is school-branded."
            }
        }
    }
    doLast {
        // Convert both PNG and JPEG uploads to a real PNG resource. Never just rename JPEG bytes.
        val sourceIcon = tenantLauncherIcon ?: brandIcon.asFile
        val decoded = requireNotNull(javax.imageio.ImageIO.read(sourceIcon)) {
            "The launcher icon could not be decoded: " + sourceIcon.absolutePath
        }
        val destination = generatedBrandingRes.get().dir("drawable-nodpi").file("qe_launcher_icon.png").asFile
        destination.parentFile.mkdirs()
        check(javax.imageio.ImageIO.write(decoded, "png", destination)) {
            "The launcher image could not be encoded as PNG."
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
