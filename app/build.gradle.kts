plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "dk.pennico.tabcast"
    compileSdk = 35
    ndkVersion = "27.2.12479018" // 0.5.0: native/yuv.c for "Mirror screen" (libtcmirror.so)

    defaultConfig {
        applicationId = "dk.pennico.tabcast"
        minSdk = 26          // FCast sender SDK needs 26; the tablet runs Android 14 (API 34)
        targetSdk = 34
        versionCode = 17
        versionName = "0.6.0-dev2"
        // The tablet is 32-bit ARM only; shipping just this ABI keeps the APK ~4x smaller
        // (yt-dlp's bundled Python alone is ~13 MB per ABI).
        ndk { abiFilters += listOf("armeabi-v7a") }
    }

    externalNativeBuild { ndkBuild { path = file("src/main/jni/Android.mk") } }
    // A shared debug key (Gradle's standard format, store/key password "android") from TABCAST_DEBUG_KEYSTORE, so
    // every build machine keeps the same signature and `adb install -r` updates the app in place. Unset or missing:
    // Gradle's own ~/.android/debug.keystore. Never commit a keystore.
    val sharedDebugKey = System.getenv("TABCAST_DEBUG_KEYSTORE")?.takeIf { it.isNotBlank() }?.let { file(it) }
    // Release key: never in git. tools/publish-fdroid.sh decodes it from $TABCAST_KEYDIR into a temp folder and passes
    // it in through the environment; without it the release build is unsigned.
    val releaseStore = System.getenv("TABCAST_RELEASE_STORE")?.takeIf { it.isNotBlank() }
    signingConfigs {
        getByName("debug") {
            sharedDebugKey?.takeIf { it.exists() }?.let { storeFile = it }
        }
        if (releaseStore != null) create("release") {
            storeFile = file(releaseStore)
            storeType = "PKCS12"
            storePassword = System.getenv("TABCAST_RELEASE_STORE_PASSWORD")
            keyAlias = System.getenv("TABCAST_RELEASE_KEY_ALIAS") ?: "tab-cast"
            keyPassword = System.getenv("TABCAST_RELEASE_STORE_PASSWORD")
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
        release {
            // F-Droid builds (0.3.3). No R8: JNA/uniffi would need keep rules, and size is dominated by native libs.
            isMinifyEnabled = false
            if (releaseStore != null) signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    buildFeatures { buildConfig = true }

    packaging {
        // youtubedl-android loads libpython.zip.so etc. from nativeLibraryDir, so they must be extracted on install.
        jniLibs {
            useLegacyPackaging = true
            // 0.5.0 added the NDK (libtcmirror.so), so AGP now strips native libs: youtubedl-android's *.zip.so are zip
            // files named .so, packaged untouched as before.
            keepDebugSymbols += listOf("**/*.zip.so")
        }
        resources { excludes += listOf("META-INF/*.kotlin_module", "META-INF/AL2.0", "META-INF/LGPL2.1") }
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
        unitTests.isIncludeAndroidResources = true // Robolectric smoke test (ScreensSmokeTest)
    }
}

dependencies {
    implementation(project(":shared"))   // catching code, AppLog, page assets (shared with Tab Cast TV)
    implementation(project(":link"))     // tablet <-> Tab Cast TV connection
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.webkit:webkit:1.12.1")
    implementation("androidx.media:media:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // Casting: FUTO's FCast sender SDK (MIT; Rust core via JNA; FCast + Google Cast Default Media Receiver).
    // Same library Grayjay Android uses. Ships armeabi-v7a.
    implementation("org.futo.gitlab.videostreaming.fcast-sdk-jitpack:sender-sdk-minimal:0.4.1") {
        exclude(group = "net.java.dev.jna")
    }
    implementation("net.java.dev.jna:jna:5.13.0@aar")

    // yt-dlp on Android (Python 3.12 + QuickJS, armeabi-v7a included), GPL-3.0 wrapper around Unlicense yt-dlp.
    implementation("io.github.junkfood02.youtubedl-android:library:0.18.1")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.test:core:1.6.1")
}

// PackagedListsTest reads the APK just built (assets can change on the way into the APK).
tasks.withType<Test>().configureEach {
    if (name == "testDebugUnitTest") {
        dependsOn("assembleDebug")
        systemProperty("tabcast.apk", layout.buildDirectory.file("outputs/apk/debug/${project.name}-debug.apk").get().asFile.path)
    }
}
