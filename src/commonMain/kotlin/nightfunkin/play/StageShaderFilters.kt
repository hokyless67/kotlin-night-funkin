package nightfunkin.play

import korlibs.graphics.AGTexture
import korlibs.graphics.DefaultShaders
import korlibs.graphics.ShaderIndices
import korlibs.graphics.shader.FragmentShader
import korlibs.graphics.shader.Operand
import korlibs.graphics.shader.Program
import korlibs.graphics.shader.Sampler
import korlibs.graphics.shader.SamplerVarType
import korlibs.graphics.shader.UniformBlock
import korlibs.image.bitmap.Bitmap32
import korlibs.image.color.Colors
import korlibs.image.color.RGBA
import korlibs.korge.render.RenderContext
import korlibs.korge.view.filter.ShaderFilter
import korlibs.korge.view.filter.ShaderFilter.BaseProgramProvider
import korlibs.korge.view.filter.ShaderFilter.Companion.fragmentCoords
import korlibs.korge.view.filter.ShaderFilter.Companion.fragmentCoords01
import korlibs.korge.view.filter.ShaderFilter.Companion.tex
import korlibs.math.geom.Vector4F
import nightfunkin.core.Constants
import nightfunkin.core.KnfFiles
import nightfunkin.core.KnfMath
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.sin

/**
 * `funkin.graphics.shaders.WiggleEffectRuntime` (`assets/preload/shaders/wiggle.frag`).
 *
 * Week 6 (`schoolEvil` / `schoolEvilErect`) puts this on the school, the
 * ground and the trees; the only mode the shipped stages use is DREAMY, but
 * the other four are ported as well since the source runtime exposes them.
 */
class WiggleFilter(
    private val speed: Double,
    private val frequency: Double,
    private val amplitude: Double,
    private val effectType: Int = EFFECT_DREAMY,
    /** Source frame size in pixels, *before* the sprite's stage scale. */
    private val sourceWidth: Int = 1,
    private val sourceHeight: Int = 1,
) : ShaderFilter() {

    companion object {
        const val EFFECT_DREAMY = 0
        const val EFFECT_WAVY = 1
        const val EFFECT_HEAT_HORIZONTAL = 2
        const val EFFECT_HEAT_VERTICAL = 3
        const val EFFECT_FLAG = 4

        object UB : UniformBlock(fixedLocation = 8) {
            val u_Time by float()
            val u_Speed by float()
            val u_Frequency by float()
            val u_Amplitude by float()
            val u_EffectType by float()
            /** `openfl_TextureSize` of the sprite's source frame. */
            val u_SourceSize by vec4()
        }

        private val PROGRAM_PROVIDER = object : BaseProgramProvider() {
            override val fragment: FragmentShader = FragmentShader {
                val uv = fragmentCoords01
                // `w = 1/openfl_TextureSize.y`, `h = 1/openfl_TextureSize.x`.
                //
                // This has to be the *source* frame size, not the filter's
                // render target: Week 6 scales its pixel art 6x, and using the
                // render target would quantise the dreamy rows to screen
                // pixels instead of source pixels (a visible row-by-row
                // shimmer that the original does not have).
                val sourceSize = UB.u_SourceSize
                val w = 1f.lit / sourceSize["y"]
                val h = 1f.lit / sourceSize["x"]

                val type = UB.u_EffectType
                val speed = UB.u_Speed
                val frequency = UB.u_Frequency
                val amplitude = UB.u_Amplitude
                val time = UB.u_Time

                // DREAMY.
                val dreamyX0 = floor(uv["x"] / h) * h
                val offsetX = sin(dreamyX0 * frequency + time * speed) * amplitude
                val dreamyY0 = uv["y"] + floor(offsetX / w) * w
                val dreamyY = floor(dreamyY0 / w) * w
                val offsetY = sin(
                    dreamyY * (frequency * 0.5f.lit) + time * (speed * 0.5f.lit),
                ) * (amplitude * 0.5f.lit)
                val dreamyX = uv["x"] + floor(offsetY / h) * h

                // WAVY.
                val wavyY = uv["y"] + sin(uv["x"] * frequency + time * speed) * amplitude
                // HEAT_WAVE_HORIZONTAL / VERTICAL.
                val heatX = uv["x"] + sin(uv["x"] * frequency + time * speed) * amplitude
                val heatY = uv["y"] + sin(uv["y"] * frequency + time * speed) * amplitude
                // FLAG.
                val flagY = uv["y"] +
                    sin(uv["y"] * frequency + 10f.lit * uv["x"] + time * speed) * amplitude
                val flagX = uv["x"] +
                    sin(uv["x"] * frequency + 5f.lit * uv["y"] + time * speed) * amplitude

                val x = TERNARY(
                    type eq EFFECT_DREAMY.toFloat().lit,
                    dreamyX,
                    TERNARY(
                        type eq EFFECT_HEAT_HORIZONTAL.toFloat().lit,
                        heatX,
                        TERNARY(type eq EFFECT_FLAG.toFloat().lit, flagX, uv["x"]),
                    ),
                )
                val y = TERNARY(
                    type eq EFFECT_DREAMY.toFloat().lit,
                    dreamyY,
                    TERNARY(
                        type eq EFFECT_WAVY.toFloat().lit,
                        wavyY,
                        TERNARY(
                            type eq EFFECT_HEAT_VERTICAL.toFloat().lit,
                            heatY,
                            TERNARY(type eq EFFECT_FLAG.toFloat().lit, flagY, uv["y"]),
                        ),
                    ),
                )

                SET(out, texture2D(DefaultShaders.u_Tex, vec2(x, y)))
            }
        }
    }

    var time: Double = 0.0

    fun update(elapsed: Double) {
        time += elapsed
    }

    override val programProvider: ProgramProvider = PROGRAM_PROVIDER

    override fun updateUniforms(ctx: RenderContext, filterScale: Double) {
        ctx[UB].push {
            it[u_Time] = time.toFloat()
            it[u_Speed] = speed.toFloat()
            it[u_Frequency] = frequency.toFloat()
            it[u_Amplitude] = amplitude.toFloat()
            it[u_EffectType] = effectType.toFloat()
            it[u_SourceSize] = Vector4F(
                sourceWidth.coerceAtLeast(1).toFloat(),
                sourceHeight.coerceAtLeast(1).toFloat(),
                0f, 0f,
            )
        }
    }
}

