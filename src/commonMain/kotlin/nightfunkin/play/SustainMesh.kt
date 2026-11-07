package nightfunkin.play

import korlibs.image.bitmap.Bitmaps
import korlibs.image.bitmap.BmpSlice
import korlibs.korge.render.RenderContext
import korlibs.korge.render.TexturedVertexArray
import korlibs.korge.view.View
import korlibs.math.geom.BoundsBuilder
import korlibs.math.geom.Point
import korlibs.math.geom.Rectangle
import korlibs.memory.Float32Buffer
import korlibs.memory.Uint16Buffer
import korlibs.memory.UShortArrayInt

/**
 * A [korlibs.korge.view.Mesh] that honours the skin's filtering mode.
 *
 * KorGE's stock `Mesh.renderInternal` always passes `smoothing = true` to
 * `BatchBuilder2D.drawVertices`, so setting `filter` on the view has no effect.
 * FNF keeps the hold-note mesh pixel-perfect for the pixel skin
 * (`SustainTrail.setupHoldNoteGraphic`: `isPixel = true; antialiasing = false`),
 * while the normal skin is antialiased. This copy is the stock KorGE mesh with
 * that one flag made configurable.
 */
class SustainMesh(
    var texture: BmpSlice? = null,
    var vertices: Float32Buffer = Float32Buffer(0),
    var uvs: Float32Buffer = Float32Buffer(0),
    var indices: Uint16Buffer = Uint16Buffer(0),
    var smoothing: Boolean = true,
) : View() {
    val textureNN get() = texture ?: Bitmaps.white

    var pivotX: Double = 0.0
    var pivotY: Double = 0.0

    fun updatedVertices() {
        dirtyVertices = true
    }

    private var tva: TexturedVertexArray? = null
    private var localBounds = Rectangle()

    private fun recomputeVerticesIfRequired() {
        if (!dirtyVertices) return
        dirtyVertices = false

        val m = globalMatrix
        val cmul = this.renderColorMul
        val vcount = vertices.size / 2
        val isize = indices.size

        var bb = BoundsBuilder()

        if (vcount > 0 || isize > 0) {
            val tva = when {
                tva == null || vcount > tva!!.initialVcount || isize > tva!!.indices.size -> {
                    TexturedVertexArray(vcount, ShortArray(isize))
                }
                else -> tva
            }
            this.tva = tva!!
            tva.vcount = vcount
            tva.icount = isize

            val pivotXf = pivotX.toFloat()
            val pivotYf = pivotY.toFloat()
            for (n in 0 until tva.icount) tva.indices[n] = indices[n].toShort()
            for (n in 0 until tva.vcount) {
                val x = vertices[n * 2 + 0] + pivotXf
                val y = vertices[n * 2 + 1] + pivotYf

                tva.quadV(n, m.transformX(x, y), m.transformY(x, y), uvs[n * 2 + 0], uvs[n * 2 + 1], cmul)
                bb += Point(x, y)
            }
        }
        localBounds = bb.bounds
    }

    override fun renderInternal(ctx: RenderContext) {
        recomputeVerticesIfRequired()
        tva?.let { tva ->
            ctx.useBatcher { batch ->
                batch.drawVertices(tva, ctx.getTex(textureNN).base, smoothing, renderBlendMode)
            }
        }
    }

    override fun getLocalBoundsInternal(): Rectangle {
        recomputeVerticesIfRequired()
        return localBounds
    }
}
