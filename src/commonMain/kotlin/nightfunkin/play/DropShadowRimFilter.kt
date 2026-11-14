package nightfunkin.play

import korlibs.graphics.AGTexture
import korlibs.graphics.DefaultShaders
import korlibs.graphics.ShaderIndices
import korlibs.graphics.shader.FragmentShader
import korlibs.graphics.shader.Sampler
import korlibs.graphics.shader.SamplerVarType
import korlibs.graphics.shader.UniformBlock
import korlibs.image.bitmap.Bitmap32
import korlibs.image.color.RGBA
import korlibs.korge.render.RenderContext
import korlibs.korge.view.filter.ShaderFilter
import korlibs.korge.view.filter.ShaderFilter.BaseProgramProvider
import korlibs.korge.view.filter.ShaderFilter.Companion.fragmentCoords
import korlibs.korge.view.filter.ShaderFilter.Companion.fragmentCoords01
import korlibs.korge.view.filter.ShaderFilter.Companion.tex
import korlibs.korge.view.filter.ShaderFilter.Companion.texture2DZeroOutside
import korlibs.math.geom.Vector4F
import nightfunkin.core.KnfFiles
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import nightfunkin.core.KnfMath

/**
 * Port of FNF's `funkin.graphics.shaders.DropShadowShader` as a KorGE view
 * filter, in the role `tankmanBattlefieldErect.hxc` uses it for: a rim light
 * that looks like light falling on the characters, plus Rozebud's Adjust Color
 * folded into the same pass.
 *
 * The original GLSL:
 *
 * - samples the sprite's luma at an offset (`angle * distance`) and tints the
 *   pixel toward `color` where that sample is transparent, with a threshold
 *   (`smoothstep` around a difference-based edge estimate) keeping dark
 *   outlines unaffected;
 * - runs Rozebud's Adjust Color (brightness/hue/contrast/saturation) on the
 *   base color first.
 *
 * Differences to the source, both structural: the filter runs on KorGE's
 * render-to-texture of the whole character, so the per-frame `angleOffset` is
 * not needed and `fwidth` is replaced by the filter's standard texel derivates.
 * The alt mask is still sampled in *atlas* coordinates like the original, via
 * the per-frame uv transform of [setMaskUv].
 */
