package nightfunkin.platform

/**
 * Per-platform roots for KNF's two pieces of mutable state: the FNF asset
 * tree (1.2 GB, streamed off disk - never bundled) and the save file.
 *
 * Users drop the game's assets folder into a place the app can read:
 *  - Desktop: next to the game (or via the `KNF_ASSETS` env / `-Dknf.assets`).
 *  - Android: `Android/data/dev.hokyless.kotlinfunkin/files/assets/`.
 *  - iOS: the app's Documents folder (`KNF/assets/`, exposed through Finder
 *    file sharing).
 */
expect object KnfPlatform {
    /**
     * Candidate directories for the FNF asset root, most preferred first.
     * Paths may be relative to the process working directory on desktop.
     */
    fun assetRootCandidates(): List<String>

    /** A writable directory for `knf-save.json`. */
    fun userFilesDir(): String

    /**
     * Called when none of [assetRootCandidates] exist. Platforms may guide
     * the user (Android opens the system storage settings) or log detailed
     * instructions; implementations must not spam — guard themselves.
     */
    fun handleAssetsNotFound()

    /** True on the touch-first mobile targets (Android/iOS). */
    val isMobile: Boolean

    /**
     * Asks the runtime to collect garbage now.
     *
     * Called only at scene boundaries, right after the previous scene's bitmaps
     * and decoded audio were released, so the incoming scene decodes into the
     * freed memory instead of stacking another song's worth on top of it. A
     * hint on Android/JVM (and a no-op where the collector is automatic).
     */
    fun requestMemoryReclaim()

    /** One-line heap report for the on-device log (`KNF_MEM` lines). */
    fun memoryReport(): String

    /**
     * Remembers [value] through a weak reference (mobile memory diagnostics).
     *
     * Used to tell "the outgoing scene really is garbage, the collection is
     * just slow" apart from "something global still holds it": a scene that
     * survives a GC after `sceneAfterInit` is retained.
     */
    fun trackWeak(value: Any?)

    /** True while the value passed to [trackWeak] is still reachable. */
    fun trackedStillAlive(): Boolean

    /**
     * True only on Android. `PlayState.initNoteHitbox` nudges the touch
     * strumline down by 10 px there (and by -10 px for the pixel skin).
     */
    val isAndroid: Boolean

    /**
     * Called once, before the first scene is built, so the platform can put
     * the window into the layout mobile games use: on Android that means
     * extending under the display cutout on the screen's short edges
     * (`LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES`), which is what FNF's
     * Lime window does. [window] is the korlibs game window.
     */
    fun prepareWindow(window: Any?)

    /**
     * Width of the display cutout (notch / camera hole) in **device pixels**,
     * or 0 when there is none.
     *
     * [window] is the korlibs game window the viewport belongs to; Android
     * needs it to reach the hosting activity's decor view. `KnfViewport`
     * converts the result to game units (`Constants.GAME_NOTCH_WIDTH`) so
     * edge-pinned UI can keep clear of the cutout, like FNF's
     * `FullScreenScaleMode.gameNotchSize`.
     */
    fun displayCutoutPx(window: Any?): Int
}
