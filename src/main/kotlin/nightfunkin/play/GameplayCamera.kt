package nightfunkin.play

import korlibs.korge.view.Container
import nightfunkin.core.Constants
import kotlin.math.pow

/**
 * Gameplay camera: smooth follow, zoom tweening and the beat-synced "bop".
 *
 * Ported from `PlayState`'s camera block in 0.8.6 — including the exponential
 * decay (`pow(0.95, dt*60)`) that makes the zoom settle instead of snapping.
 */
class GameplayCamera(
    /** The container whose transform acts as the camera. */
    val cam: Container,
    private val viewportWidth: Double,
    private val viewportHeight: Double,
) {
    /** Where the camera is centred, in world/content coordinates. */
    var focusX: Double = viewportWidth / 2.0
        private set
    var focusY: Double = viewportHeight / 2.0
        private set

    var followRate: Double = Constants.DEFAULT_CAMERA_FOLLOW_RATE.toDouble()
    var followEnabled: Boolean = true

    var zoom: Double = 1.0
        private set
    var stageZoom: Double = 1.0
    var targetZoom: Double = 1.0

    /** Multiplier applied on top of [zoom] while the beat "bop" is active. */
    private var bopMultiplier: Double = 1.0
    private var bopIntensity: Double = Constants.DEFAULT_BOP_INTENSITY.toDouble()

    var bopEnabled: Boolean = true
    var bopRate: Int = Constants.DEFAULT_ZOOM_RATE
    var bopOffset: Int = Constants.DEFAULT_ZOOM_OFFSET
    var hudZoomIntensity: Double = Constants.HUD_ZOOM_INTENSITY.toDouble()

    private var snapNextFrame = true

    // ---- API ------------------------------------------------------------

    fun setFocus(x: Double, y: Double) {
        focusX = x
        focusY = y
    }

    fun tweenZoomTo(value: Double) {
        targetZoom = value
    }

    fun setZoomImmediate(value: Double) {
        zoom = value
        targetZoom = value
    }

    fun setBop(intensity: Double, rate: Int = bopRate, offset: Int = bopOffset) {
        bopIntensity = intensity
        bopRate = rate
        bopOffset = offset
        hudZoomIntensity = (intensity - 1.0) * 2.0
    }

    fun snap() {
        snapNextFrame = true
    }

    /** Called on every step of the Conductor (FNF does the bop in `stepHit`). */
    fun onStep(step: Int) {
        if (!bopEnabled) return
        if (bopRate <= 0) return
        if ((step + bopOffset * 4) % (bopRate * 4) == 0) {
            bopMultiplier = bopIntensity
        }
    }

    // ---- per-frame ------------------------------------------------------

    fun update(dtSeconds: Double) {
        // Exponential decay, exactly like FNF's `pow(0.95, elapsed * 60)`.
        val decay = Constants.BOP_DECAY_PER_FRAME.toDouble().pow(dtSeconds * 60.0)
        bopMultiplier = 1.0 + (bopMultiplier - 1.0) * decay

        zoom += (targetZoom - zoom) * (1.0 - decay).coerceIn(0.0, 1.0)

        val effectiveZoom = (zoom * bopMultiplier).coerceAtLeast(0.01)

        cam.scaleX = effectiveZoom
        cam.scaleY = effectiveZoom
        cam.x = viewportWidth / 2.0 - focusX * effectiveZoom
        cam.y = viewportHeight / 2.0 - focusY * effectiveZoom

        snapNextFrame = false
    }

    /** Screen-space -> content-space, used for HUD placement inside the camera. */
    fun screenToContentX(sx: Double): Double = (sx - cam.x) / cam.scaleX

    fun contentToScreenY(cy: Double): Double = cy * cam.scaleY + cam.y
}
