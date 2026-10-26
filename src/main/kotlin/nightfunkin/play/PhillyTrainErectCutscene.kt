package nightfunkin.play

import kotlin.random.Random
import korlibs.image.color.Colors
import korlibs.image.font.TtfFont
import korlibs.image.font.getTextBounds
import korlibs.image.paint.Stroke
import korlibs.io.resources.Resourceable
import korlibs.korge.view.Container
import korlibs.korge.view.Text
import korlibs.korge.view.addUpdater
import nightfunkin.audio.MusicPlayer
import nightfunkin.core.AnimateAtlas
import nightfunkin.core.AnimateSpriteView
import nightfunkin.core.Constants
import nightfunkin.core.KnfFiles
import nightfunkin.core.SparrowSprite
import nightfunkin.core.TextureAtlas

/**
 * `PhillyTrainErectStage.doppleGangerCutscene` (`phillyTrainErect.hxc`).
 *
 * The stage script cancels the countdown when the player is `pico-playable`,
 * replaces both fighters with Pico doppelgangers, plays a short cigarette /
 * shooting animation, then either starts the countdown or (when the player is
 * shot in the explosion variant) ends the song.
 *
 * The original randomly picks the shooter (50%) and the explosion outcome (8%).
 * `KNF_PHILLY_CUTSCENE` can force an outcome for testing.
 */
class PhillyTrainErectCutscene(private val host: CutsceneHost) : SongCutscene {

    private enum class Phase { RUNNING, SKIPPING, DONE }

    override val kind: CutsceneKind
        get() = if (ending) CutsceneKind.ENDING else CutsceneKind.STARTING

    private var phase = Phase.RUNNING
    private var time = 0.0
    private var ending = false
    private var skipLocked = false
    private var cutsceneSkipped = false

    private var playerShoots = false
    private var explode = false

    private var layer: Container? = null
    private var picoPlayer: AnimateSpriteView? = null
    private var picoOpponent: AnimateSpriteView? = null
    private var bloodPool: AnimateSpriteView? = null
    private var cigarette: SparrowSprite? = null
    private var cigaretteFrameList: List<TextureAtlas.AtlasFrame> = emptyList()
    private var cigaretteZ = 0
    private var bloodExtending = false

    private var music: MusicPlayer? = null
    private var musicFadeOut = false
    private var musicFadeStartVolume = 1.0
    private var musicFadeElapsed = 0.0

    private var skipRoot: Container? = null
    private var skipFadeIn = false
    private var skipFadeOut = false
    private var skipFadeElapsed = 0.0
    private var canSkip = false

    private var blackShown = false
    private var blackFadeStart = -1.0
    private var skipBlackElapsed = 0.0
    private var skipRestored = false

    private var restoreBf = false
    private var restoreDad = false
    private var keepStageLayer = false
    private var lastDebugSecond = -1

    private val sounds = HashMap<String, String?>()
    private val events = ArrayList<TimedEvent>()
    private var eventIndex = 0

    private data class TimedEvent(val at: Double, val action: () -> Unit)

