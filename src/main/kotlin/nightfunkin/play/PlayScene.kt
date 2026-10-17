package nightfunkin.play

import korlibs.image.color.Colors
import korlibs.image.bitmap.sliceWithSize
import korlibs.korge.scene.Scene
import korlibs.korge.view.Container
import korlibs.korge.view.SContainer
import korlibs.korge.view.addUpdater
import korlibs.korge.view.image
import korlibs.korge.view.solidRect
import korlibs.korge.view.text
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
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** What to play вЂ” stashed before switching scenes. */
object PlayRequest {
    /** Audio offset adjust step, in milliseconds. */
    const val OFFSET_STEP_MS = 5f

    var songId: String = "bopeebo"
    var difficulty: String = Constants.DEFAULT_DIFFICULTY
    var variation: String = Constants.DEFAULT_VARIATION
    var autoplay: Boolean = false
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

    private lateinit var gameInput: KnfInput
    private lateinit var noteStyle: NoteStyle

    private lateinit var cameraLayer: Container
    private lateinit var hudContainer: Container
    private lateinit var playerStrumline: Strumline
    private lateinit var opponentStrumline: Strumline
    private lateinit var hud: Hud
    private lateinit var camera: GameplayCamera

    // ---- gameplay state -------------------------------------------------
    private var score = 0
    private var combo = 0
    private var hits = 0
    private var misses = 0
    private var startingSong = true
    private var songEnded = false
    private var paused = false
    private var startTimestampMs = 0.0
    private var prePosition = 0f

    private var countdownStep = -1
    private var countdownTimer = 0.0
    private val clockDebug = System.getenv("KNF_DEBUG_CLOCK") == "1"
    private var wallClock = 0.0
    private var songStartWallMs = -1.0
    private var clockSampleTimer = 0.0

    private var nextEventIndex = 0

    private var totalNotes = 0

    private var debugFrames = 0
    private var debugEnabled = false

    // ---- stage layout (screen coordinates, inside the camera layer) -------
    private lateinit var stageLayer: Container
    private lateinit var opponent: Character
    private lateinit var girlfriend: Character
    private lateinit var player: Character
    private var stageProps: Container? = null
    private var girlfriendSinging = 0.0

