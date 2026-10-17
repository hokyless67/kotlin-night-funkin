package nightfunkin.play

import korlibs.image.bitmap.Bitmap
import korlibs.image.bitmap.sliceWithSize
import korlibs.math.geom.Anchor2D
import korlibs.korge.view.Container
import korlibs.korge.view.Image
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import nightfunkin.core.AtlasSlice
import nightfunkin.core.KnfFiles
import nightfunkin.core.TextureAtlas
import korlibs.math.geom.degrees
import kotlin.math.atan2
import kotlin.math.hypot

// ===========================================================================
// data/characters/<id>.json
// ===========================================================================

@Serializable
data class CharacterAnimationDef(
    val name: String = "",
    val prefix: String = "",
    val frameIndices: List<Int>? = null,
    val looped: Boolean = false,
    val frameRate: Int? = null,
    val flipX: Boolean = false,
    val flipY: Boolean = false,
)

@Serializable
data class CharacterData(
    val version: String = "1.0.0",
    val name: String = "",
    val renderType: String = "animateatlas",
    val assetPath: String = "",
    val flipX: Boolean = false,
    val scale: Double? = null,
    /** How long a sing pose is held, in 16th-note steps (FNF's `singTime`). */
    val singTime: Double = 8.0,
    val offsets: List<Double> = listOf(0.0, 0.0),
    val cameraOffsets: List<Double> = listOf(0.0, 0.0),
    val danceEvery: Double = 1.0,
    val animations: List<CharacterAnimationDef> = emptyList(),
)

// ===========================================================================
// spritemap1.json — Adobe Animate "Better TA Extension" atlas
// ===========================================================================

@Serializable
private data class RawSprite(@SerialName("SPRITE") val sprite: RawSpriteData? = null)

@Serializable
private data class RawSpriteData(
    val name: String = "0",
    val x: Int = 0,
    val y: Int = 0,
    val w: Int = 0,
    val h: Int = 0,
    val rotated: Boolean = false,
)

@Serializable
private data class RawAtlas(@SerialName("SPRITES") val sprites: List<RawSprite> = emptyList())

/** The spritemap file wraps everything in an `ATLAS` object. */
@Serializable
private data class SpritemapRoot(@SerialName("ATLAS") val atlas: RawAtlas = RawAtlas())

private data class SpriteDef(
    val name: String,
    val slice: AtlasSlice,
    /** Logical (un-rotated) size, which is what the animation matrices apply to. */
    val width: Int,
    val height: Int,
    /** Rotated sheets store the sprite turned 90°, so undo that when drawing. */
    val turned: Boolean,
    /**
     * Where the sprite sits inside the sheet. Always 0 for Animate, where the
     * symbol matrices place everything; for a legacy sheet it is the frame's own
     * position (`x - frameX`, `y - frameY`), because a plain `FlxSprite` loaded
     * from the whole PNG draws each sub-texture in place.
     */
    val dx: Int = 0,
    val dy: Int = 0,
)

// ===========================================================================
// Animation.json — the symbol tree
// ===========================================================================

/**
 * A 2D affine transform `[a b c d tx ty]`, matching Adobe Animate's `MX` arrays
 * and Flixel's `FlxMatrix`.
 */
private data class Mat(
    val a: Double = 1.0,
    val b: Double = 0.0,
    val c: Double = 0.0,
    val d: Double = 1.0,
    val tx: Double = 0.0,
    val ty: Double = 0.0,
) {
    /** `parent ∘ child`, i.e. Flixel's `child.concat(parent)`. */
    fun under(parent: Mat) = Mat(
        a = parent.a * this.a + parent.c * this.b,
        b = parent.b * this.a + parent.d * this.b,
        c = parent.a * this.c + parent.c * this.d,
        d = parent.b * this.c + parent.d * this.d,
        tx = parent.a * this.tx + parent.c * this.ty + parent.tx,
        ty = parent.b * this.tx + parent.d * this.ty + parent.ty,
    )

    /** Applies this transform to a point. */
    fun xform(x: Double, y: Double) = a * x + c * y + tx to b * x + d * y + ty
}

private fun matOf(mx: List<Double>?): Mat {
    if (mx == null || mx.size < 6) return Mat()
    return Mat(
        a = mx[0], b = mx[1], c = mx[2], d = mx[3], tx = mx[4], ty = mx[5],
    )
}

/**
 * A leaf of the symbol tree: one sprite out of the atlas.
 *
 * FNF calls this an "atlas sprite instance" (`ASI`). Every sprite in a character
 * sheet is one of these; the limbs only look whole because their parent symbols
 * place them.
 */
@Serializable
private data class AnimAtlasInstance(
    /** Name of the sprite in `spritemap1.json`. */
    @SerialName("N") val name: String = "",
    @SerialName("MX") val matrix: List<Double>? = null,
)

