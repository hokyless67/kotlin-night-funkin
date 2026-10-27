package nightfunkin.ui

import korlibs.image.bitmap.Bitmap
import korlibs.image.bitmap.sliceWithSize
import korlibs.image.color.Colors
import korlibs.image.color.RGBA
import korlibs.image.font.TtfFont
import korlibs.io.resources.Resourceable
import korlibs.korge.blend.BlendMode
import korlibs.korge.scene.Scene
import korlibs.korge.view.Container
import korlibs.korge.view.Image
import korlibs.korge.view.SContainer
import korlibs.korge.view.SolidRect
import korlibs.korge.view.addUpdater
import korlibs.korge.view.solidRect
import korlibs.korge.view.text
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import nightfunkin.audio.MenuMusic
import nightfunkin.audio.SfxPlayer
import nightfunkin.audio.SongPreviewPlayer
import nightfunkin.core.AnimateAtlas
import nightfunkin.core.AnimateSpriteView
import nightfunkin.core.Constants
import nightfunkin.core.KnfFiles
import nightfunkin.core.KnfInput
import nightfunkin.core.Save
import nightfunkin.core.SparrowSprite
import nightfunkin.core.TextureAtlas
import nightfunkin.play.PlayRequest
import nightfunkin.play.PlayScene
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin

/**
 * FNF 0.8.6's Freeplay menu.
 *
 * This is the real `FreeplayState` layout rather than a plain text list:
 * the pink backing card slides in, BF runs his turntable intro, and the song
 * capsules arc down the left side while the album art sits on the right.
 *
 * KNF has no character-select substate yet, so the default `bf` Freeplay style
 * is used. Q/E still cycle song variations (default/erect/pico/bf), preserving
 * the behaviour of the previous song list.
 */
class SongSelectScene : Scene() {

    private val entries: List<FreeplayEntry> by lazy { FreeplayCatalog.entries }
    private val menuMusic = MenuMusic.player
    private val preview = SongPreviewPlayer()
    private val sfx = SfxPlayer()

    private var previewJob: Job? = null
    private var previewGeneration = 0

    private var fiveBySeven: TtfFont? = null
    private var youreGone: TtfFont? = null
    private var vcr: TtfFont? = null

    private var selected = 1
    private var currentDifficulty = rememberedDifficulty
    private var currentVariation = rememberedVariation

    private val allCapsules = ArrayList<FreeplayCapsule>()
    private val capsuleBySong = HashMap<String, FreeplayCapsule>()
    private var randomCapsule: FreeplayCapsule? = null
    private var visible: MutableList<FreeplayEntry?> = mutableListOf()
    private var viewCapsules: MutableList<FreeplayCapsule> = mutableListOf()

    private lateinit var cardLayer: Container
    private lateinit var capsuleLayer: Container
    private lateinit var albumLayer: Container
    private lateinit var selectorLayer: Container
    private lateinit var uiLayer: Container

    private var pinkBack: Image? = null
    private var cardGlow: Image? = null
    private var backingImage: Image? = null
    private var orangeStrip: SolidRect? = null
    private var orangeStripLeft: SolidRect? = null
    private var cardSlideElapsed = 0.0
    private var cardSlideFinished = false
    private var glowElapsed = 0.0
    private var glowActive = false
    private var backingFadeActive = false
    private var backingFadeElapsed = 0.0
    private var difficultySlideElapsed = 0.0
    private var difficultySlideActive = false
    private var djIntroDone = false
    private var djIdleStarted = false

    private var djAtlas: AnimateAtlas? = null
    private var dj: AnimateSpriteView? = null

    private var difficultyView: Image? = null
    private var shownDifficulty: String? = null
    private var selectors: List<SparrowSprite> = emptyList()

    private var albumArt: Image? = null
    private var albumTitle: SparrowSprite? = null
    private var shownAlbumId: String? = null

    private var headerBg: SolidRect? = null
    private var headerText: korlibs.korge.view.Text? = null
    private var charHint: korlibs.korge.view.Text? = null

    private var highscoreSprite: SparrowSprite? = null
    private var scoreDigits: List<ScoreDigit> = emptyList()
    private var clearBox: Image? = null
    private var completionBitmap: Bitmap? = null
    private val completionRects = HashMap<Int, IntArray>()
    private var completionDigits: List<Image> = emptyList()
    private var difficultyDots: List<Image> = emptyList()
    private var albumStarsView: AnimateSpriteView? = null
    private var starsAtlas: AnimateAtlas? = null

    private var displayedScore = 0.0
    private var targetScore = 0
    private var displayedCompletion = 0.0
    private var targetCompletion = 0.0
    private var hintTimer = 0.0

    private lateinit var statusLine: korlibs.korge.view.Text

    private var statusTimer = 0.0

    private var upHold = 0.0
    private var downHold = 0.0

    private var transitioning = false
    private var leaving = false
    private var leaveElapsed = 0.0
    private var fadeRect: SolidRect? = null

