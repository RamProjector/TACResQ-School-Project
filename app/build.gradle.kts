plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

val releaseSigningConfigured = listOf(
    System.getenv("ANDROID_SIGNING_STORE_FILE"),
    System.getenv("ANDROID_SIGNING_STORE_PASSWORD"),
    System.getenv("ANDROID_SIGNING_KEY_ALIAS"),
    System.getenv("ANDROID_SIGNING_KEY_PASSWORD"),
).all { !it.isNullOrBlank() } && System.getenv("ANDROID_RELEASE_SIGNING_CONFIGURED") == "true"

android {
    namespace = "com.tacresq.app"
    compileSdk {
        version = release(37) {
            minorApiLevel = 0
        }
    }

    defaultConfig {
        applicationId = "com.tacresq.app"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    signingConfigs {
        if (releaseSigningConfigured) {
            create("release") {
                storeFile = file(System.getenv("ANDROID_SIGNING_STORE_FILE")!!)
                storePassword = System.getenv("ANDROID_SIGNING_STORE_PASSWORD")
                keyAlias = System.getenv("ANDROID_SIGNING_KEY_ALIAS")
                keyPassword = System.getenv("ANDROID_SIGNING_KEY_PASSWORD")
            }
        }
    }

    val useSupabaseInDebug = providers.gradleProperty("useSupabase").orNull == "true"
    val useRenderBridgeInDebug = providers.gradleProperty("useRenderBridge").orNull == "true"
    if (useRenderBridgeInDebug && !useSupabaseInDebug) {
        error("The Render bridge canary requires -PuseSupabase=true; demo mode must never call live services.")
    }

    buildTypes {
        debug {
            // Keep demo and Supabase QA installs side-by-side; demo links must not
            // intercept real Supabase confirmation callbacks.
            applicationIdSuffix = if (useSupabaseInDebug) ".supabase" else ".demo"
            manifestPlaceholders["authRedirectScheme"] =
                if (useSupabaseInDebug) "tacresq" else "tacresq-demo"
            buildConfigField("boolean", "USE_FAKE_API", (!useSupabaseInDebug).toString())
            buildConfigField("boolean", "USE_RENDER_BRIDGE", useRenderBridgeInDebug.toString())
        }
        release {
            manifestPlaceholders["authRedirectScheme"] = "tacresq"
            buildConfigField("boolean", "USE_FAKE_API", "false")
            // Keep production on the existing direct-Supabase route until canary QA is signed off.
            buildConfigField("boolean", "USE_RENDER_BRIDGE", "false")
            isMinifyEnabled = false
            if (releaseSigningConfigured) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.09.00")
    implementation(composeBom)

    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")

    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.11.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.11.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.11.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
    implementation("androidx.datastore:datastore-preferences:1.3.0")

    implementation("com.google.code.gson:gson:2.13.2")
    implementation("com.squareup.retrofit2:retrofit:2.11.0")
    implementation("com.squareup.retrofit2:converter-gson:2.11.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")

    implementation("com.google.android.gms:play-services-location:21.4.0")
    implementation("org.osmdroid:osmdroid-android:6.1.20")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.11.0")

    implementation("androidx.core.locationbutton:locationbutton:1.0.0-alpha01")
    implementation("androidx.core.locationbutton:locationbutton-compose:1.0.0-alpha01")

    testImplementation("junit:junit:4.13.2")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
    androidTestImplementation(composeBom)
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
