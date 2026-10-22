package nightfunkin.play

import korlibs.image.bitmap.Bitmap
import korlibs.image.bitmap.Bitmap32
import korlibs.image.color.Colors
import korlibs.korge.scene.Scene
import korlibs.korge.view.Container
import korlibs.korge.view.FixedSizeContainer
import korlibs.korge.view.Image
import korlibs.korge.view.SContainer
import korlibs.korge.view.SolidRect
import korlibs.korge.view.addUpdater
import korlibs.korge.view.image
import korlibs.korge.view.solidRect
import korlibs.math.geom.Anchor2D
import korlibs.math.geom.Size
import korlibs.math.geom.degrees
import kotlinx.coroutines.launch
import nightfunkin.audio.MusicPlayer
import nightfunkin.audio.SfxPlayer
import nightfunkin.core.AnimateAtlas
import nightfunkin.core.Constants
import nightfunkin.core.KnfFiles
import nightfunkin.core.KnfInput
import nightfunkin.core.SparrowSprite
import nightfunkin.core.TextureAtlas
import nightfunkin.ui.SongSelectScene
import nightfunkin.ui.secondsDouble
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.sin

/**
 * The results screen, ported from FNF 0.8.6's `ResultState`.
 *
 * This is intentionally a faithful *subset*: it shows one song's score and
 * rank, then returns to KNF's song list. Freeplay's rank-slam, story mode,
 * stickers and medals are out of scope.
 */
class ResultScene : Scene() {

    // ---- scene timing ---------------------------------------------------
    private var time = 0.0
    private val timers = ArrayList<TimedAction>()
    private val tweens = ArrayList<Tween>()
    private var busy = false
    private var ready = false
    private var exiting = false
    private var exitElapsed = 0.0

    // ---- score data -----------------------------------------------------
    private var rank: ScoringRank = ScoringRank.SHIT
    private var clearPercentTarget = 100
    private var barHeight = 148.0
    private var difficultyWidth = 0.0

    // ---- views ----------------------------------------------------------
    private lateinit var rankBg: SolidRect
    private var bgFlash: Image? = null
    private lateinit var songName: MonoBitmapText
    private var difficultyView: Image? = null
    private lateinit var clearPercentSmall: ClearPercentCounter
    private lateinit var clipLayer: FixedSizeContainer
    private lateinit var scrollLayer: Container
    private lateinit var clearLayer: Container
    private lateinit var characterLayer: Container
    private lateinit var verticalLayer: Container
    private lateinit var difficultyLayer: Container
    private lateinit var barLayer: Container
    private lateinit var effectsLayer: Container

    private var soundSystem: SpriteAnim? = null
    private var resultsAnim: SpriteAnim? = null
    private var ratingsPopin: SpriteAnim? = null
    private var scorePopin: SpriteAnim? = null
    private var highscoreNew: SpriteAnim? = null
    private var highscoreReplay = false
    private var scoreView: ResultScore? = null

    private val tallyCounters = ArrayList<TallyCounter>()
    private val characters = ArrayList<ResultCharacter>()
    private val rankRows = ArrayList<RankRow>()

    private var verticalRank: Container? = null
    private var verticalRankOffset = 0.0
    private var verticalRankPeriod = 0.0
    private var verticalRankSpeed = 0.0

    private var rankScrollBitmap: Bitmap? = null
    private var rankTextBitmap: Bitmap? = null

    // ---- title/idle scrolling ------------------------------------------
    private var movingSongStuff = false
    private var speedX = 0.0
    private var speedY = 0.0
    private var difficultyWorldX = 555.0
    private var smallWorldX = 0.0
    private var titleWorldX = 0.0

    // ---- audio ----------------------------------------------------------
    private val sfx = SfxPlayer()
    private val music = MusicPlayer()
    private val introMusic = MusicPlayer()
    private var introAvailable = false
    private var introStarted = false
    private var introStartedAt = 0.0
    private var loopStarted = false
    private var scrollMenuSound: String? = null
    private var confirmMenuSound: String? = null

    // =====================================================================
    // setup
    // =====================================================================