    override suspend fun start() {
        host.setHudVisible(false)

        chooseOutcome()
        preloadSounds()
        loadMusic()

        val bf = host.characterPosition("bf")
        val dad = host.characterPosition("dad")
        if (bf == null || dad == null) {
            println("KNF: philly cutscene aborted: missing stage character")
            phase = Phase.DONE
            return
        }

        val playerFocus = host.characterCameraFocus("bf") ?: bf
        val dadFocus = host.characterCameraFocus("dad") ?: dad

        val layer = Container()
        this.layer = layer
        host.addStageView(layer, 1000)

        val blood = AnimateSpriteView(loadAnimate("philly/erect/bloodPool"))
        blood.visible = false
        blood.play("", loop = false)
        blood.stop()
        blood.onFinish = { bloodExtending = true }
        bloodPool = blood

        // Both doppelgangers use the same large Animate atlas; parse it once.
        val picoAtlas = loadAnimate("philly/erect/pico_doppleganger")
        val picoPlayer = AnimateSpriteView(picoAtlas)
        picoPlayer.x = bf.first - 402.0
        picoPlayer.y = bf.second - 144.0
        this.picoPlayer = picoPlayer

        val picoOpponent = AnimateSpriteView(picoAtlas)
        picoOpponent.x = dad.first - 333.0
        picoOpponent.y = dad.second - 144.5
        this.picoOpponent = picoOpponent

        val cigarette = loadCigarette()
        this.cigarette = cigarette

        val playerZ = host.characterZIndex("bf")
        val dadZ = host.characterZIndex("dad")
        val picoPlayerZ = playerZ + 1
        val picoOpponentZ = if (playerShoots) picoPlayerZ - 1 else picoPlayerZ + 1
        val bloodZ = if (playerShoots) picoOpponentZ - 1 else picoPlayerZ - 1
        val cigaretteZ = if (playerShoots) dadZ - 2 else dadZ - 1
        this.cigaretteZ = cigaretteZ

        val actors = ArrayList<Pair<Int, Container>>()
        cigarette?.let { actors += cigaretteZ to it }
        actors += bloodZ to blood
        actors += picoOpponentZ to picoOpponent
        actors += picoPlayerZ to picoPlayer
        for ((_, actor) in actors.sortedBy { it.first }) layer.addChild(actor)

        val cigarettePos: Pair<Double, Double>
        val shooterPos: Pair<Double, Double>
        if (playerShoots) {
            cigarette?.scaleX = -1.0
            cigarette?.x = bf.first - 10.0 + (cigarette?.logicalWidth ?: 0)
            cigarette?.y = bf.second + 260.0
            blood.x = dad.first - 30.0
            blood.y = dad.second + 460.0
            shooterPos = playerFocus
            cigarettePos = dadFocus
            picoPlayer.play("shootPlayer", loop = false)
            if (explode) {
                picoOpponent.play("explodeOpponent", loop = false)
                picoOpponent.onFinish = { picoOpponent.play("loopOpponent", loop = true) }
            } else {
                picoOpponent.play("cigaretteOpponent", loop = false)
            }
        } else {
            cigarette?.x = bf.first - 350.0
            cigarette?.y = bf.second + 260.0
            blood.x = bf.first + 640.0
            blood.y = bf.second + 460.0
            shooterPos = dadFocus
            cigarettePos = playerFocus
            picoOpponent.play("shootOpponent", loop = false)
            if (explode) {
                picoPlayer.play("explodePlayer", loop = false)
                picoPlayer.onFinish = { picoPlayer.play("loopPlayer", loop = true) }
            } else {
                picoPlayer.play("cigarettePlayer", loop = false)
            }
        }

        host.setCharacterVisible("bf", false)
        host.setCharacterVisible("dad", false)

        val midX = (shooterPos.first + cigarettePos.first) / 2.0
        val midY = (shooterPos.second + cigarettePos.second) / 2.0
        host.focusCamera(midX, midY, host.stageZoom)

        createSkipText()
        scheduleEvents(cigarettePos, shooterPos)
        music?.play()

        println(
            "KNF: philly cutscene started (playerShoots=$playerShoots, explode=$explode, " +
                "music=${music != null})"
        )
    }

    override fun update(dt: Double): Boolean {
        if (phase == Phase.DONE) return true
        time += dt
        if (DEBUG && time.toInt() != lastDebugSecond) {
            lastDebugSecond = time.toInt()
            println("KNF: philly t=$lastDebugSecond phase=$phase next=${events.getOrNull(eventIndex)?.at}")
        }

        if (phase == Phase.SKIPPING) {
            updateSkip(dt)
            return phase == Phase.DONE
        }

        if (!cutsceneSkipped) {
            while (eventIndex < events.size && events[eventIndex].at <= time) {
                val event = events[eventIndex++]
                event.action()
                if (phase != Phase.RUNNING) break
            }
        }

        picoPlayer?.update(dt)
        picoOpponent?.update(dt)
        bloodPool?.let {
            it.update(dt)
            if (bloodExtending) {
                it.scaleX += 0.02 * dt
                it.scaleY += 0.02 * dt
            }
        }
        cigarette?.update(dt)
        updateSkipText(dt)
        updateMusic(dt)

        if (ending) {
            if (blackFadeStart < 0.0 && time >= DEATH_BLACK_START) {
                blackFadeStart = time
                showBlack()
            }
            if (blackFadeStart >= 0.0) {
                host.setBlackScreenAlpha(
                    ((time - blackFadeStart) / DEATH_BLACK_FADE).coerceIn(0.0, 1.0),
                )
            }
            if (time >= DEATH_DONE) {
                phase = Phase.DONE
                return true
            }
        }

        if (host.advancePressed && !skipLocked && !cutsceneSkipped) {
            if (!canSkip) {
                startSkipPrompt()
            } else {
                startSkip()
            }
        }
        return false
    }

