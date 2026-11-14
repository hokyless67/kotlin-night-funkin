package nightfunkin.ui

import korlibs.image.bitmap.Bitmap
import korlibs.image.bitmap.extract
import korlibs.korge.scene.Scene
import korlibs.korge.view.Image
import korlibs.korge.view.SContainer
import korlibs.korge.view.addUpdater
import korlibs.korge.view.renderToBitmap
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import nightfunkin.audio.MenuMusic
import nightfunkin.audio.SfxPlayer
import nightfunkin.core.Constants
import nightfunkin.core.KnfFiles
import nightfunkin.core.KnfInput
import nightfunkin.core.KnfTouch
import nightfunkin.core.SparrowSprite
import nightfunkin.core.TextureAtlas
import nightfunkin.core.syncViewport
import nightfunkin.platform.KnfLinks
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Friday Night Funkin' 0.8.6's `MainMenuState`.
 *
 * The background and the five menu items are real Sparrow atlases. The camera
 * follows the selected item with the same 0.06 lerp, and the background/items
 * use FNF's 0.17/0.4 scroll factors, so moving through the list pans the scene
 * instead of sliding each label independently.
 */
class MainMenuScene : Scene() {

    private val music = MenuMusic.player
    private val sfx = SfxPlayer()

    private var confirmSoundPath: String? = null
    private var scrollSoundPath: String? = null
    private var cancelSoundPath: String? = null

    private var bg: Image? = null
    private var bgBaseY = 0.0
    private var magenta: Image? = null

    /** The scene's root container; member functions like `goBack` add to it. */
    private lateinit var sceneRoot: SContainer

    private val items = ArrayList<MenuItem>()
    private var selectedIndex = 0

    private var cameraScrollY = 0.0
    private var targetScrollY = 0.0

    private var interacting = false
    private var busy = false
    private var exiting = false

    private var acceptingItem: MenuItem? = null
    private var itemFlickerElapsed = 0.0
    private var itemFlickerTogglesLeft = 0

    /** `goBack`'s default FNF transition: 0.7 s fade to black, then the title. */
    private var leaving = false
    private var leaveElapsed = 0.0

    private var magentaFlickerElapsed = 0.0
    private var magentaFlickerTogglesLeft = 0

    /** `freeplay.goBack` restarts freakyMenu from the top with a 4 s fade-in. */
    private var musicFadeInElapsed = -1.0

    /** `FlxTransitionableState.defaultTransIn`: 1 s black wipe on entry. */
    private var fadeInWipe: SceneWipe? = null
    private var fadeInElapsed = 0.0

    /** `defaultTransOut`: the 0.7 s wipe to the title, created in `goBack`. */
    private var fadeOutWipe: SceneWipe? = null

    override suspend fun SContainer.sceneInit() {
        sceneRoot = this
        syncViewport()
        buildBackground()
        buildMenuItems()

        val spacing = 160.0
        val top = (Constants.GAME_HEIGHT - spacing * (items.size - 1)) / 2.0
        for ((index, item) in items.withIndex()) {
            item.worldY = top + spacing * index
            item.sprite.x = Constants.GAME_WIDTH / 2.0
            item.sprite.y = item.worldY
            addChild(item.sprite)
        }

        selectedIndex = rememberedSelectedIndex.coerceIn(0, (items.size - 1).coerceAtLeast(0))
        if (items.isNotEmpty()) selectItem(selectedIndex)
        resetCamera(snap = true)
        applyCamera()

        loadSounds()
        loadAndStartMusic()

        // `FlxTransitionableState.defaultTransIn`: the 1 s black wipe. Freeplay
        // closes back into this state as a substate, which never plays a
        // transition (`skipNextTransIn`), so that path opts out.
        if (!SceneWipe.consumeSkipNextReveal()) {
            fadeInWipe = SceneWipe(
                Constants.GAME_WIDTH.toDouble(),
                Constants.GAME_HEIGHT.toDouble(),
                reveal = true,
            ).also { addChild(it) }
        }
    }

