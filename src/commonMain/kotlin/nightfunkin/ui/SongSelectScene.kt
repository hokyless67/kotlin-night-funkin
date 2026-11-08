package nightfunkin.ui

import korlibs.image.bitmap.Bitmap
import korlibs.image.bitmap.Bitmap32
import korlibs.image.bitmap.sliceWithSize
import korlibs.image.color.Colors
import korlibs.image.color.RGBA
import korlibs.image.font.BitmapFont
import korlibs.image.font.TtfFont
import korlibs.io.resources.Resourceable
import korlibs.korge.blend.BlendMode
import korlibs.korge.scene.Scene
import korlibs.korge.view.Container
import korlibs.korge.view.Image
import korlibs.korge.view.SContainer
import korlibs.korge.view.SolidRect
import korlibs.korge.view.Text
import korlibs.korge.view.addUpdater
import korlibs.korge.view.solidRect
import korlibs.korge.view.text
import korlibs.math.geom.Anchor2D
import korlibs.math.geom.degrees
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.hypot
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
import nightfunkin.core.KnfFmt
import nightfunkin.core.KnfInput
import nightfunkin.core.KnfTouch
import nightfunkin.core.Save
import nightfunkin.core.SparrowSprite
import nightfunkin.core.TextureAtlas
import nightfunkin.platform.KnfEnv
import nightfunkin.play.PlayRequest
import nightfunkin.play.PlayScene
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random
import nightfunkin.core.KnfMath

