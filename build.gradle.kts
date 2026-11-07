import korlibs.korge.gradle.Orientation

plugins {
    // The KorGE multiplatform plugin brings its own Kotlin (2.0.0), JVM/Android/iOS
    // toolchains and per-target bootstrap code, so `kotlin("jvm")`/`application`
    // must NOT be applied alongside it.
    id("com.soywiz.korge") version "6.0.0"
    // Keep in sync with the Kotlin version shipped by the korge plugin above.
    kotlin("plugin.serialization") version "2.0.0"
}

group = "nightfunkin"
version = "0.1.0"

korge {
    // IMPORTANT: all properties first — `targetXxx()` captures the current
    // extension state (application id, entry point, ...) when they run.
    // Application id used on Android: assets are dropped into
    // `Documents/KNF/files/assets/` on the device storage (see
    // KnfPlatform.android). Reading the shared Documents folder requires:
    //  - `MANAGE_EXTERNAL_STORAGE` ("All files access") on Android 11+,
    //  - `READ_EXTERNAL_STORAGE` on Android 10 and below,
    //  - `requestLegacyExternalStorage` keeps direct paths working on
    //    Android 10.
    id = "dev.hokyless.kotlinfunkin"
    name = "Kotlin Night Funkin"
    version = "0.1.0"

    androidPermission("android.permission.MANAGE_EXTERNAL_STORAGE")
    androidPermission("android.permission.READ_EXTERNAL_STORAGE")
    androidCustomApplicationAttribute("android:requestLegacyExternalStorage", "true")
    // Fully decoded FNF stems are heavy; give the JVM heap room on top of the
    // default 256 MB (audio is streamed now, but sprites still allocate big
    // bitmaps during loading).
    androidCustomApplicationAttribute("android:largeHeap", "true")

    // The game's entry is the top-level `suspend fun main()` in the
    // `nightfunkin` package. This drives three things at once: the `jvmRun`
    // main class (`nightfunkin.MainKt`) and the generated Android/iOS
    // bootstrap classes, which import `nightfunkin.main`.
    jvmMainClassName = "nightfunkin.MainKt"

    // Desktop (JVM) keeps working as before; Android + iOS are the mobile targets.
    targetJvm()
    targetAndroid()
    targetIos()

    // Rhythm game — force landscape.
    orientation = Orientation.LANDSCAPE

    // NOTE: iOS builds also need `UIFileSharingEnabled` + `LSSupportsOpeningDocumentsInPlace`
    // in the generated Info.plist so users can drop `KNF/assets/` into the app's
    // Documents folder via Finder. If this plugin version lacks a DSL for plist
    // keys, add them in Xcode after the first iOS build.
}

repositories {
    mavenCentral()
    // Same story as settings.gradle.kts: dl.google.com is unreachable from
    // this network, so the Aliyun Google mirror goes first.
    maven("https://maven.aliyun.com/repository/google")
    google()
}

dependencies {
    // Common (all targets) libraries. KorGE itself and korlibs come from the plugin.
    add("commonMainImplementation", "org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    add("commonMainImplementation", "org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
}
