import org.gradle.api.tasks.Copy

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

val generatedBrandingRes = layout.buildDirectory.dir("generated/quantaedge-brand/res")
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
        applicationId = "com.quantaedge.android"
        minSdk = 26
        targetSdk = 36
        versionCode = configuredVersionCode
        versionName = configuredVersionName
        buildConfigField("String", "API_BASE_URL", "\"$configuredApiBaseUrl\"")
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
tasks.register<Copy>("syncQuantaEdgeBranding") {
    from(brandIcon)
    into(generatedBrandingRes.map { it.dir("drawable-nodpi") })
    rename { "quantaedge_icon.png" }
    doFirst {
        check(brandIcon.asFile.isFile) {
            "QuantaEdge canonical Android branding icon is missing at " + brandIcon.asFile.absolutePath
        }
        check(javax.imageio.ImageIO.read(brandIcon.asFile) != null) {
            "QuantaEdge canonical branding icon is not a decodable image: " + brandIcon.asFile.absolutePath
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
