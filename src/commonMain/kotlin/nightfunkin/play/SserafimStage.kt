package nightfunkin.play

import korlibs.image.bitmap.Bitmap
import korlibs.image.color.Colors
import korlibs.image.color.RGBA
import korlibs.image.font.TtfFont
import korlibs.image.font.getTextBounds
import korlibs.image.paint.Stroke
import korlibs.io.resources.Resourceable
import korlibs.korge.blend.BlendMode
import korlibs.korge.view.Container
import korlibs.korge.view.Image
import korlibs.korge.view.Text
import korlibs.korge.view.filter.filter
import korlibs.korge.view.filter.filterScale
import korlibs.korge.view.solidRect
import korlibs.math.geom.Matrix
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import nightfunkin.core.AnimateAtlas
import nightfunkin.core.AnimateSpriteView
import nightfunkin.core.Constants
import nightfunkin.core.HapticUtil
import nightfunkin.core.KnfFiles
import nightfunkin.audio.SfxChannel
import nightfunkin.platform.KnfPlatform
import nightfunkin.data.SongEventData
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * `assets/preload/scripts/stages/sserafim.hxc`: the LE SSERAFIM diner.
 *
 * The stage JSON only carries the static props and the three normal character
 * slots. Everything the song is known for lives in the stage script:
 *
 *  - a skewed `PerspectiveSprite` floor that fakes depth from the camera scroll;
 *  - four horizontally tiling `FlxBackdrop` dust layers;
 *  - three extra Animate characters (Yunjin, Chaewon, Eunchae) at fixed points;
 *  - `SserafimShader` on the stage and on every character, with its
 *    `AdjustColor` half animated by `sserafimDark` and the light multiply driven
 *    by `sserafimLights`/`sserafimPulseLights`;
 *  - a long in-game intro (the truck pulling up, Yunjin kicking the door open)
 *    and a `SEEYOU` outro;
 *  - `sserafimShow`/`sserafimSing`, which decide who is on screen and whether a
 *    girl sings the player or the opponent lane.
 *
 * The script used HScript timers/tweens; this class keeps the same data as a
 * small list of timed actions and a generic tween list.
 */
