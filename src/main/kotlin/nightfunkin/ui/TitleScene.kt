package nightfunkin.ui

import korlibs.image.bitmap.Bitmap
import korlibs.image.bitmap.sliceWithSize
import korlibs.image.color.Colors
import korlibs.korge.scene.Scene
import korlibs.korge.view.Container
import korlibs.korge.view.Image
import korlibs.korge.view.SContainer
import korlibs.korge.view.SolidRect
import korlibs.korge.view.addUpdater
import korlibs.korge.view.image
import korlibs.korge.view.solidRect
import kotlinx.coroutines.launch
import nightfunkin.audio.MusicPlayer
import nightfunkin.audio.SfxPlayer
import nightfunkin.core.AnimateAtlas
import nightfunkin.core.Constants
import nightfunkin.core.FunkinAtlasText
import nightfunkin.core.KnfFiles
import nightfunkin.core.KnfInput
import nightfunkin.core.SparrowSprite
import nightfunkin.core.TextureAtlas
import kotlin.math.min
import kotlin.random.Random
import kotlin.time.Duration

/**
 * Friday Night Funkin' 0.8.6's title screen.
 *
 * `TitleState` first plays a one-shot credits sequence (black screen,
 * `freakyMenu`, text groups on conductor beats 1-16). When it finishes - or the
 * player presses accept to skip it - the real title screen underneath is
 * revealed: the bumping logo, GF dancing on her speakers, and the pulsing
 * "Press Enter to Begin" text.
 */
class TitleScene : Scene() {

    private var t = 0.0
    private var musicTime = 0.0
    private val music = MusicPlayer()
    private val sfx = SfxPlayer()

    private lateinit var titleGroup: Container
    private var introGroup: Container? = null
    private var introTextGroup: Container? = null
    private var flashRect: SolidRect? = null

    private var playIntro = false
    private var introDone = false
    private var musicStarted = false
    private var introElapsed = 0.0
    private var lastBeat = 0
    private var flashElapsed = 0.0

    private var logoView: SparrowSprite? = null
    private var gfView: SparrowSprite? = null
    private var gfLeftFrames: List<TextureAtlas.AtlasFrame> = emptyList()
    private var gfRightFrames: List<TextureAtlas.AtlasFrame> = emptyList()
    private var titleText: TitleTextSprite? = null

    private var danceLeft = false
    private var lastTitleBeat = 0
    private var confirmSoundPath: String? = null

    private var transitioning = false
    private var transitionElapsed = 0.0
    private var movedToMenu = false

    private var newgrounds: Image? = null
    private var newgroundsBitmap: Bitmap? = null
    private var newgroundsAnimated = false
    private var newgroundsFrameWidth = 0

    private val wacky: Pair<String, String> by lazy { loadRandomIntroText() }

    override suspend fun SContainer.sceneInit() {
        playIntro = !initialized
        if (playIntro) initialized = true
        introDone = !playIntro

        titleGroup = Container()
        addChild(titleGroup)
        buildTitleScreen(titleGroup)
        titleGroup.visible = !playIntro

        KnfFiles.resolveSound("preload/music/freakyMenu/freakyMenu")?.let { music.load(it, 0.55) }
        KnfFiles.resolveSound("preload/sounds/confirmMenu")?.let {
            confirmSoundPath = it
            sfx.preload(it)
        }

        if (playIntro) {
            buildIntro(this)
        } else {
            startMusic()
        }

        // `FlxG.camera.flash` sits above both the credits and the title screen.
        flashRect = solidRect(
            Constants.GAME_WIDTH.toDouble(),
            Constants.GAME_HEIGHT.toDouble(),
            Colors.WHITE,
        ).also {
            it.visible = false
            it.alpha = 1.0
        }
    }

    private fun buildTitleScreen(parent: Container) {
        // FNF's camera background is black; the logo, GF and text are the state.
        parent.solidRect(Constants.GAME_WIDTH * 2.0, Constants.GAME_HEIGHT * 2.0, Colors.BLACK)

        // logoBl = new FunkinSprite(-150 + cutout/2.5, -100).
        val logoAtlas = TextureAtlas.tryLoad("preload/images/logoBumpin.xml")
        if (logoAtlas != null) {
            val frames = logoAtlas.framesByPrefix("logo bumpin")
            if (frames.isNotEmpty()) {
                logoView = SparrowSprite(logoAtlas, frames, fps = 24.0, loop = true).also {
                    it.x = -150.0
                    it.y = -100.0
                    parent.addChild(it)
                }
            }
        }

        // gfDance = new FunkinSprite(width * 0.4 + cutout/2.5, height * 0.07).
        val gfAtlas = TextureAtlas.tryLoad("preload/images/gfDanceTitle.xml")
        if (gfAtlas != null) {
            val all = gfAtlas.framesByPrefix("gfDance")
            // `addByIndices` silently drops indices that have no frame; 30 is
            // such an index in the shipped 0.8.6 atlas, so left starts at 0.
            gfLeftFrames = (listOf(30) + (0..14)).mapNotNull { all.getOrNull(it) }
            gfRightFrames = (15..29).mapNotNull { all.getOrNull(it) }
            if (gfLeftFrames.isNotEmpty()) {
                gfView = SparrowSprite(gfAtlas, gfLeftFrames, fps = 24.0, loop = false).also {
                    it.x = Constants.GAME_WIDTH * 0.4
                    it.y = Constants.GAME_HEIGHT * 0.07
                    it.playing = false
                    parent.addChild(it)
                }
            }
        }

        // titleText = FunkinSprite.createTextureAtlas(100, height * 0.8, ...).
        val titleAtlas = AnimateAtlas("preload/images/title-screen-text")
        val slices = listOf("0", "1", "2", "3").mapNotNull { titleAtlas.sprites[it]?.slice }
        if (slices.size == 4) {
            titleText = TitleTextSprite(slices[0], slices[1], slices[2], slices[3]).also {
                it.x = 100.0
                it.y = Constants.GAME_HEIGHT * 0.8
                parent.addChild(it)
            }
        }
    }

