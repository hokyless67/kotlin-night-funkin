package nightfunkin.core

import korlibs.audio.sound.Sound
import korlibs.audio.sound.readSound
import korlibs.image.bitmap.Bitmap
import korlibs.image.format.RegisteredImageFormats
import nightfunkin.platform.KnfFile
import nightfunkin.platform.KnfPlatform
import kotlin.concurrent.Volatile


/**
 * Resolves and reads the Friday Night Funkin' asset tree straight off disk.
 *
 * KNF does NOT copy the 1.2 GB asset folder into the binary — everything is
 * streamed from the real directory at runtime. That keeps builds fast and lets
 * you drop in new songs without recompiling. Where the folder lives is decided
 * by [KnfPlatform] (next to the game on desktop, the app storage dir on
 * mobile); file access goes through the platform-independent [KnfFile].
 */
object KnfFiles {
    /**
     * Resolved lazily and *retrying*: on a first Android run the storage
     * permission is granted through the system settings screen while the
     * loading scene waits, so the root may only appear a few seconds in.
     */
    @Volatile
    private var resolvedRoot: KnfFile? = null

    val assetsRoot: KnfFile
        get() = tryResolveRoot()
            ?: error(
                "KNF: could not locate the FNF asset root. Tried:\n" +
                    KnfPlatform.assetRootCandidates().joinToString("\n") { "  - $it" } +
                    "\nPut the 'assets' folder where the platform expects it, or set KNF_ASSETS=<path>"
            )

    /** True once the asset tree has been located. */
    fun hasAssets(): Boolean = tryResolveRoot() != null

    private fun tryResolveRoot(): KnfFile? {
        resolvedRoot?.let { return it }
        for (candidate in KnfPlatform.assetRootCandidates()) {
            val dir = KnfFile(candidate)
            if (isReadableRoot(dir)) {
                resolvedRoot = dir
                println("KNF: asset root found: ${dir.absolutePath()}")
                return dir
            }
        }
        KnfPlatform.handleAssetsNotFound()
        return null
    }

    /**
     * Directory existence is not enough: on Android 11+ without the "All
     * files access" permission folders are still visible (stat passes) but
     * every file open fails with `EACCES`, so the actual read of a small
     * probe file is the only reliable check. `introText.txt` ships with every
     * FNF asset tree; when it is absent (a trimmed tree) the directory check
     * alone is accepted.
     */
    private fun isReadableRoot(dir: KnfFile): Boolean {
        if (!dir.isDirectory()) return false
        val probe = dir.child("preload/data/introText.txt")
        if (!probe.isFile()) return true
        return runCatching { probe.readBytes() }.isSuccess
    }

    fun file(path: String): KnfFile = assetsRoot.child(path)

    fun fileOrNull(path: String): KnfFile? = file(path).takeIf { it.isFile() }

    fun exists(path: String): Boolean = file(path).isFile()

    /** Like [exists], but for a folder - Animate atlases live in one. */
    fun isDirectory(path: String): Boolean = file(path).isDirectory()

    /**
     * Finds the folder holding an Animate atlas, given the stage's library and
     * the prop's asset path.
     *
     * A stage's `directory` names the week's library (`week2`, `week7`, ...), and
     * the prop's path is relative to that library's `images` folder.
     */
    fun findAnimateDir(library: String, assetPath: String): String? {
        val raw = assetPath.substringAfter(':')
        for (lib in listOf(library, "shared", "preload") + imageLibraries) {
            if (lib.isEmpty()) continue
            val dir = "$lib/images/$raw"
            if (isDirectory(dir) && exists("$dir/Animation.json")) return dir
        }
        return null
    }

    // ---- FNF's asset libraries ------------------------------------------

