package nightfunkin.play

import korlibs.korge.view.Container
import korlibs.math.geom.Anchor2D
import korlibs.korge.view.Image
import korlibs.korge.view.image
import nightfunkin.core.AtlasSlice
import nightfunkin.core.Constants
import nightfunkin.core.NoteDirection
import nightfunkin.core.NoteStyle
import nightfunkin.timing.Conductor
import kotlin.math.abs

/**
 * The sustain "tail" of a hold note.
 *
 * FNF draws this with a custom mesh over `NOTE_hold_assets.png`; KNF stretches
 * the body slice and pins the end cap, which reads identically at gameplay
 * speed and costs nothing.
 *
 * Geometry follows `play/notes/SustainTrail.hx` plus the y-offset rules in
 * `Strumline.updateNotes`.
 */
class SustainTrail(
    parent: Container,
    private val directionIndex: Int,
    private val noteStyle: NoteStyle,
    /** Lane-local centre of this direction's receptor. */
    private val laneX: Float,
) : Container() {

    var strumTime: Float = 0f
    var fullSustainLength: Float = 0f
    var sustainLength: Float = 0f
    var scrollSpeed: Float = 1f
    var downscroll: Boolean = false
    var strumlineY: Float = 0f

    /** True while the head is held, which pins the tail to the receptor. */
    var clipping: Boolean = false

    /** Set when a held note is released early, so the tail stays where it was. */
    var yOffset: Float = 0f

    private var body: Image? = null
    private var cap: Image? = null

    /** `SustainTrail.setupHoldNoteGraphic`: `graphic.width / 8 * holdNoteScale`. */
    private val widthPx: Double =
        (noteStyle.sustainWidth() ?: Constants.STRUMLINE_SIZE) * noteStyle.scale.toDouble()

    init {
        // FNF: `holdNoteSprite.x = this.x + getXPos(dir) + STRUMLINE_SIZE / 2 - width / 2`,
        // and the trail is centre-anchored, so its centre is the lane centre.
        // Without this every tail piles up in lane 0.
        x = laneX.toDouble()

        val pair = noteStyle.sustainSlices(directionIndex)
        if (pair != null) {
            val (bodySlice, capSlice) = pair
            // Both sprites pivot on their centre, matching FNF's
            // `origin.set(width * 0.5, height * 0.5)`.
            body = image(bodySlice).also { it.anchor = Anchor2D.CENTER }
            cap = image(capSlice).also { it.anchor = Anchor2D.TOP }
            cap?.visible = false
        }
        parent.addChild(this)
    }

    /** `SustainTrail.sustainHeight`: linear in milliseconds, times the scroll speed. */
    private fun sustainHeightPx(): Double =
        sustainLength * Constants.PIXELS_PER_MS.toDouble() * scrollSpeed.toDouble()

    private fun noteOffsetY(conductor: Conductor): Double =
        Constants.PIXELS_PER_MS * (conductor.timeWithDelta - strumTime) * scrollSpeed *
            (if (downscroll) 1.0 else -1.0)

    /**
     * Ported from the three branches of `Strumline.updateHoldNotes`.
     *
     * The trail is centre-anchored and sits `STRUMLINE_SIZE / 2` below the note
     * head; once the head is being held it pins to that spot and shrinks, and a
     * note released early keeps the offset it had drifted to.
     */
    fun updateGeometry(conductor: Conductor) {
        val b = body ?: return
        val c = cap ?: return

        if (clipping) {
            sustainLength = (strumTime + fullSustainLength) - conductor.songPosition
        }
        if (sustainLength <= 10f) {
            visible = false
            b.visible = false
            c.visible = false
            return
        }
        visible = true

        val headY = strumlineY - Constants.INITIAL_OFFSET + noteOffsetY(conductor)
        val half = Constants.STRUMLINE_SIZE / 2.0
        val centreY = if (clipping) strumlineY - Constants.INITIAL_OFFSET + half else headY + half + yOffset

        val h = sustainHeightPx()
        if (h <= 1.0) {
            b.visible = false
            c.visible = false
            return
        }
        b.visible = true
        c.visible = true

        y = centreY
        b.x = 0.0
        b.y = 0.0
        b.scaleX = widthPx / b.bitmap.width.toDouble().coerceAtLeast(1.0)
        b.scaleY = h / b.bitmap.height.toDouble().coerceAtLeast(1.0)

        // The rounded cap closes the end the tail is heading towards; downscroll
        // mirrors the trail, so the cap swaps to the top (`flipY` in FNF).
        val capScale = widthPx / c.bitmap.width.toDouble().coerceAtLeast(1.0)
        c.scaleX = capScale
        c.scaleY = capScale
        c.x = 0.0
        c.y = if (downscroll) -h / 2.0 else h / 2.0
    }

    fun kill() = removeFromParent()
}
/**
 * A single note вЂ” either a tap or the head of a hold.
 *
 * Ported from `play/notes/NoteSprite.hx`. The y-position rule is FNF's
 * `GRhythmUtil.getNoteY`, verbatim.
 */
