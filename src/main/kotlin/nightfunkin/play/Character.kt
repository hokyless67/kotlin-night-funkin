package nightfunkin.play

import korlibs.image.bitmap.Bitmap
import korlibs.image.bitmap.sliceWithSize
import korlibs.math.geom.Anchor2D
import korlibs.korge.view.Container
import korlibs.korge.view.Image
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import nightfunkin.core.AnimateAtlas
import nightfunkin.core.AtlasSlice
import nightfunkin.core.Bounds
import nightfunkin.core.Mat
import nightfunkin.core.Pose
import nightfunkin.core.PosePart
import nightfunkin.core.SpriteDef
import nightfunkin.core.KnfFiles
import nightfunkin.core.TextureAtlas
import nightfunkin.core.setPixelArt
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

/**
 * FNF's `CharacterData.HealthIconData` - which face to show on the health bar.
 *
 * `CharacterData` fills in `id: null` when the field is missing and then
 * `if (input.healthIcon.id == null) input.healthIcon.id = id;`, so a character
 * without an explicit entry uses its own icon.
 */
@Serializable
data class HealthIconData(
    val id: String? = null,
    val isPixel: Boolean? = null,
    val scale: Double? = null,
    val offsets: List<Double>? = null,
    val flipX: Boolean? = null,
    val shouldBop: Boolean? = null,
)

@Serializable
data class CharacterData(
    val version: String = "1.0.0",
    val name: String = "",
    val renderType: String = "animateatlas",
    val assetPath: String = "",
    val flipX: Boolean = false,
    val scale: Double? = null,
    /**
     * FNF turns antialiasing off for the whole character when this is set -
     * `SparrowCharacter.loadSpritesheet` does
     * `if (_data.isPixel) { isPixel = true; antialiasing = false; }`.
     */
    val isPixel: Boolean = false,
    /** Which face to show on the health bar; defaults to the character's own. */
    @SerialName("healthIcon") val healthIcon: HealthIconData? = null,
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

    /** The Animate atlas, for `renderType: animateatlas` / `multianimateatlas`. */
    private val animateAtlas: AnimateAtlas? = animateDir?.let { AnimateAtlas(it) }

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
        animateAtlas?.sprites ?: loadLegacySprites(legacyAtlas)

    /** All poses, indexed by timeline frame of the main symbol (or atlas frame). */
    private val poses: List<Pose> =
        if (legacyAtlas != null) legacyPoses() else animateAtlas?.poses ?: emptyList()

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
    } ?: animateAtlas?.bounds ?: Bounds()

    /** Undo the authoring origin, so local (0,0) is the character's top-left. */
    val originX: Double get() = bounds.minX
    val originY: Double get() = bounds.minY
    val localWidth: Double get() = bounds.width
    val localHeight: Double get() = bounds.height

    val displayName: String get() = data.name.ifEmpty { id }

    /** FNF's `_data.isPixel`; pixel sheets render with nearest-neighbour sampling. */
    val isPixel: Boolean get() = data.isPixel

    /**
     * Which face to put on the health bar.
     *
     * `BaseCharacter.getHealthIconId` falls back to the character's own id, and
     * `BaseCharacter.initHealthIcon` flips the *player's* icon once because
     * "BF is looking the other way".
     */
    val healthIconId: String get() = data.healthIcon?.id ?: id
    val healthIconIsPixel: Boolean get() = data.healthIcon?.isPixel ?: data.isPixel
    val healthIconFlipX: Boolean get() = data.healthIcon?.flipX ?: false
    val healthIconShouldBop: Boolean get() = data.healthIcon?.shouldBop ?: true

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

    private val frameRate: Double = animateAtlas?.frameRate ?: 24.0

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
    /**
     * Every main-timeline frame carrying [prefix], expanded over each keyframe's
     * duration.
     *
     * Mirrors flixel-animate's `findFrameLabelIndices`: layers are walked in
     * order and the scan stops at the *first layer that has a match*, so a label
     * that only exists on layer 1 is still found.
     */
    private fun labelFrames(prefix: String): List<Int> =
        animateAtlas?.labelFrames(prefix) ?: emptyList()

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
        if (animateAtlas == null && legacyAtlas == null) return emptyMap()
        val out = LinkedHashMap<String, List<Int>>()

        for (def in data.animations) {
            val frames = if (legacyAtlas != null) {
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
            img.setPixelArt(isPixel)

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
