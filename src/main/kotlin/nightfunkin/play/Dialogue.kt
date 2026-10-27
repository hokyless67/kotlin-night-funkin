package nightfunkin.play

import korlibs.image.color.Colors
import korlibs.image.color.RGBA
import korlibs.image.font.TtfFont
import korlibs.image.font.getTextBounds
import korlibs.io.resources.Resourceable
import korlibs.korge.view.Container
import korlibs.korge.view.SolidRect
import korlibs.korge.view.Text
import korlibs.korge.view.solidRect
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import nightfunkin.audio.MusicPlayer
import nightfunkin.core.Constants
import nightfunkin.core.KnfFiles
import nightfunkin.core.SparrowSprite
import nightfunkin.core.TextureAtlas
import nightfunkin.core.setPixelArt
import kotlin.math.floor
import kotlin.math.min

// ===========================================================================
// Data
// ===========================================================================

@Serializable
private data class ConversationFile(
    val version: String = "",
    val backdrop: BackdropData? = null,
    val outro: OutroData? = null,
    val music: MusicData? = null,
    val dialogue: List<DialogueEntryData> = emptyList(),
)

@Serializable
private data class BackdropData(
    val type: String = "",
    val color: String = "#000000",
    val fadeTime: Double = 0.0,
)

@Serializable
private data class OutroData(
    val type: String = "",
    val fadeTime: Double = 1.0,
)

@Serializable
private data class MusicData(
    val asset: String = "",
    val fadeTime: Double = 0.0,
    val looped: Boolean = false,
)

@Serializable
private data class DialogueEntryData(
    val speaker: String = "",
    val speakerAnimation: String = "",
    val box: String = "",
    val boxAnimation: String = "",
    val text: List<String> = emptyList(),
    val speed: Double = 1.0,
)

@Serializable
private data class DialogueBoxData(
    val version: String = "",
    val name: String = "",
    val assetPath: String = "",
    val flipX: Boolean = false,
    val flipY: Boolean = false,
    val isPixel: Boolean = false,
    val offsets: List<Double> = emptyList(),
    val scale: Double = 1.0,
    val text: DialogueTextData = DialogueTextData(),
    val animations: List<DialogueAnimationData> = emptyList(),
)

@Serializable
private data class DialogueTextData(
    val offsets: List<Double> = emptyList(),
    val width: Int = 300,
    val size: Int = 32,
    val color: String = "#000000",
    val fontFamily: String = "Arial",
    val shadowColor: String = "#00000000",
    val shadowWidth: Int = 2,
)

@Serializable
private data class SpeakerData(
    val version: String = "",
    val name: String = "",
    val assetPath: String = "",
    val flipX: Boolean = false,
    val flipY: Boolean = false,
    val isPixel: Boolean = false,
    val offsets: List<Double> = emptyList(),
    val scale: Double = 1.0,
    val animations: List<DialogueAnimationData> = emptyList(),
)

@Serializable
private data class DialogueAnimationData(
    val name: String = "",
    val prefix: String = "",
    val offsets: List<Double> = emptyList(),
    val looped: Boolean = false,
    val flipX: Boolean = false,
    val flipY: Boolean = false,
    val frameRate: Int = 24,
    val frameIndices: List<Int> = emptyList(),
) {
    val offsetX: Double get() = offsets.getOrNull(0) ?: 0.0
    val offsetY: Double get() = offsets.getOrNull(1) ?: 0.0
}

private val dialogueJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
    coerceInputValues = true
    explicitNulls = false
}

// ===========================================================================
// A reusable Sparrow portrait / dialogue box sprite
// ===========================================================================

private class SpriteSpec(
    val assetPath: String,
    val scale: Double,
    val isPixel: Boolean,
    val offsets: List<Double>,
    val flipX: Boolean,
    val flipY: Boolean,
    val animations: List<DialogueAnimationData>,
)

