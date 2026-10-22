package nightfunkin.core

import korlibs.audio.sound.Sound
import korlibs.audio.sound.readSound
import korlibs.image.bitmap.Bitmap
import korlibs.image.format.RegisteredImageFormats
import java.io.File


/**
 * Resolves and reads the Friday Night Funkin' asset tree straight off disk.
 *
 * KNF does NOT copy the 1.2 GB asset folder into the jar — everything is
 * streamed from the real directory at runtime. That keeps builds fast and lets
 * you drop in new songs without recompiling.
 */
object KnfFiles {
    /** Candidate locations for the FNF asset root, relative to the working dir. */
    private val ROOT_CANDIDATES = listOf(
        "Funkin-0.8.6-Source/assets",
        "assets",
        "../assets",
    )

    val assetsRoot: File by lazy {
        for (candidate in ROOT_CANDIDATES) {
            val dir = File(candidate).absoluteFile
            if (dir.isDirectory) return@lazy dir
        }
        error(
            "KNF: could not locate the FNF asset root. Tried:\n" +
                ROOT_CANDIDATES.joinToString("\n") { "  - ${File(it).absolutePath}" } +
                "\nPut the 'assets' folder next to the game, or set -Dknf.assets=<path>"
        )
    }

    /** Last-resort override: `-Dknf.assets=C:/path/to/assets`. */
    private val override: String? = System.getProperty("knf.assets")

    init {
        override?.let { System.setProperty("knf.assets.resolved", it) }
    }

    fun file(path: String): File = File(assetsRoot, path)

    fun fileOrNull(path: String): File? = file(path).takeIf { it.isFile }

    fun exists(path: String): Boolean = file(path).isFile

    /** Like [exists], but for a folder - Animate atlases live in one. */
    fun isDirectory(path: String): Boolean = file(path).isDirectory

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
        assetsRoot.listFiles()
            ?.filter { it.isDirectory && File(it, "images").isDirectory }
            ?.map { it.name }
            ?.sortedBy { when (it) { "shared" -> 0; "preload" -> 2; else -> 1 } }
            ?: emptyList()
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
        if (!f.isFile) error("KNF: missing asset '$path' (looked in ${f.absolutePath})")
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
     * KorGE's JVM backend decodes MP3 and WAV but not Ogg Vorbis, while FNF
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
     * black bitmap. Those go through ImageIO instead, which is in the JDK.
     */
    private fun decodeImage(data: ByteArray): Bitmap {
        val raw =
            if (needsImageIo(data)) decodeViaImageIo(data) else RegisteredImageFormats.decode(data)
        return premultiply(raw)
    }

    /**
     * KorGE 6's OpenGL backend demands premultiplied alpha, but neither its own
     * PNG decoder nor ImageIO produce it, so every sheet gets logged as
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

    /**
     * True for PNGs KorGE's own decoder cannot handle.
     *
     * `korlibs.image.format.PNG` only implements 8-bit RGBA (colour type 6);
     * every other colour type falls into a `TODO("Bytes: N")` and throws
     * `NotImplementedError`. FNF ships plenty of the others - the judgement
     * popups and the health bar are greyscale+alpha (type 4), the note sheets
     * are indexed (type 3).
     *
     * Those go through ImageIO instead, which is in the JDK.
     */
    private fun needsImageIo(d: ByteArray): Boolean {
        if (d.size < 30) return false
        val pngSignature = d[0] == 0x89.toByte() &&
            d[1] == 'P'.code.toByte() && d[2] == 'N'.code.toByte() && d[3] == 'G'.code.toByte()
        if (!pngSignature) return false
        // IHDR payload: bit depth at byte 24, colour type at byte 25.
        val bitDepth = d[24].toInt() and 0xFF
        val colorType = d[25].toInt() and 0xFF
        return colorType != 6 || bitDepth != 8
    }

    /**
     * Palette PNGs have to come through ImageIO, but its `getRGB` hands back
     * `0xAARRGGBB` while `Bitmap32` stores its ints little-endian as
     * `0xAABBGGRR`. Packing the ARGB value straight in swaps red and blue, which
     * turns the cyan down-arrow yellow and the red right-arrow blue — so the
     * channels are reversed here.
     */
    private fun decodeViaImageIo(data: ByteArray): Bitmap {
        val img = javax.imageio.ImageIO.read(java.io.ByteArrayInputStream(data))
            ?: return RegisteredImageFormats.decode(data)
        val w = img.width
        val h = img.height
        val argb = img.getRGB(0, 0, w, h, null, 0, w)
        val pixels = IntArray(argb.size)
        for (i in argb.indices) {
            val v = argb[i]
            val a = (v ushr 24) and 0xFF
            val r = (v ushr 16) and 0xFF
            val g = (v ushr 8) and 0xFF
            val b = v and 0xFF
            // 0xAABBGGRR to match Bitmap32's in-memory layout.
            pixels[i] = (a shl 24) or (b shl 16) or (g shl 8) or r
        }
        return korlibs.image.bitmap.Bitmap32(w, h, pixels, false)
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