    override suspend fun SContainer.sceneInit() {
        Save.load()
        fiveBySeven = loadFont("fonts/5by7.ttf")
        youreGone = loadFont("fonts/YoureGone-Regular.otf")
        vcr = loadFont("fonts/vcr.ttf")

        ensureDifficultyForVariation()

        solidRect(Constants.GAME_WIDTH.toDouble(), Constants.GAME_HEIGHT.toDouble(), Colors.BLACK)

        cardLayer = Container().also { addChild(it) }
        buildCard()

        val djLayer = Container().also { addChild(it) }
        buildDj(djLayer)

        val difficultyLayer = Container().also { addChild(it) }
        buildDifficulty(difficultyLayer)

        backingImage = buildBackingImage()
        backingImage?.let {
            it.alpha = 0.0
            addChild(it)
        }

        capsuleLayer = Container().also { addChild(it) }
        buildCapsules()

        albumLayer = Container().also {
            it.visible = false
            addChild(it)
        }

        selectorLayer = Container().also { addChild(it) }
        buildSelectors(selectorLayer)

        uiLayer = Container().also {
            it.visible = false
            addChild(it)
        }
        buildTopUi()

        // If the DJ atlas could not be loaded, do not leave the rest of the UI
        // hidden forever.
        if (dj == null) onDjIntroDone()

        // Throw-away overlay for the short exit fade.
        fadeRect = solidRect(
            Constants.GAME_WIDTH.toDouble(),
            Constants.GAME_HEIGHT.toDouble(),
            Colors.BLACK,
        ).also {
            it.alpha = 0.0
        }

        loadSounds()
        menuMusic.pause()

        rebuildVisible(
            preserveSongId = rememberedSongId,
            initial = true,
        )
        selectRememberedEntry()
        updateSelectionPositions()
        updateTopUi()
        updateAlbum()
        requestPreview()

        if (entries.isEmpty()) {
            setStatus("No songs found - is the FNF 'assets' folder reachable?")
        }
    }

    // ======================================================================
    // Freeplay scenery
    // ======================================================================

    private fun buildCard() {
        val pinkBitmap = KnfFiles.bitmapOrNull("preload/images/freeplay/pinkBack.png")
        if (pinkBitmap != null) {
            pinkBack = Image(pinkBitmap).also {
                it.x = -pinkBitmap.width.toDouble()
                it.y = 0.0
                it.tint = RGBA(0xFF, 0xD4, 0xE9)
                cardLayer.addChild(it)
            }
        }

        val width = pinkBack?.let { it.bitmap.width.toDouble() } ?: 524.0
        orangeStrip = cardLayer.solidRect(width, 75.0, RGBA(0xFE, 0xDA, 0x00)).also {
            it.x = 84.0
            it.y = 440.0
            it.visible = false
        }
        orangeStripLeft = cardLayer.solidRect(100.0, 75.0, RGBA(0xFF, 0xD4, 0x00)).also {
            it.x = 0.0
            it.y = 440.0
            it.visible = false
        }

        // BackingCard adds the glow last and uses additive blending, so it sits
        // over the pink plate instead of disappearing behind it.
        val glowBitmap = KnfFiles.bitmapOrNull("preload/images/freeplay/cardGlow.png")
        if (glowBitmap != null) {
            cardGlow = Image(glowBitmap).also {
                it.x = -30.0
                it.y = -30.0
                it.alpha = 0.0
                it.blendMode = BlendMode.ADD
                cardLayer.addChild(it)
            }
        }
    }

    private fun buildDj(parent: Container) {
        val atlas = runCatching {
            AnimateAtlas("preload/images/freeplay/freeplay-boyfriend")
        }.getOrNull() ?: return
        if (atlas.poses.isEmpty()) return

        djAtlas = atlas
        val view = AnimateSpriteView(atlas, isPixel = false, applyStageMatrix = true)
        view.x = 0.0
        view.y = 0.0
        dj = view
        parent.addChild(view)

        view.onFinish = { onDjIntroDone() }

        if (hasDjLabel("Intro")) {
            view.play("Intro", loop = false)
        } else {
            onDjIntroDone()
        }
    }

    private fun buildBackingImage(): Image? {
        val bitmap = KnfFiles.bitmapOrNull("preload/images/freeplay/freeplayBGweek1-bf.png") ?: return null
        val targetHeight = Constants.GAME_HEIGHT + 1.0
        val scale = targetHeight / bitmap.height
        val width = pinkBack?.let { it.bitmap.width.toDouble() } ?: 524.0
        return Image(bitmap).also {
            it.scaleX = scale
            it.scaleY = scale
            it.x = width * 0.74
            it.y = 0.0
        }
    }

    private fun buildDifficulty(parent: Container) {
        val bitmap = difficultyBitmap(currentDifficulty)
        difficultyView = bitmap?.let {
            Image(it).also { image ->
                // `grpDifficulties` starts at x=-300 and tweens to x=90. The
                // children are moved by the group setter, then each child is
                // tweened to the same world x. FlxTypedSpriteGroup.x is only
                // an offset holder, so the final rendered x is 90 (not 180).
                // The source only changes the sprite's hitbox height (2.5x),
                // not its graphic scale.
                image.x = -300.0
                image.y = 80.0
                image.smoothing = false
                parent.addChild(image)
            }
        }
        shownDifficulty = currentDifficulty
    }

    private fun buildSelectors(parent: Container) {
        val atlas = TextureAtlas.tryLoad(
            "preload/images/freeplay/freeplaySelector/freeplaySelector.xml",
        ) ?: return
        val frames = atlas.framesByPrefix("arrow pointer loop")
        if (frames.isEmpty()) return

        val left = SparrowSprite(atlas, frames, fps = 24.0, loop = true).also {
            it.x = 20.0
            it.y = 70.0
            it.alpha = 0.0
            parent.addChild(it)
        }
        val right = SparrowSprite(atlas, frames, fps = 24.0, loop = true).also {
            it.x = 374.0
            it.y = 70.0
            it.scaleX = -1.0
            it.alpha = 0.0
            parent.addChild(it)
        }
        selectors = listOf(left, right)
    }

