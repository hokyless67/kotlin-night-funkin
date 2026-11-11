package nightfunkin.platform

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import java.io.File

/**
 * Android roots.
 *
 * Assets live in the device's **public Documents folder** — the user copies
 * the tree from a PC over USB (or a file manager) into
 * `Documents/KNF/files/assets/`. Reading shared storage on Android 11+ needs
 * the "All files access" special permission, so when the assets are missing
 * [handleAssetsNotFound] opens that exact system settings screen once; the
 * loading scene waits until the folder shows up. The app-scoped dirs
 * (`Android/data/<pkg>/files/assets/`) remain as fallbacks that work with no
 * permissions at all. The save file stays in the app-scoped dir, which is
 * writable without any permission.
 */
actual object KnfPlatform {
    private var storageSettingsLaunched = false

    private fun externalFilesDir(): String? =
        AndroidContextHolder.appContext()?.getExternalFilesDir(null)?.absolutePath

    private fun internalFilesDir(): String? =
        AndroidContextHolder.appContext()?.filesDir?.absolutePath

    /**
     * `Documents/KNF/files/assets` in the shared storage, e.g.
     * `/storage/emulated/0/Documents/KNF/files/assets`.
     */
    private fun publicDocumentsAssets(): String? {
        val docs = @Suppress("DEPRECATION")
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
            ?.absolutePath
            ?: return null
        return "$docs/KNF/files/assets"
    }

    actual fun assetRootCandidates(): List<String> = listOfNotNull(
        KnfEnv["KNF_ASSETS"]?.takeIf { it.isNotBlank() },
        publicDocumentsAssets(),
        externalFilesDir()?.let { "$it/assets" },
        internalFilesDir()?.let { "$it/assets" },
    )

    actual fun userFilesDir(): String =
        externalFilesDir() ?: internalFilesDir() ?: "."

    /**
     * On Android 11+ shared storage is only readable with "All files access",
     * which cannot be requested with a runtime dialog — the system settings
     * screen has to be opened once. Launched at most once per process.
     */
    actual fun handleAssetsNotFound() {
        val hasAllFilesAccess = Build.VERSION.SDK_INT >= 30 && Environment.isExternalStorageManager()
        if (hasAllFilesAccess) {
            println(
                "KNF: could not locate the FNF asset root. Tried:\n" +
                    assetRootCandidates().joinToString("\n") { "  - $it" }
            )
            return
        }
        println(
            "KNF: assets not found and storage access not granted — " +
                "opening the 'All files access' settings; copy the game assets " +
                "into Documents/KNF/files/assets/ afterwards"
        )
        if (storageSettingsLaunched) return
        storageSettingsLaunched = true
        val context = AndroidContextHolder.appContext() ?: return
        runCatching {
            context.startActivity(
                Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                    .setData(Uri.parse("package:${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }.recoverCatching {
            // A few OEM builds lack the per-app settings action.
            context.startActivity(
                Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }.onFailure {
            println("KNF: could not open the storage settings: ${it.message}")
        }
    }

    actual val isMobile: Boolean get() = true

    actual val isAndroid: Boolean get() = true
}
