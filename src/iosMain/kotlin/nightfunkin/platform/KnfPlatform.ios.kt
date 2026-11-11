package nightfunkin.platform

import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSHomeDirectory

/**
 * iOS roots. The app's Documents folder is exposed to Finder through
 * `UIFileSharingEnabled` (+ `LSSupportsOpeningDocumentsInPlace`) in
 * Info.plist, so users drop the asset tree in as `KNF/assets/`. The save file
 * lives in the same `KNF` folder, keeping everything user-visible in one
 * place.
 */
@OptIn(ExperimentalForeignApi::class)
actual object KnfPlatform {
    private val home: String get() = NSHomeDirectory()

    private val documentsDir: String get() = "$home/Documents"

    private val knfDir: String get() = "$documentsDir/KNF"

    actual fun assetRootCandidates(): List<String> = listOfNotNull(
        KnfEnv["KNF_ASSETS"]?.takeIf { it.isNotBlank() },
        "$knfDir/assets",
    )

    actual fun userFilesDir(): String = knfDir

    private var hinted = false

    actual fun handleAssetsNotFound() {
        if (hinted) return
        hinted = true
        println(
            "KNF: could not locate the FNF asset root. Tried:\n" +
                assetRootCandidates().joinToString("\n") { "  - $it" } +
                "\nCopy the 'assets' folder into Files/KNF/ on the device (Finder file sharing)."
        )
    }

    actual val isMobile: Boolean get() = true

    actual val isAndroid: Boolean get() = false
}