/**
 * An instance of another symbol (`SI`), placed with its own matrix and advanced
 * from `FF` — the frame its own timeline starts on.
 */
@Serializable
private data class AnimSymbolInstance(
    @SerialName("SN") val symbolName: String = "",
    @SerialName("FF") val firstFrame: Int = 0,
    @SerialName("TRP") val translate: AnimVec = AnimVec(),
    /** `LP` (loop), `PO` (play once) or `SF` (single frame). */
    @SerialName("LP") val loop: String = "LP",
    @SerialName("MX") val matrix: List<Double>? = null,
)

@Serializable
private data class AnimVec(val x: Double = 0.0, val y: Double = 0.0)

@Serializable
private data class AnimEvent(
    @SerialName("SI") val instance: AnimSymbolInstance? = null,
    @SerialName("ASI") val atlas: AnimAtlasInstance? = null,
)

@Serializable
private data class AnimFrame(
    /** Frame label, only present on the layer that names the poses. */
    @SerialName("N") val name: String? = null,
    /** Index of the first timeline frame this keyframe covers. */
    @SerialName("I") val start: Int = 0,
    /** How many frames it covers. */
    @SerialName("DU") val duration: Int = 1,
    @SerialName("E") val events: List<AnimEvent> = emptyList(),
)

@Serializable
private data class AnimLayer(
    @SerialName("LN") val name: String = "",
    /** `Clp` marks a mask layer, which is never drawn itself. */
    @SerialName("LT") val layerType: String? = null,
    @SerialName("FR") val frames: List<AnimFrame> = emptyList(),
) {
    /**
     * `frameIndices[i]` is the keyframe shown at timeline frame `i`, built by
     * repeating each keyframe's index for its `DU` frames. Flixel precomputes
     * exactly this table.
     */
    val frameIndices: IntArray by lazy {
        buildList {
            frames.forEachIndexed { index, f -> repeat(f.duration.coerceAtLeast(1)) { add(index) } }
        }.toIntArray()
    }

    val frameCount: Int get() = frameIndices.size

    val isMask: Boolean get() = layerType == "Clp" || layerType == "Clipper"

    /** The keyframe active at [index], or null if the layer has ended. */
    fun keyframeAt(index: Int): AnimFrame? {
        if (index < 0 || index >= frameIndices.size) return null
        return frames.getOrNull(frameIndices[index])
    }
}

@Serializable
private data class AnimTimeline(@SerialName("L") val layers: List<AnimLayer> = emptyList()) {
    val frameCount: Int get() = layers.maxOfOrNull { it.frameCount } ?: 0
}

@Serializable
private data class AnimSymbol(@SerialName("TL") val timeline: AnimTimeline = AnimTimeline())

/** `SD.S` — the symbol dictionary, keyed by the symbol name `SI.SN` refers to. */
@Serializable
private data class AnimSymbolDefs(@SerialName("S") val symbols: List<AnimSymbolDef> = emptyList())

@Serializable
private data class AnimSymbolDef(
    @SerialName("SN") val name: String = "",
    @SerialName("TL") val timeline: AnimTimeline = AnimTimeline(),
)

@Serializable
private data class AnimMetadata(
    @SerialName("FRT") val frameRate: Double = 24.0,
    @SerialName("W") val width: Int = 0,
    @SerialName("H") val height: Int = 0,
)

@Serializable
private data class AnimRoot(
    @SerialName("AN") val animation: AnimSymbol = AnimSymbol(),
    @SerialName("SD") val symbolDefs: AnimSymbolDefs = AnimSymbolDefs(),
    @SerialName("MD") val metadata: AnimMetadata = AnimMetadata(),
)

// ===========================================================================

/** One atlas sprite to draw for a given timeline frame, already placed. */
private data class PosePart(
    val sprite: String,
    val matrix: Mat,
)

private class Pose(val parts: List<PosePart>)

private class Bounds {
    var minX = Double.MAX_VALUE
    var minY = Double.MAX_VALUE
    var maxX = -Double.MAX_VALUE
    var maxY = -Double.MAX_VALUE
    val empty: Boolean get() = minX > maxX
    val width: Double get() = if (empty) 0.0 else maxX - minX
    val height: Double get() = if (empty) 0.0 else maxY - minY

    fun add(x: Double, y: Double) {
        minX = minOf(minX, x); maxX = maxOf(maxX, x)
        minY = minOf(minY, y); maxY = maxOf(maxY, y)
    }
}

