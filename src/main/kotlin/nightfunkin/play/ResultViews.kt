package nightfunkin.play

import korlibs.image.bitmap.Bitmap32
import korlibs.image.bitmap.sliceWithSize
import korlibs.image.color.Colors
import korlibs.image.color.RGBA
import korlibs.korge.view.Container
import korlibs.korge.view.Image
import korlibs.korge.view.addUpdater
import korlibs.korge.view.image
import korlibs.korge.view.solidRect
import korlibs.math.geom.Anchor2D
import korlibs.math.geom.degrees
import nightfunkin.core.AnimateAtlas
import nightfunkin.core.Bounds
import nightfunkin.core.KnfFiles
import nightfunkin.core.Mat
import nightfunkin.core.PosePart
import nightfunkin.core.SparrowSprite
import nightfunkin.core.TextureAtlas
import nightfunkin.core.setPixelArt
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max

// ===========================================================================
// The "tardling" monospace font used for the song title
// ===========================================================================

/**
 * FNF's results title font: `FlxBitmapFont.fromMonospace(tardlingSpritesheet,
 * "AaBbCc...", 49x61)` with `letterSpacing = -15` and a `-4.4` degree angle.
 *
 * Each character is one fixed cell in the sheet, so this builds a plain
 * container of images rather than going through the AngelCode font loader.
 */
class MonoBitmapText(
    text: String,
    private val letterSpacing: Double = -15.0,
    private val angleDegrees: Double = -4.4,
) : Container() {

    private val glyphs = ArrayList<Image>()

    /**
     * FNF rotates the whole `FlxBitmapText` around its origin, which Flixel
     * places at the centre of the text frame. The outer container keeps the
     * sprite's top-left semantics; this child carries the rotation so the
     * pivot is the centre, exactly like `FlxSprite.origin`.
     */
    private val rotationContainer = Container()

    var textWidth: Double = 0.0
        private set

    init {
        addChild(rotationContainer)
        rotationContainer.rotation = angleDegrees.degrees
        setText(text)
    }

    fun setText(value: String) {
        glyphs.forEach { it.removeFromParent() }
        glyphs.clear()

        var pen = 0.0
        val placed = ArrayList<Pair<Image, Double>>()
        for (ch in value) {
            val index = glyphIndex(ch)
            if (index == null) {
                // FNF's font has no space glyph; FlxBitmapText still advances.
                pen += SPACE_ADVANCE
                continue
            }
            val col = index % GRID_COLUMNS
            val row = index / GRID_COLUMNS
            val slice = sheet()?.sliceWithSize(
                col * CELL_WIDTH,
                row * CELL_HEIGHT,
                CELL_WIDTH,
                CELL_HEIGHT,
            ) ?: continue
            val img = Image(slice)
            img.anchor = Anchor2D.TOP_LEFT
            rotationContainer.addChild(img)
            glyphs += img
            placed += img to pen
            pen += CELL_WIDTH + letterSpacing
        }
        textWidth = if (glyphs.isEmpty()) 0.0 else pen - letterSpacing

        // Layout after knowing the width: frame origin = text centre.
        rotationContainer.x = textWidth / 2.0
        rotationContainer.y = CELL_HEIGHT / 2.0
        for ((img, x) in placed) {
            img.x = x - textWidth / 2.0
            img.y = -CELL_HEIGHT / 2.0
        }
    }

    companion object {
        const val CELL_WIDTH = 49
        const val CELL_HEIGHT = 61
        const val GRID_COLUMNS = 10
        const val SPACE_ADVANCE = 20.0

        private const val LETTERS =
            "AaBbCcDdEeFfGgHhiIJjKkLlMmNnOoPpQqRrSsTtUuVvWwXxYyZz:1234567890().-"

        private var cachedSheet: korlibs.image.bitmap.Bitmap? = null
        private var sheetLoaded = false

        private fun sheet(): korlibs.image.bitmap.Bitmap? {
            if (!sheetLoaded) {
                sheetLoaded = true
                cachedSheet = KnfFiles.bitmapOrNull("shared/images/resultScreen/tardlingSpritesheet.png")
                if (cachedSheet == null) println("KNF: missing results font sheet")
            }
            return cachedSheet
        }

        /** Index into [LETTERS], or null for characters the font does not carry. */
        fun glyphIndex(ch: Char): Int? {
            if (ch == ' ') return null
            val letter = ch.lowercaseChar()
            if (letter in 'a'..'z') {
                val base = (letter - 'a') * 2
                return if (ch.isUpperCase()) base else base + 1
            }
            if (ch.isDigit()) {
                // The sheet is 1..9 then 0.
                val digit = ch - '0'
                return 53 + if (digit == 0) 9 else digit - 1
            }
            return when (ch) {
                ':' -> 52
                '(' -> 63
                ')' -> 64
                '.' -> 65
                '-' -> 66
                else -> null
            }
        }
    }
}