/**
 * FNF 0.8.6's Freeplay menu.
 *
 * This is the real `FreeplayState` layout rather than a plain text list:
 * the pink backing card slides in, BF runs his turntable intro, and the song
 * capsules arc down the left side while the album art sits on the right.
 *
 * KNF has no character-select substate yet, so the stock save counts both
 * playable characters as seen. Freeplay therefore uses BF's scripted
 * `BoyfriendCard` (the yellow card with the giant marquee text), while
 * `NewCharacterCard` remains available for saves with an unseen character.
 * A/D cycles every difficulty visible to BF (easy/normal/hard/erect/nightmare
 * per `listAllDifficulties`), the variation is derived per song like
 * `changeDiff` does, and there is no manual variation cycling. HOME/END jump
 * to the first/last capsule and F toggles the favourite heart, matching
 * 0.8.6's Solo bindings.
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
    private var bandFontSmall: BitmapFont? = null
    private var bandFontMedium: BitmapFont? = null
    private var bandFontLarge: BitmapFont? = null

    private var selected = 1
    private var currentDifficulty = rememberedDifficulty

    private val allCapsules = ArrayList<FreeplayCapsule>()
    private val capsuleBySong = HashMap<String, FreeplayCapsule>()
    private var randomCapsule: FreeplayCapsule? = null
    private var visible: MutableList<FreeplayEntry?> = mutableListOf()
    private var viewCapsules: MutableList<FreeplayCapsule> = mutableListOf()

    private lateinit var cardLayer: Container
    private lateinit var difficultyLayer: Container
    private lateinit var capsuleLayer: Container
    private lateinit var albumLayer: Container
    private lateinit var selectorLayer: Container
    private lateinit var uiLayer: Container

    private var pinkBack: Image? = null
    private var cardGlow: Image? = null
    private var backingImage: Image? = null
    private var orangeStrip: SolidRect? = null
    private var orangeStripLeft: SolidRect? = null

    // `BoyfriendCard` (assets/preload/scripts/players/backcards/backcard-bf.hxc).
    private var boyfriendBands: List<BackingScrollBand> = emptyList()
    private var backingTextYeah: AnimateSpriteView? = null
    private var beatGlow: Image? = null
    private var beatGlowDark: Image? = null
    private var beatClock = 0.0
    private var beatIndex = 0
    private var glowTweenElapsed = 0.0
    private var glowTweenActive = false
    private var glowDarkTweenElapsed = 0.0
    private var glowDarkTweenActive = false

    // `NewCharacterCard` is only used for saves that still have an unseen
    // playable character (KNF's stock save marks both as seen).
    private var newCharacterCard = false
    private var newCardGroup: Container? = null
    private var newCardLight1: Image? = null
    private var newCardLight2: Image? = null
    private var newCardLight3: Image? = null
    private var newCardMultiply1: Image? = null
    private var newCardMultiply2: Image? = null
    private var newCardYellow: Image? = null
    private var newCardTimer = 0.0
    private val newCardBands = ArrayList<BackingScrollBand>()

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

    // `grpDifficulties`: one badge sprite per difficulty id; the stock
    // nightmare badge is a Sparrow `idle0` glitch loop, the rest are static.
    private var difficultyView: DifficultyBadge? = null
    private var difficultyViewIn: DifficultyBadge? = null
    private val difficultyBadges = HashMap<String, DifficultyBadge>()
    private var shownDifficulty: String? = null
    private var selectors: List<SparrowSprite> = emptyList()

    // `changeDiff`: the outgoing badge tweens out while the incoming one
    // slides in from the opposite side, both over 0.2 s of circInOut.
    private var badgeSlideActive = false
    private var badgeSlideElapsed = 0.0
    private var badgeSlideSign = 0
    private var badgePendingDifficulty: String? = null

    private var albumArt: Image? = null
    private var albumTitle: SparrowSprite? = null
    private var shownAlbumId: String? = null
    private var albumArtAtlas: AnimateAtlas? = null
    private var albumArtAnimator: AlbumArtAnimator? = null
    private var albumTitleIdleFrames: List<TextureAtlas.AtlasFrame> = emptyList()
    private var albumTitleSwitchFrames: List<TextureAtlas.AtlasFrame> = emptyList()
    private var albumTitleSwitching = false
    private var albumRollReady = false

    private var letterSort: FreeplayLetterSort? = null
    private var letterFilter = "ALL"

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
    private var albumFlames: FreeplayFlamesView? = null

    private var displayedScore = 0.0
    private var targetScore = 0
    private var displayedCompletion = 0.0
    private var targetCompletion = 0.0
    private var hintTimer = 0.0

    private lateinit var statusLine: korlibs.korge.view.Text

    private var statusTimer = 0.0

    // `FreeplayState.spamTimer` / `spamming`: first change on press, then a
    // 0.9 s pause before held-key repeats every 0.07 s.
    private var spamTimer = 0.0
    private var spamming = false

    // `uiStateMachine.transition(Interacting)` from the favourite wiggle blocks
    // input until its tweens finish.
    private var interactLockTimer = 0.0

    private var transitioning = false
    private var leaving = false
    private var leaveElapsed = 0.0
    private var fadeRect: SolidRect? = null

    override suspend fun SContainer.sceneInit() {
        sceneRoot = this
        Save.load()
        newCharacterCard = KnfEnv["KNF_NEWCARD"] == "1" || Save.hasNewCharacter()
        fiveBySeven = loadFont("fonts/5by7.ttf")
        youreGone = loadFont("fonts/YoureGone-Regular.otf")
        vcr = loadFont("fonts/vcr.ttf")
        // `BGScrollingText` is static text; a bitmap glyph atlas keeps all of
        // its repeated copies cheap to draw while they scroll.
        bandFontSmall = fiveBySeven?.let { runCatching { BitmapFont(it, 43.0) }.getOrNull() }
        bandFontMedium = fiveBySeven?.let { runCatching { BitmapFont(it, 60.0) }.getOrNull() }
        bandFontLarge = fiveBySeven?.let { runCatching { BitmapFont(it, 80.0) }.getOrNull() }

        solidRect(Constants.GAME_WIDTH.toDouble(), Constants.GAME_HEIGHT.toDouble(), Colors.BLACK)

        cardLayer = Container().also { addChild(it) }
        buildCard()

        val djLayer = Container().also { addChild(it) }
        buildDj(djLayer)

        difficultyLayer = Container().also { addChild(it) }
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

        // `FreeplayState.new(params, stickers)`: the sticker transition's
        // REVEAL phase plays over the freshly built scene, hiding it until
        // every sticker peels off.
        StickerSceneTarget.consumeReveal()?.let { reveal ->
            if (reveal.placements.isNotEmpty()) {
                revealActive = true
                addStickerRevealOverlay(reveal)
            }
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
        if (newCharacterCard) {
            buildNewCharacterCard()
        } else {
            buildBoyfriendCard(width)
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

    /**
     * `BoyfriendCard`, the scripted backing card from
     * `assets/preload/scripts/players/backcards/backcard-bf.hxc`.
     *
     * The base card paints the yellow plate and the "GET IT!! / YEAH!!!"
     * collage; the script adds the six giant `BGScrollingText` marquees made
     * from BF's freeplay DJ lines and the two pulsing beat glows.
     */
    private fun buildBoyfriendCard(width: Double) {
        // `orangeBackShit` is alpha-masked by `pinkBack`. Its 524 px source
        // width reaches past the plate's slanted right edge, so crop it to the
        // ~390 px of the plate that is actually visible at y=440..515.
        orangeStrip = cardLayer.solidRect(width * 0.75, 75.0, RGBA(0xFE, 0xDA, 0x00)).also {
            it.x = 84.0
            it.y = 440.0
            it.visible = false
        }
        orangeStripLeft = cardLayer.solidRect(100.0, 75.0, RGBA(0xFF, 0xD4, 0x00)).also {
            it.x = 0.0
            it.y = 440.0
            it.visible = false
        }

        val yeahAtlas = runCatching {
            AnimateAtlas("preload/images/freeplay/backing-text-yeah")
        }.getOrNull()
        backingTextYeah = yeahAtlas?.takeIf { it.poses.isNotEmpty() }?.let { atlas ->
            AnimateSpriteView(atlas, applyStageMatrix = false).also {
                it.x = -320.0
                it.y = 120.0
                // `BackingCard` never calls `play` on this sprite while
                // browsing: the atlas' first pose is empty and the
                // YEAH!!!/YES YES YES collage only plays on confirm.
                it.playFrames("", listOf(0), loop = false)
                cardLayer.addChild(it)
            }
        }

        val bands = ArrayList<BackingScrollBand>()
        // `BGScrollingText` arguments: x, y, text, widthShit, bold, size.
        buildBackingBand(cardLayer, 0.0, 160.0, "HOT BLOODED IN MORE WAYS THAN ONE",
            1280.0, RGBA(0xFF, 0xF3, 0x83), 43.0, 6.8, bold = true, out = bands, visible = false)
        buildBackingBand(cardLayer, 0.0, 220.0, "BOYFRIEND",
            640.0, RGBA(0xFF, 0x99, 0x63), 60.0, -3.8, bold = false, out = bands, visible = false)
        buildBackingBand(cardLayer, 0.0, 285.0, "PROTECT YO NUTS",
            640.0, Colors.WHITE, 43.0, 3.5, bold = true, out = bands, visible = false)
        buildBackingBand(cardLayer, 0.0, 335.0, "BOYFRIEND",
            640.0, RGBA(0xFF, 0x99, 0x63), 60.0, -3.8, bold = false, out = bands, visible = false)
        buildBackingBand(cardLayer, 0.0, 397.0, "HOT BLOODED IN MORE WAYS THAN ONE",
            1280.0, RGBA(0xFF, 0xF3, 0x83), 43.0, 6.8, bold = true, out = bands, visible = false)
        buildBackingBand(cardLayer, 0.0, 450.0, "BOYFRIEND",
            640.0, RGBA(0xFE, 0xA4, 0x00), 60.0, -3.8, bold = false, out = bands, visible = false)
        boyfriendBands = bands

        val glowBitmap = KnfFiles.bitmapOrNull("preload/images/freeplay/beatglow.png")
        if (glowBitmap != null) {
            beatGlowDark = Image(glowBitmap).also {
                it.x = -300.0
                it.y = 330.0
                it.alpha = 0.0
                it.visible = false
                cardLayer.addChild(it)
            }
            beatGlow = Image(glowBitmap).also {
                it.x = -300.0
                it.y = 330.0
                it.alpha = 0.0
                it.blendMode = BlendMode.ADD
                it.visible = false
                cardLayer.addChild(it)
            }
        }
    }

    /**
     * `NewCharacterCard` from 0.8.6. Shown only when the save still has an
     * unseen playable character (`KNF_NEWCARD=1` can force it for testing).
     */
    private fun buildNewCharacterCard() {
        val group = Container().also {
            it.visible = false
            cardLayer.addChild(it)
        }
        newCardGroup = group

        fun addImage(path: String, x: Double, y: Double, blend: BlendMode? = null): Image? {
            val bitmap = KnfFiles.bitmapOrNull("preload/images/freeplay/backingCards/newCharacter/$path")
                ?: return null
            return Image(bitmap).also {
                it.x = x
                it.y = y
                if (blend != null) it.blendMode = blend
                group.addChild(it)
            }
        }

        addImage("darkback.png", 0.0, 0.0)

        // The six `BGScrollingText` lines, top to bottom. All six use the
        // script's `bold = true`.
        buildBackingBand(group, 0.0, 163.0, "COULD IT BE A NEW FRIEND? OR FOE??", 1280.0, RGBA(0x13, 0x93, 0x76), 43.0, -4.0, bold = true)
        buildBackingBand(group, -440.0, 215.0, "NEW UNLOCK!", 640.0, RGBA(0x99, 0xBD, 0xF2), 80.0, 2.0, bold = true)
        buildBackingBand(group, 0.0, 286.0, "SOMEONE'S WAITING!", 640.0, RGBA(0x40, 0xEA, 0x84), 43.0, -2.0, bold = true)
        buildBackingBand(group, -220.0, 331.0, "NEW UNLOCK!", 640.0, RGBA(0x99, 0xBD, 0xF2), 80.0, 2.0, bold = true)
        buildBackingBand(group, 0.0, 402.0, "COULD IT BE A NEW FRIEND? OR FOE??", 1280.0, RGBA(0x13, 0x93, 0x76), 43.0, -4.0, bold = true)
        buildBackingBand(group, 0.0, 458.0, "NEW UNLOCK!", 640.0, RGBA(0x99, 0xBD, 0xF2), 80.0, 2.0, bold = true)

        addImage("multiplyBar.png", -10.0, 440.0, BlendMode.MULTIPLY)

        // The pulsing light/multiply stack that turns the card yellow/red.
        newCardLight1 = addImage("orange gradient.png", -360.0, 230.0, BlendMode.ADD)
        newCardMultiply1 = addImage("red.png", -15.0, -125.0, BlendMode.MULTIPLY)
        newCardMultiply2 = addImage("red.png", -15.0, -125.0, BlendMode.MULTIPLY)
        newCardLight2 = addImage("orange gradient.png", -360.0, 230.0, BlendMode.ADD)
        newCardYellow = addImage("yellow bg piece.png", 0.0, 0.0, BlendMode.MULTIPLY)
        newCardLight3 = addImage("red gradient.png", -360.0, 290.0, BlendMode.ADD)
    }

    /**
     * One `BGScrollingText`: a row of repeated copies that wraps. Each copy is
     * a small [Container] so `bold` can be faked with a one-pixel second pass
     * (FlxText's fake bold) while still using a cached [BitmapFont] atlas.
     */
    private fun buildBackingBand(
        parent: Container,
        x: Double,
        y: Double,
        text: String,
        widthLimit: Double,
        color: RGBA,
        textSize: Double,
        speed: Double,
        bold: Boolean = true,
        out: MutableList<BackingScrollBand> = newCardBands,
        visible: Boolean = true,
    ) {
        val holder = Container().also {
            it.x = x
            it.y = y
            it.visible = visible
            parent.addChild(it)
        }

        val font = (when {
            textSize >= 80.0 -> bandFontLarge
            textSize >= 60.0 -> bandFontMedium
            else -> bandFontSmall
        })?.let { Resourceable.Fixed(it) }
            ?: fiveBySeven?.let { Resourceable.Fixed(it) }
            ?: return

        fun makeCopy(): Container {
            val copy = Container().also { holder.addChild(it) }
            copy.text(
                text,
                textSize = textSize,
                color = color,
                font = font,
            ).also { it.y = 0.0 }
            if (bold) {
                // `FlxText.bold` is a faux second pass; Korge's bitmap-font
                // renderer ignores `stroke`, so draw the glyphs twice.
                copy.text(
                    text,
                    textSize = textSize,
                    color = color,
                    font = font,
                ).also {
                    it.x = 1.0
                    it.y = 0.0
                }
            }
            return copy
        }

        val first = makeCopy()
        first.x = 0.0
        val firstText = first.getChildAtOrNull(0) as? Text
        val textWidth = firstText?.width?.takeIf { it > 0.0 }?.toDouble()
            ?: (textSize * text.length * 0.55)
        val needed = max(1, ceil(widthLimit / textWidth).toInt() + 1)
        val copies = ArrayList<Container>(needed)
        copies += first
        for (i in 1 until needed) {
            copies += makeCopy().also { it.x = i * (textWidth + 20.0) }
        }
        out += BackingScrollBand(holder, copies, textWidth, 20.0, speed)
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
        val masked = applyAngleMask(bitmap)
        val targetHeight = Constants.GAME_HEIGHT + 1.0
        val scale = targetHeight / masked.height
        val width = pinkBack?.let { it.bitmap.width.toDouble() } ?: 524.0
        return Image(masked).also {
            it.scaleX = scale
            it.scaleY = scale
            it.x = width * 0.74
            it.y = 0.0
        }
    }

    /**
     * `AngleMask` from `funkin.graphics.shaders.AngleMask`.
     *
     * The backdrop keeps only the pixels right of the line running from the
     * texture's top-left corner to `(90, height)`, so its left edge follows the
     * pink card's slanted right edge instead of being a hard rectangle. The
     * shader supersamples that edge every frame; baking the same coverage into
     * the bitmap once keeps it antialiased for free.
     */
    private fun applyAngleMask(bitmap: Bitmap): Bitmap {
        val src = bitmap.toBMP32IfRequired()
        val width = src.width
        val height = src.height
        val out = Bitmap32(width, height, src.ints.copyOf(), src.premultiplied)
        for (y in 0 until height) {
            // Matches `endPosition = (90, 100)` in normalized texture space.
            val edge = ANGLE_MASK_X * (y + 0.5) / height
            val limit = min(width, ceil(edge).toInt() + 1)
            for (x in 0 until limit) {
                val coverage = (x + 1.0 - edge).coerceIn(0.0, 1.0)
                val index = y * width + x
                val color = out.getRgbaAtIndex(index)
                out.setRgbaAtIndex(
                    index,
                    RGBA(color.r, color.g, color.b, (color.a * coverage).toInt()),
                )
            }
        }
        return out
    }

    private fun buildDifficulty(parent: Container) {
        val badge = obtainBadge(currentDifficulty) ?: return
        // `grpDifficulties` starts at x=-300 and tweens to x=90. The
        // children are moved by the group setter, then each child is
        // tweened to the same world x. FlxTypedSpriteGroup.x is only
        // an offset holder, so the final rendered x is 90 (not 180).
        // The source only changes the sprite's hitbox height (2.5x),
        // not its graphic scale.
        badge.x = -300.0
        badge.y = 80.0
        badge.visible = true
        difficultyView = badge
        shownDifficulty = currentDifficulty
    }

    private fun buildSelectors(parent: Container) {
        val atlas = TextureAtlas.tryLoad(
            "preload/images/freeplay/freeplaySelector/freeplaySelector.xml",
        ) ?: return
        val frames = atlas.framesByPrefix("arrow pointer loop")
        if (frames.isEmpty()) return

        val left = SparrowSprite(atlas, frames, fps = 24.0, loop = true).also {
            it.setPixelArt(true)
            it.x = 20.0
            it.y = 70.0
            it.alpha = 0.0
            parent.addChild(it)
        }
        val right = SparrowSprite(atlas, frames, fps = 24.0, loop = true).also {
            it.setPixelArt(true)
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
        // `overhangStuff` is a full-width black strip. It is 164 px tall but
        // parked at y=-100, so exactly 64 px remain visible above every other
        // Freeplay layer.
        uiLayer.solidRect(Constants.GAME_WIDTH.toDouble(), 164.0, Colors.BLACK).also {
            it.x = 0.0
            it.y = -100.0
        }

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

        // FreeplayState's `charSelectHint`. A stock save has both BF and Pico
        // unlocked, so FNF adds this line (`countUnlockedCharacters() > 1`).
        charHint = uiLayer.text(
            "Press [ TAB ] to change characters",
            textSize = 32.0,
            color = RGBA(0x5F, 0x5F, 0x5F),
        ).also {
            fiveBySeven?.let { font -> it.font = Resourceable.Fixed(font) }
            // FlxText(-40, 18, GAME_WIDTH - 16) with CENTER alignment.
            it.x = -40.0 + (Constants.GAME_WIDTH - 16.0 - it.width) / 2.0
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
        val atlas = runCatching {
            AnimateAtlas("preload/images/freeplay/sortedLetters")
        }.getOrNull() ?: return
        val sort = FreeplayLetterSort(atlas).also {
            it.onChange = ::changeLetterFilter
            it.onSound = {
                scrollSound?.let { sound -> sfx.play(sound, 0.4) }
            }
            uiLayer.addChild(it.container)
        }
        letterSort = sort
    }

    /**
     * `letterSort.changeSelectionCallback`: rebuilds the capsule list for the
     * chosen range. If the current song is still present, keep it selected;
     * if Random was selected, stay on Random; otherwise land on the first real
     * song of the new group.
     */
    private fun changeLetterFilter(letter: String) {
        val wasRandom = selected == 0
        val currentSong = selectedEntry()?.song?.id
        val previewBefore = previewTarget()
        letterFilter = letter

        rebuildVisible(preserveSongId = currentSong)
        if (wasRandom && visible.isNotEmpty()) {
            selected = 0
            updateSelectionPositions()
            updateAlbum()
        }

        // `generateSongList(..., force = true, onlyIfChanged = false)` rebuilds
        // every capsule and plays the jump-in again after a tab change.
        viewCapsules.forEachIndexed { index, capsule ->
            capsule.startIntro(intendedY((index + 1).toDouble()) + 10.0)
        }

        updateSelectionStats()
        updateTopUi()
        rememberSelection()
        requestPreviewIfTargetChanged(previewBefore)
    }

    /** `FreeplayState.sortSongs`'s REGEXP / FAVORITE cases for the top tabs. */
    private fun matchesLetterFilter(entry: FreeplayEntry): Boolean {
        if (letterFilter == "ALL") return true
        if (letterFilter == "fav") {
            // `filteredSong.isFav`: favourites are keyed `songId:variation`.
            val variation = resolvedVariation(entry)
            return Save.isSongFavorited("${entry.song.id}:$variation")
        }
        val first = entry.song.name.firstOrNull()?.uppercaseChar() ?: return false
        if (letterFilter == "#") return first in '0'..'9'
        if (letterFilter.length == 1) return first == letterFilter[0]
        val low = letterFilter[0]
        val high = letterFilter[2]
        return first in low..high
    }

    /** REGEXP filters are alphabetised; ALL and FAVORITE keep level order. */
    private fun letterFilteredEntries(): List<FreeplayEntry> {
        val pool = entries.filter(::matchesLetterFilter)
        return if (letterFilter == "ALL" || letterFilter == "fav") {
            pool
        } else {
            pool.sortedBy { it.song.name.lowercase() }
        }
    }

    private fun buildHighscore() {
        val atlas = TextureAtlas.tryLoad("preload/images/freeplay/highscore.xml") ?: return
        val frames = atlas.framesByPrefix("highscore small instance 1")
        if (frames.isEmpty()) return
        highscoreSprite = SparrowSprite(atlas, frames, fps = 24.0, loop = false).also {
            it.setPixelArt(true)
            it.x = 860.0
            it.y = 70.0
            it.visible = false
            uiLayer.addChild(it)
        }
    }

    private fun buildScore() {
        val atlas = TextureAtlas.tryLoad("preload/images/digital_numbers.xml") ?: return
        val animations = numberAnimations(atlas, "DIGITAL")
        val zeroFrames = animations[0] ?: return
        // `FreeplayScore` is a `FlxSpriteGroup` at (0, 60) and every `ScoreNum`
        // is constructed at y = 60; `preAdd` adds the group's offset, so the
        // digits actually sit at y = 120.
        val y = 120.0
        scoreDigits = (0 until 7).map { i ->
            val baseX = 927.0 + 45.0 * i
            val image = Image(atlas.slice(zeroFrames.first())).also {
                it.scaleX = ScoreDigit.SCALE
                it.scaleY = ScoreDigit.SCALE
                it.visible = false
                uiLayer.addChild(it)
            }
            ScoreDigit(image, atlas, animations, baseX, y)
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
        val full = Constants.DEFAULT_DIFFICULTY_LIST_FULL
        val selectedIndex = full.indexOf(currentDifficulty)
        val entry = selectedEntry()
        for ((i, dot) in difficultyDots.withIndex()) {
            // `refreshDots`: a difficulty the current song lacks (in any
            // bf-visible variation) renders in the dimmed INACTIVE state.
            val available = entry != null && hasDifficultyForCharacter(entry, full[i])
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

    private fun numberAnimations(
        atlas: TextureAtlas,
        suffix: String,
    ): Map<Int, List<TextureAtlas.AtlasFrame>> =
        listOf("ZERO", "ONE", "TWO", "THREE", "FOUR", "FIVE", "SIX", "SEVEN", "EIGHT", "NINE")
            .mapIndexedNotNull { index, name ->
                atlas.framesByPrefix("$name $suffix")
                    .sortedBy { it.name }
                    .takeIf { it.isNotEmpty() }
                    ?.let { index to it }
            }.toMap()

    /**
     * One `ScoreNum` from `FreeplayScore.hx`.
     *
     * Each digit animation is a 16-frame pop: the first frames are the large
     * glowing version and the last frames are the crisp resting digit. The
     * animation only restarts when the digit actually changes, which is why
     * the glow appears just for the digits that are counting and fades once
     * they settle.
     */
    private class ScoreDigit(
        private val image: Image,
        private val atlas: TextureAtlas,
        private val animations: Map<Int, List<TextureAtlas.AtlasFrame>>,
        private val baseX: Double,
        private val baseY: Double,
    ) {
        companion object {
            /**
             * `setGraphicSize(Std.int(138 * 0.4))` → 55, so every `ScoreNum`
             * keeps Flixel's 55 / 138 scale even when the digit's logical
             * frame width changes.
             */
            const val SCALE = 55.0 / 138.0

            /** `animation.addByPrefix(..., 24, false)`. */
            const val FPS = 24.0

            /** `ScoreNum.set_digit` nudges the narrow "1" 15 px to the right. */
            const val ONE_SHIFT = 15.0
        }

        private var value = 0
        private var clock = 0.0
        private var animating = true

        init {
            applyFrame(0)
        }

        fun set(value: Int) {
            if (this.value == value) return
            this.value = value
            // `ScoreNum.set_digit` restarts the animation at frame 0 only when
            // the digit differs from the currently displayed one.
            clock = 0.0
            animating = true
            applyFrame(0)
        }

        fun setVisible(value: Boolean) {
            image.visible = value
        }

        fun update(dt: Double) {
            if (!animating) return
            clock += dt
            val frames = animations[value] ?: return
            val index = (clock * FPS).toInt()
            if (index >= frames.lastIndex) {
                applyFrame(frames.lastIndex)
                animating = false
            } else {
                applyFrame(index)
            }
        }

        private fun applyFrame(index: Int) {
            val frames = animations[value] ?: return
            val frame = frames[index.coerceIn(0, frames.lastIndex)]
            image.bitmap = atlas.slice(frame)
            // The logical frame stays anchored at (x, y); trimmed frames are
            // inset by (-frameX, -frameY). `updateHitbox` + `centerOffsets`
            // cancel out for every digit except "1".
            val shift = if (value == 1) ONE_SHIFT else 0.0
            image.x = baseX + shift - frame.frameX * SCALE
            image.y = baseY - frame.frameY * SCALE
        }
    }

    private fun updateDifficultyVisual() {
        // The badge slide animation performs the swap itself once the incoming
        // badge lands.
        if (badgeSlideActive) return
        if (shownDifficulty == currentDifficulty) return
        val badge = obtainBadge(currentDifficulty) ?: return
        shownDifficulty = currentDifficulty
        difficultyView?.visible = false
        badge.x = 90.0
        badge.y = 80.0
        badge.alpha = 1.0
        badge.visible = true
        difficultyView = badge
    }

    /**
     * `changeDiff` + `handleDifficultySwitch`: changeDiff starts a slide-out
     * tween for the old badge and a slide-in for the new one, but the
     * generateSongList call right after runs changeSelection -> changeDiff(0),
     * whose `diffSprite.visible = isCurrentDiff` sync hides the old badge the
     * same frame. Its slide-out tween is therefore never seen: the old badge
     * disappears instantly while the new one is already visible at the entry
     * side (alpha 0.5, drawn 5 px up by the offset flash) and slides to 90
     * over 0.2 s of circInOut, reaching full alpha on the 1/24 s mark.
     */
    private fun startDifficultyBadgeSlide(delta: Int, newDifficulty: String) {
        // The intro slide shares the sprite; park it on its final position.
        if (difficultySlideActive) {
            difficultySlideActive = false
            difficultyView?.x = 90.0
        }
        // A press during the 0.4 s slide lands the previous tween instantly.
        if (badgeSlideActive) finishBadgeSlide()

        val outgoing = difficultyView ?: return
        val incoming = obtainBadge(newDifficulty) ?: return

        badgeSlideSign = if (delta > 0) 1 else -1
        badgePendingDifficulty = newDifficulty
        badgeSlideElapsed = 0.0

        // The slide-out tween exists in the source but is occluded the same
        // frame; park the old badge straight away.
        outgoing.visible = false
        outgoing.x = 90.0

        incoming.x = if (badgeSlideSign > 0) 500.0 else -320.0
        incoming.y = 80.0 - 5.0
        incoming.alpha = 0.5
        incoming.visible = true
        difficultyViewIn = incoming
        badgeSlideActive = true
    }

    private fun updateBadgeSlide(dt: Double) {
        badgeSlideElapsed += dt
        val t = (badgeSlideElapsed / 0.2).coerceIn(0.0, 1.0)
        val eased = circInOut(t)
        val enterX = if (badgeSlideSign > 0) 500.0 else -320.0

        difficultyViewIn?.let { incoming ->
            incoming.x = enterX + (90.0 - enterX) * eased
            // The 1/24 s flash timer: full alpha and the offset reset.
            if (badgeSlideElapsed >= 1.0 / 24.0) {
                incoming.alpha = 1.0
                incoming.y = 80.0
            }
        }

        if (t >= 1.0) finishBadgeSlide()
    }

    private fun finishBadgeSlide() {
        badgeSlideActive = false
        // The outgoing tween's onComplete: park back at 90 and hide.
        difficultyView?.also {
            it.x = 90.0
            it.y = 80.0
            it.alpha = 1.0
            it.visible = false
        }
        difficultyViewIn?.also { incoming ->
            incoming.x = 90.0
            incoming.y = 80.0
            incoming.alpha = 1.0
            incoming.visible = true
            difficultyView = incoming
        }
        difficultyViewIn = null
        shownDifficulty = badgePendingDifficulty
    }

    private fun circInOut(t: Double): Double =
        if (t < 0.5) {
            (1.0 - sqrt(1.0 - 4.0 * t * t)) / 2.0
        } else {
            (1.0 + sqrt(1.0 - (2.0 - 2.0 * t).pow(2.0))) / 2.0
        }

    /** `grpDifficulties` recycles one sprite per difficulty id. */
    private fun obtainBadge(difficultyId: String): DifficultyBadge? {
        difficultyBadges[difficultyId]?.let { return it }
        val badge = DifficultyBadge(difficultyLayer, difficultyId)
        if (!badge.hasContent) return null
        difficultyBadges[difficultyId] = badge
        return badge
    }

    // ======================================================================
    // Album roll
    // ======================================================================

    private fun updateAlbum() {
        val entry = selectedEntry() ?: run {
            resetAlbumRoll()
            return
        }
        val albumId = entry.song.metadataFor(resolvedVariation(entry)).playData.album ?: run {
            resetAlbumRoll()
            return
        }
        if (shownAlbumId == albumId) {
            updateAlbumStars()
            return
        }

        // `FreeplayState` only plays the switch animation once the album
        // actually changes after the initial DJ intro.
        val albumChanged = albumRollReady
        shownAlbumId = albumId
        albumLayer.removeChildren()
        albumArt = null
        albumTitle = null
        albumArtAnimator = null
        albumTitleSwitching = false
        clearAlbumStars()

        val info = FreeplayCatalog.albums[albumId] ?: return
        val art = KnfFiles.bitmapOrNull("preload/images/${info.artAsset}.png")
        if (art != null) {
            val view = Image(art).also {
                // AlbumRoll replaces the 262x262 `freeplayAlbum` symbol with
                // the album graphic; the symbol's pose supplies the tilt.
                it.anchor = Anchor2D.TOP_LEFT
                it.x = ALBUM_ART_X
                it.y = ALBUM_ART_Y
                albumLayer.addChild(it)
            }
            albumArt = view

            val atlas = albumArtAtlas ?: runCatching {
                AnimateAtlas("preload/images/freeplay/albumRoll/freeplayAlbum")
            }.getOrNull()?.also { albumArtAtlas = it }
            if (atlas != null) {
                val animator = AlbumArtAnimator(atlas, view).also { albumArtAnimator = it }
                animator.onFinish = { animator.play("idle", loop = true) }
                animator.play(if (albumChanged) "switch" else "idle", loop = !albumChanged)
            }
        }

        val titleAtlas = TextureAtlas.tryLoad("preload/images/${info.titleAsset}.xml")
        if (titleAtlas != null) {
            val idle = titleAtlas.framesByPrefix("idle")
            val switch = titleAtlas.framesByPrefix("switch")
            if (idle.isNotEmpty()) {
                albumTitleIdleFrames = idle
                albumTitleSwitchFrames = switch
                val offsetX = info.titleOffsets.getOrElse(0) { 0.0 }
                val offsetY = info.titleOffsets.getOrElse(1) { 0.0 }
                albumTitle = SparrowSprite(
                    titleAtlas,
                    idle,
                    fps = 24.0,
                    loop = true,
                ).also {
                    // AlbumRoll: FunkinSprite.createSparrow(width - 355, 500)
                    // plus the album's title offsets. No scale.
                    it.setPixelArt(true)
                    it.x = 925.0 + offsetX
                    it.y = 500.0 + offsetY
                    albumLayer.addChild(it)
                }
                if (albumChanged) playAlbumTitleSwitch()
            }
        }
        updateAlbumStars()
    }

    private fun resetAlbumRoll() {
        shownAlbumId = null
        albumLayer.removeChildren()
        albumArt = null
        albumTitle = null
        albumArtAnimator = null
        albumTitleSwitching = false
        clearAlbumStars()
    }

    /**
     * `AlbumRoll.skipIntro`: the title flashes its blue `switch` frames once
     * when the album changes, then returns to the black `idle` loop.
     */
    private fun playAlbumTitleSwitch() {
        val title = albumTitle ?: return
        val frames = albumTitleSwitchFrames
        if (frames.isEmpty()) return
        title.loop = false
        title.play(frames, restart = true)
        albumTitleSwitching = true
    }

    private fun updateAlbumTitleSwitch() {
        if (!albumTitleSwitching) return
        val title = albumTitle ?: run {
            albumTitleSwitching = false
            return
        }
        if (!title.finished) return
        albumTitleSwitching = false
        title.loop = true
        if (albumTitleIdleFrames.isNotEmpty()) title.play(albumTitleIdleFrames, restart = true)
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

        // `DifficultyStars.flameCheck`: ratings 11+ put the excess stars on
        // fire, one `freeplayFlame` per point above 10. The flames sit behind
        // the stars and above the album artwork.
        val flames = albumFlames ?: FreeplayFlamesView().also { albumFlames = it }
        if (flames.container.parent == null) albumLayer.addChild(flames.container)
        flames.setCount(if (rating > 10) rating - 10 else 0)

        // `DifficultyStars`: the `diff stars` timeline has one 100-frame band
        // per rating (0-99 = 1 star, ..., 1400-1499 = 15 stars). Each band
        // loops in place; rating 0 parks on frame 1500 ("empty rating").
        val band = when {
            rating == 0 -> listOf(1500)
            else -> ((rating - 1) * 100 until rating * 100)
                .filter { it in atlas.poses.indices }
        }
        if (band.isEmpty()) return
        val view = AnimateSpriteView(atlas).also {
            it.x = 950.0
            it.y = 209.0
            it.playFrames("difficulty-$rating", band, loop = rating != 0)
            if (rating == 0) it.stop()
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
     *
     * [fallbackRandom] selects Random when the remembered song is gone, the
     * way `generateSongList` does; the letter-sort callback instead lands on
     * the first real song (`curSelected = 1`).
     */
    private fun rebuildVisible(
        preserveSongId: String? = null,
        initial: Boolean = false,
        fallbackRandom: Boolean = false,
    ) {
        val keep = preserveSongId ?: selectedEntry()?.song?.id
        val next = ArrayList<FreeplayEntry?>(entries.size + 1)
        next += null
        for (entry in letterFilteredEntries()) {
            // `generateSongList`: keep songs that have the difficulty in any
            // variation visible to the character.
            if (hasDifficultyForCharacter(entry, currentDifficulty)) {
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
                if (entry == null) Constants.DEFAULT_VARIATION else resolvedVariation(entry),
            )
        }

        val keptIndex = if (keep == null) -1 else visible.indexOfFirst { it?.song?.id == keep }
        selected = when {
            keptIndex > 0 -> keptIndex
            fallbackRandom -> 0
            visible.size > 1 -> 1
            else -> 0
        }
        selected = selected.coerceIn(0, (visible.size - 1).coerceAtLeast(0))

        updateSelectionPositions()

        if (initial) {
            viewCapsules.forEachIndexed { index, capsule ->
                capsule.startIntro(intendedY((index + 1).toDouble()) + 10.0)
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
        val previous = selected
        selected = ((selected + delta) % viewCapsules.size + viewCapsules.size) % viewCapsules.size
        // `if (curSelected != prevSelected)` gates the scroll sound, so HOME /
        // END on the edge of the list stay silent.
        if (selected == previous) return
        updateSelectionPositions()
        updateAlbum()
        updateSelectionStats()
        // changeSelection runs changeDiff(0) in the source, and that calls
        // refreshDots: the dimmed dots follow the newly selected song.
        updateDifficultyDots()
        rememberSelection()
        scrollSound?.let { sfx.play(it, 0.4) }
        requestPreview()
    }

    private fun changeDifficulty(delta: Int) {
        val choices = availableDifficulties()
        if (choices.isEmpty()) return
        var index = choices.indexOf(currentDifficulty)
        if (index < 0) index = choices.indexOf(Constants.DEFAULT_DIFFICULTY)
        if (index < 0) index = 0
        val newDifficulty = choices[(index + delta + choices.size) % choices.size]

        // `changeDiff`: when the current song lacks the new difficulty, the
        // selection jumps to the nearest capsule that has it before the list
        // is regenerated. Selecting Random keeps Random selected.
        val entry = selectedEntry()
        val previewBefore = entry?.let { it.song.id to resolvedVariation(it) }
        val preserveSongId: String? = when {
            entry == null -> null
            hasDifficultyForCharacter(entry, newDifficulty) -> entry.song.id
            else -> visible.getOrNull(findClosestDiff(newDifficulty))?.song?.id
        }

        startDifficultyBadgeSlide(delta, newDifficulty)
        currentDifficulty = newDifficulty
        rebuildVisible(preserveSongId = preserveSongId, fallbackRandom = true)
        // `handleDifficultySwitch` regenerates the list with force=true, so
        // the capsules replay the same simultaneous jump-in after every
        // difficulty change.
        viewCapsules.forEachIndexed { capsuleIndex, capsule ->
            capsule.startIntro(intendedY((capsuleIndex + 1).toDouble()) + 10.0)
        }
        updateTopUi()
        rememberSelection()
        scrollSound?.let { sfx.play(it, 0.4) }
        // `playCurSongPreview` runs with `restartTrack: false`: a pure
        // difficulty switch keeps the current preview playing.
        requestPreviewIfTargetChanged(previewBefore)
    }

    /** `FreeplayState.favoriteSong`: F toggles the heart on the selected song. */
    private fun favoriteSong() {
        val capsule = viewCapsules.getOrNull(selected) ?: return
        val entry = selectedEntry() ?: return
        val key = "${entry.song.id}:${resolvedVariation(entry)}"
        val isFav = if (Save.isSongFavorited(key)) {
            Save.unfavoriteSong(key)
            false
        } else {
            Save.favoriteSong(key)
            true
        }
        capsule.setFavorite(isFav)
        if (isFav) {
            favSound?.let { sfx.play(it, 1.0) }
        } else {
            unfavSound?.let { sfx.play(it, 1.0) }
        }
        // The wiggle runs under `uiStateMachine.transition(Interacting)`.
        interactLockTimer = 0.2
    }

    // ----------------------------------------------------------------------
    // Character variations (`Song.getVariationsByCharacter` for BF)
    // ----------------------------------------------------------------------

    /**
     * `Song.getVariationsByCharacter(currentCharacter)`: the song's variations
     * whose player character is visible to BF, in `defaultsThenAlphabetically`
     * order against `DEFAULT_VARIATION_LIST`.
     *
     * `shouldShowCharacter` shows a char when BF owns it (`bf*`) or when no
     * playable character owns it (`bf.json`'s `showUnownedChars = true`). The
     * only other stock owner is the Pico player, whose chars are hidden from
     * BF, so visibility reduces to "not a pico char".
     */
    private fun characterVariations(entry: FreeplayEntry): List<String> =
        entry.song.variations
            .filter { isVariationVisibleToBf(entry.song.metadataFor(it).playData.characters.player) }
            .sortedWith(
                compareBy(
                    { difficultyOrVariationSortIndex(it, Constants.DEFAULT_VARIATION_LIST) },
                    { it },
                ),
            )

    private fun isVariationVisibleToBf(player: String): Boolean = !isPicoPlayer(player)

    private fun isPicoPlayer(player: String): Boolean =
        player == "pico" || player.startsWith("pico-")

    /** `Song.listDifficulties(variations)`: declared difficulties, sorted. */
    private fun listDifficultiesFor(entry: FreeplayEntry, variations: List<String>): List<String> =
        variations.flatMap { entry.song.difficultiesFor(it) }
            .distinct()
            .sortedWith(compareBy({ difficultyOrVariationSortIndex(it, Constants.DEFAULT_DIFFICULTY_LIST_FULL) }, { it }))

    /** `Song.hasDifficulty`: any bf-visible variation declares the difficulty. */
    private fun hasDifficultyForCharacter(entry: FreeplayEntry, diff: String): Boolean =
        characterVariations(entry).any { entry.song.difficultiesFor(it).contains(diff) }

    /** `SongRegistry.listAllDifficulties(currentCharacterId)` for BF. */
    private fun availableDifficulties(): List<String> {
        val found = LinkedHashSet<String>()
        for (entry in entries) {
            found.addAll(listDifficultiesFor(entry, characterVariations(entry)))
        }
        if (found.isEmpty()) return Constants.DEFAULT_DIFFICULTY_LIST
        return found.sortedWith(compareBy({ difficultyOrVariationSortIndex(it, Constants.DEFAULT_DIFFICULTY_LIST_FULL) }, { it }))
    }

    /** `SortUtil.defaultsThenAlphabetically`: defaults first, unknowns after, A-Z. */
    private fun difficultyOrVariationSortIndex(value: String, defaults: List<String>): Int =
        defaults.indexOf(value).let { if (it < 0) Int.MAX_VALUE else it }

    /**
     * `changeDiff`'s variation loop: the first bf-visible variation (defaults
     * first, then alphabetical) that declares the current difficulty owns the
     * mix. Erect remixes therefore pick the `erect` variation automatically,
     * and weekend 1 songs resolve to their `bf` mix.
     */
    private fun resolvedVariation(entry: FreeplayEntry): String =
        characterVariations(entry)
            .firstOrNull { entry.song.difficultiesFor(it).contains(currentDifficulty) }
            ?: Constants.DEFAULT_VARIATION

    /**
     * `findClosestDiff`: the capsule nearest to the selection (index distance
     * in the current list) that has [diff]. The source's `closestIndex == 0`
     * shortcut means "first candidate wins"; index 0 is the Random capsule, so
     * it doubles as "no candidate yet".
     */
    private fun findClosestDiff(diff: String): Int {
        var closestIndex = -1
        for ((index, entry) in visible.withIndex()) {
            entry ?: continue
            if (!hasDifficultyForCharacter(entry, diff)) continue
            val distance = abs(selected - index)
            if (closestIndex == -1 || distance < abs(closestIndex - selected)) closestIndex = index
        }
        return closestIndex
    }

    private fun rememberSelection() {
        rememberedSongId = selectedEntry()?.song?.id
        rememberedDifficulty = currentDifficulty
    }

    // ======================================================================
    // Preview / playback
    // ======================================================================

    /** The `(songId, variation)` pair the song preview is targeting right now. */
    private fun previewTarget(): Pair<String, String>? =
        selectedEntry()?.let { it.song.id to resolvedVariation(it) }

    /**
     * `playCurSongPreview` calls `FunkinSound.playMusic(..., restartTrack: false)`:
     * an unchanged target keeps the current preview playing seamlessly instead
     * of restarting it, so only real song/variation switches reload the track.
     */
    private fun requestPreviewIfTargetChanged(previous: Pair<String, String>?) {
        if (previewTarget() != previous) requestPreview()
    }

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
        PlayRequest.storyMode = KnfEnv["KNF_STORY"] == "1"

        rememberedSongId = entry.song.id
        rememberedDifficulty = currentDifficulty

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

    /** Vertical culloff band around the viewport for capsule rendering. */
    private val CAPSULE_CULL_TOP = -400.0
    private val CAPSULE_CULL_BOTTOM = Constants.GAME_HEIGHT + 300.0

    private var confirmSound: String? = null
    private var scrollSound: String? = null
    private var cancelSound: String? = null
    private var favSound: String? = null
    private var unfavSound: String? = null

    private var headerSized = false

    /** Scene root container captured in [sceneInit] for late view additions. */
    private var sceneRoot: SContainer? = null

    private suspend fun loadSounds() {
        confirmSound = KnfFiles.resolveSound("preload/sounds/confirmMenu")?.also { sfx.preload(it) }
        scrollSound = KnfFiles.resolveSound("preload/sounds/scrollMenu")?.also { sfx.preload(it) }
        cancelSound = KnfFiles.resolveSound("preload/sounds/cancelMenu")?.also { sfx.preload(it) }
        favSound = KnfFiles.resolveSound("preload/sounds/fav")?.also { sfx.preload(it) }
        unfavSound = KnfFiles.resolveSound("preload/sounds/unfav")?.also { sfx.preload(it) }
    }


    /**
     * `StickerSubState.degenStickers`: the stickers that covered the screen
     * during the switch peel off one by one, key clicks and all, revealing
     * the scene behind them.
     */
    private fun addStickerRevealOverlay(reveal: StickerReveal) {
        val root = sceneRoot ?: return
        val overlay = Container()
        root.addChild(overlay)
        val images = reveal.placements.map { placement ->
            Image(KnfFiles.bitmap("shared/images/${placement.path}.png")).also { img ->
                img.smoothing = true
                img.x = placement.x
                img.y = placement.y
                img.rotation = placement.angleDegrees.degrees
                img.scaleX = placement.popScale
                img.scaleY = placement.popScale
                overlay.addChild(img)
            }
        }
        overlay.addUpdater { dt ->
            revealElapsed += dt.secondsDouble()
            var remaining = 0
            for ((index, img) in images.withIndex()) {
                val placement = reveal.placements[index]
                if (img.visible && placement.appearAt <= revealElapsed) {
                    img.visible = false
                    if (placement.soundStem.isNotEmpty()) sfx.play(placement.soundStem)
                }
                if (img.visible) remaining++
            }
            if (remaining == 0 || revealElapsed >= 2.0) {
                overlay.removeFromParent()
                revealActive = false
            }
        }
    }

    private var revealActive = false
    private var revealElapsed = 0.0

    // ======================================================================
    // Update loop
    // ======================================================================

    override suspend fun SContainer.sceneMain() {
        val knfInput = KnfInput(keys)
        val reportFps = KnfEnv["KNF_FPS"] == "1"
        var fpsAccum = 0.0
        var fpsFrames = 0
        addUpdater { dt ->
            val d = dt.secondsDouble()
            if (reportFps) {
                fpsAccum += d
                fpsFrames++
                if (fpsAccum >= 1.0) {
                    println("KNF_FPS ${KnfFmt.decimals(fpsFrames / fpsAccum, 1)}")
                    fpsAccum = 0.0
                    fpsFrames = 0
                }
            }
            knfInput.update()

            updateCard(d)
            updateDj(d)
            // Korge computes Text bounds during the first render, so the
            // FlxText-derived plate behind FREEPLAY is sized here — once:
            // re-reading the text size every frame stalled the mobile render
            // thread in the text layout path.
            if (!headerSized) {
                headerBg?.let { bg ->
                    headerText?.let { txt ->
                        if (txt.width > 0.0 && txt.height > 0.0) {
                            headerSized = true
                            bg.unscaledSize = korlibs.math.geom.Size(
                                txt.width + 16.0,
                                txt.height + 16.0,
                            )
                        }
                    }
                }
            }
            // Mirror Flixel's behaviour (what the original FNF relies on):
            // every capsule updates (positions keep lerping), but only the
            // ones near the viewport get drawn. korlibs has no viewport
            // culling, so this has to be explicit.
            for (capsule in viewCapsules) {
                val onScreen = capsule.y > CAPSULE_CULL_TOP && capsule.y < CAPSULE_CULL_BOTTOM
                if (capsule.visible != onScreen) capsule.visible = onScreen
                capsule.update(d)
            }
            letterSort?.update(d)
            for (selector in selectors) selector.update(d)
            for (badge in difficultyBadges.values) badge.update(d)
            albumTitle?.update(d)
            updateAlbumTitleSwitch()
            albumArtAnimator?.update(d)
            albumStarsView?.update(d)
            albumFlames?.update(d)
            highscoreSprite?.update(d)
            preview.update()

            displayedScore += (targetScore - displayedScore) * 0.2
            if (abs(targetScore - displayedScore) < 1.0) displayedScore = targetScore.toDouble()
            displayedCompletion += (targetCompletion - displayedCompletion) * 0.5
            if (abs(targetCompletion - displayedCompletion) < 0.005) {
                displayedCompletion = targetCompletion
            }
            updateScoreVisual()
            scoreDigits.forEach { it.update(d) }
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

            if (!transitioning && !leaving && !revealActive) {
                handleInputs(knfInput, d)
            }

        }

        // Menu gestures on top of everything (tap = accept, drags = navigation).
        if (KnfTouch.enabled) addChild(TouchControls())
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

        if (badgeSlideActive) updateBadgeSlide(dt)

        if (newCharacterCard) updateNewCharacterCard(dt) else updateBoyfriendCard(dt)

        // FreeplayState.update: hintTimer += elapsed * 2;
        // charSelectHint.alpha = lerp(0.3, 0.9, (sin(hintTimer) + 1) / 2).
        hintTimer += dt * 2.0
        charHint?.alpha = lerp(0.3, 0.9, (sin(hintTimer) + 1.0) / 2.0)
    }

    private fun updateNewCharacterCard(dt: Double) {
        newCardTimer += dt * 2.0
        val s = (sin(newCardTimer) + 1.0) / 2.0

        newCardLight1?.alpha = lerp(0.4, 1.0, s)
        newCardLight2?.alpha = lerp(0.2, 0.5, s)
        newCardLight3?.alpha = lerp(0.1, 0.7, s)
        newCardMultiply1?.alpha = lerp(1.0, 0.21, s)
        newCardMultiply2?.alpha = lerp(1.0, 0.21, s)
        newCardYellow?.alpha = lerp(0.2, 0.72, s)

        // `angleMaskShader.extraColor` multiplies the backing image.
        backingImage?.tint = mixColor(0x2E2E46, 0x60607B, s)
        newCardBands.forEach { it.update(dt) }
    }

    /** `BoyfriendCard`: marquees, animated collage and beat-synced glows. */
    private fun updateBoyfriendCard(dt: Double) {
        if (!djIntroDone) return

        boyfriendBands.forEach { it.update(dt) }

        val bpm: Double = selectedEntry()?.let { entry ->
            entry.song.metadataFor(resolvedVariation(entry)).timeChanges.firstOrNull()?.bpm?.toDouble()
        } ?: 100.0
        if (bpm > 0.0) {
            val beatFreq = BEAT_FREQUENCIES[
                (bpm / 140.0).toInt().coerceIn(0, BEAT_FREQUENCIES.size - 1),
            ]
            val secondsPerBeat = 60.0 / bpm
            beatClock += dt
            while (beatClock >= secondsPerBeat) {
                beatClock -= secondsPerBeat
                beatIndex++
                if (beatIndex % beatFreq == 0) pulseBeatGlow()
            }
        }

        if (glowTweenActive) {
            glowTweenElapsed += dt
            val t = (glowTweenElapsed / (16.0 / 24.0)).coerceIn(0.0, 1.0)
            beatGlow?.alpha = 0.8 * (1.0 - quartOut(t))
            if (t >= 1.0) {
                beatGlow?.alpha = 0.0
                glowTweenActive = false
            }
        }
        if (glowDarkTweenActive) {
            glowDarkTweenElapsed += dt
            val t = (glowDarkTweenElapsed / (18.0 / 24.0)).coerceIn(0.0, 1.0)
            beatGlowDark?.alpha = 0.6 * quartOut(t)
            if (t >= 1.0) {
                beatGlowDark?.alpha = 0.6
                glowDarkTweenActive = false
            }
        }
    }

    private fun pulseBeatGlow() {
        beatGlow?.alpha = 0.8
        glowTweenElapsed = 0.0
        glowTweenActive = true
        beatGlowDark?.alpha = 0.0
        glowDarkTweenElapsed = 0.0
        glowDarkTweenActive = true
    }

    private fun quartOut(t: Double): Double = 1.0 - (1.0 - t).pow(4.0)

    private fun lerp(a: Double, b: Double, t: Double): Double = a + (b - a) * t

    private fun mixColor(a: Int, b: Int, t: Double): RGBA {
        fun channel(shift: Int): Int {
            val ca = (a shr shift) and 0xFF
            val cb = (b shr shift) and 0xFF
            return (ca + (cb - ca) * t).toInt().coerceIn(0, 255)
        }
        return RGBA(channel(16), channel(8), channel(0))
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
        if (newCharacterCard) {
            // NewCharacterCard keeps the pink plate underneath; its yellow and
            // red multiply passes create the in-game card colour.
            newCardGroup?.visible = true
        } else {
            // BackingCard.introDone(): plate turns yellow, the two strips and
            // all scripted marquees/beat glows appear.
            pinkBack?.tint = RGBA(0xFF, 0xD8, 0x63)
            orangeStrip?.visible = true
            orangeStripLeft?.visible = true
            boyfriendBands.forEach { it.container.visible = true }
            beatGlow?.visible = true
            beatGlowDark?.visible = true
        }
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
        scoreDigits.forEach { it.setVisible(true) }
        clearBox?.visible = true
        completionDigits.forEach { it.visible = true }
        difficultyDots.forEach { it.visible = true }
        updateTopUi()
        updateAlbum()
        albumRollReady = true
        requestPreview()
    }

    private fun hasDjLabel(label: String): Boolean =
        djAtlas?.labelFrames(label)?.isNotEmpty() == true

    private fun handleInputs(input: KnfInput, dt: Double) {
        // `uiStateMachine.transition(EnteringFreeplay)` blocks interaction
        // until BF's turntable intro reaches `onDJIntroDone`.
        if (!djIntroDone) return

        // The favourite wiggle runs under `Interacting`, which pauses every
        // input for its 0.2 s just like the source's state machine.
        if (interactLockTimer > 0.0) {
            interactLockTimer -= dt
            return
        }

        if (input.back) {
            backToMenu()
            return
        }
        if (input.accept) {
            startSelected()
            return
        }

        // `controls.FREEPLAY_JUMP_TO_TOP` / `FREEPLAY_JUMP_TO_BOTTOM` (HOME/END).
        if (input.jumpToTop) {
            changeSelection(-selected)
            return
        }
        if (input.jumpToBottom) {
            changeSelection(viewCapsules.size - 1 - selected)
            return
        }
        if (input.favorite) {
            favoriteSong()
            return
        }

        // `FreeplayState.handleDirectionalInput`: change once on press, then
        // start repeating every 0.07 s after the key has been held for 0.9 s.
        val up = input.isDown(2)
        val down = input.isDown(1)
        if (up || down) {
            if (spamming) {
                if (spamTimer >= 0.07) {
                    spamTimer = 0.0
                    changeSelection(if (up) -1 else 1)
                }
            } else if (spamTimer >= 0.9) {
                spamming = true
            } else if (spamTimer <= 0.0) {
                changeSelection(if (up) -1 else 1)
            }
            spamTimer += dt
        } else {
            spamming = false
            spamTimer = 0.0
        }

        if (input.justPressed(0)) changeDifficulty(-1)
        if (input.justPressed(3)) changeDifficulty(1)
        if (input.letterSortLeft) letterSort?.changeSelection(-1)
        if (input.letterSortRight) letterSort?.changeSelection(1)
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

        /** `BoyfriendCard.beatFreqList`: `[1, 2, 4, 8]`. */
        private val BEAT_FREQUENCIES = intArrayOf(1, 2, 4, 8)

        /** `AngleMask.endPosition.x`: the backdrop cut is 90 px at its bottom. */
        private const val ANGLE_MASK_X = 90.0

        /** `FreeplayState.rememberedSongId` starts at the tutorial in FNF. */
        private var rememberedSongId: String? = "tutorial"
        private var rememberedDifficulty = Constants.DEFAULT_DIFFICULTY
    }
}

/**
 * One `DifficultySprite` from `freeplay/components/DifficultySprite.hx`.
 *
 * The asset id sheds '-' suffixes until `freeplay<id>.png` exists; when a
 * Sparrow XML ships next to it (stock nightmare), the sprite plays its
 * looping `idle0` glitch animation at 24 fps instead of showing a static
 * image. `Preferences.flashingLights` gates the animation and defaults on.
 */
private class DifficultyBadge(
    parent: Container,
    difficultyId: String,
) : Container() {
    val hasContent: Boolean
    private val animation: SparrowSprite?

    init {
        animation = buildChild(difficultyId)
        hasContent = numChildren > 0
        x = 90.0
        y = 80.0
        visible = false
        parent.addChild(this)
    }

    private fun buildChild(difficultyId: String): SparrowSprite? {
        var assetId = difficultyId
        while (assetId.isNotEmpty() && !KnfFiles.exists("preload/images/freeplay/freeplay$assetId.png")) {
            assetId = assetId.substringBeforeLast('-', "")
        }
        if (assetId.isEmpty()) return null

        val atlas = TextureAtlas.tryLoad("preload/images/freeplay/freeplay$assetId.xml")
        val frames = atlas?.framesByPrefix("idle0").orEmpty().sortedBy { it.name }
        if (atlas != null && frames.isNotEmpty()) {
            return SparrowSprite(atlas, frames, fps = 24.0, loop = true).also {
                // FlxSprite renders with antialiasing off; nearest sampling
                // keeps the badge crisp while it slides.
                it.setPixelArt(true)
                addChild(it)
                it.play(frames, restart = true)
            }
        }

        KnfFiles.bitmapOrNull("preload/images/freeplay/freeplay$assetId.png")?.let { bitmap ->
            addChild(Image(bitmap).also { it.smoothing = false })
        }
        return null
    }

    fun update(dt: Double) {
        animation?.update(dt)
    }
}

/**
 * Kotlin equivalent of FNF's `BGScrollingText`: a row of identical text
 * copies that scrolls horizontally and wraps seamlessly.
 */
private class BackingScrollBand(
    val container: Container,
    val copies: List<Container>,
    val textWidth: Double,
    val placementOffset: Double,
    var speed: Double,
) {
    private val positions = copies.map { it.x }.toMutableList()

    fun update(dt: Double) {
        if (dt <= 0.0) return
        val step = speed * (dt / (1.0 / 60.0))
        for (i in positions.indices) positions[i] -= step

        if (speed > 0.0) {
            var last = positions.maxOrNull() ?: 0.0
            for (i in positions.indices) {
                if (positions[i] < -textWidth) {
                    positions[i] = last + textWidth + placementOffset
                    last = positions[i]
                }
            }
        } else if (speed < 0.0) {
            var first = positions.minOrNull() ?: 0.0
            for (i in positions.indices) {
                if (positions[i] > textWidth * 2.0) {
                    positions[i] = first - textWidth - placementOffset
                    first = positions[i]
                }
            }
        }

        positions.sort()
        for (i in copies.indices) copies[i].x = positions[i]
    }
}

/**
 * `FreeplayFlames.hx`: the blue fire on the excess difficulty stars.
 *
 * Ratings above 10 light up `rating - 10` flames: five pre-created sprites
 * laid out along the top edge of the album cover. Each one plays its ignition
 * once, then loops from frame 2; newly visible flames are revealed one by one
 * every 0.25 s.
 */
private class FreeplayFlamesView {
    companion object {
        const val FLAME_X = 913.0
        const val FLAME_Y = 91.0
        const val SPREAD_X = 29.0
        const val SPREAD_Y = 6.0
        const val REVEAL_DELAY = 0.25
    }

    val container = Container()
    private val flames: List<SparrowSprite>

    private class Pending(val index: Int, val delay: Double)

    private val pending = ArrayList<Pending>()
    private var clock = 0.0

    init {
        val atlas = TextureAtlas.tryLoad("preload/images/freeplay/freeplayFlame.xml")
        val frames = atlas?.framesByPrefix("fire loop full instance 1").orEmpty()
        flames = if (atlas != null && frames.isNotEmpty()) {
            (0 until 5).map { i ->
                // `FlxG.random.int(23, 25)` per flame.
                SparrowSprite(
                    atlas,
                    frames,
                    fps = Random.nextInt(23, 26).toDouble(),
                    loop = false,
                ).also {
                    it.setPixelArt(true)
                    it.x = FLAME_X + SPREAD_X * i
                    it.y = FLAME_Y + SPREAD_Y * i
                    it.visible = false
                    container.addChild(it)
                }
            }
        } else {
            emptyList()
        }
    }

    /** `FreeplayFlames.flameCount`, clamped to the five available flames. */
    fun setCount(count: Int) {
        val target = count.coerceIn(0, flames.size)
        // `set_flameCount` destroys every pending reveal timer first.
        pending.clear()
        clock = 0.0
        var waiting = 0
        for (i in flames.indices) {
            val flame = flames[i]
            if (i >= target) {
                flame.visible = false
            } else if (!flame.visible) {
                pending += Pending(i, REVEAL_DELAY * waiting)
                waiting++
            }
        }
    }

    fun update(dt: Double) {
        for (flame in flames) {
            flame.update(dt)
            // `onFinish`: play("flame", true, false, 2), skipping ignition.
            if (!flame.playing && flame.finished) flame.seekTo(2)
        }
        if (pending.isEmpty()) return
        clock += dt
        val iterator = pending.iterator()
        while (iterator.hasNext()) {
            val next = iterator.next()
            if (clock < next.delay) continue
            val flame = flames[next.index]
            flame.restart()
            flame.visible = true
            iterator.remove()
        }
    }
}

/**
 * Drives the album sleeve through `freeplay/albumRoll/freeplayAlbum`'s
 * timeline. `intro` rolls it in, `switch` does the 8 px pop when a different
 * album is selected, and `idle` holds the tilted pose. Each pose is applied to
 * the plain album `Image` with the same matrix/bounds convention as
 * `AnimateSpriteView`.
 */
private class AlbumArtAnimator(
    private val atlas: AnimateAtlas,
    private val art: Image,
) {
    private val bounds = atlas.bounds
    private var frames: List<Int> = emptyList()
    private var index = 0
    private var elapsed = 0.0
    private var loop = false
    private var playing = false

    /** Fired when a non-looping animation reaches its last frame. */
    var onFinish: (() -> Unit)? = null

    fun play(label: String, loop: Boolean = false) {
        val list = atlas.labelFrames(label)
        if (list.isEmpty()) return
        frames = list
        this.loop = loop
        index = 0
        elapsed = 0.0
        playing = true
        applyFrame(0)
    }

    fun update(dt: Double) {
        if (!playing || frames.isEmpty()) return
        elapsed += dt
        val step = 1.0 / atlas.frameRate
        var changed = false
        while (elapsed >= step) {
            elapsed -= step
            if (index + 1 < frames.size) {
                index++
                changed = true
            } else if (loop) {
                index = 0
                changed = true
            } else {
                playing = false
                onFinish?.invoke()
                break
            }
        }
        if (changed) applyFrame(index)
    }

    private fun applyFrame(i: Int) {
        val pose = atlas.poses.getOrNull(frames.getOrNull(i) ?: return) ?: return
        val part = pose.parts.firstOrNull() ?: return
        val m = part.matrix
        art.x = ALBUM_ART_X + (m.tx - bounds.minX)
        art.y = ALBUM_ART_Y + (m.ty - bounds.minY)
        art.rotation = KnfMath.toDegrees(atan2(m.b, m.a)).degrees
        art.scaleX = hypot(m.a, m.b)
        art.scaleY = hypot(m.c, m.d)
    }
}

private const val ALBUM_ART_X = 920.0
private const val ALBUM_ART_Y = 220.0