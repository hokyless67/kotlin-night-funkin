package nightfunkin.ui

import korlibs.event.MouseButton
import korlibs.korge.input.touch
import korlibs.korge.view.Container
import korlibs.korge.view.addUpdater
import korlibs.korge.view.image
import korlibs.math.geom.Anchor2D
import nightfunkin.core.Constants
import nightfunkin.core.KnfFiles
import nightfunkin.core.KnfTouch
import nightfunkin.core.TextureAtlas
import kotlin.math.abs

/**
 * On-screen touch controls, added by the scenes on top of everything else.
 *
 * Gameplay is ported from FNF's mobile "Arrows" scheme:
 *
 *  - The visible buttons are the player's own strumline receptors, which the
 *    mobile layout enlarges and spreads across the bottom of the screen
 *    (`PlayScene.layoutTouchStrumlines`). FNF's `FunkinHitbox` hints are
 *    `alpha = 0`, so only their rectangles are used here, via [laneZones].
 *  - The pause button is FNF's `pauseButton` / `pauseCircle` pair in the
 *    top-right corner. Like `TouchUtil.pressAction`, it triggers on release
 *    inside the button.
 *
 * Menu mode is unchanged: invisible full-screen gestures — tap = CONFIRM,
 * vertical drag = UI up/down, horizontal drag = left/right (song difficulty in
 * Freeplay), two-finger tap = BACK.
 *
 * Coordinates are virtual 1280x720 stage space, like every other view in the
 * game. On desktop (`KNF_TOUCH=1`, or full mobile mode via `KNF_MOBILE=1`) the
 * mouse emulates a single finger so the overlay is testable without a touch
 * screen.
 */
class TouchControls : Container() {

    /**
     * One gameplay touch target in stage coordinates, taken from the
     * `FunkinHitbox` transparent hint of a lane.
     */
    class LaneZone(
        val lane: Int,
        val x: Double,
        val y: Double,
        val width: Double,
        val height: Double,
    ) {
        fun contains(px: Double, py: Double): Boolean =
            px >= x && px < x + width && py >= y && py < y + height
    }

    /**
     * Menu gesture mode. PlayScene flips this while the pause menu or the game
     * over screen is up; the menu scenes keep it on all the time.
     */
    var menuMode: Boolean = true
        set(value) {
            if (field == value) return
            field = value
            pauseButton?.visible = !value
            if (value) releaseAll()
            // A pulse left over from the screen we just left must not fire in
            // this one.
            KnfTouch.clearFrameActions()
        }

    /**
     * Gameplay touch targets. Empty on the desktop layout, which falls back to
     * the two thumb pairs in [laneAt].
     */
    var laneZones: List<LaneZone> = emptyList()

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