    private fun buildCapsules() {
        randomCapsule = FreeplayCapsule(
            entry = null,
            fiveBySeven = fiveBySeven,
            youreGone = youreGone,
        ).also {
            it.visible = false
            it.x = Constants.GAME_WIDTH.toDouble()
            capsuleLayer.addChild(it)
            allCapsules += it
        }

        for (entry in entries) {
            if (capsuleBySong.containsKey(entry.song.id)) continue
            val capsule = FreeplayCapsule(
                entry = entry,
                fiveBySeven = fiveBySeven,
                youreGone = youreGone,
            ).also {
                it.visible = false
                it.x = Constants.GAME_WIDTH.toDouble()
                capsuleLayer.addChild(it)
            }
            capsuleBySong[entry.song.id] = capsule
            allCapsules += capsule
        }
    }

    // ======================================================================
    // Top UI
    // ======================================================================

    private fun buildTopUi() {
        // `freeplayTxtBg`: the black plate behind the top-left heading. FNF
        // sizes it from the rendered FlxText, so use the Korge text bounds.
        headerBg = uiLayer.solidRect(250.0, 72.0, Colors.BLACK).also {
            it.x = 0.0
            it.y = 0.0
        }

        headerText = uiLayer.text("FREEPLAY", textSize = 48.0, color = Colors.WHITE).also {
            vcr?.let { font -> it.font = Resourceable.Fixed(font) }
            it.x = 8.0
            it.y = 8.0
        }

        val ost = uiLayer.text("OFFICIAL OST", textSize = 48.0, color = Colors.WHITE)
        vcr?.let { ost.font = Resourceable.Fixed(it) }
        ost.x = Constants.GAME_WIDTH - 8.0 - ost.width
        ost.y = 8.0

        // FreeplayState's `charSelectHint`. KNF cycles variations rather than
        // characters for now, so the wording describes the key that exists.
        charHint = uiLayer.text(
            "Press [ Q / E ] to change variations",
            textSize = 32.0,
            color = RGBA(0x5F, 0x5F, 0x5F),
        ).also {
            fiveBySeven?.let { font -> it.font = Resourceable.Fixed(font) }
            it.x = 0.0
            it.y = 18.0
            it.alpha = 0.6
        }

        buildLetterSort()
        buildHighscore()
        buildScore()
        buildClearBox()
        buildDifficultyDots()

        // KNF-only status line for missing charts/assets; FNF has no HUD text
        // here, but keeping it invisible until something fails does not alter
        // the normal Freeplay layout.
        statusLine = uiLayer.text("", textSize = 20.0, color = Colors["#66FF66"])
        fiveBySeven?.let { statusLine.font = Resourceable.Fixed(it) }
        statusLine.x = 56.0
        statusLine.y = 700.0
    }

    private fun buildLetterSort() {
        val atlas = TextureAtlas.tryLoad("preload/images/freeplay/letterStuff.xml")
        for (i in 0 until 5) {
            val dark = when (i) {
                0, 4 -> 0xAA
                1, 3 -> 0x80
                else -> 0xFF
            }
            val frame = when (i) {
                0 -> atlas?.frame("# instance 10000")
                1 -> atlas?.frame("fav instance 10000")
                2 -> atlas?.frame("ALL instance 10000")
                else -> null
            }
            if (frame != null && atlas != null) {
                Image(atlas.slice(frame)).also {
                    it.smoothing = false
                    it.x = 400.0 + i * 80.0
                    it.y = 65.0
                    it.tint = RGBA(dark, dark, dark)
                    uiLayer.addChild(it)
                }
            } else {
                val label = when (i) {
                    0 -> "#"
                    1 -> "fav"
                    2 -> "ALL"
                    3 -> "A-B"
                    else -> "C-D"
                }
                uiLayer.text(label, textSize = 36.0, color = RGBA(dark, dark, dark)).also {
                    fiveBySeven?.let { font -> it.font = Resourceable.Fixed(font) }
                    it.x = 400.0 + i * 80.0
                    it.y = 65.0
                }
            }
        }

        // Separators and the two mini arrows are part of the letter strip in
        // `LetterSort.hx`. They sit between the five slots.
        val separator = KnfFiles.bitmapOrNull("preload/images/freeplay/seperator.png")
        if (separator != null) {
            for (i in 0 until 4) {
                Image(separator).also {
                    it.smoothing = false
                    it.x = 460.0 + i * 80.0
                    it.y = 95.0
                    val shade = if (i == 1 || i == 2) 0x80 else 0xAA
                    it.tint = RGBA(shade, shade, shade)
                    uiLayer.addChild(it)
                }
            }
        }
        val arrow = KnfFiles.bitmapOrNull("preload/images/freeplay/miniArrow.png")
        if (arrow != null) {
            Image(arrow).also {
                it.smoothing = false
                it.x = 380.0
                it.y = 90.0
                it.scaleX = -1.0
                it.alpha = 0.8
                uiLayer.addChild(it)
            }
            Image(arrow).also {
                it.smoothing = false
                it.x = 780.0
                it.y = 90.0
                it.alpha = 0.8
                uiLayer.addChild(it)
            }
        }
    }

    private fun buildHighscore() {
        val atlas = TextureAtlas.tryLoad("preload/images/freeplay/highscore.xml") ?: return
        val frames = atlas.framesByPrefix("highscore small instance 1")
        if (frames.isEmpty()) return
        highscoreSprite = SparrowSprite(atlas, frames, fps = 24.0, loop = false).also {
            it.x = 860.0
            it.y = 70.0
            it.visible = false
            uiLayer.addChild(it)
        }
    }

