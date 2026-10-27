package nightfunkin.play

import korlibs.audio.sound.AudioChannel
import korlibs.image.color.Colors
import korlibs.image.bitmap.sliceWithSize
import korlibs.korge.scene.Scene
import korlibs.korge.view.Container
import korlibs.korge.view.SContainer
import korlibs.korge.view.SolidRect
import korlibs.korge.view.View
import korlibs.korge.view.addUpdater
import korlibs.korge.view.image
import korlibs.korge.view.solidRect
import korlibs.korge.view.text
import kotlinx.coroutines.launch
import nightfunkin.audio.MusicPlayer
import nightfunkin.audio.SfxPlayer
import nightfunkin.audio.dtSeconds
import korlibs.math.geom.degrees
import nightfunkin.core.Constants
import nightfunkin.core.KnfFiles
import nightfunkin.core.KnfInput
import nightfunkin.core.Knf
import nightfunkin.core.NoteStyle
import nightfunkin.core.Save
import nightfunkin.data.SongDifficulty
import nightfunkin.data.SongEntry
import nightfunkin.data.SongEventData
import nightfunkin.data.SongRegistry
import nightfunkin.timing.Conductor
import nightfunkin.ui.SongSelectScene
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.random.Random

/** What to play вЂ” stashed before switching scenes. */
object PlayRequest {
    /** Audio offset adjust step, in milliseconds. */
    const val OFFSET_STEP_MS = 5f

    var songId: String = "bopeebo"
    var difficulty: String = Constants.DEFAULT_DIFFICULTY
    var variation: String = Constants.DEFAULT_VARIATION
    var autoplay: Boolean = false

    /**
     * FNF's `PlayStatePlaylist.isStoryMode`. KNF has no campaign yet; the
     * Freeplay song list sets this to false, while `KNF_SONG` boots as Story
     * Mode so the story-only video cutscenes can be tested. `KNF_STORY=1`
     * forces Story Mode for normal Freeplay runs too.
     */
    var storyMode: Boolean = false
}

/**
 * The gameplay scene.
 *
 * Structure and update order follow FNF 0.8.6's `PlayState.update`:
 * input first, then song events, then note processing вЂ” because the bot and the
 * miss logic need to see a consistent input state within the same frame.
 */
class PlayScene : Scene() {

    private lateinit var song: SongEntry
    private lateinit var chart: SongDifficulty

    private val conductor = Conductor()
    private val music = MusicPlayer()
/**
     * FNF's `VoicesGroup`: one mixed vocals track played through two heads so
     * each character has an independent volume, which is how the song decides
     * whose line you hear without duplicating the audio.
     */
    private val vocals = MusicPlayer()
    private val opponentVocals = MusicPlayer()
    private val sfx = SfxPlayer()

    /** FNF's `PauseSubState` plays `shared/music/breakfast` under the menu. */
    private val pauseMusic = MusicPlayer()

    private lateinit var gameInput: KnfInput
    private lateinit var noteStyle: NoteStyle

    private lateinit var cameraLayer: Container
    private lateinit var hudContainer: Container
    private lateinit var playerStrumline: Strumline
    private lateinit var opponentStrumline: Strumline
    private lateinit var hud: Hud

    /** When the bot should let go of each lane, in song milliseconds. */
    private val autoReleaseAt = HashMap<Int, Float>()
    private lateinit var camera: GameplayCamera
    private lateinit var sceneRoot: SContainer

    // ---- gameplay state -------------------------------------------------
    private var score = 0
    private var combo = 0
    private var hits = 0
    private var misses = 0
    private val tallies = ScoreTallies()
    private var startingSong = true
    private var songEnded = false
    private var paused = false

    /** Non-null while the pause overlay is up. */
    private var pauseMenu: PauseMenu? = null
    private var pauseMusicFade = 0.0
    private var pauseMusicGeneration = 0
    private var scrollSound = ""

    /** Non-null while a scripted intro cutscene has replaced the countdown. */
    private var cutscene: SongCutscene? = null
    private var cutsceneSound: AudioChannel? = null
    private var blackScreen: SolidRect? = null

    /** HUD fade-in started by a cutscene; negative when no fade is running. */
    private var hudFadeElapsed = -1.0
    private var hudFadeDuration = 0.0

    /** FNF's `isSongEnd`: the results transition is running. */
    private var ending = false
    private var endingTimer = 0.0
    private var endingTransitionStarted = false
    private var fadeOverlay: SolidRect? = null
    private var startTimestampMs = 0.0
    private var prePosition = 0f

    private var countdownStep = -1
    private var countdownTimer = 0.0
    private val clockDebug = System.getenv("KNF_DEBUG_CLOCK") == "1"
    private var wallClock = 0.0
    private var songStartWallMs = -1.0
    private var clockSampleTimer = 0.0

    private var nextEventIndex = 0

    /** Test hook: `KNF_END_AT_MS=6000` treats the song as finished at 6 s. */
    private val forcedSongEndMs = System.getenv("KNF_END_AT_MS")?.toDoubleOrNull()

    private var totalNotes = 0

    private var debugFrames = 0
    private var debugEnabled = false

    // ---- stage layout (screen coordinates, inside the camera layer) -------
    private lateinit var stageLayer: Container
    private lateinit var opponent: Character
    /** FNF only creates a GF when the song names one; the Tutorial has none. */
    private var girlfriend: Character? = null
    private lateinit var player: Character
    private var stageProps: Stage? = null

    /** Nene/Otis' A-Bot speaker rigs, kept in sync with their character. */
    private val speakerSupports = ArrayList<SpeakerSupport>()

    /**
     * Characters that use a stage-specific `scroll` factor.
     *
     * Flixel applies scroll factors in `getScreenPosition`, but a character's
     * `x`/`y` stay in world space (the stage scripts and camera focus depend on
     * that). KNF therefore keeps the character in a wrapper whose only job is
     * the parallax shift: `(1 - scroll) * camera.scroll`.
     */
    private class CharacterLayer(
        val character: Character,
        val container: Container,
        val scrollX: Double,
        val scrollY: Double,
    )

    private val characterLayers = ArrayList<CharacterLayer>()

    /**
     * FNF's desktop default is upscroll (`Preferences.downscroll == false`),
     * with the receptors one `STRUMLINE_Y_OFFSET` below the top edge.
     */
    private val downscroll = false

    /** Stage-space data for the current stage: props, character slots, zoom. */
    private lateinit var stageFile: StageDataFile

    /** Where the camera rests, i.e. on the opponent's focus point. */
    private lateinit var defaultCameraFocus: Pair<Double, Double>

    /**
     * Stage props go behind the characters but in front of the backdrop.
     *
     * Positions stay in FNF stage space; the camera transform maps them, so no
     * per-prop screen maths is needed.
     */
    private fun buildStageProps() {
        val stage = Stage(chart.stage)
        if (stage.propCount == 0) return
        stageLayer.addChild(stage)
        stageProps = stage
    }

    /**
     * Puts a character on its stage slot using FNF's own arithmetic:
     * feet at `position`, minus half the timeline width and its full height,
     * with the per-character `offsets` applied by `Character.placeFeet`.
     */
    private fun placeCharacter(character: Character, slot: String): StageCharacterData {
        val data = stageFile.characterFor(slot)
        var feetX = data?.position?.getOrElse(0) { 0.0 } ?: 0.0
        var feetY = data?.position?.getOrElse(1) { 0.0 } ?: 0.0
        var applyOffsets = true
        // Scripted `gf` characters override `onAdd`: when Girlfriend is the
        // opponent, `gf.hxc` moves her to the stage's girlfriend point while
        // keeping the dad slot's zIndex, scale and camera offsets.
        if (slot == "dad" && character.id == "gf") {
            stageFile.characterFor("gf")?.position?.let {
                feetX = it.getOrElse(0) { 0.0 }
                feetY = it.getOrElse(1) { 0.0 }
                applyOffsets = false
            }
        }
        val scale = character.baseScale * (data?.scale ?: 1.0)
        // `Stage.addCharacter` inverts the file's `flipX` for the player slot only.
        character.flip = Character.flipForSlot(slot, character.dataFlipX)
        character.placeFeet(feetX, feetY, scale, applyOffsets)
        character.alpha = data?.alpha ?: 1.0
        character.rotation = (data?.angle ?: 0.0).degrees
        return data ?: StageCharacterData()
    }

    // ---- audio/visual sync measurement -------------------------------------
    private val avsyncDebug = System.getenv("KNF_DEBUG_AVSYNC") == "1"
    private var prevConductorMs = 0f
    private var prevAudioMs = 0f
    private var avsum = 0.0
    private var avcount = 0

