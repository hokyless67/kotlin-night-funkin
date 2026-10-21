package nightfunkin.play

import korlibs.korge.view.Container
import korlibs.korge.view.addUpdater
import korlibs.korge.view.Image
import nightfunkin.core.Constants
import nightfunkin.core.NoteStyle
import nightfunkin.core.setPixelArt
import kotlin.time.Duration
import kotlin.math.cos
import kotlin.math.sin

/**
 * Judgement popups and the combo counter.
 *
 * Ported from FNF's `play/components/PopUpStuff.hx`.
 *
 * Both are little sprites that get thrown upwards and then fall: FNF gives them
 * a downward acceleration and an initial upward velocity, and fades them out
 * after roughly one beat.
 *
 * ```haxe
 * rating.acceleration.y = 550;
 * rating.velocity.y -= FlxG.random.int(140, 175);
 * rating.velocity.x -= FlxG.random.int(0, 10);
 *
 * FlxTween.tween(rating, {alpha: 0}, 0.2, {
 *   startDelay: Conductor.instance.beatLengthMs * 0.001,
 *   ease: fadeEase   // stepped(2) for pixel art
 * });
 * ```
 */
class PopUpStuff(
    private val noteStyle: NoteStyle,
    /** Offsets applied to every element, so scripts can move the whole group. */
    val offsets: List<Double> = listOf(0.0, 0.0),
) : Container() {

    /** Beat length in ms; the popups fade one beat after they appear. */
    var beatLengthMs: Double = 500.0

    /**
     * FNF's `PopUpStuff.displayRating`.
     *
     * The rating sprite sits slightly left of centre and above the middle, then
     * is thrown upwards.
     */
    fun displayRating(rating: String) {
        val asset = noteStyle.judgement(rating)
        if (asset == null) {
            println("KNF: no judgement asset for '$rating'")
            return
        }
        val img = Image(asset.slice)
        img.setPixelArt(asset.isPixel)
        img.scaleX = asset.scale.toDouble()
        img.scaleY = asset.scale.toDouble()
        img.anchor = korlibs.math.geom.Anchor2D.TOP_LEFT

        val w = img.width * asset.scale
        val h = img.height * asset.scale
        img.x = (Constants.GAME_WIDTH * 0.474) - w / 2.0 + offsets.getOrElse(0) { 0.0 } +
            asset.offsets.getOrElse(0) { 0.0 }
        img.y = (Constants.GAME_HEIGHT * 0.45 - 60) - h / 2.0 + offsets.getOrElse(1) { 0.0 } +
            asset.offsets.getOrElse(1) { 0.0 }

        addChild(img)
        launch(img, accelerationY = 550.0, velocityY = -(140.0 + Math.random() * 35.0),
            velocityX = -(Math.random() * 10.0), delay = beatLengthMs * 0.001, stepped = asset.isPixel)
    }

    /**
     * FNF's `PopUpStuff.displayCombo`: the digits of the combo counter, thrown
     * up a little lower and with a bit of horizontal drift.
     */
    fun displayCombo(combo: Int) {
        if (combo <= 0) return
        val digits = ArrayList<Int>()
        var value = combo
        while (value != 0) {
            digits += value % 10
            value /= 10
        }
        while (digits.size < 3) digits += 0

        var daLoop = 1
        for (digit in digits) {
            val asset = noteStyle.comboNumber(digit) ?: continue
            val img = Image(asset.slice)
            img.setPixelArt(asset.isPixel)
            img.scaleX = asset.scale.toDouble()
            img.scaleY = asset.scale.toDouble()
            img.anchor = korlibs.math.geom.Anchor2D.TOP_LEFT

            img.x = (Constants.GAME_WIDTH * 0.507) - (36.0 * daLoop) - 65.0 +
                offsets.getOrElse(0) { 0.0 } + asset.offsets.getOrElse(0) { 0.0 }
            img.y = (Constants.GAME_HEIGHT * 0.44) + offsets.getOrElse(1) { 0.0 } +
                asset.offsets.getOrElse(1) { 0.0 }

            addChild(img)
            launch(img, accelerationY = 250.0 + Math.random() * 50.0,
                velocityY = -(130.0 + Math.random() * 20.0),
                velocityX = -5.0 + Math.random() * 10.0,
                delay = beatLengthMs * 0.002, stepped = asset.isPixel)
            daLoop++
        }
    }

    /**
     * One popup's little flight: an initial velocity, gravity, and a fade once
     * the delay has passed.
     */
    private fun launch(
        img: Image,
        accelerationY: Double,
        velocityY: Double,
        velocityX: Double,
        delay: Double,
        stepped: Boolean,
    ) {
        var vy = velocityY
        var vx = velocityX
        var elapsed = 0.0
        var fadeStarted = false
        var fadeElapsed = 0.0

        img.addUpdater { dt ->
            val s = dt.toSeconds()
            elapsed += s
            vy += accelerationY * s
            img.y += vy * s
            img.x += vx * s

            if (!fadeStarted && elapsed >= delay) {
                fadeStarted = true
                fadeElapsed = 0.0
            }
            if (fadeStarted) {
                fadeElapsed += s
                // FNF fades over 0.2 s. Pixel art uses `EaseUtil.stepped(2)`,
                // i.e. alpha 1.0 -> 0.5 -> 0.0, not a single jump to zero.
                val t = (fadeElapsed / 0.2).coerceIn(0.0, 1.0)
                val eased = if (stepped) kotlin.math.floor(t * 2.0) / 2.0 else t
                img.alpha = 1.0 - eased
                if (t >= 1.0) img.removeFromParent()
            }
        }
    }
}

private fun Duration.toSeconds(): Double = inWholeNanoseconds / 1_000_000_000.0