    private fun buildScore() {
        val atlas = TextureAtlas.tryLoad("preload/images/digital_numbers.xml") ?: return
        val frames = numberFrames(atlas, "DIGITAL")
        if (frames.isEmpty()) return
        scoreDigits = (0 until 7).map { i ->
            val frame = frames[0] ?: frames.values.first()
            val image = Image(atlas.slice(frame)).also {
                it.x = 927.0 + 45.0 * i
                it.y = 60.0
                it.scaleX = 0.4
                it.scaleY = 0.4
                it.visible = false
                uiLayer.addChild(it)
            }
            ScoreDigit(image, atlas, frames)
        }
    }

    private fun buildClearBox() {
        clearBox = KnfFiles.bitmapOrNull("preload/images/freeplay/clearBox.png")?.let {
            Image(it).also { image ->
                image.x = 1165.0
                image.y = 65.0
                image.visible = false
                uiLayer.addChild(image)
            }
        }

        // `freeplay-clear.xml` points at the original Animate sheet name, but
        // the shipped font PNG is split out as `freeplay-clear.png`. Parse the
        // tiny digit sheet directly instead of relying on imagePath.
        val bitmap = KnfFiles.bitmapOrNull("preload/images/fonts/freeplay-clear.png") ?: return
        completionBitmap = bitmap
        val rects = listOf(
            intArrayOf(4, 4, 25, 24),
            intArrayOf(33, 3, 10, 24),
            intArrayOf(47, 3, 24, 24),
            intArrayOf(75, 3, 24, 24),
            intArrayOf(3, 32, 24, 24),
            intArrayOf(32, 31, 24, 24),
            intArrayOf(61, 31, 24, 24),
            intArrayOf(90, 31, 24, 24),
            intArrayOf(4, 59, 24, 24),
            intArrayOf(32, 59, 24, 24),
        )
        for ((digit, rect) in rects.withIndex()) completionRects[digit] = rect
        completionDigits = (0 until 3).map { i ->
            Image(bitmap.sliceWithSize(4, 4, 25, 24)).also {
                it.x = 1185.0 + 24.0 * i
                it.y = 87.0
                it.visible = false
                uiLayer.addChild(it)
            }
        }
    }

    private fun buildDifficultyDots() {
        val dot = KnfFiles.bitmapOrNull("preload/images/freeplay/seperator.png") ?: return
        difficultyDots = (0 until 5).map { i ->
            Image(dot).also {
                it.smoothing = false
                it.x = 260.0 - 14.7 * 4.0 + 30.0 * i - 75.0
                it.y = 170.0
                it.visible = false
                uiLayer.addChild(it)
            }
        }
    }

    private fun updateTopUi() {
        updateDifficultyVisual()
        updateSelectionStats()
        updateDifficultyDots()
    }

    private fun updateSelectionStats() {
        val entry = selectedEntry()
        if (entry == null) {
            targetScore = 0
            targetCompletion = 0.0
            updateScoreVisual()
            updateCompletionVisual()
            updateAlbumStars()
            return
        }

        val variation = resolvedVariation(entry)
        val key = "${entry.song.id}:${currentDifficulty}:$variation"
        targetScore = Save.highscore(key)
        targetCompletion = Save.completion(key).coerceIn(0f, 1f).toDouble()
        updateScoreVisual()
        updateCompletionVisual()
        updateAlbumStars()
    }

    private fun updateScoreVisual() {
        var value = displayedScore.coerceIn(0.0, 9_999_999.0).toInt()
        for (i in scoreDigits.indices.reversed()) {
            scoreDigits[i].set(value % 10)
            value /= 10
        }
    }

    private fun updateCompletionVisual() {
        val percent = kotlin.math.floor(displayedCompletion * 100.0).toInt().coerceIn(0, 100)
        val text = percent.toString()
        // AtlasText's right-alignment offsets from FreeplayState.update.
        val offset = when (text.length) {
            3 -> 10.0
            1 -> -24.0
            else -> 0.0
        }
        val baseX = 1185.0 - offset
        for (i in completionDigits.indices) {
            val image = completionDigits[i]
            val digit = text.getOrNull(i)
            image.visible = digit != null
            image.x = baseX + 24.0 * i
            if (digit != null) {
                val rect = completionRects[digit - '0']
                val bitmap = completionBitmap
                if (rect != null && bitmap != null) {
                    image.bitmap = bitmap.sliceWithSize(rect[0], rect[1], rect[2], rect[3])
                }
            }
        }
    }

    private fun updateDifficultyDots() {
        if (difficultyDots.isEmpty()) return
        val full = listOf("easy", "normal", "hard", "erect", "nightmare")
        val selectedIndex = full.indexOf(currentDifficulty)
        val selectable = if (currentVariation == "erect") {
            setOf("erect", "nightmare")
        } else {
            setOf("easy", "normal", "hard")
        }
        for ((i, dot) in difficultyDots.withIndex()) {
            val available = full[i] in selectable
            dot.visible = true
            dot.alpha = if (available) 1.0 else 0.33
            val shade = when {
                i == selectedIndex -> 0xFA
                available -> 0x48
                else -> 0x12
            }
            dot.tint = RGBA(shade, shade, shade)
        }
    }

    private fun numberFrames(
        atlas: TextureAtlas,
        suffix: String,
    ): Map<Int, TextureAtlas.AtlasFrame> =
        listOf("ZERO", "ONE", "TWO", "THREE", "FOUR", "FIVE", "SIX", "SEVEN", "EIGHT", "NINE")
            .mapIndexedNotNull { index, name ->
                atlas.framesByPrefix("$name $suffix").firstOrNull()?.let { index to it }
            }.toMap()

    private class ScoreDigit(
        val image: Image,
        private val atlas: TextureAtlas,
        private val frames: Map<Int, TextureAtlas.AtlasFrame>,
    ) {
        fun set(value: Int) {
            val frame = frames[value] ?: return
            image.bitmap = atlas.slice(frame)
        }
    }