    private val countdownSounds = listOf(
        "shared/sounds/gameplay/countdown/funkin/introTHREE",
        "shared/sounds/gameplay/countdown/funkin/introTWO",
        "shared/sounds/gameplay/countdown/funkin/introONE",
        "shared/sounds/gameplay/countdown/funkin/introGO",
    ).map { resolveSfx(it) }
    private var missSound: String = ""

    // ---------------------------------------------------------------------

    override suspend fun SContainer.sceneInit() {
        // Available here as well as in sceneMain: cutscenes need to mount their
        // black screen while sceneInit is still running.
        sceneRoot = this
        song = SongRegistry.get(PlayRequest.songId)
            ?: error("KNF: unknown song '${PlayRequest.songId}'")
        chart = song.difficulty(PlayRequest.difficulty, PlayRequest.variation)
            ?: error("KNF: ${song.name} has no chart for ${PlayRequest.difficulty}/${PlayRequest.variation}")

        noteStyle = NoteStyle.get(chart.noteStyle)
        gameInput = KnfInput(keys)
        totalNotes = chart.notes.count { it.isPlayerNote }

        // ---- timing ------------------------------------------------------
        conductor.reset()
        conductor.instrumentalOffset = chart.offsets.instrumental
        conductor.formatOffset = 0f
        conductor.globalOffset = Save.globalOffsetMs
        conductor.mapTimeChanges(chart.timeChanges)

        startTimestampMs = 0.0
        prePosition = conductor.beatLengthMs * -5f

        // ---- view graph --------------------------------------------------
        // The backdrop is screen-space, so it must live *outside* the camera
        // transform or it would slide and zoom with the stage.
        buildStageBackdrop(this)

        cameraLayer = Container()
        addChild(cameraLayer)

        camera = GameplayCamera(cameraLayer, Constants.GAME_WIDTH.toDouble(), Constants.GAME_HEIGHT.toDouble())
        conductor.update(prePosition, false)

        // ---- stage + characters ------------------------------------------
        stageFile = Stage.file(chart.stage)
        stageLayer = Container()
        cameraLayer.addChild(stageLayer)
        buildStageProps()

        val chars = chart.characters
        // FNF's `fetchCharacter()` constructs a new character for every slot.
        // Reusing one cached instance made Tutorial's GF serve as both the
        // opponent and the girlfriend, so her dance()/update() ran twice per
        // beat and the idle always ended on the same side.
        opponent = Character(chars.opponent)
        girlfriend = chars.girlfriend.takeIf { it.isNotEmpty() }?.let { Character(it) }
        player = Character(chars.player)

        val opponentSlot = placeCharacter(opponent, "dad")
        girlfriend?.let { placeCharacter(it, "gf") }
        placeCharacter(player, "bf")

        // tankmanBattlefieldErect.hxc moves the bricks when the girlfriend is
        // added. All of its charts have one, so this runs at stage start.
        if (girlfriend != null && stageFile.id == "tankmanBattlefieldErect") {
            stageProps?.setNamedPropPosition("tankBricks", 445.0, 774.0)
        }

        // `Stage.refresh()` sorts every prop *and* character by zIndex ascending;
        // tankmanBattlefield relies on foreground props (audience, bricks) having
        // higher zIndex than the characters.
        val byZIndex = listOfNotNull(
            Triple("dad", opponent, opponentSlot.zIndex),
            girlfriend?.let { Triple("gf", it, stageFile.characterFor("gf")?.zIndex ?: 0) },
            Triple("bf", player, stageFile.characterFor("bf")?.zIndex ?: 0),
        ).sortedBy { it.third }
        for ((slot, character, zIndex) in byZIndex) {
            character.dance(forceRestart = true)
            val support = SpeakerSupport.forCharacter(character, music)
            val stage = stageProps
            val scroll = stageFile.characterFor(slot)?.scroll.orEmpty()
            val layer = Container().also { it.addChild(character) }
            characterLayers += CharacterLayer(
                character = character,
                container = layer,
                scrollX = scroll.getOrElse(0) { 1.0 },
                scrollY = scroll.getOrElse(1) { 1.0 },
            )
            if (stage != null) {
                // A-Bot sits ten zIndexes under its character, like the scripts.
                support?.let { stage.addCharacter(it, zIndex - 10) }
                stage.addCharacter(layer, zIndex)
            } else {
                support?.let { stageLayer.addChild(it) }
                stageLayer.addChild(layer)
            }
            support?.let { speakerSupports += it }
        }

        // Some shipped characters are placeholders that resolve to nothing
        // (`gf-tankmen` points at a folder that does not exist). Hiding them keeps
        // a missing asset from dragging the camera focus around.
        for ((slot, ch) in listOfNotNull("dad" to opponent, girlfriend?.let { "gf" to it }, "bf" to player)) {
            if (ch.localWidth <= 0.0 || ch.localHeight <= 0.0) {
                ch.visible = false
                println("KNF: character '" + ch.id + "' for slot " + slot + " has no art")
            }
        }
        for (support in speakerSupports) support.update(0.0)

        // FNF opens the camera on the opponent's focus point, at the stage zoom.
        val (focusX, focusY) = opponent.cameraFocusPoint(opponentSlot.cameraOffsets)
        defaultCameraFocus = focusX to focusY
        // FNF's resetCamera with snap=true jumps to the focus point on the first frame.

        camera.setFocusImmediate(focusX, focusY)
        camera.stageZoom = stageFile.cameraZoom
        camera.setZoomImmediate(stageFile.cameraZoom)

        val hudContainer = Container()
        addChild(hudContainer)
        this@PlayScene.hudContainer = hudContainer

        // Each strumline owns its own container so they can be positioned apart.
        val playerLane = Container()
        hudContainer.addChild(playerLane)
        val opponentLane = Container()
        hudContainer.addChild(opponentLane)

        playerStrumline = Strumline(
            container = playerLane,
            noteStyle = noteStyle,
            isPlayer = true,
            initialScrollSpeed = chart.scrollSpeed,
            downscroll = downscroll,
        )
        opponentStrumline = Strumline(
            container = opponentLane,
            noteStyle = noteStyle,
            isPlayer = false,
            initialScrollSpeed = chart.scrollSpeed,
            downscroll = downscroll,
        )
        layoutStrumlines()

        playerStrumline.setNoteData(chart.notes.filter { it.isPlayerNote })
        opponentStrumline.setNoteData(chart.notes.filter { !it.isPlayerNote })

        hud = Hud(hudContainer, noteStyle, downscroll)
        hud.resetHealth()
        hud.setHealthIcons(player, opponent)

        // ---- audio -------------------------------------------------------
        val instPath = song.instrumentalPath(PlayRequest.variation)
        if (instPath == null || !music.load(instPath)) {
            println("KNF: no instrumental found for '${song.name}'")
        } else if (KnfFiles.isMp3(instPath)) {
            // MP3 decoders add ~12 ms of latency; FNF compensates the same way.
            conductor.formatOffset = Constants.MP3_DELAY_MS
        }

        // FNF's `VoicesGroup` plays both characters at once, each from its *own*
        // stem: `Voices-bf.mp3` and `Voices-dad.mp3` are different files rather
        // than one shared mix, so the opponent head needs its own track or the
        // opponent stays silent.
        chart.characters.let { chars ->
            song.vocalsPath(chars.player, PlayRequest.variation)?.let { path ->
                vocals.load(path)
                println("KNF: vocals player " + path)
            }
            song.vocalsPath(chars.opponent, PlayRequest.variation)?.let { path ->
                opponentVocals.load(path)
                println("KNF: vocals opponent " + path)
            }
        }

        sfx.preload(resolveSfx("shared/sounds/gameplay/countdown/funkin/introTHREE"))
        sfx.preload(resolveSfx("shared/sounds/gameplay/countdown/funkin/introTWO"))
        sfx.preload(resolveSfx("shared/sounds/gameplay/countdown/funkin/introONE"))
        sfx.preload(resolveSfx("shared/sounds/gameplay/countdown/funkin/introGO"))
        missSound = resolveSfx("shared/sounds/missnote1")
        sfx.preload(missSound)
        scrollSound = resolveSfx("preload/sounds/scrollMenu")
        sfx.preload(scrollSound)

        // Attached only now: `Conductor.update` dispatches step hits immediately,
        // and `onBeat()` needs the characters to already exist.
        conductor.onStepHit = {
            val step = conductor.currentStep
            camera.onStep(step)
            stageProps?.onStep(step)
            // FNF's `HealthIcon.onStepHit` bops on every beat
            // (`bopEvery = Constants.STEPS_PER_BEAT`), not on the hits.
            if (step % Constants.STEPS_PER_BEAT == 0) {
                hud.bopIcons(conductor.stepLengthMs.toDouble())
            }
            // `danceEvery` counts beats and defaults to one, so everyone re-poses
            // on every quarter note.
            if (step % 4 == 0) onBeat()
        }

        nextEventIndex = 0
        resetForSongStart()
        startIntroCutscene()
        println("KNF: playing '${chart.songName}' [${chart.difficulty}] notes=${chart.notes.size} inst=$instPath")
        println(
            "KNF: layout scene=" + sceneContainer.size + " hud=" + hudContainer.size +
                " player=(" + playerStrumline.container.x + "," + playerStrumline.container.y + ")" +
                " opponent=(" + opponentStrumline.container.x + "," + opponentStrumline.container.y + ")" +
                " opponentLane=(" + opponentStrumline.container.x + "," + opponentStrumline.container.y + ")"
        )
        println(
            "KNF: chars dad='" + opponent.id + "'@" + opponent.x + "," + opponent.y +
                " " + opponent.localWidth + "x" + opponent.localHeight +
                " | gf=" + (girlfriend?.let {
                    "'" + it.id + "'@" + it.x + "," + it.y + " " + it.localWidth + "x" + it.localHeight
                } ?: "<none>") +
                " | bf='" + player.id + "'@" + player.x + "," + player.y +
                " " + player.localWidth + "x" + player.localHeight +
                " | camFocus=" + camera.focusX + "," + camera.focusY + " zoom=" + stageFile.cameraZoom
        )
        println(
            "KNF: skin scale=" + noteStyle.scale +
                " sustainWidth=" + noteStyle.sustainWidth() +
                " splash=" + noteStyle.splashSlices(0).size
        )
        println(
            "KNF: frames " + listOf(0, 1, 2, 3).joinToString(" | ") { d ->
                val names = listOf("LEFT", "DOWN", "UP", "RIGHT")[d]
                names + ": note=" + noteStyle.noteFrameName(d) +
                    " static=" + noteStyle.strumFrameName(d, "Static") +
                    " press=" + noteStyle.strumFrameName(d, "Press") +
                    " confirm=" + noteStyle.strumFrameName(d, "Confirm")
            }
        )
        if (System.getenv("KNF_DEBUG_TEXTURES") == "1") addTextureDebugStrip()
        debugEnabled = System.getenv("KNF_DEBUG_FRAMES") == "1"
        debugFrames = 0
    }

