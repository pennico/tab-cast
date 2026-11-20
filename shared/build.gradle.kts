// Code both Tab Cast apps use: the tablet (:app) and Tab Cast TV (:tv). Media catching (core/, web/WebCatcher,
// AdBlock, assets/inject.js, blocklist, site rules) and the app log. Pure Kotlin core is unit-tested here.
plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "dk.pennico.tabcast.shared"
    compileSdk = 35
    defaultConfig { minSdk = 26 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    testOptions {
        unitTests.isReturnDefaultValues = true
        unitTests.isIncludeAndroidResources = true // Robolectric (WebCatcherThreadTest)
    }
    lint { lintConfig = file("../app/lint.xml") }
}

dependencies {
    api("androidx.webkit:webkit:1.12.1")
    api("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")   // also AdblockReplayTest
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.test:core:1.6.1")
}
