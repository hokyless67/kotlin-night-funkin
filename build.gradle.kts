import korlibs.korge.gradle.Orientation
import korlibs.korge.gradle.targets.ios.iosXcodegenExt

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

/**
 * `-Pknf.ios.unsigned=true` builds an unsigned iOS app: no Apple developer
 * account, certificate or provisioning profile is required. The resulting
 * `.app`/`.ipa` can be re-signed by a sideloading tool (Sideloadly, AltStore,
 * ...) with the user's own Apple ID. See `.github/workflows/ios.yml`.
 */
val unsignedIosBuild = providers.gradleProperty("knf.ios.unsigned").orNull?.toBoolean() == true

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

    // Without an explicit team the plugin runs `security find-certificate`
    // and crashes on machines (like GitHub's macOS runners) that have no
    // signing certificate. An unsigned build does not care about the value,
    // so feed it a placeholder; signed local builds can pass
    // `-Pknf.ios.team=<TEAMID>` or set `DEVELOPMENT_TEAM`.
    iosDevelopmentTeam = providers.gradleProperty("knf.ios.team").orNull
        ?: System.getenv("DEVELOPMENT_TEAM")
        ?: if (unsignedIosBuild) "KNFTEAM000" else null

    // Desktop (JVM) keeps working as before; Android + iOS are the mobile targets.
    targetJvm()
    targetAndroid()
    targetIos()

    // Rhythm game — force landscape.
    orientation = Orientation.LANDSCAPE
}

/**
 * The Korge plugin generates the Xcode project with a hardcoded Info.plist
 * (portrait + landscape, no document sharing). The game is landscape-only and
 * loads its assets from the app's Documents folder, so patch the generated
 * files before xcodebuild runs.
 *
 * When [unsignedIosBuild] is set, code signing is also disabled in the
 * generated Xcode project so device builds work without any Apple account.
 */