class SserafimStage(
    private val host: CutsceneHost,
    private val stage: Stage,
    private val camera: GameplayCamera,
    private val hud: Hud,
    private val player: Character,
    private val opponent: Character,
    private val girlfriend: Character?,
    private val opponentStrumline: Strumline,
    private val resolveSound: (String) -> String?,
    private val onEndSong: () -> Unit,
) {

    companion object {
        /** FNF's per-stage `hasPlayedCutscene`: retries skip the long intro. */
        var skipIntroOnce = false

        private const val LIBRARY = "sserafim"
        private const val FRAME = 1.0 / 24.0

        /** `SserafimStage.baseVisible` / `baseSinging`. */
        private val BASE_VISIBLE = booleanArrayOf(true, false, false, false, false)
        private val BASE_SINGING = booleanArrayOf(false, false, false, false, false)

        /** Props the script explicitly leaves without the stage shader. */
        private val UNSHADED = setOf(
            "solid", "solidCover",
            "truckLight1", "truckLight2",
            "backLightColor", "backLightWhite",
        )
    }

    private val stageFilter = SserafimFilter(isCharacter = false)
    private val characterFilter = SserafimFilter(isCharacter = true)

    private val yunjin = Character("sserafim-yunjin")
    private val chaewon = Character("sserafim-chaewon")
    private val eunchae = Character("sserafim-eunchae")

    /** `BaseCharacter.characterType == BF` for each entry of [girls]. */
    private val singing = BASE_SINGING.copyOf()

    /** `sserafimSing[5]`: the real girlfriend's `characterType`. */
    private var girlfriendSinging = false
    private val visible = BASE_VISIBLE.copyOf()

    private val girls = arrayOf<Character>(yunjin, opponent, chaewon, eunchae, player)

    private class Extra(
        val character: Character,
        val layer: Container,
        val scrollX: Double,
        val scrollY: Double,
    )

    private val extras = ArrayList<Extra>()

    // ---- floor -----------------------------------------------------------
    private val floorImage: Image?
    private val cutsceneFloorImage: Image?
    private val floorRoot = Container()
    private val floorW: Double
    private val floorH: Double

    // ---- dust ------------------------------------------------------------
    private class Dust(
        val image: Bitmap?,
        val layer: Container,
        val copies: List<Image>,
        val scrollX: Double,
        val scrollY: Double,
        val scale: Double,
        val tile: Double,
        val initialX: Double,
        val initialY: Double,
        var baseX: Double,
        var baseY: Double,
        var velocity: Double,
        var tileOffset: Double = 0.0,
    )

    private val dustLayers = ArrayList<Dust>()

    // ---- cutscene actors -------------------------------------------------
    private val cutsceneSprite: AnimateSpriteView?
    private val gfGetUp: AnimateSpriteView?
    private val bfGetUp: AnimateSpriteView?

    private var cutsceneSpriteVisible = false

    // ---- tweens ----------------------------------------------------------
    private class Tween(
        val duration: Double,
        val ease: (Double) -> Double,
        val apply: (Double) -> Unit,
        val onComplete: (() -> Unit)? = null,
    ) {
        var elapsed = 0.0
        var dead = false
    }

    private val tweens = ArrayList<Tween>()

    private var adjustTween: Tween? = null
    private var adjustFrom = DoubleArray(4)
    private var lightTween: Tween? = null
    private var pulseTween: Tween? = null

    // ---- shader state ----------------------------------------------------
    private var darkenAmount = 0.0
    private var pulseStrength = 0.0
    private var truckStrength = 0.0
    private var pulseColor: RGBA = Colors.WHITE

    private var adjustB = 0.0
    private var adjustH = 0.0
    private var adjustC = 0.0
    private var adjustS = 0.0

    // ---- lights ----------------------------------------------------------
    private var lightsEnabled = false
    private val lightColors = ArrayList<RGBA>()
    private val lightDurations = ArrayList<Double>()
    private val lightIntensities = ArrayList<Double>()

    // ---- end sequence ----------------------------------------------------
    private var endingSequence = false
    private var endingElapsed = 0.0
    private var seeYou1: Image? = null
    private var seeYou2: Image? = null

    /** The normal `checkSongEnd` must wait for `sserafimEnd` to call `endSong`. */
    var suppressSongEnd = false
        private set

    /** FNF's `mayPauseGame = false` during `endStuff`. */
    val pauseBlocked: Boolean get() = endingSequence

    /** True while the intro owns the camera/characters. */
    var inIntroCutscene = false
        private set

    private class Placement(
        val character: Character,
        val feetX: Double,
        val feetY: Double,
        val scroll: Double,
    )

    init {
        // ---- shader ------------------------------------------------------
        for (name in stage.namedPropNames) {
            if (name in UNSHADED) continue
            val prop = stage.namedProp(name) ?: continue
            prop.filter = stageFilter
            prop.filterScale = 0.5
        }
        stage.namedProp("truckLight1")?.blendMode = BlendMode.ADD
        stage.namedProp("backLightColor")?.apply {
            blendMode = BlendMode.ADD
            colorMul = RGBA(0xCC, 0x33, 0x00)
        }
        stage.namedProp("backLightWhite")?.apply {
            blendMode = BlendMode.NORMAL
        }

        for (character in girls) {
            character.filter = characterFilter
            character.filterScale = 0.75
        }
        girlfriend?.apply {
            filter = characterFilter
            filterScale = 0.75
        }

        // ---- extra girls -------------------------------------------------
        for (placement in listOf(
            Placement(yunjin, -621.0, 154.0, 0.95),
            Placement(chaewon, 687.0, 98.0, 0.95),
            Placement(eunchae, 770.0, 675.0, 0.97),
        )) {
            val character = placement.character
            character.dance(forceRestart = true)
            // The script calls `setPosition(x - characterOrigin.x, ...)`
            // directly, so the character data's global offsets are NOT added.
            character.placeFeet(
                placement.feetX,
                placement.feetY,
                character.baseScale,
                applyOffsets = false,
            )
            val layer = Container()
            layer.addChild(character)
            stage.addCutsceneView(layer, 1000)
            extras += Extra(character, layer, placement.scroll, placement.scroll)
        }
        yunjin.play("doorclosed", forceRestart = true)
        yunjin.dancesOnBeat = false

        // ---- perspective floor -------------------------------------------
        val floorInfo = loadStageImage("floor")
        floorW = floorInfo?.width ?: 5362.0
        floorH = floorInfo?.height ?: 754.0
        val cutsceneFloorInfo = loadStageImage("cutscene/floor-cutscene")
        val image = floorInfo?.let {
            Image(it.bitmap).also { img ->
                img.scaleX = 1.0 / it.scaleX
                img.scaleY = 1.0 / it.scaleY
                img.smoothing = true
            }
        }
        val cutsceneImage = cutsceneFloorInfo?.let {
            Image(it.bitmap).also { img ->
                img.scaleX = 1.0 / it.scaleX
                img.scaleY = 1.0 / it.scaleY
                img.smoothing = true
                img.visible = false
            }
        }
        floorImage = image
        cutsceneFloorImage = cutsceneImage
        if (image != null) floorRoot.addChild(image)
        if (cutsceneImage != null) floorRoot.addChild(cutsceneImage)
        floorRoot.filter = stageFilter
        floorRoot.filterScale = 0.5
        stage.addCutsceneView(floorRoot, 11)

        // ---- dust --------------------------------------------------------
        addDust("dust/dustMid", -650.0, -200.0, 1.5, 1.1, 350.0, 0.8, RGBA(0x98, 0x84, 0x7D))
        addDust("dust/dustBack", -650.0, -250.0, 1.5, 1.15, -300.0, 0.9, RGBA(0x8B, 0x6C, 0x63))
        addDust("dust/dustMid", -650.0, -400.0, 2.0, 1.2, -200.0, 0.8, RGBA(0x6E, 0x64, 0x5C))
        addDust("dust/dustBack", -650.0, -1300.0, 3.5, 1.25, -150.0, 0.9, RGBA(0x88, 0x6A, 0x60))

        // ---- cutscene actors ---------------------------------------------
        cutsceneSprite = loadAnimate("cutscene/cutsceneMain")?.let { atlas ->
            AnimateSpriteView(atlas).also {
                it.x = -395.0
                it.y = 10.0
                it.visible = false
                it.filter = characterFilter
                it.filterScale = 0.75
                it.play("", loop = false)
                it.stop()
                stage.addCutsceneView(it, 25)
            }
        }
        gfGetUp = loadAnimate("cutscene/gfGetUp")?.let { atlas ->
            AnimateSpriteView(atlas).also {
                it.x = 655.0
                it.y = -104.0
                it.alpha = 0.5
                it.visible = false
                it.filter = characterFilter
                it.filterScale = 0.75
                it.play("static", loop = false)
                it.stop()
                stage.addCutsceneView(it, 25)
            }
        }
        bfGetUp = loadAnimate("cutscene/bfGetUp")?.let { atlas ->
            AnimateSpriteView(atlas).also {
                it.x = 1220.0
                it.y = 531.0
                it.visible = false
                it.filter = characterFilter
                it.filterScale = 0.75
                it.play("static", loop = false)
                it.stop()
                stage.addCutsceneView(it, 305)
            }
        }

        // ---- initial state ----------------------------------------------
        setGirlsVisible(BASE_VISIBLE)
        setGirlsSinging(BASE_SINGING)
        resetClear()
        opponentStrumline.hideReceptors()
        hud.hidePopUps()
        // `onSongRetry`: FNF hides the opponent icon again; the `sserafimKick`
        // event at ~2.5 s reveals it.
        if (skipIntroOnce) hud.setOpponentIconVisible(false)
    }

    // ======================================================================
    // construction helpers
    // ======================================================================

    private class StageImage(val bitmap: Bitmap, val scaleX: Double, val scaleY: Double, val width: Double, val height: Double)

    private fun loadStageImage(path: String): StageImage? {
        val resolved = KnfFiles.resolve(path, ".png", LIBRARY) ?: return null
        val decoded = KnfFiles.decodedImageOrNull(resolved, KnfFiles.backdropTextureDimension) ?: return null
        val w = decoded.bitmap.width / decoded.scaleX
        val h = decoded.bitmap.height / decoded.scaleY
        return StageImage(decoded.bitmap, decoded.scaleX, decoded.scaleY, w, h)
    }

    private fun loadAnimate(path: String): AnimateAtlas? {
        val dir = KnfFiles.findAnimateDir(LIBRARY, path)
        if (dir == null) {
            println("KNF: sserafim animate asset missing: $path")
            return null
        }
        return AnimateAtlas.cached(dir, KnfFiles.backdropTextureDimension)
    }

    private fun addDust(
        path: String,
        x: Double,
        y: Double,
        scale: Double,
        scroll: Double,
        velocity: Double,
        alpha: Double,
        tint: RGBA,
    ) {
        val info = loadStageImage(path)
        val layer = Container()
        val copies = ArrayList<Image>()
        var tile = 1.0
        if (info != null) {
            // `FlxBackdrop` repeats the image horizontally. Five copies cover
            // the camera view at zoom 0.5 plus a full tile of wrap.
            val texScaleX = 1.0 / info.scaleX
            val texScaleY = 1.0 / info.scaleY
            tile = info.width * scale
            repeat(5) { i ->
                val img = Image(info.bitmap)
                img.scaleX = texScaleX * scale
                img.scaleY = texScaleY * scale
                img.x = (i - 2) * tile
                img.y = 0.0
                img.alpha = alpha
                img.colorMul = tint
                layer.addChild(img)
                copies += img
            }
        }
        layer.visible = false
        stage.addCutsceneView(layer, 2000)
        dustLayers += Dust(
            image = info?.bitmap,
            layer = layer,
            copies = copies,
            scrollX = scroll,
            scrollY = scroll,
            scale = scale,
            tile = tile,
            initialX = x,
            initialY = y,
            baseX = x,
            baseY = y,
            velocity = velocity,
        )
    }

    // ======================================================================
    // per-frame
    // ======================================================================

    fun update(dt: Double, songMs: Double) {
        updateTweens(dt)
        updateExtras(dt)
        updateFloor()
        updateDust(dt)
        updateScheduled(dt)
        if (endingSequence) updateEnding(dt)
        // `SserafimLipSyncSprite.update` runs from every character's `update`.
        for (girl in girls) girl.updateLipSync(songMs)
        applyUniforms()
    }

    private fun updateTweens(dt: Double) {
        if (tweens.isEmpty()) return
        val iterator = tweens.iterator()
        while (iterator.hasNext()) {
            val tween = iterator.next()
            tween.elapsed += dt
            val t = if (tween.duration <= 0.0) 1.0 else (tween.elapsed / tween.duration).coerceIn(0.0, 1.0)
            tween.apply(tween.ease(t))
            if (t >= 1.0) {
                tween.dead = true
                iterator.remove()
                tween.onComplete?.invoke()
            }
        }
    }

    private fun updateExtras(dt: Double) {
        for (extra in extras) {
            extra.character.update(dt)
            extra.layer.x = (1.0 - extra.scrollX) * camera.scrollX
            extra.layer.y = (1.0 - extra.scrollY) * camera.scrollY
        }
        cutsceneSprite?.let {
            it.x = -395.0 + (1.0 - 0.94) * camera.scrollX
            it.y = 10.0 + (1.0 - 0.94) * camera.scrollY
            it.update(dt)
        }
        gfGetUp?.let {
            it.x = 655.0 + (1.0 - 0.95) * camera.scrollX
            it.y = -104.0 + (1.0 - 0.95) * camera.scrollY
            it.update(dt)
        }
        bfGetUp?.let {
            it.x = 1220.0 + (1.0 - 0.99) * camera.scrollX
            it.y = 531.0 + (1.0 - 0.99) * camera.scrollY
            it.update(dt)
        }
    }

    /** `PerspectiveSprite.updateSkew(PlayState.camGame)`. */
    private fun updateFloor() {
        if (floorImage == null) return
        val h = floorH
        if (h <= 0.0) return
        val scrollX = camera.scrollX
        val scrollY = camera.scrollY

        val bottomX = 760.0 + scrollX * (1.0 - 1.05)
        val bottomY = 1375.0 + scrollY * (1.0 - 1.05)
        val topX = 790.0 + scrollX * (1.0 - 0.93)
        val topY = 625.0 + scrollY * (1.0 - 0.93)

        val distX = topX - bottomX
        val distY = topY - (bottomY - h)

        val a = 1.0
        val b = 0.0
        val c = -(distX / h)
        val d = 1.0 - (distY / h)
        val tx = distX / 2.0
        val ty = distY / 2.0

        val spriteX = bottomX - floorW / 2.0
        val spriteY = bottomY - h
        floorRoot.setMatrix(Matrix(a, b, c, d, spriteX + tx, spriteY + ty))
    }

    private fun updateDust(dt: Double) {
        for (dust in dustLayers) {
            val tile = dust.tile
            if (tile > 0.0) {
                dust.tileOffset = (dust.tileOffset + dust.velocity * dt) % tile
                if (dust.tileOffset < 0.0) dust.tileOffset += tile
            }
            dust.layer.x = dust.baseX + (1.0 - dust.scrollX) * camera.scrollX - dust.tileOffset
            dust.layer.y = dust.baseY + (1.0 - dust.scrollY) * camera.scrollY
        }
    }

    private fun applyUniforms() {
        for (filter in arrayOf(stageFilter, characterFilter)) {
            filter.setAdjustColor(adjustB, adjustH, adjustC, adjustS)
            filter.darkenAmount = darkenAmount
            filter.pulseLightColor = pulseColor
            filter.pulseLightStrength = pulseStrength
            filter.truckLightStrength = truckStrength
        }
    }

    /** Called by `PlayScene` on every fourth conductor step. */
    fun onBeat(beat: Int) {
        if (!lightsEnabled) return
        if (lightColors.isEmpty() || lightDurations.isEmpty() || lightIntensities.isEmpty()) return
        // `lightsColors[event.beat % lightsColors.length]` etc: each array is
        // indexed by its OWN length, not by a shared index.
        fun indexFor(size: Int) = ((beat % size) + size) % size
        val color = lightColors[indexFor(lightColors.size)]
        val duration = lightDurations[indexFor(lightDurations.size)]
        val intensity = lightIntensities[indexFor(lightIntensities.size)]
        flashBackLight(intensity, duration, color)
        for (extra in extras) {
            if (extra.character.visible && extra.character.dancesOnBeat) {
                extra.character.dance(forceRestart = true)
            }
        }
    }

    // ======================================================================
    // song events
    // ======================================================================

    /** @return true when the event belonged to this stage. */
    fun onSongEvent(ev: SongEventData): Boolean {
        when (ev.kind) {
            "sserafimShow" -> setGirlsVisible(boolArray(ev, "visible") ?: return true)
            "sserafimSing" -> setGirlsSinging(boolArray(ev, "singing") ?: return true)
            "sserafimDark" -> setDarkenAmt(
                ev.getFloat("amount")?.toDouble() ?: 0.0,
                ev.getFloat("duration")?.toDouble() ?: 0.1,
            )
            "sserafimLights" -> flashTruckLights(
                ev.getFloat("amount")?.toDouble() ?: 0.0,
                ev.getFloat("duration")?.toDouble() ?: 0.5,
            )
            "sserafimCover" -> setCoverVisible(ev.getBool("visible") ?: false)
            "sserafimFlash" -> flashScreen(ev.getFloat("duration")?.toDouble() ?: 1.0)
            "sserafimPulseLights" -> setLightState(
                ev.getBool("enabled") ?: false,
                colorArray(ev, "colors"),
                doubleArray(ev, "durations"),
                doubleArray(ev, "intensities"),
            )
            "sserafimKick" -> kick(ev.getBool("final") ?: false)
            // `HapticUtil.increasingVibrate(MIN, MAX / 2, duration)`.
            "sserafimGuitarVibration" -> HapticUtil.increasingVibrate(
                HapticUtil.MIN_VIBRATION_AMPLITUDE,
                HapticUtil.MAX_VIBRATION_AMPLITUDE / 2.0,
                ev.getFloat("duration")?.toDouble() ?: 1.0,
            )
            "sserafimEnd" -> beginEnding()
            "sserafimBeautiful" -> {
                girlfriend?.animationSuffix = if (ev.getBool("beautiful") == true) "-beautiful" else ""
            }
            else -> return false
        }
        return true
    }

    private fun boolArray(ev: SongEventData, key: String): BooleanArray? {
        val array = (ev.value as? JsonObject)?.get(key) as? JsonArray ?: return null
        return BooleanArray(array.size) { (array[it] as? JsonPrimitive)?.content?.toBooleanStrictOrNull() ?: false }
    }

    private fun doubleArray(ev: SongEventData, key: String): DoubleArray? {
        val array = (ev.value as? JsonObject)?.get(key) as? JsonArray ?: return null
        return DoubleArray(array.size) { (array[it] as? JsonPrimitive)?.content?.toDoubleOrNull() ?: 0.0 }
    }

    private fun colorArray(ev: SongEventData, key: String): List<RGBA> {
        val array = (ev.value as? JsonObject)?.get(key) as? JsonArray ?: return emptyList()
        return array.mapNotNull { (it as? JsonPrimitive)?.content?.let(::parseColor) }
    }

    private fun parseColor(text: String): RGBA? {
        val hex = text.removePrefix("0x").removePrefix("0X").removePrefix("#")
        val value = hex.toLongOrNull(16) ?: return null
        val r: Int
        val g: Int
        val b: Int
        if (hex.length >= 8) {
            r = ((value ushr 16) and 0xFF).toInt()
            g = ((value ushr 8) and 0xFF).toInt()
            b = (value and 0xFF).toInt()
        } else {
            r = ((value ushr 16) and 0xFF).toInt()
            g = ((value ushr 8) and 0xFF).toInt()
            b = (value and 0xFF).toInt()
        }
        return RGBA(r, g, b)
    }

    private fun setGirlsVisible(array: BooleanArray) {
        if (array.size < 5) return
        for (i in 0 until 5) {
            visible[i] = array[i]
            girls[i].visible = array[i]
        }
    }

    private fun setGirlsSinging(array: BooleanArray) {
        if (array.size < 5) return
        for (i in 0 until 5) {
            singing[i] = array[i]
            // `lipSyncSprite.shouldSing = characterType == CharacterType.BF`
            // is re-applied from the character's own `update` on every frame.
            girls[i].lipSyncShouldSing = array[i]
        }
        // The script also flips the real girlfriend's type: `singing[5]`.
        if (array.size >= 6) girlfriendSinging = array[5]
    }

    // ======================================================================
    // gameplay singing
    // ======================================================================

    /**
     * `BaseCharacter.onNoteHit`: every visible BF-type girl sings the player
     * lane, every visible DAD-type girl sings the opponent lane. The note kind
     * suffix (`sakura-joint`, `sakura-bf1`, ...) is only present in Sakura's
     * atlas, so passing it to the others is harmless.
     */
    fun singPlayerNote(direction: Int, kind: String?, stepLengthMs: Double, miss: Boolean) {
        val suffix = kindSuffix(kind, miss)
        for (i in 0 until 5) {
            val character = girls[i]
            if (!singing[i] || !character.visible) continue
            character.sing(direction, stepLengthMs, miss, suffix)
        }
        // `setGirlsSinging` also sets `getGirlfriend()?.characterType`.
        val gf = girlfriend
        if (girlfriendSinging && gf != null && gf.visible) {
            gf.sing(direction, stepLengthMs, miss = false)
        }
    }

    fun singOpponentNote(direction: Int, stepLengthMs: Double) {
        for (i in 0 until 5) {
            val character = girls[i]
            if (singing[i] || !character.visible) continue
            character.sing(direction, stepLengthMs)
        }
        val gf = girlfriend
        if (!girlfriendSinging && gf != null && gf.visible) {
            gf.sing(direction, stepLengthMs)
        }
    }

    private fun kindSuffix(kind: String?, miss: Boolean): String? = when (kind) {
        "sakura-joint" -> "joint"
        "sakura-bf1", "sakura-bf2" -> if (miss) "bf2" else kind.substringAfter("sakura-")
        else -> null
    }

    // ======================================================================
    // lights / dark
    // ======================================================================

    private fun setDarkenAmt(amount: Double, duration: Double) {
        cancelAdjust()
        val from = darkenAmount
        val tween = Tween(duration, { sineInOut(it) }, { k ->
            darkenAmount = from + (amount - from) * k
        })
        tweens += tween
    }

    private fun flashTruckLights(amount: Double, duration: Double) {
        val light1 = stage.namedProp("truckLight1") ?: return
        val light2 = stage.namedProp("truckLight2") ?: return
        HapticUtil.vibrate(0.0, duration / 2.0, amount / 2.0, 0.0)
        lightTween?.let { tweens.remove(it); lightTween = null }
        light1.alpha = amount
        light2.alpha = amount
        truckStrength = amount
        val tween = Tween(duration, { cubeInOut(it) }, { k ->
            val value = amount * (1.0 - k)
            light1.alpha = value
            light2.alpha = value
            truckStrength = value
        }) {
            light1.alpha = 0.0
            light2.alpha = 0.0
            truckStrength = 0.0
        }
        lightTween = tween
        tweens += tween
    }

    private fun setLightState(
        enabled: Boolean,
        colors: List<RGBA>,
        durations: DoubleArray?,
        intensities: DoubleArray?,
    ) {
        lightsEnabled = enabled
        if (colors.isEmpty() || durations == null || intensities == null) return
        lightColors.clear()
        lightColors += colors
        lightDurations.clear()
        for (value in durations) lightDurations += value
        lightIntensities.clear()
        for (value in intensities) lightIntensities += value
    }

    private fun flashBackLight(amount: Double, duration: Double, color: RGBA) {
        val colorProp = stage.namedProp("backLightColor") ?: return
        val whiteProp = stage.namedProp("backLightWhite") ?: return
        pulseTween?.let { tweens.remove(it); pulseTween = null }

        colorProp.colorMul = color
        colorProp.alpha = amount * 0.8
        whiteProp.alpha = amount * 0.7
        pulseColor = color
        pulseStrength = colorProp.alpha

        val startColor = amount * 0.8
        val startWhite = amount * 0.7
        val tween = Tween(duration, { cubeInOut(it) }, { k ->
            colorProp.alpha = startColor * (1.0 - k)
            whiteProp.alpha = startWhite * (1.0 - k)
            pulseStrength = colorProp.alpha
        }) {
            colorProp.alpha = 0.0
            whiteProp.alpha = 0.0
            pulseStrength = 0.0
        }
        pulseTween = tween
        tweens += tween
    }

    private fun setCoverVisible(visible: Boolean) {
        stage.namedProp("solidCover")?.alpha = if (visible) 1.0 else 0.0
    }

    private fun flashScreen(duration: Double) {
        val rect = Container().solidRect(
            Constants.GAME_WIDTH * 2.0,
            Constants.GAME_HEIGHT * 2.0,
            Colors.WHITE,
        )
        rect.alpha = 1.0
        host.addGameOverlayView(rect)
        var elapsed = 0.0
        tweens += Tween(duration, { it }, { k ->
            rect.alpha = (1.0 - k).coerceIn(0.0, 1.0)
        }) {
            rect.removeFromParent()
        }
    }

    // ======================================================================
    // kick / clear
    // ======================================================================

    private fun kick(final: Boolean) {
        if (final) {
            yunjin.play("kick2", forceRestart = true)
            yunjin.dancesOnBeat = true
            playStageSfx("sounds/doorKick2")
            HapticUtil.vibrate(0.0, 0.2, 0.8, 0.0)
            hud.setOpponentIconVisible(true)
            // "hide the cutscene characters if theyre present! and show the
            // REAL gf" - the `SserafimGfSprite` actors stay loaded, so this
            // happens unconditionally.
            girlfriend?.visible = true
            gfGetUp?.visible = false
            bfGetUp?.visible = false
            // `yunjin.animation.onFrameChange` shows the static door at frame 23.
            schedule(23.0 * FRAME) {
                stage.namedProp("truckDoor")?.visible = true
            }
            startClear()
        } else {
            yunjin.play("kick1", forceRestart = true)
            yunjin.dancesOnBeat = false
            playStageSfx("sounds/doorKick1")
            HapticUtil.vibrate(0.0, 0.2, 0.4, 0.0)
        }
    }

    private fun startClear() {
        tweenAdjust(0.0, 0.0, 0.0, 0.0, 6.0 * 4, { sineOut(it) })
        val dust = dustLayers
        for (i in dust.indices) {
            val layer = dust[i]
            val duration = when (i) {
                0 -> 5.0 * 4
                1 -> 4.0 * 4
                2 -> 6.0 * 4
                else -> 4.0 * 4
            }
            val drop = when (i) {
                0 -> 100.0
                1 -> 200.0
                2 -> 150.0
                else -> 100.0
            }
            val startAlpha = layer.copies.firstOrNull()?.alpha ?: 1.0
            val startY = layer.baseY
            val startVelocity = layer.velocity
            tweens += Tween(duration, { sineOut(it) }, { k ->
                for (copy in layer.copies) copy.alpha = startAlpha * (1.0 - k)
                layer.baseY = startY + drop * k
                layer.velocity = startVelocity * (1.0 - k)
            })
        }
    }

    /** `SserafimStage.resetClear`. */
    fun resetClear() {
        yunjin.play("doorclosed", forceRestart = true)
        yunjin.dancesOnBeat = false
        stage.namedProp("truckDoor")?.visible = false

        cancelAdjust()
        setAdjustColorImmediate(-24.0, 6.0, -26.0, -74.0)

        val positions = listOf(
            -650.0 to -400.0,
            -650.0 to -450.0,
            -650.0 to -600.0,
            -650.0 to -1500.0,
        )
        val velocities = doubleArrayOf(350.0, -300.0, -200.0, -150.0)
        for (i in dustLayers.indices) {
            val dust = dustLayers[i]
            dust.baseX = positions[i].first
            dust.baseY = positions[i].second
            dust.velocity = velocities[i]
            dust.tileOffset = 0.0
            for (copy in dust.copies) copy.alpha = 1.0
        }
    }

    private fun tweenAdjust(
        b: Double,
        h: Double,
        c: Double,
        s: Double,
        duration: Double,
        ease: (Double) -> Double,
    ) {
        cancelAdjust()
        adjustFrom = doubleArrayOf(adjustB, adjustH, adjustC, adjustS)
        val tween = Tween(duration, ease, { k ->
            adjustB = adjustFrom[0] + (b - adjustFrom[0]) * k
            adjustH = adjustFrom[1] + (h - adjustFrom[1]) * k
            adjustC = adjustFrom[2] + (c - adjustFrom[2]) * k
            adjustS = adjustFrom[3] + (s - adjustFrom[3]) * k
        })
        adjustTween = tween
        tweens += tween
    }

    private fun cancelAdjust() {
        adjustTween?.let { tweens.remove(it) }
        adjustTween = null
    }

    fun setAdjustColorImmediate(b: Double, h: Double, c: Double, s: Double) {
        adjustB = b
        adjustH = h
        adjustC = c
        adjustS = s
    }

    // ---- cutscene-facing wrappers (the .hxc tween calls) -----------------

    fun setDarkenForCutscene(amount: Double, duration: Double) = setDarkenAmt(amount, duration)

    fun tweenAdjustForCutscene(
        b: Double,
        h: Double,
        c: Double,
        s: Double,
        duration: Double,
        ease: (Double) -> Double,
    ) = tweenAdjust(b, h, c, s, duration, ease)

    fun setAdjustColorForCutscene(b: Double, h: Double, c: Double, s: Double) =
        setAdjustColorImmediate(b, h, c, s)

    fun setCoverForCutscene(alpha: Double) {
        stage.namedProp("solidCover")?.alpha = alpha
    }

    fun tweenCoverForCutscene(target: Double, duration: Double) {
        val prop = stage.namedProp("solidCover") ?: return
        val from = prop.alpha
        tweens += Tween(duration, { sineOut(it) }, { k -> prop.alpha = from + (target - from) * k })
    }

    // ======================================================================
    // cutscene transitions
    // ======================================================================

    /** `setCutsceneVisibility(inCutscene)`. */
    fun setCutsceneVisibility(inCutscene: Boolean) {
        inIntroCutscene = inCutscene
        if (inCutscene) {
            setGirlsVisible(BooleanArray(5) { false })
        } else {
            setGirlsVisible(BASE_VISIBLE)
        }
        stage.namedProp("truck")?.visible = !inCutscene
        stage.namedProp("truckDoor")?.visible = false
        stage.namedProp("backTables")?.visible = !inCutscene
        stage.namedProp("backStools")?.visible = !inCutscene
        stage.namedProp("frontStool")?.visible = !inCutscene
        for (dust in dustLayers) dust.layer.visible = !inCutscene

        floorImage?.visible = !inCutscene
        cutsceneFloorImage?.visible = inCutscene

        stage.namedProp("backTablesCutscene")?.visible = inCutscene
        stage.namedProp("burgerCutscene")?.visible = inCutscene

        girlfriend?.visible = false
        cutsceneSpriteVisible = inCutscene
        cutsceneSprite?.visible = inCutscene
    }

    fun playCutsceneAnimation() {
        cutsceneSprite?.play("", loop = false, restart = true)
    }

    fun showGetUpStatic() {
        gfGetUp?.visible = true
        bfGetUp?.visible = true
        gfGetUp?.play("static", loop = false, restart = true)
        bfGetUp?.play("static", loop = false, restart = true)
    }

    fun playGetUp() {
        gfGetUp?.play("getup", loop = false, restart = true)
        bfGetUp?.play("getup", loop = false, restart = true)
    }

    /** `playCutsceneFromRestart()`: the state the intro leaves behind. */
    fun applyPostIntro() {
        setCutsceneVisibility(false)
        resetClear()
        setCoverVisible(false)
        showGetUpStatic()
        playGetUp()
        // `tweenCameraZoom(0.55, 1, true, circOut)` +
        // `tweenCameraToPosition(1070, 470, 1, circOut)`. The countdown is
        // already running, so land on the final values immediately.
        host.focusCamera(1070.0, 470.0, 0.55)
    }

    // ======================================================================
    // ending
    // ======================================================================

    private fun beginEnding() {
        if (endingSequence) return
        endingSequence = true
        endingElapsed = 0.0
        suppressSongEnd = true
        playStageSfx("sounds/cutscene/end1")

        seeYou1 = loadScreenImage("end/end1", center = true, scale = 0.67)
        seeYou2 = loadScreenImage("end/end2", center = false, scale = 0.67)
        seeYou1?.visible = false
        seeYou2?.visible = false
    }

    private fun updateEnding(dt: Double) {
        val previous = endingElapsed
        endingElapsed += dt

        fun crossed(time: Double): Boolean = previous < time && endingElapsed >= time

        if (crossed(0.05)) {
            HapticUtil.vibrate(0.0, 0.2, 0.4, 0.0)
            seeYou1?.visible = true
            setCoverVisible(true)
            hud.visible = false
        }
        if (crossed(4.0)) {
            seeYou1?.visible = false
            seeYou2?.visible = true
            playStageSfx("sounds/cutscene/end2")
            HapticUtil.vibrate(0.0, 0.4, 0.5, 0.0)
        }
        if (crossed(8.0)) {
            seeYou1?.visible = false
            seeYou2?.visible = false
        }
        if (endingElapsed >= 9.0) {
            endingSequence = false
            onEndSong()
        }
    }

    private fun loadScreenImage(path: String, center: Boolean, scale: Double): Image? {
        val info = loadStageImage(path) ?: return null
        val img = Image(info.bitmap)
        img.scaleX = scale / info.scaleX
        img.scaleY = scale / info.scaleY
        val w = info.width * scale
        val h = info.height * scale
        img.x = if (center) (Constants.GAME_WIDTH - w) / 2.0 else Constants.GAME_WIDTH - 40.0 - w
        img.y = if (center) (Constants.GAME_HEIGHT - h) / 2.0 else Constants.GAME_HEIGHT - 40.0 - h
        host.addCutsceneView(img)
        return img
    }

    // ======================================================================
    // small helpers
    // ======================================================================

    private var pendingActions = ArrayList<Pair<Double, () -> Unit>>()

    private fun schedule(delay: Double, action: () -> Unit) {
        pendingActions += delay to action
    }

    /** Runs the timed actions created with [schedule] after their delay. */
    private fun updateScheduled(dt: Double) {
        if (pendingActions.isEmpty()) return
        val due = ArrayList<Pair<Double, () -> Unit>>()
        for (index in pendingActions.indices.reversed()) {
            val (remaining, action) = pendingActions[index]
            val next = remaining - dt
            if (next <= 0.0) {
                due += 0.0 to action
                pendingActions.removeAt(index)
            } else {
                pendingActions[index] = next to action
            }
        }
        for ((_, action) in due) action()
    }

    internal fun playSound(stem: String) {
        playSoundChannel(stem)
    }

    internal fun playSoundChannel(stem: String): SfxChannel? =
        resolveSound(stem)?.let { host.playSfx(it, 1.0) }

    private fun playStageSfx(stem: String) = playSound(stem)

    private fun sineInOut(t: Double): Double = -(kotlin.math.cos(Math.PI * t) - 1.0) / 2.0
    private fun sineOut(t: Double): Double = kotlin.math.sin(t * Math.PI / 2.0)
    private fun cubeInOut(t: Double): Double =
        if (t < 0.5) 4.0 * t * t * t else 1.0 - (-2.0 * t + 2.0).pow(3) / 2.0
}