    override suspend fun SContainer.sceneMain() {
        sceneRoot = this
        addUpdater { dt -> tick(dt.dtSeconds()) }
    }

    override suspend fun sceneDestroy() {
        cutscene?.destroy()
        cutscene = null
        blackScreen?.removeFromParent()
        blackScreen = null
        music.stop()
        vocals.stop()
        opponentVocals.stop()
        pauseMusic.dispose()
        sfx.stopAll()
        playerStrumline.destroy()
        opponentStrumline.destroy()
    }

    // ======================================================================
    // setup helpers
    // ======================================================================

    /** Screen-space backdrop, drawn behind the camera layer. */
    private fun buildStageBackdrop(parent: Container) {
        val layer = Container()
        layer.solidRect(
            Constants.GAME_WIDTH * 2.0, Constants.GAME_HEIGHT * 2.0, Colors["#1b1030"],
        )
        layer.solidRect(
            Constants.GAME_WIDTH * 2.0, 140.0, Colors["#2a1b45"],
        ).apply { y = Constants.GAME_HEIGHT * 1.08 }

        val label = layer.text(
            "${chart.songName}\n${chart.artist}",
            textSize = 34.0,
            color = Colors["#5a4a7a"],
        )
        label.x = Constants.GAME_WIDTH / 2.0 - label.width / 2.0
        label.y = Constants.GAME_HEIGHT * 0.30
        parent.addChild(layer)
    }

    /**
     * Strumline placement, from `PlayState.initStrumlines`.
     *
     * For upscroll FNF simply parks the lane `STRUMLINE_Y_OFFSET` from the top;
     * downscroll lifts it by the lane's own height instead.
     */
    private fun layoutStrumlines() {
        // FNF:
        //   cutoutSize = FullScreenScaleMode.gameCutoutSize.x / 2.5
        //   player.x = FlxG.width / 2 + STRUMLINE_X_OFFSET + cutoutSize / 2
        //   opponent.x = STRUMLINE_X_OFFSET + cutoutSize
        // At 16:9 `gameCutoutSize.x` is zero, so the cutout must not move the
        // lanes. Using `GAME_WIDTH / 2.5 / 2` pushed both rows 128 px right.
        val cutout = 0.0
        val playerX = Constants.GAME_WIDTH / 2.0 + Constants.STRUMLINE_X_OFFSET + cutout / 2.0
        val opponentX = Constants.STRUMLINE_X_OFFSET.toDouble() + cutout

        val receptorH = playerStrumline.receptorHeight()
        val receptorOffsetY = noteStyle.strumlineOffsets.getOrElse(1) { 0.0 }
        val strumY = if (downscroll) {
            Constants.GAME_HEIGHT - receptorH - Constants.STRUMLINE_Y_OFFSET - receptorOffsetY
        } else {
            Constants.STRUMLINE_Y_OFFSET.toDouble()
        }

        for (lane in listOf(playerStrumline to playerX, opponentStrumline to opponentX)) {
            val (strum, x) = lane
            strum.container.x = x
            strum.container.y = strumY
            // Notes are children of the lane, so their y must stay lane-local.
            strum.strumlineY = 0f
        }
    }

    private fun resetForSongStart() {
        score = 0
        combo = 0
        hits = 0
        misses = 0
        tallies.reset()
        tallies.totalNotes = totalNotes
        songEnded = false
        ending = false
        endingTimer = 0.0
        endingTransitionStarted = false
        fadeOverlay = null
        startingSong = true
        paused = false
        countdownStep = -1
        countdownTimer = 0.0
        hudContainer.visible = true
        hudContainer.alpha = 1.0
        hudFadeElapsed = -1.0
        hudFadeDuration = 0.0
        cutscene?.destroy()
        cutscene = null
        cutsceneSound = null
        blackScreen?.removeFromParent()
        blackScreen = null
        music.volume = 1.0
        vocals.volume = 1.0
        opponentVocals.volume = 1.0
        playerStrumline.clean()
        opponentStrumline.clean()
        playerStrumline.setNoteData(chart.notes.filter { it.isPlayerNote })
        opponentStrumline.setNoteData(chart.notes.filter { !it.isPlayerNote })
        hud.resetHealth()
        hud.setScore(0)
        // `SongEventData.activated` is runtime state, but the chart (and its
        // event objects) is cached by SongEntry across scene changes. Without
        // clearing it, every event stays suppressed after a restart/replay and
        // the camera keeps staring at the opponent for the whole song.
        chart.events.forEach { it.activated = false }
        nextEventIndex = 0
        conductor.update(prePosition, false)
    }

    // ======================================================================
    // intro cutscenes
    // ======================================================================

    /**
     * `Song.onCountdownStart` in FNF: songs (and stage scripts) can cancel the
     * countdown and take over until they call `PlayState.startCountdown()`.
     */
    private suspend fun startIntroCutscene() {
        val intro = SongCutscenes.createIntro(
            song.id,
            chart.stage,
            chart.variation,
            PlayRequest.storyMode,
            CutsceneHostImpl(),
        ) ?: return
        cutscene = intro
        println("KNF: playing intro cutscene for '${song.id}'")
        intro.start()
    }

    /**
     * `PlayState.startCountdown`: the cutscene is over, so the HUD comes back
     * and the normal pre-song countdown starts from the beginning.
     *
     * FNF keeps updating `camera.zoom` from the stage's `cameraZoom` every
     * frame, which silently undoes the cutscene's direct `zoom = 2.5`. KNF
     * applies that reset explicitly here, just before the countdown.
     */
    private fun beginCountdown() {
        camera.setZoomImmediate(stageFile.cameraZoom)
        countdownStep = -1
        countdownTimer = 0.0
        // Restart note streaming from the top of the chart. A cutscene must
        // not leave partially spawned notes or an advanced `nextNoteIndex`
        // behind for the real countdown.
        playerStrumline.setNoteData(chart.notes.filter { it.isPlayerNote })
        opponentStrumline.setNoteData(chart.notes.filter { !it.isPlayerNote })
        conductor.update(prePosition, false)
    }

    /** FNF's `FlxTween.tween(camHUD, {alpha: 1.0}, ..., FlxEase.quadInOut)`. */
    private fun updateHudFade(dt: Double) {
        if (hudFadeElapsed < 0.0) return
        hudFadeElapsed += dt
        val duration = hudFadeDuration.coerceAtLeast(0.0001)
        val t = (hudFadeElapsed / duration).coerceIn(0.0, 1.0)
        hudContainer.alpha = quadInOut(t)
        if (t >= 1.0) hudFadeElapsed = -1.0
    }

