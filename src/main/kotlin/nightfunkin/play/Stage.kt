package nightfunkin.play

import korlibs.image.bitmap.Bitmap
import korlibs.korge.view.Container
import korlibs.korge.view.Image
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import nightfunkin.core.AnimateAtlas
import nightfunkin.core.Constants
import nightfunkin.core.KnfFiles
import nightfunkin.core.TextureAtlas
import nightfunkin.core.setPixelArt
import korlibs.math.geom.Anchor2D
import korlibs.math.geom.degrees
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.random.Random

// ===========================================================================
// preload/data/stages/<id>.json
// ===========================================================================

@Serializable
data class StagePropData(
    val name: String = "",
    /** Either an image path, or a `#rrggbb` fill for solid-colour props. */
    val assetPath: String = "",
    val position: List<Double> = listOf(0.0, 0.0),
    val scale: List<Double> = listOf(1.0, 1.0),
    val alpha: Double = 1.0,
    val zIndex: Int = 0,
    /** Parallax factors; `[0, 0]` pins the prop to the camera. */
    val scroll: List<Double> = listOf(1.0, 1.0),
    val angle: Double = 0.0,
    val isPixel: Boolean = false,
    val flipX: Boolean = false,
    val flipY: Boolean = false,
    /** `animateatlas`, `sparrow` or `packer`; FNF defaults to `sparrow`. */
    val animType: String = "",
    /** The animations the prop can play, as frame-label prefixes. */
    val animations: List<StagePropAnimation>? = null,
    /** Which of [animations] starts playing. */
    val startingAnimation: String? = null,
    /** If non-zero, `danceLeft`/`danceRight` (or `idle`) replay every N beats. */
    val danceEvery: Double = 0.0,
    /** Extra Animate atlas settings, e.g. `applyStageMatrix`. */
    val atlasSettings: AtlasSettings = AtlasSettings(),
)

/** `atlasSettings` from a stage prop's JSON. */
@Serializable
data class AtlasSettings(val applyStageMatrix: Boolean = false)

/** One of a prop's animations: a name and the frame-label prefix it plays. */
@Serializable
data class StagePropAnimation(
    val name: String = "",
    val prefix: String = "",
    val looped: Boolean = false,
    val frameIndices: List<Int>? = null,
    val frameRate: Int? = null,
    /** `framelabel` for normal Animate labels, or `symbol` for a symbol clip. */
    val animType: String = "framelabel",
    val offsets: List<Double> = listOf(0.0, 0.0),
    val flipX: Boolean = false,
    val flipY: Boolean = false,
)

@Serializable
data class StageCharacterData(
    val position: List<Double> = listOf(0.0, 0.0),
    val scale: Double? = null,
    val zIndex: Int = 0,
    val alpha: Double = 1.0,
    val angle: Double = 0.0,
    val scroll: List<Double> = listOf(1.0, 1.0),
    val cameraOffsets: List<Double> = listOf(0.0, 0.0),
)

@Serializable
data class StageCharacters(
    @SerialName("bf") val bf: StageCharacterData? = null,
    @SerialName("gf") val gf: StageCharacterData? = null,
    @SerialName("dad") val dad: StageCharacterData? = null,
)

@Serializable
data class StageData(
    val name: String = "",
    val directory: String = "",
    val cameraZoom: Double? = null,
    val props: List<StagePropData> = emptyList(),
    val characters: StageCharacters = StageCharacters(),
)

/** A loaded stage definition: props, character placement and camera zoom. */
class StageDataFile(val id: String) {
    val data: StageData = runCatching {
        stageJson.decodeFromString<StageData>(KnfFiles.text("preload/data/stages/$id.json"))
    }.getOrElse {
        println("KNF: no stage data for '$id': $it")
        StageData(name = id)
    }

    val props: List<StagePropData> get() = data.props
    val cameraZoom: Double get() = data.cameraZoom ?: 1.0
    val characters: StageCharacters get() = data.characters