    private fun SContainer.buildBackground() {
        val bitmap = KnfFiles.bitmapOrNull("preload/images/menuBG.png") ?: return
        // `setGraphicSize(Std.int(FlxG.width * 1.2))` keeps the source aspect.
        val targetWidth = (Constants.GAME_WIDTH * 1.2).toInt().toDouble()
        val scale = targetWidth / bitmap.width
        val targetHeight = bitmap.height * scale

        bgBaseY = (Constants.GAME_HEIGHT - targetHeight) / 2.0
        bg = Image(bitmap).also {
            it.smoothing = false
            it.scaleX = scale
            it.scaleY = scale
            it.x = (Constants.GAME_WIDTH - targetWidth) / 2.0
            it.y = bgBaseY
            addChild(it)
        }

        val magentaBitmap = KnfFiles.bitmapOrNull("preload/images/menuBGMagenta.png") ?: return
        // `magenta.setGraphicSize(Std.int(bg.width))`; it shares the bg position.
        val magentaScale = targetWidth / magentaBitmap.width
        magenta = Image(magentaBitmap).also {
            it.smoothing = false
            it.scaleX = magentaScale
            it.scaleY = magentaScale
            it.x = bg?.x ?: 0.0
            it.y = bgBaseY
            it.visible = false
            addChild(it)
        }
    }

    private fun buildMenuItems() {
        createMenuItem("storymode", "mainmenu/storymode") {
            onScreenNotPorted("Story Mode")
        }
        createMenuItem("freeplay", "mainmenu/freeplay") {
            startFreeplay()
        }
        // Desktop 0.8.6 has FEATURE_OPEN_URL enabled and hasUpgraded is always
        // true outside the mobile IAP build, so Merch is present. It fires from
        // the key event itself, without the confirm flicker.
        createMenuItem("merch", "mainmenu/merch", fireInstantly = true) {
            openMerch()
        }
        createMenuItem("options", "mainmenu/options") {
            onScreenNotPorted("Options")
        }
        createMenuItem("credits", "mainmenu/credits") {
            onScreenNotPorted("Credits")
        }
    }

    private fun createMenuItem(
        name: String,
        atlasPath: String,
        fireInstantly: Boolean = false,
        callback: () -> Unit,
    ) {
        val atlas = TextureAtlas.tryLoad("preload/images/$atlasPath.xml") ?: return
        val idle = atlas.framesByPrefix("$name idle")
        val selected = atlas.framesByPrefix("$name selected")
        if (idle.isEmpty() || selected.isEmpty()) return
        items += MenuItem(atlas, idle, selected, callback, fireInstantly)
    }

    private suspend fun loadSounds() {
        confirmSoundPath = KnfFiles.resolveSound("preload/sounds/confirmMenu")
            ?.also { sfx.preload(it) }
        scrollSoundPath = KnfFiles.resolveSound("preload/sounds/scrollMenu")
            ?.also { sfx.preload(it) }
        cancelSoundPath = KnfFiles.resolveSound("preload/sounds/cancelMenu")
            ?.also { sfx.preload(it) }
    }

    private suspend fun loadAndStartMusic() {
        val path = KnfFiles.resolveSound(MenuMusic.PATH_STEM) ?: return
        // `playMenuMusic`: overrideExisting = true, restartTrack = false,
        // persist = true. Loading the same sound is a no-op, and `playing`
        // keeps the track running across the title/main-menu transition.
        val wasPaused = music.isPaused
        music.load(path, 1.0)
        if (!music.playing) {
            if (wasPaused) {
                // Freeplay closed: FNF's playMusic sees a paused track as not
                // playing, replaces it, then calls fadeIn(4, 0, 1).
                music.volume = 0.0
                music.play(0.0)
                musicFadeInElapsed = 0.0
            } else {
                music.volume = 1.0
                music.play(0.0)
            }
        }
    }