    override suspend fun SContainer.sceneInit() {
        val data = ResultRequest.data
        if (data == null) {
            println("KNF: ResultScene started without ResultRequest.data")
            launch { sceneContainer.changeTo<SongSelectScene>() }
            return
        }

        ready = true
        rank = Scoring.calculateRank(data.tallies)

        // Preload UI sounds before any timer can ask for them.
        scrollMenuSound = resolveSoundAsset("scrollMenu")
        confirmMenuSound = resolveSoundAsset("confirmMenu")
        scrollMenuSound?.let { runCatching { sfx.preload(it) } }
        confirmMenuSound?.let { runCatching { sfx.preload(it) } }

        val playerResults = ResultPlayerRegistry.resultsFor(data.characterId)
        val musicName = playerResults?.musicFor(rank) ?: "resultsNORMAL"
        val musicStem = "shared/music/$musicName/$musicName"
        val loopPath = KnfFiles.resolveSound(musicStem)
        val introPath = KnfFiles.resolveSound("$musicStem-intro")
        if (loopPath != null) {
            music.loop = true
            music.load(loopPath, 1.0)
        }
        if (introPath != null) {
            introAvailable = introMusic.load(introPath, 1.0)
        }

        // ---- background + flash -----------------------------------------
        val bgLayer = Container().also { addChild(it) }
        bgLayer.image(
            gradientBitmap(
                Constants.GAME_WIDTH,
                Constants.GAME_HEIGHT,
                0xFFFECC5C.toInt(),
                0xFFFDC05C.toInt(),
            )
        ).anchor = Anchor2D.TOP_LEFT
        bgFlash = bgLayer.image(
            gradientBitmap(
                Constants.GAME_WIDTH,
                Constants.GAME_HEIGHT,
                0xFFFFF1A6.toInt(),
                0xFFFFF1BE.toInt(),
            )
        ).also {
            it.anchor = Anchor2D.TOP_LEFT
            it.visible = false
            it.alpha = 0.0
        }

        // FNF rotates its "resultsScroll" camera by -3.8 degrees. Rotating a
        // container around the screen centre gives the same visual effect.
        scrollLayer = Container().also {
            it.x = Constants.GAME_WIDTH / 2.0
            it.y = Constants.GAME_HEIGHT / 2.0
            it.rotation = (-3.8).degrees
            addChild(it)
        }

        clearLayer = Container().also { addChild(it) }
        characterLayer = Container().also { addChild(it) }
        verticalLayer = Container().also { addChild(it) }
        // FNF's `LeftMaskShader` for the difficulty resolves to a negative
        // cutoff (`difficulty.x - 30` minus `difficulty.x`), so the sprite is
        // not actually masked; unlike the title it stays visible while it
        // scrolls past the x=520 title clip.
        difficultyLayer = Container().also { addChild(it) }

        // ---- title, difficulty, small clear percent ----------------------
        clipLayer = FixedSizeContainer(
            Size(Constants.GAME_WIDTH - CLIP_X, Constants.GAME_HEIGHT.toDouble()),
            true,
        ).also {
            it.x = CLIP_X
            it.y = 0.0
            addChild(it)
        }

        val title = MonoBitmapText(data.title)
        title.visible = false
        songName = title

        val diffPath = KnfFiles.resolve("shared:resultScreen/diff_${data.difficultyId}", ".png")
        val diffBitmap = diffPath?.let { KnfFiles.bitmapOrNull(it) }
        if (diffBitmap == null) {
            println("KNF: missing results difficulty image for '${data.difficultyId}'")
        } else {
            difficultyWidth = diffBitmap.width.toDouble()
            difficultyView = Image(diffBitmap).also {
                it.anchor = Anchor2D.TOP_LEFT
                it.x = difficultyWorldX
                difficultyLayer.addChild(it)
            }
        }

        clearPercentSmall = ClearPercentCounter(0.0, 0.0, 100, true)
        clearPercentSmall.visible = false
        clipLayer.addChild(clearPercentSmall)
        clipLayer.addChild(songName)

        // ---- everything that draws above the title/bar -------------------
        // FNF z-order: title/difficulty 1000, black bar 1010, sound system
        // 1100, then the results/score/highscore popins at 1200.
        barLayer = Container().also { addChild(it) }
        effectsLayer = Container().also { addChild(it) }

        soundSystem = makeSparrowAnim("resultScreen/soundSystem", "sound system", -15.0, -180.0)
            ?.also { effectsLayer.addChild(it.sprite) }
        resultsAnim = makeSparrowAnim("resultScreen/results", "results instance 1", -200.0, -10.0)
            ?.also { effectsLayer.addChild(it.sprite) }
        ratingsPopin = makeSparrowAnim("resultScreen/ratingsPopin", "Categories", -135.0, 135.0)
            ?.also { effectsLayer.addChild(it.sprite) }
        scorePopin = makeSparrowAnim("resultScreen/scorePopin", "tally score", -180.0, 515.0)
            ?.also { effectsLayer.addChild(it.sprite) }
        highscoreNew = makeSparrowAnim("resultScreen/highscoreNew", "highscoreAnim0", 44.0, 557.0)
            ?.also { effectsLayer.addChild(it.sprite) }

        after(6.0 / 24.0) { resultsAnim?.showAndPlay() }
        after(8.0 / 24.0) { soundSystem?.showAndPlay() }
        after(21.0 / 24.0) { ratingsPopin?.showAndPlay() }
        after(36.0 / 24.0) { scorePopin?.showAndPlay() }

        // ---- black bar ----------------------------------------------------
        val barBitmap = KnfFiles.bitmapOrNull("shared/images/resultScreen/topBarBlack.png")
        if (barBitmap != null) {
            barHeight = barBitmap.height.toDouble()
            val bar = Image(barBitmap).also {
                it.anchor = Anchor2D.TOP_LEFT
                it.y = -barHeight
                barLayer.addChild(it)
            }
            tween(3.0 / 24.0, 7.0 / 24.0, ::quartOut, { t ->
                bar.y = lerp(-barHeight, 0.0, t)
            }, {
                songName.visible = true
            })
        } else {
            songName.visible = true
        }

        // FNF applies this offset after loading the (normally 148 px) bar.
        val barOffset = barHeight - 148.0
        val angleRad = Math.toRadians(-4.4)
        speedX = -cos(angleRad)
        speedY = -sin(angleRad)
        timerThenSongName(1.0, false, barOffset, angleRad)

        // ---- characters ---------------------------------------------------
        val resolved = ArrayList<ResolvedCharacter>()
        for (anim in playerResults?.animationsFor(rank).orEmpty()) {
            if (!filterPasses(anim.filter)) continue
            val offsets = anim.offsets.orEmpty()
            val x = offsets.getOrElse(0) { 0.0 }
            val y = offsets.getOrElse(1) { 0.0 }
            val scale = anim.scale ?: 1.0
            val zIndex = anim.zIndex ?: 500
            val soundPath = anim.sound?.let { resolveSoundAsset(it) }
            soundPath?.let { runCatching { sfx.preload(it) } }

            val character: ResultCharacter? = when (anim.renderType.lowercase()) {
                "animateatlas" -> createAnimateCharacter(anim, x, y, scale, soundPath)
                "sparrow" -> createSparrowCharacter(anim, x, y, scale, soundPath)
                else -> null
            }
            if (character != null) {
                after(rank.bfDelay + character.delay) {
                    if (!character.view.visible) {
                        character.view.visible = true
                        character.start()
                        character.soundPath?.let { sfx.play(it) }
                    }
                }
                resolved += ResolvedCharacter(zIndex, character)
            }
        }
        resolved.sortedBy { it.zIndex }.forEach {
            it.character.view.visible = false
            characterLayer.addChild(it.character.view)
            characters += it.character
        }

        // ---- tallies ------------------------------------------------------
        val tallies = data.tallies
        val hitX = if (tallies.totalNotesHit >= 1000) 345.0 else 375.0
        val tallyAtlas = requireTallyAtlas()
        tallyCounters += TallyCounter(tallyAtlas, hitX, 150.0, tallies.totalNotesHit)
        tallyCounters += TallyCounter(tallyAtlas, hitX, 200.0, tallies.maxCombo)
        tallyCounters += TallyCounter(tallyAtlas, 230.0, 277.0, tallies.sick, Colors["#89E59E"])
        tallyCounters += TallyCounter(tallyAtlas, 210.0, 331.0, tallies.good, Colors["#89C9E5"])
        tallyCounters += TallyCounter(tallyAtlas, 190.0, 385.0, tallies.bad, Colors["#E6CF8A"])
        tallyCounters += TallyCounter(tallyAtlas, 220.0, 439.0, tallies.shit, Colors["#E68C8A"])
        tallyCounters += TallyCounter(tallyAtlas, 260.0, 493.0, tallies.missed, Colors["#C68AE6"])
        for ((index, counter) in tallyCounters.withIndex()) {
            counter.visible = false
            effectsLayer.addChild(counter)
            after(1.20 + 0.3 * index) {
                counter.visible = true
                val needed = counter.neededNumber
                tween(0.0, 0.5, ::quartOut, { t ->
                    counter.curNumber = needed * t
                })
            }
        }

        val scoreAtlas = TextureAtlas.tryLoad("shared/images/resultScreen/score-digital-numbers.xml")
        if (scoreAtlas != null) {
            val score = ResultScore(scoreAtlas, 35.0, 305.0, 10, data.score)
            score.visible = false
            effectsLayer.addChild(score)
            scoreView = score
            after(37.0 / 24.0) {
                score.visible = true
                score.animateNumbers()
                startRankTallySequence()
            }
        } else {
            after(37.0 / 24.0) { startRankTallySequence() }
        }

        // ---- rank assets --------------------------------------------------
        rankScrollBitmap = loadPng(rank.horTextAsset)
        rankTextBitmap = loadPng(rank.verTextAsset)

        after(rank.flashDelay) { displayRankText() }
        after(rank.bfDelay) { showSmallClearPercent() }
        after(rank.highscoreDelay) {
            if (data.isNewHighscore) {
                highscoreNew?.let {
                    it.showAndPlay()
                    highscoreReplay = true
                }
            } else {
                highscoreNew?.sprite?.visible = false
            }
        }
        after(rank.musicDelay) { startRankMusic() }

        // ---- the black fade used when leaving the results -----------------
        rankBg = solidRect(
            Constants.GAME_WIDTH.toDouble(),
            Constants.GAME_HEIGHT.toDouble(),
            Colors.BLACK,
        )
        rankBg.alpha = 0.0
        addChild(rankBg)
    }

