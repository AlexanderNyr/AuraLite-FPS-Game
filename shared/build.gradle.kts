plugins {
    kotlin("jvm")
    `java-library`
}

// Java 11 bytecode: consumable by the JVM 17 server AND by the Android client
// (AGP 8 / D8 handle class-file v55 fine at minSdk 24).
java {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
    }
}

dependencies {
    // JOML is the only third-party runtime dependency in the whole project.
    // Allowed by spec: pure math utility, not a game engine.
    api("org.joml:joml:1.10.5")

    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "failed", "skipped")
        showStandardStreams = true
    }
}