    /** `FlxEase.quadInOut`. */
    private fun quadInOut(t: Double): Double =
        if (t < 0.5) 2.0 * t * t else 1.0 - (-2.0 * t + 2.0) * (-2.0 * t + 2.0) / 2.0

    /**
     * `Paths.sound`: the current stage library wins, then `shared`, then
     * `preload`. Winter Horrorland lives in `week5`, hence the stage directory
     * is what makes `Lights_Turn_On` resolve to `week5/sounds/...`.
     */
    private fun resolveStageSound(stem: String): String? {
        val candidates = buildList {
            if (stageFile.directory.isNotEmpty()) add("${stageFile.directory}/$stem")
            add("shared/$stem")
            add("preload/$stem")
            add(stem)
        }
        for (candidate in candidates) KnfFiles.resolveSound(candidate)?.let { return it }
        return null
    }

    private inner class CutsceneHostImpl : CutsceneHost {
        override fun setHudVisible(visible: Boolean) {
            hudContainer.visible = visible
        }

        override fun fadeHudIn(durationSeconds: Double) {
            hudContainer.visible = true
            hudContainer.alpha = 0.0
            hudFadeElapsed = 0.0
            hudFadeDuration = durationSeconds
        }

        override fun showBlackScreen() {
            hideBlackScreen()
            // `new FlxSprite(-200, -200).makeGraphic(width * 2, height * 2, BLACK)`
            // with `scrollFactor(0)` and zIndex 1,000,000.
            blackScreen = sceneRoot.solidRect(
                Constants.GAME_WIDTH * 2.0,
                Constants.GAME_HEIGHT * 2.0,
                Colors.BLACK,
            )
        }

        override fun hideBlackScreen() {
            blackScreen?.removeFromParent()
            blackScreen = null
        }

        override fun setBlackScreenAlpha(alpha: Double) {
            blackScreen?.alpha = alpha
        }

        override fun addCutsceneView(view: View) {
            // Added after the black screen, so it is the topmost layer.
            sceneRoot.addChild(view)
        }

        override fun removeCutsceneView(view: View) {
            view.removeFromParent()
        }

        override fun focusCamera(x: Double, y: Double, zoom: Double) {
            camera.setFocusImmediate(x, y)
            camera.setZoomImmediate(zoom)
        }

        override fun setCameraZoom(zoom: Double) {
            camera.setZoomImmediate(zoom)
        }

        override fun addStageView(view: View, zIndex: Int) {
            val stage = stageProps
            if (stage != null) stage.addCutsceneView(view, zIndex) else stageLayer.addChild(view)
        }

        override fun removeStageView(view: View) {
            val stage = stageProps
            if (stage != null) stage.removeCutsceneView(view) else view.removeFromParent()
        }

        override fun setCharacterVisible(slot: String, visible: Boolean) {
            characterForSlot(slot)?.visible = visible
        }

        override fun characterPosition(slot: String): Pair<Double, Double>? {
            val character = characterForSlot(slot) ?: return null
            // FNF's `BaseCharacter.x`/`.y` is `originalPosition`, before the
            // scaled global-offset nudge that `Bopper.getScreenPosition` adds
            // only while drawing. Stage scripts read that x/y directly.
            return character.stageX to character.stageY
        }

        override fun characterZIndex(slot: String): Int =
            stageFile.characterFor(slot)?.zIndex ?: 0

        override fun characterCameraFocus(slot: String): Pair<Double, Double>? {
            val character = characterForSlot(slot) ?: return null
            return character.cameraFocusPoint(stageFile.characterFor(slot)?.cameraOffsets.orEmpty())
        }

        override fun setOpponentVocalsVolume(volume: Double) {
            opponentVocals.volume = volume
        }

        override val cameraZoom: Double get() = camera.zoom
        override val cameraFocusX: Double get() = camera.focusX
        override val cameraFocusY: Double get() = camera.focusY
        override val stageZoom: Double get() = stageFile.cameraZoom

        override fun playCharacterAnimation(slot: String, animation: String, forceRestart: Boolean) {
            characterForSlot(slot)?.play(animation, forceRestart)
        }

        override fun danceCharacter(slot: String, forceRestart: Boolean) {
            characterForSlot(slot)?.dance(forceRestart)
        }

        override fun isCharacterAnimationFinished(slot: String): Boolean =
            characterForSlot(slot)?.animationFinished == true

        override fun setCharacterDancesOnBeat(slot: String, enabled: Boolean) {
            characterForSlot(slot)?.dancesOnBeat = enabled
        }

        private fun characterForSlot(slot: String): Character? = when (slot) {
            "bf" -> player
            "dad" -> opponent
            "gf" -> girlfriend
            else -> null
        }

        override suspend fun preloadSound(stem: String): String? = runCatching {
            val path = resolveStageSound(stem) ?: return@runCatching null
            path.takeIf { sfx.preload(it) != null }
        }.getOrNull()

        override suspend fun preloadMusic(stem: String): String? = runCatching {
            resolveStageSound(stem)
        }.getOrNull()

        override val advancePressed: Boolean get() = gameInput.accept

        override fun playSound(path: String, volume: Double) {
            cutsceneSound = sfx.play(path, volume)
        }

        override fun playSfx(path: String, volume: Double) {
            sfx.play(path, volume)
        }

        override fun isSoundPlaying(): Boolean = cutsceneSound?.playing == true
    }

    // ======================================================================
    // main loop
    // ======================================================================

    private fun tick(dt: Double) {
        wallClock += dt * 1000.0
        gameInput.update()

        // While the substate is open, only it is updated: PlayState freezes
        // gameplay, the stage and the conductor until it closes.
        val menu = pauseMenu
        if (menu != null) {
            menu.update(dt, gameInput)
            if (pauseMenu != null) {
                pauseMusicFade += dt
                pauseMusic.volume = PAUSE_MUSIC_VOLUME *
                    (pauseMusicFade / PAUSE_MUSIC_FADE_SECONDS).coerceIn(0.0, 1.0)
            }
            return
        }

        // `PlayState.handleCutsceneKeys`: conversations handle CUTSCENE_ADVANCE
        // before PAUSE, so ENTER advances a line instead of opening the menu.
        // Videos consume the full PAUSE action, including ENTER.
        val activeCutscene = cutscene
        if (activeCutscene != null) {
            val pauseMode = activeCutscene.pauseMode
            val pausePressed = when (pauseMode) {
                CutscenePauseMode.VIDEO -> gameInput.pause
                CutscenePauseMode.CONVERSATION -> gameInput.pauseOnly
                CutscenePauseMode.NONE -> false
            }
            if (pausePressed) {
                openCutscenePause(activeCutscene, pauseMode)
                return
            }
        }

        // `PlayState.pause`: no pausing during the countdown, after the song
        // ended, or while the results transition runs.
        if (cutscene == null && !startingSong && !songEnded && !ending && gameInput.pause) {
            openPause()
            return
        }

        handleOffsetKeys()

        if (paused) return

        // Characters advance before the conductor dispatches this frame's
        // step/beat events, matching `PlayState.update` where `super.update()`
        // runs before `Conductor.instance.update()`. With the opposite order a
        // non-looped animation that reaches its last frame on the same frame as
        // a beat is still marked unfinished when `dance()` runs, so the restart
        // slips to the following beat.
        opponent.update(dt)
        girlfriend?.update(dt)
        player.update(dt)
        for (support in speakerSupports) support.update(dt)

        if (!ending) {
            // SONG_END freezes gameplay. Ending videos still tick their own
            // clock, but the just-finished instrumental must not drag the
            // Conductor backwards when its channel is released.
            if (!songEnded) updateClock(dt)
            // FNF's song scripts cancel `onCountdownStart` and call
            // `PlayState.startCountdown()` when their cutscene is done. Until
            // then gameplay notes and the countdown are held back (the
            // original sets `isInCutscene`).
            val cutsceneToUpdate = cutscene
            if (cutsceneToUpdate != null) {
                if (cutsceneToUpdate.update(dt)) {
                    cutscene = null
                    val kind = cutsceneToUpdate.kind
                    cutsceneToUpdate.destroy()
                    when (kind) {
                        CutsceneKind.STARTING -> {
                            println("KNF: intro cutscene finished, starting countdown")
                            beginCountdown()
                        }
                        CutsceneKind.ENDING -> {
                            println("KNF: ending cutscene finished, ending the song")
                            endSong()
                        }
                    }
                }
            } else if (startingSong) {
                updateCountdown(dt)
            } else if (!songEnded) {
                processSongEvents()
                processInput()
                processNotes()
                checkSongEnd()
            }
        } else {
            updateEnding(dt)
        }

        updateHudFade(dt)
        camera.update(dt)
        for (layer in characterLayers) {
            layer.container.x = (1.0 - layer.scrollX) * camera.scrollX
            layer.container.y = (1.0 - layer.scrollY) * camera.scrollY
        }
        hud.popUps.beatLengthMs = conductor.beatLengthMs.toDouble()
        hud.update(dt)
        // `isInCutscene`: no gameplay notes may spawn or scroll while a
        // scripted cutscene owns the screen. Otherwise a ten-second video
        // consumes the first notes of the chart and the player sees them fly
        // past as soon as the song starts.
        if (cutscene == null) {
            playerStrumline.update(conductor, startTimestampMs.toFloat(), dt)
            opponentStrumline.update(conductor, startTimestampMs.toFloat(), dt)
        }

        stageProps?.update(dt, camera.scrollX, camera.scrollY)

        if (avsyncDebug && !startingSong) measureAvSync()
        if (System.getenv("KNF_DEBUG_CHAR") == "1" && debugFrames == 300) dumpCharacters()
        if (clockDebug && !startingSong) sampleClock(dt)

        if (debugEnabled && (debugFrames in 700..702 || debugFrames in 1500..1502)) {
            val n = playerStrumline.notes
            val child = n.firstOrNull()?.getChildAt(0)
            val img = child as? korlibs.korge.view.Image
            println(
                "KNF: t=" + conductor.songPosition.toInt() + "ms notes=" + n.size +
                    " y=" + n.take(4).joinToString { it.y.toInt().toString() } +
                    " vis=" + n.take(4).joinToString { it.visible.toString() }
            )
            println(
                "KNF: child=" + child?.javaClass?.simpleName + " w=" + child?.width +
                    " h=" + child?.height + " sx=" + img?.scaleX + " alpha=" + child?.alpha +
                    " bmp=" + img?.bitmap + " parent=" + child?.parent?.javaClass?.simpleName
            )
        }
        debugFrames++
    }