/**
 * `BuildingEffectShader` (`assets/preload/shaders/building.frag`).
 *
 * Week 3 (`phillyTrain`) and Week 7 Erect (`phillyTrainErect`) use it on the
 * train windows: the whole sprite is darkened by `alphaShit`, which grows
 * with the beat and resets every four beats, while the sprite is tinted with
 * a random traffic-light colour.
 */
class BuildingEffectFilter : ShaderFilter() {

    companion object {
        object UB : UniformBlock(fixedLocation = 9) {
            val u_Alpha by float()
            val u_Tint by vec4()
        }

        private val PROGRAM_PROVIDER = object : BaseProgramProvider() {
            override val fragment: FragmentShader = FragmentShader {
                val c4 = tex(fragmentCoords)
                val alpha = c4["a"]
                val base = TERNARY(alpha gt 0f.lit, c4["rgb"] / alpha, c4["rgb"])
                val dark = vec3(UB.u_Alpha, UB.u_Alpha, UB.u_Alpha)
                SET(out, vec4((base * UB.u_Tint["xyz"] - dark) * alpha, alpha))
            }
        }
    }

    /** `BuildingEffectShader.effectAlpha`. */
    var alphaShit: Double = 0.0

    /** `FlxSprite.color` (multiplied after the shader). */
    var tint: RGBA = Colors.WHITE

    /** `BuildingEffectShader.update(elapsed)`. */
    fun update(elapsed: Double, speed: Double = 1.0) {
        alphaShit += speed * elapsed
    }

    /** `BuildingEffectShader.reset()`. */
    fun reset() {
        alphaShit = 0.0
    }

    override val programProvider: ProgramProvider = PROGRAM_PROVIDER

