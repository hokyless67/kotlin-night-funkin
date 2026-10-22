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

    /**
     * Where the camera is heading.
     *
     * FNF never moves the camera directly: `resetCamera` does
     * `FlxG.camera.follow(cameraFollowPoint, LOCKON, Constants.DEFAULT_CAMERA_FOLLOW_RATE)`,
     * so the camera *follows* a point with a lerp and every focus change is a
     * glide rather than a cut.
     */
    var targetX: Double = viewportWidth / 2.0
        private set
    var targetY: Double = viewportHeight / 2.0
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
        targetX = x
        targetY = y
    }

    /** Jumps straight to the target, like FNF's `camera.focusOn(...)`. */
    fun setFocusImmediate(x: Double, y: Double) {
        targetX = x
        targetY = y
        focusX = x
        focusY = y
    }

    /**
     * FNF's `ZoomCamera` supports `mode: "stage"`, where the event value is a
     * multiplier on the stage's default zoom, and `mode: "direct"`, where it is
     * an absolute zoom.
     */
    fun tweenZoomTo(value: Double, relativeToStage: Boolean = false) {
        targetZoom = if (relativeToStage) value * stageZoom else value
    }

    fun setZoomImmediate(value: Double) {
        zoom = value
        targetZoom = value
    }

    fun setBop(intensity: Double, rate: Int = bopRate, offset: Int = bopOffset) {
        // FNF's SetCameraBop intensity is a multiplier on the *bop amount*,
        // not a direct zoom multiplier. A value of 0.5 means half of the
        // default +1.5%, not a 0.5x camera zoom.
        val bopAmount = Constants.DEFAULT_BOP_INTENSITY - 1.0
        bopIntensity = bopAmount * intensity + 1.0
        hudZoomIntensity = bopAmount * intensity * 2.0
        bopRate = rate
        bopOffset = offset
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
        // FNF/Flixel applies `followLerp` once per 1/60 second. Raising the
        // remaining distance to `dt * 60` keeps the same speed on high-refresh
        // displays instead of making the camera lag more at 120/144 Hz.
        if (snapNextFrame) {
            focusX = targetX
            focusY = targetY
            snapNextFrame = false
        } else if (followEnabled) {
            val rate = followRate.coerceIn(0.0, 1.0)
            val follow = 1.0 - (1.0 - rate).pow((dtSeconds * 60.0).coerceAtLeast(0.0))
            focusX += (targetX - focusX) * follow
            focusY += (targetY - focusY) * follow
        }

        // Exponential decay, exactly like FNF's `pow(0.95, elapsed * 60)`.
        val decay = Constants.BOP_DECAY_PER_FRAME.toDouble().pow(dtSeconds * 60.0)
        bopMultiplier = 1.0 + (bopMultiplier - 1.0) * decay

        zoom += (targetZoom - zoom) * (1.0 - decay).coerceIn(0.0, 1.0)

        val effectiveZoom = (zoom * bopMultiplier).coerceAtLeast(0.01)

        cam.scaleX = effectiveZoom
        cam.scaleY = effectiveZoom
        cam.x = viewportWidth / 2.0 - focusX * effectiveZoom
        cam.y = viewportHeight / 2.0 - focusY * effectiveZoom
    }

    /** Screen-space -> content-space, used for HUD placement inside the camera. */
    fun screenToContentX(sx: Double): Double = (sx - cam.x) / cam.scaleX

    fun screenToContentY(sy: Double): Double = (sy - cam.y) / cam.scaleY

    fun contentToScreenY(cy: Double): Double = cy * cam.scaleY + cam.y
}
