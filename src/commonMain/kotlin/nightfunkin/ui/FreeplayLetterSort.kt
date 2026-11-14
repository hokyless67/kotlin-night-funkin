package nightfunkin.ui

import korlibs.image.color.RGBA
import korlibs.korge.view.Container
import korlibs.korge.view.Image
import nightfunkin.core.AnimateAtlas
import nightfunkin.core.AnimateSpriteView
import nightfunkin.core.KnfFiles
import kotlin.math.abs

/**
 * Port of `funkin.ui.freeplay.LetterSort` plus `FreeplayLetter`.
 *
 * The top strip holds five slots. Each slot plays one of the
 * `freeplay/sortedLetters` Animate symbols (`ALL move`, `# move`, ...), while
 * the middle slot loops its bounce and the other four stay paused on frame 0.
 * Moving the strip shifts every slot by one letter and offsets it through the
 * same `[-10, -22, 2, 0]` kick used by the original.
 */
internal class FreeplayLetterSort(
    private val atlas: AnimateAtlas,
    /** `LetterSort((CUTOUT_WIDTH * SONGS_POS_MULTI) + 400, 75)`. */
    groupX: Double = 400.0,
) {
    companion object {
        /** `FreeplayLetter.regexLetters`, including the inserted leading tabs. */
        val REGEX_LETTERS: List<String> = listOf(
            "#", "fav", "ALL",
            "A-B", "C-D", "E-H", "I-L", "M-N", "O-R", "S", "T", "U-Z",
        )

        /** `animLetters`: Flash symbol names drop the dash (`A-B` -> `AB`). */
        val ANIM_LETTERS: List<String> = REGEX_LETTERS.map { it.replace("-", "") }

        private const val SLOT_COUNT = 5

        private const val GROUP_Y = 75.0

        /** `doLetterChangeAnims`'s four kick positions, in 1/24 s steps. */
        private val KICK_POSITIONS = doubleArrayOf(-10.0, -22.0, 2.0, 0.0)

        /** `FlxColor.getDarkened`, including its integer truncation per pass. */
        fun shade(slot: Int, times: Int = 1): RGBA {
            val darkness = maxOf(abs(slot - 2) / 6.0, 0.01)
            val factor = 1.0 - darkness
            var channel = 255
            repeat(times) { channel = (channel * factor).toInt().coerceIn(0, 255) }
            return RGBA(channel, channel, channel)
        }
    }

    val container: Container = Container().also {
        it.x = groupX
        it.y = GROUP_Y
    }

    var onChange: ((String) -> Unit)? = null
    var onSound: (() -> Unit)? = null

    private var selection = 2
    private var clock = 0.0
    private val pending = ArrayList<Pending>()
    private val letters = ArrayList<Letter>()
    private val separators = ArrayList<Separator>()

    private var leftArrow: Image? = null
    private var rightArrow: Image? = null
    private var leftArrowX = 0.0
    private var rightArrowX = 0.0

    init {
        val separatorBitmap = KnfFiles.bitmapOrNull("preload/images/freeplay/seperator.png")
        if (separatorBitmap != null) {
            for (i in 0 until SLOT_COUNT - 1) {
                val image = Image(separatorBitmap).also {
                    it.smoothing = false
                    it.x = 60.0 + i * 80.0
                    it.y = 20.0
                    // `sep.color = letter.color.getDarkened(darkness)`, and
                    // the letter was already darkened once above.
                    it.tint = shade(i, times = 2)
                    container.addChild(it)
                }
                separators += Separator(image, 60.0 + i * 80.0)
            }
        }

        val arrowBitmap = KnfFiles.bitmapOrNull("preload/images/freeplay/miniArrow.png")
        if (arrowBitmap != null) {
            val width = arrowBitmap.width.toDouble()
            leftArrow = Image(arrowBitmap).also {
                it.smoothing = false
                // FlxSprite.flipX mirrors in place; Korge mirrors around the
                // left edge, so shift by the arrow width to keep the same span.
                it.scaleX = -1.0
                leftArrowX = -20.0 + width
                it.x = leftArrowX
                it.y = 15.0
                container.addChild(it)
            }
            rightArrow = Image(arrowBitmap).also {
                it.smoothing = false
                rightArrowX = 380.0
                it.x = rightArrowX
                it.y = 15.0
                container.addChild(it)
            }
        }

        for (i in 0 until SLOT_COUNT) {
            val letter = Letter(i, atlas).also {
                it.container.x = 50.0 + i * 80.0
                it.container.y = 50.0
                container.addChild(it.container)
            }
            letters += letter
        }

        // `LetterSort`'s constructor ends with changeSelection(0). The scene
        // installs `onChange` afterwards, so this first pass only lays the
        // strip out and does not regenerate the song list.
        changeSelection(0)
    }

    /** Moves the sorting tab by [diff], exactly like `LetterSort.changeSelection`. */
    fun changeSelection(diff: Int, playSound: Boolean = true) {
        val sign = if (diff > 0) 1.0 else -1.0

        for (separator in separators) {
            separator.setOffset(KICK_POSITIONS[0] * sign)
            schedule(1.0 / 24.0) { separator.setOffset(KICK_POSITIONS[1] * sign) }
            schedule(2.0 / 24.0) { separator.setOffset(KICK_POSITIONS[2] * sign) }
            schedule(3.0 / 24.0) { separator.setOffset(KICK_POSITIONS[3] * sign) }
        }

        for ((index, letter) in letters.withIndex()) {
            letter.setOffset(KICK_POSITIONS[0] * sign)
            schedule(1.0 / 24.0) {
                letter.setOffset(KICK_POSITIONS[1] * sign)
                if (index == 0) letter.container.visible = false
            }
            schedule(2.0 / 24.0) {
                letter.setOffset(KICK_POSITIONS[2] * sign)
                if (index == 0) letter.container.visible = true
            }
            schedule(3.0 / 24.0) {
                letter.setOffset(if (index == 2) 0.0 else KICK_POSITIONS[3] * sign)
            }
        }

        val arrow = if (diff < 0) leftArrow else rightArrow
        val arrowBase = if (diff < 0) leftArrowX else rightArrowX
        if (arrow != null) {
            arrow.x = arrowBase - 3.0 * sign
            schedule(2.0 / 24.0) { arrow.x = arrowBase }
        }

        selection = wrap(selection + diff, REGEX_LETTERS.size)
        for (letter in letters) {
            letter.apply(wrap(letter.curLetter + diff, REGEX_LETTERS.size), selection)
        }

        if (playSound && diff != 0) onSound?.invoke()
        onChange?.invoke(REGEX_LETTERS[selection])
    }

    fun update(dt: Double) {
        for (letter in letters) letter.view.update(dt)
        if (pending.isEmpty()) return

        clock += dt
        var i = 0
        while (i < pending.size) {
            val next = pending[i]
            if (clock >= next.time) {
                pending.removeAt(i)
                next.action()
            } else {
                i++
            }
        }
    }

    private fun schedule(delay: Double, action: () -> Unit) {
        pending += Pending(clock + delay, action)
    }

    private fun wrap(value: Int, size: Int): Int = ((value % size) + size) % size

    private class Pending(val time: Double, val action: () -> Unit)

    private class Separator(val image: Image, private val baseX: Double) {
        fun setOffset(value: Double) {
            image.x = baseX - value
        }
    }

    private class Letter(val index: Int, val atlas: AnimateAtlas) {
        val container = Container()
        val view = AnimateSpriteView(atlas).also { container.addChild(it) }

        var curLetter = index
            private set

        private val scale = if (index == 2) 1.0 else 0.8
        private var offsetX = 0.0

        init {
            view.applyTint(shade(index))
        }

        fun apply(letterIndex: Int, selection: Int) {
            curLetter = letterIndex
            val symbol = ANIM_LETTERS[letterIndex] + " move"
            val frames = atlas.symbolFrames(symbol)
            if (frames.isEmpty()) return

            // `FreeplayLetter` is a FlxAnimate whose `origin` is the stage
            // symbol's transformation point (0,0). Play the symbol on its own,
            // exactly like `anim.play(name, true)`, instead of the root pose.
            val origin = atlas.symbolOrigin(symbol)
            view.playFrames(symbol, frames, loop = true, restart = true, origin = origin)
            if (selection != letterIndex) view.stop()
            refresh()
        }

        fun setOffset(value: Double) {
            offsetX = value
            refresh()
        }

        private fun refresh() {
            container.scaleX = scale
            container.scaleY = scale
            // FlxSprite draws at `x - offset`; positive offset moves the
            // graphic left, so subtract the kick instead of adding it.
            container.x = 50.0 + index * 80.0 - offsetX
            container.y = 50.0
        }
    }
}
