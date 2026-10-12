import org.gradle.api.tasks.Copy

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val configuredApiBaseUrl = providers.gradleProperty("quantaedgeApiBaseUrl")
    .orElse(providers.environmentVariable("QUANTAEDGE_API_BASE_URL"))
    .orElse("http://10.0.2.2:8080/")
    .get()
    .let { if (it.endsWith("/")) it else "$it/" }

android {
    sourceSets.getByName("main").res.srcDir(generatedBrandingRes)
    namespace = "com.quantaedge.android"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.quantaedge.android"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"
        buildConfigField("String", "API_BASE_URL", "\"$configuredApiBaseUrl\"")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildTypes {
        debug { manifestPlaceholders["allowCleartextTraffic"] = "true" }
        release {
            isMinifyEnabled = false
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
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("androidx.work:work-runtime-ktx:2.10.0")
    testImplementation("junit:junit:4.13.2")
    debugImplementation("androidx.compose.ui:ui-tooling")
}

val brandWordmark = rootProject.layout.projectDirectory.file("../web/public/branding/quantaedge-wordmark.png")
val generatedBrandingRes = layout.buildDirectory.dir("generated/quantaedge-brand/res")
tasks.register<Copy>("syncQuantaEdgeBranding") {
    from(brandWordmark)
    into(generatedBrandingRes.map { it.dir("drawable-nodpi") })
    rename { "quantaedge_wordmark.png" }
    doFirst {
        check(brandWordmark.asFile.exists()) {
            "Approved QuantaEdge wordmark is missing at " + brandWordmark.asFile.absolutePath
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
tasks.configureEach {
    if (name == "assembleRelease") dependsOn("verifyReleaseApiConfiguration")
}
