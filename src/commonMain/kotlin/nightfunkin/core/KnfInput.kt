package nightfunkin.core

import korlibs.event.Key
import korlibs.korge.input.InputKeys

/**
 * Input bindings and edge detection, merged from two sources:
 *
 *  - the keyboard, polled from `InputKeys` once per frame (FNF's default solo
 *    scheme is A/S/W/D plus the arrow keys), and
 *  - the on-screen touch controls ([KnfTouch]), whose lane zones and action
 *    buttons the [nightfunkin.ui.TouchControls] overlay writes every frame.
 *
 * Edge flags are computed centrally here, which maps cleanly onto FNF's
 * per-conductor-step input queue — gameplay and menus need no changes to work
 * with either source.
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

    // Touch action edges (one-shot pulses written by the overlay and consumed
    // here, so an event that fires between two updates is never dropped).
    private var touchAcceptJust = false
    private var touchBackJust = false
    private var touchPauseJust = false

    fun isDown(lane: Int): Boolean = _down[lane]
    fun justPressed(lane: Int): Boolean = _justPressed[lane]
    fun justReleased(lane: Int): Boolean = _justReleased[lane]

    val accept: Boolean get() = anyJustPressed(Key.Z, Key.ENTER, Key.SPACE) || touchAcceptJust
    val back: Boolean get() = anyJustPressed(Key.X, Key.ESCAPE, Key.BACKSPACE) || touchBackJust

    /** FNF's PAUSE action: [P, ENTER, ESCAPE]. */
    val pause: Boolean get() = anyJustPressed(Key.P, Key.ENTER, Key.ESCAPE) || touchPauseJust

    /**
     * PAUSE without CONFIRM. Conversations check CUTSCENE_ADVANCE (Z/ENTER)
     * before PAUSE, so ENTER must advance the line instead of opening the
     * pause menu there. The touch pause button carries no CONFIRM semantics,
     * so it drives this action too.
     */
    val pauseOnly: Boolean get() = anyJustPressed(Key.P, Key.ESCAPE) || touchPauseJust

    /** FNF's UI_UP / UI_DOWN actions — the same edges as lanes 2 / 1. */
    val uiUp: Boolean get() = justPressed(2)
    val uiDown: Boolean get() = justPressed(1)

    val restart: Boolean get() = inputKeys.justPressed(Key.R)

    /** Freeplay's `FREEPLAY_LEFT` / `FREEPLAY_RIGHT` (Q/E). */
    val letterSortLeft: Boolean get() = inputKeys.justPressed(Key.Q)
    val letterSortRight: Boolean get() = inputKeys.justPressed(Key.E)

    /** Freeplay's `FREEPLAY_JUMP_TO_TOP` / `FREEPLAY_JUMP_TO_BOTTOM` (HOME/END). */
    val jumpToTop: Boolean get() = inputKeys.justPressed(Key.HOME)
    val jumpToBottom: Boolean get() = inputKeys.justPressed(Key.END)

    /** Freeplay's `FREEPLAY_FAVORITE` (F). */
    val favorite: Boolean get() = inputKeys.justPressed(Key.F)

    /** Nudges the global audio offset, the way FNF's options do. */
    val offsetDown: Boolean get() = anyJustPressed(Key.OPEN_BRACKET, Key.MINUS)
    val offsetUp: Boolean get() = anyJustPressed(Key.CLOSE_BRACKET, Key.EQUAL)

    private fun anyJustPressed(vararg keys: Key): Boolean = keys.any { inputKeys.justPressed(it) }

    /** Call once per frame, before gameplay logic. */
    fun update() {
        val touchLanes = KnfTouch.lanes
        for (lane in laneKeys.indices) {
            val isDownNow = laneKeys[lane].any { inputKeys.get(it) } ||
                (lane < touchLanes.size && touchLanes[lane])
            _justReleased[lane] = _down[lane] && !isDownNow
            _justPressed[lane] = (!_down[lane] && isDownNow) || KnfTouch.pressed[lane]
            _down[lane] = isDownNow
        }
        // A tap that began and ended inside one frame gap leaves `lanes` clear
        // but `pressed` set; it still counts as a press.
        KnfTouch.pressed.fill(false)

        touchAcceptJust = KnfTouch.acceptDown
        touchBackJust = KnfTouch.backDown
        touchPauseJust = KnfTouch.pauseDown
        // The overlay writes these from touch callbacks at any point in the
        // frame, so they stay set until an update consumes them.
        KnfTouch.clearFrameActions()
    }

    fun reset() {
        _down.fill(false)
        _justPressed.fill(false)
        _justReleased.fill(false)
        touchAcceptJust = false
        touchBackJust = false
        touchPauseJust = false
    }
}