    /**
     * The asset library the stage lives in, e.g. `week2`.
     *
     * FNF's `Paths.getLibraryPathForce(file, library)` builds
     * `'$library:assets/$library/$file'`, so a stage's `directory` names the
     * top-level folder its props are resolved against.
     */
    val directory: String get() = data.directory

    fun characterFor(slot: String): StageCharacterData? =
        when (slot) {
            "bf" -> characters.bf
            "gf" -> characters.gf
            "dad" -> characters.dad
            else -> null
        }
}

/**
 * The visible half of a stage: its background props, in FNF's `zIndex` order.
 *
 * Positions are **stage space**, not screen space. FNF lets the camera sit
 * anywhere over a stage-sized world, so KNF applies one shared transform for
 * the props and the characters rather than baking screen coordinates into each
 * prop — that is what `StageSpace` is for.
 */
class Stage(id: String) : Container() {

    private val stageId = id
    private val file = StageDataFile(id)
    private val loaded = ArrayList<Container>()

    /** zIndex of every child in draw order, parallel to the container's children. */
    private val childZIndices = ArrayList<Int>()

    /** Parallax factors of each loaded prop, parallel to [loaded]. */
    val scrollFactors = ArrayList<Pair<Double, Double>>()

    private val animatedProps = ArrayList<StageAnimationPlayer>()
    private val boppers = ArrayList<Pair<Double, StageAnimationPlayer>>()

    /** Props addressable by their JSON `name`, like FNF's `getNamedProp`. */
    private val namedProps = LinkedHashMap<String, Container>()

    /** Per-prop transform state used to emulate Flixel scroll factors. */
    private class PlacedProp(
        val container: Container,
        var baseX: Double,
        var baseY: Double,
        val scrollX: Double,
        val scrollY: Double,
        var rotationDegrees: Double,
        val pivotX: Double = 0.0,
        val pivotY: Double = 0.0,
        val hasPivot: Boolean = false,
    )

    private val placedProps = ArrayList<PlacedProp>()
    private val placedByName = HashMap<String, PlacedProp>()

    /** Last Flixel camera scroll, so scripted position changes apply immediately. */
    private var lastScrollX = 0.0
    private var lastScrollY = 0.0

    /** Stage-scripted rotation origins, keyed by prop name. */
    private val propPivots = HashMap<String, Pair<Double, Double>>()

    /**
     * Rotation origins for ordinary props with an `angle`.
     *
     * Flixel rotates a sprite around `origin * scale` (its centre after
     * `updateHitbox`), while KorGE rotates a container around its top-left.
     * Static props need their own pivot because their image carries the scale.
     */
    private val staticPivots = HashMap<Container, Pair<Double, Double>>()

    // tankmanBattlefield.hx: the tank rolls in a large circle.
    private var tankAngle = 0.0
    private var tankSpeed = 0.0
    private val tankX = 400.0

    init {
        // `Stage.refresh()` sorts every child by zIndex, ascending.
        for (prop in file.props.sortedBy { it.zIndex }) {
            val img = buildProp(prop) ?: continue
            addChild(img)
            childZIndices += prop.zIndex
            loaded += img
            val scroll = prop.scroll.getOrElse(0) { 1.0 } to prop.scroll.getOrElse(1) { 1.0 }
            scrollFactors += scroll
            val pivot = staticPivots[img] ?: propPivots[prop.name]
            val placed = PlacedProp(
                container = img,
                baseX = img.x,
                baseY = img.y,
                scrollX = scroll.first,
                scrollY = scroll.second,
                rotationDegrees = prop.angle,
                pivotX = pivot?.first ?: 0.0,
                pivotY = pivot?.second ?: 0.0,
                hasPivot = pivot != null,
            )
            placedProps += placed
            if (prop.name.isNotEmpty()) {
                namedProps[prop.name] = img
                placedByName[prop.name] = placed
            }
        }

        if (stageId == "tankmanBattlefield") {
            tankAngle = Random.nextInt(-90, 46).toDouble()
            tankSpeed = Random.nextDouble(5.0, 7.0)
            moveTank(0.0)
        }

        // spookyMansionErect's stage script starts with both lightning layers
        // hidden (`buildStage()` sets them to 0.0001) and only flashes them on
        // a strike. Without the script they would sit at full alpha over the
        // dark layers and wash the whole mansion out.
        if (id == "spookyMansionErect") {
            namedProps["bgLight"]?.alpha = 0.0001
            namedProps["stairsLight"]?.alpha = 0.0001
        }

        update(0.0)

        if (loaded.isEmpty()) println("KNF: stage '$id' has no usable props")
    }