/**
 * A playable FNF character: an Adobe Animate texture atlas plus its timeline.
 *
 * Ported from FNF's `renderType: "animateatlas"` path, which in turn is
 * `flixel-animate` (`MaybeMaru/flixel-animate`). The important part is that the
 * JSON is a *tree*, not a flat sprite list:
 *
 * ```
 * AN.TL.L[*].FR[*].E[].SI   instance of a symbol, placed by MX, started at FF
 *   └─ SD.S[i].TL.L[*]...  that symbol's own timeline
 *        └─ ...E[].ASI     finally: one sprite out of spritemap1.json
 * ```
 *
 * A pose is therefore the union of every `ASI` reachable at that frame, each
 * transformed by the product of the `MX` matrices above it. Character limbs are
 * exactly those `ASI` sprites; nothing is pre-composed.
 */
class Character(val id: String) : Container() {
    val data: CharacterData = runCatching {
        knfJson.decodeFromString<CharacterData>(KnfFiles.text("preload/data/characters/$id.json"))
    }.getOrElse {
        println("KNF: no character data for '$id': $it")
        CharacterData(name = id)
    }

    /** `assetPath` with any `shared:` scheme stripped, e.g. `characters/spooky`. */
    private val rawPath: String = data.assetPath.substringAfter(':')

    /**
     * Animate pipeline folder, e.g. `shared/images/characters/bf`.
     *
     * Present for `renderType: animateatlas` / `multianimateatlas`.
     */
    private val animateDir: String? = findAsset("/spritemap1.json")?.removeSuffix("/spritemap1.json")

    /** Starling `<name>.xml` atlas, sitting *next to* the folder. */
    private val legacyXml: String? = if (animateDir != null) null else findAsset(".xml")

    /** spirit's `renderType: "packer"` frame list, also next to the folder. */
    private val legacyTxt: String? = if (animateDir != null || legacyXml != null) null else findAsset(".txt")

    /**
     * Resolves [suffix] appended to the character's `assetPath`, trying FNF's
     * image roots in order.
     *
     * The asset path is a *prefix*, not a directory: the Animate sheets are in a
     * folder called after it, but the legacy atlases are `<prefix>.xml` and
     * `<prefix>.png` files sitting right beside that folder.
     */
    private fun findAsset(suffix: String): String? =
        ASSET_ROOTS.firstNotNullOfOrNull { root ->
            val path = "$root/$rawPath$suffix".removePrefix("/")
            if (KnfFiles.exists(path)) path else null
        }

    /** Every path this character could have used, for the "nothing loaded" log. */
    private fun triedPaths(): String = ASSET_ROOTS.joinToString(", ") { root ->
        "$root/$rawPath".removePrefix("/")
    }

    private val anim: AnimRoot? = animateDir?.let { dir ->
        runCatching {
            knfJson.decodeFromString<AnimRoot>(KnfFiles.textOrNull("$dir/Animation.json") ?: "{}")
        }.getOrNull()
    }

    /**
     * Legacy atlas for every character that predates the Animate pipeline.
     *
     * `renderType` says `animateatlas` for most of them, but senpai, spooky,
     * spooky-dark, gf-pixel, nene-pixel, pico-pixel, gf-tankmen, bf-pixel,
     * bf-christmas and bf-holding-gf all ship a Starling `<name>.xml` atlas
     * instead, and spirit ships a `renderType: "packer"` text list. Both live
     * *next to* the character folder rather than inside it, and have no
     * timeline: the animation is just a name prefix matched against the frames.
     */
    private val legacyAtlas: TextureAtlas? = if (animateDir != null) null else {
        legacyXml?.let { TextureAtlas.tryLoad(it) } ?: legacyTxt?.let { TextureAtlas.tryLoadPacker(it) }
    }

    private val sprites: Map<String, SpriteDef> =
        if (animateDir != null) loadSprites(animateDir) else loadLegacySprites(legacyAtlas)

    private val symbolByName: Map<String, AnimTimeline> =
        anim?.symbolDefs?.symbols.orEmpty().associate { it.name to it.timeline }

    /** All poses, indexed by timeline frame of the main symbol (or atlas frame). */
    private val poses: List<Pose> =
        if (legacyAtlas != null) legacyPoses()
        else runCatching { loadPoses() }.getOrElse {
            println("KNF: character '$id' timeline failed: $it"); emptyList()
        }

    private val anims: Map<String, List<Int>> = runCatching { loadAnimations() }.getOrElse { emptyMap() }

