import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Pure Kotlin: session engine, coaching engines, FIT encoder, formatting, watch protocol, design tokens.
// No Android dependency, so its tests run on any machine.
plugins {
    alias(libs.plugins.kotlin.jvm)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
    }
}

dependencies {
    // JSON tree only (the session document): no compiler plugin needed.
    implementation(libs.kotlinx.serialization.json)
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
    // Tests read the web tokens and the session fixtures, and write the FIT fixture shared with the server.
    systemProperty("repo.root", rootDir.parentFile.absolutePath)
    systemProperty("update.fixtures", providers.gradleProperty("updateFixtures").getOrElse("false"))
}