    override suspend fun SContainer.sceneMain() {
        if (!ready) return

        val input = KnfInput(keys)
        addUpdater { dt ->
            val d = dt.secondsDouble()
            input.update()
            updateTimeline(d)
            updateScene(d)

            if (!busy && (input.accept || input.back)) startExit()
        }
    }

    override suspend fun sceneDestroy() {
        sfx.stopAll()
        music.dispose()
        introMusic.dispose()
    }

    // =====================================================================
    // frame update
    // =====================================================================

    private fun updateTimeline(dt: Double) {
        time += dt

        var timerIndex = 0
        while (timerIndex < timers.size) {
            val action = timers[timerIndex]
            if (time >= action.time) {
                timers.removeAt(timerIndex)
                action.action()
            } else {
                timerIndex++
            }
        }

        var tweenIndex = 0
        while (tweenIndex < tweens.size) {
            val tween = tweens[tweenIndex]
            if (time < tween.start) {
                tweenIndex++
                continue
            }
            val raw = if (tween.duration <= 0.0) 1.0 else ((time - tween.start) / tween.duration).coerceIn(0.0, 1.0)
            tween.apply(tween.ease(raw))
            if (raw >= 1.0) {
                tweens.removeAt(tweenIndex)
                tween.onComplete?.invoke()
            } else {
                tweenIndex++
            }
        }
    }