    override fun destroy() {
        val finished = phase == Phase.DONE
        phase = Phase.DONE
        music?.dispose()
        music = null
        if (!keepStageLayer) {
            layer?.let { host.removeStageView(it) }
        } else {
            // The Pico-mix explosion outcome leaves the opponent's corpse and
            // blood pool in the stage after the cutscene, exactly like the
            // FlxSprite actors added by phillyTrainErect.hxc.
            val corpse = picoOpponent
            val blood = bloodPool
            layer?.addUpdater { dt ->
                val seconds = dt.inWholeNanoseconds / 1_000_000_000.0
                corpse?.update(seconds)
                blood?.let {
                    it.update(seconds)
                    if (bloodExtending) {
                        it.scaleX += 0.02 * seconds
                        it.scaleY += 0.02 * seconds
                    }
                }
            }
        }
        layer = null
        skipRoot?.let { host.removeCutsceneView(it) }
        skipRoot = null
        if (blackShown || ending) host.hideBlackScreen()
        blackShown = false

        if (!finished) {
            // The scene was left in the middle of the cutscene; don't leave the
            // real fighters hidden if the player retries or exits.
            host.setCharacterVisible("bf", true)
            host.setCharacterVisible("dad", true)
        } else {
            if (restoreBf) host.setCharacterVisible("bf", true)
            if (restoreDad) host.setCharacterVisible("dad", true)
        }
        host.setHudVisible(true)
    }

    // ------------------------------------------------------------------
    // setup
    // ------------------------------------------------------------------

    private fun chooseOutcome() {
        when (System.getenv("KNF_PHILLY_CUTSCENE")?.lowercase()) {
            "player-explode" -> {
                playerShoots = true
                explode = true
            }
            "opponent-explode" -> {
                playerShoots = false
                explode = true
            }
            "player-cigarette" -> {
                playerShoots = true
                explode = false
            }
            "opponent-cigarette" -> {
                playerShoots = false
                explode = false
            }
            else -> {
                playerShoots = Random.nextDouble() < 0.5
                explode = Random.nextDouble() < 0.08
            }
        }
    }

    private suspend fun preloadSounds() {
        for (name in listOf(
            "picoGasp", "picoShoot", "picoSpin", "picoCigarette",
            "picoCigarette2", "picoExplode",
        )) {
            sounds[name] = runCatching {
                host.preloadSound("sounds/cutscene/$name")
            }.getOrNull()
        }
    }

    private suspend fun loadMusic() {
        val stem = if (explode) "music/cutscene/cutscene2" else "music/cutscene/cutscene"
        val path = host.preloadMusic(stem) ?: run {
            println("KNF: philly cutscene music missing: $stem")
            return
        }
        val player = MusicPlayer()
        player.loop = true
        if (!player.load(path, 1.0)) {
            println("KNF: could not load philly cutscene music '$path'")
            player.dispose()
            return
        }
        music = player
    }

    private fun loadAnimate(path: String): AnimateAtlas {
        val dir = KNF_STAGE_LIBRARY.let { KnfFiles.findAnimateDir(it, path) }
        if (dir == null) {
            println("KNF: philly cutscene animate asset missing: $path")
        }
        val atlas = AnimateAtlas(dir ?: path)
        if (DEBUG) {
            val b = atlas.bounds
            println(
                "KNF: philly atlas $path dir=$dir frames=${atlas.poses.size} " +
                    "sprites=${atlas.sprites.size} bounds=(${b.minX},${b.minY},${b.width},${b.height})"
            )
            for (label in listOf("shootPlayer", "explodePlayer", "loopPlayer", "cigarettePlayer",
                "shootOpponent", "explodeOpponent", "loopOpponent", "cigaretteOpponent")) {
                println("KNF: philly label $label=${atlas.labelFrames(label).size}")
            }
        }
        return atlas
    }

