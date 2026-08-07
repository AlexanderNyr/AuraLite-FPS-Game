// Root build file.
//
// Deliberately has NO `plugins {}` block. Plugin *versions* are declared once in
// settings.gradle.kts (pluginManagement.plugins) and each module applies what it
// needs by id.
//
// Why it matters: if the Kotlin plugin were put on the root buildscript
// classpath, the Kotlin *Android* plugin in :client-android would be loaded by a
// parent classloader that cannot see the Android Gradle Plugin, and applying it
// fails with "Could not generate a decorated class for KotlinAndroidTarget".
// Keeping the root clean lets :client-android load AGP + kotlin-android together
// while :shared / :server load only the Kotlin JVM plugin - which is also what
// makes the Android module genuinely optional on machines without the SDK.

allprojects {
    group = "com.lanfps"
    version = "1.0.0"
}

tasks.register("printModules") {
    group = "help"
    description = "Prints the modules that are part of this build."
    val names = subprojects.map { it.path }
    doLast {
        println("LAN FPS modules: " + names.joinToString(", "))
    }
}