    companion object {
        /** How often the girlfriend strikes a pose on her own. */
        const val GIRLFRIEND_SING_INTERVAL = 3.2
    }

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
        cameraLayer.addChildAt(stage, 0)
        stageProps = stage
    }

    /**
     * Puts a character on its stage slot using FNF's own arithmetic:
     * feet at `position`, minus half the timeline width and its full height,
     * plus the per-character `offsets`, all scaled by `baseScale * stageScale`.
     */
    private fun placeCharacter(character: Character, slot: String): StageCharacterData {
        val data = stageFile.characterFor(slot)
        val feetX = data?.position?.getOrElse(0) { 0.0 } ?: 0.0
        val feetY = data?.position?.getOrElse(1) { 0.0 } ?: 0.0
        val scale = character.baseScale * (data?.scale ?: 1.0)
        // `Stage.addCharacter` inverts the file's `flipX` for the player slot only.
        character.flip = Character.flipForSlot(slot, character.dataFlipX)
        character.placeFeet(feetX, feetY, scale)
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

        val chars = chart.characters
        opponent = Character.get(chars.opponent)
        girlfriend = Character.get(chars.girlfriend)
        player = Character.get(chars.player)

        val opponentSlot = placeCharacter(opponent, "dad")
        placeCharacter(girlfriend, "gf")
        placeCharacter(player, "bf")

        // `Stage.refresh()` sorts every child by zIndex ascending; on mainStage
        // that is gf (100) behind dad (200) behind bf (300).
        val byZIndex = listOf(
            Triple("dad", opponent, opponentSlot.zIndex),
            Triple("gf", girlfriend, stageFile.characterFor("gf")?.zIndex ?: 0),
            Triple("bf", player, stageFile.characterFor("bf")?.zIndex ?: 0),
        ).sortedBy { it.third }
        for ((_, character, _) in byZIndex) {
            character.dance(forceRestart = true)
            stageLayer.addChild(character)
        }

        // Some shipped characters are placeholders that resolve to nothing
        // (`gf-tankmen` points at a folder that does not exist). Hiding them keeps
        // a missing asset from dragging the camera focus around.
        for ((slot, ch) in listOf("dad" to opponent, "gf" to girlfriend, "bf" to player)) {
            if (ch.localWidth <= 0.0 || ch.localHeight <= 0.0) {
                ch.visible = false
                println("KNF: character '" + ch.id + "' for slot " + slot + " has no art")
            }
        }

        // FNF opens the camera on the opponent's focus point, at the stage zoom.
        val (focusX, focusY) = opponent.cameraFocusPoint(opponentSlot.cameraOffsets)
        defaultCameraFocus = focusX to focusY
        camera.setFocus(focusX, focusY)
        camera.setZoomImmediate(stageFile.cameraZoom)

        buildStageProps()



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

        hud = Hud(hudContainer, downscroll)
        hud.resetHealth()
        hud.setSongLabels(song.name, song.artist, chart.difficulty.uppercase())
        hud.setWatermark("${song.name} [${song.artist}]", chart.difficulty + " / " + chart.variation)
        playerStrumline.pressKey(0)

        // ---- audio -------------------------------------------------------
        val instPath = song.instrumentalPath()
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

        // Attached only now: `Conductor.update` dispatches step hits immediately,
        // and `onBeat()` needs the characters to already exist.
        conductor.onStepHit = {
            val step = conductor.currentStep
            camera.onStep(step)
            // `danceEvery` counts beats and defaults to one, so everyone re-poses
            // on every quarter note.
            if (step % 4 == 0) onBeat()
        }

        nextEventIndex = 0
        resetForSongStart()
        println("KNF: playing '${song.name}' [${chart.difficulty}] notes=${chart.notes.size} inst=$instPath")
        println(
            "KNF: layout scene=" + sceneContainer.size + " hud=" + hudContainer.size +
                " player=(" + playerStrumline.container.x + "," + playerStrumline.container.y + ")" +
                " opponent=(" + opponentStrumline.container.x + "," + opponentStrumline.container.y + ")" +
                " opponentLane=(" + opponentStrumline.container.x + "," + opponentStrumline.container.y + ")"
        )
        println(
            "KNF: chars dad='" + opponent.id + "'@" + opponent.x + "," + opponent.y +
                " " + opponent.localWidth + "x" + opponent.localHeight +
                " | gf='" + girlfriend.id + "'@" + girlfriend.x + "," + girlfriend.y +
                " " + girlfriend.localWidth + "x" + girlfriend.localHeight +
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
        addUpdater { dt -> tick(dt.dtSeconds()) }
    }

    override suspend fun sceneDestroy() {
        music.stop()
        vocals.stop()
        opponentVocals.stop()
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
            "${song.name}\n${song.artist}",
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
        val cutout = Constants.GAME_WIDTH / 2.5 / 2.0
        val playerX = Constants.GAME_WIDTH / 2.0 + Constants.STRUMLINE_X_OFFSET + cutout / 2.0
        val opponentX = Constants.STRUMLINE_X_OFFSET.toDouble() + cutout

        val receptorH = playerStrumline.receptorHeight()
        val strumY = if (downscroll) {
            Constants.GAME_HEIGHT - receptorH - Constants.STRUMLINE_Y_OFFSET
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
        songEnded = false
        startingSong = true
        paused = false
        countdownStep = -1
        countdownTimer = 0.0
        playerStrumline.clean()
        opponentStrumline.clean()
        playerStrumline.setNoteData(chart.notes.filter { it.isPlayerNote })
        opponentStrumline.setNoteData(chart.notes.filter { !it.isPlayerNote })
        hud.resetHealth()
        hud.setScore(0)
        hud.setCombo(0)
        conductor.update(prePosition, false)
    }

    // ======================================================================
    // main loop
    // ======================================================================

    private fun tick(dt: Double) {
        wallClock += dt * 1000.0
        gameInput.update()
        handleOffsetKeys()

        if (paused) return

        updateClock(dt)
        if (startingSong) {
            updateCountdown(dt)
        } else if (!songEnded) {
            processSongEvents()
            processInput()
            processNotes()
            checkSongEnd()
        }

        camera.update(dt)
        hud.update(dt)
        playerStrumline.update(conductor, startTimestampMs.toFloat())
        opponentStrumline.update(conductor, startTimestampMs.toFloat())

        opponent.update(dt)
        girlfriend.update(dt)
        player.update(dt)
        if (girlfriendSinging > 0.0) girlfriendSinging -= dt

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
        hud.setWatermark(
            "${song.name} [${song.artist}]",
            "${chart.difficulty} / ${chart.variation}   offset ${"%+.0f".format(ms)} ms",
        )
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
            conductor.accumulateDelta(dt.toFloat())
            conductor.update(conductor.songPosition + (dt * 1000.0).toFloat(), false)
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
                0 -> "3"
                1 -> "2"
                2 -> "1"
                else -> "GO!"
            }
            hud.showCountdown(label)
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
        hud.showCountdown("GO!")
    }

    private fun checkSongEnd() {
        val length = music.totalMs
        if (length > 0 && music.positionMs >= length - 120) {
            songEnded = true
            finishSong()
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

    private fun finishSong() {
        music.stop()
        vocals.stop()
        opponentVocals.stop()
        val accuracy = if (totalNotes > 0) hits.toFloat() / totalNotes else 0f
        val rank = Scoring.rankFor(accuracy)
        hud.setRank("${rank.uppercase()}  ${(accuracy * 100).roundToInt()}%")
        if (avsyncDebug && avcount > 0) {
            val avg = avsum / avcount
            println("KNF: AVSYNC notes=$avcount avg=${"%.2f".format(avg)}ms audio-vs-chart")
            println("KNF: AVSYNC conductor=${conductor.songPosition}ms audio=${music.positionMs}ms offset=${conductor.combinedOffset}ms")
        }
        println("KNF: song finished $rank (${(accuracy * 100).roundToInt()}%), score $score")
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
        val candidates = playerStrumline.notesMayHit(lane)
        if (candidates.isEmpty()) {
            playerStrumline.pressKey(lane)
            ghostTap()
            return
        }
        val note = candidates.first()
        goodNoteHit(note)
        playerStrumline.playConfirm(lane)
    }

    private fun onRelease(lane: Int) {
        playerStrumline.releaseKey(lane)
    }

    // ======================================================================
    // note processing
    // ======================================================================

    /** Debug helper: where each character actually ended up on screen. */
    private fun dumpCharacters() {
        for ((label, ch) in listOf("dad" to opponent, "gf" to girlfriend, "bf" to player)) {
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
     * by default); the girlfriend additionally picks a fresh pose on her own
     * longer cycle.
     */
    private fun onBeat() {
        player.dance()
        opponent.dance()
        girlfriend.dance()

        if (girlfriendSinging <= 0.0) {
            girlfriendSinging = GIRLFRIEND_SING_INTERVAL
            girlfriend.sing((conductor.currentStep + 1) % 4, conductor.stepLengthMs.toDouble())
        }
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

        score += Scoring.scoreNote(absError)
        combo++
        hits++
        hud.setScore(score)
        hud.setCombo(combo)
        hud.showJudgement(judgement)
        hud.addHealth(Scoring.healthDelta(judgement))
        hud.bopIcons()

        // FNF's BF behaviour: strike the matching `singDIR` pose on every hit.
        if (note.isPlayerNote) player.sing(note.directionIndex, conductor.stepLengthMs.toDouble())

        if (note.isHoldNote) note.startClip()


    }

    private fun ghostTap() {
        combo = 0
        misses++
        hud.setCombo(0)
        hud.addHealth(Constants.HEALTH_GHOST_MISS_PENALTY)
    }

    private fun processNotes() {
        if (PlayRequest.autoplay) autoPlayPlayerNotes()
        handleOpponentNotes()
        handleMisses()
        handleHolds()
        checkDeath()
    }

    /** FNF's botplay: strikes every note exactly on its strum time. */
    private fun autoPlayPlayerNotes() {
        val now = conductor.songPosition
        for (note in playerStrumline.notes) {
            if (note.hasBeenHit || note.hasMissed) continue
            if (now >= note.strumTime - 1f) {
                goodNoteHit(note)
                playerStrumline.playConfirm(note.directionIndex)
                playerStrumline.pressKey(note.directionIndex)
            } else if (now >= note.strumTime - 60f) {
                playerStrumline.pressKey(note.directionIndex)
            }
        }
    }

    private fun handleOpponentNotes() {
        for (note in opponentStrumline.notes) {
            if (note.mayHit && !note.hasBeenHit && !note.hasMissed) {
                note.hasBeenHit = true
                note.timingErrorMs = conductor.songPosition - note.strumTime
                opponentStrumline.playConfirm(note.directionIndex)
                // FNF's DAD behaviour: the CPU poses on every note it lands.
                opponent.sing(note.directionIndex, conductor.stepLengthMs.toDouble())
                if (note.isHoldNote) note.startClip()
            }
        }
    }

    private fun handleMisses() {
        for (note in playerStrumline.notes) {
            if (note.hasMissed && !note.handledMiss) {
                note.handledMiss = true
                combo = 0
                misses++
                hud.setCombo(0)
                hud.addHealth(Constants.HEALTH_MISS_PENALTY)
                hud.showJudgement(Judgement.MISS)
                sfx.play(missSound, 0.35)
            }
        }
        for (note in opponentStrumline.notes) {
            if (note.hasMissed && !note.handledMiss) {
                note.handledMiss = true
                // The opponent flubbing it is good for us.
                hud.addHealth(-Constants.HEALTH_MISS_PENALTY)
                hud.bopIcons()
            }
        }
    }

    private fun handleHolds() {
        val now = conductor.songPosition
        for (note in playerStrumline.notes) {
            if (!note.isHoldNote) continue
            val lane = note.directionIndex
            if (note.isHolding) {
                if (!gameInput.isDown(lane)) {
                    note.releaseClip()
                    continue
                }
                val remaining = note.fullSustainLength - (now - note.strumTime)
                if (remaining <= 0f) {
                    note.releaseClip()
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
            hud.setRank("YOU DIED")
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
                val char = ev.getInt("char") ?: (ev.valueAsStruct()["char"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toIntOrNull() ?: 0
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
                camera.tweenZoomTo(zoom)
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
            2 -> girlfriend.cameraFocusPoint(stageFile.characterFor("gf")?.cameraOffsets.orEmpty())
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

    /** Picks the playable audio file for an extension-less stem. */
    private fun resolveSfx(stem: String): String = KnfFiles.resolveSound(stem) ?: "$stem.mp3"
}
