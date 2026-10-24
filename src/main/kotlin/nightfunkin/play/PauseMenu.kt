package nightfunkin.play

import korlibs.image.color.Colors
import korlibs.image.font.TtfFont
import korlibs.io.resources.Resourceable
import korlibs.korge.view.Container
import korlibs.korge.view.Text
import korlibs.korge.view.solidRect
import korlibs.korge.view.text
import nightfunkin.core.Constants
import nightfunkin.core.FunkinAtlasText
import nightfunkin.core.KnfFiles
import nightfunkin.core.KnfInput
import kotlin.math.min

/**
 * Port of FNF 0.8.6's `PauseSubState` (standard and difficulty modes).
 *
 * The menu is an overlay, not a separate scene, so the stage keeps rendering
 * underneath it while `PlayScene` freezes its update loop, exactly like a
 * substate. Layout, timings (0.8 s background fade, 0.33 s entry slide,
 * 1.8 s staggered metadata fade) and input order come from `PauseSubState.hx`.
 *
 * Entries omitted from the original list are the ones KNF has no backing
 * feature for: practice mode, chart-editor/conversation/cutscene modes, and
 * story/freeplay-specific exit paths.
 */
class PauseMenu(
    songName: String,
    artistName: String,
    difficulty: String,
    private val difficulties: List<String>,
    private val offsetMs: Float,
    private val onScroll: () -> Unit,
    private val onResume: () -> Unit,
    private val onRestartSong: () -> Unit,
    private val onChangeDifficulty: (String) -> Unit,
    private val onExitToMenu: () -> Unit,
) : Container() {

    private enum class Mode { STANDARD, DIFFICULTY }

    /** One menu entry plus the state of its `changeSelection` slide. */
    private class Entry(
        val action: () -> Unit,
        val view: FunkinAtlasText,
    ) {
        var startX = 0.0
        var startY = 0.0
        var targetX = 0.0
        var targetY = 0.0
        var moveTime = 0.0
    }

    /** A metadata line and its slot in the staggered fade-in. */
    private class Meta(val view: Text, val targetY: Double, val delay: Double)

    private val background = solidRect(
        Constants.GAME_WIDTH * 2.0,
        Constants.GAME_HEIGHT * 2.0,
        Colors.BLACK,
    ).apply { alpha = 0.0 }

    private val entriesLayer = Container()
    private val metadata = ArrayList<Meta>()
    private var entries: List<Entry> = emptyList()
    private var mode = Mode.STANDARD
    private var selected = 0
    private var time = 0.0
    private var justOpened = true

    init {
        addChild(entriesLayer)

        // `buildMetadata`: top-right, 32 px VCR, then the global offset near the
        // bottom edge (`Paths.font('vcr.ttf')`).
        var delay = 0.1
        meta(songName, 15.0, delay)
        delay += 0.1
        meta("Artist: $artistName", 47.0, delay)
        delay += 0.1
        meta("Difficulty: ${titleCase(difficulty)}", 79.0, delay)
        delay += 0.1
        meta("0 Blue Balls", 111.0, delay)
        delay += 0.1
        // FNF also tweens its (invisible) practice-mode line, which consumes a
        // slot before the offset text.
        delay += 0.1

        val offset = text(
            "Global Offset: ${offsetMs.toInt()}ms",
            textSize = 16.0,
            color = Colors.WHITE,
        )
        vcrFont?.let { offset.font = Resourceable.Fixed(it) }
        val offsetY = Constants.GAME_HEIGHT - (offset.height * 2.0 + 40.0)
        metadata += Meta(offset, offsetY, delay)

        rebuild(Mode.STANDARD)
    }

    /** Creates a metadata line parked at its final position but fully transparent. */
    private fun meta(content: String, y: Double, delay: Double) {
        val view = text(content, textSize = 32.0, color = Colors.WHITE)
        vcrFont?.let { view.font = Resourceable.Fixed(it) }
        view.alpha = 0.0
        view.y = y
        view.x = Constants.GAME_WIDTH - 20.0 - view.width
        metadata += Meta(view, y, delay)
    }

    /**
     * `regenerateMenu`: clear the list, reset the selection and rebuild the
     * entries for [newMode]. The new entries start off-screen-left, then slide
     * into their `changeSelection` positions.
     */
    private fun rebuild(newMode: Mode) {
        mode = newMode
        entriesLayer.removeChildren()
        entries = when (mode) {
            Mode.STANDARD -> listOf(
                "Resume" to onResume,
                "Restart Song" to onRestartSong,
                "Change Difficulty" to {
                    rebuild(Mode.DIFFICULTY)
                },
                "Exit to Menu" to onExitToMenu,
            )

            Mode.DIFFICULTY -> difficulties.map { difficultyId ->
                titleCase(difficultyId) to { onChangeDifficulty(difficultyId) }
            } + ("Back" to { rebuild(Mode.STANDARD) })
        }.mapIndexed { index, (label, action) ->
            // `PauseSubState`'s `letter.width *= 2` only resizes the
            // FlxSprite hitbox; it does not scale the graphic, so the
            // entries render at the atlas font's native size.
            val view = FunkinAtlasText.bold(label)
            view.alpha = 0.0
            // `clearAndAddMenuEntries`: x=0, y=70 * index + 30.
            view.x = 0.0
            view.y = 70.0 * index + 30.0
            entriesLayer.addChild(view)
            Entry(action, view)
        }

        selected = 0
        changeSelection(0)
    }

    /**
     * `changeSelection`: the selected entry sits at x=90, y=0.48*height; the
     * others step diagonally away by (26, 156) per index. Entries wrap around
     * at both ends.
     */
    private fun changeSelection(change: Int) {
        if (entries.isEmpty()) return
        val previous = selected
        selected = (selected + change).mod(entries.size)
        if (selected != previous) onScroll()

        for ((index, entry) in entries.withIndex()) {
            entry.startX = entry.view.x
            entry.startY = entry.view.y
            val delta = index - selected
            entry.targetX = 90.0 + delta * 26.0
            entry.targetY = Constants.GAME_HEIGHT * 0.48 + delta * 156.0
            entry.moveTime = 0.0
            entry.view.alpha = if (index == selected) 1.0 else 0.6
        }
    }

    /**
     * Drives the overlay. Input is ignored on the frame the menu opens, like
     * `PauseSubState.justOpened`, so the same key press cannot close it again.
     */
    fun update(dt: Double, input: KnfInput) {
        time += dt
        background.alpha = BACKGROUND_ALPHA * quartOut(min(time / BACKGROUND_FADE_SECONDS, 1.0))

        for (meta in metadata) {
            val t = quartOut(((time - meta.delay) / METADATA_FADE_SECONDS).coerceIn(0.0, 1.0))
            meta.view.alpha = t
            // `transitionMetadataIn`: fade in while moving 5 px downwards.
            meta.view.y = meta.targetY + 5.0 * t
            meta.view.x = Constants.GAME_WIDTH - 20.0 - meta.view.width
        }

        for (entry in entries) {
            entry.moveTime += dt
            val t = quartOut(min(entry.moveTime / ENTRY_MOVE_SECONDS, 1.0))
            entry.view.x = entry.startX + (entry.targetX - entry.startX) * t
            entry.view.y = entry.startY + (entry.targetY - entry.startY) * t
            entry.view.update(time)
        }

        if (input.uiUp) changeSelection(-1)
        if (input.uiDown) changeSelection(1)

        // `PauseSubState`: the selection still moves on the opening frame, but
        // ACCEPT/PAUSE are ignored for one frame so the button that opened the
        // menu cannot also activate an entry or close it.
        if (justOpened) {
            justOpened = false
            return
        }

        if (input.accept) {
            entries.getOrNull(selected)?.action?.invoke()
        } else if (input.pause) {
            onResume()
        }
    }

    companion object {
        /** `Paths.font('vcr.ttf')`, loaded straight from the FNF asset tree. */
        private val vcrFont: TtfFont? by lazy {
            KnfFiles.bytesOrNull("fonts/vcr.ttf")
                ?.let { runCatching { TtfFont(it, "vcr.ttf") }.getOrNull() }
        }

        private const val BACKGROUND_ALPHA = 0.6
        private const val BACKGROUND_FADE_SECONDS = 0.8
        private const val ENTRY_MOVE_SECONDS = 0.33
        private const val METADATA_FADE_SECONDS = 1.8

        /** `FlxEase.quartOut`. */
        private fun quartOut(t: Double): Double {
            val inv = 1.0 - t
            return 1.0 - inv * inv * inv * inv
        }

        /** `currentDifficulty.replace('-', ' ').toTitleCase()`. */
        private fun titleCase(value: String): String =
            value.replace('-', ' ')
                .split(' ')
                .joinToString(" ") { word ->
                    word.replaceFirstChar { it.uppercase() }
                }
    }
}
