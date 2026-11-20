pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // Only FUTO's FCast sender SDK comes from JitPack (it is published nowhere else).
        maven("https://jitpack.io") {
            content { includeGroup("org.futo.gitlab.videostreaming.fcast-sdk-jitpack") }
        }
    }
}
rootProject.name = "tab-cast"
include(":app")
include(":shared") // catching code + assets used by the tablet app and Tab Cast TV
include(":probe") // 0.2 mirroring probe (separate debug app, see probe/README.md)
include(":link") // 0.2 mirroring: wire format + sender/receiver connection (pure JVM)
include(":tv") // 0.2 mirroring: Tab Cast Receiver for the Android TV
