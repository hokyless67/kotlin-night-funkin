package nightfunkin.ui

import korlibs.image.bitmap.Bitmap32
import korlibs.image.color.Colors
import korlibs.image.color.RGBA
import korlibs.korge.view.Container
import korlibs.korge.view.Image
import korlibs.korge.view.solidRect
import kotlin.math.roundToInt

/**
 * FNF's default state transition.
 *
 * `InitState` installs `FlxTransitionableState.defaultTransIn/Out` as
 * `TransitionData(FADE, BLACK, ..., direction (0, ±1), ..., region)` with
 * region `(-200, -200, width * 1.4, height * 1.4)`.
 * `flixel.addons.transition.TransitionFade` turns a *vertical* direction into a
 * wipe, not a full-screen fade: a black band (`region.height * 2` tall, solid
 * on one half, a soft gradient on the other) sliding downwards.
 *
 *  - the out transition (`transOut`, 0.7 s, `dirY = +1`) starts fully above the
 *    screen (`y = -band * 2`) with the solid half on top and slides down to
 *    `y = 0`, so the scene is covered top to bottom behind a soft gradient
 *    edge;
 *  - the in transition (`transIn`, 1.0 s, `dirY = -1`) starts fully covered
 *    (`y = -band`, solid half over the screen) and slides down to `y = height`
 *    with the gradient half leading, so the new scene is revealed top to
 *    bottom behind the same soft edge.
 *
 * Geometry is copied from `TransitionFade.makeSprite`: the band is
 * `region.height * 2` tall with each half `1.4 * screen` tall, `1.4 * screen`
 * wide and starts 200 px to the left, so the visible part is always covered.
 */
class SceneWipe(
    width: Double,
    height: Double,
    /** `true` for `transIn` (revealing), `false` for `transOut`. */
    private val reveal: Boolean,
) : Container() {

    /** `region.height` in `TransitionFade`: the screen times 1.4. */
    private val band = height * 1.4

    /** `region.width`: the band is wider than the screen, offset by -200. */
    private val bandWidth = width * 1.4

    /** `FlxG.height`, the in transition's end position. */
    private val screenHeight = height

    init {
        if (reveal) {
            // `dirY = -1`: gradient half on top (transparent -> black), solid
            // half below it, which is what covers the screen at the start.
            addGradient(topAlpha = 0.0, bottomAlpha = 1.0, y = 0.0)
            solidRect(bandWidth, band, Colors.BLACK).also {
                it.x = OFFSET_X
                it.y = band
            }
        } else {
            // `dirY = +1`: solid half on top, gradient half below it
            // (black -> transparent) so the leading edge is soft.
            solidRect(bandWidth, band, Colors.BLACK).also {
                it.x = OFFSET_X
                it.y = 0.0
            }
            addGradient(topAlpha = 1.0, bottomAlpha = 0.0, y = band)
        }
        update(0.0)
    }

    /** `progress` is 0..1 through the transition duration. */
    fun update(progress: Double) {
        val t = progress.coerceIn(0.0, 1.0)
        // `TransitionFade.setTweenValues`, vertical wipe:
        //   OUT: sprite.y  -back.height      -> 0
        //   IN:  sprite.y  -back.height / 2  -> FlxG.height
        y = if (reveal) {
            -band + (band + screenHeight) * t
        } else {
            -2.0 * band + 2.0 * band * t
        }
    }

    private fun addGradient(topAlpha: Double, bottomAlpha: Double, y: Double) {
        // A 1 px wide image stretched horizontally: the vertical fade stays
        // perfectly smooth and costs a few KB.
        Image(gradientBitmap(band.roundToInt(), topAlpha, bottomAlpha)).also {
            it.x = OFFSET_X
            it.y = y
            it.scaleX = bandWidth
            it.smoothing = false
            addChild(it)
        }
    }

    companion object {
        /** `region.x = -200` in `InitState`. */
        private const val OFFSET_X = -200.0

        /**
         * `FlxTransitionableState.skipNextTransIn`: the state being entered
         * does not play its in transition (e.g. Freeplay closes back to the
         * main menu as a substate, LoadingState skips into TitleState).
         */
        private var skipNextReveal: Boolean = false

        /** Marks that the next scene must appear without the in transition. */
        fun requestSkipNextReveal() {
            skipNextReveal = true
        }

        /** Takes (and clears) the pending skip flag. */
        fun consumeSkipNextReveal(): Boolean {
            val value = skipNextReveal
            skipNextReveal = false
            return value
        }

        /** A vertical black gradient; premultiplied black is alpha-only. */
        private fun gradientBitmap(height: Int, topAlpha: Double, bottomAlpha: Double): Bitmap32 {
            val h = height.coerceAtLeast(1)
            val bitmap = Bitmap32(1, h, premultiplied = true)
            for (y in 0 until h) {
                val t = (y + 0.5) / h
                val alpha = (topAlpha + (bottomAlpha - topAlpha) * t).coerceIn(0.0, 1.0)
                bitmap.setRgbaAtIndex(y, RGBA(0, 0, 0, (alpha * 255.0).roundToInt()))
            }
            return bitmap
        }
    }
}
