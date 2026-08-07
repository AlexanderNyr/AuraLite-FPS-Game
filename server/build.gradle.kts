plugins {
    kotlin("jvm")
    application
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":shared"))
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
}

application {
    mainClass.set("com.lanfps.server.MainKt")
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "failed", "skipped")
        showStandardStreams = true
    }
}

/**
 * Fat jar: bundles the Kotlin stdlib, :shared and JOML so the Windows machine
 * only needs a JRE — `java -jar server.jar` just works.
 */
val fatJar = tasks.register<Jar>("fatJar") {
    group = "distribution"
    description = "Builds a self-contained runnable server jar."
    archiveFileName.set("server.jar")
    manifest {
        attributes(
            "Main-Class" to "com.lanfps.server.MainKt",
            "Implementation-Title" to "LAN FPS Server",
            "Implementation-Version" to project.version.toString(),
        )
    }
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    from(sourceSets.main.get().output)
    dependsOn(configurations.runtimeClasspath)
    from({
        configurations.runtimeClasspath.get()
            .filter { it.name.endsWith("jar") }
            .map { zipTree(it) }
    }) {
        exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "META-INF/versions/9/module-info.class")
    }
}

/**
 * Assembles the Windows server bundle:
 *   dist/server.zip -> server.jar, run-server.bat, server.properties, arena01.json, README
 */
val packageServer = tasks.register<Zip>("packageServer") {
    group = "distribution"
    description = "Creates release/server.zip ready to copy to the Windows 10 machine."
    dependsOn(fatJar)
    archiveFileName.set("server.zip")
    destinationDirectory.set(rootProject.layout.projectDirectory.dir("release"))

    from(fatJar.get().archiveFile)
    from("run-server.bat")
    from("README_SERVER_WINDOWS.txt")
    from("src/main/resources/server.properties")
    from("src/main/resources/arena01.json")
}

tasks.named("build") {
    finalizedBy(fatJar)
}
