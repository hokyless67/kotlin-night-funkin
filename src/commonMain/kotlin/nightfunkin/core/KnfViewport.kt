package nightfunkin.core

import korlibs.event.ReshapeEvent
import korlibs.korge.view.SContainer
import korlibs.korge.view.Views
import korlibs.math.geom.Anchor
import korlibs.math.geom.ScaleMode
import korlibs.math.geom.Size
import nightfunkin.platform.KnfPlatform
import kotlin.math.ceil

/**
 * Fills the device screen instead of letterboxing the game to 16:9.
 *
 * This is FNF's `funkin.ui.FullScreenScaleMode`, reduced to what KNF needs:
 *
 *  - the *game area* (`Constants.GAME_WIDTH`/`GAME_HEIGHT`) is resized to the
 *    window's aspect ratio, keeping the short side at the reference 1280x720,
 *    so the world is never stretched and never cropped - the camera simply
 *    shows a little more of the stage, exactly like `FlxG.width` growing on a
 *    20:9 phone;
 *  - the aspect is capped at 20:9 (`FullScreenScaleMode.maxAspectRatio`), the
 *    same limit FNF uses, so an ultra-wide window still gets thin bars instead
 *    of an absurd field of view;
 *  - the korlibs scale mode stays `SHOW_ALL`, which now fits the window
 *    exactly (a no-op letterbox); the stage still clips to the virtual area,
 *    so screens wider than 20:9 get the same thin pillars FNF leaves;
 *  - the camera cutout is measured too (`Constants.GAME_NOTCH_WIDTH`), so the
 *    edge-pinned UI can step inward like FNF's `gameNotchSize` does - the
 *    game area itself still runs under the notch, as in the original.
 *
 * On desktop nothing changes: the virtual size stays 1280x720 and the window
 * keeps its 16:9 letterbox.
 *
 * `guardedSceneInit` re-applies this before every scene is built, so even a
 * boot where the window had not reported its size yet ends up with the right
 * game area.
 */
object KnfViewport {

    /** `FullScreenScaleMode.maxAspectRatio` (20:9). */
    const val MAX_ASPECT = 20.0 / 9.0

    /** The reference aspect FNF is authored at. */
    private const val BASE_ASPECT = Constants.BASE_WIDTH.toDouble() / Constants.BASE_HEIGHT

    /**
     * Game area for a [windowWidth] x [windowHeight] device.
     *
     * `FullScreenScaleMode.updateGameSize`: the ratio axis decides which side
     * grows. A wider-than-16:9 screen grows the width, a taller/narrower one
     * grows the height.
     */
    fun fit(windowWidth: Int, windowHeight: Int): Pair<Int, Int> {
        if (windowWidth <= 0 || windowHeight <= 0) {
            return Constants.BASE_WIDTH to Constants.BASE_HEIGHT
        }
        val aspect = windowWidth.toDouble() / windowHeight
        return if (aspect >= BASE_ASPECT) {
            // `FlxG.width = Math.ceil(gameWidth)`, capped at 20:9.
            val width = ceil(Constants.BASE_HEIGHT * aspect.coerceAtMost(MAX_ASPECT)).toInt()
            width to Constants.BASE_HEIGHT
        } else {
            // FNF only caps the axis that grows on ultra-wide screens; a
            // narrower one (a 4:3 tablet) grows the height without a limit.
            val height = ceil(Constants.BASE_WIDTH / aspect).toInt()
            Constants.BASE_WIDTH to height
        }
    }

    /** Applies the game area that fits the window's current size. */
    fun apply(views: Views): Boolean {
        // Before the first reshape the window has no size yet; leave the
        // authored 16:9 area alone instead of shrinking the game to 1280x720
        // on a device that is about to report 1600x720.
        if (views.actualWidth <= 0 || views.actualHeight <= 0) return false
        return apply(views, views.actualWidth, views.actualHeight)
    }

    /**
     * Applies a game area fitted to [windowWidth] x [windowHeight].
     *
     * Call once before the first scene is built and on every window resize /
     * orientation change; scenes created afterwards lay themselves out for the
     * new area. Returns true when the area actually changed.
     */
    fun apply(views: Views, windowWidth: Int, windowHeight: Int): Boolean {
        val (width, height) =
            if (KnfPlatform.isMobile) fit(windowWidth, windowHeight)
            else Constants.BASE_WIDTH to Constants.BASE_HEIGHT

        val notch = if (KnfPlatform.isMobile) {
            gameNotch(KnfPlatform.displayCutoutPx(views.gameWindow), windowWidth, windowHeight, width, height)
        } else 0

        val changed = width != Constants.GAME_WIDTH || height != Constants.GAME_HEIGHT ||
            notch != Constants.GAME_NOTCH_WIDTH
        Constants.applyViewport(width, height, notch)

        if (views.virtualWidth != width || views.virtualHeight != height) {
            views.scaleMode = ScaleMode.SHOW_ALL
            views.scaleAnchor = Anchor.CENTER
            views.virtualSizeDouble = Size(width.toDouble(), height.toDouble())
            views.resized()
        }
        return changed
    }

    /**
     * Converts the cutout width from device pixels to game units, like
     * `FullScreenScaleMode.updateDeviceNotch` (plus the `adjustGameSize` cap
     * correction).
     *
     * `gameNotchSize = notchSize / (logicalSize.x / initialWidth)`, where
     * `logicalSize.x` is the width the device would have at the 16:9 reference
     * aspect. When the aspect is capped at 20:9 the game no longer reaches the
     * screen edges; the black pillars are already outside the cutout, so their
     * width is subtracted first (FNF does the same with `sizeDifference`).
     */
    private fun gameNotch(notchPx: Int, windowWidth: Int, windowHeight: Int, width: Int, height: Int): Int {
        if (notchPx <= 0) return 0
        // Device pixels per game unit.
        val unitsPerPx = if (windowWidth.toDouble() / windowHeight >= BASE_ASPECT) {
            height.toDouble() / windowHeight
        } else {
            width.toDouble() / windowWidth
        }
        val coveredPx = width / unitsPerPx
        val exposed = notchPx - (windowWidth - coveredPx)
        if (exposed <= 0.0) return 0
        return ceil(exposed * unitsPerPx).toInt()
    }

    /**
     * Keeps the game area in sync with the OS window.
     *
     * Orientation is locked to landscape, so in practice this only matters for
     * the first real window size and for desktop window resizing; it is cheap
     * enough to leave registered.
     */
    fun install(views: Views) {
        // Give the platform a chance to make the window fill the display
        // (Android: draw under the camera cutout) before measuring it.
        KnfPlatform.prepareWindow(views.gameWindow)
        apply(views)
        // The window's own reshape event, not `views.resized()`: that one is
        // dispatched *by* `apply`, which would re-enter this handler.
        views.gameWindow.onEvent(ReshapeEvent) { event ->
            if (event.width > 0 && event.height > 0) apply(views, event.width, event.height)
        }
    }
}

/**
 * Re-fits the game area to the current window right before a scene lays itself
 * out. After boot this is a no-op; it only matters if the first frame arrived
 * before the window reported its size.
 */
fun SContainer.syncViewport() {
    stage?.views?.let { KnfViewport.apply(it) }
}