    private fun updateScene(dt: Double) {
        soundSystem?.sprite?.update(dt)
        resultsAnim?.sprite?.update(dt)
        ratingsPopin?.sprite?.update(dt)
        scorePopin?.sprite?.update(dt)
        highscoreNew?.sprite?.update(dt)
        scoreView?.update(dt)
        characters.forEach { it.update(dt) }

        if (highscoreReplay) {
            highscoreNew?.let {
                if (it.sprite.visible && !it.sprite.playing && it.sprite.frameIndex >= it.sprite.frameCount - 1) {
                    it.sprite.seekTo(16)
                }
            }
        }

        if (movingSongStuff) {
            val dx = speedX * 60.0 * dt
            val dy = speedY * 60.0 * dt

            difficultyWorldX += dx
            smallWorldX += dx
            titleWorldX += dx
            difficultyView?.x = difficultyWorldX
            difficultyView?.y += dy
            clearPercentSmall.x = smallWorldX - CLIP_X
            clearPercentSmall.y += dy
            songName.x = titleWorldX - CLIP_X
            songName.y += dy

            if (titleWorldX + songName.textWidth < 100.0) {
                timerThenSongName(3.0, true, barHeight - 148.0, Math.toRadians(-4.4))
            }
        }

        for (row in rankRows) {
            row.shift = (row.shift + row.velocity * dt) % row.period
            row.container.x = RANK_ROW_LOCAL_X + row.shift
        }

        if (verticalRankPeriod > 0.0 && verticalRankSpeed != 0.0) {
            verticalRank?.let {
                if (it.visible) {
                    verticalRankOffset = (verticalRankOffset + verticalRankSpeed * dt) % verticalRankPeriod
                    it.y = 100.0 + verticalRankOffset
                }
            }
        }

        // The intro (when it exists) hands over to the looping track.
        if (introStarted && time - introStartedAt > 0.05) {
            val ended = !introMusic.playing ||
                (introMusic.totalMs > 0.0 && introMusic.positionMs >= introMusic.totalMs - 50.0)
            if (ended) {
                introStarted = false
                introMusic.stop()
                startLoopMusic()
            }
        }

        if (exiting) {
            exitElapsed += dt
            val volume = 1.0 - (exitElapsed / EXIT_FADE_SECONDS).coerceIn(0.0, 1.0)
            music.volume = volume
            introMusic.volume = volume
        }
    }