    override fun updateUniforms(ctx: RenderContext, filterScale: Double) {
        ctx[UB].push {
            it[u_Alpha] = alphaShit.toFloat()
            it[u_Tint] = Vector4F(
                tint.r / 255f, tint.g / 255f, tint.b / 255f, 1f,
            )
        }
    }
}

/**
 * `funkin.graphics.shaders.RuntimeRainShader` (`assets/preload/shaders/rain.frag`).
 *
 * Philly Streets, Blazin' and the Erect Spooky Mansion apply this as a camera
 * post effect, while `spookyMansionErect` puts the very same shader on its
 * `bgTrees` sprite (`spriteMode = true`). The puddle/light sections of the
 * original are commented out in 0.8.6, so what is left is the layered rain
 * distortion plus a colour mix - which is exactly what this port runs.
 */
class RainFilter(
    scale: Double = 1.0,
    intensity: Double = 0.5,
    color: RGBA = DEFAULT_RAIN_COLOR,
) : ShaderFilter() {

    companion object {
        val DEFAULT_RAIN_COLOR = RGBA(0x66, 0x80, 0xCC)

        object UB : UniformBlock(fixedLocation = 10) {
            val u_Time by float()
            val u_Scale by float()
            val u_Intensity by float()
            /** `(camera.viewLeft, camera.viewTop, viewWidth, viewHeight)`. */
            val u_View by vec4()
            val u_RainColor by vec4()
        }

        private val SCALES = floatArrayOf(1.0f, 1.8f, 2.6f, 4.8f)

        /** `rain.frag`'s `rand`. */
        private fun Program.ExpressionBuilder.rainRand(v: Operand): Operand =
            fract(
                sin(
                    dot(
                        mod(v, vec2(1000f.lit, 1000f.lit)),
                        vec2(12.9898f.lit, 78.233f.lit),
                    ),
                ) * 43758.5453f.lit,
            )

        /**
         * `rain.frag`'s `rainDist`, written with `TEMP`s.
         *
         * KorGE's shader DSL inlines an operand at every use site: the chained
         * `p = ...` assignments of the original made the generated GLSL grow
         * exponentially (each step re-expands the previous one, ~6x per rain
         * layer), which produced a gigantic program and made the driver hang
         * while compiling it. Storing every step in a temp keeps the source
         * the size of the hand-written GLSL.
         */
        private fun Program.Builder.rainDist(
            p0: Operand,
            scale: Float,
            intensity: Operand,
        ): Operand {
            val s = scale.lit
            // p *= 0.1
            val p = TEMP(p0 * 0.1f.lit)
            // p.x += p.y * 0.1
            SET(p["x"], p["x"] + p["y"] * 0.1f.lit)
            // p.y -= uTime * 500 / scale
            SET(p["y"], p["y"] - UB.u_Time * (500f.lit / s))
            // p.y *= 0.03
            SET(p["y"], p["y"] * 0.03f.lit)
            // float ix = floor(p.x)
            val ix = TEMP(floor(p["x"]))
            // p.y += mod(ix, 2.0) * 0.5 + (rand(vec2(ix)) - 0.5) * 0.3
            SET(
                p["y"],
                p["y"] + mod(ix, 2f.lit) * 0.5f.lit +
                    (rainRand(vec2(ix, ix)) - 0.5f.lit) * 0.3f.lit,
            )
            // float iy = floor(p.y)
            val iy = TEMP(floor(p["y"]))
            val index = TEMP(vec2(ix, iy))
            // p -= index
            SET(p, p - index)
            // p.x += (rand(index.yx) * 2.0 - 1.0) * 0.35
            SET(p["x"], p["x"] + (rainRand(vec2(iy, ix)) * 2f.lit - 1f.lit) * 0.35f.lit)
            // vec2 a = abs(p - 0.5)
            val a = TEMP(abs(p - 0.5f.lit))
            // float res = max(a.x * 0.8, a.y * 0.5) - 0.1
            val res = TEMP(max(a["x"] * 0.8f.lit, a["y"] * 0.5f.lit) - 0.1f.lit)
            // return (rand(index) < mix(1.0, 0.1, intensity)) ? 1.0 : res
            return TERNARY(
                rainRand(index) lt mix(1f.lit, 0.1f.lit, intensity),
                1f.lit,
                res,
            )
        }

        private val PROGRAM_PROVIDER = object : BaseProgramProvider() {
            override val fragment: FragmentShader = FragmentShader {
                val uv = fragmentCoords01
                val viewLeft = UB.u_View["x"]
                val viewTop = UB.u_View["y"]
                val viewSize = TEMP(vec2(UB.u_View["z"], UB.u_View["w"]))

                // `vec2 wpos = screenToWorld(screenCoord)`.
                val origin = TEMP(vec2(viewLeft, viewTop) + uv * viewSize)
                val wpos = TEMP(origin)
                val add = TEMP(vec3(0f, 0f, 0f))
                val rainSum = TEMP(0f.lit)

                for (i in SCALES.indices) {
                    val scale = SCALES[i]
                    val p = wpos * (scale.lit / UB.u_Scale) + (500f * i).lit
                    val r = TEMP(rainDist(p, scale, UB.u_Intensity))
                    IF(r lt 0f.lit) {
                        val v = TEMP((1f.lit - exp(r * 5f.lit)) / scale.lit * 2f.lit)
                        SET(wpos["x"], wpos["x"] + v * 10f.lit * UB.u_Scale)
                        SET(wpos["y"], wpos["y"] - v * 2f.lit * UB.u_Scale)
                        SET(add, add + vec3(0.1f, 0.15f, 0.2f) * v)
                        SET(rainSum, rainSum + (1f.lit - rainSum) * 0.75f.lit)
                    }
                }

                val screenUv = uv + (wpos - origin) / viewSize
                val c4 = TEMP(texture2D(DefaultShaders.u_Tex, screenUv))
                val alpha = TEMP(c4["a"])
                val base = TERNARY(alpha gt 0f.lit, c4["rgb"] / alpha, c4["rgb"])
                val rainColor = vec3(UB.u_RainColor["x"], UB.u_RainColor["y"], UB.u_RainColor["z"])
                val rgb = mix(base + add, rainColor, 0.1f.lit * rainSum)
                SET(out, vec4(rgb * alpha, alpha))
            }
        }
    }

    var time: Double = 1.0
    var scale: Double = scale
    var intensity: Double = intensity
    var rainColor: RGBA = color

    /** Flixel's camera view, in world units (already divided by the zoom). */
    var viewLeft: Double = 0.0
    var viewTop: Double = 0.0
    var viewWidth: Double = Constants.GAME_WIDTH.toDouble()
    var viewHeight: Double = Constants.GAME_HEIGHT.toDouble()

    fun update(elapsed: Double) {
        time += elapsed
    }

    override val programProvider: ProgramProvider = PROGRAM_PROVIDER

    override fun updateUniforms(ctx: RenderContext, filterScale: Double) {
        ctx[UB].push {
            it[u_Time] = time.toFloat()
            it[u_Scale] = scale.toFloat()
            it[u_Intensity] = intensity.toFloat()
            it[u_View] = Vector4F(
                viewLeft.toFloat(), viewTop.toFloat(),
                viewWidth.toFloat(), viewHeight.toFloat(),
            )
            it[u_RainColor] = Vector4F(
                rainColor.r / 255f, rainColor.g / 255f, rainColor.b / 255f, 1f,
            )
        }
    }
}