class DropShadowRimFilter(
    angle: Double = 0.0,
    distance: Double = 15.0,
    strength: Double = 1.0,
    threshold: Double = 0.1,
    lightColor: RGBA = DEFAULT_LIGHT_COLOR,
    adjustBrightness: Double = 0.0,
    adjustHue: Double = 0.0,
    adjustContrast: Double = 0.0,
    adjustSaturation: Double = 0.0,
) : ShaderFilter() {
    companion object {
        /** `rim.color = 0xFFDFEF3C`: the yellow key light of the stage script. */
        val DEFAULT_LIGHT_COLOR = RGBA(0xDF, 0xEF, 0x3C)

        /**
         * Longest side the alt mask is decoded at.
         *
         * It is only a boolean-ish region test in the shader, and the shipped
         * masks are 8192x4096; at full size that single upload is 128 MB of GL
         * memory for a light rim. 2048 still resolves each frame cell at
         * ~1/4 of its authored size, which is plenty for a threshold region.
         */
        val MASK_TEXTURE_LIMIT: Int
            get() = if (nightfunkin.platform.KnfPlatform.isMobile) 2048 else 4096

        /** Sampler index 0 is the one `ShaderIndices` reserves for users. */
        val u_AltMask by Sampler(ShaderIndices.SAMPLER_INDEX0, SamplerVarType.Sampler2D)

        object UB : UniformBlock(fixedLocation = 6) {
            val u_Dist by float()
            val u_Str by float()
            val u_Thr by float()
            val u_Thr2 by float()
            val u_AngCos by float()
            val u_AngSin by float()
            val u_UseMask by float()
            /** 1 when [u_MaskScale]/[u_MaskOffset] remap the mask uv to the frame. */
            val u_MaskFrame by float()
            val u_DropColor by vec4()
            val u_HsbRow0 by vec4()
            val u_HsbRow1 by vec4()
            val u_HsbRow2 by vec4()
            /** uv01 -> mask uv, see [setMaskUv]. */
            val u_MaskScale by vec4()
            val u_MaskOffset by vec4()
        }

        private val PROGRAM_PROVIDER = object : BaseProgramProvider() {
            override val fragment: FragmentShader = FragmentShader {
                val luma = vec3(0.2126f.lit, 0.7152f.lit, 0.0722f.lit)

                val c4 = tex(fragmentCoords)
                val center = dot(c4["rgb"], luma)

                // `lwidth_manual`: difference-based AA width of the edge.
                val right = dot(tex(fragmentCoords + vec2(1f.lit, 0f.lit))["rgb"], luma)
                val down = dot(tex(fragmentCoords + vec2(0f.lit, 1f.lit))["rgb"], luma)
                val diagonal = dot(tex(fragmentCoords + vec2(1f.lit, 1f.lit))["rgb"], luma)
                val delta =
                    ((abs(right - center) + abs(down - center) + abs(diagonal - center) * 0.7f.lit) / 2.7f.lit) * 2f.lit

                // `getThreshold`: inside the alt mask the mask threshold wins.
                //
                // `DropShadowShader` samples the mask at the sprite's *atlas*
                // uv (the shipped masks, e.g. `erect/masks/gfTankmen_mask`, are
                // painted over the character's atlas). KNF renders the trimmed
                // frame into its own render target, so for legacy sheets the
                // atlas rect of the current frame is folded into a uv
                // transform ([setMaskUv]); Animate characters keep the raw
                // render-target uv, which is their whole animation canvas.
                val maskUv = TERNARY(
                    UB.u_MaskFrame eq 1f.lit,
                    fragmentCoords01 * UB.u_MaskScale["xy"] + UB.u_MaskOffset["xy"],
                    fragmentCoords01,
                )
                val maskIntensity = texture2DZeroOutside(u_AltMask, maskUv, check = false)["b"]
                val threshold = TERNARY(
                    (UB.u_UseMask eq 1f.lit) and (maskIntensity gt 0f.lit),
                    UB.u_Thr2,
                    UB.u_Thr,
                )
                val intensity = smoothstep(threshold - delta, threshold + delta, center)

                // Adjust Color, pre-folded on the CPU into one affine transform
                // (`applyHSBCEffect` is brightness -> hue -> contrast -> saturation).
                val alpha = c4["a"]
                val base = TERNARY(alpha gt 0f.lit, c4["rgb"] / alpha, c4["rgb"])
                val adjusted = vec3(
                    dot(base, UB.u_HsbRow0["xyz"]) + UB.u_HsbRow0["w"],
                    dot(base, UB.u_HsbRow1["xyz"]) + UB.u_HsbRow1["w"],
                    dot(base, UB.u_HsbRow2["xyz"]) + UB.u_HsbRow2["w"],
                )

                // `createDropShadowEx`: the offset sample; anything outside the
                // character render counts as fully transparent.
                val checked = fragmentCoords01 + vec2(
                    UB.u_AngCos * UB.u_Dist * TexInfoUB.u_StdTexDerivates.x,
                    -UB.u_AngSin * UB.u_Dist * TexInfoUB.u_StdTexDerivates.y,
                )
                val shadowAlpha = texture2DZeroOutside(DefaultShaders.u_Tex, checked)["a"]
                val rim = (1f.lit - shadowAlpha * UB.u_Str) * intensity

                SET(out, vec4((adjusted + UB.u_DropColor["xyz"] * rim) * alpha, alpha))
            }
        }

        /**
         * Folds FNF's four `setAdjustColor` setters into one affine transform:
         * `applyHSBCEffect` is `(brightness + color) * hue`, then contrast,
         * then saturation - every step linear except two constant offsets.
         *
         * Shared with [AdjustColorFilter], which is FNF's plain
         * `AdjustColorShader` (the A-Bot pieces on Week 6 Erect).
         */
        internal fun foldAdjustColor(
            b: Double,
            h: Double,
            c: Double,
            s: Double,
        ): Pair<Array<DoubleArray>, DoubleArray> {
            val rad = KnfMath.toRadians(h)
            val cosA = cos(rad)
            val sinA = sin(rad)
            val wR = 0.299
            val wG = 0.587
            val wB = 0.114
            val hue = arrayOf(
                doubleArrayOf(wR + (1 - wR) * cosA - wR * sinA, wG - wG * cosA - wG * sinA, wB - wB * cosA + (1 - wB) * sinA),
                doubleArrayOf(wR - wR * cosA + 0.143 * sinA, wG + (1 - wG) * cosA + 0.140 * sinA, wB - wB * cosA - 0.283 * sinA),
                doubleArrayOf(wR - wR * cosA - (1 - wR) * sinA, wG - wG * cosA + wG * sinA, wB + (1 - wB) * cosA + wB * sinA),
            )

            var sv = if (s > 0) s * 3 else s
            sv = 1 + sv / 100.0
            val lr = 0.2126
            val lg = 0.7152
            val lb = 0.0722
            val inv = 1.0 - sv
            val sat = arrayOf(
                doubleArrayOf(lr * inv + sv, lg * inv, lb * inv),
                doubleArrayOf(lr * inv, lg * inv + sv, lb * inv),
                doubleArrayOf(lr * inv, lg * inv, lb * inv + sv),
            )

            var cv = 1.0 + c / 100.0
            if (cv > 1.0) {
                val e = 2.718281828459045
                cv = ((0.00852259 * e.pow(4.76454 * (cv - 1.0))) * 1.01 - 0.0086078159) * 10.0 + 1.0
            }

            val brightness = b / 255.0
            val k = 0.25 - 0.25 * cv
            val rows = Array(3) { i ->
                DoubleArray(3) { j ->
                    var acc = 0.0
                    for (t in 0..2) acc += sat[i][t] * hue[t][j]
                    acc * cv
                }
            }
            val offsets = DoubleArray(3) { i ->
                var sSum = 0.0
                var brightSum = 0.0
                for (t in 0..2) {
                    sSum += sat[i][t]
                    val hueRowSum = hue[t][0] + hue[t][1] + hue[t][2]
                    brightSum += sat[i][t] * hueRowSum
                }
                cv * brightness * brightSum + k * sSum
            }
            return rows to offsets
        }
    }

    private val dist = distance
    private val str = strength
    private val thr = threshold
    private val angCos = cos(KnfMath.toRadians(angle))
    private val angSin = sin(KnfMath.toRadians(angle))
    private val dropR = lightColor.r / 255.0
    private val dropG = lightColor.g / 255.0
    private val dropB = lightColor.b / 255.0
    private val hsb = foldAdjustColor(adjustBrightness, adjustHue, adjustContrast, adjustSaturation)

    /** `erect/masks/...` image path, set for the tankmen character variants. */
    var altMaskPath: String? = null
        private set

    /** `rim.maskThreshold` (`thr2` in the shader). */
    var maskThreshold: Double = 1.0
        private set

    /** `rim.useAltMask`, toggled by the `EnableMask` song event. */
    var useAltMask: Boolean = false

    /** True when the stage gave this rim an alt mask (`rim.loadAltMask`). */
    val usesAltMask: Boolean get() = altMaskPath != null

    /**
     * uv transform into the alt mask, used instead of the raw render-target uv
     * while [maskFrameActive].
     *
     * `DropShadowShader` samples the mask with the sprite's atlas uv; KNF's
     * filter sees a render target of the trimmed frame instead. [Character]
     * keeps this in sync with the pose it is drawing (see
     * `Character.updateRimMask`).
     */
    private var maskFrameActive = false
    private var maskScaleX = 1.0
    private var maskScaleY = 1.0
    private var maskOffsetX = 0.0
    private var maskOffsetY = 0.0

    /** `renderTargetUv * (scaleX, scaleY) + (offsetX, offsetY)` = mask uv. */
    fun setMaskUv(scaleX: Double, scaleY: Double, offsetX: Double, offsetY: Double) {
        maskFrameActive = true
        maskScaleX = scaleX
        maskScaleY = scaleY
        maskOffsetX = offsetX
        maskOffsetY = offsetY
    }

    fun clearMaskUv() {
        maskFrameActive = false
    }

    private var maskTexture: AGTexture? = null

    /**
     * The decoded mask, capped to [MASK_TEXTURE_LIMIT].
     *
     * The shader only samples it through `fragmentCoords01` to decide whether a
     * pixel is inside the alt-mask region, so resolution beyond a few hundred
     * pixels is wasted GPU memory. FNF's own masks are 8192x4096, i.e. 128 MB
     * on a device that may only have 4096-wide textures.
     */
    private var maskBitmap: Bitmap32? = null

    /** `rim.loadAltMask(path)` + `maskThreshold` + the initial `useAltMask`. */
    fun setAltMask(path: String, thresholdValue: Double, use: Boolean) {
        altMaskPath = path
        maskThreshold = thresholdValue
        useAltMask = use
        // Decode here, during `sceneInit`, not inside `updateUniforms`: the
        // latter runs on the render thread mid-frame, and the tankmen masks are
        // 8192x4096 PNGs (128 MB decoded) - allocating that while a render
        // target is bound is exactly how a phone loses the frame.
        maskBitmap = runCatching {
            // `Paths.image` in FNF resolves through `shared` and the week
            // libraries; the masks live in `week7/images`, while the stage
            // script passes `erect/masks/...` (no week, no extension).
            KnfFiles.resolve("$path.png")
                ?.let { KnfFiles.bitmapOrNull(it, MASK_TEXTURE_LIMIT) }
                ?.toBMP32IfRequired()
        }.getOrNull()
        if (maskBitmap == null) {
            println(
                "KNF: rim mask '$path' not found (looked in " +
                    KnfFiles.resolveCandidates("$path.png") + ")",
            )
        }
        maskTexture = null
    }

    override val programProvider: ProgramProvider = PROGRAM_PROVIDER

    override fun updateUniforms(ctx: RenderContext, filterScale: Double) {
        ctx[UB].push {
            it[u_Dist] = dist.toFloat()
            it[u_Str] = str.toFloat()
            it[u_Thr] = thr.toFloat()
            it[u_Thr2] = maskThreshold.toFloat()
            it[u_AngCos] = angCos.toFloat()
            it[u_AngSin] = angSin.toFloat()
            it[u_UseMask] = if (useAltMask) 1f else 0f
            it[u_MaskFrame] = if (maskFrameActive) 1f else 0f
            it[u_DropColor] = Vector4F(dropR.toFloat(), dropG.toFloat(), dropB.toFloat(), 0f)
            it[u_MaskScale] = Vector4F(maskScaleX.toFloat(), maskScaleY.toFloat(), 0f, 0f)
            it[u_MaskOffset] = Vector4F(maskOffsetX.toFloat(), maskOffsetY.toFloat(), 0f, 0f)
            it[u_HsbRow0] = Vector4F(
                hsb.first[0][0].toFloat(), hsb.first[0][1].toFloat(),
                hsb.first[0][2].toFloat(), hsb.second[0].toFloat(),
            )
            it[u_HsbRow1] = Vector4F(
                hsb.first[1][0].toFloat(), hsb.first[1][1].toFloat(),
                hsb.first[1][2].toFloat(), hsb.second[1].toFloat(),
            )
            it[u_HsbRow2] = Vector4F(
                hsb.first[2][0].toFloat(), hsb.first[2][1].toFloat(),
                hsb.first[2][2].toFloat(), hsb.second[2].toFloat(),
            )
        }
        // The mask sampler must always be bound: the shader samples it
        // unconditionally and throws the result away when `u_UseMask` is 0.
        setTex(ctx, u_AltMask, resolveMaskTexture(ctx))
    }

    private fun resolveMaskTexture(ctx: RenderContext): AGTexture? {
        maskTexture?.let { return it }
        val bitmap = maskBitmap
        if (bitmap == null) {
            // A transparent 1x1 fallback: mask intensity 0 keeps the base
            // threshold active even if `useAltMask` is on.
            return AGTexture().also {
                it.upload(Bitmap32(1, 1, intArrayOf(0), false))
            }.also { maskTexture = it }
        }
        return AGTexture().also { it.upload(bitmap) }.also {
            maskTexture = it
            // The texture keeps its own reference to the pixels; this CPU copy
            // is one upload's worth of memory (32 MB at 4096x2048) and never
            // changes.
            maskBitmap = null
        }
    }
}
