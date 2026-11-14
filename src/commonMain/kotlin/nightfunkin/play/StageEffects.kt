package nightfunkin.play

import korlibs.image.color.RGBA
import korlibs.korge.blend.BlendMode
import korlibs.korge.view.Container
import korlibs.korge.view.FixedSizeContainer
import korlibs.korge.view.Image
import korlibs.korge.view.filter.filter
import nightfunkin.core.Constants
import kotlin.math.pow

/**
 * The per-stage view shaders of 0.8.6, as their `.hxc` scripts set them up.
 *
 * The stage JSON only describes props and characters; the scripts add the
 * shaders on top. This class keeps that split: [Stage] still owns the artwork,
 * while this owns the filters and the per-song parameters they animate with.
 *
 * Covered stages:
 *
 *  - `schoolEvil` / `schoolEvilErect` - `WiggleEffectRuntime` (dreamy) on the
 *    school, ground and trees;
 *  - `phillyTrain` / `phillyTrainErect` - `BuildingEffectShader` on `lights`;
 *  - `limoRide` - `OverlayBlend` on the sunset; `limoRideErect` tints the
 *    dancers/car and the characters with a shared `AdjustColorShader`;
 *  - `mallXmasErect` - `AdjustColorShader` on Santa, the boppers and everyone;
 *  - `mainStageErect` - additive light props (no shader, but the same block);
 *  - `phillyStreets` / `phillyStreetsErect` - `RuntimeRainShader` on the game
 *    camera, plus the additive `*_lightmap` props;
 *  - `phillyBlazin` - the same rain, with its look-at-the-sky blends;
 *  - `spookyMansionErect` - the rain in `spriteMode` on `bgTrees`.
 *
 * `sserafim` is deliberately absent: its shader is driven entirely by song
 * events that KNF does not run yet, and at its constructor defaults it is a
 * no-op, so adding it alone would be invisible.
 */