/**
 * `funkin.graphics.shaders.OverlayBlend` (the inline shader in
 * `OverlayBlend.hx`).
 *
 * `limoRide.hxc` paints the sun with an overlay of `limo/limoOverlay`.
 * (`limoRideErect.hxc` keeps the same code but the assignment is commented
 * out in 0.8.6, so it is not applied there.)
 */
class OverlayBlendFilter(
    /** `Paths.image('limo/limoOverlay')`. */
    private val overlay: Bitmap32?,
) : ShaderFilter() {

    companion object {
        /**
         * `ShaderIndices.SAMPLER_INDEX0` is the unit KorGE reserves for user
         * samplers. `SAMPLER_INDEX1` is `u_Tex` - the filter's own input - so
         * binding the overlay there overwrote the render target: both samplers
         * ended up reading the overlay image, and the sunset collapsed into a
         * stretched copy of it.
         */
        val u_Overlay by Sampler(ShaderIndices.SAMPLER_INDEX0, SamplerVarType.Sampler2D)

        private val PROGRAM_PROVIDER = object : BaseProgramProvider() {
            override val fragment: FragmentShader = FragmentShader {
                val c4 = tex(fragmentCoords)
                val uv = fragmentCoords01

                // `blendOverlay` works on the full vec4, including alpha:
                //   mix(1 - 2*(1-base)*(1-blend), 2*base*blend, step(base, 0.5))
                val gf4 = texture2D(u_Overlay, uv + vec2(0.1f.lit, 0.2f.lit))
                val one = vec4(1f.lit, 1f.lit, 1f.lit, 1f.lit)
                val half = vec4(0.5f.lit, 0.5f.lit, 0.5f.lit, 0.5f.lit)

                val dark = one - 2f.lit * (one - c4) * (one - gf4)
                val light = 2f.lit * c4 * gf4
                val mixed = mix(dark, light, step(c4, half))
                SET(out, mixed)
            }
        }

        /** Decode at scene init, never inside the render thread. */
        fun loadOverlay(): Bitmap32? = runCatching {
            KnfFiles.resolve("limo/limoOverlay.png")
                ?.let { KnfFiles.bitmapOrNull(it) }
                ?.toBMP32IfRequired()
        }.getOrNull().also {
            if (it == null) println("KNF: stage overlay 'limo/limoOverlay' not found")
        }
    }

    private var texture: AGTexture? = null

    override val programProvider: ProgramProvider = PROGRAM_PROVIDER

    override fun updateUniforms(ctx: RenderContext, filterScale: Double) {
        val tex = texture ?: run {
            val bmp = overlay ?: Bitmap32(1, 1, intArrayOf(0), false)
            AGTexture().also {
                it.upload(bmp)
                texture = it
            }
        }
        setTex(ctx, u_Overlay, tex)
    }
}

