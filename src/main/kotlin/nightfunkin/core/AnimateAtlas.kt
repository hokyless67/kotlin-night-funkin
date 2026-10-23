package nightfunkin.core

import korlibs.image.bitmap.Bitmap
import korlibs.image.bitmap.sliceWithSize
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
@Serializable
data class RawSprite(@SerialName("SPRITE") val sprite: RawSpriteData? = null)

@Serializable
data class RawSpriteData(
    val name: String = "0",
    val x: Int = 0,
    val y: Int = 0,
    val w: Int = 0,
    val h: Int = 0,
    val rotated: Boolean = false,
)

@Serializable
data class RawAtlas(@SerialName("SPRITES") val sprites: List<RawSprite> = emptyList())

/** The spritemap file wraps everything in an `ATLAS` object. */
@Serializable
data class SpritemapRoot(@SerialName("ATLAS") val atlas: RawAtlas = RawAtlas())

data class SpriteDef(
    val name: String,
    val slice: AtlasSlice,
    /** Logical (un-rotated) size, which is what the animation matrices apply to. */
    val width: Int,
    val height: Int,
    /** Rotated sheets store the sprite turned 90В°, so undo that when drawing. */
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
// Animation.json вЂ” the symbol tree
// ===========================================================================

/**
 * A 2D affine transform `[a b c d tx ty]`, matching Adobe Animate's `MX` arrays
 * and Flixel's `FlxMatrix`.
 */
data class Mat(
    val a: Double = 1.0,
    val b: Double = 0.0,
    val c: Double = 0.0,
    val d: Double = 1.0,
    val tx: Double = 0.0,
    val ty: Double = 0.0,
) {
    /** `parent в€ child`, i.e. Flixel's `child.concat(parent)`. */
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

/**
 * Resolves an element transform the way flixel-animate's `MatrixJson.resolve`
 * does: prefer the 2D `MX` array, otherwise flatten the 16-value `M3D` array.
 *
 * BetterTextureAtlas exports most Animate symbol instances with `M3D` instead
 * of `MX`; ignoring it collapses the whole character onto the stage origin.
 */
private fun matOf(mx: List<Double>?, m3d: List<Double>? = null): Mat {
    if (mx != null && mx.size >= 6) {
        return Mat(
            a = mx[0], b = mx[1], c = mx[2], d = mx[3], tx = mx[4], ty = mx[5],
        )
    }
    if (m3d == null || m3d.size < 16) return Mat()

    // No perspective: keep the 2D components directly.
    val hasPerspective = m3d[3] != 0.0 || m3d[7] != 0.0 || m3d[11] != 0.0 || m3d[15] != 1.0
    if (!hasPerspective) {
        return Mat(a = m3d[0], b = m3d[1], c = m3d[4], d = m3d[5], tx = m3d[12], ty = m3d[13])
    }

    fun project(x: Double, y: Double): Pair<Double, Double> {
        val z = m3d[3] * x + m3d[7] * y + m3d[15]
        return (m3d[0] * x + m3d[4] * y + m3d[12] / z) to
            (m3d[1] * x + m3d[5] * y + m3d[13] / z)
    }
    val (p0x, p0y) = project(0.0, 0.0)
    val (p1x, p1y) = project(1.0, 0.0)
    val (p2x, p2y) = project(0.0, 1.0)
    return Mat(
        a = p1x - p0x, b = p1y - p0y,
        c = p2x - p0x, d = p2y - p0y,
        tx = p0x, ty = p0y,
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
data class AnimAtlasInstance(
    /** Name of the sprite in `spritemap1.json`. */
    @SerialName("N") val name: String = "",
    @SerialName("MX") val matrix: List<Double>? = null,
    @SerialName("M3D") val matrix3D: List<Double>? = null,
)

/**
 * An instance of another symbol (`SI`), placed with its own matrix and advanced
 * from `FF` вЂ” the frame its own timeline starts on.
 */
@Serializable
data class AnimSymbolInstance(
    @SerialName("SN") val symbolName: String = "",
    @SerialName("FF") val firstFrame: Int = 0,
    @SerialName("TRP") val translate: AnimVec = AnimVec(),
    /** `LP` (loop), `PO` (play once) or `SF` (single frame). */
    @SerialName("LP") val loop: String = "LP",
    @SerialName("MX") val matrix: List<Double>? = null,
    @SerialName("M3D") val matrix3D: List<Double>? = null,
)

@Serializable
data class AnimVec(val x: Double = 0.0, val y: Double = 0.0)

@Serializable
data class AnimEvent(
    @SerialName("SI") val instance: AnimSymbolInstance? = null,
    @SerialName("ASI") val atlas: AnimAtlasInstance? = null,
)

@Serializable
data class AnimFrame(
    /** Frame label, only present on the layer that names the poses. */
    @SerialName("N") val name: String? = null,
    /** Index of the first timeline frame this keyframe covers. */
    @SerialName("I") val start: Int = 0,
    /** How many frames it covers. */
    @SerialName("DU") val duration: Int = 1,
    @SerialName("E") val events: List<AnimEvent> = emptyList(),
)

@Serializable
data class AnimLayer(
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
data class AnimTimeline(@SerialName("L") val layers: List<AnimLayer> = emptyList()) {
    val frameCount: Int get() = layers.maxOfOrNull { it.frameCount } ?: 0
}

@Serializable
data class AnimSymbol(@SerialName("TL") val timeline: AnimTimeline = AnimTimeline())

/** `SD.S` вЂ” the symbol dictionary, keyed by the symbol name `SI.SN` refers to. */
@Serializable
data class AnimSymbolDefs(@SerialName("S") val symbols: List<AnimSymbolDef> = emptyList())

@Serializable
data class AnimSymbolDef(
    @SerialName("SN") val name: String = "",
    @SerialName("TL") val timeline: AnimTimeline = AnimTimeline(),
)

@Serializable
data class AnimMetadata(
    @SerialName("FRT") val frameRate: Double = 24.0,
    @SerialName("W") val width: Int = 0,
    @SerialName("H") val height: Int = 0,
)

@Serializable
data class AnimRoot(
    @SerialName("AN") val animation: AnimSymbol = AnimSymbol(),
    @SerialName("SD") val symbolDefs: AnimSymbolDefs = AnimSymbolDefs(),
    @SerialName("MD") val metadata: AnimMetadata = AnimMetadata(),
)

// ===========================================================================

/** One atlas sprite to draw for a given timeline frame, already placed. */
data class PosePart(
    val sprite: String,
    val matrix: Mat,
)

class Pose(val parts: List<PosePart>)

class Bounds {
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
 * An Adobe Animate texture atlas plus its timeline.
 *
 * The JSON is a *tree*, not a flat sprite list:
 *
 * ```
 * AN.TL.L[*].FR[*].E[].SI   instance of a symbol, placed by MX/M3D, started at FF
 *   +- SD.S[i].TL.L[*]...  that symbol's own timeline
 *        +- ...E[].ASI     finally: one sprite out of spritemap1.json
 * ```
 *
 * A pose is therefore the union of every `ASI` reachable at that frame, each
 * transformed by the product of the `MX` matrices above it.
 */
class AnimateAtlas(
    /** The folder holding `Animation.json` and `spritemap1.png`. */
    val dir: String,
) {
    private val anim: AnimRoot? = runCatching {
        knfJson.decodeFromString<AnimRoot>(KnfFiles.textOrNull("$dir/Animation.json") ?: "{}")
    }.getOrNull()

    val sprites: Map<String, SpriteDef> = loadSprites(dir)

    private val symbolByName: Map<String, AnimTimeline> =
        anim?.symbolDefs?.symbols.orEmpty().associate { it.name to it.timeline }

    /** One pose per frame of the main timeline. */
    val poses: List<Pose> = loadPoses()

    /** The union of every sprite in every pose - FNF's `Timeline._bounds`. */
    val bounds: Bounds = computeBounds()

    /** `MD.FRT`, the sheet's frame rate. */
    val frameRate: Double get() = anim?.metadata?.frameRate ?: 24.0

    /**
     * Every main-timeline frame carrying [prefix], expanded over each keyframe's
     * duration.
     *
     * Mirrors flixel-animate's `findFrameLabelIndices`: layers are walked in
     * order and the scan stops at the *first layer that has a match*, so a label
     * that only exists on layer 1 is still found. Only the trailing whitespace
     * of a label is stripped (`frame.name.rtrim()`).
     */
    fun labelFrames(prefix: String): List<Int> {
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

    /** The bounding box of a single pose, for props that draw one frame. */
    fun boundsOf(poseIndex: Int): Bounds {
        val b = Bounds()
        val pose = poses.getOrNull(poseIndex) ?: return b
        for (part in pose.parts) {
            val def = sprites[part.sprite] ?: continue
            val w = def.width.toDouble()
            val h = def.height.toDouble()
            for (corner in CORNERS) {
                val (x, y) = part.matrix.xform(corner[0] * w, corner[1] * h)
                b.add(x, y)
            }
        }
        return b
    }
    fun loadSprites(dir: String): Map<String, SpriteDef> {
        val json = KnfFiles.textOrNull("$dir/spritemap1.json") ?: return emptyMap()
        val bitmap: Bitmap = KnfFiles.bitmapOrNull("$dir/spritemap1.png") ?: return emptyMap()

        val raw = runCatching { knfJson.decodeFromString<SpritemapRoot>(json) }
            .getOrElse {
                println("KNF: animate atlas '$dir' spritemap parse failed: $it")
                return emptyMap()
            }.atlas

        val out = LinkedHashMap<String, SpriteDef>()
        for (entry in raw.sprites) {
            val s = entry.sprite ?: continue
            if (s.w <= 0 || s.h <= 0) continue
            // Better TA stores rotated sprites turned 90В° clockwise in the sheet.
            if (s.rotated) {
                // BetterTA keeps `w`/`h` as the size of the packed (rotated)
                // rectangle in the sheet while FlxAnimate swaps them for the
                // logical sprite size and rotates the frame by -90 degrees.
                // Slicing `h x w` here grabbed neighbouring sprites instead,
                // which made results characters appear giant and misplaced.
                out[s.name] = SpriteDef(
                    name = s.name,
                    slice = bitmap.sliceWithSize(s.x, s.y, s.w, s.h),
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
    fun loadPoses(): List<Pose> {
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

    fun collect(
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
                    out += PosePart(asi.name, matOf(asi.matrix, asi.matrix3D).under(parent))
                    continue
                }
                val si = event.instance ?: continue
                val child = symbolByName[si.symbolName] ?: continue
                val childFrame = resolveSymbolFrame(si, frameIndex, keyframe.start, child)
                collect(child, childFrame, matOf(si.matrix, si.matrix3D).under(parent), out, depth + 1)
            }
        }
    }

    /**
     * `SymbolInstance.getFrameIndex`: the instance's own timeline starts at `FF`
     * and advances with the parent's frame counter, clamped or wrapped by `LP`.
     */
    fun resolveSymbolFrame(
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
     * Union of every sprite in every pose вЂ” FNF's `Timeline._bounds`.
     *
     * All four corners are transformed, because a limb that is rotated or scaled
     * covers more than a plain `centre +/- half size` box; using the box instead
     * would clip characters whose sing poses swing further than their idle ones.
     */
    fun computeBounds(): Bounds {
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


    companion object {
        /** Guards against a malformed file with a cyclic symbol reference. */
        const val MAX_SYMBOL_DEPTH = 24

        /** Unit corners of a sprite's logical rectangle, for bounding boxes. */
        val CORNERS = arrayOf(
            doubleArrayOf(0.0, 0.0), doubleArrayOf(1.0, 0.0),
            doubleArrayOf(0.0, 1.0), doubleArrayOf(1.0, 1.0),
        )

        val knfJson = Json {
            ignoreUnknownKeys = true
            isLenient = true
            coerceInputValues = true
            explicitNulls = false
        }
    }
}