    /**
     * Adds a character to the stage in FNF's global draw order.
     *
     * FNF puts props and characters in the same `FlxSpriteGroup` and calls
     * `refresh()`, which sorts *all* of them by `zIndex`. Keeping characters in
     * a separate layer makes every character draw over foreground props such as
     * tankmanBattlefield's audience.
     */
    fun addCharacter(character: Container, zIndex: Int) {
        var index = 0
        while (index < childZIndices.size && childZIndices[index] <= zIndex) index++
        addChildAt(character, index)
        childZIndices.add(index, zIndex)
    }

    /** FNF's `getNamedProp(name).setPosition(x, y)` for stage-script overrides. */
    fun setNamedPropPosition(name: String, x: Double, y: Double) {
        val prop = placedByName[name] ?: return
        prop.baseX = x
        prop.baseY = y
        prop.container.x = x + (1.0 - prop.scrollX) * lastScrollX
        prop.container.y = y + (1.0 - prop.scrollY) * lastScrollY
    }

    /**
     * Advances every animated prop and applies Flixel's per-prop `scrollFactor`.
     *
     * [scrollLeft]/[scrollTop] must be Flixel's `camera.scroll`, i.e. the
     * top-left of the **unzoomed** view - not the visible world rectangle at
     * the current zoom. Flixel draws a sprite at
     * `(position - scroll * scrollFactor)`, so a prop with factor `s` needs to
     * be shifted by `(1 - s) * scroll` inside our single shared camera
     * transform.
     */
    fun update(dt: Double, scrollLeft: Double = 0.0, scrollTop: Double = 0.0) {
        lastScrollX = scrollLeft
        lastScrollY = scrollTop
        for (prop in animatedProps) prop.update(dt)
        if (stageId == "tankmanBattlefield") moveTank(dt)
        for (prop in placedProps) {
            val x = prop.baseX + (1.0 - prop.scrollX) * scrollLeft
            val y = prop.baseY + (1.0 - prop.scrollY) * scrollTop
            prop.container.rotation = prop.rotationDegrees.degrees
            if (!prop.hasPivot) {
                prop.container.x = x
                prop.container.y = y
                continue
            }
            // Flixel rotates sprites around their centre. KorGE rotates a
            // container around (0,0), so compensate by the rotated pivot.
            val radians = Math.toRadians(prop.rotationDegrees)
            val cosA = cos(radians)
            val sinA = sin(radians)
            val rotPivotX = cosA * prop.container.scaleX * prop.pivotX -
                sinA * prop.container.scaleY * prop.pivotY
            val rotPivotY = sinA * prop.container.scaleX * prop.pivotX +
                cosA * prop.container.scaleY * prop.pivotY
            prop.container.x = x + prop.pivotX - rotPivotX
            prop.container.y = y + prop.pivotY - rotPivotY
        }
    }

    /** `TankmanBattlefieldStage.moveTank`. */
    private fun moveTank(elapsed: Double) {
        val tank = placedByName["tankRolling"] ?: return
        tankAngle += elapsed * tankSpeed
        val radians = Math.toRadians(tankAngle + 180.0)
        tank.baseX = tankX + cos(radians) * 1500.0
        tank.baseY = 1300.0 + sin(radians) * 1100.0
        tank.rotationDegrees = tankAngle - 90.0 + 15.0
    }

