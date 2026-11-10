package nightfunkin.ui

import korlibs.event.MouseButton
import korlibs.image.color.Colors
import korlibs.korge.view.Container
import korlibs.korge.view.View
import korlibs.korge.view.addUpdater
import korlibs.korge.view.roundRect
import korlibs.korge.view.text
import korlibs.math.geom.RectCorners
import korlibs.math.geom.Size
import nightfunkin.core.Constants
import nightfunkin.core.KnfTouch
import kotlin.math.abs

/**
 * On-screen touch controls, added by the scenes on top of everything else.
 *
 * Two modes:
 *  - **Gameplay** (`menuMode == false`): four thumb zones at the bottom, split
 *    into a pair under each thumb (lanes left/down on the left, up/right on the
 *    right), full multi-touch, plus a pause button in the top-right corner.
 *    Presses feed [KnfTouch.lanes], which [nightfunkin.core.KnfInput] merges
 *    with the keyboard — receptors, hit logic and holds work unchanged.
 *  - **Menu** (`menuMode == true`): invisible full-screen gestures — tap =
 *    CONFIRM, vertical drag = UI up/down, horizontal drag = left/right (song
 *    difficulty in Freeplay), two-finger tap = BACK.
 *
 * Coordinates are virtual 1280x720 stage space, like every other view in the
 * game. On desktop (`KNF_TOUCH=1`, or full mobile mode via `KNF_MOBILE=1`) the
 * mouse emulates a single finger so the overlay is testable without a touch
 * screen.
 */
class TouchControls : Container() {

    /**
     * Menu gesture mode. PlayScene flips this while the pause menu or the game
     * over screen is up; the menu scenes keep it on all the time.
     */
    var menuMode: Boolean = true
        set(value) {
            field = value
            zones?.visible = !value
            pauseButton?.visible = !value
            if (value) releaseAll()
        }

    /** One tracked finger (or the emulated mouse pointer). */
    private class Pointer(
        var x: Double,
        var y: Double,
        val startX: Double,
        val startY: Double,
    ) {
        /** Gameplay lane zone held by this pointer, -1 when none. */
        var lane: Int = -1

        /** Menu drag axis once it passed the threshold: 0 none, -1 vertical, 1 horizontal. */
        var navAxis: Int = 0

        /** Passed the tap slop, so it can no longer count as a tap. */
        var moved: Boolean = false

        /** Gesture already handled (two-finger back, pause button). */
        var consumed: Boolean = false
    }

    private val pointers = HashMap<Int, Pointer>()
    private var zones: Container? = null
    private var pauseButton: View? = null

    init {
        buildZones()
        buildPauseButton()
        addUpdater { poll() }
    }

    // ------------------------------------------------------------------
    // per-frame polling
    // ------------------------------------------------------------------

    private fun poll() {
        bringToFront()
        // Action flags are one-frame pulses; the tap/button handlers below run
        // after this clear within the same poll.
        KnfTouch.clearFrameActions()
        pollPointers()
        if (++debugFrames % 120 == 1) {
            val input = stage?.views?.input
            println(
                "KNF: touchPoll f=$debugFrames stage=${stage != null} " +
                    "activeTouches=${input?.activeTouches?.size} " +
                    "mouseButtons=${input?.mouseButtons} mousePos=${input?.mousePos} menuMode=$menuMode"
            )
        }
    }

    private var debugFrames = 0

