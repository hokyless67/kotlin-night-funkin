package nightfunkin.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import korlibs.image.bitmap.Bitmap
import korlibs.image.bitmap.Bitmap32
import korlibs.image.bitmap.sliceWithSize
import korlibs.image.color.RGBA

@Serializable
private data class PrefixRef(val prefix: String = "")

@Serializable
private data class NoteStyleAssets(
    @SerialName("assetPath") val assetPath: String = "",
    @SerialName("scale") val scale: Float = 1f,
    @SerialName("alpha") val alpha: Double = 1.0,
    @SerialName("offsets") val offsets: List<Double> = listOf(0.0, 0.0),
    @SerialName("enabled") val enabled: Boolean = true,
    /**
     * Note-skin files mix objects and scalars inside `data` (e.g. `"enabled": true`
     * sitting next to prefix maps), so it is read as raw JSON.
     */
    @SerialName("data") val data: JsonObject? = null,
    @SerialName("leftSplashes") val leftSplashes: List<PrefixRef>? = null,
    @SerialName("downSplashes") val downSplashes: List<PrefixRef>? = null,
    @SerialName("upSplashes") val upSplashes: List<PrefixRef>? = null,
    @SerialName("rightSplashes") val rightSplashes: List<PrefixRef>? = null,
    @SerialName("left") val left: PrefixRef? = null,
    @SerialName("down") val down: PrefixRef? = null,
    @SerialName("up") val up: PrefixRef? = null,
    @SerialName("right") val right: PrefixRef? = null,
)

@Serializable
private data class NoteStyleData(
    val version: String = "1.0.0",
    val name: String = "",
    val author: String = "",
    val fallback: String? = null,
    val assets: Map<String, NoteStyleAssets> = emptyMap(),
)

/**
 * Resolves note-skin assets declared by `preload/data/notestyles/<id>.json`
 * (FNF ships `funkin`, `pixel` and possibly modded skins).
 *
 * All lookups go through [active], which is this skin when its atlases resolve
 * and otherwise the skin it declares as `fallback`. That keeps the texture and
 * the scale coming from the same place — mixing them (a pixel texture at the
 * non-pixel scale, say) blows the strumline geometry up by 8x.
 */
class NoteStyle(val id: String) {

    private val data: NoteStyleData = runCatching {
        knfJson.decodeFromString<NoteStyleData>(KnfFiles.text(KnfFiles.noteStyleData(id)))
    }.getOrElse { e ->
        println("KNF: could not read note style '$id': $e")
        NoteStyleData(name = id)
    }

    private val atlases = HashMap<String, TextureAtlas>()
    private val bitmaps = HashMap<String, Bitmap>()
    private val sustainCache = HashMap<Int, Pair<AtlasSlice, AtlasSlice>>()
    private val colorFallbackCache = HashMap<Int, AtlasSlice>()

    private val arrowKeys = listOf("left", "down", "up", "right")

    private val declaredFallback: NoteStyle? by lazy {
        data.fallback?.takeIf { it.isNotBlank() && it != id }?.let { NoteStyle.get(it) }
    }

    /** The style actually used for rendering. */
    private val active: NoteStyle by lazy {
        val asset = data.assets["note"]
        val primaryResolves = asset != null &&
            asset.assetPath.isNotEmpty() &&
            atlasFor(asset.assetPath) != null
        if (primaryResolves) this else (declaredFallback ?: this)
    }

    // ======================================================================
    // public API
    // ======================================================================

    /** Note-skin scale, taken from the style that actually supplied the art. */
    val scale: Float
        get() = active.data.assets["note"]?.scale
            ?: active.data.assets["noteStrumline"]?.scale
            ?: 1f

    val splashEnabled: Boolean get() = active.data.assets["noteSplash"]?.enabled ?: true
    val splashAlpha: Double get() = active.data.assets["noteSplash"]?.alpha ?: 0.8
    val splashOffsets: List<Double>
        get() = active.data.assets["noteSplash"]?.offsets ?: listOf(25.0, -5.0)

    /** Name of the atlas frame a direction's arrow resolves to, for diagnostics. */
    fun noteFrameName(direction: Int): String {
        val style = active
        val asset = style.data.assets["note"]
        val atlas = asset?.let { style.atlasFor(it.assetPath) }
        if (asset != null && atlas != null) {
            val key = style.arrowKeys[direction]
            val prefix = asset.assetBy(key) ?: key
            val hit = atlas.framesByPrefix(prefix).firstOrNull()
            if (hit != null) return prefix + " -> " + hit.name
        }
        return "FALLBACK-SOLID"
    }

