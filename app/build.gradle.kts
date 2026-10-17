plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// The backend for live builds. The default is the development machine as seen from the emulator;
// for a phone on USB, `adb reverse tcp:3000 tcp:3000` and build with -PapiBaseUrl=http://127.0.0.1:3000.
val apiBaseUrl: String = providers.gradleProperty("apiBaseUrl").getOrElse("http://10.0.2.2:3000")

// Debuggable live builds prefill sign-in with this driver (registered by ops for a walkthrough).
val demoDriverPhone: String = providers.gradleProperty("demoDriverPhone").getOrElse("+233 20 000 0077")

// ...and simulate scanning this asset tag (the backend keeps tag ids to itself).
val demoTagId: String = providers.gradleProperty("demoTagId").getOrElse("04:A3:7E:91")

android {
    namespace = "com.galaxy.assettrail.driver"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.galaxy.assettrail.driver"
        minSdk = 28
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"

        // Demo build: in-process fake platform (signer service, ledger, anchor) and demo controls.
        buildConfigField("boolean", "DEMO", "true")
        buildConfigField("String", "API_BASE_URL", "\"$apiBaseUrl\"")
        buildConfigField("String", "DEMO_DRIVER_PHONE", "\"$demoDriverPhone\"")
        buildConfigField("String", "DEMO_TAG_ID", "\"$demoTagId\"")
    }

    buildTypes {
        // Debug with the real backend: ./gradlew :app:installLive
        create("live") {
            initWith(getByName("debug"))
            buildConfigField("boolean", "DEMO", "false")
            applicationIdSuffix = ".live"
            matchingFallbacks += listOf("debug")
        }
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "/META-INF/versions/9/OSGI-INF/MANIFEST.MF",
                "/META-INF/DEPENDENCIES",
                "/META-INF/LICENSE*",
                "/META-INF/NOTICE*",
            )
        }
    }
}

dependencies {
    implementation(project(":core"))
    // Demo builds: in-process stand-in for the backend. Live builds: the HTTP client.
    implementation(project(":platform-mock"))
    implementation(project(":platform-http"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.navigation.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.text.google.fonts)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
}