/**
 * `funkin.graphics.shaders.SserafimShader` (the inline GLSL in
 * `SserafimShader.hx`), used by the LE SSERAFIM collab stage.
 *
 * The original folds Rozebud's HSBC "Adjust Color" pass and a light-driven
 * multiply into one fragment shader. Here the HSBC part is pre-composed on the
 * CPU into a 3x4 matrix (`u_Row0..2`, exactly like [AdjustColorFilter]), and
 * the light multiply is a per-frame uniform (`u_Mul`): the values are uniforms
 * in the original too, so this is the same picture without re-deriving the
 * hue matrix per pixel.
 *
 * `darkFactor` scales differently for the characters (`isChar`), which is why
 * the stage and the characters each own an instance of this filter.
 */
class SserafimFilter(
    /** `SserafimShader(true)` for the characters, `false` for the stage. */
    val isCharacter: Boolean = false,
) : ShaderFilter() {

    companion object {
        object UB : UniformBlock(fixedLocation = 11) {
            /** Contrast + hue pass, `w` carrying the additive term. */
            val u_Row0 by vec4()
            val u_Row1 by vec4()
            val u_Row2 by vec4()
            /** `grayscaleValues` from the shader. */
            val u_Gray by vec4()
            /** `1 + saturation/100` (the source triples positive values). */
            val u_Sat by float()
            /** Final per-channel multiply (`multiplyColor`). */
            val u_Mul by vec4()
        }

        private val GRAY = doubleArrayOf(
            0.3098039215686275, 0.607843137254902, 0.0823529411764706,
        )
        private const val E = 2.718281828459045

        private val PROGRAM_PROVIDER = object : BaseProgramProvider() {
            override val fragment: FragmentShader = FragmentShader {
                val c4 = tex(fragmentCoords)
                val alpha = c4["a"]
                val base = TERNARY(alpha gt 0f.lit, c4["rgb"] / alpha, c4["rgb"])

                // brightness -> hue -> contrast (the original clamps here)
                val hsbc = clamp(
                    vec3(
                        dot(base, UB.u_Row0["xyz"]) + UB.u_Row0["w"],
                        dot(base, UB.u_Row1["xyz"]) + UB.u_Row1["w"],
                        dot(base, UB.u_Row2["xyz"]) + UB.u_Row2["w"],
                    ),
                    0f.lit,
                    1f.lit,
                )
                // saturation: clamp(mix(vec3(dot(c, grayscale)), c, value))
                val gray = vec3(dot(hsbc, UB.u_Gray["xyz"]))
                val saturated = clamp(mix(gray, hsbc, UB.u_Sat), 0f.lit, 1f.lit)
                val outColor = saturated * UB.u_Mul["xyz"]
                SET(out, vec4(outColor * alpha, alpha))
            }
        }

        /** `applyHueRotate`'s matrix, row-major after the column-major literals. */
        private fun hueRows(degrees: Double): Array<DoubleArray> {
            val angle = KnfMath.toRadians(degrees)
            val c = cos(angle)
            val s = sin(angle)
            // GLSL `mat3(...)` is column-major; the literals below are already
            // written in row form (`row = (m00, m01, m02)`).
            // `m = m1 + cos(angle)*m2 + sin(angle)*m3`.
            val m1 = arrayOf(
                doubleArrayOf(0.213, 0.715, 0.072),
                doubleArrayOf(0.213, 0.715, 0.072),
                doubleArrayOf(0.213, 0.715, 0.072),
            )
            val m2 = arrayOf(
                doubleArrayOf(0.787, -0.715, -0.072),
                doubleArrayOf(-0.213, 0.285, -0.072),
                doubleArrayOf(-0.213, -0.715, 0.928),
            )
            val m3 = arrayOf(
                doubleArrayOf(-0.213, -0.715, 0.928),
                doubleArrayOf(0.143, 0.140, -0.283),
                doubleArrayOf(-0.787, 0.715, 0.072),
            )
            return Array(3) { r -> DoubleArray(3) { col -> m1[r][col] + c * m2[r][col] + s * m3[r][col] } }
        }

        /** `hue2rgb` from the shader. */
        private fun hue2rgb(hue: Double): DoubleArray {
            var h = hue - floor(hue)
            fun clamp01(v: Double) = v.coerceIn(0.0, 1.0)
            return doubleArrayOf(
                clamp01(abs(h * 6.0 - 3.0) - 1.0),
                clamp01(2.0 - abs(h * 6.0 - 2.0)),
                clamp01(2.0 - abs(h * 6.0 - 4.0)),
            )
        }

        /** `rgb2hsl` from the shader. */
        private fun rgb2hsl(c: DoubleArray): DoubleArray {
            val cMin = minOf(c[0], c[1], c[2])
            val cMax = maxOf(c[0], c[1], c[2])
            val delta = cMax - cMin
            val l = (cMax + cMin) / 2.0
            var h = 0.0
            var s = 0.0
            if (delta != 0.0) {
                s = if (l < 0.5) delta / (cMax + cMin) else delta / (2.0 - cMax - cMin)
                val dR = (((cMax - c[0]) / 6.0) + delta / 2.0) / delta
                val dG = (((cMax - c[1]) / 6.0) + delta / 2.0) / delta
                val dB = (((cMax - c[2]) / 6.0) + delta / 2.0) / delta
                h = when {
                    c[0] == cMax -> dB - dG
                    c[1] == cMax -> 1.0 / 3.0 + dR - dB
                    else -> 2.0 / 3.0 + dG - dR
                }
                h -= floor(h)
            }
            return doubleArrayOf(h, s, l)
        }

        /** `hsl2rgb` from the shader. */
        private fun hsl2rgb(hsl: DoubleArray): DoubleArray {
            if (hsl[1] == 0.0) return doubleArrayOf(hsl[2], hsl[2], hsl[2])
            val b = if (hsl[2] < 0.5) hsl[2] * (1.0 + hsl[1]) else hsl[2] + hsl[1] - hsl[1] * hsl[2]
            val a = 2.0 * hsl[2] - b
            val rgb = hue2rgb(hsl[0])
            return doubleArrayOf(
                a + rgb[0] * (b - a),
                a + rgb[1] * (b - a),
                a + rgb[2] * (b - a),
            )
        }
    }

    // ---- HSBC inputs -----------------------------------------------------
    private var brightness = 0.0
    private var hue = 0.0
    private var contrast = 0.0
    private var saturation = 0.0

    private var row0 = Vector4F(1f, 0f, 0f, 0f)
    private var row1 = Vector4F(0f, 1f, 0f, 0f)
    private var row2 = Vector4F(0f, 0f, 1f, 0f)
    private var sat = 1.0

    // ---- light multiply --------------------------------------------------
    var darkenAmount: Double = 0.0
    var pulseLightColor: RGBA = Colors.WHITE
    var pulseLightStrength: Double = 0.0
    var truckLightStrength: Double = 0.0

    init {
        rebuildHsbc()
    }

    /** `SserafimShader.setAdjustColor(brightness, hue, contrast, saturation)`. */
    fun setAdjustColor(
        brightness: Double = this.brightness,
        hue: Double = this.hue,
        contrast: Double = this.contrast,
        saturation: Double = this.saturation,
    ) {
        this.brightness = brightness
        this.hue = hue
        this.contrast = contrast
        this.saturation = saturation
        rebuildHsbc()
    }

    private fun rebuildHsbc() {
        // hue rotation
        val h = hueRows(hue)
        // contrast
        var cv = 1.0 + contrast / 100.0
        if (cv > 1.0) {
            cv = (((0.00852259 * E.pow(4.76454 * (cv - 1.0))) * 1.01) - 0.0086078159) * 10.0 + 1.0
        }
        // brightness is a `b/255` offset applied before the hue rotation
        val b = brightness / 255.0
        val k = 0.25 - 0.25 * cv
        val rows = Array(3) { r -> DoubleArray(3) { c -> cv * h[r][c] } }
        val offsets = DoubleArray(3) { r ->
            var sum = 0.0
            for (c in 0..2) sum += h[r][c]
            cv * b * sum + k
        }
        row0 = Vector4F(rows[0][0].toFloat(), rows[0][1].toFloat(), rows[0][2].toFloat(), offsets[0].toFloat())
        row1 = Vector4F(rows[1][0].toFloat(), rows[1][1].toFloat(), rows[1][2].toFloat(), offsets[1].toFloat())
        row2 = Vector4F(rows[2][0].toFloat(), rows[2][1].toFloat(), rows[2][2].toFloat(), offsets[2].toFloat())

        // saturation, including the source's "positives are tripled" quirk
        val sv = if (saturation > 0.0) saturation * 3.0 else saturation
        sat = 1.0 + sv / 100.0
    }

    /** The `multiplyColor` term, `mix(mix(white, black, d), bruhColor, d/n)`. */
    private fun multiplyColor(): DoubleArray {
        val combined = truckLightStrength + pulseLightStrength
        var darkFactor = combined * 0.07 + darkenAmount
        if (darkenAmount > 0.65) darkFactor = combined * -0.07 + darkenAmount

        val bruh = hsl2rgb(
            rgb2hsl(doubleArrayOf(
                pulseLightColor.r / 255.0,
                pulseLightColor.g / 255.0,
                pulseLightColor.b / 255.0,
            )).also { it[2] *= pulseLightStrength },
        )

        val divisor = if (isCharacter) 5.0 else 1.0
        val strengthDivisor = if (isCharacter) 3.0 else 2.0
        return DoubleArray(3) {
            val dark = 1.0 + (0.0 - 1.0) * (darkFactor / divisor)
            dark + (bruh[it] - dark) * (darkFactor / strengthDivisor)
        }
    }

    override val programProvider: ProgramProvider = PROGRAM_PROVIDER

    override fun updateUniforms(ctx: RenderContext, filterScale: Double) {
        val mul = multiplyColor()
        ctx[UB].push {
            it[u_Row0] = row0
            it[u_Row1] = row1
            it[u_Row2] = row2
            it[u_Gray] = Vector4F(GRAY[0].toFloat(), GRAY[1].toFloat(), GRAY[2].toFloat(), 0f)
            it[u_Sat] = sat.toFloat()
            it[u_Mul] = Vector4F(mul[0].toFloat(), mul[1].toFloat(), mul[2].toFloat(), 1f)
        }
    }
}