        /** The press started on the pause button. */
        var pause: Boolean = false
    }

    private val pointers = HashMap<Int, Pointer>()
    private var pauseButton: Container? = null

    // FNF's `pauseButton` hitbox: 123x113 frame at scale 0.8, 35 px from the
    // top-right corner. Kept as defaults so input still works without the art.
    private var pauseX = Constants.GAME_WIDTH - 123.0 * PAUSE_SCALE - PAUSE_MARGIN
    private var pauseY = PAUSE_MARGIN
    private var pauseW = 123.0 * PAUSE_SCALE
    private var pauseH = 113.0 * PAUSE_SCALE

    init {
        buildPauseButton()
        addUpdater { poll() }
    }

    // ------------------------------------------------------------------
    // touch events
    // ------------------------------------------------------------------

    /** `stage.touch` listeners are bound on the first update, once the stage exists. */
    private var eventsBound = false

    /**
     * Listens to the touch events themselves instead of only sampling
     * `input.activeTouches` once per frame.
     *
     * On this phone the game runs at ~7 fps while it streams and uploads, so a
     * normal 80-120 ms tap can *begin and end inside one frame gap*: the poll
     * never sees the finger and the tap is lost. The events fire as Android
     * delivers them, and [KnfTouch.pressed] keeps the press readable until
     * `KnfInput` consumes it, so a tap is never dropped no matter how long the
     * frame took.
     */
    private fun bindTouchEvents() {
        if (eventsBound) return
        val stage = stage ?: return
        eventsBound = true
        stage.touch.start { onPointerDown(it.id, it.global.x, it.global.y) }
        stage.touch.move { onPointerMove(it.id, it.global.x, it.global.y) }
        stage.touch.end { onPointerEnd(it.id) }
    }

    // ------------------------------------------------------------------
    // per-frame polling
    // ------------------------------------------------------------------

    private fun poll() {
        bringToFront()
        bindTouchEvents()
        pollPointers()
    }

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
        // A poll and a touch event can both report the same first frame; the
        // events win, the poll just confirms the position.
        if (pointers.containsKey(id)) {
            onPointerMove(id, x, y)
            return
        }
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
            // FNF's `TouchUtil.pressAction` fires on release, so only remember
            // the press here.
            pointer.pause = true
            pointer.consumed = true
            return
        }
        val lane = laneAt(x, y)
        if (lane >= 0) {
            pointer.lane = lane
            KnfTouch.lanes[lane] = true
            KnfTouch.pressed[lane] = true
        }
    }

    private fun onPointerMove(id: Int, x: Double, y: Double) {
        val pointer = pointers[id] ?: return
        val dx = x - pointer.startX
        val dy = y - pointer.startY
        pointer.x = x
        pointer.y = y
        if (abs(dx) > TAP_SLOP_PX || abs(dy) > TAP_SLOP_PX) pointer.moved = true
        if (!menuMode && !pointer.consumed) {
            if (pointer.lane >= 0 && !laneContains(pointer.lane, x, y)) {
                // `FunkinButton.limitToBounds`: sliding off the button releases it.
                releaseLane(pointer)
            }
            if (pointer.lane < 0) {
                // A finger dragged onto another hint presses it, like Flixel's
                // buttons do when an already-down touch enters them.
                val lane = laneAt(x, y)
                if (lane >= 0) {
                    pointer.lane = lane
                    KnfTouch.lanes[lane] = true
                    KnfTouch.pressed[lane] = true
                }
            }
        }
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
        val pointer = pointers.remove(id) ?: return
        releaseLane(pointer)
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
        if (pointer.pause) {
            // `TouchUtil.pressAction`: `justReleased && overlaps`.
            if (hitPauseButton(pointer.x, pointer.y)) KnfTouch.pauseDown = true
            pointer.pause = false
            return
        }
        if (menuMode && !pointer.consumed && !pointer.moved && pointers.isEmpty()) {
            KnfTouch.acceptDown = true
        }
    }

    private fun releaseAll() {
        KnfTouch.lanes.fill(false)
        KnfTouch.pressed.fill(false)
        for (pointer in pointers.values) {
            pointer.lane = -1
            pointer.navAxis = 0
        }
    }

    /** Releases [pointer]'s lane unless another finger still covers it. */
    private fun releaseLane(pointer: Pointer) {
        if (pointer.lane < 0) return
        val stillHeld = pointers.values.any { it !== pointer && it.lane == pointer.lane }
        if (!stillHeld) KnfTouch.lanes[pointer.lane] = false
        pointer.lane = -1
    }

    /**
     * Whether [x], [y] is still inside the touch target of [lane].
     *
     * FNF's hint rectangles (`setSize(width * 1.35, height * 8)`) overlap a
     * little, so holding a note survives a sideways slide; the fallback
     * desktop zones tile the screen and keep the old hold-until-release
     * behaviour.
     */
    private fun laneContains(lane: Int, x: Double, y: Double): Boolean {
        if (laneZones.isEmpty()) return true
        return laneZones.any { it.lane == lane && it.contains(x, y) }
    }

    // ------------------------------------------------------------------
    // hit testing
    // ------------------------------------------------------------------

    /**
     * Lane for a gameplay press, -1 outside the touch targets.
     *
     * On the mobile "Arrows" layout the targets come from FNF's transparent
     * hints; neighbouring hints overlap by a few dozen pixels, so a press goes
     * to the nearest lane centre - FNF itself lets one touch press every
     * overlapping hint, which would strike several lanes at once.
     */
    private fun laneAt(x: Double, y: Double): Int {
        if (laneZones.isNotEmpty()) {
            var lane = -1
            var distance = Double.MAX_VALUE
            for (zone in laneZones) {
                if (x < zone.x || x >= zone.x + zone.width) continue
                if (y < zone.y || y >= zone.y + zone.height) continue
                val d = abs(x - (zone.x + zone.width / 2.0))
                if (d < distance) {
                    distance = d
                    lane = zone.lane
                }
            }
            return lane
        }

        // Desktop fallback: two pairs under the thumbs - left/down on the left
        // edge, up/right on the right edge - so the middle stays free.
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
        x >= pauseX && x < pauseX + pauseW && y >= pauseY && y < pauseY + pauseH

    // ------------------------------------------------------------------
    // visuals
    // ------------------------------------------------------------------

    /**
     * `PlayState.initPauseSprites`: the `pauseButton` sparrow's first frame at
     * scale 0.8, 35 px from the top-right corner, over `pauseCircle` at alpha
     * 0.1.
     */
    private fun buildPauseButton() {
        val button = Container()
        val atlas = KnfFiles.resolve("pauseButton", ".xml")?.let { TextureAtlas.tryLoad(it) }
        val frame = atlas?.frame("pause0000") ?: atlas?.firstFrame()

        if (atlas != null && frame != null) {
            pauseW = frame.logicalWidth * PAUSE_SCALE
            pauseH = frame.logicalHeight * PAUSE_SCALE
            pauseX = Constants.GAME_WIDTH - pauseW - PAUSE_MARGIN
            pauseY = PAUSE_MARGIN

            // `pauseCircle` is centred on the button and draws behind it.
            KnfFiles.resolve("pauseCircle", ".png")?.let { path ->
                KnfFiles.bitmapOrNull(path)?.let { bmp ->
                    button.image(bmp).also { img ->
                        img.anchor = Anchor2D.TOP_LEFT
                        img.scaleX = PAUSE_CIRCLE_SCALE_X
                        img.scaleY = PAUSE_CIRCLE_SCALE_Y
                        img.smoothing = true
                        img.alpha = PAUSE_CIRCLE_ALPHA
                        img.x = pauseX + pauseW / 2.0 - bmp.width * PAUSE_CIRCLE_SCALE_X / 2.0
                        img.y = pauseY + pauseH / 2.0 - bmp.height * PAUSE_CIRCLE_SCALE_Y / 2.0
                    }
                }
            }

            // The atlas frame is trimmed: its logical box starts at (x, y) and
            // the artwork is inset by -frameX / -frameY.
            button.image(atlas.slice(frame)).also { img ->
                img.anchor = Anchor2D.TOP_LEFT
                img.scaleX = PAUSE_SCALE
                img.scaleY = PAUSE_SCALE
                img.smoothing = true
                img.x = pauseX - frame.frameX * PAUSE_SCALE
                img.y = pauseY - frame.frameY * PAUSE_SCALE
            }
        }

        button.visible = !menuMode
        pauseButton = button
        addChild(button)
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

        /** Desktop fallback zones cover the bottom part of the screen. */
        const val ZONE_TOP_FRACTION = 0.55

        /**
         * Width of one fallback zone as a fraction of the stage width. Zones
         * come in two pairs hugging the screen edges, so the middle stays clear.
         */
        const val ZONE_WIDTH_FRACTION = 0.20

        /** A tap must stay within this distance of its start. */
        const val TAP_SLOP_PX = 12.0

        /** Drag distance that locks the gesture onto a navigation axis. */
        const val DRAG_NAV_PX = 40.0

        /** `pauseButton.setPosition((FlxG.width - width) - 35, 35)` + scale 0.8. */
        const val PAUSE_SCALE = 0.8
        const val PAUSE_MARGIN = 35.0
        const val PAUSE_CIRCLE_SCALE_X = 0.84
        const val PAUSE_CIRCLE_SCALE_Y = 0.8
        const val PAUSE_CIRCLE_ALPHA = 0.1
    }
}