// ===========================================================================
// intro cutscene
// ===========================================================================

/**
 * `SserafimStage.onCountdownStart` -> `introCutscene()`.
 *
 * 730 frames at 24 fps of camera moves, a truck crash and a four-second fade
 * back in, followed by the regular countdown. Skipping is allowed only until
 * frame 499, exactly like the script (`cutsceneSkipped = true` there).
 */
class SserafimCutscene(
    private val host: CutsceneHost,
    private val stage: SserafimStage,
) : SongCutscene {

    override val kind: CutsceneKind get() = CutsceneKind.STARTING

    /** The intro leaves the camera at 0.55 / (1070, 470); do not reset it. */
    override val keepCameraOnFinish: Boolean get() = true

    private companion object {
        const val FRAME = 1.0 / 24.0
        const val SKIP_LOCK_FRAME = 499
        const val END_FRAME = 730
        const val SKIP_FADE = 0.5
    }

    private enum class Phase { RUNNING, SKIPPING, DONE }

    private var phase = Phase.RUNNING
    private var time = 0.0
    private var events = ArrayList<Pair<Double, () -> Unit>>()
    private var eventIndex = 0

    private var skipRoot: Container? = null
    private var skipFadeIn = false
    private var skipFadeOut = false
    private var skipElapsed = 0.0
    private var canSkip = false
    private var skipLocked = false

    private var skipPhaseTime = 0.0
    private var skipStageApplied = false

    /** `sserafim.hxc`'s `cutsceneSounds`: the intro's `startCutscene` track. */
    private var cutsceneSound: nightfunkin.audio.SfxChannel? = null

    private var posTween: Pair<DoubleArray, DoubleArray>? = null // fromXY, toXY
    private var posDuration = 0.0
    private var posElapsed = 0.0
    private var posEase: (Double) -> Double = { it }

    private var zoomFrom = 1.0
    private var zoomTo = 1.0
    private var zoomDuration = 0.0
    private var zoomElapsed = 0.0
    private var zoomEase: (Double) -> Double = { it }

    private var fadeRect: korlibs.korge.view.View? = null
    private var fadeDuration = 0.0
    private var fadeElapsed = 0.0

    private val vcrFont: TtfFont? by lazy {
        KnfFiles.bytesOrNull("fonts/vcr.ttf")
            ?.let { runCatching { TtfFont(it, "vcr.ttf") }.getOrNull() }
    }

    override suspend fun start() {
        if (SserafimStage.skipIntroOnce) {
            SserafimStage.skipIntroOnce = false
            host.setHudVisible(true)
            stage.applyPostIntro()
            phase = Phase.DONE
            return
        }

        host.setHudVisible(false)
        stage.setCutsceneVisibility(true)
        stage.playCutsceneAnimation()
        createSkipText()
        // `startCutscene` starts a frame later; preload it so the channel is
        // real (a late load would start after a skip and keep playing).
        host.preloadSound("sounds/cutscene/startCutscene")
        host.focusCamera(660.0, -200.0, 0.5)
        schedule(0.0) {
            fadeFrom(Colors.BLACK, 3.0)
            // `cutsceneSounds = FunkinSound.load(Paths.sound('cutscene/startCutscene'), ...)`
            // - kept as a channel so skipping can fade and stop it.
            cutsceneSound = stage.playSoundChannel("sounds/cutscene/startCutscene")
        }
        schedule(20.0 * FRAME) {
            tweenZoom(0.7, 3.0) { circOut(it) }
            tweenPosition(660.0, 300.0, 3.0) { circOut(it) }
        }
        schedule(245.0 * FRAME) {
            HapticUtil.vibrate(0.0, 0.1, 0.2, 0.0)
            stage.setDarkenForCutscene(0.2, 0.01)
        }
        schedule(251.0 * FRAME) { stage.setDarkenForCutscene(0.0, 0.8) }
        schedule(406.0 * FRAME) {
            HapticUtil.vibrate(0.0, 0.1, 0.2, 0.0)
            stage.setDarkenForCutscene(0.2, 0.01)
        }
        schedule(411.0 * FRAME) { stage.setDarkenForCutscene(0.0, 0.8) }
        schedule(499.0 * FRAME) {
            skipLocked = true
            fadeSkipTextOut()
            HapticUtil.vibrate(10.0 / 24.0, 50.0 / 24.0, 0.1, 0.1)
            stage.tweenAdjustForCutscene(55.0, 0.0, -30.0, 0.0, 49.0 * FRAME) { sineOut(it) }
        }
        schedule(548.0 * FRAME) {
            HapticUtil.vibrate(2.0 / 24.0, 20.0 / 24.0, 0.4, 0.4)
            stage.tweenAdjustForCutscene(66.0, 10.0, -17.0, 0.0, 15.0 * FRAME) { expoIn(it) }
        }
        schedule(563.0 * FRAME) {
            stage.setAdjustColorForCutscene(0.0, 0.0, 0.0, 0.0)
            fadeFrom(Colors.WHITE, 30.0 * FRAME)
            stage.setCoverForCutscene(1.0)
            stage.setCutsceneVisibility(false)
        }
        // The crash itself, 5/24 s after the white flash.
        schedule(563.0 * FRAME + 5.0 / 24.0) {
            HapticUtil.vibrate(0.0, 10.0 / 24.0, 1.0, 1.0)
        }
        schedule(650.0 * FRAME) {
            host.focusCamera(1070.0, 470.0, 0.7)
            tweenZoom(0.55, 3.0) { circOut(it) }
            stage.resetClear()
            stage.tweenCoverForCutscene(0.0, 3.0)
            stage.showGetUpStatic()
        }
        schedule(710.0 * FRAME) { stage.playGetUp() }
        schedule(END_FRAME * FRAME) { phase = Phase.DONE }

        println("KNF: sserafim intro cutscene started")
    }

    override fun update(dt: Double): Boolean {
        when (phase) {
            Phase.DONE -> return true
            Phase.SKIPPING -> {
                updateSkip(dt)
                return phase == Phase.DONE
            }
            Phase.RUNNING -> Unit
        }

        time += dt
        while (eventIndex < events.size && time >= events[eventIndex].first) {
            events[eventIndex].second()
            eventIndex++
        }

        updateTweens(dt)
        updateSkipText(dt)
        updateFade(dt)

        if (host.advancePressed && !skipLocked) {
            if (!canSkip) {
                fadeSkipTextIn()
            } else {
                beginSkip()
            }
        }

        // The script calls `resetCamera`/`resetCameraZoom` after the tweens;
        // our camera is driven directly, so nothing else to do here.
        return phase == Phase.DONE
    }

    override fun destroy() {
        skipRoot?.let { host.removeCutsceneView(it) }
        skipRoot = null
        // A retry mid-intro must not leave `startCutscene` playing under the
        // countdown.
        cutsceneSound?.stop()
        cutsceneSound = null
        fadeRect?.removeFromParent()
        fadeRect = null
        host.hideBlackScreen()
        host.setHudVisible(true)
    }

    // ------------------------------------------------------------------
    // scheduling
    // ------------------------------------------------------------------

    private fun schedule(at: Double, action: () -> Unit) {
        events += at to action
    }

    // ------------------------------------------------------------------
    // camera
    // ------------------------------------------------------------------

    private fun tweenPosition(x: Double, y: Double, duration: Double, ease: (Double) -> Double) {
        posTween = doubleArrayOf(host.cameraFocusX, host.cameraFocusY) to doubleArrayOf(x, y)
        posDuration = duration
        posElapsed = 0.0
        posEase = ease
    }

    private var zoomActive = false

    private fun tweenZoom(zoom: Double, duration: Double, ease: (Double) -> Double) {
        zoomFrom = host.cameraZoom
        zoomTo = zoom
        zoomDuration = duration
        zoomElapsed = 0.0
        zoomEase = ease
        zoomActive = duration > 0.0
        if (!zoomActive) host.setCameraZoom(zoom)
    }

    private fun updateTweens(dt: Double) {
        var x = host.cameraFocusX
        var y = host.cameraFocusY
        var zoom = host.cameraZoom

        posTween?.let { (from, to) ->
            posElapsed += dt
            val t = if (posDuration <= 0.0) 1.0 else (posElapsed / posDuration).coerceIn(0.0, 1.0)
            val e = posEase(t)
            x = from[0] + (to[0] - from[0]) * e
            y = from[1] + (to[1] - from[1]) * e
            if (t >= 1.0) posTween = null
        }
        if (zoomActive) {
            zoomElapsed += dt
            val t = if (zoomDuration <= 0.0) 1.0 else (zoomElapsed / zoomDuration).coerceIn(0.0, 1.0)
            zoom = zoomFrom + (zoomTo - zoomFrom) * zoomEase(t)
            if (t >= 1.0) zoomActive = false
        }

        host.focusCamera(x, y, zoom)
    }

    // ------------------------------------------------------------------
    // fades
    // ------------------------------------------------------------------

    private fun fadeFrom(color: RGBA, duration: Double) {
        val rect = Container().solidRect(
            Constants.GAME_WIDTH * 2.0,
            Constants.GAME_HEIGHT * 2.0,
            color,
        )
        rect.alpha = 1.0
        host.addGameOverlayView(rect)
        fadeRect = rect
        fadeDuration = duration
        fadeElapsed = 0.0
    }

    private fun updateFade(dt: Double) {
        val rect = fadeRect ?: return
        fadeElapsed += dt
        val t = if (fadeDuration <= 0.0) 1.0 else (fadeElapsed / fadeDuration).coerceIn(0.0, 1.0)
        rect.alpha = (1.0 - t).coerceIn(0.0, 1.0)
        if (t >= 1.0) {
            rect.removeFromParent()
            fadeRect = null
        }
    }

    // ------------------------------------------------------------------
    // skip
    // ------------------------------------------------------------------

    private fun createSkipText() {
        val root = Container()
        val mobile = KnfPlatform.isMobile
        // `skipText.text = 'Skip [Pause Button]'; skipText.x -= 136;` (mobile)
        val label = if (mobile) "Skip [Pause Button]" else "Skip [ Enter ]"
        val font = vcrFont
        val width = font?.getTextBounds(40.0, label)?.width ?: 220.0
        val x = 936.0 * (Constants.GAME_WIDTH / 1280.0) - width - if (mobile) 136.0 else 0.0
        val y = 618.0 * (Constants.GAME_HEIGHT / 720.0)

        val main = Text(label, 40.0, Colors.WHITE)
        font?.let { main.font = Resourceable.Fixed(it) }
        main.smoothing = false
        main.stroke = Stroke(Colors.BLACK, 2.0)
        main.x = x
        main.y = y
        root.addChild(main)
        root.alpha = 0.0
        skipRoot = root
        host.addCutsceneView(root)
    }

    private fun fadeSkipTextIn() {
        skipFadeIn = true
        skipFadeOut = false
        skipElapsed = 0.0
    }

    private fun fadeSkipTextOut() {
        skipFadeIn = false
        skipFadeOut = true
        skipElapsed = 0.0
    }

    private fun updateSkipText(dt: Double) {
        val root = skipRoot ?: return
        if (skipFadeIn) {
            skipElapsed += dt
            root.alpha = (skipElapsed / 0.5).coerceIn(0.0, 1.0)
            if (root.alpha >= 1.0) {
                skipFadeIn = false
                canSkip = true
            }
        } else if (skipFadeOut) {
            skipElapsed += dt
            root.alpha = (1.0 - skipElapsed / 0.5).coerceIn(0.0, 1.0)
            if (root.alpha <= 0.0) {
                skipFadeOut = false
                root.visible = false
            }
        }
    }

    private fun beginSkip() {
        phase = Phase.SKIPPING
        skipPhaseTime = 0.0
        skipStageApplied = false
        skipRoot?.visible = false
        fadeRect?.removeFromParent()
        fadeRect = null
        host.showBlackScreen()
        host.setBlackScreenAlpha(0.0)
    }

    private fun updateSkip(dt: Double) {
        skipPhaseTime += dt
        if (!skipStageApplied) {
            val t = (skipPhaseTime / SKIP_FADE).coerceIn(0.0, 1.0)
            // `cutsceneSounds.fadeOut(0.5, 0)` while the screen goes black.
            cutsceneSound?.volume = 1.0 - t
            host.setBlackScreenAlpha(t)
            if (skipPhaseTime >= SKIP_FADE) {
                skipStageApplied = true
                // `cutsceneSounds.stop()` once the fade finishes.
                cutsceneSound?.stop()
                cutsceneSound = null
                stage.applyPostIntro()
                host.focusCamera(1070.0, 470.0, 0.55)
            }
        } else {
            val t = ((skipPhaseTime - SKIP_FADE) / SKIP_FADE).coerceIn(0.0, 1.0)
            host.setBlackScreenAlpha(1.0 - t)
            if (t >= 1.0) {
                host.hideBlackScreen()
                phase = Phase.DONE
            }
        }
    }

    // ------------------------------------------------------------------
    // easing
    // ------------------------------------------------------------------

    private fun circOut(t: Double): Double = kotlin.math.sqrt(1.0 - (t - 1.0) * (t - 1.0))
    private fun sineOut(t: Double): Double = kotlin.math.sin(t * Math.PI / 2.0)
    private fun expoIn(t: Double): Double = if (t <= 0.0) 0.0 else 2.0.pow(10.0 * (t - 1.0))
}