    /**
     * FNF's `Bopper.onStepHit`: replay the dance animation every
     * `danceEvery * STEPS_PER_BEAT` steps.
     */
    fun onStep(step: Int) {
        for ((danceEvery, prop) in boppers) {
            val steps = (danceEvery * Constants.STEPS_PER_BEAT).toInt()
            if (steps > 0 && step % steps == 0) prop.dance(forceRestart = true)
        }
    }

    private fun buildProp(prop: StagePropData): Container? {
        if (prop.assetPath.isEmpty()) return null
        if (prop.assetPath.startsWith("#")) return buildSolidColor(prop)

        if (!prop.animations.isNullOrEmpty()) {
            val animated = when (prop.animType) {
                "animateatlas" -> buildAnimateProp(prop)
                "packer" -> buildLegacyAnimatedProp(prop, packer = true)
                else -> buildLegacyAnimatedProp(prop, packer = false)
            }
            if (animated != null) return animated
        }

        return buildStaticProp(prop)
    }

    private fun buildStaticProp(prop: StagePropData): Container? {
        val bmp: Bitmap = resolveAsset(prop.assetPath) ?: return null
        val img = Image(bmp)
        apply(img, prop, bmp.width.toDouble(), bmp.height.toDouble())
        // FNF: `propSprite.antialiasing = !dataProp.isPixel;`
        img.setPixelArt(prop.isPixel)
        val container = Container().also { it.addChild(img) }
        if (prop.angle != 0.0) {
            val sx = prop.scale.getOrElse(0) { 1.0 }
            val sy = prop.scale.getOrElse(1) { 1.0 }
            staticPivots[container] =
                abs(bmp.width * sx) / 2.0 to abs(bmp.height * sy) / 2.0
        }
        return container
    }

    /**
     * A prop drawn from an Adobe Animate atlas.
     *
     * FNF's `Stage.addProp` loads the atlas and then `FlxAnimationUtil` resolves
     * each animation through `addByFrameLabel` (or `addBySymbol`). A prop can
     * therefore have several clips - tankman's sniper idles and sips, the
     * spooky mansion background starts `static` and flashes `thunder`.
     */
    private fun buildAnimateProp(prop: StagePropData): Container? {
        val dir = KnfFiles.findAnimateDir(file.directory, prop.assetPath)
        if (dir == null) {
            println("KNF: stage prop asset missing: ${prop.assetPath} (${prop.animType})")
            return null
        }
        val atlas = AnimateAtlas(dir)
        if (atlas.sprites.isEmpty()) {
            println("KNF: stage prop '${file.id}' has no sprites in $dir")
            return null
        }

        val allFrames = atlas.poses.indices.toList()
        val animations = LinkedHashMap<String, StageAnimation>()
        for (def in prop.animations.orEmpty()) {
            val labelled = atlas.labelFrames(def.prefix)
            // `animType: "symbol"` references a symbol clip, not a frame label.
            // Our flattened pose list cannot address arbitrary symbol timelines,
            // so play the main timeline for those clips. This keeps the two
            // shipped symbol props (mallXmasErect's boppers and tankman's guy)
            // animated instead of dropping them entirely.
            val base = when {
                labelled.isNotEmpty() -> labelled
                def.animType == "symbol" || def.prefix.isEmpty() -> allFrames
                else -> emptyList()
            }
            val frames = if (def.frameIndices.isNullOrEmpty()) {
                base
            } else {
                def.frameIndices.mapNotNull { base.getOrNull(it) }
            }
            if (frames.isEmpty()) continue
            animations[def.name] = def.toStageAnimation(frames)
        }

        if (animations.isEmpty()) {
            println("KNF: stage prop '${file.id}' has no playable animation in $dir")
            return null
        }

        val container = Container()
        placeAnimatedContainer(container, prop)

        val box = atlas.bounds.takeUnless { it.empty } ?: atlas.boundsOf(0)
        val applyStageMatrix = prop.atlasSettings.applyStageMatrix
        // `FlxAnimate.prepareDrawMatrix`: with `applyStageMatrix` the timeline
        // bounds translation is cancelled and `library.matrix` is applied, so
        // the atlas is rendered in Animate-stage coordinates instead of being
        // shifted to its own top-left. Without it, Flixel renders at
        // `-bounds.min` (the old behaviour).
        val originX = if (applyStageMatrix) 0.0 else box.minX
        val originY = if (applyStageMatrix) 0.0 else box.minY
        val images = ArrayList<Image>()
        val placeholder = atlas.sprites.values.firstOrNull()?.slice

        fun render(anim: StageAnimation, frameIndex: Int) {
            val pose = atlas.poses.getOrNull(frameIndex) ?: return
            if (placeholder != null) {
                while (images.size < pose.parts.size) {
                    val img = Image(placeholder)
                    img.anchor = Anchor2D.CENTER
                    img.setPixelArt(prop.isPixel)
                    images += img
                    container.addChild(img)
                }
            }
            for (i in images.indices) {
                val img = images[i]
                val part = pose.parts.getOrNull(i)
                val def = part?.let { atlas.sprites[it.sprite] }
                if (part == null || def == null) {
                    img.visible = false
                    continue
                }
                img.visible = true
                img.bitmap = def.slice
                val m = if (applyStageMatrix) {
                    part.matrix.under(atlas.stageMatrix)
                } else {
                    part.matrix
                }
                val (cx, cy) = m.xform(def.width / 2.0, def.height / 2.0)
                img.x = cx - originX - anim.offsetX
                img.y = cy - originY - anim.offsetY
                // The same decomposition `Character.renderPose` uses: a negative
                // determinant means the symbol is mirrored, not rotated.
                img.scaleX = hypot(m.a, m.b)
                img.scaleY = hypot(m.c, m.d)
                if (m.a * m.d - m.c * m.b < 0.0) img.scaleY = -img.scaleY
                img.rotation = ((if (def.turned) -90.0 else 0.0) +
                    Math.toDegrees(atan2(m.b, m.a))).degrees
            }
        }

        val player = StageAnimationPlayer(animations, ::render, prop.startingAnimation)
        animatedProps += player
        if (prop.danceEvery > 0.0) boppers += prop.danceEvery to player
        return container
    }