private class DialogueSprite(private val spec: SpriteSpec) : Container() {
    private val atlas: TextureAtlas? = loadDialogueAtlas(spec.assetPath)
    private val view: SparrowSprite?
    private var current: DialogueAnimationData? = null

    val nativeWidth: Int
    val nativeHeight: Int
    var baseX: Double = 0.0
    var baseY: Double = 0.0

    init {
        val firstFrame = atlas?.firstFrame()
        nativeWidth = firstFrame?.logicalWidth ?: 0
        nativeHeight = firstFrame?.logicalHeight ?: 0

        val initialFrames = atlas?.let { a ->
            spec.animations.firstOrNull()?.let { framesFor(a, it) }.orEmpty()
                .ifEmpty { a.frames.values.toList() }
        }.orEmpty()

        view = if (atlas != null && initialFrames.isNotEmpty()) {
            SparrowSprite(
                atlas,
                initialFrames,
                fps = spec.animations.firstOrNull()?.frameRate?.toDouble() ?: 24.0,
                loop = spec.animations.firstOrNull()?.looped ?: false,
                centered = false,
            ).also {
                it.x = 0.0
                it.y = 0.0
                it.scaleX = if (spec.flipX) -spec.scale else spec.scale
                it.scaleY = if (spec.flipY) -spec.scale else spec.scale
                if (spec.isPixel) it.setPixelArt(true)
                addChild(it)
            }
        } else {
            null
        }
    }

    /** Current animation is a looping one (an opening animation must not block). */
    val looped: Boolean get() = current?.looped ?: true

    val finished: Boolean get() = view?.finished ?: true

    /**
     * `Speaker.playAnimation` / `DialogueBox.playAnimation`: fall back from a
     * missing `foo-alt` to `foo`, then to `idle`.
     */
    fun playAnimation(name: String): DialogueAnimationData? {
        val anim = resolveAnimation(name) ?: return null
        val previous = current?.name
        current = anim
        val v = view ?: return anim
        val frames = framesFor(atlas ?: return anim, anim)
        if (frames.isEmpty()) return anim
        v.fps = anim.frameRate.toDouble()
        v.loop = anim.looped
        // `playAnimation(name, restart = false)` in FNF does not restart the
        // same animation, but switching to a different one starts at frame 0.
        v.play(frames, restart = previous != anim.name)
        v.visible = true
        return anim
    }

    fun update(dt: Double) {
        view?.update(dt)
    }

    /** Applies this sprite's per-animation offset to its own container. */
    fun applyOffset(anim: DialogueAnimationData?) {
        x = baseX + (anim?.offsetX ?: 0.0)
        y = baseY + (anim?.offsetY ?: 0.0)
    }

    private fun resolveAnimation(name: String): DialogueAnimationData? {
        spec.animations.firstOrNull { it.name == name }?.let { return it }
        var candidate = name
        while (candidate.contains('-')) {
            candidate = candidate.substringBeforeLast('-')
            spec.animations.firstOrNull { it.name == candidate }?.let { return it }
        }
        return spec.animations.firstOrNull { it.name == "idle" }
    }
}

private fun framesFor(
    atlas: TextureAtlas,
    anim: DialogueAnimationData,
): List<TextureAtlas.AtlasFrame> {
    val all = if (anim.prefix.isNotBlank()) {
        atlas.framesByPrefix(anim.prefix)
    } else {
        atlas.frames.values.toList()
    }
    return if (anim.frameIndices.isNotEmpty()) {
        anim.frameIndices.mapNotNull { all.getOrNull(it) }
    } else {
        all
    }
}

private fun loadDialogueAtlas(assetPath: String): TextureAtlas? {
    if (assetPath.isBlank()) return null
    val xml = KnfFiles.resolve(assetPath, ".xml") ?: run {
        println("KNF: dialogue sprite '$assetPath' has no texture atlas")
        return null
    }
    return TextureAtlas.tryLoad(xml) ?: run {
        println("KNF: could not load dialogue atlas '$xml'")
        null
    }
}