    private fun loadCigarette(): SparrowSprite? {
        val path = KnfFiles.resolve("philly/erect/cigarette", ".xml", KNF_STAGE_LIBRARY)
        val atlas = path?.let { TextureAtlas.tryLoad(it) } ?: run {
            println("KNF: philly cutscene cigarette atlas missing")
            return null
        }
        val frames = atlas.framesByPrefix("cigarette spit")
        if (frames.isEmpty()) return null
        cigaretteFrameList = frames
        return SparrowSprite(atlas, frames, fps = 24.0, loop = false).also {
            it.playing = false
            it.visible = false
        }
    }

    private fun createSkipText() {
        val root = Container()
        val label = "Skip [ Enter ]"
        val font = vcrFont
        val width = font?.getTextBounds(40.0, label)?.width ?: 220.0
        val x = 936.0 * (Constants.GAME_WIDTH / 1280.0) - width
        val y = 618.0 * (Constants.GAME_HEIGHT / 720.0)

        val main = Text(label, 40.0, Colors.WHITE)
        font?.let { main.font = Resourceable.Fixed(it) }
        main.smoothing = false
        main.stroke = Stroke(Colors.BLACK, 2.0)
        main.x = x
        main.y = y

        root.addChild(main)
        root.alpha = 0.0
        skipRoot = root
        host.addCutsceneView(root)
    }

    private fun scheduleEvents(
        cigarettePos: Pair<Double, Double>,
        shooterPos: Pair<Double, Double>,
    ) {
        fun add(at: Double, action: () -> Unit) {
            events += TimedEvent(at, action)
        }
        fun sfx(name: String) {
            sounds[name]?.let { host.playSfx(it, 1.0) }
        }

        add(0.3) {
            sfx("picoGasp")
            sfx("picoGasp")
        }
        add(3.7) { sfx(if (explode) "picoCigarette2" else "picoCigarette") }
        add(4.0) { host.focusCamera(cigarettePos.first, cigarettePos.second, host.cameraZoom) }
        add(6.29) { sfx("picoShoot") }
        add(6.3) { host.focusCamera(shooterPos.first, shooterPos.second, host.cameraZoom) }
        add(8.75) {
            skipLocked = true
            canSkip = false
            skipFadeIn = false
            skipFadeOut = true
            skipFadeElapsed = 0.0
            host.focusCamera(cigarettePos.first, cigarettePos.second, host.cameraZoom)
            if (explode) host.playCharacterAnimation("gf", "drop70", true)
            if (explode) sfx("picoExplode")
        }
        add(10.33) { sfx("picoSpin") }
        add(11.2) {
            if (explode) {
                bloodPool?.visible = true
                bloodPool?.play("", loop = false)
            }
        }
        add(11.5) {
            if (!explode) {
                cigarette?.visible = true
                cigarette?.play(cigaretteFrameList, restart = true)
            }
        }
        add(13.0) { finishAnimation() }

        events.sortBy { it.at }
    }

    // ------------------------------------------------------------------
    // outcomes
    // ------------------------------------------------------------------

    private fun finishAnimation() {
        println("KNF: philly cutscene animation finished")
        music?.stop()
        musicFadeOut = false
        if (explode && playerShoots) {
            // Player shot the opponent; Pico's corpse and the blood stay in the stage.
            picoPlayer?.visible = false
            host.setCharacterVisible("bf", true)
            host.setOpponentVocalsVolume(0.0)
            keepStageLayer = true
            restoreBf = false
            restoreDad = false
            phase = Phase.DONE
            return
        }
        if (explode) {
            // Opponent shot the player: the original fades to black and calls endSong(true).
            picoOpponent?.visible = false
            host.setCharacterVisible("dad", true)
            host.setOpponentVocalsVolume(0.0)
            ending = true
            restoreBf = false
            restoreDad = false
            return
        }
        picoPlayer?.visible = false
        picoOpponent?.visible = false
        host.setCharacterVisible("bf", true)
        host.setCharacterVisible("dad", true)
        // The original leaves the spit-out cigarette in the stage.
        cigarette?.let { can ->
            can.removeFromParent()
            host.addStageView(can, cigaretteZ)
            can.addUpdater { dt -> can.update(dt.inWholeNanoseconds / 1_000_000_000.0) }
        }
        restoreBf = false
        restoreDad = false
        phase = Phase.DONE
    }

