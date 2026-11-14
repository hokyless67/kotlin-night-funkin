package nightfunkin.platform

import java.io.File

actual object KnfPlatform {
    private val ROOT_CANDIDATES = listOf(
        "Funkin-0.8.6-Source/assets",
        "assets",
        "../assets",
    )

    actual fun assetRootCandidates(): List<String> = listOfNotNull(
        KnfEnv["KNF_ASSETS"],
        System.getProperty("knf.assets"),
    ).filter { it.isNotBlank() } + ROOT_CANDIDATES + upwardCandidates()

    /**
     * `Funkin-0.8.6-Source/assets` / `assets` in the working directory's
     * parents.
     *
     * A `jpackage` app image lives in `build/jpackage/<name>/`, and launching
     * it from Explorer makes its own folder the working directory. Walking up
     * lets it find the repository's asset tree without setting `KNF_ASSETS`.
     */
    private fun upwardCandidates(): List<String> {
        val candidates = ArrayList<String>()
        var dir = File(".").absoluteFile
        repeat(6) {
            dir = dir.parentFile ?: return candidates
            candidates += File(dir, "Funkin-0.8.6-Source/assets").path
            candidates += File(dir, "assets").path
        }
        return candidates
    }

    actual fun userFilesDir(): String = System.getProperty("user.home") ?: "."

    private var hinted = false

    actual fun handleAssetsNotFound() {
        if (hinted) return
        hinted = true
        println(
            "KNF: could not locate the FNF asset root. Tried:\n" +
                assetRootCandidates().joinToString("\n") { "  - $it" } +
                "\nPut the 'assets' folder next to the game, or set KNF_ASSETS=<path>"
        )
    }

    /**
     * `KNF_MOBILE=1` turns the desktop build into a mobile emulator: the
     * on-screen touch overlay appears (the mouse emulates a finger) and the
     * video backend behaves like mobile, skipping cutscenes. Asset and save
     * locations stay desktop, so you can test with your local asset tree.
     */
    actual val isMobile: Boolean get() = KnfEnv["KNF_MOBILE"] == "1"

    actual val isAndroid: Boolean get() = false

    actual fun requestMemoryReclaim() {
        runCatching { Runtime.getRuntime().gc() }
    }

    actual fun memoryReport(): String = runCatching {
        val runtime = Runtime.getRuntime()
        val used = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024)
        val total = runtime.totalMemory() / (1024 * 1024)
        val max = runtime.maxMemory() / (1024 * 1024)
        "$used/${total}MB of ${max}MB"
    }.getOrDefault("unknown")

    private var tracked: java.lang.ref.WeakReference<Any>? = null

    actual fun trackWeak(value: Any?) {
        tracked = value?.let { java.lang.ref.WeakReference(it) }
    }

    actual fun trackedStillAlive(): Boolean = tracked?.get() != null

    /** No cutout on a desktop window. */
    actual fun prepareWindow(window: Any?) = Unit

    /** No cutout on a desktop window. */
    actual fun displayCutoutPx(window: Any?): Int = 0
}