val knfIosPrepareProject = tasks.register("knfIosPrepareProject") {
    group = "korge package"
    description = "Patches the generated iOS Info.plist / Xcode project (landscape, file sharing, optional unsigned build)."

    if (tasks.findByName("prepareKotlinNativeIosProject") != null) {
        dependsOn("prepareKotlinNativeIosProject")
    }

    doLast {
        val projectFolder = layout.buildDirectory.dir("platforms/ios").get().asFile
        val plistFile = File(projectFolder, "app/Info.plist")
        check(plistFile.isFile) { "KNF: missing generated Info.plist at $plistFile" }

        var plist = plistFile.readText()

        // Drop portrait/portraitUpsideDown entries, keep both landscape ones.
        plist = plist.lines()
            .filterNot { it.contains("UIInterfaceOrientationPortrait") }
            .joinToString("\n")

        // Kotlin/Native only builds arm64 these days; the plugin still emits
        // the 32-bit `armv7` requirement.
        plist = plist.replace("<string>armv7</string>", "<string>arm64</string>")

        // `ibtool` on GitHub's macOS runners sometimes refuses to compile the
        // generated LaunchScreen.storyboard ("iOS 18.0 Platform Not
        // Installed"), which fails the whole Xcode build. Replace it with the
        // native iOS 14+ `UILaunchScreen` dictionary and delete the storyboard
        // below, so no Interface Builder step runs at all.
        plist = plist.replace(
            Regex("""\s*<key>UILaunchStoryboardName</key>\s*<string>[^<]*</string>"""),
            ""
        )

        // Let users drop the FNF asset tree into Files/Finder and keep a
        // landscape-only iPad app (iPad requires full screen for that).
        val extraKeys = """
            <key>UILaunchScreen</key>
            <dict/>
            <key>UIFileSharingEnabled</key>
            <true/>
            <key>LSSupportsOpeningDocumentsInPlace</key>
            <true/>
            <key>UIRequiresFullScreen</key>
            <true/>
            <key>UIStatusBarHidden</key>
            <true/>
        """.trimIndent() + "\n"
        plist = plist.replaceFirst("</dict>", "$extraKeys    </dict>")

        plist = Regex("""(<key>CFBundleShortVersionString</key>\s*<string>)[^<]*(</string>)""")
            .replace(plist) { "${it.groupValues[1]}$version${it.groupValues[2]}" }

        plistFile.writeText(plist)
        logger.lifecycle("KNF: patched iOS Info.plist ($plistFile)")

        val projectYml = File(projectFolder, "project.yml")
        check(projectYml.isFile) { "KNF: missing generated project.yml at $projectYml" }

        var yml = projectYml.readText()
        var regenerateProject = false

        // Drop the generated LaunchScreen.storyboard (see the Info.plist patch
        // above): the app uses the `UILaunchScreen` key instead. XcodeGen only
        // picks up files present at generation time, so remove it before the
        // project is regenerated.
        val launchStoryboard = File(projectFolder, "app/Base.lproj/LaunchScreen.storyboard")
        if (launchStoryboard.isFile) {
            launchStoryboard.delete()
            launchStoryboard.parentFile?.delete()
            regenerateProject = true
        }

        // XcodeGen looks for `SettingPresets` in its working directory first.
        // Older builds of this script copied them there; drop any leftover so
        // the default presets (app-icon + iPad asset targets) do not come back.
        val stalePresets = File(projectFolder, "SettingPresets")
        if (stalePresets.isDirectory) {
            stalePresets.deleteRecursively()
            regenerateProject = true
        }

        // XcodeGen does not create schemes unless they are listed, but the
        // KorGE plugin builds with `xcodebuild -scheme app-<arch>-<config>`.
        if (!Regex("""(?m)^schemes:""").containsMatchIn(yml)) {
            yml = yml.trimEnd() + "\n" + buildString {
                appendLine("schemes:")
                for (arch in listOf("X64", "SimulatorArm64", "Arm64")) {
                    for (config in listOf("Debug", "Release")) {
                        val scheme = "app-$arch-$config"
                        appendLine("  $scheme:")
                        appendLine("    build:")
                        appendLine("      targets:")
                        appendLine("        $scheme: all")
                        appendLine("    run:")
                        appendLine("      config: $config")
                        appendLine("    archive:")
                        appendLine("      config: $config")
                    }
                }
            }
            regenerateProject = true
        }

        if (unsignedIosBuild && !yml.contains("CODE_SIGNING_ALLOWED")) {
            yml = yml.replaceFirst(
                "ENABLE_BITCODE: NO",
                "ENABLE_BITCODE: NO\n  CODE_SIGNING_ALLOWED: \"NO\"\n  CODE_SIGNING_REQUIRED: \"NO\"\n  CODE_SIGN_IDENTITY: \"\""
            )
            regenerateProject = true
        }

        // `main.m` imports UIKit, but the Xcode project generated by KorGE has
        // no Objective-C module settings, so clang does not emit the
        // `-framework UIKit` autolink hint and the final `Ld` step fails with
        // undefined `UIResponder`/`UIApplicationMain`. Enable modules and add
        // the UIKit linker flag explicitly. (Do NOT copy XcodeGen's
        // SettingPresets here: they also re-enable `--app-icon` and iPad asset
        // targets, and `actool` on the CI runners then fails because the
        // matching simulator runtime is not installed.)
        if (!yml.contains("CLANG_ENABLE_MODULES")) {
            yml = yml.replaceFirst(
                "ENABLE_BITCODE: NO",
                "ENABLE_BITCODE: NO\n  CLANG_ENABLE_MODULES: \"YES\"\n  OTHER_LDFLAGS: [\"-framework\", \"UIKit\"]"
            )
            regenerateProject = true
        }

        if (regenerateProject) {
            projectYml.writeText(yml)

            // Regenerate app.xcodeproj so xcodebuild sees the new settings/schemes.
            project.exec {
                workingDir(projectFolder)
                commandLine(project.iosXcodegenExt.xcodeGenExe.absolutePath)
            }
            logger.lifecycle("KNF: regenerated the Xcode project (schemes${if (unsignedIosBuild) ", unsigned" else ""})")
        }
    }
}

// Make sure the patch runs after project generation but before xcodebuild,
// for every device/simulator, debug/release build.
tasks.matching {
    it.name in setOf(
        "iosBuildDeviceDebug", "iosBuildDeviceRelease",
        "iosBuildSimulatorDebug", "iosBuildSimulatorRelease",
    )
}.configureEach {
    dependsOn(knfIosPrepareProject)
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
