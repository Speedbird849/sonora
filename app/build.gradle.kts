import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

/**
 * The release signing key, deliberately not in the repository.
 *
 * keystore.properties holds the keystore's path and passwords and is gitignored, so a checkout
 * without it can still build and test a debug APK. Losing it means losing the ability to update an
 * installed release, so it belongs in a password manager as much as it belongs on disk.
 */
val keystoreProperties = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

android {
    namespace = "dev.sonora"
    compileSdk = 37

    defaultConfig {
        applicationId = "dev.sonora"
        minSdk = 26
        // Provisional. See docs/toolchain.md - this value drives the Android 15
        // foreground-service time cap described in the PRD (D3).
        targetSdk = 35
        versionCode = 4
        versionName = "1.1.0"

        // Needed by the device tests that check YouTube will actually serve audio,
        // which cannot be answered from a JVM fixture.
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // InnerTubeX is published only through JitPack, which rebuilds every tagged
    // version with whatever Kotlin is current — so each release carries 2.4
    // metadata while this module compiles with the 2.2.10 that AGP 9.4.1 ships.
    // The check is skipped rather than the toolchain moved: AGP pins that Kotlin
    // version and the Compose compiler plugin has to agree with it, so raising
    // Kotlin means moving AGP too, which is a far larger change than reading a
    // library compiled one metadata version ahead.
    //
    // Safe in practice because 2.4 metadata carries no construct the 2.2
    // compiler cannot read; the failure mode it guards against is a library
    // using language features this compiler does not have, and none of the ones
    // InnerTubeX uses are new.
    kotlin {
        compilerOptions {
            freeCompilerArgs.add("-Xskip-metadata-version-check")
        }
    }

    buildFeatures {
        compose = true
    }

    signingConfigs {
        // Only when the key is present, so that a checkout without one still builds.
        if (keystoreProperties.isNotEmpty()) {
            create("release") {
                storeFile = rootProject.file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            // Null when there is no keystore: the build runs and produces an unsigned APK, which
            // is a clear enough outcome for a checkout that has no key to sign with.
            signingConfig = signingConfigs.findByName("release")

            // Shrinking is the point of a release build: a debug APK carries every unused class
            // and every debug assertion.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.icons.core)
    implementation(libs.androidx.compose.icons.extended)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.session)
    implementation(libs.kotlinx.serialization.json)

    // ---- YouTube Music ----
    // Search needs none of this; streaming does. InnerTubeX answers which client
    // identity to ask with and unlocks the ciphered formats, and the PO token it
    // needs for that comes from a WebView running BotGuard under Rhino.
    implementation(libs.innerTubeX)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.json)
    implementation(libs.rhino)
    implementation(libs.rhino.engine)

    // Shared HTTP client. The Soulseek protocol speaks its own framing over raw
    // sockets and does not use this; everything that fetches over HTTP does, so
    // connections are pooled in one place rather than per call site.
    implementation(libs.okhttp)

    // Frosted bars: the tab bar and mini player blur what scrolls under them.
    // Real image loading for cover art, which is fetched from three different
    // services at three different sizes.
    implementation(libs.haze)
    implementation(libs.haze.materials)
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.runner)
}