    fun strumFrameName(direction: Int, kind: String): String {
        val style = active
        val asset = style.data.assets["noteStrumline"]
        val atlas = asset?.let { style.atlasFor(it.assetPath) }
        if (asset != null && atlas != null) {
            val key = style.arrowKeys[direction] + kind
            val prefix = asset.assetBy(key) ?: key
            val hit = atlas.framesByPrefix(prefix).firstOrNull()
            if (hit != null) return prefix + " -> " + hit.name
        }
        return "FALLBACK-GREY"
    }

    fun noteSlice(direction: Int): AtlasSlice {
        val style = active
        val asset = style.data.assets["note"]
        val atlas = asset?.let { style.atlasFor(it.assetPath) }
        if (asset != null && atlas != null) {
            val key = style.arrowKeys[direction]
            val prefix = asset.assetBy(key) ?: key
            atlas.sliceByPrefix(prefix)?.let { return it }
            atlas.sliceByPrefix(key)?.let { return it }
        }
        return colorFallbackSlice(direction)
    }

    /** `kind` is one of `Static`, `Press`, `Confirm`, `ConfirmHold`. */
    fun strumSlice(direction: Int, kind: String): AtlasSlice {
        val style = active
        val asset = style.data.assets["noteStrumline"]
        val atlas = asset?.let { style.atlasFor(it.assetPath) }
        if (asset != null && atlas != null) {
            val key = "${style.arrowKeys[direction]}$kind"
            val prefix = asset.assetBy(key) ?: key
            atlas.sliceByPrefix(prefix)?.let { return it }
            atlas.sliceByPrefix(key)?.let { return it }
        }
        return grayFallbackSlice()
    }

    /**
     * Note splash animations, one list of frames per variant.
     *
     * FNF registers `note impact 1|2 <colour>0` as animations and plays them at
     * 24 +/- 2 fps, killing the sprite when the animation ends. Showing a single
     * frame instead leaves a big bright arrow parked on the lane.
     */
    fun splashVariants(direction: Int): List<List<AtlasSlice>> {
        val asset = active.data.assets["noteSplash"] ?: return emptyList()
        val atlas = atlasFor(asset.assetPath) ?: return emptyList()
        val key = when (direction) {
            0 -> "leftSplashes"
            1 -> "downSplashes"
            2 -> "upSplashes"
            else -> "rightSplashes"
        }
        val arr = asset.data?.get(key) as? JsonArray ?: return emptyList()
        return arr.mapNotNull { entry ->
            val prefix = ((entry as? JsonObject)?.get("prefix") as? JsonPrimitive)?.content
                ?: return@mapNotNull null
            atlas.framesByPrefix(prefix).map { atlas.slice(it) }
        }.filter { it.isNotEmpty() }
    }

    fun splashSlices(direction: Int): List<AtlasSlice> {
        val asset = active.data.assets["noteSplash"] ?: return emptyList()
        val atlas = atlasFor(asset.assetPath) ?: return emptyList()
        // Splash definitions live inside `data`, e.g. `data.leftSplashes`.
        val key = when (direction) {
            0 -> "leftSplashes"
            1 -> "downSplashes"
            2 -> "upSplashes"
            else -> "rightSplashes"
        }
        val arr = asset.data?.get(key) as? JsonArray ?: return emptyList()
        return arr.mapNotNull { entry ->
            val prefix = ((entry as? JsonObject)?.get("prefix") as? JsonPrimitive)?.content
                ?: return@mapNotNull null
            atlas.sliceByPrefix(prefix)
        }
    }

    /**
     * `NOTE_hold_assets.png` is a raw 8-column strip (no XML): for direction `d`
     * the body is column `d*2` and the end cap column `d*2+1`.
     */
    /**
     * Width of one sustain column in the raw strip, before the skin scale.
     *
     * FNF's `SustainTrail.setupHoldNoteGraphic` uses `graphic.width / 8 * zoom`,
     * so the trail is deliberately much narrower than the note head that sits on
     * it — stretching it to the head's width is what makes holds look like slabs.
     */
    fun sustainWidth(): Int? {
        val asset = data.assets["holdNote"] ?: return null
        val bmp = bitmapFor(asset.assetPath) ?: return null
        return (bmp.width / 8).takeIf { it > 0 }
    }

