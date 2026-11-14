package nightfunkin.play

import korlibs.image.color.Colors
import korlibs.image.color.RGBA
import korlibs.image.bitmap.sliceWithSize
import korlibs.korge.render.RenderContext
import korlibs.korge.scene.Scene
import korlibs.korge.view.Container
import korlibs.korge.view.SContainer
import korlibs.korge.view.SolidRect
import korlibs.korge.view.View
import korlibs.korge.view.addUpdater
import korlibs.korge.view.filter.filter
import korlibs.korge.view.image
import korlibs.korge.view.solidRect
import korlibs.korge.view.text
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import nightfunkin.audio.MusicPlayer
import nightfunkin.audio.SfxChannel
import nightfunkin.audio.SfxPlayer
import nightfunkin.audio.dtSeconds
import korlibs.math.geom.degrees
import nightfunkin.WindowBop
import nightfunkin.core.Constants
import nightfunkin.core.KnfFiles
import nightfunkin.core.KnfFmt
import nightfunkin.core.KnfInput
import nightfunkin.core.Knf
import nightfunkin.core.KnfMemory
import nightfunkin.core.KnfTouch
import nightfunkin.core.guardedSceneInit
import nightfunkin.core.NoteStyle
import nightfunkin.core.Save
import nightfunkin.data.SongDifficulty
import nightfunkin.data.SongEntry
import nightfunkin.data.SongEventData
import nightfunkin.data.SongRegistry
import nightfunkin.platform.KnfEnv
import nightfunkin.platform.KnfPlatform
import nightfunkin.platform.KnfProfiler
import nightfunkin.timing.Conductor
import nightfunkin.ui.SongSelectScene
import nightfunkin.ui.StickerOverlay
import nightfunkin.ui.StickerSceneTarget
import nightfunkin.ui.TouchControls
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.time.DurationUnit
import kotlin.time.TimeSource
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

    /** On-screen controls on touch devices; null on desktop without `KNF_TOUCH`. */
    private var touchControls: TouchControls? = null

    /**
     * Touch targets for the mobile "Arrows" scheme, in stage coordinates.
     * Empty on the desktop layout (the overlay then uses its default zones).
     */
    private var touchZones: List<TouchControls.LaneZone> = emptyList()

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
    private var cutsceneSound: SfxChannel? = null
    private var blackScreen: SolidRect? = null

    /** HUD fade-in started by a cutscene; negative when no fade is running. */
    private var hudFadeElapsed = -1.0
    private var hudFadeDuration = 0.0

    /**
     * `tankmanBattlefieldErect.hxc`'s saved `tankmanRim`: the opponent's rim
     * light, whose alt mask the `EnableMask` song event turns on.
     */
    private var tankmanRimFilter: DropShadowRimFilter? = null

    // ---- game over (`GameOverSubState`) ----------------------------------

    private var gameOverActive = false
    private var gameOverEnding = false

    /** `isStarting`: a `gameOverStart` track plays once before the loop. */
    private var gameOverStarting = false

    /** `blueballed`: set when the loss SFX plays, gates both inputs. */
    private var gameOverBlueBalled = false
    private var gameOverAnimStarted = false
    private var gameOverLoopStarted = false

    /**
     * `bf.hxc`'s `fakeoutDeath` easter egg (1/4096): `fakeout_death` plays, then
     * `firstDeath` restarts and only then does the blue-ball SFX play.
     */
    private var gameOverFakeout = false
    private var fakeoutSoundPath: String? = null
    private var gameOverStartCountdown = 0.0
    private var gameOverConfirmElapsed = 0.0
    private var gameOverConfirmFadeDelay = 0.0
    private var gameOverWorldLayer: Container? = null
    private var gameOverPlayerLayer: CharacterLayer? = null
    private var gameOverMusicGeneration = 0
    private val gameOverMusic = MusicPlayer()

    /**
     * `GameOverSubState.deathQuoteSound` / `hasPlayedDeathQuote`: the
     * character's `getDeathQuote()` track (BF and Pico insult Tankman). The
     * random pick is made at scene start so the clip can be preloaded.
     */
    private var deathQuotePath: String? = null
    private var deathQuoteChannel: SfxChannel? = null

    /** `startDeathMusic(startingVolume)`; lowered while a quote plays. */
    private var deathMusicTargetVolume = 1.0

    /** FNF's `isSongEnd`: the results transition is running. */
    private var ending = false
    private var endingTimer = 0.0
    private var endingTransitionStarted = false
    private var fadeOverlay: SolidRect? = null
    private var startTimestampMs = 0.0
    private var prePosition = 0f

    private var exitingViaStickers = false

    private var countdownStep = -1
    private var countdownTimer = 0.0
    private val clockDebug = KnfEnv["KNF_DEBUG_CLOCK"] == "1"
    /** KorGE clamps `dt`, so the song needs a real monotonic clock. */
    private val realClock = TimeSource.Monotonic.markNow()
    private var wallClock = 0.0

    /**
     * Audible song time in ms (instrumental content position).
     *
     * `MusicPlayer.positionMs` is the number of samples *generated into the
     * output queue*, not the sample reaching the speakers; the backends buffer
     * 40-100 ms ahead. The audio thread plays in real time independently of
     * rendering, so a monotonic clock advanced from [wallClock] is the clock
     * the player actually hears.
     */
    private var songClockMs = 0.0
    private var lastSongWallMs = -1.0
    private var clockSampleTimer = 0.0

    /** When [startSong] fired; used to detect an output that never starts. */
    private var audioStartWatchMs = -1.0

    /** Last audio-restart attempt, and how many were made for this song. */
    private var lastAudioRecoveryMs = -1.0
    private var audioRecoveryAttempts = 0

    /**
     * When the stems were last (re)started.
     *
     * `SoundChannel.state.playing` can report `false` for the first frames
     * after `play()` (the output is opened asynchronously on the audio thread).
     * Without this grace window that looked like a dead stem on the very first
     * frame of the song - `recoverDeadAudio()` then rebuilt all three
     * `AudioTrack`s, which is exactly the half-second freeze players saw on
     * mobile right as the first note arrived.
     */
    private var lastStemsStartWallMs = -1.0

    private fun markStemsStarted() {
        lastStemsStartWallMs = wallClock
    }

    /** Frames slower than this are logged to the boot log (bounded count). */
    private var slowFrameLogs = 0

    /**
     * `KNF_DEBUG_SLOW=1` writes a `KNF_SLOW` line for every frame that runs
     * long. Off for normal play: it is a diagnostic, not a player-facing log.
     */
    private val debugSlowFrames: Boolean get() = KnfEnv["KNF_DEBUG_SLOW"] == "1"

    /** `KNF_DEBUG_HIT=1` logs the first judged hits with their per-step cost. */
    private val debugHitFrames: Boolean get() = KnfEnv["KNF_DEBUG_HIT"] == "1"

    /** Wall clock of the previous frame, for the gap measurement in `sceneMain`. */
    private var lastTickWallMs = -1.0

    /**
     * Per-phase timings of the last `tick`, in ms.
     *
     * A stall inside the game update has to be attributed to a phase, otherwise
     * the only data is "the frame took 260 ms" - which is exactly what the
     * first-note freeze looked like.
     */
    private var phaseStart = 0.0
    private var phaseMenuMs = 0.0
    private var phaseCharsMs = 0.0
    private var phaseClockMs = 0.0
    private var phaseNotesMs = 0.0
    private var phaseHudMs = 0.0
    private var phaseStrumMs = 0.0
    private var phaseStageMs = 0.0

    /** True while the stems are still opening their devices. */
    private fun stemsStarting(): Boolean =
        lastStemsStartWallMs >= 0.0 && wallClock - lastStemsStartWallMs < STEM_START_GRACE_MS

    private fun realNowMs(): Double = realClock.elapsedNow().toDouble(DurationUnit.MILLISECONDS)

    private var nextEventIndex = 0

    /** Test hook: `KNF_END_AT_MS=6000` treats the song as finished at 6 s. */
    private val forcedSongEndMs = KnfEnv["KNF_END_AT_MS"]?.toDoubleOrNull()

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
     *
     * The touch "Arrows" control scheme forces downscroll
     * (`Strumline.isDownscroll`), because the enlarged player lane lives at the
     * bottom of the screen.
     */
    private val touchLayout = KnfPlatform.isMobile
    private val downscroll = touchLayout

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
    private val avsyncDebug = KnfEnv["KNF_DEBUG_AVSYNC"] == "1"
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

    override suspend fun SContainer.sceneInit() = guardedSceneInit("PlayScene") {
        runSceneInit()
    }

    private suspend fun SContainer.runSceneInit() {
        // Available here as well as in sceneMain: cutscenes need to mount their
        // black screen while sceneInit is still running.
        sceneRoot = this
        // KorGE initialises this scene *before* destroying the old one, so the
        // outgoing scene's art and a retried song's decoded stems would still
        // be alive while this scene loads its own. Release both first: the
        // overlap is what used to exhaust the heap on a phone.
        releasePreviousSongAudio()
        KnfMemory.beginScene(views, "PlayScene")
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
            // `schoolErect.hxc`'s GF case runs `character.addSunsetShaders()`
            // for Nene (pixel), which lights the whole A-Bot rig.
            val support = SpeakerSupport.forCharacter(
                character,
                music,
                sunset = stageFile.id == "schoolErect" && character.id == "nene-pixel",
            )
            val stage = stageProps
            val scroll = stageFile.characterFor(slot)?.scroll.orEmpty()
            val layer = Container().also { it.addChild(character) }
            characterLayers += CharacterLayer(
                character = character,
                container = layer,
                scrollX = scroll.getOrElse(0) { 1.0 },
                scrollY = scroll.getOrElse(1) { 1.0 },
            )
            applyStageCharacterEffects(slot, character, layer)
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
        KnfMemory.log(views, "stage+chars")

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
            arrowsScheme = touchLayout,
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

        // Decode every note-style asset (splash sheets, judgements, hold
        // strips) now instead of lazily mid-song: the first Sick hit used to
        // freeze the GL thread for half a second while the splash sheet
        // decoded on a mobile CPU.
        withContext(Dispatchers.Default) { noteStyle.warmup() }

        // ---- audio -------------------------------------------------------
        val instPath = song.instrumentalPath(PlayRequest.variation)
        if (instPath == null || !music.load(instPath, streaming = false)) {
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
                vocals.load(path, streaming = false)
                println("KNF: vocals player " + path)
            }
            song.vocalsPath(chars.opponent, PlayRequest.variation)?.let { path ->
                opponentVocals.load(path, streaming = false)
                println("KNF: vocals opponent " + path)
            }
        }
        // If this scene is replaced (retry, quit) the next PlayScene runs its
        // `sceneInit` while this one is still alive; it disposes these players
        // before decoding its own stems so two songs' PCM never coexist.
        previousSongPlayers = listOf(music, vocals, opponentVocals)
        KnfMemory.log(views, "stems")

        sfx.preload(resolveSfx("shared/sounds/gameplay/countdown/funkin/introTHREE"))
        sfx.preload(resolveSfx("shared/sounds/gameplay/countdown/funkin/introTWO"))
        sfx.preload(resolveSfx("shared/sounds/gameplay/countdown/funkin/introONE"))
        sfx.preload(resolveSfx("shared/sounds/gameplay/countdown/funkin/introGO"))
        missSound = resolveSfx("shared/sounds/missnote1")
        sfx.preload(missSound)
        scrollSound = resolveSfx("preload/sounds/scrollMenu")
        sfx.preload(scrollSound)

        // Preload the death loss SFX and the character's death quote so
        // `playLossSfx()` / `playDeathQuote()` can start them synchronously
        // from `tick()` (non-suspend context) instead of relying on a launch
        // block whose suspend preload may not complete before `play()` runs.
        val deathSfxPath = resolveDeathSfx(gameOverSuffixes().second)
        deathSfxPath?.let { sfx.preload(it) }
        deathQuotePath = resolveDeathQuote()?.also { sfx.preload(it) }
        fakeoutSoundPath = if (player.id == "bf" && player.hasAnimation("fakeoutDeath")) {
            resolveStageSound("sounds/gameplay/gameover/fakeout_death")
        } else {
            null
        }
        fakeoutSoundPath?.let { sfx.preload(it) }
        if (KnfEnv["KNF_DEBUG_DEATH"] == "1") {
            println("KNF: death sfx=[$deathSfxPath] quote=[$deathQuotePath] fakeout=[$fakeoutSoundPath]")
        }

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
        if (KnfEnv["KNF_DEBUG_TEXTURES"] == "1") addTextureDebugStrip()
        debugEnabled = KnfEnv["KNF_DEBUG_FRAMES"] == "1"
        debugFrames = 0

        // Upload the skin's textures last, right before the scene is shown.
        // `AgBitmapTextureManager` deletes any texture that has not been drawn
        // for 60 frames, and it runs on the *outgoing* scene's frames while the
        // stems decode. Doing this earlier meant the whole note skin (splash
        // sheet included, ~38 MB) was dropped during loading and had to be
        // uploaded again on the first rendered frame of the song - a measured
        // 1.3 s stall at the point the first note arrives.
        val prewarmStart = realNowMs()
        runCatching { noteStyle.prewarmTextures(views.renderContext) }
        println("KNF: note textures prewarmed in ${(realNowMs() - prewarmStart).roundToInt()}ms")
        KnfMemory.log(views, "noteStyle")
        // After `warmup` so the keeper never triggers a lazy atlas decode on
        // the render thread. Keeps the textures on the GPU for the whole song.
        addChild(NoteStyleTextureKeeper(noteStyle))

        // Everything the first judged hit can create for the first time is
        // created here, while loading still covers the screen: the judgement /
        // combo popup images, the four splash layers and one hidden splash per
        // direction, the receptor Press/Confirm animations and the characters'
        // sing poses. The first Sick was the only hit that could pay for a lazy
        // first-time step on the GL thread (texture binding, view creation,
        // program use), which is what a single ~0.5 s freeze at that one hit
        // looks like.
        runCatching { hud.prewarmPopUps() }
            .onFailure { println("KNF: popup prewarm failed: $it") }
        runCatching { playerStrumline.prewarmHitEffects() }
            .onFailure { println("KNF: splash prewarm failed: $it") }
        runCatching {
            player.prewarmHitPoses()
            opponent.prewarmHitPoses()
        }.onFailure { println("KNF: pose prewarm failed: $it") }

        // Free the decode garbage (compressed bytes, the decoder's sample
        // arrays) now, while the previous scene is still alive but the heap is
        // at its peak. The previous scene itself only becomes garbage later:
        // `sceneAfterInit` collects that.
        KnfMemory.endScene(views, "PlayScene")
    }

    /**
     * Per-stage shaders on the characters, as their stage scripts apply them.
     *
     * Both stages that use `DropShadowShader` do it in `addCharacter`, so every
     * character gets the stage's rim light (plus its Adjust Color pass) and, for
     * most of them, an alternate mask that suppresses the rim over specific
     * parts of the sheet.
     */
    private fun applyStageCharacterEffects(slot: String, character: Character, layer: Container) {
        val rim = when (stageFile.id) {
            "tankmanBattlefieldErect" -> tankmanBattlefieldRim(slot, character)
            "schoolErect" -> schoolErectRim(slot, character)
            else -> null
        }
        if (rim != null) {
            layer.filter = rim
            // The filter draws its render target with this sampling mode. A
            // pixel character is upscaled 6x *before* the filter, so bilinear
            // here would blur the whole sprite; nearest keeps both the art and
            // the rim's texel steps crisp, like FNF's shader on the raw sheet.
            rim.filtering = !character.isPixel
            // `DropShadowShader.attachedSprite` + `updateFrameInfo`: the alt
            // masks are painted over the character's *atlas*, so the filter
            // needs the atlas rect of every frame the character draws.
            if (rim.usesAltMask) character.bindRimMask(rim)
        }
    }

    /** `tankmanBattlefieldErect.hxc`'s `addCharacter` (Week 7 Erect). */
    private fun tankmanBattlefieldRim(slot: String, character: Character): DropShadowRimFilter? = when (slot) {
        "bf" -> stageRimFilter(character, angle = 90.0)
        "gf" -> stageRimFilter(character, angle = 90.0).also { f ->
            // gf-tankmen / nene-tankmen sit on the tankmen speakers, where
            // the plain drop shadow picks up conflicting colors and needs
            // the alternate mask (`useAltMask = true` right away).
            when (character.id) {
                "gf-tankmen" -> f.setAltMask("erect/masks/gfTankmen_mask", 0.4, use = true)
                "nene-tankmen" -> f.setAltMask("erect/masks/neneTankmen_mask", 0.4, use = true)
            }
        }
        "dad" -> stageRimFilter(character, angle = 25.0, threshold = 0.3).also { f ->
            // tankman-bloody's mask starts off; the `EnableMask` song event
            // turns it on mid-song.
            if (character.id == "tankman-bloody") {
                f.setAltMask("erect/masks/tankmanCaptainBloody_mask", 1.0, use = false)
                tankmanRimFilter = f
            }
        }
        else -> null
    }

    /**
     * `schoolErect.hxc`'s `addCharacter` (Week 6 Erect: Senpai / Roses).
     *
     * The stage's mood is a warm brown light from above: a much stronger Adjust
     * Color (`-66, -10, 24, -23`), a dark brown rim (`0xFF52351D`) at only 5 px,
     * and masks that switch the rim *off* (`maskThreshold = 1`) over the parts
     * the script excluded.
     */
    private fun schoolErectRim(slot: String, character: Character): DropShadowRimFilter? = when (slot) {
        "bf" -> stageRimFilter(
            character,
            angle = 90.0,
            distance = 5.0,
            lightColor = SCHOOL_ERECT_LIGHT,
            brightness = -66.0,
            hue = -10.0,
            contrast = 24.0,
            saturation = -23.0,
        ).also { f ->
            f.setAltMask(
                if (character.id == "pico-pixel") {
                    "weeb/erect/masks/picoPixel_mask"
                } else {
                    "weeb/erect/masks/bfPixel_mask"
                },
                1.0,
                use = true,
            )
        }
        "gf" -> stageRimFilter(
            character,
            angle = 90.0,
            distance = 3.0,
            threshold = 0.3,
            lightColor = SCHOOL_ERECT_LIGHT,
            brightness = -42.0,
            hue = -10.0,
            contrast = 5.0,
            saturation = -25.0,
        ).also { f ->
            f.setAltMask(
                if (character.id == "nene-pixel") {
                    "weeb/erect/masks/nenePixel_mask"
                } else {
                    "weeb/erect/masks/gfPixel_mask"
                },
                1.0,
                use = true,
            )
        }
        "dad" -> stageRimFilter(
            character,
            angle = 90.0,
            distance = 5.0,
            lightColor = SCHOOL_ERECT_LIGHT,
            brightness = -66.0,
            hue = -10.0,
            contrast = 24.0,
            saturation = -23.0,
        ).also { f -> f.setAltMask("weeb/erect/masks/senpai_mask", 1.0, use = true) }
        else -> null
    }

    /** `rim.setAdjustColor(...)` + the stage's `rim.color`; tankman defaults. */
    private fun stageRimFilter(
        character: Character,
        angle: Double,
        /**
         * `DropShadowShader.distance` is in *source texture* pixels. The filter
         * runs on the already-scaled render target, so a 6x pixel character
         * needs a 6x larger offset to keep the same on-screen rim.
         */
        distance: Double = 15.0,
        threshold: Double = 0.1,
        lightColor: RGBA = DropShadowRimFilter.DEFAULT_LIGHT_COLOR,
        brightness: Double = -46.0,
        hue: Double = -38.0,
        contrast: Double = -25.0,
        saturation: Double = -20.0,
    ): DropShadowRimFilter = DropShadowRimFilter(
        angle = angle,
        distance = distance * abs(character.scaleX).coerceAtLeast(0.0001),
        threshold = threshold,
        lightColor = lightColor,
        adjustBrightness = brightness,
        adjustHue = hue,
        adjustContrast = contrast,
        adjustSaturation = saturation,
    )

    /**
     * Runs after the outgoing scene has been destroyed - the first moment its
     * views, atlases and bitmaps are actually garbage.
     *
     * KorGE's `SceneContainer._changeTo` starts the incoming `sceneInit`, then
     * `sceneMain` (undispatched!), then `sceneBeforeLeaving`/`sceneDestroy` of
     * the old scene, and only then `sceneAfterInit`. So the collection at the
     * end of `sceneInit` cannot free Freeplay: `KNF_MEM` showed 360/384 MB
     * after a Freeplay entry against 183/207 MB on a direct boot, with the
     * difference being the still-live Freeplay scene, not a leak. By this hook
     * that scene is gone and one collection brings the song back to the direct
     * boot footprint, before the countdown ends - instead of a blocking GC
     * landing on the first notes.
     */
    override suspend fun sceneAfterInit() {
        KnfMemory.endScene(views, "PlayScene after transition")
    }

    override suspend fun SContainer.sceneMain() {
        sceneRoot = this
        // One-off stalls (a lazily decoded sheet, a device open) are invisible
        // in averages; sample this thread's stack so they get a name in the
        // boot log. Off by default, `KNF_WATCHDOG=1` turns it on.
        if (KnfPlatform.isMobile && KnfEnv["KNF_WATCHDOG"] == "1") {
            runCatching { KnfProfiler.startWatchdog("GLThread") }
                .onFailure { println("KNF: watchdog start failed: $it") }
        }
        addUpdater { dt ->
            // A stall can happen inside `tick` or between frames (render pass,
            // GC pause). Post-frame deltas alias both into one clamped `dt`, so
            // the wall-clock gap between frames is the honest measurement.
            val frameStart = realNowMs()
            val gap = if (lastTickWallMs >= 0.0) frameStart - lastTickWallMs else 0.0
            lastTickWallMs = frameStart
            tick(dt.dtSeconds())
            KnfProfiler.frame()
            val spent = realNowMs() - frameStart
            if (
                debugSlowFrames &&
                (spent >= SLOW_FRAME_LOG_MS || gap >= SLOW_FRAME_LOG_MS) &&
                slowFrameLogs < SLOW_FRAME_LOG_LIMIT
            ) {
                slowFrameLogs++
                println(
                    "KNF_SLOW gap=" + gap.roundToInt() + "ms spent=" + spent.roundToInt() +
                        "ms song=" + songClockMs.roundToInt() + "ms starting=" + startingSong +
                        " inst=" + music.playing + "/" + music.exists +
                        " vocals=" + vocals.playing + "/" + vocals.exists +
                        " opponent=" + opponentVocals.playing + "/" + opponentVocals.exists +
                        " | phases menu=" + phaseMenuMs.roundToInt() +
                        " chars=" + phaseCharsMs.roundToInt() +
                        " clock=" + phaseClockMs.roundToInt() +
                        " notes=" + phaseNotesMs.roundToInt() +
                        " hud=" + phaseHudMs.roundToInt() +
                        " strum=" + phaseStrumMs.roundToInt() +
                        " stage=" + phaseStageMs.roundToInt()
                )
            }
        }

        // Added in sceneMain so it sits above everything sceneInit creates
        // (cutscene black screens included). It re-asserts itself on top every
        // frame, so runtime overlays like the pause menu stay below it.
        if (KnfTouch.enabled) {
            val touch = TouchControls()
            touch.laneZones = touchZones
            touch.menuMode = false
            addChild(touch)
            touchControls = touch
        }
    }

    override suspend fun sceneDestroy() {
        WindowBop.reset(views)
        cutscene?.destroy()
        cutscene = null
        blackScreen?.removeFromParent()
        blackScreen = null
        // `dispose` (not `stop`): a stopped player still holds the fully
        // decoded PCM (~30 MB per stem). The scene switch happens long before
        // this object is collected, so freeing the samples here is what keeps
        // the next scene from decoding on top of them.
        music.dispose()
        vocals.dispose()
        opponentVocals.dispose()
        if (previousSongPlayers.contains(music)) previousSongPlayers = emptyList()
        gameOverMusic.dispose()
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
     * Strumline placement, from `PlayState.initStrumlines` /
     * `PlayState.initNoteHitbox`.
     *
     * For upscroll FNF simply parks the lane `STRUMLINE_Y_OFFSET` from the top;
     * downscroll lifts it by the lane's own height instead. On touch devices
     * the "Arrows" scheme replaces that with the mobile layout: a large,
     * spread-out player lane at the bottom and a mini opponent lane in the
     * top-left corner.
     */
    private fun layoutStrumlines() {
        if (touchLayout) {
            layoutTouchStrumlines()
            return
        }
        // FNF:
        //   cutoutSize = FullScreenScaleMode.gameCutoutSize.x / 2.5
        //   player.x = FlxG.width / 2 + STRUMLINE_X_OFFSET + cutoutSize / 2
        //   opponent.x = STRUMLINE_X_OFFSET + cutoutSize
        // At 16:9 `gameCutoutSize.x` is zero, so the cutout does not move the
        // lanes. On a wide phone it spreads them towards the screen edges the
        // same way the original mobile build does.
        val cutout = Constants.GAME_CUTOUT_WIDTH / 2.5
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

    /**
     * `PlayState.initNoteHitbox`: the touch "Arrows" scheme.
     *
     * The player's lane is scaled to `1.95 * height / width` with a
     * `2.8 * height / width` pitch, which turns the four receptors into the
     * on-screen buttons; the opponent's lane collapses to `0.4` and its notes
     * are hidden (`Strumline.enterMiniMode`).
     *
     * `amplification` is FNF's `(width / height) / (initialWidth /
     * initialHeight)`; on a 16:9 game area it is exactly 1, on a wide phone it
     * spreads the buttons over the wider screen.
     */
    private fun layoutTouchStrumlines() {
        val amplification = (Constants.GAME_WIDTH.toDouble() / Constants.GAME_HEIGHT) /
            (Constants.BASE_WIDTH.toDouble() / Constants.BASE_HEIGHT)
        val laneScale = (Constants.GAME_HEIGHT.toDouble() / Constants.GAME_WIDTH) * 1.95 * amplification
        val spacing = (Constants.GAME_HEIGHT.toDouble() / Constants.GAME_WIDTH) * 2.8 * amplification

        playerStrumline.configureLanes(laneScale.toFloat(), spacing.toFloat())
        opponentStrumline.enterMiniMode((0.4 * amplification).toFloat())

        val playerX = (Constants.GAME_WIDTH - playerStrumline.laneWidth) / 2.0 +
            Constants.STRUMLINE_X_OFFSET
        var playerY = (Constants.GAME_HEIGHT - playerStrumline.receptorHeight()) * 0.95 -
            Constants.STRUMLINE_Y_OFFSET
        if (noteStyle.isPixel) {
            playerY -= 10.0
        } else if (KnfPlatform.isAndroid) {
            playerY += 10.0
        }

        playerStrumline.container.x = playerX
        playerStrumline.container.y = playerY
        playerStrumline.strumlineY = 0f

        opponentStrumline.container.x = Constants.STRUMLINE_X_OFFSET - 30.0
        opponentStrumline.container.y = Constants.STRUMLINE_Y_OFFSET * 0.3
        opponentStrumline.strumlineY = 0f

        // `FunkinHitbox`'s transparent note hints are invisible in FNF
        // (`hint.alpha = 0`); only their rectangles drive touch input.
        touchZones = playerStrumline.touchHints().map { hint ->
            TouchControls.LaneZone(
                lane = hint.lane,
                x = playerStrumline.container.x + hint.x,
                y = playerStrumline.container.y + hint.y,
                width = hint.width,
                height = hint.height,
            )
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
        playerStrumline.vwooshInNotes(conductor, startTimestampMs.toFloat())
        opponentStrumline.vwooshInNotes(conductor, startTimestampMs.toFloat())
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
        playerStrumline.vwooshInNotes(conductor, startTimestampMs.toFloat())
        opponentStrumline.vwooshInNotes(conductor, startTimestampMs.toFloat())
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
        wallClock = realNowMs()
        phaseStart = wallClock
        phaseMenuMs = 0.0
        phaseCharsMs = 0.0
        phaseClockMs = 0.0
        phaseNotesMs = 0.0
        phaseHudMs = 0.0
        phaseStrumMs = 0.0
        phaseStageMs = 0.0
        gameInput.update()
        // While an overlay owns the screen the touch overlay switches to
        // menu gestures (tap = accept, drags = navigation). `PlayState.endSong`
        // also hides the mobile buttons while the results transition runs.
        touchControls?.menuMode = pauseMenu != null || gameOverActive || ending

        // While the substate is open, only it is updated: PlayState freezes
        // gameplay, the stage and the conductor until it closes.
        val menu = pauseMenu
        if (menu != null) {
            // Gameplay is frozen here, so this update is the only thing the
            // frame does; without timing it a menu hitch shows up as "the
            // frame took 136 ms" with every gameplay phase at 0.
            val tMenu = realNowMs()
            if (!exitingViaStickers) menu.update(dt, gameInput)
            if (pauseMenu != null) {
                pauseMusicFade += dt
                pauseMusic.volume = PAUSE_MUSIC_VOLUME *
                    (pauseMusicFade / PAUSE_MUSIC_FADE_SECONDS).coerceIn(0.0, 1.0)
            }
            phaseMenuMs = realNowMs() - tMenu
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
            val t = realNowMs()
            openPause()
            phaseMenuMs = realNowMs() - t
            return
        }

        handleOffsetKeys()

        if (paused) return

        // `GameOverSubState` over a frozen PlayState: the death camera, the
        // dying pose and the retry/quit inputs, while everything else waits.
        if (gameOverActive) {
            updateGameOver(dt, gameInput)
            return
        }

        if (KnfEnv["KNF_DEBUG_DEATH"] == "1" && conductor.songPosition > 1000f) hud.setHealth(0f)

        // Characters advance before the conductor dispatches this frame's
        // step/beat events, matching `PlayState.update` where `super.update()`
        // runs before `Conductor.instance.update()`. With the opposite order a
        // non-looped animation that reaches its last frame on the same frame as
        // a beat is still marked unfinished when `dance()` runs, so the restart
        // slips to the following beat.
        val tChars = realNowMs()
        opponent.update(dt)
        girlfriend?.update(dt)
        player.update(dt)
        for (support in speakerSupports) support.update(dt)
        phaseCharsMs = realNowMs() - tChars

        if (!ending) {
            // SONG_END freezes gameplay. Ending videos still tick their own
            // clock, but the just-finished instrumental must not drag the
            // Conductor backwards when its channel is released.
            if (!songEnded) {
                val tClock = realNowMs()
                updateClock(dt)
                phaseClockMs = realNowMs() - tClock
            }
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
                val tNotes = realNowMs()
                processSongEvents()
                processInput()
                processNotes()
                checkSongEnd()
                phaseNotesMs = realNowMs() - tNotes
            }
        } else {
            updateEnding(dt)
        }

        val tHud = realNowMs()
        updateHudFade(dt)
        camera.update(dt)
        // FNF's camHUD zoom scales every HUD element around the screen centre;
        // shifting by `centre * (1 - zoom)` keeps the middle pinned.
        val hudZoom = camera.hudZoom
        hudContainer.scaleX = hudZoom
        hudContainer.scaleY = hudZoom
        hudContainer.x = Constants.GAME_WIDTH / 2.0 * (1.0 - hudZoom)
        hudContainer.y = Constants.GAME_HEIGHT / 2.0 * (1.0 - hudZoom)
        // The OS window bops on the very same value the HUD is scaled with, so
        // both pulses have the same timing and shape (see `WindowBop`).
        WindowBop.apply(views, hudZoom)
        for (layer in characterLayers) {
            layer.container.x = (1.0 - layer.scrollX) * camera.scrollX
            layer.container.y = (1.0 - layer.scrollY) * camera.scrollY
        }
        hud.popUps.beatLengthMs = conductor.beatLengthMs.toDouble()
        hud.update(dt)
        phaseHudMs = realNowMs() - tHud
        // `isInCutscene`: no gameplay notes may spawn or scroll while a
        // scripted cutscene owns the screen. Otherwise a ten-second video
        // consumes the first notes of the chart and the player sees them fly
        // past as soon as the song starts.
        if (cutscene == null) {
            val tStrum = realNowMs()
            playerStrumline.update(conductor, startTimestampMs.toFloat(), dt)
            opponentStrumline.update(conductor, startTimestampMs.toFloat(), dt)
            phaseStrumMs = realNowMs() - tStrum
        }

        val tStage = realNowMs()
        stageProps?.update(dt, camera.scrollX, camera.scrollY)
        phaseStageMs = realNowMs() - tStage

        if (avsyncDebug && !startingSong) measureAvSync()
        if (KnfEnv["KNF_DEBUG_CHAR"] == "1" && debugFrames == 300) dumpCharacters()
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
                "KNF: child=" + (child?.let { it::class.simpleName } ?: "null") + " w=" + child?.width +
                    " h=" + child?.height + " sx=" + img?.scaleX + " alpha=" + child?.alpha +
                    " bmp=" + img?.bitmap + " parent=" + (child?.parent?.let { it::class.simpleName } ?: "null")
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
        // are; `closeSubState` resumes them from the same audible position.
        // Advance the song clock to this frame first: the tick returns early
        // while the menu is open, so otherwise the last frame would be replayed
        // on resume.
        advanceSongClock()
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
            if (!pauseMusic.load(resolvePauseMusic(), streaming = true)) return@launch
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
        // The tick stood still while the overlay was open; don't bill that time
        // to the song clock (the stems themselves were not paused here, but a
        // conversation's own music may have been).
        lastSongWallMs = wallClock
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
        // `PlayState.closeSubState` resumes the stems and then calls
        // `resyncVocals()`. Restart all three from the audible position: the
        // channels' own last samples are *generated* samples, buffered ahead
        // of what was heard, so resuming from them would skip ~50-100 ms of
        // music and put every stem behind the arrows.
        val resumeAt = songClockMs
        music.play(resumeAt)
        vocals.play(resumeAt)
        opponentVocals.play(resumeAt)
        lastSongWallMs = wallClock
        markStemsStarted()
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

    /** `PauseSubState.quitToMenu`: sticker transition back to the song list. */
    private fun exitToSongSelect() {
        StickerSceneTarget.beginCover()
        exitingViaStickers = true
        // `PauseSubState.quitToMenu` opens the sticker substate OVER the live
        // pause menu; the scene switch happens once the screen is covered.
        val overlay = StickerOverlay(chart.characters.player, sfx) {
            launch {
                // FNF purges the old state's cache in `preStateSwitch`, i.e.
                // right here. The stems are tens of megabytes each and
                // `sceneDestroy` only runs *after* Freeplay has decoded its own
                // art, so without this both live through the transition's peak.
                runCatching {
                    music.dispose()
                    vocals.dispose()
                    opponentVocals.dispose()
                    gameOverMusic.dispose()
                }
                sceneContainer.changeTo<SongSelectScene>()
            }
        }
        sceneRoot.addChild(overlay)
    }

    /**
     * Advances [songClockMs] by the real time elapsed since the last frame.
     *
     * Playback is real-time on its own high-priority thread, so the audible
     * song position is exactly the wall clock. Unlike KorGE's frame delta it is
     * not clamped to 100 ms (`Views.clampElapsedTimeTo`), which is what made a
     * dt-integrated song clock fall behind on every freeze and never recover.
     */
    private fun advanceSongClock(force: Boolean = false) {
        if (!force && !music.playing) {
            lastSongWallMs = wallClock
            return
        }
        val now = wallClock
        if (lastSongWallMs >= 0.0) {
            val realDt = (now - lastSongWallMs).coerceIn(0.0, 5000.0) / 1000.0
            songClockMs += realDt * 1000.0 * music.pitch
        }
        lastSongWallMs = now
    }

    /** Keeps the chart running when no audio output is alive. */
    private fun advanceOnWallClock(dt: Double) {
        if (lastSongWallMs < 0.0) lastSongWallMs = wallClock
        advanceSongClock(force = true)
        conductor.accumulateDelta(dt.toFloat(), music.pitch.toFloat())
        conductor.update(songClockMs.toFloat() + conductor.combinedOffset, false)
    }

    /**
     * Restarts the stems after their output died.
     *
     * The chart itself never waits for this: every stem is restarted from
     * [songClockMs], which keeps running on the wall clock, so the music simply
     * rejoins the song wherever it is. Attempts are throttled and capped so a
     * permanently broken device does not spawn an `AudioTrack` every frame.
     */
    private fun recoverDeadAudio() {
        if (!music.exists || songEnded) return
        // The stems open their devices on the audio thread; a channel that has
        // not reported `playing` yet is not dead.
        if (stemsStarting()) return
        val length = music.totalMs
        if (length > 0.0 && songClockMs >= length - AUDIO_END_GUARD_MS) return
        if (audioRecoveryAttempts >= AUDIO_RECOVERY_LIMIT) return
        if (lastAudioRecoveryMs >= 0.0 &&
            wallClock - lastAudioRecoveryMs < AUDIO_RECOVERY_INTERVAL_MS
        ) {
            return
        }
        lastAudioRecoveryMs = wallClock
        audioRecoveryAttempts++
        println(
            "KNF: music output stopped at ${songClockMs.roundToInt()}ms, " +
                "restarting stems (attempt $audioRecoveryAttempts)"
        )
        val resumeAt = songClockMs
        music.play(resumeAt)
        vocals.play(resumeAt)
        opponentVocals.play(resumeAt)
        markStemsStarted()
    }

    /**
     * Restarts a vocal stem whose output died while the instrumental plays on.
     *
     * Called on every beat (`onBeat`), so a stem that cannot start is only
     * retried at beat rate - and only while it is not simply finished.
     */
    private fun rescueDeadVocals() {
        if (songEnded) return
        if (stemsStarting()) return
        val playerDead = vocals.exists && !vocals.playing && !stemFinished(vocals)
        val opponentDead = opponentVocals.exists && !opponentVocals.playing && !stemFinished(opponentVocals)
        if (!playerDead && !opponentDead) return
        if (lastAudioRecoveryMs >= 0.0 &&
            wallClock - lastAudioRecoveryMs < AUDIO_RECOVERY_INTERVAL_MS
        ) {
            return
        }
        lastAudioRecoveryMs = wallClock
        println("KNF: vocal stem stopped at ${songClockMs.roundToInt()}ms, restarting")
        if (playerDead) vocals.play(songClockMs)
        if (opponentDead) opponentVocals.play(songClockMs)
        markStemsStarted()
    }

    /** A stem that ran out of samples is finished, not broken. */
    private fun stemFinished(player: MusicPlayer): Boolean {
        val length = player.totalMs
        return length > 0.0 && songClockMs >= length - AUDIO_END_GUARD_MS
    }

    /**
     * Advances the Conductor from the audible song clock.
     *
     * FNF reads `FlxG.sound.music.time`, which OpenAL/lime report as the sample
     * actually being played. KorGE instead reports the sample *generated* into
     * the platform output's queue: the JVM WaveOut backend keeps up to eight
     * 512-sample headers queued (~93 ms), and Android's `AudioTrack` and iOS
     * CoreAudio buffer comparably. Following it made every note fire 50-100 ms
     * before the music was heard.
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

        if (!music.playing) {
            // The stems' output can stop without the song being over: an
            // `AudioTrack` invalidated by the audio server (route change,
            // reclaim, MIUI killing the focus) makes every write fail and the
            // pump exits, and a stem that never got a block at all leaves
            // `positionMs` parked at the countdown offset. FNF reads its clock
            // from the sound backend, so its arrows would simply freeze while
            // the characters keep idling - the exact symptom this fallback
            // removes. The wall clock keeps the chart moving and the stems
            // rejoin from it.
            if (music.exists) recoverDeadAudio()
            advanceOnWallClock(dt)
            return
        }

        if (lastSongWallMs < 0.0) {
            // The audio thread has not queued its first block yet. `play()`
            // returns immediately; `waveOutOpen` / `AudioTrack` start a few
            // milliseconds later. Anchoring before that would make every note
            // arrive by the device-open latency.
            if (music.positionMs <= startTimestampMs) {
                // ... but if it never does, do not sit at the countdown
                // forever: run on the wall clock and try the output again.
                if (audioStartWatchMs < 0.0) {
                    audioStartWatchMs = wallClock
                } else if (wallClock - audioStartWatchMs > AUDIO_START_TIMEOUT_MS) {
                    println("KNF: audio output never queued a block, using the wall clock")
                    audioStartWatchMs = -1.0
                    lastSongWallMs = wallClock
                    recoverDeadAudio()
                }
                return
            }
            audioStartWatchMs = -1.0
            lastSongWallMs = wallClock
            return
        }

        advanceSongClock()
        // Sub-frame interpolation for smooth scrolling; the authoritative
        // position remains the wall clock.
        conductor.accumulateDelta(dt.toFloat(), music.pitch.toFloat())
        conductor.update(songClockMs.toFloat() + conductor.combinedOffset, false)
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
        songClockMs = startTimestampMs
        // Anchor when the audio thread submits its first buffer (see
        // `updateClock`): `play` starts the output asynchronously, and starting
        // the wall clock now would count the device-open latency as song time.
        lastSongWallMs = -1.0
        audioStartWatchMs = -1.0
        lastAudioRecoveryMs = -1.0
        audioRecoveryAttempts = 0
        music.play(startTimestampMs)
        vocals.play(startTimestampMs - conductor.instrumentalOffset)
        opponentVocals.play(startTimestampMs - conductor.instrumentalOffset)
        markStemsStarted()
        hud.showCountdown("go", conductor.beatLengthMs.toDouble())
        // No `resyncVocals()` here. `SoundChannel.current` is the decoder's
        // read cursor, while the samples already queued in the output keep
        // playing; seeking one stem to the other's (block-quantized) position
        // can therefore move it by a whole 2048-sample block (~46 ms), which
        // then stays for the whole song. All three stems already start at the
        // same sample, so there is nothing to align.
    }

    /**
     * `PlayState.resyncVocals` (`PlayState.hx:2724-2743`).
     *
     * Restarts the instrumental and both vocal stems from the same *audible*
     * song position, the same path `closePause` uses. An in-place
     * `SoundChannel.current` seek cannot do this: it moves only the decoder's
     * read cursor while the samples already queued in the output keep playing,
     * so it can land one 2048-sample block (~46 ms) off - the persistent
     * "one stem is a block ahead" offset this exists to remove. FNF can seek
     * `FlxSound.time` directly because its backends expose a real sample
     * position; KorGE does not.
     */
    private fun resyncVocals() {
        if (!music.playing) return

        val resumeAt = songClockMs
        music.pause()
        vocals.pause()
        opponentVocals.pause()
        music.play(resumeAt)
        vocals.play(resumeAt)
        opponentVocals.play(resumeAt)
        markStemsStarted()
    }

    private fun checkSongEnd() {
        val length = music.totalMs.takeIf { it > 0.0 } ?: fallbackSongLengthMs()
        // `positionMs` is the *generated* position: it counts the samples
        // handed to the platform output. The audible position lags by
        // `outputLeadMs` and freezes at `total - outputLeadMs` once the
        // channel runs out of samples, so it never reaches `length` - that
        // stopped every song from ever reaching the results screen.
        //
        // Ending on the generated position does not cut the tail: the last
        // `outputLeadMs` of samples are already queued in the device and keep
        // playing after `endSong` mutes the mixer. `songClockMs` is the
        // fallback when the output is dead or the instrumental never loaded,
        // so a silent song still reaches its results instead of hanging.
        val generated = maxOf(music.positionMs, songClockMs)
        val reachedEnd = (length > 0 && generated >= length - 120) ||
            (forcedSongEndMs != null && generated >= forcedSongEndMs)
        if (reachedEnd) {
            if (forcedSongEndMs != null && length > 0 && generated < length - 120) {
                println("KNF: forced song end at ${generated.toInt()}ms (KNF_END_AT_MS)")
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
        Save.setCompletion(scoreKey, tallies.completion)

        if (avsyncDebug && avcount > 0) {
            val avg = avsum / avcount
            println("KNF: AVSYNC notes=$avcount avg=${KnfFmt.decimals(avg, 2)}ms audible-vs-chart")
            println(
                "KNF: AVSYNC conductor=${conductor.songPosition}ms " +
                    "audible=${(songClockMs + conductor.combinedOffset)}ms " +
                    "generated=${music.positionMs}ms offset=${conductor.combinedOffset}ms"
            )
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

    /** Song length when the instrumental is missing: last note plus a short tail. */
    private fun fallbackSongLengthMs(): Double =
        (chart.notes.maxOfOrNull { it.time }?.toDouble()?.plus(1_500.0)) ?: 0.0

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
     * Compares the audible song clock, the position KorGE reports for the
     * instrumental, and the Conductor derived from the audible clock.
     *
     * `audio - wall` is the output queue: the samples generated but not yet
     * heard. It is expected to stay at ~50-100 ms and is not drift.
     */
    private fun sampleClock(dt: Double) {
        clockSampleTimer += dt
        if (clockSampleTimer < 0.5) return
        clockSampleTimer = 0.0
        val wall = songClockMs
        val audio = music.positionMs
        val heard = music.audiblePositionMs
        val lead = playerStrumline.notes.minOfOrNull { it.strumTime.toDouble() } ?: -1.0
        val playerVoice = if (vocals.playing) vocals.positionMs - audio else 0.0
        val opponentVoice = if (opponentVocals.playing) opponentVocals.positionMs - audio else 0.0
        println(
            "KNF: CLOCK wall=" + KnfFmt.decimals(wall, 0) +
                "ms audio=" + KnfFmt.decimals(audio, 0) +
                "ms heard=" + KnfFmt.decimals(heard, 0) +
                "ms lead=" + KnfFmt.decimals(music.outputLeadMs, 0) +
                "ms drift=" + KnfFmt.decimals(heard - wall, 0) +
                "ms cond=" + KnfFmt.decimals(conductor.songPosition.toDouble(), 0) +
                "ms nextNote=" + KnfFmt.decimals(lead, 0) +
                "ms ahead=" + KnfFmt.decimals(lead - conductor.songPosition.toDouble(), 0) +
                " pv=" + KnfFmt.decimals(playerVoice, 0) +
                " ov=" + KnfFmt.decimals(opponentVoice, 0)
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

        // `PlayState.beatHit` (PlayState.hx:1897-1921). All stems report the
        // same *generated* clock here, so the instrumental is the reference:
        // their queue-fill jitter cancels out and only a real drift remains.
        // The instrumental itself is driven by `updateClock`, so it is the only
        // stem never compared against anything.
        if (!startingSong && music.playing) {
            rescueDeadVocals()
            val length = music.totalMs
            if (length > 0.0) {
                val correctSync = min(length, max(0.0, music.positionMs))
                val playerError = if (vocals.playing) vocals.positionMs - correctSync else 0.0
                val opponentError = if (opponentVocals.playing) opponentVocals.positionMs - correctSync else 0.0
                if (
                    abs(playerError) > RESYNC_THRESHOLD_MS ||
                    abs(opponentError) > RESYNC_THRESHOLD_MS
                ) {
                    if (clockDebug) {
                        println(
                            "KNF: VOCALS NEED RESYNC player=${playerError.roundToInt()}ms " +
                                "opponent=${opponentError.roundToInt()}ms"
                        )
                    }
                    resyncVocals()
                }
            }
        }
    }

    private fun goodNoteHit(note: NoteSprite) {
        val hitStart = realNowMs()
        val timingError = conductor.songPosition - note.strumTime
        val absError = abs(timingError)

        if (avsyncDebug && !startingSong) {
            // Audio clock at the exact moment the note's strum time was reached.
            val audioNow = music.audiblePositionMs.toFloat() + conductor.combinedOffset
            avsum += (audioNow - note.strumTime - timingError).toDouble()
            avcount++
        }
        val judgement = Scoring.judgeNote(absError)

        note.hasBeenHit = true
        note.judgement = judgement
        note.timingErrorMs = timingError
        // FNF's `hitNote(note, removeNote = !event.isComboBreak)`: a BAD/SHIT
        // hit breaks the combo, so the arrow is kept on screen at half alpha
        // and 20% saturation instead of being killed on impact.
        if (judgement == Judgement.BAD || judgement == Judgement.SHIT) {
            note.dimAfterHit()
        } else {
            note.hideHead()
        }

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

        val scoreStart = realNowMs()
        hud.setScore(score)
        val scoreDone = realNowMs()
        hud.showJudgement(judgement)
        hud.showCombo(combo)
        hud.addHealth(Scoring.healthDelta(judgement))
        val hudDone = realNowMs()

        // FNF's BF behaviour: strike the matching `singDIR` pose on every hit.
        if (note.isPlayerNote) player.sing(note.directionIndex, conductor.stepLengthMs.toDouble())
        val singDone = realNowMs()

        // FNF's `HitNoteScriptEvent.doesNotesplash` defaults to `daRating == 'sick'`,
        // and PlayState only calls `playerStrumline.playNoteSplash` when that
        // event flag is set. The opponent never gets a splash.
        if (judgement == Judgement.SICK) playerStrumline.playNoteSplash(note.directionIndex)
        val splashDone = realNowMs()

        if (note.isHoldNote) {
            note.startClip()
            // FNF's `PlayState.processNoteHit`:
            // `if (note.isHoldNote && note.holdNoteSprite != null) playerStrumline.playNoteHoldCover(note.holdNoteSprite);`
            playerStrumline.playNoteHoldCover(note.directionIndex)
        }
        // The first hits are the only ones that can pay for a lazy first-time
        // cost, so time them once (bounded) - see `KNF_SLOW` for the frame view.
        // `KNF_DEBUG_HIT=1` logs every one of them, however cheap.
        if (debugHitFrames && hitTimingLogs < 3) {
            hitTimingLogs++
            val total = realNowMs() - hitStart
            println(
                "KNF_HIT total=" + total.roundToInt() + "ms score=" +
                    (scoreDone - scoreStart).roundToInt() + " hud=" +
                    (hudDone - scoreDone).roundToInt() + " sing=" +
                    (singDone - hudDone).roundToInt() + " splash=" +
                    (splashDone - singDone).roundToInt() + " hold=" +
                    (realNowMs() - splashDone).roundToInt() + "ms"
            )
        }
    }

    private var hitTimingLogs = 0

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
            println("KNF: died at ${(conductor.songPosition / 1000.0).roundToInt()}s")
            // `PlayState.startGameOver`: the instrumental and both stems stop.
            music.stop()
            vocals.stop()
            opponentVocals.stop()
            enterGameOver()
        }
    }

    // ======================================================================
    // game over (`GameOverSubState`)
    // ======================================================================

    /**
     * `GameOverSubState.create`: an opaque black screen over the whole game
     * with the dead character drawn above it at its world position, and the
     * camera gliding to the character's death focus point.
     */
    private fun enterGameOver() {
        if (gameOverActive) return
        gameOverActive = true
        gameOverStarting = true
        gameOverEnding = false
        gameOverBlueBalled = false
        gameOverAnimStarted = false
        gameOverLoopStarted = false
        gameOverFakeout = false
        gameOverStartCountdown = 0.0
        gameOverConfirmElapsed = 0.0
        gameOverConfirmFadeDelay = 0.0
        deathQuoteChannel = null
        deathMusicTargetVolume = 1.0
        tankmanRimFilter = null

        val overlay = sceneRoot.solidRect(
            Constants.GAME_WIDTH * 2.0,
            Constants.GAME_HEIGHT * 2.0,
            Colors.BLACK,
        ).also {
            it.x = -Constants.GAME_WIDTH / 2.0
            it.y = -Constants.GAME_HEIGHT / 2.0
        }

        // The world layer copies the gameplay camera's transform every frame,
        // so the character sits exactly where it was on the stage.
        val world = Container()
        sceneRoot.addChild(world)
        gameOverWorldLayer = world
        val playerLayer = characterLayers.firstOrNull { it.character === player }
        if (playerLayer != null) {
            playerLayer.container.removeFromParent()
            world.addChild(playerLayer.container)
        }
        gameOverPlayerLayer = playerLayer

        // `GameOverSubState.create`: `boyfriend.isDead = true` keeps the beat
        // handler from dancing over the death animation.
        player.isDead = true

        // `tankmanBattlefieldErect.hxc`'s `onGameOver`: the dead character
        // loses its rim light on this screen. `schoolErect.hxc` has no such
        // hook, so BF keeps its shader there.
        if (stageFile.id == "tankmanBattlefieldErect") playerLayer?.container?.filter = null

        // `setCameraTarget`: FNF starts from `resetCameraFocusPoint`'s center
        // (original position + half the idle frame), subtracts the character's
        // own and the stage's camera offsets - the character's own cancel
        // against the center - and adds the death offsets. Half the normal
        // follow rate; zoom = stage zoom * death zoom.
        val (cx, cy) = player.idleFrameCenter()
        val stageOffsets = stageFile.characterFor("bf")?.cameraOffsets.orEmpty()
        val deathOffsets = player.deathCameraOffsets
        val focusX = cx - stageOffsets.getOrElse(0) { 0.0 } + deathOffsets.getOrElse(0) { 0.0 }
        val focusY = cy - stageOffsets.getOrElse(1) { 0.0 } + deathOffsets.getOrElse(1) { 0.0 }
        camera.followRate = (Constants.DEFAULT_CAMERA_FOLLOW_RATE / 2.0).toDouble()
        camera.setFocus(focusX, focusY)
        camera.targetZoom = camera.stageZoom * player.deathCameraZoom
    }

    /** `GameOverSubState.update`: the dying pose, the music hand-off, inputs. */
    private fun updateGameOver(dt: Double, input: KnfInput) {
        // First frame: the dying pose and the "blue ball" SFX. `bf.hxc` rolls
        // the 1/4096 `fakeoutDeath` easter egg instead, which restarts
        // `firstDeath` (and plays the loss SFX) once it finishes.
        if (!gameOverAnimStarted) {
            gameOverAnimStarted = true
            val fakeout = fakeoutSoundPath
            if (fakeout != null && Random.nextDouble() < FAKEOUT_CHANCE) {
                gameOverFakeout = true
                sfx.play(fakeout, 1.0)
                player.play("fakeoutDeath", forceRestart = true)
            } else {
                if (player.hasAnimation("firstDeath")) player.play("firstDeath", forceRestart = true)
                playLossSfx()
            }
        }

        if (!gameOverEnding) {
            if (gameOverFakeout) {
                if (player.currentAnimationName?.startsWith("fakeoutDeath") == true && player.animationFinished) {
                    gameOverFakeout = false
                    if (player.hasAnimation("firstDeath")) player.play("firstDeath", forceRestart = true)
                    playLossSfx()
                }
            } else if (player.currentAnimationName?.startsWith("firstDeath") == true && player.animationFinished) {
                if (!gameOverLoopStarted) {
                    gameOverLoopStarted = true
                    val quote = deathQuotePath
                    if (quote != null) {
                        playDeathQuote(quote)
                    } else {
                        startDeathMusic(1.0)
                        if (player.hasAnimation("deathLoop")) player.play("deathLoop", forceRestart = true)
                    }
                }
            }
            // `startDeathMusic`'s onComplete: `gameOverStart` hands over to the
            // loop track when its one-shot run ends.
            if (gameOverStarting && gameOverStartCountdown > 0.0) {
                gameOverStartCountdown -= dt
                if (gameOverStartCountdown <= 0.0) {
                    gameOverStartCountdown = 0.0
                    gameOverStarting = false
                    startDeathMusic()
                }
            }
        }

        // `controls.ACCEPT_P && blueballed` restarts, BACK quits. The blue
        // ball flag is set the moment the loss SFX plays, so both work right
        // away; `isEnding` guards double presses.
        if (gameOverBlueBalled && !gameOverEnding) {
            if (input.accept) {
                confirmGameOver()
            } else if (input.back) {
                exitGameOver()
            }
        }

        // The dead character keeps animating while the camera glides over.
        player.update(dt)
        camera.update(dt)
        gameOverWorldLayer?.also { world ->
            world.scaleX = camera.cam.scaleX
            world.scaleY = camera.cam.scaleY
            world.x = camera.cam.x
            world.y = camera.cam.y
        }
        gameOverPlayerLayer?.let { layer ->
            layer.container.x = (1.0 - layer.scrollX) * camera.scrollX
            layer.container.y = (1.0 - layer.scrollY) * camera.scrollY
        }

        // Confirm sequence: `FADE_TIMER = length / 7000` after the ending
        // music starts, then two seconds of black, then the scene rebuilds.
        if (gameOverEnding) {
            gameOverConfirmElapsed += dt
            if (gameOverConfirmElapsed >= gameOverConfirmFadeDelay + 2.0) {
                gameOverEnding = false
                gameOverActive = false
                restartSong()
            }
        }
    }

    /**
     * `startDeathMusic`: `gameOverStart` once (when a variant exists), then
     * the looping `gameOver`. The suffixes come from the character scripts
     * (`bf-pixel`, the Pico variants); FNF strips trailing `-` components
     * until it finds an existing track.
     *
     * [startingVolume] is `0.2` while a death quote plays; the quote's
     * completion then fades the loop in (`gameOverMusic.fadeIn(4, 0.2, 1)`).
     */
    private fun startDeathMusic(startingVolume: Double = 1.0) {
        gameOverMusicGeneration++
        val generation = gameOverMusicGeneration
        deathMusicTargetVolume = startingVolume
        val suffix = gameOverSuffixes().first
        if (gameOverStarting) {
            val startPath = resolveDeathMusic("gameOverStart", suffix)
            if (startPath == null) {
                gameOverStarting = false
            } else {
                launch {
                    if (gameOverMusicGeneration != generation) return@launch
                    if (gameOverMusic.load(startPath, streaming = true)) {
                        gameOverMusic.loop = false
                        gameOverMusic.volume = deathMusicTargetVolume
                        gameOverMusic.play()
                        // `onComplete` hands over to the loop track.
                        gameOverStartCountdown = gameOverMusic.totalMs / 1000.0
                    } else {
                        gameOverStarting = false
                        startDeathMusic(startingVolume)
                    }
                }
                return
            }
        }
        val loopPath = resolveDeathMusic("gameOver", suffix)
        if (loopPath == null) {
            println("KNF: game over music missing")
            return
        }
        launch {
            if (gameOverMusicGeneration != generation) return@launch
            if (gameOverMusic.load(loopPath, streaming = true)) {
                gameOverMusic.loop = true
                gameOverMusic.volume = deathMusicTargetVolume
                gameOverMusic.play()
            }
        }
    }

    /**
     * `playDeathQuote`: start the loop quietly, play the character's insult,
     * then fade the music back in once the clip ends.
     */
    private fun playDeathQuote(quote: String) {
        startDeathMusic(0.2)
        val generation = gameOverMusicGeneration
        if (player.hasAnimation("deathLoop")) player.play("deathLoop", forceRestart = true)
        val channel = sfx.play(quote, 1.0) { late -> startDeathQuoteFade(late, generation) }
        if (channel != null) {
            startDeathQuoteFade(channel, generation)
        } else {
            // A late load should start the clip within a frame or two; if the
            // asset is truly missing, do not leave the music ducked forever.
            launch {
                delay(1_500)
                if (deathQuoteChannel == null && generation == gameOverMusicGeneration) {
                    deathMusicTargetVolume = 1.0
                    gameOverMusic.volume = 1.0
                }
            }
        }
    }

    private fun startDeathQuoteFade(channel: SfxChannel?, generation: Int) {
        deathQuoteChannel = channel
        if (channel == null || generation != gameOverMusicGeneration) {
            deathMusicTargetVolume = 1.0
            gameOverMusic.volume = 1.0
            return
        }
        launch {
            while (channel.playing && generation == gameOverMusicGeneration) delay(50)
            if (generation != gameOverMusicGeneration) return@launch
            // `FunkinSound.fadeIn(4, 0.2, 1)`.
            var elapsed = 0.0
            while (elapsed < QUOTE_FADE_SECONDS && generation == gameOverMusicGeneration) {
                elapsed += 0.05
                val t = (elapsed / QUOTE_FADE_SECONDS).coerceIn(0.0, 1.0)
                val volume = QUOTE_MUSIC_VOLUME + (1.0 - QUOTE_MUSIC_VOLUME) * t
                deathMusicTargetVolume = volume
                gameOverMusic.volume = volume
                delay(50)
            }
        }
    }

    /**
     * `BaseCharacter.getDeathQuote` overrides from `bf.hxc`, `bf-holding-gf.hxc`,
     * `pico-playable.hxc` (inherited by `pico-christmas`/`pico-dark`) and
     * `pico-holding-nene.hxc`. BF and Pico only insult Tankman, except
     * `bf-holding-gf` and Pico holding Nene, who always do.
     *
     * Stock 0.8.6 is built with `CENSOR_EXPLETIVES` off and defaults
     * `Preferences.naughtyness` to true, so no clip numbers are excluded.
     */
    private fun resolveDeathQuote(): String? {
        val dad = opponent.id
        val id = player.id
        val folder: String
        val max: Int
        when (id) {
            "bf" -> {
                if (dad != "tankman") return null
                folder = "jeffGameover"
                max = 25
            }
            "bf-holding-gf" -> {
                folder = "jeffGameover"
                max = 25
            }
            "pico-playable", "pico-christmas", "pico-dark" -> {
                if (dad != "tankman") return null
                folder = "jeffGameover-pico"
                max = 10
            }
            "pico-holding-nene" -> {
                folder = "jeffGameover-pico"
                max = 10
            }
            else -> return null
        }
        val number = (1..max).random()
        return resolveStageSound("sounds/$folder/jeffGameover-$number")
    }

    /** `confirmDeath`: the `deathConfirm` pose, the ending track, the reset. */
    private fun confirmGameOver() {
        if (gameOverEnding) return
        gameOverEnding = true
        gameOverStarting = false
        gameOverConfirmElapsed = 0.0
        gameOverConfirmFadeDelay = 0.0

        // `confirmDeath` stops the quote immediately.
        deathQuoteChannel?.stop()
        deathQuoteChannel = null

        gameOverMusicGeneration++
        val generation = gameOverMusicGeneration
        val endPath = resolveDeathMusic("gameOverEnd", gameOverSuffixes().first)
        if (endPath != null) {
            launch {
                if (gameOverMusicGeneration != generation) return@launch
                if (gameOverMusic.load(endPath, streaming = true)) {
                    gameOverMusic.loop = false
                    gameOverMusic.volume = 1.0
                    gameOverMusic.play()
                    // `FADE_TIMER = length / 7000` keeps mods with longer
                    // confirm tracks from being cut off.
                    gameOverConfirmFadeDelay = (gameOverMusic.totalMs / 1000.0) / 7.0
                }
            }
        }
        if (player.hasAnimation("deathConfirm")) player.play("deathConfirm", forceRestart = true)
    }

    /** `GameOverSubState` quit: sticker transition back to the song list. */
    private fun exitGameOver() {
        if (gameOverEnding) return
        gameOverEnding = true
        deathQuoteChannel?.stop()
        deathQuoteChannel = null
        gameOverMusic.stop()
        exitToSongSelect()
    }

    /**
     * `playBlueBalledSFX`. Unlike the music path this does not strip suffixes
     * (`GameOverSubState.playBlueBalledSFX` checks the exact asset), so a
     * missing variant is logged instead of silently falling back to BF's.
     */
    private fun playLossSfx() {
        gameOverBlueBalled = true
        val suffix = gameOverSuffixes().second
        val path = resolveDeathSfx(suffix)
        if (path == null) {
            println("KNF: fnf_loss_sfx$suffix missing")
            return
        }
        sfx.play(path, 1.0)
    }

    /**
     * `GameOverSubState.musicSuffix` / `blueBallSuffix`, assigned by character
     * scripts in FNF's creation order (GF, dad, BF), so the last match wins.
     *
     * Note that the two suffixes are not always equal: Pico's Weekend 1 and
     * Week 7 characters use the shared `-pico` death music but their own
     * `-pico-gutpunch` / `-pico-and-nene` loss SFX.
     */
    private fun gameOverSuffixes(): Pair<String, String> {
        var music = ""
        var loss = ""
        val chars = chart.characters
        for (id in listOf(chars.girlfriend, chars.opponent, chars.player)) {
            when (id) {
                "bf-pixel" -> {
                    music = "-pixel"
                    loss = "-pixel"
                }
                "pico-playable", "pico-christmas", "pico-dark" -> {
                    music = "-pico"
                    loss = "-pico"
                }
                "pico-blazin" -> {
                    music = "-pico"
                    loss = "-pico-gutpunch"
                }
                "pico-holding-nene" -> {
                    music = "-pico"
                    loss = "-pico-and-nene"
                }
                "pico-pixel" -> {
                    music = "-pixel-pico"
                    loss = "-pixel-pico"
                }
            }
        }
        return music to loss
    }

    /** `GameOverSubState.playBlueBalledSFX`: exact suffix, stage library first. */
    private fun resolveDeathSfx(suffix: String): String? =
        resolveStageSound("sounds/gameplay/gameover/fnf_loss_sfx$suffix")

    /**
     * `GameOverSubState.resolveMusicPath`: try `base+suffix`, then strip the
     * last `-component` until a track exists (`-pixel-pico` -> `-pixel` -> ``).
     */
    private fun resolveDeathMusic(base: String, suffix: String): String? {
        var current = suffix
        while (true) {
            resolveStageSound("music/gameplay/gameover/$base$current")?.let { return it }
            if (current.isEmpty()) return null
            current = current.substringBeforeLast('-', "")
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

            // `tankmanBattlefieldErect.hxc`: turns on tankman-bloody's mask.
            "EnableMask" -> tankmanRimFilter?.useAltMask = true

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
     * Measures the residual between the audible song clock and the instant each
     * note's strum time is crossed. It should stay near zero; a growing value
     * means the Conductor is not tracking the audible clock.
     *
     * `music.positionMs` is deliberately not used here: it is the *generated*
     * position, buffered ahead of the speakers, so it would report the output
     * queue as a permanent offset.
     */
    private fun measureAvSync() {
        val nowC = conductor.songPosition
        val nowA = songClockMs.toFloat() + conductor.combinedOffset
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

    /**
     * Stems of the gameplay scene that is still alive during a scene switch.
     *
     * `SceneContainer` runs the incoming scene's `sceneInit` before the
     * outgoing scene's `sceneDestroy`, so on a retry the old song's decoded PCM
     * would still be allocated while the new song decodes its own (three stems
     * are tens of megabytes each). The next PlayScene disposes these first.
     */
    private fun releasePreviousSongAudio() {
        if (previousSongPlayers.isEmpty()) return
        for (player in previousSongPlayers) runCatching { player.dispose() }
        previousSongPlayers = emptyList()
    }

    private companion object {
        /** Players registered by the previous gameplay scene, see [releasePreviousSongAudio]. */
        var previousSongPlayers: List<MusicPlayer> = emptyList()

        /** `schoolErect.hxc`: `rim.color = 0xFF52351D`, the warm brown key light. */
        val SCHOOL_ERECT_LIGHT = RGBA(0x52, 0x35, 0x1D)

        /**
         * How long after `go` a stem may take to queue its first block before
         * the chart switches to the wall clock. Device-open is ~10-100 ms; a
         * second of silence means the output is dead.
         */
        const val AUDIO_START_TIMEOUT_MS = 1_000.0

        /** Throttle for restarting dead stems, so a broken device is not spammed. */
        const val AUDIO_RECOVERY_INTERVAL_MS = 1_500.0

        /**
         * How long a just-started stem may report `playing = false` before it
         * counts as dead. The output opens on the audio thread, so the channel
         * state lags `play()` by a few frames - long enough for the old code to
         * rebuild every `AudioTrack` on the first frame of the song.
         */
        const val STEM_START_GRACE_MS = 1_000.0

        /** Give up restarting after this many attempts; the chart still runs. */
        const val AUDIO_RECOVERY_LIMIT = 6

        /** Near the end a stopped channel is the song finishing, not a failure. */
        const val AUDIO_END_GUARD_MS = 500.0

        /** FNF's `camTransition.fade(..., 0.6, ...)` and HUD fade. */
        const val ENDING_FADE_SECONDS = 0.6

        /** A `tick` this long freezes the frame; log it (see `sceneMain`). */
        const val SLOW_FRAME_LOG_MS = 120.0
        const val SLOW_FRAME_LOG_LIMIT = 24

        /** `PauseSubState.MUSIC_FINAL_VOLUME` / `MUSIC_FADE_IN_TIME`. */
        const val PAUSE_MUSIC_VOLUME = 0.75
        const val PAUSE_MUSIC_FADE_SECONDS = 5.0

        /** `playDeathQuote`: `startDeathMusic(0.2)` + `fadeIn(4, 0.2, 1)`. */
        const val QUOTE_MUSIC_VOLUME = 0.2
        const val QUOTE_FADE_SECONDS = 4.0

        /** `GameOverSubState.update`: `FlxG.random.bool((1 / 4096) * 100)`. */
        const val FAKEOUT_CHANCE = 1.0 / 4096.0

        /**
         * `PlayState.RESYNC_THRESHOLD` is 40 ms in FNF, where `FlxSound.time`
         * is sample-accurate. Here `SoundChannel.current` is the number of
         * *generated* samples, and each stem's output queue is filled
         * independently, so two stems routinely report 0 or one 2048-sample
         * block (~46 ms), occasionally two, while staying audibly in phase.
         * Resyncing on that reporting jitter would restart the stems for no
         * reason, so `resyncVocals()` only reacts to a real, gross drift.
         */
        const val RESYNC_THRESHOLD_MS = 150.0
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
                "pico-playable", "pico-christmas", "pico-dark",
                "pico-blazin", "pico-holding-nene" -> suffix = "-pico"
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

/**
 * Invisible view whose only job is to re-reference the note-style textures
 * every frame.
 *
 * KorGE's `AgBitmapTextureManager` drops a texture that has not been drawn for
 * 60 frames. The 2048x2048 note-splash atlas only appears while a splash lives,
 * so it was evicted between Sick streaks and re-uploaded (16 MB) on the next
 * Sick, freezing the GL thread for ~100 ms on mobile. Touching the textures in
 * a render pass keeps them in the manager's referenced set.
 */
private class NoteStyleTextureKeeper(
    private val noteStyle: NoteStyle,
) : Container() {
    override fun renderInternal(ctx: RenderContext) {
        noteStyle.touchTextures(ctx)
        super.renderInternal(ctx)
    }
}