class StageEffects(
    private val stageId: String,
    private val songId: String,
    private val stage: Stage,
    private val world: FixedSizeContainer,
    private val camera: GameplayCamera,
    private val songLengthMs: () -> Double,
) {

    private val wiggles = ArrayList<WiggleFilter>()

    private var rain: RainFilter? = null
    private var rainTarget: Container? = null
    private var rainStartIntensity = 0.0
    private var rainEndIntensity = 0.0
    private var rainRamp = false
    private var rainTimeScale = 1.0
    private var rainHitScale = false

    private var trainLights: BuildingEffectFilter? = null
    private var trainColorIndex = 0

    init {
        setup()
    }

    // ---- setup -------------------------------------------------------------

    private fun setup() {
        when (stageId) {
            "schoolEvil" -> {
                addWiggle("backTrees", speed = 2 * 0.8, frequency = 4 * 0.4, amplitude = 0.011)
                addWiggle("school", speed = 2.0, frequency = 4.0, amplitude = 0.017)
                addWiggle("street", speed = 2.0, frequency = 4.0, amplitude = 0.007)
                addWiggle("trees", speed = 2.0, frequency = 4.0, amplitude = 0.007)
            }

            "schoolEvilErect" -> {
                addWiggle("school", speed = 2.0, frequency = 4.0, amplitude = 0.017)
                addWiggle("evilstreet", speed = 2.0, frequency = 4.0, amplitude = 0.007)
                addWiggle("backspikes", speed = 2 * 0.8, frequency = 4 * 0.4, amplitude = 0.011)
                addWiggle("backspike", speed = 2.0, frequency = 4.0, amplitude = 0.01)
            }

            "phillyTrain", "phillyTrainErect" -> {
                val lights = BuildingEffectFilter()
                trainLights = lights
                stage.namedProp("lights")?.filter = lights
                if (stageId == "phillyTrainErect") {
                    // `phillyTrainErect.hxc`: the train itself has its own
                    // `colorShader` with the same values as the characters.
                    stage.namedProp("train")?.filter = adjustColor(-5.0, -26.0, 0.0, -16.0)
                }
            }

            "limoRide" -> {
                // `limoRide.hxc` samples `limo/limoOverlay` over the sunset.
                val overlay = OverlayBlendFilter.loadOverlay()
                stage.namedProp("limoSunset")?.filter = OverlayBlendFilter(overlay)
            }

            "limoRideErect" -> {
                // `limoRideErect.hxc` comments the sky overlay out, but tints
                // the dancers and the fast car.
                val tint = adjustColor(-30.0, -30.0, 0.0, -20.0)
                for (name in DANCERS) stage.namedProp(name)?.filter = tint
                stage.namedProp("fastCar")?.filter = tint
            }

            "mallXmasErect" -> {
                stage.namedProp("santa")?.filter = adjustColor(0.0, 5.0, 0.0, 20.0)
                stage.namedProp("bottomBoppers")?.filter = adjustColor(20.0, 15.0, 0.0, 0.0)
            }

            "mainStageErect" -> {
                for (name in MAIN_STAGE_LIGHTS) stage.namedProp(name)?.blendMode = BlendMode.ADD
            }

            "phillyStreets" -> {
                installPhillyStreetsRain(erect = false)
            }

            "phillyStreetsErect" -> {
                installPhillyStreetsRain(erect = true)
            }

            "phillyBlazin" -> {
                // `phillyBlazin.hxc`: fixed half-intensity rain, and the
                // effect's time scale settles to 0.02 (hits kick it up).
                installRain(
                    scale = Constants.GAME_HEIGHT / 200.0,
                    start = 0.5,
                    end = 0.5,
                    color = RainFilter.DEFAULT_RAIN_COLOR,
                )
                rainHitScale = true
                applyBlazinBlends()
            }

            "spookyMansionErect" -> {
                // `spookyMansionErect.hxc` puts the rain on `bgTrees` itself
                // (`spriteMode = true`), at twice the usual scale.
                installRain(
                    scale = Constants.GAME_HEIGHT / 200.0 * 2.0,
                    start = 0.4,
                    end = 0.4,
                    color = RainFilter.DEFAULT_RAIN_COLOR,
                    sprite = stage.namedProp("bgTrees"),
                )
            }
        }
    }

    private fun addWiggle(name: String, speed: Double, frequency: Double, amplitude: Double) {
        val prop = stage.namedProp(name) ?: return
        // `wiggle.frag` derives its pixel grid from `openfl_TextureSize`, i.e.
        // the source frame. These props are pixel art scaled 6x, so the
        // render-target size would turn the dreamy rows into screen-pixel
        // noise; use the bitmap's own dimensions instead.
        val size = sourceFrameSize(prop) ?: (1 to 1)
        val filter = WiggleFilter(
            speed = speed,
            frequency = frequency,
            amplitude = amplitude,
            sourceWidth = size.first,
            sourceHeight = size.second,
        )
        // FNF draws pixel sprites with `antialiasing = false`, so the shader's
        // texture lookups must be nearest too. With linear filtering the 6x
        // Week 6 pixel art comes back blurred, which reads as "AA is on".
        filter.filtering = !stage.namedPropIsPixel(name)
        prop.filter = filter
        wiggles += filter
    }

    /** First image under a stage prop: its source frame size, in pixels. */
    private fun sourceFrameSize(prop: Container): Pair<Int, Int>? {
        fun visit(view: Container): Pair<Int, Int>? {
            for (child in view.children) {
                when (child) {
                    is Image -> return child.bitmap.width to child.bitmap.height
                    is Container -> visit(child)?.let { return it }
                }
            }
            return null
        }
        return visit(prop)
    }

    private fun adjustColor(
        brightness: Double,
        hue: Double,
        contrast: Double,
        saturation: Double,
    ) = AdjustColorFilter(
        brightness = brightness,
        hue = hue,
        contrast = contrast,
        saturation = saturation,
    )

    private fun installPhillyStreetsRain(erect: Boolean) {
        val (start, end) = when (songId) {
            "darnell" -> if (erect) 0.0 to 0.01 else 0.0 to 0.1
            "lit-up" -> if (erect) 0.01 to 0.02 else 0.1 to 0.2
            "2hot" -> if (erect) 0.02 to 0.04 else 0.2 to 0.4
            else -> if (erect) 0.0 to 0.01 else 0.0 to 0.1
        }
        // `phillyStreetsErect.hxc` swaps the rain colour to a pale grey.
        val color = if (erect) RGBA(0xA8, 0xAD, 0xB5) else RainFilter.DEFAULT_RAIN_COLOR
        installRain(Constants.GAME_HEIGHT / 200.0, start, end, color)
        rainRamp = true
        applyPhillyStreetsBlends()
    }

    private fun installRain(
        scale: Double,
        start: Double,
        end: Double,
        color: RGBA,
        sprite: Container? = null,
    ) {
        val filter = RainFilter(scale, start, color)
        rain = filter
        rainStartIntensity = start
        rainEndIntensity = end
        val target = sprite ?: world
        rainTarget = target
        target.filter = filter
    }

    /**
     * `phillyStreets*.hxc`'s `addProp`: every `*_lightmap` is additive at 60%,
     * `grey1` additive and `grey2` multiplied (both used as frame-buffer masks
     * in FNF; the blends still apply to the visible sprites).
     */
    private fun applyPhillyStreetsBlends() {
        for (name in stage.namedPropNames) {
            val prop = stage.namedProp(name) ?: continue
            when {
                name.endsWith("_lightmap") -> {
                    prop.blendMode = BlendMode.ADD
                    prop.alpha = 0.6
                }
                name == "grey1" -> prop.blendMode = BlendMode.ADD
                name == "grey2" -> prop.blendMode = BlendMode.MULTIPLY
            }
        }
    }

    /** `phillyBlazin.hxc`'s `buildStage` blend/visibility block. */
    private fun applyBlazinBlends() {
        stage.namedProp("skyAdditive")?.let {
            it.blendMode = BlendMode.ADD
            it.visible = false
        }
        stage.namedProp("lightning")?.visible = false
        stage.namedProp("foregroundMultiply")?.let {
            it.blendMode = BlendMode.MULTIPLY
            it.visible = false
        }
        stage.namedProp("additionalLighten")?.let {
            it.blendMode = BlendMode.ADD
            it.visible = false
        }
    }

    // ---- per frame ---------------------------------------------------------

    fun update(dt: Double, songPositionMs: Double, beatLengthMs: Double) {
        if (wiggles.isNotEmpty()) {
            for (filter in wiggles) filter.update(dt)
        }

        trainLights?.update((beatLengthMs / 1000.0) * dt * 1.5)

        val filter = rain ?: return
        var elapsed = dt
        if (rainHitScale) {
            // `rainShader.update(event.elapsed * rainTimeScale)` happens before
            // `rainTimeScale` is eased, exactly like the source.
            elapsed *= rainTimeScale
            rainTimeScale = smoothLerpPrecision(rainTimeScale, 0.02, dt, 1.535)
        }
        filter.update(elapsed)

        if (rainRamp) {
            val length = songLengthMs().takeIf { it > 0.0 } ?: return
            val t = (songPositionMs / length).coerceIn(0.0, 1.0)
            filter.intensity = rainStartIntensity + (rainEndIntensity - rainStartIntensity) * t
        }

        // `RuntimePostEffectShader.updateViewInfo`: Flixel's camera view rect,
        // in world units. The fixed-size world host is the filter's target, so
        // its uv is screen space.
        val zoom = camera.cam.scaleX.coerceAtLeast(0.01)
        val viewWidth = Constants.GAME_WIDTH / zoom
        val viewHeight = Constants.GAME_HEIGHT / zoom
        filter.viewWidth = viewWidth
        filter.viewHeight = viewHeight
        filter.viewLeft = camera.scrollX + (Constants.GAME_WIDTH - viewWidth) / 2.0
        filter.viewTop = camera.scrollY + (Constants.GAME_HEIGHT - viewHeight) / 2.0
    }

    fun onBeat(beat: Int) {
        val lights = trainLights ?: return
        if (beat % 4 != 0) return
        // `phillyTrain*.hxc`: reset the darkness and pick a new traffic light.
        lights.reset()
        val colors = TRAIN_LIGHT_COLORS
        trainColorIndex = kotlin.random.Random.nextInt(colors.size)
        lights.tint = colors[trainColorIndex]
    }

    /** `phillyBlazin.hxc`'s `onNoteHit`: every hit speeds the rain up. */
    fun onNoteHit() {
        if (rainHitScale) rainTimeScale += 0.7
    }

    /** `onGameOver`: the rain must not draw over the death screen. */
    fun onGameOver() {
        val filter = rain ?: return
        if (rainTarget?.filter === filter) rainTarget?.filter = null
    }

    private fun smoothLerpPrecision(base: Double, target: Double, dt: Double, duration: Double): Double {
        if (dt == 0.0 || base == target) return target
        val precision = 0.01
        return target + (base - target) * precision.pow(dt / duration)
    }

    companion object {
        private val DANCERS = listOf(
            "limoDancer1", "limoDancer2", "limoDancer3", "limoDancer4", "limoDancer5",
        )

        private val MAIN_STAGE_LIGHTS = listOf(
            "brightLightSmall", "orangeLight", "lightgreen", "lightred", "lightAbove",
        )

        /** `phillyTrainStage.lightColors`. */
        private val TRAIN_LIGHT_COLORS = listOf(
            RGBA(0x31, 0xA2, 0xFD),
            RGBA(0x31, 0xFD, 0x8C),
            RGBA(0xFB, 0x33, 0xF5),
            RGBA(0xFB, 0xA6, 0x33),
            RGBA(0xFD, 0x45, 0x31),
        )
    }
}