    /**
     * Every top-level folder under `assets/` that holds an `images/` directory.
     *
     * FNF's `Paths.getLibraryPathForce` builds `'$library:assets/$library/$file'`,
     * so a "library" is just a folder name - `shared`, `preload`, and one per
     * week. `getPath` checks `shared` first and falls back to `preload`, which is
     * the order used here; week folders are tried in between, because a
     * `school`-style stage references `weeb/weebSky` and that only resolves under
     * `week6/images`.
     */
    val imageLibraries: List<String> by lazy {
        assetsRoot.listNames().orEmpty()
            .filter { name ->
                val dir = assetsRoot.child(name)
                dir.isDirectory() && dir.child("images").isDirectory()
            }
            .sortedBy { when (it) { "shared" -> 0; "preload" -> 2; else -> 1 } }
    }

    /** The library prefix of an `assetPath`, e.g. `week6` in `week6:weeb/weebSky`. */
    fun library(assetPath: String): String? =
        assetPath.substringBefore(':', "").ifEmpty { null }

    /**
     * Turns an FNF `assetPath` into a real path under the asset root.
     *
     * A library prefix wins if it is set and the file is there; otherwise every
     * image library is tried in FNF's order, and finally the path as given.
     * [suffix] is appended to the path itself, so `"/Animation.json"` or
     * `".png"` both work - note that an asset path is a *prefix*, not a
     * directory: the Animate sheets live in a folder named after it while the
     * legacy atlases are `<prefix>.xml` / `<prefix>.png` sitting beside it.
     */
    fun resolve(
        assetPath: String,
        suffix: String = "",
        forcedLibrary: String? = null,
    ): String? {
        val path = assetPath.substringAfter(':') + suffix
        library(assetPath)?.let { lib ->
            val direct = "$lib/images/$path"
            if (exists(direct)) return direct
        }
        forcedLibrary?.takeIf { it.isNotEmpty() }?.let { lib ->
            val direct = "$lib/images/$path"
            if (exists(direct)) return direct
        }
        for (root in imageLibraries) {
            val candidate = "$root/images/$path"
            if (exists(candidate)) return candidate
        }
        return path.takeIf { exists(it) }
    }

    /** Human-readable list of where [assetPath] was looked for. */
    fun resolveCandidates(
        assetPath: String,
        suffix: String = "",
        forcedLibrary: String? = null,
    ): String {
        val path = assetPath.substringAfter(':') + suffix
        val roots = listOfNotNull(
            library(assetPath)?.let { "$it/images" },
            forcedLibrary?.takeIf { it.isNotEmpty() }?.let { "$it/images" },
        ) + imageLibraries.map { "$it/images" } + listOf("<root>")
        return roots.joinToString(", ") { "$it/$path" }
    }

    fun bytes(path: String): ByteArray {
        val f = file(path)
        if (!f.isFile()) error("KNF: missing asset '$path' (looked in ${f.absolutePath()})")
        return f.readBytes()
    }

    fun bytesOrNull(path: String): ByteArray? = fileOrNull(path)?.readBytes()

    fun text(path: String): String = bytes(path).decodeToString().stripBom()

    fun textOrNull(path: String): String? = bytesOrNull(path)?.decodeToString()?.stripBom()

    /**
     * Adobe Animate's exported `spritemap1.json` files start with a UTF-8 BOM.
     * kotlinx.serialization rejects the resulting U+FEFF, so drop it before
     * handing any text asset to a JSON decoder.
     */
    private fun String.stripBom(): String =
        if (startsWith('\uFEFF')) substring(1) else this

    // ---- audio format negotiation --------------------------------------

    /**
     * KorGE's audio backends decode MP3 and WAV but not Ogg Vorbis, while FNF
     * ships every sound as `.ogg`. The asset tree conveniently contains both,
     * so we probe MP3 first and fall back to Ogg.
     */
    private val SOUND_EXTENSIONS = listOf(".mp3", ".ogg", ".wav")

    /** Returns the first candidate that exists on disk, trying [path] as-is first. */
    fun firstExisting(vararg paths: String): String? =
        paths.firstOrNull { exists(it) }