    /**
     * Live audio-offset adjustment (`[` / `]`). Every machine has a different
     * output latency, and no engine clock can guess it — FNF exposes this in its
     * options for the same reason.
     */
    private fun handleOffsetKeys() {
        var changed = false
        if (gameInput.offsetDown) {
            conductor.globalOffset -= PlayRequest.OFFSET_STEP_MS
            changed = true
        }
        if (gameInput.offsetUp) {
            conductor.globalOffset += PlayRequest.OFFSET_STEP_MS
            changed = true
        }
        if (!changed) return
        val ms = conductor.globalOffset
        Save.globalOffsetMs = ms
    }

    // ======================================================================
    // pause menu
    // ======================================================================

    /**
     * `PlayState.pause()` -> `PauseSubState`. The overlay lives on top of the
     * gameplay scene so the stage keeps its last frame visible behind it.
     */
    private fun openPause() {
        if (pauseMenu != null) return
        paused = true

        val menu = PauseMenu(
            songName = chart.songName,
            artistName = chart.artist,
            difficulty = chart.difficulty,
            difficulties = song.difficultiesFor(chart.variation),
            offsetMs = conductor.globalOffset,
            onScroll = { sfx.play(scrollSound, 0.4) },
            onResume = { closePause() },
            onRestartSong = { restartSong() },
            onChangeDifficulty = { difficulty -> restartSong(difficulty) },
            onExitToMenu = { exitToSongSelect() },
        )
        pauseMenu = menu
        sceneRoot.addChild(menu)

        // `openSubState`: the instrumental and both vocal stems stop where they
        // are; `closeSubState` resumes them from the same position.
        music.pause()
        vocals.pause()
        opponentVocals.pause()

        startPauseMusic()
    }

    /**
     * `PauseSubState` in `Cutscene`/`Conversation` mode. Song stems are not
     * touched because an intro cutscene runs before the countdown and an ending
     * cutscene has already muted them; only the cutscene's own audio pauses.
     */
    private fun openCutscenePause(active: SongCutscene, pauseMode: CutscenePauseMode) {
        if (pauseMenu != null) return
        val menuMode = when (pauseMode) {
            CutscenePauseMode.VIDEO -> PauseMenuMode.CUTSCENE
            CutscenePauseMode.CONVERSATION -> PauseMenuMode.CONVERSATION
            CutscenePauseMode.NONE -> return
        }
        paused = true

        val menu = PauseMenu(
            songName = chart.songName,
            artistName = chart.artist,
            difficulty = chart.difficulty,
            difficulties = song.difficultiesFor(chart.variation),
            offsetMs = conductor.globalOffset,
            onScroll = { sfx.play(scrollSound, 0.4) },
            onResume = {
                active.resume()
                closeCutscenePause()
            },
            onRestartSong = { restartSong() },
            onChangeDifficulty = { difficulty -> restartSong(difficulty) },
            onExitToMenu = { exitToSongSelect() },
            initialMode = menuMode,
            onSkip = {
                active.skip()
                // `closeSubState` resumes a conversation after skipping so the
                // outro's music fade plays. Video audio was already stopped by
                // `finishVideo`.
                if (pauseMode == CutscenePauseMode.CONVERSATION) active.resume()
                closeCutscenePause()
            },
            onRestart = {
                active.restart()
                closeCutscenePause()
            },
        )
        pauseMenu = menu
        sceneRoot.addChild(menu)

        active.pause()
        startPauseMusic()
        println("KNF: cutscene pause menu ($pauseMode)")
    }

    private fun startPauseMusic() {
        pauseMusic.loop = true
        pauseMusicGeneration++
        val generation = pauseMusicGeneration
        launch {
            if (!pauseMusic.load(resolvePauseMusic())) return@launch
            if (generation != pauseMusicGeneration) return@launch
            pauseMusicFade = 0.0
            pauseMusic.volume = 0.0
            // `startPauseMusic`: start somewhere in the first half.
            val half = (pauseMusic.totalMs / 2.0).toInt().coerceAtLeast(0)
            pauseMusic.play(Random.nextInt(0, half + 1).toDouble())
        }
    }

    /** Close a cutscene pause menu without resuming the song stems. */
    private fun closeCutscenePause() {
        if (pauseMenu == null) return
        pauseMusicGeneration++
        pauseMenu?.removeFromParent()
        pauseMenu = null
        paused = false
        pauseMusic.stop()
        println("KNF: cutscene pause closed")
    }

    /** `PauseSubState.resume`: close the overlay and unpause the song. */
    private fun closePause() {
        if (pauseMenu == null) return
        pauseMusicGeneration++
        pauseMenu?.removeFromParent()
        pauseMenu = null
        paused = false
        pauseMusic.stop()
        // Restart all three stems from the instrumental's paused sample. Their
        // own positions can differ by a whole WaveOut buffer (~90 ms), and the
        // JVM backend's per-channel pause leaves those queues frozen, so
        // resuming each track where it happened to stop makes the vocals drift
        // from the instrumental after every pause.
        val resumeAt = music.pausedAtMs
        music.play(resumeAt)
        vocals.play(resumeAt)
        opponentVocals.play(resumeAt)
    }

    /** `PauseSubState.restartPlayState`: rebuild the scene from `PlayRequest`. */
    private fun restartSong(difficulty: String? = null) {
        pauseMenu?.removeFromParent()
        pauseMenu = null
        // Keep the scene frozen until the replacement is ready.
        paused = true
        pauseMusicGeneration++
        pauseMusic.stop()
        if (difficulty != null) PlayRequest.difficulty = difficulty
        launch { sceneContainer.changeTo<PlayScene>() }
    }

    /** `PauseSubState.quitToMenu`: back to the song list, as the results screen does. */
    private fun exitToSongSelect() {
        pauseMenu?.removeFromParent()
        pauseMenu = null
        paused = true
        pauseMusicGeneration++
        pauseMusic.stop()
        launch { sceneContainer.changeTo<SongSelectScene>() }
    }

    /**
     * Advances the Conductor.
     *
     * KorGE reports audio position with sub-millisecond accuracy, so unlike FNF
     * (which had to ease onto Lime's coarse `FlxSound.time`) we can follow the
     * audio clock directly. Easing onto it instead leaves a constant ~25 ms lag,
     * which is exactly the "notes look out of sync with the music" artefact.
     */
    private fun updateClock(dt: Double) {
        if (startingSong) {
            // FNF only advances the conductor while `isInCountdown` is true;
            // during a scripted intro it stays parked at `prePosition` until
            // `startCountdown()` resets it. Advancing the clock behind a movie
            // would consume chart notes before the song even starts.
            if (cutscene == null) {
                conductor.accumulateDelta(dt.toFloat())
                conductor.update(conductor.songPosition + (dt * 1000.0).toFloat(), false)
            }
            return
        }

        conductor.accumulateDelta(dt.toFloat(), music.pitch.toFloat())

        val target = music.positionMs.toFloat() + conductor.combinedOffset
        conductor.update(target, false)
    }

