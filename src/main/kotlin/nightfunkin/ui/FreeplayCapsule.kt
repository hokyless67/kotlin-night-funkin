package nightfunkin.ui

import korlibs.image.bitmap.effect.blur
import korlibs.image.color.Colors
import korlibs.image.color.RGBA
import korlibs.image.font.TtfFont
import korlibs.image.font.renderTextToBitmap
import korlibs.io.resources.Resourceable
import korlibs.korge.blend.BlendMode
import korlibs.korge.view.Container
import korlibs.korge.view.Image
import korlibs.korge.view.Text
import korlibs.korge.view.text
import nightfunkin.core.Constants
import nightfunkin.core.KnfFiles
import nightfunkin.core.Save
import nightfunkin.core.SparrowSprite
import nightfunkin.core.TextureAtlas
import kotlin.math.abs
import kotlin.math.pow

/**
 * One Freeplay song capsule, ported from `SongMenuItem.hx`.
 *
 * The capsule itself is the 612x132 Sparrow animation; its labels, BPM digits
 * and difficulty rating sit on top at the same local coordinates as FNF. The
 * container is moved by [SongSelectScene] with the same `intendedX` /
 * `intendedY` maths, so the list arcs out of the bottom-left corner.
 */
internal class FreeplayCapsule(
    val entry: FreeplayEntry?,
    private val fiveBySeven: TtfFont?,
    private val youreGone: TtfFont?,
) : Container() {

    private val atlas = capsuleAtlas
    private val selectedFrames = atlas?.framesByPrefix("mp3 capsule w backing0").orEmpty()
    private val unselectedFrames =
        atlas?.framesByPrefix("mp3 capsule w backing NOT SELECTED").orEmpty()

    private val capsuleSprite: SparrowSprite? =
        if (atlas != null && selectedFrames.isNotEmpty()) {
            SparrowSprite(atlas, selectedFrames, fps = 24.0, loop = true).also {
                // FlxSprite scales/rotates around its frame origin, which is
                // centred by loadGraphic(). Compensate for the 0.8 scale here
                // so the capsule visual lines up with the labels at the
                // logical 612x132 frame coordinates.
                it.x = CAPSULE_OFFSET_X
                it.y = CAPSULE_OFFSET_Y
                it.scaleX = REAL_SCALED
                it.scaleY = REAL_SCALED
                addChild(it)
            }
        } else {
            null
        }

    /**
     * `CapsuleText.blurredText`: a cyan copy rendered once and Gaussian
     * blurred. Doing this offline keeps the selected capsule's glow without a
     * per-frame filter (the source uses a `GaussianBlurShader` here).
     */
    private var songGlow: Image? = null

    private val songText: Text = text(
        if (entry == null) "Random" else entry.song.name,
        textSize = 32.0,
        color = Colors.WHITE,
    ).also {
        fiveBySeven?.let { font -> it.font = Resourceable.Fixed(font) }
        // Only `blurredText` is cyan; the white copy stays thin. The glow is
        // shown for the selected capsule alone (`updateSelected`), matching
        // the reference where unselected titles are plain grey.
        it.x = 159.12
        it.y = 45.0
    }

    private val bpmLabel: Image? = loadLabel("freeplay/freeplayCapsule/bpmtext", 144.0, 87.0)
    private val difficultyLabel: Image? =
        loadLabel("freeplay/freeplayCapsule/difficultytext", 414.0, 87.0)

    private val weekText: Text = text(
        "",
        textSize = 20.0,
        color = RGBA(0x21, 0x24, 0x2E),
    ).also {
        youreGone?.let { font -> it.font = Resourceable.Fixed(font) }
        // FunkinSprite.setGraphicSize/scale scales around the centre. The
        // position is adjusted in layoutWeekText() once the text size is known.
        it.scaleX = 0.9
        it.scaleY = 0.9
        it.x = WEEK_TEXT_X
        it.y = WEEK_TEXT_Y
        it.visible = false
    }

    private val bpmDigits: List<DigitView> = digitRow(
        smallNumbersAtlas,
        smallNumberFrames,
        185.0,
        88.5,
        count = 3,
        step = 11.0,
    )

    private val ratingDigits: List<DigitView> = digitRow(
        bigNumbersAtlas,
        bigNumberFrames,
        466.0,
        32.0,
        count = 2,
        step = 30.0,
    )

    private val rankFrames: Map<String, List<TextureAtlas.AtlasFrame>> =
        rankAtlas?.let { rankAtlas ->
            mapOf(
                "perfectGold" to rankAtlas.framesByPrefix("PERFECT rank GOLD"),
                "perfect" to rankAtlas.framesByPrefix("PERFECT rank0"),
                "excellent" to rankAtlas.framesByPrefix("EXCELLENT rank0"),
                "great" to rankAtlas.framesByPrefix("GREAT rank0"),
                "good" to rankAtlas.framesByPrefix("GOOD rank0"),
                "loss" to rankAtlas.framesByPrefix("LOSS rank0"),
            ).filterValues { it.isNotEmpty() }
        }.orEmpty()

    private val rankSprite: SparrowSprite? =
        rankFrames.values.firstOrNull()?.let { first ->
            SparrowSprite(rankAtlas!!, first, fps = 24.0, loop = false).also {
                it.setPixelArt(true)
                it.x = 420.0
                it.y = 41.0
                it.scaleX = 0.9
                it.scaleY = 0.9
                // `FreeplayRank` uses additive blending so the badge glows.
                it.blendMode = BlendMode.ADD
                it.visible = false
                addChild(it)
            }
        }

    private var currentlySelected = false
    private var appliedSelected: Boolean? = null
    private var appliedRankKey: String? = null

    private var pixelIcon: Container? = null
    private var pixelIconSprite: SparrowSprite? = null
    private var pixelIconChar: String? = null

    /** `favHeart` frames; the pop animation parks on the full heart. */
    private val favFrames: List<TextureAtlas.AtlasFrame> =
        favHeartAtlas?.framesByPrefix("favorite heart").orEmpty().sortedBy { it.name }

    /** `favIconBlurred` is created first, so the plain heart draws above it. */
    private val favIconBlurred: SparrowSprite? = makeFavHeart()
    private val favIcon: SparrowSprite? = makeFavHeart()

    // `favoriteSong()`: the capsule wiggles 5 px (expoOut/expoIn, 0.1 s each)
    // with `doLerp` paused, up for a new favourite and down for a removed one.
    private var bounceActive = false
    private var bounceElapsed = 0.0
    private var bounceStartY = 0.0
    private var bounceDy = 0.0

    /** Unfavouriting hides the shrinking hearts after a 0.2 s timer. */
    private var favHideCountdown = 0.0

    var targetX = 0.0
        private set
    var targetY = 0.0
        private set

    // `SongMenuItem`'s doJumpIn state: one squash frame per 1/24 s through
    // `xFrames`, running while the container lerps in from the right edge.
    private var jumpInActive = false
    private var jumpInFrame = 0
    private var jumpInTicker = 0.0

    init {
        setTitle(if (entry == null) "Random" else entry.song.name)
        // Capsules that are filtered out of the first list keep their normal
        // alpha. If a later difficulty/variation reveals one it slides in from
        // the right instead of staying permanently transparent.
        alpha = 1.0
        updateSelected()
    }

    /** `SongMenuItem`'s two ADD-blended `favHeart` sprites at 50x50. */
    private fun makeFavHeart(): SparrowSprite? {
        val atlas = favHeartAtlas ?: return null
        val first = favFrames.firstOrNull() ?: return null
        return SparrowSprite(atlas, favFrames, fps = 24.0, loop = false).also {
            it.setPixelArt(true)
            it.scaleX = FAV_ICON_SIZE / first.logicalWidth
            it.scaleY = FAV_ICON_SIZE / first.logicalHeight
            it.blendMode = BlendMode.ADD
            it.visible = false
            addChild(it)
        }
    }

    private fun setTitle(value: String) {
        songText.text = value
        songGlow?.removeFromParent()
        songGlow = null
        val font = fiveBySeven ?: return
        val result = runCatching {
            // Matches the `Text` view's layout: the bitmap's top-left is the
            // text bounds' top-left, with the usual 1 px raster border.
            font.renderTextToBitmap(32.0, value, paint = CAPSULE_GLOW)
        }.getOrNull() ?: return
        val blurred = runCatching {
            result.bmp.toBMP32IfRequired().blur(GLOW_BLUR)
        }.getOrNull() ?: return
        songGlow = Image(blurred).also {
            it.smoothing = true
            // `blur` grows the bitmap by GLOW_BLUR on every side.
            it.x = 159.12 - GLOW_BLUR
            it.y = 45.0 - GLOW_BLUR
            it.visible = currentlySelected
            addChildAt(it, songText.index)
        }
    }

    /** Positions this capsule relative to the currently selected one. */
    fun setTarget(x: Double, y: Double) {
        targetX = x
        targetY = y
    }

    /**
     * `initJumpIn(0, force = true)`: every rebuild replays the same jump-in —
     * no stagger, alpha 1 immediately, the capsule flying in from the right
     * edge (`initPosition(FlxG.width, 0)`) from the flattened
     * `intendedY(index + 1) + 10` start row while its graphic squashes
     * through `xFrames`.
     */
    fun startIntro(startY: Double) {
        jumpInActive = true
        jumpInFrame = 0
        jumpInTicker = 0.0
        x = Constants.GAME_WIDTH.toDouble()
        y = startY
        alpha = 1.0
        bounceActive = false
    }

    fun setSelected(value: Boolean) {
        if (currentlySelected == value) {
            return
        }
        currentlySelected = value
        updateSelected()
    }

    /**
     * `FreeplayState.favoriteSong`: plays the heart pop for a new favourite or
     * the reversed shrink (from frame 9) for a removed one, and wiggles the
     * capsule 5 px in the matching direction.
     */
    fun setFavorite(isFav: Boolean) {
        val icon = favIcon ?: return
        favHideCountdown = 0.0
        if (isFav) {
            icon.visible = true
            icon.play(favFrames, restart = true)
            favIconBlurred?.also {
                it.visible = true
                it.play(favFrames, restart = true)
            }
            startBounce(-5.0)
        } else {
            // `play('fav', true, true, 9)`: shrink back out from the mid frame.
            icon.play(favFrames, restart = true, reversed = true)
            icon.seekTo(9)
            favIconBlurred?.also {
                it.play(favFrames, restart = true, reversed = true)
                it.seekTo(9)
            }
            favHideCountdown = 0.2
            startBounce(5.0)
        }
        // updateSelected only runs on a selection change; refresh these now.
        icon.alpha = if (currentlySelected) 1.0 else 0.6
        favIconBlurred?.alpha = if (currentlySelected) 1.0 else 0.0
    }

    private fun startBounce(dy: Double) {
        bounceActive = true
        bounceElapsed = 0.0
        bounceStartY = y
        bounceDy = dy
    }

    private fun setFavHeartsVisible(value: Boolean) {
        favIcon?.visible = value
        favIconBlurred?.visible = value
    }

    /**
     * `initData`'s `favIcon.visible = freeplayData.isFav` plus `checkClip`'s
     * heart placement: left of the rank badge when one is showing, otherwise
     * at its resting spot.
     */
    private fun updateFavHearts(variation: String) {
        val song = entry?.song ?: return
        favHideCountdown = 0.0
        val isFav = Save.isSongFavorited("${song.id}:$variation")
        val x = if (rankSprite?.visible == true) FAV_ICON_X_WITH_RANK else FAV_ICON_X
        favIcon?.also {
            it.x = x
            it.y = FAV_ICON_Y
            it.visible = isFav
        }
        favIconBlurred?.also {
            it.x = x
            it.y = FAV_ICON_Y
            it.visible = isFav
        }
    }

    private fun expoOut(t: Double): Double = if (t >= 1.0) 1.0 else 1.0 - 2.0.pow(-10.0 * t)

    private fun expoIn(t: Double): Double = if (t <= 0.0) 0.0 else 2.0.pow(10.0 * (t - 1.0))

    /** Re-reads the song metadata after a difficulty/variation switch. */
    fun refresh(difficulty: String, variation: String) {
        val song = entry?.song
        if (song == null) {
            setTitle("Random")
            setBpmVisible(false)
            setRatingVisible(false)
            weekText.visible = false
            rankSprite?.visible = false
            appliedRankKey = null
            setPixelIcon(null)
            setFavHeartsVisible(false)
            return
        }

        val metadata = song.metadataFor(variation)
        setTitle(metadata.songName)
        setPixelIcon(metadata.playData.characters.opponent)

        val bpm = metadata.timeChanges.firstOrNull()?.bpm?.toInt() ?: 100
        setBpm(bpm)

        val rating = metadata.playData.ratings[difficulty] ?: 0
        setRating(rating)

        val week = entry?.weekLabel
        weekText.text = week.orEmpty()
        weekText.visible = !week.isNullOrBlank()
        layoutWeekText()

        updateRank(difficulty, variation)
        updateFavHearts(variation)
        updateSelected()
    }

    /** Advances the capsule animation and its easing towards [targetX]/[targetY]. */
    fun update(dt: Double) {
        capsuleSprite?.update(dt)
        rankSprite?.update(dt)
        pixelIconSprite?.update(dt)
        favIcon?.update(dt)
        favIconBlurred?.update(dt)
        // Text bounds are only known after the first render.
        layoutWeekText()

        // `favoriteSong()`'s 0.2 s timer hides the shrinking hearts.
        if (favHideCountdown > 0.0) {
            favHideCountdown -= dt
            if (favHideCountdown <= 0.0) {
                favHideCountdown = 0.0
                setFavHeartsVisible(false)
            }
        }

        // `SongMenuItem.update`'s doJumpIn block: the capsule graphic squashes
        // through `xFrames` while the container keeps lerping into place.
        if (jumpInActive) {
            jumpInTicker += dt
            while (jumpInTicker >= 1.0 / 24.0 && jumpInFrame < X_FRAMES.size) {
                jumpInTicker -= 1.0 / 24.0
                applySquash(jumpInFrame)
                jumpInFrame++
            }
            if (jumpInFrame >= X_FRAMES.size) {
                jumpInActive = false
            }
        }

        if (bounceActive) {
            // `favoriteSong` tweens y with doLerp paused: 5 px out with
            // expoOut, then back past the rest point with expoIn before the
            // normal lerp takes over again.
            bounceElapsed += dt
            val t = bounceElapsed / BOUNCE_HALF
            y = if (t < 1.0) {
                bounceStartY + bounceDy * expoOut(t)
            } else {
                bounceStartY + bounceDy - 2.0 * bounceDy * expoIn((t - 1.0).coerceIn(0.0, 1.0))
            }
            x = smoothLerpPrecision(x, targetX, dt, 0.256)
            if (t >= 2.0) {
                bounceActive = false
            }
        } else {
            // `SongMenuItem.update`: x and y use different smoothing durations.
            // `smoothLerpPrecision` keeps only 1% of the remaining distance after
            // the duration, which makes held-key scrolling glide instead of
            // snapping one capsule at a time.
            x = smoothLerpPrecision(x, targetX, dt, 0.256)
            y = smoothLerpPrecision(y, targetY, dt, 0.192)
            if (abs(targetX - x) < 0.5 && abs(targetY - y) < 0.5) {
                x = targetX
                y = targetY
            }
        }
    }

    private fun updateSelected() {
        if (appliedSelected == currentlySelected) return
        appliedSelected = currentlySelected

        val frames = if (currentlySelected) selectedFrames else unselectedFrames
        if (frames.isNotEmpty()) {
            capsuleSprite?.play(frames, restart = true)
        }
        // FlxSprite updates `offset.x = -5`, not `x`, when the capsule
        // becomes unselected. A negative offset draws the graphic 5 px to the
        // right, so add it on top of the centred-scale compensation.
        if (!jumpInActive) {
            capsuleSprite?.x = capsuleSpriteBaseX(REAL_SCALED)
        }
        songText.alpha = if (currentlySelected) 1.0 else 0.6
        songGlow?.visible = currentlySelected
        rankSprite?.alpha = if (currentlySelected) 1.0 else 0.7
        rankSprite?.tint = if (currentlySelected) Colors.WHITE else RGBA(0xAA, 0xAA, 0xAA)
        favIcon?.alpha = if (currentlySelected) 1.0 else 0.6
        favIconBlurred?.alpha = if (currentlySelected) 1.0 else 0.0
    }

    /**
     * `SongMenuItem.update`'s doJumpIn squash: `capsule.scale.x = xFrames[i] *
     * realScaled`, `scale.y = 1 / xFrames[i] * realScaled`, scaled around the
     * frame centre like FlxSprite's centred origin.
     */
    private fun applySquash(frame: Int) {
        val sprite = capsuleSprite ?: return
        val factor = X_FRAMES[frame.coerceIn(0, X_FRAMES.lastIndex)]
        val sx = factor * REAL_SCALED
        val sy = (1.0 / factor) * REAL_SCALED
        sprite.scaleX = sx
        sprite.scaleY = sy
        sprite.x = capsuleSpriteBaseX(sx)
        sprite.y = CAPSULE_FRAME_HEIGHT * (1.0 - sy) / 2.0
    }

    /**
     * Top-left x that keeps the scaled capsule graphic centred on the frame's
     * centre, plus the unselected 5 px `offset.x = -5` shift.
     */
    private fun capsuleSpriteBaseX(scaleX: Double): Double =
        CAPSULE_FRAME_WIDTH * (1.0 - scaleX) / 2.0 + if (currentlySelected) 0.0 else 5.0

    private fun setBpm(value: Int) {
        if (bpmLabel == null && bpmDigits.isEmpty()) return
        setBpmVisible(true)

        var shiftX = 191.0
        if (value / 100 == 1) shiftX = 186.0
        var tempShift = 0.0

        val digits = intArrayOf(
            if (value < 100) 0 else (value / 100) % 10,
            if (value < 10) 0 else (value / 10) % 10,
            value % 10,
        )
        if (digits[1] == 1) tempShift -= 4.0
        if (digits[2] == 1) tempShift -= 4.0

        for (i in bpmDigits.indices) {
            bpmDigits[i].set(digits[i], shiftX + i * 11.0 + tempShift)
        }
    }

    private fun setRating(value: Int) {
        if (difficultyLabel == null && ratingDigits.isEmpty()) return
        setRatingVisible(true)

        val digits = intArrayOf(
            if (value < 10) 0 else value / 10,
            value % 10,
        )
        for (i in ratingDigits.indices) {
            ratingDigits[i].set(digits[i], 466.0 + i * 30.0)
        }
    }

    private fun setBpmVisible(value: Boolean) {
        bpmLabel?.visible = value
        bpmDigits.forEach { it.image.visible = value }
    }

    private fun setRatingVisible(value: Boolean) {
        difficultyLabel?.visible = value
        ratingDigits.forEach { it.image.visible = value }
    }

    /**
     * `SongMenuItem`'s `PixelatedIcon`: a 50x50 (or 100x42) Sparrow frame,
     * drawn at scale 2 with custom horizontal origins. The icon is not part of
     * the 0.8-scaled capsule; it is a sibling child and keeps its fixed place
     * while different capsule animation frames shift the backing graphic.
     */
    private fun setPixelIcon(character: String?) {
        val old = pixelIcon
        if (character == null) {
            old?.visible = false
            return
        }
        if (pixelIconChar == character && old != null) {
            old.visible = true
            return
        }

        val base = resolvePixelIconName(character)
        if (base == null) {
            old?.visible = false
            pixelIconChar = character
            return
        }

        if (old != null) {
            removeChild(old)
        }
        pixelIcon = null
        pixelIconSprite = null
        pixelIconChar = character

        val xmlPath = "$PIXEL_ICON_DIR/${base}pixel.xml"
        val pngPath = "$PIXEL_ICON_DIR/${base}pixel.png"
        val originX = when (character) {
            "parents-christmas" -> 140.0
            "sserafim-kazuha" -> 195.0
            else -> 100.0
        }

        val wrapper = Container().also { addChild(it) }
        val atlas = if (KnfFiles.exists(xmlPath)) TextureAtlas.tryLoad(xmlPath) else null
        val idleFrames = atlas?.framesByPrefix("idle0").orEmpty()
        if (atlas != null && idleFrames.isNotEmpty()) {
            val frame = idleFrames.first()
            val sprite = SparrowSprite(atlas, idleFrames, fps = 10.0, loop = true).also {
                it.setPixelArt(true)
                it.x = 0.0
                it.y = 0.0
                wrapper.addChild(it)
            }
            pixelIconSprite = sprite
            wrapper.x = 160.0 - originX
            wrapper.y = 35.0 - frame.logicalHeight / 2.0
        } else {
            val bitmap = KnfFiles.bitmapOrNull(pngPath)
            if (bitmap == null) {
                removeChild(wrapper)
                return
            }
            Image(bitmap).also {
                it.smoothing = false
                it.x = 0.0
                it.y = 0.0
                wrapper.addChild(it)
            }
            wrapper.x = 160.0 - originX
            wrapper.y = 35.0 - bitmap.height / 2.0
        }
        wrapper.scaleX = 2.0
        wrapper.scaleY = 2.0
        pixelIcon = wrapper
    }

    /** `PixelatedIcon.setCharacter`: progressively strip hyphenated suffixes. */
    private fun resolvePixelIconName(character: String): String? {
        val parts = character.split('-')
        var name = ""
        var lastValid: String? = null
        for ((index, part) in parts.withIndex()) {
            name += part
            if (KnfFiles.exists("$PIXEL_ICON_DIR/${name}pixel.png")) {
                lastValid = name
            }
            if (index < parts.lastIndex) name += '-'
        }
        return lastValid
    }

    private fun updateRank(difficulty: String, variation: String) {
        val sprite = rankSprite ?: return
        val song = entry?.song ?: return
        val key = "${song.id}:$difficulty:$variation"
        val rank = Save.songRating(key)
        val frames = rank?.let { rankFrames[it] }
        if (rank == null || frames.isNullOrEmpty()) {
            sprite.visible = false
            appliedRankKey = null
            return
        }

        sprite.visible = true
        if (appliedRankKey != rank) {
            appliedRankKey = rank
            sprite.play(frames, restart = true)
        }
    }

    private fun loadLabel(path: String, x: Double, y: Double): Image? {
        val bitmap = KnfFiles.bitmapOrNull("preload/images/$path.png") ?: return null
        // FlxSprite.setGraphicSize(Std.int(width * 0.9)) with the default
        // centre origin leaves a (1 - scale) / 2 inset on the left and top.
        val scale = (bitmap.width * 0.9).toInt().toDouble() / bitmap.width
        return Image(bitmap).also {
            it.smoothing = false
            it.x = x + bitmap.width * (1.0 - scale) / 2.0
            it.y = y + bitmap.height * (1.0 - scale) / 2.0
            it.scaleX = scale
            it.scaleY = scale
            addChild(it)
        }
    }

    /**
     * `FunkinSprite(291, 88)` + `scale.set(0.9, 0.9)` + the level's capsule
     * title offsets. Flixel scales around the frame centre.
     */
    private fun layoutWeekText() {
        if (!weekText.visible) return
        val offsets = entry?.weekLabelOffsets.orEmpty()
        val ox = offsets.getOrElse(0) { 0.0 }
        val oy = offsets.getOrElse(1) { 0.0 }
        weekText.x = WEEK_TEXT_X - ox + weekText.width * (1.0 - 0.9) / 2.0
        weekText.y = WEEK_TEXT_Y - oy + weekText.height * (1.0 - 0.9) / 2.0
    }

    private fun digitRow(
        atlas: TextureAtlas?,
        frames: Map<Int, TextureAtlas.AtlasFrame>,
        x: Double,
        y: Double,
        count: Int,
        step: Double,
    ): List<DigitView> {
        if (atlas == null) return emptyList()
        val valid = (0..9).mapNotNull { frames[it] }
        if (valid.isEmpty()) return emptyList()

        val zero = frames[0] ?: valid.first()
        val scale = (zero.logicalWidth * 0.9).toInt().toDouble() / zero.logicalWidth
        return (0 until count).map { index ->
            val image = Image(atlas.slice(zero)).also {
                it.smoothing = false
                it.x = x + index * step
                it.y = y
                it.scaleX = scale
                it.scaleY = scale
                addChild(it)
            }
            DigitView(image, atlas, (0..9).associateWith { frames[it] })
        }
    }

    private class DigitView(
        val image: Image,
        private val atlas: TextureAtlas,
        private val frames: Map<Int, TextureAtlas.AtlasFrame?>,
    ) {
        fun set(value: Int, left: Double) {
            val frame = frames[value] ?: return
            image.bitmap = atlas.slice(frame)
            // CapsuleNumber.set_digit: offset.x -= 4 for 1 and -= 1 for 3.
            image.x = left + when (value) {
                1 -> 4.0
                3 -> 1.0
                else -> 0.0
            }
        }
    }

    private companion object {
        const val REAL_SCALED = 0.8

        /** Logical frame size of every capsule frame (`freeplayCapsule.xml`). */
        const val CAPSULE_FRAME_WIDTH = 612.0
        const val CAPSULE_FRAME_HEIGHT = 132.0

        /** `origin = frame centre` shifted by the 0.8 scale. */
        const val CAPSULE_OFFSET_X =
            (CAPSULE_FRAME_WIDTH - CAPSULE_FRAME_WIDTH * REAL_SCALED) / 2.0
        const val CAPSULE_OFFSET_Y =
            (CAPSULE_FRAME_HEIGHT - CAPSULE_FRAME_HEIGHT * REAL_SCALED) / 2.0

        const val WEEK_TEXT_X = 291.0
        const val WEEK_TEXT_Y = 88.0

        // `favIcon`/`favIconBlurred`: `setGraphicSize(50, 50)` at (380, 40),
        // then `checkClip` moves them to 405, or 370 when a rank badge shows.
        const val FAV_ICON_SIZE = 50.0
        const val FAV_ICON_X = 405.0
        const val FAV_ICON_X_WITH_RANK = 370.0
        const val FAV_ICON_Y = 40.0

        /** `FlxTween` duration of each half of the favourite wiggle. */
        const val BOUNCE_HALF = 0.1

        /** `SongMenuItem.xFrames`: the jump-in squash keyframes, 1/24 s apart. */
        val X_FRAMES = doubleArrayOf(1.7, 1.8, 0.85, 0.85, 0.97, 0.97, 1.0)

        const val PIXEL_ICON_DIR = "preload/images/freeplay/icons"

        /** bf.json `capsuleTextColors` / `CapsuleText`'s glow filter. */
        val CAPSULE_GLOW = RGBA(0x00, 0xCC, 0xFF)

        /** `GaussianBlurShader(1)` + 5x5 GlowFilter: a small cyan halo. */
        const val GLOW_BLUR = 3

        val capsuleAtlas: TextureAtlas? by lazy {
            TextureAtlas.tryLoad(
                "preload/images/freeplay/freeplayCapsule/capsule/freeplayCapsule.xml",
            )
        }

        val smallNumbersAtlas: TextureAtlas? by lazy {
            TextureAtlas.tryLoad(
                "preload/images/freeplay/freeplayCapsule/smallnumbers.xml",
            )
        }

        val bigNumbersAtlas: TextureAtlas? by lazy {
            TextureAtlas.tryLoad(
                "preload/images/freeplay/freeplayCapsule/bignumbers.xml",
            )
        }

        val rankAtlas: TextureAtlas? by lazy {
            TextureAtlas.tryLoad("preload/images/freeplay/rankbadges.xml")
        }

        val favHeartAtlas: TextureAtlas? by lazy {
            TextureAtlas.tryLoad("preload/images/freeplay/favHeart.xml")
        }

        val smallNumberFrames: Map<Int, TextureAtlas.AtlasFrame> by lazy {
            digitFrames(smallNumbersAtlas)
        }

        val bigNumberFrames: Map<Int, TextureAtlas.AtlasFrame> by lazy {
            digitFrames(bigNumbersAtlas)
        }

        private val DIGIT_NAMES = listOf(
            "ZERO", "ONE", "TWO", "THREE", "FOUR",
            "FIVE", "SIX", "SEVEN", "EIGHT", "NINE",
        )

        fun digitFrames(atlas: TextureAtlas?): Map<Int, TextureAtlas.AtlasFrame> {
            if (atlas == null) return emptyMap()
            return DIGIT_NAMES.mapIndexedNotNull { index, name ->
                atlas.framesByPrefix(name).firstOrNull()?.let { index to it }
            }.toMap()
        }
    }
}

/**
 * Port of `MathUtil.smoothLerpPrecision` from `funkin.util.MathUtil`.
 * Framerate-independent exponential decay: after [duration] seconds only
 * [precision] of the original distance remains.
 */
private fun smoothLerpPrecision(
    base: Double,
    target: Double,
    deltaTime: Double,
    duration: Double,
    precision: Double = 0.01,
): Double {
    if (deltaTime == 0.0) return base
    if (base == target) return target
    val factor = precision.pow(deltaTime / duration)
    return target + factor * (base - target)
}