    /** Expands a stem like `sounds/foo` into its existing audio-file variants. */
    fun soundVariants(stem: String): List<String> =
        buildList {
            add(stem)
            for (ext in SOUND_EXTENSIONS) add("$stem$ext")
        }

    /** Picks the best playable audio file for [stem] (extension may be omitted). */
    fun resolveSound(stem: String): String? = firstExisting(*soundVariants(stem).toTypedArray())

    /** True when the resolved file is MP3, which carries ~12 ms of decoder latency. */
    fun isMp3(path: String): Boolean = path.endsWith(".mp3", ignoreCase = true)

    // ---- typed loaders --------------------------------------------------

    fun bitmap(path: String): Bitmap = decodeImage(bytes(path))

    fun bitmapOrNull(path: String): Bitmap? =
        bytesOrNull(path)?.let { runCatching { decodeImage(it) }.getOrNull() }

    /**
     * PNG decoding, with two workarounds.
     *
     * FNF exports several spritesheets (`notes.png`, `NOTE_hold_assets.png`, the
     * hold covers, most character sheets) as *indexed* PNGs, and KorGE's pure
     * Kotlin PNG decoder does not handle colour type 3 — it silently produces a
     * black bitmap. Those go through [KnfPng], KNF's own decoder for the colour
     * types KorGE does not implement.
     */
    private fun decodeImage(data: ByteArray): Bitmap {
        val raw =
            if (KnfPng.needsOwnDecoder(data)) KnfPng.decode(data) else RegisteredImageFormats.decode(data)
        return premultiply(raw)
    }

    /**
     * KorGE 6's OpenGL backend demands premultiplied alpha, but neither its own
     * PNG decoder nor [KnfPng] produce it, so every sheet gets logged as
     * `Trying to upload a non-premultiplied bitmap` and its soft edges render
     * wrong. Converting once here fixes note skins, characters and stages alike.
     */
    private fun premultiply(src: Bitmap): Bitmap {
        if (src.premultiplied) return src
        val bmp = src.toBMP32IfRequired()
        val ints = bmp.ints
        for (i in ints.indices) {
            val v = ints[i]
            val a = (v ushr 24) and 0xFF
            if (a == 0) {
                ints[i] = 0
            } else if (a != 255) {
                // Bitmap32 ints are 0xAABBGGRR.
                val b = ((v ushr 16) and 0xFF) * a / 255
                val g = ((v ushr 8) and 0xFF) * a / 255
                val r = (v and 0xFF) * a / 255
                ints[i] = (a shl 24) or (b shl 16) or (g shl 8) or r
            }
        }
        bmp.premultiplied = true
        return bmp
    }

    suspend fun sound(path: String): Sound = bytes(path).readSound()

    suspend fun soundOrNull(path: String): Sound? =
        bytesOrNull(path)?.readSound()

    // ---- convenience paths used across the game -------------------------

    fun noteStyleData(id: String): String = "preload/data/notestyles/$id.json"

    fun songMetadata(songId: String, variation: String = "default"): String =
        "preload/data/songs/$songId/$songId-metadata" + variationSuffix(variation) + ".json"

    fun songChart(songId: String, variation: String = "default"): String =
        "preload/data/songs/$songId/$songId-chart" + variationSuffix(variation) + ".json"

    private fun variationSuffix(variation: String): String =
        if (variation == Constants.DEFAULT_VARIATION) "" else "-$variation"

    fun instrumental(songId: String, suffix: String = ""): String =
        "songs/$songId/Inst$suffix.ogg"

    fun vocals(songId: String, charId: String, suffix: String = ""): String =
        "songs/$songId/Voices-$charId$suffix.ogg"

    /** Instrumental stem without an extension, for [resolveSound]. */
    fun instrumentalStem(songId: String, suffix: String = ""): String =
        "songs/$songId/Inst$suffix"

    /** Vocals stem without an extension, for [resolveSound]. */
    fun vocalsStem(songId: String, charId: String, suffix: String = ""): String =
        "songs/$songId/Voices-$charId$suffix"
}