    /**
     * A `sparrow` or `packer` prop with animations. These are ordinary Flixel
     * sprites whose frames come from an XML or spirit frame list, so animation
     * data uses frame-name prefixes and optional indices into that prefix run.
     */
    private fun buildLegacyAnimatedProp(prop: StagePropData, packer: Boolean): Container? {
        val suffix = if (packer) ".txt" else ".xml"
        val path = KnfFiles.resolve(prop.assetPath, suffix, file.directory)
        if (path == null) {
            println("KNF: stage prop atlas missing: ${prop.assetPath}$suffix")
            return null
        }
        val atlas = if (packer) TextureAtlas.tryLoadPacker(path) else TextureAtlas.tryLoad(path)
        if (atlas == null || atlas.frames.isEmpty()) {
            println("KNF: stage prop atlas failed to load: $path")
            return null
        }

        val ordered = atlas.frames.values.toList()
        if (stageId == "tankmanBattlefield" && prop.name == "tankRolling") {
            val first = ordered.first()
            propPivots[prop.name] =
                first.logicalWidth / 2.0 to first.logicalHeight / 2.0
        }
        val animations = LinkedHashMap<String, StageAnimation>()
        for (def in prop.animations.orEmpty()) {
            val matching = ordered.indices.filter { ordered[it].name.startsWith(def.prefix) }
            val frames = if (def.frameIndices.isNullOrEmpty()) {
                matching
            } else if (packer) {
                // Packer props call `animation.add(name, frameIndices)` directly,
                // so those indices address the whole frame list.
                def.frameIndices.filter { it in ordered.indices }
            } else {
                def.frameIndices.mapNotNull { matching.getOrNull(it) }
            }
            if (frames.isEmpty()) continue
            animations[def.name] = def.toStageAnimation(frames)
        }
        if (animations.isEmpty()) {
            println("KNF: stage prop '${file.id}' has no matching frames for ${prop.assetPath}")
            return null
        }

        val container = Container()
        placeAnimatedContainer(container, prop)
        val img = Image(atlas.slice(ordered.first()))
        img.setPixelArt(prop.isPixel)
        container.addChild(img)

        fun render(anim: StageAnimation, frameIndex: Int) {
            val frame = ordered.getOrNull(frameIndex) ?: return
            img.bitmap = atlas.slice(frame)
            img.scaleX = if (anim.flipX) -1.0 else 1.0
            img.scaleY = if (anim.flipY) -1.0 else 1.0
            // `Bopper.getScreenPosition` subtracts the per-animation offsets
            // after scaling. Frame trimming shifts the drawn rect inside the
            // logical frame, exactly like `TextureAtlas.addImage`.
            img.x = (if (anim.flipX) frame.logicalWidth - frame.frameX else -frame.frameX) - anim.offsetX
            img.y = (if (anim.flipY) frame.logicalHeight - frame.frameY else -frame.frameY) - anim.offsetY
            img.visible = true
        }

        val player = StageAnimationPlayer(animations, ::render, prop.startingAnimation)
        animatedProps += player
        if (prop.danceEvery > 0.0) boppers += prop.danceEvery to player
        return container
    }