    private fun buildIntro(root: SContainer) {
        val group = Container()
        root.addChild(group)
        introGroup = group

        // FlxSprite(-1).makeSolidColor(width + 2, height, BLACK), then screenCenter.
        group.solidRect(Constants.GAME_WIDTH + 2.0, Constants.GAME_HEIGHT.toDouble(), Colors.BLACK).also {
            it.x = -1.0
        }

        buildNewgrounds(group)

        introTextGroup = Container().also { group.addChild(it) }
    }

    private fun buildNewgrounds(parent: Container) {
        val roll = Random.nextDouble()
        val animated = roll >= 0.01 && roll < 0.31
        val asset = when {
            roll < 0.01 -> "newgrounds_logo_classic.png"
            animated -> "newgrounds_logo_animated.png"
            else -> "newgrounds_logo.png"
        }
        val bitmap = KnfFiles.bitmapOrNull("preload/images/$asset") ?: return

        val frameWidth = if (animated) 600 else bitmap.width
        val frame = bitmap.sliceWithSize(0, 0, frameWidth, bitmap.height)
        val scale = when {
            animated -> 0.55
            roll < 0.01 -> 1.0
            else -> 0.8
        }
        val y = Constants.GAME_HEIGHT * 0.52 + if (animated) 25.0 else 0.0

        newgrounds = parent.image(frame).also {
            it.smoothing = false
            it.scaleX = scale
            it.scaleY = scale
            it.x = Constants.GAME_WIDTH / 2.0 - frameWidth * scale / 2.0
            it.y = y
            it.visible = false
        }
        newgroundsBitmap = bitmap
        newgroundsAnimated = animated
        newgroundsFrameWidth = frameWidth
    }

    private fun startMusic() {
        if (musicStarted) return
        musicStarted = true
        musicTime = 0.0
        music.volume = if (playIntro) 0.0 else 1.0
        music.play()
    }

    override suspend fun SContainer.sceneMain() {
        val knfInput = KnfInput(keys)
        addUpdater { dt ->
            val s = dt.secondsDouble()
            t += s
            knfInput.update()

            // TitleState.create() waits one second before startIntro().
            if (!musicStarted && t >= 1.0) startMusic()

            if (musicStarted) {
                musicTime = if (music.exists) music.positionMs / 1000.0 else musicTime + s

                if (playIntro && !introDone) {
                    introElapsed = musicTime
                    music.volume = min(1.0, introElapsed / 4.0)
                    updateIntro(introElapsed)
                }
            }

            updateTitle(s)

            flashRect?.let { flash ->
                if (flash.visible) {
                    flashElapsed += s
                    flash.alpha = (1.0 - flashElapsed).coerceIn(0.0, 1.0)
                    if (flashElapsed >= 1.0) flash.visible = false
                }
            }

            if (transitioning) {
                transitionElapsed += s
                if (transitionElapsed >= CONFIRM_DELAY) moveToMenu()
            }

            if (knfInput.accept) {
                if (playIntro && !introDone) {
                    // Before startIntro() FNF has not set `initialized` yet, so
                    // an early press cannot skip the credits.
                    if (musicStarted) skipIntro(playBeat = false)
                } else if (!movedToMenu) {
                    if (transitioning) moveToMenu() else startTitleConfirm()
                }
            }
        }
    }

    /** Drives the title screen's idle animations and per-beat bops. */
    private fun updateTitle(dt: Double) {
        logoView?.update(dt)
        titleText?.update(dt)

        if (!introDone) return

        val beat = (musicTime / BEAT_SECONDS).toInt()
        if (beat > lastTitleBeat) {
            repeat(beat - lastTitleBeat) { onTitleBeat() }
            lastTitleBeat = beat
        }

        gfView?.update(dt)
    }