    // =====================================================================
    // timed sequences
    // =====================================================================

    private fun startRankTallySequence() {
        bgFlash?.let {
            it.visible = true
            it.alpha = 1.0
            tween(0.0, 5.0 / 24.0, { t -> t }, { p -> it.alpha = 1.0 - p })
        }

        val data = ResultRequest.data ?: return
        val percent = data.tallies.completion * 100.0
        clearPercentTarget = floor(percent).toInt()
        val start = max(0, clearPercentTarget - 36)

        val counter = ClearPercentCounter(
            Constants.GAME_WIDTH / 2.0 + 190.0,
            Constants.GAME_HEIGHT / 2.0 - 70.0,
            start,
        )
        clearLayer.addChild(counter)

        var lastTicked = start
        tween(0.0, 58.0 / 24.0, ::quartOut, { t ->
            val value = Math.round(start + (clearPercentTarget - start) * t).toInt()
            if (value != lastTicked) {
                lastTicked = value
                scrollMenuSound?.let { sfx.play(it) }
            }
            counter.curNumber = value
        }, {
            counter.curNumber = clearPercentTarget
            confirmMenuSound?.let { sfx.play(it) }
            counter.flash(true)
            after(0.4) { counter.flash(false) }

            after(0.25) {
                tween(0.5, 0.5, ::quartOut, { t ->
                    counter.alpha = 1.0 - t
                }, {
                    counter.removeFromParent()
                })
            }
        })
    }

    private fun showSmallClearPercent() {
        clearPercentSmall.visible = true
        clearPercentSmall.flash(true)
        after(0.4) { clearPercentSmall.flash(false) }
        clearPercentSmall.curNumber = clearPercentTarget
        after(2.5) { movingSongStuff = true }
    }