    /**
     * Legacy sheets have no timeline, so the bounds come from a single frame
     * instead: `SparrowCharacter` and `PackerCharacter` are plain `FlxSprite`s
     * carrying a frame collection, which makes `width`/`height` follow whatever
     * frame is current - and `Stage.addCharacter` calls `resetCharacter(true)`
     * (forced `dance()`, then `updateHitbox()`) right before measuring, so that
     * is the idle animation's first frame.
     */
    private val bounds: Bounds = legacyAtlas?.let { atlas ->
        val order = sprites.keys.toList()
        var index = anims["idle"]?.firstOrNull() ?: -1
        if (index < 0) index = anims["danceLeft"]?.firstOrNull() ?: -1
        if (index < 0) index = anims.values.firstOrNull()?.firstOrNull() ?: -1
        if (index < 0) index = 0
        val frame = order.getOrNull(index)?.let { atlas.frame(it) }
        if (frame == null) {
            Bounds().apply {
                add(0.0, 0.0)
                add(atlas.bitmap.width.toDouble(), atlas.bitmap.height.toDouble())
            }
        } else {
            Bounds().apply {
                // The logical frame, trimmed rect at (-frameX, -frameY) inside it.
                add((-frame.frameX).toDouble(), (-frame.frameY).toDouble())
                add(
                    (frame.logicalWidth - frame.frameX).toDouble(),
                    (frame.logicalHeight - frame.frameY).toDouble(),
                )
            }
        }
    } ?: computeBounds()

    /** Undo the authoring origin, so local (0,0) is the character's top-left. */
    val originX: Double get() = bounds.minX
    val originY: Double get() = bounds.minY
    val localWidth: Double get() = bounds.width
    val localHeight: Double get() = bounds.height

    val displayName: String get() = data.name.ifEmpty { id }

    /** `BaseCharacter.getDataFlipX()` - the raw flag from `characters/<id>.json`. */
    val dataFlipX: Boolean get() = data.flipX

    /**
     * `BaseCharacter.flipX` as actually used on stage, which is *not* the file's
     * value for the player. `Stage.addCharacter`:
     *
     * ```haxe
     * case BF:      character.flipX = !character.getDataFlipX();
     * case GF:      character.flipX =  character.getDataFlipX();
     * case DAD:     character.flipX =  character.getDataFlipX();
     * ```
     *
     * `bf.json` ships `"flipX": true`, so the player is drawn *unmirrored* while
     * every other slot honours its own flag. See [flipForSlot].
     */
    var flip: Boolean = data.flipX

    /** FNF's `getBaseScale()`; `characters/<id>.json` defaults to 1. */
    val baseScale: Double get() = data.scale ?: 1.0

    private val images = ArrayList<Image>()
    private var currentAnim: String? = null
    private var frameTime = 0.0
    private var frameIndex = 0

    private val frameRate: Double = anim?.metadata?.frameRate ?: 24.0

    private var singTimer = 0.0
    private var dancing = true
    private var danceFlipped = false

    init {
        if (sprites.isEmpty()) {
            println("KNF: character '$id' has no sprites")
            println("KNF:   assetPath='${data.assetPath}' renderType='${data.renderType}'")
            println("KNF:   looked for " + triedPaths() + "{/Animation.json,.xml,.txt}")
        }
        if (System.getenv("KNF_DEBUG_CHAR") == "1") {
            println(
                "KNF: char $id sprites=${sprites.size} poses=${poses.size} " +
                    "bounds=[${bounds.minX.toInt()},${bounds.minY.toInt()} " +
                    "${bounds.width.toInt()}x${bounds.height.toInt()}] anims=${anims.keys}"
            )
            println("KNF: char $id pose0 parts=" + (poses.firstOrNull()?.parts?.size) + " idle=" + anims.containsKey("idle"))
            dumpParts()
        }
    }

    /** Debug helper: prints the parts of the first frame of every animation. */
    private fun dumpParts() {
        for ((name, frames) in anims) {
            println("KNF: char $id anim $name = [" + frames.joinToString(",") + "]")
        }
        for ((name, frames) in anims) {
            val pose = poses.getOrNull(frames.firstOrNull() ?: continue) ?: continue
            val list = pose.parts.mapNotNull { p ->
                val d = sprites[p.sprite] ?: return@mapNotNull "MISSING:" + p.sprite
                val c = p.matrix.xform(d.width / 2.0, d.height / 2.0)
                p.sprite + "(" + d.width + "x" + d.height +
                    (if (p.matrix.a * p.matrix.d - p.matrix.c * p.matrix.b < 0.0) " MIRROR" else "") +
                    " m=[" + p.matrix.a + "," + p.matrix.b + "," + p.matrix.c + "," + p.matrix.d +
                    "] t=" + p.matrix.tx.toInt() + "," + p.matrix.ty.toInt() + ")"
            }
            println("KNF: char " + id + " anim " + name + " frames=" + frames.size + " parts=" + pose.parts.size)
            println("KNF: char " + id + "   " + list.joinToString(" "))
        }
    }

    // ======================================================================
    // loading
    // ======================================================================

