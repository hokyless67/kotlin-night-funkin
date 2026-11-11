package nightfunkin.play

import korlibs.korge.view.Container
import korlibs.korge.blend.BlendMode
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
import kotlin.math.pow
import kotlin.math.sqrt

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
    /**
     * FNF's `Strumline.strumlineScale`: a multiplier on the note style's own
     * receptor scale. The touch "Arrows" scheme blows the player's lane up to
     * `1.95 * height / width`, so the receptors double as the on-screen buttons.
     */
    private var laneScale: Float = 1f,
    /**
     * FNF's `Strumline.noteSpacingScale`: the lane pitch multiplier. Set from
     * `PlayState.initNoteHitbox` on the touch layout.
     */
    private var noteSpacingScale: Float = 1f,
    /**
     * FNF's `inArrowControlSchemeMode && isPlayer`: shifts the first/second and
     * third/fourth lanes apart so the four touch buttons can share the bottom
     * of the screen (`Strumline.getXPos`).
     */
    private val arrowsScheme: Boolean = false,
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
     * Everything that is not a receptor: notes, hold tails, covers and
     * splashes. FNF's `Strumline.enterMiniMode` hides all of it except the
     * arrows, so the opponent lane on the touch layout is just a row of
     * bouncing mini arrows.
     */
    private val noteEffects = Container()

    /** FNF's `Strumline.get_width()`, in pixels. */
    val laneWidth: Double
        get() = Constants.KEY_COUNT.toDouble() * Constants.NOTE_SPACING * noteSpacingScale * laneScale

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
     * Intro fly-in (`Strumline.vwooshInNotes`): notes slide in from below (or
     * above on downscroll) over half a second. `-1` means inactive.
     */
    private var vwooshElapsed = -1.0
    private val vwooshDuration = 0.5
    private val vwooshDistance = 200.0

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
        // Added after the receptors so notes, tails and splashes draw over them.
        // The z-index mirrors FNF's `notes` group and keeps that true on a
        // container that sorts by z-index.
        noteEffects.zIndex = 30.0
        container.addChild(noteEffects)
    }

    // ======================================================================
    // setup
    // ======================================================================

    private fun buildReceptors() {
        for (i in 0 until Constants.KEY_COUNT) {
            receptors[i] = Receptor(container, i, noteStyle, isPlayer).also {
                // FNF: `this.strumlineNotes.zIndex = 10;`
                it.container.zIndex = 10.0
            }
        }
        placeReceptors()
        for (i in 0 until Constants.KEY_COUNT) {
            // FNF's `fadeInArrows`: y -= 10, alpha = 0, then each arrow tweens
            // back over 1s after a staggered 0.5s delay.
            receptors[i]?.startIntro(i)
        }
    }

    /** Our lane pitch in pixels (`getXPos` multiplier). */
    private val lanePitch: Double
        get() = Constants.NOTE_SPACING.toDouble() * noteSpacingScale * laneScale

    /**
     * Lays the receptors out at their current scale/spacing.
     *
     * FNF's `Strumline.setNoteSpacing` does the same thing whenever
     * `strumlineScale` changes.
     */
    private fun placeReceptors() {
        val offsets = noteStyle.strumlineOffsets
        val offsetX = offsets.getOrElse(0) { 0.0 }
        val offsetY = offsets.getOrElse(1) { 0.0 }
        for (i in 0 until Constants.KEY_COUNT) {
            val receptor = receptors[i] ?: continue
            // FNF: child.x = getXPos(dir) + INITIAL_OFFSET; child.y = 0; - the
            // receptors are children of the strumline, so the lane offset is
            // added here. `applyStrumlineOffsets` then shifts only the
            // receptor, which is why note heads and hold trails use the
            // unshifted lane centre.
            receptor.container.x = xPos(i) + Constants.INITIAL_OFFSET + offsetX
            receptor.container.y = offsetY
            receptor.setLaneScale(laneScale)
        }
    }

    /**
     * FNF's `PlayState.initNoteHitbox` lane setup: rescale the lane and change
     * its pitch. Used by the touch "Arrows" scheme.
     */
    fun configureLanes(scale: Float, spacing: Float = noteSpacingScale) {
        laneScale = scale
        noteSpacingScale = spacing
        placeReceptors()
    }

    /**
     * FNF's `Strumline.enterMiniMode`: hide the lane's notes and turn it into
     * a row of small arrows. The opponent lane gets this on touch devices.
     */
    fun enterMiniMode(scale: Float) {
        noteEffects.visible = false
        configureLanes(scale)
    }

    /**
     * FNF's `Strumline.constructNoteSprite` on the touch scheme:
     * `noteSprite.scale.scale(strumlineScale - (height / width) * 0.2)`, which
     * leaves the note heads a hair smaller than the enlarged receptors.
     */
    private val noteScaleMultiplier: Double
        get() = if (arrowsScheme) {
            laneScale - (Constants.GAME_HEIGHT.toDouble() / Constants.GAME_WIDTH) * 0.2
        } else {
            1.0
        }

    /** Displayed height of a receptor sprite; FNF uses this to place the lane. */
    fun receptorHeight(): Double {
        // `Strumline.height` comes from the receptors' fixed setup hitbox,
        // which keeps the note style scale even after `strumlineScale` changes.
        val base = noteStyle.strumlineBaseSize()?.second ?: Constants.STRUMLINE_SIZE
        return base * noteStyle.strumlineScale.toDouble()
    }

    /** One invisible touch target, in lane-local coordinates. */
    data class TouchHint(
        val lane: Int,
        val x: Double,
        val y: Double,
        val width: Double,
        val height: Double,
    )

    /**
     * `FunkinHitbox.createHintTransparentNote` + `FunkinHint.follow`: the
     * player lane's touch targets are the note arrows scaled to roughly the
     * receptor, widened 1.35x and stretched 8x vertically.
     *
     * The hint sprites themselves have `alpha = 0` in FNF - what you actually
     * see is the player's strumline - so only the rectangles matter here.
     *
     * `initNoteHitbox`'s `for (strum in playerStrumline) strum.width *= 2`
     * iterates the *group's members* (background, note groups...), and
     * `FlxSpriteGroup.set_width` is a no-op, so the receptor width the hint
     * follows is the fixed setup hitbox, i.e. plain
     * `noteStyle.strumlineScale * frameWidth`.
     */
    fun touchHints(): List<TouchHint> {
        val base = noteStyle.strumlineBaseSize()
            ?: (Constants.STRUMLINE_SIZE to Constants.STRUMLINE_SIZE)
        val offsets = noteStyle.strumlineOffsets
        val offsetX = offsets.getOrElse(0) { 0.0 }
        val offsetY = offsets.getOrElse(1) { 0.0 }
        // `StrumlineNote.width`/`height` stay at the note style's own scale
        // (`strumlineScaleCallback` never calls `updateHitbox`).
        val styleScale = noteStyle.strumlineScale.toDouble()
        return (0 until Constants.KEY_COUNT).map { direction ->
            val targetX = xPos(direction) + Constants.INITIAL_OFFSET + offsetX
            val targetWidth = base.first * styleScale
            val targetHeight = base.second * styleScale
            TouchHint(
                lane = direction,
                x = targetX - targetWidth * 0.175,
                y = offsetY - 220.0,
                width = targetWidth * 1.35,
                height = targetHeight * 8.0,
            )
        }
    }

    /**
     * Lane-local x of a direction, matching `Strumline.getXPos`.
     *
     * Note the lanes are `NOTE_SPACING` apart *regardless of the skin scale* -
     * FNF scales the arrow sprites themselves, not the lane pitch, which is why
     * lanes stay put when you switch to the pixel skin. The touch "Arrows"
     * scheme additionally pushes the outer lanes apart by `pos`, so the four
     * receptors can be tapped with two thumbs.
     */
    fun xPos(direction: Int): Double {
        // FNF: `pos = 35 * (FlxG.width / FlxG.height) / (FlxG.initialWidth / FlxG.initialHeight)`.
        // KNF's virtual stage is a fixed 16:9 canvas, so the ratio is 1.
        val pos = if (arrowsScheme && isPlayer) 35.0 else 0.0
        val pitch = lanePitch
        return when (direction) {
            0 -> -pos * 2
            1 -> -pos * 2 + pitch
            2 -> pos + pitch * 2
            3 -> pos + pitch * 3
            else -> -pos * 2
        }
    }

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
        updateVwoosh(dt)
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

    /**
     * FNF's `Strumline.vwooshInNotes`: force the first visible notes into
     * existence, then slide them in from 200 px away. The countdown starts at
     * the same time, so the arrows are already moving as the pre-roll begins.
     */
    fun vwooshInNotes(conductor: Conductor, songStart: Float = 0f) {
        vwooshElapsed = 0.0
        spawnIncoming(conductor, songStart)
        val start = if (downscroll) -vwooshDistance else vwooshDistance
        for (note in notes) note.vwooshOffsetY = start
    }

    private fun updateVwoosh(dt: Double) {
        if (vwooshElapsed < 0.0) return
        vwooshElapsed += dt
        val t = (vwooshElapsed / vwooshDuration).coerceIn(0.0, 1.0)
        val start = if (downscroll) -vwooshDistance else vwooshDistance
        // FlxEase.expoOut: 1 - 2^(-10t).
        val ease = 1.0 - 2.0.pow(-10.0 * t)
        val offset = start * (1.0 - ease)
        for (note in notes) note.vwooshOffsetY = offset
        if (t >= 1.0) {
            vwooshElapsed = -1.0
            for (note in notes) note.vwooshOffsetY = 0.0
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
            ?: NoteSprite(noteEffects, dir, noteStyle, noteStyle.noteSlice(dir), noteScaleMultiplier)

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
        note.vwooshOffsetY = 0.0
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
                // FNF's updateNotes calls holdConfirm for every active hold,
                // on both strumlines. The player side additionally runs
                // PlayScene.handleHolds for input/health, but the opponent has
                // no other caller.
                if (!isPlayer) holdConfirm(note.directionIndex)
                val remaining = (note.strumTime + note.fullSustainLength) - now
                if (remaining <= 0f) {
                    note.releaseClip(conductor)
                    // FNF only plays the cover's end animation on the player
                    // strumline; opponent covers are hidden as soon as the hold
                    // finishes.
                    if (!isPlayer) {
                        holdCovers[note.directionIndex]?.kill()
                        holdCovers[note.directionIndex] = null
                        // FNF's completed-hold branch: no key is held on the
                        // opponent, so the receptor returns to its static pose.
                        playStatic(note.directionIndex)
                    }
                }
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
     * FNF's `Strumline.hitNote`: the confirm animation. Note splashes are
     * separate and only play on a Sick hit (`HitNoteScriptEvent.doesNotesplash`).
     */
    fun hitNote(direction: Int) {
        receptors[direction]?.confirm()
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
        val cover = HoldCover(noteEffects, direction, asset, noteStyle).also {
            noteEffects.addChild(it)
            it.zIndex = 40.0
        }
        cover.setPosition(xPos(direction), strumlineY.toDouble())
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
     * drifts across the lane. PlayState only calls this for a Sick hit.
     */
    fun playNoteSplash(direction: Int) {
        if (!noteStyle.splashEnabled) return
        val variants = noteStyle.splashVariants(direction)
        if (variants.isEmpty()) return

        val layer = splashLayers[direction] ?: Container().also {
            it.y = 0.0
            noteEffects.addChild(it)
            it.zIndex = 50.0
            splashLayers[direction] = it
        }
        while (layer.numChildren >= Constants.NOTE_SPLASH_CAP) layer.removeChildAt(0)

        val frames = variants.random().toMutableList()
        val first = frames.first()
        val scale = noteStyle.splashScale.toDouble()
        val ox = noteStyle.splashOffsets.getOrElse(0) { 0.0 } * scale
        val oy = noteStyle.splashOffsets.getOrElse(1) { 0.0 } * scale

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
        // `NoteStyle.buildSplashSprite` first calls `updateHitbox()`, which
        // centres the origin on the *logical* frame and leaves `width` at
        // `frameWidth * scale`. Replacing the offset then draws the trimmed
        // artwork at `origin - offset + scale * (trim - origin)`, which reduces
        // to the expression below.
        val fw = (if (first.frameWidth > 0) first.frameWidth else first.slice.width).toDouble()
        val fh = (if (first.frameHeight > 0) first.frameHeight else first.slice.height).toDouble()
        // The hitbox (and therefore the origin) is fixed from the first frame,
        // but the visible art moves inside its logical frame when the exporter
        // trims frames differently. FNF gets that for free from `FlxFrame.offset`;
        // place each frame against the same logical top-left here.
        val baseX = xPos(direction) + Constants.INITIAL_OFFSET + ox +
            fw * (0.5 - 0.8 * scale)
        val baseY = -Constants.INITIAL_OFFSET + oy +
            fh * (0.5 - 0.8 * scale)
        fun place(frame: NoteStyle.TrimmedFrame) {
            img.x = baseX - frame.offsetX * scale
            img.y = baseY - frame.offsetY * scale
        }
        img.scaleX = scale
        img.scaleY = scale
        place(first)
        img.alpha = noteStyle.splashAlpha
        img.setPixelArt(noteStyle.isPixel)
        if (noteStyle.splashBlendMode.equals("screen", ignoreCase = true)) {
            img.blendMode = BlendMode.SCREEN
        }

        val baseFps = noteStyle.splashFrameRate
        val variance = noteStyle.splashFrameRateVariance.coerceAtLeast(0)
        val fps = (baseFps + if (variance > 0) (-variance..variance).random() else 0)
            .coerceAtLeast(1)
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
                    val next = frames.first()
                    img.bitmap = next.slice
                    place(next)
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
        // FNF's `Strumline.clean()` also kills the hold covers and returns the
        // receptors to their static pose.
        holdCovers.forEach { it?.kill() }
        holdCovers.fill(null)
        heldKeys.fill(false)
        receptors.forEach { it?.release() }
        vwooshElapsed = -1.0
        nextNoteIndex = 0
    }

    fun destroy() {
        clean()
        receptors.forEach { it?.destroy() }
        splashLayers.forEach { it?.removeFromParent() }
        receptors.fill(null)
        splashLayers.fill(null)
        noteEffects.removeFromParent()
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
    private val isPlayer: Boolean,
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
     *
     * For the player, FNF starts this timer when the animation finishes; for
     * the opponent it starts when `playConfirm` is called. An active hold
     * cancels it when `holdConfirm` switches to `confirm-hold`.
     */
    private var confirmTimer = -1.0

    /** True while the player's confirm animation still has to finish. */
    private var confirmWaitFinish = false

    /** FNF's `Strumline.fadeInArrow` animation state. */
    private var introActive = false
    private var introTime = 0.0
    private var introDelay = 0.0
    private var introBaseY = 0.0

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

    /**
     * FNF's `strumlineScale` for this arrow. The touch layout rescales the
     * whole lane after construction (`enterMiniMode` / `initNoteHitbox`), so
     * this cannot be a `val`.
     */
    private var laneScale: Double = 1.0

    private val receptorScale: Double
        get() = noteStyle.strumlineScale.toDouble() * laneScale

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
     * Crucially, `strumlineScaleCallback` only changes `scale`; `width` and
     * `offset` stay at the values from setup, i.e. based on the note style's
     * own scale. That is why the mini opponent lane (`enterMiniMode(0.4)` in
     * `PlayState.initNoteHitbox`) draws its arrows inset by ~49 px instead of
     * hugging the corner: the hitbox is still 0.7-scaled while the artwork is
     * drawn at 0.28.
     *
     * `StrumlineNote.fixOffsets` exists in the sources but is never called in
     * 0.8.6, so the confirm glow is *not* nudged.
     */
    private val baseSize: Pair<Int, Int> =
        noteStyle.strumlineBaseSize() ?: (Constants.STRUMLINE_SIZE to Constants.STRUMLINE_SIZE)

    /** `getStrumlineScale()`: the hitbox is fixed at this scale for life. */
    private val styleScale: Double = noteStyle.strumlineScale.toDouble()

    private val centreX: Double get() = baseSize.first * styleScale / 2.0
    private val centreY: Double get() = baseSize.second * styleScale / 2.0

    /** Rescales this arrow to the lane's `strumlineScale` and redraws it. */
    fun setLaneScale(scale: Float) {
        if (laneScale == scale.toDouble()) return
        laneScale = scale.toDouble()
        redraw()
    }

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

    /**
     * FNF's `Strumline.fadeInArrow`: the receptor starts 10 px above its final
     * position and invisible, then fades/slides in over one second. Arrows are
     * staggered by 0.2 s after an initial 0.5 s delay.
     */
    fun startIntro(index: Int) {
        introActive = true
        introTime = 0.0
        introDelay = 0.5 + 0.2 * index
        introBaseY = container.y
        container.alpha = 0.0
        container.y = introBaseY - 10.0
    }

    private fun updateIntro(dt: Double) {
        if (!introActive) return
        introTime += dt
        val t = ((introTime - introDelay) / 1.0).coerceIn(0.0, 1.0)
        // FlxEase.circOut: sqrt(1 - (t - 1)^2).
        val eased = sqrt(1.0 - (t - 1.0) * (t - 1.0))
        container.alpha = eased
        container.y = introBaseY - 10.0 + 10.0 * eased
        if (t >= 1.0) {
            introActive = false
            container.alpha = 1.0
            container.y = introBaseY
        }
    }

    fun press() {
        setState("Press")
    }

    fun confirm() {
        setState("Confirm")
        // FNF's `playConfirm`: on the opponent the timer starts immediately, on
        // the player it starts when the confirm animation finishes
        // (`onAnimationFinished`). Starting it immediately on the player makes
        // a held note pulse twice: the timer expires while the hold is active,
        // then `holdConfirm` restarts the confirm animation.
        confirmWaitFinish = isPlayer && !finished
        confirmTimer = if (isPlayer) -1.0 else 0.0
    }

    /** True while the receptor shows its idle pose. */
    val isStatic: Boolean get() = state == "Static"

    /**
     * FNF's `StrumlineNote.holdConfirm`: the receptor keeps the confirm pose
     * for as long as the hold note is being held.
     */
    fun holdConfirm() {
        if (state == "ConfirmHold") return
        if (state == "Confirm") {
            if (finished) {
                // Cancels the player's post-confirm timer so it does not drop
                // back to static in the middle of a hold.
                confirmWaitFinish = false
                confirmTimer = -1.0
                setState("ConfirmHold")
            }
        } else {
            setState("Confirm")
            // FNF's `holdConfirm` calls `playAnimation('confirm')` without
            // setting the timer; the player's finish callback arms it.
            if (isPlayer) {
                confirmWaitFinish = !finished
                confirmTimer = -1.0
            }
        }
    }

    fun release() {
        confirmWaitFinish = false
        confirmTimer = -1.0
        setState("Static")
    }

    fun update(dt: Double, keyHeld: Boolean = false) {
        updateIntro(dt)
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
        if (confirmWaitFinish && finished) {
            confirmWaitFinish = false
            confirmTimer = 0.0
        }
        if (confirmTimer >= 0.0) {
            confirmTimer += dt
            // FNF's `CONFIRM_HOLD_TIME`: after the confirm glow, the receptor
            // goes back to the static pose. The strumline's update loop then
            // puts it back on the pressed one if the key is still down.
            if (confirmTimer >= CONFIRM_HOLD_TIME) {
                confirmTimer = -1.0
                confirmWaitFinish = false
                setState("Static")
            }
        }
    }

    fun destroy() = container.removeFromParent()
}