    private fun selectItem(index: Int) {
        if (items.isEmpty()) return
        val wrapped = ((index % items.size) + items.size) % items.size
        items.getOrNull(selectedIndex)?.idle()
        selectedIndex = wrapped
        val selected = items[wrapped]
        selected.select()
        targetScrollY = selected.worldY - Constants.GAME_HEIGHT / 2.0
    }

    private fun resetCamera(snap: Boolean) {
        if (items.isEmpty()) return
        targetScrollY = items[selectedIndex].worldY - Constants.GAME_HEIGHT / 2.0
        if (snap) cameraScrollY = targetScrollY
    }

    private fun updateCamera(dt: Double) {
        // FlxCamera.follow(..., 0.06) interpolates once per 1/60 s.
        val follow = 1.0 - (1.0 - FOLLOW_LERP).pow((dt * 60.0).coerceAtLeast(0.0))
        cameraScrollY += (targetScrollY - cameraScrollY) * follow
    }

    private fun applyCamera() {
        val scroll = cameraScrollY
        bg?.let { it.y = bgBaseY - scroll * BG_SCROLL_Y }
        magenta?.let { it.y = bgBaseY - scroll * BG_SCROLL_Y }
        for (item in items) {
            item.sprite.y = item.worldY - scroll * ITEM_SCROLL_Y
        }
    }

    private fun startItemFlicker(item: MenuItem) {
        acceptingItem = item
        itemFlickerElapsed = 0.0
        // FlxFlicker.flicker(item, 1, 0.06, true, false): 16 toggles end visible.
        itemFlickerTogglesLeft = (1.0 / 0.06).toInt()
        item.sprite.visible = true
    }

    private fun startMagentaFlicker() {
        // FlxFlicker.flicker(magenta, 1.1, 0.15, false, true): 7 toggles end hidden.
        if (magentaFlickerTogglesLeft > 0) magenta?.visible = true
        magentaFlickerElapsed = 0.0
        magentaFlickerTogglesLeft = (1.1 / 0.15).toInt()
    }

    private fun updateFlicker(dt: Double) {
        acceptingItem?.let { item ->
            itemFlickerElapsed += dt
            while (itemFlickerElapsed >= ITEM_FLICKER_INTERVAL && itemFlickerTogglesLeft > 0) {
                itemFlickerElapsed -= ITEM_FLICKER_INTERVAL
                item.sprite.visible = !item.sprite.visible
                itemFlickerTogglesLeft--
            }
            if (itemFlickerTogglesLeft == 0) {
                item.sprite.visible = true
                acceptingItem = null
                busy = false
                item.callback()
            }
        }

        if (magentaFlickerTogglesLeft > 0) {
            magentaFlickerElapsed += dt
            while (magentaFlickerElapsed >= MAGENTA_FLICKER_INTERVAL && magentaFlickerTogglesLeft > 0) {
                magentaFlickerElapsed -= MAGENTA_FLICKER_INTERVAL
                magenta?.visible = !(magenta?.visible ?: false)
                magentaFlickerTogglesLeft--
            }
            if (magentaFlickerTogglesLeft == 0) magenta?.visible = false
        }
    }

    private fun handleInputs(input: KnfInput) {
        if (exiting || interacting || busy || items.isEmpty()) return

        if (input.back) {
            goBack()
            return
        }

        // MenuTypedList uses UI_UP_P/UI_DOWN_P and wraps in both directions.
        val up = input.justPressed(2)
        val down = input.justPressed(1)
        if (up != down) {
            scrollSoundPath?.let { sfx.play(it, 0.4) }
            selectItem(if (up) selectedIndex - 1 else selectedIndex + 1)
            return
        }

        if (input.accept) acceptSelected()
    }

    private fun acceptSelected() {
        val item = items.getOrNull(selectedIndex) ?: return
        interacting = true
        startMagentaFlicker()

        if (item.fireInstantly) {
            item.callback()
            return
        }

        busy = true
        confirmSoundPath?.let { sfx.play(it) }
        startItemFlicker(item)
    }