    private fun startSkip() {
        cutsceneSkipped = true
        explode = false
        ending = false
        skipLocked = true
        canSkip = false
        skipFadeIn = false
        skipFadeOut = true
        skipFadeElapsed = 0.0
        phase = Phase.SKIPPING
        musicFadeOut = true
        musicFadeStartVolume = music?.volume ?: 0.0
        musicFadeElapsed = 0.0
        skipRestored = false
        showBlack()
        host.setBlackScreenAlpha(0.0)
        skipBlackElapsed = 0.0
        println("KNF: philly cutscene skipped")
    }

    private fun updateSkip(dt: Double) {
        skipBlackElapsed += dt
        updateMusic(dt)

        if (!skipRestored && skipBlackElapsed >= SKIP_FADE_TIME) {
            skipRestored = true
            picoPlayer?.visible = false
            picoOpponent?.visible = false
            host.setCharacterVisible("bf", true)
            host.setCharacterVisible("dad", true)
            restoreBf = false
            restoreDad = false
            keepStageLayer = false
            skipRoot?.visible = false
            music?.stop()
            host.setHudVisible(true)
        }

        val fade = if (skipBlackElapsed < SKIP_FADE_TIME) {
            skipBlackElapsed / SKIP_FADE_TIME
        } else {
            1.0 - (skipBlackElapsed - SKIP_FADE_TIME) / SKIP_FADE_TIME
        }
        host.setBlackScreenAlpha(fade.coerceIn(0.0, 1.0))

        if (skipBlackElapsed >= SKIP_FADE_TIME * 2.0) {
            phase = Phase.DONE
        }
    }

    // ------------------------------------------------------------------
    // small helpers
    // ------------------------------------------------------------------

    private fun startSkipPrompt() {
        if (skipRoot == null) return
        skipFadeIn = true
        skipFadeOut = false
        skipFadeElapsed = 0.0
    }

    private fun updateSkipText(dt: Double) {
        val root = skipRoot ?: return
        if (skipFadeIn) {
            skipFadeElapsed += dt
            root.alpha = (skipFadeElapsed / 0.5).coerceIn(0.0, 1.0)
            if (root.alpha >= 1.0) {
                skipFadeIn = false
                canSkip = true
            }
        } else if (skipFadeOut) {
            skipFadeElapsed += dt
            root.alpha = (1.0 - skipFadeElapsed / 0.5).coerceIn(0.0, 1.0)
            if (root.alpha <= 0.0) {
                skipFadeOut = false
                root.visible = false
            }
        }
    }

    private fun updateMusic(dt: Double) {
        val player = music ?: return
        if (!musicFadeOut) return
        musicFadeElapsed += dt
        val t = (musicFadeElapsed / 0.5).coerceIn(0.0, 1.0)
        player.volume = musicFadeStartVolume * (1.0 - t)
        if (t >= 1.0) player.stop()
    }

    private fun showBlack() {
        if (blackShown) return
        blackShown = true
        host.showBlackScreen()
    }

    private companion object {
        const val KNF_STAGE_LIBRARY = "week3"
        const val DEATH_BLACK_START = 14.0
        const val DEATH_BLACK_FADE = 1.0
        const val DEATH_DONE = 15.0
        const val SKIP_FADE_TIME = 0.5

        val DEBUG: Boolean = System.getenv("KNF_DEBUG_PHILLY") == "1"

        val vcrFont: TtfFont? by lazy {
            KnfFiles.bytesOrNull("fonts/vcr.ttf")
                ?.let { runCatching { TtfFont(it, "vcr.ttf") }.getOrNull() }
        }
    }
}
