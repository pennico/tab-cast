// Tab Cast probe (0.2 "Cast this tab", step 1): a small separate debug app that measures on the Tab S2 what the
// mirroring PoC can build on (PixelCopy, C colour conversion, MediaCodec ByteBuffer encode, VirtualDisplay paths,
// MediaProjection + playback audio capture). Separate package, so it installs next to Tab Cast 0.1.x and changes
// nothing there. See probe/README.md.
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "dk.pennico.tabcast.probe"
    compileSdk = 35
    ndkVersion = "27.2.12479018"

    defaultConfig {
        applicationId = "dk.pennico.tabcast.probe"
        minSdk = 29 // playback audio capture; the probe only runs on the Tab S2 (Android 14)
        targetSdk = 34
        versionCode = 2
        versionName = "0.2.0-probe2"
        ndk { abiFilters += listOf("armeabi-v7a") } // the tablet is 32-bit ARM only
    }

    externalNativeBuild { ndkBuild { path = file("src/main/jni/Android.mk") } }

    val sharedDebugKey = System.getenv("TABCAST_DEBUG_KEYSTORE")?.takeIf { it.isNotBlank() }?.let { file(it) } // see app/
    signingConfigs { getByName("debug") { sharedDebugKey?.takeIf { it.exists() }?.let { storeFile = it } } }
    buildTypes { debug { signingConfig = signingConfigs.getByName("debug") } }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    testOptions { unitTests.isReturnDefaultValues = true }
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    testImplementation("junit:junit:4.13.2")
}