    private fun displayRankText() {
        bgFlash?.let {
            it.visible = true
            it.alpha = 1.0
            tween(0.0, 14.0 / 24.0, { t -> t }, { p -> it.alpha = 1.0 - p })
        }

        rankTextBitmap?.let { bitmap ->
            val column = Container().also {
                it.x = Constants.GAME_WIDTH - 44.0
                it.y = 100.0
                verticalLayer.addChild(it)
            }
            val period = bitmap.height + 30.0
            for (index in -1..3) {
                column.image(bitmap).also {
                    it.anchor = Anchor2D.TOP_LEFT
                    it.y = index * period
                }
            }
            verticalRank = column
            verticalRankPeriod = period
            verticalRankOffset = 0.0
            verticalRankSpeed = 0.0

            // FlxFlicker.flicker(column, 2/24*3, 2/24, true).
            for (cycle in 0 until 3) {
                after((2.0 * cycle + 1.0) / 24.0) { column.visible = false }
                after((2.0 * cycle + 2.0) / 24.0) { column.visible = true }
            }
            after(30.0 / 24.0) { verticalRankSpeed = -80.0 }
        }

        rankScrollBitmap?.let { bitmap ->
            val period = bitmap.width + 10.0
            for (index in 0 until 12) {
                val row = Container().also {
                    it.x = RANK_ROW_LOCAL_X
                    it.y = 50.0 + 135.0 * index / 2.0 + 10.0 - Constants.GAME_HEIGHT / 2.0
                    scrollLayer.addChild(it)
                }
                for (copy in -2..4) {
                    row.image(bitmap).also {
                        it.anchor = Anchor2D.TOP_LEFT
                        it.x = copy * period
                    }
                }
                rankRows += RankRow(row, period, if (index % 2 == 0) -7.0 else 7.0)
            }
        }
    }

    private fun timerThenSongName(
        timerLength: Double,
        autoScroll: Boolean,
        barOffset: Double,
        angleRad: Double,
    ) {
        movingSongStuff = false

        difficultyWorldX = 555.0
        smallWorldX = difficultyWorldX + difficultyWidth + 60.0
        titleWorldX = smallWorldX + 94.0

        val diffTargetY = 122.0 + barOffset
        difficultyView?.let { view ->
            view.x = difficultyWorldX
            view.y = -view.height
            tween(0.8, 0.5, ::expoOut, { t ->
                view.y = lerp(-view.height, diffTargetY, t)
            })
        }

        clearPercentSmall.x = smallWorldX - CLIP_X
        clearPercentSmall.y = -SMALL_CLEAR_HEIGHT
        tween(0.85, 0.5, ::expoOut, { t ->
            clearPercentSmall.y = lerp(-SMALL_CLEAR_HEIGHT, diffTargetY - 5.0, t)
        })

        val fuckedUpNumber = -(songName.textWidth * 0.5) * sin(angleRad) - 10.0
        val titleTargetY = (122.0 - 25.0 - fuckedUpNumber) + barOffset
        songName.x = titleWorldX - CLIP_X
        songName.y = -SONG_NAME_HEIGHT
        tween(0.9, 0.5, ::expoOut, { t ->
            songName.y = lerp(-SONG_NAME_HEIGHT, titleTargetY, t)
        })

        after(timerLength) {
            val targetX = speedX
            val targetY = speedY
            speedX = 0.0
            speedY = 0.0
            tween(0.0, 0.7, ::quadIn, { t ->
                speedX = lerp(0.0, targetX, t)
                speedY = lerp(0.0, targetY, t)
            })
            movingSongStuff = autoScroll
        }
    }

    private fun startRankMusic() {
        if (introAvailable && introMusic.exists) {
            introStarted = true
            introStartedAt = time
            introMusic.play()
        } else {
            startLoopMusic()
        }
    }

    private fun startLoopMusic() {
        if (loopStarted || !music.exists) return
        loopStarted = true
        music.play()
    }

    // =====================================================================
    // input / transition
    // =====================================================================

    private fun startExit() {
        if (busy) return
        busy = true
        exiting = true
        exitElapsed = 0.0
        tween(0.0, EXIT_FADE_SECONDS, ::expoOut, { t ->
            rankBg.alpha = t
        }, {
            launch { sceneContainer.changeTo<SongSelectScene>() }
        })
    }

    // =====================================================================
    // asset helpers
    // =====================================================================

    private fun loadPng(asset: String): Bitmap? {
        val path = KnfFiles.resolve(asset, ".png") ?: return null
        return KnfFiles.bitmapOrNull(path)
    }

