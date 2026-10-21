package nightfunkin.audio

/**
 * The menu track is persistent in FNF 0.8.6: `freakyMenu` starts on the title
 * screen and keeps playing through `MainMenuState` (and back). Both states use
 * this shared player so switching scenes never restarts the song. Freeplay
 * pauses it while a song preview plays; closing Freeplay restarts it from the
 * top with a four-second fade-in.
 */
object MenuMusic {
    const val PATH_STEM = "preload/music/freakyMenu/freakyMenu"

    /** `freakyMenu-metadata.json` has `"looped": true`. */
    val player: MusicPlayer = MusicPlayer().apply { loop = true }
}