    private fun loadSprites(dir: String): Map<String, SpriteDef> {
        val json = KnfFiles.textOrNull("$dir/spritemap1.json") ?: return emptyMap()
        val bitmap: Bitmap = KnfFiles.bitmapOrNull("$dir/spritemap1.png") ?: return emptyMap()

        val raw = runCatching { knfJson.decodeFromString<SpritemapRoot>(json) }
            .getOrElse {
                println("KNF: character '$id' spritemap parse failed: $it")
                return emptyMap()
            }.atlas

        val out = LinkedHashMap<String, SpriteDef>()
        for (entry in raw.sprites) {
            val s = entry.sprite ?: continue
            if (s.w <= 0 || s.h <= 0) continue
            // Better TA stores rotated sprites turned 90° clockwise in the sheet.
            if (s.rotated) {
                out[s.name] = SpriteDef(
                    name = s.name,
                    slice = bitmap.sliceWithSize(s.x, s.y, s.h, s.w),
                    width = s.h,
                    height = s.w,
                    turned = true,
                )
            } else {
                out[s.name] = SpriteDef(
                    name = s.name,
                    slice = bitmap.sliceWithSize(s.x, s.y, s.w, s.h),
                    width = s.w,
                    height = s.h,
                    turned = false,
                )
            }
        }
        return out
    }

    /**
     * Legacy sheet: one [SpriteDef] per atlas frame. A rotated frame has already
     * been turned upright by [TextureAtlas], so `trimmedWidth`/`trimmedHeight`
     * are the size that gets drawn and `turned` stays false.
     *
     * `dx`/`dy` put the trimmed rect where the logical frame puts it. Flixel
     * bakes that in as `FlxFrame.offset`, which is how a sprite keeps one origin
     * across a whole animation even though the sheet trims each frame
     * differently.
     */
    private fun loadLegacySprites(atlas: TextureAtlas?): Map<String, SpriteDef> {
        if (atlas == null) return emptyMap()
        val out = LinkedHashMap<String, SpriteDef>()
        for (f in atlas.frames.values) {
            out[f.name] = SpriteDef(
                name = f.name,
                slice = atlas.slice(f),
                width = f.trimmedWidth,
                height = f.trimmedHeight,
                turned = false,
                dx = -f.frameX,
                dy = -f.frameY,
            )
        }
        return out
    }

    /**
     * One pose per atlas frame: a single sprite translated to where its trimmed
     * rect sits inside the logical frame, which is what Flixel does when it plays
     * a sub-texture with one shared origin.
     */
    private fun legacyPoses(): List<Pose> = sprites.values.map { def ->
        Pose(listOf(PosePart(def.name, Mat(1.0, 0.0, 0.0, 1.0, def.dx.toDouble(), def.dy.toDouble()))))
    }

    /**
     * Flattens the symbol tree into one [Pose] per frame of the main timeline.
     *
     * Mirrors `flixel-animate`: layers are drawn back-to-front, each keyframe's
     * events in order, symbol instances recursing into their own timeline at
     * `FF + (frameIndex - keyframeStart)`.
     */
    private fun loadPoses(): List<Pose> {
        val timeline = anim?.animation?.timeline ?: return emptyList()
        val total = timeline.frameCount
        if (total <= 0) return emptyList()

        val out = ArrayList<Pose>(total)
        for (index in 0 until total) {
            val parts = ArrayList<PosePart>()
            collect(timeline, index, Mat(), parts, 0)
            out += Pose(parts)
        }
        return out
    }

    private fun collect(
        timeline: AnimTimeline,
        frameIndex: Int,
        parent: Mat,
        out: MutableList<PosePart>,
        depth: Int,
    ) {
        if (depth > MAX_SYMBOL_DEPTH) return
        // Flixel skips a timeline entirely once its frame counter runs past the end.
        if (frameIndex < 0 || frameIndex >= timeline.frameCount) return

        // `Timeline.draw` walks layers from the last index down to the first.
        for (li in timeline.layers.indices.reversed()) {
            val layer = timeline.layers[li]
            if (layer.isMask) continue
            val keyframe = layer.keyframeAt(frameIndex) ?: continue

            for (event in keyframe.events) {
                val asi = event.atlas
                if (asi != null) {
                    val def = sprites[asi.name] ?: continue
                    out += PosePart(asi.name, matOf(asi.matrix).under(parent))
                    continue
                }
                val si = event.instance ?: continue
                val child = symbolByName[si.symbolName] ?: continue
                val childFrame = resolveSymbolFrame(si, frameIndex, keyframe.start, child)
                collect(child, childFrame, matOf(si.matrix).under(parent), out, depth + 1)
            }
        }
    }

    /**
     * `SymbolInstance.getFrameIndex`: the instance's own timeline starts at `FF`
     * and advances with the parent's frame counter, clamped or wrapped by `LP`.
     */
    private fun resolveSymbolFrame(
        si: AnimSymbolInstance,
        frameIndex: Int,
        keyframeStart: Int,
        child: AnimTimeline,
    ): Int {
        val first = si.firstFrame
        val last = child.frameCount - 1
        if (last < 0) return 0
        val raw = first + (frameIndex - keyframeStart)
        return when (si.loop) {
            "PO", "playonce" -> raw.coerceAtMost(last)
            "SF", "singleframe" -> first
            else -> ((raw % (last + 1)) + (last + 1)) % (last + 1)
        }
    }