// ===========================================================================
// The cutscene
// ===========================================================================

/**
 * Data-driven dialogue cutscene, the Kotlin port of FNF 0.8.6's
 * `Conversation` + `Speaker` + `DialogueBox`.
 *
 * The conversation cancels the countdown, owns the screen while it runs, and
 * returns `true` once its outro has finished. `PlayScene` then calls
 * `beginCountdown()`, just like `onDialogueEnd` calls
 * `Countdown.performCountdown()` in the original song scripts.
 */
class DialogueCutscene(
    private val host: CutsceneHost,
    private val conversationId: String,
    /** Played once immediately before the first line, e.g. roses' ANGRY_TEXT_BOX. */
    private val beforeSoundStem: String? = null,
    /** `senpai-pico.hxc`: stop the music when this speaker enters. */
    private val musicStopSpeakers: Set<String> = emptySet(),
    /** `senpai-pico.hxc`: start it again when this speaker returns. */
    private val musicRestartSpeakers: Set<String> = emptySet(),
) : SongCutscene {

    private enum class State { START, OPENING, SPEAKING, IDLE, ENDING }

    private var data: ConversationFile? = null
    private var root: Container? = null
    private var backdrop: SolidRect? = null
    private var speakerLayer: Container? = null
    private var boxLayer: Container? = null
    private var boxGroup: Container? = null
    private var box: DialogueSprite? = null
    private var speaker: DialogueSprite? = null
    private var mainText: Text? = null
    private var shadowText: Text? = null

    private var boxId = ""
    private var boxBaseX = 0.0
    private var boxBaseY = 0.0
    private var textX = 0.0
    private var textY = 0.0
    private var textWidth = 900
    private var textSize = 32
    private var typeSpeed = 1.0

    private var currentSpeakerId = ""

    private var state = State.START
    private var entryIndex = 0
    private var lineIndex = 0

    private var combinedText = ""
    private var wrappedText = ""
    private var shownChars = 0
    private var typing = false
    private var typeAccumulator = 0.0

    private var backdropElapsed = 0.0
    private var backdropFadeTime = 0.0
    /** Alpha encoded in the backdrop color; Korge's `View.alpha` overwrites `color.a`. */
    private var backdropAlpha = 1.0

    private var music: MusicPlayer? = null
    private var musicPausedByMenu = false
    private var musicFadeElapsed = 0.0
    private var musicFadeTime = 0.0
    private var musicActive = false
    private var musicStoppedForSpeaker = false
    private var musicOutroStartVolume = 0.0

    private var outroElapsed = 0.0
    private var outroDuration = 0.0

    private var textSoundPath: String? = null
    private var beforeSoundPath: String? = null
    private var musicData: MusicData? = null
    private var prepared = false
    private var shown = false
    private var started = false
    private var done = false

    override suspend fun start() {
        if (started) return
        started = true
        prepare()
        show()
    }

    /**
     * Loads the conversation, fonts/sounds and music without touching the view
     * graph. Scripted preludes (thorns, senpai) use this to get the dialogue
     * ready while their own sequence owns the screen.
     */
    suspend fun prepare() {
        if (prepared) return
        prepared = true

        val loaded = loadConversation(conversationId)
        if (loaded == null) {
            println("KNF: dialogue '$conversationId' data is missing")
            done = true
            return
        }
        data = loaded
        if (loaded.dialogue.isEmpty()) {
            println("KNF: dialogue '$conversationId' has no entries")
            done = true
            return
        }

        host.setHudVisible(false)

        beforeSoundPath = beforeSoundStem?.let { stem ->
            runCatching { host.preloadSound(stem) }.getOrNull()
        }
        textSoundPath = runCatching { host.preloadSound("sounds/pixelText") }.getOrNull()
        prepareMusic(loaded.music)
    }

    /** Adds the conversation's backdrop, speaker and box and starts typing. */
    fun show() {
        if (shown || done) return
        val loaded = data ?: return
        shown = true

        val root = Container()
        this.root = root
        host.addCutsceneView(root)

        setupBackdrop(root, loaded.backdrop)
        speakerLayer = Container().also { root.addChild(it) }
        boxLayer = Container().also { root.addChild(it) }

        beforeSoundPath?.let { host.playSfx(it, 1.0) }
        startMusic()
        beginEntry(0)
        println("KNF: dialogue '$conversationId' started (${loaded.dialogue.size} entries)")
    }

    override fun update(dt: Double): Boolean {
        if (done) return true
        if (root == null) return false

        updateBackdrop(dt)
        updateMusicFade(dt)
        box?.update(dt)
        speaker?.update(dt)

        if (state == State.ENDING) {
            updateOutro(dt)
            return done
        }

        if (state == State.OPENING) {
            val b = box
            if (b == null || b.looped || b.finished) finishOpening()
        }

        if (state == State.SPEAKING) updateTyping(dt)

        if (host.advancePressed) {
            if (DEBUG) println("KNF: dialogue advance pressed in $state")
            advance()
        }

        return done
    }

    override val pauseMode: CutscenePauseMode
        get() = if (shown && !done) CutscenePauseMode.CONVERSATION else CutscenePauseMode.NONE

    /** `Conversation.pause`: stop the conversation track, not the whole song. */
    override fun pause() {
        if (musicPausedByMenu) return
        val player = music ?: return
        musicPausedByMenu = player.pauseIfPlaying()
    }

    /** `Conversation.resume`; runs when the pause substate closes. */
    override fun resume() {
        if (!musicPausedByMenu) return
        music?.resume()
        musicPausedByMenu = false
    }

    /** `Conversation.skipConversation`: jump to the outro. */
    override fun skip() {
        if (shown && !done) startOutro()
    }

    /**
     * `Conversation.resetConversation`: clear the speaker, box and backdrop,
     * stop the track, rewind to the first line, and rebuild the view. The
     * trace is kept loaded so restarting does not repeat the initial load.
     */
    override fun restart() {
        if (!shown) return

        root?.let { host.removeCutsceneView(it) }
        root = null
        backdrop = null
        speakerLayer = null
        boxLayer = null
        boxGroup = null
        box = null
        speaker = null
        mainText = null
        shadowText = null

        music?.stop()
        musicPausedByMenu = false
        musicActive = false
        musicStoppedForSpeaker = false
        musicFadeElapsed = 0.0
        musicOutroStartVolume = 0.0

        backdropElapsed = 0.0
        backdropFadeTime = 0.0
        outroElapsed = 0.0
        outroDuration = 0.0
        state = State.START
        entryIndex = 0
        lineIndex = 0
        boxId = ""
        currentSpeakerId = ""
        combinedText = ""
        wrappedText = ""
        shownChars = 0
        typing = false
        typeAccumulator = 0.0

        shown = false
        done = false
        show()
        println("KNF: dialogue '$conversationId' restarted")
    }

    override fun destroy() {
        done = true
        musicPausedByMenu = false
        music?.dispose()
        music = null
        root?.let { host.removeCutsceneView(it) }
        root = null
        backdrop = null
        speakerLayer = null
        boxLayer = null
        boxGroup = null
        box = null
        speaker = null
        mainText = null
        shadowText = null
        host.setHudVisible(true)
    }

    // ------------------------------------------------------------------
    // setup
    // ------------------------------------------------------------------

    private fun setupBackdrop(parent: Container, data: BackdropData?) {
        if (data == null || data.type != "solid") return
        val color = parseColor(data.color)
        // `View.alpha` and `SolidRect.color` share the same Korge color
        // transform: assigning `rect.alpha` would overwrite the alpha byte in
        // `color`. Keep the color alpha separately and fold it into the fade.
        backdropAlpha = color.a / 255.0
        val rect = parent.solidRect(
            Constants.GAME_WIDTH.toDouble(),
            Constants.GAME_HEIGHT.toDouble(),
            color,
        )
        backdropFadeTime = data.fadeTime.coerceAtLeast(0.0)
        backdropElapsed = 0.0
        rect.alpha = if (backdropFadeTime <= 0.0) backdropAlpha else 0.0
        backdrop = rect
    }

    private fun updateBackdrop(dt: Double) {
        val rect = backdrop ?: return
        if (backdropFadeTime <= 0.0) {
            rect.alpha = backdropAlpha
            return
        }
        backdropElapsed += dt
        val t = (backdropElapsed / backdropFadeTime).coerceIn(0.0, 1.0)
        rect.alpha = floor(t * 10.0) / 10.0 * backdropAlpha
    }

    private fun ensureBox(id: String) {
        if (boxGroup != null && boxId == id) return

        boxGroup?.removeFromParent()
        boxGroup = null
        box = null
        mainText = null
        shadowText = null
        boxId = id

        val boxData = loadBox(id) ?: fallbackBoxData(id)
        val spec = SpriteSpec(
            assetPath = boxData.assetPath,
            scale = boxData.scale,
            isPixel = boxData.isPixel,
            offsets = boxData.offsets,
            flipX = boxData.flipX,
            flipY = boxData.flipY,
            animations = boxData.animations,
        )
        val group = Container()
        val sprite = DialogueSprite(spec)
        group.addChild(sprite)
        boxLayer?.addChild(group)

        // `DialogueBox.globalOffsets` is assigned before its box sprite is
        // added to the group. `FlxSpriteGroup.screenCenter()` therefore sees a
        // zero-sized group and starts from the screen center, then applies the
        // data offsets. The sprite itself is scaled afterwards.
        boxBaseX = Constants.GAME_WIDTH / 2.0 + (spec.offsets.getOrNull(0) ?: 0.0)
        boxBaseY = Constants.GAME_HEIGHT / 2.0 + (spec.offsets.getOrNull(1) ?: 0.0)
        group.x = boxBaseX
        group.y = boxBaseY

        textX = boxData.text.offsets.getOrNull(0) ?: 0.0
        textY = boxData.text.offsets.getOrNull(1) ?: 0.0
        textWidth = boxData.text.width.coerceAtLeast(1)
        textSize = boxData.text.size.coerceAtLeast(1)

        val shadowColor = parseColor(boxData.text.shadowColor)
        if (boxData.text.shadowWidth > 0 && shadowColor.a > 0) {
            val shadow = Text(
                "",
                boxData.text.size.toDouble(),
                shadowColor,
            )
            dialogueFont?.let { shadow.font = Resourceable.Fixed(it) }
            shadow.smoothing = false
            shadow.x = textX + boxData.text.shadowWidth
            shadow.y = textY + boxData.text.shadowWidth
            group.addChild(shadow)
            shadowText = shadow
        }

        val main = Text(
            "",
            boxData.text.size.toDouble(),
            parseColor(boxData.text.color),
        )
        dialogueFont?.let { main.font = Resourceable.Fixed(it) }
        main.smoothing = false
        main.x = textX
        main.y = textY
        group.addChild(main)
        mainText = main

        boxGroup = group
        box = sprite
    }

    private fun showSpeaker(id: String) {
        if (id == currentSpeakerId && speaker != null) return

        if (id.isNotEmpty() && id != currentSpeakerId) {
            if (id in musicStopSpeakers) stopMusicForSpeaker()
            if (id in musicRestartSpeakers) restartMusicForSpeaker()
        }

        speakerLayer?.removeChildren()
        speaker = null
        currentSpeakerId = id

        if (id.isEmpty()) return
        val speakerData = loadSpeaker(id)
        if (speakerData == null) {
            println("KNF: dialogue speaker '$id' is missing")
            return
        }

        val spec = SpriteSpec(
            assetPath = speakerData.assetPath,
            scale = speakerData.scale,
            isPixel = speakerData.isPixel,
            offsets = speakerData.offsets,
            flipX = speakerData.flipX,
            flipY = speakerData.flipY,
            animations = speakerData.animations,
        )
        val sprite = DialogueSprite(spec)
        sprite.baseX = (Constants.GAME_WIDTH - sprite.nativeWidth) / 2.0 +
            (spec.offsets.getOrNull(0) ?: 0.0)
        sprite.baseY = (Constants.GAME_HEIGHT - sprite.nativeHeight) / 2.0 +
            (spec.offsets.getOrNull(1) ?: 0.0)
        sprite.applyOffset(null)
        sprite.visible = false
        speakerLayer?.addChild(sprite)
        speaker = sprite
    }

    // ------------------------------------------------------------------
    // conversation flow
    // ------------------------------------------------------------------

    private fun beginEntry(index: Int) {
        val entries = data?.dialogue.orEmpty()
        if (index >= entries.size) {
            startOutro()
            return
        }

        entryIndex = index
        lineIndex = 0
        val entry = entries[index]
        typeSpeed = entry.speed.coerceAtLeast(0.001)

        ensureBox(entry.box)
        val anim = box?.playAnimation(entry.boxAnimation.ifEmpty { "idle" })
        boxGroup?.x = boxBaseX + (anim?.offsetX ?: 0.0)
        boxGroup?.y = boxBaseY + (anim?.offsetY ?: 0.0)
        combinedText = ""
        wrappedText = ""
        shownChars = 0
        typing = false
        updateText()
        state = State.OPENING
        if (DEBUG) println("KNF: dialogue entry $index (${entry.speaker})")
    }

    private fun finishOpening() {
        if (state != State.OPENING) return
        val entry = currentEntry() ?: run {
            startOutro()
            return
        }

        state = State.SPEAKING
        showSpeaker(entry.speaker)
        val anim = speaker?.playAnimation(entry.speakerAnimation)
        speaker?.applyOffset(anim)
        speaker?.visible = true

        val first = entry.text.firstOrNull()
        if (first == null) {
            advanceEntry()
        } else {
            lineIndex = 0
            startLine(first)
        }
    }

    private fun startLine(line: String) {
        combinedText = line
        wrappedText = wrapText(combinedText, textWidth)
        shownChars = 0
        typeAccumulator = 0.0
        typing = wrappedText.isNotEmpty()
        state = if (typing) State.SPEAKING else State.IDLE
        updateText()
        if (DEBUG) println("KNF: dialogue line $entryIndex.$lineIndex")
    }

    private fun appendLine(line: String) {
        val previousLength = wrappedText.length
        combinedText += line
        wrappedText = wrapText(combinedText, textWidth)
        shownChars = min(previousLength, wrappedText.length)
        typeAccumulator = 0.0
        typing = shownChars < wrappedText.length
        state = if (typing) State.SPEAKING else State.IDLE
        updateText()
        if (DEBUG) println("KNF: dialogue line $entryIndex.$lineIndex")
    }

    private fun updateTyping(dt: Double) {
        if (!typing) return
        val interval = 0.05 * typeSpeed
        typeAccumulator += dt
        var changed = false
        while (typing && typeAccumulator >= interval && shownChars < wrappedText.length) {
            typeAccumulator -= interval
            shownChars++
            changed = true
        }
        if (changed) {
            updateText()
            // FlxTypeText plays one blip per update, even if several characters
            // were revealed at once after a dropped frame.
            playTextSound()
        }
        if (shownChars >= wrappedText.length) {
            shownChars = wrappedText.length
            typing = false
            state = State.IDLE
        }
    }

    private fun advance() {
        when (state) {
            State.OPENING -> finishOpening()
            State.SPEAKING -> {
                shownChars = wrappedText.length
                typing = false
                updateText()
                state = State.IDLE
            }
            State.IDLE -> advanceEntry()
            State.ENDING -> finishOutro()
            State.START -> Unit
        }
    }

    private fun advanceEntry() {
        val entry = currentEntry() ?: run {
            startOutro()
            return
        }
        lineIndex++
        if (lineIndex < entry.text.size) {
            appendLine(entry.text[lineIndex])
        } else if (entryIndex + 1 < data?.dialogue.orEmpty().size) {
            beginEntry(entryIndex + 1)
        } else {
            startOutro()
        }
    }

    private fun startOutro() {
        if (state == State.ENDING) return
        state = State.ENDING
        musicActive = false
        musicOutroStartVolume = music?.volume ?: 0.0
        outroElapsed = 0.0
        outroDuration = data?.outro
            ?.takeIf { it.type == "fade" }
            ?.fadeTime
            ?.coerceAtLeast(0.0)
            ?: 0.0
        if (outroDuration <= 0.0) finishOutro()
    }

    private fun updateOutro(dt: Double) {
        outroElapsed += dt
        val t = (outroElapsed / outroDuration).coerceIn(0.0, 1.0)
        root?.alpha = 1.0 - floor(t * 8.0) / 8.0
        music?.volume = musicOutroStartVolume * (1.0 - t)
        if (t >= 1.0) finishOutro()
    }

    private fun finishOutro() {
        if (done) return
        done = true
        root?.alpha = 0.0
        println("KNF: dialogue '$conversationId' finished")
    }

    // ------------------------------------------------------------------
    // text
    // ------------------------------------------------------------------

    private fun updateText() {
        val visible = wrappedText.take(shownChars)
        mainText?.text = visible
        shadowText?.text = visible
    }

    private fun playTextSound() {
        textSoundPath?.let { host.playSfx(it, 0.6) }
    }

    /**
     * `FunkinTypeText.insertBreakLines`: the complete line is wrapped before
     * typing starts, so words never jump to another line mid-animation.
     */
    private fun wrapText(text: String, width: Int): String {
        val font = dialogueFont ?: return text
        val out = StringBuilder()
        var firstParagraph = true
        for (paragraph in text.split('\n')) {
            if (!firstParagraph) out.append('\n')
            firstParagraph = false
            var line = ""
            for (match in WORD_OR_SPACE.findAll(paragraph)) {
                val token = match.value
                if (token.isBlank()) {
                    line += token
                    continue
                }
                val candidate = line + token
                if (line.isNotEmpty() && font.getTextBounds(textSize.toDouble(), candidate).width > width) {
                    out.append(line.trimEnd()).append('\n')
                    line = token
                } else {
                    line = candidate
                }
            }
            out.append(line)
        }
        return out.toString()
    }

    // ------------------------------------------------------------------
    // music
    // ------------------------------------------------------------------

    private suspend fun prepareMusic(musicData: MusicData?) {
        if (musicData == null || musicData.asset.isBlank()) return
        this.musicData = musicData
        val path = host.preloadMusic("music/${musicData.asset}") ?: run {
            println("KNF: dialogue music '${musicData.asset}' not found")
            return
        }
        val player = MusicPlayer()
        player.loop = musicData.looped
        if (!player.load(path, 0.0)) {
            println("KNF: could not load dialogue music '$path'")
            player.dispose()
            return
        }
        music = player
        musicFadeTime = musicData.fadeTime.coerceAtLeast(0.0)
        musicFadeElapsed = 0.0
        musicActive = false
        musicStoppedForSpeaker = false
        if (DEBUG) println("KNF: dialogue music '${musicData.asset}' loaded")
    }

    /**
     * Starts the preloaded track. Split from [prepareMusic] so scripted
     * preludes can load it without hearing music before the conversation.
     */
    private fun startMusic() {
        val player = music ?: return
        musicFadeElapsed = 0.0
        musicActive = true
        musicStoppedForSpeaker = false
        player.volume = 0.0
        player.play(0.0)
        if (DEBUG) println("KNF: dialogue music started")
    }

    private fun updateMusicFade(dt: Double) {
        val player = music ?: return
        if (!musicActive) return
        musicFadeElapsed += dt
        player.volume = if (musicFadeTime <= 0.0) {
            1.0
        } else {
            (musicFadeElapsed / musicFadeTime).coerceIn(0.0, 1.0)
        }
    }

    private fun stopMusicForSpeaker() {
        music?.stop()
        musicActive = false
        musicStoppedForSpeaker = true
        if (DEBUG) println("KNF: dialogue music stopped for speaker")
    }

    private fun restartMusicForSpeaker() {
        val player = music ?: return
        if (!musicStoppedForSpeaker) return
        musicStoppedForSpeaker = false
        musicActive = true
        musicFadeElapsed = 0.0
        player.volume = 0.0
        player.play(0.0)
        if (DEBUG) println("KNF: dialogue music restarted")
    }

    // ------------------------------------------------------------------
    // data loading
    // ------------------------------------------------------------------

    private fun currentEntry(): DialogueEntryData? =
        data?.dialogue?.getOrNull(entryIndex)

    private fun loadConversation(id: String): ConversationFile? =
        loadDialogueJson("preload/data/dialogue/conversations/$id.json")

    private fun loadBox(id: String): DialogueBoxData? =
        loadDialogueJson("preload/data/dialogue/boxes/$id.json")

    private fun loadSpeaker(id: String): SpeakerData? =
        loadDialogueJson("preload/data/dialogue/speakers/$id.json")

    private inline fun <reified T> loadDialogueJson(path: String): T? {
        val text = KnfFiles.textOrNull(path) ?: return null
        return runCatching { dialogueJson.decodeFromString<T>(text) }
            .onFailure { println("KNF: failed to parse dialogue data '$path': $it") }
            .getOrNull()
    }

    private fun fallbackBoxData(id: String) = DialogueBoxData(
        name = id,
        text = DialogueTextData(
            offsets = listOf(0.0, 0.0),
            width = 900,
            size = 32,
            color = "#FFFFFF",
            shadowColor = "#00000000",
            shadowWidth = 0,
        ),
    )

    private companion object {
        val debug = System.getenv("KNF_DEBUG_DIALOGUE") == "1"
        val DEBUG: Boolean get() = debug

        val WORD_OR_SPACE = Regex("\\S+|\\s+")

        /**
         * `assets/fonts/pixel.otf`, family `Pixel Arial 11`; the dialogue JSON
         * asks for the bold face of that family.
         */
        val dialogueFont: TtfFont? by lazy {
            KnfFiles.bytesOrNull("fonts/pixel.otf")
                ?.let { runCatching { TtfFont(it, "pixel.otf") }.getOrNull() }
        }

        /** `#RRGGBB` or `#AARRGGBB`, matching Flixel's `FlxColor.fromString`. */
        fun parseColor(value: String): RGBA {
            val hex = value.trim().removePrefix("#")
            return runCatching {
                when (hex.length) {
                    8 -> RGBA(
                        hex.substring(2, 4).toInt(16),
                        hex.substring(4, 6).toInt(16),
                        hex.substring(6, 8).toInt(16),
                        hex.substring(0, 2).toInt(16),
                    )
                    6 -> RGBA(
                        hex.substring(0, 2).toInt(16),
                        hex.substring(2, 4).toInt(16),
                        hex.substring(4, 6).toInt(16),
                    )
                    3 -> {
                        val r = hex.substring(0, 1).repeat(2).toInt(16)
                        val g = hex.substring(1, 2).repeat(2).toInt(16)
                        val b = hex.substring(2, 3).repeat(2).toInt(16)
                        RGBA(r, g, b)
                    }
                    else -> Colors.WHITE
                }
            }.getOrDefault(Colors.WHITE)
        }
    }
}
