package nightfunkin.ui

import korlibs.korge.view.Container
import korlibs.korge.view.Image
import korlibs.math.geom.Anchor2D
import nightfunkin.core.AtlasSlice

/**
 * The title screen's "Press Enter to Begin" text.
 *
 * `title-screen-text` is exported from Adobe Animate, but its main timeline
 * only ever shows two layers with complementary alpha. That is simple enough
 * to reproduce directly from the four spritemap frames instead of dragging the
 * whole symbol timeline along:
 *
 * ```
 * Idle:    sprite 0 <-> sprite 1, 45 frames at 24 fps
 * Confirm: sprite 2 / sprite 3 alternating, 8 frames at 24 fps
 * ```
 *
 * The alphas are the values baked into `Animation.json`; they are not a clean
 * linear ramp because Animate quantised them on export.
 */
internal class TitleTextSprite(
    idleBase: AtlasSlice,
    idleAlt: AtlasSlice,
    pressOverblown: AtlasSlice,
    pressUnderblown: AtlasSlice,
) : Container() {

    // Animate draws Layer_2 (sprite 1) first and Layer_3 (sprite 0) on top.
    private val altImage = Image(idleAlt).also {
        it.anchor = Anchor2D.TOP_LEFT
        addChild(it)
    }
    private val baseImage = Image(idleBase).also {
        it.anchor = Anchor2D.TOP_LEFT
        addChild(it)
    }
    private val overblownImage = Image(pressOverblown).also {
        it.anchor = Anchor2D.TOP_LEFT
        addChild(it)
    }
    private val underblownImage = Image(pressUnderblown).also {
        it.anchor = Anchor2D.TOP_LEFT
        addChild(it)
    }

    private var pressed = false
    private var elapsed = 0.0
    private var lastFrame = -1

    init {
        applyIdle(0)
    }

    /** Switches to the eight-frame `Confirm` animation. */
    fun press() {
        if (pressed) return
        pressed = true
        elapsed = 0.0
        lastFrame = -1
        applyPress(0)
    }

    /** Advances the currently playing label. */
    fun update(dt: Double) {
        elapsed += dt
        val frame = (elapsed * FPS).toInt()
        if (frame == lastFrame) return
        lastFrame = frame
        if (pressed) applyPress(frame % PRESS_FRAMES) else applyIdle(frame % IDLE_FRAMES)
    }

    private fun applyIdle(frame: Int) {
        val alpha = IDLE_ALPHAS[frame % IDLE_FRAMES]
        baseImage.visible = true
        altImage.visible = true
        baseImage.alpha = alpha
        altImage.alpha = 1.0 - alpha
        overblownImage.visible = false
        underblownImage.visible = false
    }

    private fun applyPress(frame: Int) {
        baseImage.visible = false
        altImage.visible = false
        val overblown = frame % 2 == 0
        overblownImage.visible = overblown
        underblownImage.visible = !overblown
    }

    companion object {
        private const val FPS = 24.0
        private const val IDLE_FRAMES = 45
        private const val PRESS_FRAMES = 8

        /** `FRAME 2`'s alpha; `FRAME 1` is the complement. */
        private val IDLE_ALPHAS = doubleArrayOf(
            0.00,
            0.05, 0.09, 0.14, 0.18, 0.23, 0.27, 0.32, 0.36, 0.41, 0.45,
            0.50, 0.55, 0.59, 0.64, 0.68, 0.73, 0.77, 0.82, 0.86, 0.91,
            0.95, 1.00, 1.00,
            0.95, 0.91, 0.86, 0.82, 0.77, 0.73, 0.68, 0.64, 0.59, 0.55,
            0.50, 0.45, 0.41, 0.36, 0.32, 0.27, 0.23, 0.18, 0.14, 0.09,
            0.05,
        )
    }
}