    /**
     * Union of every sprite in every pose — FNF's `Timeline._bounds`.
     *
     * All four corners are transformed, because a limb that is rotated or scaled
     * covers more than a plain `centre +/- half size` box; using the box instead
     * would clip characters whose sing poses swing further than their idle ones.
     */
    private fun computeBounds(): Bounds {
        val b = Bounds()
        for (pose in poses) {
            for (part in pose.parts) {
                val def = sprites[part.sprite] ?: continue
                val w = def.width.toDouble()
                val h = def.height.toDouble()
                for (corner in CORNERS) {
                    val (x, y) = part.matrix.xform(corner[0] * w, corner[1] * h)
                    b.add(x, y)
                }
            }
        }
        return b
    }

    /**
     * Every main-timeline frame carrying [prefix], expanded over each keyframe's
     * duration.
     *
     * Mirrors flixel-animate's `findFrameLabelIndices`: layers are walked in
     * order and the scan stops at the *first layer that has a match*, so a label
     * that only exists on layer 1 is still found. Only the trailing whitespace
     * of a label is stripped (`frame.name.rtrim()`).
     */
    private fun labelFrames(prefix: String): List<Int> {
        val timeline = anim?.animation?.timeline ?: return emptyList()
        for (layer in timeline.layers) {
            val out = ArrayList<Int>()
            for (frame in layer.frames) {
                if (frame.name?.trimEnd() != prefix) continue
                for (i in 0 until frame.duration) {
                    val index = frame.start + i
                    if (index in poses.indices) out += index
                }
            }
            if (out.isNotEmpty()) return out
        }
        return emptyList()
    }

    /**
     * Animation name -> the main-timeline frames it plays.
     *
     * FNF's `addByFrameLabel` takes every frame whose label matches the prefix,
     * expanded over the keyframe's duration.
     *
     * `frameIndices` is **not** a list of absolute timeline frames - FNF calls
     * `addByFrameLabelIndices`, which indexes *into the labelled run*:
     *
     * ```haxe
     * var foundFrames = findFrameLabelIndices(label, timeline);
     * for (index in indices) {
     *     var frameIndex:Null<Int> = foundFrames[index];
     *     if (frameIndex != null) useableFrames.push(frameIndex);
     * }
     * ```
     *
     * Anything past the end of the label resolves to null and is dropped. That
     * matters: `dad.json`'s `idle-hold` is `[11, 12, 0, 1]` against a twelve
     * frame `Idle` run, so frame `12` never plays - reading it as an absolute
     * index would show `singUP`'s first frame for a single frame of dad's idle.
     */
    private fun loadAnimations(): Map<String, List<Int>> {
        val legacy = legacyAtlas != null
        if (!legacy && anim?.animation?.timeline == null) return emptyMap()
        val out = LinkedHashMap<String, List<Int>>()

        for (def in data.animations) {
            val frames = if (legacy) {
                legacyAnimationFrames(def)
            } else if (!def.frameIndices.isNullOrEmpty()) {
                val found = labelFrames(def.prefix)
                def.frameIndices.mapNotNull { found.getOrNull(it) }
            } else {
                labelFrames(def.prefix)
            }
            if (frames.isNotEmpty()) out[def.name] = frames
        }

        if (out.isEmpty()) out["idle"] = poses.indices.toList()
        return out
    }

    /**
     * Legacy sheet animations, resolved the way Flixel does it on a plain
     * `FlxSprite` loaded from the whole PNG.
     *
     * With no prefix list to index into, `frameIndices` are absolute positions in
     * the sheet's frame collection, so `spooky`'s `danceRight` `[8..15]` picks the
     * second half of its sixteen `spooky dance idle0` frames. Without them the
     * prefix match runs over every frame name in sheet order, which is the same
     * order the exporter wrote them in.
     */
    private fun legacyAnimationFrames(def: CharacterAnimationDef): List<Int> {
        val order = sprites.keys.toList()
        if (def.frameIndices.isNullOrEmpty()) {
            return order.indices.filter { order[it].startsWith(def.prefix) }
        }
        return def.frameIndices.filter { it in order.indices }
    }

    // ======================================================================
    // playback
    // ======================================================================

    /** FNF's `BaseCharacter.isSinging`. */
    val isSinging: Boolean
        get() = currentAnim?.startsWith("sing") == true

