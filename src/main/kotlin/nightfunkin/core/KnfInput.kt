package nightfunkin.core

import korlibs.event.Key
import korlibs.korge.input.InputKeys

/**
 * Keyboard bindings and edge detection.
 *
 * FNF's default solo scheme is A/S/W/D plus the arrow keys. We poll
 * `InputKeys` once per frame and keep edge flags, which maps cleanly onto FNF's
 * per-conductor-step input queue.
 */
class KnfInput(private val inputKeys: InputKeys) {

    /** Lane order matches `NoteDirection`: 0=left, 1=down, 2=up, 3=right. */
    private val laneKeys: List<List<Key>> = listOf(
        listOf(Key.A, Key.LEFT),
        listOf(Key.S, Key.DOWN),
        listOf(Key.W, Key.UP),
        listOf(Key.D, Key.RIGHT),
    )

    private val _down = BooleanArray(laneKeys.size)
    private val _justPressed = BooleanArray(laneKeys.size)
    private val _justReleased = BooleanArray(laneKeys.size)

    fun isDown(lane: Int): Boolean = _down[lane]
    fun justPressed(lane: Int): Boolean = _justPressed[lane]
    fun justReleased(lane: Int): Boolean = _justReleased[lane]

    val accept: Boolean get() = anyJustPressed(Key.Z, Key.ENTER, Key.SPACE)
    val back: Boolean get() = anyJustPressed(Key.X, Key.ESCAPE, Key.BACKSPACE)

    /** FNF's PAUSE action: [P, ENTER, ESCAPE]. */
    val pause: Boolean get() = anyJustPressed(Key.P, Key.ENTER, Key.ESCAPE)

    /** FNF's UI_UP / UI_DOWN actions. */
    val uiUp: Boolean get() = anyJustPressed(Key.W, Key.UP)
    val uiDown: Boolean get() = anyJustPressed(Key.S, Key.DOWN)

    val restart: Boolean get() = inputKeys.justPressed(Key.R)

    /** Freeplay song-variation cycling. */
    val previousVariation: Boolean get() = inputKeys.justPressed(Key.Q)
    val nextVariation: Boolean get() = inputKeys.justPressed(Key.E)

    /** Nudges the global audio offset, the way FNF's options do. */
    val offsetDown: Boolean get() = anyJustPressed(Key.OPEN_BRACKET, Key.MINUS)
    val offsetUp: Boolean get() = anyJustPressed(Key.CLOSE_BRACKET, Key.EQUAL)

    private fun anyJustPressed(vararg keys: Key): Boolean = keys.any { inputKeys.justPressed(it) }

    /** Call once per frame, before gameplay logic. */
    fun update() {
        for (lane in laneKeys.indices) {
            val isDownNow = laneKeys[lane].any { inputKeys.get(it) }
            _justReleased[lane] = _down[lane] && !isDownNow
            _justPressed[lane] = !_down[lane] && isDownNow
            _down[lane] = isDownNow
        }
    }

    fun reset() {
        _down.fill(false)
        _justPressed.fill(false)
        _justReleased.fill(false)
    }
}
