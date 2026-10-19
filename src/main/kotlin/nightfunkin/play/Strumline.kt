package nightfunkin.play

import korlibs.korge.view.Container
import korlibs.math.geom.Anchor2D
import korlibs.korge.view.Image
import korlibs.korge.view.addUpdater
import korlibs.korge.view.image
import nightfunkin.audio.dtSeconds
import nightfunkin.core.AtlasSlice
import nightfunkin.core.Constants
import nightfunkin.core.NoteStyle
import nightfunkin.core.setPixelArt
import nightfunkin.data.SongNoteData
import nightfunkin.timing.Conductor

/**
 * One lane: receptors, incoming notes and splashes.
 *
 * Ported from `play/notes/Strumline.hx` (0.8.6). Notes stream in as they enter
 * the render window and are recycled into a free list, so the frame cost depends
 * on how many notes are *visible*, not on song length.
 */
class Strumline(
    val container: Container,
    private val noteStyle: NoteStyle,
    val isPlayer: Boolean,
    initialScrollSpeed: Float,
    val downscroll: Boolean,
) {
    // ---- public state ---------------------------------------------------
    var scrollSpeed: Float = initialScrollSpeed

    var noteData: List<SongNoteData> = emptyList()
        private set

    /** Index of the next note to spawn вЂ” this is what keeps the loop O(visible). */
    var nextNoteIndex: Int = 0
        private set

    val notes: MutableList<NoteSprite> = arrayListOf()
    private val freeNotes = ArrayList<NoteSprite>()

    private val receptors = arrayOfNulls<Receptor>(Constants.KEY_COUNT)
    private val splashLayers = arrayOfNulls<Container>(Constants.KEY_COUNT)

    /**
     * Lane-local y of the receptor line. Notes are children of the same
     * container, so this stays local — adding the lane's screen y on top of it
     * would count the offset twice.
     */
    var strumlineY: Float = 0f

    /** Total notes in the chart, for accuracy maths. */
    var noteCount: Int = 0
        private set

    init {
        buildReceptors()
    }

    // ======================================================================
    // setup
    // ======================================================================

    private fun buildReceptors() {
        for (i in 0 until Constants.KEY_COUNT) {
            receptors[i] = Receptor(container, i, noteStyle).also {
                // Receptor, note head and tail all sit on the same lane centre.
                // FNF spreads them by a few pixels (`INITIAL_OFFSET` on the
                // receptor, `STRUMLINE_SIZE / 2` on the tail), which leaves the
                // hold tail visibly off to the right of its arrow.
                it.container.x = xPos(i)
                it.container.y = 0.0
            }
        }
    }

    /** Displayed height of a receptor sprite; FNF uses this to place the lane. */
    fun receptorHeight(): Double {
        var h = 0.0
        for (i in 0 until Constants.KEY_COUNT) {
            val slice = noteStyle.strumSlice(i, "Static") ?: continue
            h = maxOf(h, slice.height.toDouble() * noteStyle.scale.toDouble())
        }
        return if (h <= 0.0) Constants.STRUMLINE_SIZE.toDouble() else h
    }

    /**
     * Lane-local x of a direction, matching `Strumline.getXPos`.
     *
     * Note the lanes are `NOTE_SPACING` apart *regardless of the skin scale* —
     * FNF scales the arrow sprites themselves, not the lane pitch, which is why
     * lanes stay put when you switch to the pixel skin.
     */
    fun xPos(direction: Int): Double = direction.toDouble() * Constants.NOTE_SPACING

    fun setNoteData(data: List<SongNoteData>) {
        clean()
        noteData = data.sortedBy { it.time }
        noteCount = noteData.size
        nextNoteIndex = 0
    }

    fun resetScrollSpeed(newSpeed: Float) {
        scrollSpeed = newSpeed
    }

    /** Render distance in ms, matching FNF so existing charts feel the same. */
    val renderDistanceMs: Float
        get() = Constants.GAME_HEIGHT / Constants.PIXELS_PER_MS /
            (if (scrollSpeed < 1f) scrollSpeed else 1f)

    // ======================================================================
    // per-frame
    // ======================================================================

    fun update(conductor: Conductor, songStart: Float = 0f, dt: Double = 1.0 / 60.0) {
        spawnIncoming(conductor, songStart)
        updateNotes(conductor)
        receptors.forEach { it?.update(dt) }
    }

    private fun spawnIncoming(conductor: Conductor, songStart: Float) {
        val windowEnd = conductor.songPosition + renderDistanceMs
        val windowStart = maxOf(songStart, conductor.songPosition - Constants.HIT_WINDOW_MS)

        while (nextNoteIndex < noteData.size) {
            val nd = noteData[nextNoteIndex]
            if (nd.time > windowEnd) break
            nextNoteIndex++
            if (nd.time < windowStart) continue
            spawn(nd)
        }
    }

    private fun spawn(nd: SongNoteData) {
        val dir = nd.direction
        val note = freeNotes.removeLastOrNull()?.also { it.setDirection(dir, noteStyle.noteSlice(dir)) }
            ?: NoteSprite(container, dir, noteStyle, noteStyle.noteSlice(dir))

        note.strumTime = nd.time
        note.fullSustainLength = nd.length
        note.sustainLength = nd.length
        note.isPlayerNote = nd.isPlayerNote
        note.kind = nd.kind
        note.scrollSpeed = scrollSpeed
        note.downscroll = downscroll
        note.strumlineY = strumlineY
        note.laneX = xPos(dir).toFloat()
        note.hasBeenHit = false
        note.hasMissed = false
        note.handledMiss = false
        note.tooEarly = false
        note.mayHit = false
        note.lowPriority = false
        note.judgement = null
        note.visible = true

        // Head, centred on the lane and nudged 2px left, matching FNF.
        note.x = xPos(dir) - Constants.NUDGE

        if (nd.length > 0f) note.ensureTrail()
        notes += note
    }

    private fun updateNotes(conductor: Conductor) {
        val now = conductor.songPosition
        var i = 0
        while (i < notes.size) {
            val note = notes[i]
            note.updatePosition(conductor)

            // GRhythmUtil.processWindow
            if (!note.hasBeenHit && !note.hasMissed) {
                if (!isPlayer) {
                    // Opponent / AI lands exactly on the beat.
                    note.mayHit = now >= note.strumTime
                } else {
                    note.mayHit = now >= note.strumTime - Constants.HIT_WINDOW_MS
                    note.tooEarly = !note.mayHit
                }
                if (now > note.strumTime + Constants.HIT_WINDOW_MS) {
                    note.hasMissed = true
                    note.mayHit = false
                    note.timingErrorMs = now - note.strumTime
                }
            }

            val offscreen = if (downscroll) {
                note.y > Constants.GAME_HEIGHT + 128
            } else {
                note.y < -256
            }
            // FNF recycles anything resolved (hit or missed) once it scrolls off.
            // Hit notes matter here too, otherwise they pile up for the whole song.
            if (offscreen && (note.hasMissed || note.hasBeenHit || note.handledMiss)) {
                recycle(i)
                continue
            }
            i++
        }
    }

    private fun recycle(index: Int) {
        val note = notes.removeAt(index)
        note.visible = false
        note.holdTrail?.let { it.visible = false }
        freeNotes += note
    }

    // ======================================================================
    // input-facing API
    // ======================================================================

    /** Live notes in [direction] that may still be struck, oldest first. */
    fun notesMayHit(direction: Int): List<NoteSprite> = notes.filter {
        it.directionIndex == direction && it.mayHit && !it.hasBeenHit && !it.hasMissed
    }

    fun pressKey(direction: Int) {
        receptors[direction]?.press()
        playSplash(direction)
    }

    fun releaseKey(direction: Int) {
        receptors[direction]?.release()
    }

    fun playConfirm(direction: Int) {
        receptors[direction]?.confirm()
    }

    /**
     * FNF's `Strumline.holdConfirm`: the receptor keeps the confirm pose for as
     * long as the hold note is being held.
     */
    fun holdConfirm(direction: Int) {
        receptors[direction]?.holdConfirm()
    }

    fun playStatic(direction: Int) {
        receptors[direction]?.release()
    }

    /**
     * `Strumline.playNoteSplash`.
     *
     * FNF plays a short 24 fps animation and kills the sprite when it ends, so
     * the splash is a brief flare at the receptor rather than something that
     * drifts across the lane.
     */
    private fun playSplash(direction: Int) {
        if (!noteStyle.splashEnabled) return
        val variants = noteStyle.splashVariants(direction)
        if (variants.isEmpty()) return

        val layer = splashLayers[direction] ?: Container().also {
            it.y = 0.0
            container.addChild(it)
            splashLayers[direction] = it
        }
        while (layer.numChildren >= Constants.NOTE_SPLASH_CAP) layer.removeChildAt(0)

        val frames = variants.random().toMutableList()
        val first = frames.first()
        val ox = noteStyle.splashOffsets.getOrElse(0) { 0.0 }
        val oy = noteStyle.splashOffsets.getOrElse(1) { 0.0 }

        val img = layer.image(first)
        img.anchor = Anchor2D.TOP_LEFT
        // FNF's `NoteSplash.play` uses `offset.set(width * 0.3, height * 0.3)`.
        img.x = xPos(direction) + Constants.INITIAL_OFFSET + ox
        img.y = -Constants.INITIAL_OFFSET + oy
        img.alpha = noteStyle.splashAlpha

        val fps = 24 + (-2..2).random()
        val step = 1.0 / fps
        var elapsed = 0.0
        img.addUpdater { dt ->
            elapsed += dt.dtSeconds()
            if (elapsed >= step) {
                elapsed -= step
                frames.removeAt(0)
                if (frames.isEmpty()) {
                    img.removeFromParent()
                } else {
                    img.bitmap = frames.first()
                }
            }
        }
    }
    // ======================================================================
    // lifecycle
    // ======================================================================

    fun clean() {
        notes.forEach { it.kill() }
        notes.clear()
        freeNotes.forEach { it.kill() }
        freeNotes.clear()
        nextNoteIndex = 0
    }

    fun destroy() {
        clean()
        receptors.forEach { it?.destroy() }
        splashLayers.forEach { it?.removeFromParent() }
        receptors.fill(null)
        splashLayers.fill(null)
    }
}

