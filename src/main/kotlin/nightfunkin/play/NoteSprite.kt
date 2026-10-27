package nightfunkin.play

import korlibs.image.bitmap.slice
import korlibs.korge.view.Container
import korlibs.math.geom.Anchor2D
import korlibs.korge.view.Image
import korlibs.korge.view.image
import korlibs.memory.Float32Buffer
import korlibs.memory.Uint16Buffer
import korlibs.memory.UShortArrayInt
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
 * Ported from `play/notes/SustainTrail.hx`. FNF does not draw the tail as two
 * stacked images: it builds eight vertices (four for the body, four for the
 * end cap) and samples the hold-note strip through them. That distinction is
 * visible on short tails, where the cap has to be clipped through its UVs
 * instead of simply being hidden, so KNF uses the same mesh.
 *
 * The body samples the top row of its column (FNF's negative V coordinate is
 * clamped to it), while the cap is drawn at its native sheet scale. Geometry
 * and the y rules from `Strumline.updateNotes` are kept verbatim below.
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

    /** Intro fly-in offset (`vwooshInNotes`); 0 during normal gameplay. */
    var vwooshOffsetY: Double = 0.0

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
    private val sheet = noteStyle.holdNoteSheet()
    private val sheetHeight: Double = sheet?.height?.toDouble() ?: 0.0
    private val zoom: Double = noteStyle.holdNoteScale.toDouble()
    private val widthPx: Double = ((sheet?.width ?: Constants.STRUMLINE_SIZE) / 8.0) * zoom

    /** `endOffset` / `bottomClip` from `setupHoldNoteGraphic`. */
    private val endOffset: Double = if (noteStyle.isHoldNotePixel) 1.0 else 0.5
    private val bottomClip: Double = if (noteStyle.isHoldNotePixel) 1.0 else 0.9

    /** `graphic.height * zoom * endOffset` - the part of the sheet the cap eats. */
    private val bottomHeightPx: Double = sheetHeight * zoom * endOffset

    /**
     * `SustainTrail.updateHitbox` stores the skin offsets in `offset`, and the
     * custom triangle draw subtracts them from the position before laying out
     * its vertices. The whole trail therefore moves by `-offsets`.
     */
    private val offsetX: Double = noteStyle.holdNoteOffsets.getOrElse(0) { 0.0 }
    private val offsetY: Double = noteStyle.holdNoteOffsets.getOrElse(1) { 0.0 }

    private val vertices = Float32Buffer(16)
    private val uvs = Float32Buffer(16)

    /**
     * One mesh for the body and cap. The texture is the entire eight-column
     * strip, so the direction is selected with UVs exactly like FNF instead of
     * cropping each column into a separate bitmap.
     */
    private val mesh: SustainMesh? = sheet?.let { sheet ->
        SustainMesh(
            texture = sheet.slice(),
            vertices = vertices,
            uvs = uvs,
            indices = Uint16Buffer(
                UShortArrayInt(
                    shortArrayOf(
                        0, 1, 2,
                        1, 2, 3,
                        4, 5, 6,
                        5, 6, 7,
                    ),
                ),
            ),
            smoothing = !noteStyle.isHoldNotePixel,
        ).also {
            it.x = -offsetX
            it.y = -offsetY
            addChild(it)
        }
    }

    init {
        // FNF: `holdNoteSprite.x = this.x + getXPos(dir) + STRUMLINE_SIZE / 2 - holdNote.width / 2;`
        // The mesh itself starts at x = 0, so the lane offset belongs to this
        // container. `-offsetX` stays on the mesh, where FNF's draw subtracts it.
        x = laneX.toDouble() + Constants.STRUMLINE_SIZE / 2.0 - widthPx / 2.0
        parent.addChild(this)
    }

    /** `SustainTrail.sustainHeight`: linear in milliseconds, times the scroll speed. */
    private fun sustainHeightPx(lengthMs: Float): Double =
        lengthMs * Constants.PIXELS_PER_MS.toDouble() * scrollSpeed.toDouble()

    private fun noteOffsetY(conductor: Conductor): Double =
        Constants.PIXELS_PER_MS * (conductor.timeWithDelta - strumTime) * scrollSpeed *
            (if (downscroll) 1.0 else -1.0)

    /**
     * FNF calls `updateClipping()` from `triggerRedraw()` with its default
     * `songTime = 0`, so the mesh is always unclipped at the top during normal
     * play. Keeping the calculation here documents the source and leaves the
     * same behaviour if a future caller passes an explicit time.
     */
    private fun clipHeight(): Double {
        val unclipped = sustainHeightPx(sustainLength + strumTime)
        return unclipped.coerceIn(0.0, sustainHeightPx(sustainLength))
    }

    /**
     * Ported from `SustainTrail.updateClipping` plus the y rules in
     * `Strumline.updateNotes`.
     *
     * The four body vertices are the top edge of the visible tail and the
     * bottom of the solid part. The four cap vertices then stretch the cap's
     * texture from that bottom edge to its rounded end. For tails shorter than
     * `bottomHeight`, `partHeight` becomes negative and the cap's top UV moves
     * down instead of the whole sprite disappearing - that is the clipping
     * behaviour that the old body/cap image pair could not reproduce.
     */
    fun updateGeometry(conductor: Conductor) {
        val mesh = mesh ?: return

        // FNF shortens the tail while the note is held
        // (`holdNote.sustainLength = (strumTime + fullSustainLength) - songPosition`),
        // which makes it shrink at exactly the scroll rate. Use the
        // sub-frame-interpolated clock here: the audio clock itself is only
        // refreshed in coarse chunks, and using it directly makes the tail's
        // end jump by many pixels once per audio update.
        if (clipping) {
            sustainLength = (strumTime + fullSustainLength) - conductor.timeWithDelta
        }
        if (sustainLength <= 10f) {
            visible = false
            return
        }
        visible = true

        val graphicHeight = sustainHeightPx(sustainLength)
        if (graphicHeight <= 0.1) {
            visible = false
            return
        }

        val clipHeight = clipHeight()
        val partHeight = clipHeight - bottomHeightPx
        val topY = if (downscroll) clipHeight else graphicHeight - clipHeight
        val bodyBottomY = if (partHeight > 0.0) {
            if (downscroll) bottomHeightPx else topY + partHeight
        } else {
            topY
        }
        val capBottomY = if (downscroll) {
            sheetHeight * (-bottomClip + endOffset) * zoom
        } else {
            graphicHeight + sheetHeight * (bottomClip - endOffset) * zoom
        }

        // Vertices: body top-left/right, body bottom-left/right, then the same
        // four points for the cap. `flipY` in FNF is represented by the actual
        // vertex order above, not by a negative view scale.
        fun vertex(index: Int, x: Double, y: Double) {
            vertices[index * 2] = x.toFloat()
            vertices[index * 2 + 1] = y.toFloat()
        }
        vertex(0, 0.0, topY)
        vertex(1, widthPx, topY)
        vertex(2, 0.0, bodyBottomY)
        vertex(3, widthPx, bodyBottomY)
        vertex(4, 0.0, bodyBottomY)
        vertex(5, widthPx, bodyBottomY)
        vertex(6, 0.0, capBottomY)
        vertex(7, widthPx, capBottomY)

        // UVs select body column `direction * 2` and cap column `direction * 2 + 1`
        // from the eight-column sheet. The body's FNF V coordinate is negative
        // for a full-length tail; clamping to the top row is what FNF gets from
        // the texture sampler, and sampling row 0 directly avoids relying on the
        // backend's wrap mode.
        val u0 = (directionIndex % 4) / 4.0
        val uBodyRight = u0 + 1.0 / 8.0
        val uCapRight = uBodyRight + 1.0 / 8.0
        val vCapTop = if (partHeight > 0.0) {
            0.0
        } else {
            (bottomHeightPx - clipHeight) / zoom / sheetHeight
        }

        fun uv(index: Int, u: Double, v: Double) {
            uvs[index * 2] = u.toFloat()
            uvs[index * 2 + 1] = v.toFloat()
        }
        uv(0, u0, 0.0)
        uv(1, uBodyRight, 0.0)
        uv(2, u0, 0.0)
        uv(3, uBodyRight, 0.0)
        uv(4, uBodyRight, vCapTop)
        uv(5, uCapRight, vCapTop)
        uv(6, uBodyRight, bottomClip)
        uv(7, uCapRight, bottomClip)

        // The point the tail hangs from is always the head's centre. FNF's
        // downscroll branch subtracts `holdNote.height`, so the same mesh is
        // shifted up by its full length there.
        val attachY = if (clipping) {
            strumlineY - Constants.INITIAL_OFFSET + Constants.STRUMLINE_SIZE / 2.0
        } else {
            strumlineY - Constants.INITIAL_OFFSET + noteOffsetY(conductor) +
                Constants.STRUMLINE_SIZE / 2.0 + yOffset + vwooshOffsetY
        }
        y = attachY - if (downscroll) graphicHeight else 0.0

        mesh.updatedVertices()
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

    /** Intro fly-in offset (`vwooshInNotes`); 0 during normal gameplay. */
    var vwooshOffsetY: Double = 0.0

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
        y = (strumlineY - Constants.INITIAL_OFFSET + noteOffsetY(conductor) + vwooshOffsetY).toDouble()
        holdTrail?.let {
            it.scrollSpeed = scrollSpeed
            it.downscroll = downscroll
            it.strumlineY = strumlineY
            it.vwooshOffsetY = vwooshOffsetY
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
                // FNF keeps a dropped hold visually where it had got to. The
                // upscroll branch shifts the sprite by the played part of the
                // tail; downscroll does not, because the mesh's cap is on the
                // other end (`Strumline.updateNotes` lines 693-711).
                val consumed = (trail.strumTime + trail.fullSustainLength) - conductor.timeWithDelta
                trail.yOffset = if (trail.downscroll) {
                    0f
                } else {
                    (trail.fullSustainLength - consumed.coerceAtLeast(0f)) * Constants.PIXELS_PER_MS
                }
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