    /**
     * FNF's `BaseCharacter.dance()`.
     *
     * Alternates `danceLeft`/`danceRight` when the sheet has them, and — crucially
     * — bails out while a sing pose is playing. Without that guard the per-beat
     * dance call chops every sing pose short and characters flicker between
     * singing and idling.
     */
    fun dance(forceRestart: Boolean = false) {
        if (!forceRestart && isSinging) return
        if (anims.containsKey("danceLeft") && anims.containsKey("danceRight")) {
            dancing = true
            play(if (danceFlipped) "danceRight" else "danceLeft", forceRestart)
            danceFlipped = !danceFlipped
        } else {
            dancing = true
            play("idle", forceRestart)
        }
    }

    /**
     * Holds a sing pose for FNF's `singTime`, which is counted in 16th-note
     * steps rather than seconds (8 steps = two beats at any tempo).
     */
    fun sing(direction: Int, stepLengthMs: Double) {
        val anim = when (direction) {
            0 -> "singLEFT"
            1 -> "singDOWN"
            2 -> "singUP"
            3 -> "singRIGHT"
            else -> return
        }
        if (!anims.containsKey(anim)) return
        dancing = false
        play(anim, forceRestart = true)
        singTimer = data.singTime * stepLengthMs / 1000.0
    }

    fun play(anim: String, forceRestart: Boolean = false) {
        if (anim == currentAnim && !forceRestart) return
        if (!anims.containsKey(anim)) return
        if (System.getenv("KNF_DEBUG_CHAR") == "1") {
            val fr = anims[anim]?.firstOrNull()
            println("KNF: char $id -> $anim  poseParts=" + (fr?.let { poses.getOrNull(it)?.parts?.size }))
        }
        currentAnim = anim
        frameTime = 0.0
        frameIndex = 0
        renderPose()
    }

    fun update(dt: Double) {
        if (singTimer > 0.0) {
            singTimer -= dt
            if (singTimer <= 0.0) {
                singTimer = 0.0
                dance(forceRestart = true)
            }
        }

        val frames = currentAnim?.let { anims[it] } ?: return
        if (frames.isEmpty()) return

        frameTime += dt
        val step = 1.0 / frameRate
        while (frameTime >= step) {
            frameTime -= step
            frameIndex = (frameIndex + 1) % frames.size
            // FNF hands over to `<anim>-hold` once a pose runs out, so dad keeps
            // his eyes flickering while idle and Darnell keeps his lighter lit.
            // `BaseCharacter.update` documents this as working for sing poses
            // too, and `singUP-hold` is authored as the settle frames at the end
            // of the UP pose. The sing timer still owns the return to idle.
            val hold = holdAnimFor(frames)
            if (hold != null && frameIndex == 0) play(hold, forceRestart = true) else renderPose()
        }
    }

    private fun holdAnimFor(frames: List<Int>): String? {
        val name = currentAnim ?: return null
        val hold = "$name$ANIMATION_HOLD_SUFFIX"
        if (name.endsWith(ANIMATION_HOLD_SUFFIX)) return null
        // Only worth switching if the pose actually stops part-way through.
        if (frames.size <= 1) return null
        return if (anims.containsKey(hold)) hold else null
    }

    private fun renderPose() {
        val frames = currentAnim?.let { anims[it] } ?: return
        if (frames.isEmpty()) return
        val pose = poses.getOrNull(frames[frameIndex % frames.size]) ?: return

        while (images.size < pose.parts.size) {
            val img = Image(EMPTY_BITMAP, Anchor2D.CENTER)
            images += img
            addChild(img)
        }
        for (i in images.indices) {
            val img = images[i]
            val part = pose.parts.getOrNull(i)
            val def = part?.let { sprites[it.sprite] }
            if (part == null || def == null) {
                img.visible = false
                continue
            }
            img.visible = true
            img.bitmap = def.slice

            // Anchor at the sprite's centre so scale and rotation compose the
            // same way the Animate matrix does.
            //
            // The bounds offset is what FNF's `drawAnimate` applies with
            // `matrix.translate(-bounds.x, -bounds.y)`: it moves the authoring
            // origin to the sprite's own top-left. Without it every character is
            // drawn shifted by its bounding-box corner.
            val (cx, cy) = part.matrix.xform(def.width / 2.0, def.height / 2.0)
            img.x = cx - originX
            img.y = cy - originY

            // KGE builds `translation * rotation * scale`, i.e. `R * S`, whose
            // first column is `sx * (cos, sin)` and second is
            // `sy * (-sin, cos)`. Both `hypot` lengths are positive by
            // construction, which throws away the *sign* of the determinant -
            // and Animate mirrors plenty of symbols. A mirrored limb then gets
            // drawn rotated by 180 degrees instead of flipped.
            //
            // Tankman's sing poses are the clearest case: `Tankman DOWN note`
            // places its legs with `MX = [-1, 0, 0, 1]`, which decomposes to
            // `scale 1, rotation 180` and puts his shorts at his ankles.
            //
            // Negating one axis restores the determinant's sign: `R * S(sx, -sy)`
            // has second column `sy * (sin, -cos)`, which is exactly what a
            // negatively-oriented matrix carries.
            val m = part.matrix
            img.scaleX = hypot(m.a, m.b)
            img.scaleY = hypot(m.c, m.d)
            if (m.a * m.d - m.c * m.b < 0.0) img.scaleY = -img.scaleY

            val degrees = (if (def.turned) -90.0 else 0.0) +
                Math.toDegrees(atan2(m.b, m.a))
            img.rotation = degrees.degrees
        }
    }