// ===========================================================================
// Animate-atlas result character
// ===========================================================================

/**
 * A results-screen character loaded from an Adobe Animate atlas.
 *
 * FNF's `ResultState` plays a named frame label once, then either pauses or
 * jumps to a loop label/frame. Rendering is the same symbol-tree walk as
 * [Character], just without singing or stage placement.
 */
class ResultAnimateSprite(
    private val atlas: AnimateAtlas,
    startFrameLabel: String? = null,
    loopFrameLabel: String? = null,
    private val loopFrame: Int? = null,
    private val looped: Boolean = true,
    scale: Double = 1.0,
) : Container() {

    private val allFrames: List<Int> = atlas.poses.indices.toList()
    private val loopLabel: String? = loopFrameLabel?.takeIf { it.isNotEmpty() }
    private var frames: List<Int> =
        startFrameLabel?.takeIf { it.isNotEmpty() }
            ?.let { atlas.labelFrames(it) }
            .orEmpty()
            .ifEmpty { allFrames }
    private var frameIndex = 0
    private var elapsed = 0.0
    private var done = false
    private var running = false

    private val images = ArrayList<Image>()
    private val sprites = atlas.sprites
    private val poses = atlas.poses
    private val bounds: Bounds = atlas.bounds

    /**
     * FlxAnimate scales around the centre of the timeline bounds, not around
     * the top-left corner. Keep the outer node at the authored x/y and offset
     * the scaled content by `origin * (1 - scale)`.
     */
    private val content = Container()

    var onFinished: (() -> Unit)? = null

    init {
        content.scaleX = scale
        content.scaleY = scale
        content.x = bounds.width / 2.0 * (1.0 - scale)
        content.y = bounds.height / 2.0 * (1.0 - scale)
        addChild(content)
        render()
    }

    /** Starts the animation from its first frame (FNF keeps it hidden until now). */
    fun start() {
        running = true
        done = false
        frameIndex = 0
        elapsed = 0.0
        render()
    }

    /** Starts at a specific frame of the current label, for `loopFrame`. */
    fun startAt(frame: Int) {
        running = true
        done = false
        frameIndex = frame.coerceIn(0, (frames.size - 1).coerceAtLeast(0))
        elapsed = 0.0
        render()
    }

    fun update(dt: Double) {
        if (!running || done || frames.isEmpty()) return
        elapsed += dt
        val step = 1.0 / atlas.frameRate
        while (elapsed >= step) {
            elapsed -= step
            frameIndex++
            if (frameIndex >= frames.size) {
                // FNF checks `looped` first: a non-looping animation pauses on
                // its final frame even when loopFrame/loopFrameLabel are set.
                if (!looped) {
                    frameIndex = frames.size - 1
                    done = true
                    onFinished?.invoke()
                    break
                }
                if (loopLabel != null) {
                    val next = atlas.labelFrames(loopLabel)
                    if (next.isNotEmpty()) {
                        frames = next
                        frameIndex = 0
                    } else {
                        frameIndex = 0
                    }
                } else if (loopFrame != null) {
                    // The loop point is a frame index in the atlas timeline,
                    // not an index into a label's frame list.
                    frames = allFrames
                    frameIndex = loopFrame.coerceIn(0, frames.size - 1)
                } else {
                    frameIndex = 0
                }
            }
            render()
        }
    }

    private fun render() {
        if (frames.isEmpty()) return
        val pose = poses.getOrNull(frames[frameIndex % frames.size]) ?: return

        while (images.size < pose.parts.size) {
            images += Image(EMPTY_BITMAP, Anchor2D.CENTER).also { content.addChild(it) }
        }
        for (i in images.indices) {
            val img = images[i]
            val part = pose.parts.getOrNull(i)
            val def = part?.let { sprites[it.sprite] }
            if (part == null || def == null) {
                img.visible = false
                continue
            }
            img.visible = true
            img.bitmap = def.slice
            img.setPixelArt(false)

            val (cx, cy) = part.matrix.xform(def.width / 2.0, def.height / 2.0)
            img.x = cx - bounds.minX
            img.y = cy - bounds.minY

            val m: Mat = part.matrix
            img.scaleX = hypot(m.a, m.b)
            img.scaleY = hypot(m.c, m.d)
            if (m.a * m.d - m.c * m.b < 0.0) img.scaleY = -img.scaleY
            val degrees = (if (def.turned) -90.0 else 0.0) + Math.toDegrees(atan2(m.b, m.a))
            img.rotation = degrees.degrees
        }
    }

    companion object {
        private val EMPTY_BITMAP =
            Bitmap32(1, 1, intArrayOf(0xFF000000.toInt()), true)
    }
}

