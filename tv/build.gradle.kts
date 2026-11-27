// Tab Cast TV: the browser on the Chromecast HD (Android TV), opened and controlled from the tablet over :link.
// Same catching code as the tablet (:shared); plays the caught video in Media3's player full screen.
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "dk.pennico.tabcast.tv"
    compileSdk = 35

    defaultConfig {
        applicationId = "dk.pennico.tabcast.tv"
        minSdk = 26
        targetSdk = 34
        versionCode = 15
        versionName = "0.6.0-dev2"
    }

    val sharedDebugKey = System.getenv("TABCAST_DEBUG_KEYSTORE")?.takeIf { it.isNotBlank() }?.let { file(it) } // see app/
    // Release key: never in git. tools/publish-fdroid.sh decodes it from $TABCAST_KEYDIR into a temp folder and passes
    // it in through the environment; without it the release build is unsigned.
    val releaseStore = System.getenv("TABCAST_RELEASE_STORE")?.takeIf { it.isNotBlank() }
    signingConfigs {
        getByName("debug") { sharedDebugKey?.takeIf { it.exists() }?.let { storeFile = it } }
        if (releaseStore != null) create("release") {
            storeFile = file(releaseStore)
            storeType = "PKCS12"
            storePassword = System.getenv("TABCAST_RELEASE_STORE_PASSWORD")
            keyAlias = System.getenv("TABCAST_RELEASE_KEY_ALIAS") ?: "tab-cast"
            keyPassword = System.getenv("TABCAST_RELEASE_STORE_PASSWORD")
        }
    }
    buildTypes {
        debug { signingConfig = signingConfigs.getByName("debug") }
        release {
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
    testOptions {
        unitTests.isReturnDefaultValues = true
        unitTests.isIncludeAndroidResources = true // Robolectric smoke test
    }
}

dependencies {
    implementation(project(":shared"))
    implementation(project(":link"))
    val media3 = "1.9.4"
    implementation("androidx.media3:media3-exoplayer:$media3")
    implementation("androidx.media3:media3-exoplayer-hls:$media3")
    implementation("androidx.media3:media3-exoplayer-dash:$media3")
    implementation("androidx.media3:media3-ui:$media3")

    testImplementation("junit:junit:4.13.2")
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