    private fun updateCountdown(dt: Double) {
        countdownTimer += dt
        val beat = (conductor.beatLengthMs / 1000.0).toDouble()
        val step = (countdownTimer / beat).toInt().coerceAtLeast(0)

        if (step != countdownStep && step <= 3) {
            countdownStep = step
            val label = when (step) {
                0 -> "three"
                1 -> "two"
                2 -> "one"
                else -> "go"
            }
            hud.showCountdown(label, conductor.beatLengthMs.toDouble())
            when (step) {
                0 -> sfx.play(countdownSounds[0], 0.6)
                1 -> sfx.play(countdownSounds[1], 0.6)
                2 -> sfx.play(countdownSounds[2], 0.6)
                else -> sfx.play(countdownSounds[3], 0.6)
            }
        }

        if (conductor.songPosition >= startTimestampMs.toFloat() + conductor.combinedOffset) {
            startSong()
        }
    }

    private fun startSong() {
        startingSong = false
        music.play(startTimestampMs)
vocals.play(startTimestampMs - conductor.instrumentalOffset)
        opponentVocals.play(startTimestampMs - conductor.instrumentalOffset)
        hud.showCountdown("go", conductor.beatLengthMs.toDouble())
    }

    private fun checkSongEnd() {
        val length = music.totalMs
        val reachedEnd = (length > 0 && music.positionMs >= length - 120) ||
            (forcedSongEndMs != null && music.positionMs >= forcedSongEndMs)
        if (reachedEnd) {
            if (forcedSongEndMs != null && length > 0 && music.positionMs < length - 120) {
                println("KNF: forced song end at ${music.positionMs.toInt()}ms (KNF_END_AT_MS)")
            }
            songEnded = true

            // FNF dispatches SONG_END before saving the score. Scripts can
            // cancel it, play an ENDING video, and call `endSong(true)` later.
            val endingCutscene = SongCutscenes.createEnding(
                song.id,
                chart.stage,
                chart.variation,
                PlayRequest.storyMode,
                CutsceneHostImpl(),
            )
            if (endingCutscene != null) {
                music.volume = 0.0
                vocals.volume = 0.0
                opponentVocals.volume = 0.0
                gameInput.reset()
                cutscene = endingCutscene
                println("KNF: playing ending cutscene for '${song.id}'")
                launch { endingCutscene.start() }
                return
            }

            endSong()
        }
    }

    /**
     * FNF's `PlayState.endSong` / `zoomIntoResultsScreen`.
     *
     * The music is muted rather than stopped (FNF does the same), the ending
     * transition is armed, and the score data is persisted for the results
     * screen. Gameplay is frozen from here on, so notes stop and the beat no
     * longer drives the characters' dance poses.
     */
    private fun endSong() {
        if (ending) return
        ending = true
        endingTimer = 0.0
        endingTransitionStarted = false

        music.volume = 0.0
        vocals.volume = 0.0
        opponentVocals.volume = 0.0
        gameInput.reset()

        tallies.totalNotes = totalNotes
        val rank = Scoring.calculateRank(tallies)
        val scoreKey = "${song.id}:${chart.difficulty}:${chart.variation}"
        val previousBest = Save.highscore(scoreKey)
        val isNewHighscore = Save.submitHighscore(scoreKey, score)
        Save.setSongRating(scoreKey, rank.key)

        if (avsyncDebug && avcount > 0) {
            val avg = avsum / avcount
            println("KNF: AVSYNC notes=$avcount avg=${"%.2f".format(avg)}ms audio-vs-chart")
            println("KNF: AVSYNC conductor=${conductor.songPosition}ms audio=${music.positionMs}ms offset=${conductor.combinedOffset}ms")
        }
        println(
            "KNF: song finished $rank (${(tallies.completion * 100).roundToInt()}%), " +
                "score $score (best was $previousBest, new=$isNewHighscore)"
        )

        // FNF follows GF (or BF when there is no GF) with targetOffset (20, -350).
        // Flixel's `updateFollow` adds `targetOffset` to the target midpoint, so
        // the same sign applies here (earlier this was inverted).
        camera.bopEnabled = false
        camera.tweenZoomTo(camera.zoom)
        camera.followRate = 0.05
        val dadIsGf = opponent.localWidth > 0.0 && opponent.id == "gf"
        val gf = girlfriend
        val follow = when {
            (gf == null || gf.localWidth <= 0.0) && !dadIsGf ->
                player.cameraFocusPoint(stageFile.characterFor("bf")?.cameraOffsets.orEmpty())
            dadIsGf ->
                opponent.cameraFocusPoint(stageFile.characterFor("dad")?.cameraOffsets.orEmpty())
            else ->
                gf!!.cameraFocusPoint(stageFile.characterFor("gf")?.cameraOffsets.orEmpty())
        }
        camera.setFocus(follow.first + 20.0, follow.second - 350.0)

        // Fade the HUD, then fade the whole screen to black over 0.6 s.
        val overlay = Container()
        sceneRoot.addChild(overlay)
        fadeOverlay = overlay.solidRect(
            Constants.GAME_WIDTH * 2.0,
            Constants.GAME_HEIGHT * 2.0,
            Colors.BLACK,
        ).apply { alpha = 0.0 }

        ResultRequest.data = ResultData(
            songId = song.id,
            title = "${chart.songName} by ${chart.artist}",
            difficultyId = chart.difficulty,
            variationId = chart.variation,
            characterId = chart.characters.player,
            score = score,
            tallies = tallies.copy(),
            isNewHighscore = isNewHighscore,
        )
    }

    private fun updateEnding(dt: Double) {
        endingTimer += dt
        val t = (endingTimer / ENDING_FADE_SECONDS).coerceIn(0.0, 1.0)
        hudContainer.alpha = 1.0 - t
        fadeOverlay?.alpha = t

        if (!endingTransitionStarted && endingTimer >= ENDING_FADE_SECONDS) {
            endingTransitionStarted = true
            launch { sceneContainer.changeTo<ResultScene>() }
        }
    }

    /**
     * Compares three clocks: real time since the instrumental started, the
     * position KorGE reports for it, and the Conductor derived from that.
     *
     * If `audio` runs ahead of `wall` the conductor is driving the notes from a
     * clock that is ahead of what the speakers are actually emitting, which
     * reads as "the arrows arrive before the music".
     */
    private fun sampleClock(dt: Double) {
        clockSampleTimer += dt
        if (clockSampleTimer < 0.5) return
        clockSampleTimer = 0.0
        // Anchor on the frame the instrumental was started, not on the first sample.
        if (songStartWallMs < 0.0) songStartWallMs = wallClock
        val wall = wallClock - songStartWallMs
        val audio = music.positionMs
        val lead = playerStrumline.notes.minOfOrNull { it.strumTime.toDouble() } ?: -1.0
        println(
            "KNF: CLOCK wall=" + "%.0f".format(wall) +
                "ms audio=" + "%.0f".format(audio) +
                "ms drift=" + "%.0f".format(audio - wall) +
                "ms cond=" + "%.0f".format(conductor.songPosition.toDouble()) +
                "ms nextNote=" + "%.0f".format(lead) +
                "ms ahead=" + "%.0f".format(lead - conductor.songPosition.toDouble())
        )
    }

    // ======================================================================
    // input
    // ======================================================================

    private fun processInput() {
        for (lane in 0 until Constants.KEY_COUNT) {
            if (gameInput.justPressed(lane)) onPress(lane)
            if (gameInput.justReleased(lane)) onRelease(lane)
        }
    }

    private fun onPress(lane: Int) {
        // FNF's strumline plays the press animation from its update loop, as
        // soon as the key is held:
        //
        // ```haxe
        // if (isKeyHeld(dir) && getByDirection(dir).getCurrentAnimation() == 'static') {
        //     playPress(dir);
        // }
        // ```
        //
        // so the arrow sinks the moment you press it, hit or miss.
        playerStrumline.pressKey(lane)

        val candidates = playerStrumline.notesMayHit(lane)
        if (candidates.isEmpty()) {
            // FNF's `Strumline.mayGhostTap`: a tap into empty air only counts
            // when no note is in range and none was hit just before.
            if (playerStrumline.mayGhostTap()) ghostTap(lane)
            return
        }
        val note = candidates.first()
        goodNoteHit(note)
        // FNF's `Strumline.hitNote`: the confirm animation and the splash.
        playerStrumline.hitNote(lane)
    }

    private fun onRelease(lane: Int) {
        playerStrumline.releaseKey(lane)
    }

