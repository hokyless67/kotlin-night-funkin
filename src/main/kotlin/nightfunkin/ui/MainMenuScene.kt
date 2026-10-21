package nightfunkin.ui

import korlibs.korge.scene.Scene
import korlibs.korge.view.Image
import korlibs.korge.view.SContainer
import korlibs.korge.view.addUpdater
import kotlinx.coroutines.launch
import nightfunkin.audio.MenuMusic
import nightfunkin.audio.SfxPlayer
import nightfunkin.core.Constants
import nightfunkin.core.KnfFiles
import nightfunkin.core.KnfInput
import nightfunkin.core.SparrowSprite
import nightfunkin.core.TextureAtlas
import java.awt.Desktop
import java.net.URI
import kotlin.math.min
import kotlin.math.pow

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

    private var magentaFlickerElapsed = 0.0
    private var magentaFlickerTogglesLeft = 0

    /** `freeplay.goBack` restarts freakyMenu from the top with a 4 s fade-in. */
    private var musicFadeInElapsed = -1.0

    override suspend fun SContainer.sceneInit() {
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
        // FNF opens Freeplay as a substate and leaves freakyMenu playing until
        // the first capsule selection pauses it. SongSelectScene owns that pause
        // and the preview, so the menu track must stay loaded here.
        launch { sceneContainer.changeTo<SongSelectScene>() }
    }

    private fun onScreenNotPorted(name: String) {
        println("KNF: $name is not ported yet")
        interacting = false
        busy = false
        selectedIndex = selectedIndex.coerceIn(0, (items.size - 1).coerceAtLeast(0))
    }

    private fun openMerch() {
        runCatching {
            Desktop.getDesktop().browse(URI("https://needlejuicerecords.com/en-ca/pages/friday-night-funkin"))
        }
        // `selectMerch` returns the menu to Idle immediately.
        interacting = false
    }

    private fun goBack() {
        rememberedSelectedIndex = selectedIndex
        exiting = true
        cancelSoundPath?.let { sfx.play(it) }
        launch { sceneContainer.changeTo<TitleScene>() }
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

            handleInputs(knfInput)
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
    }
}