    private fun makeSparrowAnim(asset: String, prefix: String, x: Double, y: Double): SpriteAnim? {
        val path = KnfFiles.resolve("shared:$asset", ".xml") ?: return null
        val atlas = TextureAtlas.tryLoad(path) ?: return null
        val frames = atlas.framesByPrefix(prefix)
        if (frames.isEmpty()) {
            println("KNF: results atlas 'shared:$asset' has no '$prefix' frames")
            return null
        }
        val sprite = SparrowSprite(atlas, frames, 24.0, false)
        sprite.visible = false
        sprite.playing = false
        sprite.x = x
        sprite.y = y
        return SpriteAnim(sprite, frames)
    }

    private fun resolveSoundAsset(asset: String): String? {
        val library = KnfFiles.library(asset)
        val name = asset.substringAfter(':')
        val stem = if (library == null) "preload/sounds/$name" else "$library/sounds/$name"
        return KnfFiles.resolveSound(stem)
    }

    private fun filterPasses(filter: String): Boolean = when (filter) {
        "both" -> true
        "naughty" -> NAUGHTYNESS
        "safe" -> !NAUGHTYNESS
        else -> true
    }

    private fun createAnimateCharacter(
        anim: PlayerResultsAnimationData,
        x: Double,
        y: Double,
        scale: Double,
        soundPath: String?,
    ): ResultCharacter? {
        val asset = anim.assetPath ?: scriptAssetPath(anim.scriptClass) ?: return null
        val library = KnfFiles.library(asset) ?: ""
        val raw = asset.substringAfter(':')
        val dir = KnfFiles.findAnimateDir(library, raw) ?: run {
            println("KNF: missing results animate atlas '$asset'")
            return null
        }

        val atlas = runCatching { AnimateAtlas(dir) }.getOrNull() ?: return null
        if (atlas.poses.isEmpty()) return null

        var startLabel = anim.startFrameLabel
        var loopLabel = anim.loopFrameLabel
        var loopFrame = anim.loopFrame
        var looped = anim.looped ?: true

        // PicoGoodResults randomly picks one of three intros, then loops.
        if (anim.scriptClass == "PicoGoodResults") {
            val roll = Math.random()
            startLabel = when {
                roll < 0.05 -> "intro fat gf"
                roll < 0.25 -> "intro cass"
                else -> "intro"
            }
            loopLabel = "loop"
            loopFrame = null
            looped = true
        }

        val sprite = ResultAnimateSprite(
            atlas,
            startFrameLabel = startLabel,
            loopFrameLabel = loopLabel,
            loopFrame = loopFrame,
            looped = looped,
            scale = scale,
        )
        sprite.x = x
        sprite.y = y
        return AnimateCharacter(sprite, anim.delay ?: 0.0, soundPath)
    }

    private fun createSparrowCharacter(
        anim: PlayerResultsAnimationData,
        x: Double,
        y: Double,
        scale: Double,
        soundPath: String?,
    ): ResultCharacter? {
        val asset = anim.assetPath ?: scriptAssetPath(anim.scriptClass) ?: return null
        val path = KnfFiles.resolve(asset, ".xml") ?: return null
        val atlas = TextureAtlas.tryLoad(path) ?: return null
        if (atlas.framesByPrefix("").isEmpty()) return null
        val sprite = ResultSparrowSprite(atlas, anim.loopFrame, scale)
        sprite.x = x
        sprite.y = y
        return SparrowCharacter(sprite, anim.delay ?: 0.0, soundPath)
    }

    private fun requireTallyAtlas(): TextureAtlas =
        TextureAtlas.load("shared/images/resultScreen/tallieNumber.xml")

    private fun scriptAssetPath(scriptClass: String?): String? = when (scriptClass) {
        "BFBedPerfectResults" -> "shared:resultScreen/results-bf/resultsPERFECT/bed"
        "BFShitResults" -> "shared:resultScreen/results-bf/resultsSHIT"
        "PicoPerfectResults" -> "shared:resultScreen/results-pico/resultsPERFECT"
        "PicoGreatResults" -> "shared:resultScreen/results-pico/resultsGREAT"
        "PicoGoodResults" -> "shared:resultScreen/results-pico/resultsGOOD"
        else -> null
    }