    private fun updateDifficultyVisual() {
        if (shownDifficulty == currentDifficulty) return
        shownDifficulty = currentDifficulty
        val bitmap = difficultyBitmap(currentDifficulty) ?: return
        difficultyView?.bitmap = bitmap.sliceWithSize(0, 0, bitmap.width, bitmap.height)
        difficultyView?.visible = true
    }

    private fun difficultyBitmap(difficulty: String): korlibs.image.bitmap.Bitmap? {
        var assetId = difficulty
        while (assetId.isNotEmpty()) {
            KnfFiles.bitmapOrNull("preload/images/freeplay/freeplay$assetId.png")?.let { return it }
            assetId = assetId.substringBeforeLast('-', "")
        }
        return null
    }

    // ======================================================================
    // Album roll
    // ======================================================================

    private fun updateAlbum() {
        val entry = selectedEntry() ?: run {
            shownAlbumId = null
            albumLayer.removeChildren()
            albumArt = null
            albumTitle = null
            clearAlbumStars()
            return
        }
        val albumId = entry.song.metadataFor(resolvedVariation(entry)).playData.album ?: run {
            shownAlbumId = null
            albumLayer.removeChildren()
            albumArt = null
            albumTitle = null
            clearAlbumStars()
            return
        }
        if (shownAlbumId == albumId) {
            updateAlbumStars()
            return
        }

        shownAlbumId = albumId
        albumLayer.removeChildren()
        albumArt = null
        albumTitle = null
        clearAlbumStars()

        val info = FreeplayCatalog.albums[albumId] ?: return
        val art = KnfFiles.bitmapOrNull("preload/images/${info.artAsset}.png")
        if (art != null) {
            albumArt = Image(art).also {
                // AlbumRoll replaces the 262x262 `freeplayAlbum` symbol with
                // the album graphic; it is not scaled.
                it.x = 920.0
                it.y = 220.0
                albumLayer.addChild(it)
            }
        }

        val titleAtlas = TextureAtlas.tryLoad("preload/images/${info.titleAsset}.xml")
        if (titleAtlas != null) {
            val frames = titleAtlas.framesByPrefix("idle")
            if (frames.isNotEmpty()) {
                val offsetX = info.titleOffsets.getOrElse(0) { 0.0 }
                val offsetY = info.titleOffsets.getOrElse(1) { 0.0 }
                albumTitle = SparrowSprite(
                    titleAtlas,
                    frames,
                    fps = 24.0,
                    loop = true,
                ).also {
                    // AlbumRoll: FunkinSprite.createSparrow(width - 355, 500)
                    // plus the album's title offsets. No scale.
                    it.x = 925.0 + offsetX
                    it.y = 500.0 + offsetY
                    albumLayer.addChild(it)
                }
            }
        }
        updateAlbumStars()
    }

    private fun clearAlbumStars() {
        albumStarsView?.let { albumLayer.removeChild(it) }
        albumStarsView = null
    }

    /**
     * `AlbumRoll.setDifficultyStars` / `DifficultyStars.hx`.
     *
     * The star sheet is a 1501-pose Animate timeline: rating 1 starts at pose
     * 0, rating 15 at pose 1400, and rating 0 parks on the final pose, which is
     * ten small white "empty rating" dots. Rendering the real pose also keeps
     * the arc, scale and dot positions identical to the source.
     */
    private fun updateAlbumStars() {
        clearAlbumStars()
        val entry = selectedEntry() ?: return
        val rating = (entry.song.metadataFor(resolvedVariation(entry))
            .playData.ratings[currentDifficulty] ?: 0).coerceIn(0, 15)

        val atlas = starsAtlas ?: runCatching {
            AnimateAtlas("preload/images/freeplay/freeplayStars")
        }.getOrNull()?.also { starsAtlas = it } ?: return

        val pose = if (rating == 0) 1500 else (rating - 1) * 100
        val view = AnimateSpriteView(atlas).also {
            it.x = 950.0
            it.y = 209.0
            it.playFrames("difficulty-$rating", listOf(pose.coerceIn(0, atlas.poses.lastIndex)), false)
            albumLayer.addChild(it)
        }
        albumStarsView = view
    }

    // ======================================================================
    // Selection / filtering
    // ======================================================================

    private fun selectedEntry(): FreeplayEntry? = visible.getOrNull(selected)

    private fun selectRememberedEntry() {
        if (visible.isEmpty()) {
            selected = 0
            return
        }
        val remembered = visible.indexOfFirst { it?.song?.id == rememberedSongId }
        selected = when {
            remembered > 0 -> remembered
            visible.size > 1 -> 1
            else -> 0
        }
        selected = selected.coerceIn(0, visible.size - 1)
    }