    // ======================================================================
    // note processing
    // ======================================================================

    /** Debug helper: where each character actually ended up on screen. */
    private fun dumpCharacters() {
        for ((label, ch) in listOfNotNull("dad" to opponent, girlfriend?.let { "gf" to it }, "bf" to player)) {
            val box = ch.getBounds()
            val vis = ch.children.count { it.visible }
            println(
                "KNF: charpos " + label + " id=" + ch.id +
                    " local=" + ch.x + "," + ch.y +
                    " boundsLocal=" + box.x + "," + box.y + " " + box.width + "x" + box.height +
                    " onScreen=" + box.left + "," + box.top + " " + box.width + "x" + box.height +
                    " children=" + ch.numChildren + " visible=" + vis +
                    " alpha=" + ch.alpha + " scale=" + ch.scaleX + "," + ch.scaleY
            )
        }
        println("KNF: charpos camera focus=" + camera.focusX + "," + camera.focusY + " zoom=" + camera.zoom)
    }

    /**
     * Per-beat character behaviour.
     *
     * FNF's `BaseCharacter.onBeatHit` re-poses everyone (`danceEvery`, one beat
     * by default). Unlike KNF used to, it does not make the girlfriend sing on
     * her own: her `sing` animations belong to scripted events only.
     */
    private fun onBeat() {
        if (player.dancesOnBeat) player.dance()
        if (opponent.dancesOnBeat) opponent.dance()
        girlfriend?.let { if (it.dancesOnBeat) it.dance() }
        for (support in speakerSupports) support.onBeat()
    }

    private fun goodNoteHit(note: NoteSprite) {
        val timingError = conductor.songPosition - note.strumTime
        val absError = abs(timingError)

        if (avsyncDebug && !startingSong) {
            // Audio clock at the exact moment the note's strum time was reached.
            val audioNow = music.positionMs.toFloat() + conductor.combinedOffset
            avsum += (audioNow - note.strumTime - timingError).toDouble()
            avcount++
        }
        val judgement = Scoring.judgeNote(absError)

        note.hasBeenHit = true
        note.judgement = judgement
        note.timingErrorMs = timingError
        // FNF's `hitNote` -> `killNote`: the arrow disappears on impact.
        note.hideHead()

        score += Scoring.scoreNote(absError)
        hits++
        when (judgement) {
            Judgement.SICK -> tallies.sick++
            Judgement.GOOD -> tallies.good++
            Judgement.BAD -> tallies.bad++
            Judgement.SHIT -> tallies.shit++
            Judgement.MISS -> tallies.missed++
        }
        tallies.totalNotesHit++

        // FNF's `applyScore`: BAD and SHIT break the combo, everything else
        // extends it. The tallies are authoritative; the local `combo` is just
        // a mirror used by the HUD.
        if (judgement == Judgement.BAD || judgement == Judgement.SHIT) {
            if (tallies.combo >= 10) hud.showComboBreak()
            tallies.combo = 0
        } else {
            tallies.combo++
            if (tallies.combo > tallies.maxCombo) tallies.maxCombo = tallies.combo
        }
        combo = tallies.combo

        hud.setScore(score)
        hud.showJudgement(judgement)
        hud.showCombo(combo)
        hud.addHealth(Scoring.healthDelta(judgement))

        // FNF's BF behaviour: strike the matching `singDIR` pose on every hit.
        if (note.isPlayerNote) player.sing(note.directionIndex, conductor.stepLengthMs.toDouble())

        // FNF's `HitNoteScriptEvent.doesNotesplash` defaults to `daRating == 'sick'`,
        // and PlayState only calls `playerStrumline.playNoteSplash` when that
        // event flag is set. The opponent never gets a splash.
        if (judgement == Judgement.SICK) playerStrumline.playNoteSplash(note.directionIndex)

        if (note.isHoldNote) {
            note.startClip()
            // FNF's `PlayState.processNoteHit`:
            // `if (note.isHoldNote && note.holdNoteSprite != null) playerStrumline.playNoteHoldCover(note.holdNoteSprite);`
            playerStrumline.playNoteHoldCover(note.directionIndex)
        }
    }

    private fun ghostTap(lane: Int) {
        // FNF's ghost miss only takes health and score. It intentionally does
        // not touch the tallies (so it can't lower the results completion) and
        // does not reset the combo.
        score += Constants.SCORE_GHOST_MISS_PENALTY.toInt()
        hud.setScore(score)
        hud.addHealth(Constants.HEALTH_GHOST_MISS_PENALTY)
        // `GhostMissNoteScriptEvent.playAnim` defaults to true and
        // `BaseCharacter.onNoteGhostMiss` turns it into BF's miss pose.
        player.sing(lane, conductor.stepLengthMs.toDouble(), miss = true)
    }

    private fun processNotes() {
        if (PlayRequest.autoplay) autoPlayPlayerNotes()
        handleOpponentNotes()
        handleMisses()
        handleHolds()
        checkDeath()
    }

    /**
     * FNF's botplay: strikes every note exactly on its strum time.
     *
     * The bot holds the key down for the note's length and then lets go, so the
     * receptor goes back to its static pose instead of sticking on the pressed
     * one.
     */
    private fun autoPlayPlayerNotes() {
        val now = conductor.songPosition
        for (note in playerStrumline.notes) {
            if (note.hasBeenHit || note.hasMissed) continue
            if (now >= note.strumTime - 1f) {
                goodNoteHit(note)
                playerStrumline.pressKey(note.directionIndex)
                playerStrumline.hitNote(note.directionIndex)
                // Let go once the note (or its sustain) is over.
                val hold = if (note.isHoldNote) note.fullSustainLength else 60f
                autoReleaseAt[note.directionIndex] = note.strumTime + hold
            } else if (now >= note.strumTime - 60f) {
                playerStrumline.pressKey(note.directionIndex)
            }
        }
        for (lane in autoReleaseAt.keys.toList()) {
            val at = autoReleaseAt[lane] ?: continue
            if (now >= at) {
                autoReleaseAt.remove(lane)
                playerStrumline.releaseKey(lane)
            }
        }
    }

    private fun handleOpponentNotes() {
        for (note in opponentStrumline.notes) {
            if (note.mayHit && !note.hasBeenHit && !note.hasMissed) {
                note.hasBeenHit = true
                note.timingErrorMs = conductor.songPosition - note.strumTime
                note.hideHead()
                opponentStrumline.hitNote(note.directionIndex)
                // FNF's DAD behaviour: the CPU poses on every note it lands.
                opponent.sing(note.directionIndex, conductor.stepLengthMs.toDouble())
                if (note.isHoldNote) {
                    note.startClip()
                    // FNF's opponent branch mirrors the player strumline:
                    // `opponentStrumline.playNoteHoldCover(note.holdNoteSprite);`
                    opponentStrumline.playNoteHoldCover(note.directionIndex)
                }
            }
        }
    }

    private fun handleMisses() {
        for (note in playerStrumline.notes) {
            if (note.hasMissed && !note.handledMiss) {
                note.handledMiss = true
                combo = 0
                misses++
                tallies.missed++
                tallies.combo = 0
                score += Constants.PBOT1_MISS_SCORE
                hud.setScore(score)
                hud.addHealth(Constants.HEALTH_MISS_PENALTY)
                hud.showJudgement(Judgement.MISS)
                // `BaseCharacter.onNoteMiss` plays the matching miss pose for
                // every player note that leaves the window.
                player.sing(note.directionIndex, conductor.stepLengthMs.toDouble(), miss = true)
                // FNF hides the tail of a dropped hold note:
                // `holdNote.missedNote = true; holdNote.visible = false;`
                note.killTrail()
                // FNF breaks the combo counter: `if (combo >= 10) comboPopUps.displayCombo(0);`
                hud.showComboBreak()
                sfx.play(missSound, 0.35)
            }
        }
        for (note in opponentStrumline.notes) {
            if (note.hasMissed && !note.handledMiss) {
                note.handledMiss = true
                // The opponent flubbing it is good for us.
                hud.addHealth(-Constants.HEALTH_MISS_PENALTY)
            }
        }
    }

