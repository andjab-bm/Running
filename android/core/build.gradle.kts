import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Pure Kotlin: session engine, FIT encoder, formatting, watch protocol, design tokens.
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
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
    // Tests read the web tokens and write the FIT fixture shared with the server.
    systemProperty("repo.root", rootDir.parentFile.absolutePath)
    systemProperty("update.fixtures", providers.gradleProperty("updateFixtures").getOrElse("false"))
}