    // ======================================================================
    // stage placement
    // ======================================================================

    /**
     * Places the character so its feet land on ([feetX], [feetY]), matching
     * `Stage.addCharacter`:
     *
     * ```
     * x = feetX - width/2 + offsets[0]
     * y = feetY - height + offsets[1]
     * ```
     *
     * where `width`/`height` are the timeline bounds times the scale.
     */
    fun placeFeet(feetX: Double, feetY: Double, scale: Double) {
        val s = scale
        this.scaleX = s
        this.scaleY = s
        val x = feetX - localWidth * s / 2.0 + data.offsets.getOrElse(0) { 0.0 }
        val y = feetY - localHeight * s + data.offsets.getOrElse(1) { 0.0 }
        // A mirrored sheet is drawn around its own right edge, so the origin
        // moves to `x + width` and `scaleX` goes negative - same shape as
        // Flixel's `matrix.scale(-1, 1); matrix.translate(bounds.width, 0)`.
        // See [flip] for why the player is usually *not* mirrored.
        if (flip) {
            this.x = x + localWidth * s
            this.scaleX = -s
        } else {
            this.x = x
        }
        this.y = y
    }

    /**
     * Where the camera should look, per `BaseCharacter.resetCameraFocusPoint`.
     *
     * Note it is the character's **middle**, not its feet, and both the
     * character's own `cameraOffsets` and the stage's are added on top:
     *
     * ```
     * x = originalPosition.x + width/2  + data.cameraOffsets[0] + stage.cameraOffsets[0]
     * y = originalPosition.y + height/2 + data.cameraOffsets[1] + stage.cameraOffsets[1]
     * ```
     */
    fun cameraFocusPoint(stageCameraOffsets: List<Double>): Pair<Double, Double> {
        // `scaleX` is negative when the sheet is flipped (bf is), so the centre
        // has to follow the sign — otherwise the focus lands a whole width to
        // the wrong side and the camera flies off after a FocusCamera event.
        val cx = x + localWidth * scaleX / 2.0 + data.cameraOffsets.getOrElse(0) { 0.0 }
        val cy = y + localHeight * scaleY / 2.0 + data.cameraOffsets.getOrElse(1) { 0.0 }
        return (cx + stageCameraOffsets.getOrElse(0) { 0.0 }) to
            (cy + stageCameraOffsets.getOrElse(1) { 0.0 })
    }

    fun destroy() = removeFromParent()

    companion object {
        /** FNF's `Constants.ANIMATION_HOLD_SUFFIX`. */
        const val ANIMATION_HOLD_SUFFIX = "-hold"

        /**
         * Which way a sheet ends up facing once it is on a stage slot.
         *
         * `Stage.addCharacter` inverts the file's `flipX` for the player slot
         * only - see the [flip] docs.
         */
        fun flipForSlot(slot: String, dataFlipX: Boolean): Boolean =
            if (slot == "bf") !dataFlipX else dataFlipX

        /** Unit corners of a sprite's logical rectangle, for bounding boxes. */
        private val CORNERS = arrayOf(doubleArrayOf(0.0, 0.0), doubleArrayOf(1.0, 0.0), doubleArrayOf(0.0, 1.0), doubleArrayOf(1.0, 1.0))

        /** Guards against a malformed file with a cyclic symbol reference. */
        private const val MAX_SYMBOL_DEPTH = 24

        /**
         * Stand-in bitmap so an `Image` can be created before its pose is known.
         *
         * A fully opaque pixel: a transparent one makes KorGE warn about a
         * non-premultiplied upload the first time the placeholder is bound.
         */
        private val EMPTY_BITMAP =
            korlibs.image.bitmap.Bitmap32(1, 1, intArrayOf(0xFF000000.toInt()), true)

        private val cache = HashMap<String, Character>()
        fun get(id: String): Character = cache.getOrPut(id) { Character(id) }

        /**
         * FNF's image roots, in lookup order.
         *
         * Weekly and shared content lives under `shared/images`, the base game
         * leftovers under `preload/images`; the bare path is the last resort for
         * assets dropped straight into the asset root.
         */
        private val ASSET_ROOTS = listOf("shared/images", "preload/images", "")

        private val knfJson = Json {
            ignoreUnknownKeys = true
            isLenient = true
            coerceInputValues = true
            explicitNulls = false
        }
    }
}