    private fun handleHolds() {
        val now = conductor.songPosition
        for (note in playerStrumline.notes) {
            if (!note.isHoldNote) continue
            val lane = note.directionIndex
            if (note.isHolding) {
                // Check the key *before* `holdConfirm`. FNF's hold-drop branch
                // calls `playStatic`, so confirming first would leave the
                // receptor stuck on the confirm/press pose after an early
                // release.
                if (!playerStrumline.isKeyHeld(lane)) {
                    playerStrumline.playStatic(lane)
                    note.releaseClip(conductor)
                    playerStrumline.endNoteHoldCover(lane)
                    continue
                }
                // FNF's `Strumline.holdConfirm`: the receptor keeps the confirm
                // pose for as long as the note is held.
                playerStrumline.holdConfirm(lane)
                val remaining = note.fullSustainLength - (now - note.strumTime)
                if (remaining <= 0f) {
                    note.releaseClip(conductor)
                    playerStrumline.endNoteHoldCover(lane)
                    // FNF: a completed hold leaves the receptor pressed while the
                    // key is still down, otherwise it returns to static.
                    if (playerStrumline.isKeyHeld(lane)) playerStrumline.pressKey(lane)
                    else playerStrumline.playStatic(lane)
                    continue
                }
                // FNF grants a small health drip while the tail is held.
                hud.addHealth(Constants.HEALTH_HOLD_BONUS_PER_SECOND * (1.0 / 60.0).toFloat())
                score += 10
                hud.setScore(score)
            }
        }
    }

    private fun checkDeath() {
        if (hud.isDead() && !songEnded) {
            songEnded = true
            music.pause()
            vocals.pause()
        opponentVocals.pause()
            println("KNF: died at ${(conductor.songPosition / 1000.0).roundToInt()}s")
        }
    }

    // ======================================================================
    // song events
    // ======================================================================

    private fun processSongEvents() {
        val now = conductor.songPosition
        while (nextEventIndex < chart.events.size) {
            val ev = chart.events[nextEventIndex]
            if (ev.time > now) break
            nextEventIndex++
            if (!ev.activated) {
                ev.activated = true
                handleSongEvent(ev)
            }
        }
    }

    private fun handleSongEvent(ev: SongEventData) {
        when (ev.kind) {
            "FocusCamera" -> {
                // Legacy charts (week 7 among them) store a bare `"v": 1`
                // instead of `{"char": 1}`. FocusCameraSongEvent falls back to
                // `cast data.value`; without that fallback every event was
                // interpreted as char 0, pinning the camera to BF.
                val char = ev.getInt("char")
                    ?: (ev.value as? kotlinx.serialization.json.JsonPrimitive)?.content?.toIntOrNull()
                    ?: 0
                val px = ev.getFloat("x") ?: 0f
                val py = ev.getFloat("y") ?: 0f
                val duration = ev.getFloat("duration") ?: 4f
                focusCamera(char, px, py, duration)
            }

            "ScrollSpeed" -> {
                var scroll = ev.getFloat("scroll") ?: 1f
                val absolute = ev.getBool("absolute") ?: false
                if (!absolute) scroll *= chart.scrollSpeed
                val target = ev.getString("strumline") ?: "both"
                if (target == "both" || target == "player") playerStrumline.resetScrollSpeed(scroll)
                if (target == "both" || target == "opponent") opponentStrumline.resetScrollSpeed(scroll)
            }

            "ZoomCamera" -> {
                val zoom = (ev.getFloat("zoom") ?: 1f).toDouble()
                val stageRelative = (ev.getString("mode") ?: "direct") == "stage"
                camera.tweenZoomTo(zoom, relativeToStage = stageRelative)
            }

            "SetCameraBop" -> {
                val intensity = ev.getFloat("intensity") ?: 1.0
                val rate = ev.getInt("rate") ?: Constants.DEFAULT_ZOOM_RATE
                val offset = ev.getInt("offset") ?: Constants.DEFAULT_ZOOM_OFFSET
                camera.setBop(intensity.toDouble(), rate, offset)
            }

            "SetTargetBopSpeed", "SetCharacter", "SetStage", "PlayAnimation", "SetHealthIcon" -> {
                // Needs character sprites; handled in a later milestone.
            }

            else -> println("KNF: unhandled song event '${ev.kind}'")
        }
    }

    /**
     * FNF targets: 0 = player, 1 = opponent, 2 = girlfriend, -1 = fixed position.
     *
     * The focus is the character's own `cameraFocusPoint` (its centre plus the
     * stage's per-character camera offsets), all in stage space.
     */
    private fun focusCamera(char: Int, x: Float, y: Float, durationBeats: Float) {
        val target: Pair<Double, Double> = when (char) {
            0 -> player.cameraFocusPoint(stageFile.characterFor("bf")?.cameraOffsets.orEmpty())
            1 -> opponent.cameraFocusPoint(stageFile.characterFor("dad")?.cameraOffsets.orEmpty())
            2 -> girlfriend?.cameraFocusPoint(stageFile.characterFor("gf")?.cameraOffsets.orEmpty())
                ?: return // FNF: "No GF to focus on."
            else -> defaultCameraFocus
        }
        val dx = if (char < 0) x.toDouble() else 0.0
        val dy = if (char < 0) y.toDouble() else 0.0
        camera.setFocus(target.first + dx, target.second + dy)
    }

    /**
     * Measures how far the audio clock leads the Conductor at the instant each
     * note's strum time is crossed. A non-zero average is the visual offset
     * players would perceive.
     */
    private fun measureAvSync() {
        val nowC = conductor.songPosition
        val nowA = music.positionMs.toFloat() + conductor.combinedOffset
        val dC = nowC - prevConductorMs
        val dA = nowA - prevAudioMs

        if (dC > 0.01f) {
            for (note in playerStrumline.notes) {
                if (note.hasBeenHit || note.hasMissed) continue
                if (note.strumTime < prevConductorMs || note.strumTime > nowC) continue
                val frac = ((nowC - note.strumTime) / dC).coerceIn(0f, 1f)
                val audioAtNote = prevAudioMs + dA * frac
                avsum += (audioAtNote - note.strumTime).toDouble()
                avcount++
            }
        }
        prevConductorMs = nowC
        prevAudioMs = nowA
    }

    /** Temporary: draws each note-skin bitmap whole (unsliced) to isolate slicing bugs. */
    private fun addTextureDebugStrip() {
        val strip = Container()
        hudContainer.addChild(strip)
        strip.x = 20.0
        strip.y = 200.0

        val whole = listOf(
            "shared/images/notes.png",
            "shared/images/NOTE_hold_assets.png",
        )
        var x = 0.0
        for (path in whole) {
            val bmp = KnfFiles.bitmapOrNull(path) ?: continue
            strip.image(bmp).also { img ->
                img.scaleX = 0.35
                img.scaleY = 0.35
                img.x = x
            }
            // ...and the same bitmap cut down to a single frame, for comparison.
            strip.image(bmp.sliceWithSize(0, 0, (bmp.width / 4), (bmp.height / 4))).also { img ->
                img.scaleX = 0.35
                img.scaleY = 0.35
                img.x = x
                img.y = 140.0
            }
            x += 200.0
        }
    }

    private val variation: String get() = PlayRequest.variation

    private companion object {
        /** FNF's `camTransition.fade(..., 0.6, ...)` and HUD fade. */
        const val ENDING_FADE_SECONDS = 0.6

        /** `PauseSubState.MUSIC_FINAL_VOLUME` / `MUSIC_FADE_IN_TIME`. */
        const val PAUSE_MUSIC_VOLUME = 0.75
        const val PAUSE_MUSIC_FADE_SECONDS = 5.0
    }

    /**
     * `PauseSubState.startPauseMusic` plays
     * `breakfast$musicSuffix/breakfast$musicSuffix`. The suffix is assigned by
     * character scripts when they are created (`bf-pixel.hxc` -> `-pixel`,
     * Pico's scripts -> `-pico`); FNF constructs GF, dad, then BF, so the last
     * matching character wins.
     */
    private fun pauseMusicSuffix(): String {
        var suffix = ""
        val chars = chart.characters
        for (id in listOf(chars.girlfriend, chars.opponent, chars.player)) {
            when (id) {
                "bf-pixel", "pico-pixel" -> suffix = "-pixel"
                "pico-playable", "pico-blazin", "pico-holding-nene" -> suffix = "-pico"
            }
        }
        return suffix
    }

    /**
     * `Paths.music`: the current stage library wins, then `shared`, then
     * `preload`. `LoadingState` sets that library from the stage's `directory`,
     * which is why Week 6 resolves `week6/music/breakfast-pixel`.
     */
    private fun resolvePauseMusic(): String {
        val suffix = pauseMusicSuffix()
        val stem = "breakfast$suffix/breakfast$suffix"
        val candidates = buildList {
            if (stageFile.directory.isNotEmpty()) add("${stageFile.directory}/music/$stem")
            add("shared/music/$stem")
            add("preload/music/$stem")
            add("music/$stem")
        }
        for (candidate in candidates) KnfFiles.resolveSound(candidate)?.let { return it }
        return "shared/music/$stem.mp3"
    }

    /** Picks the playable audio file for an extension-less stem. */
    private fun resolveSfx(stem: String): String = KnfFiles.resolveSound(stem) ?: "$stem.mp3"
}