class NoteSprite(
    private val host: Container,
    directionIndex: Int,
    private val noteStyle: NoteStyle,
    slice: AtlasSlice?,
) : Container() {

    var directionIndex: Int = directionIndex
        private set

    val direction: NoteDirection get() = NoteDirection.of(directionIndex)

    var strumTime: Float = 0f
    var fullSustainLength: Float = 0f
    var sustainLength: Float = 0f

    // --- hit state (mirrors FNF's NoteSprite flags) ---
    var hasBeenHit = false
    var hasMissed = false
    var handledMiss = false
    var tooEarly = false
    var mayHit = false
    var lowPriority = false
    var isPlayerNote = true
    var kind: String? = null
    var judgement: Judgement? = null
    var timingErrorMs: Float = 0f

    var strumlineY: Float = 0f
    /** Lane-local centre of this direction's receptor, where the tail hangs. */
    var laneX: Float = 0f
    var scrollSpeed: Float = 1f
    var downscroll: Boolean = false

    var holdTrail: SustainTrail? = null
        private set

    private var head: Image? = null

    init {
        host.addChild(this)
        slice?.let { s ->
            head = image(s).also {
                // Flixel draws sprites centred on their origin; matching that here
                // is what keeps the arrow head sitting on the receptor.
                it.anchor = Anchor2D.CENTER
                it.scaleX = noteStyle.scale.toDouble()
                it.scaleY = noteStyle.scale.toDouble()
            }
        }
    }

    val isHoldNote: Boolean get() = fullSustainLength > 0f
    val isHolding: Boolean get() = holdTrail?.clipping == true

    fun setDirection(dir: Int, slice: AtlasSlice?) {
        // A pooled note can come back as a different direction, and the tail is
        // baked for one direction only, so it has to be rebuilt.
        if (dir != directionIndex) {
            holdTrail?.kill()
            holdTrail = null
        }
        directionIndex = dir
        val img = head
        if (img != null && slice != null) {
            img.bitmap = slice
            img.scaleX = noteStyle.scale.toDouble()
            img.scaleY = noteStyle.scale.toDouble()
        }
    }

    /** Displayed width of the note head, which FNF uses to centre it on the lane. */
    fun displayWidth(): Double {
        val slice = noteStyle.noteSlice(directionIndex) ?: return Constants.STRUMLINE_SIZE.toDouble()
        return slice.width.toDouble() * noteStyle.scale.toDouble()
    }

    /** Lazily attaches (or refreshes) the sustain tail. */
    fun ensureTrail(): SustainTrail? {
        val dir = directionIndex
        var trail = holdTrail
        if (trail == null) {
            trail = SustainTrail(host, dir, noteStyle, laneX)
            holdTrail = trail
        }
        trail.strumTime = strumTime
        trail.fullSustainLength = fullSustainLength
        trail.sustainLength = fullSustainLength
        trail.scrollSpeed = scrollSpeed
        trail.downscroll = downscroll
        trail.strumlineY = strumlineY
        trail.clipping = false
        trail.visible = true
        return trail
    }

    fun isInWindow(conductor: Conductor, windowMs: Float = Constants.HIT_WINDOW_MS): Boolean =
        abs(conductor.songPosition - strumTime) <= windowMs

    /** FNF's `GRhythmUtil.getNoteY`: linear in milliseconds, not in beats. */
    fun noteOffsetY(conductor: Conductor): Float =
        Constants.PIXELS_PER_MS * (conductor.timeWithDelta - strumTime) * scrollSpeed *
            (if (downscroll) 1f else -1f)

    fun updatePosition(conductor: Conductor) {
        // FNF: `note.y = strumline.y - INITIAL_OFFSET + getNoteY(...)`. The note is
        // a child of the strumline, so only the lane-*local* part belongs here.
        y = (strumlineY - Constants.INITIAL_OFFSET + noteOffsetY(conductor)).toDouble()
        holdTrail?.let {
            it.scrollSpeed = scrollSpeed
            it.downscroll = downscroll
            it.strumlineY = strumlineY
            it.updateGeometry(conductor)
        }
    }

    fun startClip() {
        holdTrail?.clipping = true
    }

    fun releaseClip(conductor: Conductor? = null) {
        holdTrail?.let { trail ->
            if (trail.clipping && conductor != null) {
                // FNF keeps a dropped hold visually where it had got to:
                // `yOffset = (fullSustainLength - sustainLength) * PIXELS_PER_MS`.
                val consumed = (trail.strumTime + trail.fullSustainLength) - conductor.songPosition
                trail.yOffset =
                    (trail.fullSustainLength - consumed.coerceAtLeast(0f)) * Constants.PIXELS_PER_MS
            }
            trail.clipping = false
        }
    }

    fun kill() {
        holdTrail?.kill()
        holdTrail = null
        removeFromParent()
    }
}
