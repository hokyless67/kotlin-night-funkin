plugins {
    kotlin("jvm") version "2.0.20"
    kotlin("plugin.serialization") version "2.0.20"
    application
}

group = "nightfunkin"
version = "0.1.0"

repositories {
    mavenCentral()
}

dependencies {
    implementation("com.soywiz.korge:korge-jvm:6.0.0")
    // korlibs' Injector builds Scene instances reflectively.
    implementation(kotlin("reflect"))
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
}

application {
    mainClass.set("nightfunkin.MainKt")
    applicationDefaultJvmArgs = listOf("-Xmx2g")
}

// KorGE needs these for smooth JVM windowing
tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerOptions {
        freeCompilerArgs.add("-Xjvm-default=all")
    }
}