    private fun pollPointers() {
        // Views access goes through the stage: plain views do not carry a
        // `views` reference in KorGE 6.
        val input = stage?.views?.input ?: return
        val touches = input.activeTouches
        val seen = HashSet<Int>()

        if (touches.isNotEmpty()) {
            for (touch in touches) {
                seen.add(touch.id)
                val existing = pointers[touch.id]
                if (existing == null) onPointerDown(touch.id, touch.x, touch.y)
                else onPointerMove(touch.id, touch.x, touch.y)
            }
        }

        // End detection runs EVERY frame regardless of the branch above: when
        // a finger lifts, `activeTouches` empties out entirely, and a check
        // scoped to the non-empty branch would never fire, leaving the
        // pointer stuck forever.
        for (id in pointers.keys.filter { it >= 0 && it !in seen }) onPointerEnd(id)

        // Desktop fallback: with no real touch tracked, the left mouse button
        // emulates one finger.
        if (touches.isEmpty() && pointers.keys.none { it >= 0 }) {
            val mouseDown = input.mouseButtonPressed(MouseButton.LEFT)
            val had = pointers.containsKey(MOUSE_POINTER_ID)
            val pos = input.mousePos
            when {
                mouseDown && !had -> onPointerDown(MOUSE_POINTER_ID, pos.x, pos.y)
                mouseDown && had -> onPointerMove(MOUSE_POINTER_ID, pos.x, pos.y)
                !mouseDown && had -> onPointerEnd(MOUSE_POINTER_ID)
            }
        }
    }

    // ------------------------------------------------------------------
    // pointer state machine
    // ------------------------------------------------------------------

    private fun onPointerDown(id: Int, x: Double, y: Double) {
        println("KNF: touchDown id=$id x=$x y=$y menuMode=$menuMode")
        val pointer = Pointer(x, y, x, y)
        pointers[id] = pointer

        if (menuMode) {
            // A second finger while one is already down is BACK; both fingers
            // are consumed so the remaining one cannot turn into a tap.
            if (pointers.size >= 2) {
                for (held in pointers.values) held.consumed = true
                KnfTouch.backDown = true
            }
            return
        }

        if (hitPauseButton(x, y)) {
            pointer.consumed = true
            KnfTouch.pauseDown = true
            return
        }
        val lane = laneAt(x, y)
        if (lane >= 0) {
            pointer.lane = lane
            KnfTouch.lanes[lane] = true
        }
    }

    private fun onPointerMove(id: Int, x: Double, y: Double) {
        val pointer = pointers[id] ?: return
        val dx = x - pointer.startX
        val dy = y - pointer.startY
        pointer.x = x
        pointer.y = y
        if (abs(dx) > TAP_SLOP_PX || abs(dy) > TAP_SLOP_PX) pointer.moved = true
        if (!menuMode || pointer.consumed) return

        // Lock onto the first direction the drag crosses; hold the matching
        // navigation lanes until the drag ends (scenes already implement
        // hold-to-repeat scrolling on top of `KnfInput.isDown`).
        if (pointer.navAxis == 0) {
            if (abs(dy) >= DRAG_NAV_PX) pointer.navAxis = NAV_VERTICAL
            else if (abs(dx) >= DRAG_NAV_PX) pointer.navAxis = NAV_HORIZONTAL
        }
        when (pointer.navAxis) {
            NAV_VERTICAL -> {
                KnfTouch.lanes[2] = dy < 0 // up
                KnfTouch.lanes[1] = dy > 0 // down
            }
            NAV_HORIZONTAL -> {
                KnfTouch.lanes[0] = dx < 0 // left
                KnfTouch.lanes[3] = dx > 0 // right
            }
        }
    }

    private fun onPointerEnd(id: Int) {
        println("KNF: touchEnd id=$id")
        val pointer = pointers.remove(id) ?: return
        if (pointer.lane >= 0) {
            // Only release when no other finger still covers that lane.
            val stillHeld = pointers.values.any { it.lane == pointer.lane }
            if (!stillHeld) KnfTouch.lanes[pointer.lane] = false
            pointer.lane = -1
        }
        when (pointer.navAxis) {
            NAV_VERTICAL -> {
                KnfTouch.lanes[1] = false
                KnfTouch.lanes[2] = false
            }
            NAV_HORIZONTAL -> {
                KnfTouch.lanes[0] = false
                KnfTouch.lanes[3] = false
            }
        }
        if (menuMode && !pointer.consumed && !pointer.moved && pointers.isEmpty()) {
            KnfTouch.acceptDown = true
        }
    }

    private fun releaseAll() {
        KnfTouch.lanes.fill(false)
        for (pointer in pointers.values) {
            pointer.lane = -1
            pointer.navAxis = 0
        }
    }

    // ------------------------------------------------------------------
    // hit testing
    // ------------------------------------------------------------------

