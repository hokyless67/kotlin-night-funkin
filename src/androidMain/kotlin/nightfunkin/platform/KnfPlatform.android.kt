package nightfunkin.platform

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import korlibs.render.AndroidGameWindow
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

    actual fun requestMemoryReclaim() {
        runCatching { Runtime.getRuntime().gc() }
    }

    actual fun memoryReport(): String = runCatching {
        val runtime = Runtime.getRuntime()
        val used = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024)
        val total = runtime.totalMemory() / (1024 * 1024)
        val max = runtime.maxMemory() / (1024 * 1024)
        // Java-heap objects are what KNF's bitmaps mostly are; the native
        // figure separates them from GPU/driver allocations, so a scene that
        // "leaves 176 MB behind" can be attributed to the right allocator.
        val native = android.os.Debug.getNativeHeapAllocatedSize() / (1024 * 1024)
        "$used/${total}MB of ${max}MB java + ${native}MB native"
    }.getOrDefault("unknown")

    private var tracked: java.lang.ref.WeakReference<Any>? = null

    actual fun trackWeak(value: Any?) {
        tracked = value?.let { java.lang.ref.WeakReference(it) }
    }

    actual fun trackedStillAlive(): Boolean = tracked?.get() != null

    private var cutoutModeApplied = false

    /**
     * `LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES`: in landscape the camera
     * cutout sits on the display's short edge, and Android's `default` mode
     * does allow drawing under it there — but a few OEM layouts still `NEVER`
     * it, which leaves a black strip instead of filling the screen. FNF's
     * Lime window fills it, so ask for the same explicitly.
     */
    actual fun prepareWindow(window: Any?) {
        if (cutoutModeApplied || Build.VERSION.SDK_INT < 28) return
        val activity = (window as? AndroidGameWindow)?.activity ?: return
        cutoutModeApplied = true
        runCatching {
            activity.runOnUiThread {
                runCatching {
                    val attrs = activity.window.attributes
                    attrs.layoutInDisplayCutoutMode =
                        android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                    activity.window.attributes = attrs
                }
            }
        }
    }

    /**
     * `ScreenUtil.getNotchRect` (Android): the widest safe inset of the
     * display cutout, in device pixels. The activity is reached through the
     * korlibs game window, which owns a public reference to it.
     */
    actual fun displayCutoutPx(window: Any?): Int {
        if (Build.VERSION.SDK_INT < 28) return 0
        val activity = (window as? AndroidGameWindow)?.activity ?: return 0
        return runCatching {
            val insets = activity.window.decorView.rootWindowInsets ?: return 0
            val cutout = insets.displayCutout ?: return 0
            // `ScreenUtil.getNotchRect` (`ScreenUtil.java`): sum the widths of
            // every cutout rectangle, which are already in window coordinates.
            var width = 0
            for (rect in cutout.boundingRects) width += rect.width()
            width
        }.getOrDefault(0)
    }
}
