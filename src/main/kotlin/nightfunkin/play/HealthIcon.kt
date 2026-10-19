package nightfunkin.play

import korlibs.image.bitmap.Bitmap
import korlibs.image.bitmap.Bitmap32
import korlibs.image.bitmap.sliceWithSize
import korlibs.korge.view.Image
import korlibs.math.geom.Anchor2D
import korlibs.math.geom.slice.RectSlice
import nightfunkin.core.KnfFiles
import nightfunkin.core.setPixelArt
/**
 * The little character head that rides the health bar.
 *
 * Ported from FNF's `play/components/HealthIcon.hx`.
 *
 * 0.8.6 ships no `icons/icon-<id>.xml` sheets, so every icon uses the legacy
 * format - a single PNG split into a grid of `HEALTH_ICON_SIZE` (or
 * `PIXEL_ICON_SIZE`) squares:
 *
 * ```haxe
 * loadGraphic(Paths.image('icons/icon-$charId'), true,
 *              isPixel ? PIXEL_ICON_SIZE : HEALTH_ICON_SIZE,
 *              isPixel ? PIXEL_ICON_SIZE : HEALTH_ICON_SIZE);
 *
 * // loadAnimationOld: "Simply assumes two icons, the idle and losing icons."
 * this.animation.add(Idle,   [0], 0, false, false);
 * this.animation.add(Losing, [1], 0, false, false);
 * if (animation.numFrames >= 3) this.animation.add(Winning, [2], 0, false, false);
 * ```
 *
 * So frame 0 is the neutral face, frame 1 the "losing" one and frame 2 the
 * "winning" one when the sheet has it.
 */
class HealthIcon(
    /** The character this icon represents, e.g. `bf` or `dad`. */
    val characterId: String,
    /** 0 for the player, 1 for the opponent - it decides which side of the bar. */
    val playerId: Int,
    /** Pixel icons are drawn at 32x32 instead of 150x150. */
    val isPixel: Boolean = false,
    /** FNF's `HealthIcon.configure` flip flag. */
    val flipX: Boolean = false,
) {

    companion object {
        /** Size of a non-pixel icon, per `HealthIcon.HEALTH_ICON_SIZE`. */
        const val HEALTH_ICON_SIZE = 150

        /** Size of a pixel icon, per `HealthIcon.PIXEL_ICON_SIZE`. */
        const val PIXEL_ICON_SIZE = 32

        /** How much bigger the icon gets on the bop, per `HealthIcon.BOP_SCALE`. */
        const val BOP_SCALE = 0.2f

        /** "shitty hardcoded value for a specific positioning!!!" - FNF's own words. */
        const val POSITION_OFFSET = 26

        /** At this health the icon switches to its "winning" face. */
        const val WINNING_THRESHOLD = 0.8f * 2f

        /** At this health the icon switches to its "losing" face. */
        const val LOSING_THRESHOLD = 0.2f * 2f
    }

    val image: Image = Image(Bitmap32(1, 1, intArrayOf(0), false))

    /** The frames of the icon sheet: idle, losing, and winning when present. */
    private val frames: List<RectSlice<Bitmap>>

    /** Current width of the icon in pixels; the bop animates this. */
    private var currentWidth = 0.0

    private var bopFrom = 0.0
    private var bopTo = 0.0
    private var bopElapsed = 0.0
    private var bopDuration = 0.0

    init {
        val size = if (isPixel) PIXEL_ICON_SIZE else HEALTH_ICON_SIZE
        val bitmap = loadBitmap(characterId)
        frames = if (bitmap == null) {
            emptyList()
        } else {
            val columns = (bitmap.width / size).coerceAtLeast(1)
            val rows = (bitmap.height / size).coerceAtLeast(1)
            buildList {
                for (row in 0 until rows) {
                    for (col in 0 until columns) {
                        add(bitmap.sliceWithSize(col * size, row * size, size, size))
                    }
                }
            }
        }
        currentWidth = HEALTH_ICON_SIZE.toDouble()
        image.anchor = Anchor2D.CENTER
        image.setPixelArt(isPixel)
        image.scaleX = if (flipX) -1.0 else 1.0
        applyFrame(0)
        scaleTo(currentWidth)
    }

    private fun loadBitmap(id: String): Bitmap? {
        val path = KnfFiles.resolve("icons/icon-$id", ".png")
        if (path == null) {
            println("KNF: no health icon for '$id'")
            return null
        }
        return KnfFiles.bitmapOrNull(path)
    }

    private fun applyFrame(index: Int) {
        val frame = frames.getOrNull(index) ?: return
        image.bitmap = frame
    }

    private fun scaleTo(width: Double) {
        val frame = frames.firstOrNull() ?: return
        val w = frame.width.toDouble()
        if (w <= 0) return
        val s = width / w
        image.scaleX = s * (if (flipX) -1.0 else 1.0)
        image.scaleY = s
        currentWidth = width
    }

    /** Height of the icon at its current scale, used to centre it on the bar. */
    val height: Double get() = image.height * image.scaleY

    /**
     * FNF's `HealthIcon.onStepHit`: the icon grows by [BOP_SCALE] and shrinks
     * back, over at most 175 ms.
     *
     * The bop runs on the beat, not on the hits: `bopEvery` defaults to
     * `Constants.STEPS_PER_BEAT`, and `PlayState.onStepHit` feeds every step to
     * the icon.
     */
    fun bop(stepLengthMs: Double) {
        val target = (HEALTH_ICON_SIZE + HEALTH_ICON_SIZE * BOP_SCALE).toDouble()
        bopFrom = currentWidth
        bopTo = target
        bopElapsed = 0.0
        bopDuration = (stepLengthMs * 0.002).coerceAtMost(0.175)
        if (bopDuration <= 0.0) scaleTo(bopTo)
    }

    /**
     * FNF's `HealthIcon.updateHealthIcon`: the legacy icon swaps to its
     * "losing" face when the health drops and to the "winning" one when it
     * climbs past the threshold.
     */
    fun updateFace(health: Float) {
        if (frames.size < 2) return
        val index = when {
            health < LOSING_THRESHOLD -> 1
            health > WINNING_THRESHOLD && frames.size >= 3 -> 2
            else -> 0
        }
        applyFrame(index)
    }

    fun update(dt: Double) {
        if (bopDuration > 0.0 && bopElapsed < bopDuration) {
            bopElapsed += dt
            val t = (bopElapsed / bopDuration).coerceIn(0.0, 1.0)
            // FNF tweens back down with `FlxTween.num`, which is linear.
            scaleTo(bopFrom + (bopTo - bopFrom) * (1.0 - t))
        }
    }
}