/** Sparrow result character that supports FNF's `loopFrame` restart. */
class ResultSparrowSprite(
    private val atlas: TextureAtlas,
    loopFrame: Int?,
    scale: Double = 1.0,
) : Container() {

    private val allFrames = atlas.framesByPrefix("")
    // FNF registers the sparrow result animation with `looped = false`; a
    // configured `loopFrame` is applied by restarting the animation from that
    // frame whenever it finishes.
    private val sprite = SparrowSprite(atlas, allFrames, 24.0, loop = false)
    private val loopFrame = loopFrame
    private var running = false

    init {
        scaleX = scale
        scaleY = scale
        addChild(sprite)
    }

    fun start() {
        running = true
        sprite.seekTo(0)
    }

    fun update(dt: Double) {
        if (!running) return
        sprite.update(dt)
        if (loopFrame != null && sprite.finished) sprite.seekTo(loopFrame)
    }
}

// ===========================================================================
// Digital score
// ===========================================================================

/** One digit of the results score, from `resultScreen/score-digital-numbers`. */
private class ScoreNum(
    private val atlas: TextureAtlas,
    x: Double,
    y: Double,
) : Container() {
    private val names = listOf(
        "ZERO", "ONE", "TWO", "THREE", "FOUR",
        "FIVE", "SIX", "SEVEN", "EIGHT", "NINE",
    )
    private val digitFrames = names.map { atlas.framesByPrefix("$it DIGITAL") }
    private val disabledFrames = atlas.framesByPrefix("DISABLED")
    private val goneFrames = atlas.framesByPrefix("GONE")
    private val view = SparrowSprite(
        atlas,
        disabledFrames.ifEmpty { goneFrames },
        fps = 24.0,
        loop = false,
    )

    /**
     * The digit this numeral will settle on. FNF's `set_finalDigit` immediately
     * switches the sprite to the blank `GONE` animation, so only the leading
     * `DISABLED` numerals stay visible before the shuffle starts.
     */
    var finalDigit: Int = 10
        set(value) {
            field = value
            if (goneFrames.isNotEmpty()) view.play(goneFrames, restart = true)
        }

    /**
     * Seconds before the shuffle starts, in 1/24 s frames.
     *
     * `ResultScore.animateNumbers` only arms the digits that the score actually
     * occupies; leading zeroes must stay on `DISABLED`. The original never
     * shuffles them because `shuffle()` is called from `animateNumbers` alone,
     * so a default of 0 here would make them cycle and settle on `9`
     * (`floor(1.0 * 10)` clamped by `playDigit`), which is not a real digit.
     */
    var startDelay: Double = Double.POSITIVE_INFINITY

    /** Frames to wait after settling before replaying the final animation. */
    var finalDelay: Double = 0.0

    /**
     * FNF's `animateNumbers` starts a fresh `FlxTimer` per digit, so the wait
     * begins when the digit is armed, not when the results screen was built.
     * Resetting here keeps the digits staggered instead of starting their
     * shuffle in lockstep on the first update after arming.
     */
    fun schedule(shuffleDelay: Double, settleDelay: Double) {
        startDelay = shuffleDelay
        finalDelay = settleDelay
        timer = 0.0
        phase = Phase.WAITING
        shuffleElapsed = 0.0
    }

    private var phase = Phase.WAITING
    private var timer = 0.0
    private var value = 10
    private var restartWait = 0.0

    private enum class Phase { WAITING, SHUFFLING, SETTLING, RESTARTING, DONE }

    init {
        this.x = x
        this.y = y
        addChild(view)
    }

    fun update(dt: Double) {
        view.update(dt)
        when (phase) {
            Phase.WAITING -> {
                timer += dt
                if (timer >= startDelay) {
                    timer = 0.0
                    phase = Phase.SHUFFLING
                    value = 0
                    playDigit(0)
                }
            }
            Phase.SHUFFLING -> {
                timer += dt
                while (timer >= SHUFFLE_STEP) {
                    timer -= SHUFFLE_STEP
                    value = (value + 1) % 10
                    playDigit(value)
                }
                if (timer <= SHUFFLE_STEP * 0.001) Unit
                // The FNF timer runs 41 ticks; count elapsed explicitly.
                shuffleElapsed += dt
                if (shuffleElapsed >= SHUFFLE_DURATION) {
                    phase = Phase.SETTLING
                    timer = 0.0
                }
            }
            Phase.SETTLING -> {
                timer += dt
                val t = (timer / SETTLE_DURATION).coerceIn(0.0, 1.0)
                val eased = 1.0 - (1.0 - t) * (1.0 - t)
                val shown = Math.floor(eased * finalDigit).toInt()
                if (shown != value) {
                    value = shown
                    playDigit(value)
                }
                if (t >= 1.0) {
                    value = finalDigit
                    playDigit(value)
                    restartWait = finalDelay
                    phase = Phase.RESTARTING
                }
            }
            Phase.RESTARTING -> {
                restartWait -= dt
                if (restartWait <= 0.0) {
                    if (value in 0..9) playDigit(value)
                    phase = Phase.DONE
                }
            }
            Phase.DONE -> Unit
        }
    }

    private var shuffleElapsed = 0.0

    private fun playDigit(digit: Int) {
        if (digit !in 0..9) return
        val frames = digitFrames[digit]
        if (frames.isNotEmpty()) view.play(frames, restart = true)
    }

    companion object {
        const val SHUFFLE_STEP = 1.0 / 24.0
        const val SHUFFLE_DURATION = 41.0 / 24.0
        const val SETTLE_DURATION = 23.0 / 24.0
    }
}

