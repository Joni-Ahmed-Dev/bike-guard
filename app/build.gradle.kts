import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.devtools.ksp")
}

// Push alerts need Firebase; the build works without it (push is simply skipped).
if (file("google-services.json").exists()) apply(plugin = "com.google.gms.google-services")

val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun cfg(key: String): String = localProps.getProperty(key) ?: System.getenv(key) ?: ""

android {
    namespace = "com.bikeguard.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.bikeguard.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 55
        versionName = "1.2.43"
        buildConfigField("String", "SUPABASE_URL", "\"${cfg("SUPABASE_URL")}\"")
        buildConfigField("String", "SHARE_BASE_URL", "\"${cfg("SHARE_BASE_URL")}\"")
        buildConfigField("String", "SUPABASE_ANON_KEY", "\"${cfg("SUPABASE_ANON_KEY")}\"")
        buildConfigField("String", "DIAG_URL", "\"https://bikeguard-diag.aminuljoni180.workers.dev\"")
        buildConfigField("String", "DIAG_TOKEN", "\"${cfg("DIAG_TOKEN")}\"")
    }

    // Optional release signing: set KEYSTORE_PATH, KEYSTORE_PASSWORD, KEY_ALIAS, KEY_PASSWORD (env or local.properties)
    signingConfigs {
        if (cfg("KEYSTORE_PATH").isNotEmpty()) {
            create("release") {
                storeFile = file(cfg("KEYSTORE_PATH"))
                storePassword = cfg("KEYSTORE_PASSWORD")
                keyAlias = cfg("KEY_ALIAS")
                keyPassword = cfg("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            // v1.2.21 (review item #23): R8 on. The rules live in proguard-rules.pro
            // (kotlinx.serialization, Room, MapLibre, OkHttp); CI going green is the
            // check that nothing the app reflects on was stripped. APK was ~27 MB.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")

    implementation(platform("androidx.compose:compose-bom:2024.10.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    implementation("androidx.work:work-runtime-ktx:2.9.1")
    implementation("com.google.android.gms:play-services-location:21.3.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    // v1.2.21: 1.1.0 is the first stable release (Jul 2025). Note it deprecates
    // every API in the artifact (MasterKey, EncryptedSharedPreferences) in favour
    // of platform Keystore APIs — a later item should migrate Prefs off it.
    implementation("androidx.security:security-crypto:1.1.0")

    implementation("org.maplibre.gl:android-sdk:11.5.2")
    implementation("androidx.fragment:fragment:1.8.4")
    implementation("androidx.biometric:biometric:1.1.0")
    implementation(platform("com.google.firebase:firebase-bom:33.5.1"))
    implementation("com.google.firebase:firebase-messaging")

    // Unit tests (v1.2.21, review item #23): AdaptivePolicy + pickActiveDevice.
    // Run with: gradle :app:testDebugUnitTest (CI runs this in the test job).
    testImplementation("junit:junit:4.13.2")
}