    /**
     * Lane for a gameplay zone press, -1 outside the zones.
     *
     * The zones sit in two pairs under the thumbs — left/down on the left edge,
     * up/right on the right edge — so the middle of the screen stays free.
     */
    private fun laneAt(x: Double, y: Double): Int {
        if (y < Constants.GAME_HEIGHT * ZONE_TOP_FRACTION) return -1
        val zoneWidth = Constants.GAME_WIDTH * ZONE_WIDTH_FRACTION
        val pairSpan = zoneWidth * 2.0
        val rightStart = Constants.GAME_WIDTH - pairSpan
        return when {
            x < pairSpan -> (x / zoneWidth).toInt().coerceIn(0, 1)
            x >= rightStart -> 2 + ((x - rightStart) / zoneWidth).toInt().coerceIn(0, 1)
            else -> -1
        }
    }

    private fun hitPauseButton(x: Double, y: Double): Boolean =
        x >= Constants.GAME_WIDTH - PAUSE_HIT_W && y <= PAUSE_HIT_H

    // ------------------------------------------------------------------
    // visuals
    // ------------------------------------------------------------------

    private fun buildZones() {
        val height = Constants.GAME_HEIGHT * (1.0 - ZONE_TOP_FRACTION)
        val width = Constants.GAME_WIDTH * ZONE_WIDTH_FRACTION
        val rightStart = Constants.GAME_WIDTH - width * 2.0
        val lefts = listOf(0.0, width, rightStart, rightStart + width)
        val top = Constants.GAME_HEIGHT * ZONE_TOP_FRACTION
        zones = Container().also { zoneLayer ->
            for (lane in 0 until 4) {
                zoneLayer.roundRect(
                    Size(width - ZONE_GAP, height - ZONE_GAP),
                    RectCorners(14.0, 14.0, 14.0, 14.0),
                    fill = Colors.WHITE,
                ).also {
                    it.x = lefts[lane] + ZONE_GAP / 2.0
                    it.y = top + ZONE_GAP / 2.0
                    it.alpha = 0.10
                }
            }
            zoneLayer.visible = !menuMode
            addChild(zoneLayer)
        }
    }

    private fun buildPauseButton() {
        pauseButton = Container().also { button ->
            button.roundRect(
                Size(56.0, 40.0),
                RectCorners(8.0, 8.0, 8.0, 8.0),
                fill = Colors.WHITE,
            ).also { it.alpha = 0.22 }
            button.text("II", textSize = 20.0, color = Colors.WHITE).also {
                it.x = 28.0 - it.width / 2.0
                it.y = 20.0 - it.height / 2.0
            }
            button.x = Constants.GAME_WIDTH - 64.0
            button.y = 12.0
            button.alpha = 0.7
            button.visible = !menuMode
            addChild(button)
        }
    }

    // ------------------------------------------------------------------

    /** Runtime overlays (pause menu, game over) would cover this otherwise. */
    private fun bringToFront() {
        val parent = parent ?: return
        if (parent.numChildren == 0 || parent.getChildAt(parent.numChildren - 1) !== this) {
            removeFromParent()
            parent.addChild(this)
        }
    }

    private companion object {
        const val MOUSE_POINTER_ID = -1
        const val NAV_VERTICAL = -1
        const val NAV_HORIZONTAL = 1

        /** Gameplay zones cover the bottom part of the screen. */
        const val ZONE_TOP_FRACTION = 0.55

        /**
         * Width of one zone as a fraction of the stage width. Zones come in two
         * pairs hugging the screen edges, so the middle stays clear.
         */
        const val ZONE_WIDTH_FRACTION = 0.20

        /** Inset between neighbouring zone rects, in stage pixels. */
        const val ZONE_GAP = 12.0

        /** A tap must stay within this distance of its start. */
        const val TAP_SLOP_PX = 12.0

        /** Drag distance that locks the gesture onto a navigation axis. */
        const val DRAG_NAV_PX = 40.0

        /** Pause button hit area: top-right corner. */
        const val PAUSE_HIT_W = 76.0
        const val PAUSE_HIT_H = 68.0
    }
}
