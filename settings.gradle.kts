pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }

    // Plugin versions live here, not in the module build files. A module then
    // applies a plugin by id with no version, which is what lets the Kotlin JVM
    // plugin (used by :shared / :server) and the Kotlin Android plugin (used by
    // :client-android) coexist on one classpath.
    plugins {
        id("org.jetbrains.kotlin.jvm") version "1.9.24"
        id("org.jetbrains.kotlin.android") version "1.9.24"
        id("com.android.application") version "8.5.2"
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "lanfps"

// The pure-JVM modules always build.
include(":shared")
include(":server")

// The Android client is only configured when an Android SDK is available
// (either ANDROID_SDK_ROOT / ANDROID_HOME is exported, or local.properties exists).
// This keeps `:shared:build` and `:server:build` working on machines without the
// Android SDK, while still allowing a full APK build when the SDK is present.
val androidSdkAvailable =
    System.getenv("ANDROID_SDK_ROOT") != null ||
    System.getenv("ANDROID_HOME") != null ||
    file("local.properties").exists()

if (androidSdkAvailable) {
    include(":client-android")
    println("[settings] Android SDK detected -> including :client-android")
} else {
    println("[settings] No Android SDK detected -> skipping :client-android (JVM modules only)")
}