    /**
     * `TitleState.beatHit` once the credits are over: restart the logo bump and
     * alternate GF's left/right dance animations.
     */
    private fun onTitleBeat() {
        logoView?.restart()
        danceLeft = !danceLeft
        val frames = if (danceLeft) gfRightFrames else gfLeftFrames
        if (frames.isNotEmpty()) gfView?.play(frames)
    }

    private fun startTitleConfirm() {
        if (transitioning || movedToMenu) return
        titleText?.press()

        flashElapsed = 0.0
        flashRect?.visible = true
        flashRect?.alpha = 1.0

        confirmSoundPath?.let { sfx.play(it, 0.7) }
        transitioning = true
        transitionElapsed = 0.0
    }

    private fun moveToMenu() {
        if (movedToMenu) return
        movedToMenu = true
        music.stop()
        launch { sceneContainer.changeTo<SongSelectScene>() }
    }

    private fun updateIntro(elapsed: Double) {
        if (!playIntro || introDone) return

        if (newgroundsAnimated && newgrounds?.visible == true) {
            val bitmap = newgroundsBitmap
            if (bitmap != null && newgroundsFrameWidth > 0) {
                val frame = ((elapsed * 4.0).toInt() % 2) * newgroundsFrameWidth
                newgrounds?.bitmap = bitmap.sliceWithSize(frame, 0, newgroundsFrameWidth, bitmap.height)
            }
        }

        val beat = (elapsed / BEAT_SECONDS).toInt()
        if (beat > lastBeat) {
            for (i in lastBeat until beat) {
                when (i + 1) {
                    1 -> createCoolText(listOf("The", "Spaghetti (hokyless)"))
                    3 -> addMoreText("Presents")
                    4 -> deleteCoolText()
                    5 -> createCoolText(listOf("In association", "with"))
                    7 -> {
                        addMoreText("newgrounds")
                        newgrounds?.visible = true
                    }
                    8 -> {
                        deleteCoolText()
                        newgrounds?.visible = false
                    }
                    9 -> createCoolText(listOf(wacky.first))
                    11 -> addMoreText(wacky.second)
                    12 -> deleteCoolText()
                    13 -> addMoreText("Kotlin")
                    14 -> addMoreText(if (wacky.first == "trending") "Nigth" else "Night")
                    15 -> addMoreText("Funkin")
                    16 -> skipIntro(playBeat = true)
                }
                if (introDone) break
            }
            lastBeat = beat
        }

        val group = introTextGroup ?: return
        for (child in group.children) {
            (child as? FunkinAtlasText)?.update(elapsed)
        }
    }

    private fun createCoolText(lines: List<String>) {
        val group = introTextGroup ?: return
        for ((i, line) in lines.withIndex()) {
            val text = FunkinAtlasText.bold(line).also { it.bornAt = introElapsed }
            text.x = Constants.GAME_WIDTH / 2.0 - text.contentWidth / 2.0
            text.y = i * 60.0 + 200.0
            group.addChild(text)
        }
    }

    private fun addMoreText(value: String) {
        val group = introTextGroup ?: return
        val text = FunkinAtlasText.bold(value.trim()).also { it.bornAt = introElapsed }
        text.x = Constants.GAME_WIDTH / 2.0 - text.contentWidth / 2.0
        text.y = group.children.size * 60.0 + 200.0
        group.addChild(text)
    }

    private fun deleteCoolText() {
        introTextGroup?.removeChildren()
    }

    private fun skipIntro(playBeat: Boolean) {
        if (introDone) return
        introDone = true
        introGroup?.visible = false
        titleGroup.visible = true
        music.volume = 1.0

        // `skipIntro()` can run inside beatHit (beat 16) or from update (an
        // accept press). Only the former also runs the skipIntro branch of
        // beatHit, so only it starts a dance/bump immediately.
        lastTitleBeat = (musicTime / BEAT_SECONDS).toInt()
        if (playBeat) onTitleBeat()

        flashElapsed = 0.0
        flashRect?.visible = true
        flashRect?.alpha = 1.0
    }

    private fun loadRandomIntroText(): Pair<String, String> {
        val lines = KnfFiles.textOrNull("preload/data/introText.txt")
            ?.lineSequence()
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?.toList()
            .orEmpty()

        if (lines.isEmpty()) return "Kotlin" to "Night Funkin"

        val split = lines[Random.nextInt(lines.size)].split("--", limit = 2)
        return split[0] to split.getOrElse(1) { "" }
    }

    companion object {
        /** `TitleState.initialized` - the credits only play once per session. */
        private var initialized = false

        /** `freakyMenu` is 102 BPM in 4/4. */
        private const val BEAT_SECONDS = 60.0 / 102.0

        /** `new FlxTimer().start(2, ...)` after the confirm press. */
        private const val CONFIRM_DELAY = 2.0
    }
}

fun Duration.secondsDouble(): Double = inWholeNanoseconds / 1_000_000_000.0
