package nightfunkin.platform

import android.os.Environment
import java.io.File

/**
 * `System.getenv` exists on Android but is basically empty for apps, so the
 * documented `KNF_*` debug hooks were unreachable on the device that needs them
 * most. This adds a plain-text `knf.cfg` next to the assets:
 *
 * ```
 * # Documents/KNF/knf.cfg
 * KNF_FPS=1
 * KNF_DEBUG_DEATH=1
 * ```
 *
 * `KEY=VALUE` per line, `#` starts a comment, a bare `KEY` means `1`. The file
 * is read once per launch, so the values can be changed with any file manager
 * and take effect after restarting the game - no adb, no rebuild.
 *
 * Two rules keep this safe on the boot path:
 *  - the candidate paths are built straight from `Environment`/the app context,
 *    never through [KnfPlatform] - `assetRootCandidates()` itself reads
 *    `KNF_ASSETS`, and a lazy value re-entered from its own initializer
 *    recurses until the stack overflows (that killed startup once already);
 *  - the config is loaded behind a plain flag, so a re-entrant read just sees
 *    an empty map.
 */
actual object KnfEnv {
    private var loaded = false
    private var values: Map<String, String> = emptyMap()

    actual operator fun get(name: String): String? {
        System.getenv(name)?.let { return it }
        ensureLoaded()
        return values[name]
    }

    /** True when `Documents/KNF/knf.cfg` was found and parsed this launch. */
    val hasConfigFile: Boolean
        get() {
            ensureLoaded()
            return values.isNotEmpty()
        }

    /** Keys currently set through the config file, for the boot log. */
    val configKeys: Set<String>
        get() {
            ensureLoaded()
            return values.keys
        }

    private fun ensureLoaded() {
        if (loaded) return
        loaded = true
        values = runCatching { loadConfig() }.getOrDefault(emptyMap())
    }

    private fun loadConfig(): Map<String, String> {
        val file = configCandidates().firstOrNull { it.isFile } ?: return emptyMap()
        return file.readLines().mapNotNull { raw ->
            val line = raw.substringBefore('#').trim()
            if (line.isEmpty()) return@mapNotNull null
            val eq = line.indexOf('=')
            if (eq < 0) {
                line to "1"
            } else {
                line.substring(0, eq).trim() to line.substring(eq + 1).trim()
            }
        }.toMap()
    }

    private fun configCandidates(): List<File> = listOfNotNull(
        // Same folder the user copies the assets into:
        // `<public Documents>/KNF/knf.cfg`.
        runCatching {
            @Suppress("DEPRECATION")
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
        }.getOrNull()?.let { File(File(it, "KNF"), "knf.cfg") },
        AndroidContextHolder.appContext()?.getExternalFilesDir(null)?.let { File(it, "knf.cfg") },
        AndroidContextHolder.appContext()?.filesDir?.let { File(it, "knf.cfg") },
    )
}