    // =====================================================================
    // small helpers
    // =====================================================================

    private fun after(delay: Double, action: () -> Unit) {
        timers += TimedAction(time + delay, action)
    }

    private fun tween(
        delay: Double,
        duration: Double,
        ease: (Double) -> Double,
        apply: (Double) -> Unit,
        onComplete: (() -> Unit)? = null,
    ) {
        tweens += Tween(time + delay, duration, ease, apply, onComplete)
    }

    private fun lerp(a: Double, b: Double, t: Double): Double = a + (b - a) * t

    private fun quartOut(t: Double): Double {
        val inv = 1.0 - t
        return 1.0 - inv * inv * inv * inv
    }

    private fun expoOut(t: Double): Double =
        if (t >= 1.0) 1.0 else 1.0 - Math.pow(2.0, -10.0 * t)

    private fun quadIn(t: Double): Double = t * t

    private fun gradientBitmap(width: Int, height: Int, top: Int, bottom: Int): Bitmap32 {
        val pixels = IntArray(width * height)
        for (y in 0 until height) {
            val t = if (height <= 1) 0.0 else y.toDouble() / (height - 1)
            val a = lerpChannel((top ushr 24) and 0xFF, (bottom ushr 24) and 0xFF, t)
            val r = lerpChannel((top ushr 16) and 0xFF, (bottom ushr 16) and 0xFF, t)
            val g = lerpChannel((top ushr 8) and 0xFF, (bottom ushr 8) and 0xFF, t)
            val b = lerpChannel(top and 0xFF, bottom and 0xFF, t)
            // Bitmap32 stores 0xAABBGGRR.
            val packed = (a shl 24) or (b shl 16) or (g shl 8) or r
            val start = y * width
            for (x in 0 until width) pixels[start + x] = packed
        }
        return Bitmap32(width, height, pixels, true)
    }

    private fun lerpChannel(a: Int, b: Int, t: Double): Int =
        Math.round(a + (b - a) * t).toInt()

    // =====================================================================
    // data holders
    // =====================================================================

    private class TimedAction(val time: Double, val action: () -> Unit)

    private class Tween(
        val start: Double,
        val duration: Double,
        val ease: (Double) -> Double,
        val apply: (Double) -> Unit,
        val onComplete: (() -> Unit)?,
    )

    private class SpriteAnim(
        val sprite: SparrowSprite,
        val frames: List<TextureAtlas.AtlasFrame>,
    ) {
        fun showAndPlay() {
            sprite.visible = true
            sprite.play(frames, true)
        }
    }

    private class RankRow(val container: Container, val period: Double, val velocity: Double) {
        var shift = 0.0
    }

    private class ResolvedCharacter(val zIndex: Int, val character: ResultCharacter)

    private interface ResultCharacter {
        val view: Container
        val delay: Double
        val soundPath: String?
        fun start()
        fun update(dt: Double)
    }

    private class AnimateCharacter(
        val sprite: ResultAnimateSprite,
        override val delay: Double,
        override val soundPath: String?,
    ) : ResultCharacter {
        override val view: Container get() = sprite
        override fun start() = sprite.start()
        override fun update(dt: Double) = sprite.update(dt)
    }

    private class SparrowCharacter(
        val sprite: ResultSparrowSprite,
        override val delay: Double,
        override val soundPath: String?,
    ) : ResultCharacter {
        override val view: Container get() = sprite
        override fun start() = sprite.start()
        override fun update(dt: Double) = sprite.update(dt)
    }

    private companion object {
        /** Results title/difficulty are masked to the right of this x. */
        const val CLIP_X = 520.0
        const val SONG_NAME_HEIGHT = 61.0
        const val SMALL_CLEAR_HEIGHT = 72.0
        const val RANK_ROW_LOCAL_X = 320.0 - Constants.GAME_WIDTH / 2.0

        /** Preferences.naughtyness defaults to true in FNF 0.8.6. */
        const val NAUGHTYNESS = true

        const val EXIT_FADE_SECONDS = 0.5
    }
}