    fun sustainSlices(direction: Int): Pair<AtlasSlice, AtlasSlice>? {
        sustainCache[direction]?.let { return it }
        val asset = data.assets["holdNote"] ?: return null
        val bmp = bitmapFor(asset.assetPath) ?: return null
        val segW = bmp.width / 8
        if (segW <= 0 || bmp.height <= 0) return null
        val d = direction.coerceIn(0, 3)
        val body = bmp.sliceWithSize(d * 2 * segW, 0, segW, bmp.height)
        val cap = bmp.sliceWithSize((d * 2 + 1) * segW, 0, segW, bmp.height)
        return (body to cap).also { sustainCache[d] = it }
    }

    // ======================================================================
    // plumbing
    // ======================================================================

    private fun NoteStyleAssets.assetBy(key: String): String? =
        (data?.get(key) as? JsonObject)?.get("prefix")?.let { (it as? JsonPrimitive)?.content }
            ?: when (key) {
                "left" -> left?.prefix
                "down" -> down?.prefix
                "up" -> up?.prefix
                "right" -> right?.prefix
                else -> null
            }

    /**
     * FNF asset paths are bare (`NOTE_hold_assets`) or library-prefixed
     * (`week6:weeb/pixelUI/arrows-pixels`); the prefix names a top-level folder.
     */
    private fun assetCandidates(assetPath: String): List<String> {
        val library = assetPath.substringBefore(':', "")
        val raw = assetPath.substringAfter(':')
        return buildList {
            if (library.isNotEmpty() && library != raw) add("$library/$raw")
            add("shared/images/$raw")
            add("preload/images/$raw")
            add(raw)
        }
    }

    private fun atlasFor(assetPath: String): TextureAtlas? {
        atlases[assetPath]?.let { return it }
        for (candidate in assetCandidates(assetPath)) {
            val xml = "$candidate.xml"
            if (KnfFiles.exists(xml)) {
                val atlas = runCatching { TextureAtlas.load(xml) }
                    .onFailure { println("KNF: bad atlas $xml: ${it.message}") }
                    .getOrNull()
                if (atlas != null) {
                    atlases[assetPath] = atlas
                    return atlas
                }
            }
        }
        return null
    }

    private fun bitmapFor(assetPath: String): Bitmap? {
        bitmaps[assetPath]?.let { return it }
        for (candidate in assetCandidates(assetPath)) {
            val png = "$candidate.png"
            if (KnfFiles.exists(png)) {
                val bmp = runCatching { KnfFiles.bitmap(png) }.getOrNull()
                if (bmp != null) {
                    bitmaps[assetPath] = bmp
                    return bmp
                }
            }
        }
        return null
    }

    // ---- procedural stand-ins, only used when a skin asset is missing ----

    private fun colorFallbackSlice(direction: Int): AtlasSlice {
        val dir = direction.coerceIn(0, 3)
        return colorFallbackCache.getOrPut(dir) { solidSlice(dir + 1) }
    }

    private val grayFallback: AtlasSlice by lazy { solidSlice(0) }
    private fun grayFallbackSlice(): AtlasSlice = grayFallback

    /**
     * A 16x16 solid-colour bitmap standing in for a missing sprite.
     *
     * Packed by hand because `Bitmap32` stores its ints as 0xAABBGGRR; going
     * through `RGBA(color).value` swaps red and blue and turns the cyan
     * stand-in yellow.
     */
    private fun solidSlice(slot: Int): AtlasSlice {
        val color = when (slot) {
            0 -> 0xFF404040.toInt()
            1 -> 0xFFFF22AA.toInt()
            2 -> 0xFF00EEFF.toInt()
            3 -> 0xFF00CC00.toInt()
            else -> 0xFFCC1111.toInt()
        }
        val argb = color
        val px = (argb ushr 16) and 0xFF
        val py = (argb ushr 8) and 0xFF
        val pz = argb and 0xFF
        val packed = (0xFF shl 24) or (pz shl 16) or (py shl 8) or px
        println("KNF: solid fallback slot=$slot argb=" + ("#" + argb.toUInt().toString(16)))
        val bmp = Bitmap32(16, 16, IntArray(16 * 16) { packed }, false)
        return bmp.sliceWithSize(0, 0, 16, 16)
    }

    companion object {
        private val cache = HashMap<String, NoteStyle>()
        fun get(id: String): NoteStyle = cache.getOrPut(id) { NoteStyle(id) }

        private val knfJson = Json {
            ignoreUnknownKeys = true
            coerceInputValues = true
            explicitNulls = false
        }
    }
}