    private fun startFreeplay() {
        rememberedSelectedIndex = selectedIndex
        exiting = true
        // FNF opens Freeplay as a substate of MainMenuState with
        // `persistentDraw = true`, so the menu stays on screen while the
        // capsules and the pink card animate in. KorGE replaces the scene, so
        // hand Freeplay the menu's last frame to draw under its entrance.
        launch {
            // `renderToBitmap` waits for the next frame, hence the coroutine;
            // the timeout keeps a stalled renderer from freezing the switch.
            val frame = withTimeoutOrNull(1_000L) {
                runCatching { captureStageFrame() }.getOrNull()
            }
            FreeplayBackdrop.offer(frame)
            // FNF opens Freeplay as a substate and leaves freakyMenu playing until
            // the first capsule selection pauses it. SongSelectScene owns that pause
            // and the preview, so the menu track must stay loaded here.
            sceneContainer.changeTo<SongSelectScene>()
        }
    }

    /**
     * Snapshots the visible game area for the Freeplay hand-off.
     *
     * Rendering the stage directly is normally already the game area - the
     * stage is a `FixedSizeContainer`, so its local bounds are its virtual
     * size. That cache is only invalidated from its children, though, and
     * `KnfViewport` resizes the virtual area underneath it after the window
     * reports its size, so the snapshot used to come out at the previous
     * 1280x720 size and left a cropped copy of the menu on the right. Refresh
     * the bounds and crop the game area out of whatever the stage reports.
     */
    private suspend fun captureStageFrame(): Bitmap {
        // An in wipe that is still running extends the stage bounds by several
        // screens; the entrance covers that area anyway, so drop it first.
        fadeInWipe?.removeFromParent()
        fadeInWipe = null

        // The menu flashes its magenta plate when an item is accepted and
        // Freeplay freezes the menu mid-flash. The captured frame is what stays
        // on screen through the entrance, so hide the flash for that render;
        // otherwise the whole backdrop comes out magenta.
        val magentaVisible = magenta?.visible == true
        magenta?.visible = false
        try {
            // The stage is a `FixedSizeContainer`, so its local bounds are the game
            // area - but that cache is only invalidated from the children and the
            // virtual size can change underneath it (`KnfViewport`), so refresh it
            // to keep the snapshot from coming out at the old 1280x720 size.
            stage.invalidateLocalBounds()
            val bounds = stage.getLocalBounds(includeFilters = true)
            // `includeBackground` fills the parts the menu art does not cover with
            // the window's clear colour, like FNF's `persistentDraw` does.
            val full = stage.renderToBitmap(includeBackground = true)
            val width = Constants.GAME_WIDTH
            val height = Constants.GAME_HEIGHT
            val left = (-bounds.x).roundToInt().coerceIn(0, (full.width - width).coerceAtLeast(0))
            val top = (-bounds.y).roundToInt().coerceIn(0, (full.height - height).coerceAtLeast(0))
            return full.extract(
                left,
                top,
                width.coerceAtMost(full.width - left).coerceAtLeast(1),
                height.coerceAtMost(full.height - top).coerceAtLeast(1),
            )
        } finally {
            if (magentaVisible) magenta?.visible = true
        }
    }

    private fun onScreenNotPorted(name: String) {
        println("KNF: $name is not ported yet")
        interacting = false
        busy = false
        selectedIndex = selectedIndex.coerceIn(0, (items.size - 1).coerceAtLeast(0))
    }

    private fun openMerch() {
        KnfLinks.open("https://needlejuicerecords.com/en-ca/pages/friday-night-funkin")
        // `selectMerch` returns the menu to Idle immediately.
        interacting = false
    }

