package nightfunkin.ui

import korlibs.image.color.Colors
import korlibs.image.color.RGBA
import korlibs.image.font.TtfFont
import korlibs.image.paint.Stroke
import korlibs.io.resources.Resourceable
import korlibs.korge.view.Container
import korlibs.korge.view.Image
import korlibs.korge.view.Text
import korlibs.korge.view.text
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

    private val songText: Text = text(
        if (entry == null) "Random" else entry.song.name,
        textSize = 32.0,
        color = Colors.WHITE,
    ).also {
        fiveBySeven?.let { font -> it.font = Resourceable.Fixed(font) }
        // CapsuleText gives the white text a cyan GlowFilter. KorGE's text
        // stroke is the closest equivalent (and, unlike a second text view,
        // it never changes the glyph metrics).
        it.stroke = Stroke(CAPSULE_GLOW, 2.0)
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
                it.x = 420.0
                it.y = 41.0
                it.scaleX = 0.9
                it.scaleY = 0.9
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

    var targetX = 0.0
        private set
    var targetY = 0.0
        private set

    private var introClock = 0.0
    private var introFinished = true

    init {
        // Capsules that are filtered out of the first list keep their normal
        // alpha. If a later difficulty/variation reveals one it slides in from
        // the right instead of staying permanently transparent.
        alpha = 1.0
        updateSelected()
    }

    /** Positions this capsule relative to the currently selected one. */
    fun setTarget(x: Double, y: Double) {
        targetX = x
        targetY = y
    }

    /**
     * Starts the right-to-left jump-in. FNF stages capsules by a frame timer;
     * a small stagger gives the same cascading look without a timer heap.
     */
    fun startIntro(delay: Double) {
        introClock = -delay
        introFinished = false
        alpha = 0.0
    }

    fun setSelected(value: Boolean) {
        if (currentlySelected == value) {
            return
        }
        currentlySelected = value
        updateSelected()
    }

    /** Re-reads the song metadata after a difficulty/variation switch. */
    fun refresh(difficulty: String, variation: String) {
        val song = entry?.song
        if (song == null) {
            songText.text = "Random"
            setBpmVisible(false)
            setRatingVisible(false)
            weekText.visible = false
            rankSprite?.visible = false
            appliedRankKey = null
            setPixelIcon(null)
            return
        }

        val metadata = song.metadataFor(variation)
        songText.text = metadata.songName
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
        updateSelected()
    }

    /** Advances the capsule animation and its easing towards [targetX]/[targetY]. */
    fun update(dt: Double) {
        capsuleSprite?.update(dt)
        rankSprite?.update(dt)
        pixelIconSprite?.update(dt)
        // Text bounds are only known after the first render.
        layoutWeekText()

        if (!introFinished) {
            introClock += dt
            if (introClock < 0.0) {
                return
            }
            alpha = (introClock / 0.18).coerceIn(0.0, 1.0)
        }

        val follow = 1.0 - 0.18.pow((dt * 60.0).coerceAtLeast(0.0))
        x += (targetX - x) * follow
        y += (targetY - y) * follow

        if (!introFinished && introClock >= 0.7) {
            introFinished = true
            x = targetX
            y = targetY
            alpha = 1.0
        } else if (introFinished && abs(targetX - x) < 0.5 && abs(targetY - y) < 0.5) {
            x = targetX
            y = targetY
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
        capsuleSprite?.x = CAPSULE_OFFSET_X + if (currentlySelected) 0.0 else 5.0
        songText.alpha = if (currentlySelected) 1.0 else 0.6
        weekText.alpha = if (currentlySelected) 1.0 else 0.8
        bpmLabel?.alpha = if (currentlySelected) 1.0 else 0.8
        difficultyLabel?.alpha = if (currentlySelected) 1.0 else 0.8
        rankSprite?.alpha = if (currentlySelected) 1.0 else 0.7
    }

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

        const val PIXEL_ICON_DIR = "preload/images/freeplay/icons"

        /** bf.json `capsuleTextColors` / `CapsuleText`'s glow filter. */
        val CAPSULE_GLOW = RGBA(0x00, 0xCC, 0xFF)

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