    /**
     * Rebuilds the visible capsule list for the current difficulty/variation.
     * Random is always index zero, exactly like `FreeplayState.generateSongList`.
     */
    private fun rebuildVisible(
        preserveSongId: String? = null,
        initial: Boolean = false,
    ) {
        val keep = preserveSongId ?: selectedEntry()?.song?.id
        val next = ArrayList<FreeplayEntry?>(entries.size + 1)
        next += null
        for (entry in entries) {
            if (entry.song.difficulty(currentDifficulty, resolvedVariation(entry)) != null) {
                next += entry
            }
        }
        visible = next

        for (capsule in allCapsules) {
            capsule.visible = false
        }

        viewCapsules = visible.mapNotNull { entry ->
            if (entry == null) {
                randomCapsule
            } else {
                capsuleBySong[entry.song.id]
            }
        }.toMutableList()

        for ((index, capsule) in viewCapsules.withIndex()) {
            capsule.visible = true
            val entry = visible.getOrNull(index)
            capsule.refresh(
                currentDifficulty,
                if (entry == null) currentVariation else resolvedVariation(entry),
            )
        }

        val keptIndex = if (keep == null) -1 else visible.indexOfFirst { it?.song?.id == keep }
        selected = when {
            keptIndex > 0 -> keptIndex
            visible.size > 1 -> 1
            else -> 0
        }
        selected = selected.coerceIn(0, (visible.size - 1).coerceAtLeast(0))

        updateSelectionPositions()

        if (initial) {
            viewCapsules.forEachIndexed { index, capsule ->
                // SongMenuItem.initPosition(width, 0) + intendedY before the
                // jump-in; only the horizontal position animates in.
                capsule.x = Constants.GAME_WIDTH.toDouble()
                capsule.y = capsule.targetY
                capsule.startIntro(0.12 + index * 0.035)
            }
        }

        updateAlbum()
    }

    private fun updateSelectionPositions() {
        for ((pos, capsule) in viewCapsules.withIndex()) {
            // FNF indexes the group 1-based because the Random capsule occupies
            // slot zero; `curSelected` is its group index.
            val capsuleIndex = (pos + 1 - selected).toDouble()
            var targetY = intendedY(capsuleIndex)
            if (capsuleIndex < 0.0) {
                targetY += 50.0
            } else if (capsuleIndex > 4.0) {
                targetY -= 10.0
            }
            if (pos + 1 < selected) {
                targetY -= 100.0
            }
            capsule.setTarget(intendedX(capsuleIndex), targetY)
            capsule.setSelected(pos == selected)
        }
    }

    private fun intendedX(index: Double): Double = 270.0 + 60.0 * sin(index)

    private fun intendedY(index: Double): Double = index * (CAPSULE_HEIGHT * CAPSULE_SCALE + 10.0) + 120.0

    private fun changeSelection(delta: Int) {
        if (viewCapsules.isEmpty()) return
        selected = ((selected + delta) % viewCapsules.size + viewCapsules.size) % viewCapsules.size
        updateSelectionPositions()
        updateAlbum()
        updateSelectionStats()
        rememberSelection()
        scrollSound?.let { sfx.play(it, 0.4) }
        requestPreview()
    }

    private fun changeDifficulty(delta: Int) {
        val choices = difficultyChoices()
        if (choices.isEmpty()) return
        val index = choices.indexOf(currentDifficulty).let { if (it < 0) 0 else it }
        currentDifficulty = choices[(index + delta + choices.size) % choices.size]
        rebuildVisible(preserveSongId = selectedEntry()?.song?.id ?: rememberedSongId)
        updateTopUi()
        rememberSelection()
        scrollSound?.let { sfx.play(it, 0.4) }
        requestPreview()
    }

    private fun changeVariation(delta: Int) {
        val variations = Constants.DEFAULT_VARIATION_LIST
        val index = variations.indexOf(currentVariation).let { if (it < 0) 0 else it }
        currentVariation = variations[(index + delta + variations.size) % variations.size]
        ensureDifficultyForVariation()
        rebuildVisible(preserveSongId = selectedEntry()?.song?.id ?: rememberedSongId)
        updateTopUi()
        rememberSelection()
        scrollSound?.let { sfx.play(it, 0.4) }
        requestPreview()
    }

    /**
     * `FreeplaySongData.curVariation` for the default BF profile.
     *
     * FNF does not use a global variation for every song: it picks the first
     * variation owned by the current character. The shipped BF player owns
     * `bf*`, so weekend 1 songs (whose default chart uses Pico) resolve to the
     * `bf` mix, while week 1/2/3 songs keep their default charts.
     *
     * Q/E still force a concrete variation; in that mode unavailable songs are
     * filtered out instead of silently falling back.
     */
    private fun resolvedVariation(entry: FreeplayEntry): String {
        if (currentVariation != Constants.DEFAULT_VARIATION) return currentVariation

        val defaultPlayer = entry.song.metadataFor(Constants.DEFAULT_VARIATION)
            .playData.characters.player
        if (isBfPlayer(defaultPlayer) &&
            entry.song.difficulty(currentDifficulty, Constants.DEFAULT_VARIATION) != null
        ) {
            return Constants.DEFAULT_VARIATION
        }

        if (entry.song.variations.contains("bf") &&
            entry.song.difficulty(currentDifficulty, "bf") != null
        ) {
            val bfPlayer = entry.song.metadataFor("bf").playData.characters.player
            if (isBfPlayer(bfPlayer)) return "bf"
        }

        if (entry.song.difficulty(currentDifficulty, Constants.DEFAULT_VARIATION) != null) {
            return Constants.DEFAULT_VARIATION
        }
        return entry.song.variations.firstOrNull {
            entry.song.difficulty(currentDifficulty, it) != null
        } ?: Constants.DEFAULT_VARIATION
    }

    private fun isBfPlayer(player: String): Boolean =
        player == "bf" || player.startsWith("bf-")

    private fun difficultyChoices(): List<String> =
        if (currentVariation == "erect") listOf("erect", "nightmare")
        else Constants.DEFAULT_DIFFICULTY_LIST

    private fun ensureDifficultyForVariation() {
        val choices = difficultyChoices()
        if (currentDifficulty in choices) return
        currentDifficulty =
            if ("normal" in choices) "normal" else choices.firstOrNull() ?: Constants.DEFAULT_DIFFICULTY
    }

    private fun rememberSelection() {
        rememberedSongId = selectedEntry()?.song?.id
        rememberedDifficulty = currentDifficulty
        rememberedVariation = currentVariation
    }

