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
     * FNF's per-asset pixel flag; `NoteStyle.buildJudgementSprite` uses it to
     * turn antialiasing off and to pick a stepped fade for the popup.
     */
    @SerialName("isPixel") val isPixel: Boolean = false,
    /** Frame rate for the skin's animations, `anim.frameRate ?? 24`. */
    @SerialName("frameRate") val frameRate: Int = 24,
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

    val splashEnabled: Boolean
        get() {
            val asset = active.data.assets["noteSplash"]
            val enabled = (asset?.data?.get("enabled") as? JsonPrimitive)?.content
            return when (enabled) {
                null -> asset?.enabled ?: true
                else -> enabled.toBooleanStrictOrNull() ?: true
            }
        }
    val splashAlpha: Double get() = active.data.assets["noteSplash"]?.alpha ?: 1.0
    val splashOffsets: List<Double>
        get() = active.data.assets["noteSplash"]?.offsets ?: listOf(0.0, 0.0)
    val splashScale: Float
        get() = active.data.assets["noteSplash"]?.scale ?: 1f
    val splashFrameRate: Int
        get() = ((active.data.assets["noteSplash"]?.data?.get("framerateDefault") as? JsonPrimitive)
            ?.content?.toIntOrNull()) ?: 24
    val splashFrameRateVariance: Int
        get() = ((active.data.assets["noteSplash"]?.data?.get("framerateVariance") as? JsonPrimitive)
            ?.content?.toIntOrNull()) ?: 2
    val splashBlendMode: String
        get() = (active.data.assets["noteSplash"]?.data?.get("blendMode") as? JsonPrimitive)
            ?.content ?: "normal"

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
     * One frame of a strumline animation, with the offset of the artwork
     * inside its logical frame.
     *
     * Flixel draws a frame's *trimmed* rectangle at the sprite's position plus
     * the frame's trim (`FlxFrame.offset` is `frameX`/`frameY`), so the artwork of
     * a padded frame sits off the sprite's origin. The receptor frames carry
     * 37/38 px of padding, which is what puts the arrow below the top of the
     * screen instead of being cut by it.
     */
    data class TrimmedFrame(
        val slice: AtlasSlice,
        val offsetX: Int,
        val offsetY: Int,
        /** The logical frame's size, which includes the trimmed-off margins. */
        val frameWidth: Int = 0,
        val frameHeight: Int = 0,
    )

    /**
     * Every frame of a strumline animation.
     *
     * FNF registers the receptors as animations, not single sprites:
     *
     * ```haxe
     * _data.assets.noteStrumline?.data?.leftPress?.toNamed('press'),
     * _data.assets.noteStrumline?.data?.leftConfirm?.toNamed('confirm'),
     * _data.assets.noteStrumline?.data?.leftConfirmHold?.toNamed('confirm-hold'),
     * ```
     *
     * and `pressLeft0` / `confirmLeft0` are four-frame sequences, so swapping a
     * single PNG loses the whole squish.
     */
    fun strumFrames(direction: Int, kind: String): List<TrimmedFrame> {
        val style = active
        val asset = style.data.assets["noteStrumline"] ?: return emptyList()
        val atlas = style.atlasFor(asset.assetPath) ?: return emptyList()
        val key = "${style.arrowKeys[direction]}$kind"
        val prefix = asset.assetBy(key) ?: key
        return atlas.framesByPrefix(prefix).map {
            TrimmedFrame(atlas.slice(it), it.frameX, it.frameY, it.frameWidth, it.frameHeight)
        }
    }

    /** `NoteStyle.getStrumlineScale()` - the receptor has its own scale. */
    val strumlineScale: Float
        get() = active.data.assets["noteStrumline"]?.scale ?: scale

    /** `NoteStyle.getStrumlineOffsets()` - applied to receptors, not note heads. */
    val strumlineOffsets: List<Double>
        get() = active.data.assets["noteStrumline"]?.offsets ?: listOf(0.0, 0.0)

    /**
     * Logical size of the first frame in the strumline atlas.
     *
     * FNF's `StrumlineNote.setup` runs `updateHitbox()` before any animation is
     * played, so the receptor sprite's `width`/`height` - and through them the
     * point Flixel's `centerOffsets` centres every animation frame on - are
     * locked to this frame. Without it every state change would jump.
     */
    fun strumlineBaseSize(): Pair<Int, Int>? {
        val style = active
        val asset = style.data.assets["noteStrumline"] ?: return null
        val atlas = style.atlasFor(asset.assetPath) ?: return null
        val frame = atlas.firstFrame() ?: return null
        return frame.logicalWidth to frame.logicalHeight
    }

    /** Frame rate of the strumline animations, `anim.frameRate ?? 24`. */
    val strumFrameRate: Double
        get() = (active.data.assets["noteStrumline"]?.frameRate ?: 24).toDouble()

    /**
     * Note splash animations, one list of frames per variant.
     *
     * FNF registers `note impact 1|2 <colour>0` as animations and plays them at
     * 24 +/- 2 fps, killing the sprite when the animation ends. Showing a single
     * frame instead leaves a big bright arrow parked on the lane.
     */
    fun splashVariants(direction: Int): List<List<TrimmedFrame>> {
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
            atlas.framesByPrefix(prefix).map { TrimmedFrame(atlas.slice(it), it.frameX, it.frameY, it.frameWidth, it.frameHeight) }
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
     * The hold-note sheet.
     *
     * `funkin` uses the raw 8-column strip `NOTE_hold_assets.png` (416x87), but
     * the `pixel` skin ships its own little sheet, `week6:weeb/pixelUI/arrowEndsNew`
     * (72x6), which is stretched by a scale of 6 instead.
     *
     * Either way the sheet is eight columns: for direction `d` the body is
     * column `d*2` and the end cap column `d*2+1`.
     */
    fun holdNoteSheet(): Bitmap? {
        val asset = active.data.assets["holdNote"] ?: return null
        return bitmapFor(asset.assetPath)
    }

    /** Width of the whole hold-note sheet, or null when it is missing. */
    fun holdNoteSheetWidth(): Int? = holdNoteSheet()?.width

    /** Height of the whole hold-note sheet, or null when it is missing. */
    fun holdNoteSheetHeight(): Int? = holdNoteSheet()?.height

    /** `NoteStyle.fetchHoldNoteScale` - the hold note has its own scale. */
    val holdNoteScale: Float
        get() = active.data.assets["holdNote"]?.scale ?: 1f

    /** `NoteStyle.getHoldNoteOffsets()` - baked into the trail's draw offset. */
    val holdNoteOffsets: List<Double>
        get() = active.data.assets["holdNote"]?.offsets ?: listOf(0.0, 0.0)

    /** `NoteStyle.isHoldNotePixel`. */
    val isHoldNotePixel: Boolean
        get() = active.data.assets["holdNote"]?.isPixel ?: false

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
        val bmp = holdNoteSheet() ?: return null
        return (bmp.width / 8).takeIf { it > 0 }
    }

    fun sustainSlices(direction: Int): Pair<AtlasSlice, AtlasSlice>? {
        sustainCache[direction]?.let { return it }
        val bmp = holdNoteSheet() ?: return null
        val segW = bmp.width / 8
        if (segW <= 0 || bmp.height <= 0) return null
        val d = direction.coerceIn(0, 3)
        val body = bmp.sliceWithSize(d * 2 * segW, 0, segW, bmp.height)
        val cap = bmp.sliceWithSize((d * 2 + 1) * segW, 0, segW, bmp.height)
        return (body to cap).also { sustainCache[d] = it }
    }

    // ======================================================================
    // judgements and combo digits
    // ======================================================================

    /**
     * One judgement popup: the texture, its scale, its pixel flag and the
     * per-style offsets.
     *
     * Mirrors `NoteStyle.buildJudgementSprite`:
     *
     * ```haxe
     * case "sick":
     *   var assetPath = buildJudgementSpritePath(rating);
     *   result.loadTexture(assetPath);
     *   result.scale.x = _data.assets.judgementSick?.scale ?? 1.0;
     * ```
     */
    data class JudgementAsset(
        val slice: AtlasSlice,
        val scale: Float,
        val isPixel: Boolean,
        val offsets: List<Double>,
    )

    private fun judgementAsset(name: String): JudgementAsset? {
        val style = active
        val asset = style.data.assets[name] ?: return null
        val bmp = style.bitmapFor(asset.assetPath) ?: return null
        return JudgementAsset(
            slice = bmp.sliceWithSize(0, 0, bmp.width, bmp.height),
            scale = asset.scale,
            isPixel = asset.isPixel,
            offsets = asset.offsets,
        )
    }

    /** The popup for a judgement name: `sick`, `good`, `bad` or `shit`. */
    fun judgement(name: String): JudgementAsset? = when (name) {
        "sick" -> judgementAsset("judgementSick")
        "good" -> judgementAsset("judgementGood")
        "bad" -> judgementAsset("judgementBad")
        "shit" -> judgementAsset("judgementShit")
        else -> null
    }

    /** One digit of the combo counter, `0` to `9`. */
    fun comboNumber(digit: Int): JudgementAsset? =
        if (digit in 0..9) judgementAsset("comboNumber$digit") else null

    /** True when the whole skin is pixel art, which also covers the notes. */
    val isPixel: Boolean
        get() = active.data.assets["note"]?.isPixel
            ?: active.data.assets.values.any { it.isPixel }

    /**
     * The three animations of a hold note's cover.
     *
     * FNF's `NoteHoldCover` is a glow sprite that plays `holdCoverStart<Color>`
     * when the note is hit, `holdCover<Color>` while it is held and
     * `holdCoverEnd<Color>` when it is released.
     */
    data class HoldCoverAsset(
        val start: List<TrimmedFrame>,
        val hold: List<TrimmedFrame>,
        val end: List<TrimmedFrame>,
        val offsets: List<Double>,
        val scale: Double,
        val isPixel: Boolean,
    )

    /** The hold cover of one direction, or null when the skin has none. */
    fun holdCover(direction: Int): HoldCoverAsset? {
        val style = active
        val asset = style.data.assets["holdNoteCover"] ?: return null
        val root = asset.data ?: return null
        if ((root["enabled"] as? kotlinx.serialization.json.JsonPrimitive)?.content == "false") return null
        val key = when (direction) {
            0 -> "left"
            1 -> "down"
            2 -> "up"
            else -> "right"
        }
        val dirData = root[key] as? kotlinx.serialization.json.JsonObject ?: return null
        val atlasPath = (dirData["assetPath"] as? kotlinx.serialization.json.JsonPrimitive)?.content
            ?: return null
        val atlas = style.atlasFor(atlasPath) ?: return null

        fun framesOf(name: String): List<TrimmedFrame> {
            val prefix = ((dirData[name] as? kotlinx.serialization.json.JsonObject)
                ?.get("prefix") as? kotlinx.serialization.json.JsonPrimitive)?.content ?: return emptyList()
            return atlas.framesByPrefix(prefix).map {
                TrimmedFrame(atlas.slice(it), it.frameX, it.frameY, it.frameWidth, it.frameHeight)
            }
        }

        return HoldCoverAsset(
            start = framesOf("start"),
            hold = framesOf("hold"),
            end = framesOf("end"),
            offsets = asset.offsets,
            scale = asset.scale.toDouble(),
            isPixel = asset.isPixel,
        )
    }

    /**
     * The countdown graphic for a step.
     *
     * `NoteStyle.buildCountdownSprite`:
     *
     * ```haxe
     * case TWO: result.loadTexture(assetPath); result.scale.x = _data.assets.countdownTwo?.scale ?? 1.0;
     * ```
     *
     * The steps are `three`, `two`, `one` and `go`; `funkin` ships no graphic
     * for `three`, so only the sound plays there.
     */
    fun countdown(step: String): JudgementAsset? {
        val name = when (step) {
            "three" -> "countdownThree"
            "two" -> "countdownTwo"
            "one" -> "countdownOne"
            "go" -> "countdownGo"
            else -> return null
        }
        val style = active
        val asset = style.data.assets[name] ?: return null
        if (asset.assetPath.isEmpty()) return null
        val bmp = style.bitmapFor(asset.assetPath) ?: return null
        return JudgementAsset(
            slice = bmp.sliceWithSize(0, 0, bmp.width, bmp.height),
            scale = asset.scale,
            isPixel = asset.isPixel,
            offsets = asset.offsets,
        )
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

    private fun atlasFor(assetPath: String): TextureAtlas? {
        atlases[assetPath]?.let { return it }
        val xml = KnfFiles.resolve(assetPath, ".xml") ?: return null
        val atlas = runCatching { TextureAtlas.load(xml) }
            .onFailure { println("KNF: bad atlas $xml: ${it.message}") }
            .getOrNull()
        if (atlas != null) atlases[assetPath] = atlas
        return atlas
    }

    private fun bitmapFor(assetPath: String): Bitmap? {
        bitmaps[assetPath]?.let { return it }
        val png = KnfFiles.resolve(assetPath, ".png") ?: return null
        val bmp = runCatching { KnfFiles.bitmap(png) }
            .onFailure { println("KNF: bitmap '$png' failed: $it") }
            .getOrNull()
        if (bmp != null) bitmaps[assetPath] = bmp
        return bmp
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