    private fun goBack() {
        if (leaving) return
        rememberedSelectedIndex = selectedIndex
        exiting = true
        leaving = true
        leaveElapsed = 0.0
        cancelSoundPath?.let { sfx.play(it) }
        // `defaultTransOut`: the black band slides down over the menu, then
        // TitleState plays its own in wipe.
        fadeInWipe?.removeFromParent()
        fadeInWipe = null
        fadeOutWipe = SceneWipe(
            Constants.GAME_WIDTH.toDouble(),
            Constants.GAME_HEIGHT.toDouble(),
            reveal = false,
        ).also { sceneRoot.addChild(it) }
    }

    override suspend fun SContainer.sceneMain() {
        val knfInput = KnfInput(keys)
        addUpdater { dt ->
            val d = dt.secondsDouble()
            knfInput.update()

            for (item in items) item.sprite.update(d)
            updateFlicker(d)
            updateCamera(d)
            applyCamera()

            if (musicFadeInElapsed >= 0.0) {
                musicFadeInElapsed += d
                music.volume = min(1.0, musicFadeInElapsed / MUSIC_FADE_IN_SECONDS)
                if (musicFadeInElapsed >= MUSIC_FADE_IN_SECONDS) {
                    music.volume = 1.0
                    musicFadeInElapsed = -1.0
                }
            } else if (music.exists && music.volume < 0.8) {
                music.volume = min(1.0, music.volume + 0.5 * d)
            }

            if (fadeInWipe != null && fadeInElapsed < TRANSITION_FADE_IN) {
                fadeInElapsed += d
                val t = (fadeInElapsed / TRANSITION_FADE_IN).coerceIn(0.0, 1.0)
                fadeInWipe?.update(t)
                if (t >= 1.0) {
                    fadeInWipe?.removeFromParent()
                    fadeInWipe = null
                }
            }

            if (leaving) {
                leaveElapsed += d
                val t = (leaveElapsed / TRANSITION_FADE_OUT).coerceIn(0.0, 1.0)
                fadeOutWipe?.update(t)
                if (leaveElapsed >= TRANSITION_FADE_OUT) {
                    leaving = false
                    launch { sceneContainer.changeTo<TitleScene>() }
                    return@addUpdater
                }
            }

            // `UIStateMachine.canInteract` counts `EnteringMainMenu` as an
            // enabled state - "we want to be able to interact even during the
            // screen fade wipe thing" - so the menu takes input while the
            // incoming black is still fading.
            handleInputs(knfInput)
        }

        // Menu gestures on top of everything (tap = accept, drags = navigation).
        if (KnfTouch.enabled) addChild(TouchControls())

        // The entry wipe covers the touch overlay too.
        fadeInWipe?.let {
            it.removeFromParent()
            addChild(it)
        }
    }

    private class MenuItem(
        atlas: TextureAtlas,
        val idleFrames: List<TextureAtlas.AtlasFrame>,
        val selectedFrames: List<TextureAtlas.AtlasFrame>,
        val callback: () -> Unit,
        val fireInstantly: Boolean,
    ) {
        val sprite: SparrowSprite = SparrowSprite(
            atlas,
            idleFrames,
            fps = 24.0,
            loop = true,
            centered = true,
        )

        var worldY = 0.0

        fun idle() = sprite.play(idleFrames)
        fun select() = sprite.play(selectedFrames)
    }

    private companion object {
        /** `MainMenuState.rememberedSelectedIndex`. */
        private var rememberedSelectedIndex = 0

        const val FOLLOW_LERP = 0.06
        const val BG_SCROLL_Y = 0.17
        const val ITEM_SCROLL_Y = 0.4
        const val ITEM_FLICKER_INTERVAL = 0.06
        const val MAGENTA_FLICKER_INTERVAL = 0.15
        const val MUSIC_FADE_IN_SECONDS = 4.0
        const val TRANSITION_FADE_IN = 1.0
        /** `FlxTransitionableState.defaultTransOut`. */
        const val TRANSITION_FADE_OUT = 0.7
    }
}