/**
 * A single arrow receptor with static / press / confirm states.
 *
 * FNF's receptors are *animations*, not single sprites -
 * `pressLeft0` and `confirmLeft0` are four-frame sequences - so this plays the
 * frames through instead of swapping one PNG.
 *
 * ```haxe
 * public function playPress():Void {
 *     this.active = (forceActive || isAnimationDynamic('press'));
 *     this.playAnimation('press', true);
 * }
 * ```
 *
 * The animations are not looped (`anim.looped ?? false`), so they play once and
 * freeze on their last frame.
 */
private class Receptor(
    private val host: Container,
    private val direction: Int,
    private val noteStyle: NoteStyle,
) {
    val container: Container = Container().also { host.addChild(it) }

    /**
     * FNF's `StrumlineNote.fixOffsets`: while the "confirm" animation plays, the
     * graphic is nudged to compensate for the glow the animation draws around
     * the arrow.
     */
    private val confirmOffset = 13.0

    private var state = "Static"
    private var frames: List<AtlasSlice> = emptyList()
    private var frameIndex = 0
    private var timer = 0.0
    private var finished = true

    private val image: Image = Container().let { _ ->
        // The image lives in its own child container so the confirm offset can
        // move the graphic without moving the receptor's lane position.
        Image(korlibs.image.bitmap.Bitmap32(1, 1, intArrayOf(0), false)).also { img ->
            container.addChild(img)
        }
    }

    init {
        redraw()
    }

    private fun setState(next: String) {
        if (state == next && finished) return
        state = next
        frames = noteStyle.strumFrames(direction, next)
        if (frames.isEmpty()) {
            // No animation for this state - fall back to the single sprite.
            val single = noteStyle.strumSlice(direction, next)
            frames = listOf(single)
        }
        frameIndex = 0
        timer = 0.0
        finished = frames.size <= 1
        redraw()
    }

    private fun redraw() {
        val slice = frames.getOrNull(frameIndex) ?: return
        image.bitmap = slice
        image.anchor = Anchor2D.CENTER
        image.scaleX = noteStyle.scale.toDouble()
        image.scaleY = noteStyle.scale.toDouble()
        image.x = if (state == "Confirm") -confirmOffset else 0.0
        image.y = if (state == "Confirm") -confirmOffset else 0.0
        image.alpha = 0.95
        image.setPixelArt(noteStyle.isPixel)
    }

    fun press() {
        setState("Press")
    }

    fun confirm() {
        setState("Confirm")
    }

    /**
     * FNF's `StrumlineNote.holdConfirm`: once the confirm animation has played
     * out, the held receptor keeps the confirm pose.
     */
    fun holdConfirm() {
        if (state == "Confirm" && finished) setState("ConfirmHold")
        else if (state != "Confirm") setState("Confirm")
    }

    fun release() {
        setState("Static")
    }

    fun update(dt: Double) {
        if (frames.size <= 1 || finished) return
        val step = 1.0 / noteStyle.strumFrameRate
        timer += dt
        while (timer >= step) {
            timer -= step
            frameIndex++
            if (frameIndex >= frames.size) {
                frameIndex = frames.size - 1
                finished = true
                timer = 0.0
                break
            }
        }
        redraw()
    }

    fun destroy() = container.removeFromParent()
}