/**
 * FNF's `ResultScore`: ten digits that shuffle and settle on the score.
 *
 * `ResultScore` extends `FlxTypedSpriteGroup`, whose `preAdd` adds the group
 * position to each member. FNF passes `(35, 305)` both to the group and to
 * every `ScoreNum`, so the digits actually render at `2 * (35, 305)` plus
 * `65 * i`. Reproducing that here keeps the score in the bottom band instead
 * of floating up over the tallies.
 */
class ResultScore(
    atlas: TextureAtlas,
    x: Double,
    y: Double,
    digitCount: Int,
    score: Int,
) : Container() {

    private val nums = (0 until digitCount).map { i ->
        ScoreNum(atlas, x + 65.0 * i, y)
    }
    private var scoreStart = 0

    init {
        this.x = x
        this.y = y
        nums.forEach { addChild(it) }
        val clamped = score.coerceIn(0, Math.pow(10.0, digitCount.toDouble()).toInt() - 1)
        var n = clamped
        var loop = digitCount - 1
        while (n > 0) {
            scoreStart++
            nums[loop].finalDigit = n % 10
            n /= 10
            loop--
        }
        // FNF also leaves the leading digit disabled; every numeral starts as
        // the DISABLED animation in its constructor.
    }

    fun animateNumbers() {
        for (i in (nums.size - scoreStart) until nums.size) {
            nums[i].schedule(
                shuffleDelay = (i - 1).coerceAtLeast(0) / 24.0,
                settleDelay = (scoreStart - (i - 1)).coerceAtLeast(0) / 24.0,
            )
        }
    }

    fun update(dt: Double) {
        nums.forEach { it.update(dt) }
    }
}

// ===========================================================================
// Tally counters
// ===========================================================================

