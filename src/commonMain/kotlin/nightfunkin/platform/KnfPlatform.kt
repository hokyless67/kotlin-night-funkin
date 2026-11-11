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
     * True only on Android. `PlayState.initNoteHitbox` nudges the touch
     * strumline down by 10 px there (and by -10 px for the pixel skin).
     */
    val isAndroid: Boolean
}