    private fun placeAnimatedContainer(container: Container, prop: StagePropData) {
        container.x = prop.position.getOrElse(0) { 0.0 }
        container.y = prop.position.getOrElse(1) { 0.0 }
        container.scaleX = prop.scale.getOrElse(0) { 1.0 }
        container.scaleY = prop.scale.getOrElse(1) { 1.0 }
        container.alpha = prop.alpha
        container.rotation = prop.angle.degrees
    }

    /**
     * Solid-colour props: FNF makes a 1x1 sprite and scales it to `scale`, which
     * is how the speaker/lamp blocks in later weeks are drawn.
     */
    private fun buildSolidColor(prop: StagePropData): Container? {
        val w = prop.scale.getOrElse(0) { 1.0 }
        val h = prop.scale.getOrElse(1) { 1.0 }
        if (w <= 0 || h <= 0) return null
        val argb = parseHexColor(prop.assetPath) ?: return null
        val bmp = korlibs.image.bitmap.Bitmap32(
            1, 1, intArrayOf(argb), false,
        )
        val img = Image(bmp)
        apply(img, prop, w, h, extraScaleX = w, extraScaleY = h)
        val container = Container().also { it.addChild(img) }
        if (prop.angle != 0.0) staticPivots[container] = w / 2.0 to h / 2.0
        return container
    }

    private fun apply(
        img: Image,
        prop: StagePropData,
        width: Double,
        height: Double,
        extraScaleX: Double = 1.0,
        extraScaleY: Double = 1.0,
    ) {
        val sx = prop.scale.getOrElse(0) { 1.0 } * extraScaleX
        val sy = prop.scale.getOrElse(1) { 1.0 } * extraScaleY
        // Flipping mirrors within the sprite's rect, so shift x by its width.
        img.scaleX = if (prop.flipX) -sx else sx
        img.scaleY = if (prop.flipY) -sy else sy
        img.x = prop.position.getOrElse(0) { 0.0 } + if (prop.flipX) width * sx else 0.0
        img.y = prop.position.getOrElse(1) { 0.0 } + if (prop.flipY) height * sy else 0.0
        img.alpha = prop.alpha
        // Rotation is applied by [Stage.update] on the prop's container, so a
        // static image must not rotate itself as well.
        img.visible = true
    }

