package nightfunkin.play

import korlibs.graphics.shader.FragmentShader
import korlibs.graphics.shader.UniformBlock
import korlibs.korge.render.RenderContext
import korlibs.korge.view.filter.ShaderFilter
import korlibs.korge.view.filter.ShaderFilter.BaseProgramProvider
import korlibs.korge.view.filter.ShaderFilter.Companion.fragmentCoords
import korlibs.korge.view.filter.ShaderFilter.Companion.tex
import korlibs.math.geom.Vector4F

/**
 * Port of FNF's `funkin.graphics.shaders.AdjustColorShader` (Rozebud's
 * `applyHSBCEffect`) as a KorGE view filter.
 *
 * The shader is just the HSBC pass that [DropShadowRimFilter] folds into its
 * rim: brightness, then hue, then contrast, then saturation. Here there is no
 * rim, so the same affine transform is applied to the view's pixels and
 * re-premultiplied.
 *
 * `nene-pixel.hxc`'s `addSunsetShaders` puts this on every A-Bot piece except
 * the speaker, which gets the full `DropShadowShader` instead.
 */
class AdjustColorFilter(
    brightness: Double = 0.0,
    hue: Double = 0.0,
    contrast: Double = 0.0,
    saturation: Double = 0.0,
) : ShaderFilter() {

    companion object {
        /**
         * FNF's `AdjustColorShader` has its own program, so it needs its own
         * uniform block. Different binding point than the rim's block.
         */
        object UB : UniformBlock(fixedLocation = 7) {
            val u_Row0 by vec4()
            val u_Row1 by vec4()
            val u_Row2 by vec4()
        }

        private val PROGRAM_PROVIDER = object : BaseProgramProvider() {
            override val fragment: FragmentShader = FragmentShader {
                val c4 = tex(fragmentCoords)
                val alpha = c4["a"]
                // Same premultiplied handling as the rim shader.
                val base = TERNARY(alpha gt 0f.lit, c4["rgb"] / alpha, c4["rgb"])
                val adjusted = vec3(
                    dot(base, UB.u_Row0["xyz"]) + UB.u_Row0["w"],
                    dot(base, UB.u_Row1["xyz"]) + UB.u_Row1["w"],
                    dot(base, UB.u_Row2["xyz"]) + UB.u_Row2["w"],
                )
                SET(out, vec4(adjusted * alpha, alpha))
            }
        }
    }

    private val hsb = DropShadowRimFilter.foldAdjustColor(brightness, hue, contrast, saturation)

    override val programProvider: ProgramProvider = PROGRAM_PROVIDER

    override fun updateUniforms(ctx: RenderContext, filterScale: Double) {
        ctx[UB].push {
            it[u_Row0] = Vector4F(
                hsb.first[0][0].toFloat(), hsb.first[0][1].toFloat(),
                hsb.first[0][2].toFloat(), hsb.second[0].toFloat(),
            )
            it[u_Row1] = Vector4F(
                hsb.first[1][0].toFloat(), hsb.first[1][1].toFloat(),
                hsb.first[1][2].toFloat(), hsb.second[1].toFloat(),
            )
            it[u_Row2] = Vector4F(
                hsb.first[2][0].toFloat(), hsb.first[2][1].toFloat(),
                hsb.first[2][2].toFloat(), hsb.second[2].toFloat(),
            )
        }
    }
}
