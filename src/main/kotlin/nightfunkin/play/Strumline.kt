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
     * Which lanes are currently held down.
     *
     * The receptor needs to know: FNF's `StrumlineNote.onAnimationFinished` only
     * falls back to the pressed pose while the key is still down.
     */
    private val heldKeys = BooleanArray(Constants.KEY_COUNT)

    /** The hold note covers, one per lane. */
    private val holdCovers = arrayOfNulls<HoldCover>(Constants.KEY_COUNT)

    /**
     * FNF's `Constants.GHOST_TAP_DELAY` (3/8 s): a tap into empty air is
     * forgiven for a moment after a real hit.
     */
    private var ghostTapTimer = 0.0

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
                // FNF: child.x = getXPos(dir) + INITIAL_OFFSET; child.y = 0; - the receptors
                // are children of the strumline, so the lane offset is added here.
                it.container.x = xPos(i) + Constants.INITIAL_OFFSET.toDouble()
                it.container.y = 0.0
                // FNF: `this.strumlineNotes.zIndex = 10;`
                it.container.zIndex = 10.0
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
        if (ghostTapTimer > 0.0) {
            ghostTapTimer -= dt * 1000.0
            if (ghostTapTimer <= 0.0) ghostTapTimer = 0.0
        }
        // FNF's `Strumline.updateNotes`:
        //
        // ```haxe
        // for (dir in DIRECTIONS) {
        //     if (isKeyHeld(dir) && getByDirection(dir).getCurrentAnimation() == 'static') {
        //         playPress(dir);
        //     }
        // }
        // ```
        //
        // The press pose is driven by the *held* state, and only entered from
        // the static one - which is also what makes the receptor come back to
        // the static pose once the confirm animation is over.
        for (dir in 0 until Constants.KEY_COUNT) {
            val receptor = receptors[dir] ?: continue
            if (heldKeys[dir] && receptor.isStatic) receptor.press()
            receptor.update(dt, heldKeys[dir])
            holdCovers[dir]?.update(dt)
        }
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
        note.showHead()

        // FNF: `noteSprite.x = this.x + getXPos(dir) - (width - STRUMLINE_SIZE) / 2 - NUDGE;`
        // The note's artwork is centred on that position, so the head and the
        // receptor line up when the note reaches the strumline.
        note.x = xPos(dir) - (note.displayWidth() - Constants.STRUMLINE_SIZE) / 2.0 - Constants.NUDGE.toDouble()

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

            // A hold note's tail lives on after its head has scrolled off, so
            // the note has to stay around until the hold is over. FNF keeps them
            // apart - the head is in the `notes` group and the tail in
            // `holdNotes` - while KNF hangs the tail off the head.
            if (note.isHoldNote && note.isHolding) {
                val remaining = (note.strumTime + note.fullSustainLength) - now
                if (remaining <= 0f) note.releaseClip(conductor)
            }

            // FNF recycles anything resolved (hit or missed) once it scrolls off.
            // Hit notes matter here too, otherwise they pile up for the whole song.
            val holdActive = note.isHoldNote && note.isHolding
            if (!holdActive && offscreen && (note.hasMissed || note.hasBeenHit || note.handledMiss)) {
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

    /**
     * FNF's `Strumline.playPress` path: the key went down.
     *
     * The press *pose* is not what this plays - the splash is FNF's
     * `PlayState.processNoteHit`:
     *
     * ```haxe
     * playerStrumline.hitNote(note, !event.isComboBreak);
     * if (event.doesNotesplash) playerStrumline.playNoteSplash(note.noteData.getDirection());
     * ```
     *
     * so the impact graphic belongs to a hit, not to a key press.
     */
    fun pressKey(direction: Int) {
        heldKeys[direction] = true
        receptors[direction]?.press()
    }

    fun releaseKey(direction: Int) {
        heldKeys[direction] = false
        receptors[direction]?.release()
    }

    /** FNF's `Strumline.isKeyHeld`, including notes struck by the bot. */
    fun isKeyHeld(direction: Int): Boolean = heldKeys[direction]

    /**
     * FNF's `Strumline.hitNote`: the confirm animation plus the note splash.
     */
    fun hitNote(direction: Int) {
        receptors[direction]?.confirm()
        playSplash(direction)
        // FNF forgives ghost taps for a moment after a hit.
        ghostTapTimer = Constants.GHOST_TAP_DELAY
    }

    /**
     * FNF's `Strumline.playNoteHoldCover`: the glow that covers the receptor
     * while a hold note is held.
     */
    fun playNoteHoldCover(direction: Int) {
        val asset = noteStyle.holdCover(direction) ?: return
        // FNF: `this.noteHoldCovers.zIndex = 40;`
        val cover = HoldCover(container, direction, asset, noteStyle).also { it.zIndex = 40.0 }
        cover.laneX = xPos(direction)
        cover.strumlineY = strumlineY.toDouble()
        holdCovers[direction]?.kill()
        holdCovers[direction] = cover
    }

    /** FNF's `holdNote.cover.playEnd()`, when the hold is released. */
    fun endNoteHoldCover(direction: Int) {
        holdCovers[direction]?.playEnd()
    }

    /**
     * FNF's `Strumline.mayGhostTap`: a tap into empty air is only a ghost tap
     * when no note is in range and none was hit recently.
     */
    fun mayGhostTap(): Boolean {
        if (notes.any { it.mayHit }) return false
        if (notes.any { it.isHoldNote && (it.hasBeenHit || it.hasMissed) }) return false
        if (ghostTapTimer > 0.0) return false
        return true
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
            it.zIndex = 50.0
            splashLayers[direction] = it
        }
        while (layer.numChildren >= Constants.NOTE_SPLASH_CAP) layer.removeChildAt(0)

        val frames = variants.random().toMutableList()
        val first = frames.first()
        val ox = noteStyle.splashOffsets.getOrElse(0) { 0.0 }
        val oy = noteStyle.splashOffsets.getOrElse(1) { 0.0 }

        val img = layer.image(first.slice)
        img.anchor = Anchor2D.TOP_LEFT
        // FNF's `NoteSplash.play`:
        //
        // ```haxe
        // splash.x = this.x + getXPos(direction) + INITIAL_OFFSET + offsets[0] * scale.x;
        // splash.y = this.y - INITIAL_OFFSET + offsets[1] * scale.y;
        // // Center the animation on the note splash.
        // offset.set(width * 0.3, height * 0.3);
        // ```
        //
        // The splash sprite never gets `centerOrigin`, so its origin stays zero
        // and the artwork ends up 30% of its size up and to the left of the lane
        // - which is what centres the impact graphic on the note.
        val w = first.slice.width.toDouble()
        val h = first.slice.height.toDouble()
        img.x = xPos(direction) + Constants.INITIAL_OFFSET + ox - w * 0.3 - first.offsetX
        img.y = -Constants.INITIAL_OFFSET + oy - h * 0.3 - first.offsetY
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
                    img.bitmap = frames.first().slice
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

    companion object {
        /** FNF's `StrumlineNote.CONFIRM_HOLD_TIME`. */
        const val CONFIRM_HOLD_TIME = 0.15
    }

    private var state = ""
    private var frames: List<NoteStyle.TrimmedFrame> = emptyList()
    private var frameIndex = 0
    private var timer = 0.0
    private var finished = true

    /**
     * FNF's `StrumlineNote.confirmHoldTimer`: how long the confirm pose is kept
     * after the confirm animation finished.
     */
    private var confirmTimer = -1.0

    private val image: Image = Container().let { _ ->
        // The image is a plain child of the lane container; each animation frame
        // is placed relative to the fixed logical centre computed below.
        Image(korlibs.image.bitmap.Bitmap32(1, 1, intArrayOf(0), false)).also { img ->
            container.addChild(img)
        }
    }

    private fun setState(next: String) {
        if (state == next && finished) return
        state = next
        frames = noteStyle.strumFrames(direction, next)
        if (frames.isEmpty()) {
            // No animation for this state - fall back to the single sprite.
            val single = noteStyle.strumSlice(direction, next)
            frames = listOf(NoteStyle.TrimmedFrame(single, 0, 0))
        }
        frameIndex = 0
        timer = 0.0
        finished = frames.size <= 1
        redraw()
    }

    private val receptorScale: Double = noteStyle.strumlineScale.toDouble()

    /**
     * Where every animation frame's *logical* centre lands, in the container's
     * coordinates.
     *
     * Flixel's `StrumlineNote.setup` calls `updateHitbox()` while the atlas
     * still points at its first frame, so the sprite's `width`/`height` - used
     * by `centerOffsets` - never change afterwards. `playAnimation` then calls
     * `centerOffsets()` + `centerOrigin()` (but never `updateHitbox`), which
     * pins the logical frame's centre at `x + width / 2`, `y + height / 2` for
     * *every* state. The container already carries `xPos(dir) + INITIAL_OFFSET`,
     * so the centre within it is simply half of that fixed hitbox.
     *
     * `StrumlineNote.fixOffsets` exists in the sources but is never called in
     * 0.8.6, so the confirm glow is *not* nudged.
     */
    private val baseSize: Pair<Int, Int> =
        noteStyle.strumlineBaseSize() ?: (Constants.STRUMLINE_SIZE to Constants.STRUMLINE_SIZE)

    private val centreX: Double = baseSize.first * receptorScale / 2.0
    private val centreY: Double = baseSize.second * receptorScale / 2.0

    init {
        // The static pose has to be drawn up front, otherwise the receptor only
        // appears once the player touches a key.
        //
        // This has to run *after* the properties above: Kotlin initialises
        // properties in declaration order, so calling `setState` earlier would
        // place the arrow with `centreX`/`centreY` still at zero, and it would
        // only jump into place on the first key press.
        state = ""
        finished = false
        setState("Static")
    }

    private fun redraw() {
        val frame = frames.getOrNull(frameIndex) ?: return
        val slice = frame.slice
        image.bitmap = slice
        // Flixel draws the trimmed artwork at
        //   pos - offset + origin + scale * (frameTrim + q - origin)
        // with `origin = frameWidth / 2` and `offset = (frameWidth - width) / 2`.
        // The logical centre therefore stays at `pos + fixedHitbox / 2`, and the
        // artwork's top-left lands at
        //   centre + scale * (trimX - frameWidth / 2)
        // where `trimX = -frameX` and `frameWidth` is the *current* frame's
        // logical width (states use different sizes, which is what makes the
        // arrow sink on press and bloom on confirm).
        val s = receptorScale
        val fw = if (frame.frameWidth > 0) frame.frameWidth.toDouble() else slice.width.toDouble()
        val fh = if (frame.frameHeight > 0) frame.frameHeight.toDouble() else slice.height.toDouble()
        image.anchor = Anchor2D.TOP_LEFT
        image.scaleX = s
        image.scaleY = s
        image.x = centreX + s * (-frame.offsetX - fw / 2.0)
        image.y = centreY + s * (-frame.offsetY - fh / 2.0)
        image.alpha = 0.95
        image.setPixelArt(noteStyle.isPixel)
    }

    fun press() {
        setState("Press")
    }

    fun confirm() {
        setState("Confirm")
        // FNF's `StrumlineNote.onAnimationFinished` starts a timer, and
        // `update` falls back to the static pose once it runs out.
        confirmTimer = 0.0
    }

    /** True while the receptor shows its idle pose. */
    val isStatic: Boolean get() = state == "Static"

    /**
     * FNF's `StrumlineNote.holdConfirm`: the receptor keeps the confirm pose
     * for as long as the hold note is being held.
     */
    fun holdConfirm() {
        if (state == "Confirm" && finished) setState("ConfirmHold")
        else if (state != "Confirm") setState("Confirm")
    }

    fun release() {
        setState("Static")
    }

    fun update(dt: Double, keyHeld: Boolean = false) {
        if (frames.size > 1 && !finished) {
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
        if (confirmTimer >= 0.0) {
            confirmTimer += dt
            // FNF's `CONFIRM_HOLD_TIME`: after the confirm glow, the receptor
            // goes back to the static pose. The strumline's update loop then
            // puts it back on the pressed one if the key is still down.
            if (confirmTimer >= CONFIRM_HOLD_TIME) {
                confirmTimer = -1.0
                setState("Static")
            }
        }
    }

    fun destroy() = container.removeFromParent()
}
