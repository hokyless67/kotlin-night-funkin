package nightfunkin.play

import korlibs.korge.view.Container
import korlibs.math.geom.Anchor2D
import korlibs.korge.view.Image
import korlibs.korge.view.image
import nightfunkin.core.AtlasSlice
import nightfunkin.core.Constants
import nightfunkin.core.NoteDirection
import nightfunkin.core.NoteStyle
import nightfunkin.core.setPixelArt
import nightfunkin.timing.Conductor
import kotlin.math.abs

/**
 * The sustain "tail" of a hold note.
 *
 * FNF draws this with a custom mesh over the hold-note sheet; KNF stretches
 * the body column and pins the end cap, which reads identically at gameplay
 * speed and costs nothing.
 *
 * Geometry follows `play/notes/SustainTrail.hx` plus the y rules in
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

    /**
     * FNF's `SustainTrail.setupHoldNoteGraphic`:
     *
     * ```haxe
     * graphicWidth = graphic.width / 8 * zoom;
     * var bottomHeight:Float = graphic.height * zoom * endOffset;
     * ```
     *
     * `zoom` is the note style's own hold-note scale (0.7 for `funkin`, 6 for
     * `pixel`), which is *not* the note scale - that is why the pixel hold note
     * is a 72x6 sheet stretched to a fat bar.
     */
    private val widthPx: Double =
        ((noteStyle.holdNoteSheetWidth() ?: Constants.STRUMLINE_SIZE) / 8.0) *
            noteStyle.holdNoteScale.toDouble()

    /** `endOffset` / `bottomClip` from `setupHoldNoteGraphic`. */
    private val endOffset: Double = if (noteStyle.isHoldNotePixel) 1.0 else 0.5
    private val bottomClip: Double = if (noteStyle.isHoldNotePixel) 1.0 else 0.9

    /** `graphic.height * zoom * endOffset` - the part of the sheet the cap eats. */
    private val bottomHeightPx: Double =
        (noteStyle.holdNoteSheetHeight() ?: 0) * noteStyle.holdNoteScale.toDouble() * endOffset

    init {
        // FNF: `holdNoteSprite.x = this.x + getXPos(dir) + STRUMLINE_SIZE / 2 - holdNote.width / 2;`
        // The tail's artwork is centred on the lane, exactly like the note head.
        x = laneX.toDouble() + Constants.STRUMLINE_SIZE / 2.0

        val pair = noteStyle.sustainSlices(directionIndex)
        if (pair != null) {
            val (bodySlice, capSlice) = pair
            // FNF's vertices start at the top of the sheet and grow downwards, so
            // the body hangs from its first row rather than being centred on it.
            body = image(bodySlice).also { it.anchor = Anchor2D.TOP_LEFT }
            // FNF samples the cap's UVs from 0 to `bottomClip` (0.9 for the
            // normal skins, 1.0 for pixel), i.e. the column is drawn at its own
            // uniform zoom, not squashed into `endOffset`. Crop the column to
            // that fraction so KorGE reproduces the same height.
            val capRows = (capSlice.height * bottomClip).toInt().coerceAtLeast(1)
            val capVisible =
                if (capRows < capSlice.height) capSlice.sliceWithSize(0, 0, capSlice.width, capRows)
                else capSlice
            cap = image(capVisible).also { it.anchor = Anchor2D.TOP_LEFT }
            body?.setPixelArt(noteStyle.isHoldNotePixel)
            cap?.setPixelArt(noteStyle.isHoldNotePixel)
        }
        parent.addChild(this)
    }

    /** `SustainTrail.sustainHeight`: linear in milliseconds, times the scroll speed. */
    private fun sustainHeightPx(lengthMs: Float): Double =
        lengthMs * Constants.PIXELS_PER_MS.toDouble() * scrollSpeed.toDouble()

    private fun noteOffsetY(conductor: Conductor): Double =
        Constants.PIXELS_PER_MS * (conductor.timeWithDelta - strumTime) * scrollSpeed *
            (if (downscroll) 1.0 else -1.0)

    /**
     * Ported from the three branches of `Strumline.updateNotes`.
     *
     * FNF's trail is a mesh whose first row sits at the sprite's position and
     * grows *downwards* (upscroll) or *upwards* (downscroll, `flipY`):
     *
     * ```haxe
     * // upscroll, not held
     * holdNote.y = this.y - INITIAL_OFFSET + getNoteY(...) + STRUMLINE_SIZE / 2 + yOffset;
     * // upscroll, held
     * holdNote.y = this.y - INITIAL_OFFSET + STRUMLINE_SIZE / 2;
     * // downscroll, not held
     * holdNote.y = this.y - INITIAL_OFFSET + getNoteY(...) - holdNote.height + STRUMLINE_SIZE / 2 + yOffset;
     * // downscroll, held
     * holdNote.y = this.y - INITIAL_OFFSET - holdNote.height + STRUMLINE_SIZE / 2;
     * ```
     *
     * The `- holdNote.height` term cancels the mesh's own height, so the edge
     * that meets the arrow is `y + graphicHeight` - the head's *centre* in both
     * scroll directions. That is the point computed below as [attachY]; getting
     * it wrong is what leaves the tail hanging below the arrow instead of being
     * clipped in its middle.
     */
    fun updateGeometry(conductor: Conductor) {
        val b = body ?: return
        val c = cap ?: return

        // FNF shortens the tail while the note is held
        // (`holdNote.sustainLength = (strumTime + fullSustainLength) - songPosition`),
        // which makes it shrink at exactly the scroll rate - the far end of the
        // tail therefore stays put in the world while the top is pinned to the
        // receptor.
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

        val half = Constants.STRUMLINE_SIZE / 2.0
        val graphicHeight = sustainHeightPx(sustainLength)
        val bodyHeight = graphicHeight - bottomHeightPx
        if (bodyHeight <= 1.0) {
            b.visible = false
            c.visible = false
            return
        }
        b.visible = true
        c.visible = true

        // The point the tail hangs from is always the head's centre: the
        // receptor centre once it is held, and the (moving) head centre while it
        // is being approached. FNF computes a different `holdNote.y` for each
        // scroll direction (`y`, and `y - height`), but the mesh's leading edge
        // is `y` upscroll and `y + graphicHeight` downscroll, so the on-screen
        // attachment is identical either way.
        val attachY = if (clipping) {
            strumlineY - Constants.INITIAL_OFFSET + half
        } else {
            strumlineY - Constants.INITIAL_OFFSET + noteOffsetY(conductor) + half + yOffset
        }
        // The container only carries the lane's X; the Y is absolute, so the
        // container must not offset it a second time.
        y = 0.0

        // The body hangs from the attachment, in the direction the note came
        // from; the cap then closes the far end (`flipY` mirrors both in FNF).
        val bodyTop = if (downscroll) attachY - bodyHeight else attachY
        b.x = -widthPx / 2.0
        b.y = bodyTop
        b.scaleX = widthPx / b.bitmap.width.toDouble().coerceAtLeast(1.0)
        b.scaleY = bodyHeight / b.bitmap.height.toDouble().coerceAtLeast(1.0)

        // FNF draws the cap at its own uniform `zoom` over the top `bottomClip`
        // of the column, so its height is `graphic.height * zoom * bottomClip`
        // and the rounded end reaches
        // `graphic.height * zoom * (bottomClip - endOffset)` past the body.
        val capScreenHeight = c.bitmap.height.toDouble() * noteStyle.holdNoteScale.toDouble()
        c.scaleX = noteStyle.holdNoteScale.toDouble()
        c.scaleY = noteStyle.holdNoteScale.toDouble()
        c.x = -widthPx / 2.0
        c.y = if (downscroll) bodyTop - capScreenHeight else bodyTop + bodyHeight
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
        // FNF: `this.notes.zIndex = 30;` - the note head draws over the tail.
        zIndex = 30.0
        slice?.let { s ->
            head = image(s).also {
                // Flixel draws a sprite's graphic from its *top left* corner
                // (`centerOffsets` + `centerOrigin` cancel out), so the note head
                // is anchored the same way as the receptor it has to land on.
                it.anchor = Anchor2D.TOP_LEFT
                it.scaleX = noteStyle.scale.toDouble()
                it.scaleY = noteStyle.scale.toDouble()
                it.setPixelArt(noteStyle.isPixel)
            }
        }
    }

    val isHoldNote: Boolean get() = fullSustainLength > 0f
    val isHolding: Boolean get() = holdTrail?.clipping == true

    fun setDirection(dir: Int, slice: AtlasSlice?) {
        // The tail belongs to the note that owned it, so it always goes with the
        // note into the pool - otherwise a tap note can inherit the tail of the
        // hold note that used the same sprite.
        holdTrail?.kill()
        holdTrail = null
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
            // FNF: `this.holdNotes.zIndex = 20;` - the tail draws over the
            // receptor but under the note head.
            trail = SustainTrail(host, dir, noteStyle, laneX).also { it.zIndex = 20.0 }
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

    /** FNF hides the tail of a hold note that was dropped: holdNote.visible = false. */
    fun killTrail() {
        holdTrail?.kill()
        holdTrail = null
    }

    fun startClip() {
        holdTrail?.clipping = true
    }

    /**
     * FNF's `Strumline.hitNote` -> `killNote`: the note head is hidden (and
     * killed) as soon as it is struck, while the hold trail lives on. This is
     * what lets a held trail show all the way up to the receptor's centre
     * instead of being covered by its own arrow.
     */
    fun hideHead() {
        head?.visible = false
    }

    /** Re-shows the head when the pooled sprite is handed back out. */
    fun showHead() {
        head?.visible = true
    }

    fun releaseClip(conductor: Conductor? = null) {
        holdTrail?.let { trail ->
            if (trail.clipping && conductor != null) {
                // FNF keeps a dropped hold visually where it had got to:
                // `yOffset = (fullSustainLength - sustainLength) * PIXELS_PER_MS`.
                val consumed = (trail.strumTime + trail.fullSustainLength) - conductor.songPosition
                trail.yOffset =
                    (trail.fullSustainLength - consumed.coerceAtLeast(0f)) * Constants.PIXELS_PER_MS
                // The tail is cut back to what was actually played.
                trail.sustainLength = consumed.coerceAtLeast(0f)
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