    /**
     * Resolves a prop's `assetPath` to a PNG.
     *
     * Props with no animations are plain `loadTexture` in FNF, so a bare `.png`
     * is all that is needed - but where it lives is not obvious. `school.json`
     * says `weeb/weebSky`, which only exists under `assets/week6/images`, so the
     * lookup has to walk every asset library rather than just `shared`/`preload`.
     * See [KnfFiles.resolve].
     */
    private fun resolveAsset(assetPath: String): Bitmap? {
        // Static stage props use FNF's `Paths.image` while the stage's library
        // is active. Without forcing it, same-named assets from an earlier week
        // (for example week1's `erect/bg`) win over the stage's own artwork.
        val path = KnfFiles.resolve(assetPath, ".png", file.directory)
        if (path == null) {
            println("KNF: stage prop asset missing: $assetPath")
            println("KNF:   tried " + KnfFiles.resolveCandidates(assetPath, ".png", file.directory))
            return null
        }
        return KnfFiles.bitmapOrNull(path)
    }

    private fun parseHexColor(text: String): Int? {
        val hex = text.removePrefix("#")
        if (hex.length != 6 && hex.length != 8) return null
        val value = hex.toLongOrNull(16) ?: return null
        return if (hex.length == 6) (0xFF000000L or value).toInt() else value.toInt()
    }

    val propCount: Int get() = loaded.size

    companion object {
        private val cache = HashMap<String, StageDataFile>()
        fun file(id: String): StageDataFile = cache.getOrPut(id) { StageDataFile(id) }
    }
}

/**
 * One playable stage clip. [frames] are renderer-specific frame numbers: atlas
 * indices for sparrow/packer props, main-timeline pose indices for Animate.
 */
private data class StageAnimation(
    val name: String,
    val frames: List<Int>,
    val frameRate: Double,
    val looped: Boolean,
    val offsetX: Double,
    val offsetY: Double,
    val flipX: Boolean,
    val flipY: Boolean,
)

private class StageAnimationPlayer(
    private val animations: Map<String, StageAnimation>,
    private val render: (StageAnimation, Int) -> Unit,
    startingAnimation: String?,
) {
    private var current: StageAnimation? = null
    private var frameIndex = 0
    private var frameTime = 0.0
    private var danced = false

    init {
        val start = startingAnimation?.let { animations[it] }
        if (start != null) {
            current = start
            draw()
        } else {
            // With no startingAnimation, FNF leaves the sprite on its initial
            // frame instead of auto-playing.
            animations.values.firstOrNull()?.let { render(it, it.frames.first()) }
        }
    }

    fun update(dt: Double) {
        val anim = current ?: return
        if (anim.frames.size <= 1) return
        val step = 1.0 / anim.frameRate.coerceAtLeast(1.0)
        frameTime += dt
        while (frameTime >= step) {
            frameTime -= step
            if (frameIndex + 1 < anim.frames.size) {
                frameIndex++
            } else if (anim.looped) {
                frameIndex = 0
            } else {
                // Flixel holds the final frame of a play-once animation.
                frameTime = 0.0
                break
            }
            draw()
        }
    }

    /** FNF's `Bopper.dance`: alternate `danceLeft`/`danceRight`, else `idle`. */
    fun dance(forceRestart: Boolean = false) {
        val left = animations["danceLeft"]
        val right = animations["danceRight"]
        val name = if (left != null && right != null) {
            (if (danced) "danceRight" else "danceLeft").also { danced = !danced }
        } else {
            "idle"
        }
        play(name, forceRestart)
    }

    fun play(name: String, restart: Boolean = false) {
        val anim = animations[name] ?: return
        if (anim === current && !restart) return
        current = anim
        frameIndex = 0
        frameTime = 0.0
        draw()
    }

    private fun draw() {
        val anim = current ?: return
        val frame = anim.frames.getOrNull(frameIndex) ?: return
        render(anim, frame)
    }
}

private fun StagePropAnimation.toStageAnimation(frames: List<Int>) = StageAnimation(
    name = name,
    frames = frames,
    frameRate = (frameRate ?: 24).toDouble(),
    looped = looped,
    offsetX = offsets.getOrElse(0) { 0.0 },
    offsetY = offsets.getOrElse(1) { 0.0 },
    flipX = flipX,
    flipY = flipY,
)

private val stageJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
    coerceInputValues = true
    explicitNulls = false
}