/** FNF's `TallyCounter`: numbers that count up next to each judgement. */
class TallyCounter(
    private val atlas: TextureAtlas,
    x: Double,
    y: Double,
    val neededNumber: Int,
    private val flavour: RGBA = Colors.WHITE,
) : Container() {

    private val digits = ArrayList<Image>()

    /**
     * `TallyCounter.curNumber`. The original starts at zero and only draws a
     * digit once the results screen tweens it up to [neededNumber], which is
     * what makes the numbers count upwards.
     */
    var curNumber: Double = 0.0
        set(value) {
            field = value
            setNumber(value)
        }

    init {
        this.x = x
        this.y = y
        // With a non-zero target the counter starts empty; a zero tally draws
        // its single "0" immediately, exactly like the Haxe constructor.
        if (curNumber == neededNumber.toDouble()) setNumber(curNumber)
    }

    fun setNumber(number: Double) {
        var temp = Math.round(number).toInt()
        val separated = ArrayList<Int>()
        while (temp != 0) {
            separated += temp % 10
            temp /= 10
        }
        if (separated.isEmpty()) separated += 0
        separated.reverse()

        for ((ind, num) in separated.withIndex()) {
            if (ind >= digits.size) {
                val frame = atlas.framesByPrefix("$num small").firstOrNull() ?: continue
                val img = Image(atlas.slice(frame))
                img.anchor = Anchor2D.TOP_LEFT
                img.x = ind * 43.0
                img.y = 0.0
                addChild(img)
                digits += img
            }
            val frame = atlas.framesByPrefix("$num small").firstOrNull() ?: continue
            val img = digits[ind]
            img.bitmap = atlas.slice(frame)
            img.x = -frame.frameX + ind * 43.0
            img.y = -frame.frameY.toDouble()
            img.tint = flavour
            img.visible = true
        }
        for (ind in separated.size until digits.size) digits[ind].visible = false
    }
}

// ===========================================================================
// Clear percent
// ===========================================================================

/** FNF's `ClearPercentCounter`, in both big and small flavours. */
class ClearPercentCounter(
    x: Double,
    y: Double,
    startingNumber: Int = 0,
    private val small: Boolean = false,
) : Container() {

    private val textSprite: Image?
    private val digits = ArrayList<Image>()
    private val leftAtlas: TextureAtlas?
    private val rightAtlas: TextureAtlas?
    private val smallAtlas: TextureAtlas?

    var curNumber: Int = startingNumber
        set(value) {
            field = value
            drawNumbers()
        }

    var number: Int
        get() = curNumber
        set(value) {
            curNumber = value
        }

    init {
        this.x = x
        this.y = y
        val textPath = "shared/images/resultScreen/clearPercent/clearPercentText" +
            (if (small) "Small" else "") + ".png"
        textSprite = KnfFiles.bitmapOrNull(textPath)?.let { bmp ->
            image(bmp).also {
                it.anchor = Anchor2D.TOP_LEFT
                it.x = if (small) 40.0 else 0.0
            }
        }
        leftAtlas = TextureAtlas.tryLoad(
            "shared/images/resultScreen/clearPercent/clearPercentNumberLeft.xml"
        )
        rightAtlas = TextureAtlas.tryLoad(
            "shared/images/resultScreen/clearPercent/clearPercentNumberRight.xml"
        )
        smallAtlas = TextureAtlas.tryLoad(
            "shared/images/resultScreen/clearPercent/clearPercentNumberSmall.xml"
        )
        drawNumbers()
    }

    fun flash(enabled: Boolean) {
        // The original swaps in a pure-white shader; KorGE's per-image tint is
        // multiplicative, so the closest cheap equivalent is leaving the art
        // alone. The sound and timing still match.
    }

    private fun drawNumbers() {
        var temp = Math.round(curNumber.toDouble()).toInt()
        val separated = ArrayList<Int>()
        while (temp > 0) {
            separated += temp % 10
            temp /= 10
        }
        if (separated.isEmpty()) separated += 0
        separated.reverse()

        for ((ind, num) in separated.withIndex()) {
            val digitIndex = ind + 1
            val digitOffset = when (separated.size) {
                1 -> 1
                3 -> -1
                else -> 0
            }
            val digitSize = if (small) 32 else 72
            val digitHeightOffset = if (small) -4 else 0
            val xPos = (digitIndex - 1 + digitOffset) * digitSize +
                (if (small) -24 else 0)
            val yPos = (digitIndex - 1 + digitOffset) * digitHeightOffset +
                (if (small) 0 else 72)

            val variant = if (separated.size == 3) digitIndex >= 2 else digitIndex >= 1
            val atlas = when {
                small -> smallAtlas
                variant -> rightAtlas
                else -> leftAtlas
            } ?: continue
            val frames = atlas.framesByPrefix("number $num 0")
            val frame = frames.firstOrNull() ?: continue

            val img: Image
            if (ind >= digits.size) {
                img = Image(atlas.slice(frame))
                img.anchor = Anchor2D.TOP_LEFT
                addChild(img)
                digits += img
            } else {
                img = digits[ind]
                img.bitmap = atlas.slice(frame)
            }
            img.x = (xPos - frame.frameX).toDouble()
            img.y = yPos - frame.frameY.toDouble()
            img.visible = true
        }
        for (ind in separated.size until digits.size) digits[ind].visible = false
    }
}