    // ======================================================================
    // Preview / playback
    // ======================================================================

    private fun requestPreview() {
        // FNF starts the preview from the `onDJIntroDone` changeSelection call,
        // not while the turntable intro is still running.
        if (!djIntroDone) return

        previewGeneration++
        val generation = previewGeneration
        previewJob?.cancel()
        previewJob = null
        preview.stop()
        menuMusic.pause()

        val entry = selectedEntry()
        val path: String?
        val previewStart: Double
        val previewEnd: Double
        if (entry == null) {
            path = KnfFiles.resolveSound("preload/music/freeplayRandom/freeplayRandom")
            previewStart = 0.0
            previewEnd = 1.0
        } else {
            val variation = resolvedVariation(entry)
            val metadata = entry.song.metadataFor(variation)
            path = entry.song.instrumentalPath(variation)
            previewStart = metadata.playData.previewStart
            previewEnd = metadata.playData.previewEnd
        }
        if (path == null) return

        previewJob = launch {
            delay(FADE_IN_DELAY_MS)
            if (generation != previewGeneration) return@launch
            preview.play(path, previewStart, previewEnd, isCurrent = { generation == previewGeneration })
        }
    }

    private fun stopPreview() {
        previewGeneration++
        previewJob?.cancel()
        previewJob = null
        preview.stop()
    }

    private fun startSelected() {
        if (transitioning) return
        val entry = if (selected == 0) {
            visible.drop(1).filterNotNull().randomOrNull()
        } else {
            selectedEntry()
        }
        if (entry == null) {
            setStatus("No songs available.")
            return
        }
        val variation = resolvedVariation(entry)
        if (entry.song.difficulty(currentDifficulty, variation) == null) {
            setStatus("${entry.song.name} has no chart for $currentDifficulty/$variation")
            return
        }

        transitioning = true
        confirmSound?.let { sfx.play(it, 0.7) }
        dj?.let { if (hasDjLabel("Confirm")) it.play("Confirm", loop = false) }

        PlayRequest.songId = entry.song.id
        PlayRequest.difficulty = currentDifficulty
        PlayRequest.variation = variation
        // Freeplay normally skips story-mode videos. KNF_STORY=1 keeps the old
        // test hook that lets those sequences run from the normal song list.
        PlayRequest.storyMode = System.getenv("KNF_STORY") == "1"

        rememberedSongId = entry.song.id
        rememberedDifficulty = currentDifficulty
        rememberedVariation = currentVariation

        stopPreview()
        launch { sceneContainer.changeTo<PlayScene>() }
    }

    private fun backToMenu() {
        if (leaving) return
        leaving = true
        transitioning = true
        leaveElapsed = 0.0
        cancelSound?.let { sfx.play(it, 0.7) }
        stopPreview()
    }

    private fun setStatus(message: String) {
        statusText = message
        statusTimer = 3.0
        statusLine.text = message
        statusLine.x = max(8.0, (Constants.GAME_WIDTH - statusLine.width) / 2.0)
    }

    private var statusText = ""
    private var confirmSound: String? = null
    private var scrollSound: String? = null
    private var cancelSound: String? = null

    private suspend fun loadSounds() {
        confirmSound = KnfFiles.resolveSound("preload/sounds/confirmMenu")?.also { sfx.preload(it) }
        scrollSound = KnfFiles.resolveSound("preload/sounds/scrollMenu")?.also { sfx.preload(it) }
        cancelSound = KnfFiles.resolveSound("preload/sounds/cancelMenu")?.also { sfx.preload(it) }
    }

    // ======================================================================
    // Update loop
    // ======================================================================

    override suspend fun SContainer.sceneMain() {
        val knfInput = KnfInput(keys)
        addUpdater { dt ->
            val d = dt.secondsDouble()
            knfInput.update()

            updateCard(d)
            updateDj(d)
            // Korge computes Text bounds during the first render, so the
            // FlxText-derived plate behind FREEPLAY is sized here.
            headerBg?.let { bg ->
                headerText?.let { txt ->
                    if (txt.width > 0.0 && txt.height > 0.0) {
                        bg.unscaledSize = korlibs.math.geom.Size(
                            txt.width + 16.0,
                            txt.height + 16.0,
                        )
                    }
                }
            }
            for (capsule in viewCapsules) capsule.update(d)
            for (selector in selectors) selector.update(d)
            albumTitle?.update(d)
            highscoreSprite?.update(d)
            preview.update()

            displayedScore += (targetScore - displayedScore) * 0.2
            if (abs(targetScore - displayedScore) < 1.0) displayedScore = targetScore.toDouble()
            displayedCompletion += (targetCompletion - displayedCompletion) * 0.5
            if (abs(targetCompletion - displayedCompletion) < 0.005) {
                displayedCompletion = targetCompletion
            }
            updateScoreVisual()
            updateCompletionVisual()

            charHint?.let { hint ->
                hintTimer += d * 2.0
                hint.alpha = 0.3 + 0.6 * ((sin(hintTimer) + 1.0) / 2.0)
                if (hint.width > 0.0) {
                    hint.x = (Constants.GAME_WIDTH - hint.width) / 2.0
                }
            }

            if (statusTimer > 0.0) {
                statusTimer -= d
                if (statusTimer <= 0.0) statusLine.text = ""
            }

            if (leaving) {
                leaveElapsed += d
                fadeRect?.alpha = (leaveElapsed / LEAVE_FADE).coerceIn(0.0, 1.0)
                if (leaveElapsed >= LEAVE_FADE) {
                    leaving = false
                    launch { sceneContainer.changeTo<MainMenuScene>() }
                    return@addUpdater
                }
            }

            if (!transitioning && !leaving) {
                handleInputs(knfInput, d)
            }
        }
    }

    private fun updateCard(dt: Double) {
        val pink = pinkBack ?: return
        if (!cardSlideFinished) {
            cardSlideElapsed += dt
            val t = (cardSlideElapsed / 0.6).coerceIn(0.0, 1.0)
            // FlxEase.quartOut
            val eased = 1.0 - (1.0 - t).pow(4.0)
            pink.x = -pink.bitmap.width.toDouble() * (1.0 - eased)
            if (t >= 1.0) {
                pink.x = 0.0
                cardSlideFinished = true
            }
        }

        if (glowActive && cardGlow != null) {
            glowElapsed += dt
            val t = (glowElapsed / 0.45).coerceIn(0.0, 1.0)
            cardGlow?.alpha = (1.0 - t).coerceIn(0.0, 1.0)
            cardGlow?.scaleX = 1.0 + 0.2 * t
            cardGlow?.scaleY = 1.0 + 0.2 * t
            if (t >= 1.0) glowActive = false
        }

        if (backingFadeActive) {
            backingFadeElapsed += dt
            val t = (backingFadeElapsed / 0.6).coerceIn(0.0, 1.0)
            // FlxEase.expoOut
            val eased = if (t >= 1.0) 1.0 else 1.0 - 2.0.pow(-10.0 * t)
            backingImage?.alpha = eased
            if (t >= 1.0) backingFadeActive = false
        }

        if (difficultySlideActive) {
            difficultySlideElapsed += dt
            val t = (difficultySlideElapsed / 0.6).coerceIn(0.0, 1.0)
            val eased = 1.0 - (1.0 - t).pow(4.0)
            difficultyView?.x = -300.0 + 390.0 * eased
            if (t >= 1.0) {
                difficultyView?.x = 90.0
                difficultySlideActive = false
            }
        }
    }

    private fun updateDj(dt: Double) {
        val view = dj ?: return
        view.update(dt)
        if (!djIntroDone && view.finished) {
            onDjIntroDone()
        }
        if (djIntroDone && !djIdleStarted && hasDjLabel("Idle")) {
            djIdleStarted = true
            view.play("Idle", loop = true)
        }
    }

    private fun onDjIntroDone() {
        if (djIntroDone) return
        djIntroDone = true
        pinkBack?.tint = RGBA(0xFF, 0xD8, 0x63)
        orangeStrip?.visible = true
        orangeStripLeft?.visible = true
        glowActive = true
        glowElapsed = 0.0
        cardGlow?.alpha = 1.0
        cardGlow?.scaleX = 1.0
        cardGlow?.scaleY = 1.0

        backingFadeActive = true
        backingFadeElapsed = 0.0
        backingImage?.alpha = 0.0

        difficultySlideActive = true
        difficultySlideElapsed = 0.0
        selectors.forEach { it.alpha = 1.0 }

        albumLayer.visible = true
        uiLayer.visible = true
        highscoreSprite?.let {
            it.visible = true
            it.restart()
        }
        scoreDigits.forEach { it.image.visible = true }
        clearBox?.visible = true
        completionDigits.forEach { it.visible = true }
        difficultyDots.forEach { it.visible = true }
        updateTopUi()
        updateAlbum()
        requestPreview()
    }

    private fun hasDjLabel(label: String): Boolean =
        djAtlas?.labelFrames(label)?.isNotEmpty() == true

    private fun handleInputs(input: KnfInput, dt: Double) {
        // `uiStateMachine.transition(EnteringFreeplay)` blocks interaction
        // until BF's turntable intro reaches `onDJIntroDone`.
        if (!djIntroDone) return

        if (input.back) {
            backToMenu()
            return
        }
        if (input.accept) {
            startSelected()
            return
        }

        // FNF's MenuTypedList repeats after holding for a moment. 0.35 s feels
        // close to the 0.9 s/0.07 s pair in FreeplayState's handleDirectionalInput.
        val up = input.justPressed(2)
        val down = input.justPressed(1)
        if (up && !down) {
            changeSelection(-1)
            upHold = 0.0
        } else if (down && !up) {
            changeSelection(1)
            downHold = 0.0
        } else {
            if (input.isDown(2)) {
                upHold += dt
                if (upHold >= 0.35) {
                    changeSelection(-1)
                    upHold = 0.07
                }
            } else {
                upHold = 0.0
            }
            if (input.isDown(1)) {
                downHold += dt
                if (downHold >= 0.35) {
                    changeSelection(1)
                    downHold = 0.07
                }
            } else {
                downHold = 0.0
            }
        }

        if (input.justPressed(0)) changeDifficulty(-1)
        if (input.justPressed(3)) changeDifficulty(1)
        if (input.nextVariation) changeVariation(1)
        if (input.previousVariation) changeVariation(-1)
    }

    override suspend fun sceneDestroy() {
        stopPreview()
        preview.dispose()
        rememberSelection()
    }

    private fun loadFont(path: String): TtfFont? =
        KnfFiles.bytesOrNull(path)?.let {
            runCatching { TtfFont(it, path.substringAfterLast('/')) }.getOrNull()
        }

    private companion object {
        private const val FADE_IN_DELAY_MS = 250L
        private const val LEAVE_FADE = 0.25
        private const val CAPSULE_HEIGHT = 132.0
        private const val CAPSULE_SCALE = 0.8

        /** `FreeplayState.rememberedSongId` starts at the tutorial in FNF. */
        private var rememberedSongId: String? = "tutorial"
        private var rememberedDifficulty = Constants.DEFAULT_DIFFICULTY
        private var rememberedVariation = Constants.DEFAULT_VARIATION
    }
}
